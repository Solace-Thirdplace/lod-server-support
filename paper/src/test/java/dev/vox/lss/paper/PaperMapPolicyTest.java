package dev.vox.lss.paper;

import dev.vox.lss.common.HandshakeGate;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.ServiceGateState;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The server map-ingestion request on Paper/Folia (map-policy plan A3/A5 "server
 * glue" — the xplat twin rides the Fabric gametests): the handshake rung through the
 * plugin's static core (real vs quiet refusal per dialect, the capability hand-off to
 * the sender, the Paper/Folia ladder-race row — the refusal reply goes out INLINE
 * while a v20 reply is deferred to the pump), the production gate's refusal hook, and
 * the service's live half: the arm sweep (CURRENT-without-bit only, legacy kept,
 * idempotent, chat once), the memo reason mask (an armed policy with the permission
 * gate OFF replays nobody across N sweeps), the disarm replay, revoke-before-repush on
 * the arming reload, the 200-tick cadence, and the dimension-change push carrying the
 * session's capabilities.
 */
class PaperMapPolicyTest {

    private static final int VOXEL = LSSConstants.CAPABILITY_VOXEL_COLUMNS;
    private static final int BIT = LSSConstants.CAPABILITY_MAP_POLICY;

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ---- service rig (the PaperServiceGateSweepTest shape) ----

    private Map<UUID, PaperPlayerRequestState> players;
    private PaperChunkDiskReader diskReader;
    private MinecraftServer server;
    private PlayerList playerList;
    private PaperConfig config;
    private PaperRequestProcessingService service;
    /** {uuid, enabled, capabilities, denyMapIngestion-at-send} in send order. */
    private final List<Object[]> sent = new ArrayList<>();
    private final List<ServiceGateState.DeniedHandshake> replays = new ArrayList<>();
    private final List<UUID> notices = new ArrayList<>();

    @BeforeEach
    void buildRig() {
        config = new MutablePaperSettings();
        MutablePaperSettings.normalize(config);
        players = new ConcurrentHashMap<>();
        diskReader = new PaperChunkDiskReader(1, false);
        var processor = new PaperRequestProcessingServiceTest.RecordingProcessor(players, diskReader);
        var genService = new PaperRequestProcessingServiceTest.RecordingGenService(config);
        server = mock(MinecraftServer.class);
        playerList = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(playerList);
        var tracker = new DirtyColumnTracker();
        var broadcaster = new PaperRequestProcessingServiceTest.RecordingBroadcaster(
                server, players, tracker, processor);
        service = new PaperRequestProcessingService(server, config,
                new PaperRequestProcessingService.Wiring(
                        players, diskReader, genService, processor, tracker, broadcaster));
        service.setLoadedColumnProbe((level, cx, cz) -> null);
        service.setSessionConfigSender((p, cfg, enabled, caps) ->
                sent.add(new Object[]{p.getUUID(), enabled, caps, cfg.requestNoMapIngestion()}));
        service.setHandshakeReplayer((p, remembered) -> replays.add(remembered));
        service.setMapPolicyNoticeSender((p, text) -> notices.add(p.getUUID()));
    }

    @AfterEach
    void teardownReader() {
        diskReader.shutdown();
    }

    private static ServerLevel level(ResourceKey<Level> key) {
        var level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(key);
        return level;
    }

    private static ServerPlayer playerIn(UUID uuid, ServerLevel level) {
        var p = mock(ServerPlayer.class);
        when(p.getUUID()).thenReturn(uuid);
        when(p.level()).thenReturn(level);
        when(p.chunkPosition()).thenReturn(new ChunkPos(0, 0));
        when(p.getName()).thenReturn(Component.literal("p-" + uuid.toString().substring(0, 8)));
        return p;
    }

