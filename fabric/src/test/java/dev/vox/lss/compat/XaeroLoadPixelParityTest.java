package dev.vox.lss.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.mojang.serialization.Lifecycle;
import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.platform.SectionConstruction;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

/**
 * The parity oracle for the extractor (cave-layers plan §B9): the REAL {@code MapWriter.loadPixel}
 * of this line's Xaero jar, loaded child-first and run with {@code writeChunk}'s per-pixel
 * arguments, against {@link XaeroTileExtractor} on the same column — every {@code MapBlock} field
 * per pixel (state, height, topHeight, biome, light, glowing, overlays). The writer's public
 * {@code shouldOverlay} is overridden with the extractor's documented approximation (Xaero's
 * model-quad translucency needs a live model manager); the {@code Level}/{@code LevelChunk} it
 * reads are Mockito mocks fed from the column, with the extractor's documented light rule
 * across sections the column does not carry (the real light engine is not reproducible). An
 * overlay fixture also mocks {@code Minecraft.getBlockRenderer()} (1.21.x; 26.3 reads the model manager) for the overlay builder's
 * material check (one distinct material per block, which is the extractor's run rule).
 *
 * <p>Biome: Xaero answers its {@code UNKNOWN_BIOME} where the column has no section, the
 * extractor null (both mean "no biome data"; the commit's {@code write} keeps a null biome
 * unset) — compared as equal.
 */
class XaeroLoadPixelParityTest {
    private static final XaeroTileExtractor.LevelFacts NETHER = new XaeroTileExtractor.LevelFacts(0, 256, false, true, 128);
    private static final XaeroTileExtractor.LevelFacts OVERWORLD = new XaeroTileExtractor.LevelFacts(-64, 320, true, false, 384);
    private static final int CHUNK_X = 5;
    private static final int CHUNK_Z = -3;

    // LINE FLAVOR (1.21.1): no palette factory type on this MC — the SectionConstruction
    // seam's construction parameter is the biome Registry (see that class's javadoc).
    private static net.minecraft.core.Registry<Biome> FACTORY;
    private static HolderLookup.RegistryLookup<Biome> BIOME_LOOKUP;
    private static MappedRegistry<Biome> BIOMES;
    private static URLClassLoader loader;
    private static Object writer;
    private static Method loadPixel;
    private static Class<?> blockClass;
    private static Object unknownBiome;

    @BeforeAll
    static void setup(@TempDir Path scratch) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        bindBlockTagsIfUnbound();
        HolderLookup.Provider provider = VanillaRegistries.createLookup();
        BIOME_LOOKUP = provider.lookupOrThrow(Registries.BIOME);
        BIOMES = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        BIOME_LOOKUP.listElements().forEach(ref -> BIOMES.register(ref.key(), ref.value(), RegistrationInfo.BUILT_IN));
        BIOMES.freeze();
        FACTORY = BIOMES;

