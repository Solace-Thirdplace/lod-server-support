package journeymap.client.model.region;

import java.io.File;
import journeymap.client.model.map.MapType;

/** Tier-1 stub: a value carrying what {@code fromChunkPos} was given. */
public class RegionCoord {
    public final File worldDir;
    public final MapType mapType;
    public final int regionX;
    public final int regionZ;

    RegionCoord(File worldDir, MapType mapType, int regionX, int regionZ) {
        this.worldDir = worldDir;
        this.mapType = mapType;
        this.regionX = regionX;
        this.regionZ = regionZ;
    }

    public static RegionCoord fromChunkPos(File worldDir, MapType mapType, int chunkX, int chunkZ) {
        return new RegionCoord(worldDir, mapType, chunkX >> 5, chunkZ >> 5);
    }
}
