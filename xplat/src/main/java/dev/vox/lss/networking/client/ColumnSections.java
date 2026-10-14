package dev.vox.lss.networking.client;

import dev.vox.lss.api.VoxelColumnData;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

/** The client decode of a column's section bytes, for readers outside this package: the Xaero
 *  band-fill store keeps its columns in this format and reads them back with it. */
public final class ColumnSections {
    private ColumnSections() {}

    public static VoxelColumnData.SectionData[] decode(byte[] raw, int levelSectionCount,
                                                       PalettedContainerFactory factory) {
        return ClientColumnProcessor.decodeSections(raw, levelSectionCount, factory);
    }
}
