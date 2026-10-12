package dev.vox.lss.compat;

import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * The JourneyMap bridge's only per-line flavor file (map-ingestion plan §3.1): the client
 * level's height facts and the biome identifier accessor. Every other JourneyMap file is
 * identical on every line.
 *
 * <p>LINE FLAVOR (1.21.11, same as 26.x): {@code getMinSectionY/getMinY/getMaxY} (max inclusive) and
 * {@code ResourceKey.identifier()}.
 */
final class JourneyMapLineSurface {
    private JourneyMapLineSurface() {}

    /** The client level's vertical layout; {@code maxYExclusive} is one past the top block. */
    record LevelFacts(int minSectionY, int sectionCount, int minY, int maxYExclusive) {}

    static LevelFacts facts(Level level) {
        return new LevelFacts(level.getMinSectionY(), level.getSectionsCount(), level.getMinY(), level.getMaxY() + 1);
    }

    /** The biome's registry identifier; a keyless holder renders as plains (JourneyMap's own fallback). */
    static String biomeId(Holder<Biome> holder) {
        var key = holder == null ? null : holder.unwrapKey().orElse(null);
        return key == null ? "minecraft:plains" : key.identifier().toString();
    }
}
