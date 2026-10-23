package dev.vox.lss.mixin;

import dev.vox.lss.networking.client.FarPlaneExtension;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Far-plane extension ({@code far_players.extend_far_plane}, far-plane plan #364 §5).
 *
 * <p>LINE-FACT(1.21.x): the far plane is {@code GameRenderer.getDepthFar()}, read by the
 * projection, the culling projection and the world border, several times a frame (Voxy also
 * calls it mid-frame to invert the projection). So the value is published once per frame at
 * {@code renderLevel} HEAD and the getter only reads it: it never changes inside a frame.
 * {@code NeoForgeFarPlaneHookContractTest} pins both targets (the Fabric twin is identical).
 *
 * <p>{@code require = 0}: an optional visual must never crash the game when another mod rewrites
 * this code; {@link FarPlaneExtension#rendererPass} reports a hook that never runs.
 */
@Mixin(GameRenderer.class)
public class FarPlaneHook {
    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"), require = 0)
    private void lss$farPlaneFrameStart(DeltaTracker deltaTracker, CallbackInfo ci) {
        FarPlaneExtension.frameStart(Minecraft.getInstance().level);
    }

    @Inject(method = "getDepthFar()F", at = @At("RETURN"), cancellable = true, require = 0)
    private void lss$extendFarPlane(CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(FarPlaneExtension.apply(cir.getReturnValueF()));
    }
}
