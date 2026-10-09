package dev.vox.lssfixture.p4.mixin;
import dev.vox.lss.common.dh.*;
import dev.vox.lssfixture.p4.ConfigurationProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=DhTerrainDecoder.class,remap=false)
public class DecodedSource {
 @Inject(method="decode",at=@At("RETURN"),remap=false)
 private void decoded(CallbackInfoReturnable<DhTerrainTile> ci){ConfigurationProbe.decoded(ci.getReturnValue());}
}
