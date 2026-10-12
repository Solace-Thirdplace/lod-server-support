package dev.vox.lss.common.dh;

/**
 * Official DH 3.3.4's sky-light bake ({@code DhLightingEngine.bakeDataSourceSkyLight}),
 * ported line for line. DH 3.3.x sends real-time updates before this bake runs, so a
 * pushed column arrives with block light but zero sky light; a DH client re-bakes every
 * full-detail tile when it stores it ({@code FullDataUpdaterV2}). Stored replies are
 * already baked server-side.
 *
 * <p>Opacity follows DH's {@code BlockStateWrapper}: 0 transparent, 1 a translucent
 * fluid, 16 opaque. Only 0 lets light into a span; translucent spans attenuate the
 * light passed down to the span below by {@code opacity * height}.
 *
 * <p>DH stores spans top-down and its index arithmetic depends on that. Columns are put
 * in that order in place first (DH's own output already is; the decoder guarantees
 * unique bottoms), and columns are visited in DH's order (z outer, x inner), because the
 * first pass can lower a value that sideways propagation from an earlier column raised.
 *
 * <p>The sideways spread is capped at {@link #SPREAD_BUDGET} adjacent-span visits: a
 * hostile tile of one-block spans would otherwise cost seconds to minutes. Real tiles stay
 * far below it (at most 2.04M over 365 stored tiles, 2026-10-12); past the cap only the
 * sideways spread stops and the vertical light stays exact.
 */
final class DhSkyLightBake {
    private static final int WIDTH = 64;
    private static final int[] OFFSETS = {-1, 0, +1, 0, 0, -1, 0, +1};
    private static final long SKY = 15L << 56;
    static final long SPREAD_BUDGET = 16_000_000L;

    private DhSkyLightBake() {}

    /** @return false when the sideways spread used up {@link #SPREAD_BUDGET} and stopped early. */
    static boolean bake(long[][] columns, int[] opacity, int maxSkyLight) {
        return bake(columns, opacity, maxSkyLight, SPREAD_BUDGET) >= 0;
    }
    /** @return the unused spread budget; negative when the spread stopped early. */
    static long bake(long[][] columns, int[] opacity, int maxSkyLight, long spreadBudget) {
        for (long[] column : columns) {
            if (column == null || column.length == 0) continue;
            for (int i = 0; i < column.length; i++) column[i] &= ~SKY;
            topDown(column);
        }
        long[] budget = {spreadBudget};
        for (int z = 0; z < WIDTH; z++) for (int x = 0; x < WIDTH; x++) {
            long[] column = columns[x * WIDTH + z];
            if (column == null) continue;
            for (int index = 0; index < column.length; index++) {
                if (!transparent(column[index], opacity)) continue;
                int light;
                if (index == 0) light = maxSkyLight;
                else {
                    long above = column[index - 1];
                    if (!transparent(above, opacity)) continue;
                    light = sky(above);
                }
                column[index] = withSky(column[index], light);
                propagate(columns, opacity, x, z, column[index], budget);
            }
        }
        for (long[] column : columns) {
            if (column == null) continue;
            for (int index = 0; index < column.length; index++) {
                if (index == 0) { column[index] = withSky(column[index], maxSkyLight); continue; }
                if (transparent(column[index], opacity)) continue;
                long above = column[index - 1];
                int aboveLight = sky(above);
                if (transparent(above, opacity)) column[index] = withSky(column[index], aboveLight);
                else {
                    int absorption = opacity(above, opacity) * DhTerrainTile.height(above);
                    if (absorption < aboveLight) column[index] = withSky(column[index], aboveLight - absorption);
                }
            }
        }
        return budget[0];
    }

    /** DH's {@code recursivelyLightAdjacentDataPoints}: spread into overlapping transparent spans. */
    private static void propagate(long[][] columns, int[] opacity, int x, int z, long point, long[] budget) {
        int light = sky(point);
        if (light <= 1) return;
        int minY = DhTerrainTile.bottom(point), maxY = minY + DhTerrainTile.height(point);
        for (int o = 0; o < OFFSETS.length; ) {
            int ax = x + OFFSETS[o++], az = z + OFFSETS[o++];
            if (ax < 0 || ax >= WIDTH || az < 0 || az >= WIDTH) continue;
            long[] column = columns[ax * WIDTH + az];
            if (column == null) continue;
            for (int index = 0; index < column.length; index++) {
                if (--budget[0] < 0) return;
                long adjacent = column[index];
                int low = DhTerrainTile.bottom(adjacent), high = low + DhTerrainTile.height(adjacent);
                if (low >= maxY) continue;
                if (high <= minY) break;
                if (!transparent(adjacent, opacity)) continue;
                if (light - 1 > sky(adjacent)) {
                    column[index] = withSky(adjacent, light - 1);
                    propagate(columns, opacity, ax, az, column[index], budget);
                }
            }
        }
    }

    /** Reorders a column top-down in place; already-ordered columns (DH's output) are untouched. */
    private static void topDown(long[] column) {
        boolean ordered = true;
        for (int i = 1; i < column.length && ordered; i++)
            ordered = DhTerrainTile.bottom(column[i]) < DhTerrainTile.bottom(column[i - 1]);
        if (ordered) return;
        long[] keys = new long[column.length];
        for (int i = 0; i < keys.length; i++) keys[i] = (long) DhTerrainTile.bottom(column[i]) << 32 | i;
        java.util.Arrays.sort(keys);
        long[] original = column.clone();
        for (int i = 0; i < keys.length; i++) column[i] = original[(int) keys[keys.length - 1 - i]];
    }
    private static int opacity(long point, int[] opacity) {
        int id = DhTerrainTile.id(point);
        return id < opacity.length ? opacity[id] : 16;
    }
    private static boolean transparent(long point, int[] opacity) { return opacity(point, opacity) == 0; }
    private static int sky(long point) { return (int) (point >>> 56 & 15); }
    private static long withSky(long point, int light) { return point & ~SKY | (long) (light & 15) << 56; }
}
