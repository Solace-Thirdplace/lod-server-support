package dev.vox.lss.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vox.lss.common.LSSConstants;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A world's LOD distance of 0 means no LOD service there (per dimension, per Paper world, or
 *  as the default with served worlds listed): the schema accepts it, negatives normalize to it,
 *  the request radius rejects everything, and a legacy JSON 0 (which meant 1) never becomes it. */
class LodDistanceOffTest {
    @Test
    void zeroIsAcceptedWithoutANoteForEveryDistancePath() {
        var decoded = SettingsSchema.server(true).fromValues(Map.of(
                "lod.distance.default_chunks", 0,
                "lod.distance.by_dimension", Map.of("minecraft:the_nether", 0),
                "lod.distance.by_world", Map.of("world_the_end", 0)));
        var distance = decoded.normalized().lod().distance();
        assertEquals(0, distance.defaultChunks());
        assertEquals(0, distance.byDimension().get("minecraft:the_nether"));
        assertEquals(0, distance.byWorld().get("world_the_end"));
        assertTrue(decoded.normalizations().isEmpty(), decoded.normalizations().toString());
    }

    @Test
    void negativeDistancesNormalizeToOffWithANote() {
        var decoded = SettingsSchema.server(false).fromValues(Map.of(
                "lod.distance.by_dimension", Map.of("minecraft:the_end", -5)));
        assertEquals(0, decoded.normalized().lod().distance().byDimension().get("minecraft:the_end"));
        assertTrue(decoded.normalizations().stream().anyMatch(n -> n.path().equals("lod.distance.by_dimension")));
    }

    @Test
    void positiveDistancesKeepTheirRange() {
        var decoded = SettingsSchema.server(false).fromValues(Map.of(
                "lod.distance.by_dimension", Map.of("a:b", 4096, "c:d", 7)));
        var map = decoded.normalized().lod().distance().byDimension();
        assertEquals(2048, map.get("a:b"));
        assertEquals(7, map.get("c:d"));
    }

    @Test
    void theDescriptorsDocumentTheZeroSentinelAndOnlyOnTheDistancePaths() {
        var server = SettingsSchema.server(true).descriptorsByPath();
        for (String path : new String[]{"lod.distance.default_chunks", "lod.distance.by_dimension", "lod.distance.by_world"}) {
            assertTrue(server.get(path).zeroSentinel(), path);
            assertEquals(1.0, server.get(path).minimum(), path);
            assertEquals(2048.0, server.get(path).maximum(), path);
        }
        // A suffix match once gave the client's boolean cache.split_by_world a 1–2048 range.
        assertNull(SettingsSchema.client().descriptorsByPath().get("cache.split_by_world").minimum());
    }

    @Test
    void aLegacyJsonZeroRadiusStaysOne() {
        var settings = LegacySettingsMigration.migrate(
                "{\"lodDistanceChunks\":0}".getBytes(StandardCharsets.UTF_8), SettingsSchema.server(false)).settings();
        assertEquals(1, settings.lod().distance().defaultChunks(), "old JSON clamped 0 to 1; it never meant off");
        assertEquals(1, settings.lod().distance().byDimension().get("minecraft:overworld"));
    }

    @Test
    void theRequestRadiusRejectsEverythingInAnOffWorld() {
        assertEquals(-1, ServerConfigBase.requestRadius(0));
        assertEquals(-1, ServerConfigBase.requestRadius(-3));
        assertEquals(64 + LSSConstants.LOD_DISTANCE_BUFFER, ServerConfigBase.requestRadius(64));
        assertTrue(ServerConfigBase.servesWorld(1));
        assertTrue(!ServerConfigBase.servesWorld(0));
    }

    @Test
    void theUnservedLogFiresOnlyWhenTheOffSetChanges() {
        var lines = new java.util.ArrayList<String>();
        var log = new UnservedWorldsLog((warn, message) -> lines.add((warn ? "WARN " : "") + message));
        log.note(config(512, Map.of()));                                  // boot, all served: silent
        log.note(config(512, Map.of("minecraft:the_nether", 0)));          // nether off
        log.note(config(256, Map.of("minecraft:the_nether", 0)));          // unrelated edit: silent
        log.note(config(256, Map.of()));                                  // back on
        log.note(config(128, Map.of()));                                  // unrelated edit: silent
        log.note(config(0, Map.of()));                                    // everything off
        log.note(config(0, Map.of()));                                    // unchanged: silent
        assertEquals(java.util.List.of(
                "LOD service is off (distance 0) in: minecraft:the_nether",
                "LOD service is on in every world",
                "LOD service is off (distance 0) in: unlisted dimensions (default_chunks)",
                "WARN Every LOD distance is 0: no world is served LODs (far players still run)"), lines);
    }

    private static ServerConfigBase config(int defaultChunks, Map<String, Integer> byDimension) {
        var decoded = SettingsSchema.server(false).fromValues(Map.of(
                "lod.distance.default_chunks", defaultChunks,
                "lod.distance.by_dimension", byDimension));
        return new ServerConfigBase(decoded.normalized(), false);
    }
}