    private ServerPlayer register(UUID uuid, HandshakeGate.WireDialect dialect, int caps) {
        var p = playerIn(uuid, level(Level.OVERWORLD));
        service.getDialectTracker().onHandshake(uuid, dialect);
        service.registerPlayer(p, caps);
        when(playerList.getPlayer(uuid)).thenReturn(p);
        return p;
    }

    private void arm(boolean armed) {
        MutablePaperSettings.set(config, "privacy.request_no_map_ingestion", armed);
    }

    // ---- the arm sweep ----

    @Test
    void theArmSweepRevokesOnlyCurrentSessionsWithoutTheBit() {
        var old = UUID.randomUUID();
        var capable = UUID.randomUUID();
        var legacy = UUID.randomUUID();
        register(old, HandshakeGate.WireDialect.CURRENT, VOXEL);
        register(capable, HandshakeGate.WireDialect.CURRENT, VOXEL | BIT);
        register(legacy, HandshakeGate.WireDialect.V19, VOXEL);
        service.getFarPlayerService().subscribeViewer(old);

        assertEquals(0, service.runMapPolicySweep(), "disarmed: inert");
        assertEquals(List.of(), sent);

        arm(true);
        assertEquals(1, service.runMapPolicySweep());
        assertEquals(1, sent.size());
        assertEquals(old, sent.getFirst()[0]);
        assertEquals(false, sent.getFirst()[1], "the revocation push advertises disabled");
        assertEquals(VOXEL, sent.getFirst()[2], "…to a session without the bit (no flags field)");
        assertNull(players.get(old), "the unregistration composite ran");
        assertEquals(0, service.getFarPlayerService().subscriberCount(), "the viewer was shed");
        assertNotNull(players.get(capable), "a capable session keeps serving (its repush carries the flag)");
        assertNotNull(players.get(legacy),
                "legacy dialects are NEVER revoked mid-session (they have no map ingestion)");
        var memo = service.getServiceGateState().peekDenied(old);
        assertTrue(memo != null && memo.has(ServiceGateState.REASON_MAP_POLICY)
                        && !memo.has(ServiceGateState.REASON_PERMISSION),
                "the memo deposit carries reason MAP_POLICY: " + memo);
        assertEquals(1, service.getServiceGateState().mapPolicyRefusedTotal());
        assertEquals(0, service.getServiceGateState().permissionDeniedTotal());
        assertEquals(List.of(old), notices, "the chat line, once");

        assertEquals(0, service.runMapPolicySweep(), "idempotent: the revoked session is gone");
        assertEquals(1, sent.size());
        assertEquals(List.of(old), notices);
    }

    @Test
    void anArmedPolicyWithTheGateOffReplaysNobodyAcrossNSweepsAndADisarmHeals() {
        // The replay-loop pin (plan A3.4): without the reason mask the sweep's "gate off ⇒
        // cleared" replayed every refused player every 10 s, each replay re-sending
        // enabled=false. Exactly ONE reply = the handshake's own refusal; the sweeps add none.
        arm(true);
        var uuid = UUID.randomUUID();
        var online = playerIn(uuid, level(Level.OVERWORLD));
        when(playerList.getPlayer(uuid)).thenReturn(online);
        service.getServiceGateState().rememberDenied(uuid, "steve", 19, VOXEL,
                ServiceGateState.REASON_MAP_POLICY);
        for (int i = 0; i < 5; i++) service.runServiceGateSweeps();
        assertEquals(List.of(), replays, "an armed policy keeps a policy refusal remembered, not replayed");
        assertTrue(service.getServiceGateState().isDenied(uuid));

        arm(false);
        service.runServiceGateSweeps();
        assertEquals(1, replays.size(), "a disarm clears the reason: the stored handshake replays");
        assertEquals(19, replays.getFirst().protocolVersion(), "in the client's own dialect");
        service.runServiceGateSweeps();
        assertEquals(1, replays.size(), "once");
    }

