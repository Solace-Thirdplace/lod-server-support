package dev.vox.lss;

import dev.vox.lss.testutil.SourcePaths;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source wiring pins for the server map-ingestion request (map-policy plan A2/A3/A4 —
 * the {@code LoaderPermissionSeamContractTest} family): the behavior suites pin each
 * piece, but a production site left on a flag-less constructor, a call site left on a
 * narrower {@code HandshakeGate.evaluate} overload, a missing cadence call, or a map
 * backend that forgot the write gate all ship GREEN — the flags silently vanish from
 * one push, or the policy silently stops applying on one path. So, in source:
 * <ul>
 *   <li>the SITE CENSUS: every production SessionConfig construction/send passes the
 *       session's capabilities and reads the policy at send time — no flag-less
 *       constructor anywhere in production (A2's table, incl. the dimension-change
 *       push that fires on every Nether trip at stock settings);</li>
 *   <li>both production handshake cores use the widest evaluate form and branch on
 *       {@code MAP_POLICY_REFUSED} explicitly (a new outcome would otherwise fall into
 *       a generic {@code ==} branch silently), real vs quiet by the capability bit;</li>
 *   <li>the 200-tick cadence and the revoke-before-repush reload order on both
 *       services;</li>
 *   <li>every map backend composes the server's write gate on exactly its write paths,
 *       never into {@code enabled};</li>
 *   <li>both client handshake sends use the one shared capability mask.</li>
 * </ul>
 */
class MapPolicyWiringContractTest {

    private static String source(String repoPath) throws IOException {
        return Files.readString(SourcePaths.repoFile(repoPath));
    }

    private static String normalized(String repoPath) throws IOException {
        return source(repoPath).replaceAll("\\s+", " ");
    }

