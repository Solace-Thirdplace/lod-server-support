package dev.vox.lss.mixin;

import dev.vox.lss.networking.client.FarPlaneExtension;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Far-plane extension ({@code far_players.extend_far_plane}, far-plane plan #364 §5).
 *
 * <p>LINE-FACT(26.x): {@code Camera.update} runs once per frame and writes
 * {@code depthFar = max(renderDistance × 64, cloudRange × 16)}, then builds the culling frustum
 * and the projection from it in the same method. Injecting right after that field write (not
 * on the {@code Math.max} call, which another mod could redirect) makes this the frame start and
 * the far plane at once, and every later reader (culling, projection,
 * {@code CameraRenderState.depthFar}) sees the same value. {@code FarPlaneHookContractTest} pins
 * the bytecode shape; 1.21.x hooks {@code GameRenderer} instead.
 *
 * <p>{@code require = 0}: an optional visual must never crash the game when another mod rewrites
 * this code; {@link FarPlaneExtension#rendererPass} reports a hook that never runs.
 */
@Mixin(Camera.class)
public class FarPlaneHook {
    @Shadow private float depthFar;

    @Inject(method = "update(Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/Camera;depthFar:F",
                    opcode = org.objectweb.asm.Opcodes.PUTFIELD, shift = At.Shift.AFTER),
            require = 0)
    private void lss$extendFarPlane(DeltaTracker deltaTracker, CallbackInfo ci) {
        FarPlaneExtension.frameStart(Minecraft.getInstance().level);
        this.depthFar = FarPlaneExtension.apply(this.depthFar);
    }
}
