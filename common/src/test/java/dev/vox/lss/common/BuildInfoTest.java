package dev.vox.lss.common;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The store's {@code build_mc} source (paper-version-guard-store-plan §3.2). */
class BuildInfoTest {

    @Test
    void minecraftVersionIsTheExpandedGradleProperty() throws Exception {
        var props = new java.util.Properties();
        try (var in = Files.newInputStream(MinecraftVersionRuleTest.locate("gradle.properties"))) {
            props.load(in);
        }
        assertEquals(props.getProperty("minecraft_version"), BuildInfo.minecraftVersion(),
                "processResources must expand gradle.properties' minecraft_version");
    }

    @Test
    void missingOrUnexpandedResourceFailsLoudly() {
        assertThrows(IllegalStateException.class, () -> BuildInfo.load(null));
        assertThrows(IllegalStateException.class, () -> BuildInfo.load(stream(
                "minecraft_version=${minecraft_version}\n")));
        assertThrows(IllegalStateException.class, () -> BuildInfo.load(stream("other=1\n")));
        assertEquals("26.3", BuildInfo.load(stream("minecraft_version=26.3\n")));
    }

    private static ByteArrayInputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.ISO_8859_1));
    }
}
