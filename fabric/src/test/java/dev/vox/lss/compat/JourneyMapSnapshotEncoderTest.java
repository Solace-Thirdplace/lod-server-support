package dev.vox.lss.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.DataLayer;
import org.junit.jupiter.api.Test;

/**
 * The JourneyMap snapshot encoder's wire layout (map-ingestion plan §3.3), read back by an
 * independent bit-by-bit reference reader. JourneyMap's own reader decodes the same bytes in
 * {@link JourneyMapDecoderParityTest}; these pins name WHICH field broke when that reds.
 */
class JourneyMapSnapshotEncoderTest {
    private static final JourneyMapLineSurface.LevelFacts OVERWORLD = new JourneyMapLineSurface.LevelFacts(-4, 24, -64, 320);
    private static final JourneyMapLineSurface.LevelFacts FLAT_256 = new JourneyMapLineSurface.LevelFacts(0, 16, 0, 256);

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; ; shift += 7) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
    }

    /** The reference unpacker: bit i of the stream is bit (i & 63) of long (i >> 6). */
    private static int[] unpack(DataInputStream in, int size, int bits) throws IOException {
        int[] out = new int[size];
        if (bits == 0) return out;
        long[] words = new long[(size * bits + 63) / 64];
        for (int i = 0; i < words.length; i++) words[i] = in.readLong();
        for (int v = 0; v < size; v++) {
            int value = 0;
            for (int b = 0; b < bits; b++) {
                int bit = v * bits + b;
                if ((words[bit >> 6] >>> (bit & 63) & 1L) != 0) value |= 1 << b;
            }
            out[v] = value;
        }
        return out;
    }

    private record Header(int chunkX, int chunkZ, DataInputStream payload) {}

    private static Header open(byte[] frame) throws IOException {
        var in = new DataInputStream(new ByteArrayInputStream(frame));
        int x = in.readInt();
        int z = in.readInt();
        int length = readVarInt(in);
        assertEquals(in.available(), length, "the VarInt length covers exactly the payload");
        return new Header(x, z, in);
    }

    @Test
    void varIntsAreLeb128() throws IOException {
        var bytes = new ByteArrayOutputStream();
        JourneyMapSnapshotEncoder.writeVarInt(new DataOutputStream(bytes), 300);
        assertArrayEquals(new byte[]{(byte) 0xAC, 0x02}, bytes.toByteArray());
    }

    @Test
    void packedValuesAreLsbFirstAndSpanLongBoundaries() throws IOException {
        int[] values = new int[64];
        for (int i = 0; i < values.length; i++) values[i] = (i * 7 + 3) % 32;
        var bytes = new ByteArrayOutputStream();
        JourneyMapSnapshotEncoder.writePacked(new DataOutputStream(bytes), values, 5);
        assertEquals(5 * 8, bytes.size(), "ceil(64·5/64) longs");
        var in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        assertArrayEquals(values, unpack(in, 64, 5), "value 12 straddles longs 0 and 1");
    }

    @Test
    void theHeaderCarriesThePositionAndTheLevelLayout() throws IOException {
        var section = JourneyMapTestColumns.empty();
        section.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState());
        var h = open(JourneyMapSnapshotEncoder.encode(-7, 12, JourneyMapTestColumns.column(
                new JourneyMapTestColumns.Spec(0, section, null)), OVERWORLD));
        assertEquals(-7, h.chunkX());
        assertEquals(12, h.chunkZ());
        var in = h.payload();
        assertEquals(JourneyMapSnapshotEncoder.MAGIC, in.readInt());
        assertEquals(2, in.readUnsignedByte());
        assertEquals(-4, in.readInt());
        assertEquals(-64, in.readInt());
        assertEquals(320, in.readInt(), "max build height EXCLUSIVE");
        assertEquals(1, in.readUnsignedByte(), "one non-air section");
        assertEquals(4, in.readUnsignedByte(), "section y 0 is index 4 above -4");
        assertEquals(2, readVarInt(in), "palette: stone + air");
    }

    @Test
    void airOnlySectionsAreOmittedButTheirBlockLightIsKept() throws IOException {
        var lit = new DataLayer();
        lit.set(3, 4, 5, 14);
        var data = JourneyMapTestColumns.column(
                new JourneyMapTestColumns.Spec(2, JourneyMapTestColumns.empty(), lit),
                new JourneyMapTestColumns.Spec(3, JourneyMapTestColumns.empty(), new DataLayer()));
        var in = open(JourneyMapSnapshotEncoder.encode(0, 0, data, OVERWORLD)).payload();
        in.skipNBytes(4 + 1 + 12);
        assertEquals(0, in.readUnsignedByte(), "no block sections");
        assertEquals(1, in.readUnsignedByte(), "one heightmap");
        assertEquals(1, in.readUnsignedByte(), "WORLD_SURFACE");
        int[] heights = unpack(in, 256, 9);
        assertArrayEquals(new int[256], heights, "all void: offset 0 = the minimum build height");
        assertEquals(1, in.readUnsignedByte(), "one light layer: the all-zero layer is dropped");
        assertEquals(6, in.readUnsignedByte(), "section y 2 → index 6");
        byte[] light = new byte[2048];
        in.readFully(light);
        assertArrayEquals(lit.getData(), light);
        assertEquals(0, in.available());
    }

    @Test
    void theSurfaceIsOnePastTheTopBlockIndexedXPlusZTimes16() throws IOException {
        var low = JourneyMapTestColumns.empty();
        low.setBlockState(2, 5, 9, Blocks.STONE.defaultBlockState()); // y = -64 + 5
        var high = JourneyMapTestColumns.empty();
        high.setBlockState(2, 15, 9, Blocks.GLASS.defaultBlockState()); // y = 304 + 15 = 319
        high.setBlockState(15, 0, 0, Blocks.TORCH.defaultBlockState());
        var data = JourneyMapTestColumns.column(new JourneyMapTestColumns.Spec(-4, low, null),
                new JourneyMapTestColumns.Spec(19, high, null));
        int[] offsets = JourneyMapSnapshotEncoder.worldSurface(sectionsOf(data, OVERWORLD), OVERWORLD);
        assertEquals(320 - -64, offsets[2 + 9 * 16], "a build-limit top reads the full height");
        assertEquals(304 + 1 - -64, offsets[15], "non-air counts, opaque or not (WORLD_SURFACE)");
        assertEquals(0, offsets[0]);
    }

    @Test
    void aBuildLimitTopInA256HighDimensionKeepsItsNinthBit() throws IOException {
        var top = JourneyMapTestColumns.empty();
        top.setBlockState(0, 15, 0, Blocks.STONE.defaultBlockState()); // y = 255
        var in = open(JourneyMapSnapshotEncoder.encode(0, 0, JourneyMapTestColumns.column(
                new JourneyMapTestColumns.Spec(15, top, null)), FLAT_256)).payload();
        in.skipNBytes(4 + 1 + 4 + 4);
        assertEquals(256, in.readInt(), "exclusive: an inclusive 255 would leave 8 bits and mask 256 to 0");
        in.readUnsignedByte();
        in.readUnsignedByte();
        int paletteSize = readVarInt(in);
        for (int i = 0; i < paletteSize; i++) readVarInt(in);
        int bits = in.readUnsignedByte();
        unpack(in, 4096, bits);
        int biomes = readVarInt(in);
        for (int i = 0; i < biomes; i++) in.readUTF();
        unpack(in, 64, in.readUnsignedByte());
        assertEquals(1, in.readUnsignedByte());
        assertEquals(1, in.readUnsignedByte());
        assertEquals(256, unpack(in, 256, 9)[0]);
    }

    @Test
    void sectionsFarBelowTheLowestSurfaceAreLeftOut() throws IOException {
        var deep = JourneyMapTestColumns.empty();
        deep.setBlockState(0, 0, 0, Blocks.DEEPSLATE.defaultBlockState()); // y = -64
        var lit = new DataLayer();
        lit.set(1, 1, 1, 9);
        var surface = JourneyMapTestColumns.empty();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) surface.setBlockState(x, 4, z, Blocks.GRASS_BLOCK.defaultBlockState());
        var near = JourneyMapTestColumns.empty();
        near.setBlockState(3, 3, 3, Blocks.STONE.defaultBlockState()); // y = 3: inside 64 blocks of y = 69
        var in = open(JourneyMapSnapshotEncoder.encode(0, 0, JourneyMapTestColumns.column(
                new JourneyMapTestColumns.Spec(-4, deep, lit), new JourneyMapTestColumns.Spec(0, near, null),
                new JourneyMapTestColumns.Spec(4, surface, null)), OVERWORLD)).payload();
        in.skipNBytes(4 + 1 + 12);
        assertEquals(2, in.readUnsignedByte(), "the y=-64 section is never read by a surface map");
        assertEquals(4, in.readUnsignedByte(), "section y 0 stays");
    }

    @Test
    void moreThan255SectionsCannotBeExpressed() {
        assertNull(JourneyMapSnapshotEncoder.encode(0, 0, JourneyMapTestColumns.column(),
                new JourneyMapLineSurface.LevelFacts(0, 256, 0, 4096)));
    }

    private static net.minecraft.world.level.chunk.LevelChunkSection[] sectionsOf(
            dev.vox.lss.api.VoxelColumnData data, JourneyMapLineSurface.LevelFacts facts) {
        var out = new net.minecraft.world.level.chunk.LevelChunkSection[facts.sectionCount()];
        for (var s : data.sections()) out[s.sectionY() - facts.minSectionY()] = s.section();
        return out;
    }
}
