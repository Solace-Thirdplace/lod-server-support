package dev.vox.lss.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Every Xaero member the map bridge binds exists in the REAL Xaero's World Map jars of this
 * line — both loaders' pinned build and the line's floor build — with the descriptor and
 * static-ness the bindings request (cave-layers plan §B7). The expected surface is the list
 * {@link XaeroBindings} RECORDS while resolving against the Tier-1 stubs (its handles are
 * {@code asType}-adapted and cannot be cracked afterwards, unlike the JourneyMap bindings), so
 * the bindings, the stubs and the real jars cannot drift apart without a red.
 *
 * <p>Classfile level, nested xaerolib included: members bound by exact type compare by
 * descriptor; members bound by NAME (the ClientLevel-typed boundaries and the xaerolib option
 * surface) compare by name and arity. A member may live on a superclass inside the jars
 * (instance members only — {@code setAllCachePrepared} is declared on {@code LeveledRegion}).
 */
class XaeroBindingSurfaceTest {
    /**
     * LINE FLAVOR (1.21.x): the Fabric jars (pinned and floor) are intermediary-mapped, so they
     * compare descriptors with Minecraft types erased (the jar names them
     * {@code net/minecraft/class_NNNN}) — Xaero-typed parts, names, arity and static-ness stay
     * exact; the NeoForge jar is Mojang-named and compares exactly.
     */
    private static final boolean FABRIC_INTERMEDIARY = true;
    private static final Pattern MINECRAFT_TYPE = Pattern.compile("Lnet/minecraft/[^;]+;");

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    static List<XaeroBindings.BoundMember> boundSurface() throws Exception {
        return XaeroBindings.resolve(Class::forName).boundMembers();
    }

    @Test
    void theRecordedSurfaceCoversEveryBinding() throws Exception {
        var h = XaeroBindings.resolve(Class::forName);
        int handles = 0;
        for (Field f : XaeroBindings.class.getDeclaredFields()) {
            f.setAccessible(true);
            if (f.getType() == MethodHandle.class) {
                if (f.get(h) != null) handles++;
            } else if (f.getType().isRecord()) {
                Object group = f.get(h);
                assertNotNull(group, f.getName() + " binds against the stubs");
                for (RecordComponent c : f.getType().getRecordComponents()) {
                    if (c.getType() == MethodHandle.class) handles++;
                }
            }
        }
        var surface = h.boundMembers();
        // +1: the interpretation version is a reflective static read, not a handle.
        assertEquals(handles + 1, surface.size(), "every handle records exactly one member");
        assertTrue(surface.stream().allMatch(b -> b.owner().startsWith("xaero/")), surface.toString());
        assertTrue(surface.stream().anyMatch(b -> b.group().equals("cave-layers")
                        && b.name().equals("isIgnoreHeightmaps")),
                "the cave-layer group is part of the checked surface");
    }

    @Test
    void theFabricBuildHasEveryBoundMember(@TempDir Path scratch) throws Exception {
        assertSurface(XaeroGolden.jar("lss.xaeroGoldenFabricJar"), scratch, Set.of(), FABRIC_INTERMEDIARY);
    }

    @Test
    void theNeoForgeBuildHasEveryBoundMember(@TempDir Path scratch) throws Exception {
        assertSurface(XaeroGolden.jar("lss.xaeroGoldenNeoForgeJar"), scratch, Set.of(), false);
    }

    /** The oldest Xaero build of this line: every group binds there too (no optional group
     *  may be missing on this line — list it here, with a reason, if a floor ever lacks one). */
    @Test
    void theLinesFloorBuildHasEveryBoundMember(@TempDir Path scratch) throws Exception {
        assertSurface(XaeroGolden.jar("lss.xaeroGoldenFloorFabricJar"), scratch, Set.of(), FABRIC_INTERMEDIARY);
    }

