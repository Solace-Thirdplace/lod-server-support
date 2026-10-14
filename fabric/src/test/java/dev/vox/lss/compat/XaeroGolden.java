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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Assumptions;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * The real Xaero's World Map jars (fabric/build.gradle {@code xaeroGolden*}): the line's pinned
 * build on both loaders and its floor build. Two views of one jar — a classfile INDEX across the
 * jar and its nested jars (xaerolib is nested in the World Map jar on every 26.x line), for the
 * binding-surface check, and a CHILD-FIRST class loader (the {@link JourneyMapGolden} pattern)
 * for the loadPixel parity test: Xaero's own packages come from the jar and shadow the Tier-1
 * stubs of the same names; everything else — Minecraft above all — resolves through the test
 * class loader.
 */
final class XaeroGolden {
    private static final String[] CHILD_FIRST = {"xaero."};

    private XaeroGolden() {}

    /** The resolved jar, or a skipped test when offline; a FAILURE under CI=true (a mistyped
     *  coordinate must not silently void the golden arm). */
    static Path jar(String property) {
        String jarPath = System.getProperty(property, "");
        boolean present = !jarPath.isBlank() && Files.isRegularFile(Path.of(jarPath));
        if ("true".equals(System.getenv("CI"))) {
            assertTrue(present, "CI must resolve " + property + " (gradle.properties xaero_golden_*)");
        }
        Assumptions.assumeTrue(present, property + " not resolved (offline?) — skipping the real-Xaero check");
        return Path.of(jarPath);
    }

    /** The jar plus its nested jars, extracted into {@code scratch}. */
    static List<Path> withNested(Path jar, Path scratch) throws IOException {
        var out = new ArrayList<Path>();
        out.add(jar);
        try (var zip = new ZipFile(jar.toFile())) {
            var nested = zip.stream()
                    .filter(e -> (e.getName().startsWith("META-INF/jars/") || e.getName().startsWith("META-INF/jarjar/"))
                            && e.getName().endsWith(".jar"))
                    .toList();
            for (var entry : nested) {
                Path target = scratch.resolve(Path.of(entry.getName()).getFileName().toString());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                out.add(target);
            }
        }
        return out;
    }

    static URLClassLoader loader(Path jar, Path scratch) throws IOException {
        var urls = new ArrayList<URL>();
        for (var path : withNested(jar, scratch)) urls.add(path.toUri().toURL());
        return new ChildFirst(urls.toArray(URL[]::new), XaeroGolden.class.getClassLoader());
    }

    /** Classfile index over a jar and its nested jars (code skipped — members only). */
    static final class ClassIndex implements AutoCloseable {
        private final List<ZipFile> zips = new ArrayList<>();
        private final Map<String, ClassNode> nodes = new HashMap<>();

        ClassIndex(Path jar, Path scratch) throws IOException {
            for (var path : withNested(jar, scratch)) this.zips.add(new ZipFile(path.toFile()));
        }

        /** The class by internal name, or null when no jar of this build declares it. */
        ClassNode read(String internalName) throws IOException {
            if (this.nodes.containsKey(internalName)) return this.nodes.get(internalName);
            ClassNode node = null;
            for (var zip : this.zips) {
                var entry = zip.getEntry(internalName + ".class");
                if (entry == null) continue;
                try (var in = zip.getInputStream(entry)) {
                    node = new ClassNode();
                    new ClassReader(in).accept(node, ClassReader.SKIP_CODE);
                }
                break;
            }
            this.nodes.put(internalName, node);
            return node;
        }

        @Override
        public void close() throws IOException {
            for (var zip : this.zips) zip.close();
        }
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