    @Test
    void aMemoHoldingBothReasonsWaitsForBoth() {
        MutablePaperSettings.set(config, "service.require_permission", false);
        arm(true);
        var uuid = UUID.randomUUID();
        var online = playerIn(uuid, level(Level.OVERWORLD)); // built BEFORE the stub (no nested stubbing)
        when(playerList.getPlayer(uuid)).thenReturn(online);
        service.getServiceGateState().rememberDenied(uuid, "steve", 20, VOXEL);
        service.getServiceGateState().rememberDenied(uuid, "steve", 20, VOXEL,
                ServiceGateState.REASON_MAP_POLICY);
        service.runServiceGateSweeps();
        assertEquals(List.of(), replays, "the permission reason cleared (gate off) but the policy holds");
        arm(false);
        service.runServiceGateSweeps();
        assertEquals(1, replays.size(), "every reason cleared: replay");
    }

    @Test
    void aCapableStoredHandshakeClearsThePolicyReason() {
        arm(true);
        var uuid = UUID.randomUUID();
        var online = playerIn(uuid, level(Level.OVERWORLD)); // built BEFORE the stub (no nested stubbing)
        when(playerList.getPlayer(uuid)).thenReturn(online);
        service.getServiceGateState().rememberDenied(uuid, "steve", LSSConstants.PROTOCOL_VERSION,
                VOXEL | BIT, ServiceGateState.REASON_MAP_POLICY);
        service.runServiceGateSweeps();
        assertEquals(1, replays.size(),
                "a stored CURRENT handshake WITH the bit can honor the request: no longer refused");
    }

    @Test
    void theNoticeIsOncePerConnection() {
        var uuid = UUID.randomUUID();
        var p = playerIn(uuid, level(Level.OVERWORLD));
        when(playerList.getPlayer(uuid)).thenReturn(p);
        service.noticeMapPolicyRefusal(p);
        service.noticeMapPolicyRefusal(p);
        assertEquals(List.of(uuid), notices);
        service.getServiceGateState().onDisconnect(uuid);
        service.noticeMapPolicyRefusal(p);
        assertEquals(List.of(uuid, uuid), notices, "a new connection gets the line again");
    }

    /** Review fold A7: the handshake's refusal marshals the line onto the pump; a player who
     *  quits before that task drains (the quit's own cleanup already ran) is skipped BEFORE
     *  the claim — a claim then would leak a latch entry for a gone connection. */
    @Test
    void aRefusalThenAQuitWithinOnePumpTickLeaksNothing() {
        var uuid = UUID.randomUUID();
        var p = playerIn(uuid, level(Level.OVERWORLD));
        when(playerList.getPlayer(uuid)).thenReturn(p);
        service.enqueueRuntimeTask(() -> service.noticeMapPolicyRefusal(p)); // the refusal hook
        when(playerList.getPlayer(uuid)).thenReturn(null); // the quit
        service.getServiceGateState().onDisconnect(uuid);
        service.enqueueRemove(uuid);
        service.tick();
        assertEquals(List.of(), notices, "nobody to tell");
        assertFalse(service.getServiceGateState().hasMapPolicyNotice(uuid), "and no latch entry leaks");
    }

    /** Review fold A7: a rejoin (a NEW player instance for the same uuid) that beats the old
     *  connection's marshaled line gets its own line; the stale task is skipped. */
    @Test
    void aRejoinBeforeTheDrainGetsItsOwnNotice() {
        var uuid = UUID.randomUUID();
        var first = playerIn(uuid, level(Level.OVERWORLD));
        var second = playerIn(uuid, level(Level.OVERWORLD));
        var told = new ArrayList<ServerPlayer>();
        service.setMapPolicyNoticeSender((player, text) -> told.add(player));
        when(playerList.getPlayer(uuid)).thenReturn(first);
        service.enqueueRuntimeTask(() -> service.noticeMapPolicyRefusal(first));
        when(playerList.getPlayer(uuid)).thenReturn(second); // quit + rejoin, nothing drained yet
        service.enqueueRuntimeTask(() -> service.noticeMapPolicyRefusal(second));
        service.tick();
        assertEquals(1, told.size(), "one line");
        assertSame(second, told.getFirst(), "…to the LIVE connection, not the departed one");
        service.noticeMapPolicyRefusal(second);
        assertEquals(1, told.size(), "once per connection");
    }

