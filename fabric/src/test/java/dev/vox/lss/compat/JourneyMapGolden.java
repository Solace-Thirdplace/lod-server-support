package dev.vox.lss.compat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Assumptions;

/**
 * The real JourneyMap jars (fabric/build.gradle {@code journeymapGolden*}) in a CHILD-FIRST
 * class loader: JourneyMap's own packages (and its nested API, networking and PNG jars) come
 * from the jar, so they shadow the Tier-1 stubs of the same names; everything else —
 * Minecraft above all — resolves through the test class loader, exactly like the bindings'
 * MethodTypes do in production.
 */
final class JourneyMapGolden {
    private static final String[] CHILD_FIRST = {"journeymap.", "commonnetwork.", "ar.com.hjg.pngj."};

    private JourneyMapGolden() {}

    /** The resolved jar, or a skipped test when offline; a FAILURE under CI=true (a mistyped
     *  coordinate must not silently void the golden arm). */
    static Path jar(String property) {
        String jarPath = System.getProperty(property, "");
        boolean present = !jarPath.isBlank() && Files.isRegularFile(Path.of(jarPath));
        if ("true".equals(System.getenv("CI"))) {
            assertTrue(present, "CI must resolve " + property + " (gradle.properties journeymap_golden_*)");
        }
        Assumptions.assumeTrue(present, property + " not resolved (offline?) — skipping the real-JourneyMap check");
        return Path.of(jarPath);
    }

    static URLClassLoader loader(Path jar, Path scratch) throws IOException {
        var urls = new ArrayList<URL>();
        urls.add(jar.toUri().toURL());
        try (var zip = new ZipFile(jar.toFile())) {
            var nested = zip.stream()
                    .filter(e -> (e.getName().startsWith("META-INF/jars/") || e.getName().startsWith("META-INF/jarjar/"))
                            && e.getName().endsWith(".jar"))
                    .toList();
            for (var entry : nested) {
                Path out = scratch.resolve(Path.of(entry.getName()).getFileName().toString());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
                urls.add(out.toUri().toURL());
            }
        }
        return new ChildFirst(urls.toArray(URL[]::new), JourneyMapGolden.class.getClassLoader());
    }

    static List<String> childFirstPrefixes() {
        return List.of(CHILD_FIRST);
    }

    private static final class ChildFirst extends URLClassLoader {
        ChildFirst(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            for (String prefix : CHILD_FIRST) {
                if (!name.startsWith(prefix)) continue;
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) c = findClass(name);
                    if (resolve) resolveClass(c);
                    return c;
                }
            }
            return super.loadClass(name, resolve);
        }
    }
}
