package dev.vox.lss.common.diagnostics;

import dev.vox.lss.common.dh.DhProfile;
import dev.vox.lss.common.dh.DhAcquisition.Gate;
import java.util.List;

/** Allowlisted DH observations. No address, world key, position, server text or durable-storage claim. */
public record DhStatusSnapshot(DhProfile profile, State state, long requestedTiles, long enqueuedTiles,
        long incompleteTiles, long refusedTiles, long convertedColumns, long enqueuedColumns,
        long vanillaSkippedColumns, long failedColumns, long wireBytes, long ownedBytes, long peakOwnedBytes,
        int decodeQueue, int inFlight, int ingestBacklog, int effectiveRange, int generationRate,
        int syncRate, int bandwidthKB, boolean syncOnLoad, boolean syncAvailable,
        boolean liveUpdates, boolean liveAvailable, long receivedUpdates, long enqueuedUpdates, long lastSyncAgeMillis,
        long completeColumns, long partialColumns, long unknownColumns, long cacheDeclinedAdmissions, long pressureDroppedTerrainFrames,
        Reason reason,long reusedColumns,long preservedColumns, Gate acquisitionWait, long acquisitionGates, long acquisitionWaitMillis) {
    public enum State { PAUSED, NEED_TRANSPORT, NEED_VOXY, BETWEEN_SESSIONS, WAIT_CONFIG, WAIT_WORLD, WAIT_FAMILY, READY, BLOCKED, STOPPED }
    public enum Reason { NONE, NATIVE_PREFERRED, NATIVE_OFFER, TRANSFER_RETRY_DISABLED, LEGACY_TRIAL, FAMILY_UNCONFIRMED, WORLD_CHANGED, LOCAL_CACHE_UNAVAILABLE, SOURCE_STOPPED, CLIENT_MOD_CONFLICT, TRANSPORT_UNAVAILABLE, LEGACY_TRIAL_SUPPRESSED }
    public List<String> lines() {
        return List.of("Source: Distant Horizons, " + profile + ", " + state+", reason="+reason,
                "Acquisition: "+acquisitionWait+", gates="+acquisitionGates+", local gate wait="+acquisitionWaitMillis+" ms (-1: readiness unknown; not a delivery ETA)",
                "Terrain: block-resolution DH approximation; complete/partial/unknown columns="
                        +completeColumns+"/"+partialColumns+"/"+unknownColumns,
                "Tiles: requested="+requestedTiles+", enqueued="+enqueuedTiles+", incomplete="+incompleteTiles+", refused="+refusedTiles,
                "Columns: converted="+convertedColumns+", enqueued="+enqueuedColumns+", vanilla skipped="+vanillaSkippedColumns+", failed="+failedColumns,
                "Resources: wire="+wireBytes+", owned="+ownedBytes+", peak owned="+peakOwnedBytes
                        +" bytes; decode="+decodeQueue+", requests="+inFlight+", Voxy backlog="+ingestBacklog,
                "Limits: range="+effectiveRange+" chunks, generation="+generationRate+", sync="+syncRate
                        +" requests/s, bandwidth="+bandwidthKB+" KB/s",
                "Source cache: declined admissions="+cacheDeclinedAdmissions+", pressure-dropped terrain frames="+pressureDroppedTerrainFrames
                        +", current-readback reused/preserved columns="+reusedColumns+"/"+preservedColumns,
                "Freshness: session-only enqueue acceptance; sync on load="+syncOnLoad+", available="+syncAvailable
                        +"; live updates="+liveUpdates+", available="+liveAvailable+", received/enqueued="+receivedUpdates+"/"+enqueuedUpdates
                        +", last sync age="+lastSyncAgeMillis+" ms; completed ingestion/persistence unacknowledged");
    }
}
