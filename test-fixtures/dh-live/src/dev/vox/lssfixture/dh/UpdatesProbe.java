package dev.vox.lssfixture.dh;
import dev.vox.lss.config.LSSClientConfig;

import dev.vox.lss.common.dh.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import java.util.concurrent.atomic.AtomicLong;
import dev.vox.lss.networking.client.DhClientSession;
import net.minecraft.client.Minecraft;
import java.nio.file.*;
import java.util.*;

/** Real server pushes, hot controls and independent Voxy block reads; test-only. */
public final class UpdatesProbe {
    static int phase;
    static long deadline=System.nanoTime()+240_000_000_000L,changed,lastStatus,stableSince,lastRequests=-1,baselineRequests,baselineUpdates;
    static long firstUpdates,secondUpdates;
    static Object epoch,connection,firstEpoch;
    static long originalStamp;
    static volatile int targetId;
    static volatile boolean unchangedReply;
    static final AtomicLong rejoinConversions=new AtomicLong(),rejoinPayloads=new AtomicLong();
    static String edit;
    static void sent(Object owner,DhMessage message) {
        if(phase==9 && message instanceof DhMessage.Request request && request.position().equals(ContentProbe.TILE)) {
            if(request.timestamp()==null || request.timestamp()!=originalStamp)throw new IllegalStateException("Warm load did not request the persisted receipt timestamp");
            targetId=request.requestId();
        }
    }
    static final AtomicLong pushFrames=new AtomicLong();
    private static long pausedPushFrames;
    private static long pauseRequestedAtRequests,pausedBaselineRequests;
    static void received(byte[] bytes) throws Exception {
        if(phase!=9 || bytes.length<4 || bytes[2]!=0 || bytes[3]!=8)return;
        var response=(DhMessage.Response)new DhProtocolCodec(DhProfile.OFFICIAL_16,DhLimits.DEFAULT).decode(bytes);
        if(response.requestId()==targetId) {if(response.bufferId()!=null)rejoinPayloads.incrementAndGet();else unchangedReply=true;}
    }
    static void converted(DhTerrainTile tile) {
        if(phase==9 && tile.position().equals(ContentProbe.TILE))rejoinConversions.incrementAndGet();
    }
    static void advance(int next,long now) {phase=next;changed=now;deadline=now+180_000_000_000L;stableSince=0;lastRequests=-1;}
    static void edit(String id,String high,String low) throws Exception {
        edit=id;
        var json=new com.google.gson.JsonObject();json.addProperty("id",id);
        var commands=new com.google.gson.JsonArray();
        commands.add("execute in lssfixture:tall run fill -192 350 0 -129 351 63 minecraft:"+high);
        commands.add("execute in lssfixture:tall run fill -192 -118 0 -129 -117 63 minecraft:"+low);
        commands.add("save-all flush");json.add("commands",commands);
        Path target=Minecraft.getInstance().gameDirectory.toPath().resolve("dh-server-command.json"),temp=target.resolveSibling("dh-server-command.tmp");
        Files.writeString(temp,json.toString());Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    static boolean acknowledged() throws Exception {
        Path ack=Minecraft.getInstance().gameDirectory.toPath().resolve("dh-server-ack.json");
        return Files.isRegularFile(ack) && com.google.gson.JsonParser.parseString(Files.readString(ack)).getAsJsonObject().get("id").getAsString().equals(edit);
    }
    static boolean quiet(Object session,long now) throws Exception {
        long requests=Probe.requests.get();
        if(!((Map<?,?>)Probe.field(session,"pending")).isEmpty() || requests!=lastRequests || DhClientSession.diagnostics().ingestBacklog()!=0) {
            stableSince=0;lastRequests=requests;return false;
        }
        if(stableSince==0)stableSince=now;return now-stableSince>=5_000_000_000L;
    }
    static void noRequests() {
        if(Probe.requests.get()!=baselineRequests)throw new IllegalStateException("Push-only edit triggered a terrain request");
    }
    public static void tick(long now) throws Exception {
        var mc=Minecraft.getInstance();
        if(now>deadline)throw new IllegalStateException("Live updates phase "+phase+" timed out; "+DhClientSession.status());
        if(now-lastStatus>1_000_000_000L) {lastStatus=now;Probe.write("dh-updates-status.txt",phase+" "+DhClientSession.status());}
        // Disconnect can replace the requested title screen with the return
        // screen. Rejoin our owned endpoint once the world has retired instead
        // of depending on that transient GUI state.
        if(phase==8 && mc.level==null && now-changed>=1_000_000_000L) {
            String address=System.getProperty("lss.rig.initialEndpoint");advance(9,now);
            ConnectScreen.startConnecting(new TitleScreen(),mc,ServerAddress.parseString(address),new ServerData("DH unchanged rejoin",address,ServerData.Type.OTHER),false,null);return;
        }
        if(phase==10 && mc.level==null && now-changed>=1_000_000_000L) {
            mc.gui.setScreen(new TitleScreen());Probe.phase=0;Probe.changed=now;
            System.setProperty("lss.rig.scenario","menu");return;
        }
        if(phase==-2) {if(ContentProbe.reconnectAfterEdit())advance(11,now);return;}
        if(mc.level==null || mc.player==null)return;
        mc.player.getAbilities().flying=true;mc.player.onUpdateAbilities();mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        if(!mc.level.dimension().equals(ContentProbe.DIM)) {ContentProbe.teleport(now);return;}
        if(phase==13) {
            if(mc.getConnection()!=connection)throw new IllegalStateException("Reception OFF disconnected Minecraft");
            if(LSSClientConfig.CONFIG.receiveServerLods() || DhClientSession.running() || now-changed<3_000_000_000L)return;
            // Reload and request dispatch are asynchronous. Measure OFF only after
            // it is applied and the source retired, not while pause is still pending.
            pausedBaselineRequests=Probe.requests.get();
            epoch=null;pausedPushFrames=pushFrames.get();edit("receive-off-edit","diamond_block","emerald_block");advance(14,now);return;
        }
        if(phase==14) {
            if(!acknowledged() || now-changed<30_000_000_000L)return;
            if(pushFrames.get()!=pausedPushFrames)throw new IllegalStateException("Reception OFF left server live pushes enabled");
            if(!LSSClientConfig.CONFIG.snapshot().lod().dh().realTimeUpdates())throw new IllegalStateException("Reception OFF changed saved live preferences");
            if(Probe.requests.get()!=pausedBaselineRequests)throw new IllegalStateException("Applied reception OFF emitted a terrain request");
            Probe.settings(Map.of("lod.receive",true));advance(15,now);return;
        }
        Object session=Probe.field(DhClientSession.class,"session");if(session==null || !DhClientSession.running())return;
        if(epoch!=null && (session!=epoch || mc.getConnection()!=connection))throw new IllegalStateException("Hot settings replaced the established session/connection");
        var cfg=(DhMessage.SessionConfig)Probe.field(session,"config");if(cfg==null)return;
        if(phase==0 && cfg.synchronize()) {
            // Reload publishes asynchronously. Crossing the connection boundary
            // now would cancel it, so first await the negotiated off state.
            Probe.settings(Map.of("lod.dh.real_time_updates",false));
            advance(12,now);return;
        } else if(phase==12 && !cfg.updates() && cfg.synchronize()) {
            // The first asynchronous DH export can precede console seeding.
            // Seed again after the dimension's DH service is live, with push
            // subscription disabled while establishing a complete receipt baseline.
            dev.vox.lss.networking.client.ClientCommandActions.runReset(ignored->{},true,false);
            ContentProbe.disconnectAndEdit("updates-cold-source-prime",List.of(
                    "execute in lssfixture:tall run fill -192 350 0 -129 351 63 minecraft:lime_concrete",
                    "execute in lssfixture:tall run fill -192 -118 0 -129 -117 63 minecraft:cyan_concrete",
                    "execute in lssfixture:tall run fill -192 350 0 -129 351 63 minecraft:red_concrete",
                    "execute in lssfixture:tall run fill -192 -118 0 -129 -117 63 minecraft:blue_concrete"));
            advance(-2,now);return;
        } else if(phase==11 && !cfg.updates() && cfg.synchronize()
                && ContentProbe.knownBlocks(0,now) && quiet(session,now) && RejoinProbe.fullyStored(session,now)) {
            firstEpoch=session;originalStamp=((DhSessionPolicy)Probe.field(session,"policy")).timestamp(ContentProbe.TILE);
            mc.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);mc.gui.setScreen(new TitleScreen());advance(8,now);
        } else if(phase==9 && unchangedReply && ContentProbe.knownBlocks(0,now) && quiet(session,now)) {
            if(session==firstEpoch || rejoinPayloads.get()!=0 || rejoinConversions.get()!=0)throw new IllegalStateException("Unchanged rejoin replaced existing destination terrain");
            if(((DhSessionPolicy)Probe.field(session,"policy")).timestamp(ContentProbe.TILE)!=originalStamp)throw new IllegalStateException("Unchanged rejoin lost its persisted receipt");
            if(((AtomicLong)Probe.field(session,"restored")).get()==0)throw new IllegalStateException("Unchanged rejoin restored no receipt");
            epoch=session;connection=mc.getConnection();
            Probe.settings(Map.of("lod.dh.sync_on_load",false,"lod.dh.real_time_updates",true));advance(1,now);
        } else if(phase==1 && !cfg.synchronize() && cfg.updates() && quiet(session,now)) {
            baselineRequests=Probe.requests.get();baselineUpdates=DhClientSession.diagnostics().receivedUpdates();
            edit("live-edit","lime_concrete","cyan_concrete");advance(2,now);
        } else if(phase==2 && acknowledged() && ContentProbe.knownBlocks(1,now) && DhClientSession.diagnostics().receivedUpdates()>baselineUpdates && quiet(session,now)) {
            noRequests();firstUpdates=DhClientSession.diagnostics().receivedUpdates()-baselineUpdates;
            Probe.settings(Map.of("lod.dh.real_time_updates",false));advance(3,now);
        } else if(phase==3 && !cfg.updates() && !cfg.synchronize() && quiet(session,now)) {
            baselineUpdates=DhClientSession.diagnostics().receivedUpdates();edit("disabled-edit","diamond_block","emerald_block");advance(4,now);
        } else if(phase==4 && acknowledged() && now-changed>30_000_000_000L) {
            noRequests();
            if(DhClientSession.diagnostics().receivedUpdates()!=baselineUpdates || !ContentProbe.knownBlocks(1,now))
                throw new IllegalStateException("Disabled live updates changed the retained terrain");
            Probe.settings(Map.of("lod.dh.real_time_updates",true));advance(5,now);
        } else if(phase==5 && cfg.updates() && !cfg.synchronize() && quiet(session,now)) {
            baselineUpdates=DhClientSession.diagnostics().receivedUpdates();edit("live-removal","air","purple_concrete");advance(6,now);
        } else if(phase==6 && acknowledged() && ContentProbe.knownBlocks(2,now) && DhClientSession.diagnostics().receivedUpdates()>baselineUpdates && quiet(session,now)) {
            noRequests();secondUpdates=DhClientSession.diagnostics().receivedUpdates()-baselineUpdates;
            Probe.settings(Map.of("lod.dh.sync_on_load",true));advance(7,now);
        } else if(phase==7 && cfg.synchronize() && cfg.updates()) {
            pauseRequestedAtRequests=Probe.requests.get();Probe.settings(Map.of("lod.receive",false));advance(13,now);
        } else if(phase==15 && cfg.synchronize() && cfg.updates()) {
            var result=new com.google.gson.JsonObject();result.addProperty("pass",true);result.addProperty("source","stock-official-DH-3.3.3");
            result.addProperty("push_only_edits",true);result.addProperty("live_disabled_retained_destination",true);result.addProperty("air_removal_with_same_section_sentinel",true);
            result.addProperty("same_connection_and_session",true);result.addProperty("sync_hot_negotiation",true);result.addProperty("receive_off_server_pushes",0);
            result.addProperty("receive_off_saved_live_preference",true);result.addProperty("receive_resume_same_connection",mc.getConnection()==connection);result.addProperty("independent_block_comparisons",ContentProbe.blockComparisons.get());
            result.addProperty("requests_while_pause_pending",pausedBaselineRequests-pauseRequestedAtRequests);
            result.addProperty("requests_while_reception_applied_off",0);
            result.addProperty("comparison_counter_includes_settle_retries",true);
            result.addProperty("unchanged_rejoin",true);result.addProperty("rejoin_target_payloads",rejoinPayloads.get());result.addProperty("rejoin_target_conversions",rejoinConversions.get());
            result.addProperty("edit_updates",firstUpdates);result.addProperty("removal_updates",secondUpdates);result.addProperty("terrain_requests_during_push_only",0);
            Probe.resourceEvidence();Probe.write("dh-updates.json",result.toString());
            mc.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);advance(10,now);
        }
    }
}
