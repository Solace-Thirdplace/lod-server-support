package dev.vox.lss.compat;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnConsumer;
import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.LogThrottle;
import dev.vox.lss.config.LSSClientConfig;
import io.netty.buffer.Unpooled;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * The JourneyMap map-ingestion backend (map-ingestion plan §3): writes received LSS columns
 * into JourneyMap's map through JourneyMap's OWN chunk-snapshot render path — the sequence
 * JourneyMap's own (unused) debug code runs: snapshot bytes → {@code JMChunkSnapshot.decode}
 * → {@code SnapshotChunkMD} (a chunk wrapper that reads no world state) →
 * {@code ChunkRenderController.renderChunk} per map type → {@code RegionImageCache
 * .updateTextures}. JourneyMap then saves its region PNGs, uploads textures and refreshes the
 * minimap and fullscreen map itself. Reflection only (JourneyMap is All Rights Reserved).
 *
 * <p>Threads: the consumer ENCODES on the LSS decode thread and queues the bytes; the PUMP
 * (main client thread) observes JourneyMap's mapping identity, settles, and submits ONE batch
 * at a time to JourneyMap's own single task thread ({@code queueOneOff}, under the
 * {@code JourneymapClient} monitor {@code stopMapping} holds); the BATCH decodes, renders and
 * publishes on that thread and always ends in a terminal result the pump reconciles.
 *
 * <p>Doctrine (Xaero bridge parity): a column is accepted only when every requested map type
 * rendered it under an unchanged identity; anything else is reported for its bounded re-serve
 * or requeued, never silently lost. Inert states (JourneyMap disabled, mapping not allowed,
 * dimensions with a ceiling) drop offers before encoding and report no backpressure, so a map
 * that cannot take terrain never holds the stream.
 */
final class JourneyMapSession {
    static final int MAX_QUEUE = 8192;
    static final long MAX_QUEUE_BYTES = 48L * 1024 * 1024;
    static final int MAX_BATCH_COLUMNS = 256;
    /** A JourneyMap server sends its world id after mapping starts and JourneyMap then
     *  restarts mapping in a new directory: writes wait for the identity to settle. */
    static final long SETTLE_MILLIS = 3000;
    /** {@code getJMWorldDir} is static-synchronized and creates directories. */
    static final long IDENTITY_POLL_MILLIS = 1000;
    static final long START_TIMEOUT_MILLIS = 30_000;
    static final long SLOW_BATCH_MILLIS = 10_000;
    static final long DEFAULT_RENDER_DELAY_MILLIS = 500;
    static final int DEATH_LATCH = 5;
    static final int CONTEXT_CAPACITY = 512;
    static final int REPLAY_CAPACITY = 4096;
    static final long REPLAY_WINDOW_MILLIS = 30_000;
    /** The edge sweep's cadence and reach beyond JourneyMap's own mapping range (chunks). */
    static final long EDGE_SWEEP_MILLIS = 2000;
    static final int EDGE_BAND_CHUNKS = 3;

    static final LogThrottle FAILURE_WARN = new LogThrottle(60_000);

    /** The Minecraft operations the session needs — a seam: {@code ClientLevel} and
     *  {@code Minecraft} are unconstructible under fabric-loader-junit. */
    interface Host {
        Object minecraft();
        Object level();
        boolean playerInLevel();
        Object dimension(Object level);
        boolean hasCeiling(Object level);
        /** The player's packed chunk position, or {@link Long#MIN_VALUE} without a player. */
        long playerChunk();
    }

    /** Encoded snapshot bytes → a {@code SnapshotChunkMD}, or null when JourneyMap refuses the
     *  bytes. A seam: production decodes through the bindings, which need a real
     *  {@code ClientLevel}; Tier 1 substitutes the stub (the real decoder has its own
     *  parity test against the real jar). */
    @FunctionalInterface
    interface SnapshotDecoder {
        Object decode(byte[] bytes, Object level) throws Throwable;
    }

    static final Host PRODUCTION_HOST = new Host() {
        @Override public Object minecraft() { return Minecraft.getInstance(); }

        @Override public Object level() {
            var mc = Minecraft.getInstance();
            return mc == null ? null : mc.level;
        }

        @Override public boolean playerInLevel() {
            var mc = Minecraft.getInstance();
            return mc != null && mc.player != null && mc.level != null && mc.player.level() == mc.level;
        }

        @Override public Object dimension(Object level) { return ((ClientLevel) level).dimension(); }

        @Override public boolean hasCeiling(Object level) { return ((ClientLevel) level).dimensionType().hasCeiling(); }

        @Override public long playerChunk() {
            var mc = Minecraft.getInstance();
            var player = mc == null ? null : mc.player;
            return player == null ? Long.MIN_VALUE : packed(player.getBlockX() >> 4, player.getBlockZ() >> 4);
        }
    };

    // ---- static facade (ModCompat owns it through JourneyMapCompat) ----

    static volatile JourneyMapSession instance;
    /** Installed but this JourneyMap predates the snapshot layer (the 1.21.10 beta). */
    static volatile boolean unsupported;
    /** Installed, has the snapshot layer, but a member did not resolve: drift. */
    static volatile String unavailableReason;

    /**
     * Resolves JourneyMap's classes WITHOUT initializing them. Binding runs at LSS's client
     * init, before JourneyMap has necessarily initialized itself: running a JourneyMap
     * static initializer there (DataCache's reads JourneyMap's not-yet-loaded config)
     * throws, poisons the class for good, and JourneyMap then crashes on its first chunk
     * load (found by the live smoke). Classes initialize on first use instead — on the pump
     * or JourneyMap's own thread, once JourneyMap is mapping.
     */
    static final XaeroSession.ClassResolver PRODUCTION_RESOLVER =
            name -> Class.forName(name, false, JourneyMapSession.class.getClassLoader());

    static boolean init() {
        return initWith(PRODUCTION_RESOLVER, PRODUCTION_HOST);
    }

    static boolean initWith(XaeroSession.ClassResolver resolver, Host host) {
        try {
            var h = JourneyMapBindings.resolve(resolver);
            var session = new JourneyMapSession(h, host,
                    () -> LSSClientConfig.CONFIG.mapIngestionEnabled(),
                    LSSApi::isServerEnabled,
                    LSSApi::registerColumnConsumer, LSSApi::removeColumnConsumer,
                    () -> LSSClientConfig.CONFIG.enableIngestBackpressure()
                            && LSSClientConfig.CONFIG.mapIngestionBackpressure(),
                    XaeroSession::reportDroppedProduction,
                    System::currentTimeMillis);
            session.memory = MapMemoryGuard.PRODUCTION;
            session.maybeRegister();
            instance = session;
            LSSLogger.info(LSSClientConfig.CONFIG.mapIngestionEnabled()
                    ? "JourneyMap detected — LOD map ingestion active"
                    : "JourneyMap detected — LOD map ingestion ready (map ingestion is disabled in the client settings)");
            return true;
        } catch (JourneyMapBindings.Unsupported e) {
            unsupported = true;
            LSSLogger.info("JourneyMap detected, but " + e.getMessage() + " — LOD map ingestion is off for it");
            return false;
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException | IllegalAccessException e) {
            unavailableReason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            LSSLogger.warn("JourneyMap map ingestion: this JourneyMap version has a different internal surface"
                    + " — map ingestion is off for it:", e);
            return false;
        } catch (Throwable e) {
            unavailableReason = e.getClass().getSimpleName();
            LSSLogger.error("Failed to initialize JourneyMap map ingestion", e);
            return false;
        }
    }

