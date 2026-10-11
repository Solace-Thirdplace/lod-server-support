package dev.vox.lss.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vox.lss.api.VoxelColumnConsumer;
import dev.vox.lss.networking.client.LodRequestManager;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import journeymap.client.JourneymapClient;
import journeymap.client.cartography.ChunkRenderController;
import journeymap.client.data.DataCache;
import journeymap.client.io.FileHandler;
import journeymap.client.model.chunk.ChunkMD;
import journeymap.client.model.chunk.SnapshotChunkMD;
import journeymap.client.model.map.MapType;
import journeymap.client.model.region.RegionImageCache;
import journeymap.client.task.multi.TaskController;
import journeymap.common.chunk.JMChunkSnapshot;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The JourneyMap map-ingestion session (map-ingestion plan §3.4-§3.8) against the
 * real-package stubs: identity settle, one-region batches on JourneyMap's task thread,
 * the receipt doctrine (accepted only when every requested map type rendered under an
 * unchanged identity; everything else reported or requeued, never silently lost), the
 * inert states that never hold the stream, the late-world-id replay, the watchdog, the
 * death latch, and the backpressure report.
 */
class JourneyMapSessionTest {
    private static final ResourceKey<Level> OVERWORLD = Level.OVERWORLD;
    private static final ResourceKey<Level> END = Level.END;
    private static final ResourceKey<Level> NETHER = Level.NETHER;

    private final Object levelMarker = new Object();
    private Object level;
    private ResourceKey<Level> dimension;
    private boolean ceiling;
    private boolean enabled;
    private boolean sessionActive;
    private boolean backpressure;
    private boolean failDecode;
    private long clock;
    private final List<long[]> reports = new ArrayList<>();
    private final List<VoxelColumnConsumer> registered = new ArrayList<>();
    private JourneymapClient jm;
    private JourneyMapSession session;

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() throws Exception {
        this.jm = new JourneymapClient();
        JourneymapClient.INSTANCE = this.jm;
        DataCache.INSTANCE = new DataCache();
        RegionImageCache.INSTANCE = new RegionImageCache();
        MapType.DENIED.clear();
        FileHandler.worldDir = new File("jm-test/mp/Server_A");
        this.level = this.levelMarker;
        this.dimension = OVERWORLD;
        this.ceiling = false;
        this.enabled = true;
        this.sessionActive = true;
        this.backpressure = true;
        this.failDecode = false;
        this.clock = 1_000_000L;
        this.reports.clear();
        this.registered.clear();
        var host = new JourneyMapSession.Host() {
            @Override public Object minecraft() { return null; }
            @Override public Object level() { return level; }
            @Override public boolean playerInLevel() { return level != null; }
            @Override public Object dimension(Object l) { return dimension; }
            @Override public boolean hasCeiling(Object l) { return ceiling; }
        };
        this.session = new JourneyMapSession(JourneyMapBindings.resolve(Class::forName), host,
                () -> this.enabled, () -> this.sessionActive, this.registered::add, this.registered::remove,
                () -> this.backpressure,
                (dim, x, z) -> this.reports.add(new long[]{dimIndex(dim), x, z}),
                () -> this.clock);
        this.session.decoder = (bytes, l) -> {
            if (this.failDecode) return null;
            var buf = ByteBuffer.wrap(bytes);
            return new SnapshotChunkMD(null, new JMChunkSnapshot(buf.getInt(), buf.getInt()), true);
        };
        this.session.maybeRegister();
    }

    @AfterEach
    void tearDown() {
        synchronized (this.session.queueLock) {
            assertEquals(this.session.queue.size(), this.session.queuedGauge,
                    "the diag queue gauge must match membership after every scenario");
        }
        JourneyMapSession.resetFacadeForTest();
    }

    private static long dimIndex(Object dim) {
        return dim == OVERWORLD ? 0 : dim == END ? 1 : dim == NETHER ? 2 : -1;
    }

    // ---- helpers ----

    private static byte[] frame(int x, int z) {
        return ByteBuffer.allocate(64).putInt(x).putInt(z).array();
    }

    private void offer(ResourceKey<Level> dim, int x, int z) {
        this.session.offerEncoded(new JourneyMapSession.Entry(dim, x, z, frame(x, z), this.session.new Receipt()));
    }

