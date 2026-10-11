package dev.vox.lss.sponge;

import dev.vox.lss.platform.SpongeVanishVisibility;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Sponge vanish source (sponge-support-plan.md §1.5), through the injected reader —
 *  {@code Keys} is registry-backed and cannot initialize without a running game. */
class SpongeVanishVisibilityTest {
    private static final UUID TARGET = UUID.randomUUID();

    @Test
    void vanishedTargetIsHiddenFromEveryViewer() {
        var v = new SpongeVanishVisibility((server, target) -> Optional.of(true));
        assertTrue(v.present());
        assertTrue(v.isVanished(null, TARGET));
        assertFalse(v.canSee(null, null, TARGET), "Sponge vanish is global");
    }

    @Test
    void visibleAndOfflineTargetsAreNotVanished() {
        assertFalse(new SpongeVanishVisibility((s, t) -> Optional.of(false)).isVanished(null, TARGET));
        assertFalse(new SpongeVanishVisibility((s, t) -> Optional.empty()).isVanished(null, TARGET));
    }

    @Test
    void anUnreadableStateFailsHidden() {
        var v = new SpongeVanishVisibility((s, t) -> { throw new IllegalStateException("boom"); });
        assertTrue(v.isVanished(null, TARGET), "a leaked vanished admin is the unrecoverable error");
        assertTrue(v.isVanished(null, TARGET), "and stays hidden (once-warned)");
    }
}
