package dev.vox.lss.spongesmoke;

import com.google.inject.Inject;
import dev.vox.lss.networking.server.LSSServerNetworking;
import dev.vox.lss.platform.LoaderServices;
import dev.vox.lss.sponge.SpongeChannels;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.api.Platform;
import org.spongepowered.api.Server;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.lifecycle.StartedEngineEvent;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.util.Ticks;
import org.spongepowered.api.util.Tristate;
import org.spongepowered.plugin.PluginContainer;
import org.spongepowered.plugin.builtin.jvm.Plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Dev-only boot-smoke companion (sponge-support-plan.md §5.2). Never shipped: it lives in
 * the {@code smoke} source set, is jarred outside build/libs under a non-release name, and
 * release_check forbids its package. {@code scripts/sponge_boot_smoke.py} boots the
 * pinned SpongeVanilla server twice on one world with the RELEASE jar beside this one:
 * <ul>
 *   <li>phase 1 (fresh world): service up, 13 channels, tick hook, permission defaults,
 *       diag through the Raw command, store state, the implementation guard; a forced
 *       generation fires {@code ChunkLoadHook} with newlyGenerated=true; a forced save
 *       fires {@code ChunkSaveDataHook};</li>
 *   <li>phase 2 (same world): a disk-read serve of the phase-1 chunk through the
 *       service's own reader (Sponge overwrites IOWorker internals the bg-split path
 *       reads), then a forced re-load fires {@code ChunkLoadHook} with
 *       newlyGenerated=false.</li>
 * </ul>
 * The verdict is a JSON file ({@code -Dlss.sponge.smoke.result}); the server then stops.
 */
@Plugin("lsssmoke")
public class SmokePlugin {
    static final int PROBE_X = 300;
    static final int PROBE_Z = 300;

    private final PluginContainer container;
    private final Map<String, Object> checks = new LinkedHashMap<>();
    private boolean pass = true;

    @Inject
    public SmokePlugin(PluginContainer container) {
        this.container = container;
    }

    @Listener
    public void onStarted(StartedEngineEvent<Server> event) {
        Sponge.server().scheduler().submit(Task.builder().plugin(container)
                .delay(Ticks.of(40)).execute(this::run).build());
    }

    private void check(String name, boolean ok, Object detail) {
        var row = new LinkedHashMap<String, Object>();
        row.put("ok", ok);
        row.put("detail", String.valueOf(detail));
        checks.put(name, row);
        pass &= ok;
    }

    private void run() {
        String phase = System.getProperty("lss.sponge.smoke.phase", "1");
        try {
            var server = (MinecraftServer) (Object) Sponge.server();
            ServerLevel level = server.overworld();
            var service = LSSServerNetworking.getRequestService();
            check("service_started", service != null, service);
            check("channels_registered", SpongeChannels.registeredChannelCount() == 13,
                    SpongeChannels.registeredChannelCount());
            check("tick_hook", LSSServerNetworking.TICK_HOOK_CALLS.get() >= 20,
                    LSSServerNetworking.TICK_HOOK_CALLS.get());
            String impl = Sponge.platform().container(Platform.Component.IMPLEMENTATION).metadata().id();
            check("implementation_spongevanilla", "spongevanilla".equals(impl), impl);
            String loader = LoaderServices.get().diagnosticVersions().components()
                    .get(dev.vox.lss.common.diagnostics.DiagnosticVersions.Component.LOADER);
            check("diag_loader_token", loader != null && loader.startsWith("sponge-"), loader);
            var defaults = Sponge.server().serviceProvider().permissionService().defaults();
            check("perm_use_default_true", defaults.permissionValue("lss.use") == Tristate.TRUE,
                    defaults.permissionValue("lss.use"));
            check("perm_hidden_default_false", defaults.permissionValue("lss.farplayers.hidden") == Tristate.FALSE,
                    defaults.permissionValue("lss.farplayers.hidden"));
            var diag = Sponge.server().commandManager().process(dev.vox.lss.common.Brand.serverCommand() + " diag");
            check("diag_command", diag.isSuccess(), diag);
            if (service != null) {
                var store = service.getLodStore();
                check("store_state_ok", store != null && "ok".equals(store.stateToken()),
                        store == null ? "null" : store.stateToken());
            }
            if (phase.equals("1")) {
                long generated = LSSServerNetworking.LOAD_HOOK_GENERATED_CALLS.get();
                level.getChunk(PROBE_X, PROBE_Z);
                check("load_hook_generated", LSSServerNetworking.LOAD_HOOK_GENERATED_CALLS.get() > generated,
                        LSSServerNetworking.LOAD_HOOK_GENERATED_CALLS.get() - generated);
                long saves = LSSServerNetworking.SAVE_HOOK_CALLS.get();
                Sponge.server().commandManager().process("save-all flush");
                check("save_hook", LSSServerNetworking.SAVE_HOOK_CALLS.get() > saves,
                        LSSServerNetworking.SAVE_HOOK_CALLS.get() - saves);
            } else if (service != null) {
                var reader = service.getDiskReader();
                byte[] bytes = CompletableFuture.supplyAsync(() -> {
                    try {
                        return reader.readColumnBytesSyncForBackfill(level, PROBE_X, PROBE_Z);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }).get(60, TimeUnit.SECONDS);
                check("disk_read_serve", bytes != null && bytes.length > 0, bytes == null ? "null" : bytes.length);
                String readerDiag = reader.getDiagnostics();
                check("read_path_bg_split", readerDiag.contains("read_path=bg-split"), readerDiag);
                long disk = LSSServerNetworking.LOAD_HOOK_DISK_CALLS.get();
                level.getChunk(PROBE_X, PROBE_Z);
                check("load_hook_disk", LSSServerNetworking.LOAD_HOOK_DISK_CALLS.get() > disk,
                        LSSServerNetworking.LOAD_HOOK_DISK_CALLS.get() - disk);
            }
        } catch (Throwable t) {
            check("no_exception", false, t);
        }
        write(phase);
        Sponge.server().shutdown();
    }

    private void write(String phase) {
        var out = new StringBuilder("{\"phase\":").append(phase).append(",\"pass\":").append(pass).append(",\"checks\":{");
        boolean first = true;
        for (var e : checks.entrySet()) {
            if (!first) out.append(',');
            first = false;
            @SuppressWarnings("unchecked") var row = (Map<String, Object>) e.getValue();
            out.append('"').append(e.getKey()).append("\":{\"ok\":").append(row.get("ok"))
                    .append(",\"detail\":\"").append(escape((String) row.get("detail"))).append("\"}");
        }
        out.append("}}\n");
        try {
            Files.writeString(Path.of(System.getProperty("lss.sponge.smoke.result", "smoke-result.json")), out);
        } catch (Exception e) {
            System.err.println("[lss-smoke] could not write the verdict: " + e);
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
