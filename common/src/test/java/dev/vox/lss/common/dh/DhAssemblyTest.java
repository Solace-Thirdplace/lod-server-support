package dev.vox.lss.common.dh;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static dev.vox.lss.common.dh.DhMessage.*;

class DhAssemblyTest {
    @Test void retainedFragmentsStayImmutableWhenPublicInputsAndAccessorsAreMutated() throws Exception {
        var bytes=DhProtocolCodecTest.capture("dh-support-0.14.0-ocean","received-0003.bin");
        var codec=new DhProtocolCodec(DhProfile.SUPPORT_15,DhLimits.DEFAULT);
        var original=(Split)codec.decode(bytes);byte[] input=original.bytes();
        var split=new Split(original.bufferId(),input,true);var budget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
        try(var assembly=new DhAssembly(DhLimits.DEFAULT,budget)) {
            assembly.expect(1,new DhPosition(6,4,-1));assembly.accept(split);
            java.util.Arrays.fill(input,(byte)0);java.util.Arrays.fill(split.bytes(),(byte)0);
            java.util.Arrays.fill(original.bytes(),(byte)0);java.util.Arrays.fill(bytes,(byte)0);
            var response=(Response)codec.decode(DhProtocolCodecTest.capture("dh-support-0.14.0-ocean","received-0004.bin"));
            try(var completed=assembly.response(response);var tile=completed.decode(new DhTerrainDecoder(DhLimits.DEFAULT),
                    DhProfile.SUPPORT_15,new DhTerrainTile.WorldShape(-64,384,true))) {
                assertEquals("d4466603cf698f715933c6cc986bde4e766ff06a559b15361a002f1f4661310f",tile.normalizedSha256());
            }
        }
        assertEquals(0,budget.used());
    }
    @Test void safeArrivalCutoffProtectsTimelyQueuedFramesAndExpiresOlderBuffers() throws Exception {
        var time=new AtomicLong();var budget=new DhMemoryBudget(10000);
        try(var a=new DhAssembly(deadlines(),budget,time::get)) {
            a.expect(1,new DhPosition(6,0,0));a.accept(new Split(10,new byte[]{1},true),1);
            time.set(100);
            assertTrue(a.expire(9).buffers().isEmpty());assertTrue(a.expire(9).requests().isEmpty());
            a.accept(new Split(10,new byte[]{2},false),9);
            assertEquals(List.of(10),a.expire(19).buffers());assertEquals(0,budget.used());
            assertTrue(a.expire(24).requests().isEmpty());assertEquals(List.of(1),a.expire(25).requests());
        }
        assertEquals(0,budget.used());
    }
    private static DhLimits deadlines() {
        var d = DhLimits.DEFAULT;
        return new DhLimits(d.frameBytes(),d.tileBytes(),d.decodedBytes(),d.ownedBytes(),d.decompressorKiB(),
                d.buffers(),d.spans(),d.mappings(),d.mappingBytes(),d.stringBytes(),d.worldHeight(),10,25);
    }
    @Test void startupOwnersKeepTheirDeadlineWhileNewOpenRateWorkReturnsToSixtySeconds() throws Exception {
        var time=new AtomicLong();var lifetime=new AtomicLong(120_000_000_000L);var budget=new DhMemoryBudget(10000);
        try(var a=new DhAssembly(DhLimits.DEFAULT.withLifetime(lifetime.get()),budget,time::get,lifetime::get)) {
            a.expect(1,new DhPosition(6,0,0));a.accept(new Split(1,new byte[]{1},true));
            lifetime.set(60_000_000_000L);a.expect(2,new DhPosition(6,1,0));a.accept(new Split(2,new byte[]{1},true));
            for(int i=1;i<8;i++) {time.set(i*9_000_000_000L);a.accept(new Split(1,new byte[]{1},false));}
            assertEquals(List.of(2),a.expire().requests());
            try(var complete=a.response(new Response(1,1,List.of()))) {assertEquals(8,complete.bytes());}
        }
        assertEquals(0,budget.used());
    }
    @Test void pacedLargeStartupTransferHasBoundedHeadroomAndUnchangedMemoryAndIdleLimits() throws Exception {
        var time=new AtomicLong();var limits=DhLimits.DEFAULT.withLifetime(120_000_000_000L);
        var budget=new DhMemoryBudget(limits.ownedBytes());
        try(var assembly=new DhAssembly(limits,budget,time::get)) {
            assembly.expect(1,new DhPosition(6,0,0));
            for(int i=0;i<35;i++) {time.set(i*2_000_000_000L);assembly.accept(new Split(1,new byte[65536],i==0));}
            time.set(70_000_000_000L);
            try(var completed=assembly.response(new Response(1,1,List.of()))) {assertEquals(35*65536,completed.bytes());}
            assertEquals(0,budget.used());
            time.set(0);assembly.expect(2,new DhPosition(6,0,0));
            for(int i=0;i<60;i++) {time.set(i*2_000_000_000L);assembly.accept(new Split(2,new byte[]{1},i==0));}
            time.set(120_000_000_000L);
            assertEquals(DhCodecException.Kind.EXPIRED,assertThrows(DhCodecException.class,
                    ()->assembly.response(new Response(2,2,List.of()))).kind());
        }
        assertEquals(0,budget.used());assertEquals(DhLimits.DEFAULT.idleNanos(),limits.idleNanos());
        assertEquals(DhLimits.DEFAULT.ownedBytes(),limits.ownedBytes());assertEquals(DhLimits.DEFAULT.tileBytes(),limits.tileBytes());
    }
    @Test void requestAndBufferOwnershipAreSeparateAndInterleaved() throws Exception {
        var budget = new DhMemoryBudget(10000);
        try (var a = new DhAssembly(DhLimits.DEFAULT, budget)) {
            a.expect(1,new DhPosition(6,-1,2)); a.expect(2,new DhPosition(6,4,-1));
            a.accept(new Split(70,new byte[]{1,2},true)); a.accept(new Split(80,new byte[]{3},true));
            a.accept(new Split(70,new byte[]{4},false));
            assertEquals(4,a.assembledBytes()); assertEquals(2,a.incompleteBuffers());
            try (var result = a.response(new Response(2,80,List.of()))) { assertEquals(1,result.bytes()); }
            try (var result = a.response(new Response(1,70,List.of()))) { assertEquals(3,result.bytes()); }
            assertEquals(0,budget.used());
            assertThrows(DhCodecException.class, () -> a.response(new Response(1,70,List.of())));
        }
    }
    @Test void joinPeakIsReservedAndFailureDoesNotLeak() throws Exception {
        var budget = new DhMemoryBudget(300);
        try (var a = new DhAssembly(DhLimits.DEFAULT,budget)) {
            a.expect(1,new DhPosition(6,0,0));
            a.accept(new Split(10,new byte[50],true));
            assertEquals(210,budget.used());
            var ex = assertThrows(DhCodecException.class, () -> a.response(new Response(1,10,List.of())));
            assertEquals(DhCodecException.Kind.RESOURCE_LIMIT,ex.kind());
            assertEquals(0,budget.used());assertEquals(0,a.incompleteBuffers());
        }
        assertEquals(0,budget.used());
    }
    @Test void idleAndAbsoluteDeadlinesCannotBeExtendedForever() throws Exception {
        var time = new AtomicLong();
        var budget = new DhMemoryBudget(10000);
        try (var a = new DhAssembly(deadlines(),budget,time::get)) {
            a.expect(1,new DhPosition(6,0,0)); a.accept(new Split(10,new byte[]{1},true));
            time.set(10);
            assertEquals(List.of(10),a.expire().buffers()); assertEquals(0,budget.used());
            a.accept(new Split(11,new byte[]{1},true));
            time.set(19); a.accept(new Split(11,new byte[]{2},false));
            time.set(25); assertEquals(List.of(1),a.expire().requests());
            time.set(28); a.accept(new Split(11,new byte[]{3},false));
            time.set(35);
            assertEquals(DhCodecException.Kind.EXPIRED,
                    assertThrows(DhCodecException.class, () -> a.accept(new Split(11,new byte[]{4},false))).kind());
        }
        assertEquals(0,budget.used());
    }
    @Test void timelyCapturedFramesSurviveWorkerDelayButLateArrivalStillExpires() throws Exception {
        var time = new AtomicLong();
        var budget = new DhMemoryBudget(10000);
        try (var a = new DhAssembly(deadlines(), budget, time::get)) {
            a.expect(1, new DhPosition(6, 0, 0));
            time.set(100); // Processing starts after both the idle and total deadlines.
            a.accept(new Split(10, new byte[]{1}, true), 1);
            a.accept(new Split(10, new byte[]{2}, false), 9);
            try (var complete = a.response(new Response(1, 10, List.of()), 18)) {
                assertEquals(2, complete.bytes());
            }
            assertEquals(0, budget.used());
            time.set(101); a.expect(2, new DhPosition(6, 0, 0));
            a.accept(new Split(20, new byte[]{1}, true), 102);
            var expired = assertThrows(DhCodecException.class,
                    () -> a.response(new Response(2, 20, List.of()), 112));
            assertEquals(DhCodecException.Kind.EXPIRED, expired.kind());
            assertEquals(0, budget.used());
        }
    }
    @Test void arrivalTimeCannotExtendTheAbsoluteRequestDeadline() throws Exception {
        var time = new AtomicLong(); var budget = new DhMemoryBudget(10000);
        try (var a = new DhAssembly(deadlines(), budget, time::get)) {
            a.expect(1, new DhPosition(6, 0, 0)); time.set(100);
            a.accept(new Split(10, new byte[]{1}, true), 24);
            assertEquals(DhCodecException.Kind.EXPIRED, assertThrows(DhCodecException.class,
                    () -> a.response(new Response(1, 10, List.of()), 25)).kind());
        }
        assertEquals(0, budget.used());
    }
    @Test void unknownDuplicateAndMissingFragmentsNeverComplete() throws Exception {
        var budget = new DhMemoryBudget(10000);
        try (var a = new DhAssembly(DhLimits.DEFAULT,budget)) {
            assertThrows(DhCodecException.class, () -> a.accept(new Split(9,new byte[]{1},true)));
            a.expect(1,new DhPosition(6,0,0));
            assertThrows(DhCodecException.class, () -> a.accept(new Split(9,new byte[]{1},false)));
            a.accept(new Split(9,new byte[]{1},true));
            assertThrows(DhCodecException.class, () -> a.accept(new Split(9,new byte[]{1},true)));
            assertThrows(DhCodecException.class, () -> a.response(new Response(2,9,List.of())));
            assertThrows(DhCodecException.class, () -> a.response(new Response(1,8,List.of())));
            a.cancel(1);
            assertThrows(DhCodecException.class, () -> a.response(new Response(1,9,List.of())));
        }
        assertEquals(0,budget.used());
    }
    @Test void absentResponseAndRetirementPreserveReceiptMeaning() throws Exception {
        var budget = new DhMemoryBudget(10000);
        var a = new DhAssembly(DhLimits.DEFAULT,budget);
        a.expect(1,new DhPosition(6,0,0));
        assertNull(a.response(new Response(1,null,List.of())));
        a.expect(2,new DhPosition(6,0,0)); a.accept(new Split(3,new byte[]{7},true));
        var complete = a.response(new Response(2,3,List.of()));
        a.close();
        assertTrue(budget.used()>0); assertEquals(1,complete.bytes());
        assertThrows(IllegalStateException.class, () -> a.expect(4,new DhPosition(6,0,0)));
        complete.close(); complete.close(); assertEquals(0,budget.used());
    }
    @Test void tinyFragmentsHaveABoundedObjectCount() throws Exception {
        var budget = new DhMemoryBudget(2 << 20);
        try (var a = new DhAssembly(DhLimits.DEFAULT, budget)) {
            a.expect(1, new DhPosition(6,0,0));
            for (int i=0;i<4096;i++) a.accept(new Split(1,new byte[]{1},i==0));
            assertEquals(4096L * 161, budget.used());
            assertEquals(DhCodecException.Kind.RESOURCE_LIMIT,
                    assertThrows(DhCodecException.class, () -> a.accept(new Split(1,new byte[]{1},false))).kind());
        }
        assertEquals(0, budget.used());
    }
    @Test void limitsBoundBuffersAndRequestBookkeeping() throws Exception {
        var budget = new DhMemoryBudget(10000);
        try (var a = new DhAssembly(DhLimits.DEFAULT,budget)) {
            for (int i=0;i<DhLimits.DEFAULT.buffers();i++) {
                a.expect(i,new DhPosition(6,0,0)); a.accept(new Split(i,new byte[]{1},true));
            }
            assertThrows(DhCodecException.class, () -> a.expect(100,new DhPosition(6,0,0)));
            assertThrows(DhCodecException.class, () -> a.accept(new Split(100,new byte[]{1},true)));
        }
        assertEquals(0,budget.used());
    }
    @org.junit.jupiter.api.Test void unsolicitedFragmentsRequireAnUpdateSubscriptionAndReleaseTheirOwnership() throws Exception {
        var budget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
        try(var assembly=new DhAssembly(DhLimits.DEFAULT,budget)) {
            var fragment=new DhMessage.Split(91,new byte[]{1,2,3},true);
            assertEquals(DhCodecException.Kind.UNOWNED,assertThrows(DhCodecException.class,()->assembly.accept(fragment)).kind());
            assembly.allowPush(true);assembly.accept(fragment);
            try(var completed=assembly.push(new DhMessage.PartialUpdate("world",91,List.of()),System.nanoTime())) {
                assertEquals(3,completed.bytes());
            }
            assertEquals(0,budget.used());
        }
    }
    @org.junit.jupiter.api.Test void rejectingAnUpdateDiscardsItsBufferWithoutCancellingAnIndependentRequest() throws Exception {
        var budget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
        try(var assembly=new DhAssembly(DhLimits.DEFAULT,budget)) {
            assembly.expect(12,new DhPosition(6,1,2));assembly.allowPush(true);
            assembly.accept(new DhMessage.Split(91,new byte[]{1},true));
            assembly.allowPush(false);
            assertThrows(DhCodecException.class,()->assembly.push(new DhMessage.PartialUpdate("world",91,List.of()),System.nanoTime()));
            assertNull(assembly.response(new DhMessage.Response(12,null,List.of())));
            assertEquals(0,budget.used());
        }
    }

