package dev.vox.lss.common.config;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class SettingMetadataTest {
    @Test void immutableRecordsAndMetadataCoverTheSamePaths() {
        for (var schema : List.of(SettingsSchema.server(false), SettingsSchema.server(true), SettingsSchema.client())) {
            var keys = schema.descriptors().stream().map(SettingsSchema.Descriptor::path).collect(Collectors.toSet());
            assertEquals(schema.descriptors().size(), keys.size());
            assertEquals(schema.defaultValues().keySet(), keys);
            assertFalse(keys.stream().anyMatch(k -> k.startsWith("bytesPerSecond")));
            assertTrue(schema.descriptors().stream().allMatch(d -> !d.description().isBlank()));
        }
    }
    @Test void performancePoliciesAreReloadableButIdentityAndTopologyRemainDeferred() {
        var fields = SettingsSchema.server(false).descriptorsByPath();
        for (String key : List.of("generation.enabled", "generation.timeout_ticks", "storage.lod_store.max_size_mib",
                "storage.lod_store.backfill.enabled", "storage.lod_store.backfill.columns_per_second",
                "storage.timestamp_cache_mib_per_dimension", "storage.miss_memo_ttl_seconds",
                "network.yield_to_vanilla", "serialization.nbt_transcode"))
            assertEquals(SettingsSchema.Timing.H, fields.get(key).timing(), key);
        for (String key : List.of("storage.lod_store.enabled", "privacy.xray.mode", "storage.disk.reader_threads"))
            assertEquals(SettingsSchema.Timing.R, fields.get(key).timing(), key);
        assertEquals(SettingsSchema.Timing.S, SettingsSchema.client().descriptorsByPath().get("integrations.map_ingestion.enabled").timing());
    }
}
