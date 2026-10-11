package dev.vox.lss.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The neutral map-ingestion setting (map-ingestion plan §2): {@code integrations.map_ingestion}
 * replaced the Xaero-only {@code integrations.xaero_map}. New files enable it; the old group is
 * accepted wherever a document or change set is read, rewritten canonical by the next save
 * that changes something, and never rewritten at startup.
 */
class MapIngestionSettingsTest {
    /** The integrations block every v0.16/v0.17 client file carries (template-generated). */
    private static final String OLD_BLOCK = """
            integrations:
              # Writes received terrain into Xaero's persistent map beyond normal view distance.
              # Filling a large map adds client CPU, memory and disk work. Turning this off stops
              # future writes after reconnect; it does not remove terrain already saved on the map.
              # Backpressure lets map writing keep up instead of allowing an unchecked backlog.
              xaero_map:
                enabled: %s # [reconnect] Write native terrain service data into Xaero's World Map; requires that mod.
                backpressure: %s # Pace downloads to map writes; requires ingest_backpressure.
            """;
    private static final String NEW_BLOCK_START = "integrations:\n  # Writes received distant terrain into installed map mods";

    @TempDir Path dir;

    private static String template() {
        return new String(new YamlSettingsCodec<>(SettingsSchema.client()).defaults(), StandardCharsets.UTF_8);
    }
    /** The current template with its integrations block swapped for the old one. */
    private static String oldFile(boolean enabled, boolean backpressure) {
        String t = template();
        int start = t.indexOf(NEW_BLOCK_START), end = t.indexOf("\n\n", start) + 1;
        assertTrue(start >= 0 && end > start, "template integrations block moved");
        // The old block ends with one newline; the template's blank-line separator follows.
        return t.substring(0, start) + OLD_BLOCK.formatted(enabled, backpressure) + t.substring(end);
    }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test void newFilesEnableMapIngestionAndAliasesCoverBothLeaves() {
        var schema = SettingsSchema.client();
        assertTrue(schema.defaults().integrations().mapIngestion().enabled());
        assertTrue(schema.defaults().integrations().mapIngestion().backpressure());
        assertEquals(Map.of("integrations.xaero_map.enabled", "integrations.map_ingestion.enabled",
                "integrations.xaero_map.backpressure", "integrations.map_ingestion.backpressure"), schema.aliases());
        assertTrue(SettingsSchema.server(false).aliases().isEmpty());
        assertFalse(template().contains("xaero_map"), "new files are written canonical");
    }

    @Test void oldFilesReadUnderTheCanonicalPath() {
        var codec = new YamlSettingsCodec<>(SettingsSchema.client());
        var off = codec.parse(bytes(oldFile(false, true)));
        assertFalse(off.configured().integrations().mapIngestion().enabled(), "upgraders keep their explicit off");
        assertTrue(off.normalizations().isEmpty());
        var on = codec.parse(bytes(oldFile(true, false)));
        assertTrue(on.configured().integrations().mapIngestion().enabled());
        assertFalse(on.configured().integrations().mapIngestion().backpressure());
    }

    @Test void theCanonicalPathWinsWhenBothArePresentAndTheDecodeSaysSo() {
        var codec = new YamlSettingsCodec<>(SettingsSchema.client());
        String both = template().replace("  map_ingestion:\n", "  xaero_map:\n    enabled: false\n  map_ingestion:\n");
        var document = codec.parse(bytes(both));
        assertTrue(document.configured().integrations().mapIngestion().enabled());
        assertEquals(1, document.normalizations().size());
        var note = document.normalizations().getFirst();
        assertEquals("integrations.xaero_map.enabled", note.path());
        assertEquals(false, note.requested());
        assertEquals(true, note.effective());
    }

    @Test void changeSetsAcceptTheOldPathAndRejectConflicts() {
        var codec = new YamlSettingsCodec<>(SettingsSchema.client());
        var fresh = codec.parse(codec.defaults());
        var out = codec.editSplit(fresh, Map.of("integrations.xaero_map.enabled", false));
        String main = new String(out.main(), StandardCharsets.UTF_8);
        assertFalse(main.contains("xaero_map"));
        assertFalse(codec.parse(out.main()).configured().integrations().mapIngestion().enabled());
        var conflicting = new LinkedHashMap<String, Object>();
        conflicting.put("integrations.xaero_map.enabled", false);
        conflicting.put("integrations.map_ingestion.enabled", true);
        assertThrows(SettingsException.class, () -> codec.editSplit(fresh, conflicting));
        assertThrows(SettingsException.class, () -> codec.editSplit(fresh, Map.of("integrations.xaero_map.bogus", true)));
    }