    private static int occurrences(String text, String needle) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /** The text of every call starting at {@code needle}, up to its balanced close paren. */
    private static List<String> calls(String text, String needle) {
        var out = new ArrayList<String>();
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            int depth = 0, end = idx;
            for (int i = text.indexOf('(', idx); i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '(') depth++;
                else if (c == ')' && --depth == 0) { end = i; break; }
            }
            out.add(text.substring(idx, end + 1));
            idx = end + 1;
        }
        return out;
    }

    /** A method's body text (balanced braces from the first '{' after the signature). */
    private static String body(String text, String signature) {
        int start = text.indexOf(signature);
        assertTrue(start >= 0, "missing " + signature);
        int open = text.indexOf('{', start), depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return text.substring(open, i + 1);
        }
        throw new AssertionError("unbalanced " + signature);
    }

    private static final String GLUE = "xplat/src/main/java/dev/vox/lss/networking/server/ServerReceiverGlue.java";
    private static final String RPS = "xplat/src/main/java/dev/vox/lss/networking/server/RequestProcessingService.java";
    private static final String PAPER_PLUGIN = "paper/src/main/java/dev/vox/lss/paper/LSSPaperPlugin.java";
    private static final String PRPS = "paper/src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java";
    private static final String PAPER_HANDLER = "paper/src/main/java/dev/vox/lss/paper/PaperPayloadHandler.java";
    private static final String PAYLOAD = "SessionConfigS2CPayload.java";

    // ---- the site census (A2) ----

    @Test
    void noProductionCodeUsesAFlagLessSessionConfigConstructor() throws IOException {
        for (String tree : new String[]{"xplat/src/main/java", "fabric/src/main/java",
                "neoforge/src/main/java", "sponge/src/main/java"}) {
            Path root = SourcePaths.repoFile(tree);
            try (var walk = Files.walk(root)) {
                for (var f : walk.filter(x -> x.toString().endsWith(".java")).toList()) {
                    if (f.getFileName().toString().equals(PAYLOAD)) continue; // its own factories + decode
                    assertFalse(Files.readString(f).contains("new SessionConfigS2CPayload("),
                            f + " builds a SessionConfig without the session's capabilities — "
                                    + "production sends go through forSession (or v16Legacy)");
                }
            }
        }
    }

    @Test
    void everyXplatSendPassesTheSessionCapabilitiesAndReadsThePolicyAtSendTime() throws IOException {
        String glue = source(GLUE);
        var replies = calls(glue, "SessionConfigS2CPayload.forSession(");
        assertEquals(1, replies.size(), "the handshake reply (v16 takes v16Legacy)");
        assertTrue(replies.getFirst().contains("payload.capabilities(), mapPolicyArmed)"), replies.getFirst());
        assertEquals(1, occurrences(glue, "SessionConfigS2CPayload.v16Legacy("));

        String rps = source(RPS);
        var pushes = calls(rps, "SessionConfigS2CPayload.forSession(");
        assertEquals(4, pushes.size(),
                "repush, permission revocation, map-policy revocation, dimension change: " + pushes);
        for (String push : pushes) {
            assertTrue(push.contains("getCapabilities(), config.requestNoMapIngestion())")
                            || push.contains("capabilities, config.requestNoMapIngestion())")
                            || push.contains("capabilities, true)"),
                    "every push passes the session's capabilities and the policy: " + push);
        }
        assertTrue(source(RPS).contains("capabilities, config.requestNoMapIngestion()));"),
                "the dimension-change push (every Nether trip at stock settings) carries the flags");
    }

    @Test
    void everyPaperSendPassesTheSessionCapabilities() throws IOException {
        String handler = source(PAPER_HANDLER);
        assertEquals(1, occurrences(handler, "public static void sendSessionConfig("),
                "only the capability-carrying send exists on Paper");
        var sends = calls(source(PAPER_PLUGIN), "PaperPayloadHandler.sendSessionConfig(");
        assertEquals(1, sends.size());
        assertTrue(sends.getFirst().contains("capabilities, this.lssConfig.requestNoMapIngestion())"),
                "the handshake reply reads the policy at SEND time (the deferred drain): " + sends);
        String prps = source(PRPS);
        var lambda = calls(prps, "PaperPayloadHandler.sendSessionConfig(");
        assertEquals(1, lambda.size());
        assertTrue(lambda.getFirst().contains("capabilities, cfg.requestNoMapIngestion())"), lambda.getFirst());
        var senderCalls = calls(prps, "this.sessionConfigSender.send(");
        assertEquals(4, senderCalls.size(),
                "repush, permission revocation, map-policy revocation, dimension change: " + senderCalls);
        for (String call : senderCalls) {
            assertTrue(call.contains("getCapabilities())") || call.contains("capabilities)"),
                    "every Paper push passes the session's capabilities: " + call);
        }
        assertTrue(source(PAPER_PLUGIN).contains("config.enableChunkGeneration(),")
                        && source(PAPER_PLUGIN).contains("handshake.capabilities());"),
                "the deferred handshake reply hands the handshake's capabilities to the sender");
    }

    // ---- the handshake rung (A3.2) ----

    @Test
    void bothProductionCoresUseTheWidestGateAndBranchOnTheRefusalExplicitly() throws IOException {
        for (String path : new String[]{GLUE, PAPER_PLUGIN}) {
            String src = normalized(path);
            assertTrue(src.contains("boolean mapPolicyArmed = config.requestNoMapIngestion();"),
                    path + " reads the policy once for the rung and the reply");
            assertTrue(src.contains("ViaProbe.isMismatch(viaProtocol, nativeProtocol), mapPolicyArmed);"),
                    path + " must ride the widest (9-arg) evaluate form");
            assertTrue(src.contains("decision.outcome() == HandshakeGate.Outcome.MAP_POLICY_REFUSED"),
                    path + " must branch on the new outcome explicitly");
            assertTrue(src.contains("& LSSConstants.CAPABILITY_MAP_POLICY) == 0;")
                            && src.contains("onMapPolicyRefused("),
                    path + ": real vs quiet refusal keys on the capability bit");
        }
        String glue = normalized(GLUE);
        int reply = glue.indexOf("responder.send(");
        int branch = glue.indexOf("decision.outcome() == HandshakeGate.Outcome.MAP_POLICY_REFUSED");
        assertTrue(reply >= 0 && reply < branch, "the refusal's side effects follow its reply");
        assertTrue(glue.contains("serviceGateFor(player, service, true)"),
                "the grant sweep's replay rides a gate that never sends the chat line");
        assertTrue(source(RPS).contains("ServerReceiverGlue.replayHandshake("),
                "the xplat grant sweep replays through the no-chat entry");
        assertTrue(source(PAPER_PLUGIN).contains("service == null || replay ? null"),
                "Paper's replay never sends the chat line either");
    }

    // ---- live changes (A3.4) ----

    @Test
    void bothServicesRunTheSweepOnTheCadenceAndRevokeBeforeTheRepush() throws IOException {
        String rps = normalized(RPS);
        int sweep = rps.indexOf("runMapPolicySweep(config); runServiceGateSweeps(config);");
        assertTrue(sweep >= 0 && rps.lastIndexOf("if (++this.permissionRecheckCounter >= PERMISSION_RECHECK_TICKS)", sweep) >= 0,
                "the 200-tick cadence runs the idempotent enforcement");
        String prps = normalized(PRPS);
        assertTrue(prps.contains("runMapPolicySweep(); runServiceGateSweeps();"),
                "Paper's twin cadence (after the lifecycle drain)");
        for (String src : new String[]{rps, prps}) {
            int revoke = src.indexOf("if (mapPolicyChanged) settingsMapPolicyRevoked = runMapPolicySweep(");
            int repush = src.indexOf("repushSessionConfig", revoke);
            assertTrue(revoke >= 0 && revoke < repush,
                    "revoke FIRST: a session about to lose service must not get an enabled=true rebuild");
            assertTrue(src.contains("adoptedMapPolicy != mapPolicy"),
                    "the trigger has its own adoption mark (the service compare never sees privacy.*)");
            assertTrue(src.contains("if (!mapPolicy) replayDeniedHandshakes("),
                    "a disarm heals refused players at once");
        }
    }

    // ---- the client write gate (A4) ----

    @Test
    void everyMapBackendComposesTheServerWriteGateOnItsWritePathsOnly() throws IOException {
        String xaero = source("xplat/src/main/java/dev/vox/lss/compat/XaeroSession.java");
        String jm = source("xplat/src/main/java/dev/vox/lss/compat/JourneyMapSession.java");
        for (String src : new String[]{xaero, jm}) {
            assertTrue(src.contains("dev.vox.lss.networking.client.ClientNetGlue::serverAllowsMapIngestion"),
                    "production init wires the connection's flag");
            assertTrue(src.contains("() -> LSSClientConfig.CONFIG.mapIngestionEnabled(),"),
                    "the LOCAL enabled supplier stays the setting alone (it drives registration)");
        }
        for (String method : new String[]{"void offerColumn(", "int reportBackpressure() {", "void pump() {"}) {
            assertTrue(body(xaero, method).contains("this.serverAllowsWrites.getAsBoolean()"),
                    "Xaero " + method + " composes the server gate");
        }
        for (String method : new String[]{"void offerColumn(", "void offerEncoded(Entry entry) {",
                "int reportBackpressure() {", "void pump() {"}) {
            assertTrue(body(jm, method).contains("this.serverAllowsWrites.getAsBoolean()"),
                    "JourneyMap " + method + " composes the server gate");
        }
        assertFalse(body(xaero, "void settleSessionEnd() {").contains("serverAllowsWrites"),
                "registration settling keys on the local setting only");
        assertFalse(body(xaero, "void settleOfflineRegistration() {").contains("serverAllowsWrites"));
    }

    @Test
    void bothClientHandshakeSendsUseTheSharedCapabilityMask() throws IOException {
        String glue = source("xplat/src/main/java/dev/vox/lss/networking/client/ClientNetGlue.java");
        var sends = calls(glue, "new HandshakeC2SPayload(");
        assertEquals(2, sends.size(), "the join/ladder announce and the LAN-host self-handshake");
        for (String send : sends) {
            assertTrue(send.contains("handshakeCapabilities()"), send);
        }
        assertTrue(glue.contains("dev.vox.lss.compat.ModCompat.isMapOnlyIngestion());"),
                "the session gate learns whether a map mod is the only consumer");
    }
}
