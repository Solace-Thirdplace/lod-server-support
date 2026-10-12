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
 * <p>DH stores spans top-down and its index arithmetic depends on that. This port
 * walks each column top-down whatever the wire order, and visits columns in DH's own
 * order (z outer, x inner), because the first pass can lower a value that sideways
 * propagation from an earlier column raised.
 */
final class DhSkyLightBake {
    private static final int WIDTH = 64;
    private static final int[] OFFSETS = {-1, 0, +1, 0, 0, -1, 0, +1};
    private static final long SKY = 15L << 56;

    private DhSkyLightBake() {}

    static void bake(long[][] columns, int[] opacity, int maxSkyLight) {
        int[][] order = new int[columns.length][];
        for (int c = 0; c < columns.length; c++) {
            long[] column = columns[c];
            if (column == null || column.length == 0) continue;
            for (int i = 0; i < column.length; i++) column[i] &= ~SKY;
            order[c] = topDown(column);
        }
        for (int z = 0; z < WIDTH; z++) for (int x = 0; x < WIDTH; x++) {
            int c = x * WIDTH + z;
            long[] column = columns[c]; int[] rank = order[c];
            if (rank == null) continue;
            for (int index = 0; index < rank.length; index++) {
                int i = rank[index];
                if (!transparent(column[i], opacity)) continue;
                int light;
                if (index == 0) light = maxSkyLight;
                else {
                    long above = column[rank[index - 1]];
                    if (!transparent(above, opacity)) continue;
                    light = sky(above);
                }
                column[i] = withSky(column[i], light);
                propagate(columns, order, opacity, x, z, column[i]);
            }
        }
        for (int c = 0; c < columns.length; c++) {
            long[] column = columns[c]; int[] rank = order[c];
            if (rank == null) continue;
            for (int index = 0; index < rank.length; index++) {
                int i = rank[index];
                if (index == 0) { column[i] = withSky(column[i], maxSkyLight); continue; }
                if (transparent(column[i], opacity)) continue;
                long above = column[rank[index - 1]];
                int aboveLight = sky(above);
                if (transparent(above, opacity)) column[i] = withSky(column[i], aboveLight);
                else {
                    int absorption = opacity(above, opacity) * DhTerrainTile.height(above);
                    if (absorption < aboveLight) column[i] = withSky(column[i], aboveLight - absorption);
                }
            }
        }
    }

    /** DH's {@code recursivelyLightAdjacentDataPoints}: spread into overlapping transparent spans. */
    private static void propagate(long[][] columns, int[][] order, int[] opacity, int x, int z, long point) {
        int light = sky(point);
        if (light <= 1) return;
        int minY = DhTerrainTile.bottom(point), maxY = minY + DhTerrainTile.height(point);
        for (int o = 0; o < OFFSETS.length; ) {
            int ax = x + OFFSETS[o++], az = z + OFFSETS[o++];
            if (ax < 0 || ax >= WIDTH || az < 0 || az >= WIDTH) continue;
            int c = ax * WIDTH + az;
            long[] column = columns[c]; int[] rank = order[c];
            if (rank == null) continue;
            for (int index = 0; index < rank.length; index++) {
                int i = rank[index];
                long adjacent = column[i];
                int low = DhTerrainTile.bottom(adjacent), high = low + DhTerrainTile.height(adjacent);
                if (low >= maxY) continue;
                if (high <= minY) break;
                if (!transparent(adjacent, opacity)) continue;
                if (light - 1 > sky(adjacent)) {
                    column[i] = withSky(adjacent, light - 1);
                    propagate(columns, order, opacity, ax, az, column[i]);
                }
            }
        }
    }

    private static int[] topDown(long[] column) {
        var boxed = new Integer[column.length];
        for (int i = 0; i < boxed.length; i++) boxed[i] = i;
        java.util.Arrays.sort(boxed, (a, b) -> Integer.compare(DhTerrainTile.bottom(column[b]), DhTerrainTile.bottom(column[a])));
        int[] rank = new int[boxed.length];
        for (int i = 0; i < rank.length; i++) rank[i] = boxed[i];
        return rank;
    }
    private static int opacity(long point, int[] opacity) {
        int id = DhTerrainTile.id(point);
        return id < opacity.length ? opacity[id] : 16;
    }
    private static boolean transparent(long point, int[] opacity) { return opacity(point, opacity) == 0; }
    private static int sky(long point) { return (int) (point >>> 56 & 15); }
    private static long withSky(long point, int light) { return point & ~SKY | (long) (light & 15) << 56; }
}
