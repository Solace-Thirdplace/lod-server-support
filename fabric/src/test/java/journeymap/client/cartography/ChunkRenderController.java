package journeymap.client.cartography;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import journeymap.client.model.chunk.ChunkMD;
import journeymap.client.model.map.MapType;
import journeymap.client.model.region.RegionCoord;

/** Tier-1 stub: records every render and answers with {@link #result}. */
public class ChunkRenderController {
    public record Render(RegionCoord region, MapType type, ChunkMD chunk) {}

    public final List<Render> renders = new ArrayList<>();
    public BiPredicate<MapType, ChunkMD> result = (type, chunk) -> true;
    public RuntimeException throwing;

    public boolean renderChunk(RegionCoord rCoord, MapType mapType, ChunkMD chunkMd) {
        if (this.throwing != null) throw this.throwing;
        this.renders.add(new Render(rCoord, mapType, chunkMd));
        return this.result.test(mapType, chunkMd);
    }
}
