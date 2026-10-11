package dev.vox.lss;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.21.1-LINE contract for {@code fabric.mod.json} + {@code gradle.properties}, read from
 * the SOURCE tree (the fabric mirror of paper's
 * {@code PluginYmlContractTest.apiVersionMatchesTheDevBundleMinecraftVersion} lockstep pin).
 * The regression vector: the LOWER Minecraft bound is enforced by fabric-loader in every
 * gametest launch, but the UPPER bound is caught by nothing at build time — and the mixins
 * here are required:true over MC internals, so an unbounded pin turns a foreign line into
 * a hard mixin-apply crash instead of Loader's clean refusal (the v0.8.0 compat review's
 * MAJOR). On THIS line the pin is the EXACT version "1.21.1" (the 1.21.11 support
 * branch's per-line choice, mirrored): the LAN-hook descriptor, the save-hook target,
 * and the ticket/NBT API families are all 1.21.1-specific, so neither a newer nor an
 * older 1.21.x is safe. Main and each support branch carry their own flavors of this test.
 */
class FabricModJsonContractTest {

    // ---- the line's expected constants (each branch carries its own values) ----
    // Exact-version pin (no range): see the class doc — every neighboring 1.21.x differs
    // in at least one required-mixin surface, so the jar declares exactly this version.
    private static final String EXPECTED_MINECRAFT_DEPENDS = "1.21.1";
    private static final String EXPECTED_MINECRAFT_VERSION_PREFIX = "1.21.1";

    private static JsonObject modJson;
    private static Properties gradleProps;

    @BeforeAll
    static void load() throws Exception {
        modJson = JsonParser.parseString(
                Files.readString(locate("fabric/src/main/resources/fabric.mod.json"))).getAsJsonObject();
        gradleProps = new Properties();
        gradleProps.load(new StringReader(Files.readString(locate("gradle.properties"))));
    }

    /** Walks up from the working dir (fabric/ under Gradle, the repo root elsewhere). */
    private static Path locate(String repoRelative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(repoRelative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("cannot locate " + repoRelative + " above " + Path.of("").toAbsolutePath());
    }

    @Test
    void dependsMinecraftPinsThisLineBothWays() {
        // V-1/P3: the SOURCE resource carries the template token — the actual range is
        // per-line DATA in gradle.properties (minecraft_dependency), pinned by FORM here
        // because it is NOT derivable (a copied range template on an exact-pin line ships
        // a jar that loads on wire-incompatible MC).
        assertEquals("${minecraft_dependency}",
                modJson.getAsJsonObject("depends").get("minecraft").getAsString(),
                "fabric.mod.json's minecraft depends must stay templated from the data key");
        assertEquals(EXPECTED_MINECRAFT_DEPENDS,
                gradleProps.getProperty("minecraft_dependency", ""),
                "gradle.properties minecraft_dependency must pin the line's range exactly — "
                        + "the upper bound is what keeps this jar off incompatible newer lines");
        // The G back-flow's sibling key: the fabric-api floor is per-line data too
        // (the 26.1 port found the literal floor naming a 26.2-family version no
        // 26.1 install can satisfy — silently unresolvable at mod load).
        assertEquals("${fabric_api_dependency}",
                modJson.getAsJsonObject("depends").get("fabric-api").getAsString(),
                "fabric.mod.json's fabric-api depends must stay templated from the data key");
        assertTrue(!gradleProps.getProperty("fabric_api_dependency", "").isEmpty(),
                "gradle.properties must carry the line's fabric_api_dependency floor");
    }

    @Test
    void suggestsXaeroWorldMapAsLiteralWildcard() {
        // Deliberately a literal "*", NOT a version range: the Xaero bridge binds
        // reflectively and fails soft across Xaero versions (xaero-map-bridge-plan.md
        // §2.2) — a range here would claim a compatibility pin we don't have.
        assertEquals("*",
                modJson.getAsJsonObject("suggests").get("xaeroworldmap").getAsString(),
                "suggests.xaeroworldmap must be the literal any-version wildcard");
    }

    @Test
    void suggestsJourneyMapAsLiteralWildcard() {
        // Same rule as Xaero (map-ingestion plan §3.2): the binding probe classifies
        // unsupported/unrecognized JourneyMap builds at runtime; no range is claimed.
        assertEquals("*",
                modJson.getAsJsonObject("suggests").get("journeymap").getAsString(),
                "suggests.journeymap must be the literal any-version wildcard");
    }

    @Test
    void gradlePropertiesTargetsTheSameLine() {
        String mc = gradleProps.getProperty("minecraft_version", "");
        // equals-or-dot: a bare prefix match would also accept e.g. 26.10, which the
        // depends range above would exclude — the two must move in lockstep.
        assertTrue(mc.equals(EXPECTED_MINECRAFT_VERSION_PREFIX)
                        || mc.startsWith(EXPECTED_MINECRAFT_VERSION_PREFIX + "."),
                "gradle.properties minecraft_version (" + mc + ") must stay on the "
                        + EXPECTED_MINECRAFT_VERSION_PREFIX + " line");
    }
}
