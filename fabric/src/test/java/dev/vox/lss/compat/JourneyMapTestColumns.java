package dev.vox.lss.compat;

import com.mojang.serialization.Lifecycle;
import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.platform.SectionConstruction;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

/** Hand-built LSS columns for the JourneyMap encoder tests, over a real (frozen) biome
 *  registry so biome holders carry their keys like the client's. LINE FLAVOR (26.1, same as 26.2):
 *  {@code PalettedContainerFactory}. */
final class JourneyMapTestColumns {
    static final Registry<Biome> BIOMES;
    static final RegistryAccess REGISTRY_ACCESS;
    private static final PalettedContainerFactory FACTORY;

    static {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var source = VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME);
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        source.listElements().forEach(ref -> biomes.register(ref.key(), ref.value(), RegistrationInfo.BUILT_IN));
        biomes.freeze();
        BIOMES = biomes;
        REGISTRY_ACCESS = new RegistryAccess.ImmutableRegistryAccess(List.of(biomes));
        FACTORY = PalettedContainerFactory.create(REGISTRY_ACCESS);
    }

    private JourneyMapTestColumns() {}

    static LevelChunkSection empty() {
        return SectionConstruction.empty(FACTORY);
    }

    static Holder<Biome> biome(ResourceKey<Biome> key) {
        return BIOMES.getOrThrow(key);
    }

    @SuppressWarnings("unchecked")
    static void setBiome(LevelChunkSection section, int qx, int qy, int qz, ResourceKey<Biome> key) {
        ((PalettedContainer<Holder<Biome>>) section.getBiomes()).getAndSet(qx, qy, qz, biome(key));
    }

    record Spec(int sectionY, LevelChunkSection section, net.minecraft.world.level.chunk.DataLayer blockLight) {}

    static VoxelColumnData column(Spec... specs) {
        var sections = new ArrayList<VoxelColumnData.SectionData>();
        for (var spec : specs) {
            sections.add(new VoxelColumnData.SectionData(spec.sectionY(), spec.section(), spec.blockLight(), null));
        }
        return new VoxelColumnData(sections.toArray(VoxelColumnData.SectionData[]::new), 1L);
    }
}