        // LINE FLAVOR (1.21.x): the Fabric jar is intermediary-mapped (its Minecraft references
        // are class_NNNN, absent from this Mojang-named test classpath), so the oracle runs the
        // NeoForge build of the same Xaero version — Mojang-named, and its MapWriter references
        // no NeoForge type (javap-verified; loadPixel is the same bytecode modulo names).
        loader = XaeroGolden.loader(XaeroGolden.jar("lss.xaeroGoldenNeoForgeJar"), scratch);
        Class<?> writerClass = loader.loadClass("xaero.map.MapWriter");
        blockClass = loader.loadClass("xaero.map.region.MapBlock");
        Object overlayManager = loader.loadClass("xaero.map.region.OverlayManager").getConstructor().newInstance();
        Object shapeCache = loader.loadClass("xaero.map.cache.BlockStateShortShapeCache").getConstructor().newInstance();
        Object biomeGetter = loader.loadClass("xaero.map.biome.BiomeGetter").getConstructor().newInstance();
        unknownBiome = biomeGetter.getClass().getField("UNKNOWN_BIOME").get(biomeGetter);
        Answer<Object> overrideShouldOverlay = invocation -> {
            if (invocation.getMethod().getName().equals("shouldOverlay")) {
                Object holder = invocation.getArgument(0);
                return holder instanceof FluidState fluid
                        ? XaeroTileExtractor.fluidOverlayable(fluid)
                        : XaeroTileExtractor.blockOverlayable((BlockState) holder);
            }
            return invocation.callRealMethod();
        };
        // stubOnly (here and on the per-fixture Level/LevelChunk doubles): nothing verifies
        // them, and a recording writer would retain every loadPixel invocation — with the
        // fixture doubles in its arguments and THEIR recorded calls — for the whole executor
        // (the test-flakes catalog's test-double heap exhaustion; the 1.21.11 port tripped
        // the default test heap with it).
        writer = Mockito.mock(writerClass, withSettings()
                .stubOnly()
                .useConstructor(overlayManager, shapeCache, biomeGetter)
                .defaultAnswer(overrideShouldOverlay));
        loadPixel = writerClass.getMethod("loadPixel", Level.class, Registry.class, blockClass, blockClass,
                LevelChunk.class, int.class, int.class, int.class, int.class, boolean.class, boolean.class,
                int.class, boolean.class, boolean.class, Registry.class, boolean.class, int.class,
                BlockPos.MutableBlockPos.class);
    }

    @AfterAll
    static void close() throws Exception {
        if (writer != null) Mockito.framework().clearInlineMock(writer);
        writer = null;
        if (loader != null) loader.close();
    }

    /** Xaero's isInvisible reads the FLOWERS tag on every visible block. LINE FLAVOR (1.21.1):
     *  a holder's tag set starts EMPTY here (1.21.2+ throws "Tags not bound" until a reload), so
     *  the Tier-1 JVM's unloaded tag reads already answer false — for Xaero and for the
     *  extractor's mirror alike; nothing to bind. */
    private static void bindBlockTagsIfUnbound() {
        if (Blocks.STONE.defaultBlockState().is(BlockTags.FLOWERS)) {
            throw new AssertionError("the parity oracle expects the Tier-1 JVM's tags unloaded");
        }
    }

    // ---- column fixtures ----

    @FunctionalInterface
    interface Blocks3 {
        BlockState at(int x, int y, int z);
    }

    @FunctionalInterface
    interface Light3 {
        int at(int x, int y, int z);
    }

    /** A column as LSS would carry it: sections with blocks, plus the given light layers;
     *  all-air sections without light are culled, exactly like the serializer. */
    private record Fixture(String name, XaeroTileExtractor.LevelFacts level, VoxelColumnData column,
                           XaeroTileExtractor.LayerTarget target, boolean ignoreHeightmaps) {}

    private static VoxelColumnData column(XaeroTileExtractor.LevelFacts level, Blocks3 blocks,
                                          Light3 blockLight, Light3 skyLight, ResourceKey<Biome> biome) {
        var out = new ArrayList<VoxelColumnData.SectionData>();
        Holder<Biome> holder = BIOME_LOOKUP.getOrThrow(biome);
        Holder<Biome> deep = BIOME_LOOKUP.getOrThrow(Biomes.DEEP_DARK);
        for (int sy = level.minY() >> 4; sy <= (level.topY() - 1) >> 4; sy++) {
            LevelChunkSection section = SectionConstruction.empty(FACTORY);
            boolean any = false;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = blocks.at(x, (sy << 4) + y, z);
                        if (!state.isAir()) {
                            section.setBlockState(x, y, z, state);
                            any = true;
                        }
                    }
                }
            }
            @SuppressWarnings("unchecked")
            var biomes = (PalettedContainer<Holder<Biome>>) section.getBiomes();
            for (int qx = 0; qx < 4; qx++) {
                for (int qy = 0; qy < 4; qy++) {
                    for (int qz = 0; qz < 4; qz++) {
                        biomes.getAndSet(qx, qy, qz, (sy + qx + qz) % 3 == 0 ? deep : holder);
                    }
                }
            }
            DataLayer bl = layer(sy, blockLight);
            DataLayer sl = layer(sy, skyLight);
            if (!any && bl == null && sl == null) continue;
            out.add(new VoxelColumnData.SectionData(sy, section, bl, sl));
        }
        return new VoxelColumnData(out.toArray(new VoxelColumnData.SectionData[0]), 1L);
    }

    /** The section's layer, or null when the function is absent or the layer holds no light
     *  (LSS serves an all-zero layer as null). */
    private static DataLayer layer(int sectionY, Light3 light) {
        if (light == null) return null;
        var layer = new DataLayer();
        boolean any = false;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    int v = light.at(x, (sectionY << 4) + y, z);
                    if (v != 0) {
                        layer.set(x, y, z, v);
                        any = true;
                    }
                }
            }
        }
        return any ? layer : null;
    }

    private static BlockState air() {
        return Blocks.AIR.defaultBlockState();
    }

    private static BlockState s(Block block) {
        return block.defaultBlockState();
    }

    // ---- the oracle ----

    /** Run Xaero's loadPixel for every pixel with writeChunk's arguments and compare;
     *  returns the extractor's tile for the fixture's own non-vacuity checks. */
    private static XaeroTileExtractor.PreparedTile assertParity(Fixture f) throws Exception {
        var level = f.level();
        var sections = new HashMap<Integer, VoxelColumnData.SectionData>();
        int highestNonAir = Integer.MIN_VALUE;
        int highestLit = Integer.MIN_VALUE;
        for (var sd : f.column().sections()) {
            sections.put(sd.sectionY(), sd);
            if (!sd.section().hasOnlyAir()) highestNonAir = Math.max(highestNonAir, sd.sectionY());
            if (!sd.section().hasOnlyAir() || sd.skyLight() != null) highestLit = Math.max(highestLit, sd.sectionY());
        }
        final int topLit = highestLit;
        Level world = mock(Level.class, withSettings().stubOnly());
        DimensionType type = mock(DimensionType.class, withSettings().stubOnly());
        when(type.hasSkyLight()).thenReturn(level.skyLight());
        when(world.dimensionType()).thenReturn(type);
        when(world.getBrightness(any(), any())).thenAnswer(inv -> {
            LightLayer which = inv.getArgument(0);
            BlockPos pos = inv.getArgument(1);
            var sd = sections.get(pos.getY() >> 4);
            DataLayer data = sd == null ? null : which == LightLayer.BLOCK ? sd.blockLight() : sd.skyLight();
            if (data != null) return data.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
            return which == LightLayer.SKY && (pos.getY() >> 4) > topLit ? 15 : 0;
        });
        when(world.getBiome(any())).thenAnswer(inv -> {
            BlockPos pos = inv.getArgument(0);
            var sd = sections.get(pos.getY() >> 4);
            return sd == null ? null : sd.section().getNoiseBiome((pos.getX() & 15) >> 2, (pos.getY() & 15) >> 2,
                    (pos.getZ() & 15) >> 2);
        });
        LevelChunk chunk = mock(LevelChunk.class, withSettings().stubOnly());
        when(chunk.getPos()).thenReturn(new ChunkPos(CHUNK_X, CHUNK_Z));
        when(chunk.getBlockState(any(BlockPos.class))).thenAnswer(inv -> {
            BlockPos pos = inv.getArgument(0);
            var sd = sections.get(pos.getY() >> 4);
            return sd == null ? air() : sd.section().getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        });

        var target = f.target();
        var tiles = XaeroTileExtractor.extract(CHUNK_X, CHUNK_Z, level, f.column(), List.of(target), f.ignoreHeightmaps());
        assertEquals(1, tiles.size(), f.name() + ": a reliable target always yields its tile");
        var tile = tiles.get(0);
        assertEquals(target.layer(), tile.layer());
        assertEquals(target.caveStart(), tile.caveStart());
        assertEquals(target.depth(), tile.depth());

        boolean cave = target.caveStart() != XaeroTileExtractor.SURFACE;
        boolean fullCave = target.caveStart() == XaeroTileExtractor.FULL;
        int minY = level.minY();
        int lowH = minY;
        if (cave && !fullCave) lowH = Math.max(minY, target.caveStart() + 1 - target.depth());
        int sectionBased = highestNonAir == Integer.MIN_VALUE ? 0 : (highestNonAir << 4) + 15;
        var mismatches = new ArrayList<String>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int mapped = minY - 1;
                for (int y = level.topY() - 1; y >= minY; y--) {
                    var sd = sections.get(y >> 4);
                    if (sd != null && !sd.section().getBlockState(x, y & 15, z).isAir()) {
                        mapped = y;
                        break;
                    }
                }
                int start;
                if (cave && !fullCave) start = target.caveStart();
                else if (f.ignoreHeightmaps() || mapped < minY) start = sectionBased;
                else start = mapped;
                if (start >= level.topY()) start = level.topY() - 1;
                Object pixel = blockClass.getConstructor().newInstance();
                loadPixel.invoke(writer, world, BuiltInRegistries.BLOCK, pixel, null, chunk, x, z, start, lowH,
                        cave, fullCave, mapped, false, f.ignoreHeightmaps(), BIOMES, true, minY,
                        new BlockPos.MutableBlockPos());
                compare(f.name(), x, z, pixel, tile, mismatches);
            }
        }
        assertTrue(mismatches.isEmpty(), f.name() + ": " + mismatches.size() + " mismatch(es):\n"
                + String.join("\n", mismatches.subList(0, Math.min(12, mismatches.size()))));
        return tile;
    }

    private static int count(XaeroTileExtractor.PreparedTile tile, java.util.function.IntPredicate pixel) {
        int n = 0;
        for (int i = 0; i < 256; i++) if (pixel.test(i)) n++;
        return n;
    }

    private static void compare(String name, int x, int z, Object pixel, XaeroTileExtractor.PreparedTile tile,
                                List<String> out) throws Exception {
        int i = x * 16 + z;
        BlockState state = (BlockState) call(pixel, "getState");
        int height = (int) call(pixel, "getHeight");
        int topHeight = (int) call(pixel, "getTopHeight");
        Object biome = call(pixel, "getBiome");
        byte light = (byte) field(pixel, "light");
        boolean glowing = (boolean) field(pixel, "glowing");
        String at = "(" + x + "," + z + ") ";
        if (state != tile.floorState()[i]) out.add(at + "state " + state + " vs " + tile.floorState()[i]);
        if (height != tile.floorY()[i]) out.add(at + "height " + height + " vs " + tile.floorY()[i]);
        if (topHeight != tile.topY()[i]) out.add(at + "topHeight " + topHeight + " vs " + tile.topY()[i]);
        Object expectedBiome = biome == unknownBiome ? null : biome;
        if (!java.util.Objects.equals(expectedBiome, tile.biome()[i])) {
            out.add(at + "biome " + biome + " vs " + tile.biome()[i]);
        }
        if (light != tile.light()[i]) out.add(at + "light " + light + " vs " + tile.light()[i]);
        if (glowing != tile.glowing()[i]) out.add(at + "glowing " + glowing + " vs " + tile.glowing()[i]);
        @SuppressWarnings("unchecked")
        var overlays = (List<Object>) call(pixel, "getOverlays");
        var runs = tile.overlays()[i];
        int n = overlays == null ? 0 : overlays.size();
        int m = runs == null ? 0 : runs.length;
        if (n != m) {
            out.add(at + "overlays " + n + " vs " + m);
            return;
        }
        for (int k = 0; k < n; k++) {
            Object o = overlays.get(k);
            var run = runs[k];
            if (call(o, "getState") != run.state()) out.add(at + "overlay" + k + " state " + call(o, "getState") + " vs " + run.state());
            if ((byte) field(o, "light") != run.light()) out.add(at + "overlay" + k + " light " + field(o, "light") + " vs " + run.light());
            // The commit's increaseOpacity clamps to 15 exactly like the per-block native adds.
            if ((int) call(o, "getOpacity") != Math.min(15, run.opacity())) {
                out.add(at + "overlay" + k + " opacity " + call(o, "getOpacity") + " vs " + run.opacity());
            }
        }
    }

    private static Object call(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }

    private static Object field(Object target, String name) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException next) {
                // walk up to MapPixel
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** Overlay fixtures: the builder's sprite check reads the block model shaper — one distinct
     *  particle icon per block. LINE FLAVOR (1.21.x): Xaero's OverlayBuilder reads
     *  {@code getBlockRenderer().getBlockModelShaper().getParticleIcon(state)} (26.3: the model
     *  manager's {@code BlockStateModelSet.getParticleMaterial}). */
    private static void withModelManager(ThrowingRunnable body) throws Exception {
        var sprites = new HashMap<Block, TextureAtlasSprite>();
        BlockModelShaper shaper = mock(BlockModelShaper.class);
        when(shaper.getParticleIcon(any())).thenAnswer(inv ->
                sprites.computeIfAbsent(((BlockState) inv.getArgument(0)).getBlock(), b -> mock(TextureAtlasSprite.class)));
        BlockRenderDispatcher dispatcher = mock(BlockRenderDispatcher.class);
        when(dispatcher.getBlockModelShaper()).thenReturn(shaper);
        Minecraft minecraft = mock(Minecraft.class);
        when(minecraft.getBlockRenderer()).thenReturn(dispatcher);
        try (var statics = mockStatic(Minecraft.class)) {
            statics.when(Minecraft::getInstance).thenReturn(minecraft);
            body.run();
        }
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    // ---- shared Nether-shaped column: floor, open cave, thick roof, bedrock ceiling ----

    /** Netherrack floor topping out at 60..67 (by z), air, a roof from 75−(x&3) up to 126, bedrock 127. */
    private static BlockState netherCave(int x, int y, int z) {
        if (y == 0 || y == 127) return s(Blocks.BEDROCK);
        if (y > 127) return air();
        if (y <= 60 + (z & 7)) return s(Blocks.NETHERRACK);
        if (y >= 75 - (x & 3)) return s(Blocks.NETHERRACK);
        return air();
    }

    private static Fixture nether(String name, Blocks3 blocks, XaeroTileExtractor.LayerTarget target) {
        return new Fixture(name, NETHER, column(NETHER, blocks, (x, y, z) -> 0, null, Biomes.NETHER_WASTES), target, false);
    }

    private static XaeroTileExtractor.LayerTarget band(int band, XaeroTileExtractor.LevelFacts level) {
        return XaeroTileExtractor.LayerTarget.band(band, level.topY() - 1, 30);
    }

    // ---- roofs ----

    @Test
    void caveStartInsideTheRoof() throws Exception {
        // band 5 = caveStart 95, window [66, 95]: the roof skip runs down to the cave air
        // (72..75), and only the pixels whose floor reaches 66 floor; the rest are void
        // with topHeight 66.
        var tile = assertParity(nether("caveStart inside the roof", XaeroLoadPixelParityTest::netherCave, band(5, NETHER)));
        assertEquals(64, count(tile, i -> tile.floorState()[i] != air()), "z&7 in {6,7} floor at 66/67");
        assertEquals(192, count(tile, i -> tile.floorState()[i] == air() && tile.topY()[i] == 66), "the rest is void at lowY");
    }

    @Test
    void caveStartInTheCaveAir() throws Exception {
        // band 4 = caveStart 79 with the roof raised to 85: the window starts in air.
        assertParity(nether("caveStart in air", (x, y, z) -> y >= 70 && y < 85 ? air() : netherCave(x, y, z),
                band(4, NETHER)));
    }

    @Test
    void aLavaPocketEndsTheRoofSkip() throws Exception {
        // A lava pocket inside the roof (x < 8, y 86..88): the fluid ends the roof skip and
        // floors the pixel there; the rest skip on to the cave below.
        var tile = assertParity(nether("lava pocket in the roof", (x, y, z) -> x < 8 && y >= 86 && y <= 88
                ? s(Blocks.LAVA) : netherCave(x, y, z), band(5, NETHER)));
        assertEquals(128, count(tile, i -> tile.floorState()[i].getBlock() == Blocks.LAVA && tile.floorY()[i] == 88));
    }

    @Test
    void aWaterloggedRoofBlockBecomesAnOverlayOverTheRoof() throws Exception {
        // A waterlogged slab in the roof (z < 8, y 90): the fluid branch sets underair, the
        // water is an overlay, and the next roof block below is the floor.
        BlockState waterlogged = Blocks.STONE_SLAB.defaultBlockState()
                .setValue(SlabBlock.WATERLOGGED, true);
        withModelManager(() -> {
            var tile = assertParity(nether("waterlogged roof",
                    (x, y, z) -> z < 8 && y == 90 ? waterlogged : netherCave(x, y, z), band(5, NETHER)));
            assertEquals(128, count(tile, i -> tile.overlays()[i] != null && tile.floorY()[i] == 90),
                    "the waterlogged slab floors under its own water overlay");
        });
    }

    // ---- the depth window ----

    @Test
    void theFloorAtLowYFloorsAndOneBelowIsVoid() throws Exception {
        // band 3 = caveStart 63, lowY 34: the floor tops out at 34 for x < 8 (visible) and 33
        // for x >= 8 (void, topHeight 34, biome sampled at 34).
        assertParity(nether("floor at lowY / lowY-1", (x, y, z) -> {
            if (y == 0) return s(Blocks.BEDROCK);
            if (y <= (x < 8 ? 34 : 33)) return s(Blocks.SOUL_SAND);
            if (y >= 100 && y < 128) return s(Blocks.NETHERRACK);
            return air();
        }, band(3, NETHER)));
    }

    @Test
    void theWindowClampsAtTheWorldBottom() throws Exception {
        // band 0 = caveStart 15: lowY 15+1-30 = -14 clamps to minY 0 (the bedrock floor).
        assertParity(nether("minY clamp", (x, y, z) -> y == 0 ? s(Blocks.BEDROCK)
                : y >= 12 + (x & 3) && y < 128 ? s(Blocks.NETHERRACK) : air(), band(0, NETHER)));
        // ...and a negative band in the Overworld: band -4 = caveStart -49, lowY clamps to -64.
        assertParity(new Fixture("negative band clamp", OVERWORLD, column(OVERWORLD, (x, y, z) ->
                y == -64 ? s(Blocks.BEDROCK) : y < -60 + (z & 3) ? s(Blocks.DEEPSLATE)
                        : y >= -45 && y < 64 ? s(Blocks.DEEPSLATE) : air(), null, null, Biomes.PLAINS),
                band(-4, OVERWORLD), false));
    }

    // ---- Full mode ----

    /** Overworld terrain over a cave (y 20..30): stone, then the per-fixture surface above 60. */
    private static Fixture full(String name, Blocks3 surface, boolean ignoreHeightmaps) {
        Blocks3 blocks = (x, y, z) -> {
            if (y == -64) return s(Blocks.BEDROCK);
            if (y >= 20 && y <= 30 - (x & 3)) return air();
            if (y < 60) return z < 4 && y <= 10 && y > 0 ? air() : s(Blocks.STONE);
            return surface.at(x, y, z);
        };
        return new Fixture(name, OVERWORLD, column(OVERWORLD, blocks, null,
                (x, y, z) -> y >= 61 ? 15 : 0, Biomes.FOREST),
                XaeroTileExtractor.LayerTarget.full(30), ignoreHeightmaps);
    }

    @Test
    void fullModeSkipsGrassLogsLeavesAndSnowToTheCaveBelow() throws Exception {
        Blocks3 grass = (x, y, z) -> y == 60 ? s(Blocks.GRASS_BLOCK) : air();
        assertParity(full("full: grass", grass, false));
        assertParity(full("full: log + leaves", (x, y, z) -> {
            if (y == 60) return s(Blocks.GRASS_BLOCK);
            if (x == 8 && z == 8 && y <= 66) return s(Blocks.OAK_LOG);
            if (y >= 64 && y <= 68 && x > 4 && x < 12) return s(Blocks.OAK_LEAVES);
            return air();
        }, false));
        assertParity(full("full: snow layer", (x, y, z) -> y == 60 ? s(Blocks.GRASS_BLOCK)
                : y == 61 ? Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 1 + (x & 3)) : air(), false));
        assertParity(full("full: ignored heightmaps", grass, true));
    }

    @Test
    void fullModeIgnoresTheOceanUntilItsGround() throws Exception {
        withModelManager(() -> assertParity(full("full: ocean", (x, y, z) -> {
            if (y == 60) return s(Blocks.SAND);
            if (y <= 72) return s(Blocks.WATER);
            return air();
        }, false)));
    }

    // ---- light ----

    @Test
    void theSkyShortcutLightsAWindowAboveTheSurface() throws Exception {
        // band 4 = caveStart 79 over terrain at 60..67: highY >= the WORLD_SURFACE height, so
        // the sky reads 15 without a lookup (and the overlay-free floor takes it); the x=15
        // pillar rises above the window top, so its pixels take the lookup path instead.
        assertParity(new Fixture("sky shortcut", OVERWORLD, column(OVERWORLD,
                (x, y, z) -> y <= (x == 15 ? 90 : 60 + (z & 7)) ? s(Blocks.GRASS_BLOCK) : air(),
                (x, y, z) -> y == 61 + (z & 7) && x < 4 ? 7 : 0, null, Biomes.PLAINS), band(4, OVERWORLD), false));
    }

    @Test
    void skyLightAtTheFloorWithAndWithoutOverlays() throws Exception {
        // A cave (40..60) under a roof (61..70), lit by sky through its section's layer, with a
        // pool on half its floor: an overlay-free floor takes max(block, sky); with an overlay
        // the FIRST overlay takes it and the floor keeps block light.
        Blocks3 blocks = (x, y, z) -> {
            if (y < 40) return s(Blocks.STONE);
            if (y == 40 && x < 8) return s(Blocks.WATER);
            if (y <= 60) return air();
            if (y <= 70) return s(Blocks.STONE);
            return air();
        };
        Light3 sky = (x, y, z) -> y >= 71 ? 15 : y >= 40 && y <= 60 ? 3 + (z & 7) : 0;
        Light3 block = (x, y, z) -> y == 41 && z < 4 ? 12 : 0;
        withModelManager(() -> {
            var tile = assertParity(new Fixture("sky light, overlays", OVERWORLD,
                    column(OVERWORLD, blocks, block, sky, Biomes.PLAINS), band(3, OVERWORLD), false));
            assertTrue(count(tile, i -> tile.overlays()[i] == null && tile.light()[i] > 0) > 0,
                    "overlay-free floors take the sky");
            assertTrue(count(tile, i -> tile.overlays()[i] != null && tile.light()[i] == 0
                    && tile.overlays()[i][0].light() > 0) > 0, "with an overlay the first overlay takes it");
        });
    }

    // ---- band edges ----

    @Test
    void bandEdges() throws Exception {
        // Xaero's arithmetic shift: caveStart -5 is layer -1, not 0.
        assertEquals(-1, XaeroTileExtractor.caveLayerOf(-5));
        assertEquals(0, XaeroTileExtractor.caveLayerOf(15));
        assertEquals(XaeroTileExtractor.SURFACE, XaeroTileExtractor.caveLayerOf(XaeroTileExtractor.SURFACE));
        assertEquals(XaeroTileExtractor.FULL, XaeroTileExtractor.caveLayerOf(XaeroTileExtractor.FULL));
        Blocks3 deep = (x, y, z) -> y == -64 ? s(Blocks.BEDROCK) : y <= -20 + (x & 7) ? s(Blocks.DEEPSLATE)
                : y < 10 ? air() : y < 64 ? s(Blocks.STONE) : air();
        // An exact native caveStart (-5, the band -1 window [-34, -5]).
        assertParity(new Fixture("caveStart -5", OVERWORLD, column(OVERWORLD, deep, null, null, Biomes.PLAINS),
                new XaeroTileExtractor.LayerTarget(-1, -5, 30), false));
        // The top band of each shape: caveStart clamps to maxY.
        assertEquals(255, band(15, NETHER).caveStart());
        assertEquals(319, band(19, OVERWORLD).caveStart());
        assertParity(nether("top band", XaeroLoadPixelParityTest::netherCave, band(15, NETHER)));
        assertParity(new Fixture("top band, overworld", OVERWORLD, column(OVERWORLD,
                (x, y, z) -> y <= 290 + x ? s(Blocks.STONE) : air(), null, null, Biomes.PLAINS),
                band(19, OVERWORLD), false));
    }

    // ---- the surface path, against the same oracle ----

    @Test
    void theSurfacePathMatchesTheOracleToo() throws Exception {
        assertParity(new Fixture("surface: nether roof", NETHER,
                column(NETHER, XaeroLoadPixelParityTest::netherCave, null, null, Biomes.NETHER_WASTES),
                XaeroTileExtractor.LayerTarget.surface(30), false));
        assertParity(full("surface-shaped column, Full", (x, y, z) -> y == 60 ? s(Blocks.GRASS_BLOCK) : air(), false));
        withModelManager(() -> assertParity(new Fixture("surface: shallow sea", OVERWORLD,
                column(OVERWORLD, (x, y, z) -> y < 58 + (x & 1) ? s(Blocks.SAND) : y <= 62 ? s(Blocks.WATER) : air(),
                        (x, y, z) -> y == 63 && z == 0 ? 9 : 0, null, Biomes.OCEAN),
                XaeroTileExtractor.LayerTarget.surface(30), false)));
    }

    @Test
    void aBandBuriedInRockErases() {
        Blocks3 solid = (x, y, z) -> y < 128 ? s(Blocks.NETHERRACK) : air();
        var column = column(NETHER, solid, null, null, Biomes.NETHER_WASTES);
        var tiles = XaeroTileExtractor.extract(0, 0, NETHER, column, List.of(band(4, NETHER)), false);
        assertEquals(1, tiles.size(), "every target yields its tile");
        for (int i = 0; i < 256; i++) {
            assertEquals(Blocks.AIR.defaultBlockState(), tiles.get(0).floorState()[i], "the void erases");
            assertEquals(50, tiles.get(0).topY()[i], "void topHeight = lowY (79 + 1 - 30)");
        }
    }

    @Test
    void oneDecodeServesEveryTargetAndTheSurfaceIsUnchanged() {
        var column = column(NETHER, XaeroLoadPixelParityTest::netherCave, null, null, Biomes.NETHER_WASTES);
        var tiles = XaeroTileExtractor.extract(CHUNK_X, CHUNK_Z, NETHER, column,
                List.of(XaeroTileExtractor.LayerTarget.surface(30), band(4, NETHER),
                        XaeroTileExtractor.LayerTarget.full(30)), false);
        assertEquals(3, tiles.size());
        var legacy = XaeroTileExtractor.extract(CHUNK_X, CHUNK_Z, NETHER.minY(), NETHER.topY(), column);
        var surface = tiles.get(0);
        assertTrue(java.util.Arrays.equals(legacy.floorState(), surface.floorState())
                && java.util.Arrays.equals(legacy.floorY(), surface.floorY())
                && java.util.Arrays.equals(legacy.topY(), surface.topY())
                && java.util.Arrays.equals(legacy.biome(), surface.biome())
                && java.util.Arrays.equals(legacy.light(), surface.light()),
                "the surface tile is the pre-cave extraction, byte for byte");
        assertTrue(!surface.cave() && tiles.get(1).cave() && tiles.get(2).cave());
        assertEquals(4, tiles.get(1).layer());
        assertEquals(79, tiles.get(1).caveStart(), "band tiles carry the band top");
        assertEquals(XaeroTileExtractor.FULL, tiles.get(2).caveStart());
    }
}
