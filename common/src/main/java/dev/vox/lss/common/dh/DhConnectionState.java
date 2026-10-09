package dev.vox.lss.common.dh;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Main-thread authority for a single connection, retained across dimension visits. */
public final class DhConnectionState {
    /** Why a dimension stopped acquiring for the rest of this connection. */
    public enum Block { WORLD_CHANGED, VANILLA_LIMIT, DIMENSION_LIMIT }
    /** 65,536 regions x 1,024 chunks: far beyond any realistic session, bounded in memory (~13 MiB). */
    static final int MAX_VANILLA_REGIONS = 65_536;
    static final int MAX_DIMENSIONS = 16;
    private final Map<String, DhSessionPolicy> policies = new HashMap<>();
    private final Map<String, Block> blocked = new HashMap<>();
    private final Map<String, String> worldKeys = new HashMap<>();
    private final Map<String, LinkedHashMap<DhPosition, Long>> loadedAt = new HashMap<>();
    private int vanillaRegions;

    public DhSessionPolicy policy(String dimension) {
        if (!policies.containsKey(dimension) && policies.size() >= MAX_DIMENSIONS) {
            blocked.putIfAbsent(dimension, Block.DIMENSION_LIMIT);
            throw new IllegalStateException("DH dimension authority limit reached; reconnect");
        }
        return policies.computeIfAbsent(dimension, ignored -> new DhSessionPolicy());
    }

    public void vanilla(String dimension, int x, int z) {
        var policy = policy(dimension);
        if (!policy.eligible(x, z)) return;
        if (vanillaRegions >= MAX_VANILLA_REGIONS && !policy.hasVanillaRegion(x, z)) {
            blocked.putIfAbsent(dimension, Block.VANILLA_LIMIT);
            throw new IllegalStateException("DH vanilla authority limit reached; reconnect");
        }
        if (policy.vanilla(x, z)) vanillaRegions++;
    }

    public boolean blocked(String dimension) { return blocked.containsKey(dimension); }
    public Block blockReason(String dimension) { return blocked.get(dimension); }

    /** A changed logical world on one connection cannot reuse receipts or authority. */
    public boolean bindWorld(String dimension, String key) {
        String old = worldKeys.put(dimension, key);
        if (old == null || old.equals(key)) return true;
        blocked.putIfAbsent(dimension, Block.WORLD_CHANGED);
        return false;
    }

    /**
     * Sync-on-load debounce, per connection and dimension (not per session): a tile
     * loaded within the last 20 minutes is not revalidated again on a dimension
     * return or session restart. Bounded like receipts.
     */
    public Map<DhPosition, Long> loadedAt(String dimension) {
        return loadedAt.computeIfAbsent(dimension, ignored -> new LinkedHashMap<>());
    }
    /** Reception resumed or synchronization re-enabled: the next load revalidates. */
    public void forgetLoads(String dimension) { loadedAt.remove(dimension); }

    public void resetReceipts() { policies.values().forEach(DhSessionPolicy::clearReceipts); loadedAt.clear(); }
    public void disconnect() {
        policies.clear(); blocked.clear(); worldKeys.clear(); loadedAt.clear(); vanillaRegions = 0;
    }
}
