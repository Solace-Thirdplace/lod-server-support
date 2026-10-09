package dev.vox.lssfixture.p4;

import dev.vox.lss.common.dh.*;
import dev.vox.lss.networking.client.DhClientSession;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;

/** Raw-source expansion oracle. Never reads converted SectionData or calls matchesCache. */
public final class ContentOracle {
    record Source(DhPosition pos,int min,int height,boolean sky,List<DhMaterial> materials,List<DhTerrainTile.Span>[] spans) {}
    static volatile Source source;static long checkedAt;static CompletableFuture<Map<String,Object>> checking;
    static String problem="waiting for complete source";
    public static synchronized void capture(DhTerrainTile tile,int column) {
        if(!P4Probe.mode().equals("content") || column!=0 || tile.position().x()!=-3 || tile.position().z()!=0)return;
        
        @SuppressWarnings("unchecked") List<DhTerrainTile.Span>[] spans=new List[4096];
        for(int i=0;i<4096;i++){
            if(tile.coverage(i)!=DhTerrainTile.Coverage.COMPLETE)return;
            var rows=new ArrayList<DhTerrainTile.Span>();for(int n=0;n<tile.spanCount(i);n++)rows.add(tile.span(i,n));spans[i]=List.copyOf(rows);
        }
        source=new Source(tile.position(),tile.world().minY(),tile.world().height(),tile.world().sky(),tile.materials(),spans);
    }
    public static void tick(long now) throws Exception {
        var mc=Minecraft.getInstance();mc.player.getAbilities().flying=true;mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);mc.player.setYRot(90);mc.player.setXRot(15);
        if(!mc.level.dimension().location().toString().equals(System.getProperty("lss.p4.dimension","lssfixture:tall")))return;
        Object session=P4Probe.field(DhClientSession.class,"session");if(session==null)return; if(!DhClientSession.running()){if(DhClientSession.status().contains("reconnecting once"))return;throw new IllegalStateException(DhClientSession.status());}
        if(checking!=null){if(!checking.isDone())return;var result=checking.get();checking=null;
            if(Boolean.TRUE.equals(result.get("matched"))){P4Probe.pass(result);return;}problem=result.toString();}
        P4Probe.write("dh-p4-status.json",Map.of("phase","source-comparison","status",DhClientSession.status(),"oracle",problem));
        if(source==null || now-checkedAt<3_000_000_000L || !((Map<?,?>)P4Probe.field(session,"pending")).isEmpty())return;
        var policy=(DhSessionPolicy)P4Probe.field(session,"policy");if(!policy.hasSnapshot(source.pos))return;checkedAt=now;
        Object id=Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier").getMethod("of",Level.class).invoke(null,mc.level);
        Object engine=id.getClass().getMethod("getNullable").invoke(id);if(engine==null)return;
        var biomes=mc.level.registryAccess().registryOrThrow(Registries.BIOME);Source captured=source;
        checking=CompletableFuture.supplyAsync(()->{try{return compare(captured,engine,biomes);}catch(Exception e){throw new CompletionException(e);}});
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    static BlockState state(DhMaterial material) {
        var block=BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(material.block()));BlockState state=block.defaultBlockState();
        for(var entry:material.properties().entrySet()) {
            Property property=block.getStateDefinition().getProperty(entry.getKey());if(property==null)throw new IllegalArgumentException("unknown source property");
            state=state.setValue(property,(Comparable)property.getValue(entry.getValue()).orElseThrow());
        }
        return state;
    }
    static Map<String,Object> compare(Source s,Object engine,net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> biomes) throws Exception {
        long begin=System.nanoTime();Class<?> ec=engine.getClass(),vs=Class.forName("me.cortex.voxy.common.voxelization.VoxelizedSection"),factory=Class.forName("me.cortex.voxy.common.voxelization.WorldConversionFactory"),sc=Class.forName("me.cortex.voxy.common.world.WorldSection"),mt=Class.forName("me.cortex.voxy.common.world.other.Mapper");
        Object mapper=ec.getMethod("getMapper").invoke(engine);Method acquire=ec.getMethod("acquireIfExists",int.class,int.class,int.class,int.class),copy=sc.getMethod("copyData"),release=sc.getMethod("release"),mip=factory.getMethod("mipSection",vs,mt),compose=mt.getMethod("composeMappingId",byte.class,int.class,int.class);
        var blocks=new HashMap<BlockState,Integer>();for(Object x:(Object[])mt.getMethod("getStateEntries").invoke(mapper))blocks.put((BlockState)x.getClass().getField("state").get(x),x.getClass().getField("id").getInt(x));
        var ids=new HashMap<String,Integer>();for(Object x:(Object[])mt.getMethod("getBiomeEntries").invoke(mapper))ids.put((String)x.getClass().getField("biome").get(x),x.getClass().getField("id").getInt(x));
        var states=new ArrayList<BlockState>();var names=new TreeSet<String>();var materials=new TreeSet<String>();
        for(var m:s.materials){states.add(state(m));if(!m.air()){names.add(m.biome());materials.add(m.block());}}
        if(names.size()<4)throw new IllegalStateException("Four source biome quadrants not present: "+names);
        long[] compared=new long[5];long differences=0,blockDiff=0,biomeDiff=0,lightDiff=0;String first="";
        var snapshots=new HashMap<String,long[]>();Method bid=mt.getMethod("getBlockId",long.class),bio=mt.getMethod("getBiomeId",long.class),lid=mt.getMethod("getLightId",long.class);
        ec.getMethod("acquireRef").invoke(engine);
        try {
        for(int chunk=0;chunk<16;chunk++)for(int sy=0;sy<s.height/16;sy++) {
            Object expected=vs.getMethod("createEmpty").invoke(null);long[] data=(long[])vs.getField("section").get(expected);
            int cx=s.pos.x()*4+chunk/4,cz=s.pos.z()*4+chunk%4,sectionY=s.min/16+sy;
            for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                int relative=sy*16+y,index=(chunk/4*16+x)*64+chunk%4*16+z;DhTerrainTile.Span found=null;
                for(var span:s.spans[index])if(relative>=span.bottom() && relative<span.bottom()+span.height()){found=span;break;}
                if(found==null)throw new IllegalStateException("fixture unexpectedly has a gap");
                var m=s.materials.get(found.material());BlockState state=states.get(found.material());
                int sky=s.sky?found.skyLight():0;
                if(!state.getFluidState().isEmpty())sky=Math.max(0,sky-(found.bottom()+found.height()-relative)); // fixture water/kelp: one light unit per depth
                int faceBlock=found.blockLight();
                if(state.isAir() && relative==found.bottom())for(var lower:s.spans[index]) {
                    var lowerState=states.get(lower.material());
                    if(lower.bottom()+lower.height()==relative && !lowerState.isAir()) {
                        if(lowerState.getFluidState().isEmpty())sky=s.sky?lower.skyLight():0;
                        faceBlock=lower.blockLight();break;
                    }
                }
                int light=sky|(faceBlock<<4),block=state.isAir()?0:blocks.getOrDefault(state,-1),biome=state.isAir()?0:ids.getOrDefault(m.biome(),-1);
                if(block<0 || biome<0)throw new IllegalStateException("source registry identity missing from destination mappings");
                data[y*256+z*16+x]=(long)compose.invoke(null,(byte)light,block,biome);
            }
            mip.invoke(null,expected,mapper); // installed reducer oracle; expected L0 is independent of LSS conversion
            for(int level=0;level<=4;level++) {
                int width=16>>level,base=(int)vs.getMethod("getBaseIndexForLevel",int.class).invoke(null,level);
                int wx=cx*16,wy=sectionY*16,wz=cz*16,sx=wx>>(5+level),sz=wz>>(5+level),ys=wy>>(5+level);
                String key=level+":"+sx+":"+ys+":"+sz;long[] actual=snapshots.get(key);
                if(actual==null){Object node=acquire.invoke(engine,level,sx,ys,sz);if(node==null)return Map.of("matched",false,"missing",key);try{actual=(long[])copy.invoke(node);}finally{release.invoke(node);}snapshots.put(key,actual);}
                for(int y=0;y<width;y++)for(int z=0;z<width;z++)for(int x=0;x<width;x++) {
                    long wanted=data[base+(y*width+z)*width+x];int ix=((wx>>level)&31)+x,iy=((wy>>level)&31)+y,iz=((wz>>level)&31)+z;long got=actual[(iy<<10)|(iz<<5)|ix];compared[level]++;
                    if(got!=wanted) {
                        differences++;if((int)bid.invoke(null,got)!=(int)bid.invoke(null,wanted))blockDiff++;
                        if((int)bio.invoke(null,got)!=(int)bio.invoke(null,wanted))biomeDiff++;
                        if((int)lid.invoke(null,got)!=(int)lid.invoke(null,wanted))lightDiff++;
                        if(first.isEmpty())first="level="+level+" block="+(wx+(x<<level))+","+(wy+(y<<level))+","+(wz+(z<<level))+" actual="+got+" wanted="+wanted;
                    }
                }
            }
        }
        } finally {ec.getMethod("releaseRef").invoke(engine);}
        var row=new LinkedHashMap<String,Object>();row.put("matched",differences==0);row.put("raw_source_not_converted_sections",true);row.put("lod_comparisons",compared);row.put("biomes",names);row.put("materials",materials);row.put("differences",differences);row.put("block_differences",blockDiff);row.put("biome_differences",biomeDiff);row.put("light_differences",lightDiff);row.put("first_difference",first);row.put("comparison_wall_seconds",(System.nanoTime()-begin)/1e9);row.put("mip_oracle","Pinned Voxy binary reducer applied to independently expanded source; checks stored propagation, not independent reducer correctness.");return row;
    }
}
