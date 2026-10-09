package dev.vox.lssfixture.p4;
public final class VoxyDrawStats {
 public static volatile Object latest;public static volatile long calls;
 public static void rendered(Object renderer){latest=renderer;calls++;}
 public static int sections()throws Exception {
  if(latest==null)return 0;Object data=P4Probe.field(latest,"geometryData");return (int)Class.forName("me.cortex.voxy.client.core.rendering.section.geometry.IGeometryData").getMethod("getSectionCount").invoke(data);
 }
}
