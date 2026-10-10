package dev.vox.lssfixture.dh.mixin;
import dev.vox.lssfixture.dh.Probe;
import dev.vox.lss.common.dh.DhMessage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="dev.vox.lss.networking.client.DhClientSession$Session",remap=false)
public class SendMixin {
    @Inject(method="send",at=@At("HEAD"),remap=false)
    private void observe(DhMessage message,CallbackInfo ci) { Probe.sent(this,message); }
}