    // ---- live changes through the reload ----

    @Test
    void theArmingReloadRevokesBeforeTheRepushAndReportsItsOwnFeedback() throws Exception {
        var old = UUID.randomUUID();
        var capable = UUID.randomUUID();
        var legacy = UUID.randomUUID();
        register(old, HandshakeGate.WireDialect.CURRENT, VOXEL);
        register(capable, HandshakeGate.WireDialect.CURRENT, VOXEL | BIT);
        register(legacy, HandshakeGate.WireDialect.V18, VOXEL);
        var original = config.snapshot();
        arm(true);
        var result = service.reconcileSettings(original, config.snapshot(), 1);
        service.tick();
        result.get(1, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(2, sent.size(), "one revocation + one flags repush: " + sent.size());
        assertEquals(old, sent.get(0)[0], "REVOKE FIRST");
        assertEquals(false, sent.get(0)[1]);
        assertEquals(capable, sent.get(1)[0], "then the repush — only to the capable session");
        assertEquals(true, sent.get(1)[1]);
        assertEquals(VOXEL | BIT, sent.get(1)[2]);
        assertEquals(true, sent.get(1)[3], "the repush reads the armed policy at send time");
        assertEquals(1, service.settingsFeedback().mapPolicyRevoked());
        assertTrue(service.settingsFeedback().mapPolicyArmed(),
                "the feedback carries the ADOPTED policy (review fold A9) — the reply never reads the live config");
        assertEquals(0, service.settingsFeedback().legacyReconnects(),
                "a policy-only change is not a distance/generation reconnect");
        assertNotNull(players.get(legacy), "the legacy session keeps serving until rejoin");

        // Disarm: capable sessions get flags 0; the revoked player is re-offered at once.
        var armed = config.snapshot();
        arm(false);
        sent.clear();
        var off = service.reconcileSettings(armed, config.snapshot(), 2);
        service.tick();
        off.get(1, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(1, sent.size());
        assertEquals(capable, sent.getFirst()[0]);
        assertEquals(false, sent.getFirst()[3], "flags 0");
        assertEquals(1, replays.size(), "the refused player heals through the replay");
        assertEquals(0, service.settingsFeedback().mapPolicyRevoked());
        assertFalse(service.settingsFeedback().mapPolicyArmed());

        // A later reload that does not touch the policy reports no map-policy line (-1), never
        // the previous revision's count again.
        var disarmed = config.snapshot();
        MutablePaperSettings.set(config, "lod.distance.default_chunks", 256);
        var other = service.reconcileSettings(disarmed, config.snapshot(), 3);
        service.tick();
        other.get(1, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(-1, service.settingsFeedback().mapPolicyRevoked());
    }

    @Test
    void theCadenceEnforcesThePolicyForARegistrationThatRacedTheArm() {
        // Folia race: a region thread decided the handshake on the pre-arm config and its
        // Register drained after the reload's one-shot sweep — the cadence closes it.
        arm(true);
        var raced = UUID.randomUUID();
        register(raced, HandshakeGate.WireDialect.CURRENT, VOXEL);
        // Review fold A9: the cadence runs only once the reconcile ADOPTED the arm — the
        // reload owns the transition (and its feedback's revoked count).
        for (int i = 0; i < PaperRequestProcessingService.PERMISSION_RECHECK_TICKS; i++) {
            service.tick();
        }
        assertNotNull(players.get(raced), "an arm the reconcile has not adopted: the cadence waits");
        service.adoptMapPolicyForTest(true);
        for (int i = 0; i < PaperRequestProcessingService.PERMISSION_RECHECK_TICKS - 1; i++) {
            service.tick();
        }
        assertNotNull(players.get(raced), "199 ticks: not yet");
        service.tick();
        assertNull(players.get(raced), "the 200th tick enforces");
        assertEquals(List.of(raced), notices);
    }

    /** Review fold A9: the sweep needs an EXPLICIT CURRENT mark — an unmarked session (the
     *  tracker's dialectOf defaults to CURRENT) is not revoked as if it were one. */
    @Test
    void theArmSweepSkipsASessionWithoutAnExplicitCurrentMark() {
        var uuid = UUID.randomUUID();
        var p = playerIn(uuid, level(Level.OVERWORLD));
        service.registerPlayer(p, VOXEL);
        when(playerList.getPlayer(uuid)).thenReturn(p);
        arm(true);
        assertEquals(0, service.runMapPolicySweep());
        assertNotNull(players.get(uuid));
    }

    /** Review fold A9: a policy-only change re-pushes FLAGS only — a session in an off world
     *  (distance 0) is not re-registered and its far-player roster is not re-sent. */
    @Test
    void aPolicyOnlyReloadRepushesFlagsWithoutReRegisteringAnOffWorldSession() throws Exception {
        var uuid = UUID.randomUUID();
        var p = register(uuid, HandshakeGate.WireDialect.CURRENT, VOXEL | BIT);
        var nether = level(Level.NETHER); // built BEFORE the stub (no nested stubbing)
        when(p.level()).thenReturn(nether);
        var original = config.snapshot();
        MutablePaperSettings.dimension(config, "minecraft:the_nether", 0);
        var off = config.snapshot();
        var first = service.reconcileSettings(original, off, 1); // a distance change: the full repush
        service.tick();
        first.get(1, java.util.concurrent.TimeUnit.SECONDS);
        var state = players.get(uuid);
        assertNotNull(state);
        sent.clear();
        arm(true);
        var policy = service.reconcileSettings(off, config.snapshot(), 2);
        service.tick();
        policy.get(1, java.util.concurrent.TimeUnit.SECONDS);
        assertSame(state, players.get(uuid), "the off-world session is NOT removed and re-registered");
        assertEquals(1, sent.size());
        assertEquals(false, sent.getFirst()[1], "the push still says the world is off");
        assertEquals(true, sent.getFirst()[3], "…and carries the new flags");
    }

    @Test
    void theDimensionChangePushCarriesTheSessionCapabilities() {
        // Stock settings: overworld 512 vs nether 64 — every Nether trip pushes.
        arm(true);
        MutablePaperSettings.set(config, "lod.distance.default_chunks", 512);
        MutablePaperSettings.dimension(config, "minecraft:the_nether", 64);
        var uuid = UUID.randomUUID();
        var p = register(uuid, HandshakeGate.WireDialect.CURRENT, VOXEL | BIT);
        var nether = level(Level.NETHER);
        when(p.level()).thenReturn(nether);
        service.tick();
        assertEquals(1, sent.size());
        assertEquals(VOXEL | BIT, sent.getFirst()[2], "the session's capabilities ride the push");
        assertEquals(true, sent.getFirst()[3], "…with the policy read at send time");
    }

    // ---- the handshake core (the plugin's static glue) ----

    private record Reply(HandshakeGate.WireDialect dialect, boolean enabled, int capabilities) {}

    /** The replies' keepDialectMark flags, in send order (review fold A8). */
    private final List<Boolean> keptMarks = new ArrayList<>();

    private final List<Reply> replies = new ArrayList<>();
    private final List<Integer> registrations = new ArrayList<>();
    private final List<Runnable> deferred = new ArrayList<>();
    private final List<int[]> refusals = new ArrayList<>();

    private final LSSPaperPlugin.SessionConfigSender sender =
            (dialect, enabled, lod, syncCap, genCap, gen, caps, keepMark) -> {
                replies.add(new Reply(dialect, enabled, caps));
                keptMarks.add(keepMark);
            };
    private final LSSPaperPlugin.HandshakeRegistrar registrar = (caps, dialect, reply) -> {
        registrations.add(caps);
        deferred.add(reply);
    };

    private final dev.vox.lss.common.PlayerServiceGate gate = new dev.vox.lss.common.PlayerServiceGate() {
        @Override public boolean hasPermission(String node) { return true; }
        @Override public boolean claimDenialLog() { return false; }
        @Override public void onServiceDenied(int protocolVersion, int capabilities) { }
        @Override public boolean onMapPolicyRefused(int protocolVersion, int capabilities) {
            refusals.add(new int[]{protocolVersion, capabilities});
            return refusals.size() == 1;
        }
    };

    private static byte[] handshakeFrame(int version, int caps) {
        var b = new FriendlyByteBuf(Unpooled.buffer());
        b.writeVarInt(version);
        b.writeVarInt(caps);
        byte[] out = new byte[b.readableBytes()];
        b.readBytes(out);
        b.release();
        return out;
    }

    private void handshake(int version, int caps) {
        LSSPaperPlugin.handleHandshake(handshakeFrame(version, caps), "steve", config, true,
                dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, 0, gate, sender, registrar);
    }

    private static HandshakeGate.WireDialect dialectOf(int version) {
        return switch (version) {
            case LSSConstants.V19_COMPAT_PROTOCOL_VERSION -> HandshakeGate.WireDialect.V19;
            case LSSConstants.V18_COMPAT_PROTOCOL_VERSION -> HandshakeGate.WireDialect.V18;
            case LSSConstants.V16_COMPAT_PROTOCOL_VERSION -> HandshakeGate.WireDialect.V16;
            default -> HandshakeGate.WireDialect.CURRENT;
        };
    }

    @Test
    void aRealRefusalRepliesDisabledInTheClientsOwnDialectAndRunsTheHook() {
        arm(true);
        int[] versions = {LSSConstants.PROTOCOL_VERSION, LSSConstants.V19_COMPAT_PROTOCOL_VERSION,
                LSSConstants.V18_COMPAT_PROTOCOL_VERSION, LSSConstants.V16_COMPAT_PROTOCOL_VERSION};
        for (int version : versions) {
            replies.clear();
            refusals.clear();
            keptMarks.clear();
            handshake(version, VOXEL);
            assertEquals(List.of(new Reply(dialectOf(version), false, VOXEL)), replies,
                    "v" + version + ": enabled=false in its OWN dialect, inline, never silence");
            assertEquals(List.of(false), keptMarks, "a REAL refusal sheds a stale cross-dialect mark");
            assertEquals(1, refusals.size(), "v" + version + ": the REAL refusal hook runs");
            assertEquals(version, refusals.getFirst()[0]);
        }
        assertEquals(List.of(), registrations, "nobody registers");
    }

    @Test
    void aQuietRefusalOfARacedLegacyRungIsTheReplyOnly() {
        arm(true);
        for (int version : new int[]{LSSConstants.V19_COMPAT_PROTOCOL_VERSION,
                LSSConstants.V18_COMPAT_PROTOCOL_VERSION, LSSConstants.V16_COMPAT_PROTOCOL_VERSION}) {
            replies.clear();
            keptMarks.clear();
            handshake(version, VOXEL | BIT);
            assertEquals(List.of(new Reply(dialectOf(version), false, VOXEL | BIT)), replies);
            assertEquals(List.of(true), keptMarks,
                    "a QUIET refusal must not shed the live CURRENT mark (review fold A8)");
        }
        assertEquals(List.of(), refusals, "a 0.19 client's raced rung: no memo, chat, counter or unregister");
        assertEquals(List.of(), registrations);
    }

    @Test
    void aCapableCurrentHandshakeRegistersAndHandsItsCapabilitiesToTheDeferredReply() {
        arm(true);
        handshake(LSSConstants.PROTOCOL_VERSION, VOXEL | BIT);
        assertEquals(List.of(VOXEL | BIT), registrations);
        assertEquals(List.of(), replies, "the v20 reply is DEFERRED to the pump's Register drain");
        deferred.forEach(Runnable::run);
        assertEquals(List.of(new Reply(HandshakeGate.WireDialect.CURRENT, true, VOXEL | BIT)), replies,
                "the sender gets the capabilities and appends the flags at send time");
        arm(false);
        replies.clear();
        registrations.clear();
        deferred.clear();
        handshake(LSSConstants.PROTOCOL_VERSION, VOXEL);
        assertEquals(List.of(VOXEL), registrations, "disarmed: an old client registers as before");
    }

    @Test
    void thePaperLadderRaceRefusesTheRacedRungInlineBeforeTheDeferredV20Reply() {
        // A 0.19 client's slow v20 echo: it announced 20 (registers; reply deferred to the
        // pump) then its ladder raced a 19 rung (with the bit) before the pump drained. On
        // Paper the quiet refusal reply goes out INLINE, so the client sees 19/disabled
        // first and the v20/enabled reply after — its downgrade guard and the slow-echo
        // upgrade path heal that order; the server never unregisters or remembers anything.
        arm(true);
        handshake(LSSConstants.PROTOCOL_VERSION, VOXEL | BIT);
        handshake(LSSConstants.V19_COMPAT_PROTOCOL_VERSION, VOXEL | BIT);
        assertEquals(List.of(new Reply(HandshakeGate.WireDialect.V19, false, VOXEL | BIT)), replies,
                "the raced rung's refusal is inline");
        deferred.forEach(Runnable::run);
        assertEquals(new Reply(HandshakeGate.WireDialect.CURRENT, true, VOXEL | BIT), replies.get(1),
                "then the deferred v20 reply");
        assertEquals(List.of(VOXEL | BIT), registrations, "one registration, never undone");
        assertEquals(List.of(), refusals, "no real-refusal side effects");
    }

    @Test
    void theProductionGateDepositsNotifiesAndUnregisters() {
        var uuid = UUID.randomUUID();
        var bukkit = mock(org.bukkit.entity.Player.class);
        var state = new ServiceGateState();
        int[] composite = {0};
        int[] notice = {0};
        var production = LSSPaperPlugin.serviceGateFor(bukkit, uuid, "steve", state,
                () -> composite[0]++, () -> notice[0]++);
        assertTrue(production.onMapPolicyRefused(20, VOXEL), "the first refusal is a transition");
        assertFalse(production.onMapPolicyRefused(19, VOXEL), "a re-refusal is not");
        var memo = state.peekDenied(uuid);
        assertTrue(memo.has(ServiceGateState.REASON_MAP_POLICY) && !memo.has(ServiceGateState.REASON_PERMISSION));
        assertEquals(20, memo.protocolVersion(),
                "the raced 19 rung never replaces the stored CURRENT handshake (review fold A11)");
        assertEquals(1, state.mapPolicyRefusedTotal());
        assertEquals(2, composite[0], "every refusal marshals the composite (pump no-op when unregistered)");
        assertEquals(2, notice[0], "the notice is marshaled; the service's latch makes it once");

        // Review fold A6: a grant-sweep replay sends the line on a MAP_POLICY transition — a
        // player permission-denied first, then granted while the policy is armed, has never
        // seen it. Re-refusals on later replays stay silent (and the latch makes it once anyway).
        var replayState = new ServiceGateState();
        var alex = UUID.randomUUID();
        replayState.rememberDenied(alex, "alex", 20, VOXEL); // the permission denial
        replayState.takeDenied(alex); // the grant sweep takes the entry to replay it
        int[] replayNotice = {0};
        var replay = LSSPaperPlugin.serviceGateFor(bukkit, alex, "alex", replayState, null,
                () -> replayNotice[0]++, true);
        assertTrue(replay.onMapPolicyRefused(20, VOXEL), "the replay lands on the armed policy: a transition");
        assertEquals(1, replayNotice[0], "exactly one notice after the grant");
        for (int i = 0; i < 5; i++) assertFalse(replay.onMapPolicyRefused(20, VOXEL));
        assertEquals(1, replayNotice[0], "still one after N replays");
    }

    // ---- review fold A10: a deferred Register that drains after an arm ----

    private ServerPlayer onlinePlayer(UUID uuid) {
        var p = playerIn(uuid, level(Level.OVERWORLD));
        when(playerList.getPlayer(uuid)).thenReturn(p);
        return p;
    }

    /** Drives the plugin's static core into the REAL service mailbox: the registrar is the
     *  production shape (dialect flip before the register, reply after, the dialect carried). */
    private void handshakeInto(ServerPlayer p, int version, int caps) {
        LSSPaperPlugin.handleHandshake(handshakeFrame(version, caps), "steve", p, config, true,
                dev.vox.lss.common.compat.ViaProbe.NO_SIGNAL, 0, gate, sender,
                (c, dialect, reply) -> service.enqueueRegister(p, c,
                        () -> service.getDialectTracker().onHandshake(p.getUUID(), dialect), reply, dialect));
    }

    @Test
    void aRegisterDecidedBeforeTheArmIsRefusedAtTheDrain() {
        var uuid = UUID.randomUUID();
        var p = onlinePlayer(uuid);
        handshakeInto(p, LSSConstants.PROTOCOL_VERSION, VOXEL); // disarmed: REGISTER, reply deferred
        assertEquals(List.of(), replies);
        arm(true); // the reload lands before the pump drains the Register
        service.tick();
        assertNull(players.get(uuid), "refused instead of registered");
        assertEquals(List.of(new Reply(HandshakeGate.WireDialect.CURRENT, false, VOXEL)), replies,
                "the deferred reply re-reads the policy at send time: enabled=false, CURRENT shape");
        assertEquals(List.of(false), keptMarks, "a real refusal sheds like the handshake would");
        var memo = service.getServiceGateState().peekDenied(uuid);
        assertTrue(memo != null && memo.has(ServiceGateState.REASON_MAP_POLICY)
                && memo.protocolVersion() == LSSConstants.PROTOCOL_VERSION, "memo MAP_POLICY: " + memo);
        assertEquals(1, service.getServiceGateState().mapPolicyRefusedTotal());
        assertEquals(List.of(uuid), notices, "the chat line, as the armed handshake would have sent");
        assertFalse(service.getDialectTracker().isCurrent(uuid), "the dialect flip never ran");
    }

    @Test
    void aQuietRungRegisterDecidedBeforeTheArmIsTheReplyOnlyAtTheDrain() {
        var uuid = UUID.randomUUID();
        var p = onlinePlayer(uuid);
        handshakeInto(p, LSSConstants.V19_COMPAT_PROTOCOL_VERSION, VOXEL | BIT);
        arm(true);
        service.tick();
        assertNull(players.get(uuid));
        assertEquals(List.of(new Reply(HandshakeGate.WireDialect.V19, false, VOXEL | BIT)), replies);
        assertEquals(List.of(true), keptMarks, "a quiet refusal keeps the live dialect mark");
        assertFalse(service.getServiceGateState().isDenied(uuid), "no memo");
        assertEquals(List.of(), notices, "no chat line");
    }

    @Test
    void aCapableRegisterStillRegistersAfterTheArm() {
        var uuid = UUID.randomUUID();
        var p = onlinePlayer(uuid);
        handshakeInto(p, LSSConstants.PROTOCOL_VERSION, VOXEL | BIT);
        arm(true);
        service.tick();
        assertNotNull(players.get(uuid));
        assertEquals(List.of(new Reply(HandshakeGate.WireDialect.CURRENT, true, VOXEL | BIT)), replies);
        assertTrue(service.getDialectTracker().isCurrent(uuid));
    }
}
