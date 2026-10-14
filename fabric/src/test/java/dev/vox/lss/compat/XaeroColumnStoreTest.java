package dev.vox.lss.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vox.lss.common.store.StoreCodec;
import dev.vox.lss.networking.client.ColumnCacheStore;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fill-on-switch column store: round trip and nearest-first positions, re-serve overwrite,
 * reopen and the registry fingerprint, the total cap with LRU eviction (other stores first,
 * then the oldest segment; a fill read copies forward), tolerance of a torn tail and a corrupt
 * record, per-bucket and per-dimension keying, the bounded capture queue, no-zstd, and the
 * column cache's clears deleting the store.
 */
class XaeroColumnStoreTest {
    private static final long FP = 0x1234L;
    private final List<XaeroColumnStore> opened = new ArrayList<>();

    @TempDir
    Path root;

    @AfterEach
    void closeAll() {
        for (var store : this.opened) store.suspendForClear();
    }

    private XaeroColumnStore store(long capBytes) {
        return store(capBytes, StoreCodec.zstdOrNull());
    }

    private XaeroColumnStore store(long capBytes, StoreCodec codec) {
        var store = new XaeroColumnStore(b -> this.root.resolve(b), () -> this.root, codec, capBytes);
        this.opened.add(store);
        return store;
    }

