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
    void minecraftVersionRuleIsTheExpandedDependencyRule() throws Exception {
        var props = new java.util.Properties();
        try (var in = Files.newInputStream(MinecraftVersionRuleTest.locate("gradle.properties"))) {
            props.load(in);
        }
        assertEquals(props.getProperty("minecraft_dependency"), BuildInfo.minecraftVersionRule().raw(),
                "processResources must expand gradle.properties' minecraft_dependency");
        org.junit.jupiter.api.Assertions.assertTrue(
                BuildInfo.minecraftVersionRule().accepts(BuildInfo.minecraftVersion()),
                "the jar's rule accepts the version it is built for");
    }

    @Test
    void missingUnexpandedOrMalformedRuleFailsLoudly() {
        assertThrows(IllegalStateException.class, () -> BuildInfo.loadRule(null));
        assertThrows(IllegalStateException.class, () -> BuildInfo.loadRule(stream(
                "minecraft_version=26.3\nminecraft_dependency=${minecraft_dependency}\n")));
        assertThrows(IllegalStateException.class, () -> BuildInfo.loadRule(stream("minecraft_version=26.3\n")));
        assertThrows(IllegalStateException.class, () -> BuildInfo.loadRule(stream(
                "minecraft_dependency=>=x.y\n")));
        assertEquals(">=26.3 <26.4-", BuildInfo.loadRule(stream("minecraft_dependency=>=26.3 <26.4-\n")).raw());
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
