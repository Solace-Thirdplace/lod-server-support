package dev.vox.lss.compat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        // A READ under other registries finds nothing and deletes nothing: a stale reader
        // (an old fingerprint) must never throw away the current store (review item 9).
        assertEquals(0, near(third, "b", "nether", FP + 1, 5).length, "other registries: nothing to fill from");
        assertEquals(2, near(third, "b", "nether", FP, 5).length, "the store survived the foreign read");
        third.suspendForClear();
        // Only a WRITE under other registries discards the old store.
        var fourth = store(64L << 20);
        assertTrue(fourth.capture("b", "nether", FP + 1, pos(7, 0), column(9, 3000)));
        fourth.flush();
        try (Stream<Path> files = Files.list(this.root.resolve("b"))) {
            assertEquals(1, files.filter(p -> p.getFileName().toString().startsWith("nether.xfill.")).count(),
                    "the old segments went, the new one stays");
        }
        assertArrayEquals(new long[]{pos(7, 0)}, near(fourth, "b", "nether", FP + 1, 10));
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
        reopened.suspendForClear();
        var later = store(64L << 20); // review fold O4-F3: the corrupt record is forgotten for good
        var left = Arrays.stream(near(later, "b", "nether", FP, 100)).boxed().toList();
        assertEquals(4, left.size(), "1, 2, 3 and 9");
        assertFalse(left.contains(pos(0, 0)), "a later session never re-indexes it");
        read(later, "b", "nether", FP, pos(1, 0), pos(2, 0), pos(3, 0), pos(9, 0));
        assertEquals(0, later.corrupt.get(), "nor re-counts it");
    }

    // ---- review fold O4-F2/F3/F4/F8, O6 T5/T8 ----

    @Test
    void theCountBoundNeverErodesTheByteBound() throws Exception {
        var store = store(64L << 20);
        var release = new java.util.concurrent.CountDownLatch(1);
        store.stallIoForTest(release); // a stalled disk: captures pile up behind it
        int accepted = 0;
        for (int i = 0; i < XaeroColumnStore.MAX_PENDING_CAPTURES + 100; i++) {
            if (store.capture("b", "nether", FP, pos(i, 0), column(i, 1000))) accepted++;
        }
        assertEquals(XaeroColumnStore.MAX_PENDING_CAPTURES, accepted, "the count bound holds");
        assertEquals((long) accepted * 1000, store.pendingBytes.get(), "a count-bound drop never touches the byte figure");
        release.countDown();
        store.flush();
        assertEquals(0, store.pendingCaptures.get());
        assertEquals(0L, store.pendingBytes.get(), "back to zero, never below (the byte bound keeps its 32 MiB)");
    }

    @Test
    void aDroppedReServeForgetsTheOlderCopyForLaterSessions() throws Exception {
        var store = store(64L << 20);
        store.capture("b", "nether", FP, pos(0, 0), column(1, 2000));
        store.capture("b", "nether", FP, pos(1, 0), column(2, 2000));
        store.flush();
        store.suspendForClear(); // a clear elsewhere: the re-serve of (0, 0) is dropped
        assertFalse(store.capture("b", "nether", FP, pos(0, 0), column(3, 2000)));
        store.resumeAfterClear();
        store.flush(); // the forget held across the clear lands
        assertEquals(1, store.forgotten.get());
        assertArrayEquals(new long[]{pos(1, 0)}, near(store, "b", "nether", FP, 10));
        store.suspendForClear();
        var next = store(64L << 20); // the next session reopens the files
        assertArrayEquals(new long[]{pos(1, 0)}, near(next, "b", "nether", FP, 10),
                "the older copy never comes back, so no later session fills it into another band");
        assertTrue(next.capture("b", "nether", FP, pos(0, 0), column(4, 2000)), "a later capture keeps it again");
        next.flush();
        next.suspendForClear();
        var third = store(64L << 20);
        assertArrayEquals(column(4, 2000), read(third, "b", "nether", FP, pos(0, 0)).get(0).raw());
    }

    @Test
    void aReopenedStoreAppendsToItsNewestSegmentUnlessItsTailIsTorn() throws Exception {
        for (int session = 0; session < 3; session++) {
            var store = store(64L << 20);
            store.capture("b", "nether", FP, pos(session, 0), column(session, 1000));
            store.flush();
            assertEquals(1, store.segmentsForTest().size(), "session " + session + " appended to the one segment");
            store.suspendForClear();
        }
        assertEquals(1, segmentFiles("b").size(), "one file, not one per session");
        var reader = store(64L << 20);
        assertEquals(3, read(reader, "b", "nether", FP, pos(0, 0), pos(1, 0), pos(2, 0)).size());
        reader.suspendForClear();
        try (var raf = new RandomAccessFile(segmentFiles("b").get(0).toFile(), "rw")) {
            raf.setLength(raf.length() - 5); // a torn tail is never appended after
        }
        var torn = store(64L << 20);
        torn.capture("b", "nether", FP, pos(9, 0), column(9, 1000));
        torn.flush();
        assertEquals(2, segmentFiles("b").size(), "a new segment after a torn tail");
        assertEquals(3, near(torn, "b", "nether", FP, 100).length, "the two whole records and the new one");
    }

    @Test
    void readHandlesOpenLazilyAndStayBounded() throws Exception {
        var writer = store(1L << 20); // 128 KiB segments
        for (int i = 0; i < 25; i++) writer.capture("b", "nether", FP, pos(i, 0), column(i, 30_000));
        writer.flush();
        int segments = writer.segmentsForTest().size();
        assertTrue(segments >= 5, segments + " segments");
        writer.suspendForClear();
        var reader = store(1L << 20);
        reader.maxOpenReaders = 2;
        assertEquals(25, near(reader, "b", "nether", FP, 100).length);
        assertEquals(0, reader.openChannelsForTest(), "an opened store holds no idle handle");
        long[] all = new long[25];
        for (int i = 0; i < 25; i++) all[i] = pos(i, 0);
        assertEquals(25, read(reader, "b", "nether", FP, all).size());
        assertTrue(reader.openChannelsForTest() <= 2, reader.openChannelsForTest() + " handles open");
    }

    @Test
    void aReadNeverDeletesASegmentItCannotUse() throws Exception {
        var store = store(64L << 20);
        store.capture("b", "nether", FP, pos(0, 0), column(1, 500));
        store.capture("b", "the_end", FP, pos(0, 0), column(2, 500));
        store.flush();
        store.suspendForClear();
        Path dir = this.root.resolve("b");
        Path garbled = Files.write(dir.resolve("nether.xfill.7"), new byte[]{1, 2, 3}); // a short header
        Path newer = dir.resolve("the_end.xfill.0");
        try (var raf = new RandomAccessFile(newer.toFile(), "rw")) {
            raf.seek(4);
            raf.writeInt(XaeroColumnStore.VERSION + 1); // a later build's format wrote this store
        }
        var reader = store(64L << 20);
        assertArrayEquals(new long[]{pos(0, 0)}, near(reader, "b", "nether", FP, 10), "the usable segment reads");
        assertEquals(0, near(reader, "b", "the_end", FP, 10).length, "another version's store answers empty");
        assertTrue(Files.exists(garbled) && Files.exists(newer), "a read deletes nothing");
        reader.capture("b", "nether", FP, pos(1, 0), column(3, 500)); // a write discards what it cannot use
        reader.flush();
        assertFalse(Files.exists(garbled));
        assertTrue(Files.exists(newer), "only the written store's own files");
        assertEquals(2, near(reader, "b", "nether", FP, 10).length);
    }

    @Test
    void aSuccessfulWriteResetsTheFailureCount() throws Exception {
        var store = store(64L << 20);
        int failures = XaeroColumnStore.MAX_CONSECUTIVE_FAILURES - 1;
        store.failAppendsForTest = failures;
        for (int i = 0; i < failures; i++) store.capture("b", "nether", FP, pos(i, 0), column(i, 500));
        store.capture("b", "nether", FP, pos(50, 0), column(50, 500)); // succeeds
        store.flush();
        assertFalse(store.capturesDisabled);
        store.failAppendsForTest = failures;
        for (int i = 0; i < failures; i++) store.capture("b", "nether", FP, pos(i, 0), column(i, 500));
        store.flush();
        assertFalse(store.capturesDisabled,
                "occasional failures (a file locked for a moment on Windows) never add up across successes");
        assertEquals(2L * failures, store.captureDrops.get());
    }

    @Test
    void theProductionStoreIsAColumnCacheTenant() throws Exception {
        Object previousRoot = cacheRootForTest(this.root.resolve("cache-root"));
        var tenants = tenantsForTest();
        var before = List.copyOf(tenants);
        try {
            var store = XaeroSession.productionColumnStore(64L << 20);
            this.opened.add(store);
            assertEquals(before.size() + 1, tenants.size(), "registered once, as a bucket tenant");
            String bucket = "test-xfill-" + Long.toHexString(System.nanoTime());
            store.capture(bucket, "minecraft_the_nether", FP, pos(0, 0), column(1, 1000));
            store.flush();
            assertEquals(1, near(store, bucket, "minecraft_the_nether", FP, 10).length);
            ColumnCacheStore.clearForServer(bucket); // the real /lss clearcache path
            assertFalse(Files.exists(ColumnCacheStore.bucketDir(bucket)), "the store went with the bucket");
            assertFalse(store.suspended, "and the store resumed after the clear");
            assertEquals(0, near(store, bucket, "minecraft_the_nether", FP, 10).length, "reopens empty");
        } finally {
            tenants.retainAll(before); // never leave a test store registered
            cacheRootForTest(previousRoot);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ColumnCacheStore.BucketTenant> tenantsForTest() throws ReflectiveOperationException {
        var field = ColumnCacheStore.class.getDeclaredField("TENANTS");
        field.setAccessible(true);
        return (List<ColumnCacheStore.BucketTenant>) field.get(null);
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
    void positionsNearIsTheServedSquareNotADisc() throws Exception {
        var store = store(64L << 20);
        store.capture("b", "nether", FP, pos(10, 10), column(1, 500)); // Euclidean 14.1, Chebyshev 10
        store.capture("b", "nether", FP, pos(-10, 10), column(2, 500));
        store.capture("b", "nether", FP, pos(11, 0), column(3, 500));  // outside the square
        store.capture("b", "nether", FP, pos(3, 0), column(4, 500));
        store.flush();
        assertArrayEquals(new long[]{pos(3, 0), pos(10, 10), pos(-10, 10)}, near(store, "b", "nether", FP, 10),
                "the square's corners are in, nearest (Euclidean) first");
    }

    @Test
    void anUndeletableOtherStoreCannotSpinTheCap() throws Exception {
        assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        var store = store(1L << 20);
        for (int i = 0; i < 12; i++) store.capture("a", "nether", FP, pos(i, 0), column(i, 30_000));
        store.flush();
        Path a = this.root.resolve("a");
        int stuckFiles = segmentFiles("a").size();
        assertTrue(stuckFiles > 1);
        var perms = Files.getPosixFilePermissions(a);
        Files.setPosixFilePermissions(a, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            boolean writable;
            try {
                Files.delete(Files.createFile(a.resolve("probe")));
                writable = true;
            } catch (IOException e) {
                writable = false;
            }
            assumeTrue(!writable, "directory permissions are not enforced (running as root)");
            for (int i = 0; i < 40; i++) store.capture("b", "nether", FP, pos(i, 0), column(100 + i, 30_000));
            // A spinning IO thread would time this read out.
            var left = Arrays.stream(near(store, "b", "nether", FP, 1000)).boxed().toList();
            assertTrue(left.contains(pos(39, 0)), "the newest column was stored");
            assertTrue(left.size() < 40, "the active store made room itself");
            assertTrue(store.bytesGauge <= store.capBytes, store.bytesGauge + " > cap");
            assertEquals(stuckFiles, segmentFiles("a").size(), "the stuck store is left alone");
        } finally {
            Files.setPosixFilePermissions(a, perms);
        }
    }

    @Test
    void aFailedSegmentLeaksNothingAndRepeatedFailuresStopCapturingForTheSession() throws Exception {
        var store = store(64L << 20);
        store.failWritesForTest = 1;
        assertTrue(store.capture("b", "nether", FP, pos(0, 0), column(1, 1000)));
        store.flush();
        assertEquals(1, store.captureDrops.get());
        assertEquals(List.of(), segmentFiles("b"), "the headerless segment was closed and deleted");
        assertTrue(store.capture("b", "nether", FP, pos(1, 0), column(2, 1000)));
        store.flush();
        assertArrayEquals(new long[]{pos(1, 0)}, near(store, "b", "nether", FP, 10), "the next write recovers");

        var failing = store(64L << 20);
        failing.failWritesForTest = 1000;
        for (int i = 0; i < XaeroColumnStore.MAX_CONSECUTIVE_FAILURES; i++) {
            failing.capture("c", "nether", FP, pos(i, 0), column(i, 1000));
        }
        failing.flush();
        assertTrue(failing.capturesDisabled, "a full disk stops the captures");
        assertFalse(failing.capture("c", "nether", FP, pos(50, 0), column(50, 1000)), "dropped at the door");
        assertEquals(List.of(), segmentFiles("c"));
        failing.failWritesForTest = 0;
        failing.resetSession(); // the next session tries again
        assertTrue(failing.capture("c", "nether", FP, pos(51, 0), column(51, 1000)));
        failing.flush();
        assertArrayEquals(new long[]{pos(51, 0)}, near(failing, "c", "nether", FP, 100));
    }

    @Test
    void theActiveBucketUnderAnotherSpellingIsNotAnotherStore() throws Exception {
        Path real = Files.createDirectories(this.root.resolve("b"));
        try {
            Files.createSymbolicLink(this.root.resolve("b-alias"), real);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "no symbolic links here");
        }
        var store = store(1L << 20);
        for (int i = 0; i < 25; i++) store.capture("b-alias", "nether", FP, pos(i, 0), column(i, 30_000));
        store.flush();
        assertEquals(0, store.othersBytesForTest(), "the same directory is the active bucket");
        assertEquals(25, near(store, "b-alias", "nether", FP, 1000).length,
                "the store never evicted itself as another server's store");
    }

    @Test
    void aColumnThatWouldNotDecodeIsDroppedFromTheIndex() throws Exception {
        var store = store(64L << 20);
        store.capture("b", "nether", FP, pos(0, 0), column(1, 500));
        store.capture("b", "nether", FP, pos(1, 0), column(2, 500));
        store.flush();
        store.dropCorrupt("b", "nether", FP, pos(0, 0));
        assertArrayEquals(new long[]{pos(1, 0)}, near(store, "b", "nether", FP, 10));
        assertEquals(1, store.corrupt.get());
        store.suspendForClear();
        var later = store(64L << 20); // review fold O4-F3: persisted, not in memory only
        assertArrayEquals(new long[]{pos(1, 0)}, near(later, "b", "nether", FP, 10),
                "a later session never re-decodes (and re-counts) it");
    }

    @Test
    void aLoweredCapTrimsOnTheNextCaptureAndAPurgeDeletesEveryStore() throws Exception {
        var store = store(64L << 20);
        for (int i = 0; i < 50; i++) store.capture("a", "nether", FP, pos(i, 0), column(i, 30_000));
        store.flush();
        assertTrue(store.bytesGauge > 1L << 20);
        store.setCapBytes(1L << 20);
        store.capture("a", "nether", FP, pos(99, 0), column(99, 30_000));
        store.flush();
        assertTrue(store.bytesGauge <= 1L << 20, store.bytesGauge + " > the lowered cap");
        assertTrue(Arrays.stream(near(store, "a", "nether", FP, 1000)).anyMatch(p -> p == pos(99, 0)));
        store.capture("b", "overworld", FP, pos(0, 0), column(1, 500));
        store.flush();
        Files.writeString(this.root.resolve("b").resolve("cache.bin"), "not ours");
        store.purgeAll();
        store.flush();
        assertEquals(List.of(), segmentFiles("a"));
        assertEquals(List.of(), segmentFiles("b"));
        assertTrue(Files.exists(this.root.resolve("b").resolve("cache.bin")), "only store files go");
        assertEquals(0, store.columnsGauge);
    }

    private List<Path> segmentFiles(String bucket) throws IOException {
        Path dir = this.root.resolve(bucket);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().contains(".xfill.")).toList();
        }
    }

    @Test
    void theColumnCachesClearsDeleteTheStore() throws Exception {
        // A temporary cache root only: this test clears buckets, so it must never see the real one.
        Object previousRoot = cacheRootForTest(this.root.resolve("cache-root"));
        try {
            clearsDeleteTheStore();
        } finally {
            cacheRootForTest(previousRoot);
        }
    }

    private static Object cacheRootForTest(Object root) throws ReflectiveOperationException {
        var field = ColumnCacheStore.class.getDeclaredField("cacheDir");
        field.setAccessible(true);
        Object previous = field.get(null);
        field.set(null, root);
        return previous;
    }

    private void clearsDeleteTheStore() throws Exception {
        String bucket = "test-xfill-" + Long.toHexString(System.nanoTime());
        assertTrue(ColumnCacheStore.cacheRootPath().startsWith(this.root), "the temporary root is in use");
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
