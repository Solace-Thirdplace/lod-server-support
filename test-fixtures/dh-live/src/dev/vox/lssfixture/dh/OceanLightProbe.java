package dev.vox.lssfixture.dh;

import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.common.dh.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import java.util.*;

/** Private readback oracle: floor light comes from upstream spans, not converted DataLayers. */
final class OceanLightProbe {
    private record Sample(int x,int y,int z,int sky,int block,boolean deep) {}
    private record Column(DhPosition tile,List<Sample> samples) {}
    private static final Map<Long,Column> expected=new LinkedHashMap<>();
    static synchronized void converted(DhTerrainTile tile,int c,VoxelColumnData data) {
        if(!System.getProperty("lss.rig.expect","").equals("ocean"))return;
        var p=tile.position();if(p.x()!=-3 || (p.z()!=0 && p.z()!=1))return;
        boolean deep=p.z()==0;int floorY=deep?255:274,depth=deep?24:5;
        int cx=p.x()*4+c/4,cz=p.z()*4+c%4;var samples=new ArrayList<Sample>();
        for(int x=2;x<16;x+=4)for(int z=2;z<16;z+=4) {
            int tx=c/4*16+x,tz=c%4*16+z;
            if(tx<2 || tx>=62 || tz<2 || tz>=62)continue;
            int index=tx*64+tz;DhTerrainTile.Span floor=null,water=null;
            for(int i=0;i<tile.spanCount(index);i++) {
                var span=tile.span(index,i);var material=tile.materials().get(span.material());
                if(material.block().equals("minecraft:sand") && span.bottom()+span.height()+tile.world().minY()-1==floorY)floor=span;
                if(material.block().equals("minecraft:water") && span.bottom()+tile.world().minY()==floorY+1 && span.height()==depth)water=span;
            }
            if(floor==null||water==null)continue;
            if(floor.skyLight()!=(deep?0:10) || water.skyLight()!=15)
                throw new IllegalStateException("Seeded ocean lighting premise differs from upstream");
            samples.add(new Sample(cx*16+x,floorY+1,cz*16+z,floor.skyLight(),water.blockLight(),deep));
            samples.add(new Sample(cx*16+x,279,cz*16+z,14,water.blockLight(),deep));
        }
        long key=(cx&0xffffffffL)|((long)cz<<32);
        if(!samples.isEmpty())expected.put(key,new Column(p,samples));
        if(expected.size()>32)throw new IllegalStateException("Ocean observer exceeded its bounded sample columns");
    }
    static synchronized boolean readback(Object session) throws Exception {
        var mc=Minecraft.getInstance();var policy=(DhSessionPolicy)Probe.field(session,"policy");
        expected.keySet().removeIf(k->!policy.eligible((int)(long)k,(int)(k>>32)));
        Object id=Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier").getMethod("of",Level.class).invoke(null,mc.level);
        Object engine=id.getClass().getMethod("getNullable").invoke(id);if(engine==null)return false;
        Class<?> engineType=Class.forName("me.cortex.voxy.common.world.WorldEngine"),sectionType=Class.forName("me.cortex.voxy.common.world.WorldSection");
        Class<?> mapperType=Class.forName("me.cortex.voxy.common.world.other.Mapper");Object mapper=engineType.getMethod("getMapper").invoke(engine);
        var acquire=engineType.getMethod("acquireIfExists",int.class,int.class,int.class,int.class);
        var copy=sectionType.getMethod("copyData");
        var release=sectionType.getMethod("release");
        var getLight=mapperType.getMethod("getLightId",long.class);
        var getBlock=mapperType.getMethod("getBlockId",long.class);
        var getState=mapperType.getMethod("getBlockStateFromBlockId",int.class);
        var inFlight=new HashSet<DhPosition>();
        for(var request:((Map<?,?>)Probe.field(session,"pending")).values())inFlight.add((DhPosition)Probe.field(request,"position"));
        Map<?,?> receipts=(Map<?,?>)Probe.field(policy,"receipts");Map<String,long[]> snapshots=new HashMap<>();
        int deep=0,shallow=0,columns=0;
        for(var column:expected.values()) {
            Object receipt=receipts.get(column.tile());
            if(receipt==null || !(boolean)Probe.field(receipt,"snapshot") || inFlight.contains(column.tile()))continue;
            columns++;
            for(var sample:column.samples()) {
                String key=(sample.x()>>5)+":"+(sample.y()>>5)+":"+(sample.z()>>5);long[] values=snapshots.get(key);
                if(values==null) {
                    Object section=acquire.invoke(engine,0,sample.x()>>5,sample.y()>>5,sample.z()>>5);if(section==null)return false;
                    try {values=(long[])copy.invoke(section);}finally {release.invoke(section);}
                    snapshots.put(key,values);
                }
                long value=values[((sample.y()&31)<<10)|((sample.z()&31)<<5)|(sample.x()&31)];
                Object state=getState.invoke(mapper,(int)getBlock.invoke(null,value));
                if(((net.minecraft.world.level.block.state.BlockState)state).getBlock()!=net.minecraft.world.level.block.Blocks.WATER)return false;
                int light=(int)getLight.invoke(null,value),wanted=sample.sky()|(sample.block()<<4);
                if(light!=wanted)throw new IllegalStateException("Stored ocean light "+light+" != "+wanted+" at "+sample.x()+","+sample.y()+","+sample.z());
                if(sample.deep())deep++;else shallow++;
            }
        }
        if(columns<8 || deep<64 || shallow<64) {
            Probe.write("dh-ocean-waiting.json","{\"expected_columns\":"+expected.size()+",\"stored_columns\":"+columns
                    +",\"deep_samples\":"+deep+",\"shallow_samples\":"+shallow+"}");
            return false;
        }
        Probe.write("dh-ocean-light.json","{\"pass\":true,\"deep_samples\":"+deep+",\"shallow_samples\":"+shallow
                +",\"columns\":"+columns+",\"source_floor_light_oracle\":true,\"deep_floor_sky\":0,\"shallow_floor_sky\":10,\"surface_water_sky\":14}");
        return true;
    }
    private OceanLightProbe() {}
}
