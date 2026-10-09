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

    @Test void dimensionsAndVanillaRegionsAreBoundedButColumnsAreNot() {
        var connection = new DhConnectionState();
        for (int i=0;i<16;i++) connection.policy("dimension-"+i);
        assertThrows(IllegalStateException.class, () -> connection.policy("dimension-16"));
        assertEquals(DhConnectionState.Block.DIMENSION_LIMIT, connection.blockReason("dimension-16"));
        // 400k columns: far beyond the retired 262,144-column hard stop.
        for (int x=0;x<640;x++) for (int z=0;z<640;z++) connection.vanilla("dimension-0", x, z);
        assertFalse(connection.blocked("dimension-0"));
        for (int i=0;i<DhConnectionState.MAX_VANILLA_REGIONS-400;i++) connection.vanilla("dimension-1", 100_000+i*32, 0);
        connection.vanilla("dimension-1", 100_000, 5); // inside a known region: free
        assertThrows(IllegalStateException.class, () -> connection.vanilla("dimension-2", -5000, -5000));
        assertEquals(DhConnectionState.Block.VANILLA_LIMIT, connection.blockReason("dimension-2"));
    }
    @Test void loadDebounceIsPerDimensionAndClearedByReset() {
        var connection = new DhConnectionState();var p=new DhPosition(6,1,1);
        connection.loadedAt("a").put(p,5L);assertEquals(5L,connection.loadedAt("a").get(p));
        assertTrue(connection.loadedAt("b").isEmpty());
        connection.forgetLoads("a");assertTrue(connection.loadedAt("a").isEmpty());
        connection.loadedAt("a").put(p,5L);connection.resetReceipts();assertTrue(connection.loadedAt("a").isEmpty());
    }
}
