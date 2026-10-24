package dev.vox.lss.common;

import dev.vox.lss.common.HandshakeGate.Outcome;
import dev.vox.lss.common.HandshakeGate.WireDialect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The map-ingestion policy's handshake rung (map-policy plan A3.2) as a full ladder
 * table: armed × dialect × capability bit × enabled × consumer × permission-denied
 * (the service gate feeds the same {@code configEnabled} input as the kill switch).
 * The rung sits after DISABLED and before REGISTER, refuses everything but a
 * CURRENT-dialect session declaring {@code CAPABILITY_MAP_POLICY}, replies (never
 * silence) and never registers — and disarmed, the bit changes no decision.
 */
class MapPolicyGateTest {

    private static final int VOXEL = LSSConstants.CAPABILITY_VOXEL_COLUMNS;
    private static final int BIT = LSSConstants.CAPABILITY_MAP_POLICY;
    private static final int[] VERSIONS = {LSSConstants.PROTOCOL_VERSION,
            LSSConstants.V19_COMPAT_PROTOCOL_VERSION, LSSConstants.V18_COMPAT_PROTOCOL_VERSION,
            LSSConstants.V16_COMPAT_PROTOCOL_VERSION};

    private static HandshakeGate.Decision gate(int version, int caps, boolean configEnabled,
                                               boolean armed) {
        return HandshakeGate.evaluate(version, caps, configEnabled, true, true, true, true,
                false, armed);
    }

    private static WireDialect dialectOf(int version) {
        if (version == LSSConstants.PROTOCOL_VERSION) return WireDialect.CURRENT;
        if (version == LSSConstants.V19_COMPAT_PROTOCOL_VERSION) return WireDialect.V19;
        if (version == LSSConstants.V18_COMPAT_PROTOCOL_VERSION) return WireDialect.V18;
        return WireDialect.V16;
    }

