package dev.vox.lssfixture.dh.mixin;
import dev.vox.lssfixture.dh.Probe;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
public class TickMixin {
    @Inject(method={"tick","method_1574"},at=@At("RETURN"),remap=false)
    private void observe(CallbackInfo ci) { Probe.tick(); }
}
