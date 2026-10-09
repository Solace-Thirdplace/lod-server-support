package dev.vox.lss.common.dh;

import java.util.*;
import java.util.function.LongSupplier;
import static dev.vox.lss.common.dh.DhMessage.*;

/**
 * Ordered, session-owned assembly. Call on one worker in wire arrival order.
 * Incoming frame/queue ownership must be charged by the transport before parsing.
 */
public final class DhAssembly implements AutoCloseable {
    public record Expired(List<Integer> buffers, List<Integer> requests) {}
    private record RequestOwner(DhPosition position, long started, long lifetime) {}
    private record Part(byte[] bytes, DhMemoryBudget.Lease lease) {}
    private static final class Buffer {
        final long started, lifetime, lossEpoch;
        long progress;
        int size;
        final List<Part> parts = new ArrayList<>();
        Buffer(long now,long lifetime,long lossEpoch) { started = now; progress = now; this.lifetime=lifetime; this.lossEpoch=lossEpoch; }
        void close() { parts.forEach(p -> p.lease.close()); parts.clear(); }
    }
    private final DhLimits limits;
    private final DhMemoryBudget budget;
    private final LongSupplier clock, lifetime;
    private final Map<Integer, RequestOwner> requests = new HashMap<>();
    private final Map<Integer, Buffer> buffers = new HashMap<>();
    private boolean closed, pushAllowed;

