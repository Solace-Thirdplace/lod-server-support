package dev.vox.lss.test;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.server.LSSServerNetworking;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/** Own batch: real asynchronous disk/reload transactions must not retune other tests. */
public class SettingsReloadGameTests {
    @GameTest(environment = "lss:settings_reload", structure = "fabric-gametest-api-v1:empty", maxTicks = 2400)
    public void reloadAppliesWorldPolicyRejectsBadFilesAndRestores(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var config = LSSServerConfig.CONFIG;
        var store = config.settingsHandle().store();
        var original = config.snapshot();
        var lines = new CopyOnWriteArrayList<String>();
        var source = server.createCommandSourceStack().withSource(new CommandSource() {
            public void sendSystemMessage(Component message) { lines.add(message.getString()); }
            public boolean acceptsSuccess() { return true; }
            public boolean acceptsFailure() { return true; }
            public boolean shouldInformAdmins() { return false; }
        });
        String world = helper.getLevel().dimension().identifier().toString();
        var work = CompletableFuture.runAsync(() -> {
            byte[] backup = null;
            Throwable failure = null;
            try {
                backup = Files.readAllBytes(store.path());
                var document = store.read();
                store.saveDraft(document.hash(), Map.of(
                        "lod.distance.default_chunks", 96,
                        "lod.distance.by_dimension", Map.of(world, 7),
                        "updates.dirty_broadcast_interval_ticks", 0,
                        "storage.disk.reader_threads", original.storage().disk().readerThreads() == 1 ? 2 : 1));
                check(config.snapshot().equals(original), "Writing YAML must leave active settings unchanged");
                reload(server, source, lines, false);
                onServer(server, () -> {
                    check(config.lodDistanceChunks() == 96 && config.lodDistanceForWorld(world) == 7,
                            "Reload must apply the correlated default and dimension rule");
                    check(config.dirtyBroadcastIntervalTicks() == 0, "Zero must disable dirty broadcasting");
                    check(config.snapshot().storage().disk().readerThreads() == original.storage().disk().readerThreads(),
                            "Pending reader-pool size must not become active");
                    check(lines.stream().anyMatch(line -> line.contains("Restart required:")), "Reply must report pending reader pool");
                    assertHandshakeAndRange(helper, world);
                    var root = server.getCommands().getDispatcher().getRoot().getChild("lsslod");
                    check(root.getChild("set") == null && root.getChild("preset") == null,
                            "Removed mutation commands must not be registered");
                });
                // 0 = no LOD service in this dimension, through the real reload path.
                store.saveDraft(store.read().hash(), Map.of("lod.distance.by_dimension", Map.of(world, 0)));
                reload(server, source, lines, false);
                onServer(server, () -> {
                    check(config.lodDistanceForWorld(world) == 0, "Reload must apply a zero (off) dimension");
                    assertOffWorldHandshake(helper);
                });
                long revision = config.settingsHandle().state().revision();
                byte[] bad = "config_version: 1\ngeneration: [broken\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                Files.write(store.path(), bad);
                reload(server, source, lines, true);
                check(config.settingsHandle().state().revision() == revision, "Rejected reload must not publish a revision");
                check(config.lodDistanceForWorld(world) == 0, "Rejected reload must retain active policy (the off dimension)");
                check(java.util.Arrays.equals(bad, Files.readAllBytes(store.path())), "Failed reload must not rewrite the bad file");
            } catch (Throwable error) {
                failure = error;
            } finally {
                if (backup != null) try {
                    Files.write(store.path(), backup);
                    reload(server, source, lines, false);
                    check(config.snapshot().equals(original), "Cleanup must restore the original effective settings");
                } catch (Throwable cleanup) {
                    if (failure == null) failure = cleanup; else failure.addSuppressed(cleanup);
                }
            }
            if (failure != null) throw new java.util.concurrent.CompletionException(failure);
        });
        helper.succeedWhen(() -> {
            if (!work.isDone()) {
                // GameTest ticks can run hundreds of times faster than real IO.
                try { Thread.sleep(25); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                helper.assertTrue(false, "Waiting for disk preparation and owner adoption");
            }
            try { work.join(); }
            catch (java.util.concurrent.CompletionException failure) {
                helper.assertTrue(false, "Reload transaction failed: " + failure.getCause());
            }
        });
    }

    @SuppressWarnings("removal")
    private static void assertHandshakeAndRange(GameTestHelper helper, String world) {
        var server = helper.getLevel().getServer();
        var service = LSSServerNetworking.getRequestService();
        var player = helper.makeMockServerPlayerInLevel();
        try {
            var replies = new ArrayList<dev.vox.lss.networking.payloads.SessionConfigS2CPayload>();
            var handshake = new dev.vox.lss.networking.payloads.HandshakeC2SPayload(
                    LSSConstants.PROTOCOL_VERSION, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
            LSSServerNetworking.handleHandshake(handshake, player, service, replies::add);
            check(replies.size() == 1 && replies.getFirst().lodDistanceChunks() == 7,
                    "Handshake must advertise the reloaded dimension distance for " + world);
            var state = service.getPlayers().get(player.getUUID());
            long before = state.getTotalRequestsReceived();
            int cx = player.getBlockX() >> 4, cz = player.getBlockZ() >> 4;
            int radius = 7 + LSSConstants.LOD_DISTANCE_BUFFER;
            service.handleBatchRequest(player, new dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload(
                    new long[]{PositionUtil.packPosition(cx + radius, cz), PositionUtil.packPosition(cx + radius + 1, cz)},
                    new long[]{0L, 0L}, 2));
            check(state.getTotalRequestsReceived() == before + 1, "Only the reloaded range boundary must be accepted");
        } finally {
            service.removePlayer(player.getUUID());
            service.getDialectTracker().onDisconnect(player.getUUID());
            server.getPlayerList().remove(player);
        }
    }

    /** A world at distance 0: a CURRENT session is told LODs are off there (and stays
     *  registered), nothing is accepted, and a legacy (v18) session — which no later push
     *  reaches — is told the server's largest distance so it stays on for served worlds. */
    @SuppressWarnings("removal")
    private static void assertOffWorldHandshake(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var service = LSSServerNetworking.getRequestService();
        var player = helper.makeMockServerPlayerInLevel();
        try {
            var replies = new ArrayList<dev.vox.lss.networking.payloads.SessionConfigS2CPayload>();
            LSSServerNetworking.handleHandshake(new dev.vox.lss.networking.payloads.HandshakeC2SPayload(
                    LSSConstants.PROTOCOL_VERSION, LSSConstants.CAPABILITY_VOXEL_COLUMNS), player, service, replies::add);
            check(replies.size() == 1 && !replies.getFirst().enabled() && replies.getFirst().lodDistanceChunks() == 0,
                    "An off world must be advertised as disabled with distance 0");
            var state = service.getPlayers().get(player.getUUID());
            check(state != null, "An off world keeps the player registered");
            long before = state.getTotalRequestsReceived();
            int cx = player.getBlockX() >> 4, cz = player.getBlockZ() >> 4;
            service.handleBatchRequest(player, new dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload(
                    new long[]{PositionUtil.packPosition(cx, cz)}, new long[]{0L}, 1));
            check(state.getTotalRequestsReceived() == before, "No ask is accepted in an off world, not even the player's own chunk");
            var legacy = new ArrayList<dev.vox.lss.networking.payloads.SessionConfigS2CPayload>();
            LSSServerNetworking.handleHandshake(new dev.vox.lss.networking.payloads.HandshakeC2SPayload(
                    LSSConstants.V18_COMPAT_PROTOCOL_VERSION, LSSConstants.CAPABILITY_VOXEL_COLUMNS), player, service, legacy::add);
            check(legacy.size() == 1 && legacy.getFirst().enabled() && legacy.getFirst().lodDistanceChunks() == 96,
                    "A legacy session must stay enabled with the server's largest distance");
        } finally {
            service.removePlayer(player.getUUID());
            service.getDialectTracker().onDisconnect(player.getUUID());
            server.getPlayerList().remove(player);
        }
    }

    private static void reload(MinecraftServer server, net.minecraft.commands.CommandSourceStack source,
                               CopyOnWriteArrayList<String> lines, boolean expectFailure) throws Exception {
        lines.clear();
        onServer(server, () -> server.getCommands().performPrefixedCommand(source, "lsslod reload"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (lines.stream().noneMatch(line -> line.startsWith("Reloaded ") || line.startsWith("Reload failed:"))) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Missing terminal reload receipt: " + lines);
            Thread.sleep(10);
        }
        check(lines.stream().anyMatch(line -> line.startsWith("Reload failed:")) == expectFailure,
                "Unexpected reload outcome: " + lines);
    }

    private static void onServer(MinecraftServer server, Runnable action) throws Exception {
        var done = new CompletableFuture<Void>();
        server.execute(() -> {
            try { action.run(); done.complete(null); }
            catch (Throwable failure) { done.completeExceptionally(failure); }
        });
        done.get(15, TimeUnit.SECONDS);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
