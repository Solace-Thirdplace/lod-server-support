package journeymap.client.model.map;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Tier-1 stub: day/topo/biome map types per dimension; {@link #DENIED} names refused types. */
public class MapType {
    public static final Set<String> DENIED = new HashSet<>();

    public final String name;
    public final ResourceKey<Level> dimension;

    MapType(String name, ResourceKey<Level> dimension) {
        this.name = name;
        this.dimension = dimension;
    }

    public static MapType day(ResourceKey<Level> dimension) { return new MapType("day", dimension); }
    public static MapType topo(ResourceKey<Level> dimension) { return new MapType("topo", dimension); }
    public static MapType biome(ResourceKey<Level> dimension) { return new MapType("biome", dimension); }

    public boolean isAllowed() { return !DENIED.contains(this.name); }

    @Override public boolean equals(Object o) {
        return o instanceof MapType t && t.name.equals(this.name) && t.dimension.equals(this.dimension);
    }

    @Override public int hashCode() { return this.name.hashCode() * 31 + this.dimension.hashCode(); }

    @Override public String toString() { return this.name; }
}
