package dev.vox.lss.common.dh;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Main-thread authority for a single connection, retained across dimension visits. */
public final class DhConnectionState {
    private final Map<String, DhSessionPolicy> policies = new HashMap<>();
    private final Set<String> blocked = new HashSet<>();
    private final Map<String, String> worldKeys = new HashMap<>();
    private int vanillaColumns;

    public DhSessionPolicy policy(String dimension) {
        if (!policies.containsKey(dimension) && policies.size() >= 16) {
            blocked.add(dimension);
            throw new IllegalStateException("DH dimension authority limit reached; reconnect");
        }
        return policies.computeIfAbsent(dimension, ignored -> new DhSessionPolicy());
    }

    public void vanilla(String dimension, int x, int z) {
        var policy = policy(dimension);
        if (!policy.eligible(x, z)) return;
        if (vanillaColumns >= 262_144) {
            blocked.add(dimension);
            throw new IllegalStateException("DH vanilla authority limit reached; reconnect");
        }
        policy.vanilla(x, z);
        vanillaColumns++;
    }

    public boolean blocked(String dimension) { return blocked.contains(dimension); }

    /** A changed logical world on one connection cannot reuse receipts or authority. */
    public boolean bindWorld(String dimension, String key) {
        String old = worldKeys.put(dimension, key);
        if (old == null || old.equals(key)) return true;
        blocked.add(dimension);
        return false;
    }

    public void resetReceipts() { policies.values().forEach(DhSessionPolicy::clearReceipts); }
    public void disconnect() {
        policies.clear(); blocked.clear(); worldKeys.clear(); vanillaColumns = 0;
    }
}
