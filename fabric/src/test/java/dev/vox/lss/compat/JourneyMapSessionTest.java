package dev.vox.lss.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vox.lss.api.VoxelColumnConsumer;
import dev.vox.lss.networking.client.LodRequestManager;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import journeymap.client.JourneymapClient;
import journeymap.client.JourneymapClientWithoutSuspend;
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
 * the receipt doctrine (accepted only when the day map rendered under an unchanged identity;
 * everything else reported or requeued, never silently lost), the edge sweep, the
 * inert states that never hold the stream, the late-world-id replay, the watchdog, the
 * death latch, and the backpressure report.
 */
class JourneyMapSessionTest {
    static {
        // Before the constants below: touching Level initializes registries, which need the
        // bootstrap — run alone (no earlier suite in the JVM) the class failed to initialize.
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static final ResourceKey<Level> OVERWORLD = Level.OVERWORLD;
    private static final ResourceKey<Level> END = Level.END;
    private static final ResourceKey<Level> NETHER = Level.NETHER;

    private final Object levelMarker = new Object();
    private Object level;
    private ResourceKey<Level> dimension;
    private boolean ceiling;
    private boolean enabled;
    private boolean sessionActive;
    /** The connected server allows map writes (map-policy plan A4). */
    private boolean serverAllows;
    private boolean backpressure;
    private boolean failDecode;
    /** The host's level read throws (JourneyMap or Minecraft internals drifting at call time). */
    private boolean levelThrows;
    private long clock;
    /** Long.MIN_VALUE (no player) keeps edge sweeps off in the tests that are not about them. */
    private long playerChunk;
    private final List<long[]> reports = new ArrayList<>();
    /** The client's own loaded chunks (the edge sweep's "already drawn" key), by packed position. */
    private final java.util.Map<Long, Object> clientChunks = new java.util.HashMap<>();
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
        this.serverAllows = true;
        this.backpressure = true;
        this.failDecode = false;
        this.levelThrows = false;
        this.clock = 1_000_000L;
        this.playerChunk = Long.MIN_VALUE;
        this.reports.clear();
        this.clientChunks.clear();
        this.registered.clear();
        this.host = new JourneyMapSession.Host() {
            @Override public Object minecraft() { return null; }
            @Override public Object level() {
                if (levelThrows) throw new IllegalStateException("drifted");
                return level;
            }
            @Override public boolean playerInLevel() { return level != null; }
            @Override public Object dimension(Object l) { return dimension; }
            @Override public boolean hasCeiling(Object l) { return ceiling; }
            @Override public long playerChunk() { return playerChunk; }
            @Override public Object liveChunk(Object l, int x, int z) {
                return clientChunks.get(JourneyMapSession.packed(x, z));
            }
        };
        newSession(JourneyMapBindings.resolve(Class::forName));
    }

    private JourneyMapSession.Host host;

