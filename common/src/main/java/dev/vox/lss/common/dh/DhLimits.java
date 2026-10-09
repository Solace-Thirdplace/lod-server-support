package dev.vox.lss.common.dh;

/** Codec limits, distinct from server policy and scheduling. All byte limits include every blob. */
public record DhLimits(int frameBytes, int tileBytes, int decodedBytes, long ownedBytes,
                       int decompressorKiB, int buffers, int spans, int mappings,
                       int mappingBytes, int stringBytes, int worldHeight,
                       long idleNanos, long lifetimeNanos) {
    public static final DhLimits DEFAULT = new DhLimits(1_048_576, 16 << 20, 64 << 20, 256L << 20,
            32 << 10, 8, 2_200_000, 65_536, 4 << 20, 4096, 4096,
            10_000_000_000L, 60_000_000_000L);

    /** Startup pacing receives bounded completion headroom without relaxing byte/idle limits. */
    public DhLimits withLifetime(long nanos) {
        return new DhLimits(frameBytes,tileBytes,decodedBytes,ownedBytes,decompressorKiB,buffers,spans,
                mappings,mappingBytes,stringBytes,worldHeight,idleNanos,nanos);
    }

    public DhLimits {
        if (frameBytes < 4 || tileBytes < 1 || decodedBytes < 1 || ownedBytes < 1
                || decompressorKiB < 1 || buffers < 1 || spans < 1 || mappings < 1
                || mappingBytes < 1 || stringBytes < 1 || stringBytes > 65535
                || worldHeight < 1 || worldHeight > 4096
                || idleNanos < 1 || lifetimeNanos < idleNanos)
            throw new IllegalArgumentException("Invalid DH codec limits");
    }
}