    /** End-of-client-tick body (main client thread; also runs on the title screen). */
    static void clientTick() {
        var session = instance;
        if (session != null) session.pump();
    }

    /** Disconnect body — any thread. */
    static void onDisconnect() {
        var session = instance;
        if (session != null) session.onSessionEnd();
    }

    /** Receive OFF / a replaced request manager. */
    static void retireClientAcquisition() {
        var session = instance;
        if (session != null) session.retireAcquisition();
    }

    /** Resolved — the menu's "a supported map mod is installed" fact. */
    static boolean isSupported() {
        return instance != null;
    }

    /** ARMED = flag ∧ installed ∧ resolved ∧ not dead: JourneyMap can write its store
     *  (the alias corroboration's gate input — its store is keyed per server-list entry). */
    static boolean isArmed() {
        var session = instance;
        return session != null && !session.dead && LSSClientConfig.CONFIG.mapIngestionEnabled();
    }

    /** The conditional {@code /lss diag} line, or null when JourneyMap is not installed. */
    static String diagLine() {
        var session = instance;
        if (session != null) return session.describe();
        if (unsupported) return "JourneyMap: state=unsupported (map ingestion needs JourneyMap 6.0.0 or newer)";
        String reason = unavailableReason;
        return reason != null
                ? "JourneyMap: state=unavailable(" + reason + ") (unrecognized JourneyMap internals — map ingestion off)"
                : null;
    }

    /** The soak/benchmark client snapshot's {@code journeymap} group (live gates). */
    static java.util.Map<String, Object> metrics() {
        var session = instance;
        var out = new java.util.LinkedHashMap<String, Object>();
        if (session == null) {
            out.put("state", unsupported ? "unsupported" : unavailableReason != null ? "unavailable" : "absent");
            return out;
        }
        out.put("state", session.state());
        out.put("queued", session.queuedGauge);
        for (String name : new String[]{"batches", "rendered", "skipped_live", "render_failed", "decode_failures",
                "column_failures", "abandoned", "replayed", "requeued", "dropped_overflow", "dropped_stale",
                "dropped_inert", "drops_reported", "optional_refusals", "edge_sweeps", "edge_rendered"}) {
            out.put(name, session.counterForTest(name));
        }
        out.put("slow_batches", session.slowBatches.get());
        out.put("encode_refused", session.encodeRefused.get());
        out.put("heap_holds", session.heapHolds.get());
        out.put("last_batch_ms", session.lastBatchMillis);
        long columns = session.encodedColumns.get();
        long rendered = session.rendered.get();
        out.put("encode_us_per_column", columns == 0 ? 0L : session.encodeNanos.get() / columns / 1000);
        out.put("render_us_per_column", rendered == 0 ? 0L : session.renderNanos.get() / rendered / 1000);
        out.put("occupancy", session.occupancy);
        return out;
    }

    static ModCompat.JourneyMapStatus cachedStatus(boolean discoveryComplete) {
        var session = instance;
        if (session == null) {
            return new ModCompat.JourneyMapStatus(unsupported || unavailableReason != null ? "unavailable"
                    : discoveryComplete ? "absent" : "unknown", false, 0);
        }
        return new ModCompat.JourneyMapStatus("available", session.dead,
                session.sessionEndPending ? 0 : session.queuedGauge + (session.inFlight != null ? session.inFlight.entries.size() : 0));
    }

    static void resetFacadeForTest() {
        instance = null;
        unsupported = false;
        unavailableReason = null;
    }

    // ---- instance ----

    final JourneyMapBindings h;
    final Host host;
    final BooleanSupplier enabled;
    final BooleanSupplier sessionActive;
    final Consumer<VoxelColumnConsumer> registrar;
    final Consumer<VoxelColumnConsumer> deregistrar;
    final BooleanSupplier backpressureEnabled;
    final XaeroSession.DropReporter dropReporter;
    final IngestBackpressureGovernor governor;
    final VoxelColumnConsumer consumer;
    MapMemoryGuard memory = MapMemoryGuard.NEVER;
    SnapshotDecoder decoder = this::decodeWithBindings;
    boolean registered; // main thread only

    // ---- queue (decode thread offers, main thread drains) ----
    final Object queueLock = new Object();
    final LinkedHashMap<Long, Entry> queue = new LinkedHashMap<>();
    long queuedBytes;
    volatile double occupancy;
    volatile int queuedGauge;
    int maxQueue = MAX_QUEUE;
    long maxQueueBytes = MAX_QUEUE_BYTES;
    final AtomicLong acquisitionGeneration = new AtomicLong();
    final ConcurrentLinkedQueue<Entry> pendingReports = new ConcurrentLinkedQueue<>();

    /** Non-null while offers drop before encoding (inert): the reason, for diag. */
    volatile String inertReason;
    /** Non-null while the pump holds the queue for a transient reason, for diag. */
    volatile String waitingReason = "not_mapping";
    volatile boolean dead;
    volatile boolean sessionEndPending;
    boolean heapHolding; // main thread only
    int consecutiveFailedBatches; // main thread only

    // ---- identity + batches (main thread) ----
    Identity observed;
    long identitySinceMillis;
    long lastIdentityPollMillis = Long.MIN_VALUE;
    Batch inFlight;
    long nextSubmitAfterMillis;
    long renderDelayMillis = DEFAULT_RENDER_DELAY_MILLIS;
    long lastRegionKey = Long.MIN_VALUE;
    final ArrayDeque<Rendered> replay = new ArrayDeque<>();
    /** Recently rendered encoded columns: the next batch's slope/biome-blend neighbours.
     *  Tagged with the map they were rendered into — a batch racing an identity change
     *  must never lend one map's terrain to another as context. */
    final LinkedHashMap<Long, ContextColumn> context = new LinkedHashMap<>(16, 0.75f, true);

