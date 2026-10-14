package dev.vox.lss.compat;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.compat.XaeroSession.LayerPlan;
import dev.vox.lss.compat.XaeroSession.PlayerPos;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Xaero cave layers, FILL-ON-SWITCH (replaces the B-II best-effort bands): in a ceiling
 * dimension (the Nether and modded equivalents) every delivered column is kept in the
 * {@link XaeroColumnStore}; live arrivals keep writing the surface plus the layer Xaero shows
 * (phase B-I). When Xaero's layer changes (a Layered band, or Full) and stays put for
 * {@link #debounceMillis}, a fill job re-extracts that layer from the store for the stored
 * positions within min(LOD distance, {@link #radiusChunks}) of the player, nearest first,
 * skipping positions already written to that layer this session, and offers the tiles to
 * the reliable queue for the CURRENT layer — Xaero renders it, so its regions rest and save
 * at the normal cadence.
 *
 * <p>Fill tiles are lease-free ({@code Origin.live == false}): they never report, owe or
 * re-serve, and they do not count in the occupancy the LSS backpressure report reads (so
 * they never slow Voxy). Live arrivals keep priority: the worker offers only while the
 * reliable queue holds fewer than {@link #queueHigh} entries, and never over a queued tile.
 *
 * <p>Threads: the trigger runs on the main thread (the pump); one daemon worker decodes,
 * extracts and offers; the store's IO thread reads. A job is cancelled by the next layer
 * switch, a dimension, world or bucket change, a disconnect, the server's deny, local map
 * ingestion off, world-save mode or missing cave bindings.
 */
final class XaeroBandFill {
    /** The client level a fill reads its facts from: the dimension, the store's file key, the
     *  heights, the section factory, and the registry fingerprint the store is keyed by. */
    record FillLevel(Object levelRef, Object dimension, String dimKey, XaeroTileExtractor.LevelFacts facts,
                     Registry<Biome> factory, long fingerprint) {}

    /** Coverage key: one Xaero layer of one dimension. ResourceKeys are interned. */
    private record CoverKey(Object dimension, int layer) {}

    /** One fill job. Fields read by the worker are final or volatile. */
    final class Job {
        final Object dimension;
        final int layer;
        final XaeroTileExtractor.LayerTarget target;
        final FillLevel level;
        final String bucket;
        final boolean ignoreHeightmaps;
        final int centerX;
        final int centerZ;
        final int radius;
        final long startedMillis;
        volatile boolean cancelled;
        volatile boolean done;
        /** The stored positions within the radius when the job started (nearest first). */
        volatile long[] positions = new long[0];
        volatile int remaining;

        Job(Object dimension, int layer, XaeroTileExtractor.LayerTarget target, FillLevel level, String bucket,
            boolean ignoreHeightmaps, int centerX, int centerZ, int radius, long startedMillis) {
            this.dimension = dimension;
            this.layer = layer;
            this.target = target;
            this.level = level;
            this.bucket = bucket;
            this.ignoreHeightmaps = ignoreHeightmaps;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radius = radius;
            this.startedMillis = startedMillis;
        }
    }

    private final XaeroSession session;
    /** Null = no store: no capture, no fill (a bare session, or the kill switch). */
    volatile XaeroColumnStore store;

    // ---- tunables (production: system properties; a bare session starts OFF) ----

    /** {@code -Dlss.xaero.bandFill} (production default true; false = the B-I arm). */
    volatile boolean enabled;
    /** {@code -Dlss.xaero.bandFill.debounceMillis}. */
    long debounceMillis = DEFAULT_DEBOUNCE_MILLIS;
    /** {@code -Dlss.xaero.bandFill.radiusChunks} (the LOD distance binds below it). */
    int radiusChunks = DEFAULT_RADIUS_CHUNKS;
    /** {@code -Dlss.xaero.bandFill.queueHigh}: offer only while the reliable queue is shorter. */
    volatile int queueHigh = DEFAULT_QUEUE_HIGH;
    /** {@code -Dlss.xaero.bandFill.batch}: stored columns read per store round trip. */
    int batch = DEFAULT_BATCH;

    static final long DEFAULT_DEBOUNCE_MILLIS = 1_500;
    static final int DEFAULT_RADIUS_CHUNKS = 128;
    static final int DEFAULT_QUEUE_HIGH = 128;
    static final int DEFAULT_BATCH = 64;
    /** A worker stuck behind a queue that never drains gives the job up after this. */
    static final long PACING_GIVE_UP_MILLIS = 120_000;

    void configureFromSystemProperties() {
        this.enabled = Boolean.parseBoolean(System.getProperty("lss.xaero.bandFill", "true"));
        this.debounceMillis = Math.max(0, Long.getLong("lss.xaero.bandFill.debounceMillis", DEFAULT_DEBOUNCE_MILLIS));
        this.radiusChunks = Math.max(1, Integer.getInteger("lss.xaero.bandFill.radiusChunks", DEFAULT_RADIUS_CHUNKS));
        this.queueHigh = Math.max(1, Integer.getInteger("lss.xaero.bandFill.queueHigh", DEFAULT_QUEUE_HIGH));
        this.batch = Math.max(1, Integer.getInteger("lss.xaero.bandFill.batch", DEFAULT_BATCH));
    }

    // ---- counters (diag + the xaero exporter group) ----
    final AtomicLong started = new AtomicLong();
    final AtomicLong completed = new AtomicLong();
    final AtomicLong cancelled = new AtomicLong();
    final AtomicLong failed = new AtomicLong();
    /** Fill tiles accepted into the reliable queue. */
    final AtomicLong tilesOffered = new AtomicLong();
    /** Fill tiles committed (counted by the drain). */
    final AtomicLong tilesCommitted = new AtomicLong();
    final AtomicLong skippedCovered = new AtomicLong();
    /** Fill tiles not offered because a live tile of that key was queued. */
    final AtomicLong skippedQueued = new AtomicLong();
    final AtomicLong captureFailures = new AtomicLong();
    volatile long lastFillMillis = -1;

    // ---- coverage: positions written to a layer THIS session (live or fill) ----
    private final ConcurrentHashMap<CoverKey, LongOpenHashSet> coverage = new ConcurrentHashMap<>();
    /** The ceiling dimension coverage is kept for (the pump publishes it). */
    volatile Object coverDimension;
    /** The column-cache bucket the coverage belongs to (main thread). */
    private String coverBucket;

    // ---- the trigger (main thread) ----
    private Object seenDimension;
    private int seenLayer = XaeroSession.SURFACE_LAYER;
    private long seenSinceMillis;
    volatile Job job;
    private ExecutorService worker;

    XaeroBandFill(XaeroSession session) {
        this.session = session;
    }

    // ---- coverage ----

    void markCovered(Object dimension, int layer, long pos) {
        if (dimension != this.coverDimension) return;
        var set = this.coverage.computeIfAbsent(new CoverKey(dimension, layer), k -> new LongOpenHashSet());
        synchronized (set) {
            set.add(pos);
        }
    }

    boolean covered(Object dimension, int layer, long pos) {
        var set = this.coverage.get(new CoverKey(dimension, layer));
        if (set == null) return false;
        synchronized (set) {
            return set.contains(pos);
        }
    }

    /** A live arrival (decode thread): the column changed or arrived, so every layer written
     *  from an older copy of it is stale (the live commit re-marks the shown layer). */
    void invalidate(Object dimension, long pos) {
        for (var e : this.coverage.entrySet()) {
            if (e.getKey().dimension() != dimension) continue;
            var set = e.getValue();
            synchronized (set) {
                set.remove(pos);
            }
        }
    }

    // ---- capture (decode thread) ----

    /** Keep a delivered column of a ceiling dimension (the caller has passed the write gates). */
    void capture(FillLevel level, String bucket, Object dimension, int chunkX, int chunkZ,
                 dev.vox.lss.api.VoxelColumnData columnData) {
        var store = this.store;
        if (!this.enabled || store == null || level == null || bucket == null) return;
        long pos = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        invalidate(dimension, pos);
        byte[] raw;
        try {
            raw = XaeroColumnCodec.encode(columnData, level.facts());
        } catch (RuntimeException e) {
            this.captureFailures.incrementAndGet();
            store.captureDrops.incrementAndGet();
            return;
        }
        store.capture(bucket, level.dimKey(), level.fingerprint(), pos, raw);
    }

    // ---- the trigger (main thread, every pump) ----

    /** Start, keep or cancel the fill for what Xaero shows now. {@code open} = writes are
     *  allowed (session live, ingestion on, server allows, not dead) and the cave bindings
     *  are bound. */
    void tick(LayerPlan plan, FillLevel level, boolean open, String bucket) {
        var current = this.job;
        boolean eligible = this.enabled && this.store != null && open && plan != null && !plan.worldSave()
                && level != null && level.facts().ceiling() && plan.dimension() == level.dimension();
        // Coverage is kept for the ceiling dimension the player is in (marks elsewhere are ignored).
        this.coverDimension = this.enabled && this.store != null && level != null && level.facts().ceiling()
                ? level.dimension() : null;
        if (!eligible) {
            cancel(current);
            this.seenDimension = null;
            return;
        }
        if (!java.util.Objects.equals(bucket, this.coverBucket)) {
            // Another world (or server identity): this session's coverage named the old one.
            if (this.coverBucket != null) this.coverage.clear();
            this.coverBucket = bucket;
        }
        int layer = plan.currentLayer();
        if (current != null && (current.dimension != plan.dimension() || current.layer != layer
                || !current.bucket.equals(bucket))) {
            cancel(current); // the switch, the dimension or the world moved on
            current = null;
        }
        long now = this.session.nowMillis();
        if (plan.dimension() != this.seenDimension || layer != this.seenLayer) {
            this.seenDimension = plan.dimension();
            this.seenLayer = layer;
            this.seenSinceMillis = now;
            return;
        }
        if (layer == XaeroSession.SURFACE_LAYER) return; // live arrivals always write the surface
        if (now - this.seenSinceMillis < this.debounceMillis) return;
        if (current != null || bucket == null) return; // running, or done for this layer
        PlayerPos player = this.session.playerProbe.position();
        if (player == null || player.dimension() != plan.dimension()) return;
        int radius = this.radiusChunks;
        int lod = this.session.serverLodDistance.getAsInt();
        if (lod > 0) radius = Math.min(radius, lod);
        var target = layer == XaeroTileExtractor.FULL ? XaeroTileExtractor.LayerTarget.full(plan.depth())
                : XaeroTileExtractor.LayerTarget.band(layer, level.facts().topY() - 1, plan.depth());
        var next = new Job(plan.dimension(), layer, target, level, bucket, plan.ignoreHeightmaps(),
                ((int) Math.floor(player.x())) >> 4, ((int) Math.floor(player.z())) >> 4, radius, now);
        this.job = next;
        this.lastJob = next;
        this.started.incrementAndGet();
        submit(next);
    }

    /** Session end, acquisition off, a deny: cancel the job and forget this session. */
    void reset() {
        cancel(this.job);
        this.job = null;
        this.seenDimension = null;
        this.coverage.clear();
        this.coverBucket = null;
        var store = this.store;
        if (store != null) store.closeActive();
    }

    void cancel(Job j) {
        if (j == null) return;
        if (!j.done && !j.cancelled) {
            j.cancelled = true;
            this.cancelled.incrementAndGet();
        }
        if (this.job == j) this.job = null;
    }

    private void submit(Job j) {
        if (this.worker == null) {
            this.worker = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, dev.vox.lss.common.Brand.shortName() + "-XaeroBandFill");
                t.setDaemon(true);
                return t;
            });
        }
        this.worker.execute(() -> run(j));
    }

    // ---- the worker ----

    void run(Job j) {
        var store = this.store;
        try {
            if (j.cancelled || store == null) return;
            var level = j.level;
            long[] positions = store.positionsNear(j.bucket, level.dimKey(), level.fingerprint(),
                    j.centerX, j.centerZ, j.radius).get(60, TimeUnit.SECONDS);
            j.positions = positions;
            for (int i = 0; i < positions.length && !j.cancelled; i += this.batch) {
                j.remaining = positions.length - i;
                int end = Math.min(positions.length, i + this.batch);
                var slice = new it.unimi.dsi.fastutil.longs.LongArrayList(end - i);
                for (int k = i; k < end; k++) {
                    if (covered(j.dimension, j.layer, positions[k])) {
                        this.skippedCovered.incrementAndGet();
                    } else {
                        slice.add(positions[k]);
                    }
                }
                if (slice.isEmpty()) continue;
                List<XaeroColumnStore.Column> columns = store.read(j.bucket, level.dimKey(), level.fingerprint(),
                        slice.toLongArray()).get(60, TimeUnit.SECONDS);
                for (var column : columns) {
                    if (j.cancelled) break;
                    if (!offerOne(j, column)) return;
                }
            }
            if (!j.cancelled) {
                this.completed.incrementAndGet();
                this.lastFillMillis = this.session.nowMillis() - j.startedMillis;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) throw vme;
            this.failed.incrementAndGet();
            long n = XaeroSession.EXTRACT_FAIL_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
            if (n > 0) LSSLogger.warn("Xaero map bridge: a band fill failed (" + n + " since the last report)", t);
        } finally {
            j.remaining = 0;
            j.done = true;
        }
    }

    /** Decode, extract and offer one stored column. @return false = give the job up. */
    private boolean offerOne(Job j, XaeroColumnStore.Column column) throws InterruptedException {
        long pos = column.pos();
        if (covered(j.dimension, j.layer, pos)) {
            this.skippedCovered.incrementAndGet();
            return true;
        }
        int chunkX = (int) (pos >> 32);
        int chunkZ = (int) pos;
        var data = XaeroColumnCodec.decode(column.raw(), j.level.facts(), j.level.factory());
        var tile = XaeroTileExtractor.extract(chunkX, chunkZ, j.level.facts(), data, List.of(j.target),
                j.ignoreHeightmaps).get(0);
        long waitStart = System.nanoTime();
        while (!j.cancelled && !this.session.fillRoom()) {
            if (System.nanoTime() - waitStart > PACING_GIVE_UP_MILLIS * 1_000_000L) {
                cancel(j);
                return false;
            }
            Thread.sleep(20);
        }
        if (j.cancelled) return false;
        if (covered(j.dimension, j.layer, pos)) {
            this.skippedCovered.incrementAndGet(); // a live arrival wrote it meanwhile
            return true;
        }
        if (this.session.offerFill(j.dimension, tile)) {
            this.tilesOffered.incrementAndGet();
        } else {
            this.skippedQueued.incrementAndGet();
        }
        return true;
    }

    // ---- diag ----

    /** The job's progress for the spike law: [stored positions in its radius, how many of
     *  them are written to its layer this session]. */
    int[] progress(Job j) {
        if (j == null) return new int[]{0, 0};
        long[] positions = j.positions;
        int covered = 0;
        var set = this.coverage.get(new CoverKey(j.dimension, j.layer));
        if (set != null) {
            synchronized (set) {
                for (long pos : positions) if (set.contains(pos)) covered++;
            }
        }
        return new int[]{positions.length, covered};
    }

    /** The last job (running, done or cancelled-by-nothing) the diag reports on. */
    volatile Job lastJob;

    String layerToken(Job j) {
        if (j == null) return "-";
        return j.layer == XaeroTileExtractor.FULL ? "full" : Integer.toString(j.layer);
    }

    String describe() {
        var store = this.store;
        var j = this.lastJob;
        int[] progress = progress(j);
        return "band_fill=" + (this.enabled && store != null ? "on" : "off")
                + ", store_columns=" + (store == null ? 0 : store.columnsGauge)
                + ", store_mb=" + (store == null ? 0 : store.bytesGauge >> 20)
                + ", store_evictions=" + (store == null ? 0 : store.evictions.get())
                + ", store_capture_drops=" + (store == null ? 0 : store.captureDrops.get())
                + ", fills=started:" + this.started.get() + "/completed:" + this.completed.get()
                + "/cancelled:" + this.cancelled.get() + "/failed:" + this.failed.get()
                + ", fill_tiles=" + this.tilesOffered.get() + "(committed " + this.tilesCommitted.get() + ")"
                + ", fill_layer=" + layerToken(j)
                + ", fill_progress=" + progress[1] + "/" + progress[0]
                + ", fill_queue=" + (j == null ? 0 : j.remaining)
                + ", fill_last_ms=" + this.lastFillMillis;
    }

    void putMetrics(java.util.Map<String, Object> out) {
        var store = this.store;
        var j = this.lastJob;
        int[] progress = progress(j);
        out.put("fill_on", this.enabled && store != null);
        out.put("store_columns", store == null ? 0 : store.columnsGauge);
        out.put("store_bytes", store == null ? 0L : store.bytesGauge);
        out.put("store_evictions", store == null ? 0L : store.evictions.get());
        out.put("store_capture_drops", store == null ? 0L : store.captureDrops.get());
        out.put("store_captured", store == null ? 0L : store.captured.get());
        out.put("store_corrupt", store == null ? 0L : store.corrupt.get());
        out.put("fill_started", this.started.get());
        out.put("fill_completed", this.completed.get());
        out.put("fill_cancelled", this.cancelled.get());
        out.put("fill_failed", this.failed.get());
        out.put("fill_tiles", this.tilesOffered.get());
        out.put("fill_committed", this.tilesCommitted.get());
        out.put("fill_skipped_covered", this.skippedCovered.get());
        out.put("fill_skipped_queued", this.skippedQueued.get());
        out.put("fill_layer", layerToken(j));
        out.put("fill_target", progress[0]);
        out.put("fill_covered", progress[1]);
        out.put("fill_age_ms", j == null ? -1L : this.session.nowMillis() - j.startedMillis);
        out.put("fill_queue", j == null ? 0 : j.remaining);
        out.put("fill_last_ms", this.lastFillMillis);
    }
}
