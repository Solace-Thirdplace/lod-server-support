package dev.vox.lss.common.dh;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DhConnectionStateTest {
    @Test void portalRoundTripAndResetKeepVanillaAuthorityUntilDisconnect() {
        var connection = new DhConnectionState();
        var overworld = connection.policy("overworld");
        connection.vanilla("overworld", 8, 9);
        connection.vanilla("nether", 20, 30);
        assertSame(overworld, connection.policy("overworld"));
        connection.resetReceipts();
        assertFalse(connection.policy("overworld").eligible(8, 9));
        assertFalse(connection.policy("nether").eligible(20, 30));
        connection.disconnect();
        assertTrue(connection.policy("overworld").eligible(8, 9));
    }

    @Test void differentWorldKeyRequiresReconnectInsteadOfReusingAuthority() {
        var connection = new DhConnectionState();
        assertTrue(connection.bindWorld("overworld", "world-a"));
        assertTrue(connection.bindWorld("overworld", "world-a"));
        assertFalse(connection.bindWorld("overworld", "world-b"));
        assertTrue(connection.blocked("overworld"));
        connection.disconnect();
        assertTrue(connection.bindWorld("overworld", "world-b"));
    }

    @Test void dimensionsAndTotalVanillaAuthorityAreBounded() {
        var connection = new DhConnectionState();
        for (int i=0;i<16;i++) connection.policy("dimension-"+i);
        assertThrows(IllegalStateException.class, () -> connection.policy("dimension-16"));
        for (int i=0;i<262_144;i++) connection.vanilla("dimension-0", i, 0);
        connection.vanilla("dimension-0", 0, 0); // duplicates do not consume another slot
        assertThrows(IllegalStateException.class, () -> connection.vanilla("dimension-1", 1, 1));
    }
}
