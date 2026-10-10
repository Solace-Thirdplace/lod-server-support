package dev.vox.lssfixture.dh;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.common.dh.*;
import dev.vox.lss.networking.client.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/** Independent source-specific known blocks; internal Voxy storage reads stay test-only. */
public final class ContentProbe {
    static final ResourceKey<Level> DIM=ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse("lssfixture:tall"));
    static final DhPosition TILE=new DhPosition(6,-3,0);
    static final List<BlockPos> SAMPLES=new ArrayList<>();
    static { for(int x=-192;x<=-129;x+=7)for(int z=0;z<=63;z+=7)SAMPLES.add(new BlockPos(x,350,z)); }
    static final Set<Long> nativeColumns=ConcurrentHashMap.newKeySet();
    static final AtomicLong nativeDeliveries=new AtomicLong(),targetConditional=new AtomicLong();
    static final AtomicReference<Throwable> callbackFailure=new AtomicReference<>();
    static final Map<Integer,Ask> requests=new ConcurrentHashMap<>();
    static final List<Reply> replies=new CopyOnWriteArrayList<>();
    static final Set<Integer> captureBuffers=ConcurrentHashMap.newKeySet();
    static final ThreadLocal<Reply> decoding=new ThreadLocal<>();
    static final AtomicInteger captureSequence=new AtomicInteger();
    record Ask(Object epoch,DhMessage.Request request,boolean target) {}
    static final class Reply {
        final Ask ask;final int id;final boolean changed;final AtomicInteger columns=new AtomicInteger();
        volatile long stamp;
        Reply(Ask ask,int id,boolean changed) {this.ask=ask;this.id=id;this.changed=changed;}
    }
    static volatile int phase;
    static long changed=System.nanoTime(),deadline=changed+240_000_000_000L,oldStamp,newStamp,nativeBaseline,dhBaseline,lastStatus,lastRead,lastTeleport,idleSince,lastRequests=-1;
    static Object dhEpoch,firstConnection;
    static String bucket,edit;
    static CompletableFuture<?> saved;
    static long warmedEntries,emptyEntries;
    static int completedTiles;
    static boolean registered;

    static void failure(Throwable e) {callbackFailure.compareAndSet(null,e);}
    static synchronized void telemetry(String event,int id,DhPosition p,Long stamp,String outcome) throws Exception {
        var row=new com.google.gson.JsonObject();row.addProperty("event",event);row.addProperty("request_id",id);
        if(p!=null) {row.addProperty("detail",p.detail());row.addProperty("x",p.x());row.addProperty("z",p.z());}
        if(stamp!=null)row.addProperty("timestamp",stamp);
        row.addProperty("wall_time",java.time.Instant.now().toString());row.addProperty("monotonic_nanos",System.nanoTime());row.addProperty("phase",phase);row.addProperty("outcome",outcome);
        java.nio.file.Files.writeString(Minecraft.getInstance().gameDirectory.toPath().resolve("dh-requests.jsonl"),row+"\n",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);
    }
    public static void sent(Object owner,DhMessage message) {
        try {
            if(!(message instanceof DhMessage.Request r))return;
            Object epoch=Probe.field(DhClientSession.class,"session");
            if(epoch!=owner || !(boolean)Probe.field(owner,"active"))return;
            Probe.requests.incrementAndGet();
            var level=(net.minecraft.client.multiplayer.ClientLevel)Probe.field(epoch,"level");
            boolean target=r.position().equals(TILE) && level.dimension().equals(DIM);
            requests.put(r.requestId(),new Ask(epoch,r,target));
            telemetry("request",r.requestId(),r.position(),r.timestamp(),"");
            if(target) {
                if(r.timestamp()!=null && r.timestamp()>0)targetConditional.incrementAndGet();
                // Level keys are opaque. Preserve all other request fields and
                // explicitly label this local reconstruction in capture metadata.
                capture("request",new DhProtocolCodec(DhProfile.OFFICIAL_16,DhLimits.DEFAULT).encode(new DhMessage.Request(r.requestId(),DIM.location().toString(),r.position(),r.timestamp())));
            }
        } catch(Throwable e) {failure(e);}
    }
    public static void received(byte[] bytes) {
        decoding.remove();
        try {
            var level=Minecraft.getInstance().level;
            if(level!=null && level.dimension().equals(DIM) && bytes.length>=21 && bytes[2]==0 && bytes[3]==10) {
                var raw=java.nio.ByteBuffer.wrap(bytes);int buffer=raw.getInt(4),length=raw.getInt(8);
                if(length==bytes.length-13 && bytes[bytes.length-1]==1 && raw.getLong(12)==TILE.packed())captureBuffers.add(buffer);
                if(captureBuffers.contains(buffer))capture("split",bytes);
            }
            if(bytes.length<4 || bytes[2]!=0 || bytes[3]!=8)return;
            var response=(DhMessage.Response)new DhProtocolCodec(DhProfile.OFFICIAL_16,DhLimits.DEFAULT).decode(bytes);
            var ask=requests.remove(response.requestId());
            if(ask==null)return;
            boolean conditional=ask.request.timestamp()!=null && ask.request.timestamp()>0;
            telemetry("reply",response.requestId(),ask.request.position(),ask.request.timestamp(),response.bufferId()!=null?"changed":conditional?"unchanged":"empty");
            if(!ask.target)return;
            capture("response",bytes);if(response.bufferId()!=null)captureBuffers.remove(response.bufferId());
            Object epoch=Probe.field(DhClientSession.class,"session");
            if(epoch!=ask.epoch)return;
            var pending=(Map<?,?>)Probe.field(epoch,"pending");
            if(!pending.containsKey(response.requestId()))return;
            var reply=new Reply(ask,response.requestId(),response.bufferId()!=null);replies.add(reply);decoding.set(reply);
        } catch(Throwable e) {failure(e);}
    }
    static synchronized void capture(String type,byte[] bytes) throws Exception {
        int sequence=captureSequence.incrementAndGet();
        if(sequence>256)throw new IllegalStateException("Known-tile capture bound exceeded");
        String prefix=System.getProperty("lss.rig.scenario","").equals("rejoin")?"target-"+ProcessHandle.current().pid()+"-":"target-";
        java.nio.file.Files.write(java.nio.file.Path.of(System.getProperty("lss.rig.captureDir"),prefix+String.format("%04d-%s.bin",sequence,type)),bytes);
    }
    public static void converted(DhTerrainTile tile,int column,VoxelColumnData data) {
        try {
            var reply=decoding.get();if(reply==null || !tile.position().equals(TILE))return;
            int stage=phase==5?2:1;
            int cx=TILE.x()*4+column/4,cz=TILE.z()*4+column%4;
            for(var p:SAMPLES)if(p.getX()>>4==cx && p.getZ()>>4==cz)
                for(int y:new int[]{350,-118,340})if(state(data,p.getX()&15,y,p.getZ()&15)!=expected(stage,y))return;
            reply.stamp=tile.modifiedTimestamp();reply.columns.getAndUpdate(mask->mask | 1<<column);
        } catch(Throwable e) {failure(e);}
    }
    static BlockState state(VoxelColumnData data,int x,int y,int z) {
        for(var s:data.sections())if(s.sectionY()==y>>4)return s.section().getBlockState(x,y&15,z);
        return null; // missing data is not evidence of air
    }
    static BlockState expected(int stage,int y) {
        if(y==340)return Blocks.GOLD_BLOCK.defaultBlockState();
        if(y==-118)return (stage==0?Blocks.BLUE_CONCRETE:stage==1?Blocks.CYAN_CONCRETE:Blocks.PURPLE_CONCRETE).defaultBlockState();
        return (stage==0?Blocks.RED_CONCRETE:stage==1?Blocks.LIME_CONCRETE:stage==2?Blocks.AIR:Blocks.YELLOW_CONCRETE).defaultBlockState();
    }
    static void nativeColumn(net.minecraft.client.multiplayer.ClientLevel level,ResourceKey<Level> dimension,int x,int z,VoxelColumnData data) {
        try {
            if(DhClientSession.selected())throw new IllegalStateException("Native column delivered while DH owns the connection");
            if(dimension.equals(DIM) && x>=-12 && x<=-9 && z>=0 && z<=3) {
                nativeDeliveries.incrementAndGet();int stage=phase>=6?3:0;
                for(var p:SAMPLES)if(p.getX()>>4==x && p.getZ()>>4==z)
                    for(int y:new int[]{350,-118,340})if(state(data,p.getX()&15,y,p.getZ()&15)!=expected(stage,y))return;
                nativeColumns.add(net.minecraft.world.level.ChunkPos.asLong(x,z));
            }
        } catch(Throwable e) {failure(e);}
    }
    static void disconnectAndEdit(String name,List<String> commands) throws Exception {
        var mc=Minecraft.getInstance();mc.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);edit=name;
        var json=new com.google.gson.JsonObject();json.addProperty("id",name);var list=new com.google.gson.JsonArray();commands.forEach(list::add);list.add("save-all flush");json.add("commands",list);
        var command=mc.gameDirectory.toPath().resolve("dh-server-command.json");
        var temporary=command.resolveSibling("dh-server-command.tmp");
        java.nio.file.Files.writeString(temporary,json.toString());
        java.nio.file.Files.move(temporary,command,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        requests.clear();captureBuffers.clear();decoding.remove();
    }
    static boolean reconnectAfterEdit() throws Exception {
        var mc=Minecraft.getInstance();var ack=mc.gameDirectory.toPath().resolve("dh-server-ack.json");
        if(!java.nio.file.Files.exists(ack))return false;
        var json=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(ack)).getAsJsonObject();
        if(!json.get("id").getAsString().equals(edit))return false;
        String addr=System.getProperty("lss.rig.initialEndpoint");
        ConnectScreen.startConnecting(new TitleScreen(),mc,ServerAddress.parseString(addr),new ServerData("DH content test",addr,ServerData.Type.OTHER),false,null);return true;
    }
    static void advance(int next,long now,long seconds) {phase=next;changed=now;deadline=now+seconds*1_000_000_000L;lastRead=0;idleSince=0;}
    static void teleport(long now) {
        var mc=Minecraft.getInstance();
        if(mc.player!=null && mc.player.hasPermissions(4) && now-lastTeleport>=1_000_000_000L) {
            mc.getConnection().sendCommand("execute in lssfixture:tall run tp @s 0 100 0");lastTeleport=now;
        }
    }
    static final java.util.concurrent.atomic.AtomicLong blockComparisons=new java.util.concurrent.atomic.AtomicLong();
    static boolean knownBlocks(int stage,long now) throws Exception {
        if(now-lastRead<1_000_000_000L)return false;lastRead=now;
        var mc=Minecraft.getInstance();if(!mc.level.dimension().equals(DIM))return false;
        if(mc.level.getMinY()!=-128 || mc.level.getHeight()!=512)throw new IllegalStateException("Custom height was not negotiated");
        var s=Probe.field(DhClientSession.class,"session");
        if(DhClientSession.selected()) {
            if(s==null || ((DhSessionPolicy)Probe.field(s,"policy")).timestamp(TILE)<=0)return false;
            var policy=(DhSessionPolicy)Probe.field(s,"policy");
            for(BlockPos p:SAMPLES)if(!policy.eligible(p.getX()>>4,p.getZ()>>4))throw new IllegalStateException("Fixture column has vanilla authority");
        }
        Class<?> identifier=Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier");
        Object engine=identifier.getMethod("getNullable").invoke(identifier.getMethod("of",Level.class).invoke(null,mc.level));if(engine==null)return false;
        Class<?> ec=Class.forName("me.cortex.voxy.common.world.WorldEngine"),sc=Class.forName("me.cortex.voxy.common.world.WorldSection"),mapperType=Class.forName("me.cortex.voxy.common.world.other.Mapper");
        Object mapper=ec.getMethod("getMapper").invoke(engine);
        Method acquire=ec.getMethod("acquireIfExists",int.class,int.class,int.class,int.class),copy=sc.getMethod("copyData"),release=sc.getMethod("release"),getId=mapperType.getMethod("getBlockId",long.class),getState=mapperType.getMethod("getBlockStateFromBlockId",int.class);
        Map<String,long[]> snapshots=new HashMap<>();
        for(BlockPos high:SAMPLES)for(int y:new int[]{350,-118,340}) {
            BlockPos p=new BlockPos(high.getX(),y,high.getZ());String key=(p.getX()>>5)+":"+(y>>5)+":"+(p.getZ()>>5);
            long[] values=snapshots.get(key);
            if(values==null) {
                Object section=acquire.invoke(engine,0,p.getX()>>5,y>>5,p.getZ()>>5);if(section==null)return false;
                try {values=(long[])copy.invoke(section);}finally {release.invoke(section);}snapshots.put(key,values);
            }
            long value=values[((y&31)<<10)|((p.getZ()&31)<<5)|(p.getX()&31)];
            blockComparisons.incrementAndGet();
            if(getState.invoke(mapper,(int)getId.invoke(null,value))!=expected(stage,y))return false;
        }
        return true;
    }
    static Reply acceptedReply(Object epoch,long stamp,boolean conditional) throws Exception {
        var pending=(Map<?,?>)Probe.field(epoch,"pending");
        for(var r:replies)if(r.ask.epoch==epoch && r.changed && r.columns.get()==0xffff && r.stamp==stamp && !pending.containsKey(r.id)
                && (!conditional || r.ask.request.timestamp()!=null && r.ask.request.timestamp()==oldStamp))return r;
        return null;
    }
    static boolean idle(Object s,long now) throws Exception {
        var policy=(DhSessionPolicy)Probe.field(s,"policy");var cfg=(DhMessage.SessionConfig)Probe.field(s,"config");
        int range=DhClientSession.diagnostics().effectiveRange(),count=0;
        var player=Minecraft.getInstance().player.chunkPosition();int cx=Math.floorDiv(player.x,4),cz=Math.floorDiv(player.z,4),rings=(range+3)/4;
        for(int dx=-rings;dx<=rings;dx++)for(int dz=-rings;dz<=rings;dz++) {
            var p=new DhPosition(6,cx+dx,cz+dz);int x=p.x()*4,z=p.z()*4;
            if(Math.max(Math.abs(x+2-player.x),Math.abs(z+2-player.z))>range || !DhSessionPolicy.inBorder(DhProfile.OFFICIAL_16,p,cfg.centerX(),cfg.centerZ(),cfg.borderRadius()))continue;
            boolean eligible=false;for(int ox=0;ox<4;ox++)for(int oz=0;oz<4;oz++)eligible|=policy.eligible(x+ox,z+oz);if(!eligible)continue;
            if(policy.acquisitionPriority(p)!=2 && policy.due(p,now)) {idleSince=0;return false;}
            if(policy.acquisitionPriority(p)==2)count++;
        }
        long requested=Probe.requests.get();
        if(!((Map<?,?>)Probe.field(s,"pending")).isEmpty() || requested!=lastRequests) {lastRequests=requested;idleSince=0;return false;}
        completedTiles=count;if(idleSince==0)idleSince=now;return now-idleSince>=5_000_000_000L;
    }
    public static void tick(long now) throws Exception {
        var mc=Minecraft.getInstance();
        if(callbackFailure.get()!=null)throw new IllegalStateException("Content observer failure",callbackFailure.get());
        if(now>deadline)throw new IllegalStateException("Content phase "+phase+" timed out; "+DhClientSession.status());
        if(now-lastStatus>1_000_000_000L) {lastStatus=now;Probe.write("dh-content-status.txt",phase+" "+DhClientSession.status()+"; native="+nativeDeliveries+" target refresh="+targetConditional);}
        if(phase==3 || phase==6) {if(reconnectAfterEdit())advance(phase==3?8:9,now,240);return;}
        if((phase==5 || phase==10) && (mc.level==null || mc.getConnection()==null || !DhClientSession.running()
                || Probe.field(DhClientSession.class,"session")!=dhEpoch))
            throw new IllegalStateException("Established content epoch became inactive: "+DhClientSession.status());
        if(mc.level==null || mc.player==null)return;
        if(!registered) {LSSApi.registerColumnConsumer(ContentProbe::nativeColumn);registered=true;}
        mc.player.getAbilities().flying=true;mc.player.onUpdateAbilities();mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        if((phase==1 || phase==4 || phase==7) && !mc.level.dimension().equals(DIM)) {teleport(now);return;}
        if(phase==4 || phase==5 || phase==10) {
            if(!DhClientSession.selected() || !DhClientSession.running())throw new IllegalStateException("DH content session inactive: "+DhClientSession.status());
            if(ClientNetGlue.hasReceivedSessionConfig() || ClientNetGlue.getRequestManager()!=null || nativeDeliveries.get()!=nativeBaseline)
                throw new IllegalStateException("Native terrain pipeline became active in DH mode");
        }
        if(phase==0 && ClientNetGlue.hasReceivedSessionConfig()) {
            if(DhClientSession.selected() || Probe.requests.get()!=0 || ClientNetGlue.getSessionVersion()!=20)throw new IllegalStateException("Initial native v20 premise failed");
            firstConnection=mc.getConnection();advance(1,now,240);teleport(now);
        } else if(phase==1 && nativeColumns.size()==16 && knownBlocks(0,now)) {
            var manager=ClientNetGlue.getRequestManager();bucket=(String)Probe.field(manager,"serverAddress");var stamps=new Long2LongOpenHashMap();
            for(long p:nativeColumns) {long stamp=manager.getColumnTimestamp((int)p,(int)(p>>32));if(stamp<=0)return;stamps.put(p,stamp);}
            saved=CompletableFuture.runAsync(()->ColumnCacheStore.save(bucket,DIM,stamps));advance(2,now,240);
        } else if(phase==2 && saved.isDone()) {
            saved.join();warmedEntries=ColumnCacheStore.load(bucket,DIM).size();if(warmedEntries<16)throw new IllegalStateException("Warm native cache premise failed");
            nativeBaseline=nativeDeliveries.get();Probe.sodiumApply(Map.of("lss:lod_source",1));if(DhClientSession.selected())throw new IllegalStateException("Source changed before reconnect");
            disconnectAndEdit("dh-colours",List.of("execute in lssfixture:tall run fill -192 350 0 -129 351 63 minecraft:lime_concrete","execute in lssfixture:tall run fill -192 -118 0 -129 -117 63 minecraft:cyan_concrete"));advance(3,now,240);
        } else if(phase==8 && DhClientSession.selected() && mc.getConnection()!=firstConnection) {advance(4,now,240);teleport(now);
        } else if(phase==4) {
            var s=Probe.field(DhClientSession.class,"session");var limits=(DhMessage.SessionConfig)Probe.field(s,"config");if(limits==null)return;
            if(limits.plan()!=DhMessage.GeneratorPlan.DISABLED || limits.updates() || limits.generationDistance()!=0 || !limits.synchronize() || limits.syncRate()<=0 || limits.syncDistance()<12)
                throw new IllegalStateException("Content requires active full-range sync, disabled generation and disabled unsolicited updates");
            var policy=(DhSessionPolicy)Probe.field(s,"policy");long stamp=policy.timestamp(TILE);
            if(acceptedReply(s,stamp,false)==null || !idle(s,now) || !knownBlocks(1,now))return;
            dhEpoch=s;oldStamp=stamp;emptyEntries=ColumnCacheStore.load(bucket,DIM).size();if(emptyEntries!=0)throw new IllegalStateException("DH did not invalidate native stamps");
            Probe.write("dh-custom-height.json","{\"pass\":true,\"min_y\":-128,\"height\":512,\"block_comparisons\":300,\"source_specific_converted_columns\":16,\"warm_native_entries\":"+warmedEntries+",\"entries_after_dh\":"+emptyEntries+",\"acquisition_idle_seconds\":5,\"completed_tiles\":"+completedTiles+"}");
            Probe.settings(Map.of("lod.dh.sync_on_load",false));advance(16,now,30);
        } else if(phase==16 && !dev.vox.lss.config.LSSClientConfig.CONFIG.snapshot().lod().dh().syncOnLoad()
                && !(boolean)Probe.field(dhEpoch,"sentSync")) {
            Probe.settings(Map.of("lod.dh.sync_on_load",true));advance(10,now,30+completedTiles*10L+90);
        } else if(phase==10) {
            if(Probe.field(DhClientSession.class,"session")!=dhEpoch)throw new IllegalStateException("Unchanged proof crossed a session boundary");
            var pending=(Map<?,?>)Probe.field(dhEpoch,"pending");
            boolean unchanged=false;
            for(var r:replies)if(r.ask.epoch==dhEpoch && !r.changed && r.ask.request.timestamp()!=null && r.ask.request.timestamp()==oldStamp && !pending.containsKey(r.id))unchanged=true;
            var policy=(DhSessionPolicy)Probe.field(dhEpoch,"policy");
            if(!unchanged || policy.syncTimestamp(TILE)!=oldStamp || policy.due(TILE,now))return;
            Probe.write("dh-unchanged.json","{\"pass\":true,\"owned_conditional_timestamp\":"+oldStamp+"}");
            mc.getConnection().sendCommand("execute in lssfixture:tall run fill -192 350 0 -129 351 63 minecraft:air");
            mc.getConnection().sendCommand("execute in lssfixture:tall run fill -192 -118 0 -129 -117 63 minecraft:purple_concrete");mc.getConnection().sendCommand("save-all flush");
            advance(5,now,30+completedTiles*10L+90);
        } else if(phase==5) {
            var s=Probe.field(DhClientSession.class,"session");if(s!=dhEpoch)throw new IllegalStateException("Removal crossed a session boundary");
            newStamp=((DhSessionPolicy)Probe.field(s,"policy")).timestamp(TILE);var accepted=acceptedReply(s,newStamp,true);
            if(newStamp<=oldStamp || accepted==null || !knownBlocks(2,now))return;
            Probe.write("dh-removal.json","{\"pass\":true,\"block_comparisons\":300,\"conditional_requests\":"+targetConditional+",\"accepted_request_id\":"+accepted.id+",\"old_timestamp\":"+oldStamp+",\"new_timestamp\":"+newStamp+",\"elapsed_ms\":"+(now-changed)/1_000_000L+",\"same_session\":true,\"low_terrain_changed\":true,\"present_sections_with_sentinel\":true}");
            nativeColumns.clear();dhBaseline=Probe.requests.get();Probe.sodiumApply(Map.of("lss:lod_source",0));if(!DhClientSession.selected())throw new IllegalStateException("Source switched without reconnect");
            disconnectAndEdit("native-colours",List.of("execute in lssfixture:tall run fill -192 350 0 -129 351 63 minecraft:yellow_concrete"));advance(6,now,240);
        } else if(phase==9 && !DhClientSession.selected() && ClientNetGlue.hasReceivedSessionConfig()) {Probe.retired(dhEpoch);advance(7,now,240);teleport(now);
        } else if(phase==7 && nativeColumns.size()==16 && knownBlocks(3,now)) {
            if(ClientNetGlue.getSessionVersion()!=20 || Probe.requests.get()!=dhBaseline || Probe.field(DhClientSession.class,"session")!=null)throw new IllegalStateException("Native v20 switch-back ownership failed");
            Probe.write("dh-native-switch.json","{\"pass\":true,\"native_protocol\":20,\"native_columns\":16,\"new_native_deliveries\":"+(nativeDeliveries.get()-nativeBaseline)+",\"retired_budget\":"+Probe.observedRetiredBytes+",\"dh_requests_stopped\":true,\"source_specific_storage_comparisons\":300}");
            Probe.write("dh-result.json","{\"pass\":true,\"custom_height\":true,\"refresh_after_removal\":true,\"warm_native_cache_invalidated\":true,\"native_lss_reconnect_and_terrain\":true}");Probe.done=true;mc.stop();
        }
    }
}
