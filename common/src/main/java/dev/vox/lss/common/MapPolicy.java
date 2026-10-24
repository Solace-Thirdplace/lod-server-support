package dev.vox.lss.common;

/**
 * Shared operator/player text for the server map-ingestion policy
 * ({@code privacy.request_no_map_ingestion}, map-policy plan A3) — one home so the
 * Fabric/NeoForge/Sponge glue and the Paper plugin cannot drift apart. Pure: no
 * Minecraft or loader types.
 */
public final class MapPolicy {
    private MapPolicy() {}

    /** The once-per-connection chat line to a refused LSS client (plan A3.3, approved
     *  text, user decision 2026-10-13). Literal English, like the other
     *  server-originated text; only LSS clients ever see it (vanilla clients never
     *  handshake). */
    public static String refusalNotice() {
        return "Distant terrain on this server needs " + Brand.displayName()
                + " 0.19.0 or newer. Update the mod to see it.";
    }

    /** The once-per-transition INFO line for a handshake refusal (plan A3.5). */
    public static String refusalLogLine(String playerName, int protocolVersion, int capabilities) {
        return "LOD unavailable for " + playerName + ": privacy.request_no_map_ingestion is on"
                + " and this client (protocol v" + protocolVersion + ", capabilities="
                + capabilities + ") cannot honor it — it needs " + Brand.shortName()
                + " 0.19.0 or newer; the client was told " + Brand.shortName()
                + " is disabled";
    }

    /** The once-per-transition INFO line for the arm sweep's mid-session revocation. */
    public static String revocationLogLine(String playerName, int protocolVersion, int capabilities) {
        return "LOD service revoked for " + playerName + ": privacy.request_no_map_ingestion"
                + " was turned on and this client (protocol v" + protocolVersion
                + ", capabilities=" + capabilities + ") cannot honor it — it needs "
                + Brand.shortName() + " 0.19.0 or newer; it is re-offered automatically if"
                + " the request is turned off";
    }

    /**
     * The per-player decision (map-ingestion permission plan §3.2) — the ONE place the
     * server-wide request and a player's exemption combine, used at every flag-carrying
     * send and policy decision: the request applies to this player iff it is on and the
     * player is not exempt. The exemption is read only while the request is on (a
     * disarmed policy costs no permission read). {@link #deny} is what the flags and the
     * handshake rung take; {@link #exempt} is what an older client's admission records.
     */
    public record Decision(boolean deny, boolean exempt) {
        /** The policy is off: nothing is denied and nobody is exempt. */
        public static final Decision OFF = new Decision(false, false);
    }

    /** @param policyOn {@code privacy.request_no_map_ingestion}
     *  @param exempt   the player's FAIL-CLOSED exemption read (consulted only while on) */
    public static Decision decide(boolean policyOn, java.util.function.BooleanSupplier exempt) {
        if (!policyOn) return Decision.OFF;
        boolean isExempt = exempt.getAsBoolean();
        return new Decision(!isExempt, isExempt);
    }

    /**
     * Whether a player holds the exemption, through a loader's permission read: EITHER
     * brand spelling (the grant model, like the far-player hide nodes). FAIL-CLOSED: the
     * reader must answer {@code false} for an unset node or a missing provider, and a
     * throwing reader answers not exempt here — unlike every service-gate read, a doubt
     * must never lift the request for anyone.
     */
    public static boolean holdsExemption(java.util.function.Predicate<String> holds) {
        try {
            return holds.test(LSSPermissions.MAP_INGESTION_LSS)
                    || holds.test(LSSPermissions.MAP_INGESTION_VSS);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable e) {
            return false;
        }
    }

    /** The {@code /lsslod diag} line while the policy is armed, or null while it is off
     *  (the line is omitted, so default-install diag output is byte-unchanged). Separate
     *  from the {@code Gate:} line, which renders only while
     *  {@code requireServicePermission} is on and whose {@code denied=} counts permission
     *  denials only. {@code refused} is the number of players currently refused;
     *  {@code exempt} the sessions the last enforcement sweep found exempt (cached there —
     *  the command reads no permission); {@code provider} the loader's permission-backend
     *  token, or null where the line carries none (Paper: always Bukkit). */
    public static String diagLineOrNull(boolean armed, int refused, int exempt, String provider) {
        if (!armed) return null;
        return "MapPolicy: on refused=" + refused + " exempt=" + exempt
                + (provider == null ? "" : " provider=" + provider);
    }
}
