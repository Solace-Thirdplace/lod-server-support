package dev.vox.lss.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vox.lss.api.VoxelColumnData;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Random;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The encoder's bytes through JourneyMap's OWN reader — {@code JMChunkSnapshot.decode} from
 * the real jar (map-ingestion plan §6.1, mandatory): block states at every position, the
 * 4×4×4 biome cells, the WORLD_SURFACE heightmap and the block light all survive the trip.
 * A JourneyMap format change, or an encoder bug, reds here before any map is drawn wrong.
 */
class JourneyMapDecoderParityTest {
    private static final JourneyMapLineSurface.LevelFacts OVERWORLD = new JourneyMapLineSurface.LevelFacts(-4, 24, -64, 320);
    private static final JourneyMapLineSurface.LevelFacts FLAT_256 = new JourneyMapLineSurface.LevelFacts(0, 16, 0, 256);

    @TempDir
    Path scratch;

    private java.net.URLClassLoader loader;

    @org.junit.jupiter.api.AfterEach
    void closeLoader() throws java.io.IOException {
        if (this.loader != null) this.loader.close();
    }

    private Object decode(byte[] frame, JourneyMapLineSurface.LevelFacts facts) throws Exception {
        Path jar = JourneyMapGolden.jar("lss.journeymapGoldenNeoForgeJar");
        if (this.loader == null) this.loader = JourneyMapGolden.loader(jar, this.scratch);
        Class<?> snapshot = Class.forName("journeymap.common.chunk.JMChunkSnapshot", true, this.loader);
        Level level = mock(Level.class);
        when(level.getSectionsCount()).thenReturn(facts.sectionCount());
        when(level.registryAccess()).thenReturn(JourneyMapTestColumns.REGISTRY_ACCESS);
        Method decode = snapshot.getMethod("decode", FriendlyByteBuf.class, Level.class);
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(frame));
        try {
            return decode.invoke(null, buf, level);
        } finally {
            buf.release();
        }
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            boolean match = true;
            for (int i = 0; i < args.length; i++) {
                Class<?> p = m.getParameterTypes()[i];
                if (args[i] instanceof Integer && p != int.class) match = false;
                if (!(args[i] instanceof Integer) && !p.isInstance(args[i])) match = false;
            }
            if (match) return m.invoke(target, args);
        }
        throw new NoSuchMethodException(name);
    }

    /** A dense, varied column: random states from a wide palette (bits > 8 in one section),
     *  biome cells, a lit cave, an air gap, a build-limit top. */
    private static VoxelColumnData richColumn() {
        var random = new Random(42);
        BlockState[] palette = java.util.stream.StreamSupport.stream(Block.BLOCK_STATE_REGISTRY.spliterator(), false)
                .filter(s -> !s.isAir()).limit(600).toArray(BlockState[]::new);
        var specs = new java.util.ArrayList<JourneyMapTestColumns.Spec>();
        var dense = JourneyMapTestColumns.empty();
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            dense.setBlockState(x, y, z, palette[random.nextInt(palette.length)]);
        }
        JourneyMapTestColumns.setBiome(dense, 1, 2, 3, Biomes.DESERT);
        JourneyMapTestColumns.setBiome(dense, 3, 0, 0, Biomes.SNOWY_PLAINS);
        specs.add(new JourneyMapTestColumns.Spec(2, dense, null)); // inside the surface depth
        var deep = JourneyMapTestColumns.empty();
        deep.setBlockState(1, 1, 1, Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState());
        var deepLight = new DataLayer();
        deepLight.set(1, 2, 1, 7);
        specs.add(new JourneyMapTestColumns.Spec(-4, deep, deepLight)); // far below: left out
        var cave = JourneyMapTestColumns.empty();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) cave.setBlockState(x, 0, z, Blocks.DEEPSLATE.defaultBlockState());
        var light = new DataLayer();
        for (int i = 0; i < 4096; i++) light.set(i & 15, (i >> 8) & 15, (i >> 4) & 15, random.nextInt(16));
        specs.add(new JourneyMapTestColumns.Spec(0, cave, light));
        var lampLight = new DataLayer();
        lampLight.set(8, 8, 8, 15);
        specs.add(new JourneyMapTestColumns.Spec(3, JourneyMapTestColumns.empty(), lampLight)); // air, lit
        var surface = JourneyMapTestColumns.empty();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            surface.setBlockState(x, 4 + ((x + z) & 3), z, Blocks.GRASS_BLOCK.defaultBlockState());
        }
        surface.setBlockState(5, 10, 5, Blocks.WATER.defaultBlockState());
        JourneyMapTestColumns.setBiome(surface, 0, 1, 0, Biomes.FOREST);
        specs.add(new JourneyMapTestColumns.Spec(4, surface, null));
        var top = JourneyMapTestColumns.empty();
        top.setBlockState(15, 15, 15, Blocks.BEDROCK.defaultBlockState());
        specs.add(new JourneyMapTestColumns.Spec(19, top, null));
        return JourneyMapTestColumns.column(specs.toArray(JourneyMapTestColumns.Spec[]::new));
    }

    private void assertParity(VoxelColumnData data, JourneyMapLineSurface.LevelFacts facts, int chunkX, int chunkZ) throws Exception {
        byte[] frame = JourneyMapSnapshotEncoder.encode(chunkX, chunkZ, data, facts);
        Object snapshot = decode(frame, facts);
        assertNotNull(snapshot, "JourneyMap refused the bytes (see its log line above)");
        var pos = call(snapshot, "getChunkPos");
        assertEquals(new net.minecraft.world.level.ChunkPos(chunkX, chunkZ), pos);
        assertEquals(facts.minSectionY(), call(snapshot, "getMinSectionY"));
        assertEquals(facts.minY(), call(snapshot, "getMinBuildHeight"));
        assertEquals(facts.maxYExclusive(), call(snapshot, "getMaxBuildHeight"));
        var decoded = (LevelChunkSection[]) call(snapshot, "getSections");
        var expected = new LevelChunkSection[facts.sectionCount()];
        var expectedLight = new DataLayer[facts.sectionCount()];
        for (var s : data.sections()) {
            expected[s.sectionY() - facts.minSectionY()] = s.section();
            expectedLight[s.sectionY() - facts.minSectionY()] = s.blockLight();
        }
        // Sections wholly more than SURFACE_DEPTH_BLOCKS below the lowest surface are left out.
        var nonAir = new LevelChunkSection[facts.sectionCount()];
        for (int i = 0; i < nonAir.length; i++) nonAir[i] = expected[i] != null && !expected[i].hasOnlyAir() ? expected[i] : null;
        int lowest = Integer.MAX_VALUE;
        for (int o : JourneyMapSnapshotEncoder.worldSurface(nonAir, facts)) if (o > 0) lowest = Math.min(lowest, o);
        int floorY = lowest == Integer.MAX_VALUE ? Integer.MIN_VALUE
                : facts.minY() + lowest - JourneyMapSnapshotEncoder.SURFACE_DEPTH_BLOCKS;
        int culled = 0;
        for (int i = 0; i < expected.length; i++) {
            if ((facts.minSectionY() + i) * 16 + 15 < floorY) {
                if (expected[i] != null && !expected[i].hasOnlyAir()) culled++;
                expected[i] = null;
                expectedLight[i] = null;
            }
        }
        this.culledSections = culled;
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] == null || expected[i].hasOnlyAir()) {
                assertNull(decoded[i], "section " + i + " is omitted");
            } else {
                assertNotNull(decoded[i], "section " + i);
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    assertSame(expected[i].getBlockState(x, y, z), decoded[i].getBlockState(x, y, z),
                            "section " + i + " block " + x + "," + y + "," + z);
                }
                for (int y = 0; y < 4; y++) for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
                    assertSame(expected[i].getNoiseBiome(x, y, z), decoded[i].getNoiseBiome(x, y, z),
                            "section " + i + " biome cell " + x + "," + y + "," + z);
                }
            }
            var light = (DataLayer) call(snapshot, "getBlockLight", i);
            boolean expectLight = expectedLight[i] != null && !expectedLight[i].isEmpty()
                    && !java.util.Arrays.equals(expectedLight[i].getData(), new byte[2048]);
            if (expectLight) {
                assertNotNull(light, "light " + i);
                assertArrayEquals(expectedLight[i].getData(), light.getData(), "light " + i);
            } else {
                assertNull(light, "light " + i);
            }
        }
        int[] offsets = JourneyMapSnapshotEncoder.worldSurface(nonAir, facts); // heights: the full column
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            assertEquals(facts.minY() + offsets[x + z * 16],
                    call(snapshot, "getHeight", Heightmap.Types.WORLD_SURFACE, x, z), "height " + x + "," + z);
        }
    }

    private int culledSections;

    @Test
    void aRichColumnSurvivesJourneyMapsReader() throws Exception {
        assertParity(richColumn(), OVERWORLD, -1234, 5678);
        assertEquals(1, this.culledSections, "the deep section was left out — and JourneyMap decodes without it");
    }

    @Test
    void aBuildLimitTopInA256HighDimensionKeepsItsHeight() throws Exception {
        var top = JourneyMapTestColumns.empty();
        top.setBlockState(0, 15, 0, Blocks.STONE.defaultBlockState());
        var data = JourneyMapTestColumns.column(new JourneyMapTestColumns.Spec(15, top, null));
        assertParity(data, FLAT_256, 3, 4);
        Object snapshot = decode(JourneyMapSnapshotEncoder.encode(3, 4, data, FLAT_256), FLAT_256);
        assertEquals(256, call(snapshot, "getHeight", Heightmap.Types.WORLD_SURFACE, 0, 0),
                "an inclusive max (JourneyMap 6.0.0/6.0.10 write one; 6.0.9 does not) would mask this to 0 and draw the top as void");
    }

    @Test
    void anAllAirColumnDecodesAsVoid() throws Exception {
        var data = JourneyMapTestColumns.column(new JourneyMapTestColumns.Spec(0, JourneyMapTestColumns.empty(), null));
        assertParity(data, OVERWORLD, 0, 0);
    }
}
