package dev.vox.lss.common.dh;

/** DH coordinates have signed 28-bit axes. Network detail 6 denotes a 64-by-64 block tile. */
public record DhPosition(int detail, int x, int z) {
    public DhPosition {
        if (detail < 0 || detail > 255 || x < -(1 << 27) || x >= 1 << 27
                || z < -(1 << 27) || z >= 1 << 27)
            throw new IllegalArgumentException("Unrepresentable DH position");
    }
    public static DhPosition tileForChunk(int x, int z) {
        return new DhPosition(6, Math.floorDiv(x, 4), Math.floorDiv(z, 4));
    }
    public long packed() { return detail | ((x & 0xfffffffL) << 8) | ((z & 0xfffffffL) << 36); }
    public static DhPosition unpack(long value) {
        return new DhPosition((int) (value & 255), (int) (value << 28 >> 36), (int) (value >> 36));
    }
}
