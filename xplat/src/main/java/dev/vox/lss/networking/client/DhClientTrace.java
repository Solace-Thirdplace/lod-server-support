package dev.vox.lss.networking.client;

import dev.vox.lss.common.dh.DhPosition;
import dev.vox.lss.common.diagnostics.DhStatusSnapshot;

/** Opt-in DH observations. No payload, address, world key or server-supplied text. */
final class DhClientTrace {
    private static long nextSample;
    private static boolean sampled;

    private DhClientTrace() {}

    /** Client owner; a fresh trace must emit immediately, even on the same session. */
    static void reset() { sampled = false; }

    static boolean sampleDue(long now) {
        if (!ClientTraceLog.enabled() || sampled && now - nextSample < 0) return false;
        sampled = true;
        nextSample = now + 1_000_000_000L;
        return true;
    }

    static void sample(DhStatusSnapshot s, int inbound, long rawLosses, long oldestRequestMillis,
                       int generationRequests, boolean recovery, boolean cacheReady,
                       Integer chunkX, Integer chunkZ) {
        if (!ClientTraceLog.enabled()) return;
        ClientTraceLog.event("dh_status", "\"profile\":\"" + s.profile() + "\",\"state\":\"" + s.state()
                + "\",\"reason\":\"" + s.reason() + "\",\"requested\":" + s.requestedTiles()
                + ",\"enqueued\":" + s.enqueuedTiles() + ",\"columns\":" + s.enqueuedColumns()
                + ",\"refused\":" + s.refusedTiles() + ",\"wire_bytes\":" + s.wireBytes()
                + ",\"owned_bytes\":" + s.ownedBytes() + ",\"decode\":" + s.decodeQueue()
                + ",\"inbound\":" + inbound + ",\"inflight\":" + s.inFlight()
                + ",\"generation_inflight\":" + generationRequests + ",\"oldest_request_ms\":" + oldestRequestMillis
                + ",\"ingest\":" + s.ingestBacklog() + ",\"range\":" + s.effectiveRange()
                + ",\"pressure_drops\":" + s.pressureDroppedTerrainFrames() + ",\"raw_losses\":" + rawLosses
                + ",\"recovery\":" + recovery + ",\"cache_ready\":" + cacheReady
                + ",\"live_updates\":" + s.liveUpdates() + ",\"live_available\":" + s.liveAvailable()
                + ",\"updates_received\":" + s.receivedUpdates() + ",\"updates_enqueued\":" + s.enqueuedUpdates()
                + ",\"last_reply_ms\":" + s.lastSyncAgeMillis()
                + ",\"acquisition_wait\":\"" + s.acquisitionWait() + "\",\"acquisition_gates\":" + s.acquisitionGates()
                + ",\"acquisition_wait_ms\":" + s.acquisitionWaitMillis()
                + ",\"chunk\":" + (chunkX == null || chunkZ == null ? "null" : "[" + chunkX + "," + chunkZ + "]"));
    }

    static void cancel(int id,DhPosition position) {
        if(!ClientTraceLog.enabled())return;
        ClientTraceLog.event("dh_cancel","\"id\":"+id+",\"x\":"+position.x()+",\"z\":"+position.z()+",\"reason\":\"OUTSIDE_INTEREST\"");
    }
    static void request(int id, DhPosition p, boolean generation, long stamp) {
        if (ClientTraceLog.enabled()) ClientTraceLog.event("dh_request", "\"id\":" + id + position(p)
                + ",\"generation\":" + generation + ",\"timestamp\":" + stamp);
    }

    static void reply(int id, DhPosition p, boolean payload, long ageMillis) {
        if (ClientTraceLog.enabled()) ClientTraceLog.event("dh_reply", "\"id\":" + id + position(p)
                + ",\"payload\":" + payload + ",\"age_ms\":" + ageMillis);
    }

    static void refusal(int id, int reason) {
        if (ClientTraceLog.enabled()) ClientTraceLog.event("dh_refusal", "\"id\":" + id + ",\"reason\":" + reason);
    }

    static void timeout(int id, DhPosition p) {
        if (ClientTraceLog.enabled()) ClientTraceLog.event("dh_timeout", "\"id\":" + id + position(p));
    }

    enum Pressure { INBOUND_QUEUE, OWNED_BYTES, ASSEMBLY_OR_DECODE, LIVE_BACKLOG, LIVE_RESOURCE, RECOVERY_QUEUE }

    static void pressure(Pressure reason) {
        if (ClientTraceLog.enabled()) ClientTraceLog.event("dh_pressure", "\"reason\":\"" + reason + "\"");
    }

    private static String position(DhPosition p) {
        return ",\"tile\":[" + p.x() + "," + p.z() + "],\"detail\":" + p.detail();
    }
}
