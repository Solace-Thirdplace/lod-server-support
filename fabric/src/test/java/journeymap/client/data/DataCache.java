package journeymap.client.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import journeymap.client.model.chunk.ChunkMD;

/** Tier-1 stub of JourneyMap's chunk cache. {@link #live} holds the "loaded chunk" wrappers
 *  JourneyMap would build for chunks the client has; {@link #cache} the explicit entries. */
public class DataCache {
    public static DataCache INSTANCE = new DataCache();

    public enum ChunkQueryIntent { LIVE_ONLY, ALLOW_CACHED, SNAPSHOT_ONLY }

    public final Map<Long, ChunkMD> cache = new HashMap<>();
    public final Map<Long, ChunkMD> live = new HashMap<>();
    public final List<Long> added = new ArrayList<>();
    public final List<Long> removed = new ArrayList<>();
    /** Fires inside the first matching renderChunk-time query (a live chunk arriving mid-batch). */
    public Runnable onAllowCachedQuery;
    /** Models the real Guava cache dropping an entry at once (its size cap, an invalidation). */
    public boolean evictEveryAdd;

    public ChunkMD getChunkMD(long coordLong) {
        return getChunkMD(coordLong, ChunkQueryIntent.LIVE_ONLY);
    }

    public ChunkMD getChunkMD(long coordLong, ChunkQueryIntent intent) {
        if (intent == ChunkQueryIntent.ALLOW_CACHED && this.onAllowCachedQuery != null) {
            var hook = this.onAllowCachedQuery;
            this.onAllowCachedQuery = null;
            hook.run();
        }
        ChunkMD liveMd = this.live.get(coordLong);
        if (liveMd != null) {
            this.cache.put(coordLong, liveMd); // JourneyMap caches the live wrapper it builds
            return liveMd;
        }
        ChunkMD cached = this.cache.get(coordLong);
        return intent == ChunkQueryIntent.LIVE_ONLY ? null : cached;
    }

    public void addChunkMD(ChunkMD chunkMD) {
        this.added.add(chunkMD.getLongCoord());
        if (!this.evictEveryAdd) this.cache.put(chunkMD.getLongCoord(), chunkMD);
    }

    public void removeChunkMD(ChunkMD chunkMD) {
        this.cache.remove(chunkMD.getLongCoord());
        this.removed.add(chunkMD.getLongCoord());
    }
}
