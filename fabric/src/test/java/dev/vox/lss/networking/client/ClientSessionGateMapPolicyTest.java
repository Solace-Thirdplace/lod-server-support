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
