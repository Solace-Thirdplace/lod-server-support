package dev.vox.lssfixture.dh.mixin;
import dev.vox.lssfixture.dh.MeasureProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Movement measurement only (FixtureMixinPlugin): wall time of each DH client tick. */
@Mixin(targets="dev.vox.lss.networking.client.DhClientSession",remap=false)
public class MeasureTimingMixin {
    @Inject(method="tick()V",at=@At("HEAD"),remap=false)
    private static void start(CallbackInfo ci) { MeasureProbe.dhTickStart(); }
    @Inject(method="tick()V",at=@At("RETURN"),remap=false)
    private static void end(CallbackInfo ci) { MeasureProbe.dhTickEnd(); }
}
