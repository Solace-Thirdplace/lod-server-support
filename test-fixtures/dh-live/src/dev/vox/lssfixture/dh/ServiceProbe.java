package dev.vox.lssfixture.dh;
import dev.vox.lss.config.LSSClientConfig;

import dev.vox.lss.common.dh.DhProfile;
import dev.vox.lss.compat.DhVoxyBridge;
import dev.vox.lss.networking.client.DhClientSession;
import dev.vox.lss.networking.client.ClientCommandActions;
import dev.vox.lss.common.Brand;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.HashMap;
import net.minecraft.client.Minecraft;

/** Bounded packaged transport/service checks; never part of a shipping artifact. */
final class ServiceProbe {
    private static long joined,stopped,requestsAtStop,lastRead;
    private static Object connection;
    private static int reconnects;
    private static int firstBandwidth,maxBandwidth;
    private static final java.util.List<Long> supportRequests=new java.util.ArrayList<>();
    private static long supportFirstDispatchDelay;
    static void sent(Object owner,dev.vox.lss.common.dh.DhMessage message) {
        try {
        if(message instanceof dev.vox.lss.common.dh.DhMessage.Request && Probe.field(owner,"profile")==DhProfile.SUPPORT_15) {
            long now=System.nanoTime();supportRequests.add(now);
            if(supportRequests.size()==1)supportFirstDispatchDelay=now-(long)Probe.field(Probe.field(owner,"bandwidthRamp"),"supportStartedAt");
        }
        if(message instanceof dev.vox.lss.common.dh.DhMessage.SessionConfig cfg) {
            if(firstBandwidth==0)firstBandwidth=cfg.bandwidthKB();maxBandwidth=Math.max(maxBandwidth,cfg.bandwidthKB());
        }
        } catch(Exception failed) {throw new IllegalStateException("Cannot observe Support startup",failed);}
    }
    private static Path trace;
    static void tick(long now) throws Exception {
        var mc=Minecraft.getInstance();
        if(mc.level==null||mc.player==null||mc.getConnection()==null)return;
        if(joined==0) {joined=now;connection=mc.getConnection();}
        if(mc.getConnection()!=connection) {
            if(++reconnects>1 || Probe.requests.get()!=0)throw new IllegalStateException("Unexpected Minecraft reconnect");
            connection=mc.getConnection();
        }
        String expectation=System.getProperty("lss.rig.expect","ready");
        if(trace==null && expectation.equals("ready") && DhClientSession.diagnostics().state()==dev.vox.lss.common.diagnostics.DhStatusSnapshot.State.READY
                && DhClientSession.diagnostics().profile()==DhProfile.valueOf(System.getProperty("lss.rig.profile","OFFICIAL_16"))) {
            ClientCommandActions.toggleTrace(ignored->{});
            try(var files=Files.list(mc.gameDirectory.toPath().resolve("logs"))) {
                trace=files.filter(path->path.getFileName().toString().startsWith(Brand.clientCommand()+"-trace-"))
                        .max(java.util.Comparator.comparing(Path::toString)).orElseThrow();
            }
            if(Files.size(trace)==0)throw new IllegalStateException("Trace start record was not flushed");
        }
        Object session=Probe.field(DhClientSession.class,"session");
        if(expectation.equals("ready") || expectation.equals("ocean")) {
            if(now-joined>180_000_000_000L)throw new IllegalStateException("Automatic service discovery/readback timed out: "+DhClientSession.status());
            if(session!=null&&!DhClientSession.running())return;
            if(session!=null)Probe.pruneVanillaObservations(session);
            if(session==null||now-joined<45_000_000_000L||Probe.count(session,"tiles")<3||DhVoxyBridge.backlog()!=0)return;
            if(now-lastRead<1_000_000_000L)return;lastRead=now;
            var profile=DhProfile.valueOf(System.getProperty("lss.rig.profile","OFFICIAL_16"));
            if(Boolean.getBoolean("lss.rig.slowConfig") && reconnects!=0)throw new IllegalStateException("Slow protocol-16 response triggered reconnect");
            if(profile==DhProfile.SUPPORT_15 && reconnects!=0)throw new IllegalStateException("DH Support replaced the Minecraft connection");
            if(DhClientSession.diagnostics().profile()!=profile)throw new IllegalStateException("Wrong production profile");
            int[] stored=Probe.readback(session);
            if(expectation.equals("ocean")&&!OceanLightProbe.readback(session))return;
            boolean localStart=profile==DhProfile.SUPPORT_15 && (boolean)Probe.field(Probe.field(session,"bandwidthRamp"),"supportStarted");
            if(profile==DhProfile.SUPPORT_15 && LSSClientConfig.CONFIG.enableAdaptiveTransferRate() && LSSClientConfig.CONFIG.enableJoinSlowStart()) {
                if(!localStart || supportRequests.size()<2)throw new IllegalStateException("Support did not use local startup pacing");
                if(supportFirstDispatchDelay<500_000_000L && supportRequests.get(1)-supportRequests.getFirst()<1_750_000_000L)
                    throw new IllegalStateException("Support startup emitted requests at the normal rate");
            }
            Probe.write("dh-service.json","{\"pass\":true,\"profile\":\""+profile+"\",\"ordinary_connected\":true,"
                    +"\"negotiation_reconnects\":"+reconnects+",\"requests\":"+Probe.requests.get()+",\"tiles\":"+Probe.count(session,"tiles")
                    +",\"support_local_startup_gate\":"+localStart+",\"support_first_request_gap_ms\":"+(supportRequests.size()<2?-1:(supportRequests.get(1)-supportRequests.getFirst())/1_000_000L)+",\"first_bandwidth_kb\":"+firstBandwidth+",\"max_bandwidth_kb\":"+maxBandwidth+",\"storage_samples\":"+stored[0]+",\"solid_samples\":"+stored[1]+",\"columns\":"+stored[2]+"}");
            if(LSSClientConfig.CONFIG.enableAdaptiveTransferRate() && LSSClientConfig.CONFIG.enableJoinSlowStart() && firstBandwidth!=50)
                throw new IllegalStateException("Packaged client did not advertise slow start");
            finish("ready_and_storage");
        } else {
            if(now-joined>90_000_000_000L&&stopped==0)throw new IllegalStateException("DH adapter did not stop: "+DhClientSession.status());
            if(stopped==0) {
                if(DhClientSession.running())return;
                String status=DhClientSession.status();
                if(expectation.equals("conflict")&&!status.contains("Remove Distant Horizons"))return;
                if(!expectation.equals("conflict")&&(session==null||(boolean)Probe.field(session,"active")))return;
                if(session!=null&&((dev.vox.lss.common.dh.DhMemoryBudget)Probe.field(session,"budget")).used()!=0)return;
                if(expectation.equals("no-service")) {
                    var method=dev.vox.lss.networking.client.ColumnCacheStore.class.getDeclaredMethod("cacheRoot");method.setAccessible(true);
                    Path root=(Path)method.invoke(null);
                    Path marker=root.resolve(dev.vox.lss.networking.client.CacheKeyAliases.addressComponent(Minecraft.getInstance().getCurrentServer().ip)).resolve("reset-confirm-guard.bin");
                    Files.createDirectories(marker.getParent());Files.writeString(marker,"keep");
                    var feedback=new java.util.ArrayList<net.minecraft.network.chat.Component>();ClientCommandActions.runReset(feedback::add,false,false);
                    if(feedback.stream().noneMatch(c->c.getString().contains("reset confirm")) || !Files.readString(marker).equals("keep"))
                        throw new IllegalStateException("No-service reset bypassed confirmation or changed caches");
                }
                if(expectation.equals("conflict") && DhClientSession.diagnostics().reason()!=dev.vox.lss.common.diagnostics.DhStatusSnapshot.Reason.CLIENT_MOD_CONFLICT)
                    throw new IllegalStateException("Real-DH conflict reason did not reach production diagnostics");
                stopped=now;requestsAtStop=Probe.requests.get();
            }
            if(Probe.requests.get()!=requestsAtStop)throw new IllegalStateException("Stopped adapter emitted more requests");
            if(now-stopped<30_000_000_000L)return;
            if(expectation.equals("no-service") && reconnects!=0)throw new IllegalStateException("No-service server triggered reconnect");
            if(expectation.equals("conflict")&&requestsAtStop!=0)throw new IllegalStateException("LSS requested terrain with real DH installed");
            Probe.write("dh-service.json","{\"pass\":true,\"expectation\":\""+expectation+"\",\"ordinary_connected\":true,"
                    +"\"requests_after_stop\":0,\"requests_before_stop\":"+requestsAtStop+",\"observation_ms\":"+(now-stopped)/1_000_000L+"}");
            finish("bounded_adapter_stop");
        }
    }
    private static void finish(String check) throws Exception {
        if(trace!=null) {
            ClientCommandActions.toggleTrace(ignored->{});
            var counts=new HashMap<String,Integer>();var requests=new HashSet<Integer>();int matched=0;
            for(String line:Files.readAllLines(trace)) {
                var row=JsonParser.parseString(line).getAsJsonObject();String type=row.get("t").getAsString();
                counts.merge(type,1,Integer::sum);
                if(type.equals("dh_status")) {
                    var gate=dev.vox.lss.common.dh.DhAcquisition.Gate.valueOf(row.get("acquisition_wait").getAsString());
                    long mask=row.get("acquisition_gates").getAsLong(),wait=row.get("acquisition_wait_ms").getAsLong();
                    if(wait< -1 || mask>>>dev.vox.lss.common.dh.DhAcquisition.Gate.values().length!=0
                            || mask!=0 && (mask&(1L<<gate.ordinal()))==0)
                        throw new IllegalStateException("Invalid packaged acquisition observation");
                }
                if(type.equals("net") && (!row.get("source").getAsString().equals("DH") || row.get("raw_bps").getAsLong()!=-1
                        || !row.has("dcpt") || !row.has("runway") || !row.has("fps")))
                    throw new IllegalStateException("DH net trace source/metrics are invalid");
                if(type.equals("dh_request"))requests.add(row.get("id").getAsInt());
                if(type.equals("dh_reply") && requests.contains(row.get("id").getAsInt()))matched++;
            }
            for(String type:java.util.List.of("trace_start","dh_status","dh_request","dh_reply","net"))
                if(counts.getOrDefault(type,0)==0)throw new IllegalStateException("Packaged trace missing "+type);
            if(matched==0)throw new IllegalStateException("Packaged trace has no matched request/reply");
            Probe.write("dh-trace.json","{\"pass\":true,\"bytes\":"+Files.size(trace)+",\"status_rows\":"+counts.get("dh_status")
                    +",\"net_rows\":"+counts.get("net")+",\"request_rows\":"+counts.get("dh_request")+",\"reply_rows\":"+counts.get("dh_reply")+",\"matched_replies\":"+matched+"}");
        }
        Probe.resourceEvidence();
        Probe.write("dh-result.json","{\"pass\":true,\""+check+"\":true,\"ordinary_connected\":true}");
        Probe.done=true;Minecraft.getInstance().stop();
    }
    private ServiceProbe() {}
}
