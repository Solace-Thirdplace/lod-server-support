package dev.vox.lssfixture.dh.mixin;

import dev.vox.lssfixture.dh.Probe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Private visual control only: hide Voxy geometry while retaining vanilla and storage. */
@Mixin(targets="me.cortex.voxy.client.core.VoxyRenderSystem",remap=false)
public abstract class VoxyRenderMixin {
    @Inject(method={"renderOpaque","renderTranslucent"},at=@At("HEAD"),cancellable=true,remap=false)
    private void fixture$hideGeometry(CallbackInfo ci) {
        if(Probe.suppressRender) ci.cancel();
    }
}
