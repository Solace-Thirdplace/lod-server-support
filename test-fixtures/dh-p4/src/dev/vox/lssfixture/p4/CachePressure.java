package dev.vox.lssfixture.p4;
import dev.vox.lss.common.dh.*;
import dev.vox.lss.networking.client.DhClientSession;
import net.minecraft.client.Minecraft;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
/** Real radius/dimension acquisition plus explicitly injected global disk-cache pressure. */
public final class CachePressure {
 static int phase;static long deadline,last,peak,retireDeadline;static Object previous;static CompletableFuture<Map<String,Object>> pressure;
 static final List<Map<String,Object>> observations=new ArrayList<>();
 static String[] dimensions={"minecraft:overworld","minecraft:the_nether","minecraft:the_end","minecraft:overworld"};
 static Object beforeRejoinConnection;static Set<DhPosition> beforeRejoinSources=Set.of();
 static long count(Object s,String key)throws Exception{return ((AtomicLong)P4Probe.field(s,key)).get();}
 static void command(String dimension)throws Exception {
  String player=Minecraft.getInstance().player.getGameProfile().getName();
  P4Probe.write("dh-server-command.json",Map.of("id","dimension-"+phase,"commands",List.of("execute in "+dimension+" run tp "+player+" 0 130 0 90 15","save-all flush")));
 }
 public static void tick(long now)throws Exception {
  if(deadline==0)deadline=now+600_000_000_000L;if(now>deadline)throw new IllegalStateException("Cache/range phase "+phase+" timed out: "+DhClientSession.status());
  var mc=Minecraft.getInstance();mc.player.getAbilities().flying=true;mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
  if(!mc.level.dimension().location().toString().equals(dimensions[Math.min(phase,3)]))return;
  Object s=P4Probe.field(DhClientSession.class,"session");if(s==null)return;
  if(!DhClientSession.running()){if(DhClientSession.status().contains("reconnecting once"))return;throw new IllegalStateException(DhClientSession.status());}
  if(DhClientSession.diagnostics().state()!=dev.vox.lss.common.diagnostics.DhStatusSnapshot.State.READY)return;
  var budget=(DhMemoryBudget)P4Probe.field(s,"budget");peak=Math.max(peak,budget.peak());
  if(DhClientSession.diagnostics().effectiveRange()!=128)throw new IllegalStateException("128 chunk configured range not effective");
  if(s==previous)return;
  if(previous!=null && ((DhMemoryBudget)P4Probe.field(previous,"budget")).used()!=0) {
   if(retireDeadline==0)retireDeadline=now+30_000_000_000L;
   if(now>retireDeadline)throw new IllegalStateException("retired dimension retained source memory");return;
  }
  var cache=(DhTerrainCache)P4Probe.field(s,"terrainCache");if(cache==null)return;
  if(now-last>2_000_000_000L){last=now;P4Probe.write("dh-p4-status.json",Map.of("phase",phase,"diagnostics",DhClientSession.diagnostics(),"peak_owned_bytes",peak));}
  if(phase<3 && (count(s,"cacheWrites")<4 || count(s,"requested")<12))return;
  if(phase==0){
   if(pressure==null){pressure=CompletableFuture.supplyAsync(()->{try{return seed(cache);}catch(Exception e){throw new CompletionException(e);}});return;}
   if(!pressure.isDone())return;observations.add(pressure.get());
  }
  // Receipts survive this dimension trip. Progress here is not evidence of
  // cross-connection source restoration, which needs the separate rejoin below.
  if(phase==3 && count(s,"cacheHits")==0 && (count(s,"cacheWrites")<4 || count(s,"requested")<12))return;
  if(phase==4 && (count(s,"cacheHits")==0 || count(s,"cacheReusedColumns")==0))return;
  var row=new LinkedHashMap<String,Object>();row.put("dimension",dimensions[Math.min(phase,3)]);row.put("source_cache_files",cache.positions().size());row.put("cache_hits",count(s,"cacheHits"));row.put("cache_writes",count(s,"cacheWrites"));row.put("requests",count(s,"requested"));row.put("diagnostics",DhClientSession.diagnostics());observations.add(row);
  if(phase==3 && P4Probe.mode().equals("cache-rejoin-range")) {
   beforeRejoinSources=Set.copyOf(cache.positions());if(beforeRejoinSources.isEmpty())throw new IllegalStateException("no genuine source available for rejoin");
   beforeRejoinConnection=mc.getConnection().getConnection();
   row.put("return_path","same_connection_progress_before_actual_rejoin");row.put("sources_before_rejoin",beforeRejoinSources.stream().map(DhPosition::packed).sorted().toList());
   previous=s;phase=4;deadline=now+600_000_000_000L;P4Probe.phase=0;mc.level.disconnect();mc.disconnect();mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen());return;
  }
  if(phase==4) {
   if(mc.getConnection().getConnection()==beforeRejoinConnection)throw new IllegalStateException("physical connection did not change");
   if(count(s,"cacheHits")==0 || count(s,"cacheReusedColumns")==0)return;
   row.put("return_path","actual_cross_connection_source_and_voxy_reuse");row.put("previous_source_tiles",beforeRejoinSources.size());
   P4Probe.pass(Map.of("observations",observations,"configured_range_chunks",128,"actual_cross_connection_reuse_proved",true,"full_radius_fill_proved",false));return;
  }
  if(phase==3){row.put("return_path",count(s,"cacheHits")>0?"retained_source_reuse":"same_connection_frontier_progress");P4Probe.pass(Map.of("observations",observations,"configured_range_chunks",128,"peak_owned_bytes",peak,"bounded_progress_not_full_radius_fill",true,"cross_connection_warm_reuse_proved",false,"pressure_is_injected_representative_records",true));return;}
  previous=s;phase++;retireDeadline=0;command(dimensions[phase]);deadline=now+600_000_000_000L;
  // Subsequent tick validates old-session release after the dimension swap has completed.
 }
 static Map<String,Object> seed(DhTerrainCache home)throws Exception {
  Path root=((Path)P4Probe.field(home,"root")).getParent();var positions=home.positions();var pos=positions.iterator().next();byte[] data;long stamp;
  var memory=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
  try(var entry=home.read(pos,memory)){if(entry==null)throw new IllegalStateException("pressure source lost");data=((byte[])P4Probe.field(entry,"data")).clone();stamp=entry.timestamp();}
  if(memory.used()!=0)throw new IllegalStateException("pressure read retained memory");
  var buckets=new ArrayList<DhTerrainCache>();for(String dimension:List.of("injected-overworld","injected-nether","injected-end"))buckets.add(new DhTerrainCache(root,new DhTerrainCache.Key("owned-pressure","owned-pressure","owned-pressure",dimension,0,DhProfile.OFFICIAL_16,new DhTerrainTile.WorldShape(-64,384,true))));
  long began=System.nanoTime();int writes=DhTerrainCache.MAX_FILES+192;
  for(int i=0;i<writes;i++){var p=new DhPosition(6,10000+i,10000);java.nio.ByteBuffer.wrap(data).putLong(p.packed());if(!buckets.get(i%3).write(p,stamp,data))throw new IllegalStateException("representative write refused");}
  Path directory=root.resolve(DhTerrainCache.DIRECTORY);long files=0,bytes=0;
  synchronized(P4Probe.field(DhTerrainCache.class,"IO_LOCK")) {
   try(var walk=Files.walk(directory)){for(Path f:walk.filter(x->x.toString().endsWith(".dht")).filter(Files::isRegularFile).toList()){files++;bytes+=Files.size(f);}}
  }
  if(files>DhTerrainCache.MAX_FILES || bytes>DhTerrainCache.MAX_BYTES || files==0 || files>=writes)throw new IllegalStateException("global quota not enforced: "+files+" / "+bytes);
  return Map.of("pressure_writes",writes,"pressure_buckets",3,"remaining_files",files,"remaining_bytes",bytes,"eviction_verified",files<writes,"write_wall_seconds",(System.nanoTime()-began)/1e9,"synthetic_representative_records",true);
 }
}
