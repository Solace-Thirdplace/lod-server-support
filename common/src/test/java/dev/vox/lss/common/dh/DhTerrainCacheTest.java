package dev.vox.lss.common.dh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DhTerrainCacheTest {
    @Test void anUndeletableBucketCannotCauseDeclinedWritesToAccumulate() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"));
        var old=new DhTerrainCache(root,key("old-read-only"),2,4096);
        var next=new DhTerrainCache(root,key("new-writable"),2,4096);
        old.write(POSITION,1,new byte[]{1});Path bucket=file().getParent();
        var permissions=Files.getPosixFilePermissions(bucket);
        try {
            Files.setPosixFilePermissions(bucket,java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
            // One irremovable file can coexist with repeated replacement in a writable bucket.
            for(int i=0;i<12;i++)assertTrue(next.write(new DhPosition(6,i,0),2,new byte[]{2}));
            assertEquals(1,next.positions().size());assertEquals(Set.of(POSITION),old.positions());
            old.protect(Set.of(POSITION));next.protect(next.positions());
            for(int i=20;i<30;i++)assertFalse(next.write(new DhPosition(6,i,0),3,new byte[]{3}));
            try(var files=Files.walk(root)){assertEquals(2,files.filter(p->p.toString().endsWith(".dht")).count());}
        }finally{Files.setPosixFilePermissions(bucket,permissions);old.close();next.close();}
    }
    @Test void clearSkipsRefusedLinksAndRepairsOnlyAnOwnedLockedRevision() throws Exception {
        Path foreign=Files.createDirectory(root.resolve("foreign"));Files.writeString(foreign.resolve("keep"),"mine");
        Path linked=root.resolve("linked");Files.createSymbolicLink(linked,foreign);
        DhTerrainCache.clear(linked,null);assertEquals("mine",Files.readString(foreign.resolve("keep")));
        Files.createSymbolicLink(root.resolve(DhTerrainCache.DIRECTORY),foreign);
        DhTerrainCache.clear(root,null);assertTrue(Files.exists(foreign.resolve("keep")));
        Files.delete(root.resolve(DhTerrainCache.DIRECTORY));
        var cache=new DhTerrainCache(root,key("repair"));cache.write(POSITION,1,new byte[]{1});
        Path lock=root.resolve(DhTerrainCache.DIRECTORY).resolve("quota.lock");Files.writeString(lock,"broken");
        assertThrows(java.io.IOException.class,cache::positions);DhTerrainCache.clear(root,null);
        assertEquals(8,Files.size(lock));assertTrue(cache.positions().isEmpty());
    }
    @Test void otherBucketsUseGlobalLruAndEveryLiveOwnersProtection() throws Exception {
        var first=new DhTerrainCache(root,key("first"),4,4096);
        var other=new DhTerrainCache(root,key("other"),4,4096);
        var old=new DhPosition(6,0,0);var recent=new DhPosition(6,100,0);
        first.write(old,1,new byte[]{1});Thread.sleep(5);
        first.write(new DhPosition(6,1,0),1,new byte[]{1});Thread.sleep(5);
        first.write(new DhPosition(6,2,0),1,new byte[]{1});Thread.sleep(5);
        other.write(recent,2,new byte[]{2});other.protect(Set.of(recent));
        for(int i=3;i<8;i++)first.write(new DhPosition(6,i,0),3,new byte[]{3});
        assertEquals(Set.of(recent),other.positions());assertFalse(first.positions().contains(old));
        other.close();Thread.sleep(5);first.write(new DhPosition(6,8,0),4,new byte[]{4});
        assertTrue(other.positions().isEmpty(),"Retiring the owner must release its old source pin");
    }
    @Test void changedRegistryOrFallbackIdentityCannotReuseAnUnchangedSource() throws Exception {
        var k=key("conversion");
        var one=new DhTerrainCache(root,new DhTerrainCache.Key(k.address(),k.server(),k.world(),k.dimension(),k.seed(),k.profile(),k.shape(),"registry-and-fallback-a"));
        one.write(POSITION,1,new byte[]{1});
        var two=new DhTerrainCache(root,new DhTerrainCache.Key(k.address(),k.server(),k.world(),k.dimension(),k.seed(),k.profile(),k.shape(),"registry-and-fallback-b"));
        assertTrue(two.positions().isEmpty());
    }
    @Test void aSecondJvmCannotBypassTheGlobalQuotaWithAStaleIndex() throws Exception {
        var cache=new DhTerrainCache(root,key("process"),12,4096);
        for(int i=0;i<12;i++)cache.write(new DhPosition(6,i,0),1,new byte[]{1});
        var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),
                Writer.class.getName(),root.toString()).redirectErrorStream(true).start();
        assertTrue(child.waitFor(30,java.util.concurrent.TimeUnit.SECONDS),"quota child deadline");
        assertEquals(0,child.exitValue(),new String(child.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        cache.write(new DhPosition(6,500,0),3,new byte[]{3});
        try(var files=Files.walk(root)){assertEquals(12,files.filter(p->p.toString().endsWith(".dht")).count());}
    }
    public static final class Writer {
        public static void main(String[] args) throws Exception {
            try(var cache=new DhTerrainCache(Path.of(args[0]),key("process"),12,4096)) {
                for(int i=100;i<112;i++)cache.write(new DhPosition(6,i,0),2,new byte[]{2});
            }
        }
    }
    @TempDir Path root;
    private static final DhPosition POSITION = new DhPosition(6, 4, -3);
    private static final DhTerrainTile.WorldShape SHAPE = new DhTerrainTile.WorldShape(-64, 384, true);
    private static DhTerrainCache.Key key(String address) { return new DhTerrainCache.Key(address,"server","world","minecraft:overworld",9,DhProfile.OFFICIAL_16,SHAPE); }
    private byte[] dto() throws Exception {
        try (var in = getClass().getResourceAsStream("/dh-corpus/dh-official-3.3.3-neoforge-ocean/database-tile.dto")) { return Objects.requireNonNull(in).readAllBytes(); }
    }
    private long stamp(byte[] data) throws Exception {
        var budget = new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
        try (var tile = new DhTerrainDecoder(DhLimits.DEFAULT).decode(data,DhProfile.OFFICIAL_16,POSITION,SHAPE,budget)) { return tile.modifiedTimestamp(); }
    }
    private Path file() throws Exception {
        try (var files = Files.walk(root)) { return files.filter(p -> p.toString().endsWith(".dht")).findFirst().orElseThrow(); }
    }
    @Test void sourceTerrainAndServerTimestampSurviveANewCacheInstanceWithoutVoxy() throws Exception {
        var data=dto();long timestamp=stamp(data);
        new DhTerrainCache(root,key("one")).write(POSITION,timestamp,data);
        var reopened=new DhTerrainCache(root,key("one"));assertEquals(Set.of(POSITION),reopened.positions());
        var budget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
        try(var entry=reopened.read(POSITION,budget)) {
            assertNotNull(entry);assertEquals(timestamp,entry.timestamp());assertTrue(budget.used()>data.length);
            try(var tile=entry.decode(new DhTerrainDecoder(DhLimits.DEFAULT),DhProfile.OFFICIAL_16,SHAPE)) {
                assertEquals(POSITION,tile.position());assertEquals(timestamp,tile.modifiedTimestamp());
                assertTrue(tile.materials().stream().anyMatch(m->m.block().equals("minecraft:water")));
            }
        }
        assertEquals(0,budget.used());
    }
    @Test void everySourceIdentityAxisIsIsolated() throws Exception {
        var k=key("one");new DhTerrainCache(root,k).write(POSITION,stamp(dto()),dto());
        var variants=List.of(key("two"),
                new DhTerrainCache.Key("one","different-server","world",k.dimension(),9,k.profile(),SHAPE),
                new DhTerrainCache.Key("one","server","different-world",k.dimension(),9,k.profile(),SHAPE),
                new DhTerrainCache.Key("one","server","world","minecraft:the_nether",9,k.profile(),SHAPE),
                new DhTerrainCache.Key("one","server","world",k.dimension(),10,k.profile(),SHAPE),
                new DhTerrainCache.Key("one","server","world",k.dimension(),9,DhProfile.OFFICIAL_15,SHAPE),
                new DhTerrainCache.Key("one","server","world",k.dimension(),9,k.profile(),new DhTerrainTile.WorldShape(-128,512,true)),
                new DhTerrainCache.Key("one","server","world",k.dimension(),9,k.profile(),new DhTerrainTile.WorldShape(-64,384,false)));
        for(var changed:variants)assertTrue(new DhTerrainCache(root,changed).positions().isEmpty(),changed.toString());
    }
    @Test void oldConversionMetadataCannotBeUsedAsAnUnchangedSource() throws Exception {
        var cache=new DhTerrainCache(root,key("one"));cache.write(POSITION,1,new byte[]{1});
        byte[] bytes=Files.readAllBytes(file());java.nio.ByteBuffer.wrap(bytes).putInt(92,DhTerrainCache.CONVERSION_VERSION-1);
        Files.write(file(),bytes);var budget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
        assertNull(cache.read(POSITION,budget));assertEquals(0,budget.used());assertTrue(cache.positions().isEmpty());
    }
    @Test void fullFlightBucketEvictsUnvisitedTerrainAndKeepsSubsequentHomeJoins() throws Exception {
        var cache=new DhTerrainCache(root,key("flight"));var home=new HashSet<DhPosition>();
        for(int i=0;i<121;i++){var p=new DhPosition(6,i,0);home.add(p);cache.write(p,1,new byte[]{1});}
        for(int i=0;i<8192;i++) {
            var p=new DhPosition(6,1000+i,0);cache.protect(Set.of(p));assertTrue(cache.write(p,2,new byte[]{2}));
        }
        for(int session=2;session<=4;session++) {
            cache=new DhTerrainCache(root,key("flight"));cache.protect(home);
            var positions=cache.positions();long retained=home.stream().filter(positions::contains).count();
            if(session>=3)assertTrue(retained>=120,"home area was declined again: "+retained);
            for(var p:home)if(!positions.contains(p))assertTrue(cache.write(p,3,new byte[]{3}));
        }
    }
    @Test void corruptionIsAMissAndReleasesItsMemoryRatherThanClaimingUnchanged() throws Exception {
        var cache=new DhTerrainCache(root,key("one"));cache.write(POSITION,1,dto());var file=file();
        byte[] contents=Files.readAllBytes(file);contents[contents.length-1]^=1;Files.write(file,contents);
        var budget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());assertNull(cache.read(POSITION,budget));
        assertEquals(0,budget.used());assertFalse(Files.exists(file));assertTrue(cache.positions().isEmpty());
    }
    @Test void truncatedOrWrongVersionFilesAreMisses() throws Exception {
        var cache=new DhTerrainCache(root,key("one"));cache.write(POSITION,1,dto());var file=file();
        Files.write(file,new byte[3]);assertNull(cache.read(POSITION,new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes())));
        cache.write(POSITION,1,dto());byte[] contents=Files.readAllBytes(file);contents[7]=3;Files.write(file,contents);
        assertNull(cache.read(POSITION,new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes())));
    }
    @Test void quotaCoversOtherServerBucketsAndEvictedDataBecomesMissing() throws Exception {
        var first=new DhTerrainCache(root,key("one"),2,1024);var second=new DhTerrainCache(root,key("two"),2,1024);
        first.write(POSITION,1,new byte[400]);Files.setLastModifiedTime(file(),java.nio.file.attribute.FileTime.fromMillis(1));
        second.write(new DhPosition(6,1,0),2,new byte[400]);second.write(new DhPosition(6,2,0),3,new byte[400]);
        assertTrue(first.positions().isEmpty());assertEquals(2,second.positions().size());
        try(var files=Files.walk(root)) { assertTrue(files.filter(p->p.toString().endsWith(".dht")).mapToLong(p->{try{return Files.size(p);}catch(Exception e){throw new RuntimeException(e);}}).sum()<=1024); }
    }
    @Test void byteQuotaCannotBeBypassedByOneOversizedTile() throws Exception {
        var cache=new DhTerrainCache(root,key("one"),5,300);cache.write(POSITION,1,new byte[301]);assertTrue(cache.positions().isEmpty());
    }
    @Test void resetClearsAllWorldVariantsForOnlyTheSelectedServer() throws Exception {
        var first=new DhTerrainCache(root,key("one"));var other=new DhTerrainCache(root,key("two"));
        var variant=new DhTerrainCache(root,new DhTerrainCache.Key("one","server","new-world","minecraft:the_end",77,DhProfile.OFFICIAL_15,SHAPE));
        for(var cache:List.of(first,other,variant))cache.write(POSITION,1,new byte[]{1});
        DhTerrainCache.clear(root,"one");assertTrue(first.positions().isEmpty());assertTrue(variant.positions().isEmpty());assertEquals(Set.of(POSITION),other.positions());
        DhTerrainCache.clear(root,null);assertTrue(other.positions().isEmpty());
    }
    @Test void symlinkedCacheRootIsRefusedAndForeignContentsSurvive() throws Exception {
        var foreign=Files.createDirectory(root.resolve("foreign"));var sentinel=foreign.resolve("sentinel");Files.writeString(sentinel,"keep");
        Files.createSymbolicLink(root.resolve(DhTerrainCache.DIRECTORY),foreign);
        var cache=new DhTerrainCache(root,key("one"));assertThrows(java.io.IOException.class,cache::positions);
        assertThrows(java.io.IOException.class,()->cache.write(POSITION,1,new byte[]{1}));
        assertDoesNotThrow(()->DhTerrainCache.clear(root,null));assertEquals("keep",Files.readString(sentinel));
    }
    @Test void symlinkedTileNeverReadsOrDeletesItsForeignTarget() throws Exception {
        var cache=new DhTerrainCache(root,key("one"));cache.write(POSITION,1,new byte[]{1});var path=file();Files.delete(path);
        var foreign=root.resolve("foreign-tile");Files.writeString(foreign,"keep");Files.createSymbolicLink(path,foreign);
        assertNull(cache.read(POSITION,new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes())));
        DhTerrainCache.clear(root,"one");assertEquals("keep",Files.readString(foreign));
    }
    @Test void pinnedWorkingSetSurvivesOneTileOverflowAndOtherDimensionWrites() throws Exception {
        var cache=new DhTerrainCache(root,key("one"),120,1<<20);
        var ordered=new ArrayList<DhPosition>();
        for(int x=0;x<121;x++){var p=new DhPosition(6,x,0);ordered.add(p);cache.write(p,1,new byte[]{1});}
        var budget=new DhMemoryBudget(1<<20);cache.protect(cache.positions());int restored=0;
        for(var p:ordered)try(var entry=cache.read(p,budget)) {
            if(entry==null)cache.write(p,1,new byte[]{1});else restored++;
        }
        assertEquals(120,restored,"One miss must not evict every subsequent restore");
        var k=key("one");
        var other=new DhTerrainCache(root,new DhTerrainCache.Key(k.address(),k.server(),k.world(),"minecraft:the_nether",k.seed(),k.profile(),SHAPE),150,1<<20);
        for(int x=0;x<49;x++)other.write(new DhPosition(6,x,0),1,new byte[]{2});
        cache=new DhTerrainCache(root,key("one"),150,1<<20);cache.protect(cache.positions());restored=0;
        for(var p:ordered)try(var entry=cache.read(p,budget)) {if(entry==null)cache.write(p,1,new byte[]{1});else restored++;}
        assertTrue(restored>=100,"Other buckets must be evicted before the active protected world");
        assertEquals(0,budget.used());
    }
    @Test void failedReplacementCannotLeaveSupersededSource() throws Exception {
        var cache=new DhTerrainCache(root,key("one"),5,300);cache.write(POSITION,5,new byte[]{1});
        assertFalse(cache.write(POSITION,6,new byte[400]));assertTrue(cache.positions().isEmpty());
        cache.write(POSITION,7,new byte[]{1});assertFalse(cache.write(POSITION,-1,new byte[]{2}));assertTrue(cache.positions().isEmpty());
    }
    @Test void successfulReadRenewsLruAndStalePendingFilesAreSwept() throws Exception {
        var cache=new DhTerrainCache(root,key("one"),2,1024);cache.write(POSITION,1,new byte[]{1});
        var p=file();Files.setLastModifiedTime(p,java.nio.file.attribute.FileTime.fromMillis(1));
        try(var entry=cache.read(POSITION,new DhMemoryBudget(1024))) {assertNotNull(entry);}
        assertTrue(Files.getLastModifiedTime(p).toMillis()>1);
        var pending=p.getParent().resolve(".pending-abandoned.tmp");Files.write(pending,new byte[500]);
        Files.setLastModifiedTime(pending,java.nio.file.attribute.FileTime.fromMillis(1));
        // Clear the rebuildable quota index to emulate opening after a process crash.
        var indexes=DhTerrainCache.class.getDeclaredField("INDEXES");indexes.setAccessible(true);((Map<?,?>)indexes.get(null)).clear();
        cache.positions();assertFalse(Files.exists(pending));assertEquals(Set.of(POSITION),cache.positions());
    }
    @Test void acceptedWorkingSetReleasesPinsForNewTerrainAdmission() throws Exception {
        var cache=new DhTerrainCache(root,key("one"),2,1024);
        var first=new DhPosition(6,0,0);var next=new DhPosition(6,1,0);var fresh=new DhPosition(6,2,0);
        cache.write(first,1,new byte[]{1});cache.write(next,1,new byte[]{1});cache.protect(Set.of(first,next));
        cache.unprotect(first);assertTrue(cache.write(fresh,2,new byte[]{2}));
        assertEquals(Set.of(next,fresh),cache.positions(),"Pending restore survives while already accepted source can evict");
    }
    @Test void budgetPressureDoesNotDeleteAValidFile() throws Exception {
        var cache=new DhTerrainCache(root,key("one"));cache.write(POSITION,1,new byte[100]);
        assertThrows(DhCodecException.class,()->cache.read(POSITION,new DhMemoryBudget(1)));
        assertEquals(Set.of(POSITION),cache.positions());
    }
    @Test void legacyOwnedBucketsAreAdoptedWithoutMovingForeignNativeFiles() throws Exception {
        var cache=new DhTerrainCache(root,key("one"));cache.write(POSITION,1,new byte[]{1});
        Files.move(root.resolve(DhTerrainCache.DIRECTORY),root.resolve(DhTerrainCache.LEGACY_DIRECTORY));
        var nativeFile=root.resolve(DhTerrainCache.LEGACY_DIRECTORY).resolve("minecraft_overworld.bin");Files.writeString(nativeFile,"keep");
        assertEquals(Set.of(POSITION),new DhTerrainCache(root,key("one")).positions());
        assertTrue(Files.isDirectory(root.resolve(DhTerrainCache.DIRECTORY)));assertEquals("keep",Files.readString(nativeFile));
        DhTerrainCache.clear(root,null);assertEquals("keep",Files.readString(nativeFile));
    }
}
