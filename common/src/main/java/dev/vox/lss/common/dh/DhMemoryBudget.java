package dev.vox.lss.common.dh;

import java.util.concurrent.atomic.AtomicBoolean;

/** Share one instance across assembly, decoding and retained tiles. Reserve BEFORE allocating. */
public final class DhMemoryBudget {
    private final long capacity;
    private long used, peak;

    public DhMemoryBudget(long capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Invalid memory capacity");
        this.capacity = capacity;
    }
    public synchronized Lease reserve(long bytes) throws DhCodecException {
        if (bytes < 0 || bytes > capacity - used)
            throw new DhCodecException(DhCodecException.Kind.RESOURCE_LIMIT, "DH owned-memory budget exceeded");
        used += bytes;
        peak = Math.max(peak, used);
        return new Lease(bytes);
    }
    public synchronized long used() { return used; }
    public synchronized long peak() { return peak; }
    public long capacity() { return capacity; }

    public final class Lease implements AutoCloseable {
        private final long bytes;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Lease(long bytes) { this.bytes = bytes; }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) synchronized (DhMemoryBudget.this) { used -= bytes; }
        }
    }
}
