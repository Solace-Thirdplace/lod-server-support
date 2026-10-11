package dev.vox.lss.networking.server;

import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.vox.lss.networking.payloads.ClientInfoC2SPayload;
import dev.vox.lss.networking.payloads.FarPlayerPrefsC2SPayload;
import dev.vox.lss.networking.payloads.HandshakeC2SPayload;
import dev.vox.lss.networking.payloads.RegionSummaryRequestC2SPayload;
import dev.vox.lss.platform.LoaderServices;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sponge server-side networking glue — the fabric/neoforge {@code LSSServerNetworking}
 * twin (same FQN; sponge-support-plan.md §1.1). The receiver BODIES are the shared
 * {@link ServerReceiverGlue}; this class owns the static service holder and the
 * lifecycle/payload entry points that {@code LSSSpongePlugin}, {@code SpongeChannels} and
 * the mixins call. Dedicated server only: SpongeVanilla has no integrated-server/LAN path.
 *
 * <p>The hook counters are diagnostics for the boot smoke (§5.2): they prove the three
 * mixins actually FIRE on the pinned SpongeVanilla build, not merely that they applied.
 */
public class LSSServerNetworking {
    private static volatile RequestProcessingService requestService;

    public static final AtomicLong TICK_HOOK_CALLS = new AtomicLong();
    public static final AtomicLong SAVE_HOOK_CALLS = new AtomicLong();
    public static final AtomicLong LOAD_HOOK_DISK_CALLS = new AtomicLong();
    public static final AtomicLong LOAD_HOOK_GENERATED_CALLS = new AtomicLong();

    public static RequestProcessingService getRequestService() {
        return requestService;
    }

    /** The client's announced MC data version, or null for a legacy client. */
    public static Integer clientDataVersion(UUID uuid) {
        return ServerReceiverGlue.clientDataVersion(uuid);
    }

    /** Hook body for the {@code ChunkSaveDataHook} mixin twin. */
    public static void onChunkSaveData(ServerLevel level, ChunkAccess chunk) {
        SAVE_HOOK_CALLS.incrementAndGet();
        ServerReceiverGlue.onChunkSaveData(level, chunk, requestService);
    }

    /** Hook body for {@code ChunkLoadHook}: the FULL-status promotion (fabric-api's
     *  {@code CHUNK_LOAD} point, with its {@code generated} derivation). */
    public static void onChunkLoad(ServerLevel level, ChunkAccess chunk, boolean newlyGenerated) {
        (newlyGenerated ? LOAD_HOOK_GENERATED_CALLS : LOAD_HOOK_DISK_CALLS).incrementAndGet();
        ServerReceiverGlue.onChunkLoaded(level, chunk, requestService, newlyGenerated);
    }

    // ---- Lifecycle (driven by LSSSpongePlugin and ServerTickHook) ----

    public static void onServerStarted(MinecraftServer server) {
        dev.vox.lss.config.LSSServerConfig.beginServerLifecycle();
        LSSLogger.info("Starting " + Brand.shortName() + " LOD request processing service");
        requestService = new RequestProcessingService(server);
        ServerReceiverGlue.flushPendingLoadSeeds(server, requestService); // the pre-service spawn set
    }

    public static void onServerStopping() {
        dev.vox.lss.config.LSSServerConfig.CONFIG.close();
        var service = requestService;
        if (service != null) {
            LSSLogger.info("Stopping " + Brand.shortName() + " LOD request processing service");
            service.shutdown();
            requestService = null;
        }
        // Sidecar facts die with the server (review C1-9).
        ServerReceiverGlue.clearClientInfo();
    }

    /** {@code ServerTickHook}: {@code tickServer} TAIL = fabric's END_SERVER_TICK. */
    public static void onServerTick() {
        TICK_HOOK_CALLS.incrementAndGet();
        var service = requestService;
        if (service != null) {
            service.tick();
        }
    }

    public static void onPlayerDisconnect(UUID uuid) {
        var service = requestService;
        if (service != null) {
            service.removePlayer(uuid);
            // Network-level: the disconnect drops the compat session identities
            // (removePlayer above only reset the v16 want-set and touches neither
            // membership — dim changes reuse that path and must keep both).
            service.getV16CompatManager().onDisconnect(uuid);
            service.getDialectTracker().onDisconnect(uuid);
            // Far players: the subscription AND the retained target prefs die with
            // the CONNECTION, never with the dimension-change remove+register cycle.
            service.getFarPlayerService().onDisconnect(uuid);
            // Region summaries: same connection-scoped cleanup.
            service.getRegionSummaries().removePlayer(uuid);
            // Service gate: the denied-handshake memo, the denial-log latch, and any
            // revocation streak are session-scoped.
            service.getServiceGateState().onDisconnect(uuid);
        }
        // Service-independent: the sidecar fact is recorded at the network level
        // (possibly before any service exists) and must die with the connection.
        ServerReceiverGlue.sweepClientInfo(uuid);
    }

    // ---- Payload handlers (main thread: Sponge's handleCustomPayload hops onto it) ----

    public static void handleHandshake(HandshakeC2SPayload payload, ServerPlayer player) {
        ServerReceiverGlue.handleHandshake(payload, player, requestService,
                reply -> LoaderServices.get().sendToPlayer(player, reply));
    }

    public static void handleBatchRequest(BatchChunkRequestC2SPayload payload, ServerPlayer player) {
        var service = requestService;
        if (service != null) {
            service.handleBatchRequest(player, payload);
        }
    }

    public static void handleClientInfo(ClientInfoC2SPayload payload, ServerPlayer player) {
        ServerReceiverGlue.recordClientInfo(player.getUUID(), payload.dataVersion());
    }

    public static void handleFarPlayerPrefs(FarPlayerPrefsC2SPayload payload, ServerPlayer player) {
        ServerReceiverGlue.onFarPlayerPrefs(requestService, player, payload.body());
    }

    public static void handleRegionSummaryRequest(RegionSummaryRequestC2SPayload payload, ServerPlayer player) {
        var service = requestService;
        if (service != null) {
            service.handleRegionSummaryRequest(player, payload.body());
        }
    }
}
