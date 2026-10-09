package dev.vox.lssfixture.p4.mixin;
import dev.vox.lss.common.dh.*;
import dev.vox.lssfixture.p4.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="dev.vox.lss.networking.client.DhClientSession$Session",remap=false)
public class BenchmarkArea {
 @Redirect(method="requestNext",at=@At(value="INVOKE",target="Ldev/vox/lss/common/dh/DhSessionPolicy;inBorder(Ldev/vox/lss/common/dh/DhProfile;Ldev/vox/lss/common/dh/DhPosition;III)Z"),remap=false)
 private boolean fixedArea(DhProfile profile,DhPosition pos,int x,int z,int radius){return (!(P4Probe.mode().equals("benchmark") || P4Probe.mode().equals("configuration")) || BenchmarkClient.TARGETS.contains(pos)) && DhSessionPolicy.inBorder(profile,pos,x,z,radius);}
 @Inject(method="send",at=@At("HEAD"),remap=false) private void sent(DhMessage msg,CallbackInfo ci){BenchmarkClient.sent(msg);}
}
