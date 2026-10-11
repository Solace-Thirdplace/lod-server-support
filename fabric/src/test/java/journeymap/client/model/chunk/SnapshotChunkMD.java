package journeymap.client.model.chunk;

import journeymap.common.chunk.JMChunkSnapshot;
import net.minecraft.client.multiplayer.ClientLevel;

/** Tier-1 stub: the snapshot-backed wrapper (never a level chunk). */
public class SnapshotChunkMD extends ChunkMD {
    public final JMChunkSnapshot snapshot;
    public final boolean forceRetain;

    public SnapshotChunkMD(ClientLevel level, JMChunkSnapshot snapshot, boolean forceRetain) {
        super(snapshot.chunkX, snapshot.chunkZ, false);
        this.snapshot = snapshot;
        this.forceRetain = forceRetain;
    }
}
