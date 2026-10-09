package dev.vox.lss.common.dh;

import com.github.luben.zstd.Zstd;
import org.tukaani.xz.XZInputStream;
import java.io.*;
import java.util.*;
import static dev.vox.lss.common.dh.DhTerrainTile.*;

/** Independent format-1/2 decoder. The plan's wire description is the contract; no upstream linkage. */
public final class DhTerrainDecoder {
    private final DhLimits limits;
    public DhTerrainDecoder(DhLimits limits) { this.limits = Objects.requireNonNull(limits); }

    public DhTerrainTile decode(byte[] dto, DhProfile profile, DhPosition expected,
                                WorldShape world, DhMemoryBudget budget) throws DhCodecException {
        if (dto.length > limits.tileBytes() || world.height() > limits.worldHeight())
            throw DhInput.limit("DH tile or world height exceeds limit");
        var leases = new ArrayList<DhMemoryBudget.Lease>();
        try {
            hold(leases, budget, dto.length + 512L * 1024); // input plus column/lease tables and validation scratch
            var in = new DhInput(dto);
            DhPosition pos = DhPosition.unpack(in.i64());
            if (!pos.equals(expected)) throw DhInput.malformed("Unexpected returned DH position");
            if (pos.detail() != 6) throw unsupported("Only network-detail-6 terrain is supported");
            int hash = in.i32();
            var blobs = new Slice[8];
            int compressed = 0;
            for (int i = 0; i < blobs.length; i++) {
                int size = in.i32();
                if (size < 0) throw DhInput.malformed("Negative DH blob length");
                if (size > limits.tileBytes() - compressed) throw DhInput.malformed("DH blob exceeds tile budget");
                in.require(size);
                blobs[i] = new Slice(dto, in.offset, size);
                in.offset += size;
                compressed += size;
            }
            int format = in.u8(), mode = in.u8();
            if (format != 1 && format != 2) throw unsupported("Unknown DH terrain format");
            if (mode != 0 && mode != 3 && mode != 4) throw unsupported("DH compression mode is unsupported");
            boolean parent = in.bool(), children = in.bool();
            boolean regenerate = profile.regenerationFlag() && in.bool();
            long firstTimestamp = in.i64(), secondTimestamp = in.i64();
            // DH Support labels its slots created/updated; official DTOs use modified/created.
            long modified = profile.support() ? secondTimestamp : firstTimestamp;
            long created = profile.support() ? firstTimestamp : secondTimestamp;
            in.exhausted();
            var reads = new Reads(mode, profile.support(), budget);
            List<DhMaterial> materials = mappings(blobs[7], reads, profile, budget, leases);
            byte[] generation = metadata(blobs[5], reads), compression = metadata(blobs[6], reads);
            long[][] columns = new long[4096][];
            var spans = new Counter();
            if (format == 1) {
                for (int i = 1; i <= 4; i++) if (blobs[i].size != 0)
                    throw unsupported("Format 1 adjacent terrain is unsupported");
                try (var stream = reads.open(blobs[0])) {
                    for (int c = 0; c < 4096; c++) {
                        int count = stream.data.readUnsignedShort();
                        allocate(count, world, spans, budget, leases);
                        columns[c] = new long[count];
                        for (int i = 0; i < count; i++) columns[c][i] = stream.data.readLong();
                    }
                    stream.finish();
                }
            } else {
                // Each blob has its own prediction state; x outer / z inner within each rectangle.
                int[][] bounds = {{1,63,1,63}, {0,64,0,1}, {0,64,63,64}, {63,64,0,64}, {0,1,0,64}};
                for (int b = 0; b < 5; b++) {
                    if (blobs[b].size == 0) throw DhInput.malformed("Missing format-2 interior or edge blob");
                    int[] rectangle = bounds[b];
                    int[] indices = rectangle(rectangle);
                    try (var stream = reads.open(blobs[b])) {
                        long[][] local = new long[indices.length][];
                        for (int c = 0; c < local.length; c++) {
                            int count = varint(stream.data);
                            if (count < 0) throw DhInput.malformed("Invalid column span count");
                            allocate(count, world, spans, budget, leases);
                            local[c] = new long[count];
                        }
                        for (long[] col : local) for (int i = 0; i < col.length; i++)
                            col[i] = varint(stream.data) & 0xffffffffL;
                        for (long[] col : local) for (int i = 0; i < col.length; i++) {
                            int height = varint(stream.data);
                            if (height < 1 || height > 4095) throw DhInput.malformed("Invalid format-2 height");
                            col[i] |= (long) height << 32;
                        }
                        int predicted = 0;
                        for (long[] col : local) for (int i = 0; i < col.length; i++) {
                            long point = col[i];
                            int error = 0;
                            if ((point & 1) != 0) { int zigzag = varint(stream.data); error = (zigzag >>> 1) ^ -(zigzag & 1); }
                            long bottom = (long) predicted - height(point) + error;
                            if (bottom < 0 || bottom > 4095) throw DhInput.malformed("Invalid format-2 bottom");
                            predicted = (int) bottom;
                            col[i] |= bottom << 44;
                        }
                        for (long[] col : local) for (int i = 0; i < col.length; i++) {
                            long point = col[i];
                            int light = (point & 2) == 0 ? 0 : stream.data.readUnsignedByte();
                            // Convert ID/flags into the shared packed representation only after all phases.
                            col[i] = word((int) (point & 0xffffffffL) >>> 2, height(point), bottom(point), light);
                        }
                        stream.finish();
                        for (int c = 0; c < local.length; c++) {
                            int index = indices[c];
                            if (columns[index] != null && !Arrays.equals(columns[index], local[c]))
                                throw DhInput.malformed("Inconsistent repeated format-2 corner");
                            columns[index] = local[c];
                        }
                    }
                }
            }
            Coverage[] coverage = new Coverage[4096];
            for (int c = 0; c < 4096; c++) {
                columns[c] = withoutCeilingSentinel(columns[c], materials, world, profile, budget, leases);
                coverage[c] = validate(columns[c], materials, world, profile, generation[c] & 255);
            }
            return new DhTerrainTile(pos, world, format, mode, hash, parent, children, regenerate, modified, created,
                    columns, materials, generation, compression, coverage, leases);
        } catch (DhCodecException e) { leases.forEach(DhMemoryBudget.Lease::close); throw e; }
        catch (org.tukaani.xz.MemoryLimitException e) {
            leases.forEach(DhMemoryBudget.Lease::close);
            throw new DhCodecException(DhCodecException.Kind.RESOURCE_LIMIT, "XZ dictionary exceeds memory limit", e);
        }
        catch (IOException | IllegalArgumentException e) {
            leases.forEach(DhMemoryBudget.Lease::close);
            throw new DhCodecException(DhCodecException.Kind.MALFORMED, "Invalid DH terrain data", e);
        } catch (RuntimeException | Error e) { leases.forEach(DhMemoryBudget.Lease::close); throw e; }
    }
    private static int[] rectangle(int[] b) {
        int[] result = new int[(b[1] - b[0]) * (b[3] - b[2])];
        int offset = 0;
        for (int x = b[0]; x < b[1]; x++) for (int z = b[2]; z < b[3]; z++) result[offset++] = x * 64 + z;
        return result;
    }
    private static Coverage validate(long[] column, List<DhMaterial> mappings, WorldShape world,
                                     DhProfile profile, int generation) throws DhCodecException {
        if (column == null) throw DhInput.malformed("Missing DH column");
        if (column.length == 0) return Coverage.UNKNOWN;
        var occupied = new BitSet(world.height());
        for (int i = 0; i < column.length; i++) {
            long point = column[i];
            int id = id(point), low = bottom(point), height = height(point), high = low + height;
            if (id >= mappings.size() || height == 0) throw DhInput.malformed("Invalid span material or height");
            // Captured Support FullBuilder and official 3.2.0-b/3.3.3 LodDataBuilder start
            // the top AIR run at exclusiveMaxBuildHeight and encode its bottom as y + 1.
            // Discard only their one known AIR voxel outside the world; never fill gaps.
            if ((profile.support() || profile == DhProfile.OFFICIAL_15 || profile == DhProfile.OFFICIAL_16)
                    && high == world.height() + 1 && low < world.height() && mappings.get(id).air()) {
                height--; high--; point = (point & ~(4095L << 32)) | ((long) height << 32);
            }
            // Captured official3.3.3 rough SURFACE output uses getHeight() as
            // absolute maxY, then subtracts minY again. Its upper AIR ends at
            // height-minY (448 in the -64/384 Overworld). Clip only that exact
            // negative-minY AIR band with SURFACE metadata; retain quality5.
            // This never fills a gap or admits solid/water overshoots.
            if (profile == DhProfile.OFFICIAL_16 && generation == 5 && world.minY() < 0
                    && low < world.height() && (long) high == (long) world.height() - world.minY()
                    && mappings.get(id).air()) {
                height = world.height() - low; high = world.height();
                point = (point & ~(4095L << 32)) | ((long) height << 32);
            }
            if (height == 0 || high > world.height()) throw DhInput.malformed("DH span outside world height");
            int next = occupied.nextSetBit(low);
            if (next >= 0 && next < high) throw DhInput.malformed("Overlapping DH spans");
            occupied.set(low, high);
            // The reserved ID bit is not a mapping index. No-sky dimensions cannot acquire skylight.
            point &= ~(1L << 31);
            if (!world.sky()) point &= ~(15L << 56);
            column[i] = point;
        }
        return occupied.cardinality() == world.height() ? Coverage.COMPLETE : Coverage.PARTIAL;
    }
    private static long[] withoutCeilingSentinel(long[] column, List<DhMaterial> materials, WorldShape world,
                                                DhProfile profile, DhMemoryBudget budget,
                                                List<DhMemoryBudget.Lease> leases) throws DhCodecException {
        if (column == null || (profile != DhProfile.OFFICIAL_16 && profile != DhProfile.OFFICIAL_15)) return column;
        // Stock 3.2.0-b and 3.3.3 captured 512-height response can contain an AIR-only run
        // beginning at the exclusive ceiling. It contributes no world coverage.
        // Support and every larger/solid overshoot retain their strict bounds.
        int kept = 0;
        for (long point : column) {
            int id = id(point);
            if (bottom(point) == world.height() && height(point) == 1
                    && id < materials.size() && materials.get(id).air()) continue;
            column[kept++] = point;
        }
        if (kept == column.length) return column;
        hold(leases, budget, kept * 8L + 64); // reserve replacement before allocation; old array remains charged
        return Arrays.copyOf(column, kept);
    }
    private void allocate(int count, WorldShape world, Counter spans, DhMemoryBudget budget,
                          List<DhMemoryBudget.Lease> leases) throws DhCodecException {
        // Every span is at least one voxel tall: more spans than the world height is
        // structurally impossible, and the per-tile span ceiling is tile-intrinsic.
        // Both are malformed tiles (tile-scoped strikes), never memory pressure.
        if (count > world.height()) throw DhInput.malformed("DH column has more spans than world height");
        if (count > limits.spans() - spans.value) throw DhInput.malformed("DH span count exceeds tile limit");
        spans.value += count;
        hold(leases, budget, count * 8L + 64);
    }
    private List<DhMaterial> mappings(Slice slice, Reads reads, DhProfile profile, DhMemoryBudget budget,
                                      List<DhMemoryBudget.Lease> leases) throws IOException {
        try (var stream = reads.open(slice)) {
            int count = stream.data.readInt();
            if (count < 0) throw DhInput.malformed("Negative DH mapping count");
            if (count > limits.mappings()) throw DhInput.limit("DH mapping count exceeds limit");
            hold(leases, budget, count * 512L);
            var result = new ArrayList<DhMaterial>(count);
            int textBytes = 0;
            for (int i = 0; i < count; i++) {
                int length = stream.data.readUnsignedShort();
                if (length > limits.stringBytes() || length > limits.mappingBytes() - textBytes)
                    throw DhInput.limit("DH mapping strings exceed limit");
                hold(leases, budget, length * 32L); // text, property objects and bounded parsing temporaries
                String value;
                if (reads.support) {
                    var text = DhInput.supportText(stream.data, length,
                            Math.min(limits.stringBytes(), limits.mappingBytes() - textBytes));
                    value = text.value(); textBytes += text.bytes();
                } else {
                    textBytes += length;
                    byte[] utf = new byte[length + 2];
                    utf[0] = (byte) (length >> 8); utf[1] = (byte) length;
                    stream.data.readFully(utf, 2, length);
                    // Official mappings use Java modified UTF-8.
                    value = new DataInputStream(new ByteArrayInputStream(utf)).readUTF();
                }
                result.add(DhMaterial.parse(value, profile));
            }
            stream.finish();
            return result;
        }
    }
    private static byte[] metadata(Slice slice, Reads reads) throws IOException {
        try (var stream = reads.open(slice)) {
            byte[] result = new byte[4096];
            stream.data.readFully(result); stream.finish(); return result;
        }
    }
    private static int varint(DataInputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int next = in.readUnsignedByte();
            if (shift == 28 && (next & 0xf0) != 0) throw DhInput.malformed("Overflowing DH varint");
            value |= (next & 127) << shift;
            if ((next & 128) == 0) {
                if (shift > 0 && (next & 127) == 0) throw DhInput.malformed("Noncanonical DH varint");
                return value;
            }
        }
        throw DhInput.malformed("Overlong DH varint");
    }
    private static void hold(List<DhMemoryBudget.Lease> leases, DhMemoryBudget budget, long amount) throws DhCodecException {
        leases.add(budget.reserve(amount));
    }
    private static DhCodecException unsupported(String message) { return new DhCodecException(DhCodecException.Kind.UNSUPPORTED, message); }
    private record Slice(byte[] bytes, int offset, int size) {}
    private static final class Counter { int value; }

    /** One live decompressor at a time; output counts are aggregate across all eight blobs. */
    private final class Reads {
        final int mode;
        final boolean support;
        final DhMemoryBudget budget;
        long consumed;
        Reads(int mode, boolean support, DhMemoryBudget budget) {
            this.mode = mode; this.support = support; this.budget = budget;
        }
        Stream open(Slice slice) throws IOException {
            DhMemoryBudget.Lease work = null, output = null;
            try {
                InputStream raw = new ByteArrayInputStream(slice.bytes, slice.offset, slice.size);
                boolean exact = mode != 3 || !support;
                if (mode == 3) {
                    work = budget.reserve(limits.decompressorKiB() * 1024L);
                    raw = new XZInputStream(raw, limits.decompressorKiB(), !support);
                } else if (mode == 4) {
                    long size = Zstd.getFrameContentSize(slice.bytes, slice.offset, slice.size);
                    if (size < 0) throw DhInput.malformed("Invalid or sizeless Zstd block");
                    if (size > limits.decodedBytes() - consumed) throw DhInput.limit("Zstd output exceeds decoded limit");
                    // zstd's one-shot decoder is bounded by its supplied destination buffer.
                    work = budget.reserve(limits.decompressorKiB() * 1024L);
                    output = budget.reserve(size);
                    byte[] data = new byte[(int) size];
                    long written;
                    // zstd-jni reports corrupt input by throwing, not by an error code.
                    try { written = Zstd.decompressByteArray(data, 0, data.length, slice.bytes, slice.offset, slice.size); }
                    catch (com.github.luben.zstd.ZstdException corrupt) { throw new DhCodecException(DhCodecException.Kind.MALFORMED, "Invalid Zstd block", corrupt); }
                    if (Zstd.isError(written) || written != size) throw DhInput.malformed("Invalid Zstd block");
                    raw = new ByteArrayInputStream(data);
                }
                return new Stream(raw, work, output, exact, this);
            } catch (IOException | RuntimeException | Error e) {
                if (work != null) work.close();
                if (output != null) output.close();
                // XZ's own dictionary-limit failure is a resource rejection.
                if (e instanceof org.tukaani.xz.MemoryLimitException)
                    throw new DhCodecException(DhCodecException.Kind.RESOURCE_LIMIT, "XZ dictionary exceeds memory limit", e);
                throw e;
            }
        }
    }
    private final class Stream implements AutoCloseable {
        final InputStream raw;
        final DataInputStream data;
        final DhMemoryBudget.Lease work, output;
        final boolean exact;
        Stream(InputStream raw, DhMemoryBudget.Lease work, DhMemoryBudget.Lease output, boolean exact, Reads reads) {
            this.raw = raw; this.work = work; this.output = output; this.exact = exact;
            data = new DataInputStream(new FilterInputStream(raw) {
                private void charge(int bytes) throws DhCodecException {
                    if (bytes > limits.decodedBytes() - reads.consumed) throw DhInput.limit("DH decoded-byte limit exceeded");
                    reads.consumed += bytes;
                }
                @Override public int read() throws IOException {
                    int value = in.read(); if (value >= 0) charge(1); return value;
                }
                @Override public int read(byte[] bytes, int offset, int count) throws IOException {
                    // Never let a decoder produce an unaccounted large result.
                    int bounded = (int) Math.min(count, limits.decodedBytes() - reads.consumed + 1);
                    int value = in.read(bytes, offset, bounded); if (value > 0) charge(value); return value;
                }
            });
        }
        void finish() throws IOException {
            // A flush-only Support stream may throw EOFException instead of returning -1
            // at its missing footer. It may never supply additional decoded terrain.
            try {
                if (data.read() != -1) throw DhInput.malformed("Trailing decompressed terrain bytes");
            } catch (EOFException e) { if (exact) throw e; }
        }
        @Override public void close() throws IOException {
            try { data.close(); } finally { if (work != null) work.close(); if (output != null) output.close(); }
        }
    }
}
