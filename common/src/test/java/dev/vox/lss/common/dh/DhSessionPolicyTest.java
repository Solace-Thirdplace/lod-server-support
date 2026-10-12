package dev.vox.lss.common.dh;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DhSessionPolicyTest {
    @Test void transientDeferralsPreserveStrikesAndValidSnapshotTimestamps() {
        var p=new DhSessionPolicy();var tile=new DhPosition(6,1,1);
        for(int i=0;i<DhSessionPolicy.MAX_REJECTED_ATTEMPTS;i++){p.cooldown(tile,i);p.defer(tile,i+1);}
        assertTrue(p.retryExhausted(tile));assertTrue(p.parked(tile));assertFalse(p.due(tile,Long.MAX_VALUE));
        p.invalidate(tile);p.accepted(tile,1,1,123,1);p.revalidate(tile,2);p.defer(tile,3);
        assertTrue(p.hasSnapshot(tile));assertEquals(123,p.syncTimestamp(tile));
        assertFalse(p.due(tile,3));assertTrue(p.due(tile,2_000_000_003L));
        p.cooldown(tile,4);p.deferUnverified(tile,5);assertEquals(0,p.syncTimestamp(tile));
        for(int i=1;i<DhSessionPolicy.MAX_REJECTED_ATTEMPTS;i++)p.cooldown(tile,6+i);
        assertTrue(p.retryExhausted(tile));
    }
    @Test void rangeRefusalsParkWithoutPermanentStrikesUntilChangedReachableContext() {
        var policy=new DhSessionPolicy();var p=new DhPosition(6,1,1);
        for(int i=0;i<12;i++) {
            policy.rangeRefused(p,i,false);assertFalse(policy.due(p,Long.MAX_VALUE));assertFalse(policy.retryExhausted(p));
            policy.reconcileRange(p,i,true,100);assertFalse(policy.due(p,100));
            policy.reconcileRange(p,i+1,false,100);assertFalse(policy.due(p,100));
            policy.reconcileRange(p,i+1,true,100);assertTrue(policy.due(p,100));
        }
    }
    @Test void rangeRecoveryRetainsGenerationAndDoesNotRevivePermanentRejections() {
        var policy=new DhSessionPolicy();var p=new DhPosition(6,1,1);
        policy.rangeRefused(p,0,true);assertTrue(policy.rangeNeedsGeneration(p));
        policy.reconcileRange(p,1,true,100);assertTrue(policy.generationNeeded(p));
        for(int i=0;i<DhSessionPolicy.MAX_REJECTED_ATTEMPTS;i++)policy.cooldown(p,100);
        policy.rangeRefused(p,2,true);policy.reconcileRange(p,3,true,100);
        assertTrue(policy.retryExhausted(p));assertFalse(policy.due(p,Long.MAX_VALUE));
    }
    @Test void rangeMetadataIsEvictedAndClearedWithReceipts() {
        var policy=new DhSessionPolicy();var first=new DhPosition(6,0,0);
        for(int i=0;i<=DhSessionPolicy.MAX_RECEIPTS;i++)policy.rangeRefused(new DhPosition(6,i,0),0,true);
        assertFalse(policy.rangeNeedsGeneration(first));assertTrue(policy.due(first,0));
        policy.clearReceipts();assertFalse(policy.rangeNeedsGeneration(new DhPosition(6,1,0)));
    }
    @Test void temporaryPressureNeverExhaustsRetriesAndPreservesAnExistingSnapshot() {
        var policy=new DhSessionPolicy();var p=new DhPosition(6,0,0);
        policy.accepted(p,1,1,100,0);
        for(int i=0;i<20;i++) {
            long now=i*3_000_000_000L;policy.defer(p,now);
            assertFalse(policy.retryExhausted(p));assertEquals(100,policy.syncTimestamp(p));
            assertTrue(policy.due(p,now+2_000_000_000L));
        }
        for(int i=0;i<4;i++)policy.cooldown(p,100_000_000_000L);
        assertTrue(policy.retryExhausted(p),"Actual repeated sink rejection remains bounded");
    }
    @Test void cachedTerrainGetsOneExplicitLoadCheckWithoutPeriodicPolling() {
        var p=new DhSessionPolicy();var tile=new DhPosition(6,1,1);
        assertTrue(p.accepted(tile,1,1,123,2));assertFalse(p.due(tile,3));
        p.revalidate(tile,3);assertTrue(p.due(tile,3));assertEquals(123,p.syncTimestamp(tile));
        assertTrue(p.accepted(tile,1,1,123,4));assertFalse(p.due(tile,Long.MAX_VALUE));
    }
    @Test void failedRejoinValidationInvalidatesTheConditionalTimestampAndKeepsRetryBackoff() {
        var p=new DhSessionPolicy();var tile=new DhPosition(6,1,1);
        p.accepted(tile,1,1,123,1);p.revalidate(tile,2);p.cooldown(tile,3);
        assertEquals(0,p.syncTimestamp(tile));assertFalse(p.due(tile,4));assertTrue(p.due(tile,30_000_000_003L));
    }
    @Test void unreachableAbsenceParksUntilMovementMakesGenerationPossibleOrReceiptsAreReset() {
        var p=new DhSessionPolicy();var tile=new DhPosition(6,20,0);
        p.absent(tile,0,false);
        assertFalse(p.due(tile,Long.MAX_VALUE));assertEquals(0,p.syncTimestamp(tile));

        p.reconcileGeneration(tile,false,200);assertFalse(p.due(tile,Long.MAX_VALUE));
        p.reconcileGeneration(tile,true,300);assertTrue(p.due(tile,300));assertTrue(p.generationNeeded(tile));
        for(int i=0;i<DhSessionPolicy.MAX_REJECTED_ATTEMPTS;i++)p.cooldown(tile,400);
        p.absent(tile,500,false);p.reconcileGeneration(tile,true,600);
        assertFalse(p.due(tile,Long.MAX_VALUE),"Movement cannot reset exhausted rejection retries");
        p.clearReceipts();assertTrue(p.due(tile,700));
    }
    @Test void emptyGenerationRetriesUseTheRejectionBudgetInsteadOfBecomingUntouchedAgain() {
        var p=new DhSessionPolicy();var tile=new DhPosition(6,1,1);
        p.absent(tile,0,true);assertEquals(0,p.acquisitionPriority(tile));
        p.cooldown(tile,2_000_000_000L);
        assertTrue(p.generationNeeded(tile));assertEquals(1,p.acquisitionPriority(tile));
        assertFalse(p.due(tile,31_999_999_999L));assertTrue(p.due(tile,32_000_000_000L));
    }
    @Test void acceptedRefreshRequiresServerSyncAndCannotUseGenerationAsASubstitute() {
        var p=new DhSessionPolicy();var tile=new DhPosition(6,0,0);
        assertTrue(p.syncAllowed(tile,false,0),"Initial terrain may still use permitted generation");
        p.accepted(tile,0xffff,0xffff,99,0);
        assertFalse(p.syncAllowed(tile,false,10));assertFalse(p.syncAllowed(tile,true,0));
        assertTrue(p.syncAllowed(tile,true,1));
    }
    @Test void restoredReceiptsRevalidateWithSyncAndHoldWithout() {
        var p=new DhSessionPolicy();var a=new DhPosition(6,0,0);var b=new DhPosition(6,1,0);
        long stamp=1_790_000_000_123L;
        p.restore(a,stamp,10,true);
        assertTrue(p.due(a,10));assertEquals(stamp,p.syncTimestamp(a),"Do not apply local clock offsets to a server-origin timestamp");
        assertEquals(2,p.acquisitionPriority(a),"A restored receipt is a conditional refresh, never frontier work");
        p.restore(b,stamp,10,false);
        assertFalse(p.due(b,Long.MAX_VALUE),"Without synchronization a restored receipt is trusted and held");
        assertTrue(p.hasSnapshot(b));
        p.restore(a,1,20,false);assertEquals(stamp,p.syncTimestamp(a),"Restore never overwrites a session receipt");
        p.restore(new DhPosition(6,2,0),0,20,true);assertEquals(0,p.timestamp(new DhPosition(6,2,0)));
        p.cooldown(a,200);assertEquals(0,p.syncTimestamp(a));
        p.clearReceipts();assertTrue(p.due(b,0));
    }
    @Test void partialRefreshRequiresFullDataUntilEveryEligibleColumnIsAcceptedAgain() {
        var p=new DhSessionPolicy();var tile=new DhPosition(6,0,0);
        p.accepted(tile,3,3,42,0);
        assertFalse(p.accepted(tile,3,1,43,100));
        assertEquals(42,p.timestamp(tile));assertEquals(0,p.syncTimestamp(tile));
        assertTrue(p.accepted(tile,3,3,44,200));assertEquals(44,p.syncTimestamp(tile));
    }




    @Test void acceptedTerrainNeverPollsButAnExplicitFailureCanScheduleRecovery() {
        var p=new DhSessionPolicy(); var tile=new DhPosition(6,0,0);
        assertTrue(p.accepted(tile,0xffff,0xffff,0,10));
        assertFalse(p.due(tile,10));
        assertFalse(p.due(tile,Long.MAX_VALUE));
        assertEquals(2,p.acquisitionPriority(tile));
        assertFalse(p.retryExhausted(tile));
        p.cooldown(tile,10); p.absent(tile,10,true);
        assertTrue(p.due(tile,2_000_000_010L)); assertTrue(p.generationNeeded(tile));
        p.clearReceipts(); assertTrue(p.due(tile,11));
    }
    @Test void officialRadius128AtATileCenterDoesNotEvictIts4225Snapshots() {
        var p=new DhSessionPolicy(); int acquired=0;
        // Player chunk (2,2) makes both inclusive edges land exactly on tile centers.
        for(int x=-32;x<=32;x++) for(int z=-32;z<=32;z++) {
            var tile=new DhPosition(6,x,z);
            assertTrue(Math.abs(tile.x()*4+2-2)<=128 && Math.abs(tile.z()*4+2-2)<=128);
            assertTrue(p.accepted(tile,0xffff,0xffff,++acquired,0));
        }
        assertEquals(4225,acquired);
        for(int x=-32;x<=32;x++) for(int z=-32;z<=32;z++) {
            var tile=new DhPosition(6,x,z);
            assertFalse(p.due(tile,Long.MAX_VALUE),"Acquired frontier must not be made untouched by receipt eviction");
            assertTrue(p.timestamp(tile)>0);
        }
    }
    @Test void receiptHistoryRemainsBoundedBeyondThePlayableFrontier() {
        var p=new DhSessionPolicy(); var first=new DhPosition(6,0,0);
        for(int x=0;x<=DhSessionPolicy.MAX_RECEIPTS;x++)
            assertTrue(p.accepted(new DhPosition(6,x,0),1,1,x+1,0));
        assertTrue(p.due(first,0)); assertEquals(0,p.timestamp(first));
        assertFalse(p.due(new DhPosition(6,1,0),Long.MAX_VALUE));
        assertFalse(p.due(new DhPosition(6,DhSessionPolicy.MAX_RECEIPTS,0),Long.MAX_VALUE));
    }
    @Test void repeatedSinkRejectionBacksOffAndStopsUntilReset() {
        var p=new DhSessionPolicy(); var tile=new DhPosition(6,0,0);
        long now=0;
        for(long delay:new long[]{30_000_000_000L,60_000_000_000L,120_000_000_000L}) {
            assertFalse(p.accepted(tile,0xffff,0xfffe,999,now));
            assertFalse(p.due(tile,now+delay-1));
            now+=delay; assertTrue(p.due(tile,now));
            assertFalse(p.retryExhausted(tile)); assertEquals(0,p.timestamp(tile));
        }
        assertFalse(p.accepted(tile,0xffff,0xfffe,999,now));
        assertTrue(p.retryExhausted(tile)); assertFalse(p.due(tile,Long.MAX_VALUE));
        p.absent(tile,now,true); assertFalse(p.due(tile,Long.MAX_VALUE));
        p.clearReceipts(); assertFalse(p.retryExhausted(tile)); assertTrue(p.due(tile,now));
    }
    @Test void aRecoveredSinkGetsAFreshRetryBudgetAndRefreshFailuresKeepItsStamp() {
        var p=new DhSessionPolicy(); var tile=new DhPosition(6,0,0);
        p.cooldown(tile,0); p.cooldown(tile,30_000_000_000L); p.cooldown(tile,90_000_000_000L);
        assertTrue(p.accepted(tile,3,3,42,210_000_000_000L));
        p.cooldown(tile,240_000_000_000L);
        assertFalse(p.retryExhausted(tile)); assertEquals(42,p.timestamp(tile));
        assertEquals(2,p.acquisitionPriority(tile));
        assertFalse(p.due(tile,269_999_999_999L)); assertTrue(p.due(tile,270_000_000_000L));
    }


    @Test void pluginBordersUseBlocksWhileOfficialBordersUseChunks() {
        var tile=new DhPosition(6,16,0); // center (66,2) chunks == (1056,32) blocks
        assertTrue(DhSessionPolicy.inBorder(DhProfile.SUPPORT_15,tile,1024,0,256));
        assertFalse(DhSessionPolicy.inBorder(DhProfile.SUPPORT_15,tile,0,0,256));
        assertTrue(DhSessionPolicy.inBorder(DhProfile.OFFICIAL_16,tile,64,0,16));
        assertFalse(DhSessionPolicy.inBorder(DhProfile.OFFICIAL_16,tile,1024,0,256));
        assertTrue(DhSessionPolicy.inBorder(DhProfile.SUPPORT_15,tile,0,0,0));
    }
    @Test void vanillaAuthorityUsesRegionMasksWithoutAColumnCap() {
        var p=new DhSessionPolicy();
        assertTrue(p.vanilla(0,0));assertFalse(p.vanilla(31,31),"Same 32x32 region reuses its mask");
        assertTrue(p.vanilla(-1,-1));assertTrue(p.vanilla(32,0));assertEquals(3,p.vanillaRegions());
        for(int x=-40;x<40;x++)for(int z=-40;z<40;z++)assertEquals(!(x==0&&z==0||x==31&&z==31||x==-1&&z==-1||x==32&&z==0),p.eligible(x,z),x+","+z);
        assertTrue(p.hasVanillaRegion(5,5));assertFalse(p.hasVanillaRegion(64,64));
        // 400k columns exceed the retired 262,144 hard stop.
        for(int x=0;x<640;x++)for(int z=0;z<640;z++)p.vanilla(x,z);
        assertFalse(p.eligible(639,639));assertEquals(401,p.vanillaRegions());
    }
    @Test void vanillaAuthoritySurvivesRefreshAndReceiptReset() {
        var p=new DhSessionPolicy();
        p.vanilla(-1,8);
        assertFalse(p.eligible(-1,8)); assertTrue(p.eligible(8,-1));
        var tile=new DhPosition(6,0,0);
        p.accepted(tile,1,1,99,0);
        assertFalse(p.due(tile,1));
        p.clearReceipts(); assertFalse(p.eligible(-1,8));
        assertTrue(p.due(tile,1)); assertEquals(0,p.timestamp(tile));
    }
    @Test void OneFailedColumnCannotMakeTheTileFresh() {
        var p=new DhSessionPolicy(); var tile=new DhPosition(6,-1,0);
        assertFalse(p.accepted(tile,0xffff,0xfffe,1234,0));
        assertEquals(0,p.timestamp(tile));
        assertFalse(p.due(tile,29_999_999_999L)); assertTrue(p.due(tile,30_000_000_000L));
    }
    @Test void onlyEligibleColumnsCountAndAllVanillaDoesNotCreateAReceipt() {
        var p=new DhSessionPolicy(); var tile=new DhPosition(6,0,0);
        assertFalse(p.accepted(tile,0,0,12,0)); assertEquals(0,p.timestamp(tile));
        assertTrue(p.accepted(tile,0x8001,0x8001,12,40_000_000_000L));
        assertEquals(12,p.timestamp(tile)); assertFalse(p.due(tile,40_000_000_099L));
        assertFalse(p.due(tile,Long.MAX_VALUE));
    }
    @Test void absenceMayScheduleGenerationButRefusalPreservesPriorReceipt() {
        var p=new DhSessionPolicy(); var tile=new DhPosition(6,0,0);
        p.absent(tile,0,true); assertTrue(p.generationNeeded(tile));
        assertFalse(p.due(tile,1_999_999_999L)); assertTrue(p.due(tile,2_000_000_000L));
        p.accepted(tile,1,1,18,3_000_000_000L);
        p.cooldown(tile,4_000_000_000L); assertEquals(18,p.timestamp(tile));
        assertFalse(p.generationNeeded(tile));
    }
    /** A push baked without its stored neighbours refetches the merged tile once the burst settles. */
    @Test void aPushRefetchReplacesTheHeldReceiptAndIsNeverPostponedByLaterPushes() {
        var p=new DhSessionPolicy(); var tile=new DhPosition(6,0,0); long delay=DhSessionPolicy.PUSH_REFETCH_DELAY_NANOS;
        p.accepted(tile,1,1,42,0); assertFalse(p.due(tile,Long.MAX_VALUE));
        p.refetchAfterPush(tile,1_000);
        assertFalse(p.hasSnapshot(tile)); assertEquals(0,p.timestamp(tile)); assertEquals(1,p.acquisitionPriority(tile));
        assertFalse(p.due(tile,1_000+delay-1)); assertTrue(p.due(tile,1_000+delay));
        p.refetchAfterPush(tile,1_000+delay/2);
        assertTrue(p.due(tile,1_000+delay),"a later push in the burst keeps the scheduled refetch");
        p.refetchAfterPush(tile,1_000+2*delay);
        assertFalse(p.due(tile,1_000+3*delay-1),"after the refetch time passed, a new push starts a new window");
    }

}
