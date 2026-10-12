package dev.vox.lss.sponge;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three Sponge hook targets against THIS line's Minecraft classes
 * (sponge-support-plan.md §1.4) — a renamed, re-signatured or reordered target reds the
 * build instead of the boot. Line-neutral: the targets are read from the compiled mixin
 * annotations and resolved against the Minecraft jar on the test classpath, so this file
 * is identical on every line.
 */
class SpongeMixinTargetContractTest {

    private static ClassNode node(byte[] bytes) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static ClassNode minecraftClass(String internalName) throws IOException {
        try (InputStream in = SpongeMixinTargetContractTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            assertNotNull(in, "Minecraft class not on the test classpath: " + internalName);
            return node(in.readAllBytes());
        }
    }

    private static ClassNode mixin(String simpleName) throws IOException {
        return node(Files.readAllBytes(SpongeTestSupport.classesDir()
                .resolve("dev/vox/lss/mixin/" + simpleName + ".class")));
    }

    /** The {@code @Mixin} target and the single {@code @Inject}'s method selector. */
    private record Target(String owner, String selector) {}

    private static Target target(ClassNode mixin) {
        String owner = null;
        for (AnnotationNode a : mixin.invisibleAnnotations == null ? List.<AnnotationNode>of() : mixin.invisibleAnnotations) {
            if (a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) {
                @SuppressWarnings("unchecked") var values = (List<Type>) a.values.get(a.values.indexOf("value") + 1);
                owner = values.get(0).getInternalName();
            }
        }
        String selector = null;
        for (MethodNode m : mixin.methods) {
            var annotations = new ArrayList<AnnotationNode>();
            if (m.visibleAnnotations != null) annotations.addAll(m.visibleAnnotations);
            if (m.invisibleAnnotations != null) annotations.addAll(m.invisibleAnnotations);
            for (AnnotationNode a : annotations) {
                if (a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) {
                    @SuppressWarnings("unchecked") var methods = (List<String>) a.values.get(a.values.indexOf("method") + 1);
                    selector = methods.get(0);
                }
            }
        }
        assertNotNull(owner, mixin.name + ": no @Mixin target");
        assertNotNull(selector, mixin.name + ": no @Inject selector");
        return new Target(owner, selector);
    }

    private static List<MethodNode> resolve(ClassNode owner, String selector) {
        int paren = selector.indexOf('(');
        String name = paren < 0 ? selector : selector.substring(0, paren);
        String desc = paren < 0 ? null : selector.substring(paren);
        return owner.methods.stream()
                // Mixin's "*" matches every name: the descriptor alone must be unique.
                .filter(m -> name.equals("*") || m.name.equals(name))
                .filter(m -> desc == null || m.desc.equals(desc))
                .toList();
    }

    @Test
    void chunkLoadHookSelectsExactlyTheFullStatusLambda() throws IOException {
        var t = target(mixin("ChunkLoadHook"));
        var owner = minecraftClass(t.owner());
        var matches = resolve(owner, t.selector());
        assertEquals(1, matches.size(), "the descriptor must select exactly one ChunkStatusTasks method; got "
                + matches.stream().map(m -> m.name + m.desc).toList());
        var lambda = matches.get(0);
        assertTrue(lambda.name.startsWith("lambda$full$"), "selected " + lambda.name + ", not a full() lambda");
        boolean buildsLevelChunk = false;
        for (var insn : lambda.instructions) {
            if (insn instanceof TypeInsnNode type && insn.getOpcode() == Opcodes.NEW
                    && type.desc.equals("net/minecraft/world/level/chunk/LevelChunk")) {
                buildsLevelChunk = true;
            }
        }
        assertTrue(buildsLevelChunk, lambda.name + " no longer constructs the LevelChunk (the FULL promotion)");
    }

    @Test
    void serverTickHookTargetsTickServer() throws IOException {
        var t = target(mixin("ServerTickHook"));
        assertEquals("net/minecraft/server/MinecraftServer", t.owner());
        var matches = resolve(minecraftClass(t.owner()), t.selector()).stream()
                .filter(m -> m.desc.equals("(Ljava/util/function/BooleanSupplier;)V")).toList();
        assertEquals(1, matches.size(), "tickServer(BooleanSupplier)V must exist exactly once");
    }

    @Test
    void chunkSaveDataHookTargetExists() throws IOException {
        var t = target(mixin("ChunkSaveDataHook"));
        var matches = resolve(minecraftClass(t.owner()), t.selector());
        assertFalse(matches.isEmpty(), t.owner() + "." + t.selector() + " not found on this line");
    }
}
