package dev.vox.lss.sponge;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The ToolchainContractTest twin for the Sponge module (sponge-support-plan.md §5.1):
 * the compiled class-file major follows line.env's LINE_JAVA_VERSION (the one per-line
 * Java datum sponge/build.gradle reads), and the line's Sponge Minecraft version is the
 * build's own minecraft_version (no second property can drift).
 */
class SpongeToolchainContractTest {

    @Test
    void classFileMajorFollowsTheLineJava() throws IOException {
        int lineJava = Integer.parseInt(SpongeTestSupport.lineEnv().get("LINE_JAVA_VERSION"));
        var cls = SpongeTestSupport.classesDir().resolve("dev/vox/lss/sponge/LSSSpongePlugin.class");
        try (var in = new DataInputStream(Files.newInputStream(cls))) {
            assertEquals(0xCAFEBABE, in.readInt());
            in.readUnsignedShort();
            assertEquals(lineJava + 44, in.readUnsignedShort(), "class-file major vs LINE_JAVA_VERSION");
        }
    }

    @Test
    void spongeMinecraftVersionIsTheBuildsMinecraftVersion() {
        String mc = SpongeTestSupport.gradleProperties().get("minecraft_version");
        var env = SpongeTestSupport.lineEnv();
        assertNotNull(env.get("LINE_MC_SPONGE"), "line.env must carry LINE_MC_SPONGE");
        assertEquals(mc, env.get("LINE_MC_SPONGE"));
        assertEquals(mc, env.get("LINE_GAME_VERSIONS_SPONGE"), "Sponge builds are exact-version");
        String sv = SpongeTestSupport.gradleProperties().get("spongevanilla_version");
        assertEquals(mc + "-", sv.substring(0, mc.length() + 1), "the pinned SpongeVanilla build targets " + mc);
    }
}
