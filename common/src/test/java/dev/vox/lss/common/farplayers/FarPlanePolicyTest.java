package dev.vox.lss.common.farplayers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Far-plane plan #364 §4/§7: the grow-only, once-per-frame sizing. */
class FarPlanePolicyTest {

    private static final float VANILLA = 512f;

    @Test
    void offAndNobodyTrackedStayVanilla() {
        var policy = new FarPlanePolicy();
        policy.report(3000);
        policy.publish(false, 0);
        assertEquals(VANILLA, policy.active(VANILLA));
        policy.publish(true, 0);
        assertEquals(VANILLA, policy.active(VANILLA), "nothing reported since the reset");
    }

    @Test
    void aProxyInsideTheVanillaPlaneChangesNothing() {
        var policy = new FarPlanePolicy();
        policy.report(300);
        policy.publish(true, 0);
        assertEquals(VANILLA, policy.active(VANILLA));
    }

    @Test
    void growsToTheNextStepPastTheFarthestProxy() {
        var policy = new FarPlanePolicy();
        policy.report(900);
        policy.report(1500);
        policy.publish(true, 0);
        assertEquals(1536f, policy.active(VANILLA), "1500 + 32 rounds up to 6 steps");
        policy.report(1504);
        policy.publish(true, 0);
        assertEquals(1536f, policy.active(VANILLA), "exactly on a step stays there");
        policy.report(1505);
        policy.publish(true, 0);
        assertEquals(1792f, policy.active(VANILLA));
    }

    @Test
    void onlyGrows() {
        var policy = new FarPlanePolicy();
        policy.report(3000);
        policy.publish(true, 0);
        assertEquals(3072f, policy.active(VANILLA));
        policy.report(1000);
        policy.publish(true, 0);
        policy.publish(true, 0); // a pass with nobody eligible
        assertEquals(3072f, policy.active(VANILLA), "a nearer or vanished far player never shrinks it");
    }

    @Test
    void theRenderCapBoundsIt() {
        assertEquals(1280, FarPlanePolicy.want(5000, 1200), "1200 + 32 rounds up to 5 steps");
        assertEquals(768, FarPlanePolicy.want(700, 1200), "a nearer proxy is not raised to the cap");
        assertEquals(FarPlanePolicy.CEILING, FarPlanePolicy.want(1e9, 0));
        assertEquals(0, FarPlanePolicy.want(0, 0));
        assertEquals(0, FarPlanePolicy.want(Double.NaN, 0));
    }

    @Test
    void resetDropsBackToVanilla() {
        var policy = new FarPlanePolicy();
        policy.report(3000);
        policy.publish(true, 0);
        policy.reset();
        assertEquals(VANILLA, policy.active(VANILLA));
        assertEquals(0, policy.latch());
    }

    @Test
    void turningTheSettingOffResetsTheLatch() {
        var policy = new FarPlanePolicy();
        policy.report(3000);
        policy.publish(true, 0);
        policy.publish(false, 0);
        policy.publish(true, 0);
        assertEquals(VANILLA, policy.active(VANILLA));
    }

    @Test
    void neverBelowVanilla() {
        var policy = new FarPlanePolicy();
        policy.report(600);
        policy.publish(true, 0);
        assertEquals(768f, policy.active(VANILLA));
        assertEquals(2048f, policy.active(2048f), "a larger vanilla plane (Cloud Distance) wins");
    }

    @Test
    void pendingIsPerPass() {
        var policy = new FarPlanePolicy();
        policy.report(3000);
        policy.publish(false, 0); // off: the report is dropped, not carried
        policy.publish(true, 0);
        assertEquals(VANILLA, policy.active(VANILLA));
    }
}
