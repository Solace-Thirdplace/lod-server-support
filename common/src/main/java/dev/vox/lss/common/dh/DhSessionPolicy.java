package dev.vox.lss.common.dh;

import java.util.*;

/** Session-only freshness and vanilla authority. Caller owns the client thread. */
public final class DhSessionPolicy {
    public static final int MAX_RECEIPTS = 8192;
    public static final int MAX_REJECTED_ATTEMPTS = 4;
    private final Set<Long> vanilla = new HashSet<>();
    private final LinkedHashMap<DhPosition, Receipt> receipts = new LinkedHashMap<>();
    private final Map<DhPosition,Long> rangeRefusals = new HashMap<>();
    private record Receipt(long timestamp, long acceptedAt, long due, boolean generate, boolean snapshot, boolean held, int failures, boolean unreachable, boolean revalidate) {}
    public void vanilla(int x, int z) {
        if (vanilla.size() >= 262_144 && !vanilla.contains(column(x,z)))
            throw new IllegalStateException("DH vanilla authority limit reached; reconnect to resume");
        vanilla.add(column(x,z));
    }
    public boolean eligible(int x, int z) { return !vanilla.contains(column(x,z)); }
    /** Untouched frontier first, then incomplete retries, then accepted refreshes. */
    public int acquisitionPriority(DhPosition p) {
        var receipt=receipts.get(p);
        return receipt==null || receipt.generate && !receipt.held && receipt.failures==0 ? 0 : receipt.snapshot?2:1;
    }
    private boolean snapshot(DhPosition p) { var r=receipts.get(p); return r!=null && r.snapshot; }
    private boolean held(DhPosition p) { var r=receipts.get(p); return r!=null && r.held; }
    public boolean due(DhPosition p, long now) { var r=receipts.get(p); return r==null || !r.held && r.due <= now; }
    /** Deadline ordering prevents near tiles from taking every synchronization slot. */
    public long dueNanos(DhPosition p) { var r=receipts.get(p); return r==null ? Long.MAX_VALUE : r.due; }
    public long timestamp(DhPosition p) { var r=receipts.get(p); return r==null ? 0 : r.timestamp; }
    /** Server timestamps remain in server milliseconds; failed/partial attempts require full data. */
    public long syncTimestamp(DhPosition p) {
        var r=receipts.get(p);
        return r!=null && r.snapshot && !r.generate && r.failures==0 && r.timestamp>0 ? r.timestamp : 0;
    }
    /** An empty reply is unchanged only for an owned conditional request with valid local acceptance. */
    public boolean unchanged(DhPosition p, long requestedTimestamp, long now) {
        if(requestedTimestamp<=0 || syncTimestamp(p)!=requestedTimestamp) return false;
        return accepted(p,1,1,requestedTimestamp,now);
    }
    public boolean generationNeeded(DhPosition p) { var r=receipts.get(p); return r!=null && r.generate && !r.held; }
    public boolean retryExhausted(DhPosition p) { var r=receipts.get(p); return r!=null && r.failures>=MAX_REJECTED_ATTEMPTS; }
    /** Diagnostic distinction: a held successful snapshot is idle, not parked missing work. */
    public boolean parked(DhPosition p) {
        var r=receipts.get(p);return r!=null && r.held && (!r.snapshot || r.generate || r.failures>0);
    }
    /** A replayed source snapshot still needs one sync check on this connection, without periodic polling. */
    public void revalidate(DhPosition p,long now) {
        var r=receipts.get(p);
        if(r!=null && syncTimestamp(p)>0)
            put(p,new Receipt(r.timestamp,r.acceptedAt,now,false,true,false,0,false,true));
    }
    /** A held snapshot may be checked only through the server's synchronization service. */
    public boolean syncAllowed(DhPosition p, boolean synchronize, int syncRate) {
        return !snapshot(p) || synchronize && syncRate>0;
    }
    private long acceptedAt(DhPosition p) { var r=receipts.get(p); return r==null ? 0 : r.acceptedAt; }
    private int failures(DhPosition p) { var r=receipts.get(p); return r==null ? 0 : r.failures; }
    /** One missing sync is enough when generation cannot resolve it. Movement may restore reachability. */
    public void absent(DhPosition p, long now, boolean generationAllowed) {
        put(p, new Receipt(timestamp(p),acceptedAt(p), generationAllowed ? now+2_000_000_000L : Long.MAX_VALUE,
                generationAllowed,snapshot(p),held(p) || !generationAllowed,failures(p),!generationAllowed,false));
    }
    /** Unpark only missing, reachable generation work, never a rejected or successful held snapshot. */
    public void reconcileGeneration(DhPosition p, boolean reachable, long now) {
        var r=receipts.get(p);
        if(r!=null && r.unreachable && reachable && r.failures<MAX_REJECTED_ATTEMPTS && (!r.snapshot || r.failures>0))
            put(p,new Receipt(r.timestamp,r.acceptedAt,now,true,r.snapshot,false,r.failures,false,false));
    }
    /** A range refusal parks until changed interest/bounds make that request reachable. */
    public void rangeRefused(DhPosition p, long rangeEpoch, boolean generation) {
        var r=receipts.get(p);
        put(p,new Receipt(timestamp(p),acceptedAt(p),Long.MAX_VALUE,generation || r!=null && r.generate,
                snapshot(p),true,failures(p),false,false));
        rangeRefusals.put(p,rangeEpoch);
    }
    public boolean rangeNeedsGeneration(DhPosition p) {
        var r=receipts.get(p);return rangeRefusals.containsKey(p) && r!=null && r.generate;
    }
    public void reconcileRange(DhPosition p, long rangeEpoch, boolean reachable, long now) {
        var epoch=rangeRefusals.get(p);var r=receipts.get(p);
        if(epoch!=null && epoch!=rangeEpoch && reachable && r!=null && r.failures<MAX_REJECTED_ATTEMPTS)
            put(p,new Receipt(r.timestamp,r.acceptedAt,now,r.generate,r.snapshot,false,r.failures,false,r.snapshot));
    }
    /** Bound repeated rejection, cancellation or timeout without inventing a successful receipt. */
    public void cooldown(DhPosition p, long now) {
        int failures=Math.min(MAX_REJECTED_ATTEMPTS,failures(p)+1);
        long delay=30_000_000_000L << (failures-1);
        var previous=receipts.get(p);
        put(p,new Receipt(timestamp(p),acceptedAt(p),now+delay,previous!=null && previous.generate,snapshot(p),
                failures>=MAX_REJECTED_ATTEMPTS,failures,false,false));
    }
    /** Temporary pressure never consumes sink-rejection strikes or parks a tile forever. */
    public void defer(DhPosition p,long now) {
        var previous=receipts.get(p);
        put(p,new Receipt(timestamp(p),acceptedAt(p),now+2_000_000_000L,
                previous!=null && previous.generate,snapshot(p),failures(p)>=MAX_REJECTED_ATTEMPTS,failures(p),false,
                previous!=null && previous.revalidate));
    }
    /** A missing source or unverifiable destination requires full data, while retaining rejection strikes. */
    public void deferUnverified(DhPosition p,long now) {
        var previous=receipts.get(p);
        put(p,new Receipt(0,acceptedAt(p),now+2_000_000_000L,previous!=null && previous.generate,
                false,failures(p)>=MAX_REJECTED_ATTEMPTS,failures(p),false,false));
    }
    /** Every eligible MC column must have been accepted. Accepted snapshots are held until a load check or server update. */
    public boolean accepted(DhPosition p, int eligibleMask, int acceptedMask, long timestamp, long now) {
        if(eligibleMask==0) {defer(p,now);return false;}
        if((acceptedMask & eligibleMask)!=eligibleMask) {cooldown(p,now);return false;}
        put(p,new Receipt(timestamp,now,Long.MAX_VALUE,false,true,true,0,false,false)); return true;
    }
    public boolean hasSnapshot(DhPosition position) {
        var receipt=receipts.get(position);
        return receipt!=null && receipt.snapshot && receipt.failures==0 && !receipt.generate;
    }
    /** A partial server edit invalidates the retained whole-source acceptance. */
    public void invalidate(DhPosition position) { receipts.remove(position);rangeRefusals.remove(position); }
    public void invalidateAll() { clearReceipts(); }
    public void clearReceipts() { receipts.clear();rangeRefusals.clear(); }
    /** DH Support advertises its Bukkit border in blocks; official DH uses chunks. */
    public static boolean inBorder(DhProfile profile,DhPosition tile,int centerX,int centerZ,int radius) {
        if(radius==0) return true;
        long x=(long)tile.x()*4+2,z=(long)tile.z()*4+2;
        if(profile.support()) { x*=16; z*=16; }
        return Math.abs(x-centerX)<=radius && Math.abs(z-centerZ)<=radius;
    }
    private void put(DhPosition p, Receipt r) {
        rangeRefusals.remove(p);
        receipts.remove(p); receipts.put(p,r);
        if (receipts.size()>MAX_RECEIPTS) invalidate(receipts.keySet().iterator().next());
    }
    private static long column(int x,int z) { return (x & 0xffffffffL) | ((long)z<<32); }
}
