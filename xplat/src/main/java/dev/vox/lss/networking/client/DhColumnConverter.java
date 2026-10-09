package dev.vox.lss.networking.client;

import dev.vox.lss.common.dh.DhTerrainTile;
import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.platform.SectionConstruction;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainer;
import java.util.*;

/** Worker-only expansion; containers and light arrays are never reused after handoff. */
final class DhColumnConverter {
    private record Material(BlockState state, Holder<Biome> biome, int waterSkyLoss) {}
    private final net.minecraft.core.Registry<Biome> biomes;
    private final ClientIdentityResolver resolver;
    private final String cacheIdentity;
    DhColumnConverter(ClientLevel level) {
        this(level.registryAccess().registryOrThrow(Registries.BIOME));
    }
    DhColumnConverter(net.minecraft.core.Registry<Biome> biomes) {
        this.biomes=biomes;
        var fallback=dev.vox.lss.config.LSSClientConfig.CONFIG.snapshot().compatibility().blockFallbacks();
        resolver=new ClientIdentityResolver(biomes, biomes.asHolderIdMap(),fallback);
        cacheIdentity=conversionIdentity(fallback);
    }
    VoxelColumnData convert(DhTerrainTile tile, int column) {
        int height=tile.world().height();
        if ((height&15)!=0 || (tile.world().minY()&15)!=0) throw new IllegalArgumentException("Unaligned world height");
        int tx=column/4*16, tz=column%4*16;
        // Validate all 256 vertical columns BEFORE constructing a replacement.
        for(int x=0;x<16;x++) for(int z=0;z<16;z++)
            if(tile.coverage((tx+x)*64+tz+z)!=DhTerrainTile.Coverage.COMPLETE) return null;
        var materials=materials(tile);
        var sections=new VoxelColumnData.SectionData[height/16];
        for(int sy=0;sy<sections.length;sy++) sections[sy]=new VoxelColumnData.SectionData(
                tile.world().minY()/16+sy, SectionConstruction.empty(biomes), new DataLayer(), new DataLayer());
        for(int x=0;x<16;x++) for(int z=0;z<16;z++) {
            int index=(tx+x)*64+tz+z;
            for(int i=0;i<tile.spanCount(index);i++) {
                var span=tile.span(index,i); var material=materials.get(span.material());
                for(int y=span.bottom();y<span.bottom()+span.height();y++) {
                    var section=sections[y/16];
                    section.section().setBlockState(x,y&15,z,material.state,false);
                    section.blockLight().set(x,y&15,z,span.blockLight());
                    section.skyLight().set(x,y&15,z,sky(tile,span,material,y));
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
                    ?Math.max(1,state.getLightBlock(EmptyBlockGetter.INSTANCE,BlockPos.ZERO)):0;
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
    String cacheIdentity() {return cacheIdentity;}
    private String conversionIdentity(dev.vox.lss.common.config.ClientSettings.CompatibilityBlockFallbacks fallback) {
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            java.util.function.Consumer<String> add=value->{
                byte[] bytes=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);
            };
            add.accept(fallback.defaultBlock());
            new TreeMap<>(fallback.overrides()).forEach((key,value)->{add.accept(key);add.accept(value);});
            for(var state:Block.BLOCK_STATE_REGISTRY)add.accept(state.toString());
            for(var biome:biomes.asHolderIdMap())add.accept(biome.unwrapKey().orElseThrow().location().toString());
            return HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    @FunctionalInterface interface CacheColumn {
        boolean matches(int x,int y,int z,BlockState state,String biome,int light) throws Exception;
    }
    /** Compare all voxels, including gaps and quart-center biomes, without creating MC sections. */
    boolean matchesCache(DhTerrainTile tile,int column,CacheColumn cached) throws Exception {
        var materials=materials(tile);int tx=column/4*16,tz=column%4*16,height=tile.world().height();
        var defaultBiome=biomes.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
        var cellBiomes=new String[height/4];
        for(int x=0;x<16;x++)for(int z=0;z<16;z++) {
            int index=(tx+x)*64+tz+z;
            if(tile.coverage(index)!=DhTerrainTile.Coverage.COMPLETE)return false;
            Arrays.fill(cellBiomes,defaultBiome.unwrapKey().orElseThrow().location().toString());
            int center=(tx+(x&~3)+2)*64+tz+(z&~3)+2;
            for(int i=0;i<tile.spanCount(center);i++) {
                var span=tile.span(center,i);String biome=materials.get(span.material()).biome.unwrapKey().orElseThrow().location().toString();
                for(int y=((span.bottom()+1)/4)*4+2;y<span.bottom()+span.height();y+=4)
                    if(y>=span.bottom() && y<height)cellBiomes[y/4]=biome;
            }
            for(int y=0;y<height;y++) {
                if(Thread.currentThread().isInterrupted())return false;
                // No span ordering is assumed; absent voxels remain the converter's zero-lit air.
                BlockState state=Blocks.AIR.defaultBlockState();int light=0;
                for(int i=0;i<tile.spanCount(index);i++) {
                    var span=tile.span(index,i);
                    if(y>=span.bottom() && y<span.bottom()+span.height()) {
                        var material=materials.get(span.material());state=material.state;
                        light=sky(tile,span,material,y)|(span.blockLight()<<4);
                        if(y==span.bottom()) {int floor=floorLight(tile,index,span,materials);if(floor>=0)light=floor;}
                        break;
                    }
                }
                if(!cached.matches(x,y+tile.world().minY(),z,state,cellBiomes[y/4],light))return false;
            }
        }
        return true;
    }
}
