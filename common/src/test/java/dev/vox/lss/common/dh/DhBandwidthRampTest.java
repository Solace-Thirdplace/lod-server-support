package dev.vox.lss.common.dh;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DhBandwidthRampTest {
    @Test void supportHasABoundedLocalRequestRampBecauseItsBandwidthFieldIsUnsupported() {
        var ramp=new DhBandwidthRamp(true,true);long start=90_000_000_000L;
        assertEquals(4_000_000_000L,ramp.supportRequestInterval(1_000_000_000L,start));
        assertEquals(2_000_000_000L,ramp.supportRequestInterval(1_000_000_000L,start+2_500_000_000L));
        assertEquals(1_000_000_000L,ramp.supportRequestInterval(1_000_000_000L,start+5_000_000_000L));
        assertEquals(1_000_000_000L,ramp.supportRequestInterval(1_000_000_000L,start+900_000_000_000L));
        var off=new DhBandwidthRamp(false,true);assertEquals(1_000_000_000L,off.supportRequestInterval(1_000_000_000L,start));
        var toggle=new DhBandwidthRamp(true,true);toggle.supportRequestInterval(1_000_000_000L,start);
        toggle.limit(true,false);assertEquals(1_000_000_000L,toggle.supportRequestInterval(1_000_000_000L,start+1));
    }
    @Test void bothSettingsAreRequiredAndOffOpensImmediatelyWithoutMidSessionRearming() {
        assertEquals(500,new DhBandwidthRamp(false,true).limit(false,true));
        assertEquals(500,new DhBandwidthRamp(true,false).limit(true,false));
        var ramp=new DhBandwidthRamp(true,true);assertEquals(50,ramp.limit(true,true));
        assertEquals(500,ramp.limit(true,false));assertEquals(500,ramp.limit(true,true));
        var another=new DhBandwidthRamp(true,true);assertEquals(500,another.limit(false,true));
    }
    @Test void saturatedTrafficRampsInDecimalKbAndNeverExceedsTheCeiling() {
        var ramp=new DhBandwidthRamp(true,true);long now=0;
        for(int expected=100;expected<=500;expected+=50) {
            ramp.received((expected-50)*1000,now);now+=1_000_000_000L;ramp.received(1,now);
            assertEquals(expected,ramp.limit(true,true));
        }
        ramp.received(Integer.MAX_VALUE,now+1_000_000_000L);assertEquals(500,ramp.limit(true,true));
    }
    @Test void SparseOrIdleTransfersNeverShrinkOrPretendSaturation() {
        var ramp=new DhBandwidthRamp(true,true);ramp.received(1000,90_000_000_000L);
        ramp.received(1000,91_000_000_000L);assertEquals(50,ramp.limit(true,true));
        ramp.received(1000,900_000_000_000L);assertEquals(50,ramp.limit(true,true));
        ramp.received(0,1_000_000_000_000L);assertEquals(50,ramp.limit(true,true));
    }
    @Test void samplesUseThroughputRatherThanAccumulatedIdleBytes() {
        var ramp=new DhBandwidthRamp(true,true);ramp.received(50_000,0);ramp.received(1,10_000_000_000L);
        assertEquals(50,ramp.limit(true,true));
    }
}