    private void newSession(JourneyMapBindings bindings) {
        this.session = new JourneyMapSession(bindings, this.host,
                () -> this.enabled, () -> this.sessionActive, this.registered::add, this.registered::remove,
                () -> this.backpressure,
                (dim, x, z) -> this.reports.add(new long[]{dimIndex(dim), x, z}),
                () -> this.clock, () -> this.serverAllows);
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
    void aFailedDayMapReportsTheColumn() {
        this.jm.renderController.result = (type, md) -> !type.name.equals("day");
        offer(0, 0);
        offer(1, 0);
        settle();
        cycle();
        assertEquals(0, counter("rendered"));
        assertEquals(2, counter("render_failed"));
        assertEquals(2, this.reports.size(), "the day map decides: a refused day render re-serves");
        assertEquals(2, renders().size(), "optional types are not attempted after a refused day render");
    }

    @Test
    void refusedOptionalTypesAreCountedNotReserved() {
        // JourneyMap's own topography renderer refuses every chunk until its palettes load;
        // its own pipeline ignores the result, so a re-serve could never fix it.
        this.jm.renderController.result = (type, md) -> !type.name.equals("topo");
        offer(0, 0);
        offer(1, 0);
        settle();
        cycle();
        assertEquals(2, counter("rendered"));
        assertEquals(2, counter("optional_refusals"));
        assertTrue(this.reports.isEmpty());
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
            for (int x = 0; x < 7; x++) offer(x, i); // 5 × 7 ≥ DEATH_LATCH_MIN_COLUMNS
            assertEquals(1, cycle());
            this.clock += 1000;
        }
        assertEquals(JourneyMapSession.DEATH_LATCH * 7, counter("decode_failures"));
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
        assertEquals(4, counter("rendered"), "the box is two renderDelays: +0..+180 fit 200 ms, +240 does not");
        assertEquals(1, counter("requeued"));
        assertEquals(1, this.session.queuedGauge);
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
    void aHeapHoldDoesNotOutliveAPumpThatNeverReachesTheBelt() {
        this.session.memory = new MapMemoryGuard(() -> 0.95, () -> this.clock);
        offer(0, 0);
        settle();
        this.session.pump();
        assertTrue(this.session.heapHolding);
        this.jm.mapping = false; // waiting on not_mapping: the belt is never consulted
        this.session.pump();
        assertFalse(this.session.heapHolding, "a stale hold must not keep the long pace box (re-review)");
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

    // ---- review folds (J5) ----

    @Test
    void journeyMapsOwnMappingSwitchOffIsInertNotAHold() {
        offer(0, 0);
        this.jm.properties.mappingEnabled.value = false;
        this.jm.mapping = Boolean.FALSE; // JourneyMap stopped and will not restart
        this.session.pump();
        assertEquals("inert(mapping_disabled)", this.session.state());
        assertEquals(-1, this.session.reportBackpressure(), "never holds the LOD stream");
        assertEquals(0, this.session.queuedGauge);
        gatedOffer(1, 1);
        assertEquals(1, counter("dropped_inert"));
    }

    @Test
    void aLateWorldIdReplaysThroughJourneyMapsRealStopGapRestart() {
        offer(0, 0);
        offer(1, 0);
        settle();
        cycle();
        assertEquals(2, counter("rendered"));
        // setCurrentWorldId: stopMapping at once ...
        this.jm.mapping = Boolean.FALSE;
        this.clock += 50;
        this.session.pump();
        assertEquals("waiting(not_mapping)", this.session.state());
        // ... and up to a second later a NEW controller maps into the id-suffixed directory.
        this.jm.mapping = Boolean.TRUE;
        this.jm.controller = new TaskController();
        this.jm.worldId = "abcdef";
        FileHandler.worldDir = new File("jm-test/mp/Server_A_abcdef");
        this.clock += 1000;
        this.session.pump();
        assertEquals(2, counter("replayed"), "the columns drawn into the id-less directory re-serve");
        assertEquals(2, this.reports.size());
    }

    @Test
    void anEvictedWrapperIsRequeuedNeverAcceptedUndrawn() {
        DataCache.INSTANCE.evictEveryAdd = true;
        offer(0, 0);
        settle();
        cycle();
        assertEquals(0, counter("rendered"));
        assertEquals(0, counter("skipped_live"), "a null lookup is not a live chunk");
        assertEquals(1, counter("requeued"));
        assertEquals(1, this.session.queuedGauge);
        assertTrue(renders().isEmpty());
    }

    @Test
    void wholesaleRenderRefusalsTripTheDeathLatch() {
        this.jm.renderController.result = (type, md) -> false;
        settle();
        for (int i = 0; i < JourneyMapSession.DEATH_LATCH; i++) {
            for (int x = 0; x < 7; x++) offer(x, i);
            cycle();
            this.clock += 1000;
        }
        assertEquals("dead", this.session.state(), "a refusing renderer must not re-serve the disc forever");
    }

    @Test
    void fiveSmallFailedBatchesDoNotKillTheSession() {
        // Steady state: one or two dirty re-serves per batch (re-review) — five failing
        // single columns are not a renderer refusing wholesale.
        this.jm.renderController.result = (type, md) -> false;
        settle();
        for (int i = 0; i < JourneyMapSession.DEATH_LATCH; i++) {
            offer(i, 0);
            cycle();
            this.clock += 1000;
        }
        assertEquals(JourneyMapSession.DEATH_LATCH, counter("render_failed"));
        assertNotEquals("dead", this.session.state());
        this.jm.renderController.result = (type, md) -> true;
        offer(40, 40);
        cycle();
        assertEquals(1, counter("rendered"), "a render resets the streak");
        assertEquals(0, this.session.consecutiveFailedColumns);
    }

    @Test
    void aWrapperEvictedEveryTimeIsReportedAfterTheLimitNotRequeuedForever() {
        DataCache.INSTANCE.evictEveryAdd = true;
        offer(0, 0);
        settle();
        for (int i = 0; i < JourneyMapSession.NULL_LOOKUP_LIMIT; i++) {
            cycle();
            this.clock += 1000;
        }
        assertEquals(JourneyMapSession.NULL_LOOKUP_LIMIT - 1, counter("requeued"));
        assertEquals(1, counter("column_failures"));
        assertEquals(1, this.reports.size(), "reported for a re-serve, its receipt closed");
        assertEquals(0, this.session.queuedGauge);
    }

    @Test
    void eastAndSouthBatchNeighboursAreInTheCacheWhenAColumnRenders() {
        // Topography's slope and shore checks read south and east, biome blending all around
        // (re-review): the (z, x) order alone only puts north and west there.
        this.jm.properties.mapTopography.value = false;
        this.jm.properties.mapBiome.value = false;
        for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++) offer(x, z);
        var seen = new java.util.HashMap<Long, java.util.Set<Long>>();
        this.jm.renderController.result = (type, md) -> {
            seen.put(md.getLongCoord(), new java.util.HashSet<>(DataCache.INSTANCE.cache.keySet()));
            return true;
        };
        settle();
        cycle();
        assertEquals(9, counter("rendered"));
        var atOrigin = seen.get(JourneyMapSession.mcChunkKey(0, 0));
        for (int[] d : new int[][]{{1, 0}, {0, 1}, {1, 1}}) {
            assertTrue(atOrigin.contains(JourneyMapSession.mcChunkKey(d[0], d[1])), d[0] + "," + d[1]);
        }
        var middle = seen.get(JourneyMapSession.mcChunkKey(1, 1));
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            assertTrue(middle.contains(JourneyMapSession.mcChunkKey(1 + dx, 1 + dz)), "all eight around (1,1)");
        }
        assertEquals(9, this.session.decodedColumns.get(), "decoded once each, ahead or not");
        assertTrue(DataCache.INSTANCE.cache.isEmpty(), "every wrapper released after the batch");
    }

