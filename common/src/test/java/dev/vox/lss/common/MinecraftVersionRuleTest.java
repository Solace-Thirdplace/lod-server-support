package dev.vox.lss.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Paper version guard's rule (paper-version-guard-store-plan §3.1/§4): the line's
 * {@code minecraft_dependency}, read the way Fabric reads it. Every recorded line form is
 * covered: the 26.x ranges (patch builds accepted, the next minor and its pre-releases
 * refused) and the exact 1.21.x pins (no neighbouring 1.21.x accepted — decision U1).
 */
class MinecraftVersionRuleTest {

    @ParameterizedTest(name = "{1} against ''{0}'' -> {2}")
    @CsvSource({
            // 26.3 line (main)
            "'>=26.3 <26.4-', 26.3, true",
            "'>=26.3 <26.4-', 26.3.1, true",
            "'>=26.3 <26.4-', 26.3.0, true",
            "'>=26.3 <26.4-', 26.4, false",
            "'>=26.3 <26.4-', 26.4-pre1, false",
            "'>=26.3 <26.4-', 26.4.1, false",
            "'>=26.3 <26.4-', 26.2, false",
            "'>=26.3 <26.4-', 26.3-rc1, false",
            "'>=26.3 <26.4-', 1.21.11, false",
            // 26.2 line
            "'>=26.2 <26.3-', 26.2, true",
            "'>=26.2 <26.3-', 26.3, false",
            // 26.1 line (built for 26.1.2, the family accepts every 26.1 patch)
            "'>=26.1 <26.2-', 26.1, true",
            "'>=26.1 <26.2-', 26.1.1, true",
            "'>=26.1 <26.2-', 26.1.2, true",
            "'>=26.1 <26.2-', 26.2, false",
            "'>=26.1 <26.2-', 26.0.9, false",
            // 1.21.x lines: exact
            "1.21.11, 1.21.11, true",
            "1.21.11, 1.21.10, false",
            "1.21.11, 1.21.12, false",
            "1.21.10, 1.21.10, true",
            "1.21.10, 1.21.1, false",
            "1.21.1, 1.21.1, true",
            "1.21.1, 1.21.10, false",
            "1.21.1, 1.21.1-pre1, false",
    })
    void recordedLineForms(String rule, String running, boolean expected) {
        assertEquals(expected, MinecraftVersionRule.parse(rule).accepts(running));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "unknown", "26.x", "-26.3", "26..3"})
    void unparseableRunningVersionIsRefused(String running) {
        assertEquals(false, MinecraftVersionRule.parse(">=26.3 <26.4-").accepts(running));
        assertEquals(false, MinecraftVersionRule.parse(">=26.3 <26.4-").accepts(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ">=", ">=26.x", "26.3 <", "abc"})
    void malformedRulesThrow(String rule) {
        assertThrows(IllegalArgumentException.class, () -> MinecraftVersionRule.parse(rule));
    }

    @Test
    void describeReadsLikeASentence() {
        assertEquals("26.3 up to (not including) 26.4",
                MinecraftVersionRule.parse(">=26.3 <26.4-").describe());
        assertEquals("1.21.10", MinecraftVersionRule.parse("1.21.10").describe());
        assertEquals(">=26.3 <26.4-", MinecraftVersionRule.parse(">=26.3 <26.4-").raw());
    }

    /** The line's own pair: the jar's build version must satisfy the rule it ships with,
     *  or the guard would refuse the server it was built for. */
    @Test
    void thisLinesBuildVersionSatisfiesItsOwnRule() throws Exception {
        var props = new java.util.Properties();
        Path gradleProps = locate("gradle.properties");
        try (var in = Files.newInputStream(gradleProps)) {
            props.load(in);
        }
        String rule = props.getProperty("minecraft_dependency");
        String mc = props.getProperty("minecraft_version");
        assertTrue(MinecraftVersionRule.parse(rule).accepts(mc),
                "minecraft_dependency '" + rule + "' must accept minecraft_version " + mc);
    }

    static Path locate(String repoRelative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(repoRelative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("cannot locate " + repoRelative);
    }
}
