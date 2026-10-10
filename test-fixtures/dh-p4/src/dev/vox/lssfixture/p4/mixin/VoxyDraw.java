package dev.vox.lssfixture.p4.mixin;
import dev.vox.lssfixture.p4.VoxyDrawStats;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Pseudo @Mixin(targets="me.cortex.voxy.client.core.VoxyRenderSystem",remap=false)
public class VoxyDraw {
 @Inject(method="renderOpaque",at=@At("RETURN"),require=0,remap=false)private void rendered(CallbackInfo ci){VoxyDrawStats.rendered(this);}
}
