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
 * 26.1-LINE contract for {@code fabric.mod.json} + {@code gradle.properties}, read from
 * the SOURCE tree (the fabric mirror of paper's
 * {@code PluginYmlContractTest.apiVersionMatchesTheDevBundleMinecraftVersion} lockstep pin).
 * The regression vector: the LOWER Minecraft bound is enforced by fabric-loader in every
 * gametest launch, but the UPPER bound is caught by nothing at build time — and the mixins
 * here are required:true over MC internals, so an unbounded range turns a foreign line into
 * a hard mixin-apply crash instead of Loader's clean refusal (the v0.8.0 compat review's
 * MAJOR). On THIS line the upper bound additionally excludes 26.2, whose
 * publishServer overload split makes the two lines' LAN-hook mixin descriptors mutually
 * incompatible. Main and each support branch carry their own flavors of this test.
 */
class FabricModJsonContractTest {

    // ---- the line's expected constants (each branch carries its own values) ----
    // The trailing '-' makes the upper bound prerelease-EXCLUSIVE: Fabric semver sorts
    // 26.2-rc below 26.2, so a bare '<26.2' would admit 26.2 prereleases — where this
    // line's required mixins over MC internals hard-crash at apply (the compat-review
    // rationale that added the same guard to the 26.2 line's '<26.3-' bound).
    private static final String EXPECTED_MINECRAFT_DEPENDS = ">=26.1 <26.2-";
    private static final String EXPECTED_MINECRAFT_VERSION_PREFIX = "26.1";

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
