package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSLogger;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Snapshot of a loaded chunk column's pre-serialized section data.
 * Built on the server thread (platform-specific serialization), consumed on the processing thread.
 *
 * <p>Two shapes. The eager shape carries finished bytes, exactly as the former record did.
 * The deferred shape ({@link #deferred}) carries a serializer over a snapshot the server
 * thread already took (copied sections and light layers), and runs it once, on the first
 * read of {@link #serializedSections()}. Every reader lives on the processing thread, so
 * expensive per-section work (Paper's LOD x-ray masking) moves off the server tick while
 * the content stays exactly what the probe saw. The serializer must not touch live world
 * state.
 *
 * <p>A deferred serializer that throws is logged and the column reads as all-air (null
 * bytes), the same as an empty column: a throw on the processing thread must never take the
 * whole cycle down with it.
 */
public final class LoadedColumnData {
    private final int cx;
    private final int cz;
    private final int eagerEstimatedBytes;
    private Supplier<byte[]> deferredSerializer;
    private volatile boolean materialized;
    private byte[] serializedSections;

    /**
     * @param cx                 chunk X coordinate
     * @param cz                 chunk Z coordinate
     * @param serializedSections section bytes in MC-native wire format
     * @param estimatedBytes     estimated wire size
     */
    public LoadedColumnData(int cx, int cz, byte[] serializedSections, int estimatedBytes) {
        this.cx = cx;
        this.cz = cz;
        this.serializedSections = serializedSections;
        this.eagerEstimatedBytes = estimatedBytes;
        this.materialized = true;
    }

    private LoadedColumnData(int cx, int cz, Supplier<byte[]> serializer) {
        this.cx = cx;
        this.cz = cz;
        this.eagerEstimatedBytes = -1;
        this.deferredSerializer = Objects.requireNonNull(serializer, "serializer");
        this.materialized = false;
    }

    /** A column whose bytes are produced by {@code serializer} on first read, at most once. */
    public static LoadedColumnData deferred(int cx, int cz, Supplier<byte[]> serializer) {
        return new LoadedColumnData(cx, cz, serializer);
    }

    public int cx() {
        return this.cx;
    }

    public int cz() {
        return this.cz;
    }

    /** Section bytes in MC-native wire format; null or empty for an all-air column. */
    public byte[] serializedSections() {
        if (!this.materialized) {
            materialize();
        }
        return this.serializedSections;
    }

    /** Estimated wire size: the caller's estimate for an eager column, the byte length once
     *  a deferred column has been serialized (forces the serialization). */
    public int estimatedBytes() {
        byte[] bytes = serializedSections();
        if (this.eagerEstimatedBytes >= 0) return this.eagerEstimatedBytes;
        return bytes == null ? 0 : bytes.length;
    }

    /** True until a deferred column's serializer has run. Always false for eager columns. */
    public boolean isDeferred() {
        return !this.materialized;
    }

    private synchronized void materialize() {
        if (this.materialized) return;
        try {
            this.serializedSections = this.deferredSerializer.get();
        } catch (Throwable t) {
            LSSLogger.error("Deferred column serialization failed for chunk ["
                    + this.cx + ", " + this.cz + "]; serving it as all-air", t);
            this.serializedSections = null;
        } finally {
            this.deferredSerializer = null;
            this.materialized = true;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LoadedColumnData other)) return false;
        return this.cx == other.cx && this.cz == other.cz
                && this.serializedSections() == other.serializedSections()
                && this.estimatedBytes() == other.estimatedBytes();
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.cx, this.cz, System.identityHashCode(serializedSections()), estimatedBytes());
    }

    @Override
    public String toString() {
        return "LoadedColumnData[cx=" + this.cx + ", cz=" + this.cz
                + (this.materialized ? ", bytes=" + (this.serializedSections == null ? 0 : this.serializedSections.length)
                                     : ", deferred") + "]";
    }
}
