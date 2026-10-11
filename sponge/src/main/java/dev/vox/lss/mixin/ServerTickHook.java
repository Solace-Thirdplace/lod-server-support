package dev.vox.lss.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * The service pump (sponge-support-plan.md §1.3): {@code tickServer} TAIL, the exact
 * point of fabric-api's {@code END_SERVER_TICK} (and NeoForge's
 * {@code ServerTickEvent.Post}). Sponge's synchronous scheduler runs at {@code tickServer}
 * HEAD — before the tick's world work — so a scheduler task would pump the service at a
 * different point in the tick than every other loader.
 */
@Mixin(MinecraftServer.class)
public abstract class ServerTickHook {

    @Inject(method = "tickServer", at = @At("TAIL"))
    private void lss$onServerTickEnd(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        dev.vox.lss.networking.server.LSSServerNetworking.onServerTick();
    }
}
