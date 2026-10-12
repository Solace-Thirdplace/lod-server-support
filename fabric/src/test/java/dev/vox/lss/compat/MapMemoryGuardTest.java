package dev.vox.lss.compat;

import org.junit.jupiter.api.Test;

import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The map-ingestion heap belt (map-ingestion plan §4.3). */
class MapMemoryGuardTest {
    private long now = 1_000_000L;
    private double fraction;

    private MapMemoryGuard guard() {
        return new MapMemoryGuard(() -> this.fraction, () -> this.now);
    }

    @Test
    void hysteresisHaltsAtTheHighMarkAndResumesAtTheLowMark() {
        var g = guard();
        this.fraction = 0.84;
        assertFalse(g.halted());
        this.now += 1000; this.fraction = 0.85;
        assertTrue(g.halted());
        assertEquals("halt(0.85)", g.token());
        this.now += 1000; this.fraction = 0.75;
        assertTrue(g.halted(), "between the marks the halt holds");
        this.now += 1000; this.fraction = 0.70;
        assertFalse(g.halted());
        assertEquals("0.70", g.token());
    }

    @Test
    void readingsAreSampledAtMostOncePerSecond() {
        var g = guard();
        this.fraction = 0.95;
        assertTrue(g.halted());
        this.fraction = 0.10;
        this.now += 999;
        assertTrue(g.halted(), "the reading is cached inside the sample interval");
        this.now += 1;
        assertFalse(g.halted());
    }

    @Test
    void aStaleFullReadingSlowsTheFillButNeverStopsIt() {
        var g = guard();
        this.fraction = 0.95;
        assertTrue(g.halted());
        this.now += MapMemoryGuard.HALT_MAX_MILLIS;
        assertFalse(g.halted(), "after the halt window the duty cycle releases");
        assertTrue(g.token().startsWith("released("), g.token());
        this.now += MapMemoryGuard.RELEASE_MILLIS - 1;
        assertFalse(g.halted());
        this.now += 1;
        assertTrue(g.halted(), "and halts again from a fresh window while still full");
        this.now += MapMemoryGuard.HALT_MAX_MILLIS - 1;
        assertTrue(g.halted());
    }

    @Test
    void noReadingNeverHalts() {
        var g = guard();
        this.fraction = Double.NaN;
        assertFalse(g.halted());
        assertEquals("n/a", g.token());
        assertFalse(MapMemoryGuard.NEVER.halted());
    }

    @Test
    void onlyAllowListedPoolsWithAPositiveMaxCount() {
        var old = pool(800, 1000);
        var unbounded = pool(10, -1);
        assertEquals(0.8, MapMemoryGuard.fractionOf(List.of(old, unbounded)), 1e-9);
        assertTrue(Double.isNaN(MapMemoryGuard.fractionOf(List.of(unbounded))));
        assertTrue(Double.isNaN(MapMemoryGuard.fractionOf(List.of())));
        assertTrue(MapMemoryGuard.OLD_POOLS.contains("G1 Old Gen"));
        assertTrue(MapMemoryGuard.OLD_POOLS.contains("ZGC Old Generation"));
        assertFalse(MapMemoryGuard.OLD_POOLS.contains("ZGC Young Generation"),
                "young pools report finite maxima on ZGC/Parallel/Serial and would read full after a young GC");
    }

    @Test
    void theProductionReadingIsAFractionOrUnknown() {
        double f = MapMemoryGuard.postGcOldFraction();
        assertTrue(Double.isNaN(f) || (f >= 0 && f <= 1), String.valueOf(f));
    }

    private static MemoryPoolMXBean pool(long used, long max) {
        var usage = new MemoryUsage(0, used, Math.max(used, 1), max);
        return (MemoryPoolMXBean) Proxy.newProxyInstance(MemoryPoolMXBean.class.getClassLoader(),
                new Class<?>[]{MemoryPoolMXBean.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getCollectionUsage" -> usage;
                    case "getName" -> "G1 Old Gen";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "pool";
                    default -> null;
                });
    }
}
