package dev.vox.lss.compat;

import dev.vox.lss.api.VoxelColumnData;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;

/**
 * A delivered column as the band-fill store keeps it: the client's own decode format
 * ({@code ColumnSections.decode} reads it back) — {@code [varint count]} then per
 * section {@code [byte sectionY][LevelChunkSection.write][bool + 2048 block light][bool + 2048
 * sky light]}, registry ids of THIS client (the store's fingerprint pins them). Every
 * delivered section inside the level's height is kept as delivered — an all-air section still
 * carries the biome a void pixel samples, so dropping it would change the tile — which makes a
 * stored column's extraction identical to the live one's.
 */
final class XaeroColumnCodec {
    private XaeroColumnCodec() {}

    /** The column's stored bytes (decode thread). Throws if a section refuses to serialize —
     *  the caller drops the capture. */
    static byte[] encode(VoxelColumnData data, XaeroTileExtractor.LevelFacts level) {
        int minSection = level.minY() >> 4;
        int maxSection = (level.topY() - 1) >> 4;
        var kept = new ArrayList<VoxelColumnData.SectionData>();
        for (var s : data.sections()) {
            if (s == null || s.section() == null) continue;
            if (s.sectionY() < minSection || s.sectionY() > maxSection) continue;
            kept.add(s);
        }
        var buf = new FriendlyByteBuf(Unpooled.buffer(kept.size() * 6144 + 8));
        try {
            buf.writeVarInt(kept.size());
            for (var s : kept) {
                buf.writeByte(s.sectionY());
                s.section().write(buf);
                writeLight(buf, s.blockLight());
                writeLight(buf, s.skyLight());
            }
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    /** A stored column back as section data (the band-fill worker). LINE FLAVOR (1.21.1): the
     *  section factory is the biome Registry; {@code topY} is already exclusive here too
     *  (getMaxBuildHeight), so the section count is the same expression as on 26.x. */
    static VoxelColumnData decode(byte[] raw, XaeroTileExtractor.LevelFacts level, Registry<Biome> factory) {
        int sectionCount = Math.max(0, (level.topY() - level.minY()) >> 4);
        return new VoxelColumnData(dev.vox.lss.networking.client.ColumnSections.decode(
                raw, sectionCount, factory), 0L);
    }

    private static void writeLight(FriendlyByteBuf buf, DataLayer layer) {
        if (layer == null) {
            buf.writeBoolean(false);
            return;
        }
        buf.writeBoolean(true);
        buf.writeBytes(layer.getData());
    }
}
