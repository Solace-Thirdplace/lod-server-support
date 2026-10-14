package dev.vox.lss.compat;

import dev.vox.lss.compat.XaeroSession.RegionKey;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Region-load instrumentation (cave-layers plan §B0, kept for the fill-on-switch spike): the
 * grant → loadState-2 latency of the bridge's region loads as a bounded histogram, and regions
 * that VANISHED after a grant — Xaero removed (or replaced) the region object before the first
 * commit landed in it. Main thread only (every observation site runs in the pump ladder).
 * Bounded: at most {@link #MAX_PENDING} outstanding grants are followed (oldest dropped
 * untracked) and a grant is forgotten after {@link #FOLLOW_MILLIS}.
 */
final class XaeroLoadStats {
    static final int MAX_PENDING = 512;
    static final long FOLLOW_MILLIS = 5 * 60_000L;
    /** Histogram bucket upper bounds, ms; the last bucket is open-ended. */
    static final long[] BUCKETS_MS = {50, 100, 200, 400, 800, 1_600, 3_200, 6_400, 12_800, 25_600, Long.MAX_VALUE};

    /** One followed grant: the region object granted, when, and whether its load has been
     *  observed. */
    private static final class Pending {
        final Object region;
        final long grantedAtMillis;
        boolean loaded;

        Pending(Object region, long grantedAtMillis) {
            this.region = region;
            this.grantedAtMillis = grantedAtMillis;
        }
    }

    /** A bounded latency histogram. */
    static final class Histogram {
        final long[] counts = new long[BUCKETS_MS.length];
        long total;

        void add(long millis) {
            for (int i = 0; i < BUCKETS_MS.length; i++) {
                if (millis <= BUCKETS_MS[i]) {
                    this.counts[i]++;
                    this.total++;
                    return;
                }
            }
        }

        /** The bucket upper bound holding the {@code q} quantile; -1 with no samples, and
         *  {@code -2} (open-ended) past the last finite bound. */
        long quantileMillis(double q) {
            if (this.total == 0) return -1;
            long rank = (long) Math.ceil(q * this.total);
            long seen = 0;
            for (int i = 0; i < BUCKETS_MS.length; i++) {
                seen += this.counts[i];
                if (seen >= rank) return BUCKETS_MS[i] == Long.MAX_VALUE ? -2 : BUCKETS_MS[i];
            }
            return -2;
        }

        void clear() {
            java.util.Arrays.fill(this.counts, 0);
            this.total = 0;
        }
    }

    private final LinkedHashMap<RegionKey, Pending> pending = new LinkedHashMap<>();
    final Histogram loads = new Histogram();
    final AtomicLong vanished = new AtomicLong();

    /** A load was requested for {@code region} (a re-request of a followed grant keeps its clock). */
    void onGrant(RegionKey key, Object region, long nowMillis) {
        var existing = this.pending.get(key);
        if (existing != null && existing.region == region) return;
        if (existing != null) this.pending.remove(key);
        while (this.pending.size() >= MAX_PENDING) {
            Iterator<Pending> it = this.pending.values().iterator();
            it.next();
            it.remove();
        }
        this.pending.put(key, new Pending(region, nowMillis));
    }

    /** A probe read {@code region}'s load state under its monitor. */
    void onObserve(RegionKey key, Object region, byte loadState, long nowMillis) {
        var p = this.pending.get(key);
        if (p == null) return;
        if (region != p.region) {
            vanished(p);
            this.pending.remove(key);
            return;
        }
        if (!p.loaded && loadState == 2) {
            p.loaded = true;
            this.loads.add(nowMillis - p.grantedAtMillis);
        } else if (nowMillis - p.grantedAtMillis > FOLLOW_MILLIS) {
            this.pending.remove(key);
        }
    }

    /** A probe found no region for the key (removed by Xaero). */
    void onMissing(RegionKey key) {
        var p = this.pending.remove(key);
        if (p != null) vanished(p);
    }

    /** A tile committed into {@code region}: the grant served its purpose. */
    void onCommit(RegionKey key, Object region) {
        var p = this.pending.get(key);
        if (p != null && p.region == region) this.pending.remove(key);
    }

    private void vanished(Pending p) {
        this.vanished.incrementAndGet();
    }

    int followed() {
        return this.pending.size();
    }

    /** Session end: the old world's grants are not followed into the next. */
    void clearPending() {
        this.pending.clear();
    }
}
