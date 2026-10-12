package dev.vox.lss.config;

import dev.vox.lss.common.config.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Platform facade derivations; strict YAML and legacy normalization live in common's codec corpus. */
class ConfigValidationTest {
    private LSSServerConfig config(Map<String,Object> changes) {
        return new LSSServerConfig(SettingsSchema.server(false).fromValues(changes).normalized());
    }
    @Test void freshDimensionLimitsAndPermissionsMatchTheApprovedDefaults() {
        var c = new LSSServerConfig();
        assertFalse(c.requireServicePermission());
        assertEquals(512, c.lodDistanceForWorld("minecraft:overworld"));
        assertEquals(64, c.lodDistanceForWorld("minecraft:the_nether"));
        assertEquals(512, c.lodDistanceForWorld("minecraft:the_end"));
        assertEquals(512, c.lodDistanceForWorld("mod:other"));
        assertEquals("on", c.lodStore());
        assertEquals(0, c.lodStoreResweepSeconds());
        assertTrue(c.enableV16Compat() && c.enableV18Compat() && c.enableV19Compat());
    }
    @Test void rangesAndGlobalBoundsRemainCoherent() {
        var c = config(Map.of("lod.distance.default_chunks", 8,
                "lod.distance.by_dimension", Map.of("mod:distant", 2048),
                "generation.concurrency.global", 3, "generation.concurrency.per_player", 100,
                "far_players.distance.max_blocks", 128, "far_players.distance.min_blocks", 2000));
        assertEquals(2048, c.maxConfiguredLodDistanceChunks());
        assertEquals(2048, c.lodDistanceForWorld("mod:distant"));
        assertEquals(3, c.generationLimits().global());
        assertEquals(3, c.generationLimits().perPlayer());
        assertEquals(128, c.farPlayersMinDistanceBlocks());
        assertTrue(c.effectiveTimestampCacheMB() > config(Map.of("lod.distance.default_chunks", 8,
                "lod.distance.by_dimension", Map.of())).effectiveTimestampCacheMB());
    }
    @Test void zeroTurnsOneDimensionOffAndNothingElse() {
        var c = config(Map.of("lod.distance.default_chunks", 512,
                "lod.distance.by_dimension", Map.of("minecraft:the_nether", 0)));
        assertEquals(0, c.lodDistanceForWorld("minecraft:the_nether"));
        assertEquals(512, c.lodDistanceForWorld("minecraft:overworld"));
        assertEquals(512, c.maxConfiguredLodDistanceChunks(), "an off world never sizes caches or sweeps");
        assertEquals(List.of("minecraft:the_nether"), c.unservedWorlds());
        assertEquals("LODs off (distance 0): minecraft:the_nether", c.unservedWorldsDiagLineOrNull());
        assertEquals(512, c.legacyAdvertisedDistance(0), "a legacy session in an off world stays on for the served ones");
        assertEquals(64, c.legacyAdvertisedDistance(64));
    }
    @Test void aZeroDefaultServesOnlyTheListedDimensions() {
        var c = config(Map.of("lod.distance.default_chunks", 0,
                "lod.distance.by_dimension", Map.of("minecraft:overworld", 256)));
        assertEquals(256, c.lodDistanceForWorld("minecraft:overworld"));
        assertEquals(0, c.lodDistanceForWorld("minecraft:the_end"));
        assertEquals(0, c.lodDistanceForWorld("mod:other"));
        assertEquals(256, c.maxConfiguredLodDistanceChunks());
        assertEquals(List.of("unlisted dimensions (default_chunks)"), c.unservedWorlds());
    }
    @Test void everyWorldOffStillAdvertisesAPositiveLegacyDistance() {
        var c = config(Map.of("lod.distance.default_chunks", 0, "lod.distance.by_dimension", Map.of()));
        assertEquals(0, c.maxConfiguredLodDistanceChunks());
        assertEquals(1, c.legacyAdvertisedDistance(0));
        assertNull(config(Map.of()).unservedWorldsDiagLineOrNull(), "fresh defaults serve every world");
    }
    @Test void autoReadGateResolvesAgainstTheActualPoolAndAttachedStore() {
        var c = config(Map.of());
        assertEquals(8, c.effectiveMaxConcurrentDiskReads(8, false));
        assertEquals(4, c.effectiveMaxConcurrentDiskReads(8, true));
        assertEquals(8, config(Map.of("storage.disk.max_concurrent_reads", 64)).effectiveMaxConcurrentDiskReads(8, true));
        assertEquals(1, config(Map.of("storage.disk.max_concurrent_reads", 1)).effectiveMaxConcurrentDiskReads(8, false));
    }
    @Test void bytesAndTimeUnitsPreserveRuntimeMeaning() {
        var c = config(Map.of("network.bandwidth.per_player_mib_per_second", 0.25,
                "network.bandwidth.global_mib_per_second", 0.0,
                "generation.timeout_ticks", 1201, "updates.dirty_broadcast_interval_ticks", 0));
        assertEquals(262144, c.bytesPerSecondPerPlayer());
        assertEquals(1024, c.bytesPerSecondGlobal());
        assertEquals(1201, c.generationTimeoutTicks(), "do not truncate through a seconds conversion");
        assertEquals(0, c.dirtyBroadcastIntervalTicks());
        assertEquals(Long.MAX_VALUE, c.lodStoreMaxBytes());
        assertEquals(64L * 1024 * 1024, config(Map.of("storage.lod_store.max_size_mib", 1)).lodStoreMaxBytes());
    }
    @Test void effectiveEchoKeepsItsExistingHarnessContract() {
        var c = config(Map.of("serialization.nbt_transcode", false, "storage.disk.split_background_reads", false));
        assertEquals("Effective config: useNbtTranscode=false, diskReaderThreads=6, useCompressedColumns=false, useBackgroundReadSplit=false, useSelectiveNbtParse=true, maxConcurrentDiskReads=2", c.effectiveConfigEcho(6, false, 2));
    }
}
