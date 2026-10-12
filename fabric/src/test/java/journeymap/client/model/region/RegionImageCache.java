package journeymap.client.model.region;

/** Tier-1 stub: counts texture refreshes. */
public class RegionImageCache {
    public static RegionImageCache INSTANCE = new RegionImageCache();

    public int updates;

    public void updateTextures(boolean forceFlush, boolean async) { this.updates++; }
}
