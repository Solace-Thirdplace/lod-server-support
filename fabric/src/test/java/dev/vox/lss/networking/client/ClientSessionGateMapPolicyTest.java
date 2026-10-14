package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import dev.vox.lss.networking.payloads.SoakDialectOverride;
import dev.vox.lss.networking.payloads.V16ClientWire;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The client half of the server map-ingestion request (map-policy plan A4/A5 "client"):
 * the per-connection deny flag is set ONLY on the SessionConfig accept path (a Paper
 * v16 re-attach prompt, a raced 19 echo or a foreign version never touch it), absence
 * means unchanged, JOIN and disconnect reset it, the protocol-bounds clamp keeps every
 * component, a flags-only flip never rebuilds the request manager, and a map-only
 * client (Xaero/JourneyMap without Voxy) has no consumer while denied — no manager at
 * accept, a §2.4 park mid-session, a re-arm on the flags-0 push. Plus the one shared
 * handshake capability mask and the soak v19 lever's bit stripping.
 */
class ClientSessionGateMapPolicyTest {

    private static final int V = LSSConstants.PROTOCOL_VERSION;
    private static final int CAPS = LSSConstants.CAPABILITY_VOXEL_COLUMNS
            | LSSConstants.CAPABILITY_MAP_POLICY;

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private final List<String> events = new ArrayList<>();
    private final List<SessionConfigS2CPayload> built = new ArrayList<>();
    private int handshakes;
    private ClientSessionGate gate;

    @BeforeEach
    void setUp() {
        gate = new ClientSessionGate(new ClientColumnProcessor(), v -> handshakes++, cfg -> {
            events.add("rebuild");
            built.add(cfg);
            return new LodRequestManager() {
                @Override
                public void disconnect() {
                    events.add("disconnect");
                }

                @Override
                public void saveCache() {
                    events.add("save");
                }
            };
        });
    }

    @AfterEach
    void resetWire() {
        V16ClientWire.reset();
    }

    private static SessionConfigS2CPayload v20(boolean deny) {
        return SessionConfigS2CPayload.forSession(V, true, 64, true, 4554, CAPS, deny);
    }

    /** A v20 frame from an old server (or a site that omitted the field): flags absent. */
    private static SessionConfigS2CPayload v20NoFlags() {
        return new SessionConfigS2CPayload(V, true, 64, true, 4554);
    }

    // ---- the flag's lifecycle ----

    @Test
    void theFlagIsSetFromTheAcceptPathAndAbsenceKeepsIt() {
        gate.onJoin(true, false, true, true);
        assertTrue(gate.serverAllowsMapIngestion(), "a new connection starts allowed");
        gate.onSessionConfig(v20(true), true, true);
        assertFalse(gate.serverAllowsMapIngestion());
        gate.onSessionConfig(v20NoFlags(), true, true);
        assertFalse(gate.serverAllowsMapIngestion(),
                "absent = unchanged: a server site that forgets the field fails CLOSED");
        gate.onSessionConfig(v20(false), true, true);
        assertTrue(gate.serverAllowsMapIngestion(), "a flags-0 push re-allows");
        gate.onSessionConfig(v20(false).withLodDistanceChunks(64), true, true);
        assertTrue(gate.serverAllowsMapIngestion());
    }

    @Test
    void anOldServerNeverDeniesAnything() {
        gate.onJoin(true, false, true, true);
        gate.onSessionConfig(v20NoFlags(), true, true);
        assertTrue(gate.serverAllowsMapIngestion(), "an old server has no policy: maps as today");
        assertNotNull(gate.getRequestManager());
    }

