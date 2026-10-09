package dev.vox.lssfixture.p4.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(net.minecraft.client.MouseHandler.class)
public class MouseGuard {
 @Inject(method="grabMouse",at=@At("HEAD"),cancellable=true)
 private void privateDesktopNeverGrabsHostMouse(CallbackInfo ci){if(Boolean.getBoolean("lss.p4.windowsNative"))ci.cancel();}
}
