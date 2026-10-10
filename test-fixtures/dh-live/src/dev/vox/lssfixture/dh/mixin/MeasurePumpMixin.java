package dev.vox.lssfixture.dh.mixin;
import dev.vox.lssfixture.dh.MeasureProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Movement measurement only (FixtureMixinPlugin): wall time of each vanilla-border pump call. */
@Mixin(targets="dev.vox.lss.compat.VoxyVanillaCoverage",remap=false)
public class MeasurePumpMixin {
    @Inject(method={"clientTick()V","afterRender()V"},at=@At("HEAD"),remap=false)
    private void start(CallbackInfo ci) { MeasureProbe.pumpStart(); }
    @Inject(method={"clientTick()V","afterRender()V"},at=@At("RETURN"),remap=false)
    private void end(CallbackInfo ci) { MeasureProbe.pumpEnd(); }
}
