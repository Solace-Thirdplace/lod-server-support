package dev.vox.lssfixture.dh.mixin;
import dev.vox.lssfixture.dh.MeasureProbe;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Movement measurement only (FixtureMixinPlugin): frame-to-frame interval. */
@Mixin(Minecraft.class)
public class MeasureFrameMixin {
    @Inject(method={"runTick","method_1523"},at=@At("HEAD"),remap=false)
    private void frame(boolean render,CallbackInfo ci) { if(render)MeasureProbe.frame(); }
}
