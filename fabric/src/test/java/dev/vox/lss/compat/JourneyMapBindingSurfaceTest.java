package dev.vox.lss.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

/**
 * The bindings' surface (derived from the handles resolved against the Tier-1 stubs), and this
 * line's REAL JourneyMap jars classified (1.21.10: the unsupported beta — see below). Mainline:
 * every JourneyMap member the bridge binds exists in the REAL JourneyMap jars of the line,
 * both loaders, with the exact descriptor and static-ness the bindings request (map-ingestion
 * plan §6.1). The expected surface is DERIVED from {@link JourneyMapBindings} itself — each
 * handle resolved against the Tier-1 stubs is cracked back to its member — so the bindings,
 * the stubs and the real jar cannot drift apart without a red.
 *
 * <p>Classfile level on purpose: the real classes cannot be LINKED in a plain test JVM
 * (JourneyMap subclasses {@code NativeImage}, which only its access widener makes
 * extendable), and resolving them through LSS's own lookup would record loader constraints
 * that poison every stub-backed test in the same JVM.
 */
class JourneyMapBindingSurfaceTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    /** One bound member, as the bindings request it. */
    record Bound(String handle, String owner, String name, String descriptor, boolean isStatic, boolean field) {}

    /** The bindings' surface, cracked from handles resolved against the stubs. */
    static List<Bound> boundSurface() throws Exception {
        var h = JourneyMapBindings.resolve(Class::forName);
        var out = new ArrayList<Bound>();
        for (Field f : JourneyMapBindings.class.getDeclaredFields()) {
            if (f.getType() != MethodHandle.class) continue;
            f.setAccessible(true);
            var handle = (MethodHandle) f.get(h);
            if (handle == null) continue; // an optional member absent from the stubs
            Member member = MethodHandles.reflectAs(Member.class, handle);
            String owner = member.getDeclaringClass().getName().replace('.', '/');
            boolean isStatic = Modifier.isStatic(member.getModifiers());
            if (member instanceof Method m) {
                out.add(new Bound(f.getName(), owner, m.getName(), Type.getMethodDescriptor(m), isStatic, false));
            } else if (member instanceof Constructor<?> c) {
                out.add(new Bound(f.getName(), owner, "<init>", Type.getConstructorDescriptor(c), false, false));
            } else if (member instanceof Field field) {
                out.add(new Bound(f.getName(), owner, field.getName(), Type.getDescriptor(field.getType()), isStatic, true));
            }
        }
        return out;
    }

    @Test
    void theDerivedSurfaceCoversEveryBinding() throws Exception {
        var surface = boundSurface();
        long handles = java.util.Arrays.stream(JourneyMapBindings.class.getDeclaredFields())
                .filter(f -> f.getType() == MethodHandle.class).count();
        assertEquals(handles, surface.size(), "every handle cracks back to a member (the stubs carry the optional ones)");
        assertTrue(surface.stream().allMatch(b -> b.owner().startsWith("journeymap/")));
    }

    // LINE FLAVOR (1.21.10): only JourneyMap 6.0.0-beta.58 exists for this version, and it
    // predates the snapshot layer the bindings need, so map ingestion classifies it
    // unsupported (map-ingestion plan D5). These arms pin that classification against the
    // real jars: the snapshot classes are absent, the client class is present. The
    // classification itself is JourneyMapSessionTest.aJourneyMapWithoutTheSnapshotLayerIsUnsupported.
    @Test
    void theFabricBuildIsTheUnsupportedBeta() throws Exception {
        assertUnsupported(JourneyMapGolden.jar("lss.journeymapGoldenFabricJar"));
    }

    @Test
    void theNeoForgeBuildIsTheUnsupportedBeta() throws Exception {
        assertUnsupported(JourneyMapGolden.jar("lss.journeymapGoldenNeoForgeJar"));
    }

    private static void assertUnsupported(Path jar) throws IOException {
        try (var zip = new ZipFile(jar.toFile())) {
            assertNotNull(zip.getEntry("journeymap/client/JourneymapClient.class"), "a JourneyMap jar");
            assertTrue(zip.getEntry("journeymap/client/model/chunk/SnapshotChunkMD.class") == null,
                    "SnapshotChunkMD present: this line's JourneyMap gained the snapshot layer — "
                            + "re-evaluate support (bindings, suggests, lang, README)");
        }
    }
}
