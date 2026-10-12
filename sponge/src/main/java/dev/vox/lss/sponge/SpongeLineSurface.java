package dev.vox.lss.sponge;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.LevelBasedPermissionSet;

/**
 * The Sponge module's ONE per-line flavor file (sponge-support-plan.md §1.1, the
 * TestPositions pattern): every Minecraft-API axis the Sponge glue touches lives here, so
 * every other file under {@code sponge/} is byte-identical across the five lines. The
 * per-line facts are recorded in docs/planning/per-version-surfaces.md §Sponge.
 *
 * <p>LINE FLAVOR (26.3): the command-source permission model is the 1.21.11+
 * {@code PermissionSet} family; 1.21.1–1.21.10 take an int op level.
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
        return source.withMaximumPermission(LevelBasedPermissionSet.GAMEMASTER);
    }
}
