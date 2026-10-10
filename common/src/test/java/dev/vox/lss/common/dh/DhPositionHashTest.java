package dev.vox.lss.common.dh;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

/** D-4: a range-128 tile window must not collapse into a few hash buckets (26 ms per chunk crossing). */
class DhPositionHashTest {
    @Test void aRange128WindowHashesNearlyUniquely() {
        var hashes = new HashSet<Integer>(); int tiles = 0;
        for (int x = -32; x <= 32; x++) for (int z = -32; z <= 32; z++) { hashes.add(new DhPosition(6, x, z).hashCode()); tiles++; }
        assertEquals(4225, tiles);
        assertTrue(hashes.size() > 4200, "distinct hashes: " + hashes.size());
    }
    @Test void equalPositionsHashEqually() {
        assertEquals(new DhPosition(6, -7, 9).hashCode(), new DhPosition(6, -7, 9).hashCode());
        assertEquals(new DhPosition(6, -7, 9), DhPosition.unpack(new DhPosition(6, -7, 9).packed()));
    }
}
