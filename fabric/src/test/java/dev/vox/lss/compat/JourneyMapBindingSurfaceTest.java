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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

/**
 * Every JourneyMap member the bridge binds exists in the REAL JourneyMap jars of this line,
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

    @Test
    void theFabricBuildHasEveryBoundMember() throws Exception {
        assertSurface(JourneyMapGolden.jar("lss.journeymapGoldenFabricJar"));
    }

    @Test
    void theNeoForgeBuildHasEveryBoundMember() throws Exception {
        assertSurface(JourneyMapGolden.jar("lss.journeymapGoldenNeoForgeJar"));
    }

    private static void assertSurface(Path jar) throws Exception {
        var missing = new ArrayList<String>();
        try (var zip = new ZipFile(jar.toFile())) {
            Map<String, ClassNode> nodes = new HashMap<>();
            assertNotNull(read(zip, nodes, "journeymap/client/model/chunk/SnapshotChunkMD"),
                    "this line's JourneyMap has the snapshot layer (otherwise: state=unsupported)");
            for (var bound : boundSurface()) {
                if (!has(zip, nodes, bound.owner(), bound)) missing.add(bound.toString());
            }
        }
        assertTrue(missing.isEmpty(), "absent from " + jar.getFileName() + ":\n" + String.join("\n", missing));
    }

    /** The member on the owner or (for an instance member) a superclass inside the jar. */
    private static boolean has(ZipFile zip, Map<String, ClassNode> nodes, String owner, Bound b) throws IOException {
        for (String cls = owner; cls != null && cls.startsWith("journeymap/"); ) {
            ClassNode node = read(zip, nodes, cls);
            if (node == null) return false;
            if (b.field()) {
                for (var f : node.fields) {
                    if (f.name.equals(b.name()) && f.desc.equals(b.descriptor())
                            && ((f.access & Opcodes.ACC_STATIC) != 0) == b.isStatic()
                            && (f.access & Opcodes.ACC_PUBLIC) != 0) return true;
                }
            } else {
                for (var m : node.methods) {
                    if (m.name.equals(b.name()) && m.desc.equals(b.descriptor())
                            && ((m.access & Opcodes.ACC_STATIC) != 0) == b.isStatic()
                            && (m.access & Opcodes.ACC_PUBLIC) != 0) return true;
                }
            }
            if (b.isStatic() || b.name().equals("<init>")) return false;
            cls = node.superName;
        }
        return false;
    }

    private static ClassNode read(ZipFile zip, Map<String, ClassNode> nodes, String internalName) throws IOException {
        if (nodes.containsKey(internalName)) return nodes.get(internalName);
        var entry = zip.getEntry(internalName + ".class");
        ClassNode node = null;
        if (entry != null) {
            try (var in = zip.getInputStream(entry)) {
                node = new ClassNode();
                new ClassReader(in).accept(node, ClassReader.SKIP_CODE);
            }
        }
        nodes.put(internalName, node);
        return node;
    }

    @Test
    void theOptionalSuspendedProbeIsPresentOnThisLine() throws Exception {
        // LINE FLAVOR (26.2/26.3, JourneyMap 6.0.10): isMappingSuspended exists; 6.0.0 lacks it
        // and the bindings tolerate that (the 1.21.11 line pins its absence instead).
        assertTrue(boundSurface().stream().anyMatch(b -> b.name().equals("isMappingSuspended")),
                "so both jar checks above require it here");
    }
}
