package dev.vox.lss.compat;

import dev.vox.lss.compat.XaeroSession.Origin;
import dev.vox.lss.compat.XaeroSession.Outcome;
import dev.vox.lss.compat.XaeroSession.RegionKey;
import dev.vox.lss.compat.XaeroSession.TileKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase B-II's best-effort band pipeline (cave-layers plan §B6): the bands of a ceiling
 * dimension that Xaero does NOT show, extracted for columns within the radius bound, written
 * without any of the reliable path's guarantees — and without touching its resources:
 * <ul>
 *   <li>its OWN queue with count and byte caps, outside the occupancy figure and the
 *       backpressure report; overflow, expiry and stale entries drop oldest-first and are
 *       never kept, owed or reported (tiles hold lease-free tokens);</li>
 *   <li>its OWN window share of open regions, which never sets the reliable window's pacing
 *       hold; a region leaves it when Xaero parks or removes it, when a grant's reservation
 *       expires, or once it has gone quiet (Xaero's own region limiter governs residency from
 *       there — regions of layers nobody renders are never parked by a frame);</li>
 *   <li>its OWN owed-rebuild debt ({@link XaeroRebuildScheduler#bestEffortUpdates}) with its
 *       own small cap, rebuilt only when no reliable rebuild is due — best-effort debt never
 *       counts against the reliable hard cap, so it can never DEFER a reliable commit;</li>
 *   <li>strict priority: while the last reliable drain retained anything (deferred, awaiting
 *       a load, window-held, capped or budget-cut), a reliable region waits on a load, or
 *       the reliable rebuild debt is past its soft cap, nothing here commits or grants —
 *       only a just-loaded region's first batch, bounded (spike round 1: B-II's extra
 *       non-current-layer saves and recaches starved the reliable SURFACE layer of the
 *       Nether, itself a non-current layer, for minutes);</li>
 *   <li>only the pump budget the reliable drain left, under its own commit cap;</li>
 *   <li>(layer, region) BATCHES — at least {@link #batchTiles} tiles or the oldest at least
 *       {@link #batchMillis} old — for loaded regions too: every commit burst into a layer
 *       nobody renders costs Xaero a 600 ms save and a recache, so tiles never trickle in;</li>
 *   <li>load grants only when the reliable path is clear and Xaero's save queue is empty,
 *       requested WITHOUT priority (appended behind the reliable path's and Xaero's own
 *       front-inserted requests), and a granted batch starts committing in the SAME pump
 *       that first sees its region at loadState 2, ahead of the 600 ms non-current-layer
 *       save and the {@code countChunks()==0} removal that would follow an empty first
 *       save (§B0); a region with queued tiles stays visited so it is not parked;</li>
 *   <li>no extraction for a column while the queue is full (refused, counted).</li>
 * </ul>
 * Queue under {@link #lock} (decode offers, main drain, any-thread retirement); everything
 * else main thread (the pump ladder, inside Xaero's render-pause monitor).
 */
final class XaeroBestEffortPipeline {
    private final XaeroSession session;

    XaeroBestEffortPipeline(XaeroSession session) {
        this.session = session;
    }

    // ---- tunables (production: the system properties below; a bare session starts OFF) ----

    /** {@code -Dlss.xaero.bestEffortBands} (production default true; false = the B-I arm). */
    volatile boolean enabled;
    /** K: {@code -Dlss.xaero.bestEffort.batchTiles}. */
    int batchTiles = DEFAULT_BATCH_TILES;
    /** T: {@code -Dlss.xaero.bestEffort.batchMillis}. */
    long batchMillis = DEFAULT_BATCH_MILLIS;
    /** The radius cap, chunks; the bound is min(this, the server's LOD distance):
     *  {@code -Dlss.xaero.bestEffort.radiusChunks}. Read on the decode thread. */
    volatile int radiusChunks = DEFAULT_RADIUS_CHUNKS;
    /** {@code -Dlss.xaero.bestEffort.maxQueue} / {@code .maxQueueMB}. */
    int maxQueue = DEFAULT_MAX_QUEUE;
    long maxQueueBytes = DEFAULT_MAX_QUEUE_BYTES;
    /** An entry older than this drops ({@code -Dlss.xaero.bestEffort.ttlMillis}). */
    long ttlMillis = DEFAULT_TTL_MILLIS;
    /** The window share; -1 = a quarter of the open-region window, 2..16
     *  ({@code -Dlss.xaero.bestEffort.window}). */
    int windowOverride = -1;
    /** {@code -Dlss.xaero.bestEffort.commitsPerPump}. */
    int commitsPerPump = DEFAULT_COMMITS_PER_PUMP;
    /** Best-effort loads in flight at once ({@code -Dlss.xaero.bestEffort.maxLoads}). */
    int maxOutstandingLoads = DEFAULT_MAX_LOADS;
    /** A committed region with nothing queued leaves the window after this quiet time
     *  ({@code -Dlss.xaero.bestEffort.idleReleaseMillis}). */
    long idleReleaseMillis = DEFAULT_IDLE_RELEASE_MILLIS;
    /** A region not mid-batch leaves the window after this long in it, queued tiles or not
     *  (the share turns over; {@code -Dlss.xaero.bestEffort.maxHoldMillis}). */
    long maxHoldMillis = DEFAULT_MAX_HOLD_MILLIS;
    /** Best-effort owed rebuilds (tile chunks) at most — commits stop at the cap
     *  ({@code -Dlss.xaero.bestEffort.maxRebuildDebt}). */
    int maxRebuildDebt = DEFAULT_MAX_REBUILD_DEBT;

    static final int DEFAULT_BATCH_TILES = 64;
    static final long DEFAULT_BATCH_MILLIS = 8_000;
    static final int DEFAULT_RADIUS_CHUNKS = 128;
    static final int DEFAULT_MAX_QUEUE = 4096;
    static final long DEFAULT_MAX_QUEUE_BYTES = 24L * 1024 * 1024;
    static final long DEFAULT_TTL_MILLIS = 60_000;
    static final int DEFAULT_COMMITS_PER_PUMP = 32;
    static final int DEFAULT_MAX_LOADS = 2;
    static final long DEFAULT_IDLE_RELEASE_MILLIS = 10_000;
    static final long DEFAULT_MAX_HOLD_MILLIS = 30_000;
    static final int DEFAULT_MAX_REBUILD_DEBT = 64;
    /** A granted load whose request is still queued in Xaero keeps its slot this long. */
    static final long GRANT_QUEUED_MAX_MILLIS = 120_000;
    /** Followed (layer, region) grant counts — the re-grant meter's bound. */
    static final int MAX_GRANT_COUNTS = 4096;

    /** Production wiring: the spike's levers (cave-layers plan §B0), with sane defaults. */
    void configureFromSystemProperties() {
        this.enabled = Boolean.parseBoolean(System.getProperty("lss.xaero.bestEffortBands", "true"));
        this.batchTiles = Math.max(1, Integer.getInteger("lss.xaero.bestEffort.batchTiles", DEFAULT_BATCH_TILES));
        this.batchMillis = Math.max(0, Long.getLong("lss.xaero.bestEffort.batchMillis", DEFAULT_BATCH_MILLIS));
        this.radiusChunks = Math.max(0, Integer.getInteger("lss.xaero.bestEffort.radiusChunks", DEFAULT_RADIUS_CHUNKS));
        this.maxQueue = Math.max(1, Integer.getInteger("lss.xaero.bestEffort.maxQueue", DEFAULT_MAX_QUEUE));
        this.maxQueueBytes = Math.max(1, Long.getLong("lss.xaero.bestEffort.maxQueueMB", DEFAULT_MAX_QUEUE_BYTES >> 20)) << 20;
        this.ttlMillis = Math.max(1, Long.getLong("lss.xaero.bestEffort.ttlMillis", DEFAULT_TTL_MILLIS));
        this.windowOverride = Integer.getInteger("lss.xaero.bestEffort.window", -1);
        this.commitsPerPump = Math.max(1, Integer.getInteger("lss.xaero.bestEffort.commitsPerPump", DEFAULT_COMMITS_PER_PUMP));
        this.maxOutstandingLoads = Math.max(1, Integer.getInteger("lss.xaero.bestEffort.maxLoads", DEFAULT_MAX_LOADS));
        this.idleReleaseMillis = Math.max(0, Long.getLong("lss.xaero.bestEffort.idleReleaseMillis", DEFAULT_IDLE_RELEASE_MILLIS));
        this.maxHoldMillis = Math.max(0, Long.getLong("lss.xaero.bestEffort.maxHoldMillis", DEFAULT_MAX_HOLD_MILLIS));
        this.maxRebuildDebt = Math.max(1, Integer.getInteger("lss.xaero.bestEffort.maxRebuildDebt", DEFAULT_MAX_REBUILD_DEBT));
    }

    /** The window share: the override, else a quarter of the open-region window (2..16), or
     *  8 with the window off. */
    int windowCap() {
        if (this.windowOverride >= 0) return this.windowOverride;
        int cap = this.session.openRegionCap;
        return cap > 0 ? Math.max(2, Math.min(16, cap / 4)) : 8;
    }

    // ---- the queue ----

    static final class Entry {
        final Object dimension;
        final XaeroTileExtractor.PreparedTile tile;
        final Origin origin;
        final int bytes;
        final long enqueuedAtMillis;

        Entry(Object dimension, XaeroTileExtractor.PreparedTile tile, Origin origin, int bytes, long enqueuedAtMillis) {
            this.dimension = dimension;
            this.tile = tile;
            this.origin = origin;
            this.bytes = bytes;
            this.enqueuedAtMillis = enqueuedAtMillis;
        }
    }

    final Object lock = new Object();
    /** (layer, chunk) → entry; insertion-ordered (oldest first), latest tile wins. */
    final LinkedHashMap<TileKey, Entry> queue = new LinkedHashMap<>();
    long queuedBytes; // under lock
    volatile int queuedGauge;
    volatile long queuedBytesGauge;

    // ---- counters (the §B0 instrumentation) ----
    final AtomicLong enqueued = new AtomicLong();
    final AtomicLong written = new AtomicLong();
    final AtomicLong droppedOverflow = new AtomicLong();
    final AtomicLong droppedExpired = new AtomicLong();
    final AtomicLong droppedStale = new AtomicLong();
    final AtomicLong droppedOther = new AtomicLong();
    /** Entries dropped by a teardown/toggle clear — not a capacity failure, kept apart. */
    final AtomicLong cleared = new AtomicLong();
    /** All-void band tiles never enqueued (bands buried in rock). */
    final AtomicLong voidSkipped = new AtomicLong();
    final AtomicLong skippedNative = new AtomicLong();
    /** Band tiles never extracted because the queue was full (a column's would-be bands). */
    final AtomicLong refused = new AtomicLong();
    /** Pumps whose best-effort work held for the reliable path (§B6 strict priority). */
    final AtomicLong heldPumps = new AtomicLong();
    /** Grants released without a single commit (the load/remove loop's signature). */
    final AtomicLong fruitlessGrants = new AtomicLong();
    /** Non-priority load requests Xaero's loader refused (it was mid-drain); retried later. */
    final AtomicLong grantRefused = new AtomicLong();
    /** First-load batches committed in the pump that saw their region load. */
    final AtomicLong batches = new AtomicLong();
    final AtomicLong grants = new AtomicLong();
    /** Grants of a (layer, region) granted before (each beyond the first). */
    final AtomicLong regrants = new AtomicLong();
    /** The most grants any one followed (layer, region) took (§B0: re-grants ≤ 2, i.e. ≤ 3). */
    volatile int grantMax;
    volatile int openGauge;
    /** Xaero's save queue / load queue as last sampled, and the save queue's peak. */
    volatile int toSaveGauge;
    volatile int toSaveMax;
    volatile int toLoadGauge;
    volatile int toLoadMax;

    long dropped() {
        return this.droppedOverflow.get() + this.droppedExpired.get() + this.droppedStale.get()
                + this.droppedOther.get();
    }

    boolean isEmpty() {
        synchronized (this.lock) {
            return this.queue.isEmpty();
        }
    }

    int queued() {
        synchronized (this.lock) {
            return this.queue.size();
        }
    }

    /** Decode thread, before extraction: room for {@code bands} more tiles? A full queue
     *  refuses the column's bands (counted) instead of paying their extraction and then
     *  dropping the oldest entries (drop-oldest stays as the concurrent-offer belt). */
    boolean admit(int bands) {
        int queued = this.queuedGauge;
        long bytes = this.queuedBytesGauge;
        long average = queued > 0 ? bytes / queued : 0;
        if (queued + bands <= this.maxQueue && bytes + average * bands <= this.maxQueueBytes) return true;
        this.refused.addAndGet(bands);
        return false;
    }

    /** Decode thread: enqueue (latest wins; past a cap the OLDEST entries drop). */
    void offer(Object dimension, XaeroTileExtractor.PreparedTile tile, Origin origin) {
        TileKey key = XaeroSession.tileKey(tile);
        int bytes = XaeroSession.approxBytes(tile);
        List<Origin> released = null;
        synchronized (this.lock) {
            if (!this.enabled || !origin.active() || !this.session.writesOpen()) {
                origin.close();
                return;
            }
            if (bytes > this.maxQueueBytes || this.maxQueue <= 0) {
                this.droppedOverflow.incrementAndGet();
                origin.close();
                return;
            }
            var existing = this.queue.remove(key);
            if (existing != null) {
                this.queuedBytes -= existing.bytes;
                released = new ArrayList<>(2);
                released.add(existing.origin); // replaced by fresher bytes: not a drop
            }
            var it = this.queue.values().iterator();
            while (it.hasNext() && (this.queue.size() + 1 > this.maxQueue
                    || this.queuedBytes + bytes > this.maxQueueBytes)) {
                var oldest = it.next();
                it.remove();
                this.queuedBytes -= oldest.bytes;
                this.droppedOverflow.incrementAndGet();
                if (released == null) released = new ArrayList<>(4);
                released.add(oldest.origin);
            }
            this.queue.put(key, new Entry(dimension, tile, origin, bytes, this.session.nowMillis()));
            this.queuedBytes += bytes;
            this.queuedGauge = this.queue.size();
            this.queuedBytesGauge = this.queuedBytes;
            this.enqueued.incrementAndGet();
        }
        if (released != null) released.forEach(Origin::close);
    }

    /** Drop everything queued (teardown, toggles, a world change) — never reported. Any thread. */
    void clear() {
        List<Entry> dropped;
        synchronized (this.lock) {
            if (this.queue.isEmpty()) return;
            dropped = new ArrayList<>(this.queue.values());
            this.queue.clear();
            this.queuedBytes = 0;
            this.queuedGauge = 0;
            this.queuedBytesGauge = 0;
        }
        this.cleared.addAndGet(dropped.size());
        for (var e : dropped) e.origin.close();
    }

    /** Session end (main): the window, grant history and samples belong to the old world. */
    void resetSession() {
        this.open.clear();
        this.openDimension = null;
        this.openGauge = 0;
        this.grantCounts.clear();
    }

    private boolean removeIfCurrent(TileKey key, Entry entry) {
        synchronized (this.lock) {
            if (this.queue.get(key) != entry) return false;
            this.queue.remove(key);
            this.queuedBytes -= entry.bytes;
            this.queuedGauge = this.queue.size();
            this.queuedBytesGauge = this.queuedBytes;
            return true;
        }
    }

    private void drop(TileKey key, Entry entry, AtomicLong reason) {
        if (removeIfCurrent(key, entry)) {
            reason.incrementAndGet();
            entry.origin.close();
        }
    }

    // ---- the window share (main thread) ----

    /** One best-effort window region. {@code grantedAtMillis} 0 = admitted on a commit into a
     *  region that was already loaded. */
    static final class Slot {
        final long grantedAtMillis;
        final long admittedAtMillis;
        boolean loadSeen;
        boolean committed;
        /** A ready batch is committing: the rest of the bucket follows without re-waiting
         *  for K/T (a batch is never split by the commit cap into re-batched trickles). */
        boolean draining;
        long lastCommitMillis;

        Slot(long grantedAtMillis, long admittedAtMillis) {
            this.grantedAtMillis = grantedAtMillis;
            this.admittedAtMillis = admittedAtMillis;
        }
    }

    final LinkedHashMap<RegionKey, Slot> open = new LinkedHashMap<>();
    Object openDimension;
    /** Followed (layer, region) grant history: [count, first grant millis]. */
    final LinkedHashMap<RegionKey, long[]> grantCounts = new LinkedHashMap<>();

    // ---- the drain (main thread, inside the pump ladder's monitors) ----

    /**
     * One pump's best-effort work, after the reliable drain: probe the window (releases,
     * visits, and the regions that just loaded), start first-load batches (bounded), then —
     * only while the reliable path is clear — ready batches of loaded regions within the
     * leftover budget, then load grants to ready batches when Xaero has nothing to save.
     */
    void drain(Object mp, Object saveLoad, Object world, Object dimensionId, boolean loadNew,
               boolean update, XaeroSession.NativeReach reach, long budgetNanos) throws Throwable {
        long start = System.nanoTime();
        long now = this.session.nowMillis();
        if (this.openDimension != dimensionId) {
            this.open.clear();
            this.openDimension = dimensionId;
        }
        // Snapshot + the stale/expired/retired drops, grouped by (layer, region), oldest first.
        List<java.util.Map.Entry<TileKey, Entry>> snapshot;
        synchronized (this.lock) {
            snapshot = new ArrayList<>(this.queue.entrySet());
        }
        var buckets = new LinkedHashMap<RegionKey, List<java.util.Map.Entry<TileKey, Entry>>>();
        for (var e : snapshot) {
            Entry entry = e.getValue();
            if (!entry.origin.active()) {
                drop(e.getKey(), entry, this.cleared);
            } else if (entry.dimension != dimensionId) {
                drop(e.getKey(), entry, this.droppedStale);
            } else if (now - entry.enqueuedAtMillis > this.ttlMillis) {
                drop(e.getKey(), entry, this.droppedExpired);
            } else {
                var tile = entry.tile;
                buckets.computeIfAbsent(new RegionKey(tile.layer(), XaeroSession.regionKeyOf(tile.chunkX(), tile.chunkZ())),
                        k -> new ArrayList<>()).add(e);
            }
        }
        var firstLoad = new ArrayList<RegionKey>();
        var loaded = new java.util.HashSet<RegionKey>();
        probeWindow(mp, buckets, now, firstLoad, loaded);

        // First-load batches start NOW (the region must hold a chunk before its first save),
        // bounded: the commit cap and one pump budget of their own, at least one commit each.
        int commits = 0;
        for (var key : firstLoad) {
            var bucket = buckets.remove(key);
            if (bucket == null) continue;
            int n = commitBucket(mp, world, dimensionId, loadNew, update, reach, key, bucket,
                    this.commitsPerPump, this.session.pumpNanosBudget, System.nanoTime(), true);
            if (n > 0) this.batches.incrementAndGet();
            commits += n;
            if (this.session.dead) return;
        }
        if (reliableFirst()) {
            this.heldPumps.incrementAndGet();
            this.openGauge = this.open.size();
            return;
        }
        for (var e : buckets.entrySet()) {
            if (commits >= this.commitsPerPump || System.nanoTime() - start > budgetNanos) break;
            if (debtFull()) break;
            RegionKey key = e.getKey();
            var bucket = e.getValue();
            Slot slot = this.open.get(key);
            if (slot != null) {
                if (!loaded.contains(key)) continue; // granted, not loaded yet
            } else {
                if (this.open.size() >= windowCap() || !ready(bucket, now)) continue;
                Object region = this.session.h.getLeafMapRegion.invoke(mp, key.layer(),
                        (int) (key.region() >> 32), (int) key.region(), false);
                if (region == null) continue;
                byte state;
                synchronized (region) {
                    state = (byte) this.session.h.getLoadState.invoke(region);
                }
                if (state != 2) continue; // needs a grant (below), batched
                slot = new Slot(0L, now);
                this.open.put(key, slot);
                loaded.add(key);
            }
            if (!slot.draining && !ready(bucket, now)) continue; // batches, never trickles
            slot.draining = true; // cleared once the whole bucket has been through
            commits += commitBucket(mp, world, dimensionId, loadNew, update, reach, key, bucket,
                    this.commitsPerPump - commits, budgetNanos, start, false);
            if (this.session.dead) return;
        }
        grant(mp, saveLoad, buckets, loaded, now);
        this.openGauge = this.open.size();
    }

    /** §B6 strict priority: the reliable path retained work in its last drain, waits on a
     *  region load, or owes rebuilds past its soft cap — best-effort holds. */
    boolean reliableFirst() {
        return this.session.reliableBacklog || this.session.acquisition.regionsWaiting > 0
                || this.session.rebuilds.pendingUpdates.size() >= this.session.pendingUpdatesSoftCap;
    }

    /** Owed rebuilds a just-loaded region's first commit may add past {@link #maxRebuildDebt}
     *  (it must hold a chunk before its first save) — at most a few per load in flight. */
    static final int FIRST_LOAD_DEBT_HEADROOM = 16;

    /** The best-effort owed-rebuild cap is reached: commits wait for the flush. */
    boolean debtFull() {
        return this.session.rebuilds.bestEffortUpdates.size() >= this.maxRebuildDebt;
    }

    /** A batch is ready: at least K tiles, or its oldest at least T old. */
    boolean ready(List<java.util.Map.Entry<TileKey, Entry>> bucket, long now) {
        return bucket.size() >= this.batchTiles || now - bucket.get(0).getValue().enqueuedAtMillis >= this.batchMillis;
    }

    /** The window probe: releases parked/removed/expired/quiet/long-held regions, keeps the
     *  ones with queued tiles visited (Xaero parks an unvisited, unwritten region after 1 s —
     *  each reload of a layer nobody renders is a load, a save and a recache), and finds the
     *  granted regions that loaded since the last pump (the first-load batches). */
    private void probeWindow(Object mp, LinkedHashMap<RegionKey, List<java.util.Map.Entry<TileKey, Entry>>> buckets,
                             long now, List<RegionKey> firstLoad, java.util.Set<RegionKey> loaded) throws Throwable {
        var it = this.open.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            RegionKey key = e.getKey();
            Slot slot = e.getValue();
            Object region = this.session.h.getLeafMapRegion.invoke(mp, key.layer(),
                    (int) (key.region() >> 32), (int) key.region(), false);
            if (region == null) {
                this.session.loadStats.onMissing(key);
                release(it, slot);
                continue;
            }
            byte state;
            boolean requestable;
            boolean queued = buckets.containsKey(key);
            synchronized (region) {
                state = (byte) this.session.h.getLoadState.invoke(region);
                requestable = state == 0 && (boolean) this.session.h.canRequestReload.invoke(region);
                if (state == 2 && queued) this.session.h.registerVisit.invoke(region);
            }
            this.session.loadStats.onObserve(key, region, state, now);
            if (state == 2) {
                if (slot.grantedAtMillis > 0 && !slot.loadSeen) {
                    slot.loadSeen = true;
                    if (queued) {
                        firstLoad.add(key);
                        slot.draining = true;
                    }
                } else if (!queued && slot.committed && now - slot.lastCommitMillis >= this.idleReleaseMillis) {
                    release(it, slot); // quiet: Xaero's own limiter governs its residency now
                    continue;
                } else if (!slot.draining && now - slot.admittedAtMillis >= this.maxHoldMillis) {
                    release(it, slot); // the share turns over (its tiles wait for a re-grant)
                    continue;
                } else if (!queued && !slot.committed && slot.grantedAtMillis == 0) {
                    release(it, slot); // admitted, nothing left to write
                    continue;
                }
                loaded.add(key);
            } else if (state == 3 || state == 4) {
                release(it, slot); // Xaero parked or released it
            } else if (slot.grantedAtMillis > 0 && state == 0
                    && ((requestable && now - slot.grantedAtMillis > XaeroSession.GRANT_RESERVE_MILLIS)
                        || now - slot.grantedAtMillis > GRANT_QUEUED_MAX_MILLIS)) {
                release(it, slot); // the load never started (request lost, or queued too long)
            }
        }
    }

    private void release(java.util.Iterator<java.util.Map.Entry<RegionKey, Slot>> it, Slot slot) {
        it.remove();
        if (slot.grantedAtMillis > 0 && !slot.committed) this.fruitlessGrants.incrementAndGet();
    }

    /** Commits a bucket's entries, oldest first, up to {@code limit} commits and the budget
     *  (at least one commit is always attempted; a first-load batch's first commit may use
     *  the debt cap's small {@link #FIRST_LOAD_DEBT_HEADROOM}). @return commits made. */
    private int commitBucket(Object mp, Object world, Object dimensionId, boolean loadNew, boolean update,
                             XaeroSession.NativeReach reach, RegionKey key,
                             List<java.util.Map.Entry<TileKey, Entry>> bucket, int limit, long budgetNanos,
                             long start, boolean firstLoad) throws Throwable {
        int commits = 0;
        int handled = 0;
        Slot slot = this.open.get(key);
        for (var e : bucket) {
            if (commits >= limit) break;
            if (commits > 0 && System.nanoTime() - start > budgetNanos) break;
            if (debtFull() && !(firstLoad && commits == 0)) break; // the best-effort rebuild cap
            handled++;
            Entry entry = e.getValue();
            if (this.session.writer.nativelyWritable(world, entry.tile, reach)) {
                // The band became the one Xaero shows and the native writer reaches it.
                if (removeIfCurrent(e.getKey(), entry)) {
                    entry.origin.close();
                    this.skippedNative.incrementAndGet();
                }
                continue;
            }
            Outcome outcome = this.session.writer.commitEntry(mp, dimensionId, entry.tile, loadNew, update);
            switch (outcome) {
                case COMMITTED -> {
                    removeIfCurrent(e.getKey(), entry);
                    entry.origin.close();
                    this.written.incrementAndGet();
                    this.session.consecutiveFailures = 0;
                    commits++;
                    if (slot != null) {
                        slot.committed = true;
                        slot.lastCommitMillis = this.session.nowMillis();
                    }
                }
                case DEFERRED_TILE -> {
                    // this tile chunk is busy: siblings keep committing; the TTL bounds it
                }
                case DEFERRED, AWAITING_REQUESTABLE, AWAITING_PARKED, AWAITING_IN_FLIGHT -> {
                    return commits; // region-scoped: the whole bucket waits (TTL-bounded)
                }
                case SKIPPED_SETTINGS -> drop(e.getKey(), entry, this.droppedOther);
                case FAILED -> {
                    drop(e.getKey(), entry, this.droppedOther);
                    if (this.session.dead) return commits;
                }
            }
        }
        if (slot != null && handled >= bucket.size()) slot.draining = false; // the batch is through
        return commits;
    }

    /** Load grants for READY batches (≥ K tiles, or the oldest ≥ T old), largest first, while
     *  the reliable path is clear and Xaero's save queue is empty — requested without priority. */
    private void grant(Object mp, Object saveLoad, LinkedHashMap<RegionKey, List<java.util.Map.Entry<TileKey, Entry>>> buckets,
                       java.util.Set<RegionKey> loaded, long now) throws Throwable {
        if (buckets.isEmpty() || debtFull()) return;
        if (!saveQueueEmpty(saveLoad)) return;
        int outstanding = 0;
        for (var slot : this.open.values()) {
            if (slot.grantedAtMillis > 0 && !slot.loadSeen) outstanding++;
        }
        int budget = Math.min(this.maxOutstandingLoads - outstanding, windowCap() - this.open.size());
        if (budget <= 0) return;
        var ready = new ArrayList<java.util.Map.Entry<RegionKey, List<java.util.Map.Entry<TileKey, Entry>>>>();
        for (var e : buckets.entrySet()) {
            if (this.open.containsKey(e.getKey()) || loaded.contains(e.getKey())) continue;
            if (ready(e.getValue(), now)) ready.add(e);
        }
        ready.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));
        for (int i = 0; i < ready.size() && budget > 0; i++) {
            RegionKey key = ready.get(i).getKey();
            if (!this.session.requestRegionLoad(mp, saveLoad, key, true)) continue;
            budget--;
            this.open.put(key, new Slot(Math.max(1L, now), now));
            this.grants.incrementAndGet();
            long[] history = this.grantCounts.get(key);
            if (history == null) {
                this.grantCounts.put(key, new long[]{1, now});
            } else {
                history[0]++;
                this.regrants.incrementAndGet();
                if (history[0] > this.grantMax) this.grantMax = (int) history[0];
            }
            if (this.grantMax < 1) this.grantMax = 1;
            while (this.grantCounts.size() > MAX_GRANT_COUNTS) {
                var it = this.grantCounts.entrySet().iterator();
                it.next();
                it.remove();
            }
        }
    }

    /** The most re-grants per minute any followed (layer, region) took since its first grant
     *  (minutes floored at one): a load/remove loop re-grants every few seconds, trickling
     *  arrival every few minutes. */
    double regrantRateMax(long now) {
        double max = 0;
        for (var h : this.grantCounts.values()) {
            if (h[0] < 2) continue;
            double minutes = Math.max(1.0, (now - h[1]) / 60_000.0);
            max = Math.max(max, (h[0] - 1) / minutes);
        }
        return max;
    }

    /** Xaero's save queue is empty — and its size sampled for the diag (a throw reads busy). */
    boolean saveQueueEmpty(Object saveLoad) {
        var g = this.session.h.bestEffortGate;
        if (g == null) return false;
        try {
            int toSave = ((java.util.List<?>) g.getToSave().invoke(saveLoad)).size();
            sample(toSave, (int) g.getSizeOfToLoad().invoke(saveLoad));
            return toSave == 0;
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            return false;
        }
    }

    void sample(int toSave, int toLoad) {
        this.toSaveGauge = toSave;
        if (toSave > this.toSaveMax) this.toSaveMax = toSave;
        this.toLoadGauge = toLoad;
        if (toLoad > this.toLoadMax) this.toLoadMax = toLoad;
    }

    /** The diag tokens (cave-layers plan §B8 + the §B0 instrumentation). */
    String describe() {
        return "best_effort_enqueued=" + this.enqueued.get()
                + ", best_effort_written=" + this.written.get()
                + ", best_effort_dropped=" + dropped()
                + ", best_effort_drops=overflow:" + this.droppedOverflow.get()
                + "/expired:" + this.droppedExpired.get()
                + "/stale:" + this.droppedStale.get()
                + "/other:" + this.droppedOther.get()
                + ", best_effort_cleared=" + this.cleared.get()
                + ", best_effort_void=" + this.voidSkipped.get()
                + ", best_effort_skipped_native=" + this.skippedNative.get()
                + ", best_effort_queued=" + this.queuedGauge
                + ", best_effort_open=" + this.openGauge + "/" + windowCap()
                + ", best_effort_batches=" + this.batches.get()
                + ", best_effort_grants=" + this.grants.get()
                + ", best_effort_regrants=" + this.regrants.get()
                + ", best_effort_grant_max=" + this.grantMax
                + ", best_effort_fruitless_grants=" + this.fruitlessGrants.get()
                + ", best_effort_grant_refused=" + this.grantRefused.get()
                + ", best_effort_grants_per_ktile=" + grantsPerKiloTile()
                + ", best_effort_regrant_rate_max=" + String.format(java.util.Locale.ROOT, "%.2f",
                        regrantRateMax(this.session.nowMillis())) + "/min"
                + ", best_effort_refused=" + this.refused.get()
                + ", best_effort_held_pumps=" + this.heldPumps.get()
                + ", best_effort_pending_updates=" + this.session.rebuilds.bestEffortUpdatesGauge
                + "/" + this.maxRebuildDebt
                + ", best_effort_buffer_updates=" + this.session.rebuilds.bestEffortBufferUpdates.get()
                + ", best_effort_dropped_updates=" + this.session.rebuilds.bestEffortDroppedUpdates.get()
                + ", best_effort_rebuild_stalled=" + this.session.rebuilds.stalledBestEffortGauge
                + ", best_effort_defers=" + this.session.bestEffortDefers.describe()
                + ", best_effort_bands=" + (this.enabled ? "on" : "off");
    }

    /** Load grants per 1000 best-effort tiles written (-1 before the first write) — loads
     *  per unit of work, so trickling arrival is not read as a load/remove loop. */
    long grantsPerKiloTile() {
        long written = this.written.get();
        return written == 0 ? -1 : this.grants.get() * 1000 / written;
    }

    void putMetrics(java.util.Map<String, Object> out) {
        out.put("best_effort_on", this.enabled);
        out.put("best_effort_enqueued", this.enqueued.get());
        out.put("best_effort_written", this.written.get());
        out.put("best_effort_dropped", dropped());
        out.put("best_effort_drop_overflow", this.droppedOverflow.get());
        out.put("best_effort_drop_expired", this.droppedExpired.get());
        out.put("best_effort_drop_stale", this.droppedStale.get());
        out.put("best_effort_drop_other", this.droppedOther.get());
        out.put("best_effort_cleared", this.cleared.get());
        out.put("best_effort_void", this.voidSkipped.get());
        out.put("best_effort_skipped_native", this.skippedNative.get());
        out.put("best_effort_queued", this.queuedGauge);
        out.put("best_effort_open", this.openGauge);
        out.put("best_effort_window", windowCap());
        out.put("best_effort_batches", this.batches.get());
        out.put("best_effort_grants", this.grants.get());
        out.put("best_effort_regrants", this.regrants.get());
        out.put("best_effort_grant_max", this.grantMax);
        out.put("best_effort_fruitless_grants", this.fruitlessGrants.get());
        out.put("best_effort_grant_refused", this.grantRefused.get());
        out.put("best_effort_grants_per_ktile", grantsPerKiloTile());
        out.put("best_effort_regrant_rate_max", regrantRateMax(this.session.nowMillis()));
        out.put("best_effort_refused", this.refused.get());
        out.put("best_effort_held_pumps", this.heldPumps.get());
        out.put("best_effort_pending_updates", this.session.rebuilds.bestEffortUpdatesGauge);
        out.put("best_effort_rebuild_cap", this.maxRebuildDebt);
        out.put("best_effort_buffer_updates", this.session.rebuilds.bestEffortBufferUpdates.get());
        out.put("best_effort_dropped_updates", this.session.rebuilds.bestEffortDroppedUpdates.get());
        out.put("best_effort_rebuild_stalled", this.session.rebuilds.stalledBestEffortGauge);
        this.session.bestEffortDefers.putMetrics(out, "best_effort_");
        out.put("to_save", this.toSaveGauge);
        out.put("to_save_max", this.toSaveMax);
        out.put("to_load", this.toLoadGauge);
        out.put("to_load_max", this.toLoadMax);
    }
}
