package dev.vox.lssfixture.p4.mixin;
import dev.vox.lssfixture.p4.P4Probe;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
public class ClientTick {
 @Inject(method={"tick","method_1574"},at=@At("RETURN"),remap=false)
 private void tick(CallbackInfo ci){P4Probe.tick();}
}
