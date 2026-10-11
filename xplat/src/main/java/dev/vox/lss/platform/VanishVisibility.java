package dev.vox.lss.platform;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * The far-player vanish seam (sponge-support-plan.md §1.5): which loader-side vanish
 * source decides whether a far-player TARGET is hidden from a viewer. The default is the
 * reflective Melius Vanish bridge (Fabric/NeoForge); a loader with a native vanish state
 * (Sponge's {@code Keys.VANISH_STATE}) supplies its own through
 * {@link LoaderServices#vanishVisibility()}.
 *
 * <p>The shape is the bridge's own three calls, so {@code RequestProcessingService}'s
 * {@link #present()} short-circuit and its per-tick {@link #isVanished} memo stay exactly
 * as they were. Contract: implementations never throw; doubt answers HIDDEN (a leaked
 * vanished admin is the unrecoverable error, an over-hidden far-player set is not).
 */
public interface VanishVisibility {

    /** False = no vanish source exists on this server; every target is visible. */
    boolean present();

    /** Whether {@code target} is vanished at all (the per-tick memoized fast path). */
    boolean isVanished(MinecraftServer server, UUID target);

    /** Whether {@code observer} may see the vanished {@code target}. */
    boolean canSee(MinecraftServer server, ServerPlayer observer, UUID target);
}
