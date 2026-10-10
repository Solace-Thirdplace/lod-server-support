package dev.vox.lss.networking.client;

import dev.vox.lss.common.dh.DhTerrainTile;
import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.platform.SectionConstruction;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import java.util.*;

/** Worker-only expansion; containers and light arrays are never reused after handoff. */
final class DhColumnConverter {
    private record Material(BlockState state, Holder<Biome> biome, int waterSkyLoss) {}
    private final net.minecraft.core.Registry<Biome> biomes;
    // 26.x section-construction flavor: empty sections come from a PalettedContainerFactory
    // (the 1.21.x lines pass the Registry<Biome> to SectionConstruction instead).
    private final PalettedContainerFactory factory;
    private final ClientIdentityResolver resolver;
    DhColumnConverter(ClientLevel level) {
        this(level.registryAccess().lookupOrThrow(Registries.BIOME),
                PalettedContainerFactory.create(level.registryAccess()));
    }
    DhColumnConverter(net.minecraft.core.Registry<Biome> biomes) {
        this(biomes, PalettedContainerFactory.create(
                new net.minecraft.core.RegistryAccess.ImmutableRegistryAccess(List.of(biomes))));
    }
    private DhColumnConverter(net.minecraft.core.Registry<Biome> biomes, PalettedContainerFactory factory) {
        this.biomes=biomes;
        this.factory=factory;
        var fallback=dev.vox.lss.config.LSSClientConfig.CONFIG.snapshot().compatibility().blockFallbacks();
        resolver=new ClientIdentityResolver(biomes, biomes.asHolderIdMap(),fallback);
    }
    /** Whole-tile conversion on the worker: materials resolve once, then each
     *  selected complete column converts. Unselected/incomplete columns stay null. */
    VoxelColumnData[] convertTile(DhTerrainTile tile, int columnMask) {
        var materials=materials(tile);
        var out=new VoxelColumnData[16];
        for(int c=0;c<16;c++) if((columnMask&(1<<c))!=0) out[c]=convert(tile,c,materials);
        return out;
    }
    VoxelColumnData convert(DhTerrainTile tile, int column) { return convert(tile,column,materials(tile)); }
    private VoxelColumnData convert(DhTerrainTile tile, int column, List<Material> materials) {
        int height=tile.world().height();
        if ((height&15)!=0 || (tile.world().minY()&15)!=0) throw new IllegalArgumentException("Unaligned world height");
        int tx=column/4*16, tz=column%4*16;
        // Validate all 256 vertical columns BEFORE constructing a replacement.
        for(int x=0;x<16;x++) for(int z=0;z<16;z++)
            if(tile.coverage((tx+x)*64+tz+z)!=DhTerrainTile.Coverage.COMPLETE) return null;
        var sections=new VoxelColumnData.SectionData[height/16];
        for(int sy=0;sy<sections.length;sy++) sections[sy]=new VoxelColumnData.SectionData(
                tile.world().minY()/16+sy, SectionConstruction.empty(factory), new DataLayer(), new DataLayer());
        for(int x=0;x<16;x++) for(int z=0;z<16;z++) {
            int index=(tx+x)*64+tz+z;
            for(int i=0;i<tile.spanCount(index);i++) {
                var span=tile.span(index,i); var material=materials.get(span.material());
                // DH renders a side face exposed through an air run with the sky light of
                // the floor below it; Voxy lights faces from the neighbouring air voxel.
                // Give the whole run the floor's sky light (identical for official DH,
                // which records the floor's value; brightens DH Support overhangs to match).
                int floor=floorLight(tile,index,span,materials);
                for(int y=span.bottom();y<span.bottom()+span.height();y++) {
                    var section=sections[y/16];
                    section.section().setBlockState(x,y&15,z,material.state,false);
                    section.blockLight().set(x,y&15,z,span.blockLight());
                    section.skyLight().set(x,y&15,z,floor>=0?floor&15:sky(tile,span,material,y));
                    // Sample the (2,2,2) voxel at each 4x4x4 biome-cell center.
                    if((x&3)==2 && (z&3)==2 && (y&3)==2)
                        ((PalettedContainer<Holder<Biome>>)section.section().getBiomes())
                                .set(x/4,(y&15)/4,z/4,material.biome);
                }
            }
            for(int i=0;i<tile.spanCount(index);i++) {
                var span=tile.span(index,i);int light=floorLight(tile,index,span,materials);
                if(light>=0) {
                    int y=span.bottom();var section=sections[y/16];
                    section.blockLight().set(x,y&15,z,light>>>4);
                    section.skyLight().set(x,y&15,z,light&15);
                }
            }
        }
        return new VoxelColumnData(sections,0);
    }
    private List<Material> materials(DhTerrainTile tile) {
        var materials=new ArrayList<Material>();
        for(var m:tile.materials()) {
            var state=Block.BLOCK_STATE_REGISTRY.byId(resolver.blockIdForDh(m));
            int waterSkyLoss=!state.getFluidState().isEmpty()
                    ?Math.max(1,state.getLightDampening()):0;
            materials.add(new Material(state,
                    biomes.asHolderIdMap().byId(resolver.biomeIdFor(m.biome())),waterSkyLoss));
        }
        return materials;
    }
    private static int sky(DhTerrainTile tile,DhTerrainTile.Span span,Material material,int y) {
        // DH merges water despite changing light; Voxy lights faces from the neighboring voxel.
        return tile.world().sky()?Math.max(0,span.skyLight()-material.waterSkyLoss*(span.bottom()+span.height()-y)):0;
    }
    /** Voxy shades the upward floor face from the first AIR voxel above it.
     * DH shades that face from the floor datapoint, not the top of the AIR run. */
    private static int floorLight(DhTerrainTile tile,int column,DhTerrainTile.Span air,List<Material> materials) {
        if(!materials.get(air.material()).state.isAir())return -1;
        for(int i=0;i<tile.spanCount(column);i++) {
            var lower=tile.span(column,i);var material=materials.get(lower.material());
            if(lower.bottom()+lower.height()==air.bottom() && !material.state.isAir())
                return (tile.world().sky()?(material.waterSkyLoss==0?lower.skyLight():air.skyLight()):0)|(lower.blockLight()<<4);
        }
        return -1;
    }
}