    private static long pos(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** Incompressible bytes, so the cap math is honest whichever codec runs. */
    private static byte[] column(int seed, int size) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static long[] near(XaeroColumnStore store, String bucket, String dim, long fp, int r) throws Exception {
        return store.positionsNear(bucket, dim, fp, 0, 0, r).get(5, TimeUnit.SECONDS);
    }

    private static List<XaeroColumnStore.Column> read(XaeroColumnStore store, String bucket, String dim, long fp,
                                                      long... positions) throws Exception {
        return store.read(bucket, dim, fp, positions).get(5, TimeUnit.SECONDS);
    }

    @Test
    void columnsRoundTripAndComeBackNearestFirstWithinTheRadius() throws Exception {
        var store = store(64L << 20);
        assertTrue(store.capture("b", "nether", FP, pos(5, 0), column(1, 4000)));
        assertTrue(store.capture("b", "nether", FP, pos(1, 1), column(2, 4000)));
        assertTrue(store.capture("b", "nether", FP, pos(-3, 0), column(3, 4000)));
        assertTrue(store.capture("b", "nether", FP, pos(40, 40), column(4, 4000)));
        store.flush();
        assertArrayEquals(new long[]{pos(1, 1), pos(-3, 0), pos(5, 0)}, near(store, "b", "nether", FP, 10));
        var back = read(store, "b", "nether", FP, pos(-3, 0), pos(99, 99));
        assertEquals(1, back.size(), "an absent position is left out");
        assertArrayEquals(column(3, 4000), back.get(0).raw());
        assertEquals(4, store.columnsGauge);
        assertEquals(4, store.captured.get());
    }

    @Test
    void aReServeOverwritesItsEntry() throws Exception {
        var store = store(64L << 20);
        store.capture("b", "nether", FP, pos(0, 0), column(1, 2000));
        store.capture("b", "nether", FP, pos(0, 0), column(2, 2000));
        store.flush();
        assertArrayEquals(column(2, 2000), read(store, "b", "nether", FP, pos(0, 0)).get(0).raw());
        assertEquals(1, store.columnsGauge);
    }

    @Test
    void aReopenedStoreRebuildsItsIndexAndAnotherFingerprintDiscardsIt() throws Exception {
        var first = store(64L << 20);
        first.capture("b", "nether", FP, pos(0, 0), column(1, 3000));
        first.capture("b", "nether", FP, pos(2, 0), column(2, 3000));
        first.capture("b", "nether", FP, pos(0, 0), column(3, 3000)); // the latest wins after a reopen too
        first.flush();
        first.suspendForClear(); // closes the files (queued captures would drop: a clear is coming)
        var second = store(64L << 20);
        assertEquals(2, near(second, "b", "nether", FP, 5).length);
        assertArrayEquals(column(3, 3000), read(second, "b", "nether", FP, pos(0, 0)).get(0).raw());
        second.suspendForClear();
        var third = store(64L << 20);
        assertEquals(0, near(third, "b", "nether", FP + 1, 5).length, "other registries: discarded");
        third.flush();
        try (Stream<Path> files = Files.list(this.root.resolve("b"))) {
            assertEquals(0, files.filter(p -> p.getFileName().toString().startsWith("nether.xfill.")).count());
        }
    }

    @Test
    void theCapEvictsTheOldestSegmentAndAFillReadCopiesForward() throws Exception {
        var store = store(1L << 20); // 1 MiB cap → 128 KiB segments
        for (int i = 0; i < 40; i++) {
            store.capture("b", "nether", FP, pos(i, 0), column(i, 30_000));
            if (i == 3) {
                store.flush();
                assertEquals(1, read(store, "b", "nether", FP, pos(0, 0)).size()); // still newest: no copy
            }
            if (i == 28) { // near the cap
                store.flush();
                assertEquals(1, read(store, "b", "nether", FP, pos(1, 0)).size(), "used: copied forward");
            }
        }
        store.flush();
        assertTrue(store.bytesGauge <= store.capBytes, store.bytesGauge + " > cap");
        assertTrue(store.evictions.get() > 0);
        assertTrue(store.copiedForward.get() >= 1);
        var left = near(store, "b", "nether", FP, 1000);
        var leftSet = Arrays.stream(left).boxed().toList();
        assertTrue(leftSet.contains(pos(39, 0)), "the newest survives");
        assertFalse(leftSet.contains(pos(2, 0)), "the oldest unused went first");
        assertTrue(leftSet.contains(pos(1, 0)), "the column a fill read survived (LRU)");
    }

    @Test
    void anotherServersStoreGoesBeforeTheActiveOne() throws Exception {
        var store = store(1L << 20);
        for (int i = 0; i < 20; i++) store.capture("a", "nether", FP, pos(i, 0), column(i, 30_000));
        store.flush();
        long aBytes = store.bytesGauge;
        assertTrue(aBytes > 400_000);
        for (int i = 0; i < 25; i++) store.capture("b", "nether", FP, pos(i, 0), column(100 + i, 30_000));
        store.flush();
        assertEquals(25, near(store, "b", "nether", FP, 1000).length, "the active store kept everything");
        try (Stream<Path> files = Files.list(this.root.resolve("a"))) {
            assertEquals(0, files.count(), "the least recently used store went whole");
        }
    }

    @Test
    void aTornTailAndACorruptRecordAreTolerated() throws Exception {
        var store = store(64L << 20);
        for (int i = 0; i < 5; i++) store.capture("b", "nether", FP, pos(i, 0), column(i, 1000));
        store.flush();
        var segment = store.segmentsForTest().get(0);
        store.suspendForClear();
        long size = Files.size(segment);
        try (var raf = new RandomAccessFile(segment.toFile(), "rw")) {
            raf.setLength(size - 300); // tear the last record
            long at = XaeroColumnStore.HEADER_BYTES + XaeroColumnStore.RECORD_HEADER_BYTES + 40;
            raf.seek(at);
            int b = raf.read();
            raf.seek(at);
            raf.write(b ^ 0x5A); // garble the first record's payload (the CRC catches it)
        }
        var reopened = store(64L << 20);
        assertEquals(4, near(reopened, "b", "nether", FP, 100).length, "the torn tail record is gone");
        var back = read(reopened, "b", "nether", FP, pos(0, 0), pos(1, 0), pos(2, 0), pos(3, 0));
        assertEquals(3, back.size(), "the corrupt record is skipped, not fatal");
        assertEquals(1, reopened.corrupt.get());
        assertTrue(reopened.capture("b", "nether", FP, pos(9, 0), column(9, 1000)), "appends go on");
        reopened.flush();
        assertEquals(1, read(reopened, "b", "nether", FP, pos(9, 0)).size());
    }

    @Test
    void storesAreKeyedByBucketAndDimension() throws Exception {
        var store = store(64L << 20);
        store.capture("server.world-aaaa", "the_nether", FP, pos(0, 0), column(1, 500));
        store.capture("server.world-bbbb", "the_nether", FP, pos(1, 0), column(2, 500));
        store.capture("server.world-aaaa", "modded_ceiling", FP, pos(2, 0), column(3, 500));
        store.flush();
        assertArrayEquals(new long[]{pos(0, 0)}, near(store, "server.world-aaaa", "the_nether", FP, 10));
        assertArrayEquals(new long[]{pos(1, 0)}, near(store, "server.world-bbbb", "the_nether", FP, 10));
        assertArrayEquals(new long[]{pos(2, 0)}, near(store, "server.world-aaaa", "modded_ceiling", FP, 10));
    }

    @Test
    void capturesDropRatherThanBlockAndNotWhileSuspended() throws Exception {
        var store = store(64L << 20);
        store.suspendForClear();
        assertFalse(store.capture("b", "nether", FP, pos(0, 0), column(1, 100)));
        store.resumeAfterClear();
        assertFalse(store.capture("b", "nether", FP, pos(0, 0),
                new byte[dev.vox.lss.common.LSSConstants.MAX_SECTIONS_SIZE + 1]), "an oversized column");
        assertEquals(2, store.captureDrops.get());
        // The bounded queue: a burst past the byte bound drops instead of blocking the caller.
        int accepted = 0;
        byte[] big = column(3, 1_000_000);
        for (int i = 0; i < 200; i++) {
            if (store.capture("b", "nether", FP, pos(i, 0), big)) accepted++;
        }
        assertTrue(accepted < 200 && store.captureDrops.get() > 2, "accepted " + accepted);
        store.flush();
    }

    @Test
    void withoutTheZstdNativeColumnsAreKeptRaw() throws Exception {
        var store = store(64L << 20, null);
        store.capture("b", "nether", FP, pos(0, 0), column(7, 2048));
        store.flush();
        assertArrayEquals(column(7, 2048), read(store, "b", "nether", FP, pos(0, 0)).get(0).raw());
    }

    @Test
    void theColumnCachesClearsDeleteTheStore() throws Exception {
        String bucket = "test-xfill-" + Long.toHexString(System.nanoTime());
        var store = new XaeroColumnStore(ColumnCacheStore::bucketDir, ColumnCacheStore::cacheRootPath,
                StoreCodec.zstdOrNull(), 64L << 20);
        this.opened.add(store);
        ColumnCacheStore.BucketTenant tenant = new ColumnCacheStore.BucketTenant() {
            @Override
            public void beforeClear() {
                store.suspendForClear();
            }

            @Override
            public void afterClear() {
                store.resumeAfterClear();
            }
        };
        ColumnCacheStore.addBucketTenant(tenant);
        try {
            store.capture(bucket, "minecraft_the_nether", FP, pos(0, 0), column(1, 1000));
            store.flush();
            Path dir = ColumnCacheStore.bucketDir(bucket);
            try (Stream<Path> files = Files.list(dir)) {
                assertEquals(1, files.count(), "flat inside the cache bucket");
            }
            ColumnCacheStore.clearForServer(bucket); // /lss clearcache's single-bucket path
            assertFalse(Files.exists(dir), "the bucket and the store went together");
            assertEquals(0, near(store, bucket, "minecraft_the_nether", FP, 10).length, "reopens empty");
            store.capture(bucket, "minecraft_the_nether", FP, pos(1, 0), column(2, 1000));
            store.flush();
            ColumnCacheStore.clearForServers(List.of(bucket)); // the reset sweep
            assertFalse(Files.exists(dir));
        } finally {
            ColumnCacheStore.removeBucketTenant(tenant);
            deleteQuietly(ColumnCacheStore.bucketDir(bucket));
        }
    }

    private static void deleteQuietly(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) Files.deleteIfExists(f);
        }
        Files.deleteIfExists(dir);
    }
}
