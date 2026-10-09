package dev.vox.lss.common.dh;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.vox.lss.common.dh.DhAcquisition.Gate.*;

class DhAcquisitionTest {
    @Test void primaryOwnsTheReportedWaitEvenWhenItsOrdinalIsLater() {
        var wait=DhAcquisition.waiting(SERVER_RATE,10_000_000).and(DhAcquisition.waiting(LOCAL_PACING,490_000_000));
        assertEquals(LOCAL_PACING,wait.primary());assertEquals(490,wait.waitMillis(0));
        var unknown=wait.and(DhAcquisition.waiting(IN_FLIGHT));
        assertEquals(IN_FLIGHT,unknown.primary());assertEquals(-1,unknown.waitMillis(0));
    }
    @Test void combinedTimerGatesUseTheLongestWaitAndStablePrimary() {
        var wait=DhAcquisition.waiting(MANUAL_RATE,800_000_001).and(DhAcquisition.waiting(REFUSAL_BACKOFF,1_000_000_000));
        assertEquals(REFUSAL_BACKOFF,wait.primary());assertEquals(1000,wait.waitMillis(0));
        assertEquals(1,wait.waitMillis(999_999_999));assertEquals(0,wait.waitMillis(2_000_000_000));
        assertEquals(wait,DhAcquisition.waiting(REFUSAL_BACKOFF,1_000_000_000).and(DhAcquisition.waiting(MANUAL_RATE,800_000_001)));
        assertEquals((1L<<MANUAL_RATE.ordinal())|(1L<<REFUSAL_BACKOFF.ordinal()),wait.gates());
    }
    @Test void ReadinessDoesNotInventAnAdmissionOrDeliveryTime() {
        var wait=DhAcquisition.waiting(SYNC_SLOTS).and(DhAcquisition.waiting(SERVER_RATE,1_000_000));
        assertEquals(-1,wait.waitMillis(Long.MAX_VALUE));
        assertEquals(0,DhAcquisition.OPEN.waitMillis(0));assertEquals(0,DhAcquisition.OPEN.gates());
    }
}
