package dev.vox.lssfixture.p4;
import dev.vox.lss.common.dh.*;
import dev.vox.lss.networking.client.DhClientSession;
import dev.vox.lss.compat.DhVoxyBridge;
import net.minecraft.client.Minecraft;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
/** Fixed-area benchmark controller: filters the want area, keeps actual request/decode/ingest. */
public final class BenchmarkClient {
 public static final Set<DhPosition> TARGETS;
 static{var s=new LinkedHashSet<DhPosition>();for(int x=-5;x<=-3;x++)for(int z=-1;z<=1;z++)s.add(new DhPosition(6,x,z));TARGETS=Set.copyOf(s);}
 static final List<Map<String,Object>> asks=new CopyOnWriteArrayList<>();static long began,receivedAt,framesAt,peak,lastStatus,verifiedAt;static CompletableFuture<Boolean> verified;static Map<String,Object> row;
 public static void sent(DhMessage message){if(!(P4Probe.mode().equals("benchmark") || P4Probe.mode().equals("configuration")) || !(message instanceof DhMessage.Request r))return;
  if(!TARGETS.contains(r.position()))throw new IllegalStateException("Benchmark requested outside registered area: "+r.position());
  if(began==0)began=System.nanoTime();asks.add(Map.of("pos",List.of(r.position().detail(),r.position().x(),r.position().z()),"generation",r.timestamp()==null,"timestamp",r.timestamp()==null?-1:r.timestamp()));
 }
 public static void tick(long now)throws Exception {
  var mc=Minecraft.getInstance();if(!mc.level.dimension().location().toString().equals("minecraft:overworld"))return;
  mc.player.getAbilities().flying=true;mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);mc.player.setYRot(90);mc.player.setXRot(20);
  Object s=P4Probe.field(DhClientSession.class,"session");if(s==null)return;if(!DhClientSession.running())throw new IllegalStateException(DhClientSession.status());
  var memory=(DhMemoryBudget)P4Probe.field(s,"budget");peak=Math.max(peak,memory.peak());var policy=(DhSessionPolicy)P4Probe.field(s,"policy");
  if(now-lastStatus>1_000_000_000L){lastStatus=now;P4Probe.write("dh-p4-status.json",Map.of("phase",framesAt==0?"acquiring":"rendering","source_snapshots",TARGETS.stream().filter(policy::hasSnapshot).count(),"requests",asks.size(),"diagnostics",DhClientSession.diagnostics()));}
  if(framesAt!=0){if(now-framesAt<60_000_000_000L)return;if(Boolean.getBoolean("lss.p4.windowsNative"))P4Probe.write("dh-frame-metrics.json",FrameMetrics.finish());row.put("peak_owned_bytes",peak);P4Probe.pass(row);return;}
  if(!TARGETS.stream().allMatch(policy::hasSnapshot) || !((Map<?,?>)P4Probe.field(s,"pending")).isEmpty())return;
  if(receivedAt==0)receivedAt=now;
  // Every target is held complete (plus a persisted receipt where sync can revalidate one;
  // the benchmark runs with sync off, where no receipt is kept) and Voxy holds terrain in all 16 columns.
  if(verified==null){if(DhClientSession.diagnostics().syncAvailable()){if((boolean)P4Probe.field(s,"receiptsUnavailable"))throw new IllegalStateException("DH receipts unavailable");var receipts=(Map<?,?>)P4Probe.field(s,"stored");if(!TARGETS.stream().allMatch(receipts::containsKey))return;}
   var level=mc.level;var probe=DhVoxyBridge.openProbe(level);if(probe==null)throw new IllegalStateException("Pinned Voxy presence probe unavailable");
   int minY=level.getMinBuildHeight(),height=level.getHeight();verified=CompletableFuture.supplyAsync(()->{try{
   for(var pos:TARGETS)for(int c=0;c<16;c++)if(!probe.hasTerrain(pos.x()*4+c/4,pos.z()*4+c%4,minY,height))return false;return true;
  }catch(Exception e){throw new CompletionException(e);}});return;}
  if(!verified.isDone())return;if(!verified.get()){verified=null;return;}
  if(verifiedAt==0)verifiedAt=now; if(now-verifiedAt<15_000_000_000L || VoxyDrawStats.sections()==0)return;
  row=new LinkedHashMap<>();row.put("arm","lss");row.put("actual_loaded_geometry_sections",VoxyDrawStats.sections());row.put("actual_voxy_render_calls",VoxyDrawStats.calls);row.put("requests",asks);row.put("target_count",TARGETS.size());row.put("all_source_complete_and_voxy_present",true);row.put("acquisition_wall_seconds",(receivedAt-began)/1e9);row.put("diagnostics",DhClientSession.diagnostics());
  var probe=Class.forName("dev.vox.lssfixture.dh.Probe");for(String key:List.of("conversionCpu","conversionWall","conversionSamples","conversionAllocation"))row.put(key,((AtomicLong)P4Probe.field(probe,key)).get());
  framesAt=now;if(Boolean.getBoolean("lss.p4.windowsNative"))FrameMetrics.start();
 }
}
