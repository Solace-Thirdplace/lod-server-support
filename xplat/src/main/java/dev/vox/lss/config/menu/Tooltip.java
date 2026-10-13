package dev.vox.lss.config.menu;

import java.util.List;
import java.util.Objects;

/**
 * An option's tooltip as catalog DATA: a fixed translation key, or a pair of keys
 * chosen by an enumerable {@link Condition} over the {@link MenuContext} at page-build
 * time — the "say so where the user is looking" tooltips (join-slow-start with the
 * governor off, the map ingestion toggle without a supported map mod).
 *
 * <p>The condition is an ENUM rather than a predicate lambda so {@link #keys()} can
 * enumerate every key the tooltip may ever resolve to — that is what lets the catalog
 * test pin lang-file completeness without guessing context combinations.
 */
public record Tooltip(Condition condition, String whenTrueKey, String whenFalseKey, String nativeOnlyKey,
                      String serverBlockedKey) {

    public Tooltip(Condition condition, String whenTrueKey, String whenFalseKey) {
        this(condition, whenTrueKey, whenFalseKey, null, null);
    }

    public Tooltip(Condition condition, String whenTrueKey, String whenFalseKey, String nativeOnlyKey) {
        this(condition, whenTrueKey, whenFalseKey, nativeOnlyKey, null);
    }

    /** A native-only integration has a truthful tooltip while DH is selected. */
    public Tooltip nativeOnly(String key) {
        return new Tooltip(condition, whenTrueKey, whenFalseKey, Objects.requireNonNull(key),
                serverBlockedKey);
    }

    /** Map ingestion's third display-time condition (map-policy plan A4): the connected
     *  server asks clients not to write LOD terrain into map mods — "Turned off by this
     *  server" while that holds, whatever the local setting says. */
    public Tooltip serverBlocked(String key) {
        return new Tooltip(condition, whenTrueKey, whenFalseKey, nativeOnlyKey,
                Objects.requireNonNull(key));
    }

    /** The environment tests a tooltip may switch on. Each maps to one {@link MenuContext}
     *  fact; {@link #ALWAYS} is the fixed-tooltip degenerate case. */
    public enum Condition {
        ALWAYS,
        /** The adaptive transfer governor umbrella is on (join slow start is live). */
        GOVERNOR_ON,
        /** A supported map mod is installed (map ingestion can do something). */
        MAP_MOD_SUPPORTED;

        public boolean test(MenuContext ctx) {
            return switch (this) {
                case ALWAYS -> true;
                case GOVERNOR_ON -> ctx.governorOn();
                case MAP_MOD_SUPPORTED -> ctx.mapModSupported();
            };
        }
    }

    public Tooltip {
        Objects.requireNonNull(condition);
        Objects.requireNonNull(whenTrueKey);
        Objects.requireNonNull(whenFalseKey);
    }

    public static Tooltip fixed(String key) {
        return new Tooltip(Condition.ALWAYS, key, key);
    }

    /** {@code whenTrueKey} when the condition holds in the build context, else {@code whenFalseKey}. */
    public static Tooltip conditional(Condition condition, String whenTrueKey, String whenFalseKey) {
        return new Tooltip(condition, whenTrueKey, whenFalseKey);
    }

    public String resolve(MenuContext ctx) {
        return nativeOnlyKey != null && !ctx.nativeTerrain() ? nativeOnlyKey
                : serverBlockedKey != null && ctx.mapIngestionServerBlocked() ? serverBlockedKey
                : condition.test(ctx) ? whenTrueKey : whenFalseKey;
    }

    /** Facts that can change after a modern page is registered once at game start (the
     *  governor setting, the DH/native terrain source, the connected server's map
     *  request) must be read when the tooltip shows. */
    public boolean resolvesAtDisplay() {
        return condition == Condition.GOVERNOR_ON || nativeOnlyKey != null || serverBlockedKey != null;
    }

    /** Renderers that register once call this from their display-time tooltip callback. */
    public String resolveAtDisplay(MenuContext registered, java.util.function.Supplier<MenuContext> current) {
        return resolve(resolvesAtDisplay() ? current.get() : registered);
    }

    /** Every key this tooltip can resolve to (distinct, in declaration order). */
    public List<String> keys() {
        return java.util.stream.Stream.of(whenTrueKey, whenFalseKey, nativeOnlyKey, serverBlockedKey)
                .filter(Objects::nonNull).distinct().toList();
    }
}
