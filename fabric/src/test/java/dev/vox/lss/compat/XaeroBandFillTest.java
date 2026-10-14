package dev.vox.lss.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.Lifecycle;
import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.common.store.StoreCodec;
import dev.vox.lss.platform.SectionConstruction;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.region.MapBlock;
import xaero.map.region.MapRegion;

/**
 * Fill-on-switch against the Tier-1 stubs: which columns the store keeps (ceiling dimensions,
 * the write gates, world-save mode, the cave bindings), the debounced trigger and its
 * cancellation (a switch, a dimension change, the server's deny, the session end),
 * session coverage (live arrivals mark it, re-serves invalidate it, fills skip it), pacing
 * behind live arrivals, fill tiles being lease-free and outside the backpressure figure, and
 * the parity of a filled tile with the live tile for the same column and layer.
 */
class XaeroBandFillTest {
    private static final ResourceKey<Level> NETHER =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:the_nether"));
    private static final ResourceKey<Level> OVERWORLD =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:overworld"));
    private static final XaeroTileExtractor.LevelFacts NETHER_FACTS =
            new XaeroTileExtractor.LevelFacts(0, 256, false, true, 128);
    private static final XaeroTileExtractor.LevelFacts OVERWORLD_FACTS =
            new XaeroTileExtractor.LevelFacts(-64, 320, true, false, 384);
    private static final long FP = 0xF111L;

    private static MappedRegistry<Biome> BIOMES;
    // LINE FLAVOR (1.21.1): no palette factory type on this MC — the SectionConstruction
    // seam's (and the band-fill level's) construction parameter is the biome Registry.
    private static net.minecraft.core.Registry<Biome> FACTORY;
    /** Bedrock at 0 and a one-block netherrack platform at 16b+8 for every band b. */
    private static VoxelColumnData PLATFORMS;

    @TempDir
    Path tmp;

