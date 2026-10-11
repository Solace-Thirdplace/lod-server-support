package journeymap.client.properties;

import journeymap.common.properties.config.BooleanField;
import journeymap.common.properties.config.IntegerField;

/** Tier-1 stub: the three cartography settings the bridge reads (JourneyMap defaults). */
public class CoreProperties {
    public final BooleanField mapTopography = new BooleanField(true);
    public final BooleanField mapBiome = new BooleanField(true);
    public final IntegerField renderDelay = new IntegerField(500);
    public final BooleanField mappingEnabled = new BooleanField(true);
    public final IntegerField renderDistanceSurfaceMax = new IntegerField(0);
}
