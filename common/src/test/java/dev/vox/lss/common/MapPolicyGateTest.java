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
 * silence) and never registers — and the disarmed ladder is byte-identical to the
 * pre-policy 8-arg form.
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

    @Test
    void theDisarmedLadderIsTheEightArgLadder() {
        for (int version : new int[]{14, 16, 17, 18, 19, 20, 21}) {
            for (int caps = 0; caps < 16; caps++) {
                for (boolean enabled : new boolean[]{false, true}) {
                    for (boolean via : new boolean[]{false, true}) {
                        var legacy = HandshakeGate.evaluate(version, caps, enabled, true, true,
                                true, true, via);
                        var widest = HandshakeGate.evaluate(version, caps, enabled, true, true,
                                true, true, via, false);
                        assertEquals(legacy, widest, "v" + version + " caps=" + caps);
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
        assertTrue(MapPolicy.refusalNotice().startsWith("Distant terrain on this server needs "
                + Brand.shortName() + " 0.19.0 or newer."), MapPolicy.refusalNotice());
        assertNull(MapPolicy.diagLineOrNull(false, 3), "no line while the policy is off");
        assertEquals("MapPolicy: on refused=2", MapPolicy.diagLineOrNull(true, 2));
        assertTrue(MapPolicy.refusalLogLine("Steve", 19, 3).contains("protocol v19, capabilities=3"));
        assertTrue(MapPolicy.revocationLogLine("Steve", 20, 5).contains("Steve"));
    }
}
