package dev.vox.lss.common.config;

import dev.vox.lss.common.LSSLogger;

import java.util.List;

/** Logs the worlds with no LOD service (distance 0) at service start and whenever that set
 *  changes on a reload — so a typo that turns a world off is visible in the log, not only in
 *  {@code /lsslod diag}. Unrelated distance edits log nothing. One instance per service,
 *  called from its tick (one thread). */
public final class UnservedWorldsLog {
    /** Where the lines go (a seam for tests). */
    interface Sink { void log(boolean warn, String message); }

    private final Sink sink;
    private ServerSettings.LodDistance lastDistance;
    private List<String> lastOff;
    private boolean lastAllOff;

    public UnservedWorldsLog() {
        this((warn, message) -> { if (warn) LSSLogger.warn(message); else LSSLogger.info(message); });
    }

    UnservedWorldsLog(Sink sink) { this.sink = sink; }

    public void note(ServerConfigBase config) {
        note(config, config::unservedWorlds);
    }

    /** {@code unserved} resolves the off list only when the distance settings changed (Paper
     *  passes its per-loaded-world resolution; the config-only list is the default). */
    public void note(ServerConfigBase config, java.util.function.Supplier<List<String>> unserved) {
        var distance = config.snapshot().lod().distance();
        if (distance == this.lastDistance || distance.equals(this.lastDistance)) return;
        this.lastDistance = distance;
        var off = unserved.get();
        boolean allOff = config.maxConfiguredLodDistanceChunks() <= 0;
        boolean first = this.lastOff == null;
        if (!off.equals(this.lastOff)) {
            if (!off.isEmpty()) {
                this.sink.log(false, "LOD service is off (distance 0) in: " + String.join(", ", off));
            } else if (!first) {
                this.sink.log(false, "LOD service is on in every world");
            }
        }
        if (allOff && (first || !this.lastAllOff)) {
            this.sink.log(true, "Every LOD distance is 0: no world is served LODs (far players still run)");
        }
        this.lastOff = off;
        this.lastAllOff = allOff;
    }
}