    @Test void aBufferSpanningACaptureLossIsDiscardedNeverAssembledWithAGap() throws Exception {
        var budget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
        try(var assembly=new DhAssembly(DhLimits.DEFAULT,budget)) {
            assembly.allowPush(true);
            assembly.accept(new DhMessage.Split(7,new byte[]{1,2},true),1,0);
            // A middle fragment was dropped (loss epoch 0 -> 1); the next fragment of buffer 7 arrives later.
            var gap=assertThrows(DhCodecException.class,()->assembly.accept(new DhMessage.Split(7,new byte[]{3},false),2,1));
            assertEquals(DhCodecException.Kind.UNOWNED,gap.kind());
            assertEquals(0,assembly.incompleteBuffers());
            // Completion after a loss is also refused, for a buffer whose fragments all predate it.
            assembly.accept(new DhMessage.Split(8,new byte[]{4},true),3,1);
            assertEquals(DhCodecException.Kind.UNOWNED,assertThrows(DhCodecException.class,
                    ()->assembly.push(new DhMessage.PartialUpdate("w",8,List.of()),4,2)).kind());
            // Same-epoch buffers assemble normally.
            assembly.accept(new DhMessage.Split(9,new byte[]{5},true),5,2);
            assembly.accept(new DhMessage.Split(9,new byte[]{6},false),6,2);
            try(var done=assembly.push(new DhMessage.PartialUpdate("w",9,List.of()),7,2)) {assertEquals(2,done.bytes());}
            assertEquals(0,budget.used());
        }
    }
    @Test void expiredOrUnmatchedResponsesReleaseTheirBuffersImmediately() throws Exception {
        var budget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
        long[] clock={0};
        var limits=DhLimits.DEFAULT.withLifetime(DhLimits.DEFAULT.idleNanos());
        try(var assembly=new DhAssembly(limits,budget,()->clock[0])) {
            assembly.expect(1,new DhPosition(6,0,0));
            assembly.accept(new DhMessage.Split(3,new byte[100],true),0);
            assertEquals(DhCodecException.Kind.UNOWNED,assertThrows(DhCodecException.class,
                    ()->assembly.response(new DhMessage.Response(99,3,List.of()),1)).kind());
            assertEquals(0,assembly.incompleteBuffers(),"An unmatched response releases its buffer");
            assembly.accept(new DhMessage.Split(4,new byte[100],true),1);
            assertEquals(DhCodecException.Kind.EXPIRED,assertThrows(DhCodecException.class,
                    ()->assembly.response(new DhMessage.Response(1,4,List.of()),limits.lifetimeNanos()+1)).kind());
            assertEquals(0,assembly.incompleteBuffers(),"An expired response releases its buffer");
            assertEquals(0,budget.used());
        }
    }
}
