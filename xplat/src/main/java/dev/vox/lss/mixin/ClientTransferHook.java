package dev.vox.lss.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A transferred connection retains the original hub's ServerData on 1.21.1. */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientTransferHook {
    @Shadow @Final protected net.minecraft.client.multiplayer.ServerData serverData;
    @Inject(method="handleTransfer",at=@At("HEAD"))
    private void lss$rememberTransfer(net.minecraft.network.protocol.common.ClientboundTransferPacket packet,CallbackInfo ci) {
        if(Minecraft.getInstance().isSameThread())dev.vox.lss.networking.client.DhClientSession.transferred(serverData);
    }
}
