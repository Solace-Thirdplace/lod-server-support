package dev.vox.lss;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the far-plane extension hook (far-plane plan #364 §5, §7).
 *
 * <p>LINE-FACT(26.x): {@code Camera.update(DeltaTracker)} writes {@code depthFar} exactly once
 * and only then builds the culling projection and the perspective from it. The hook injects
 * after that write, so an MC bump that moves the computation (or the readers before it) turns
 * this red instead of {@code require = 0} silently unhooking the option. The mixin source is
 * read as text (the {@code SaveHookContractTest} idiom: mixin-package classes refuse direct
 * classloading under the mixin bootstrap).
 */
class FarPlaneHookContractTest {

    private static final String CAMERA = "net/minecraft/client/Camera";
    /** One {@code @Inject(...)} body, tolerant of one nested paren level (the {@code @At}). */
    private static final Pattern INJECT =
            Pattern.compile("@Inject\\(((?:[^()]|\\([^()]*\\))*)\\)", Pattern.DOTALL);

    private static Path moduleFile(String relative) {
        Path moduleRelative = Path.of(relative);
        return Files.exists(moduleRelative) ? moduleRelative : Path.of("fabric").resolve(moduleRelative);
    }

    @Test
    void cameraUpdateWritesDepthFarOnceBeforeCullingAndProjection() throws Exception {
        ClassNode camera = new ClassNode();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CAMERA + ".class")) {
            assertNotNull(in, "Camera.class on the test classpath");
            new ClassReader(in).accept(camera, 0);
        }
        MethodNode update = camera.methods.stream()
                .filter(m -> m.name.equals("update") && m.desc.equals("(Lnet/minecraft/client/DeltaTracker;)V"))
                .findFirst().orElseThrow(() -> new AssertionError("Camera.update(DeltaTracker) is gone"));
        List<AbstractInsnNode> insns = List.of(update.instructions.toArray());
        int put = -1, puts = 0, culling = -1, perspective = -1;
        for (int i = 0; i < insns.size(); i++) {
            AbstractInsnNode insn = insns.get(i);
            if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD
                    && f.owner.equals(CAMERA) && f.name.equals("depthFar") && f.desc.equals("F")) {
                puts++;
                put = i;
            } else if (insn instanceof MethodInsnNode m && m.owner.equals(CAMERA)) {
                if (m.name.equals("createProjectionMatrixForCulling") && culling < 0) culling = i;
                if (m.name.equals("setupPerspective") && perspective < 0) perspective = i;
            }
        }
        assertEquals(1, puts, "update writes depthFar exactly once (the hook injects after it)");
        assertTrue(culling > put, "the culling projection is built after the write, so it sees the extension");
        assertTrue(perspective > put, "the perspective is set up after the write, so it sees the extension");
    }

    @Test
    void theHookIsOptionalAndTargetsTheFieldWrite() throws Exception {
        String source = Files.readString(moduleFile("src/main/java/dev/vox/lss/mixin/FarPlaneHook.java"));
        assertTrue(source.contains("@Mixin(Camera.class)"));
        var inject = INJECT.matcher(source);
        assertTrue(inject.find(), "the mixin declares an @Inject");
        String body = inject.group(1);
        assertTrue(!inject.find(), "exactly one @Inject");
        assertTrue(body.contains("method = \"update(Lnet/minecraft/client/DeltaTracker;)V\""));
        assertTrue(Pattern.compile("value\\s*=\\s*\"FIELD\"[^)]*target\\s*=\\s*\"Lnet/minecraft/client/Camera;depthFar:F\"",
                Pattern.DOTALL).matcher(body).find(), "the injection point is the depthFar field write");
        assertTrue(body.contains("opcode = org.objectweb.asm.Opcodes.PUTFIELD") && body.contains("shift = At.Shift.AFTER"));
        assertTrue(Pattern.compile("require\\s*=\\s*0").matcher(body).find(),
                "require = 0: an optional visual must never crash the game when another mod rewrites update");
        String config = Files.readString(moduleFile("src/main/resources/lss.mixins.json"));
        var client = Pattern.compile("\"client\"\\s*:\\s*\\[([^]]*)]").matcher(config);
        assertTrue(client.find() && client.group(1).contains("\"FarPlaneHook\""),
                "FarPlaneHook is a client mixin (a dedicated server has no Camera)");
    }
}
