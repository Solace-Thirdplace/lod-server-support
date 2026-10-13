package dev.vox.lss.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.platform.SectionConstruction;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.region.MapRegion;

/**
 * Phase B-II's best-effort band pipeline (cave-layers plan §B6) against the Tier-1 stubs:
 * which bands a column gets (Layered only, the radius bound, all-void skips, the switch),
 * isolation from the reliable path (its own capped queue outside occupancy and the report,
 * drop-oldest, never owed or reported), batching and the same-pump first-load commit, the
 * grant gates (a waiting reliable region, Xaero's save queue, the rebuild sub-cap), its own
 * window share, and the §B0 instrumentation (re-grants, vanished regions, load latency).
 */
class XaeroBestEffortTest {
    private static final ResourceKey<Level> NETHER =
            ResourceKey.create(Registries.DIMENSION, Identifier.parse("minecraft:the_nether"));
    private static final ResourceKey<Level> OVERWORLD =
            ResourceKey.create(Registries.DIMENSION, Identifier.parse("minecraft:overworld"));
    private static final XaeroTileExtractor.LevelFacts NETHER_FACTS =
            new XaeroTileExtractor.LevelFacts(0, 256, false, true, 128);
    private static final int FULL = Integer.MIN_VALUE;

    private static PalettedContainerFactory FACTORY;
    /** Bedrock at 0 and a one-block netherrack platform at 16b+8 for every band b: every band
     *  window floors on its own platform. */
    private static VoxelColumnData PLATFORMS;
    /** Solid netherrack to 127: every band window is buried (all-void). */
    private static VoxelColumnData SOLID;