    public DhAssembly(DhLimits limits, DhMemoryBudget budget) { this(limits, budget, System::nanoTime); }
    public DhAssembly(DhLimits limits, DhMemoryBudget budget, LongSupplier clock) {
        this(limits,budget,clock,limits::lifetimeNanos);
    }
    public DhAssembly(DhLimits limits,DhMemoryBudget budget,LongSupplier clock,LongSupplier lifetime) {
        this.limits=Objects.requireNonNull(limits);this.budget=Objects.requireNonNull(budget);
        this.clock=Objects.requireNonNull(clock);this.lifetime=Objects.requireNonNull(lifetime);
    }
    private long checkedLifetime(long nanos) {
        if(nanos<limits.idleNanos() || nanos>limits.lifetimeNanos())throw new IllegalArgumentException("Invalid assembly lifetime");
        return nanos;
    }
    public void expect(int requestId, DhPosition position) throws DhCodecException {
        expect(requestId,position,lifetime.getAsLong());
    }
    public void expect(int requestId,DhPosition position,long lifetimeNanos) throws DhCodecException {
        open();
        if (position.detail() != 6) throw new DhCodecException(DhCodecException.Kind.UNSUPPORTED, "Only detail-6 acquisition is supported");
        if (requests.containsKey(requestId)) throw unowned("Duplicate DH request owner");
        if (requests.size() >= limits.buffers()) throw DhInput.limit("Too many outstanding DH requests");
        requests.put(requestId, new RequestOwner(position, clock.getAsLong(),checkedLifetime(lifetimeNanos)));
    }
    public void accept(Split split) throws DhCodecException {
        accept(split, clock.getAsLong());
    }
    /** Capture time on the connection's ordered inbound path; queue stalls are not peer idleness. */
    public void accept(Split split, long arrivalNanos) throws DhCodecException { accept(split, arrivalNanos, 0); }
    /**
     * {@code lossEpoch} is the transport's dropped-frame counter sampled when this
     * frame was captured. A buffer whose fragments span a loss may be missing a
     * fragment, so it is discarded (contained UNOWNED) rather than assembled with
     * a gap that would decode as MALFORMED.
     */
    public void accept(Split split, long arrivalNanos, long lossEpoch) throws DhCodecException {
        open();
        long now = arrivalNanos;
        Buffer buffer = buffers.get(split.bufferId());
        if (split.first()) {
            if (buffer != null) throw unowned("Duplicate first DH fragment");
            if (requests.isEmpty() && !pushAllowed) throw unowned("DH fragments without a request owner or update subscription");
            if (buffers.size() >= limits.buffers()) throw DhInput.limit("Too many incomplete DH buffers");
            buffer = new Buffer(now,checkedLifetime(lifetime.getAsLong()),lossEpoch);
        } else if (buffer == null) throw unowned("Missing first DH fragment");
        else if (buffer.lossEpoch != lossEpoch) {
            discard(split.bufferId());
            throw unowned("DH fragment lost under pressure; buffer discarded");
        }
        if (now - buffer.started >= buffer.lifetime || now - buffer.progress >= limits.idleNanos()) {
            buffers.remove(split.bufferId()); buffer.close();
            throw new DhCodecException(DhCodecException.Kind.EXPIRED, "DH assembly deadline exceeded");
        }
        if (split.length() < 1 || split.length() > limits.frameBytes() - 13
                || split.length() > limits.tileBytes() - buffer.size)
            throw DhInput.limit("DH assembly exceeds fragment/tile limit");
        // Bound object overhead even if a peer sends one byte in each frame.
        if (buffer.parts.size() >= 4096) throw DhInput.limit("Too many DH fragments in one tile");
        var lease = budget.reserve(split.length() + 160L);
        // Split owns immutable bytes; our lease covers retention after the codec owner closes.
        try { buffer.parts.add(new Part(split.contents(), lease)); }
        catch (RuntimeException | Error e) { lease.close(); throw e; }
        buffer.size += split.length(); buffer.progress = now;
        buffers.put(split.bufferId(), buffer);
    }
    /** A null result means a genuine absent/unchanged response; it never invents a terrain receipt. */
    public Completed response(Response response) throws DhCodecException {
        return response(response, clock.getAsLong());
    }
    /** The deadline is checked against network arrival, not delayed worker execution. */
    public Completed response(Response response, long arrivalNanos) throws DhCodecException { return response(response, arrivalNanos, 0); }
    public Completed response(Response response, long arrivalNanos, long lossEpoch) throws DhCodecException {
        open();
        RequestOwner owner = requests.get(response.requestId());
        if (owner == null) {
            // Release the referenced buffer now; it can never complete.
            if (response.bufferId() != null) discard(response.bufferId());
            throw unowned("Unmatched DH response");
        }
        if (arrivalNanos - owner.started >= owner.lifetime) {
            requests.remove(response.requestId());
            if (response.bufferId() != null) discard(response.bufferId());
            throw new DhCodecException(DhCodecException.Kind.EXPIRED, "DH request deadline exceeded");
        }
        if (response.bufferId() == null) {
            if (!response.beacons().isEmpty()) throw DhInput.malformed("Beacons without DH payload");
            requests.remove(response.requestId());
            return null;
        }
        try { return take(response.bufferId(), owner.position, arrivalNanos, lossEpoch); }
        finally { requests.remove(response.requestId()); }
    }
    /** The active world's negotiated subscription also owns unsolicited fragments. */
    public void allowPush(boolean allowed) { open(); pushAllowed = allowed; }
    public void discard(int bufferId) {
        Buffer buffer = buffers.remove(bufferId);
        if (buffer != null) buffer.close();
    }
    public Completed push(PartialUpdate message, long arrivalNanos) throws DhCodecException { return push(message, arrivalNanos, 0); }
    public Completed push(PartialUpdate message, long arrivalNanos, long lossEpoch) throws DhCodecException {
        open();
        if (!pushAllowed) { discard(message.bufferId()); throw unowned("DH updates are not enabled"); }
        return take(message.bufferId(), null, arrivalNanos, lossEpoch);
    }
    private Completed take(int id, DhPosition position, long now, long lossEpoch) throws DhCodecException {
        Buffer buffer = buffers.get(id);
        if (buffer == null) throw unowned("DH payload references a missing buffer");
        if (buffer.lossEpoch != lossEpoch) {
            discard(id);
            throw unowned("DH fragment lost under pressure; buffer discarded");
        }
        if (now - buffer.started >= buffer.lifetime || now - buffer.progress >= limits.idleNanos()) {
            discard(id);
            throw new DhCodecException(DhCodecException.Kind.EXPIRED, "DH assembly deadline exceeded");
        }
        DhMemoryBudget.Lease lease;
        try {lease=budget.reserve(buffer.size+64L);}
        catch(DhCodecException pressure){discard(id);throw pressure;}
        try {
            byte[] data = new byte[buffer.size];
            int offset = 0;
            for (Part part : buffer.parts) {
                System.arraycopy(part.bytes, 0, data, offset, part.bytes.length);
                offset += part.bytes.length;
            }
            discard(id);
            return new Completed(position, data, lease);
        } catch (RuntimeException | Error e) { lease.close(); throw e; }
    }
    public void cancel(int requestId) { requests.remove(requestId); }
    /** The fragment format has no request ID: cancel cannot identify/release a partially owned buffer.
     * It remains bounded until expiry or whole-session close.
     */
    public Expired expire() {
        return expire(clock.getAsLong());
    }
    /** Worker-owned safe arrival cutoff: outstanding timely captures must be processed first. */
    public Expired expire(long now) {
        var expiredBuffers = new ArrayList<Integer>();
        var expiredRequests = new ArrayList<Integer>();
        buffers.entrySet().removeIf(e -> {
            Buffer value = e.getValue();
            boolean expired = now - value.started >= value.lifetime || now - value.progress >= limits.idleNanos();
            if (expired) { expiredBuffers.add(e.getKey()); value.close(); }
            return expired;
        });
        requests.entrySet().removeIf(e -> {
            boolean expired = now - e.getValue().started >= e.getValue().lifetime;
            if (expired) expiredRequests.add(e.getKey());
            return expired;
        });
        return new Expired(List.copyOf(expiredBuffers), List.copyOf(expiredRequests));
    }
    public int incompleteBuffers() { return buffers.size(); }
    public long assembledBytes() { return buffers.values().stream().mapToLong(b -> b.size).sum(); }
    private void open() { if (closed) throw new IllegalStateException("DH assembly retired"); }
    private static DhCodecException unowned(String message) { return new DhCodecException(DhCodecException.Kind.UNOWNED, message); }
    /** Drop incomplete transfers under pressure while retaining the session. */
    public void discardAll() {open();buffers.values().forEach(Buffer::close);buffers.clear();requests.clear();}
    @Override public void close() {if(!closed){discardAll();closed=true;}}

