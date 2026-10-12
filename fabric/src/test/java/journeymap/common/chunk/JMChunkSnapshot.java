package journeymap.common.chunk;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;

/** Tier-1 stub: carries the frame's chunk position. The real decoder is exercised against
 *  the real JourneyMap jar by JourneyMapDecoderParityTest. */
public class JMChunkSnapshot {
    public final int chunkX;
    public final int chunkZ;

    public JMChunkSnapshot(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public static JMChunkSnapshot decode(FriendlyByteBuf buf, Level level) {
        return new JMChunkSnapshot(buf.readInt(), buf.readInt());
    }
}
