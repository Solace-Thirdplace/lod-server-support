package dev.vox.lss.networking.client;

import dev.vox.lss.common.diagnostics.ClientStatusSnapshot.Availability;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The map backends' status availability with the server's map-ingestion request
 * (map-policy plan A4): {@code SERVER_BLOCKED} when the map mod is installed, resolved
 * and enabled locally but the connected server asks clients not to write LOD terrain
 * into maps. Integration facts and the LOCAL setting outrank it (the local setting is
 * never changed by a server), and every status reads the same ladder for Xaero and
 * JourneyMap. The lang completeness of every {@link Availability} value is pinned by
 * {@code ClientStatusTextTest}.
 */
class ClientStatusMapAvailabilityTest {

    @Test
    void theServerBlockSitsBelowTheIntegrationFactsAndTheLocalSetting() {
        assertEquals(Availability.SERVER_BLOCKED,
                ClientStatus.mapAvailability(false, "available", true, false));
        assertEquals(Availability.AVAILABLE,
                ClientStatus.mapAvailability(false, "available", true, true));
        assertEquals(Availability.DISABLED,
                ClientStatus.mapAvailability(false, "available", false, false),
                "locally off reads as the user's own choice, whatever the server asks");
        assertEquals(Availability.ABSENT,
                ClientStatus.mapAvailability(false, "absent", true, false));
        assertEquals(Availability.UNAVAILABLE,
                ClientStatus.mapAvailability(false, "unavailable", true, false));
        assertEquals(Availability.UNKNOWN,
                ClientStatus.mapAvailability(false, "unknown", true, false));
        assertEquals(Availability.FAILED,
                ClientStatus.mapAvailability(true, "available", true, false));
    }
}
