package dev.vox.lss.sponge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated {@code META-INF/sponge_plugins.json} and the plugin's static wiring
 * (sponge-support-plan.md §5.1). SpongeVanilla instantiates the metadata ENTRYPOINT (never
 * the {@code @Plugin} value), and rejects a version that does not start with a digit.
 */
class SpongePluginMetadataContractTest {

    private static JsonObject plugin() {
        var root = JsonParser.parseString(SpongeTestSupport.read(Path.of(System.getProperty("lss.sponge.pluginMetadata"))))
                .getAsJsonObject();
        var plugins = root.getAsJsonArray("plugins");
        assertEquals(1, plugins.size(), "exactly one plugin per jar");
        assertEquals("java_plain", root.getAsJsonObject("loader").get("name").getAsString());
        return plugins.get(0).getAsJsonObject();
    }

    @Test
    void identityVersionAndEntrypoint() {
        var p = plugin();
        assertEquals("lodserversupport", p.get("id").getAsString());
        String version = p.get("version").getAsString();
        assertEquals(SpongeTestSupport.gradleProperties().get("mod_version"), version);
        assertTrue(Character.isDigit(version.charAt(0)), "SpongeVanilla refuses non-numeric versions");
        String entrypoint = p.get("entrypoint").getAsString();
        assertEquals("dev.vox.lss.sponge.LSSSpongePlugin", entrypoint);
        assertTrue(Files.isRegularFile(SpongeTestSupport.classesDir().resolve(entrypoint.replace('.', '/') + ".class")));
    }

    /** The API dependency is the line's RANGE (RC builds report N.0.0-SNAPSHOT, which a
     *  bare release floor would refuse) — gradle.properties is the single source. */
    @Test
    void spongeApiDependencyIsTheLinesRange() {
        var deps = plugin().getAsJsonArray("dependencies");
        assertEquals(1, deps.size());
        var api = deps.get(0).getAsJsonObject();
        assertEquals("spongeapi", api.get("id").getAsString());
        assertEquals(SpongeTestSupport.gradleProperties().get("sponge_api_dependency"), api.get("version").getAsString());
        assertFalse(api.get("optional").getAsBoolean());
    }

    @Test
    void serviceLoaderFallbackNamesTheSpongeImpl() {
        String services = SpongeTestSupport.read(SpongeTestSupport.repoRoot()
                .resolve("sponge/src/main/resources/META-INF/services/dev.vox.lss.platform.LoaderServices")).strip();
        assertEquals("dev.vox.lss.platform.SpongeLoaderServices", services);
    }

    /** The VSS jar carries plugin id voxyserverside, so a lookup of our own plugin BY ID
     *  silently answers "absent" there: plugin facts must come from the injected
     *  PluginContainer (plan §1.1, review A-m8). */
    @Test
    void noSourceLooksUpThisPluginById() throws IOException {
        Path src = SpongeTestSupport.repoRoot().resolve("sponge/src/main/java");
        try (Stream<Path> s = Files.walk(src)) {
            for (Path file : s.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = SpongeTestSupport.read(file);
                assertFalse(text.contains("plugin(\"lodserversupport\")") || text.contains("plugin(\"voxyserverside\")"),
                        file + " looks up this plugin by id");
            }
        }
    }

    @Test
    void manifestNamesTheMixinConfigAndAccessWidenerResources() {
        String build = SpongeTestSupport.read(SpongeTestSupport.repoRoot().resolve("sponge/build.gradle"));
        assertTrue(build.contains("'MixinConfigs': 'lss.sponge.mixins.json'"));
        assertTrue(build.contains("'Access-Widener': 'lss-sponge.accesswidener'"));
        assertTrue(Files.isRegularFile(SpongeTestSupport.repoRoot().resolve("sponge/src/main/resources/lss.sponge.mixins.json")));
        String aw = SpongeTestSupport.read(SpongeTestSupport.repoRoot().resolve("sponge/src/main/resources/lss-sponge.accesswidener"));
        // SpongeVanilla runs on Mojang names and applies no namespace check: `named` on
        // every line (S0-proven on API 12 and API 20).
        assertTrue(aw.startsWith("accessWidener v2 named\n"));
        // Same widenings as fabric's accesswidener (body lines, header excluded).
        String fabricAw = SpongeTestSupport.read(SpongeTestSupport.repoRoot().resolve("fabric/src/main/resources/lss.accesswidener"));
        assertEquals(entries(fabricAw), entries(aw));
    }

    private static java.util.Set<String> entries(String aw) {
        var out = new java.util.TreeSet<String>();
        for (String line : aw.split("\n")) {
            String t = line.strip();
            if (t.startsWith("accessible ") || t.startsWith("mutable ") || t.startsWith("extendable ")) out.add(t);
        }
        return out;
    }
}
