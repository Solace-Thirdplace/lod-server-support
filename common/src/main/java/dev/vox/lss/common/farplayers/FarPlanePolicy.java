package dev.vox.lss.common.farplayers;

/**
 * Far-plane extension sizing (far-plane plan #364 §4, pure math: no MC types, so the Tier 1
 * suite owns it). Opt-in ({@code far_players.extend_far_plane}): vanilla clips far-player
 * proxies past its far plane, so while the setting is on the plane grows to just past the
 * farthest proxy the renderer would draw.
 *
 * <p>Three steps per frame, all on the render thread:
 * <ol>
 *   <li>the renderer {@link #report}s each eligible proxy's camera distance during its pass
 *       (after its own gates, before the frustum test);</li>
 *   <li>the frame-start hook {@link #publish}es that into the latch, once per frame, so the
 *       far plane never changes inside a frame (Voxy inverts {@code getDepthFar()} mid-frame
 *       on 1.21.x);</li>
 *   <li>the far-plane hook reads {@link #active}.</li>
 * </ol>
 *
 * <p>The latch only grows (review MAJOR: the server adds and drops far players exactly at its
 * ring edge, so any shrink rule flaps the projection). It resets when the setting turns off and
 * when the caller resets it (level or dimension change, disconnect, a shader pack turning on).
 */
public final class FarPlanePolicy {

    /** Growth step, in blocks: the projection changes at most once per step. */
    public static final int STEP = 256;
    /** Room past the farthest proxy for its model and name tag. */
    public static final int MARGIN = 32;
    /** Upper bound: the far-player ring's own ceiling (16384) plus one step. */
    public static final int CEILING = 16384 + STEP;

    private double pendingFarthest;
    private int latch;

    /** Renderer: one eligible proxy at {@code distanceBlocks} from the camera this pass. */
    public void report(double distanceBlocks) {
        if (distanceBlocks > this.pendingFarthest) this.pendingFarthest = distanceBlocks;
    }

    /** Frame start: fold the last pass's farthest proxy into the grow-only latch. */
    public void publish(boolean enabled, int renderCapBlocks) {
        if (!enabled) {
            reset();
            return;
        }
        this.latch = Math.max(this.latch, want(this.pendingFarthest, renderCapBlocks));
        this.pendingFarthest = 0;
    }

    /** Level or dimension change, disconnect, or a shader pack turning on. */
    public void reset() {
        this.latch = 0;
        this.pendingFarthest = 0;
    }

    /** The far plane to use: the latch while it is past vanilla's, otherwise vanilla's. */
    public float active(float vanillaFar) {
        return this.latch > vanillaFar ? this.latch : vanillaFar;
    }

    public int latch() { return this.latch; }

    /** The plane a proxy at {@code farthest} blocks needs, in whole steps; 0 = none.
     *  {@code renderCapBlocks} > 0 caps it ({@code far_players.render_distance_blocks}). */
    public static int want(double farthest, int renderCapBlocks) {
        if (!(farthest > 0)) return 0;
        double need = farthest + MARGIN;
        if (renderCapBlocks > 0) need = Math.min(need, renderCapBlocks + (double) MARGIN);
        need = Math.min(need, CEILING);
        return (int) Math.min(CEILING, Math.ceil(need / STEP) * STEP);
    }
}
