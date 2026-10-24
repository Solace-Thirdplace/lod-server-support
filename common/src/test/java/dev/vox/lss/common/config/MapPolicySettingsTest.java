package dev.vox.lss.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The server map-ingestion request (map-policy plan A3.1):
 * {@code privacy.request_no_map_ingestion}, default false on every platform, hot via
 * {@code /lsslod reload}, a legacy JSON name that never existed (a migrated file reads
 * false), {@code config_version} still 1, a file without the key reads false, and the
 * reload feedback carries its own line (never the legacy-reconnect text).
 */
class MapPolicySettingsTest {
    private static final String PATH = "privacy.request_no_map_ingestion";

    @TempDir Path directory;

    @Test void defaultsOffOnEveryPlatformAndAppliesOnReload() {
        for (boolean paper : new boolean[]{false, true}) {
            var schema = SettingsSchema.server(paper);
            assertFalse(schema.defaults().privacy().requestNoMapIngestion(), "default off, paper=" + paper);
            var d = schema.descriptorsByPath().get(PATH);
            assertNotNull(d, "the key exists, paper=" + paper);
            assertEquals(SettingsSchema.Kind.BOOLEAN, d.kind());
            assertEquals(SettingsSchema.Timing.H, d.timing(), "hot: edit the file, then reload");
            assertEquals(SettingsSchema.Platform.ALL, d.platform());
            assertEquals(Boolean.FALSE, d.defaultValue());
            assertEquals("Ask clients not to add distant terrain to their maps.", d.description());
            assertFalse(new ServerConfigBase(schema.defaults(), paper).requestNoMapIngestion());
        }
    }

    @Test void theTemplatesCarryTheHonestyScopeComment() {
        for (boolean paper : new boolean[]{false, true}) {
            String template = SettingsSchema.server(paper).template();
            int key = template.indexOf("  request_no_map_ingestion: false");
            assertTrue(key > 0, "template key, paper=" + paper);
            String before = template.substring(template.lastIndexOf("\nprivacy:", key), key);
            assertTrue(before.contains("Only clients on 0.19.0 or newer can honor this"), before);
            assertTrue(before.contains("a modified client can ignore"), before);
            // Map-ingestion permission plan §2: the exemption node, and the older-client
            // sentence qualified by it.
            assertTrue(before.contains("lss.map_ingestion (vss.map_ingestion) permission"), before);
            assertTrue(before.contains("until they update, unless"), before);
            assertTrue(template.contains("The map-ingestion request applies on reload."),
                    "the privacy group comment is no longer anti-xray only");
        }
    }

    @Test void theLegacyKeyNeverExistedSoMigrationReadsFalse() {
        var d = SettingsSchema.server(false).descriptorsByPath().get(PATH);
        assertEquals("requestNoMapIngestion", d.legacyKey());
        var migrated = LegacySettingsMigration.migrate(
                "{\"lodDistanceChunks\":128}".getBytes(StandardCharsets.UTF_8), SettingsSchema.server(false));
        assertFalse(migrated.settings().privacy().requestNoMapIngestion());
    }

    @Test void aFileWithoutTheKeyReadsFalseAndAFileWithItReadsTrue() {
        var codec = new YamlSettingsCodec<>(SettingsSchema.server(false));
        var bare = codec.parse("config_version: 1\nprivacy:\n  xray:\n    mode: \"auto\"\n"
                .getBytes(StandardCharsets.UTF_8));
        assertFalse(bare.configured().privacy().requestNoMapIngestion(),
                "an upgraded 0.18 file (no key) keeps the old behavior");
        var armed = codec.parse("config_version: 1\nprivacy:\n  request_no_map_ingestion: true\n"
                .getBytes(StandardCharsets.UTF_8));
        assertTrue(armed.configured().privacy().requestNoMapIngestion());
        assertTrue(armed.normalizations().isEmpty());
    }

    @Test void aReloadReportsItsOwnLineAndTheServiceOutcomeReplacesIt() throws Exception {
        try (var config = new YamlServerConfig(directory, false, s -> {})) {
            var handle = config.settingsHandle();
            var document = handle.store().read();
            handle.store().saveDraft(document.hash(), Map.of(PATH, true));
            var outcome = config.reload(Runnable::run,
                    (a, b, r) -> CompletableFuture.completedFuture(null)).get(30, TimeUnit.SECONDS);
            assertTrue(config.requestNoMapIngestion(), "applied on reload, no restart");
            var lines = new ArrayList<>(ReloadFeedback.lines(config.settingsPath(), outcome));
            assertTrue(lines.contains(ReloadFeedback.MAP_POLICY_CHANGED), lines.toString());
            assertTrue(lines.stream().noneMatch(l -> l.startsWith("Existing legacy clients may")),
                    "the policy is not a distance/generation change");
            assertTrue(lines.stream().noneMatch(l -> l.startsWith("Restart required")), lines.toString());

            ReloadFeedback.applyMapPolicyOutcome(lines, true, 2);
            assertFalse(lines.contains(ReloadFeedback.MAP_POLICY_CHANGED));
            assertTrue(lines.getLast().startsWith("Map ingestion request on:")
                    && lines.getLast().contains("2 older client(s) lost distant terrain"), lines.getLast());

            var off = new ArrayList<>(java.util.List.of(ReloadFeedback.MAP_POLICY_CHANGED));
            ReloadFeedback.applyMapPolicyOutcome(off, false, 0);
            assertTrue(off.getFirst().startsWith("Map ingestion request off:"), off.toString());

            var unobserved = new ArrayList<>(java.util.List.of(ReloadFeedback.MAP_POLICY_CHANGED));
            ReloadFeedback.applyMapPolicyOutcome(unobserved, true, -1);
            assertEquals(java.util.List.of(ReloadFeedback.MAP_POLICY_CHANGED), unobserved,
                    "no service observation of the revision: the path-keyed line stays");
        }
    }

    @Test void configVersionStaysOne() {
        String template = SettingsSchema.server(false).template();
        assertTrue(template.contains("config_version: 1 "), "no schema version bump for one key");
        assertTrue(Files.exists(directory));
    }
}
