package dev.vox.lss.neoforge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the NeoForge twin of the far-plane extension hook (far-plane plan #364 §5): the same
 * {@code GameRenderer} targets as the Fabric 1.21.1 hook (whose contract test also checks the
 * targets exist in the game's classfile), both optional, registered as a client mixin.
 */
class NeoForgeFarPlaneHookContractTest {

    private static final Pattern INJECT =
            Pattern.compile("@Inject\\(((?:[^()]|\\([^()]*\\))*)\\)", Pattern.DOTALL);

    private static Path moduleFile(String relative) {
        Path moduleRelative = Path.of(relative);
        return Files.exists(moduleRelative) ? moduleRelative : Path.of("neoforge").resolve(moduleRelative);
    }

    @Test
    void bothInjectionsAreOptionalAndRegistered() throws Exception {
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
        String config = Files.readString(moduleFile("src/main/resources/lss.neoforge.mixins.json"));
        var client = Pattern.compile("\"client\"\\s*:\\s*\\[([^]]*)]").matcher(config);
        assertTrue(client.find() && client.group(1).contains("\"FarPlaneHook\""),
                "FarPlaneHook is a client mixin (a dedicated server has no GameRenderer)");
    }
}
