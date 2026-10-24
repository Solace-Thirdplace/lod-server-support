package dev.vox.lss;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the far-plane extension hook (far-plane plan #364 §5, §7).
 *
 * <p>LINE-FACT(1.21.x): the far plane is {@code GameRenderer.getDepthFar()F}, which the
 * projection reads; the frame start is {@code renderLevel(DeltaTracker)}. An MC bump that
 * renames either turns this red instead of {@code require = 0} silently unhooking the option.
 * The mixin source is read as text (the {@code SaveHookContractTest} idiom: mixin-package classes
 * refuse direct classloading under the mixin bootstrap).
 */
class FarPlaneHookContractTest {

    private static final String GAME_RENDERER = "net/minecraft/client/renderer/GameRenderer";
    /** One {@code @Inject(...)} body, tolerant of one nested paren level (the {@code @At}). */
    private static final Pattern INJECT =
            Pattern.compile("@Inject\\(((?:[^()]|\\([^()]*\\))*)\\)", Pattern.DOTALL);

    private static Path moduleFile(String relative) {
        Path moduleRelative = Path.of(relative);
        return Files.exists(moduleRelative) ? moduleRelative : Path.of("fabric").resolve(moduleRelative);
    }

    @Test
    void gameRendererStillHasBothTargetsAndTheProjectionReadsTheFarPlane() throws Exception {
        ClassNode renderer = new ClassNode();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(GAME_RENDERER + ".class")) {
            assertNotNull(in, "GameRenderer.class on the test classpath");
            new ClassReader(in).accept(renderer, 0);
        }
        assertTrue(renderer.methods.stream().anyMatch(m -> m.name.equals("getDepthFar") && m.desc.equals("()F")),
                "GameRenderer.getDepthFar()F is the far-plane target");
        assertTrue(renderer.methods.stream().anyMatch(m -> m.name.equals("renderLevel")
                        && m.desc.equals("(Lnet/minecraft/client/DeltaTracker;)V")),
                "GameRenderer.renderLevel(DeltaTracker) is the frame-start target");
        MethodNode projection = renderer.methods.stream().filter(m -> m.name.equals("getProjectionMatrix"))
                .findFirst().orElseThrow(() -> new AssertionError("GameRenderer.getProjectionMatrix is gone"));
        boolean readsFar = false;
        for (AbstractInsnNode insn : projection.instructions.toArray()) {
            if (insn instanceof MethodInsnNode m && m.owner.equals(GAME_RENDERER) && m.name.equals("getDepthFar")) {
                readsFar = true;
            }
        }
        assertTrue(readsFar, "the projection reads getDepthFar(), so the extension reaches it");
    }

    @Test
    void bothInjectionsAreOptional() throws Exception {
        String source = Files.readString(moduleFile("src/main/java/dev/vox/lss/mixin/FarPlaneHook.java"));
        assertTrue(source.contains("@Mixin(GameRenderer.class)"));
        List<String> bodies = new ArrayList<>();
        Matcher inject = INJECT.matcher(source);
        while (inject.find()) bodies.add(inject.group(1));
        assertEquals(2, bodies.size(), "frame start + far plane");
        assertTrue(bodies.stream().anyMatch(b -> b.contains("method = \"renderLevel(Lnet/minecraft/client/DeltaTracker;)V\"")
                && b.contains("@At(\"HEAD\")")));
        assertTrue(bodies.stream().anyMatch(b -> b.contains("method = \"getDepthFar()F\"")
                && b.contains("@At(\"RETURN\")") && b.contains("cancellable = true")));
        for (String body : bodies) {
            assertTrue(Pattern.compile("require\\s*=\\s*0").matcher(body).find(),
                    "require = 0: an optional visual must never crash the game when another mod rewrites GameRenderer");
        }
        String config = Files.readString(moduleFile("src/main/resources/lss.mixins.json"));
        var client = Pattern.compile("\"client\"\\s*:\\s*\\[([^]]*)]").matcher(config);
        assertTrue(client.find() && client.group(1).contains("\"FarPlaneHook\""),
                "FarPlaneHook is a client mixin (a dedicated server has no GameRenderer)");
    }
}
