package dev.vox.lss.common.dh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Persisted DH receipts (X-5): tile->timestamp only, checksummed, identity-bound, bounded, never terrain. */
class DhReceiptStoreTest {
    @TempDir Path root;
    static final DhTerrainTile.WorldShape SHAPE = new DhTerrainTile.WorldShape(-64, 384, true);
    DhReceiptStore store(String address, String world) {
        return new DhReceiptStore(root, new DhReceiptStore.Key(address, "server", world, "minecraft:overworld", 7, DhProfile.OFFICIAL_16, SHAPE));
    }
    static DhReceiptStore.Entry entry(long ts) { return new DhReceiptStore.Entry(ts, DhReceiptStore.CONVERSION_VERSION); }

    @Test void roundTripsInInsertionOrderAndDropsEmptyFiles() throws Exception {
        var s = store("play.example.net", "w");
        var map = new LinkedHashMap<DhPosition, DhReceiptStore.Entry>();
        map.put(new DhPosition(6, 1, 2), entry(10)); map.put(new DhPosition(6, -3, 4), entry(20));
        s.save(map);
        assertEquals(map, s.load());
        assertEquals(new java.util.ArrayList<>(map.keySet()), new java.util.ArrayList<>(s.load().keySet()));
        s.save(Map.of());
        try (var files = Files.list(root.resolve(DhReceiptStore.DIRECTORY))) { assertEquals(0, files.count()); }
    }
    @Test void aDifferentWorldIdentityNeverSeesAnotherWorldsReceipts() throws Exception {
        store("play.example.net", "a").save(Map.of(new DhPosition(6, 0, 0), entry(1)));
        assertTrue(store("play.example.net", "b").load().isEmpty());
        assertEquals(1, store("play.example.net", "a").load().size());
    }
    @Test void addressSpellingsOfOneServerShareReceipts() throws Exception {
        store("Play.Example.NET.:25565", "w").save(Map.of(new DhPosition(6, 0, 0), entry(1)));
        assertEquals(1, store("play.example.net", "w").load().size());
        DhReceiptStore.clear(root, "PLAY.example.net");
        assertTrue(store("play.example.net", "w").load().isEmpty());
    }
    @Test void corruptOrForeignFilesReadAsEmptyNeverAsReceipts() throws Exception {
        var s = store("x", "w");
        s.save(Map.of(new DhPosition(6, 0, 0), entry(1)));
        Path file;
        try (var files = Files.list(root.resolve(DhReceiptStore.DIRECTORY))) { file = files.findFirst().orElseThrow(); }
        byte[] bytes = Files.readAllBytes(file); bytes[bytes.length - 20] ^= 1; Files.write(file, bytes);
        assertTrue(s.load().isEmpty(), "A checksum failure is no receipt");
        assertFalse(Files.exists(file), "The corrupt file is removed");
    }
    @Test void clearRemovesOnlyOneServerAndPreservesForeignFiles() throws Exception {
        store("a", "w").save(Map.of(new DhPosition(6, 0, 0), entry(1)));
        store("b", "w").save(Map.of(new DhPosition(6, 0, 0), entry(1)));
        Path foreign = root.resolve(DhReceiptStore.DIRECTORY).resolve("keep.txt"); Files.writeString(foreign, "x");
        DhReceiptStore.clear(root, "a");
        assertTrue(store("a", "w").load().isEmpty()); assertEquals(1, store("b", "w").load().size());
        DhReceiptStore.clear(root, null);
        assertTrue(store("b", "w").load().isEmpty()); assertTrue(Files.exists(foreign));
    }
    @Test void theObsoleteDtoCacheIsReclaimedButForeignContentSurvives() throws Exception {
        Path legacy = root.resolve("!dh-terrain-v1"); Path bucket = legacy.resolve("a".repeat(64) + "." + "b".repeat(64));
        Files.createDirectories(bucket);
        Files.write(bucket.resolve("0000000000000001.dht"), new byte[200]);
        Files.write(bucket.resolve(".pending-1.tmp"), new byte[1]);
        Files.write(legacy.resolve("quota.lock"), new byte[8]);
        Path other = root.resolve("dh-terrain-v1").resolve("c".repeat(64) + "." + "d".repeat(64));
        Files.createDirectories(other); Files.writeString(other.resolve("mine.txt"), "foreign");
        store("x", "w").load();
        assertFalse(Files.exists(legacy), "Our own DTO files are deleted");
        assertTrue(Files.exists(other.resolve("mine.txt")), "Foreign content is never deleted");
    }
    @Test void entriesAreBoundedAndOldConversionsAreNotCurrent() throws Exception {
        var map = new LinkedHashMap<DhPosition, DhReceiptStore.Entry>();
        for (int i = 0; i < DhReceiptStore.MAX_ENTRIES + 10; i++) map.put(new DhPosition(6, i, 0), entry(i + 1));
        var s = store("x", "w"); s.save(map);
        var loaded = s.load();
        assertEquals(DhReceiptStore.MAX_ENTRIES, loaded.size());
        assertFalse(loaded.containsKey(new DhPosition(6, 0, 0)), "The oldest receipts are dropped first");
        assertFalse(new DhReceiptStore.Entry(5, DhReceiptStore.CONVERSION_VERSION - 1).current());
        assertFalse(new DhReceiptStore.Entry(0, DhReceiptStore.CONVERSION_VERSION).current());
    }
    @Test void aSymlinkedReceiptDirectoryIsRefused() throws Exception {
        Path outside = Files.createTempDirectory("dh-receipt-outside");
        try {
            Files.createSymbolicLink(root.resolve(DhReceiptStore.DIRECTORY), outside);
            assertThrows(java.io.IOException.class, () -> store("x", "w").save(Map.of(new DhPosition(6, 0, 0), entry(1))));
            DhReceiptStore.clear(root, null);
            try (var files = Files.list(outside)) { assertEquals(0, files.count()); }
        } finally { Files.deleteIfExists(root.resolve(DhReceiptStore.DIRECTORY)); Files.deleteIfExists(outside); }
    }
}
