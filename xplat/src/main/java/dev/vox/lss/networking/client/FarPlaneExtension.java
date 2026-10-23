package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.farplayers.FarPlanePolicy;
import dev.vox.lss.config.LSSClientConfig;

import java.lang.reflect.Method;

/**
 * The client side of {@code far_players.extend_far_plane} (far-plane plan #364 §4-§6). Shared by
 * every loader's hooks and far-player renderer; everything runs on the render thread.
 *
 * <ul>
 *   <li>{@link #frameStart} — once per frame, before the far plane is read: the 26.x
 *       {@code Camera.update} hook, the 1.21.x {@code GameRenderer.renderLevel} HEAD hook;</li>
 *   <li>{@link #apply} — the far-plane hook: {@code max(vanilla, active)};</li>
 *   <li>{@link #report} / {@link #rendererPass} — the far-player renderer, per pass.</li>
 * </ul>
 *
 * <p>The hooks are {@code require = 0}: another mod rewriting the same code must not crash the
 * game for an optional visual. {@link #rendererPass} turns a missing or overridden hook into one
 * WARN per session.
 */
public final class FarPlaneExtension {
    private FarPlaneExtension() {}

    private static final long HOOK_STALE_NANOS = 5_000_000_000L;
    private static final long SHADER_RECHECK_NANOS = 1_000_000_000L;

    private static final FarPlanePolicy POLICY = new FarPlanePolicy();
    private static Object level;
    private static float vanillaFar;
    private static float activeFar;
    private static long lastApplyNanos;
    private static boolean shaderPack;
    private static long shaderCheckedNanos;
    private static boolean shaderProbeFailed;
    private static Method irisInstance, irisInUse;
    private static boolean warnedStale, warnedOverride;
    /** This frame: a shader pack keeps the extension off. */
    private static boolean shaderOff;
    /** Sticky until the level changes: "hook" (never ran) or "override" (another mod won). */
    private static String conflict;

    /** Frame start. {@code currentLevel} is the client level (identity only; null = none). */
    public static void frameStart(Object currentLevel) {
        boolean enabled = LSSClientConfig.CONFIG.farPlayersExtendFarPlane();
        if (currentLevel != level) {
            // A new ClientLevel per dimension and per connection: both reset the latch.
            level = currentLevel;
            POLICY.reset();
            warnedStale = warnedOverride = false;
            conflict = null;
        }
        boolean shaders = enabled && shaderPackInUse();
        if (shaders) POLICY.reset();
        POLICY.publish(enabled && !shaders && currentLevel != null,
                LSSClientConfig.CONFIG.snapshot().farPlayers().renderDistanceBlocks());
        shaderOff = shaders;
    }

    /** The far-plane hook: the value to use given vanilla's {@code vanilla}. */
    public static float apply(float vanilla) {
        lastApplyNanos = System.nanoTime();
        vanillaFar = vanilla;
        activeFar = level == null ? vanilla : POLICY.active(vanilla);
        return activeFar;
    }

    /** Renderer: an eligible proxy (past its gates, before the frustum test). */
    public static void report(double distanceBlocks) {
        POLICY.report(distanceBlocks);
    }

    /**
     * Renderer, once per pass: {@code planeInUse} is the far plane the frame actually used
     * (26.x {@code cameraState.depthFar}, 1.21.x {@code getDepthFar()}).
     */
    public static void rendererPass(float planeInUse) {
        if (!LSSClientConfig.CONFIG.farPlayersExtendFarPlane() || shaderOff) return;
        long now = System.nanoTime();
        if (lastApplyNanos == 0 || now - lastApplyNanos > HOOK_STALE_NANOS) {
            conflict = "hook";
            if (!warnedStale) {
                warnedStale = true;
                LSSLogger.warn("Far plane extension is unavailable: another mod changes the same code.");
            }
        } else if (planeInUse + 0.5f < activeFar) {
            conflict = "override";
            if (!warnedOverride) {
                warnedOverride = true;
                LSSLogger.warn("Another mod overrides the far plane, so far players past "
                        + Math.round(planeInUse) + " blocks are not drawn.");
            }
        }
    }

    /** Gametest probes: whether the far-plane hook has run, and the last values it saw/returned. */
    public static boolean hookRan() { return lastApplyNanos != 0; }
    public static float lastVanillaFar() { return vanillaFar; }
    public static float activeFar() { return activeFar; }

    /** The {@code /lss diag} fragment for the far-player line; null while the setting is off. */
    public static String diagFragment() {
        if (!LSSClientConfig.CONFIG.farPlayersExtendFarPlane()) return null;
        String line = "far_plane=" + Math.round(activeFar) + " (vanilla " + Math.round(vanillaFar) + ")";
        String off = shaderOff ? "shaders" : conflict;
        return off == null ? line : line + ", far_plane_off=" + off;
    }

    /** Iris reports a shader pack in use (reflection; re-checked at most once a second). */
    private static boolean shaderPackInUse() {
        long now = System.nanoTime();
        if (shaderCheckedNanos != 0 && now - shaderCheckedNanos < SHADER_RECHECK_NANOS) return shaderPack;
        shaderCheckedNanos = now;
        if (shaderProbeFailed) return shaderPack = false;
        try {
            if (irisInUse == null) {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi", false,
                        FarPlaneExtension.class.getClassLoader());
                irisInstance = api.getMethod("getInstance");
                irisInUse = api.getMethod("isShaderPackInUse");
            }
            return shaderPack = (boolean) irisInUse.invoke(irisInstance.invoke(null));
        } catch (ClassNotFoundException e) {
            shaderProbeFailed = true; // no Iris
            return shaderPack = false;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            shaderProbeFailed = true;
            LSSLogger.warn("Could not ask Iris whether a shader pack is on; treating it as off.", e);
            return shaderPack = false;
        }
    }
}