    private void offer(int x, int z) {
        offer(OVERWORLD, x, z);
    }

    /** Through the consumer-side gate; the gate drops before the (never-reached) encoder. */
    private void gatedOffer(int x, int z) {
        this.session.offerColumn(null, OVERWORLD, x, z, null);
    }

    /** Observe the identity, then wait out the settle window: the next pump may submit. */
    private void settle() {
        this.session.pump();
        assertEquals("waiting(settle)", this.session.state());
        this.clock += JourneyMapSession.SETTLE_MILLIS + 1;
    }

    /** Pump (submit), run the queued batch on the "task thread", pump again (reconcile). */
    private int cycle() {
        this.session.pump();
        int ran = this.jm.runQueued();
        this.session.pump();
        return ran;
    }

    private long counter(String name) {
        return this.session.counterForTest(name);
    }

    private List<ChunkRenderController.Render> renders() {
        return this.jm.renderController.renders;
    }

    // ---- the happy path ----

    @Test
    void aSettledIdentityRendersOneRegionBatchAndAcceptsIt() {
        offer(0, 0);
        offer(1, 0);
        offer(0, 1);
        offer(40, 0); // region (1, 0)
        this.session.pump();
        assertEquals(0, this.jm.queued.size(), "nothing is written before the identity settles");
        this.clock += JourneyMapSession.SETTLE_MILLIS + 1;
        assertEquals(1, cycle());
        assertEquals(9, renders().size(), "day + topo + biome for each of region (0,0)'s three columns");
        assertTrue(renders().stream().allMatch(r -> r.region().regionX == 0 && r.region().regionZ == 0));
        assertEquals(FileHandler.worldDir, renders().getFirst().region().worldDir);
        assertEquals(3, counter("rendered"));
        assertEquals(1, RegionImageCache.INSTANCE.updates);
        assertEquals(3, DataCache.INSTANCE.added.size());
        assertTrue(DataCache.INSTANCE.cache.isEmpty(), "every snapshot wrapper leaves JourneyMap's cache");
        assertTrue(this.reports.isEmpty());
        assertEquals(1, this.session.queuedGauge);
        assertEquals(3, this.session.replay.size());
        assertTrue(renders().stream().allMatch(r -> ((SnapshotChunkMD) r.chunk()).forceRetain),
                "the wrapper retains its chunk strongly for the whole render");
        assertEquals(3, renders().stream().map(ChunkRenderController.Render::chunk).distinct()
                .mapToInt(md -> md.getBlockData().clears).sum(), "block data is cleared once per column");

        this.session.pump();
        assertEquals(0, this.jm.queued.size(), "the duty gate idles at least renderDelay after a batch");
        this.clock += 500;
        assertEquals(1, cycle());
        assertEquals(4, counter("rendered"));
        assertEquals(0, this.session.queuedGauge);
    }

    @Test
    void onlyTheEnabledAndAllowedMapTypesRender() {
        MapType.DENIED.add("topo");
        this.jm.properties.mapBiome.value = false;
        offer(0, 0);
        settle();
        cycle();
        assertEquals(1, renders().size());
        assertEquals("day", renders().getFirst().type().name);
    }

    @Test
    void aDayMapThatIsNotAllowedIsInertAndNeverHoldsTheStream() {
        MapType.DENIED.add("day");
        offer(0, 0);
        this.session.pump();
        assertEquals("inert(mapping_not_allowed)", this.session.state());
        assertEquals(0, this.session.queuedGauge, "same-dimension columns are released");
        assertTrue(this.reports.isEmpty(), "re-serving cannot help a map that is not allowed here");
        assertEquals(-1, this.session.reportBackpressure());
        gatedOffer(1, 1);
        assertEquals(1, counter("dropped_inert"), "dropped before encoding");
        assertEquals(0, this.session.queuedGauge);
    }

    // ---- live chunks belong to JourneyMap ----

    @Test
    void aLiveChunkIsSkippedAndAcceptedNotReported() {
        long key = JourneyMapSession.mcChunkKey(1, 1);
        DataCache.INSTANCE.live.put(key, new ChunkMD(1, 1, true));
        offer(1, 1);
        offer(2, 2);
        settle();
        cycle();
        assertEquals(1, counter("skipped_live"));
        assertEquals(1, counter("rendered"));
        assertTrue(this.reports.isEmpty());
        assertTrue(renders().stream().noneMatch(r -> r.chunk().chunkX == 1 && r.chunk().chunkZ == 1));
    }

