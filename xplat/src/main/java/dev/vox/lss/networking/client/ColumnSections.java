package dev.vox.lss.networking.client;

import dev.vox.lss.api.VoxelColumnData;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;

/** The client decode of a column's section bytes, for readers outside this package: the Xaero
 *  band-fill store keeps its columns in this format and reads them back with it. LINE FLAVOR
 *  (1.21.1): the section construction parameter is the biome Registry (no
 *  PalettedContainerFactory on this MC; see SectionConstruction). */
public final class ColumnSections {
    private ColumnSections() {}

    public static VoxelColumnData.SectionData[] decode(byte[] raw, int levelSectionCount,
                                                       Registry<Biome> factory) {
        return ClientColumnProcessor.decodeSections(raw, levelSectionCount, factory);
    }
}
