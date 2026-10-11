package journeymap.client.model.chunk;

import journeymap.client.model.block.BlockDataArrays;

/** Tier-1 stub: a chunk wrapper keyed by Minecraft's packed position (x low, z high). */
public class ChunkMD {
    public final int chunkX;
    public final int chunkZ;
    private final boolean levelChunk;
    private final BlockDataArrays blockData = new BlockDataArrays();

    public ChunkMD(int chunkX, int chunkZ, boolean levelChunk) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.levelChunk = levelChunk;
    }

    public boolean hasLevelChunk() { return this.levelChunk; }

    public BlockDataArrays getBlockData() { return this.blockData; }

    public long getLongCoord() { return (this.chunkX & 0xFFFFFFFFL) | ((this.chunkZ & 0xFFFFFFFFL) << 32); }
}