    @Test
    void aReattachPromptARacedEchoOrAForeignVersionNeverTouchTheFlag() {
        gate.onJoin(true, false, true, true);
        gate.onSessionConfig(v20(true), true, true);
        assertFalse(gate.serverAllowsMapIngestion());
        // Crafted shapes the codec never produces (legacy layouts carry no flags), so a
        // flag write placed ABOVE the downgrade guard / foreign return would show here.
        var prompt = new SessionConfigS2CPayload(LSSConstants.V16_COMPAT_PROTOCOL_VERSION,
                true, 64, true, 200, 40, true, 0, 0);
        gate.onSessionConfig(prompt, true, true);
        assertFalse(gate.serverAllowsMapIngestion(), "a Paper v16 re-attach prompt is the guard's");
        var raced19 = new SessionConfigS2CPayload(LSSConstants.V19_COMPAT_PROTOCOL_VERSION,
                true, 64, true, 0, 0, false, 0, 0);
        gate.onSessionConfig(raced19, true, true);
        assertFalse(gate.serverAllowsMapIngestion(), "a raced lower echo is the guard's");
        var foreign = new SessionConfigS2CPayload(V + 1, true, 64, true, 0, 0, false, 0, 0);
        gate.onSessionConfig(foreign, true, true);
        assertFalse(gate.serverAllowsMapIngestion(), "a foreign version returns before the accept path");
    }

    @Test
    void joinAndDisconnectResetTheFlag() {
        gate.onJoin(true, false, true, true);
        gate.onSessionConfig(v20(true), true, true);
        gate.onJoin(true, false, true, true); // a play->config reconfiguration fires no disconnect
        assertTrue(gate.serverAllowsMapIngestion(), "JOIN resets");
        gate.onSessionConfig(v20(true), true, true);
        gate.onDisconnect();
        assertTrue(gate.serverAllowsMapIngestion(), "disconnect resets");
    }

    @Test
    void theClampKeepsEveryComponent() {
        gate.onJoin(true, false, true, true);
        var hostile = SessionConfigS2CPayload.forSession(V, true, 1_000_000, true, 5023, CAPS, true);
        gate.onSessionConfig(hostile, true, true);
        var cfg = built.getLast();
        assertEquals(LSSConstants.MAX_LOD_DISTANCE, cfg.lodDistanceChunks(), "still clamped");
        assertEquals(5023, cfg.serverDataVersion(), "the old four-field rebuild dropped this");
        assertEquals(LSSConstants.SESSION_FLAG_DENY_MAP_INGESTION, cfg.sessionFlags());
        assertEquals(cfg, ClientSessionGate.clampToProtocolBounds(cfg), "idempotent on a clamped config");
    }

    // ---- the flags-only fast path ----

    @Test
    void aFlagsOnlyFlipNeverRebuildsTheManager() {
        gate.onJoin(true, false, true, true);
        gate.onSessionConfig(v20(false), true, true);
        var manager = gate.getRequestManager();
        events.clear();
        gate.onSessionConfig(v20(true), true, true);
        assertSame(manager, gate.getRequestManager(), "arm: same manager");
        assertFalse(gate.serverAllowsMapIngestion());
        gate.onSessionConfig(v20(false), true, true);
        assertSame(manager, gate.getRequestManager(), "disarm: same manager");
        assertTrue(gate.serverAllowsMapIngestion());
        assertEquals(List.of(), events, "no teardown, no rebuild on a flags-only flip");
    }

    @Test
    void aShapeChangeOrAnUnchangedResendStillRebuilds() {
        gate.onJoin(true, false, true, true);
        gate.onSessionConfig(v20(false), true, true);
        var first = gate.getRequestManager();
        gate.onSessionConfig(v20(false), true, true);
        assertNotSame(first, gate.getRequestManager(),
                "an identical re-send keeps today's rebuild contract (the fast path is flags-ONLY)");
        var second = gate.getRequestManager();
        gate.onSessionConfig(SessionConfigS2CPayload.forSession(V, true, 128, true, 4554, CAPS, true),
                true, true);
        assertNotSame(second, gate.getRequestManager(), "a distance change rebuilds");
        assertFalse(gate.serverAllowsMapIngestion(), "…and still applies the flag");
    }

    // ---- map-only clients ----

