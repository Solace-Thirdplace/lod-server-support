package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import net.minecraft.server.level.ChunkTrackingView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The vanilla-view exclusion ({@link SpiralScanner#isVanillaRendered}) against THIS line's
 * vanilla — the 1.21.1 corner-seam fix (2026-10-14). LSS leaves to vanilla exactly the chunks
 * vanilla delivers together with their whole 8-neighbourhood: Xaero's native writer maps a
 * client chunk only when all 8 neighbours are loaded, so an in-view chunk whose outward
 * diagonal neighbour vanilla never sends got neither vanilla map data nor LOD.
 *
 * <p>The server's send test is {@code ChunkTrackingView.isWithinDistance(…, includeOuter=true)}.
 * From 1.21.2 on it pads both axes by one chunk ({@code max(0,|d|-2)}), so every in-view chunk
 * ({@code isInViewDistance}) has its whole neighbourhood and the exclusion is unchanged. 1.21.1
 * pads only the DOMINANT axis ({@code i=max(0,|dx|-1), j=max(0,|dz|-1);
 * max(0,max(i,j)-1)² + min(i,j)² < R²}), so at R=4 the outward diagonals (±4,±5)/(±5,±4) of
 * the in-view cells (±3,±4)/(±4,±3) are never sent — the eight black Xaero tiles of the
 * 2026-10-14 C-1.21.1 manual run. Those eight now fall through to LOD on 1.21.1 only.
 *
 * <p>Identical on every line: the oracle tests ask the line's own vanilla, and the shape pins
 * branch on {@code gradle.properties minecraft_version}.
 */
class VanillaViewExclusionTest {

    private static final int MAX_VD = 64; // past any realistic render-distance mod
    private static boolean dominantAxisPadLine;

    @BeforeAll
    static void line() throws Exception {
        String mc = null;
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null && mc == null; i++, dir = dir.getParent()) {
            Path props = dir.resolve("gradle.properties");
            if (!Files.exists(props)) continue;
            for (String line : Files.readAllLines(props)) {
                String s = line.strip();
                if (s.startsWith("minecraft_version=")) mc = s.substring("minecraft_version=".length()).strip();
            }
        }
        assertNotNull(mc, "gradle.properties minecraft_version not found");
        dominantAxisPadLine = mc.equals("1.21.1");
    }

    /** The independent oracle: all 9 cells of the neighbourhood pass vanilla's send test. */
    private static boolean neighbourhoodSent(int dx, int dz, int vd) {
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (!ChunkTrackingView.isWithinDistance(0, 0, vd, dx + a, dz + b, true)) return false;
            }
        }
        return true;
    }

    /** The exclusion before this fix: ChunkTrackingView.isInViewDistance, written out. */
    private static boolean legacyInView(int dx, int dz, int vd) {
        long a = Math.max(0, Math.abs(dx) - 1);
        long b = Math.max(0, Math.abs(dz) - 1);
        return a * a + b * b < (long) vd * vd;
    }

    /** The 1.21.1 shape, closed form: the outward diagonal's dominant-axis-padded test. */
    private static boolean dominantAxisClosedForm(int dx, int dz, int vd) {
        long hi = Math.max(0, Math.max(Math.abs(dx), Math.abs(dz)) - 1);
        long lo = Math.min(Math.abs(dx), Math.abs(dz));
        return hi * hi + lo * lo < (long) vd * vd;
    }

    private static final int[][] SEAM_CELLS_VD4 = {
            {3, 4}, {4, 3}, {-3, 4}, {-4, 3}, {3, -4}, {4, -3}, {-3, -4}, {-4, -3}};

    @Test
    void exclusionIsExactlyTheNeighbourhoodVanillaDelivers() {
        // Every line: excluded ⇔ the chunk and all 8 neighbours are in vanilla's send set —
        // checked cell by cell against vanilla (the production shortcut asks only the outward
        // diagonal, valid because the send test is monotone in |dx|,|dz|). Off-origin player
        // too, so the translation and the sign handling are covered.
        int mismatches = 0;
        int[][] centers = {{0, 0}, {-17, 29}, {1_000_003, -999_983}};
        for (int[] c : centers) {
            for (int vd = 0; vd <= MAX_VD; vd++) {
                int reach = vd + 3;
                for (int dx = -reach; dx <= reach; dx++) {
                    for (int dz = -reach; dz <= reach; dz++) {
                        boolean excluded = SpiralScanner.isVanillaRendered(c[0] + dx, c[1] + dz, c[0], c[1], vd);
                        if (excluded != neighbourhoodSent(dx, dz, vd)) mismatches++;
                    }
                }
            }
        }
        assertEquals(0, mismatches,
                "the exclusion must be exactly the chunks vanilla sends with their whole 8-neighbourhood");
    }

    @Test
    void exclusionNeverExceedsVanillasOwnView() {
        // Every line: LSS never leaves to vanilla a chunk vanilla's renderer does not treat as
        // in view (the blank-corner regression class) — the exclusion sits inside isInViewDistance.
        for (int vd = 0; vd <= MAX_VD; vd++) {
            for (int dx = -vd - 3; dx <= vd + 3; dx++) {
                for (int dz = -vd - 3; dz <= vd + 3; dz++) {
                    // isInViewDistance itself is the same on every line (the formula the
                    // pre-fix exclusion copied verbatim — only the SEND pad differs).
                    assertEquals(legacyInView(dx, dz, vd), ChunkTrackingView.isInViewDistance(0, 0, vd, dx, dz),
                            "vd " + vd + " (" + dx + "," + dz + "): isInViewDistance shape");
                    if (SpiralScanner.isVanillaRendered(dx, dz, 0, 0, vd)) {
                        assertTrue(ChunkTrackingView.isInViewDistance(0, 0, vd, dx, dz),
                                "vd " + vd + ": excluded (" + dx + "," + dz + ") is outside vanilla's view");
                    }
                }
            }
        }
    }

    @Test
    void lineShapeIsPinned() {
        if (dominantAxisPadLine) {
            // The vanilla fact behind the seam (bytecode: 1.21.1 isWithinDistance(IIIIIZ)Z
            // subtracts the outer pad from max(i,j) only).
            assertTrue(ChunkTrackingView.isWithinDistance(0, 0, 4, 4, 4, true), "(4,4) is sent at vd 4");
            assertFalse(ChunkTrackingView.isWithinDistance(0, 0, 4, 5, 4, true),
                    "1.21.1 does NOT send (5,4) at vd 4 — the outward diagonal of the in-view (4,3)");
            for (int vd = 0; vd <= MAX_VD; vd++) {
                for (int dx = -vd - 3; dx <= vd + 3; dx++) {
                    for (int dz = -vd - 3; dz <= vd + 3; dz++) {
                        assertEquals(dominantAxisClosedForm(dx, dz, vd),
                                SpiralScanner.isVanillaRendered(dx, dz, 0, 0, vd),
                                "1.21.1 closed form, vd " + vd + " (" + dx + "," + dz + ")");
                    }
                }
            }
            // The exact cells this fix hands to LOD: in-view (the old exclusion) but not
            // neighbourhood-delivered. vd 4 = the eight black Xaero tiles; vd 2 = the corners.
            assertEquals(cells(SEAM_CELLS_VD4), seamCells(4));
            assertEquals(cells(new int[][]{{2, 2}, {-2, 2}, {2, -2}, {-2, -2}}), seamCells(2));
            assertEquals(16, seamCells(8).size(), "vd 8 (the C-1.21.1 client's RD) seam size");
        } else {
            // 1.21.2+: both axes padded — the fix is the identity here.
            assertTrue(ChunkTrackingView.isWithinDistance(0, 0, 4, 5, 4, true),
                    "1.21.2+ sends (5,4) at vd 4 (both axes padded)");
            for (int vd = 0; vd <= MAX_VD; vd++) {
                for (int dx = -vd - 3; dx <= vd + 3; dx++) {
                    for (int dz = -vd - 3; dz <= vd + 3; dz++) {
                        assertEquals(legacyInView(dx, dz, vd),
                                SpiralScanner.isVanillaRendered(dx, dz, 0, 0, vd),
                                "1.21.2+ exclusion must stay isInViewDistance, vd " + vd
                                        + " (" + dx + "," + dz + ")");
                    }
                }
            }
            assertTrue(seamCells(4).isEmpty());
        }
        // Every line: the axis edges stay excluded (the step-1 re-request loop's cells).
        for (int vd = 1; vd <= MAX_VD; vd++) {
            for (int[] edge : new int[][]{{vd, 0}, {-vd, 0}, {0, vd}, {0, -vd}}) {
                assertTrue(SpiralScanner.isVanillaRendered(edge[0], edge[1], 0, 0, vd),
                        "vd " + vd + ": axis edge (" + edge[0] + "," + edge[1] + ") must stay excluded");
            }
        }
    }

    private static Set<Long> cells(int[][] coords) {
        var out = new HashSet<Long>();
        for (int[] c : coords) out.add(PositionUtil.packPosition(c[0], c[1]));
        return out;
    }

    /**
     * The corner-seam cells around (0,0), from the vanilla oracle (not the production
     * predicate): in vanilla's view ({@code isInViewDistance}, the pre-fix exclusion) but
     * missing a delivered neighbour, so LOD-requested now. Empty on 1.21.2+ (pinned above);
     * {@link SpiralScannerTest}'s small-view fixtures seed these so their "the radius-vd
     * view is vanilla's" premise holds on every line.
     */
    static Set<Long> seamCells(int vd) {
        var out = new HashSet<Long>();
        for (int dx = -vd - 1; dx <= vd + 1; dx++) {
            for (int dz = -vd - 1; dz <= vd + 1; dz++) {
                if (ChunkTrackingView.isInViewDistance(0, 0, vd, dx, dz) && !neighbourhoodSent(dx, dz, vd)) {
                    out.add(PositionUtil.packPosition(dx, dz));
                }
            }
        }
        return out;
    }

    // ---- the walk: both scanners declare the seam cells and then settle ----

    private static final int VD = 4;
    private static final int LOD = 6;

    private static <T extends SpiralScanner> T configured(T s) {
        s.setConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION, true, LOD, true));
        return s;
    }

    private static final class Walk {
        final long[] pos = new long[LSSConstants.MAX_BATCH_CHUNK_REQUESTS];
        final long[] ts = new long[LSSConstants.MAX_BATCH_CHUNK_REQUESTS];

        Set<Long> fire(SpiralScanner s, int cx, int cz, ColumnStateMap columns) {
            for (int i = 0; i < LSSConstants.TICKS_PER_SECOND + 1; i++) {
                int n = s.maybeScan(cx, cz, VD, 0, 1000, 0L, Long.MAX_VALUE, -1, 1000,
                        () -> 0, columns, this.pos, this.ts);
                if (n >= 0) {
                    assertTrue(n < LSSConstants.WANT_SET_BUDGET, "premise: an untruncated walk");
                    var out = new HashSet<Long>();
                    for (int k = 0; k < n; k++) out.add(this.pos[k]);
                    return out;
                }
            }
            throw new AssertionError("scan cadence never fired");
        }
    }

    private static void serve(ColumnStateMap columns, Set<Long> declared) {
        for (long p : declared) {
            columns.onReceived(p, 1000L);
            columns.onUpToDate(p);
        }
    }

    /** Both walks: the legacy ring walk and the hybrid region walk production runs. */
    private static List<Supplier<SpiralScanner>> scanners() {
        return List.of(() -> configured(new SpiralScanner()), () -> configured(new RegionScanner()));
    }

    @Test
    void scannersDeclareTheSeamCellsAndNeverTheAxisEdges() {
        for (var make : scanners()) {
            var s = make.get();
            String who = s.getClass().getSimpleName();
            var declared = new Walk().fire(s, 0, 0, new ColumnStateMap());
            for (int[] c : SEAM_CELLS_VD4) {
                assertEquals(dominantAxisPadLine, declared.contains(PositionUtil.packPosition(c[0], c[1])),
                        who + ": seam cell (" + c[0] + "," + c[1] + ") is LOD-requested exactly on the"
                                + " dominant-axis-pad line (1.21.1)");
            }
            for (int k = -2; k <= 2; k++) {
                for (int[] c : new int[][]{{VD, k}, {-VD, k}, {k, VD}, {k, -VD}}) {
                    assertFalse(declared.contains(PositionUtil.packPosition(c[0], c[1])),
                            who + ": axis edge (" + c[0] + "," + c[1] + ") must never be requested");
                }
            }
            // The whole want-set is exactly the non-excluded universe (ring 0 is never declared).
            for (int dx = -LOD; dx <= LOD; dx++) {
                for (int dz = -LOD; dz <= LOD; dz++) {
                    boolean want = (dx != 0 || dz != 0) && !SpiralScanner.isVanillaRendered(dx, dz, 0, 0, VD);
                    assertEquals(want, declared.contains(PositionUtil.packPosition(dx, dz)),
                            who + ": (" + dx + "," + dz + ") want-set membership");
                }
            }
        }
    }

    @Test
    void servedSeamCellsSettleAndNeverReRequestUnderMovement() {
        // The loop pin: once a declared position is served it is never declared again — while
        // stationary (the scan settles to an empty want-set and confirms to lod+1) and while
        // the player oscillates across a chunk border, which flips cells in and out of the
        // exclusion (the trailing crescent incl. the seam cells is declared ONCE, then stays
        // satisfied). Cells re-entering the exclusion are simply skipped again.
        for (var make : scanners()) {
            var s = make.get();
            String who = s.getClass().getSimpleName();
            var columns = new ColumnStateMap();
            var walk = new Walk();
            var served = new HashSet<Long>();

            var first = walk.fire(s, 0, 0, columns);
            assertFalse(first.isEmpty());
            serve(columns, first);
            served.addAll(first);
            for (int i = 0; i < 5; i++) {
                assertEquals(Set.of(), walk.fire(s, 0, 0, columns),
                        who + ": a served disc must settle (stationary scan " + i + ")");
            }
            assertEquals(LOD + 1, s.getConfirmedRing(), who + ": confirmation advances through the seam ring");

            int x = 0;
            int newDeclarationsAfterFirstRoundTrip = 0;
            for (int move = 1; move <= 8; move++) {
                x = 1 - x; // (0,0) <-> (1,0)
                s.recenter(1);
                var declared = walk.fire(s, x, 0, columns);
                for (long p : declared) {
                    assertFalse(served.contains(p), who + ": move " + move + " re-requested served ("
                            + PositionUtil.unpackX(p) + "," + PositionUtil.unpackZ(p) + ") — a re-request loop");
                }
                if (move > 2) newDeclarationsAfterFirstRoundTrip += declared.size();
                serve(columns, declared);
                served.addAll(declared);
                assertEquals(Set.of(), walk.fire(s, x, 0, columns), who + ": move " + move + " settles");
                // ...and settles COVERED: every cell the exclusion does not leave to vanilla
                // around the new center was declared at some point (the crescent band reaches
                // the seam cells exiting the exclusion — no orphan behind the player).
                for (int dx = -LOD; dx <= LOD; dx++) {
                    for (int dz = -LOD; dz <= LOD; dz++) {
                        if ((dx == 0 && dz == 0) || SpiralScanner.isVanillaRendered(x + dx, dz, x, 0, VD)) continue;
                        assertEquals(ColumnStateMap.SATISFIED, columns.classify(PositionUtil.packPosition(x + dx, dz)),
                                who + ": move " + move + " orphaned (" + (x + dx) + "," + dz + ")");
                    }
                }
            }
            assertEquals(0, newDeclarationsAfterFirstRoundTrip,
                    who + ": after one round trip the oscillation declares nothing");
        }
    }
}