    // ---- counters ----
    final AtomicLong batches = new AtomicLong();
    final AtomicLong rendered = new AtomicLong();
    final AtomicLong skippedLive = new AtomicLong();
    final AtomicLong renderFailed = new AtomicLong();
    final AtomicLong decodeFailures = new AtomicLong();
    final AtomicLong columnFailures = new AtomicLong();
    final AtomicLong abandoned = new AtomicLong();
    final AtomicLong replayed = new AtomicLong();
    final AtomicLong requeued = new AtomicLong();
    final AtomicLong slowBatches = new AtomicLong();
    final AtomicLong droppedOverflow = new AtomicLong();
    final AtomicLong droppedStale = new AtomicLong();
    final AtomicLong droppedInert = new AtomicLong();
    final AtomicLong encodeRefused = new AtomicLong();
    final AtomicLong encodeNanos = new AtomicLong();
    final AtomicLong encodedColumns = new AtomicLong();
    final AtomicLong renderNanos = new AtomicLong();
    final AtomicLong dropsReported = new AtomicLong();
    final AtomicLong heapHolds = new AtomicLong();
    /** Optional map types (topography, biome) JourneyMap refused for an accepted column. */
    final AtomicLong optionalRefusals = new AtomicLong();
    final AtomicLong edgeSweeps = new AtomicLong();
    final AtomicLong edgeRendered = new AtomicLong();
    volatile long lastBatchMillis;
    long lastEdgeSweepMillis; // main thread
    /** Live chunks the edge sweep rendered, by position → the ChunkMD instance (JM thread). */
    final java.util.HashMap<Long, Object> edgeDone = new java.util.HashMap<>();
    Identity edgeIdentity; // JM thread

    JourneyMapSession(JourneyMapBindings h, Host host, BooleanSupplier enabled, BooleanSupplier sessionActive,
                      Consumer<VoxelColumnConsumer> registrar, Consumer<VoxelColumnConsumer> deregistrar,
                      BooleanSupplier backpressureEnabled, XaeroSession.DropReporter dropReporter, LongSupplier clock) {
        this.h = h;
        this.host = host;
        this.enabled = enabled;
        this.sessionActive = sessionActive;
        this.registrar = registrar;
        this.deregistrar = deregistrar;
        this.backpressureEnabled = backpressureEnabled;
        this.dropReporter = dropReporter;
        this.governor = new IngestBackpressureGovernor("JourneyMap map ingestion", clock);
        this.consumer = buildConsumer();
    }

    long now() {
        return this.governor.now();
    }

    /** One queued column: its encoded snapshot bytes and its delivery receipt. */
    static final class Entry {
        final ResourceKey<Level> dimension;
        final int chunkX;
        final int chunkZ;
        final byte[] bytes;
        final Receipt receipt;

        Entry(ResourceKey<Level> dimension, int chunkX, int chunkZ, byte[] bytes, Receipt receipt) {
            this.dimension = dimension;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.bytes = bytes;
            this.receipt = receipt;
        }

        long key() { return packed(this.chunkX, this.chunkZ); }
    }

    /** JourneyMap's mapping identity: a change means writes would land elsewhere. */
    record Identity(Object controller, Object level, Object dimension, File worldDir, String worldId) {
        @Override public boolean equals(Object o) {
            return o instanceof Identity i && i.controller == this.controller && i.level == this.level
                    && Objects.equals(i.dimension, this.dimension) && Objects.equals(i.worldDir, this.worldDir)
                    && Objects.equals(i.worldId, this.worldId);
        }

        @Override public int hashCode() { return Objects.hash(this.dimension, this.worldDir, this.worldId); }
    }

    record Rendered(Object dimension, int chunkX, int chunkZ, File worldDir, long atMillis) {}

    record ContextColumn(Object dimension, File worldDir, byte[] bytes) {}

    /** The delivery's acceptance lease (the Xaero {@code Origin} contract): released exactly
     *  once — accepted, reported or discarded. */
    final class Receipt {
        final long generation = acquisitionGeneration.get();
        final LSSApi.IngestFailureHandle handle = LSSApi.captureIngestFailureHandle();
        final Runnable release = this.handle == null ? () -> {} : this.handle.deferAcceptance();
        final AtomicBoolean closed = new AtomicBoolean();

        boolean active() {
            return !this.closed.get() && this.generation == acquisitionGeneration.get()
                    && (this.handle == null || this.handle.isActive());
        }

        void close() {
            if (this.closed.compareAndSet(false, true)) this.release.run();
        }

        /** Report for a bounded re-serve (main thread, no locks held); always closes. */
        void report(Object dimension, int chunkX, int chunkZ) {
            try {
                if (!active()) return;
                if (this.handle != null) this.handle.report();
                else dropReporter.report(dimension, chunkX, chunkZ);
                dropsReported.incrementAndGet();
            } catch (Throwable t) {
                if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
                warn("a drop report threw (contained)", t);
            } finally {
                close();
            }
        }
    }

