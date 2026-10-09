package dev.vox.lss.common.dh;

/** Optional, session-owned join ramp expressed in DH's decimal KB/s wire units.
 * Unlike sustained congestion control, sparse generation replies never reduce the
 * startup budget. The server still enforces its independent maximum. */
public final class DhBandwidthRamp {
    public static final int OPEN_KB = 500;
    private static final int STEP_KB = 50;
    private final boolean startedSlow;
    private int limit;
    private long started, bytes;
    private boolean sampling, supportStarted;
    private long supportStartedAt;

    public DhBandwidthRamp(boolean adaptive, boolean slowStart) {
        startedSlow = adaptive && slowStart;
        limit = startedSlow ? STEP_KB : OPEN_KB;
    }

    public boolean startedSlow() { return startedSlow; }

    /** Turning either toggle off opens this session; turning it on applies next join. */
    public synchronized int limit(boolean adaptive, boolean slowStart) {
        if (!adaptive || !slowStart) limit = OPEN_KB;
        return limit;
    }

    /** DH Support 0.14 ignores the bandwidth field. Smooth its request startup
     * locally for five seconds; individual response bursts remain server-controlled. */
    public synchronized long supportRequestInterval(long minimumNanos,long now) {
        if(limit==OPEN_KB)return minimumNanos;
        if(!supportStarted) {supportStarted=true;supportStartedAt=now;}
        long age=now-supportStartedAt;
        return minimumNanos*(age<2_500_000_000L?4:age<5_000_000_000L?2:1);
    }

    /** Data-driven samples exclude idle time before the first terrain packet. */
    public synchronized void received(int payloadBytes, long now) {
        if (payloadBytes <= 0 || limit == OPEN_KB) return;
        if (!sampling) { sampling = true; started = now; }
        long elapsed = now - started;
        if (elapsed >= 1_000_000_000L) {
            // 90% tolerates packet and client-tick scheduling at the server's cap.
            if (bytes * 1_000_000_000.0 / elapsed >= limit * 900.0)
                limit = Math.min(OPEN_KB, limit + STEP_KB);
            started = now; bytes = 0;
        }
        bytes += payloadBytes;
    }
}