    @Test
    void aLiveChunkArrivingMidBatchWinsAndKeepsItsCacheEntry() {
        long key = JourneyMapSession.mcChunkKey(3, 3);
        var live = new ChunkMD(3, 3, true);
        DataCache.INSTANCE.onAllowCachedQuery = () -> DataCache.INSTANCE.live.put(key, live);
        offer(3, 3);
        settle();
        cycle();
        assertEquals(1, counter("skipped_live"));
        assertEquals(0, counter("rendered"));
        assertEquals(live, DataCache.INSTANCE.cache.get(key), "removal touches only our own wrapper");
    }

    // ---- failures are reported, never silently lost ----

    @Test
    void aFailedMapTypeReportsTheColumn() {
        this.jm.renderController.result = (type, md) -> !type.name.equals("biome");
        offer(0, 0);
        offer(1, 0);
        settle();
        cycle();
        assertEquals(0, counter("rendered"));
        assertEquals(2, counter("render_failed"));
        assertEquals(2, this.reports.size(), "every requested type must render or the column re-serves");
    }

    @Test
    void aThrowingColumnIsReportedAndTheBatchCompletes() {
        this.jm.renderController.throwing = new IllegalStateException("render bug");
        offer(0, 0);
        settle();
        cycle();
        assertEquals(1, counter("column_failures"));
        assertEquals(1, this.reports.size());
        assertNull(this.session.inFlight, "the batch still reached its terminal state");
        assertTrue(DataCache.INSTANCE.cache.isEmpty());
    }

    @Test
    void decodeFailuresCloseUnreportedAndFiveFailedBatchesKillTheSession() {
        this.failDecode = true;
        settle();
        for (int i = 0; i < JourneyMapSession.DEATH_LATCH; i++) {
            offer(i, 0);
            assertEquals(1, cycle());
            this.clock += 1000;
        }
        assertEquals(JourneyMapSession.DEATH_LATCH, counter("decode_failures"));
        assertTrue(this.reports.isEmpty(), "a format mismatch would fail identically on a re-serve");
        assertEquals("dead", this.session.state());
        assertEquals(-1, this.session.reportBackpressure());
        gatedOffer(9, 9);
        assertEquals(0, this.session.queuedGauge, "a dead session drops offers");
        assertEquals(0, this.session.encodedColumns.get());
    }

    // ---- identity ----

    @Test
    void anIdentityChangeWhileQueuedAbandonsAndReports() {
        offer(0, 0);
        offer(1, 0);
        settle();
        this.session.pump();
        assertEquals(1, this.jm.queued.size());
        this.jm.controller = new TaskController(); // JourneyMap restarted mapping
        this.clock += JourneyMapSession.IDENTITY_POLL_MILLIS;
        this.session.pump();
        assertEquals(1, counter("abandoned"));
        assertEquals(2, this.reports.size());
        assertEquals("waiting(settle)", this.session.state());
        this.jm.runQueued();
        assertEquals(0, renders().size(), "the abandoned runnable loses its start CAS and does nothing");
    }

    @Test
    void aBatchThatNeverStartsIsReclaimedByTheWatchdog() {
        offer(0, 0);
        settle();
        this.session.pump();
        this.jm.queued.clear(); // the controller was replaced: the runnable is gone
        this.clock += JourneyMapSession.START_TIMEOUT_MILLIS + 1;
        this.session.pump();
        assertEquals(1, counter("abandoned"));
        assertEquals(1, this.reports.size());
        assertNull(this.session.inFlight);
    }

    @Test
    void anIdentityChangeMidBatchReportsEvenTheRenderedColumns() {
        this.jm.renderController.result = (type, md) -> {
            FileHandler.worldDir = new File("jm-test/mp/Server_A_late-world-id");
            return true;
        };
        offer(0, 0);
        offer(1, 0);
        settle();
        cycle();
        assertEquals(0, counter("rendered"));
        assertEquals(1, counter("abandoned"));
        assertEquals(2, this.reports.size(), "pixels written under the wrong identity re-serve into the new map");
    }

