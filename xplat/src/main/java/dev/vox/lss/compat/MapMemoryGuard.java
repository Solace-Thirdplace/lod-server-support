package dev.vox.lss.compat;

import dev.vox.lss.common.LSSLogger;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * The heap belt shared by the map-ingestion backends (map-ingestion plan §4.3): when the old
 * generation stays full after collections, map writers stop allocating (no commits, no region
 * loads, no keep-visited) so a map mod that cannot release memory — Xaero with a starved,
 * frame-bound release pipeline — cannot run the client out of heap.
 *
 * <p>Reading: the post-GC usage ({@code MemoryPoolMXBean.getCollectionUsage()}) of the old /
 * whole-heap pool, from an explicit allow-list — young pools report finite maxima on several
 * collectors (ZGC young, Parallel/Serial eden+survivor) and would read "full" after any young
 * GC. No allow-listed pool with a positive max = {@code n/a}: the guard never halts.
 *
 * <p>Hysteresis {@value #HALT_FRACTION}/{@value #RESUME_FRACTION}, sampled at most once per
 * second, and DUTY-CYCLED: a halt lasting {@value #HALT_MAX_MILLIS} ms releases for
 * {@value #RELEASE_MILLIS} ms — an old-generation reading only refreshes after an old
 * collection, so a stale "full" reading must slow the fill, never stop it.
 */
final class MapMemoryGuard {
    static final double HALT_FRACTION = 0.85;
    static final double RESUME_FRACTION = 0.70;
    static final long SAMPLE_INTERVAL_MILLIS = 1000;
    static final long HALT_MAX_MILLIS = 60_000;
    static final long RELEASE_MILLIS = 10_000;
    static final Set<String> OLD_POOLS = Set.of("G1 Old Gen", "PS Old Gen", "Tenured Gen",
            "ZGC Old Generation", "ZHeap", "Shenandoah");

    /** Production guard (one per JVM; both backends read the same reading). */
    static final MapMemoryGuard PRODUCTION = new MapMemoryGuard(MapMemoryGuard::postGcOldFraction,
            System::currentTimeMillis);
    /** A guard that never halts (tests; harness arms that must not shift). */
    static final MapMemoryGuard NEVER = new MapMemoryGuard(() -> Double.NaN, () -> 0L);

    private final DoubleSupplier sampler;
    private final LongSupplier clock;
    private long lastSampleMillis = Long.MIN_VALUE;
    private double fraction = Double.NaN;
    private boolean pressure;
    private long pressureSinceMillis;
    private long releaseUntilMillis = Long.MIN_VALUE;

    MapMemoryGuard(DoubleSupplier sampler, LongSupplier clock) {
        this.sampler = sampler;
        this.clock = clock;
    }

    /** True while map writers must stop allocating (inside the duty cycle's halted half). */
    synchronized boolean halted() {
        long now = this.clock.getAsLong();
        sample(now);
        if (!this.pressure) return false;
        if (now < this.releaseUntilMillis) return false;
        if (now - this.pressureSinceMillis >= HALT_MAX_MILLIS) {
            // The duty cycle: release, then halt again from a fresh window if still full.
            this.releaseUntilMillis = now + RELEASE_MILLIS;
            this.pressureSinceMillis = now + RELEASE_MILLIS;
            return false;
        }
        return true;
    }

    /** The {@code heap=} diag token: {@code n/a}, the fraction, or {@code halt(f)}/{@code released(f)}. */
    synchronized String token() {
        if (Double.isNaN(this.fraction)) return "n/a";
        String f = String.format(Locale.ROOT, "%.2f", this.fraction);
        if (!this.pressure) return f;
        return (this.clock.getAsLong() < this.releaseUntilMillis ? "released(" : "halt(") + f + ")";
    }

    synchronized double fraction() {
        return this.fraction;
    }

    private void sample(long now) {
        if (now - this.lastSampleMillis < SAMPLE_INTERVAL_MILLIS && this.lastSampleMillis != Long.MIN_VALUE) return;
        this.lastSampleMillis = now;
        double f;
        try {
            f = this.sampler.getAsDouble();
        } catch (RuntimeException e) {
            f = Double.NaN;
        }
        this.fraction = f;
        if (Double.isNaN(f)) {
            this.pressure = false;
        } else if (!this.pressure && f >= HALT_FRACTION) {
            this.pressure = true;
            this.pressureSinceMillis = now;
            this.releaseUntilMillis = Long.MIN_VALUE;
            LSSLogger.info(String.format(Locale.ROOT, "Map ingestion paused: heap %.0f%% full after"
                    + " garbage collection (map writing resumes below %.0f%%)", f * 100, RESUME_FRACTION * 100));
        } else if (this.pressure && f <= RESUME_FRACTION) {
            this.pressure = false;
            LSSLogger.info(String.format(Locale.ROOT, "Map ingestion resumed: heap %.0f%% full", f * 100));
        }
    }

    private static volatile List<MemoryPoolMXBean> oldPools;

    /** The max post-GC used/max over allow-listed heap pools with a positive max; NaN if none. */
    static double postGcOldFraction() {
        var pools = oldPools;
        if (pools == null) {
            pools = ManagementFactory.getMemoryPoolMXBeans().stream()
                    .filter(p -> p.getType() == MemoryType.HEAP && OLD_POOLS.contains(p.getName()))
                    .toList();
            oldPools = pools;
        }
        return fractionOf(pools);
    }

    static double fractionOf(List<MemoryPoolMXBean> pools) {
        double best = Double.NaN;
        for (var pool : pools) {
            var usage = pool.getCollectionUsage();
            if (usage == null || usage.getMax() <= 0) continue;
            double f = (double) usage.getUsed() / usage.getMax();
            if (Double.isNaN(best) || f > best) best = f;
        }
        return best;
    }
}
