package dev.vox.lss.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

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
        assertNull(h.optionalMissing, "every optional group binds against the stubs");
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

    /**
     * The parity oracle runs the NeoForge build's {@code MapWriter} on this line; the Fabric
     * build and the line's floor build must run the SAME algorithm — the per-pixel scan, its
     * helper, the chunk writer that feeds them and the section-based height — instruction for
     * instruction (labels by order, debug info and frames ignored). LINE FLAVOR (1.21.x): the
     * Fabric jars are intermediary-mapped, so every build is compared with Minecraft references
     * erased (class names, and member names on Minecraft owners); Xaero's own references,
     * opcodes, locals, constants and control flow stay exact.
     */
    @Test
    void theWriterAlgorithmIsTheSameInEveryGoldenBuild(@TempDir Path scratch) throws Exception {
        var reference = writerCode(XaeroGolden.jar("lss.xaeroGoldenNeoForgeJar"), scratch.resolve("neoforge"));
        assertEquals(4, reference.size(), reference.keySet().toString());
        for (String property : List.of("lss.xaeroGoldenFabricJar", "lss.xaeroGoldenFloorFabricJar")) {
            var other = writerCode(XaeroGolden.jar(property), scratch.resolve(property));
            assertEquals(reference.keySet(), other.keySet(), property + ": the same signatures");
            for (var e : reference.entrySet()) {
                var theirs = other.get(e.getKey());
                if (e.getValue().equals(theirs)) continue;
                int i = 0;
                while (i < Math.min(e.getValue().size(), theirs.size()) && e.getValue().get(i).equals(theirs.get(i))) i++;
                throw new AssertionError(property + ": " + e.getKey() + " differs at instruction " + i + ": "
                        + (i < e.getValue().size() ? e.getValue().get(i) : "<end>") + " vs "
                        + (i < theirs.size() ? theirs.get(i) : "<end>"));
            }
        }
    }

    private static final List<String> WRITER_METHODS =
            List.of("loadPixel", "loadPixelHelp", "writeChunk", "getSectionBasedHeight");

    /** name+descriptor → normalized instructions of the writer methods in this build. */
    private static Map<String, List<String>> writerCode(Path jar, Path scratch) throws IOException {
        java.nio.file.Files.createDirectories(scratch);
        for (Path path : XaeroGolden.withNested(jar, scratch)) {
            try (var zip = new java.util.zip.ZipFile(path.toFile())) {
                var entry = zip.getEntry("xaero/map/MapWriter.class");
                if (entry == null) continue;
                var node = new ClassNode();
                try (var in = zip.getInputStream(entry)) {
                    new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
                var out = new LinkedHashMap<String, List<String>>();
                for (MethodNode m : node.methods) {
                    if (WRITER_METHODS.contains(m.name)) out.put(m.name + eraseMinecraft(m.desc), instructions(m));
                }
                return out;
            }
        }
        throw new AssertionError("no MapWriter in " + jar);
    }

    private static List<String> instructions(MethodNode m) {
        var labels = new HashMap<LabelNode, Integer>();
        for (var insn : m.instructions) {
            if (insn instanceof LabelNode label) labels.put(label, labels.size());
        }
        var out = new ArrayList<String>();
        for (var insn : m.instructions) {
            if (insn.getOpcode() < 0) continue; // labels, line numbers, frames
            String operand = switch (insn) {
                case MethodInsnNode n -> member(n.owner, n.name) + n.desc;
                case FieldInsnNode n -> member(n.owner, n.name) + ":" + n.desc;
                case VarInsnNode n -> Integer.toString(n.var);
                case IntInsnNode n -> Integer.toString(n.operand);
                case LdcInsnNode n -> String.valueOf(n.cst);
                case TypeInsnNode n -> n.desc;
                case JumpInsnNode n -> "L" + labels.get(n.label);
                case IincInsnNode n -> n.var + " " + n.incr;
                case TableSwitchInsnNode n -> n.min + ".." + n.max + " L" + labels.get(n.dflt) + " "
                        + n.labels.stream().map(l -> "L" + labels.get(l)).toList();
                case LookupSwitchInsnNode n -> n.keys + " L" + labels.get(n.dflt) + " "
                        + n.labels.stream().map(l -> "L" + labels.get(l)).toList();
                case InvokeDynamicInsnNode n -> n.name + n.desc + " " + handle(n.bsm) + " "
                        + java.util.Arrays.stream(n.bsmArgs)
                                .map(a -> a instanceof org.objectweb.asm.Handle h ? handle(h) : String.valueOf(a))
                                .toList();
                case MultiANewArrayInsnNode n -> n.desc + " " + n.dims;
                default -> "";
            };
            out.add(insn.getOpcode() + (operand.isEmpty() ? "" : " " + eraseLambdaIndex(eraseMinecraft(operand))));
        }
        return out;
    }

    /** owner.name, the name erased on a Minecraft owner (intermediary vs Mojang member names). */
    private static String member(String owner, String name) {
        return owner + "." + (MINECRAFT_NAME.matcher(owner).matches() ? "*" : name);
    }

    private static String handle(org.objectweb.asm.Handle h) {
        return h.getTag() + " " + member(h.getOwner(), h.getName()) + h.getDesc();
    }

    /** Intermediary renames every obfuscated class to net/minecraft/class_NNNN, including the
     *  com.mojang.blaze3d/math ones. */
    private static final Pattern MINECRAFT_NAME =
            Pattern.compile("(?:net/minecraft|com/mojang/blaze3d|com/mojang/math)/[\\w/$]+");

    /** Minecraft class names collapsed to one token (intermediary vs Mojang names). */
    private static String eraseMinecraft(String text) {
        return MINECRAFT_NAME.matcher(text).replaceAll("net/minecraft/*");
    }

    private static final Pattern LAMBDA_INDEX = Pattern.compile("(lambda\\$[\\w$]*?\\$)\\d+");

    /** LINE FLAVOR (1.21.1): javac numbers synthetic lambdas per class, so a build with one more
     *  lambda earlier in MapWriter (the 1.42.0 floor here) names writeChunk's own lambda
     *  {@code lambda$writeChunk$1} instead of {@code $2} — a compiler artifact, not an algorithm
     *  change; the index is collapsed (the lambda's descriptor and captures stay exact). */
    private static String eraseLambdaIndex(String text) {
        return LAMBDA_INDEX.matcher(text).replaceAll("$1*");
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
            var ambiguous = new ArrayList<String>();
            for (var bound : boundSurface()) {
                if (!has(index, bound, eraseMinecraft)) {
                    missing.add(bound.toString());
                    missingGroups.add(bound.group());
                } else if (bound.byScan() && !bound.field() && scanMatches(index, bound) != 1) {
                    ambiguous.add(bound + " matches " + scanMatches(index, bound));
                }
            }
            // A name+arity scan takes the FIRST match: an overload of the same arity would make
            // the binding depend on reflection order.
            assertTrue(ambiguous.isEmpty(), "ambiguous by-name bindings in " + jar.getFileName() + ":\n"
                    + String.join("\n", ambiguous));
        }
        missingGroups.removeAll(allowedMissingGroups);
        assertTrue(missingGroups.isEmpty(), "absent from " + jar.getFileName() + ":\n" + String.join("\n", missing));
    }

    /** Distinct public, non-synthetic methods of that name and arity on the owner or a superclass. */
    private static int scanMatches(XaeroGolden.ClassIndex index, XaeroBindings.BoundMember b) throws IOException {
        var descriptors = new HashSet<String>();
        for (String cls = b.owner(); cls != null; ) {
            var node = index.read(cls);
            if (node == null) break;
            for (var m : node.methods) {
                if (m.name.equals(b.name()) && Type.getArgumentTypes(m.desc).length == b.arity()
                        && (m.access & Opcodes.ACC_PUBLIC) != 0
                        && (m.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) == 0) {
                    descriptors.add(Type.getMethodType(m.desc).getArgumentTypes().length + ":"
                            + java.util.Arrays.toString(Type.getArgumentTypes(m.desc)));
                }
            }
            cls = node.superName;
        }
        return descriptors.size();
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
