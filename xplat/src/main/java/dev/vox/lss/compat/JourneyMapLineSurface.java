package dev.vox.lss.compat;

import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * The JourneyMap bridge's only per-line flavor file (map-ingestion plan §3.1): the client
 * level's height facts and the biome identifier accessor. Every other JourneyMap file is
 * identical on every line.
 *
 * <p>LINE FLAVOR (1.21.10): {@code getMinSectionY/getMinY/getMaxY} (max inclusive) and
 * {@code ResourceKey.location()} (26.x and 1.21.11: {@code identifier()}).
 */
final class JourneyMapLineSurface {
    private JourneyMapLineSurface() {}

    /** What the unsupported state tells the player (diag line, binding failure): an upgrade
     *  hint where a supported JourneyMap exists for this line, a plain statement where none does. */
    static final String UNSUPPORTED_HINT = "JourneyMap is not supported on Minecraft 1.21.10: its only build, a 6.0.0 beta, lacks the chunk-snapshot layer";

    /** The client level's vertical layout; {@code maxYExclusive} is one past the top block. */
    record LevelFacts(int minSectionY, int sectionCount, int minY, int maxYExclusive) {}

    static LevelFacts facts(Level level) {
        return new LevelFacts(level.getMinSectionY(), level.getSectionsCount(), level.getMinY(), level.getMaxY() + 1);
    }

    /** The biome's registry identifier; a keyless holder renders as plains (JourneyMap's own fallback). */
    static String biomeId(Holder<Biome> holder) {
        var key = holder == null ? null : holder.unwrapKey().orElse(null);
        return key == null ? "minecraft:plains" : key.location().toString();
    }
}