    private MapProcessor processor;
    private final Object worldToken = new Object();
    private final List<Object[]> reports = new ArrayList<>();
    private long clockMillis = 1_000_000L;
    private boolean allow = true;
    private boolean ingestion = true;
    private XaeroSession bridge;
    private XaeroColumnStore store;
    private XaeroSession.PlayerPos player;
    private int setups;

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var biomeLookup = VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME);
        BIOMES = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        biomeLookup.listElements().forEach(ref -> BIOMES.register(ref.key(), ref.value(), RegistrationInfo.BUILT_IN));
        BIOMES.freeze();
        FACTORY = BIOMES;
        PLATFORMS = column(y -> y == 0 || (y & 15) == 8, false);
    }

    /** Netherrack where {@code solidAt}; with {@code rich}, block light near every platform,
     *  basalt-delta biomes in the upper half and an all-air section above the logical height. */
    private static VoxelColumnData column(IntPredicate solidAt, boolean rich) {
        var sections = new ArrayList<VoxelColumnData.SectionData>();
        Holder<Biome> deltas = BIOMES.getHolderOrThrow(Biomes.BASALT_DELTAS);
        for (int sy = 0; sy < (rich ? 9 : 8); sy++) {
            var section = SectionConstruction.empty(FACTORY);
            DataLayer light = rich ? new DataLayer() : null;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        int wy = (sy << 4) + y;
                        if (sy < 8 && solidAt.test(wy)) {
                            section.setBlockState(x, y, z, wy == 0 ? Blocks.BEDROCK.defaultBlockState()
                                    : (x + z) % 7 == 0 ? Blocks.GLOWSTONE.defaultBlockState()
                                    : Blocks.NETHERRACK.defaultBlockState());
                        }
                        if (light != null) light.set(x, y, z, (x * 3 + y + z) % 16);
                    }
                }
            }
            if (rich && sy >= 4) {
                @SuppressWarnings("unchecked")
                var biomes = (PalettedContainer<Holder<Biome>>) section.getBiomes();
                for (int i = 0; i < 4; i++) biomes.set(i, i & 3, (i + 1) & 3, deltas);
            }
            sections.add(new VoxelColumnData.SectionData(sy, section, light, null));
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
        this.allow = true;
        this.ingestion = true;
        this.bridge = newBridge(XaeroBindings.resolve(Class::forName));
        this.player = new XaeroSession.PlayerPos(NETHER, 64 * 16 + 8, 70, 64 * 16 + 8);
    }

    private XaeroSession newBridge(XaeroBindings h) {
        BooleanSupplier allowed = () -> this.allow;
        var b = new XaeroSession(h, new XaeroSession.LevelOps() {
            @Override
            public Object dimension(Object world) {
                return processor.mapWorld.currentDimensionId;
            }

            @Override
            public boolean isChunkLoaded(Object world, int chunkX, int chunkZ) {
                return false;
            }
        }, () -> this.ingestion, () -> true, c -> {}, c -> {}, () -> true,
                (dimension, chunkX, chunkZ) -> this.reports.add(new Object[]{dimension, chunkX, chunkZ}), allowed);
        b.pumpNanosBudget = Long.MAX_VALUE;
        b.updateNanosBudget = Long.MAX_VALUE;
        b.updateIdlePumps = 1_000_000; // keep rebuilds owed (not under test)
        b.pendingUpdatesSoftCap = 10_000;
        b.pendingUpdatesHardCap = 20_000;
        b.bpClock = () -> this.clockMillis;
        b.playerProbe = () -> this.player;
        b.serverLodDistance = () -> 64;
        b.storeBucketProbe = () -> "bucket";
        b.fillLevelProbe = () -> new XaeroBandFill.FillLevel(null, NETHER, "minecraft_the_nether", NETHER_FACTS,
                FACTORY, FP);
        this.store = new XaeroColumnStore(bucket -> this.tmp.resolve("s" + this.setups).resolve(bucket),
                () -> this.tmp.resolve("s" + this.setups), StoreCodec.zstdOrNull(), 64L << 20);
        b.fill.store = this.store;
        b.fill.enabled = true;
        return b;
    }

    @AfterEach
    void tearDown() {
        WorldMapSession.current = null;
        if (this.bridge != null) this.bridge.fill.reset();
        if (this.store != null) this.store.suspendForClear();
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

    private long count(String name) {
        return this.bridge.counterForTest(name);
    }

    private int stored() {
        this.store.flush();
        return this.store.columnsGauge;
    }

    /** Switch Xaero to {@code layer} and wait out the debounce: the fill job starts. */
    private void switchTo(int layer) {
        show(layer);
        this.clockMillis += this.bridge.fill.debounceMillis;
        this.bridge.pump();
    }

    /** Pump until the current fill job is done and its tiles committed (bounded). */
    private void runFill() throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            this.bridge.pump();
            var job = this.bridge.fill.lastJob;
            if (job != null && job.done && this.bridge.acquisition.fillQueuedGauge == 0) return;
            Thread.sleep(2);
        }
        throw new AssertionError("the fill did not finish: " + this.bridge.describe());
    }

    private MapRegion caveRegion(int layer) {
        var regions = this.processor.caveRegions.get(layer);
        return regions == null ? null : regions.get((2L << 32) | 2L);
    }

    /** The committed pixels of chunk (cx, 64) in a band region, rendered for comparison. */
    private String pixels(int layer, int chunkX) {
        var region = caveRegion(layer);
        assertNotNull(region, "band " + layer + " region");
        var tileChunk = region.getChunk((chunkX >> 2) & 7, (64 >> 2) & 7);
        assertNotNull(tileChunk);
        var tile = tileChunk.getTile(chunkX & 3, 0);
        assertNotNull(tile, "tile " + chunkX);
        var out = new StringBuilder();
        for (MapBlock[] row : tile.blocks) {
            for (MapBlock b : row) {
                out.append(b.state).append('|').append(b.height).append('|').append(b.topHeight).append('|')
                        .append(b.biome).append('|').append(b.light).append('|').append(b.glowing).append('|')
                        .append(b.cave).append('|').append(b.overlays.size()).append(';');
            }
        }
        return out.toString();
    }

    // ---- the store's gates ----

    @Test
    void aCeilingColumnIsKeptAndANonCeilingOneIsNot() {
        show(3);
        offer(64, 64, PLATFORMS);
        assertEquals(1, stored());
        this.bridge.offerColumn(OVERWORLD, 64, 64, OVERWORLD_FACTS, PLATFORMS);
        assertEquals(1, stored(), "no ceiling: B-I semantics, nothing kept");
    }

    @Test
    void nothingIsKeptOutsideTheWriteGatesOrInWorldSaveMode() {
        show(3);
        this.allow = false;
        offer(64, 64, PLATFORMS);
        assertEquals(0, stored(), "the server denies map ingestion");
        this.allow = true;
        this.ingestion = false;
        offer(64, 64, PLATFORMS);
        assertEquals(0, stored(), "local map ingestion is off");
        this.ingestion = true;
        this.player = new XaeroSession.PlayerPos(OVERWORLD, 0, 70, 0);
        offer(64, 64, PLATFORMS);
        assertEquals(0, stored(), "the player is not in that dimension");
        this.player = new XaeroSession.PlayerPos(NETHER, 64 * 16 + 8, 70, 64 * 16 + 8);
        this.processor.mapWorld.currentDimension.usingWorldSave = true;
        show(3);
        offer(64, 64, PLATFORMS);
        assertEquals(0, stored(), "world-save mode");
        this.processor.mapWorld.currentDimension.usingWorldSave = false;
        show(3);
        offer(64, 64, PLATFORMS);
        assertEquals(1, stored());
    }

    @Test
    void withoutTheCaveBindingsNothingIsKept() throws Exception {
        this.bridge = newBridge(XaeroBindings.resolve(name -> {
            if (name.equals("xaero.map.world.MapDimension")) throw new ClassNotFoundException(name);
            return Class.forName(name);
        }));
        assertNull(this.bridge.h.caveGate);
        show(3);
        offer(64, 64, PLATFORMS);
        assertEquals(0, stored());
    }

    @Test
    void theKillSwitchIsTheBIBehavior() {
        this.bridge.fill.enabled = false;
        show(3);
        offer(64, 64, PLATFORMS);
        assertEquals(0, stored());
        switchTo(5);
        assertEquals(0, count("fill_started"));
        assertTrue(this.bridge.describe().contains("band_fill=off"), this.bridge.describe());
    }

    // ---- the trigger ----

    @Test
    void aBandSwitchFillsTheNewBandFromTheStoreAfterTheDebounce() throws Exception {
        show(3);
        offer(64, 64, PLATFORMS);
        offer(65, 64, PLATFORMS);
        offer(66, 64, PLATFORMS);
        this.bridge.pump(); // live: the surface and band 3
        assertEquals(3, stored());
        assertNull(caveRegion(5));
        show(5);
        this.clockMillis += this.bridge.fill.debounceMillis - 1;
        this.bridge.pump();
        assertEquals(0, count("fill_started"), "not stable for the debounce yet");
        this.clockMillis += 1;
        this.bridge.pump();
        assertEquals(1, count("fill_started"), "band 5, once stable for the debounce");
        runFill();
        assertEquals(3, count("fill_committed"), "every stored position of band 5 written");
        assertNotNull(caveRegion(5));
        var line = this.bridge.describe();
        assertTrue(line.contains("band_fill=on, store=3 columns/") && line.contains(", band_fill_last=5 3/3 done"), line);
        assertFalse(line.contains("fill_age") || line.contains("capped_pumps") || line.contains("load_ms"),
                "spike instrumentation is exporter-only: " + line);
        assertEquals(0, count("fill_cancelled"));
        assertTrue(this.reports.isEmpty(), "fill tiles never report");
    }

    @Test
    void aLayerFlickerShorterThanTheDebounceStartsNothing() {
        show(3);
        offer(64, 64, PLATFORMS);
        this.bridge.pump();
        long before = count("fill_started");
        for (int i = 0; i < 5; i++) {
            show(4 + (i & 1));
            this.clockMillis += this.bridge.fill.debounceMillis / 2;
        }
        this.bridge.pump();
        assertEquals(before, count("fill_started"));
    }

    @Test
    void aSwitchADimensionChangeADenyAndASessionEndCancelTheJob() throws Exception {
        show(3);
        for (int x = 64; x < 72; x++) offer(x, 64, PLATFORMS);
        this.bridge.pump();
        this.bridge.fill.queueHigh = 1;
        this.processor.currentMapLocked = true; // nothing drains: the worker waits on pacing
        switchTo(5);
        awaitOffered(1);
        switchTo(6);
        assertEquals(1, count("fill_cancelled"), "the next switch");
        this.bridge.fill.queueHigh = 3; // band 6's job offers two (the band-5 tile still waits)
        awaitOffered(1);
        this.processor.mapWorld.currentDimensionId = OVERWORLD;
        this.bridge.pump();
        assertEquals(2, count("fill_cancelled"), "the dimension changed");
        this.processor.mapWorld.currentDimensionId = NETHER;
        switchTo(4); // waits at once: the queue is at the mark
        this.allow = false;
        this.bridge.pump();
        assertEquals(3, count("fill_cancelled"), "the server's deny");
        this.allow = true;
        this.bridge.fill.queueHigh = 1;
        switchTo(7);
        awaitOffered(1);
        this.bridge.onSessionEnd();
        this.bridge.pump();
        assertEquals(4, count("fill_cancelled"), "the session ended");
        this.processor.currentMapLocked = false;
    }

    private void awaitOffered(long atLeast) throws InterruptedException {
        long target = count("fill_tiles") + atLeast;
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (count("fill_tiles") < target) {
            if (System.nanoTime() > deadline) throw new AssertionError("no fill offer: " + this.bridge.describe());
            Thread.sleep(2);
        }
    }

    // ---- coverage ----

    @Test
    void positionsWrittenToTheLayerThisSessionAreSkippedAndAReServeInvalidatesThem() throws Exception {
        show(3);
        offer(64, 64, PLATFORMS);
        offer(65, 64, PLATFORMS);
        offer(66, 64, PLATFORMS);
        this.bridge.pump();
        switchTo(5);
        runFill();
        assertEquals(3, count("fill_committed"));
        switchTo(3);
        runFill();
        assertEquals(3, count("fill_committed"), "band 3 had every position live: nothing to fill");
        assertEquals(3, count("fill_skipped_covered"));
        offer(65, 64, PLATFORMS); // a re-serve while band 3 shows: band 5's copy is stale now
        this.bridge.pump();
        switchTo(5);
        runFill();
        assertEquals(4, count("fill_committed"), "only the re-served position refills");
    }

    // ---- review fold: the square, the gates, new passes, stale and bad columns ----

    @Test
    void theFillCoversTheServedSquareNotADisc() throws Exception {
        show(3);
        offer(64, 64, PLATFORMS);
        offer(128, 104, PLATFORMS); // Chebyshev 64 (the server's LOD distance), Euclidean 75
        offer(129, 64, PLATFORMS);  // Chebyshev 65: outside
        this.bridge.pump();
        assertEquals(3, stored());
        assertEquals(1, count("cave_out_of_range"), "the live band write is bounded the same way");
        switchTo(5);
        runFill();
        assertEquals(2, count("fill_committed"), "the square's corner fills, the column past it does not");
        var regions = this.processor.caveRegions.get(5);
        assertNotNull(regions.get((4L << 32) | 3L), "the corner column's band-5 region");
        assertNull(regions.get((4L << 32) | 2L), "nothing past the square");
        var metrics = new java.util.HashMap<String, Object>();
        this.bridge.fill.putMetrics(metrics);
        assertEquals(2, metrics.get("fill_target"), "the law's target is the square's stored count");
    }

    @Test
    void xaerosOwnWritesOffHoldsTheFill() throws Exception {
        show(3);
        for (int x = 64; x < 67; x++) offer(x, 64, PLATFORMS);
        this.bridge.pump();
        try {
            xaero.map.common.config.option.WorldMapProfiledConfigOptions.LOAD_NEW_CHUNKS.value = false;
            xaero.map.common.config.option.WorldMapProfiledConfigOptions.UPDATE_CHUNKS.value = false;
            this.bridge.pump(); // the ladder observes both switches off
            assertTrue(this.bridge.settingsWritesOff);
            assertFalse(this.bridge.fillRoom(), "no room for fill tiles either");
            switchTo(5);
            this.bridge.pump();
            assertEquals(0, count("fill_started"), "the user turned the map's writes off");
        } finally {
            xaero.map.common.config.option.WorldMapProfiledConfigOptions.LOAD_NEW_CHUNKS.value = true;
            xaero.map.common.config.option.WorldMapProfiledConfigOptions.UPDATE_CHUNKS.value = true;
        }
        this.bridge.pump(); // the ladder clears the flag
        this.bridge.pump(); // the trigger sees band 5 again: the debounce restarts
        this.clockMillis += this.bridge.fill.debounceMillis;
        this.bridge.pump();
        assertEquals(1, count("fill_started"));
        runFill();
        assertEquals(3, count("fill_committed"));
    }

    @Test
    void receiveOffStopsCapturesAndFillsButKeepsTheCoverage() throws Exception {
        boolean[] receive = {true};
        this.bridge.acquisitionActive = () -> receive[0];
        show(3);
        for (int x = 64; x < 67; x++) offer(x, 64, PLATFORMS);
        this.bridge.pump();
        switchTo(5);
        runFill();
        assertEquals(3, count("fill_committed"));
        receive[0] = false;
        offer(67, 64, PLATFORMS);
        assertEquals(3, stored(), "acquisition off: nothing captured");
        switchTo(6);
        this.bridge.pump();
        assertEquals(1, count("fill_started"), "acquisition off: no fill");
        receive[0] = true;
        switchTo(6);
        runFill();
        assertEquals(6, count("fill_committed"));
        switchTo(5);
        runFill();
        assertEquals(6, count("fill_committed"), "band 5's coverage survived Receive OFF");
    }

    @Test
    void aXaeroWorldChangeForgetsTheCoverageAndRestartsTheDebounce() throws Exception {
        show(3);
        for (int x = 64; x < 67; x++) offer(x, 64, PLATFORMS);
        this.bridge.pump();
        switchTo(5);
        runFill();
        assertEquals(3, count("fill_committed"));
        this.processor.currentWorldId = "another-world";
        this.bridge.pump(); // the ladder sees the new world id
        assertNull(this.bridge.fill.job, "the job named the old world");
        this.bridge.pump();
        assertEquals(1, count("fill_started"), "the debounce restarts");
        this.clockMillis += this.bridge.fill.debounceMillis;
        this.bridge.pump();
        assertEquals(2, count("fill_started"));
        runFill();
        assertEquals(6, count("fill_committed"), "the old world's coverage was forgotten");
    }

    @Test
    void aPlayerWhoMovedAQuarterRadiusGetsANewPass() throws Exception {
        show(3);
        offer(64, 64, PLATFORMS);
        offer(65, 64, PLATFORMS);
        offer(134, 64, PLATFORMS); // Chebyshev 70 from the first centre: outside its square
        this.bridge.pump();
        switchTo(5);
        runFill();
        assertEquals(2, count("fill_committed"));
        this.player = new XaeroSession.PlayerPos(NETHER, 80 * 16 + 8, 70, 64 * 16 + 8); // 16 chunks: not yet
        this.bridge.pump();
        assertEquals(1, count("fill_started"));
        this.player = new XaeroSession.PlayerPos(NETHER, 81 * 16 + 8, 70, 64 * 16 + 8); // 17 > 64/4
        this.bridge.pump();
        assertEquals(2, count("fill_started"));
        assertEquals(1, count("fill_moved_passes"));
        runFill();
        assertEquals(3, count("fill_committed"), "the new square's column; the done ones are skipped");
        assertEquals(2, count("fill_skipped_covered"));
    }

    @Test
    void aBadStoredColumnIsDroppedTheJobGoesOnAndAnUnfinishedPassReArmsOnce() throws Exception {
        show(3);
        offer(64, 64, PLATFORMS);
        offer(65, 64, PLATFORMS);
        this.bridge.pump();
        stored();
        // A record that passes its CRC but will not decode (another format, a bad id).
        this.store.capture("bucket", "minecraft_the_nether", FP, (66L << 32) | 64L, new byte[]{1, 2, 3, 4, 5});
        assertEquals(3, stored());
        switchTo(5);
        runFill();
        assertEquals(2, count("fill_committed"), "the job went on past the bad column");
        assertEquals(1, count("fill_bad_columns"));
        assertEquals(1, count("store_corrupt"));
        assertEquals(2, stored(), "dropped from the index");
        this.clockMillis += XaeroBandFill.REARM_AFTER_MILLIS - 1;
        this.bridge.pump();
        assertEquals(1, count("fill_started"));
        this.clockMillis += 1;
        this.bridge.pump();
        assertEquals(1, count("fill_rearms"), "2 of 3 written: one re-arm 30 s later");
        assertEquals(2, count("fill_started"));
        runFill();
        this.clockMillis += XaeroBandFill.REARM_AFTER_MILLIS;
        this.bridge.pump();
        assertEquals(2, count("fill_started"), "once per switch");
    }

    @Test
    void aDroppedCaptureLeavesTheOlderStoredCopyUnfilled() throws Exception {
        show(3);
        offer(64, 64, PLATFORMS);
        offer(65, 64, PLATFORMS);
        this.bridge.pump();
        assertEquals(2, stored());
        this.store.suspendForClear(); // the next capture of 65 drops: the store's copy is older
        offer(65, 64, column(y -> y == 0 || (y & 15) == 4, false));
        this.store.resumeAfterClear();
        switchTo(5);
        runFill();
        assertEquals(1, count("fill_committed"), "the stale position is skipped, never written old");
        offer(65, 64, PLATFORMS); // reaches the store: no longer stale (and band 5 is live)
        stored();
        switchTo(6);
        runFill();
        assertEquals(3, count("fill_committed"), "both positions fill band 6");
    }

    @Test
    void inSingleplayerNothingIsKeptBeforeThePlanIsKnown() {
        this.bridge.singleplayer = () -> true;
        offer(64, 64, PLATFORMS); // no plan yet: Xaero may be writing from the world save
        assertEquals(0, stored());
        show(3);
        offer(64, 64, PLATFORMS);
        assertEquals(1, stored());
    }

    @Test
    void theStoreSettingDetachesPurgesAndRecapsTheStore() throws Exception {
        var factoryStores = new ArrayList<XaeroColumnStore>();
        int[] mb = {256};
        this.bridge.fill.store = null;
        this.bridge.storeMbSetting = () -> mb[0];
        this.bridge.storeFactory = cap -> {
            this.store.setCapBytes(cap);
            factoryStores.add(this.store);
            return this.store;
        };
        show(3);
        assertTrue(this.bridge.fill.store == this.store);
        assertEquals(256L << 20, this.store.capBytes);
        offer(64, 64, PLATFORMS);
        assertEquals(1, stored());
        mb[0] = 0; // /lss reload with nether_store_mib: 0
        this.bridge.pump();
        assertNull(this.bridge.fill.store, "no store");
        this.store.flush();
        try (var files = java.nio.file.Files.walk(this.tmp)) {
            assertEquals(0, files.filter(f -> f.getFileName().toString().contains(".xfill.")).count(),
                    "the user's 0 gives the disk back");
        }
        switchTo(5);
        assertEquals(0, count("fill_started"), "and no fill on switch");
        assertTrue(this.bridge.describe().contains("band_fill=off"), this.bridge.describe());
        mb[0] = 64;
        this.bridge.pump();
        assertTrue(this.bridge.fill.store == this.store, "the same store, re-attached");
        assertEquals(64L << 20, this.store.capBytes);
        assertEquals(1, factoryStores.size(), "built once (one cache tenant)");
    }

    @Test
    void theExporterGroupCarriesTheRebuildAndSaveFigures() {
        this.processor.getMapSaveLoad().toSave.add(new MapRegion());
        this.processor.getMapSaveLoad().toSave.add(new MapRegion());
        show(3);
        this.processor.getMapSaveLoad().toSave.clear();
        show(3);
        var previous = XaeroSession.instance;
        XaeroSession.instance = this.bridge;
        try {
            var metrics = XaeroSession.metrics();
            assertEquals(0, metrics.get("to_save"), "Xaero's save queue, sampled every pump");
            assertEquals(2, metrics.get("to_save_max"));
            for (String key : List.of("buffer_updates", "dropped_updates", "pending_updates", "cave_out_of_range",
                    "dropped_stale_layer", "fill_bad_columns", "fill_rearms", "fill_moved_passes", "store_corrupt",
                    "load_ms_p95", "capped_pumps", "fill_age_ms")) {
                assertTrue(metrics.containsKey(key), key);
            }
        } finally {
            XaeroSession.instance = previous;
        }
    }

    // ---- pacing, lease-free, outside the backpressure figure ----

    @Test
    void fillOffersWaitBelowTheQueueMarkAndLiveArrivalsStillEnqueue() throws Exception {
        show(3);
        for (int x = 64; x < 72; x++) offer(x, 64, PLATFORMS);
        this.bridge.pump();
        this.bridge.fill.queueHigh = 3;
        this.processor.currentMapLocked = true;
        switchTo(5);
        awaitOffered(3);
        Thread.sleep(100);
        assertEquals(3, count("fill_queued"), "the worker waits at the mark");
        offer(80, 64, PLATFORMS);
        assertEquals(5, this.bridge.queuedForTest(), "a live column enqueues past the fill work");
        this.processor.currentMapLocked = false;
        runFill();
        assertEquals(8, count("fill_committed"));
    }

    @Test
    void fillTilesAreLeaseFreeAndNeverReadAsBackpressure() throws Exception {
        show(3);
        for (int x = 64; x < 68; x++) offer(x, 64, PLATFORMS);
        this.bridge.pump();
        this.processor.currentMapLocked = true;
        switchTo(5);
        awaitOffered(4);
        assertEquals(4, count("fill_queued"));
        assertEquals(0.0, this.bridge.acquisition.occupancy, 1e-9, "fill work is not LOD backpressure");
        assertEquals(0, this.bridge.reportBackpressure());
        // A live arrival of the same key takes the fill entry's place.
        offer(64, 64, PLATFORMS);
        assertEquals(3, count("fill_queued"));
        // The player leaves: the queued fill tiles drop as stale — no report, no debt.
        this.reports.clear();
        this.processor.currentMapLocked = false;
        this.processor.mapWorld.currentDimensionId = OVERWORLD;
        this.bridge.pump();
        assertEquals(0, count("fill_queued"));
        assertEquals(1, this.reports.size(), "only the live column's stale drop reports");
        assertEquals(0, count("owed"));
    }

    // ---- parity ----

    @Test
    void aStoredColumnExtractsExactlyLikeTheLiveOne() {
        var column = column(y -> y == 0 || (y & 15) == 8 || (y > 40 && y < 44) || y > 120, true);
        var back = XaeroColumnCodec.decode(XaeroColumnCodec.encode(column, NETHER_FACTS), NETHER_FACTS, FACTORY);
        var targets = List.of(XaeroTileExtractor.LayerTarget.surface(30), XaeroTileExtractor.LayerTarget.band(2, 255, 30),
                XaeroTileExtractor.LayerTarget.band(5, 255, 12), XaeroTileExtractor.LayerTarget.full(30));
        for (boolean ignoreHeightmaps : new boolean[]{false, true}) {
            var live = XaeroTileExtractor.extract(64, 64, NETHER_FACTS, column, targets, ignoreHeightmaps);
            var filled = XaeroTileExtractor.extract(64, 64, NETHER_FACTS, back, targets, ignoreHeightmaps);
            for (int i = 0; i < targets.size(); i++) {
                var a = live.get(i);
                var b = filled.get(i);
                String what = targets.get(i) + " ignoreHeightmaps=" + ignoreHeightmaps;
                assertArrayEquals(a.floorState(), b.floorState(), what);
                assertArrayEquals(a.floorY(), b.floorY(), what);
                assertArrayEquals(a.topY(), b.topY(), what);
                assertArrayEquals(a.biome(), b.biome(), what);
                assertArrayEquals(a.light(), b.light(), what);
                assertArrayEquals(a.glowing(), b.glowing(), what);
                assertEquals(java.util.Arrays.deepToString(a.overlays()), java.util.Arrays.deepToString(b.overlays()), what);
                assertEquals(a.caveStart(), b.caveStart(), what);
            }
        }
    }

    @Test
    void aFilledTileCommitsTheSamePixelsAsALiveArrivalInThatLayer() throws Exception {
        var column = column(y -> y == 0 || (y & 15) == 8 || (y > 70 && y < 74), true);
        show(5); // live: band 5 shown on arrival
        offer(64, 64, column);
        this.bridge.pump();
        String live = pixels(5, 64);
        tearDown();
        this.setups++;
        setUp();
        show(3); // the same column arrives under band 3, band 5 is filled on the switch
        offer(64, 64, column);
        this.bridge.pump();
        switchTo(5);
        runFill();
        assertEquals(1, count("fill_committed"));
        assertEquals(live, pixels(5, 64));
    }
}
