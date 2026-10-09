package dev.vox.lss.compat;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

/**
 * Optional, read-only destination presence probe: does Voxy hold any non-default
 * voxel in a chunk column? Used once per persisted receipt on rejoin to detect a
 * wiped or lost destination before trusting the receipt. Never writes to Voxy and
 * never compares content.
 */
public final class VoxyTerrainProbe {
    private final Object engine;
    private final Method acquire, copy, release, acquireRef, releaseRef;
    private final MethodHandle blockId, lightId;

    /** Resolve the engine on the client thread; worker reads hold engine references only per read. */
    static VoxyTerrainProbe open(ClientLevel level) {
        try {
            var resolver = VoxyCompat.classResolver;
            Class<?> identifier = resolver.resolve("me.cortex.voxy.commonImpl.WorldIdentifier");
            Object id = identifier.getMethod("of", Level.class).invoke(null, level);
            if (id == null) return null;
            Object engine = identifier.getMethod("getOrCreateEngine").invoke(id);
            if (engine == null) return null;
            return new VoxyTerrainProbe(engine, resolver);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null; // Optional ABI failure must never disable the ordinary ingest bridge.
        }
    }

    private VoxyTerrainProbe(Object engine, VoxyCompat.ClassResolver resolver) throws ReflectiveOperationException {
        this.engine = engine;
        Class<?> ec = resolver.resolve("me.cortex.voxy.common.world.WorldEngine");
        Class<?> section = resolver.resolve("me.cortex.voxy.common.world.WorldSection");
        Class<?> mapper = resolver.resolve("me.cortex.voxy.common.world.other.Mapper");
        // acquire initializes a missing section to skylit air (the pinned port's default).
        acquire = ec.getMethod("acquire", int.class, int.class, int.class, int.class);
        copy = section.getMethod("copyData"); release = section.getMethod("release");
        acquireRef = ec.getMethod("acquireRef"); releaseRef = ec.getMethod("releaseRef");
        var lookup = MethodHandles.publicLookup();
        blockId = lookup.unreflect(mapper.getMethod("getBlockId", long.class));
        lightId = lookup.unreflect(mapper.getMethod("getLightId", long.class));
    }

    /** Client thread: an external Voxy reset may replace the engine between open and use. */
    public boolean isCurrent(ClientLevel level) {
        try {
            Class<?> identifier = VoxyCompat.classResolver.resolve("me.cortex.voxy.commonImpl.WorldIdentifier");
            Object id = identifier.getMethod("of", Level.class).invoke(null, level);
            return id != null && identifier.getMethod("getOrCreateEngine").invoke(id) == engine;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return false; }
    }

    /**
     * Worker thread. True when any voxel of the chunk column differs from Voxy's
     * initialized default (air at sky light 15). Reads sections bottom-up and returns
     * at the first non-default voxel, so ordinary terrain costs one section copy.
     */
    public boolean hasTerrain(int chunkX, int chunkZ, int minY, int height) throws ReflectiveOperationException {
        int bottom = minY >> 5, top = (minY + height - 1) >> 5, ox = (chunkX & 1) * 16, oz = (chunkZ & 1) * 16;
        acquireRef.invoke(engine);
        try {
            for (int y = bottom; y <= top; y++) {
                if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Voxy probe cancelled");
                Object section = acquire.invoke(engine, 0, chunkX >> 1, y, chunkZ >> 1);
                if (section == null) throw new IllegalStateException("Voxy did not initialize a section");
                long[] values;
                try { values = (long[]) copy.invoke(section); }
                finally { release.invoke(section); }
                if (values.length != 32 * 32 * 32) throw new IllegalStateException("Unsupported Voxy section shape");
                for (int ly = 0; ly < 32; ly++) {
                    int wy = (y << 5) + ly;
                    if (wy < minY || wy >= minY + height) continue;
                    for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        long value = values[(ly << 10) | ((oz + z) << 5) | (ox + x)];
                        if (decode(blockId, value) != 0 || decode(lightId, value) != 15) return true;
                    }
                }
            }
            return false;
        } finally {
            try { releaseRef.invoke(engine); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { /* destination shutdown */ }
        }
    }

    private static int decode(MethodHandle handle, long value) throws ReflectiveOperationException {
        try { return (int) handle.invokeExact(value); }
        catch (RuntimeException | Error e) { throw e; }
        catch (Throwable e) { throw new ReflectiveOperationException(e); }
    }
}
