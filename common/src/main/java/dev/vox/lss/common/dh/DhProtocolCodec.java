package dev.vox.lss.common.dh;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static dev.vox.lss.common.dh.DhMessage.*;

/** Independent client wire implementation. Decode is server-to-client; encode is client-to-server. */
public final class DhProtocolCodec {
    public static final String CHANNEL = "distant_horizons:msg";
    private final DhProfile profile;
    private final DhLimits limits;
    public DhProtocolCodec(DhProfile profile, DhLimits limits) {
        this.profile = java.util.Objects.requireNonNull(profile);
        this.limits = java.util.Objects.requireNonNull(limits);
    }
    /** Production queue boundary: retain this lease until the message is consumed or discarded.
     * The reservation covers the frame, copied fragment/string bytes, beacon objects and parse temporaries.
     */
    public OwnedMessage decodeOwned(byte[] frame, DhMemoryBudget budget) throws DhCodecException {
        if (frame.length > limits.frameBytes()) throw DhInput.limit("DH frame exceeds limit");
        var lease = budget.reserve(frame.length * 4L + 512);
        try { return new OwnedMessage(decode(frame), lease); }
        catch (DhCodecException | RuntimeException | Error e) { lease.close(); throw e; }
    }
    public static final class OwnedMessage implements AutoCloseable {
        private DhMessage message;
        private final DhMemoryBudget.Lease lease;
        private OwnedMessage(DhMessage message, DhMemoryBudget.Lease lease) { this.message = message; this.lease = lease; }
        public DhMessage message() {
            if (message == null) throw new IllegalStateException("DH message ownership released");
            return message;
        }
        @Override public void close() { message = null; lease.close(); }
    }
    /** Per-frame parsing for tests/short synchronous use. Retained production messages use decodeOwned. */
    public DhMessage decode(byte[] frame) throws DhCodecException {
        if (frame.length > limits.frameBytes()) throw DhInput.limit("DH frame exceeds limit");
        var in = new DhInput(frame);
        int version = in.u16();
        if (version != profile.version())
            throw new DhCodecException(DhCodecException.Kind.PROTOCOL_MISMATCH, "DH protocol " + version
                    + " differs from selected " + profile.version());
        DhMessage result = switch (in.u16()) {
            case 1 -> new CloseReason(profile.support() ? in.wideString(profile, limits.stringBytes()) : text(in));
            case 2 -> new LevelInit(text(in), text(in), text(in), in.i64());
            case 3 -> new RequestLevelInit(text(in));
            case 4 -> config(in);
            case 5 -> new Cancel(in.i32());
            case 6 -> new Refusal(in.i32(), in.i32(), text(in));
            case 7 -> new Request(in.i32(), text(in), DhPosition.unpack(in.i64()), in.bool() ? in.i64() : null);
            case 8 -> {
                int request = in.i32();
                yield in.bool() ? new Response(request, in.i32(), beacons(in))
                        : new Response(request, null, List.of());
            }
            case 9 -> new PartialUpdate(text(in), in.i32(), beacons(in));
            case 10 -> {
                int buffer = in.i32(), length = in.i32();
                if (length <= 0) throw DhInput.malformed("Invalid DH fragment length");
                if (length > limits.tileBytes()) throw DhInput.limit("DH fragment length exceeds limit");
                in.require(length + 1);
                // Split owns one defensive copy. The temporary cursor copy is bounded by frameBytes.
                byte[] bytes = in.take(length);
                yield new Split(buffer, bytes, in.bool());
            }
            default -> throw new DhCodecException(DhCodecException.Kind.UNSUPPORTED, "Unknown DH message");
        };
        in.exhausted();
        return result;
    }
    private String text(DhInput in) throws DhCodecException { return in.string(profile, limits.stringBytes()); }
    private SessionConfig config(DhInput in) throws DhCodecException {
        GeneratorPlan plan;
        if (profile.version() == 15) plan = in.bool() ? GeneratorPlan.CHUNKS_ONLY : GeneratorPlan.DISABLED;
        else {
            int value = in.u8();
            if (value >= GeneratorPlan.values().length) throw DhInput.malformed("Unknown generator plan");
            plan = GeneratorPlan.values()[value];
        }
        try {
            return new SessionConfig(plan, in.i32(), in.i32(), in.i32(), in.i32(), in.i32(),
                    in.bool(), in.i32(), in.bool(), in.i32(), in.i32(), in.i32());
        } catch (IllegalArgumentException e) { throw DhInput.malformed("Invalid session limits"); }
    }
    private List<Beacon> beacons(DhInput in) throws DhCodecException {
        int count = in.i32();
        if (count < 0 || count > 4096 || count > in.remaining() / 16)
            throw DhInput.malformed("Invalid beacon count");
        var result = new ArrayList<Beacon>(count);
        for (int i = 0; i < count; i++) result.add(new Beacon(in.i32(), in.i32(), in.i32(), in.i32()));
        return result;
    }
    /** Outbound client strings use byte lengths, including Support's asymmetric decoder. */
    public byte[] encode(DhMessage message) throws DhCodecException {
        if (profile.support() && message instanceof CloseReason)
            throw new DhCodecException(DhCodecException.Kind.UNSUPPORTED, "DH Support does not accept CloseReason");
        if (message instanceof Split split && (split.length() < 1
                || split.length() > Math.min(limits.tileBytes(), limits.frameBytes() - 13)))
            throw DhInput.limit("DH fragment exceeds frame limit");
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeShort(profile.version());
            switch (message) {
                case CloseReason m -> { out.writeShort(1); text(out, m.reason()); }
                case LevelInit m -> { out.writeShort(2); text(out, m.dimension()); text(out, m.serverKey());
                    text(out, m.levelKey()); out.writeLong(m.serverTime()); }
                case RequestLevelInit m -> { out.writeShort(3); text(out, m.dimension()); }
                case SessionConfig m -> {
                    out.writeShort(4);
                    if (profile.version() == 15) {
                        if (m.plan() != GeneratorPlan.CHUNKS_ONLY && m.plan() != GeneratorPlan.DISABLED)
                            throw new DhCodecException(DhCodecException.Kind.UNSUPPORTED, "Protocol 15 uses a generation boolean");
                        out.writeBoolean(m.plan() != GeneratorPlan.DISABLED);
                    } else out.writeByte(m.plan().ordinal());
                    out.writeInt(m.generationDistance()); out.writeInt(m.centerX()); out.writeInt(m.centerZ());
                    out.writeInt(m.borderRadius()); out.writeInt(m.generationRate()); out.writeBoolean(m.updates());
                    out.writeInt(m.updateDistance()); out.writeBoolean(m.synchronize()); out.writeInt(m.syncDistance());
                    out.writeInt(m.syncRate()); out.writeInt(m.bandwidthKB());
                }
                case Cancel m -> { out.writeShort(5); out.writeInt(m.requestId()); }
                case Refusal m -> { out.writeShort(6); out.writeInt(m.requestId()); out.writeInt(m.reason()); text(out, m.message()); }
                case Request m -> { out.writeShort(7); out.writeInt(m.requestId()); text(out, m.level());
                    out.writeLong(m.position().packed()); out.writeBoolean(m.timestamp() != null);
                    if (m.timestamp() != null) out.writeLong(m.timestamp()); }
                case Response m -> {
                    out.writeShort(8); out.writeInt(m.requestId()); out.writeBoolean(m.bufferId() != null);
                    if (m.bufferId() != null) { out.writeInt(m.bufferId()); beacons(out, m.beacons()); }
                    else if (!m.beacons().isEmpty()) throw DhInput.malformed("Beacons without a response payload");
                }
                case PartialUpdate m -> { out.writeShort(9); text(out, m.level()); out.writeInt(m.bufferId()); beacons(out, m.beacons()); }
                case Split m -> { out.writeShort(10); out.writeInt(m.bufferId()); out.writeInt(m.length());
                    out.write(m.contents()); out.writeBoolean(m.first()); }
            }
        } catch (DhCodecException e) { throw e; }
        catch (IOException e) { throw new DhCodecException(DhCodecException.Kind.MALFORMED, "Cannot encode DH frame", e); }
        if (bytes.size() > limits.frameBytes()) throw DhInput.limit("DH frame exceeds limit");
        return bytes.toByteArray();
    }
    private void text(DataOutputStream out, String value) throws IOException {
        if (value.length() > limits.stringBytes()) throw DhInput.limit("DH string exceeds limit");
        // UTF-8 encoders otherwise silently replace isolated surrogates.
        for (int i = 0; i < value.length(); i++) if (Character.isSurrogate(value.charAt(i))) {
            if (!Character.isHighSurrogate(value.charAt(i)) || i + 1 == value.length()
                    || !Character.isLowSurrogate(value.charAt(++i))) throw DhInput.malformed("Invalid Unicode string");
        }
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        if (data.length > limits.stringBytes()) throw DhInput.limit("DH string exceeds byte limit");
        out.writeShort(data.length);
        out.write(data);
    }
    private void beacons(DataOutputStream out, List<Beacon> values) throws IOException {
        if (values.size() > 4096) throw DhInput.limit("Too many beacons");
        out.writeInt(values.size());
        for (var b : values) { out.writeInt(b.x()); out.writeInt(b.y()); out.writeInt(b.z()); out.writeInt(b.rgb()); }
    }
}
