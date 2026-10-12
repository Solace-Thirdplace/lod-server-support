package dev.vox.lss.common.diagnostics;

import dev.vox.lss.common.config.ClientSettings;
import dev.vox.lss.common.config.SettingsSchema;
import java.util.List;
import java.util.Set;

/** Settings observed by explicit reads/saves; never watches disk or exposes private values. */
public record ClientSettingsStatus(boolean available, Values saved, Values configured, Values effective,
        List<String> pendingReload, List<String> pendingReconnect, long publishedRevision, long adoptedRevision,
        List<String> pendingAdoption) {
    private static final Set<String> PATHS = SettingsSchema.client().descriptors().stream()
            .map(SettingsSchema.Descriptor::path).collect(java.util.stream.Collectors.toUnmodifiableSet());
    /** Deliberate scalar allowlist. Addresses, alias members and fallback mappings stay private. */
    public record Values(boolean receptionEnabled, int distanceChunks, int maxColumnsPerSecond,
                         boolean mapIngestionEnabled, boolean sharingEnabled) {
        static Values from(ClientSettings settings) {
            return new Values(settings.lod().receive(), settings.lod().distanceChunks(),
                    settings.lod().download().maxColumnsPerSecond(), settings.integrations().mapIngestion().enabled(),
                    settings.farPlayers().sharing().enabled());
        }
    }
    public ClientSettingsStatus {
        pendingReload = safePaths(pendingReload);
        pendingReconnect = safePaths(pendingReconnect);
        pendingAdoption = safePaths(pendingAdoption);
    }
    private static List<String> safePaths(java.util.Collection<String> paths) {
        return paths.stream().filter(PATHS::contains).distinct().sorted().toList();
    }
    public static ClientSettingsStatus capture(boolean available, ClientSettings saved, ClientSettings configured,
            ClientSettings effective, Set<String> pendingReconnect) {
        return capture(available, saved, configured, effective, pendingReconnect, 0, 0, Set.of());
    }
    public static ClientSettingsStatus capture(boolean available, ClientSettings saved, ClientSettings configured,
            ClientSettings effective, Set<String> pendingReconnect, long publishedRevision, long adoptedRevision,
            Set<String> pendingAdoption) {
        var accepted = configured.values();
        var pending = saved.values().entrySet().stream()
                .filter(entry -> !java.util.Objects.equals(entry.getValue(), accepted.get(entry.getKey())))
                .map(java.util.Map.Entry::getKey).toList();
        return new ClientSettingsStatus(available, Values.from(saved), Values.from(configured), Values.from(effective),
                pending, List.copyOf(pendingReconnect), publishedRevision, adoptedRevision, List.copyOf(pendingAdoption));
    }
}
