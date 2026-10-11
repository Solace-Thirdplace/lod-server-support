package dev.vox.lss.platform;

import dev.vox.lss.common.LSSLogger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.UUID;

/**
 * Sponge's far-player vanish source (sponge-support-plan.md §1.5): the native
 * {@code Keys.VANISH_STATE}. Sponge vanish is GLOBAL (no per-viewer rules), so a vanished
 * target is hidden from every far viewer ({@link #canSee} is always false). Offline or
 * unknown targets read as not vanished. A throwing read answers HIDDEN with a once-warn —
 * the {@code MeliusVanishBridge} fail direction (a leaked vanished admin is the
 * unrecoverable error).
 */
public final class SpongeVanishVisibility implements VanishVisibility {

    /** The vanish read — injectable because {@code Keys} is registry-backed and cannot
     *  initialize outside a running game (plain JUnit). Empty = target not online. */
    @FunctionalInterface
    public interface Reader {
        Optional<Boolean> invisible(MinecraftServer server, UUID target);
    }

    /** Production reader: the online player's {@code VANISH_STATE.invisible()}. */
    static final Reader LIVE = (server, target) -> {
        ServerPlayer player = server.getPlayerList().getPlayer(target);
        if (player == null) return Optional.empty();
        var spongePlayer = (org.spongepowered.api.entity.living.player.server.ServerPlayer) (Object) player;
        return Optional.of(spongePlayer.get(org.spongepowered.api.data.Keys.VANISH_STATE)
                .map(org.spongepowered.api.effect.VanishState::invisible)
                .orElse(false));
    };

    private final Reader reader;
    private volatile boolean warned;

    public SpongeVanishVisibility() {
        this(LIVE);
    }

    public SpongeVanishVisibility(Reader reader) {
        this.reader = reader;
    }

    @Override
    public boolean present() {
        return true;
    }

    @Override
    public boolean isVanished(MinecraftServer server, UUID target) {
        try {
            return reader.invisible(server, target).orElse(false);
        } catch (RuntimeException | LinkageError e) {
            if (!warned) {
                warned = true;
                LSSLogger.warn("Sponge vanish state unreadable (" + e
                        + "); hiding affected far-player targets until it reads again");
            }
            return true;
        }
    }

    @Override
    public boolean canSee(MinecraftServer server, ServerPlayer observer, UUID target) {
        return false;
    }
}
