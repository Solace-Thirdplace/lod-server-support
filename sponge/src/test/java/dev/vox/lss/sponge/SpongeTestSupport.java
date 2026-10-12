package dev.vox.lss.sponge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Paths and line data the Sponge contract tests read (wired by sponge/build.gradle). */
final class SpongeTestSupport {
    private SpongeTestSupport() {}

    static Path repoRoot() {
        return Path.of(System.getProperty("lss.sponge.repoRoot"));
    }

    static Path classesDir() {
        return Path.of(System.getProperty("lss.sponge.classesDir"));
    }

    static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** KEY=VALUE files (line.env, gradle.properties): comments/blank lines skipped. */
    static Map<String, String> keyValues(Path path) {
        var out = new LinkedHashMap<String, String>();
        for (String line : read(path).split("\n")) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) continue;
            int i = t.indexOf('=');
            out.put(t.substring(0, i).strip(), t.substring(i + 1).strip());
        }
        return out;
    }

    static Map<String, String> lineEnv() {
        return keyValues(repoRoot().resolve(".github/line.env"));
    }

    static Map<String, String> gradleProperties() {
        return keyValues(repoRoot().resolve("gradle.properties"));
    }
}