    @Test void aSaveThatChangesSomethingRewritesTheOldGroupWithTheTemplateComments() {
        var codec = new YamlSettingsCodec<>(SettingsSchema.client());
        var old = codec.parse(bytes(oldFile(false, false)));
        var saved = codec.editSplit(old, Map.of("lod.receive", false));
        String main = new String(saved.main(), StandardCharsets.UTF_8);
        assertFalse(main.contains("xaero_map"));
        assertTrue(main.contains(NEW_BLOCK_START), "the template's comments travel with the canonical group:\n" + main);
        assertTrue(main.contains("\n\n# Draws distant player models"),
                "the blank-line separator before the next group survives");
        var reread = codec.parse(saved.main()).configured();
        assertFalse(reread.integrations().mapIngestion().enabled(), "the configured value is kept, not the template's");
        assertFalse(reread.integrations().mapIngestion().backpressure());
        assertFalse(reread.lod().receive());
        // Indistinguishable from a file that was always canonical, given the same values.
        var canonical = codec.editSplit(codec.parse(codec.defaults()), Map.of("lod.receive", false,
                "integrations.map_ingestion.enabled", false, "integrations.map_ingestion.backpressure", false));
        assertSameLines(new String(canonical.main(), StandardCharsets.UTF_8), main);
    }
    private static void assertSameLines(String expected, String actual) {
        String[] e = expected.split("\n", -1), a = actual.split("\n", -1);
        for (int i = 0; i < Math.min(e.length, a.length); i++)
            assertEquals(e[i], a[i], "line " + (i + 1) + " differs");
        assertEquals(e.length, a.length, "line count");
    }

    @Test void anEmptyEditNeverCanonicalizes() {
        var codec = new YamlSettingsCodec<>(SettingsSchema.client());
        var old = codec.parse(bytes(oldFile(false, true)));
        assertArrayEquals(old.bytes(), codec.editSplit(old, Map.of()).main());
    }

    @Test void startupLeavesAnUpgradedFileReadableByOlderVersions() throws Exception {
        // A development-key split rewrites the main file at startup; it must not rename the group.
        String devKeys = oldFile(false, true).replace("lod:\n", "lod:\n  source: lss\n");
        var path = dir.resolve("lss-client-config.yaml");
        Files.writeString(path, devKeys);
        var store = new SettingsStore<>(dir, "lss", SettingsSchema.client());
        var document = store.initialize();
        String main = Files.readString(path);
        assertFalse(main.contains("source: lss"), "the development key moved to the sidecar");
        assertTrue(main.contains("  xaero_map:\n    enabled: false"), "startup must not canonicalize:\n" + main);
        assertFalse(main.contains("map_ingestion"));
        assertFalse(document.configured().integrations().mapIngestion().enabled());
        // The first real save does.
        var saved = store.saveDraft(document.hash(), Map.of("lod.distance_chunks", 64));
        assertTrue(Files.readString(path).contains("  map_ingestion:\n    enabled: false"));
        assertFalse(saved.configured().integrations().mapIngestion().enabled());
    }

    @Test void legacyJsonKeepsAnAbsentBridgeOff() {
        assertFalse(migrate("{}"));
        assertFalse(migrate("{\"enableXaeroMapBridge\":null}"));
        assertFalse(migrate("{\"enableXaeroMapBridge\":false}"));
        assertTrue(migrate("{\"enableXaeroMapBridge\":true}"));
        assertTrue(migrate("{\"enableXaeroMapBridge\":\"true\"}"));
        assertFalse(LegacySettingsMigration.migrate(bytes("{\"enableXaeroMapBackpressure\":false}"), SettingsSchema.client())
                .settings().integrations().mapIngestion().backpressure());
    }
    private static boolean migrate(String json) {
        return LegacySettingsMigration.migrate(bytes(json), SettingsSchema.client()).settings().integrations().mapIngestion().enabled();
    }
}
