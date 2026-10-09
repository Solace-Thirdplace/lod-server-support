package dev.vox.lssfixture.p4;

import dev.vox.lss.common.dh.*;
import dev.vox.lss.networking.client.DhClientSession;
import net.minecraft.client.Minecraft;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Actual producer configuration checks, including honest partial-source admission. */
public final class ConfigurationProbe {
    static final Map<DhPosition, Map<String,Object>> sources = new ConcurrentHashMap<>();
    static long joined, observed;
    public static void decoded(DhTerrainTile tile) {
        if (!P4Probe.mode().equals("configuration") || !BenchmarkClient.TARGETS.contains(tile.position())) return;
        int complete=0, partial=0, unknown=0, spans=0;
        for(int c=0;c<4096;c++) {
            switch(tile.coverage(c)) { case COMPLETE -> complete++; case PARTIAL -> partial++; case UNKNOWN -> unknown++; }
            spans+=tile.spanCount(c);
        }
        var steps=new TreeSet<Integer>();for(int c=0;c<4096;c++)steps.add(tile.generation(c));
        sources.put(tile.position(),Map.of("complete",complete,"partial",partial,"unknown",unknown,"spans",spans,"generation_steps",steps));
    }
    public static void tick(long now) throws Exception {
        var mc=Minecraft.getInstance();
        mc.player.getAbilities().flying=true;mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        Object session=P4Probe.field(DhClientSession.class,"session");if(session==null)return;
        if(!DhClientSession.running())throw new IllegalStateException(DhClientSession.status());
        if(DhClientSession.diagnostics().state()!=dev.vox.lss.common.diagnostics.DhStatusSnapshot.State.READY)return;
        if(joined==0)joined=now;
        if(now-joined>180_000_000_000L)throw new IllegalStateException("Configuration deadline: "+sources+" / "+DhClientSession.diagnostics());
        String expect=System.getProperty("lss.p4.expect","complete");
        if(expect.equals("complete") || expect.equals("surface") && sources.size()==9 && sources.values().stream().allMatch(row->(int)row.get("complete")==4096)) {BenchmarkClient.tick(now);return;}
        var policy=(DhSessionPolicy)P4Probe.field(session,"policy");
        var receipts=(Map<?,?>)P4Probe.field(session,"stored");
        long requested=((AtomicLong)P4Probe.field(session,"requested")).get();
        if(expect.equals("missing")) {
            if(requested!=0 || !sources.isEmpty() || BenchmarkClient.TARGETS.stream().anyMatch(policy::hasSnapshot))throw new IllegalStateException("Disabled generation admitted absent terrain");
            if(now-joined<30_000_000_000L)return;
        } else if(expect.equals("partial") || expect.equals("surface")) {
            var partial=sources.entrySet().stream().filter(e->(int)e.getValue().get("partial")>0 || (int)e.getValue().get("unknown")>0).toList();
            if(partial.isEmpty())return;
            for(var entry:partial)if(policy.hasSnapshot(entry.getKey()) || receipts.containsKey(entry.getKey()))throw new IllegalStateException("Partial source acquired a complete receipt");
            if(observed==0)observed=now;if(now-observed<5_000_000_000L)return;
        } else throw new IllegalArgumentException(expect);
        P4Probe.pass(Map.of("expectation",expect,"observed_sources",sources.toString(),"requested",requested,"ordinary_connected",mc.getConnection()!=null,"no_false_complete_receipts",true,"diagnostics",DhClientSession.diagnostics()));
    }
}
