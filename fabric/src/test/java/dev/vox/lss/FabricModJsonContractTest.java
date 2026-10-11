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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.21.10-LINE contract for {@code fabric.mod.json} + {@code gradle.properties}, read from
 * the SOURCE tree (the fabric mirror of paper's
 * {@code PluginYmlContractTest.apiVersionMatchesTheDevBundleMinecraftVersion} lockstep pin).
 * The regression vector: the LOWER Minecraft bound is enforced by fabric-loader in every
 * gametest launch, but the UPPER bound is caught by nothing at build time — and the mixins
 * here are required:true over MC internals, so an unbounded pin turns a foreign line into
 * a hard mixin-apply crash instead of Loader's clean refusal (the v0.8.0 compat review's
 * MAJOR). On THIS line the pin is the EXACT version "1.21.10" (the old support branch's
 * per-line choice, kept): the LAN-hook descriptor, the save-hook target, and the
 * one-count-short section layout are all 1.21.10-specific, so neither a newer nor an
 * older 1.21.x is safe. Main and each support branch carry their own flavors of this test.
 */
class FabricModJsonContractTest {

    // ---- the line's expected constants (each branch carries its own values) ----
    // Exact-version pin (no range): see the class doc — every neighboring 1.21.x differs
    // in at least one required-mixin surface, so the jar declares exactly this version.
    private static final String EXPECTED_MINECRAFT_DEPENDS = "1.21.10";
    private static final String EXPECTED_MINECRAFT_VERSION_PREFIX = "1.21.10";

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
    void doesNotSuggestJourneyMapOnThisLine() {
        // LINE FLAVOR (1.21.10): only JourneyMap 6.0.0-beta.58 exists for this version and it
        // lacks the snapshot layer map ingestion binds (state=unsupported), so suggesting it
        // would point players at a map LSS cannot fill (map-ingestion plan D5).
        assertFalse(modJson.getAsJsonObject("suggests").has("journeymap"),
                "suggests.journeymap must stay absent on 1.21.10");
    }

    @Test
    void gradlePropertiesTargetsTheSameLine() {
        String mc = gradleProps.getProperty("minecraft_version", "");
        // equals-or-dot: a bare prefix match would also accept e.g. 1.21.110, which the
        // exact depends pin above would exclude — the two must move in lockstep.
        assertTrue(mc.equals(EXPECTED_MINECRAFT_VERSION_PREFIX)
                        || mc.startsWith(EXPECTED_MINECRAFT_VERSION_PREFIX + "."),
                "gradle.properties minecraft_version (" + mc + ") must stay on the "
                        + EXPECTED_MINECRAFT_VERSION_PREFIX + " line");
    }
}
