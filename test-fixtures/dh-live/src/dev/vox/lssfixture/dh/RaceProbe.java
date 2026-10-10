package dev.vox.lssfixture.dh;

import dev.vox.lss.common.dh.DhSessionPolicy;
import dev.vox.lss.networking.client.DhClientSession;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Controlled counterexample, not a passing vanilla-authority correctness claim. */
public final class RaceProbe {
    private static final AtomicReference<LevelChunkSection> held=new AtomicReference<>();
    private static final AtomicBoolean barrier=new AtomicBoolean();
    private static final java.util.Set<LevelChunkSection> dhSections=java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    private static final ThreadLocal<Boolean> owned=ThreadLocal.withInitial(()->false),heldJob=ThreadLocal.withInitial(()->false);
    private static final CountDownLatch release=new CountDownLatch(1);
    private static volatile long blockedAt,vanillaCommitted,dhCommitted;
    private static volatile BlockState beforeDh,afterDh;
    private static volatile Throwable problem;
    private static long changed;
    private static int phase;
    private static Method updater;
    public static void enqueued(LevelChunkSection section,int x,int y,int z) {
        if(x==-12&&y==21&&z==0&&dev.vox.lss.api.LSSApi.captureIngestFailureHandle()!=null) {
            if(section.getBlockState(8,14,8)!=Blocks.RED_CONCRETE.defaultBlockState())
                throw new IllegalStateException("Owned DH target was not red");
            dhSections.add(section);held.compareAndSet(null,section);
        }
    }
    public static void beginJob(Object job) throws Exception {
        var field=job.getClass().getDeclaredField("section");field.setAccessible(true);
        Object section=field.get(job);owned.set(dhSections.contains(section));
        heldJob.set(section==held.get()&&held.get()!=null);
    }
    public static void insert(Object world,Object section) throws Exception {
        Class<?> type=section.getClass();
        boolean target=type.getField("x").getInt(section)==-12&&type.getField("y").getInt(section)==21&&type.getField("z").getInt(section)==0;
        boolean delayed=target&&heldJob.get()&&barrier.compareAndSet(false,true);
        try {
            if(delayed) {
                blockedAt=System.nanoTime();
                if(!release.await(60,TimeUnit.SECONDS))throw new IllegalStateException("Bounded race barrier timed out");
            }
            if(updater==null)updater=Class.forName("me.cortex.voxy.common.world.WorldUpdater").getMethod("insertUpdate",
                    Class.forName("me.cortex.voxy.common.world.WorldEngine"),Class.forName("me.cortex.voxy.common.voxelization.VoxelizedSection"));
            updater.invoke(null,world,section);
            if(target&&blockedAt!=0) {
                if(delayed) {dhCommitted=System.nanoTime();afterDh=sample(world,-184,350,8);}
                else if(!owned.get()&&vanillaCommitted==0) {
                    BlockState state=sample(world,-184,350,8);
                    if(state==Blocks.YELLOW_CONCRETE.defaultBlockState()) {beforeDh=state;vanillaCommitted=System.nanoTime();}
                }
            }
        } catch(Exception error) {problem=error;throw error;}
    }
    private static BlockState sample(Object world,int x,int y,int z) throws Exception {
        Class<?> engine=Class.forName("me.cortex.voxy.common.world.WorldEngine"),type=Class.forName("me.cortex.voxy.common.world.WorldSection");
        Object section=engine.getMethod("acquireIfExists",int.class,int.class,int.class,int.class).invoke(world,0,x>>5,y>>5,z>>5);
        if(section==null)throw new IllegalStateException("Race sample section is absent");
        long[] data;
        try {data=(long[])type.getMethod("copyData").invoke(section);}
        finally {type.getMethod("release").invoke(section);}
        Object mapper=engine.getMethod("getMapper").invoke(world);
        Class<?> mapping=Class.forName("me.cortex.voxy.common.world.other.Mapper");
        long value=data[((y&31)<<10)|((z&31)<<5)|(x&31)];
        int block=(int)mapping.getMethod("getBlockId",long.class).invoke(null,value);
        return (BlockState)mapping.getMethod("getBlockStateFromBlockId",int.class).invoke(mapper,block);
    }
    static void tick(long now) throws Exception {
        if(problem!=null)throw new IllegalStateException("Race observer failed",problem);
        var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null||mc.getConnection()==null)return;
        mc.player.getAbilities().flying=true;mc.player.onUpdateAbilities();mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        Path directory=mc.gameDirectory.toPath();
        if(phase==0) {
            if(!mc.player.hasPermissions(2))return;
            mc.getConnection().sendCommand("execute in lssfixture:tall run tp @s 0 350 0");phase=1;changed=now;
        } else if(phase==1&&mc.level.dimension().location().toString().equals("lssfixture:tall")&&blockedAt!=0) {
            Object session=Probe.field(DhClientSession.class,"session");
            if(!((DhSessionPolicy)Probe.field(session,"policy")).eligible(-12,0))throw new IllegalStateException("DH target already had vanilla authority");
            Path temp=directory.resolve("dh-server-command.tmp");
            Files.writeString(temp,"{\"id\":\"race-yellow\",\"commands\":[\"execute in lssfixture:tall run fill -192 350 0 -177 351 15 minecraft:yellow_concrete\",\"save-all flush\"]}");
            Files.move(temp,directory.resolve("dh-server-command.json"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            phase=2;changed=now;
        } else if(phase==2&&Files.exists(directory.resolve("dh-server-ack.json"))) {
            mc.getConnection().sendCommand("tp @s -184 353 8");phase=3;changed=now;
        } else if(phase==3&&vanillaCommitted!=0) {
            Object session=Probe.field(DhClientSession.class,"session");
            if(((DhSessionPolicy)Probe.field(session,"policy")).eligible(-12,0))return;
            release.countDown();phase=4;changed=now;
        } else if(phase==4&&dhCommitted!=0&&afterDh!=null) {
            if(dhCommitted<=vanillaCommitted||beforeDh!=Blocks.YELLOW_CONCRETE.defaultBlockState()||afterDh!=Blocks.RED_CONCRETE.defaultBlockState())
                throw new IllegalStateException("Counterexample premise/result failed: "+afterDh);
            Probe.write("dh-race.json","{\"counterexample_reproduced\":true,\"correctness_gate_passed\":false,\"release_blocker\":true,"
                    +"\"real_voxy_commit\":true,\"vanilla_authority_before_dh_commit\":true,\"vanilla_expected\":\"yellow_concrete\",\"observed_before_dh\":\"yellow_concrete\","
                    +"\"observed_after_dh\":\"red_concrete\",\"controlled_delay_ms\":"+(dhCommitted-blockedAt)/1_000_000L+"}");
            Probe.write("dh-result.json","{\"pass\":true,\"expected_counterexample\":true,\"correctness_gate_passed\":false}");
            Probe.done=true;mc.stop();
        }
        if(phase>1&&now-changed>45_000_000_000L) {release.countDown();throw new IllegalStateException("Race phase deadline: "+phase);}
    }
    private RaceProbe() {}
}