    /** The checker is not vacuous: a wrong descriptor, a wrong arity, a wrong static-ness and
     *  an inherited STATIC member all miss; an inherited instance member is found. */
    @Test
    void theCheckerRejectsDriftedMembers(@TempDir Path scratch) throws Exception {
        try (var index = new XaeroGolden.ClassIndex(XaeroGolden.jar("lss.xaeroGoldenFabricJar"), scratch)) {
            String region = "xaero/map/region/MapRegion";
            boolean erase = FABRIC_INTERMEDIARY;
            assertTrue(has(index, member(region, "setAllCachePrepared", "(Z)V", false, false, 1), erase),
                    "found on LeveledRegion through the superclass walk");
            assertTrue(!has(index, member(region, "setAllCachePrepared", "(I)V", false, false, 1), erase), "descriptor");
            assertTrue(!has(index, member(region, "setAllCachePrepared", "(Z)V", true, false, 1), erase), "static-ness");
            assertTrue(!has(index, member(region, "setAllCachePrepared", "", false, true, 2), erase), "arity (by scan)");
            assertTrue(!has(index, member("xaero/map/MapProcessor", "noSuchMember", "()V", false, false, 0), erase), "name");
        }
    }

    private static XaeroBindings.BoundMember member(String owner, String name, String descriptor,
                                                    boolean isStatic, boolean byScan, int arity) {
        return new XaeroBindings.BoundMember("self-check", owner, name, descriptor, isStatic, false, byScan, arity);
    }

    private static void assertSurface(Path jar, Path scratch, Set<String> allowedMissingGroups,
                                      boolean eraseMinecraft) throws Exception {
        var missing = new ArrayList<String>();
        var missingGroups = new LinkedHashSet<String>();
        try (var index = new XaeroGolden.ClassIndex(jar, scratch)) {
            assertNotNull(index.read("xaero/map/MapWriter"), jar.getFileName() + " is a World Map jar");
            assertNotNull(index.read("xaero/lib/common/config/channel/ConfigChannel"),
                    "the nested xaerolib jar is indexed too");
            for (var bound : boundSurface()) {
                if (!has(index, bound, eraseMinecraft)) {
                    missing.add(bound.toString());
                    missingGroups.add(bound.group());
                }
            }
        }
        missingGroups.removeAll(allowedMissingGroups);
        assertTrue(missingGroups.isEmpty(), "absent from " + jar.getFileName() + ":\n" + String.join("\n", missing));
    }

    /** The member on the owner or (instance members) a superclass inside the jars. */
    private static boolean has(XaeroGolden.ClassIndex index, XaeroBindings.BoundMember b,
                               boolean eraseMinecraft) throws IOException {
        for (String cls = b.owner(); cls != null; ) {
            var node = index.read(cls);
            if (node == null) return false; // left the jars (java/lang/Object, Minecraft)
            if (b.field()) {
                for (var f : node.fields) {
                    if (f.name.equals(b.name()) && (b.byScan() || sameDescriptor(f.desc, b.descriptor(), eraseMinecraft))
                            && ((f.access & Opcodes.ACC_STATIC) != 0) == b.isStatic()
                            && (f.access & Opcodes.ACC_PUBLIC) != 0) return true;
                }
            } else {
                for (var m : node.methods) {
                    if (!m.name.equals(b.name())) continue;
                    boolean shape = b.byScan()
                            ? Type.getArgumentTypes(m.desc).length == b.arity()
                            : sameDescriptor(m.desc, b.descriptor(), eraseMinecraft);
                    if (shape && ((m.access & Opcodes.ACC_STATIC) != 0) == b.isStatic()
                            && (m.access & Opcodes.ACC_PUBLIC) != 0
                            && (m.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) == 0) return true;
                }
            }
            if (b.isStatic() || b.name().equals("<init>")) return false;
            cls = node.superName;
        }
        return false;
    }

    private static boolean sameDescriptor(String real, String bound, boolean eraseMinecraft) {
        if (!eraseMinecraft) return real.equals(bound);
        return MINECRAFT_TYPE.matcher(real).replaceAll("L*;").equals(MINECRAFT_TYPE.matcher(bound).replaceAll("L*;"));
    }
}
