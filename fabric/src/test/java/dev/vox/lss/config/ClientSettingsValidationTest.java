package dev.vox.lss.config;

import dev.vox.lss.common.config.*;
import dev.vox.lss.config.menu.RateSliderStops;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Replaces the client flat-field clamps and JSON scratch round trips with the real schema/store. */
class ClientSettingsValidationTest {
    @TempDir Path directory;
    final SettingsSchema<ClientSettings> schema = SettingsSchema.client();

    @Test void defaultsAndEveryBooleanRoundTripThroughYaml() throws Exception {
        var store = new SettingsStore<>(directory, "lss", schema);
        var initial = store.initialize();
        assertTrue(initial.normalized().lod().receive());
        assertTrue(initial.normalized().integrations().mapIngestion().enabled());
        assertTrue(initial.normalized().integrations().mapIngestion().backpressure());
        assertTrue(initial.normalized().compatibility().protocols().v16());
        assertTrue(initial.normalized().compatibility().protocols().v19());
        assertTrue(initial.normalized().compatibility().v16Generation());
        var changes = new LinkedHashMap<String,Object>();
        schema.descriptors().stream().filter(d -> d.kind() == SettingsSchema.Kind.BOOLEAN)
                .forEach(d -> changes.put(d.path(), !(Boolean)d.defaultValue()));
        store.saveDraft(initial.hash(), changes);
        changes.forEach((key,value) -> assertEquals(value, assertDoesNotThrow(store::read).configured().values().get(key)));
        assertEquals(schema.defaults(), initial.normalized(), "saving does not mutate the previously published immutable snapshot");
    }
    @Test void downloadAndDistanceClampsPreserveZeroAndUiStops() {
        for (int[] row : new int[][]{{-1,0},{0,0},{99999,2048}})
            assertEquals(row[1], schema.fromValues(Map.of("lod.distance_chunks",row[0])).normalized().lod().distanceChunks());
        for (int[] row : new int[][]{{-5,0},{0,0},{5,10},{20,20},{1000000,100000},{3200,3200}})
            assertEquals(row[1], schema.fromValues(Map.of("lod.download.max_columns_per_second",row[0])).normalized().lod().download().maxColumnsPerSecond());
        for (int i=0;i<RateSliderStops.STOPS.length;i++) {
            int stop=RateSliderStops.STOPS[i];
            assertEquals(stop,schema.fromValues(Map.of("lod.download.max_columns_per_second",stop)).normalized().lod().download().maxColumnsPerSecond());
            assertEquals(i,RateSliderStops.nearestIndex(stop));
        }
        assertEquals(1,RateSliderStops.nearestIndex(1));
        assertEquals(0,RateSliderStops.nearestIndex(-7));
        assertEquals(RateSliderStops.STOPS.length-1,RateSliderStops.nearestIndex(100000));
        assertTrue(Arrays.stream(RateSliderStops.STOPS).anyMatch(value -> value==20));
    }
    private ClientSettings migrate(String source) throws Exception {
        Files.writeString(directory.resolve("lss-client-config.json"),source);
        var store = new SettingsStore<>(directory,"lss",schema);
        var value = store.initialize().normalized();
        assertEquals(source,Files.readString(directory.resolve("lss-client-config.json")));
        assertEquals(value,store.read().normalized());
        return value;
    }
    @Test void migrationKeepsZeroDistanceAndReceiveOptOut() throws Exception {
        var value=migrate("{\"receiveServerLods\":false,\"lodDistanceChunks\":0}");
        assertFalse(value.lod().receive());
        assertEquals(0,value.lod().distanceChunks());
    }
    @Test void migrationNullFallbacksUseHistoricalDefaultsWithoutRewritingJson() throws Exception {
        var value=migrate("{\"receiveServerLods\":false,\"unknownBlockFallback\":null,\"crossVersionBlockFallbacks\":null}");
        assertFalse(value.lod().receive());
        assertEquals("minecraft:stone",value.compatibility().blockFallbacks().defaultBlock());
        assertEquals(Map.of(),value.compatibility().blockFallbacks().overrides());
    }
    @Test void migrationNumericCuratedEntryPreservesLegacyStringCoercion() throws Exception {
        var value=migrate("{\"receiveServerLods\":false,\"crossVersionBlockFallbacks\":{\"ancient:sulfur\":5}}");
        assertFalse(value.lod().receive());
        assertEquals("5",value.compatibility().blockFallbacks().overrides().get("ancient:sulfur"));
    }
    @Test void malformedCuratedCollectionLeavesClientInactiveAndSourceIntact() throws Exception {
        String source="{\"receiveServerLods\":false,\"crossVersionBlockFallbacks\":[\"minecraft:sulfur\"]}";
        Files.writeString(directory.resolve("lss-client-config.json"),source);
        var facade=new LSSClientConfig(directory);
        assertFalse(facade.receiveServerLods());
        assertFalse(facade.farPlayersShareSelf());
        assertNotNull(facade.error());
        assertEquals(source,Files.readString(directory.resolve("lss-client-config.json")));
        assertFalse(Files.exists(directory.resolve("lss-client-config.yaml")));
    }
    @Test void malformedCuratedEntryRejectsWholeMigration() throws Exception {
        String source="{\"crossVersionBlockFallbacks\":{\"remote:block\":{\"target\":\"minecraft:stone\"}}}";
        Files.writeString(directory.resolve("lss-client-config.json"),source);
        assertThrows(SettingsException.class,()->new SettingsStore<>(directory,"lss",schema).initialize());
        assertEquals(source,Files.readString(directory.resolve("lss-client-config.json")));
    }
    @Test void yamlDoesNotSilentlyHealWrongTypesOrNullFallbacks() {
        var values=new HashMap<String,Object>();values.put("compatibility.block_fallbacks.default",null);
        assertThrows(SettingsException.class,()->schema.fromValues(values));
        assertThrows(SettingsException.class,()->schema.fromValues(Map.of("compatibility.block_fallbacks.default","  ")));
        assertThrows(SettingsException.class,()->schema.fromValues(Map.of("compatibility.block_fallbacks.overrides",List.of())));
        assertEquals("minecraft:sandstone",schema.fromValues(Map.of("compatibility.block_fallbacks.default","minecraft:sandstone")).normalized().compatibility().blockFallbacks().defaultBlock());
    }
}
