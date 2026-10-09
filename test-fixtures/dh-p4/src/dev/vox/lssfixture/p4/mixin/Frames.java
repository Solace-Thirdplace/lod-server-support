package dev.vox.lssfixture.p4.mixin;
import dev.vox.lssfixture.p4.FrameMetrics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(net.minecraft.client.Minecraft.class)
public class Frames {
 @Inject(method="runTick",at=@At("HEAD")) private void start(boolean render,CallbackInfo ci){if(render)FrameMetrics.begin();}
 @Inject(method="runTick",at=@At("RETURN")) private void end(boolean render,CallbackInfo ci){if(render)FrameMetrics.end();}
}
