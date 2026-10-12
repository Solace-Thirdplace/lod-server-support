package dev.vox.lss.common.dh;

import java.io.DataOutputStream;
import java.io.IOException;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Owned normalized terrain. Empty columns mean UNKNOWN, never fabricated air. Close after delivery. */
public final class DhTerrainTile implements AutoCloseable {
    public enum Coverage { UNKNOWN, PARTIAL, COMPLETE }
    public record WorldShape(int minY, int height, boolean sky) {
        public WorldShape {
            if (height < 1 || height > 4096 || (long) minY + height > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Invalid DH world height");
        }
    }
    public record Span(int material, int bottom, int height, int skyLight, int blockLight) {}
    private final DhPosition position;
    private final WorldShape world;
    private final int format, compression, dataHash;
    private final boolean parent, children, regenerate;
    private final long modified, created;
    private long[][] columns;
    private List<DhMaterial> materials;
    private byte[] generation, worldCompression;
    private Coverage[] coverage;
    private final List<DhMemoryBudget.Lease> leases;

    DhTerrainTile(DhPosition position, WorldShape world, int format, int compression, int dataHash,
                  boolean parent, boolean children, boolean regenerate, long modified, long created,
                  long[][] columns, List<DhMaterial> materials, byte[] generation, byte[] worldCompression,
                  Coverage[] coverage, List<DhMemoryBudget.Lease> leases) {
        this.position = position; this.world = world; this.format = format; this.compression = compression;
        this.dataHash = dataHash; this.parent = parent; this.children = children; this.regenerate = regenerate;
        this.modified = modified; this.created = created; this.columns = columns; this.materials = List.copyOf(materials);
        this.generation = generation; this.worldCompression = worldCompression; this.coverage = coverage;
        this.leases = leases;
    }
    public DhPosition position() { return position; }
    public WorldShape world() { return world; }
    public int format() { return format; }
    public int compression() { return compression; }
    public int upstreamDataHash() { return dataHash; }
    public boolean applyToParent() { return parent; }
    public boolean applyToChildren() { return children; }
    public boolean regenerate() { return regenerate; }
    public long modifiedTimestamp() { return modified; }
    public long createdTimestamp() { return created; }
    public List<DhMaterial> materials() { open(); return materials; }
    public int spanCount(int column) { open(); return columns[column].length; }
    public Span span(int column, int index) {
        open(); long word = columns[column][index];
        return new Span(id(word), bottom(word), height(word), (int) (word >>> 56 & 15), (int) (word >>> 60 & 15));
    }
    public Coverage coverage(int column) { open(); return coverage[column]; }
    public int generation(int column) { open(); return generation[column] & 255; }
    public int worldCompression(int column) { open(); return worldCompression[column] & 255; }
    /** Rebuild sky light from the spans as a DH client does on storing a full-detail tile;
     *  {@code opacity} is indexed by material (see {@link DhSkyLightBake}). Block light is kept. */
    public void bakeSkyLight(int[] opacity) { open(); DhSkyLightBake.bake(columns, opacity, world.sky() ? 15 : 0); }
    static int id(long value) { return (int) (value & 0x7fffffffL); }
    static int height(long value) { return (int) (value >>> 32 & 4095); }
    static int bottom(long value) { return (int) (value >>> 44 & 4095); }
    static long word(int id, int height, int bottom, int light) {
        return id | ((long) height << 32) | ((long) bottom << 44) | ((long) light << 56);
    }
    /** Versioned normalized content digest excludes timestamps, compression and upstream's non-cryptographic hash. */
    public String normalizedSha256() {
        open();
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var out = new DataOutputStream(new DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest))) {
                out.writeInt(1); out.writeLong(position.packed()); out.writeInt(world.minY());
                out.writeInt(world.height()); out.writeBoolean(world.sky());
                out.writeInt(materials.size());
                for (var material : materials) out.writeUTF(material.serialized());
                for (int col = 0; col < 4096; col++) {
                    out.writeByte(generation[col]); out.writeByte(worldCompression[col]);
                    out.writeInt(columns[col].length);
                    for (long point : columns[col]) out.writeLong(point);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) { throw new IllegalStateException(e); }
    }
    private void open() { if (columns == null) throw new IllegalStateException("DH terrain ownership released"); }
    @Override public void close() {
        if (columns != null) {
            columns = null; materials = null; generation = null; worldCompression = null; coverage = null;
            leases.forEach(DhMemoryBudget.Lease::close);
            leases.clear();
        }
    }
}
