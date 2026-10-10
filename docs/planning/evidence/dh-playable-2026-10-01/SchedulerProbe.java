import dev.vox.lss.common.dh.*;
import java.util.*;
public class SchedulerProbe {
 public static void main(String[] args) {
  DhSessionPolicy policy=new DhSessionPolicy(); List<DhPosition> order=new ArrayList<>();
  for(int x=-8;x<=8;x++) for(int z=-8;z<=8;z++) policy.vanilla(x,z);
  int range=32,cx=0,cz=0,rings=(range+3)/4;
  for(int ring=0;ring<=rings;ring++) for(int dx=-ring;dx<=ring;dx++) for(int dz=-ring;dz<=ring;dz++) {
   if(Math.max(Math.abs(dx),Math.abs(dz))!=ring) continue;
   DhPosition p=new DhPosition(6,cx+dx,cz+dz); int x=p.x()*4,z=p.z()*4;
   if(Math.max(Math.abs(x+2),Math.abs(z+2))>range)continue;
   boolean any=false; for(int ox=0;ox<4;ox++)for(int oz=0;oz<4;oz++)any|=policy.eligible(x+ox,z+oz);
   if(any)order.add(p);
  }
  Set<DhPosition> seen=new HashSet<>(); Map<DhPosition,Integer> calls=new HashMap<>();
  for(int second=0;second<1800;second++) {
   long now=second*1_000_000_000L;
   boolean sent=false; for(int priority=0;priority<3 && !sent;priority++) for(DhPosition p:order) if(policy.acquisitionPriority(p)==priority && policy.due(p,now)){ seen.add(p);calls.merge(p,1,Integer::sum);policy.accepted(p,0xffff,0xffff,now,now+100_000_000L,120_000_000_000L);sent=true;break; }
  }
  System.out.println("30min stationary simulation: eligible="+order.size()+", acquired="+seen.size()+", untouched="+(order.size()-seen.size())+", earliest_requests="+calls.get(order.getFirst()));
 }
}