    @Test
    void aLookAheadTheBoxCutsOffIsReleasedAndRequeued() {
        this.jm.properties.mapTopography.value = false;
        this.jm.properties.mapBiome.value = false;
        for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) offer(x, z);
        this.jm.renderController.result = (type, md) -> {
            this.clock += 5_000; // the first render spends the whole box
            return true;
        };
        settle();
        cycle();
        assertEquals(1, counter("rendered"));
        assertEquals(3, counter("requeued"));
        assertTrue(DataCache.INSTANCE.cache.isEmpty(), "the look-ahead wrappers left JourneyMap's cache");
        assertEquals(3, this.session.queuedGauge);
    }

    @Test
    void aColumnEncodedAcrossASessionEndNeverReachesTheNextSession() {
        var receipt = this.session.new Receipt(); // captured while the old session was live
        this.session.onSessionEnd();
        this.session.offerEncoded(new JourneyMapSession.Entry(OVERWORLD, 0, 0, frame(0, 0), receipt));
        assertEquals(0, this.session.queuedGauge);
        assertTrue(receipt.closed.get());
    }

    @Test
    void ourWrappersReleaseTheirChunkEvenWhenTheCacheCannotHandThemBack() {
        offer(0, 0);
        settle();
        var wrappers = new ArrayList<SnapshotChunkMD>();
        var plain = this.session.decoder;
        this.session.decoder = (bytes, level) -> {
            var md = (SnapshotChunkMD) plain.decode(bytes, level);
            wrappers.add(md);
            return md;
        };
        cycle();
        assertEquals(1, wrappers.size());
        assertFalse(wrappers.getFirst().retained, "stopChunkRetention runs for every wrapper we added");
    }

    @Test
    void decodingRealTerrainNeverStarvesRendering() {
        // The throughput gate's livelock: decoding everything first spent the whole box.
        this.jm.properties.mapTopography.value = false;
        this.jm.properties.mapBiome.value = false;
        var plain = this.session.decoder;
        this.session.decoder = (bytes, level) -> {
            this.clock += 60; // decoding costs as much as rendering on real terrain
            return plain.decode(bytes, level);
        };
        for (int i = 0; i < 20; i++) offer(i, 0);
        settle();
        cycle();
        assertTrue(counter("rendered") >= 8, "most of the box renders: " + counter("rendered"));
        assertEquals(20, counter("rendered") + counter("requeued"));
    }

    @Test
    void theEdgeSweepNeverPaintsAnotherDimensionsChunk() {
        this.jm.properties.mapTopography.value = false;
        this.jm.properties.mapBiome.value = false;
        livePlayerAt(0, 0);
        live(9, 0).dimension = END; // JourneyMap's cache already serves the new dimension
        live(0, 9);
        settle();
        cycle();
        assertEquals(1, counter("edge_rendered"));
        assertEquals(0, renders().getFirst().chunk().chunkX);
    }

    @Test
    void theEdgeSweepFollowsJourneyMapsSurfaceCap() {
        this.jm.gameRenderDistance = 7;
        this.jm.properties.renderDistanceSurfaceMax.value = 4;
        this.jm.properties.mapTopography.value = false;
        this.jm.properties.mapBiome.value = false;
        livePlayerAt(0, 0);
        live(6, 0); // inside the game range, outside JourneyMap's capped surface range
        settle();
        cycle();
        assertEquals(1, counter("edge_rendered"), "the ring starts where JourneyMap stops mapping");
    }

    @Test
    void aSweepThatDrewNothingCostsNoIdleGap() {
        livePlayerAt(0, 0);
        offer(40, 40);
        settle();
        cycle(); // an empty sweep
        assertEquals(1, counter("edge_sweeps"));
        cycle(); // the LSS batch follows at once
        assertEquals(1, counter("batches"));
    }

    // ---- the edge sweep ----

    private void livePlayerAt(int chunkX, int chunkZ) {
        this.playerChunk = JourneyMapSession.packed(chunkX, chunkZ);
    }

    /** A chunk the client has loaded (a new instance each call — a reload) that JourneyMap
     *  serves live from its cache. */
    private ChunkMD live(int x, int z) {
        var md = new ChunkMD(x, z, true);
        DataCache.INSTANCE.live.put(JourneyMapSession.mcChunkKey(x, z), md);
        this.clientChunks.put(JourneyMapSession.packed(x, z), new Object());
        return md;
    }

    @Test
    void theEdgeSweepRendersJourneyMapsOwnLiveChunksPastItsRangeOnce() {
        this.jm.gameRenderDistance = 7;
        this.jm.properties.mapTopography.value = false;
        this.jm.properties.mapBiome.value = false;
        livePlayerAt(100, 100);
        var inside = live(107, 100);  // distance 7: JourneyMap maps it itself
        var band = live(108, 100);    // 8 > 7.5: the ring JourneyMap never maps
        var diagonal = live(106, 106); // 8.5
        var far = live(111, 100);     // beyond range + 3
        settle();
        assertEquals(1, cycle(), "the first submission is the sweep");
        assertEquals(1, counter("edge_sweeps"));
        assertEquals(2, counter("edge_rendered"));
        var drawn = renders().stream().map(ChunkRenderController.Render::chunk).toList();
        assertTrue(drawn.contains(band) && drawn.contains(diagonal));
        assertFalse(drawn.contains(inside) || drawn.contains(far));
        assertEquals(0, counter("batches"), "no LSS batch, no receipts");
        assertEquals(1, band.getBlockData().clears, "block data is released like JourneyMap's own renders");
        renders().clear();
        this.clock += JourneyMapSession.EDGE_SWEEP_MILLIS;
        cycle();
        assertEquals(2, counter("edge_sweeps"));
        assertTrue(renders().isEmpty(), "an already-drawn live chunk instance is not drawn again");
        live(108, 100); // the chunk reloaded: a new instance
        this.clock += JourneyMapSession.EDGE_SWEEP_MILLIS;
        cycle();
        assertEquals(1, renders().size());
        assertEquals(3, counter("edge_rendered"));
    }

    @Test
    void aDrawnEdgeChunkCostsNoJourneyMapLookupAndLookupsArePaced() {
        // A low surface cap makes the band thousands of chunks wide: done chunks are keyed on
        // the client's own chunk, never re-looked-up in JourneyMap's cache, and first-fill
        // lookups are paced per sweep (re-review).
        this.jm.gameRenderDistance = 32;
        this.jm.properties.renderDistanceSurfaceMax.value = 4;
        this.jm.properties.mapTopography.value = false;
        this.jm.properties.mapBiome.value = false;
        livePlayerAt(0, 0);
        int band = 0;
        for (int z = -35; z <= 35; z++) for (int x = -35; x <= 35; x++) {
            if (Math.sqrt(x * x + z * z) > 4.5) {
                live(x, z);
                band++;
            }
        }
        assertTrue(band > 2 * JourneyMapSession.EDGE_LOOKUPS_PER_SWEEP);
        settle();
        cycle();
        assertEquals(JourneyMapSession.EDGE_LOOKUPS_PER_SWEEP, counter("edge_rendered"));
        int lookups = DataCache.INSTANCE.liveOnlyLookups;
        assertEquals(JourneyMapSession.EDGE_LOOKUPS_PER_SWEEP, lookups);
        for (int sweep = 2; counter("edge_rendered") < band; sweep++) {
            this.clock += JourneyMapSession.EDGE_SWEEP_MILLIS;
            cycle();
            assertTrue(sweep < 100, "the band fills");
        }
        int filled = DataCache.INSTANCE.liveOnlyLookups;
        this.clock += JourneyMapSession.EDGE_SWEEP_MILLIS;
        cycle();
        assertEquals(filled, DataCache.INSTANCE.liveOnlyLookups, "a drawn band costs no lookup");
        assertEquals(band, counter("edge_rendered"), "and draws nothing again");
    }

    @Test
    void theEdgeSweepInterleavesWithLssBatchesAndNeedsASession() {
        this.jm.properties.mapTopography.value = false;
        this.jm.properties.mapBiome.value = false;
        livePlayerAt(0, 0);
        live(9, 0);
        offer(40, 40);
        settle();
        cycle(); // the sweep
        this.clock += 500;
        cycle(); // the LSS batch
        assertEquals(1, counter("edge_sweeps"));
        assertEquals(1, counter("batches"));
        assertEquals(1, counter("rendered"));
        this.sessionActive = false;
        this.clock += JourneyMapSession.EDGE_SWEEP_MILLIS;
        this.session.pump();
        assertEquals(1, counter("edge_sweeps"), "never touches JourneyMap's map without an LSS session");
    }

    @Test
    void anIdentityChangeRedrawsTheEdge() {
        this.jm.properties.mapTopography.value = false;
        this.jm.properties.mapBiome.value = false;
        livePlayerAt(0, 0);
        live(9, 0);
        settle();
        cycle();
        assertEquals(1, counter("edge_rendered"));
        FileHandler.worldDir = new File("jm-test/mp/Server_A_abcdef");
        this.clock += JourneyMapSession.IDENTITY_POLL_MILLIS;
        this.session.pump();
        this.clock += JourneyMapSession.SETTLE_MILLIS + 1;
        cycle();
        assertEquals(2, counter("edge_rendered"), "the new map gets the ring too");
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

    /** Written by {@link InitProbe}'s static initializer — a separate class, so reading it
     *  never initializes the probe. */
    static final class InitFlag {
        static volatile boolean probeInitialized;
    }

    /** Loaded, never initialized, by the production resolver. */
    static final class InitProbe {
        static {
            InitFlag.probeInitialized = true;
        }
    }

    @Test
    void theProductionResolverNeverInitializesJourneyMapsClasses() throws Exception {
        Class<?> probe = JourneyMapSession.PRODUCTION_RESOLVER.resolve(InitProbe.class.getName());
        assertEquals(InitProbe.class.getName(), probe.getName());
        assertFalse(InitFlag.probeInitialized,
                "binding at client init must not run another mod's static initializers (the live-smoke crash)");
        assertTrue(source().contains("initWith(PRODUCTION_RESOLVER, PRODUCTION_HOST)"),
                "init() must bind through the non-initializing resolver");
    }

    private static String source() throws java.io.IOException {
        return java.nio.file.Files.readString(dev.vox.lss.testutil.SourcePaths.mainSource(
                "dev/vox/lss/compat/JourneyMapSession.java"));
    }

    @Test
    void aJourneyMapWithoutTheSuspendedProbeStillMaps() throws Exception {
        // JourneyMap 6.0.0 (the 1.21.11 line) has no isMappingSuspended, so production always
        // takes the null-handle branch — which the stubs, carrying the member, never reached
        // (port review).
        var bindings = JourneyMapBindings.resolve(name -> name.equals("journeymap.client.JourneymapClient")
                ? JourneymapClientWithoutSuspend.class : Class.forName(name));
        assertNull(bindings.isMappingSuspended);
        newSession(bindings);
        this.jm.suspended = true; // nothing on 6.0.0 can report a suspension
        offer(0, 0);
        settle();
        cycle();
        assertEquals(1, counter("rendered"));
    }

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

    // ---- the post-0.17 review fold ----

    @Test
    void aBackendDisabledOnTheTitleScreenReleasesItsConsumerAtOnce() {
        assertEquals(1, this.registered.size());
        boolean[] connected = {true};
        this.session.connected = () -> connected[0];
        this.enabled = false;
        this.session.pump();
        assertEquals(1, this.registered.size(), "connected: add-only until the session ends");
        connected[0] = false;
        this.session.pump();
        assertTrue(this.registered.isEmpty(), "no connection: the next handshake must not advertise LOD reception");
        this.enabled = true;
        this.session.pump();
        assertEquals(1, this.registered.size());
    }

    @Test
    void theHeapBeltReportsTheFullHalt() {
        this.session.memory = new MapMemoryGuard(() -> 0.95, () -> this.clock);
        offer(0, 0);
        settle();
        this.session.pump();
        assertTrue(this.session.heapHolding);
        assertEquals(LodRequestManager.INGEST_BACKLOG_HALT_SECTIONS, this.session.reportBackpressure(),
                "plan §4.3: the belt stops the stream instead of filling the queue it protects");
    }

    @Test
    void anOfferEncodedAcrossAnInertTransitionIsReleasedNotStranded() {
        this.session.pump();
        this.dimension = NETHER;
        this.ceiling = true;
        this.session.pump();
        assertEquals("inert(ceiling_dimension)", this.session.state());
        var receipt = this.session.new Receipt();
        // Encoded before the pump went inert, queued after its clear: re-checked under the lock.
        this.session.offerEncoded(new JourneyMapSession.Entry(NETHER, 0, 0, frame(0, 0), receipt));
        assertEquals(0, this.session.queuedGauge);
        assertEquals(1, counter("dropped_inert"));
        assertTrue(receipt.closed.get(), "the acceptance lease is released, never stranded");
    }

    @Test
    void aBatchStuckOnJourneyMapsThreadLatchesTheSessionDead() {
        offer(0, 0);
        settle();
        this.session.pump();
        var batch = this.session.inFlight;
        assertNotNull(batch);
        batch.state.set(JourneyMapSession.Batch.STARTED); // JourneyMap's thread entered it and never returned
        batch.startedAtMillis = this.clock;
        this.clock += JourneyMapSession.SLOW_BATCH_MILLIS + 1;
        this.session.pump();
        assertFalse(this.session.dead, "slow is not stuck");
        assertTrue(this.session.describe().contains(", inflight_ms=" + (JourneyMapSession.SLOW_BATCH_MILLIS + 1)),
                this.session.describe());
        offer(1, 1);
        this.clock += JourneyMapSession.WEDGED_BATCH_MILLIS;
        this.session.pump();
        assertTrue(this.session.dead, "a stuck task thread would otherwise hold the stream all session");
        assertEquals(0, this.session.queuedGauge);
        assertEquals(-1, this.session.reportBackpressure());
        assertSame(batch, this.session.inFlight, "the slot stays taken: the thread may still write its result");
    }

    @Test
    void aPumpThatThrowsEveryTickLatchesButAnOccasionalThrowDoesNot() {
        offer(0, 0);
        this.levelThrows = true;
        for (int i = 0; i < JourneyMapSession.DEATH_LATCH - 1; i++) this.session.pump();
        this.levelThrows = false;
        this.session.pump(); // a clean pump resets the streak
        this.levelThrows = true;
        for (int i = 0; i < JourneyMapSession.DEATH_LATCH - 1; i++) this.session.pump();
        assertFalse(this.session.dead);
        this.session.pump();
        assertTrue(this.session.dead, "drift at call time would duty-cycle the governor all session");
        assertEquals(0, this.session.queuedGauge);
    }

    @Test
    void aNotMappingHoldThatNeverEndsTurnsInert() {
        this.jm.mapping = Boolean.FALSE;
        offer(0, 0);
        this.session.pump();
        assertEquals("waiting(not_mapping)", this.session.state());
        this.clock += JourneyMapSession.STUCK_HOLD_MILLIS - 1;
        this.session.pump();
        assertEquals("waiting(not_mapping)", this.session.state(), "a dimension or world switch still holds");
        assertEquals(1, this.session.queuedGauge);
        this.clock += 2;
        this.session.pump();
        assertEquals("inert(not_mapping)", this.session.state());
        assertEquals(0, this.session.queuedGauge);
        assertEquals(-1, this.session.reportBackpressure(), "it no longer holds the stream");
        this.jm.mapping = Boolean.TRUE;
        this.session.pump();
        assertEquals("waiting(settle)", this.session.state(), "mapping again: a fresh identity settles");
    }

    @Test
    void anInterruptedHoldRestartsItsClock() {
        this.jm.mapping = Boolean.FALSE;
        this.session.pump();
        this.clock += JourneyMapSession.STUCK_HOLD_MILLIS - 1;
        this.level = null; // between worlds: not a mapping hold
        this.session.pump();
        this.level = this.levelMarker;
        this.clock += 2;
        this.session.pump();
        assertEquals("waiting(not_mapping)", this.session.state(), "only an uninterrupted hold ages");
    }

    @Test
    void aBatchStopsAtTheNextColumnWhenJourneyMapStopsItsMapping() {
        this.jm.properties.mapTopography.value = false;
        this.jm.properties.mapBiome.value = false;
        this.jm.renderController.result = (type, md) -> {
            // stopMapping/startMapping: JourneyMap replaced its task controller mid-batch
            this.jm.controller = new TaskController();
            return true;
        };
        for (int i = 0; i < 4; i++) offer(i, 0);
        settle();
        int updates = RegionImageCache.INSTANCE.updates;
        cycle();
        assertEquals(1, renders().size(), "no column after the stop is rendered");
        assertEquals(updates, RegionImageCache.INSTANCE.updates, "JourneyMap's textures are left to the next mapping");
        assertEquals(1, counter("abandoned"));
        assertEquals(4, this.reports.size(), "every column re-serves into the next mapping");
        assertTrue(DataCache.INSTANCE.cache.isEmpty(), "our wrappers are still released");
    }

    @Test
    void aNewerColumnFromAnotherDimensionReportsTheOneItReplaces() {
        offer(OVERWORLD, 3, 3);
        offer(END, 3, 3);
        this.session.drainPendingReports();
        assertEquals(1, counter("dropped_stale"));
        assertEquals(1, this.reports.size(), "the overworld column was never written: it re-serves on the way back");
        assertEquals(0, this.reports.getFirst()[0]);
        assertEquals(1, this.session.queuedGauge);
    }

    @Test
    void aDimensionChangeReportsQueuedColumnsEvenWhileJourneyMapIsNotMapping() {
        offer(OVERWORLD, 0, 0);
        this.jm.mapping = Boolean.FALSE;
        this.session.pump();
        assertEquals(1, this.session.queuedGauge, "the switch hold keeps them");
        offer(END, 1, 1);
        this.dimension = END;
        this.session.pump();
        assertEquals(1, counter("dropped_stale"));
        assertEquals(1, this.reports.size());
        assertEquals(0, this.reports.getFirst()[0]);
        assertEquals(1, this.session.queuedGauge, "the new dimension's column stays");
    }

    @Test
    void aBatchFinishingAfterItsSessionAppliesNothing() {
        offer(0, 0);
        settle();
        this.session.pump();
        this.jm.runQueued(); // done on JourneyMap's thread...
        this.session.onSessionEnd(); // ...but the session ended before a pump reconciled it
        this.session.pump();
        assertNull(this.session.inFlight);
        assertEquals(0, counter("rendered"));
        assertTrue(this.session.replay.isEmpty(), "never replayed into the next server's map");
        assertTrue(this.reports.isEmpty());
    }

    @Test
    void aRequeueRespectsTheQueueBounds() {
        this.jm.properties.renderDelay.value = 100;
        this.jm.renderController.result = (type, md) -> {
            this.clock += 300;
            return true;
        };
        offer(0, 0);
        offer(1, 0);
        settle();
        this.session.pump(); // submits region (0, 0)
        this.session.maxQueue = 1;
        offer(40, 40); // fills the queue while the batch runs
        this.jm.runQueued(); // (1, 0) misses the box
        this.session.pump();
        assertEquals(1, this.session.queuedGauge, "the requeue never overshoots the bound");
        assertEquals(1, counter("dropped_overflow"));
        assertEquals(1, this.reports.size(), "governed: the overflowed column re-serves");
        assertEquals(1, this.reports.getFirst()[1]);
    }

    @Test
    void theEdgeSweepKeyHoldsTheLevelAndControllerWeakly() {
        for (var c : JourneyMapSession.EdgeKey.class.getRecordComponents()) {
            if (c.getName().equals("level") || c.getName().equals("controller")) {
                assertEquals(java.lang.ref.WeakReference.class, c.getType(), c.getName());
            }
        }
        assertTrue(java.util.Arrays.stream(JourneyMapSession.class.getDeclaredFields())
                        .noneMatch(f -> f.getType() == JourneyMapSession.Identity.class && f.getName().startsWith("edge")),
                "a disconnect must never pin the old level through the edge sweep");
    }

    @Test
    void productionInitRegistersTheConsumerAndTheTitleScreenReleasesIt() {
        JourneyMapSession.resetFacadeForTest();
        assertFalse(dev.vox.lss.api.LSSApi.hasVoxelConsumers(), "premise: no consumer leaked from another suite");
        boolean old = dev.vox.lss.config.LSSClientConfig.CONFIG.mapIngestionEnabled();
        try {
            dev.vox.lss.config.ClientConfigTestSupport.set("integrations.map_ingestion.enabled", true);
            assertTrue(JourneyMapSession.initWith(Class::forName, this.host));
            assertTrue(dev.vox.lss.api.LSSApi.hasVoxelConsumers(), "the capability bit's consumer is registered");
            assertTrue(JourneyMapSession.isArmed());
            assertTrue(JourneyMapSession.isSupported());
            assertEquals("available", JourneyMapSession.cachedStatus(true).resolution());
            JourneyMapSession.instance.connected = () -> false; // the title screen
            dev.vox.lss.config.ClientConfigTestSupport.set("integrations.map_ingestion.enabled", false);
            JourneyMapSession.clientTick();
            assertFalse(dev.vox.lss.api.LSSApi.hasVoxelConsumers(), "disabled with no connection: released at once");
        } finally {
            var instance = JourneyMapSession.instance;
            if (instance != null) dev.vox.lss.api.LSSApi.removeColumnConsumer(instance.consumer);
            dev.vox.lss.config.ClientConfigTestSupport.set("integrations.map_ingestion.enabled", old);
            JourneyMapSession.resetFacadeForTest();
        }
    }
    // ---- the server's map-ingestion request (map-policy plan A4) ----

    @Test
    void aServerDenyDropsOffersUnreportedAndNeverThrottlesTheStream() {
        this.serverAllows = false;
        gatedOffer(0, 0);
        assertEquals(0, this.session.queuedGauge);
        assertEquals(0, this.session.encodedColumns.get(), "dropped before encoding");
        offer(1, 1); // an encode that raced the deny lands in offerEncoded's re-check
        assertEquals(0, this.session.queuedGauge);
        assertEquals(-1, this.session.reportBackpressure(),
                "a blocked map never paces Voxy's stream");
        assertTrue(this.reports.isEmpty(), "nothing is reported for re-serve");
        assertTrue(this.session.describe().contains("state=blocked_by_server"), this.session.describe());
        assertEquals(1, this.registered.size(),
                "the write gate is NOT composed into enabled: registration is untouched");
    }

    @Test
    void aMidSessionDenyClearsTheQueueAndAnAllowResumesWriting() {
        offer(0, 0);
        offer(1, 0);
        assertEquals(2, this.session.queuedGauge);
        this.serverAllows = false;
        this.session.pump();
        assertEquals(0, this.session.queuedGauge, "the local-off branch: queue dropped");
        assertTrue(this.reports.isEmpty(), "…unreported");
        assertEquals(0, this.jm.queued.size(), "nothing written while denied");
        this.session.onSessionEnd();
        this.session.pump();
        assertEquals(1, this.registered.size(), "a deny never deregisters at session end");

        this.serverAllows = true;
        offer(0, 0);
        this.session.pump();
        this.clock += JourneyMapSession.SETTLE_MILLIS + 1;
        assertEquals(1, cycle(), "an allow resumes writing for terrain arriving from now on");
    }
}