    @Test
    void aMapOnlyClientDeniedAtAcceptBuildsNoManagerAndReArmsOnFlagsZero() {
        gate.onJoin(true, false, true, true);
        gate.onSessionConfig(v20(true), true, true, true);
        assertNull(gate.getRequestManager(),
                "a map-only client under a deny has no consumer: downloading the radius would "
                        + "only drop (and map-only-stamp) every column");
        assertEquals(List.of(), events);
        assertTrue(gate.isServerEnabled(), "the server session itself is enabled");
        assertTrue(gate.hasReceivedSessionConfig());
        gate.onSessionConfig(v20(false), true, true, true);
        assertNotNull(gate.getRequestManager(), "the flags-0 push re-arms");
    }

    @Test
    void aMapOnlyClientParksMidSessionAndReArmsWithItsGovernor() {
        gate.onJoin(true, false, true, true);
        gate.onSessionConfig(v20(false), true, true, true);
        var first = gate.getRequestManager();
        first.governor.tick(1, 0, 0, 0, 0, 1, false, 50, true);
        first.governor.tick(1 + TransferRateGovernor.INTERVAL_MILLIS,
                800 * 1024, 100, 20_000, 20_000, 1, false, 2_000, true);
        first.governor.tick(1 + 2 * TransferRateGovernor.INTERVAL_MILLIS,
                1600 * 1024, 200, 40_000, 40_000, 1, false, 2_000, true);
        assertTrue(first.governor.isEngaged(), "rig engagement");
        long desired = first.governor.getDesiredBytesPerSec();
        FarPlayerClientSupport.tracker().onRoster(new dev.vox.lss.common.farplayers.FarPlayerWire.Roster(
                9, true, List.of(), new int[0]));
        events.clear();

        gate.onSessionConfig(v20(true), true, true, true); // the arm re-push
        assertNull(gate.getRequestManager(), "parked the §2.4 way");
        assertEquals(List.of("disconnect", "save"), events, "the standard teardown (cache saved)");
        assertTrue(gate.isServerEnabled());
        assertEquals(9, FarPlayerClientSupport.tracker().currentEpoch(),
                "the server session stays enabled: far players are NOT ended by a map park");

        gate.onSessionConfig(v20(false), true, true, true); // the disarm re-push
        var second = gate.getRequestManager();
        assertNotNull(second, "re-armed");
        assertTrue(second.governor.isEngaged() && desired == second.governor.getDesiredBytesPerSec(),
                "the parked governor carries into the re-armed manager");
        FarPlayerClientSupport.onSessionEnd();
    }

    /** Review fold A1: a mid-session park withdraws the declared want-set first (exactly
     *  one empty declaration), like reception OFF — otherwise the server keeps serving up
     *  to WANT_SET_BUDGET columns that this client now drops. */
    @Test
    void aMapOnlyParkWithdrawsTheDeclaredWantSetExactlyOnce() {
        gate.onJoin(true, false, true, true);
        gate.onSessionConfig(v20(false), true, true, true);
        var sent = new ArrayList<dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload>();
        gate.getRequestManager().setBatchSenderForTest(sent::add);
        events.clear();

        gate.onSessionConfig(v20(true), true, true, true); // the arm re-push
        assertNull(gate.getRequestManager());
        assertEquals(1, sent.size(), "one withdrawal at park: " + sent);
        assertEquals(0, sent.get(0).count(), "the empty want-set");
        assertEquals(List.of("disconnect", "save"), events, "then the standard teardown");
        gate.reconcileReception(true, true);
        gate.reconcileReception(true, true);
        assertEquals(1, sent.size(), "a delivered withdrawal is never repeated");
    }

