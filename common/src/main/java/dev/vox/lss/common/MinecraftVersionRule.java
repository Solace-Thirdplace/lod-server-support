package dev.vox.lss.common;

import java.util.ArrayList;
import java.util.List;

/**
 * The line's {@code minecraft_dependency} rule as a pure predicate (paper-version-guard-store-plan
 * §3.1) — the same rule {@code fabric.mod.json} declares, which Fabric's loader enforces and
 * Paper does not. Two recorded forms:
 * <ul>
 *   <li>a bare version ({@code 1.21.10}) = exactly that version;</li>
 *   <li>space-separated comparators, all of which must hold ({@code >=26.3 <26.4-}).</li>
 * </ul>
 * Versions compare as numeric tuples, zero-padded ({@code 26.3 == 26.3.0}); a pre-release
 * suffix ({@code 26.4-pre1}) sorts below its release. A bound with a bare trailing
 * {@code -} ({@code 26.4-}) is the lowest pre-release of that version, so {@code <26.4-}
 * refuses every 26.4 build, pre-releases included (Fabric's semver reading).
 */
public final class MinecraftVersionRule {

    private enum Op { EQ, GT, GE, LT, LE }

    private record Comparator(Op op, Version bound) {}

    /** A parsed version: numeric components plus a pre-release flag. A bound ending in a
     *  bare {@code -} is {@code lowest}: below every build of its numeric tuple. */
    private record Version(int[] parts, boolean preRelease, boolean lowest) {}

    private final String raw;
    private final List<Comparator> comparators;

    private MinecraftVersionRule(String raw, List<Comparator> comparators) {
        this.raw = raw;
        this.comparators = comparators;
    }

    /** @throws IllegalArgumentException on an empty or malformed rule */
    public static MinecraftVersionRule parse(String rule) {
        if (rule == null || rule.isBlank()) {
            throw new IllegalArgumentException("empty Minecraft version rule");
        }
        var comparators = new ArrayList<Comparator>();
        for (String token : rule.strip().split("\\s+")) {
            Op op;
            String version;
            if (token.startsWith(">=")) { op = Op.GE; version = token.substring(2); }
            else if (token.startsWith("<=")) { op = Op.LE; version = token.substring(2); }
            else if (token.startsWith(">")) { op = Op.GT; version = token.substring(1); }
            else if (token.startsWith("<")) { op = Op.LT; version = token.substring(1); }
            else if (token.startsWith("=")) { op = Op.EQ; version = token.substring(1); }
            else { op = Op.EQ; version = token; }
            Version bound = parseVersion(version, true);
            if (bound == null) {
                throw new IllegalArgumentException("malformed Minecraft version rule '" + rule + "'");
            }
            comparators.add(new Comparator(op, bound));
        }
        return new MinecraftVersionRule(rule.strip(), List.copyOf(comparators));
    }

    /** Whether {@code running} (e.g. {@code Bukkit.getMinecraftVersion()}) satisfies the rule.
     *  An unparseable running version never does. */
    public boolean accepts(String running) {
        Version v = running == null ? null : parseVersion(running.strip(), false);
        if (v == null) return false;
        for (Comparator c : this.comparators) {
            int cmp = compare(v, c.bound());
            boolean ok = switch (c.op()) {
                case EQ -> cmp == 0;
                case GT -> cmp > 0;
                case GE -> cmp >= 0;
                case LT -> cmp < 0;
                case LE -> cmp <= 0;
            };
            if (!ok) return false;
        }
        return true;
    }

    /** The rule as declared. */
    public String raw() {
        return this.raw;
    }

    /** A human reading for log lines: {@code 1.21.10}, or {@code 26.3 up to (not including) 26.4}
     *  for the recorded range form; anything else verbatim. */
    public String describe() {
        if (this.comparators.size() == 1 && this.comparators.get(0).op() == Op.EQ) {
            return format(this.comparators.get(0).bound());
        }
        if (this.comparators.size() == 2 && this.comparators.get(0).op() == Op.GE
                && this.comparators.get(1).op() == Op.LT) {
            return format(this.comparators.get(0).bound()) + " up to (not including) "
                    + format(this.comparators.get(1).bound());
        }
        return this.raw;
    }

    private static String format(Version v) {
        var sb = new StringBuilder();
        for (int i = 0; i < v.parts().length; i++) {
            if (i > 0) sb.append('.');
            sb.append(v.parts()[i]);
        }
        return sb.toString();
    }

    /** {@code null} when malformed. Only bounds may end in a bare {@code -}. */
    private static Version parseVersion(String s, boolean bound) {
        if (s.isEmpty()) return null;
        String numeric = s;
        boolean pre = false;
        boolean lowest = false;
        int dash = s.indexOf('-');
        if (dash >= 0) {
            numeric = s.substring(0, dash);
            String suffix = s.substring(dash + 1);
            if (suffix.isEmpty()) {
                if (!bound) return null;
                lowest = true;
            } else {
                pre = true;
            }
        }
        String[] pieces = numeric.split("\\.", -1);
        int[] parts = new int[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            String p = pieces[i];
            if (p.isEmpty() || p.length() > 9) return null;
            for (int j = 0; j < p.length(); j++) {
                char c = p.charAt(j);
                if (c < '0' || c > '9') return null;
            }
            parts[i] = Integer.parseInt(p);
        }
        return new Version(parts, pre, lowest);
    }

    /** Numeric tuple first (zero-padded); on a tie a {@code lowest} bound sorts below every
     *  build and a pre-release below its release. */
    private static int compare(Version a, Version b) {
        int n = Math.max(a.parts().length, b.parts().length);
        for (int i = 0; i < n; i++) {
            int x = i < a.parts().length ? a.parts()[i] : 0;
            int y = i < b.parts().length ? b.parts()[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return Integer.compare(rank(a), rank(b));
    }

    private static int rank(Version v) {
        if (v.lowest()) return 0;
        return v.preRelease() ? 1 : 2;
    }
}
