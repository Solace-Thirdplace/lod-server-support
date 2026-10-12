package dev.vox.lss.common.config;

import dev.vox.lss.common.LSSLogger;

/** Logs the worlds with no LOD service (distance 0) at service start and whenever the distance
 *  settings change on a reload — so a typo that turns a world off is visible in the log, not
 *  only in {@code /lsslod diag}. One instance per service, called from its tick. */
public final class UnservedWorldsLog {
    private ServerSettings.LodDistance last;

    public void note(ServerConfigBase config) {
        var distance = config.snapshot().lod().distance();
        if (distance == this.last || distance.equals(this.last)) return;
        boolean first = this.last == null;
        this.last = distance;
        var off = config.unservedWorlds();
        if (!off.isEmpty()) {
            LSSLogger.info("LOD service is off (distance 0) in: " + String.join(", ", off));
        } else if (!first) {
            LSSLogger.info("LOD service is on in every world");
        }
        if (config.maxConfiguredLodDistanceChunks() <= 0) {
            LSSLogger.warn("Every LOD distance is 0: no world is served LODs (far players still run)");
        }
    }
}