    @Test
    void theFullLadderTable() {
        for (boolean armed : new boolean[]{false, true}) {
            for (int version : VERSIONS) {
                for (boolean bit : new boolean[]{false, true}) {
                    for (boolean consumer : new boolean[]{false, true}) {
                        // enabled=false covers both the server-wide kill switch AND a
                        // service-gate permission denial (the same evaluate input).
                        for (boolean enabled : new boolean[]{false, true}) {
                            int caps = (consumer ? VOXEL : 0) | (bit ? BIT : 0);
                            var d = gate(version, caps, enabled, armed);
                            String row = "armed=" + armed + " v" + version + " bit=" + bit
                                    + " consumer=" + consumer + " enabled=" + enabled;
                            assertEquals(dialectOf(version), d.dialect(), row);
                            assertTrue(d.sendSessionConfig(), "every row replies: " + row);
                            Outcome expected;
                            if (!consumer) expected = Outcome.NO_CONSUMER;
                            else if (!enabled) expected = Outcome.DISABLED;
                            else if (armed && !(version == LSSConstants.PROTOCOL_VERSION && bit))
                                expected = Outcome.MAP_POLICY_REFUSED;
                            else expected = Outcome.REGISTER;
                            assertEquals(expected, d.outcome(), row);
                            assertEquals(expected == Outcome.REGISTER, d.registerPlayer(), row);
                            if (expected == Outcome.MAP_POLICY_REFUSED) {
                                assertFalse(d.effectiveEnabled(),
                                        "a refusal advertises enabled=false: " + row);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void aPermissionDeniedPlayerStillLandsOnDisabledSoTheServiceGateLogStaysAnchored() {
        // deniedByServiceGate keys on outcome DISABLED: the policy rung must sit BELOW it.
        for (int version : VERSIONS) {
            assertEquals(Outcome.DISABLED, gate(version, VOXEL, false, true).outcome());
            assertEquals(Outcome.DISABLED, gate(version, VOXEL | BIT, false, true).outcome());
        }
    }

    @Test
    void theSilentRungsStillOutrankThePolicy() {
        var mismatch = gate(LSSConstants.PROTOCOL_VERSION + 1, VOXEL | BIT, true, true);
        assertEquals(Outcome.VERSION_MISMATCH, mismatch.outcome());
        assertFalse(mismatch.sendSessionConfig());
        var via = HandshakeGate.evaluate(LSSConstants.V19_COMPAT_PROTOCOL_VERSION, VOXEL, true,
                true, true, true, true, true, true);
        assertEquals(Outcome.VIA_MISMATCH, via.outcome(), "Via denial precedes the policy rung");
        assertFalse(via.sendSessionConfig());
    }

    /** Disarmed, the policy is inert across the whole input domain: the rung never fires
     *  and the map-policy bit changes no decision (0.19.0 review O6: this replaced a
     *  comparison of the 8-arg overload with the 9-arg form it merely delegates to, which
     *  could not fail). The pre-policy outcomes themselves are pinned by the oracle in
     *  {@link #theFullLadderTable}. */
    @Test
    void theDisarmedLadderIgnoresTheMapPolicyBit() {
        for (int version : new int[]{14, 16, 17, 18, 19, 20, 21}) {
            for (int caps = 0; caps < 16; caps++) {
                for (boolean enabled : new boolean[]{false, true}) {
                    for (boolean via : new boolean[]{false, true}) {
                        for (boolean service : new boolean[]{false, true}) {
                            String row = "v" + version + " caps=" + caps + " enabled=" + enabled
                                    + " via=" + via + " service=" + service;
                            var with = HandshakeGate.evaluate(version, caps | BIT, enabled,
                                    service, true, true, true, via, false);
                            var without = HandshakeGate.evaluate(version, caps & ~BIT, enabled,
                                    service, true, true, true, via, false);
                            assertEquals(without, with, row);
                            assertTrue(with.outcome() != Outcome.MAP_POLICY_REFUSED, row);
                        }
                    }
                }
            }
        }
    }

    @Test
    void honorsMapPolicyNeedsCurrentAndTheBit() {
        assertTrue(HandshakeGate.honorsMapPolicy(WireDialect.CURRENT, VOXEL | BIT));
        assertFalse(HandshakeGate.honorsMapPolicy(WireDialect.CURRENT, VOXEL));
        for (var legacy : new WireDialect[]{WireDialect.V19, WireDialect.V18, WireDialect.V16}) {
            assertFalse(HandshakeGate.honorsMapPolicy(legacy, VOXEL | BIT),
                    legacy + ": a legacy layout has no flags field to carry the request");
        }
    }

    @Test
    void theSharedTextNamesTheBrandAndTheDiagLineIsArmedOnly() {
        // The user-approved wording names the mod in full ("LOD Server Support"), not "LSS".
        assertEquals("Distant terrain on this server needs " + Brand.displayName()
                + " 0.19.0 or newer. Update the mod to see it.", MapPolicy.refusalNotice());
        assertNull(MapPolicy.diagLineOrNull(false, 3, 1, "none"), "no line while the policy is off");
        assertEquals("MapPolicy: on refused=2 exempt=1 provider=luckperms",
                MapPolicy.diagLineOrNull(true, 2, 1, "luckperms"),
                "exempt= (the sweep's cached count) and the loader's provider token");
        assertEquals("MapPolicy: on refused=2 exempt=0", MapPolicy.diagLineOrNull(true, 2, 0, null),
                "Paper carries no provider token (always Bukkit)");
        assertTrue(MapPolicy.refusalLogLine("Steve", 19, 3).contains("protocol v19, capabilities=3"));
        assertTrue(MapPolicy.revocationLogLine("Steve", 20, 5).contains("Steve"));
    }

    // ---- the per-player exemption (map-ingestion permission plan §3.1/§3.2) ----

    @Test
    void theDecisionAppliesTheRequestUnlessThePlayerIsExemptAndReadsNothingWhileOff() {
        int[] reads = {0};
        var off = MapPolicy.decide(false, () -> { reads[0]++; return true; });
        assertEquals(MapPolicy.Decision.OFF, off);
        assertFalse(off.deny() || off.exempt(), "off: nothing denied, nobody exempt");
        assertEquals(0, reads[0], "a disarmed policy costs no permission read");
        var exempt = MapPolicy.decide(true, () -> { reads[0]++; return true; });
        assertFalse(exempt.deny(), "an exempt player is treated as if the request were off");
        assertTrue(exempt.exempt());
        var denied = MapPolicy.decide(true, () -> false);
        assertTrue(denied.deny() && !denied.exempt());
        assertEquals(1, reads[0]);
    }

    @Test
    void theExemptionHoldsOnEitherSpellingAndFailsClosed() {
        assertEquals("lss.map_ingestion", LSSPermissions.MAP_INGESTION_LSS);
        assertEquals("vss.map_ingestion", LSSPermissions.MAP_INGESTION_VSS);
        assertTrue(MapPolicy.holdsExemption(LSSPermissions.MAP_INGESTION_LSS::equals), "the LSS spelling");
        assertTrue(MapPolicy.holdsExemption(LSSPermissions.MAP_INGESTION_VSS::equals),
                "the VSS spelling (an LSS<->VSS jar swap keeps the grant)");
        assertFalse(MapPolicy.holdsExemption(node -> false), "neither: not exempt");
        assertFalse(MapPolicy.holdsExemption(node -> node.startsWith("lss.use")),
                "an unrelated grant exempts nobody");
        assertFalse(MapPolicy.holdsExemption(node -> { throw new IllegalStateException("backend exploded"); }),
                "FAIL-CLOSED: a throwing read is not exempt (unlike the service gate's fail-open reads)");
        assertFalse(PlayerServiceGate.OPEN.mapIngestionExempt(),
                "the OPEN gate (and every gate that does not override it) exempts nobody");
    }
}
