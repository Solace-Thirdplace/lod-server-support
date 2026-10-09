package dev.vox.lss.common.dh;

import java.util.List;

/** Transport-neutral control messages. No upstream classes, arbitrary exception construction or MC types. */
public sealed interface DhMessage {
    record CloseReason(String reason) implements DhMessage {}
    record LevelInit(String dimension, String serverKey, String levelKey, long serverTime) implements DhMessage {}
    record RequestLevelInit(String dimension) implements DhMessage {}
    enum GeneratorPlan { SURFACE_THEN_CHUNKS, SURFACE_ONLY, CHUNKS_ONLY, DISABLED }
    record SessionConfig(GeneratorPlan plan, int generationDistance, int centerX, int centerZ, int borderRadius,
                         int generationRate, boolean updates, int updateDistance, boolean synchronize,
                         int syncDistance, int syncRate, int bandwidthKB) implements DhMessage {
        public SessionConfig {
            java.util.Objects.requireNonNull(plan);
            if (generationDistance < 0 || borderRadius < 0 || generationRate < 0 || updateDistance < 0
                    || syncDistance < 0 || syncRate < 0 || bandwidthKB < 0)
                throw new IllegalArgumentException("Negative DH server limit");
        }
    }
    record Cancel(int requestId) implements DhMessage {}
    /** Reason IDs 0..3 are rate-limited, out-of-range, rejected, requires-splitting. Unknown IDs stay data. */
    record Refusal(int requestId, int reason, String message) implements DhMessage {}
    record Request(int requestId, String level, DhPosition position, Long timestamp) implements DhMessage {}
    record Beacon(int x, int y, int z, int rgb) {}
    record Response(int requestId, Integer bufferId, List<Beacon> beacons) implements DhMessage {
        public Response { beacons = List.copyOf(beacons); }
    }
    /** A server-pushed update for the named logical world; its DTO may cover only part of a tile. */
    record PartialUpdate(String level, int bufferId, List<Beacon> beacons) implements DhMessage {
        public PartialUpdate { beacons = List.copyOf(beacons); }
    }
    final class Split implements DhMessage {
        private final int bufferId;
        private final byte[] bytes;
        private final boolean first;
        public Split(int bufferId, byte[] bytes, boolean first) {
            this.bufferId = bufferId; this.bytes = bytes.clone(); this.first = first;
        }
        public int bufferId() { return bufferId; }
        public byte[] bytes() { return bytes.clone(); }
        public int length() { return bytes.length; }
        public boolean first() { return first; }
        /** Package-private immutable borrow for encoding/assembly. Never mutate or expose this array. */
        byte[] contents() { return bytes; }
    }
}
