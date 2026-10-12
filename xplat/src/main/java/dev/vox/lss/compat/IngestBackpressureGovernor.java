package dev.vox.lss.compat;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.networking.client.LodRequestManager;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * The map-ingestion backpressure report core (hybrid-scan-plan.md §12/§12.8/§12.9), as a
 * reusable class for map backends after the Xaero bridge (map-ingestion plan §3.1). The Xaero
 * bridge keeps its own, fixture-pinned copy in {@code XaeroSession}; a differential test
 * replays traces through both, so the two never drift apart silently.
 *
 * <p>The report scales a bounded queue's occupancy onto {@code [0, HALT]}, full at
 * {@value #HALT_OCCUPANCY}. It has no signal (-1) when the switch is off, the backend is
 * inactive, the pump went stale for {@value #PUMP_STALE_MILLIS} ms, or the writer is WEDGED:
 * a full report whose occupancy has not receded by {@value #PROGRESS_EPS} from its in-window
 * peak for {@value #HALT_WEDGE_MILLIS} ms (widened to {@value #PACE_HOLD_MILLIS} ms while the
 * backend declares a pacing hold). A wedge re-arms once occupancy falls below
 * {@value #REARM_OCCUPANCY} or after {@value #REARM_MILLIS} ms: a map may pace the fill,
 * never stop it. A pacing hold that ENDS opens a fresh window: the time it spent holding was
 * pacing, never evidence of a stuck writer, so it must not age into a wedge on the first
 * report after the release. A HEAP hold (map-ingestion plan §4.3) reports the full halt:
 * map writers stop allocating then, and an occupancy-scaled report would keep filling their
 * queues with exactly the memory the belt is trying to keep free. Main client thread only,
 * except {@link #wedged}, which the decode thread reads.
 */
final class IngestBackpressureGovernor {
    static final double HALT_OCCUPANCY = 0.75;
    static final long PUMP_STALE_MILLIS = 1000;
    static final long HALT_WEDGE_MILLIS = 7000;
    static final long PACE_HOLD_MILLIS = 60_000;
    static final double REARM_OCCUPANCY = 0.5;
    static final long REARM_MILLIS = 10_000;
    static final double PROGRESS_EPS = 0.05;

    private final String label;
    LongSupplier clock;
    long lastPumpMillis;
    long haltSinceMillis;
    double haltPeakOccupancy;
    /** The previous report's pacing hold: its end re-bases the halt window. */
    boolean lastPaceHold;
    /** Volatile: the decode thread reads it to decide whether an overflow drop is reported. */
    volatile boolean wedged;
    long wedgeSinceMillis;
    long lastWedgeWarnMillis;

    IngestBackpressureGovernor(String label, LongSupplier clock) {
        this.label = label;
        this.clock = clock;
    }

    long now() {
        return this.clock.getAsLong();
    }

    /** The pump ran (the staleness watchdog). */
    void notePump() {
        this.lastPumpMillis = now();
    }

    /** Session end: every latch and window re-arms. */
    void reset() {
        this.haltSinceMillis = 0;
        this.haltPeakOccupancy = 0;
        this.lastPaceHold = false;
        this.wedged = false;
        this.wedgeSinceMillis = 0;
    }

    /**
     * @param enabled   the composed backpressure switch
     * @param active    the backend can drain at all (alive, enabled, in a session)
     * @param occupancy the bounded queue's fill fraction, max of count and bytes
     * @param paceHold  the backend is deliberately holding (a mapping settle, a window) —
     *                  pacing, not a stuck writer
     */
    int report(boolean enabled, boolean active, double occupancy, boolean paceHold) {
        return report(enabled, active, occupancy, paceHold, false);
    }

    /** @param heapHold the heap belt holds every write: a pacing hold that reports the full halt */
    int report(boolean enabled, boolean active, double occupancy, boolean paceHold, boolean heapHold) {
        boolean hold = paceHold || heapHold;
        boolean released = this.lastPaceHold && !hold;
        this.lastPaceHold = hold;
        if (heapHold) occupancy = Math.max(occupancy, HALT_OCCUPANCY);
        if (!enabled || !active) return noSignal();
        long now = now();
        if (this.wedged) {
            if (occupancy < REARM_OCCUPANCY || now - this.wedgeSinceMillis > REARM_MILLIS) {
                this.wedged = false;
            } else {
                return noSignal();
            }
        }
        if (now - this.lastPumpMillis > PUMP_STALE_MILLIS) return noSignal();
        int halt = LodRequestManager.INGEST_BACKLOG_HALT_SECTIONS;
        int report = (int) Math.round(halt * Math.min(1.0, occupancy / HALT_OCCUPANCY));
        if (report < halt) {
            this.haltSinceMillis = 0;
            return report;
        }
        long window = hold ? PACE_HOLD_MILLIS : HALT_WEDGE_MILLIS;
        if (this.haltSinceMillis == 0 || released) {
            this.haltSinceMillis = now;
            this.haltPeakOccupancy = occupancy;
        } else if (occupancy < this.haltPeakOccupancy - PROGRESS_EPS) {
            this.haltSinceMillis = now;
            this.haltPeakOccupancy = occupancy;
        } else if (occupancy > this.haltPeakOccupancy) {
            this.haltPeakOccupancy = occupancy;
        } else if (now - this.haltSinceMillis > window) {
            this.wedged = true;
            this.wedgeSinceMillis = now;
            this.haltSinceMillis = 0;
            if (now - this.lastWedgeWarnMillis > 60_000) {
                this.lastWedgeWarnMillis = now;
                LSSLogger.warn(this.label + ": the map writer made no progress through a " + (window / 1000)
                        + " s backpressure halt — releasing the LOD fill for " + (REARM_MILLIS / 1000)
                        + " s (map tiles may be dropped until the writer recovers)");
            }
            return -1;
        }
        return report;
    }

    /** The {@code bp=} diag token; the check order mirrors {@link #report}. */
    String token(boolean enabled, boolean active, double occupancy) {
        if (!enabled) return "off";
        if (!active) return "-1(inactive)";
        if (this.wedged) return "-1(wedged)";
        if (now() - this.lastPumpMillis > PUMP_STALE_MILLIS) return "-1(stale)";
        return String.format(Locale.ROOT, "%.2f", occupancy);
    }

    private int noSignal() {
        this.haltSinceMillis = 0;
        return -1;
    }
}
