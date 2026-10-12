package dev.vox.lss.common.dh;

import org.junit.jupiter.api.Test;
import static dev.vox.lss.common.dh.DhTerrainFixtures.point;
import static org.junit.jupiter.api.Assertions.*;

/**
 * DH 3.3.4 sends real-time pushes before it bakes sky light: a pushed column carries
 * block light and zero sky light, and LSS converted it as pitch-black LOD terrain just
 * past the vanilla view edge (live, 2026-10-12). These pin the port of DH's bake.
 * Material ids: 0 air, 1 stone, 2 snow layer (opaque to DH), 3 water.
 */
class DhSkyLightBakeTest {
    private static final int[] OPACITY = {0, 16, 16, 1};
    private static final int AIR = 0, STONE = 1, SNOW = 2, WATER = 3;

    private static int sky(long point) { return (int) (point >>> 56 & 15); }
    private static int block(long point) { return (int) (point >>> 60 & 15); }
    private static long[][] tile() { return new long[4096][]; }

    @Test void anUnbakedOpenColumnLightsItsAirAndTheFloorUnderIt() {
        var columns = tile();
        columns[0] = new long[]{point(AIR, 72, 312, 0), point(SNOW, 71, 1, 0), point(STONE, 0, 71, 0x70)};
        DhSkyLightBake.bake(columns, OPACITY, 15);
        assertEquals(15, sky(columns[0][0]));
        assertEquals(15, sky(columns[0][1]), "an opaque top block copies the air above it");
        assertEquals(0, sky(columns[0][2]), "an opaque block under an opaque block stays dark");
        assertEquals(7, block(columns[0][2]), "block light is kept");
    }

    @Test void translucentWaterPassesLightDownLessItsDepth() {
        var columns = tile();
        columns[0] = new long[]{point(AIR, 64, 320, 0), point(WATER, 59, 5, 0), point(STONE, 0, 59, 0)};
        DhSkyLightBake.bake(columns, OPACITY, 15);
        assertEquals(15, sky(columns[0][1]));
        assertEquals(10, sky(columns[0][2]), "15 minus opacity 1 times 5 water blocks");
    }

    @Test void skyLightSpreadsSidewaysIntoAnOverlappingCovePocket() {
        var columns = tile();
        columns[0] = new long[]{point(AIR, 60, 324, 0), point(STONE, 0, 60, 0)};
        // Next column (x=0,z=1): a stone roof over an air pocket level with column 0's air.
        columns[1] = new long[]{point(AIR, 80, 304, 0), point(STONE, 79, 1, 0), point(AIR, 62, 17, 0), point(STONE, 0, 62, 0)};
        DhSkyLightBake.bake(columns, OPACITY, 15);
        assertEquals(14, sky(columns[1][2]), "the pocket takes its lit neighbour's light minus one");
        assertEquals(14, sky(columns[1][3]), "and its floor copies the pocket");
    }

    @Test void theWireOrderDoesNotMatter() {
        var topDown = tile(); var bottomUp = tile();
        topDown[0] = new long[]{point(AIR, 64, 320, 0), point(WATER, 59, 5, 0), point(STONE, 0, 59, 0)};
        bottomUp[0] = new long[]{point(STONE, 0, 59, 0), point(WATER, 59, 5, 0), point(AIR, 64, 320, 0)};
        DhSkyLightBake.bake(topDown, OPACITY, 15);
        DhSkyLightBake.bake(bottomUp, OPACITY, 15);
        for (int i = 0; i < 3; i++) assertEquals(topDown[0][i], bottomUp[0][2 - i]);
    }

    @Test void bakingIsIdempotentAndDiscardsStaleSkyLight() {
        var columns = tile();
        columns[5] = new long[]{point(AIR, 10, 374, 0x0f), point(STONE, 0, 10, 0x0f)};
        columns[6] = new long[]{point(AIR, 30, 354, 0x09), point(STONE, 29, 1, 0x0f), point(AIR, 12, 17, 0x0f), point(STONE, 0, 12, 0)};
        DhSkyLightBake.bake(columns, OPACITY, 15);
        long[][] once = new long[4096][];
        for (int c = 0; c < once.length; c++) if (columns[c] != null) once[c] = columns[c].clone();
        DhSkyLightBake.bake(columns, OPACITY, 15);
        for (int c = 0; c < once.length; c++) assertArrayEquals(once[c], columns[c]);
        assertEquals(15, sky(columns[6][0]), "a stale 9 is rebuilt to 15");
    }

    @Test void aNoSkyWorldBakesEverythingToZero() {
        var columns = tile();
        columns[0] = new long[]{point(AIR, 64, 64, 0x0f), point(STONE, 0, 64, 0x5f)};
        DhSkyLightBake.bake(columns, OPACITY, 0);
        assertEquals(0, sky(columns[0][0]));
        assertEquals(0, sky(columns[0][1]));
        assertEquals(5, block(columns[0][1]));
    }
}
