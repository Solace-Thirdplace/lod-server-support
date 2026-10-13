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
        return "Distant terrain on this server needs " + Brand.shortName()
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

    /** The {@code /lsslod diag} line while the policy is armed, or null while it is off
     *  (the line is omitted, so default-install diag output is byte-unchanged). Separate
     *  from the {@code Gate:} line, which renders only while
     *  {@code requireServicePermission} is on and whose {@code denied=} counts permission
     *  denials only. {@code refused} is the number of players currently refused. */
    public static String diagLineOrNull(boolean armed, int refused) {
        return armed ? "MapPolicy: on refused=" + refused : null;
    }
}