    @Test
    void aLateWorldIdReplaysTheRecentRendersIntoTheNewDirectory() {
        offer(0, 0);
        offer(1, 0);
        settle();
        cycle();
        assertEquals(2, counter("rendered"));
        assertTrue(this.reports.isEmpty());
        FileHandler.worldDir = new File("jm-test/mp/Server_A_abcdef");
        this.jm.worldId = "abcdef";
        this.clock += JourneyMapSession.IDENTITY_POLL_MILLIS;
        this.session.pump();
        assertEquals(2, counter("replayed"));
        assertEquals(2, this.reports.size());
        assertTrue(this.session.replay.isEmpty());
    }

    @Test
    void renderedColumnsOlderThanTheReplayWindowAreNotReplayed() {
        offer(0, 0);
        settle();
        cycle();
        this.clock += JourneyMapSession.REPLAY_WINDOW_MILLIS + 1;
        FileHandler.worldDir = new File("jm-test/mp/Server_A_abcdef");
        this.session.pump();
        assertEquals(0, counter("replayed"));
        assertTrue(this.reports.isEmpty());
    }

    @Test
    void aDimensionChangeReportsTheOtherDimensionsColumns() {
        offer(0, 0);
        offer(1, 0);
        this.session.pump();
        this.dimension = END;
        this.clock += JourneyMapSession.IDENTITY_POLL_MILLIS;
        this.session.pump();
        assertEquals(2, counter("dropped_stale"));
        assertEquals(2, this.reports.size(), "client stamps persist per dimension: only a report re-serves them");
        assertTrue(this.reports.stream().allMatch(r -> r[0] == 0));
        assertEquals(0, this.session.queuedGauge);
    }

    @Test
    void aCeilingDimensionIsInertAndReportsOtherDimensionsColumns() {
        offer(OVERWORLD, 0, 0);
        offer(NETHER, 5, 5);
        this.dimension = NETHER;
        this.ceiling = true;
        this.session.pump();
        assertEquals("inert(ceiling_dimension)", this.session.state());
        assertEquals(1, this.reports.size(), "the overworld column re-serves; the nether one cannot be written");
        assertEquals(0, this.reports.getFirst()[0]);
        assertEquals(-1, this.session.reportBackpressure());
    }

    @Test
    void journeyMapDisabledIsInertAndDropsOffersBeforeEncoding() {
        offer(0, 0);
        this.jm.enabled = false;
        this.session.pump();
        assertEquals("inert(journeymap_disabled)", this.session.state());
        assertEquals(0, this.session.queuedGauge);
        assertTrue(this.reports.isEmpty());
        gatedOffer(1, 1);
        assertEquals(1, counter("dropped_inert"));
        this.jm.enabled = true;
        this.session.pump();
        assertEquals("waiting(settle)", this.session.state(), "re-enabled: a fresh identity settles");
    }

    @Test
    void notMappingHoldsTheQueueAndKeepsGoverning() {
        this.jm.mapping = Boolean.FALSE;
        offer(0, 0);
        this.session.pump();
        assertEquals("waiting(not_mapping)", this.session.state());
        assertEquals(1, this.session.queuedGauge, "a transient state holds: maps pace downloads");
        assertTrue(this.session.reportBackpressure() >= 0);
        this.jm.mapping = Boolean.TRUE;
        this.jm.suspended = true;
        this.session.pump();
        assertEquals("waiting(not_mapping)", this.session.state(), "a suspended mapper holds too");
    }

    // ---- pacing ----

    @Test
    void theTimeBoxRequeuesTheRemainderForTheNextBatch() {
        this.jm.properties.mapTopography.value = false;
        this.jm.properties.mapBiome.value = false;
        this.jm.properties.renderDelay.value = 100;
        this.jm.renderController.result = (type, md) -> {
            this.clock += 60;
            return true;
        };
        for (int i = 0; i < 5; i++) offer(i, 0);
        settle();
        cycle();
        assertEquals(2, counter("rendered"), "renders at +0 and +60 fit the 100 ms box; +120 does not");
        assertEquals(3, counter("requeued"));
        assertEquals(3, this.session.queuedGauge);
        assertTrue(this.reports.isEmpty(), "requeued, not reported");
        assertTrue(DataCache.INSTANCE.cache.isEmpty());
    }

