package dev.vox.lss.compat;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.store.StoreCodec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * The band fill-on-switch column store (Xaero cave layers, fill-on-switch): the columns LSS
 * delivered in a CEILING dimension, kept on disk so that when Xaero switches to another band
 * (or Full) the bridge can re-extract that layer's tiles — LSS delivers a column once, and
 * only the layer Xaero shows is written on arrival.
 *
 * <p><b>Keying.</b> One store per (column-cache bucket, dimension): the files sit FLAT inside
 * the client column cache's bucket directory ({@code <dim>.xfill.<seq>}), so the cache's
 * own keying applies (address aliases, {@code .world-<hex>} sub-buckets) and every clear of a
 * bucket ({@code /lss clearcache}, {@code /lss reset}, the reset sweep) deletes the store with
 * it. The cache closes this store around each clear ({@link #suspendForClear}).
 *
 * <p><b>Format.</b> Append-only segments: a 24-byte header (magic, version, the registry
 * fingerprint) then records {@code [magic][bodyLen][crc32(body)][body]}, body =
 * {@code [packed pos][codec][rawLen][payload]}; the payload is the column's sections in the
 * client decode format (zstd when the native is there, else raw). A torn tail stops the scan;
 * a CRC failure on read drops that record. The latest record of a position wins (re-serves
 * overwrite). A TOMBSTONE (codec 2, rawLen 0, no payload) forgets a position for good: written
 * when a capture is dropped (the stored copy is then older than what LSS delivered) and when a
 * record will not read or decode, so a later session never re-indexes it (O4-F3). A fingerprint
 * mismatch (another mod set, another biome registry, another dimension height, another format
 * version) discards the store on a WRITE; a READ answers empty and never deletes anything
 * (O4-F8).
 *
 * <p><b>Segments.</b> A session's first write appends to the newest segment when its tail is
 * clean and it has room; a torn tail is never appended after (a new segment starts). New
 * segments take the number after every file the scan saw and are created exclusively, never
 * truncating one. Read handles open lazily and at most {@link #MAX_OPEN_READERS} stay open
 * (least recently used closes first), plus the segment being written (O4-F4).
 *
 * <p><b>Cap.</b> One total cap over every store under the cache root, LRU: other stores go
 * whole (oldest first), then the active store's oldest segment; a record read by a fill from
 * the older half of the segments is copied forward, so eviction is least-recently-USED at
 * segment granularity. The active store's index lives on the heap: ~100 bytes per stored
 * column, ~2.5% of its disk bytes.
 *
 * <p><b>Threads.</b> All file and index work runs on one daemon IO thread. Captures queue
 * with a bound (count and bytes) and DROP past it — the decode thread never blocks. Forgets
 * queue outside that bound, with their own.
 */
final class XaeroColumnStore {
    static final int SEGMENT_MAGIC = 0x4C584653; // "LXFS"
    static final int RECORD_MAGIC = 0x4C584652;  // "LXFR"
    static final int VERSION = 1;
    static final int HEADER_BYTES = 24;
    static final int RECORD_HEADER_BYTES = 12;
    static final byte CODEC_RAW = 0;
    static final byte CODEC_ZSTD = 1;
    /** A forgotten position: body {@code [pos][CODEC_TOMBSTONE][rawLen 0]}, no payload. */
    static final byte CODEC_TOMBSTONE = 2;
    static final int TOMBSTONE_BODY_BYTES = 13;
    static final long DEFAULT_CAP_BYTES = 256L << 20;
    static final int MAX_PENDING_CAPTURES = 1024;
    static final long MAX_PENDING_BYTES = 32L << 20;
    /** Forgets queued for the IO thread, and forgets held while a clear suspends the store. */
    static final int MAX_PENDING_FORGETS = 4096;
    /** Read handles kept open per store; the segment being written keeps its own. */
    static final int MAX_OPEN_READERS = 8;
    static final int MAX_CONSECUTIVE_FAILURES = 8;
    /** A bound on one cap pass (each round frees bytes, or marks a store it could not free). */
    static final int MAX_EVICTION_ROUNDS = 256;
    private static final Pattern SEGMENT_NAME = Pattern.compile("^(.+)\\.xfill\\.(\\d+)$");

    /** One stored column as read back: its position and its raw (decompressed) bytes. */
    record Column(long pos, byte[] raw) {}

    /** Where a record sits. */
    private record Loc(int segment, long offset, int length) {}

    private static final class Segment {
        final int seq;
        final Path path;
        /** Null while idle: read handles open lazily (the segment being written keeps one). */
        FileChannel channel;
        long size;
        /** Where the open's scan stopped: {@code size} for a clean tail (a session's first write
         *  may append), less for a torn one; -1 for a segment created this session. */
        long scannedEnd = -1;

        Segment(int seq, Path path) {
            this.seq = seq;
            this.path = path;
        }
    }

    /** A queued forget (see {@link #forget}). */
    private record Forget(String bucket, String dimKey, long fingerprint, long pos) {}

    /** The open store (IO thread only). */
    private final class Active {
        final String bucket;
        final String dimKey;
        final long fingerprint;
        final Path dir;
        final TreeMap<Integer, Segment> segments = new TreeMap<>();
        final LinkedHashMap<Long, Loc> index = new LinkedHashMap<>();
        /** Open read handles, least recently used first (never the segment being written). */
        final LinkedHashMap<Integer, Segment> readers = new LinkedHashMap<>(16, 0.75f, true);
        /** Segments a READ open could not parse: left alone, deleted by the first write. */
        final List<Path> unparsed = new ArrayList<>();
        /** The highest segment number in the directory, files the scan skipped included. */
        int maxSeq = -1;
        Segment current;
        long diskBytes;

        Active(String bucket, String dimKey, long fingerprint, Path dir) {
            this.bucket = bucket;
            this.dimKey = dimKey;
            this.fingerprint = fingerprint;
            this.dir = dir;
        }

        boolean is(String bucket, String dimKey, long fingerprint) {
            return this.bucket.equals(bucket) && this.dimKey.equals(dimKey) && this.fingerprint == fingerprint;
        }
    }

    private final Function<String, Path> bucketDir;
    private final Supplier<Path> root;
    private final StoreCodec codec;
    /** Settings-driven ({@link #setCapBytes}); the next capture trims to a lowered cap. */
    volatile long capBytes;
    volatile long segmentBytes;
    private final ExecutorService io;
    private Active active; // IO thread
    private long othersBytes; // IO thread: every other store under the root, last scanned

    final AtomicLong captured = new AtomicLong();
    final AtomicLong captureDrops = new AtomicLong();
    final AtomicLong evictions = new AtomicLong();
    final AtomicLong corrupt = new AtomicLong();
    final AtomicLong copiedForward = new AtomicLong();
    /** Tombstones written (positions forgotten for good). */
    final AtomicLong forgotten = new AtomicLong();
    final AtomicInteger pendingCaptures = new AtomicInteger();
    final AtomicInteger pendingForgets = new AtomicInteger();
    /** Forgets that met a suspended store (IO thread): replayed, in order, by the next IO task. */
    private final java.util.ArrayDeque<Forget> heldForgets = new java.util.ArrayDeque<>();
    /** Consecutive failed appends (IO thread); past {@link #MAX_CONSECUTIVE_FAILURES} the
     *  store stops capturing for the session (a full or read-only disk) with one warning. */
    private int consecutiveFailures;
    volatile boolean capturesDisabled;
    /** Test seam: the next N segment writes fail (a full disk, a locked file). */
    volatile int failWritesForTest;
    /** Test seam: the next N appends fail (after any segment write). */
    volatile int failAppendsForTest;
    /** Test seam over {@link #MAX_OPEN_READERS} (at least 1). */
    volatile int maxOpenReaders = MAX_OPEN_READERS;
    final AtomicLong pendingBytes = new AtomicLong();
    volatile int columnsGauge;
    volatile long bytesGauge;
    volatile boolean suspended;

    XaeroColumnStore(Function<String, Path> bucketDir, Supplier<Path> root, StoreCodec codec, long capBytes) {
        this.bucketDir = bucketDir;
        this.root = root;
        this.codec = codec;
        setCapBytes(capBytes);
        this.io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, dev.vox.lss.common.Brand.shortName() + "-XaeroColumnStore");
            t.setDaemon(true);
            return t;
        });
    }

    // ---- the capture path (decode thread) ----

    /** The outcome of one capture, reported once: stored, or dropped (any thread). */
    @FunctionalInterface
    interface CaptureOutcome {
        void done(boolean stored);
    }

    /** Queue one column for the store; false = dropped (queue full, suspended, disabled). */
    boolean capture(String bucket, String dimKey, long fingerprint, long pos, byte[] raw) {
        return capture(bucket, dimKey, fingerprint, pos, raw, null);
    }

    /** {@code outcome} hears once whether the column reached the store. A dropped capture also
     *  forgets the position's older stored copy ({@link #forget}). */
    boolean capture(String bucket, String dimKey, long fingerprint, long pos, byte[] raw, CaptureOutcome outcome) {
        if (this.suspended || this.capturesDisabled || raw.length > LSSConstants.MAX_SECTIONS_SIZE) {
            droppedAtTheDoor(bucket, dimKey, fingerprint, pos, outcome);
            return false;
        }
        // Each bound rolls back only what it added (O4-F2: the byte add short-circuited behind a
        // tripped count bound was still subtracted, so every count drop eroded the byte bound).
        if (this.pendingCaptures.incrementAndGet() > MAX_PENDING_CAPTURES) {
            this.pendingCaptures.decrementAndGet();
            droppedAtTheDoor(bucket, dimKey, fingerprint, pos, outcome);
            return false;
        }
        if (this.pendingBytes.addAndGet(raw.length) > MAX_PENDING_BYTES) {
            this.pendingBytes.addAndGet(-raw.length);
            this.pendingCaptures.decrementAndGet();
            droppedAtTheDoor(bucket, dimKey, fingerprint, pos, outcome);
            return false;
        }
        try {
            this.io.execute(() -> {
                boolean stored = false;
                try {
                    if (this.suspended || this.capturesDisabled) return;
                    replayHeldForgets();
                    var store = open(bucket, dimKey, fingerprint, true);
                    if (store == null) return;
                    append(store, pos, raw);
                    this.consecutiveFailures = 0;
                    stored = true;
                } catch (Throwable t) {
                    if (t instanceof VirtualMachineError vme) throw vme;
                    warn("capture", t);
                    if (++this.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES && !this.capturesDisabled) {
                        this.capturesDisabled = true;
                        LSSLogger.warn("Xaero column store: " + this.consecutiveFailures + " writes failed in a row"
                                + " (is the disk full or read-only?) — not keeping columns for the rest of this session");
                    }
                } finally {
                    this.pendingCaptures.decrementAndGet();
                    this.pendingBytes.addAndGet(-raw.length);
                    if (stored) {
                        if (outcome != null) outcome.done(true);
                    } else {
                        this.captureDrops.incrementAndGet();
                        if (outcome != null) outcome.done(false);
                        forgetOnIo(new Forget(bucket, dimKey, fingerprint, pos));
                    }
                }
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            this.pendingCaptures.decrementAndGet();
            this.pendingBytes.addAndGet(-raw.length);
            this.captureDrops.incrementAndGet();
            if (outcome != null) outcome.done(false);
            return false;
        }
    }

    private void droppedAtTheDoor(String bucket, String dimKey, long fingerprint, long pos, CaptureOutcome outcome) {
        this.captureDrops.incrementAndGet();
        if (outcome != null) outcome.done(false);
        forget(bucket, dimKey, fingerprint, pos);
    }

    /**
     * Forget {@code pos} for good (O4-F3): the stored copy is older than what LSS delivered (its
     * capture was dropped) or it will not decode. A tombstone record is appended, so a later
     * session never re-indexes the old record and fills it into another band. Queued outside the
     * capture bound, with its own; held while a clear suspends the store and replayed, in order,
     * by the next IO task. A store that refuses writes (captures disabled) forgets it in memory
     * only — a later session may see the old copy, the residual of a failing disk. Any thread.
     */
    void forget(String bucket, String dimKey, long fingerprint, long pos) {
        if (bucket == null || dimKey == null) return;
        if (this.pendingForgets.incrementAndGet() > MAX_PENDING_FORGETS) {
            this.pendingForgets.decrementAndGet();
            return;
        }
        var forget = new Forget(bucket, dimKey, fingerprint, pos);
        try {
            this.io.execute(() -> {
                this.pendingForgets.decrementAndGet();
                forgetOnIo(forget);
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            this.pendingForgets.decrementAndGet();
        }
    }

    /** IO thread: forget now, or hold it while a clear suspends the store. */
    private void forgetOnIo(Forget forget) {
        if (this.suspended) {
            if (this.heldForgets.size() < MAX_PENDING_FORGETS) this.heldForgets.add(forget);
            return;
        }
        replayHeldForgets();
        forgetNow(forget);
    }

    /** IO thread, first thing in every task that may touch a store: forgets held across a
     *  clear go before any later capture or read. */
    private void replayHeldForgets() {
        while (!this.suspended && !this.heldForgets.isEmpty()) {
            forgetNow(this.heldForgets.poll());
        }
    }

    private void forgetNow(Forget f) {
        var store = this.active != null && this.active.is(f.bucket(), f.dimKey(), f.fingerprint()) ? this.active
                : open(f.bucket(), f.dimKey(), f.fingerprint(), false);
        if (store != null && store.index.containsKey(f.pos())) tombstone(store, f.pos());
    }

    /** Session end: a disabled store tries again next session. */
    void resetSession() {
        this.capturesDisabled = false;
        try {
            this.io.execute(() -> this.consecutiveFailures = 0);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // shut down
        }
    }

    // ---- the fill path (any thread → IO thread) ----

    /** Stored positions within {@code radius} chunks of the centre — a Chebyshev SQUARE, the
     *  shape LSS serves — nearest (Euclidean) first. */
    CompletableFuture<long[]> positionsNear(String bucket, String dimKey, long fingerprint,
                                            int centerX, int centerZ, int radius) {
        return CompletableFuture.supplyAsync(() -> {
            replayHeldForgets();
            var store = this.suspended ? null : open(bucket, dimKey, fingerprint, false);
            if (store == null) return new long[0];
            var near = new ArrayList<long[]>();
            for (long pos : store.index.keySet()) {
                long dx = (int) (pos >> 32) - centerX;
                long dz = (int) pos - centerZ;
                if (Math.abs(dx) > radius || Math.abs(dz) > radius) continue;
                near.add(new long[]{dx * dx + dz * dz, pos});
            }
            near.sort((a, b) -> Long.compare(a[0], b[0]));
            long[] out = new long[near.size()];
            for (int i = 0; i < out.length; i++) out[i] = near.get(i)[1];
            return out;
        }, this.io);
    }

    /** Reads (and decompresses) the stored columns of {@code positions}; absent or corrupt
     *  ones are left out. A record from the older half of the segments is copied forward. */
    CompletableFuture<List<Column>> read(String bucket, String dimKey, long fingerprint, long[] positions) {
        return CompletableFuture.supplyAsync(() -> {
            var out = new ArrayList<Column>(positions.length);
            replayHeldForgets();
            var store = this.suspended ? null : open(bucket, dimKey, fingerprint, false);
            if (store == null) return out;
            for (long pos : positions) {
                var loc = store.index.get(pos);
                if (loc == null) continue;
                byte[] record = readRecord(store, pos, loc);
                if (record == null) continue;
                byte[] raw = payload(record);
                if (raw == null) {
                    this.corrupt.incrementAndGet();
                    tombstone(store, pos); // counted once: no later session re-decodes it
                    continue;
                }
                out.add(new Column(pos, raw));
                maybeCopyForward(store, pos, loc, record);
            }
            gauges(store);
            return out;
        }, this.io);
    }

    /** A stored column that would not decode or extract: forget it for good (counted as
     *  corrupt once — a tombstone keeps later sessions from re-decoding and re-counting it). */
    void dropCorrupt(String bucket, String dimKey, long fingerprint, long pos) {
        this.corrupt.incrementAndGet();
        forget(bucket, dimKey, fingerprint, pos);
    }

    // ---- lifecycle ----

    /** A new disk cap ({@code integrations.map_ingestion.nether_store_mib} changed). */
    void setCapBytes(long capBytes) {
        long cap = Math.max(1L << 20, capBytes);
        this.segmentBytes = Math.max(64L << 10, Math.min(8L << 20, cap / 8));
        this.capBytes = cap;
    }

    /** The store setting went to 0: close the active store and delete every store file under
     *  the cache root (other buckets included), on the IO thread. */
    void purgeAll() {
        try {
            this.io.execute(() -> {
                closeActiveNow();
                Path rootDir = this.root.get();
                if (rootDir == null || !Files.isDirectory(rootDir, LinkOption.NOFOLLOW_LINKS)) return;
                try (DirectoryStream<Path> buckets = Files.newDirectoryStream(rootDir)) {
                    for (Path bucket : buckets) {
                        if (!Files.isDirectory(bucket, LinkOption.NOFOLLOW_LINKS)) continue;
                        try (DirectoryStream<Path> files = Files.newDirectoryStream(bucket)) {
                            for (Path file : files) {
                                if (SEGMENT_NAME.matcher(file.getFileName().toString()).matches()
                                        && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                                    Files.deleteIfExists(file);
                                }
                            }
                        } catch (IOException e) {
                            warn("purge a bucket", e);
                        }
                    }
                } catch (IOException e) {
                    warn("purge the cache root", e);
                }
                this.othersBytes = 0;
                this.heldForgets.clear(); // nothing left to forget
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // shut down
        }
    }

    /** Close the active store (session end, a dimension or world change); files stay. */
    void closeActive() {
        try {
            this.io.execute(this::closeActiveNow);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // shut down
        }
    }

    /** Before a column-cache clear: stop capturing, close every file, wait (bounded). */
    void suspendForClear() {
        this.suspended = true;
        awaitIo(this::closeActiveNow);
    }

    /** After the clear: the next capture reopens (and rescans) whatever is left. */
    void resumeAfterClear() {
        this.suspended = false;
    }

    /** Wait for every queued task (tests, harness shutdown); held forgets go too unless a
     *  clear still suspends the store. */
    void flush() {
        awaitIo(this::replayHeldForgets);
    }

    private void awaitIo(Runnable task) {
        try {
            this.io.submit(task).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LSSLogger.warn("Xaero column store: IO wait failed or timed out", e);
        }
    }

    // ---- IO thread internals ----

    /** The store for (bucket, dimension, fingerprint), opened if need be. A READ never
     *  discards a store written with another fingerprint (it answers empty); only a capture —
     *  which carries the client's current registries — replaces it. */
    private Active open(String bucket, String dimKey, long fingerprint, boolean forWrite) {
        if (bucket == null || dimKey == null) return null;
        if (this.active != null && this.active.is(bucket, dimKey, fingerprint)) return this.active;
        if (!forWrite && this.active != null && this.active.bucket.equals(bucket) && this.active.dimKey.equals(dimKey)) {
            return null; // another fingerprint is open: never thrown away by a read
        }
        Path dir = this.bucketDir.apply(bucket);
        var store = new Active(bucket, dimKey, fingerprint, dir);
        try {
            if (!scan(store, forWrite)) {
                for (var seg : store.segments.values()) closeQuietly(seg);
                return null; // a read met another fingerprint: leave the files alone
            }
        } catch (IOException e) {
            warn("open", e);
        }
        closeActiveNow();
        this.active = store;
        this.othersBytes = scanOthers(store);
        enforceCap(store);
        gauges(store);
        return store;
    }

    /** @return false when a read-only scan met another fingerprint or another format version
     *  (nothing was changed). A READ never deletes a file (O4-F8); a write discards what it cannot
     *  use. Segments are scanned and closed: read handles reopen lazily (O4-F4). */
    private boolean scan(Active store, boolean discardMismatch) throws IOException {
        if (!Files.isDirectory(store.dir)) return true;
        var found = new TreeMap<Integer, Path>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(store.dir)) {
            for (Path file : files) {
                var m = SEGMENT_NAME.matcher(file.getFileName().toString());
                if (!m.matches() || !m.group(1).equals(store.dimKey)) continue;
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue; // never ours
                try {
                    found.put(Integer.parseInt(m.group(2)), file);
                } catch (NumberFormatException e) {
                    if (discardMismatch) Files.deleteIfExists(file);
                }
            }
        }
        if (!found.isEmpty()) store.maxSeq = found.lastKey(); // never reuse a number (rotate)
        boolean mismatch = false;
        for (var e : found.entrySet()) {
            var seg = new Segment(e.getKey(), e.getValue());
            if (mismatch) {
                Files.deleteIfExists(seg.path);
                continue;
            }
            try {
                seg.channel = FileChannel.open(seg.path, StandardOpenOption.READ);
                seg.size = seg.channel.size();
            } catch (IOException openFailed) {
                closeQuietly(seg);
                warn("open a segment", openFailed);
                continue;
            }
            var header = ByteBuffer.allocate(HEADER_BYTES);
            boolean ours = seg.size >= HEADER_BYTES && readFully(seg.channel, header, 0)
                    && header.getInt(0) == SEGMENT_MAGIC;
            boolean ok = ours && header.getInt(4) == VERSION;
            if (ours && (!ok || header.getLong(8) != store.fingerprint)) {
                if (!discardMismatch) {
                    closeQuietly(seg);
                    return false; // another build's or another registry's store: a read leaves it
                }
                // Another mod set / registry / height / format: every segment of this store is stale.
                mismatch = true;
                LSSLogger.info("Xaero column store for " + store.dimKey
                        + " was written with other registries — discarding it");
                closeQuietly(seg);
                for (var s : store.segments.values()) closeAndDelete(s);
                store.segments.clear();
                store.index.clear();
                store.diskBytes = 0;
                Files.deleteIfExists(seg.path);
                continue;
            }
            if (!ok) {
                // A short or garbled header: a write deletes it, a read leaves it alone.
                closeQuietly(seg);
                if (discardMismatch) {
                    Files.deleteIfExists(seg.path);
                } else {
                    store.unparsed.add(seg.path);
                }
                continue;
            }
            scanRecords(store, seg);
            closeQuietly(seg);
            store.segments.put(seg.seq, seg);
            store.diskBytes += seg.size;
        }
        return true;
    }

    private static void closeQuietly(Segment seg) {
        try {
            if (seg.channel != null) seg.channel.close();
        } catch (IOException ignored) {
            // closing
        }
        seg.channel = null;
    }

    private void scanRecords(Active store, Segment seg) throws IOException {
        long offset = HEADER_BYTES;
        var head = ByteBuffer.allocate(RECORD_HEADER_BYTES + 9); // through the codec byte
        while (offset + RECORD_HEADER_BYTES + 9 <= seg.size) {
            head.clear();
            if (!readFully(seg.channel, head, offset)) break;
            int magic = head.getInt(0);
            int bodyLen = head.getInt(4);
            if (magic != RECORD_MAGIC || bodyLen < 13 || bodyLen > LSSConstants.MAX_SECTIONS_SIZE + 13
                    || offset + RECORD_HEADER_BYTES + bodyLen > seg.size) {
                break; // a torn or garbled tail: everything before it stands
            }
            long pos = head.getLong(RECORD_HEADER_BYTES);
            store.index.remove(pos); // re-insert: scan order is recency order
            boolean tombstone = bodyLen == TOMBSTONE_BODY_BYTES
                    && head.get(RECORD_HEADER_BYTES + 8) == CODEC_TOMBSTONE;
            if (!tombstone) store.index.put(pos, new Loc(seg.seq, offset, RECORD_HEADER_BYTES + bodyLen));
            offset += RECORD_HEADER_BYTES + bodyLen;
        }
        seg.scannedEnd = offset; // == size: a clean tail a later write may append to
    }

    private void append(Active store, long pos, byte[] raw) throws IOException {
        byte codecId = CODEC_RAW;
        byte[] payload = raw;
        if (this.codec != null) {
            try {
                payload = this.codec.compress(raw);
                codecId = CODEC_ZSTD;
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError vme) throw vme;
                payload = raw; // degrade to raw for this record
            }
        }
        if (this.failAppendsForTest > 0) {
            this.failAppendsForTest--;
            throw new IOException("injected append failure");
        }
        writeRecord(store, pos, record(pos, codecId, raw.length, payload), true);
        this.captured.incrementAndGet();
    }

    private static ByteBuffer record(long pos, byte codecId, int rawLen, byte[] payload) {
        int bodyLen = 8 + 1 + 4 + payload.length;
        var buf = ByteBuffer.allocate(RECORD_HEADER_BYTES + bodyLen);
        buf.putInt(RECORD_MAGIC).putInt(bodyLen).putInt(0);
        buf.putLong(pos).put(codecId).putInt(rawLen).put(payload);
        var crc = new CRC32();
        crc.update(buf.array(), RECORD_HEADER_BYTES, bodyLen);
        buf.putInt(8, (int) crc.getValue());
        return buf.flip();
    }

    /** Forget {@code pos}: out of the index at once, and a tombstone on disk unless the store
     *  refuses writes (best effort — a failed write is warned, never fatal). IO thread. */
    private void tombstone(Active store, long pos) {
        store.index.remove(pos);
        if (!this.capturesDisabled) {
            try {
                writeRecord(store, pos, record(pos, CODEC_TOMBSTONE, 0, new byte[0]), false);
                this.forgotten.incrementAndGet();
            } catch (IOException | RuntimeException e) {
                warn("forget a column", e);
            }
        }
        gauges(store);
    }

    /** {@code indexed} false = a tombstone: the position leaves the index. */
    private void writeRecord(Active store, long pos, ByteBuffer record, boolean indexed) throws IOException {
        int length = record.remaining();
        deleteUnparsed(store);
        var seg = store.current;
        if (seg == null) {
            seg = resumeOrRotate(store, length);
        } else if (seg.size + length > this.segmentBytes) {
            seg = rotate(store);
        }
        long offset = seg.size;
        while (record.hasRemaining()) seg.channel.write(record, offset + (length - record.remaining()));
        seg.size += length;
        store.diskBytes += length;
        store.index.remove(pos);
        if (indexed) store.index.put(pos, new Loc(seg.seq, offset, length));
        enforceCap(store);
        gauges(store);
    }

    /** A write discards the segments a read-only open left alone (their header was unusable). */
    private void deleteUnparsed(Active store) {
        if (store.unparsed.isEmpty()) return;
        for (Path path : store.unparsed) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                warn("delete an unreadable segment", e);
            }
        }
        store.unparsed.clear();
    }

    /** The session's first write appends to the newest segment when its tail is clean, it has
     *  room and nothing changed it since the scan (O4-F4: a segment per session, each holding a
     *  handle, grew without bound under the cap); a torn tail is never appended after. */
    private Segment resumeOrRotate(Active store, int length) throws IOException {
        var last = store.segments.isEmpty() ? null : store.segments.lastEntry().getValue();
        if (last != null && last.scannedEnd == last.size && last.size + length <= this.segmentBytes) {
            closeReader(store, last);
            FileChannel channel = null;
            try {
                channel = FileChannel.open(last.path, StandardOpenOption.READ, StandardOpenOption.WRITE);
                if (channel.size() == last.size) {
                    last.channel = channel;
                    store.current = last;
                    return last;
                }
            } catch (IOException e) {
                warn("reopen a segment", e);
            }
            if (channel != null) channel.close();
        }
        return rotate(store);
    }

    private Segment rotate(Active store) throws IOException {
        var previous = store.current;
        int seq = Math.max(store.maxSeq, store.segments.isEmpty() ? -1 : store.segments.lastKey()) + 1;
        Files.createDirectories(store.dir);
        Segment seg;
        for (int attempt = 0; ; attempt++) {
            var candidate = new Segment(seq, store.dir.resolve(store.dimKey + ".xfill." + seq));
            try {
                // Exclusive: a rotation never truncates a file, even one the scan could not open.
                candidate.channel = FileChannel.open(candidate.path, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.READ, StandardOpenOption.WRITE);
                seg = candidate;
                break;
            } catch (java.nio.file.FileAlreadyExistsException taken) {
                if (attempt >= 16) throw taken;
                seq++;
            }
        }
        store.maxSeq = seq;
        try {
            if (this.failWritesForTest > 0) {
                this.failWritesForTest--;
                throw new IOException("injected write failure");
            }
            var header = ByteBuffer.allocate(HEADER_BYTES);
            header.putInt(SEGMENT_MAGIC).putInt(VERSION).putLong(store.fingerprint).putLong(0L).flip();
            while (header.hasRemaining()) seg.channel.write(header, HEADER_BYTES - header.remaining());
        } catch (IOException | RuntimeException e) {
            // A half-made segment must not keep its handle (on Windows an open file blocks the
            // cache clear from deleting the bucket) nor stay on disk headerless.
            closeAndDelete(seg);
            throw e;
        }
        seg.size = HEADER_BYTES;
        store.diskBytes += HEADER_BYTES;
        store.segments.put(seq, seg);
        store.current = seg;
        if (previous != null && previous.channel != null) {
            // The full segment keeps its handle as a reader (fills read recent data first).
            store.readers.put(previous.seq, previous);
            trimReaders(store);
        }
        return seg;
    }

    /** A read handle for {@code seg}, opened lazily; at most {@link #maxOpenReaders} stay open
     *  (least recently used closes first). The segment being written uses its own. */
    private FileChannel readChannel(Active store, Segment seg) throws IOException {
        if (seg == store.current) return seg.channel;
        if (seg.channel != null) {
            store.readers.get(seg.seq); // touch: most recently used
            return seg.channel;
        }
        seg.channel = FileChannel.open(seg.path, StandardOpenOption.READ);
        store.readers.put(seg.seq, seg);
        trimReaders(store);
        return seg.channel;
    }

    private void trimReaders(Active store) {
        int cap = Math.max(1, this.maxOpenReaders);
        var it = store.readers.values().iterator();
        while (store.readers.size() > cap && it.hasNext()) {
            var eldest = it.next();
            it.remove();
            if (eldest != store.current) closeQuietly(eldest);
        }
    }

    private static void closeReader(Active store, Segment seg) {
        store.readers.remove(seg.seq);
        closeQuietly(seg);
    }

    /** Over the total cap: other stores go first (whole, least recently written first), then
     *  the active store's oldest segment (never the one being written while another exists).
     *  Progress is bytes actually freed: a store whose files will not delete (read-only, locked,
     *  not a file) is skipped for the pass, and the pass is bounded. */
    private void enforceCap(Active store) {
        var stuck = new java.util.HashSet<String>();
        for (int round = 0; round < MAX_EVICTION_ROUNDS
                && store.diskBytes + this.othersBytes > this.capBytes; round++) {
            if (this.othersBytes > 0) {
                long freed = evictOldestOther(store, stuck);
                if (freed >= 0) continue; // freed some, or marked one stuck: try again
            }
            var oldest = store.segments.firstEntry();
            if (oldest == null) return;
            if (oldest.getValue() == store.current) {
                if (store.segments.size() == 1) {
                    store.current = null; // the next record opens a fresh segment
                } else {
                    return;
                }
            }
            evictSegment(store, oldest.getValue());
        }
    }

    private void evictSegment(Active store, Segment seg) {
        int dropped = 0;
        var it = store.index.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().segment() == seg.seq) {
                it.remove();
                dropped++;
            }
        }
        this.evictions.addAndGet(dropped);
        store.segments.remove(seg.seq);
        store.readers.remove(seg.seq);
        store.diskBytes -= seg.size;
        closeAndDelete(seg);
    }

    /** Every OTHER store's segments under the cache root (one level of bucket dirs). */
    private long scanOthers(Active store) {
        long total = 0;
        for (var other : listOthers(store).values()) total += other.bytes;
        return total;
    }

    private record OtherStore(List<Path> files, long bytes, long newestMillis) {}

    private Map<String, OtherStore> listOthers(Active store) {
        var out = new HashMap<String, OtherStore>();
        Path rootDir = this.root.get();
        if (rootDir == null || !Files.isDirectory(rootDir, LinkOption.NOFOLLOW_LINKS)) return out;
        Object activeKey = identity(store.dir);
        try (DirectoryStream<Path> buckets = Files.newDirectoryStream(rootDir)) {
            for (Path bucket : buckets) {
                if (!Files.isDirectory(bucket, LinkOption.NOFOLLOW_LINKS)) continue;
                // The same directory under another spelling (a case-insensitive filesystem, a
                // link) is the ACTIVE bucket, never "another store" to evict.
                boolean activeBucket = bucket.equals(store.dir) || activeKey != null && activeKey.equals(identity(bucket));
                try (DirectoryStream<Path> files = Files.newDirectoryStream(bucket)) {
                    for (Path file : files) {
                        var m = SEGMENT_NAME.matcher(file.getFileName().toString());
                        if (!m.matches() || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                        if (activeBucket && m.group(1).equalsIgnoreCase(store.dimKey)) continue;
                        String key = bucket.getFileName() + "/" + m.group(1);
                        long size = Files.size(file);
                        long mtime = Files.getLastModifiedTime(file).toMillis();
                        var prev = out.get(key);
                        var files2 = prev == null ? new ArrayList<Path>() : new ArrayList<>(prev.files());
                        files2.add(file);
                        out.put(key, new OtherStore(files2, (prev == null ? 0 : prev.bytes()) + size,
                                Math.max(prev == null ? Long.MIN_VALUE : prev.newestMillis(), mtime)));
                    }
                } catch (IOException e) {
                    // an unreadable bucket counts as empty
                }
            }
        } catch (IOException e) {
            warn("scan the cache root", e);
        }
        return out;
    }

    /** A directory's identity: its file key, else its real path; null when it is absent. */
    private static Object identity(Path dir) {
        try {
            Object key = Files.readAttributes(dir, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
            return key != null ? key : dir.toRealPath();
        } catch (IOException e) {
            return null;
        }
    }

    /** Delete the least recently written other store. @return bytes freed; 0 when nothing
     *  would delete (the store is then skipped for this pass); -1 when no store is left. */
    private long evictOldestOther(Active store, java.util.Set<String> stuck) {
        var others = listOthers(store);
        String oldestKey = null;
        OtherStore oldest = null;
        for (var e : others.entrySet()) {
            if (stuck.contains(e.getKey())) continue;
            if (oldest == null || e.getValue().newestMillis() < oldest.newestMillis()) {
                oldest = e.getValue();
                oldestKey = e.getKey();
            }
        }
        if (oldest == null) {
            this.othersBytes = 0; // only stuck stores remain: the active store must make room
            return -1;
        }
        long freed = 0;
        for (Path file : oldest.files()) {
            try {
                long size = Files.size(file);
                if (Files.deleteIfExists(file)) freed += size;
            } catch (IOException e) {
                warn("evict another store", e);
            }
        }
        if (freed == 0) stuck.add(oldestKey);
        long left = 0;
        for (var e : listOthers(store).entrySet()) if (!stuck.contains(e.getKey())) left += e.getValue().bytes();
        this.othersBytes = left;
        return freed;
    }

    /** The record at {@code loc}, CRC-checked; null when it is gone or corrupt (a corrupt one is
     *  forgotten for good: counted once, never re-read by a later session). */
    private byte[] readRecord(Active store, long pos, Loc loc) {
        var seg = store.segments.get(loc.segment());
        if (seg == null) {
            store.index.remove(pos);
            return null;
        }
        try {
            var channel = readChannel(store, seg);
            var buf = ByteBuffer.allocate(loc.length());
            if (!readFully(channel, buf, loc.offset())) throw new IOException("short read");
            byte[] record = buf.array();
            var crc = new CRC32();
            crc.update(record, RECORD_HEADER_BYTES, record.length - RECORD_HEADER_BYTES);
            if (buf.getInt(0) != RECORD_MAGIC || buf.getInt(8) != (int) crc.getValue()
                    || buf.getLong(RECORD_HEADER_BYTES) != pos) {
                this.corrupt.incrementAndGet();
                tombstone(store, pos);
                return null;
            }
            return record;
        } catch (IOException e) {
            this.corrupt.incrementAndGet();
            tombstone(store, pos);
            return null;
        }
    }

    /** The record's column bytes, decompressed; null when they cannot be. */
    private byte[] payload(byte[] record) {
        var buf = ByteBuffer.wrap(record);
        byte codecId = buf.get(RECORD_HEADER_BYTES + 8);
        int rawLen = buf.getInt(RECORD_HEADER_BYTES + 9);
        int start = RECORD_HEADER_BYTES + 13;
        byte[] payload = java.util.Arrays.copyOfRange(record, start, record.length);
        if (rawLen <= 0 || rawLen > LSSConstants.MAX_SECTIONS_SIZE) return null;
        if (codecId == CODEC_RAW) return payload.length == rawLen ? payload : null;
        if (codecId != CODEC_ZSTD || this.codec == null) return null;
        try {
            byte[] raw = this.codec.decompress(payload, rawLen);
            return raw.length == rawLen ? raw : null;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            return null;
        }
    }

    /** LRU at segment granularity: near the cap, a record read from the older half of the
     *  segments moves to the newest (below 3/4 of the cap nothing is evicted, so nothing moves). */
    private void maybeCopyForward(Active store, long pos, Loc loc, byte[] record) {
        if (store.segments.size() < 2 || store.diskBytes + this.othersBytes < this.capBytes / 4 * 3) return;
        int oldest = store.segments.firstKey();
        int newest = store.segments.lastKey();
        if (loc.segment() > oldest + (newest - oldest) / 2) return;
        try {
            writeRecord(store, pos, ByteBuffer.wrap(record), true);
            this.copiedForward.incrementAndGet();
        } catch (IOException e) {
            warn("copy forward", e);
        }
    }

    private void closeActiveNow() {
        var store = this.active;
        this.active = null;
        if (store == null) return;
        for (var seg : store.segments.values()) {
            try {
                if (seg.channel != null) seg.channel.close();
            } catch (IOException ignored) {
                // closing
            }
            seg.channel = null;
        }
        store.readers.clear();
        this.columnsGauge = 0;
        this.bytesGauge = 0;
    }

    private void closeAndDelete(Segment seg) {
        try {
            if (seg.channel != null) seg.channel.close();
        } catch (IOException ignored) {
            // deleting anyway
        }
        seg.channel = null;
        try {
            Files.deleteIfExists(seg.path);
        } catch (IOException e) {
            warn("delete a segment", e);
        }
    }

    private void gauges(Active store) {
        this.columnsGauge = store.index.size();
        this.bytesGauge = store.diskBytes;
    }

    private static boolean readFully(FileChannel channel, ByteBuffer buf, long position) throws IOException {
        long at = position;
        while (buf.hasRemaining()) {
            int n = channel.read(buf, at);
            if (n < 0) return false;
            at += n;
        }
        return true;
    }

    private static final dev.vox.lss.common.LogThrottle WARN = new dev.vox.lss.common.LogThrottle(60_000);

    private static void warn(String what, Throwable t) {
        long n = WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
        if (n > 0) LSSLogger.warn("Xaero column store: failed to " + what + " (" + n + " since the last warning)", t);
    }

    /** Test seam: every OTHER store's bytes as a fresh scan of the cache root counts them. */
    long othersBytesForTest() {
        try {
            return CompletableFuture.supplyAsync(() -> this.active == null ? -1L : scanOthers(this.active), this.io)
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Test seam: how many of the active store's segments hold an open file handle. */
    int openChannelsForTest() {
        try {
            return CompletableFuture.supplyAsync(() -> {
                int open = 0;
                if (this.active != null) for (var s : this.active.segments.values()) if (s.channel != null) open++;
                return open;
            }, this.io).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Test seam: park the IO thread until {@code release} opens (a slow or stalled disk). */
    void stallIoForTest(java.util.concurrent.CountDownLatch release) {
        this.io.execute(() -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Test seam: the active store's segment files (IO thread snapshot). */
    List<Path> segmentsForTest() {
        try {
            return CompletableFuture.supplyAsync(() -> {
                var out = new ArrayList<Path>();
                if (this.active != null) for (var s : this.active.segments.values()) out.add(s.path);
                return out;
            }, this.io).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
