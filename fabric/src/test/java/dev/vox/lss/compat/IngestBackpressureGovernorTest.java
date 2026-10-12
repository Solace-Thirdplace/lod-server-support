package dev.vox.lss.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The shared report core against the Xaero bridge's own copy (map-ingestion plan §3.1,
 * review MINOR 4): the same randomized traces — occupancy walks, pump stalls, pacing holds,
 * kill-switch flips, long plateaus that wedge and re-arm — produce the same report AND the
 * same diag token at every step. Xaero keeps its fixture-pinned copy; this keeps the two
 * from drifting apart silently.
 */
class IngestBackpressureGovernorTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void theConstantsMatchTheXaeroBridge() {
        assertEquals(XaeroSession.BP_HALT_OCCUPANCY, IngestBackpressureGovernor.HALT_OCCUPANCY);
        assertEquals(XaeroSession.BP_PUMP_STALE_MILLIS, IngestBackpressureGovernor.PUMP_STALE_MILLIS);
        assertEquals(XaeroSession.BP_HALT_WEDGE_MILLIS, IngestBackpressureGovernor.HALT_WEDGE_MILLIS);
        assertEquals(XaeroSession.BP_PACE_HOLD_MILLIS, IngestBackpressureGovernor.PACE_HOLD_MILLIS);
        assertEquals(XaeroSession.BP_WEDGE_REARM_OCCUPANCY, IngestBackpressureGovernor.REARM_OCCUPANCY);
        assertEquals(XaeroSession.BP_WEDGE_REARM_MILLIS, IngestBackpressureGovernor.REARM_MILLIS);
        assertEquals(XaeroSession.BP_HALT_PROGRESS_EPS, IngestBackpressureGovernor.PROGRESS_EPS);
    }

    @Test
    void randomizedTracesReportIdenticallyToTheXaeroCopy() throws Exception {
        for (long seed = 1; seed <= 40; seed++) replay(seed);
    }

    private static void replay(long seed) throws Exception {
        var random = new Random(seed);
        long[] clock = {1_000_000L};
        boolean[] enabled = {true};
        boolean[] backpressure = {true};
        var xaero = new XaeroSession(XaeroBindings.resolve(Class::forName), null,
                () -> enabled[0], () -> true, c -> {}, c -> {}, () -> backpressure[0], (d, x, z) -> {});
        xaero.bpClock = () -> clock[0];
        var governor = new IngestBackpressureGovernor("test", () -> clock[0]);
        double occupancy = 0;
        boolean hold = false;
        List<String> trace = new ArrayList<>();
        for (int step = 0; step < 3000; step++) {
            int action = random.nextInt(100);
            if (action < 50) occupancy = Math.max(0, Math.min(1.2, occupancy + (random.nextDouble() - 0.4) * 0.1));
            else if (action < 55) occupancy = random.nextDouble() * 1.2;
            else if (action < 58) hold = !hold;
            else if (action < 59) backpressure[0] = !backpressure[0];
            else if (action < 60) enabled[0] = !enabled[0];
            clock[0] += random.nextInt(100) < 3 ? 1500 + random.nextInt(4000) : 50 + random.nextInt(300);
            if (random.nextInt(100) < 95) { // the pump ran this tick (else: a stall)
                xaero.lastPumpMillis = clock[0];
                governor.notePump();
            }
            xaero.acquisition.occupancy = occupancy;
            xaero.windowBound = hold;
            boolean active = !xaero.dead && enabled[0];
            int expected = xaero.reportBackpressure();
            int actual = governor.report(backpressure[0], active, occupancy, hold);
            String expectedToken = xaero.bpToken();
            String actualToken = governor.token(backpressure[0], active, occupancy);
            trace.add(step + ": occ=" + occupancy + " hold=" + hold + " -> " + expected);
            if (expected != actual || !expectedToken.equals(actualToken)) {
                throw new AssertionError("seed " + seed + " step " + step + ": xaero " + expected + "/" + expectedToken
                        + " vs shared " + actual + "/" + actualToken + "\n"
                        + String.join("\n", trace.subList(Math.max(0, trace.size() - 12), trace.size())));
            }
        }
    }
}