    private MapProcessor processor;
    private final Object worldToken = new Object();
    private final List<Object[]> reports = new ArrayList<>();
    private long clockMillis = 1_000_000L;
    private XaeroSession bridge;

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var biomeLookup = VanillaRegistries.createWorldLookup().lookupOrThrow(Registries.BIOME);
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        biomeLookup.listElements().forEach(ref -> biomes.register(ref.key(), ref.value(), RegistrationInfo.BUILT_IN));
        biomes.freeze();
        FACTORY = PalettedContainerFactory.create(new RegistryAccess.ImmutableRegistryAccess(List.of(biomes)));
        PLATFORMS = column(y -> y == 0 || (y & 15) == 8);
        SOLID = column(y -> y < 128);
    }

    private static VoxelColumnData column(IntPredicate solidAt) {
        var sections = new ArrayList<VoxelColumnData.SectionData>();
        for (int sy = 0; sy < 8; sy++) {
            var section = SectionConstruction.empty(FACTORY);
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        int wy = (sy << 4) + y;
                        if (solidAt.test(wy)) {
                            section.setBlockState(x, y, z, wy == 0 ? Blocks.BEDROCK.defaultBlockState()
                                    : Blocks.NETHERRACK.defaultBlockState());
                        }
                    }
                }
            }
            sections.add(new VoxelColumnData.SectionData(sy, section, null, null));
        }
        return new VoxelColumnData(sections.toArray(new VoxelColumnData.SectionData[0]), 1L);
    }

    @BeforeEach
    void setUp() throws Exception {
        XaeroStubEvents.clear();
        this.processor = new MapProcessor();
        this.processor.createdRegionLoadState = 2;
        this.processor.world = this.worldToken;
        this.processor.mainWorld = this.worldToken;
        this.processor.mapWorld.currentDimensionId = NETHER;
        var session = new WorldMapSession();
        session.processor = this.processor;
        WorldMapSession.current = session;
        this.reports.clear();
        this.bridge = new XaeroSession(XaeroBindings.resolve(Class::forName), new XaeroSession.LevelOps() {
            @Override
            public Object dimension(Object world) {
                return processor.mapWorld.currentDimensionId;
            }

            @Override
            public boolean isChunkLoaded(Object world, int chunkX, int chunkZ) {
                return false;
            }
        }, () -> true, () -> true, c -> {}, c -> {}, () -> true,
                (dimension, chunkX, chunkZ) -> this.reports.add(new Object[]{dimension, chunkX, chunkZ}));
        this.bridge.pumpNanosBudget = Long.MAX_VALUE;
        this.bridge.updateNanosBudget = Long.MAX_VALUE;
        this.bridge.updateIdlePumps = 1_000_000; // keep rebuilds owed (not under test)
        this.bridge.pendingUpdatesSoftCap = 10_000;
        this.bridge.pendingUpdatesHardCap = 20_000;
        this.bridge.bpClock = () -> this.clockMillis;
        this.bridge.playerProbe = () -> new XaeroSession.PlayerPos(NETHER, 64 * 16 + 8, 70, 64 * 16 + 8);
        this.bridge.bestEffort.enabled = true;
        this.bridge.bestEffort.batchTiles = 3;
        this.bridge.bestEffort.batchMillis = 60_000;
    }

    @AfterEach
    void tearDown() {
        WorldMapSession.current = null;
        xaero.map.config.util.WorldMapClientConfigUtils.caveModeAllowed = true;
        XaeroStubEvents.clear();
    }

    // ---- helpers ----

    private void show(int layer) {
        this.processor.currentCaveLayer = layer;
        this.bridge.pump();
    }

    private void offer(int chunkX, int chunkZ, VoxelColumnData column) {
        this.bridge.offerColumn(NETHER, chunkX, chunkZ, NETHER_FACTS, column);
    }

    private MapRegion bandRegion(int band) {
        var regions = this.processor.caveRegions.get(band);
        return regions == null ? null : regions.get((2L << 32) | 2L);
    }

    private long count(String name) {
        return this.bridge.counterForTest(name);
    }

    /** Layer 3 shown; three columns of region (2,2): every best-effort band's batch reaches K. */
    private void threeColumnsInBand3View() {
        show(3);
        offer(64, 64, PLATFORMS);
        offer(65, 64, PLATFORMS);
        offer(66, 64, PLATFORMS);
    }

    // ---- which bands ----

    @Test
    void aLayeredNetherColumnGetsEveryOtherBandBestEffort() {
        show(3);
        offer(64, 64, PLATFORMS);
        assertEquals(2, this.bridge.queuedForTest(), "the reliable path: surface + the shown band");
        assertEquals(7, count("best_effort_queued"), "bands 0..7 of the logical height, less band 3");
        assertEquals(7, count("best_effort_enqueued"));
        var targets = this.bridge.planTargets(NETHER, 64, 64, NETHER_FACTS);
        assertEquals(9, targets.size());
        assertEquals(XaeroTileExtractor.TileClass.RELIABLE, targets.get(1).tileClass());
        assertEquals(3, targets.get(1).layer());
        assertTrue(targets.subList(2, 9).stream().allMatch(t -> t.tileClass() == XaeroTileExtractor.TileClass.BEST_EFFORT
                && t.layer() != 3 && t.caveStart() == t.layer() * 16 + 15), targets.toString());
    }

    @Test
    void onTheRoofEveryBandIsBestEffort() {
        show(Integer.MAX_VALUE); // the surface: the player is on the Nether roof
        offer(64, 64, PLATFORMS);
        assertEquals(1, this.bridge.queuedForTest());
        assertEquals(8, count("best_effort_queued"));
    }

    @Test
    void fullOffAndDisallowedModesWriteNoBestEffortBands() {
        this.processor.mapWorld.currentDimension.caveModeType = 2;
        show(FULL);
        offer(64, 64, PLATFORMS);
        assertEquals(0, count("best_effort_queued"), "Full mode is one layer, written reliably");
        this.processor.mapWorld.currentDimension.caveModeType = 0;
        show(Integer.MAX_VALUE);
        offer(65, 64, PLATFORMS);
        assertEquals(0, count("best_effort_queued"), "cave mode off");
        this.processor.mapWorld.currentDimension.caveModeType = 1;
        xaero.map.config.util.WorldMapClientConfigUtils.caveModeAllowed = false;
        show(Integer.MAX_VALUE);
        offer(66, 64, PLATFORMS);
        assertEquals(0, count("best_effort_queued"), "cave mode not allowed (server or config)");
    }

    @Test
    void theRadiusIsTheSmallerOfTheCapAndTheServersLodDistance() {
        show(3);
        this.bridge.bestEffort.radiusChunks = 10;
        assertEquals(2, this.bridge.planTargets(NETHER, 64 + 11, 64, NETHER_FACTS).size(), "outside the cap");
        assertEquals(9, this.bridge.planTargets(NETHER, 64 + 7, 64 + 7, NETHER_FACTS).size(), "inside (euclidean)");
        this.bridge.serverLodDistance = () -> 5;
        assertEquals(2, this.bridge.planTargets(NETHER, 64 + 6, 64, NETHER_FACTS).size(), "the LOD distance binds");
        assertEquals(9, this.bridge.planTargets(NETHER, 64 + 3, 64 + 3, NETHER_FACTS).size());
        this.bridge.playerProbe = () -> null;
        assertEquals(2, this.bridge.planTargets(NETHER, 64, 64, NETHER_FACTS).size(), "no player: no bound, no bands");
    }

    @Test
    void anAllVoidBandIsSkippedNotQueued() {
        show(3);
        offer(64, 64, SOLID);
        assertEquals(0, count("best_effort_queued"));
        assertEquals(7, count("best_effort_void"), "every band is buried in netherrack");
        assertEquals(2, this.bridge.queuedForTest(), "the reliable void tiles still write (they erase)");
    }

    @Test
    void theSwitchOffIsTheBIBehavior() {
        this.bridge.bestEffort.enabled = false;
        show(3);
        offer(64, 64, PLATFORMS);
        assertEquals(this.bridge.reliableTargets(NETHER, NETHER_FACTS), this.bridge.planTargets(NETHER, 64, 64, NETHER_FACTS));
        assertEquals(0, count("best_effort_enqueued"));
        assertEquals(2, this.bridge.queuedForTest());
    }

    // ---- isolation ----

    @Test
    void theBestEffortQueueSitsOutsideOccupancyAndTheReport() {
        show(3);
        this.processor.currentMapLocked = true; // nothing drains
        this.bridge.pump();
        double before = this.bridge.acquisition.occupancy;
        int reportBefore = this.bridge.reportBackpressure();
        offer(64, 64, PLATFORMS);
        assertEquals(7, count("best_effort_queued"));
        assertEquals(2, this.bridge.queuedForTest());
        // Occupancy and the report move only with the RELIABLE queue (its two tiles).
        double reliableOnly = Math.max(2.0 / this.bridge.maxQueue,
                (double) this.bridge.queuedBytesForTest() / this.bridge.maxQueueBytes);
        assertEquals(reliableOnly, this.bridge.acquisition.occupancy, 1e-9);
        assertTrue(this.bridge.acquisition.occupancy > before);
        assertEquals(reportBefore, 0);
        this.processor.currentMapLocked = false;
    }

    @Test
    void overflowDropsTheOldestAndNeverOwesOrReports() {
        show(3);
        this.bridge.bestEffort.maxQueue = 3;
        offer(64, 64, PLATFORMS);
        assertEquals(3, count("best_effort_queued"));
        assertEquals(4, count("best_effort_drop_overflow"), "oldest-first past the count cap");
        assertEquals(0, count("owed"));
        assertTrue(this.reports.isEmpty(), "best-effort drops are never reported");
    }

    @Test
    void staleExpiredAndRetiredEntriesDropUnreported() {
        show(3);
        this.processor.currentMapLocked = true;
        offer(64, 64, PLATFORMS);
        this.clockMillis += this.bridge.bestEffort.ttlMillis + 1;
        this.processor.currentMapLocked = false;
        this.bridge.pump();
        assertEquals(7, count("best_effort_drop_expired"));
        offer(65, 64, PLATFORMS);
        this.processor.mapWorld.currentDimensionId = OVERWORLD; // the player left the Nether
        this.bridge.pump();
        assertEquals(7, count("best_effort_drop_stale"));
        assertEquals(1, this.reports.size(), "only the column's RELIABLE stale drop reports (once)");
        this.reports.clear();
        this.processor.mapWorld.currentDimensionId = NETHER;
        offer(66, 64, PLATFORMS);
        this.bridge.onSessionEnd();
        assertEquals(0, count("best_effort_queued"));
        assertEquals(7, count("best_effort_cleared"), "a teardown clear is not a capacity drop");
        assertEquals(14, count("best_effort_dropped"));
        assertTrue(this.reports.isEmpty());
        assertEquals(0, count("owed"));
    }

    // ---- batching, grants, the same-pump commit ----

    @Test
    void aBatchWaitsForKTilesThenItsRegionCommitsInThePumpThatSeesItLoad() {
        show(3);
        offer(64, 64, PLATFORMS);
        offer(65, 64, PLATFORMS);
        this.bridge.pump(); // reliable regions load (state 2); band batches hold 2 < K
        assertEquals(0, count("best_effort_grants"), "not ready: under K and younger than T");
        this.processor.createdRegionLoadState = 0;
        offer(66, 64, PLATFORMS);
        this.bridge.pump();
        assertEquals(2, count("best_effort_grants"), "ready batches, at most two loads in flight");
        assertEquals(0, count("best_effort_written"));
        var granted = new ArrayList<MapRegion>();
        for (int band = 0; band < 8; band++) {
            var r = bandRegion(band);
            if (r != null && band != 3) granted.add(r);
        }
        assertEquals(2, granted.size());
        this.bridge.pump();
        assertEquals(0, count("best_effort_written"), "still loading");
        granted.forEach(r -> r.loadState = 2);
        this.clockMillis += 300;
        this.bridge.pump();
        assertEquals(6, count("best_effort_written"), "both batches commit WHOLE in the pump that sees the load");
        assertEquals(2, count("best_effort_batches"));
        assertEquals(2, count("best_effort_load_count"));
        assertEquals(400, this.bridge.loadStats.bestEffort.quantileMillis(0.95), "300 ms lands in the <=400 bucket");
    }

    @Test
    void aBatchOlderThanTIsReadyBelowK() {
        show(3);
        offer(64, 64, PLATFORMS);
        this.bridge.pump(); // the reliable regions load; one tile per band batch
        this.processor.createdRegionLoadState = 0;
        this.bridge.pump();
        assertEquals(0, count("best_effort_grants"));
        this.clockMillis += this.bridge.bestEffort.batchMillis;
        this.bridge.pump();
        assertEquals(2, count("best_effort_grants"), "the oldest entry reached T");
    }

    @Test
    void theFirstLoadBatchCommitsEvenWithNoBudgetLeft() {
        show(3);
        offer(64, 64, PLATFORMS);
        this.bridge.pump(); // the reliable regions load
        this.processor.createdRegionLoadState = 0;
        offer(65, 64, PLATFORMS);
        offer(66, 64, PLATFORMS);
        this.bridge.pump();
        assertEquals(2, count("best_effort_grants"));
        this.bridge.pumpNanosBudget = 0; // the reliable drain leaves nothing
        for (int band = 0; band < 8; band++) {
            var r = bandRegion(band);
            if (r != null && band != 3) r.loadState = 2;
        }
        this.bridge.pump();
        assertEquals(6, count("best_effort_written"), "first-load batches are budget-exempt, and only they ran");
    }

    @Test
    void aWaitingReliableRegionHoldsBestEffortGrants() {
        show(3);
        this.processor.createdRegionLoadState = 0; // the reliable regions too: they wait
        offer(64, 64, PLATFORMS);
        offer(65, 64, PLATFORMS);
        offer(66, 64, PLATFORMS);
        this.bridge.pump();
        this.bridge.pump();
        assertTrue(this.bridge.regionsWaitingForTest() > 0);
        assertEquals(0, count("best_effort_grants"), "the reliable path goes first");
    }

    @Test
    void aNonEmptySaveQueueHoldsBestEffortGrants() {
        show(3);
        offer(64, 64, PLATFORMS);
        offer(65, 64, PLATFORMS);
        this.bridge.pump();
        this.processor.createdRegionLoadState = 0;
        this.processor.saveLoad.toSave.add(new MapRegion());
        offer(66, 64, PLATFORMS);
        this.bridge.pump();
        assertEquals(0, count("best_effort_grants"), "Xaero is saving: no new best-effort region");
        assertTrue(this.bridge.describe().contains(", to_save=1(max 1)"), this.bridge.describe());
        this.processor.saveLoad.toSave.clear();
        this.bridge.pump();
        assertEquals(2, count("best_effort_grants"));
    }

    @Test
    void theRebuildSubCapRefusesBestEffortCommitsAndGrants() {
        this.bridge.pendingUpdatesSoftCap = 0;
        threeColumnsInBand3View();
        this.bridge.pump();
        assertEquals(0, count("best_effort_written"));
        assertEquals(0, count("best_effort_grants"));
    }

    @Test
    void itsOwnWindowShareNeverSetsTheReliablePacingHold() {
        this.bridge.openRegionCap = 4; // share = max(2, 4 / 4)
        assertEquals(2, this.bridge.bestEffort.windowCap());
        this.bridge.bestEffort.maxOutstandingLoads = 8;
        show(3);
        offer(64, 64, PLATFORMS);
        offer(65, 64, PLATFORMS);
        this.bridge.pump();
        this.processor.createdRegionLoadState = 0;
        offer(66, 64, PLATFORMS);
        this.bridge.pump();
        assertEquals(2, count("best_effort_grants"), "the share bounds the grants");
        this.bridge.pump();
        assertFalse(this.bridge.windowBound, "a full best-effort share is not the reliable window");
        assertTrue(this.bridge.describe().contains(", best_effort_open=2/2"), this.bridge.describe());
    }

    @Test
    void anAlreadyLoadedRegionCommitsWithoutAGrantWithinTheCommitCap() {
        show(3);
        this.bridge.bestEffort.commitsPerPump = 4;
        offer(64, 64, PLATFORMS); // band regions pre-exist loaded:
        for (int band = 0; band < 8; band++) this.processor.getLeafMapRegion(band, 2, 2, true);
        this.bridge.pump();
        assertEquals(0, count("best_effort_grants"));
        assertEquals(4, count("best_effort_written"), "leftover budget, its own commit cap");
        this.bridge.pump();
        assertEquals(7, count("best_effort_written"));
    }

    @Test
    void aQuietCommittedRegionLeavesTheShareAndXaerosLimiterGovernsIt() {
        show(3);
        offer(64, 64, PLATFORMS);
        for (int band = 0; band < 8; band++) this.processor.getLeafMapRegion(band, 2, 2, true);
        this.bridge.pump();
        assertEquals(7, count("best_effort_written"));
        assertEquals(7, this.bridge.bestEffort.open.size(), "held while committing");
        this.clockMillis += this.bridge.bestEffort.idleReleaseMillis;
        this.bridge.pump();
        assertTrue(this.bridge.bestEffort.open.isEmpty(), "nothing queued for them, quiet: released");
    }

    // ---- the §B0 instrumentation ----

    @Test
    void aRegionRemovedAfterItsGrantCountsAsVanishedAndItsReGrantIsCounted() {
        show(3);
        offer(64, 64, PLATFORMS);
        this.bridge.pump();
        this.processor.createdRegionLoadState = 0;
        offer(65, 64, PLATFORMS);
        offer(66, 64, PLATFORMS);
        this.bridge.pump();
        assertEquals(2, count("best_effort_grants"));
        // Xaero removes both granted regions before anything committed (countChunks()==0).
        for (int band = 0; band < 8; band++) {
            if (band != 3 && bandRegion(band) != null) this.processor.caveRegions.get(band).remove((2L << 32) | 2L);
        }
        this.bridge.pump();
        assertEquals(2, count("best_effort_region_vanished"), "removed before the first commit");
        assertEquals(4, count("best_effort_grants"), "the still-ready batches are granted again");
        assertEquals(2, count("best_effort_regrants"));
        assertEquals(2, count("best_effort_grant_max"));
    }

    @Test
    void aReliableRegionReplacedAfterItsGrantCountsAsVanished() {
        show(3);
        this.processor.createdRegionLoadState = 0;
        this.bridge.bestEffort.enabled = false;
        offer(64, 64, PLATFORMS);
        this.bridge.pump(); // grants the surface and band-3 regions
        assertEquals(2, count("load_requests"));
        this.processor.regions.remove((2L << 32) | 2L); // removed; the next probe re-creates it
        this.bridge.pump();
        assertEquals(1, count("region_vanished"));
    }

    @Test
    void theDiagAndTheExporterCarryTheInstrumentation() {
        threeColumnsInBand3View();
        this.bridge.pump();
        var line = this.bridge.describe();
        for (String token : List.of(", best_effort_enqueued=", ", best_effort_written=", ", best_effort_dropped=",
                ", best_effort_drops=overflow:", ", best_effort_void=", ", best_effort_queued=",
                ", best_effort_open=", ", best_effort_batches=", ", best_effort_grants=", ", best_effort_regrants=",
                ", best_effort_grant_max=", ", best_effort_bands=on", ", load_ms=p50:", ", best_effort_load_ms=p50:",
                ", region_vanished=", ", best_effort_region_vanished=", ", to_save=", ", to_load=")) {
            assertTrue(line.contains(token), token + " missing: " + line);
        }
        XaeroSession.instance = this.bridge;
        try {
            var metrics = XaeroSession.metrics();
            for (String key : List.of("layer", "mode", "written_cave", "best_effort_on", "best_effort_enqueued",
                    "best_effort_written", "best_effort_dropped", "best_effort_drop_overflow", "best_effort_drop_expired",
                    "best_effort_drop_stale", "best_effort_drop_other", "best_effort_cleared", "best_effort_void",
                    "best_effort_skipped_native", "best_effort_queued", "best_effort_open", "best_effort_window",
                    "best_effort_batches", "best_effort_grants", "best_effort_regrants", "best_effort_grant_max",
                    "to_save", "to_save_max", "to_load", "load_count", "load_ms_p50", "load_ms_p95",
                    "best_effort_load_count", "best_effort_load_ms_p50", "best_effort_load_ms_p95",
                    "region_vanished", "best_effort_region_vanished")) {
                assertTrue(metrics.containsKey(key), key + " missing from the exporter group: " + metrics.keySet());
            }
        } finally {
            XaeroSession.resetFacadeForTest();
        }
    }

    @Test
    void theHistogramQuantilesAreBucketBounds() {
        var h = new XaeroLoadStats.Histogram();
        assertEquals(-1, h.quantileMillis(0.5));
        h.add(10);
        h.add(120);
        h.add(150);
        h.add(90_000);
        assertEquals(200, h.quantileMillis(0.5));
        assertEquals(-2, h.quantileMillis(0.95), "past the last finite bound");
        assertNotNull(h);
    }
}
