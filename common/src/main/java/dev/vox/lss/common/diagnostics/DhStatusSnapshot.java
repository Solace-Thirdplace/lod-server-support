package dev.vox.lss.common.diagnostics;

import dev.vox.lss.common.dh.DhProfile;
import java.util.List;

/**
 * Allowlisted DH observations. No address, world key, position, server text or
 * durable-storage claim. Deliberately small: one lifecycle state, one reason, one
 * read-only activity summary and counters. Detail belongs in the opt-in trace.
 */
public record DhStatusSnapshot(DhProfile profile, State state, Reason reason, Activity activity,
        long requestedTiles, long enqueuedTiles, long incompleteTiles, long refusedTiles,
        long enqueuedColumns, long vanillaSkippedColumns, long failedColumns,
        long wireBytes, long ownedBytes, int decodeQueue, int inFlight, int ingestBacklog,
        int effectiveRange, int generationRate, int syncRate, int bandwidthKB,
        boolean syncOnLoad, boolean syncAvailable, boolean liveUpdates, boolean liveAvailable,
        long receivedUpdates, long deferredUpdates, long lastReplyAgeMillis,
        long storedReceipts, long restoredReceipts, long pressureDroppedTerrainFrames) {
    public enum State { PAUSED, NEED_TRANSPORT, NEED_VOXY, BETWEEN_SESSIONS, NO_SERVICE, CONNECTING, READY, BLOCKED, STOPPED }
    public enum Reason {
        NONE, NATIVE_PREFERRED, NATIVE_OFFER, CLIENT_MOD_CONFLICT, TRANSPORT_UNAVAILABLE, RESTART_REQUIRED,
        NO_DH_CHANNEL, NO_DH_REPLY, OLD_DH_PROTOCOL, WORLD_CHANGED, VANILLA_LIMIT, DIMENSION_LIMIT,
        SOURCE_STOPPED, SYNC_DISABLED_LOCALLY, RECEIPTS_UNAVAILABLE, RECEIPTS_UNVERIFIED
    }
    /** What acquisition is doing right now. Never an ETA. */
    public enum Activity { WORKING, WAITING_SERVER, WAITING_VOXY, IDLE, STOPPED }

    public List<String> lines() {
        return List.of("Source: Distant Horizons, " + profile + ", " + state + ", reason=" + reason,
                "Activity: " + activity,
                "Terrain: block-resolution DH approximation",
                "Tiles: requested=" + requestedTiles + ", enqueued=" + enqueuedTiles + ", incomplete=" + incompleteTiles + ", refused=" + refusedTiles,
                "Columns: enqueued=" + enqueuedColumns + ", vanilla skipped=" + vanillaSkippedColumns + ", failed=" + failedColumns,
                "Resources: wire=" + wireBytes + ", owned=" + ownedBytes + " bytes; decode=" + decodeQueue
                        + ", requests=" + inFlight + ", Voxy backlog=" + ingestBacklog,
                "Limits: range=" + effectiveRange + " chunks, generation=" + generationRate + ", sync=" + syncRate
                        + " requests/s, bandwidth=" + bandwidthKB + " KB/s",
                "Receipts: stored=" + storedReceipts + ", restored this session=" + restoredReceipts
                        + ", pressure-dropped terrain frames=" + pressureDroppedTerrainFrames,
                "Freshness: sync on load=" + syncOnLoad + ", available=" + syncAvailable
                        + "; live updates=" + liveUpdates + ", available=" + liveAvailable
                        + ", applied/deferred=" + receivedUpdates + "/" + deferredUpdates
                        + ", last reply=" + (lastReplyAgeMillis < 0 ? "none yet" : lastReplyAgeMillis + " ms ago"));
    }
}
