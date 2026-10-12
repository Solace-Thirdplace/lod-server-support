package dev.vox.lss.sponge;

import net.minecraft.commands.CommandSourceStack;

/**
 * The Sponge module's ONE per-line flavor file (sponge-support-plan.md §1.1, the
 * TestPositions pattern): every Minecraft-API axis the Sponge glue touches lives here, so
 * every other file under {@code sponge/} is byte-identical across the five lines. The
 * per-line facts are recorded in docs/planning/per-version-surfaces.md §Sponge.
 *
 * <p>LINE FLAVOR (1.21.1): the command source takes an int op level
 * ({@code Commands.LEVEL_GAMEMASTERS}); 1.21.11+ use the {@code PermissionSet} family.
 */
public final class SpongeLineSurface {
    private SpongeLineSurface() {}

    /**
     * Raises a command source to at least gamemaster level. The shared {@code /lsslod}
     * brigadier tree carries vanilla's op-level gate; on Sponge the Sponge permission
     * node ({@code <brand>.admin}) is the gate, checked before this runs, so the source is
     * elevated to satisfy the tree's own predicate. Never lowers a higher level.
     */
    public static CommandSourceStack elevateToGamemaster(CommandSourceStack source) {
        return source.withMaximumPermission(net.minecraft.commands.Commands.LEVEL_GAMEMASTERS);
    }
}
