package dev.vox.lss.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Facts about the build of THIS jar (paper-version-guard-store-plan §3.2), read from
 * {@code lss-build.properties}, which {@code :common:processResources} expands from
 * {@code gradle.properties}. The resource sits in this class's own package, so every
 * loader finds it the way {@code SqliteDriverRuntime} finds its descriptor (Fabric's
 * nested common jar, NeoForge's module, the flattened Paper/Sponge jars).
 */
public final class BuildInfo {
    static final String RESOURCE = "lss-build.properties";

    private BuildInfo() {}

    /** The Minecraft version this jar was built for ({@code minecraft_version}). This is not
     *  the running server's version, which can differ on Paper (no loader-side version
     *  guard before 0.20.0) and within a Fabric/NeoForge version range. */
    public static String minecraftVersion() {
        return Holder.MINECRAFT_VERSION;
    }

    private static final class Holder {
        static final String MINECRAFT_VERSION = load(BuildInfo.class.getResourceAsStream(RESOURCE));
    }

    /** Fails loudly: a missing or unexpanded value is a packaging bug, and guessing would
     *  stamp the store with a version it was not built for. */
    static String load(InputStream in) {
        if (in == null) throw new IllegalStateException("missing " + RESOURCE + " (packaging bug)");
        var props = new Properties();
        try (in) {
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("unreadable " + RESOURCE, e);
        }
        String mc = props.getProperty("minecraft_version", "").strip();
        if (mc.isEmpty() || mc.contains("${")) {
            throw new IllegalStateException(RESOURCE + " carries no expanded minecraft_version"
                    + " (got '" + mc + "')");
        }
        return mc;
    }
}
