package dev.vox.lssfixture.dh.mixin;
import dev.vox.lssfixture.dh.Probe;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Observe real traffic even while the production receiver correctly drops it. */
@Mixin(targets="dev.vox.lss.networking.client.DhClientSession",remap=false)
public class CaptureMixin {
    // Match only the owning capture boundary, not its public forwarding overloads.
    @Inject(method={
            "capture(Lnet/minecraft/network/FriendlyByteBuf;JZ)Ldev/vox/lss/networking/client/DhClientSession$Inbound;",
            "capture(Lnet/minecraft/class_2540;JZ)Ldev/vox/lss/networking/client/DhClientSession$Inbound;"
    },at=@At("HEAD"),remap=false)
    private static void observe(CallbackInfoReturnable<?> ci,@Local(argsOnly=true) FriendlyByteBuf bytes) {
        if(bytes.readableBytes()>=4)Probe.transportFrame(bytes.getUnsignedShort(bytes.readerIndex()+2));
    }
}
