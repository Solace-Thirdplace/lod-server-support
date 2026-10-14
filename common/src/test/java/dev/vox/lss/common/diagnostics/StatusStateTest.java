package dev.vox.lss.common.diagnostics;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static dev.vox.lss.common.diagnostics.ClientStatusSnapshot.*;

class StatusStateTest {
    private static ClientStatusSnapshot snapshot(boolean connected, boolean receive, boolean consumer,
            Discovery discovery, boolean server, Availability integration, int queue, int ingest, int cap, long gated, int rebuilds) {
        return new ClientStatusSnapshot(1, 4, 123, connected, discovery == Discovery.NEGOTIATED,
                receive, server, consumer, false, 20, 512, 128, 0, 0, queue, ingest, 0, cap, gated,
                rebuilds, discovery, integration, null, DiagnosticVersions.unknown());
    }
    @Test void noManagerStatesRemainUsefulAndMutuallyHonest() {
        record Case(ClientStatusSnapshot snapshot, List<Reason> expected) {}
        var cases = List.of(
                new Case(snapshot(false,true,false,Discovery.NOT_CONNECTED,false,Availability.UNKNOWN,0,-1,0,0,0), List.of(Reason.NOT_CONNECTED,Reason.NO_CONSUMER)),
                new Case(snapshot(true,false,true,Discovery.DORMANT,false,Availability.DISABLED,0,-1,0,0,0), List.of(Reason.RECEPTION_OFF)),
                new Case(snapshot(true,true,true,Discovery.AWAITING_NEGOTIATION,false,Availability.UNKNOWN,0,-1,0,0,0), List.of(Reason.AWAITING_NEGOTIATION)),
                new Case(snapshot(true,true,true,Discovery.SEND_FAILED,false,Availability.UNKNOWN,0,-1,0,0,0), List.of(Reason.HANDSHAKE_SEND_FAILED)),
                new Case(snapshot(true,true,true,Discovery.PROTOCOL_REJECTED,false,Availability.UNKNOWN,0,-1,0,0,0), List.of(Reason.PROTOCOL_REJECTED)),
                new Case(snapshot(true,true,true,Discovery.NEGOTIATED,false,Availability.ABSENT,0,-1,0,0,0), List.of(Reason.SERVER_DISABLED)),
                new Case(snapshot(true,true,false,Discovery.DORMANT,false,Availability.UNAVAILABLE,0,-1,0,0,0), List.of(Reason.NO_CONSUMER,Reason.XAERO_UNAVAILABLE)),
                new Case(snapshot(true,true,false,Discovery.DORMANT,false,Availability.FAILED,0,-1,0,0,0), List.of(Reason.NO_CONSUMER,Reason.XAERO_FAILED)));
        for (var test : cases) {
            assertEquals(test.expected(), test.snapshot().reasons());
            assertTrue(test.snapshot().lines().stream().anyMatch(line -> line.contains("Snapshot age:")));
            assertTrue(test.snapshot().lines().stream().anyMatch(line -> line.contains("Versions:")));
            assertTrue(test.snapshot().lines().stream().anyMatch(line -> line.contains("unavailable on this loader/line")));
        }
    }
    @Test void intervalCapAndQueueObservationsCoexistWithoutHistoricalPressureClaim() {
        var pending = snapshot(true,true,true,Discovery.NEGOTIATED,true,Availability.AVAILABLE,3,2,20,1,4);
        assertEquals(List.of(Reason.LOCAL_RATE_CAP,Reason.DECODE_QUEUE_PENDING,Reason.INGEST_QUEUE_PENDING,Reason.XAERO_REBUILDS),pending.reasons());
        assertEquals(List.of(Reason.LOCAL_RATE_CAP),snapshot(true,true,true,Discovery.NEGOTIATED,true,Availability.AVAILABLE,0,0,0,1,0).reasons());
        assertEquals(List.of(),snapshot(true,true,true,Discovery.NEGOTIATED,true,Availability.AVAILABLE,0,0,20,0,0).reasons());
    }
    /** Map-policy review fold A5: a server-blocked map backend explains itself among the
     *  observed conditions (the availability line alone read "turned off by this server"
     *  with no matching condition), once, whichever backend carries it. */
    @Test void aServerBlockedMapBackendIsAnObservedCondition() {
        var xaero = snapshot(true,true,true,Discovery.NEGOTIATED,true,Availability.SERVER_BLOCKED,0,0,20,0,0);
        assertEquals(List.of(Reason.MAP_INGESTION_SERVER_BLOCKED), xaero.reasons());
        assertTrue(xaero.lines().stream().anyMatch(line -> line.contains("MAP_INGESTION_SERVER_BLOCKED")));
        var both = new ClientStatusSnapshot(SCHEMA_VERSION, 4, 123, true, true, true, true, true, false, 20, 512,
                128, 0, 0, 0, 0, 0, 20, 0, 0, Discovery.NEGOTIATED, Availability.SERVER_BLOCKED, null,
                DiagnosticVersions.unknown(), null, null, Availability.SERVER_BLOCKED, 0);
        assertEquals(List.of(Reason.MAP_INGESTION_SERVER_BLOCKED), both.reasons());
        var journeyMapOnly = new ClientStatusSnapshot(SCHEMA_VERSION, 4, 123, true, true, true, true, true, false, 20,
                512, 128, 0, 0, 0, 0, 0, 20, 0, 0, Discovery.NEGOTIATED, Availability.ABSENT, null,
                DiagnosticVersions.unknown(), null, null, Availability.SERVER_BLOCKED, 0);
        assertEquals(List.of(Reason.MAP_INGESTION_SERVER_BLOCKED), journeyMapOnly.reasons());
        assertEquals(List.of(), snapshot(true,true,true,Discovery.NEGOTIATED,true,Availability.DISABLED,0,0,20,0,0).reasons(),
                "the user's own local off is not a condition");
    }
}
