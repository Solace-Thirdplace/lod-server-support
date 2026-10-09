package dev.vox.lss.common.dh;

/** Bounded admission observations. Waits describe local gates, never a terrain-delivery ETA. */
public record DhAcquisition(Gate primary, long gates, long waitNanos) {
    // Ordinals identify local diagnostic mask bits. Append new codes; never reorder existing codes.
    public enum Gate {
        STOPPED, CHANNEL, CONFIG, WORLD, FAMILY, VOXY, NO_PLAYER, RECOVERY,
        INGEST_PRESSURE, DECODE_PRESSURE, CACHE, REQUEST_SLOTS, GENERATION_SLOT,
        SYNC_SLOTS, SERVER_DISABLED, SERVER_RANGE, PARKED, DEFERRED,
        REFUSAL_BACKOFF, SUPPORT_STARTUP, MANUAL_RATE, SERVER_RATE, LOCAL_PACING,
        SCAN_CADENCE, IN_FLIGHT, NO_ELIGIBLE_WORK, REQUESTED
    }
    public static final DhAcquisition OPEN = new DhAcquisition(Gate.REQUESTED, 0, 0);
    public static DhAcquisition waiting(Gate gate) { return waiting(gate, Long.MAX_VALUE); }
    public static DhAcquisition waiting(Gate gate, long nanos) {
        return new DhAcquisition(gate, 1L << gate.ordinal(), nanos);
    }
    /** For simultaneously blocking gates: unknown readiness dominates known timer bounds. */
    public DhAcquisition and(DhAcquisition other) {
        if (gates == 0) return other;
        if (other.gates == 0) return this;
        long mask = gates | other.gates;
        Gate owner=waitNanos>other.waitNanos?primary:waitNanos<other.waitNanos?other.primary
                :primary.ordinal()<=other.primary.ordinal()?primary:other.primary;
        return new DhAcquisition(owner, mask, Math.max(waitNanos, other.waitNanos));
    }
    /** Milliseconds remaining at snapshot time. -1 means admission depends on unknown readiness. */
    public long waitMillis(long elapsedNanos) {
        if (waitNanos == Long.MAX_VALUE) return -1;
        long remaining = Math.max(0, waitNanos - Math.max(0, elapsedNanos));
        return remaining / 1_000_000L + (remaining % 1_000_000L == 0 ? 0 : 1);
    }
}