    /** Ownership survives assembler retirement until this result is closed. No raw array escapes. */
    public final class Completed implements AutoCloseable {
        private final DhPosition position;
        private final DhMemoryBudget.Lease lease;
        private byte[] data;
        private Completed(DhPosition position, byte[] data, DhMemoryBudget.Lease lease) {
            this.position = position; this.data = data; this.lease = lease;
        }
        public int bytes() { if (data == null) throw new IllegalStateException("DH result released"); return data.length; }
        public DhTerrainTile decode(DhTerrainDecoder decoder, DhProfile profile, DhTerrainTile.WorldShape world) throws DhCodecException {
            if (data == null) throw new IllegalStateException("DH result released");
            DhPosition expected = position == null ? DhPosition.unpack(new DhInput(data).i64()) : position;
            return decoder.decode(data, profile, expected, world, budget);
        }
        /** An ambiguous legacy control exchange cannot authorize terrain. Require
         * one strict successful family decode and a strict rejection by the other.
         * Memory pressure is not evidence of a different family. */
        public DhProfile legacyEvidence(DhTerrainDecoder decoder,DhTerrainTile.WorldShape world) throws DhCodecException {
            DhProfile found=null;
            for(var candidate:new DhProfile[]{DhProfile.OFFICIAL_15,DhProfile.SUPPORT_15}) {
                try(var tile=decode(decoder,candidate,world)) {
                    if(found!=null)return null;
                    found=candidate;
                } catch(DhCodecException e) {
                    if(e.kind()==DhCodecException.Kind.RESOURCE_LIMIT)throw e;
                }
            }
            return found;
        }
        @Override public void close() { data = null; lease.close(); }
    }
}