    @Test
    void aWithheldParkWithdrawalRetriesEachTickUntilItLandsAndARearmCancelsIt() {
        gate.onJoin(true, false, true, true);
        gate.onSessionConfig(v20(false), true, true, true);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        gate.getRequestManager().setBatchSenderForTest(payload -> {
            attempts.incrementAndGet();
            if (fail.get()) throw new IllegalStateException("temporarily unavailable");
        });
        gate.onSessionConfig(v20(true), true, true, true);
        assertEquals(1, attempts.get());
        gate.reconcileReception(true, true); // the next tick's retry hook
        assertEquals(2, attempts.get(), "retried while parked with reception on");
        fail.set(false);
        gate.reconcileReception(true, true);
        assertEquals(3, attempts.get(), "lands");
        gate.reconcileReception(true, true);
        assertEquals(3, attempts.get(), "and stops");

        // A withheld withdrawal is superseded by the re-arm's fresh declaration.
        gate.onSessionConfig(v20(false), true, true, true);
        gate.getRequestManager().setBatchSenderForTest(payload -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("temporarily unavailable");
        });
        gate.onSessionConfig(v20(true), true, true, true); // parks again: withheld
        assertEquals(4, attempts.get());
        gate.onSessionConfig(v20(false), true, true, true); // re-arm before any retry
        assertNotNull(gate.getRequestManager());
        gate.reconcileReception(true, true);
        gate.reconcileReception(true, true);
        assertEquals(4, attempts.get(), "an obsolete withdrawal never clears the re-armed asks");
    }

    /** Review fold A2: a server disable while a map-only session is PARKED (no manager)
     *  still ends the far-player session — proxies must not freeze mid-air. */
    @Test
    void aServerDisableWhileParkedStillEndsTheFarPlayerSession() {
        gate.onJoin(true, false, true, true);
        gate.onSessionConfig(v20(true), true, true, true); // parked from accept
        assertNull(gate.getRequestManager());
        FarPlayerClientSupport.tracker().onRoster(new dev.vox.lss.common.farplayers.FarPlayerWire.Roster(
                9, true, List.of(), new int[0]));
        assertEquals(9, FarPlayerClientSupport.tracker().currentEpoch());
        events.clear();

        gate.onSessionConfig(SessionConfigS2CPayload.forSession(V, false, 64, true, 4554, CAPS, true),
                true, true, true);
        assertFalse(gate.isServerEnabled());
        assertEquals(-1, FarPlayerClientSupport.tracker().currentEpoch(), "far players ended");
        assertEquals(List.of(), events, "no manager to retire");
        gate.onSessionConfig(v20(false), true, true, true);
        assertNotNull(gate.getRequestManager(), "a re-enable without the deny re-arms");
        FarPlayerClientSupport.onSessionEnd();
    }

    /** Review fold A8, the client half of Paper's REVERSED ladder-race order: on Paper the
     *  quiet refusal of a raced legacy rung goes out INLINE while the v20 reply waits for
     *  the pump's Register drain — so a 0.19 client sees the rung's enabled=false FIRST and
     *  the v20 deny reply after. The v20 reply must still establish (the upgrade path), take
     *  the deny flag, build the manager, heal the server-side dialect with exactly ONE v20
     *  re-announce, and leave native-body decode disarmed. One row per raced rung. */
    @Test
    void papersInlineV19RungRefusalBeforeTheDeferredV20DenyStillEstablishes() {
        papersReversedOrder(LSSConstants.V19_COMPAT_PROTOCOL_VERSION);
    }

    @Test
    void papersInlineV16RungRefusalBeforeTheDeferredV20DenyStillEstablishes() {
        papersReversedOrder(LSSConstants.V16_COMPAT_PROTOCOL_VERSION);
    }

    private void papersReversedOrder(int rung) {
        var announced = new ArrayList<Integer>();
        var ordered = new ClientSessionGate(new ClientColumnProcessor(), announced::add, cfg -> {
            built.add(cfg);
            return new LodRequestManager() {
                @Override public void disconnect() { }
                @Override public void saveCache() { }
            };
        });
        ordered.onJoin(true, false, true, true);
        int rungs = rung == LSSConstants.V19_COMPAT_PROTOCOL_VERSION ? 1 : 2;
        for (int i = 0; i < rungs * ClientSessionGate.V16_DISCOVERY_DELAY_TICKS; i++) {
            ordered.tickDiscoveryLadder();
        }
        assertEquals(rung, announced.getLast(), "premise: the ladder reached the raced rung " + announced);
        int before = announced.size();

        // 1. The rung's quiet refusal, inline: enabled=false in the rung's own dialect.
        V16ClientWire.observeSessionConfigVersion(rung);
        ordered.onSessionConfig(rung == LSSConstants.V16_COMPAT_PROTOCOL_VERSION
                        ? SessionConfigS2CPayload.v16Legacy(false, 64, 4, 4, true)
                        : new SessionConfigS2CPayload(rung, false, 64, true), // a 19 frame has no flags field
                true, true, false);
        assertFalse(ordered.isServerEnabled());
        assertNull(ordered.getRequestManager());

        // 2. The deferred v20 reply, carrying the deny flag.
        V16ClientWire.observeSessionConfigVersion(V);
        ordered.onSessionConfig(v20(true), true, true, false);
        assertTrue(ordered.isServerEnabled(), "the upgrade path establishes v20");
        assertEquals(V, ordered.getSessionVersion());
        assertFalse(ordered.serverAllowsMapIngestion(), "the deny flag is taken");
        assertNotNull(ordered.getRequestManager(), "a Voxy client keeps its manager under a deny");
        assertFalse(ordered.isV16Server());
        assertEquals(List.of(V), announced.subList(before, announced.size()),
                "exactly one v20 re-announce heals the server-side dialect mark");
        assertFalse(V16ClientWire.isNativeBodySession(), "native-body decode stays disarmed");
        assertFalse(V16ClientWire.isColumnSourceless(), "…and so does the v16 source-less decode");
    }

    @Test
    void aClientWithVoxyKeepsItsManagerUnderADeny() {
        gate.onJoin(true, false, true, true);
        gate.onSessionConfig(v20(true), true, true, false);
        assertNotNull(gate.getRequestManager(), "Voxy still renders the terrain at the same distance");
        assertFalse(gate.serverAllowsMapIngestion(), "only the map writes stop");
    }

    // ---- the handshake capability mask ----

    @Test
    void everyHandshakeDeclaresTheMapPolicyBitUnlessEmulatingV19() {
        int caps = ClientNetGlue.handshakeCapabilitiesFor(LSSConstants.CAPABILITY_ZSTD_COLUMNS,
                LSSConstants.CAPABILITY_FAR_PLAYERS, false);
        assertEquals(LSSConstants.CAPABILITY_VOXEL_COLUMNS | LSSConstants.CAPABILITY_ZSTD_COLUMNS
                        | LSSConstants.CAPABILITY_FAR_PLAYERS | LSSConstants.CAPABILITY_MAP_POLICY, caps,
                "the bit is unconditional — harness JVMs (far-player bit zeroed) keep it too");
        assertEquals(LSSConstants.CAPABILITY_VOXEL_COLUMNS | LSSConstants.CAPABILITY_MAP_POLICY,
                ClientNetGlue.handshakeCapabilitiesFor(0, 0, false), "soak/benchmark JVM shape");
        assertEquals(LSSConstants.CAPABILITY_VOXEL_COLUMNS | LSSConstants.CAPABILITY_ZSTD_COLUMNS,
                ClientNetGlue.handshakeCapabilitiesFor(LSSConstants.CAPABILITY_ZSTD_COLUMNS,
                        LSSConstants.CAPABILITY_FAR_PLAYERS, true),
                "the v19 lever strips bits 4 and 8 — a real protocol-19 client never declared them");
        assertEquals(31, SoakDialectOverride.stripCapabilitiesFor(false, 31), "identity in production");
        assertEquals(31 & ~(4 | 8), SoakDialectOverride.stripCapabilitiesFor(true, 31));
    }
}