    @Test
    void aNewerDeliveryReplacesARequeuedColumn() {
        this.jm.properties.renderDelay.value = 100;
        this.jm.renderController.result = (type, md) -> {
            this.clock += 200;
            if (md.chunkX == 0) offer(1, 0); // the newer delivery lands while rendering
            return true;
        };
        offer(0, 0);
        offer(1, 0);
        settle();
        cycle();
        assertEquals(1, this.session.queuedGauge, "one entry per position: the newer delivery wins");
    }

    @Test
    void theHeapBeltHoldsSubmitsAsAPacingHold() {
        this.session.memory = new MapMemoryGuard(() -> 0.95, () -> this.clock);
        offer(0, 0);
        settle();
        this.session.pump();
        assertEquals(0, this.jm.queued.size());
        assertTrue(this.session.heapHolding);
        this.backpressure = false;
        this.session.pump();
        assertEquals(1, this.jm.queued.size(), "the belt rides the backpressure kill switch");
    }

    @Test
    void edgeContextNeighboursJoinTheNextBatchButAreNeverRendered() {
        this.jm.properties.mapTopography.value = false;
        this.jm.properties.mapBiome.value = false;
        offer(0, 0);
        settle();
        cycle();
        DataCache.INSTANCE.added.clear();
        renders().clear();
        this.clock += 500;
        offer(1, 0); // its west neighbour (0,0) is context
        cycle();
        assertEquals(List.of(1), renders().stream().map(r -> r.chunk().chunkX).toList());
        assertTrue(DataCache.INSTANCE.added.contains(JourneyMapSession.mcChunkKey(0, 0)),
                "the neighbour joins the cache for slope shading and biome blending");
        assertTrue(DataCache.INSTANCE.cache.isEmpty(), "and leaves it with the batch");
    }

    @Test
    void contextFromAnotherMapIsNeverLent() {
        offer(0, 0);
        settle();
        cycle();
        FileHandler.worldDir = new File("jm-test/mp/Server_B");
        this.clock += JourneyMapSession.IDENTITY_POLL_MILLIS;
        this.session.pump(); // identity change clears the context
        this.session.context.put(JourneyMapSession.packed(0, 0), new JourneyMapSession.ContextColumn(
                OVERWORLD, new File("jm-test/mp/Server_A"), frame(0, 0))); // a racing batch's late insert
        DataCache.INSTANCE.added.clear();
        offer(1, 0);
        this.clock += JourneyMapSession.SETTLE_MILLIS + 1;
        cycle();
        assertFalse(DataCache.INSTANCE.added.contains(JourneyMapSession.mcChunkKey(0, 0)));
    }

    // ---- queue and backpressure ----

    @Test
    void overflowEvictsTheOldestAndReportsItOnlyWhileGoverned() {
        this.session.maxQueue = 4;
        for (int i = 0; i < 6; i++) offer(i, 0);
        this.session.pump();
        assertEquals(2, counter("dropped_overflow"));
        assertEquals(2, this.reports.size());
        assertEquals(List.of(0L, 1L), this.reports.stream().map(r -> r[1]).toList());
        this.backpressure = false;
        offer(10, 0);
        this.session.pump();
        assertEquals(2, this.reports.size(), "ungoverned overflow stays silent (the Xaero rule)");
    }

    @Test
    void theByteBoundEvictsToo() {
        this.session.maxQueueBytes = 64 * 3;
        for (int i = 0; i < 5; i++) offer(i, 0);
        assertEquals(3, this.session.queuedGauge);
        assertEquals(2, counter("dropped_overflow"));
    }

    @Test
    void theReportScalesOccupancyOntoTheHalt() {
        this.session.maxQueue = 100;
        for (int i = 0; i < 30; i++) offer(i, 0);
        this.session.pump();
        int halt = LodRequestManager.INGEST_BACKLOG_HALT_SECTIONS;
        assertEquals(Math.round(halt * 0.30 / IngestBackpressureGovernor.HALT_OCCUPANCY), this.session.reportBackpressure());
        assertEquals(this.session.reportBackpressure(), this.session.consumer.pendingIngestBacklog());
        this.backpressure = false;
        assertEquals(-1, this.session.reportBackpressure());
        this.backpressure = true;
        this.clock += IngestBackpressureGovernor.PUMP_STALE_MILLIS + 1;
        assertEquals(-1, this.session.reportBackpressure(), "a stale pump has no signal");
    }