    static long packed(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /** Minecraft's own chunk-position encoding (x low, z high) — JourneyMap's cache key. */
    static long mcChunkKey(int chunkX, int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((chunkZ & 0xFFFFFFFFL) << 32);
    }

    static long regionKeyOf(int chunkX, int chunkZ) {
        return XaeroSession.regionKeyOf(chunkX, chunkZ);
    }

    // ---- registration ----

    void maybeRegister() {
        if (!this.registered && !this.dead && this.enabled.getAsBoolean()) {
            this.registrar.accept(this.consumer);
            this.registered = true;
        }
    }

    /** Any thread: the thread-safe half; the pump settles the rest. */
    void onSessionEnd() {
        this.acquisitionGeneration.incrementAndGet(); // every outstanding receipt goes inactive
        this.sessionEndPending = true;
    }

    /** A replaced request manager / receive OFF: queued work belongs to an old acquisition. */
    void retireAcquisition() {
        this.acquisitionGeneration.incrementAndGet();
        clearQueue();
    }

    void settleSessionEnd() {
        this.sessionEndPending = false;
        clearQueue();
        var batch = this.inFlight;
        if (batch != null && batch.state.compareAndSet(Batch.QUEUED, Batch.ABANDONED)) {
            batch.entries.forEach(e -> e.receipt.close());
            this.inFlight = null;
        }
        this.observed = null;
        this.waitingReason = "not_mapping";
        this.inertReason = null;
        this.governor.reset();
        this.heapHolding = false;
        this.dead = false; // the death latch is session-scoped
        this.consecutiveFailedBatches = 0;
        this.lastRegionKey = Long.MIN_VALUE;
        this.replay.clear();
        synchronized (this.context) {
            this.context.clear();
        }
        if (this.registered && !this.enabled.getAsBoolean()) {
            this.deregistrar.accept(this.consumer);
            this.registered = false;
        } else {
            maybeRegister();
        }
    }

    // ---- the consumer (decode thread) ----

    VoxelColumnConsumer buildConsumer() {
        // An ANONYMOUS class, never a lambda: a lambda cannot override the default
        // pendingIngestBacklog() and the taper would silently never engage.
        return new VoxelColumnConsumer() {
            @Override
            public int pendingIngestBacklog() {
                return reportBackpressure();
            }

            @Override
            public void onVoxelColumnReceived(ClientLevel level, ResourceKey<Level> dimension,
                                              int chunkX, int chunkZ, VoxelColumnData columnData) {
                try {
                    offerColumn(JourneyMapLineSurface.facts(level), dimension, chunkX, chunkZ, columnData);
                } catch (Throwable t) {
                    // Swallow EVERYTHING: an escape becomes an LSS ingest failure — a re-serve
                    // loop for a map problem (the Xaero bridge's review MAJOR).
                    warn("column encoding failed", t);
                }
            }
        };
    }

    void offerColumn(JourneyMapLineSurface.LevelFacts facts, ResourceKey<Level> dimension,
                     int chunkX, int chunkZ, VoxelColumnData data) {
        var receipt = new Receipt();
        if (!receipt.active() || this.dead || !this.enabled.getAsBoolean() || !this.sessionActive.getAsBoolean()) {
            receipt.close();
            return;
        }
        if (this.inertReason != null) {
            // Inert (JourneyMap disabled, mapping not allowed here, a ceiling dimension):
            // dropped before encoding, silently — re-serving cannot help.
            this.droppedInert.incrementAndGet();
            receipt.close();
            return;
        }
        long start = System.nanoTime();
        byte[] bytes = JourneyMapSnapshotEncoder.encode(chunkX, chunkZ, data, facts);
        this.encodeNanos.addAndGet(System.nanoTime() - start);
        this.encodedColumns.incrementAndGet();
        if (bytes == null) {
            this.encodeRefused.incrementAndGet();
            receipt.close();
            return;
        }
        offerEncoded(new Entry(dimension, chunkX, chunkZ, bytes, receipt));
    }

    /** Latest-wins by position, oldest-evicting within the count and byte bounds. */
    void offerEncoded(Entry entry) {
        var evicted = new ArrayList<Entry>(0);
        synchronized (this.queueLock) {
            var previous = this.queue.remove(entry.key());
            if (previous != null) {
                this.queuedBytes -= previous.bytes.length;
                previous.receipt.close(); // superseded by the newer delivery of the same column
            }
            this.queue.put(entry.key(), entry);
            this.queuedBytes += entry.bytes.length;
            Iterator<Entry> it = this.queue.values().iterator();
            while ((this.queue.size() > this.maxQueue || this.queuedBytes > this.maxQueueBytes) && it.hasNext()) {
                var oldest = it.next();
                it.remove();
                this.queuedBytes -= oldest.bytes.length;
                evicted.add(oldest);
            }
            updateOccupancyLocked();
        }
        for (var e : evicted) {
            this.droppedOverflow.incrementAndGet();
            // The Xaero overflow rule: reported for its re-serve only while governed and not
            // wedged (a wedged stream's report would churn re-serves into the same drop).
            if (this.backpressureEnabled.getAsBoolean() && !this.governor.wedged) this.pendingReports.add(e);
            else e.receipt.close();
        }
    }

    private void updateOccupancyLocked() {
        this.queuedGauge = this.queue.size();
        this.occupancy = Math.max((double) this.queue.size() / this.maxQueue,
                (double) this.queuedBytes / this.maxQueueBytes);
    }

    void clearQueue() {
        List<Entry> cleared;
        synchronized (this.queueLock) {
            cleared = new ArrayList<>(this.queue.values());
            this.queue.clear();
            this.queuedBytes = 0;
            updateOccupancyLocked();
        }
        cleared.forEach(e -> e.receipt.close());
    }

    // ---- backpressure (main thread) ----

    int reportBackpressure() {
        return this.governor.report(this.backpressureEnabled.getAsBoolean(), active(), this.occupancy, this.heapHolding);
    }

    boolean active() {
        return !this.dead && this.enabled.getAsBoolean() && this.sessionActive.getAsBoolean() && this.inertReason == null;
    }

    // ---- the pump (main client thread) ----

    void pump() {
        if (this.sessionEndPending) settleSessionEnd();
        maybeRegister();
        this.governor.notePump();
        try {
            if (this.dead) return;
            if (!this.enabled.getAsBoolean()) {
                clearQueue();
                return;
            }
            pumpInner();
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            warn("pump failed (contained)", t);
        } finally {
            drainPendingReports();
        }
    }

    void pumpInner() throws Throwable {
        reconcileInFlight();
        long now = now();
        Object client = this.h.getInstance.invoke();
        Object level = this.host.level();
        if (client == null || level == null || !this.host.playerInLevel()) {
            this.waitingReason = "no_world";
            return;
        }
        if (!(boolean) this.h.enabled.invoke(client)) {
            inert("journeymap_disabled", null);
            return;
        }
        if (this.host.hasCeiling(level)) {
            inert("ceiling_dimension", this.host.dimension(level));
            return;
        }
        boolean mapping = Boolean.TRUE.equals(this.h.isMapping.invoke(client))
                && (this.h.isMappingSuspended == null || !(boolean) this.h.isMappingSuspended.invoke(client));
        if (!mapping) {
            this.inertReason = null;
            this.waitingReason = "not_mapping";
            this.observed = null;
            return;
        }
        if (this.observed == null || now - this.lastIdentityPollMillis >= IDENTITY_POLL_MILLIS) {
            this.lastIdentityPollMillis = now;
            Object dimension = this.host.dimension(level);
            Object day = this.h.mapTypeDay.invoke(dimension);
            if (!(boolean) this.h.isAllowed.invoke(day)) {
                inert("mapping_not_allowed", dimension);
                return;
            }
            this.inertReason = null;
            var identity = new Identity(this.h.getMultithreadTaskController.invoke(client), level, dimension,
                    (File) this.h.getJMWorldDir.invoke(this.host.minecraft()),
                    (String) this.h.getCurrentWorldId.invoke(client));
            if (!identity.equals(this.observed)) {
                onIdentityChange(this.observed, identity);
                this.observed = identity;
                this.identitySinceMillis = now;
            }
            Object props = this.h.getCoreProperties.invoke(client);
            Object delay = props == null ? null : this.h.integerFieldGet.invoke(this.h.renderDelay.invoke(props));
            this.renderDelayMillis = delay instanceof Integer d && d > 0 ? d : DEFAULT_RENDER_DELAY_MILLIS;
        }
        if (this.observed.worldDir() == null) {
            this.waitingReason = "no_world_dir";
            return;
        }
        if (now - this.identitySinceMillis < SETTLE_MILLIS) {
            this.waitingReason = "settle";
            return;
        }
        this.waitingReason = null;
        boolean heapHalt = this.memory.halted() && this.backpressureEnabled.getAsBoolean();
        this.heapHolding = heapHalt;
        if (heapHalt) {
            this.heapHolds.incrementAndGet();
            return;
        }
        if (this.inFlight != null || now < this.nextSubmitAfterMillis) return;
        Batch batch = null;
        if (now - this.lastEdgeSweepMillis >= EDGE_SWEEP_MILLIS && this.sessionActive.getAsBoolean()) {
            long player = this.host.playerChunk();
            if (player != Long.MIN_VALUE) {
                this.lastEdgeSweepMillis = now;
                batch = new Batch(List.of(), this.observed, this.renderDelayMillis, true, player);
                this.edgeSweeps.incrementAndGet();
            }
        }
        if (batch == null) {
            batch = buildBatch(this.observed);
            if (batch == null) return;
            this.batches.incrementAndGet();
        }
        this.inFlight = batch;
        batch.submittedAtMillis = now;
        synchronized (client) {
            // Under the monitor stopMapping holds: a submit can never race the executor's
            // shutdown into resurrecting it. A null controller is a silent no-op — the
            // start watchdog reclaims the batch.
            this.h.queueOneOff.invoke(client, (Runnable) batch::run);
        }
    }

    /**
     * Enter (or stay in) an inert state. Queued columns of {@code dimension} can never be
     * written and are released silently; with a dimension-scoped reason, columns of OTHER
     * dimensions are reported for their re-serve instead — client stamps persist per
     * dimension, so a silent drop there would leave a permanent hole on the way back.
     */
    private void inert(String reason, Object dimension) {
        if (!reason.equals(this.inertReason)) {
            this.inertReason = reason;
            List<Entry> cleared;
            synchronized (this.queueLock) {
                cleared = new ArrayList<>(this.queue.values());
                this.queue.clear();
                this.queuedBytes = 0;
                updateOccupancyLocked();
            }
            for (var e : cleared) {
                if (dimension != null && !Objects.equals(e.dimension, dimension)) {
                    this.droppedStale.incrementAndGet();
                    this.pendingReports.add(e);
                } else {
                    e.receipt.close();
                }
            }
        }
        this.waitingReason = null;
        this.observed = null;
    }

    /** A late world id (JourneyMap restarted mapping in a new directory): columns rendered
     *  into the abandoned directory in the last 30 s are re-requested for the new one. */
    void onIdentityChange(Identity previous, Identity next) {
        var batch = this.inFlight;
        if (batch != null && batch.state.compareAndSet(Batch.QUEUED, Batch.ABANDONED)) {
            this.abandoned.incrementAndGet();
            batch.entries.forEach(e -> e.receipt.report(e.dimension, e.chunkX, e.chunkZ));
            this.inFlight = null;
        }
        if (previous != null && previous.worldDir() != null && !previous.worldDir().equals(next.worldDir())) {
            long now = now();
            for (var r : this.replay) {
                if (now - r.atMillis() > REPLAY_WINDOW_MILLIS || !r.worldDir().equals(previous.worldDir())) continue;
                this.dropReporter.report(r.dimension(), r.chunkX(), r.chunkZ());
                this.replayed.incrementAndGet();
            }
            this.replay.clear();
        }
        synchronized (this.context) {
            this.context.clear();
        }
        // Columns of another dimension can never be written here: report them for the return.
        if (previous == null || !Objects.equals(previous.dimension(), next.dimension())) {
            var foreign = new ArrayList<Entry>();
            synchronized (this.queueLock) {
                var it = this.queue.values().iterator();
                while (it.hasNext()) {
                    var e = it.next();
                    if (!Objects.equals(e.dimension, next.dimension())) {
                        it.remove();
                        this.queuedBytes -= e.bytes.length;
                        foreign.add(e);
                    }
                }
                updateOccupancyLocked();
            }
            this.droppedStale.addAndGet(foreign.size());
            this.pendingReports.addAll(foreign);
        }
    }

    /** Up to {@value #MAX_BATCH_COLUMNS} columns of ONE region: the region just worked on while
     *  it still has columns, else the region holding the oldest entry (no region starves). */
    Batch buildBatch(Identity identity) {
        var picked = new ArrayList<Entry>();
        var foreign = new ArrayList<Entry>();
        synchronized (this.queueLock) {
            if (this.queue.isEmpty()) return null;
            long region = Long.MIN_VALUE;
            if (this.lastRegionKey != Long.MIN_VALUE) {
                for (var e : this.queue.values()) {
                    if (Objects.equals(e.dimension, identity.dimension()) && regionKeyOf(e.chunkX, e.chunkZ) == this.lastRegionKey) {
                        region = this.lastRegionKey;
                        break;
                    }
                }
            }
            var it = this.queue.values().iterator();
            while (it.hasNext() && picked.size() < MAX_BATCH_COLUMNS) {
                var e = it.next();
                if (!Objects.equals(e.dimension, identity.dimension())) {
                    it.remove();
                    this.queuedBytes -= e.bytes.length;
                    foreign.add(e);
                    continue;
                }
                long r = regionKeyOf(e.chunkX, e.chunkZ);
                if (region == Long.MIN_VALUE) region = r;
                if (r != region) continue;
                it.remove();
                this.queuedBytes -= e.bytes.length;
                picked.add(e);
            }
            updateOccupancyLocked();
            this.lastRegionKey = region;
        }
        this.droppedStale.addAndGet(foreign.size());
        this.pendingReports.addAll(foreign);
        return picked.isEmpty() ? null : new Batch(picked, identity, this.renderDelayMillis);
    }

    void reconcileInFlight() {
        var batch = this.inFlight;
        if (batch == null) return;
        long now = now();
        int state = batch.state.get();
        if (state == Batch.QUEUED) {
            if (now - batch.submittedAtMillis > START_TIMEOUT_MILLIS
                    && batch.state.compareAndSet(Batch.QUEUED, Batch.ABANDONED)) {
                // Never started: JourneyMap's task controller was replaced or shut down.
                this.abandoned.incrementAndGet();
                batch.entries.forEach(e -> e.receipt.report(e.dimension, e.chunkX, e.chunkZ));
                this.inFlight = null;
            }
            return;
        }
        if (state == Batch.STARTED) {
            if (!batch.slowCounted && now - batch.startedAtMillis > SLOW_BATCH_MILLIS) {
                batch.slowCounted = true;
                this.slowBatches.incrementAndGet();
            }
            return;
        }
        if (state != Batch.DONE) return;
        this.inFlight = null;
        applyResult(batch, batch.result);
        long wall = Math.max(0, batch.finishedAtMillis - batch.startedAtMillis);
        this.lastBatchMillis = wall;
        this.nextSubmitAfterMillis = now + Math.max(this.renderDelayMillis, wall);
    }

    void applyResult(Batch batch, Result r) {
        if (r != null) {
            this.optionalRefusals.addAndGet(r.optionalRefusals);
            this.edgeRendered.addAndGet(r.edgeRendered);
        }
        if (batch.edge) {
            // No receipts ride an edge sweep: only a throw counts, toward the death latch.
            if (r == null || r.failure != null) noteBatchFailure(r == null ? null : r.failure);
            return;
        }
        if (r == null || r.failure != null || r.abandoned) {
            if (r != null && r.abandoned) this.abandoned.incrementAndGet();
            // A throw mid-batch or an identity change: nothing is known-good — re-serve all.
            batch.entries.forEach(e -> e.receipt.report(e.dimension, e.chunkX, e.chunkZ));
            if (r == null || r.failure != null) noteBatchFailure(r == null ? null : r.failure);
            return;
        }
        if (r.notAllowed) {
            batch.entries.forEach(e -> e.receipt.close());
            this.inertReason = "mapping_not_allowed";
            return;
        }
        long now = now();
        for (var e : r.rendered) {
            e.receipt.close();
            if (this.replay.size() >= REPLAY_CAPACITY) this.replay.removeFirst();
            this.replay.addLast(new Rendered(e.dimension, e.chunkX, e.chunkZ, batch.identity.worldDir(), now));
        }
        this.rendered.addAndGet(r.rendered.size());
        r.skippedLive.forEach(e -> e.receipt.close()); // JourneyMap renders live chunks itself
        this.skippedLive.addAndGet(r.skippedLive.size());
        r.renderFailed.forEach(e -> e.receipt.report(e.dimension, e.chunkX, e.chunkZ));
        this.renderFailed.addAndGet(r.renderFailed.size());
        r.columnFailures.forEach(e -> e.receipt.report(e.dimension, e.chunkX, e.chunkZ));
        this.columnFailures.addAndGet(r.columnFailures.size());
        // A decode failure is a format mismatch: a re-serve would fail identically.
        r.decodeFailures.forEach(e -> e.receipt.close());
        this.decodeFailures.addAndGet(r.decodeFailures.size());
        this.renderNanos.addAndGet(r.renderNanos);
        requeue(r.requeued);
        boolean nothingDecoded = !r.decodeFailures.isEmpty() && r.rendered.isEmpty() && r.renderFailed.isEmpty();
        if (nothingDecoded) noteBatchFailure(null);
        else this.consecutiveFailedBatches = 0;
    }

    /** Time-boxed remainder: back into the queue unless a newer delivery replaced it. */
    void requeue(List<Entry> entries) {
        if (entries.isEmpty()) return;
        this.requeued.addAndGet(entries.size());
        synchronized (this.queueLock) {
            for (var e : entries) {
                if (!e.receipt.active() || this.queue.containsKey(e.key())) {
                    e.receipt.close();
                    continue;
                }
                this.queue.put(e.key(), e);
                this.queuedBytes += e.bytes.length;
            }
            updateOccupancyLocked();
        }
    }

    void noteBatchFailure(Throwable failure) {
        if (failure != null) warn("a map batch failed", failure);
        if (++this.consecutiveFailedBatches >= DEATH_LATCH && !this.dead) {
            this.dead = true;
            clearQueue();
            LSSLogger.error("JourneyMap map ingestion: " + DEATH_LATCH + " consecutive failed map batches —"
                    + " disabling it for this session (LODs are unaffected)", failure);
        }
    }

    void drainPendingReports() {
        Entry e;
        while ((e = this.pendingReports.poll()) != null) e.receipt.report(e.dimension, e.chunkX, e.chunkZ);
    }

    // ---- the batch (JourneyMap's task thread) ----

    static final class Result {
        final List<Entry> rendered = new ArrayList<>();
        final List<Entry> skippedLive = new ArrayList<>();
        final List<Entry> renderFailed = new ArrayList<>();
        final List<Entry> decodeFailures = new ArrayList<>();
        final List<Entry> columnFailures = new ArrayList<>();
        final List<Entry> requeued = new ArrayList<>();
        boolean abandoned;
        boolean notAllowed;
        Throwable failure;
        long renderNanos;
        long optionalRefusals;
        long edgeRendered;
    }

    final class Batch {
        static final int QUEUED = 0, STARTED = 1, DONE = 2, ABANDONED = 3;
        final List<Entry> entries;
        final Identity identity;
        final long timeBoxMillis;
        /** An edge sweep (no entries): renders JourneyMap's own live chunks past its range. */
        final boolean edge;
        final long playerChunk;
        final AtomicInteger state = new AtomicInteger(QUEUED);
        long submittedAtMillis; // main thread
        volatile long startedAtMillis;
        volatile long finishedAtMillis;
        volatile Result result;
        boolean slowCounted; // main thread

        Batch(List<Entry> entries, Identity identity, long timeBoxMillis) {
            this(entries, identity, timeBoxMillis, false, 0);
        }

        Batch(List<Entry> entries, Identity identity, long timeBoxMillis, boolean edge, long playerChunk) {
            this.entries = entries;
            this.identity = identity;
            this.timeBoxMillis = timeBoxMillis;
            this.edge = edge;
            this.playerChunk = playerChunk;
        }

        /** JourneyMap's task thread: always ends in DONE with a result (a lost slot wedges). */
        void run() {
            if (!this.state.compareAndSet(QUEUED, STARTED)) return;
            this.startedAtMillis = now();
            var result = new Result();
            Error fatal = null;
            try {
                render(this, result);
            } catch (Throwable t) {
                result.failure = t;
                if (t instanceof Error err && !(t instanceof AssertionError)) fatal = err;
            } finally {
                this.finishedAtMillis = now();
                this.result = result;
                this.state.set(DONE);
            }
            if (fatal != null) throw fatal;
        }
    }

    boolean identityStillMatches(Identity identity) throws Throwable {
        Object client = this.h.getInstance.invoke();
        if (client == null || !Boolean.TRUE.equals(this.h.isMapping.invoke(client))) return false;
        if (this.h.getMultithreadTaskController.invoke(client) != identity.controller()) return false;
        Object level = this.host.level();
        if (level != identity.level() || !Objects.equals(this.host.dimension(level), identity.dimension())) return false;
        Object dir = this.h.getJMWorldDir.invoke(this.host.minecraft());
        return dir != null && dir.equals(identity.worldDir());
    }

    /** The map types to render, day first; null when the day map is not allowed here. */
    private List<Object> mapTypes(Object client, Object dimension) throws Throwable {
        Object day = this.h.mapTypeDay.invoke(dimension);
        if (!(boolean) this.h.isAllowed.invoke(day)) return null;
        Object props = this.h.getCoreProperties.invoke(client); // replaced on every startMapping
        var types = new ArrayList<Object>(3);
        types.add(day); // the surface renderer draws day and night together
        if (props != null && Boolean.TRUE.equals(this.h.booleanFieldGet.invoke(this.h.mapTopography.invoke(props)))) {
            Object topo = this.h.mapTypeTopo.invoke(dimension);
            if ((boolean) this.h.isAllowed.invoke(topo)) types.add(topo);
        }
        if (props != null && Boolean.TRUE.equals(this.h.booleanFieldGet.invoke(this.h.mapBiome.invoke(props)))) {
            Object biome = this.h.mapTypeBiome.invoke(dimension);
            if ((boolean) this.h.isAllowed.invoke(biome)) types.add(biome);
        }
        return types;
    }

    /**
     * Renders one chunk into every map type. The DAY map decides: it is what the column is
     * accepted or re-served on. Topography and biome are best effort, exactly as in
     * JourneyMap's own pipeline, which ignores their results — its topography renderer
     * refuses every chunk until something loads its palettes (live smoke), so requiring them
     * turned every column into a re-serve.
     */
    private boolean renderTypes(Object controller, Identity id, List<Object> types, int chunkX, int chunkZ,
                                Object md, Result r) throws Throwable {
        Object day = types.getFirst();
        if (!(boolean) this.h.renderChunk.invoke(controller,
                this.h.fromChunkPos.invoke(id.worldDir(), day, chunkX, chunkZ), day, md)) return false;
        for (int t = 1; t < types.size(); t++) {
            Object type = types.get(t);
            if (!(boolean) this.h.renderChunk.invoke(controller,
                    this.h.fromChunkPos.invoke(id.worldDir(), type, chunkX, chunkZ), type, md)) r.optionalRefusals++;
        }
        return true;
    }

    void render(Batch batch, Result r) throws Throwable {
        Identity id = batch.identity;
        if (!identityStillMatches(id)) {
            r.abandoned = true;
            return;
        }
        Object client = this.h.getInstance.invoke();
        Object controller = this.h.getChunkRenderController.invoke(client);
        var types = mapTypes(client, id.dimension());
        if (types == null) {
            r.notAllowed = true;
            return;
        }
        if (batch.edge) {
            renderEdge(batch, r, client, controller, types);
            return;
        }
        Object dataCache = this.h.dataCache.invoke();
        Object allowCached = this.h.allowCached.invoke();

        long deadline = batch.startedAtMillis + batch.timeBoxMillis;
        // forceRetain=true: each SnapshotChunkMD holds its LevelChunk strongly until it
        // leaves the cache, so the render never meets a collected chunk.
        var mds = new ArrayList<Object>();
        var mdEntries = new ArrayList<Entry>();
        var batchKeys = new java.util.HashSet<Long>();
        for (var e : batch.entries) batchKeys.add(e.key());
        for (var e : batch.entries) {
            if (!this.sessionActive.getAsBoolean()) {
                r.requeued.add(e); // the session ended: requeue closes the inactive receipt
                continue;
            }
            try {
                Object live = this.h.getChunkMD.invoke(dataCache, mcChunkKey(e.chunkX, e.chunkZ));
                if (live != null && (boolean) this.h.hasLevelChunk.invoke(live)) {
                    r.skippedLive.add(e);
                    continue;
                }
                Object md = this.decoder.decode(e.bytes, id.level());
                if (md == null) {
                    r.decodeFailures.add(e);
                    continue;
                }
                mds.add(md);
                mdEntries.add(e);
            } catch (Throwable t) {
                if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
                r.columnFailures.add(e);
            }
        }
        // Slope shading and biome blending read the north, west and north-west neighbours:
        // recently rendered columns outside this batch join as context, never rendered.
        var contextMds = new ArrayList<Object>();
        var contextMcKeys = new ArrayList<Long>();
        var contextKeys = new java.util.HashSet<Long>();
        for (var e : mdEntries) {
            for (int[] d : new int[][]{{0, -1}, {-1, -1}, {-1, 0}}) {
                int x = e.chunkX + d[0], z = e.chunkZ + d[1];
                long key = packed(x, z);
                if (batchKeys.contains(key) || !contextKeys.add(key)) continue;
                ContextColumn column;
                synchronized (this.context) {
                    column = this.context.get(key);
                }
                if (column == null || !Objects.equals(column.dimension(), id.dimension())
                        || !Objects.equals(column.worldDir(), id.worldDir())) continue;
                byte[] bytes = column.bytes();
                try {
                    Object live = this.h.getChunkMD.invoke(dataCache, mcChunkKey(x, z));
                    if (live != null && (boolean) this.h.hasLevelChunk.invoke(live)) continue;
                    Object md = this.decoder.decode(bytes, id.level());
                    if (md != null) {
                        contextMds.add(md);
                        contextMcKeys.add(mcChunkKey(x, z));
                    }
                } catch (Throwable t) {
                    if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
                }
            }
        }
        for (Object md : mds) this.h.addChunkMD.invoke(dataCache, md);
        for (Object md : contextMds) this.h.addChunkMD.invoke(dataCache, md);
        long renderStart = System.nanoTime();
        try {
            for (int i = 0; i < mds.size(); i++) {
                Object md = mds.get(i);
                Entry e = mdEntries.get(i);
                if (now() > deadline) {
                    // JourneyMap's own pacing: one renderDelay of work per batch, then the
                    // pump's duty gate idles it at least as long. The rest goes back.
                    r.requeued.add(e);
                    continue;
                }
                long key = mcChunkKey(e.chunkX, e.chunkZ);
                try {
                    if (this.h.getChunkMDWithIntent.invoke(dataCache, key, allowCached) != md) {
                        r.skippedLive.add(e); // a live chunk replaced ours mid-batch: JourneyMap owns it
                        continue;
                    }
                    boolean ok = renderTypes(controller, id, types, e.chunkX, e.chunkZ, md, r);
                    this.h.clearAll.invoke(this.h.getBlockData.invoke(md));
                    (ok ? r.rendered : r.renderFailed).add(e);
                } catch (Throwable t) {
                    if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
                    r.columnFailures.add(e);
                }
            }
        } finally {
            r.renderNanos = System.nanoTime() - renderStart;
            for (int i = 0; i < mds.size(); i++) {
                var e = mdEntries.get(i);
                removeIfOurs(dataCache, allowCached, mcChunkKey(e.chunkX, e.chunkZ), mds.get(i));
            }
            for (int i = 0; i < contextMds.size(); i++) {
                removeIfOurs(dataCache, allowCached, contextMcKeys.get(i), contextMds.get(i));
            }
        }
        this.h.updateTextures.invoke(this.h.regionImageCache.invoke(), false, true);
        if (!identityStillMatches(id)) {
            // Rendered under an identity that ended mid-batch (a dimension switch, a late
            // world id): those pixels may have gone to the wrong map — re-serve them all.
            r.abandoned = true;
            return;
        }
        synchronized (this.context) {
            for (var e : r.rendered) {
                this.context.put(e.key(), new ContextColumn(id.dimension(), id.worldDir(), e.bytes));
                if (this.context.size() > CONTEXT_CAPACITY) {
                    var it = this.context.keySet().iterator();
                    it.next();
                    it.remove();
                }
            }
        }
    }

    /**
     * The edge sweep: JourneyMap maps only chunks within its own render range (about the
     * client's render distance minus one), while vanilla sends — and LSS therefore leaves to
     * vanilla — a ring a few chunks wider. A stationary player would see a permanent unmapped
     * ring between JourneyMap's area and the LOD disc (live smoke). Every
     * {@value #EDGE_SWEEP_MILLIS} ms, while an LSS session is active, this renders
     * JourneyMap's OWN live chunks in the {@value #EDGE_BAND_CHUNKS}-chunk band past its range
     * — its data, its renderer, its thread — each live chunk instance once.
     */
    void renderEdge(Batch batch, Result r, Object client, Object controller, List<Object> types) throws Throwable {
        Identity id = batch.identity;
        if (!id.equals(this.edgeIdentity)) {
            this.edgeDone.clear();
            this.edgeIdentity = id;
        }
        int range = (int) this.h.getGameRenderDistance.invoke(client);
        int px = (int) (batch.playerChunk >> 32);
        int pz = (int) batch.playerChunk;
        int reach = range + EDGE_BAND_CHUNKS;
        double inside = range + 0.5; // JourneyMap's own circle test (RenderSpec.inRange)
        Object dataCache = this.h.dataCache.invoke();
        long deadline = batch.startedAtMillis + batch.timeBoxMillis;
        var keep = new java.util.HashMap<Long, Object>();
        for (int dz = -reach; dz <= reach; dz++) {
            for (int dx = -reach; dx <= reach; dx++) {
                if (Math.sqrt(dx * dx + dz * dz) <= inside) continue;
                int x = px + dx, z = pz + dz;
                long key = packed(x, z);
                Object live = this.h.getChunkMD.invoke(dataCache, mcChunkKey(x, z));
                if (live == null || !(boolean) this.h.hasLevelChunk.invoke(live)) continue;
                if (this.edgeDone.get(key) == live) {
                    keep.put(key, live);
                    continue;
                }
                if (now() > deadline) continue; // the next sweep picks it up
                try {
                    boolean ok = renderTypes(controller, id, types, x, z, live, r);
                    this.h.clearAll.invoke(this.h.getBlockData.invoke(live));
                    if (ok) {
                        keep.put(key, live);
                        r.edgeRendered++;
                    }
                } catch (Throwable t) {
                    if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
                    // a vanilla chunk JourneyMap cannot render now; the next sweep retries
                }
            }
        }
        this.edgeDone.clear();
        this.edgeDone.putAll(keep);
        if (r.edgeRendered > 0) this.h.updateTextures.invoke(this.h.regionImageCache.invoke(), false, true);
        if (!identityStillMatches(id)) {
            this.edgeDone.clear(); // rendered into a map that just ended: redo under the new one
            r.abandoned = true;
        }
    }

    private Object decodeWithBindings(byte[] bytes, Object level) throws Throwable {
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            Object snapshot = this.h.decode.invoke(buf, level);
            return snapshot == null ? null : this.h.newSnapshotChunkMD.invoke(level, snapshot, true);
        } finally {
            buf.release();
        }
    }

