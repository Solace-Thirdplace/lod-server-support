package dev.vox.lss.sponge;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The xplat server-half boundary (sponge-support-plan.md §1.2). sponge/ is the first
 * server-only consumer of the xplat source set; these guards keep the boundary honest in
 * both directions, so it cannot silently rot as xplat grows:
 * <ol>
 *   <li>no compiled Sponge class references client or other-loader code (a constant-pool
 *       scan — the FoliaWiringContractTest shape; net/minecraft/client is a belt, the
 *       compile classpath is server-only);</li>
 *   <li>every xplat source is either compiled in or matched by an exclude, and no
 *       exclude pattern is stale;</li>
 *   <li>the Sponge mixin config is exactly fabric's common {@code mixins} array plus the
 *       two Sponge hooks — a server accessor added on Fabric without its Sponge entry reds
 *       here, not at a player's first serve.</li>
 * </ol>
 */
class SpongeServerHalfContractTest {

    private static final List<String> FORBIDDEN = List.of(
            "net/minecraft/client/", "com/mojang/blaze3d/",
            "dev/vox/lss/networking/client/", "dev/vox/lss/config/menu/",
            "net/fabricmc/", "net/neoforged/");

    private static List<Path> classFiles() throws IOException {
        try (Stream<Path> s = Files.walk(SpongeTestSupport.classesDir())) {
            return s.filter(p -> p.toString().endsWith(".class")).toList();
        }
    }

    private static Set<String> referencedTypes(Path classFile) throws IOException {
        var refs = new TreeSet<String>();
        var remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                refs.add(internalName);
                return internalName;
            }
        };
        ClassVisitor sink = new ClassVisitor(Opcodes.ASM9) {};
        new ClassReader(Files.readAllBytes(classFile)).accept(new ClassRemapper(sink, remapper), 0);
        return refs;
    }

    @Test
    void noCompiledClassReferencesClientOrOtherLoaderCode() throws IOException {
        var violations = new ArrayList<String>();
        for (Path cls : classFiles()) {
            for (String ref : referencedTypes(cls)) {
                for (String bad : FORBIDDEN) {
                    if (ref.startsWith(bad)) {
                        violations.add(SpongeTestSupport.classesDir().relativize(cls) + " -> " + ref);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "server half references client/loader code:\n"
                + String.join("\n", violations));
    }

    @Test
    void everyXplatSourceIsCompiledOrExcludedAndNoExcludeIsStale() throws IOException {
        Path xplat = SpongeTestSupport.repoRoot().resolve("xplat/src/main/java");
        var patterns = List.of(System.getProperty("lss.sponge.xplatExcludes").split(","));
        var matchers = patterns.stream()
                .map(p -> FileSystems.getDefault().getPathMatcher("glob:" + p)).toList();
        List<Path> sources;
        try (Stream<Path> s = Files.walk(xplat)) {
            sources = s.filter(p -> p.toString().endsWith(".java")).map(xplat::relativize).toList();
        }
        var unmatchedPatterns = new ArrayList<String>();
        for (int i = 0; i < patterns.size(); i++) {
            var m = matchers.get(i);
            if (sources.stream().noneMatch(m::matches)) unmatchedPatterns.add(patterns.get(i));
        }
        assertTrue(unmatchedPatterns.isEmpty(), "stale xplat exclude patterns: " + unmatchedPatterns);

        var missing = new ArrayList<String>();
        for (Path src : sources) {
            if (matchers.stream().anyMatch(m -> m.matches(src))) continue;
            String cls = src.toString().replace(".java", ".class");
            if (!Files.isRegularFile(SpongeTestSupport.classesDir().resolve(cls))) missing.add(src.toString());
        }
        assertTrue(missing.isEmpty(), "xplat sources neither excluded nor compiled: " + missing);
    }

    @Test
    void mixinConfigIsFabricsCommonArrayPlusTheSpongeHooks() {
        var fabric = JsonParser.parseString(SpongeTestSupport.read(SpongeTestSupport.repoRoot()
                .resolve("fabric/src/main/resources/lss.mixins.json"))).getAsJsonObject();
        var sponge = JsonParser.parseString(SpongeTestSupport.read(SpongeTestSupport.repoRoot()
                .resolve("sponge/src/main/resources/lss.sponge.mixins.json"))).getAsJsonObject();
        var expected = new TreeSet<String>();
        fabric.getAsJsonArray("mixins").forEach(e -> expected.add(e.getAsString()));
        expected.add("ChunkLoadHook");
        expected.add("ServerTickHook");
        var actual = new TreeSet<String>();
        sponge.getAsJsonArray("mixins").forEach(e -> actual.add(e.getAsString()));
        assertEquals(expected, actual);
        // Sponge bundles Mixin 0.8.7, whose CompatibilityLevel enum ends at JAVA_21: a
        // JAVA_25 literal (fabric's on 26.x) fails to parse a REQUIRED config at boot.
        assertEquals("JAVA_21", sponge.get("compatibilityLevel").getAsString());
        assertTrue(sponge.get("required").getAsBoolean());
        assertEquals("dev.vox.lss.mixin", sponge.get("package").getAsString());
        for (String name : actual) {
            assertTrue(Files.isRegularFile(SpongeTestSupport.classesDir().resolve("dev/vox/lss/mixin/" + name + ".class")),
                    "mixin config names a class that is not compiled: " + name);
        }
    }
}
