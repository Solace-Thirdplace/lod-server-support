package dev.vox.lssfixture.dh;

import dev.vox.lss.networking.client.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.Blocks;
import java.lang.reflect.Method;
import java.util.*;

/** Cold stationary border: loaded vanilla data outside Sodium's drawing radius. */
public final class BoundaryProbe {
    static long started,teleported,lastRead,stableSince;
    static Set<Long> lastColumns=Set.of();
    static int reads;
    static final Map<Long,Long> missingSince=new HashMap<>();
    static double movementStartX;
    static long movingAt;
    static boolean moving(){return Boolean.getBoolean("lss.rig.movingBorder");}
    static double nearest(int low,int high) {return low>0?low:high<0?high:0;}
    static boolean outsideDrawing(int cx,int cz,double x,double z,int radius) {
        // Actual Sodium 0.8.12 OcclusionCuller and installed Voxy outline shader:
        // nearest point on the section expanded by one BLOCK, then camera fraction.
        int ix=(int)x,iz=(int)z;
        double dx=nearest(cx*16-ix-1,cx*16-ix+17)-(x-ix);
        double dz=nearest(cz*16-iz-1,cz*16-iz+17)-(z-iz);
        return dx*dx+dz*dz>=(double)radius*radius*256;
    }
    public static void tick(long now) throws Exception {
        var mc=Minecraft.getInstance();if(mc.level==null || mc.player==null)return;
        if(!mc.level.dimension().equals(ContentProbe.DIM)) {
            if(mc.player.hasPermissions(4) && now-teleported>1_000_000_000L) {
                mc.getConnection().sendCommand("execute in lssfixture:tall run tp @s 0 -100 0");teleported=now;
            }
            return;
        }
        if(started==0)started=now;
        if(now-started>180_000_000_000L)throw new IllegalStateException("Boundary acquisition/readback timed out");
        mc.player.getAbilities().flying=true;mc.player.onUpdateAbilities();
        if(moving() && now-started>40_000_000_000L) {
            if(movingAt==0){movingAt=now;movementStartX=mc.player.getX();}
            mc.player.setDeltaMovement(new net.minecraft.world.phys.Vec3(.28,0,0));
        } else mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);mc.player.setYRot(-45);mc.player.setXRot(12);mc.options.hideGui=true;
        if(mc.options.getEffectiveRenderDistance()!=8 || mc.options.renderDistance().get()!=12)return;
        if(DhClientSession.selected()? !DhClientSession.running(): !ClientNetGlue.hasReceivedSessionConfig())return;
        if(now-lastRead<2_000_000_000L)return;lastRead=now;reads++;
        int radius=8;var camera=mc.gameRenderer.getMainCamera().getPosition();
        var candidates=new TreeSet<Long>();int lit=0;
        int centerX=(int)Math.floor(camera.x/16),centerZ=(int)Math.floor(camera.z/16);
        for(int x=centerX-radius-3;x<=centerX+radius+3;x++)for(int z=centerZ-radius-3;z<=centerZ+radius+3;z++) {
            var chunk=mc.level.getChunkSource().getChunk(x,z,ChunkStatus.FULL,false);
            if(chunk==null || !outsideDrawing(x,z,camera.x,camera.z,radius))continue;
            if(chunk.getBlockState(new BlockPos(x*16+8,-121,z*16+8))!=Blocks.STONE.defaultBlockState())
                throw new IllegalStateException("Unexpected vanilla flat-world source blocks");
            candidates.add(ChunkPos.asLong(x,z));
            var light=mc.level.getLightEngine().getLayerListener(LightLayer.SKY).getDataLayerData(net.minecraft.core.SectionPos.of(x,-8,z));
            if(light!=null)lit++;
        }
        if(candidates.isEmpty())return;
        if(!candidates.equals(lastColumns)){lastColumns=Set.copyOf(candidates);stableSince=now;}
        Object id=Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier").getMethod("of",Level.class).invoke(null,mc.level);
        Object engine=id.getClass().getMethod("getNullable").invoke(id);if(engine==null)return;
        Class<?> ec=Class.forName("me.cortex.voxy.common.world.WorldEngine"),sc=Class.forName("me.cortex.voxy.common.world.WorldSection"),mapperType=Class.forName("me.cortex.voxy.common.world.other.Mapper");
        Object mapper=ec.getMethod("getMapper").invoke(engine);
        Method acquire=ec.getMethod("acquireIfExists",int.class,int.class,int.class,int.class),copy=sc.getMethod("copyData"),release=sc.getMethod("release"),blockId=mapperType.getMethod("getBlockId",long.class),state=mapperType.getMethod("getBlockStateFromBlockId",int.class);
        var sections=new HashMap<Long,long[]>();var missing=new ArrayList<String>();int comparisons=0;
        for(long packed:candidates) {
            int cx=(int)packed,cz=(int)(packed>>32),sx=cx>>1,sz=cz>>1;long key=ChunkPos.asLong(sx,sz);
            if(!sections.containsKey(key)) {
                Object section=acquire.invoke(engine,0,sx,-4,sz);
                if(section==null)sections.put(key,null);
                else try{sections.put(key,(long[])copy.invoke(section));}finally{release.invoke(section);}
            }
            long[] data=sections.get(key);boolean matched=data!=null;
            for(int lx:new int[]{2,8,14})for(int lz:new int[]{2,8,14}) {
                int x=(cx&1)*16+lx,z=(cz&1)*16+lz;
                if(data==null || state.invoke(mapper,(int)blockId.invoke(null,data[(7<<10)|(z<<5)|x]))!=Blocks.STONE.defaultBlockState())matched=false;
                comparisons++;
            }
            if(!matched)missing.add(cx+","+cz);
        }
        var row=new com.google.gson.JsonObject();row.addProperty("source",DhClientSession.selected()?"dh":"native");row.addProperty("stationary",!moving());
        row.addProperty("moving_seconds",movingAt==0?0:(now-movingAt)/1e9);
        row.addProperty("movement_blocks",movingAt==0?0:mc.player.getX()-movementStartX);
        boolean nativeCase=System.getProperty("lss.rig.nativePreferred","false").equals("true");
        if(nativeCase && (DhClientSession.selected() || ClientNetGlue.getSessionVersion()!=20 || Probe.requests.get()!=0))throw new IllegalStateException("Dual-service server did not prefer native LSS");
        row.addProperty("dh_fallback_enabled",dev.vox.lss.config.LSSClientConfig.CONFIG.snapshot().lod().source().equals("distant_horizons"));
        row.addProperty("dh_requests",Probe.requests.get());row.addProperty("native_protocol",ClientNetGlue.getSessionVersion());
        row.addProperty("requested_vanilla_radius",12);row.addProperty("effective_vanilla_radius",8);row.addProperty("loaded_outside_drawing",candidates.size());row.addProperty("sky_light_present",lit);
        row.addProperty("missing_voxy_columns",missing.size());row.addProperty("independent_stone_comparisons",comparisons);row.addProperty("elapsed_seconds",(now-started)/1e9);
        row.add("missing_columns",new com.google.gson.Gson().toJsonTree(missing));Probe.write("dh-boundary-status.json",row.toString());
        if(moving()) {
            var positions=new HashSet<Long>();
            for(String coordinate:missing) {
                var parts=coordinate.split(",");long position=ChunkPos.asLong(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]));positions.add(position);
                long since=missingSince.computeIfAbsent(position,p->now);
                if(movingAt!=0 && now-since>5_000_000_000L)throw new IllegalStateException("Moving vanilla border stayed missing for more than five seconds: "+coordinate);
            }
            missingSince.keySet().retainAll(positions);
            if(movingAt==0 || now-movingAt<30_000_000_000L || mc.player.getX()-movementStartX<100)return;
        }
        boolean report=System.getProperty("lss.rig.boundaryExpect","covered").equals("report");
        if(now-started<35_000_000_000L || !moving() && now-stableSince<10_000_000_000L || !report && !missing.isEmpty())return;
        row.addProperty("correctness_gate_passed",missing.isEmpty());row.addProperty("pass",report || missing.isEmpty());row.addProperty("report_only",report);
        Probe.write("dh-boundary.json",row.toString());Probe.write("dh-result.json",row.toString());
        Screenshot.grab(mc.gameDirectory,"dh-boundary.png",mc.getMainRenderTarget(),ignored->{});
        Probe.done=true;mc.stop();
    }
}