    /** Removes our snapshot wrapper unless a live chunk replaced it (JourneyMap's to keep). */
    private void removeIfOurs(Object dataCache, Object allowCached, long mcKey, Object md) {
        try {
            Object current = this.h.getChunkMDWithIntent.invoke(dataCache, mcKey, allowCached);
            if (current == md) this.h.removeChunkMD.invoke(dataCache, md);
        } catch (Throwable t) {
            if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
        }
    }

    // ---- diagnostics ----

    String state() {
        if (this.dead) return "dead";
        if (!this.enabled.getAsBoolean()) return "disabled";
        if (this.inertReason != null) return "inert(" + this.inertReason + ")";
        String waiting = this.waitingReason;
        return waiting != null ? "waiting(" + waiting + ")" : "active";
    }

    String describe() {
        long columns = this.encodedColumns.get();
        long rendered = this.rendered.get();
        int contextSize;
        synchronized (this.context) {
            contextSize = this.context.size();
        }
        return "JourneyMap: state=" + state()
                + ", queued=" + this.queuedGauge
                + ", batches=" + this.batches.get()
                + ", rendered=" + rendered
                + ", skipped_live=" + this.skippedLive.get()
                + ", render_failed=" + this.renderFailed.get()
                + ", optional_refusals=" + this.optionalRefusals.get()
                + ", edge_rendered=" + this.edgeRendered.get()
                + ", decode_failures=" + this.decodeFailures.get()
                + ", column_failures=" + this.columnFailures.get()
                + ", abandoned=" + this.abandoned.get()
                + ", replayed=" + this.replayed.get()
                + ", requeued=" + this.requeued.get()
                + ", slow_batches=" + this.slowBatches.get()
                + ", dropped_overflow=" + this.droppedOverflow.get()
                + ", dropped_stale=" + this.droppedStale.get()
                + ", dropped_inert=" + this.droppedInert.get()
                + ", encode_refused=" + this.encodeRefused.get()
                + ", drops_reported=" + this.dropsReported.get()
                + ", context=" + contextSize
                + ", last_batch_ms=" + this.lastBatchMillis
                + ", encode_us_per_column=" + (columns == 0 ? 0 : this.encodeNanos.get() / columns / 1000)
                + ", render_us_per_column=" + (rendered == 0 ? 0 : this.renderNanos.get() / rendered / 1000)
                + ", bp=" + this.governor.token(this.backpressureEnabled.getAsBoolean(), active(), this.occupancy)
                + ", heap=" + this.memory.token()
                + (this.h.version != null ? ", version=" + this.h.version : "");
    }

    long counterForTest(String name) {
        return switch (name) {
            case "batches" -> this.batches.get();
            case "rendered" -> this.rendered.get();
            case "skipped_live" -> this.skippedLive.get();
            case "render_failed" -> this.renderFailed.get();
            case "decode_failures" -> this.decodeFailures.get();
            case "column_failures" -> this.columnFailures.get();
            case "abandoned" -> this.abandoned.get();
            case "replayed" -> this.replayed.get();
            case "requeued" -> this.requeued.get();
            case "dropped_overflow" -> this.droppedOverflow.get();
            case "dropped_stale" -> this.droppedStale.get();
            case "dropped_inert" -> this.droppedInert.get();
            case "drops_reported" -> this.dropsReported.get();
            case "optional_refusals" -> this.optionalRefusals.get();
            case "edge_sweeps" -> this.edgeSweeps.get();
            case "edge_rendered" -> this.edgeRendered.get();
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static void warn(String what, Throwable t) {
        long n = FAILURE_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
        if (n > 0) LSSLogger.warn("JourneyMap map ingestion: " + what + " (" + n + " since the last report)", t);
    }
}
