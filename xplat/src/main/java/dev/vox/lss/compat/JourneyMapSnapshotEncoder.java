package dev.vox.lss.compat;

import dev.vox.lss.api.VoxelColumnData;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * One received LSS column → JourneyMap's chunk-snapshot network format, version 2, the bytes
 * JourneyMap's public {@code JMChunkSnapshot.decode(FriendlyByteBuf, Level)} reads
 * (map-ingestion plan §3.3). New LSS code written from the observed format — no JourneyMap
 * code is copied (JourneyMap is All Rights Reserved). Pure: no JourneyMap type is touched,
 * so it runs on the LSS decode thread and is unit-testable.
 *
 * <pre>
 * int32 chunkX, int32 chunkZ, VarInt payloadLength, payload
 * payload: int32 0x4A4D4353, u8 2, int32 minSectionY, int32 minBuildHeight, int32 maxBuildHeight(exclusive)
 *          u8 nSections { u8 sectionIndex, blockStates, biomes }
 *          u8 1 { u8 WORLD_SURFACE ordinal, packed(256, ceillog2(height + 1)) of (h - minBuildHeight), index x + z*16 }
 *          u8 nLight { u8 sectionIndex, byte[2048] block light }
 * blockStates: VarInt n, n × VarInt state id, u8 bits (0 when n ≤ 1, else ceillog2(n)), packed(4096) in y,z,x order
 * biomes:      VarInt n, n × UTF identifier, u8 bits, packed(64) in y,z,x order (4×4×4)
 * packed:      ceil(size·bits/64) big-endian longs, values LSB-first, spanning long boundaries
 * </pre>
 *
 * <p>The max build height is written EXCLUSIVE on every line: JourneyMap 6.0.0/6.0.10 write
 * an inclusive one themselves, which masks a build-limit top in a 256-high dimension to 0
 * and renders it void (plan review M2); the reader treats the value as exclusive.
 */
final class JourneyMapSnapshotEncoder {
    static final int MAGIC = 0x4A4D4353;
    static final int FORMAT = 2;
    static final int SECTION_BLOCKS = 4096;
    static final int SECTION_BIOMES = 64;
    static final int LIGHT_BYTES = 2048;
    /** {@code nSections}/{@code nLight} and every section index are single unsigned bytes. */
    static final int MAX_SECTIONS = 255;
    /** JourneyMap's day, topography and biome maps read down from the surface through water
     *  and transparent blocks to the first opaque floor, never deeper: sections wholly more
     *  than this many blocks below the column's LOWEST surface are left out (deep-ocean
     *  floors sit well inside it). Decoding is half of JourneyMap's per-column cost (J4). */
    static final int SURFACE_DEPTH_BLOCKS = 64;

    private JourneyMapSnapshotEncoder() {}

