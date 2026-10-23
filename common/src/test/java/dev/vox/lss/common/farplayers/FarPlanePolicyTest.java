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
        policy.publish(false);
        assertEquals(VANILLA, policy.active(VANILLA));
        policy.publish(true);
        assertEquals(VANILLA, policy.active(VANILLA), "an off frame drops the report, it is not carried");
    }

    @Test
    void aProxyInsideTheVanillaPlaneChangesNothing() {
        var policy = new FarPlanePolicy();
        policy.report(300);
        policy.publish(true);
        assertEquals(VANILLA, policy.active(VANILLA));
    }

    @Test
    void growsToTheNextStepPastTheFarthestProxy() {
        var policy = new FarPlanePolicy();
        policy.report(900);
        policy.report(1500);
        policy.publish(true);
        assertEquals(1536f, policy.active(VANILLA), "1500 + 32 rounds up to 6 steps");
        policy.report(1504);
        policy.publish(true);
        assertEquals(1536f, policy.active(VANILLA), "exactly on a step stays there");
        policy.report(1505);
        policy.publish(true);
        assertEquals(1792f, policy.active(VANILLA));
    }

    @Test
    void onlyGrows() {
        var policy = new FarPlanePolicy();
        policy.report(3000);
        policy.publish(true);
        assertEquals(3072f, policy.active(VANILLA));
        policy.report(1000);
        policy.publish(true);
        policy.publish(true); // a pass with nobody eligible
        assertEquals(3072f, policy.active(VANILLA), "a nearer or vanished far player never shrinks it");
    }

    @Test
    void theCeilingBoundsIt() {
        assertEquals(FarPlanePolicy.CEILING, FarPlanePolicy.want(1e9));
        assertEquals(FarPlanePolicy.CEILING, FarPlanePolicy.want(16384));
        assertEquals(0, FarPlanePolicy.want(0));
        assertEquals(0, FarPlanePolicy.want(Double.NaN));
    }

    @Test
    void resetDropsBackToVanilla() {
        var policy = new FarPlanePolicy();
        policy.report(3000);
        policy.publish(true);
        policy.reset();
        assertEquals(VANILLA, policy.active(VANILLA));
        assertEquals(0, policy.latch());
    }

    @Test
    void turningTheSettingOffResetsTheLatch() {
        var policy = new FarPlanePolicy();
        policy.report(3000);
        policy.publish(true);
        policy.publish(false);
        policy.publish(true);
        assertEquals(VANILLA, policy.active(VANILLA));
    }

    @Test
    void neverBelowVanilla() {
        var policy = new FarPlanePolicy();
        policy.report(600);
        policy.publish(true);
        assertEquals(768f, policy.active(VANILLA));
        assertEquals(2048f, policy.active(2048f), "a larger vanilla plane (Cloud Distance) wins");
    }
}
