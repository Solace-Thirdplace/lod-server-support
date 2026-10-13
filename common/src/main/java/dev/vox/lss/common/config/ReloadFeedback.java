package dev.vox.lss.common.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reports schema paths and sanitized normalization values; private values arrive redacted. */
public final class ReloadFeedback {
    public static List<String> lines(Path file, SettingsReload.Outcome<?> outcome) {
        var lines = new ArrayList<String>();
        String name = file == null ? "settings" : file.getFileName().toString();
        var change = outcome.commit();
        lines.add(switch (outcome.status()) {
            case APPLIED -> "Reloaded " + name + ": " + change.changedPaths().size() + " active setting(s) applied.";
            case UNCHANGED -> "Reloaded " + name + ": no active changes.";
            case ADOPTION_PENDING -> "Accepted " + name + "; subsystem adoption pending: " + String.join(", ", change.changedPaths());
            case RECONCILIATION_FAILED -> outcome.detail();
        });
        if (!change.pendingRestart().isEmpty()) lines.add("Restart required: " + String.join(", ", change.pendingRestart()));
        if (!change.pendingReconnect().isEmpty()) lines.add("Reconnect required: " + String.join(", ", change.pendingReconnect()));
        if (!change.inactivePaths().isEmpty()) lines.add("Inactive on this platform: " + String.join(", ", change.inactivePaths()));
        if (!change.normalizations().isEmpty()) {
            lines.addAll(normalizationLines(change.normalizations()));
            lines.add("YAML was left unchanged.");
        }
        if (change.changedPaths().stream().anyMatch(path -> path.startsWith("lod.distance.") || path.equals("generation.enabled")))
            lines.add("Existing legacy clients may need to reconnect for the new distance or generation policy.");
        if (change.changedPaths().contains(MAP_POLICY_PATH)) lines.add(MAP_POLICY_CHANGED);
        return List.copyOf(lines);
    }

    /** The map-ingestion request's path (map-policy plan A3.1). */
    public static final String MAP_POLICY_PATH = "privacy.request_no_map_ingestion";
    /** The path-keyed line (no service observation yet): the honesty scope in one line. */
    public static final String MAP_POLICY_CHANGED = "Map ingestion request changed: clients on 0.19.0 or newer"
            + " follow it at once; while it is on, older clients get no distant terrain until they"
            + " update. It is a request to clients, not enforcement.";

    /**
     * Replaces the path-keyed map-policy line with the service's observation of the same
     * revision (map-policy plan A3.1 — its own feedback, never the legacy-reconnect text).
     *
     * @param armed   the policy after this reload
     * @param revoked sessions the arm sweep revoked at this revision, or -1 when the
     *                service saw no policy change (the line is then left as is)
     */
    public static void applyMapPolicyOutcome(List<String> lines, boolean armed, int revoked) {
        if (revoked < 0) return;
        lines.removeIf(MAP_POLICY_CHANGED::equals);
        lines.add(armed
                ? "Map ingestion request on: clients on 0.19.0 or newer stop adding distant terrain"
                        + " to their maps now; " + revoked + " older client(s) lost distant terrain"
                        + " until they update (legacy-protocol clients keep it until they"
                        + " reconnect). It is a request to clients, not enforcement."
                : "Map ingestion request off: clients may add distant terrain to their maps again;"
                        + " players refused while it was on are re-offered distant terrain.");
    }
    public static List<String> normalizationLines(List<SettingsSchema.Normalization> normalizations) {
        return normalizations.stream().map(n -> "Normalized " + diagnosticValue(n.path())
                + ": requested=" + diagnosticValue(n.requested()) + ", effective=" + diagnosticValue(n.effective())).toList();
    }
    private static String diagnosticValue(Object value) {
        String text = String.valueOf(value);
        var result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (result.length() >= 256) { result.append("…"); break; }
            char c = text.charAt(i);
            // Keep each operator notice on one line, including Unicode line controls.
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
                    || Character.getType(c) == Character.LINE_SEPARATOR || Character.getType(c) == Character.PARAGRAPH_SEPARATOR)
                result.append(String.format("\\u%04x", (int)c));
            else result.append(c);
        }
        return result.toString();
    }
    private ReloadFeedback() {}
}
