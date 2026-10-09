package dev.vox.lssfixture.p4;
import org.lwjgl.opengl.*;
import java.util.*;
/** Asynchronous GPU timers and actual runTick entry intervals, excluding loading. */
public final class FrameMetrics {
 public static boolean measuring;static long previous;static int query;static final ArrayDeque<Integer> pending=new ArrayDeque<>();
 static final List<Long> intervals=new ArrayList<>(),gpu=new ArrayList<>();
 public static void start(){measuring=true;previous=0;intervals.clear();gpu.clear();}
 public static void begin(){
  if(!measuring || net.minecraft.client.Minecraft.getInstance().level==null)return;
  long now=System.nanoTime();if(previous!=0)intervals.add(now-previous);previous=now;
  while(!pending.isEmpty() && GL15.glGetQueryObjecti(pending.peek(),GL15.GL_QUERY_RESULT_AVAILABLE)!=0){int q=pending.remove();gpu.add(GL33.glGetQueryObjectui64(q,GL15.GL_QUERY_RESULT));GL15.glDeleteQueries(q);}
  if(pending.size()<32){query=GL15.glGenQueries();GL15.glBeginQuery(GL33.GL_TIME_ELAPSED,query);}
 }
 public static void end(){if(query!=0){GL15.glEndQuery(GL33.GL_TIME_ELAPSED);pending.add(query);query=0;}}
 public static Map<String,Object> finish(){
  measuring=false;GL11.glFinish();while(!pending.isEmpty()){int q=pending.remove();gpu.add(GL33.glGetQueryObjectui64(q,GL15.GL_QUERY_RESULT));GL15.glDeleteQueries(q);}
  if(intervals.size()<200 || gpu.size()<200)throw new IllegalStateException("insufficient rendered frame samples");
  String renderer=GL11.glGetString(GL11.GL_RENDERER);if(renderer==null || renderer.toLowerCase().contains("llvmpipe")||renderer.toLowerCase().contains("software"))throw new IllegalStateException("native GPU measurement required");
  return Map.of("gpu_vendor",GL11.glGetString(GL11.GL_VENDOR),"gpu_renderer",renderer,"gl_version",GL11.glGetString(GL11.GL_VERSION),"frame_entry_interval_ns",List.copyOf(intervals),"gpu_elapsed_ns",List.copyOf(gpu),"condition","1280x720 window on private non-input desktop; capped steady-camera cadence only; GL timer spans include submission gaps and limiter wait, not isolated GPU busy time or compositor/display latency");
 }
}