    /** The encoded buffer, or null when the column cannot be expressed (more than 255 sections). */
    static byte[] encode(int chunkX, int chunkZ, VoxelColumnData data, JourneyMapLineSurface.LevelFacts level) {
        int count = level.sectionCount();
        if (count > MAX_SECTIONS || count <= 0) return null;
        var blocks = new LevelChunkSection[count];
        var light = new DataLayer[count];
        for (var section : data.sections()) {
            if (section == null) continue;
            int index = section.sectionY() - level.minSectionY();
            if (index < 0 || index >= count) continue; // synthetic sections outside the client level
            // Synthetic all-air sections (sky fill, absent-section air fill) carry no blocks
            // but can carry block light, which the night map reads.
            if (section.section() != null && !section.section().hasOnlyAir()) blocks[index] = section.section();
            if (section.blockLight() != null && !allZero(section.blockLight())) light[index] = section.blockLight();
        }
        int[] offsets = worldSurface(blocks, level);
        int lowest = Integer.MAX_VALUE;
        for (int offset : offsets) if (offset > 0) lowest = Math.min(lowest, offset);
        if (lowest != Integer.MAX_VALUE) {
            int floorY = level.minY() + lowest - SURFACE_DEPTH_BLOCKS;
            for (int i = 0; i < count; i++) {
                if ((level.minSectionY() + i) * 16 + 15 < floorY) {
                    blocks[i] = null;
                    light[i] = null;
                }
            }
        }
        try {
            var payload = new ByteArrayOutputStream(16 * 1024);
            var out = new DataOutputStream(payload);
            out.writeInt(MAGIC);
            out.writeByte(FORMAT);
            out.writeInt(level.minSectionY());
            out.writeInt(level.minY());
            out.writeInt(level.maxYExclusive());
            out.writeByte(nonNull(blocks));
            for (int i = 0; i < count; i++) {
                if (blocks[i] == null) continue;
                out.writeByte(i);
                writeBlockStates(out, blocks[i]);
                writeBiomes(out, blocks[i]);
            }
            out.writeByte(1);
            out.writeByte(Heightmap.Types.WORLD_SURFACE.ordinal());
            int height = level.maxYExclusive() - level.minY();
            writePacked(out, offsets, Math.max(0, Mth.ceillog2(height + 1)));
            out.writeByte(nonNull(light));
            for (int i = 0; i < count; i++) {
                if (light[i] == null) continue;
                out.writeByte(i);
                out.write(light[i].getData());
            }
            out.flush();
            byte[] body = payload.toByteArray();
            var framed = new ByteArrayOutputStream(body.length + 13);
            var head = new DataOutputStream(framed);
            head.writeInt(chunkX);
            head.writeInt(chunkZ);
            writeVarInt(head, body.length);
            head.write(body);
            head.flush();
            return framed.toByteArray();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible); // in-memory streams
        }
    }

    /** {@code WORLD_SURFACE} as offsets from the minimum build height: one past the top
     *  non-air block, else the minimum (JourneyMap's void). Index {@code x + z*16}. */
    static int[] worldSurface(LevelChunkSection[] blocks, JourneyMapLineSurface.LevelFacts level) {
        int[] offsets = new int[256];
        int unresolved = 256;
        boolean[] done = new boolean[256];
        for (int index = blocks.length - 1; index >= 0 && unresolved > 0; index--) {
            var section = blocks[index];
            if (section == null) continue;
            int baseY = (level.minSectionY() + index) * 16;
            for (int y = 15; y >= 0 && unresolved > 0; y--) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int column = x + z * 16;
                        if (done[column] || section.getBlockState(x, y, z).isAir()) continue;
                        done[column] = true;
                        offsets[column] = baseY + y + 1 - level.minY();
                        unresolved--;
                    }
                }
            }
        }
        return offsets;
    }

    private static void writeBlockStates(DataOutputStream out, LevelChunkSection section) throws IOException {
        var palette = new ArrayList<BlockState>();
        var indexOf = new IdentityHashMap<BlockState, Integer>();
        int[] values = new int[SECTION_BLOCKS];
        int i = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    Integer slot = indexOf.get(state);
                    if (slot == null) {
                        slot = palette.size();
                        palette.add(state);
                        indexOf.put(state, slot);
                    }
                    values[i++] = slot;
                }
            }
        }
        writeVarInt(out, palette.size());
        for (var state : palette) {
            int id = Block.BLOCK_STATE_REGISTRY.getId(state);
            writeVarInt(out, Math.max(0, id)); // an unregistered state reads as air
        }
        int bits = palette.size() <= 1 ? 0 : Mth.ceillog2(palette.size());
        out.writeByte(bits);
        writePacked(out, values, bits);
    }

    private static void writeBiomes(DataOutputStream out, LevelChunkSection section) throws IOException {
        var palette = new ArrayList<String>();
        var indexOf = new IdentityHashMap<Holder<Biome>, Integer>();
        var byName = new java.util.HashMap<String, Integer>();
        int[] values = new int[SECTION_BIOMES];
        int i = 0;
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    Holder<Biome> holder = section.getNoiseBiome(x, y, z);
                    Integer slot = indexOf.get(holder);
                    if (slot == null) {
                        String id = JourneyMapLineSurface.biomeId(holder);
                        slot = byName.get(id);
                        if (slot == null) {
                            slot = palette.size();
                            palette.add(id);
                            byName.put(id, slot);
                        }
                        indexOf.put(holder, slot);
                    }
                    values[i++] = slot;
                }
            }
        }
        writeVarInt(out, palette.size());
        for (var id : palette) out.writeUTF(id);
        int bits = palette.size() <= 1 ? 0 : Mth.ceillog2(palette.size());
        out.writeByte(bits);
        writePacked(out, values, bits);
    }

    /** {@code ceil(size·bits/64)} longs, LSB-first, values spanning long boundaries. */
    static void writePacked(DataOutputStream out, int[] values, int bits) throws IOException {
        if (bits == 0) return;
        long[] packed = new long[(int) (((long) values.length * bits + 63) / 64)];
        long mask = (1L << bits) - 1;
        for (int i = 0; i < values.length; i++) {
            long value = values[i] & mask;
            int start = i * bits;
            int word = start >>> 6;
            int offset = start & 63;
            packed[word] |= value << offset;
            if (offset + bits > 64) packed[word + 1] |= value >>> (64 - offset);
        }
        for (long word : packed) out.writeLong(word);
    }

    static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static int nonNull(Object[] array) {
        int n = 0;
        for (Object o : array) if (o != null) n++;
        return n;
    }

    private static boolean allZero(DataLayer layer) {
        for (byte b : layer.getData()) if (b != 0) return false;
        return true;
    }
}
