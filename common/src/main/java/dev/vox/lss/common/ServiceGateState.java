package dev.vox.lss.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-service bookkeeping for the service gate (service-permission-gate-plan.md
 * §2.3) — one instance per request-processing service, dying with it (the C1-9
 * server-stop shape falls out of ownership). Three structures, deliberately
 * SEPARATE:
 *
 * <ul>
 *   <li><b>The denied-handshake memo</b> — {@code uuid → (protocolVersion,
 *       capabilities, playerName)}, written under exactly the
 *       {@code deniedByServiceGate} conjunction and at revocation-unregister time.
 *       It is what the grant sweep replays (a re-offer through the FULL production
 *       ladder, never a cached decision). A successful registration by any path
 *       removes the entry; disconnect sweeps it; capability staleness is accepted
 *       (any real re-handshake overwrites through the normal path).</li>
 *   <li><b>The denial-log latch</b> — once per denied EPISODE, not map-presence
 *       (§8 O1-m6: revocation repopulates the memo, and each revoke→grant→revoke
 *       transition must log once — so registration clears the latch too).</li>
 *   <li><b>The revocation streaks</b> — consecutive failing recheck sweeps per
 *       registered player (the two-sweep hysteresis; context-scoped grants
 *       oscillate). Reset by any passing sweep, registration, or disconnect.</li>
 * </ul>
 *
 * <p>{@code permissionDenied} counts TRANSITIONS (a memo entry newly created), never
 * re-handshakes or sweep passes while already denied — the {@code
 * service.permission_denied} exporter/diag field.
 *
 * <p><b>Map policy</b> (map-policy plan A3.4): the memo is shared with the map-ingestion
 * policy's refusals, each entry carrying a REASON MASK ({@link #REASON_PERMISSION},
 * {@link #REASON_MAP_POLICY}). The grant sweep clears an entry only when EVERY reason it
 * holds has cleared — without that, a disarmed permission gate's "trivially clears
 * everyone" would replay a policy-refused player every sweep for as long as the policy
 * is armed. Each reason counts its own transitions ({@code permission_denied} /
 * {@code map_policy_refused}), and the policy's once-per-connection chat line has its own
 * latch (a registration clears the denial-log latch, so it cannot share it).
 *
 * <p>All structures are concurrent: handshakes may run on region threads (Folia)
 * while the pump sweeps.
 */
public final class ServiceGateState {

    /** Memo reason: the service permission gate denied the handshake. */
    public static final int REASON_PERMISSION = 1;
    /** Memo reason: the map-ingestion policy refused the handshake (map-policy plan A3.4). */
    public static final int REASON_MAP_POLICY = 2;

    /** One remembered denied handshake — what the grant sweep re-offers — and the reasons
     *  (a mask of {@code REASON_*}) that must ALL clear before it is replayed. */
    public record DeniedHandshake(int protocolVersion, int capabilities, String playerName,
                                  int reasons) {
        /** A permission-gate denial (the shape every pre-map-policy caller deposits). */
        public DeniedHandshake(int protocolVersion, int capabilities, String playerName) {
            this(protocolVersion, capabilities, playerName, REASON_PERMISSION);
        }

        public boolean has(int reason) {
            return (this.reasons & reason) != 0;
        }
    }

    private final Map<UUID, DeniedHandshake> deniedHandshakes = new ConcurrentHashMap<>();
    private final Set<UUID> denialLogged = ConcurrentHashMap.newKeySet();
    private final Set<UUID> mapPolicyNoticed = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> revocationStreaks = new ConcurrentHashMap<>();
    private final AtomicLong permissionDenied = new AtomicLong();
    private final AtomicLong mapPolicyRefused = new AtomicLong();

    /** The once-per-episode log claim — see {@link PlayerServiceGate#claimDenialLog}. */
    public boolean claimDenialLog(UUID uuid) {
        return this.denialLogged.add(uuid);
    }

    /**
     * Deposits the memo entry (overwriting — the latest denied handshake is the one
     * worth replaying) and counts the denial transition iff the player was not
     * already remembered as denied.
     */
    public void rememberDenied(UUID uuid, String playerName, int protocolVersion,
                               int capabilities) {
        rememberDenied(uuid, playerName, protocolVersion, capabilities, REASON_PERMISSION);
    }

    /**
     * Deposits (or merges into) the memo entry for {@code reason}: the version and
     * capabilities are overwritten (the latest handshake is the one worth replaying), the
     * reason mask is OR-ed, and the reason's counter counts a transition iff the entry did
     * not already hold that reason.
     *
     * @return whether this is a TRANSITION for {@code reason} (the call sites log their
     *         once-per-transition INFO line exactly then)
     */
    public boolean rememberDenied(UUID uuid, String playerName, int protocolVersion,
                                  int capabilities, int reason) {
        var previous = new DeniedHandshake[1];
        this.deniedHandshakes.compute(uuid, (k, old) -> {
            previous[0] = old;
            int reasons = (old == null ? 0 : old.reasons()) | reason;
            return new DeniedHandshake(protocolVersion, capabilities, playerName, reasons);
        });
        boolean transition = previous[0] == null || !previous[0].has(reason);
        if (transition) {
            if ((reason & REASON_PERMISSION) != 0) this.permissionDenied.incrementAndGet();
            if ((reason & REASON_MAP_POLICY) != 0) this.mapPolicyRefused.incrementAndGet();
        }
        return transition;
    }

    /** The map policy's once-per-CONNECTION chat-line claim (map-policy plan A3.3): the
     *  first call for a connection returns true, every later one false until disconnect.
     *  Deliberately NOT cleared by {@link #onRegistered} (that re-arms the denial-log
     *  latch, which would re-send the line on every arm/disarm cycle). */
    public boolean claimMapPolicyNotice(UUID uuid) {
        return this.mapPolicyNoticed.add(uuid);
    }

    /** A successful HANDSHAKE registration: the episode is over — memo entry gone
     *  (nothing to re-offer), log latch re-armed. Called from the handshake register
     *  paths ONLY (the shared glue's register branch; Paper's Register drain), never
     *  from registerPlayer itself — that is also the dimension-change reuse path, and
     *  clearing there raced a region-thread memo deposit against its own queued
     *  unregister composite (Folia review 2026-08-27 R3). The revocation streak
     *  deliberately SURVIVES even here (#244 §9): a genuinely passing player's streak
     *  dies at its next passing sweep anyway. */
    public void onRegistered(UUID uuid) {
        this.deniedHandshakes.remove(uuid);
        this.denialLogged.remove(uuid);
    }

    /** The connection died: session-scoped state goes with it (the rejoin handshake
     *  re-runs the gate from scratch and gets its log line again). */
    public void onDisconnect(UUID uuid) {
        this.deniedHandshakes.remove(uuid);
        this.denialLogged.remove(uuid);
        this.mapPolicyNoticed.remove(uuid);
        this.revocationStreaks.remove(uuid);
    }

    /** Server stop / plugin disable: everything session-scoped clears. */
    public void clear() {
        this.deniedHandshakes.clear();
        this.denialLogged.clear();
        this.mapPolicyNoticed.clear();
        this.revocationStreaks.clear();
    }

    /** Whether the memo remembers {@code uuid} as denied. */
    public boolean isDenied(UUID uuid) {
        return this.deniedHandshakes.containsKey(uuid);
    }

    /** Read-only look at {@code uuid}'s memo entry (tests/diagnostics), or null. */
    public DeniedHandshake peekDenied(UUID uuid) {
        return this.deniedHandshakes.get(uuid);
    }

    /** Removes and returns {@code uuid}'s memo entry (the grant sweep's take), or
     *  null. The caller replays it through the production handshake ladder; a replay
     *  that lands anywhere but REGISTER must NOT redeposit here (the ladder's own
     *  denial hook does that when — and only when — the gate is still what denies). */
    public DeniedHandshake takeDenied(UUID uuid) {
        return this.deniedHandshakes.remove(uuid);
    }

    /** Snapshot of the remembered UUIDs (the grant sweep's iteration set). */
    public List<UUID> deniedSnapshot() {
        return new ArrayList<>(this.deniedHandshakes.keySet());
    }

    /** The memo's current size (every reason). */
    public int deniedCount() {
        return this.deniedHandshakes.size();
    }

    /** Memo entries holding {@code reason} — the {@code Gate:} line's {@code denied=}
     *  ({@link #REASON_PERMISSION}) and the {@code MapPolicy:} line's {@code refused=}
     *  ({@link #REASON_MAP_POLICY}), so neither diag line mixes in the other's entries. */
    public int deniedCount(int reason) {
        int n = 0;
        for (var entry : this.deniedHandshakes.values()) {
            if (entry.has(reason)) n++;
        }
        return n;
    }

    /** Whether any memo entry exists — the grant sweep's cheap idle guard. */
    public boolean hasDenied() {
        return !this.deniedHandshakes.isEmpty();
    }

    /** Denial transitions since service start — {@code service.permission_denied}. */
    public long permissionDeniedTotal() {
        return this.permissionDenied.get();
    }

    /** Map-policy refusal transitions since service start —
     *  {@code service.map_policy_refused} (map-policy plan A3.5). */
    public long mapPolicyRefusedTotal() {
        return this.mapPolicyRefused.get();
    }

    /**
     * Bumps {@code uuid}'s consecutive-failing-sweep streak and reports whether it
     * reached the two-sweep hysteresis threshold (plan §2.3 — the caller revokes
     * exactly when this returns true; the streak resets so a third failing sweep
     * does not re-revoke an already-revoked player).
     */
    public boolean bumpRevocationStreak(UUID uuid) {
        int streak = this.revocationStreaks.merge(uuid, 1, Integer::sum);
        if (streak >= 2) {
            this.revocationStreaks.remove(uuid);
            return true;
        }
        return false;
    }

    /** A passing sweep: the streak dies (hysteresis is CONSECUTIVE failures). */
    public void resetRevocationStreak(UUID uuid) {
        this.revocationStreaks.remove(uuid);
    }

    /** The gate was DISARMED: every streak dies — otherwise a disarm/re-arm cycle
     *  carries a stale streak=1 and revokes on the first failing sweep instead of the
     *  pinned two (implementation review, 2026-08-27). */
    public void clearRevocationStreaks() {
        this.revocationStreaks.clear();
    }

}