    @Test
    void theConsumerOverridesTheBacklogReport() throws Exception {
        assertTrue(this.session.consumer.getClass().isAnonymousClass());
        assertNotNull(this.session.consumer.getClass().getDeclaredMethod("pendingIngestBacklog"),
                "a lambda would silently inherit the no-signal default");
    }

    // ---- lifecycle ----

    @Test
    void sessionEndClearsTheQueueAndAbandonsAQueuedBatchWithoutReports() {
        offer(0, 0);
        settle();
        this.session.pump();
        offer(1, 1);
        this.session.onSessionEnd();
        this.session.pump();
        assertEquals(0, this.session.queuedGauge);
        assertNull(this.session.inFlight);
        assertTrue(this.reports.isEmpty(), "a finished session's deliveries are not re-requested");
        this.jm.runQueued();
        assertEquals(0, renders().size());
    }

    @Test
    void registrationIsAddOnlyMidSessionAndSettlesAtSessionEnd() {
        assertEquals(1, this.registered.size());
        this.enabled = false;
        this.session.pump();
        assertEquals(1, this.registered.size(), "never deregistered mid-session (the capability bit)");
        assertEquals(0, this.session.queuedGauge);
        this.session.onSessionEnd();
        this.session.pump();
        assertTrue(this.registered.isEmpty(), "a disabled backend releases its consumer at session end");
        this.enabled = true;
        this.session.pump();
        assertEquals(1, this.registered.size());
    }

    @Test
    void offersOutsideASessionOrWhileDisabledAreDroppedBeforeEncoding() {
        this.sessionActive = false;
        gatedOffer(0, 0);
        assertEquals(-1, this.session.reportBackpressure());
        this.sessionActive = true;
        this.enabled = false;
        gatedOffer(0, 0);
        assertEquals(0, this.session.queuedGauge);
        assertEquals(0, this.session.encodedColumns.get());
    }

    @Test
    void theDiagLineCarriesTheStateAndCounters() {
        offer(0, 0);
        settle();
        cycle();
        String line = this.session.describe();
        assertTrue(line.startsWith("JourneyMap: state=active, queued=0, batches=1, rendered=1"), line);
        for (String field : List.of("skipped_live=", "render_failed=", "decode_failures=", "abandoned=",
                "replayed=", "requeued=", "slow_batches=", "context=1", "last_batch_ms=", "bp=", "heap=n/a")) {
            assertTrue(line.contains(field), field + " in " + line);
        }
        assertFalse(line.contains("jm-test"), "no paths in the diag line");
    }

    // ---- the static facade ----

    @Test
    void aJourneyMapWithoutTheSnapshotLayerIsUnsupported() {
        assertFalse(JourneyMapSession.initWith(name -> {
            if (name.equals("journeymap.client.model.chunk.SnapshotChunkMD")) throw new ClassNotFoundException(name);
            return Class.forName(name);
        }, JourneyMapSession.PRODUCTION_HOST));
        assertTrue(JourneyMapSession.unsupported);
        assertTrue(JourneyMapSession.diagLine().startsWith("JourneyMap: state=unsupported"));
        assertEquals("unavailable", JourneyMapSession.cachedStatus(true).resolution());
        assertFalse(JourneyMapSession.isSupported());
    }

    @Test
    void aDriftedSurfaceIsUnavailableAndNamesTheMember() {
        assertFalse(JourneyMapSession.initWith(name -> {
            if (name.equals("journeymap.client.io.FileHandler")) throw new ClassNotFoundException(name);
            return Class.forName(name);
        }, JourneyMapSession.PRODUCTION_HOST));
        assertFalse(JourneyMapSession.unsupported);
        assertTrue(JourneyMapSession.diagLine().startsWith("JourneyMap: state=unavailable(journeymap.client.io.FileHandler)"),
                JourneyMapSession.diagLine());
        assertNull(JourneyMapSession.instance);
    }
}
