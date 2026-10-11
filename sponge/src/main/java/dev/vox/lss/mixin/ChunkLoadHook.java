package dev.vox.lss.mixin;

import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sponge's chunk-LOAD baseline seam (xaero-scatter-remediation-plan.md WI-1b): the
 * FULL-status promotion lambda inside {@code ChunkStatusTasks.full}, the exact point
 * fabric-api's own {@code CHUNK_LOAD} hooks. Sponge's {@code ChunkEvent.Load} fires only
 * at ENTITY_TICKING and would miss the border ring.
 *
 * <p>The lambda is selected by DESCRIPTOR, not name (sponge-support-plan.md §1.4): its
 * synthetic name differs per Minecraft version ({@code lambda$full$0} on 26.2,
 * {@code lambda$full$2} on 1.21.1) while the descriptor is the same on every line, so
 * this file is identical everywhere. {@code require = 1, allow = 1} is a deliberate
 * deviation from the save hook's soft {@code require = 0}: the plugin is pinned to one
 * Minecraft version, so a mismatch can only be a build-time error, and
 * SpongeMixinTargetContractTest reds on it before any server boots.
 *
 * <p>{@code newlyGenerated} is fabric-api's own derivation, verbatim: a chunk read from
 * disk arrives wrapped in an {@link ImposterProtoChunk}; anything else was just generated
 * (and {@code ServerReceiverGlue.onChunkLoaded} refuses to seed it).
 */
@Mixin(ChunkStatusTasks.class)
public abstract class ChunkLoadHook {

    @Inject(method = "*(Lnet/minecraft/world/level/chunk/ChunkAccess;"
            + "Lnet/minecraft/world/level/chunk/status/WorldGenContext;"
            + "Lnet/minecraft/server/level/GenerationChunkHolder;)"
            + "Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At("RETURN"), require = 1, allow = 1)
    private static void lss$onChunkLoad(ChunkAccess protoChunk, WorldGenContext context,
                                        GenerationChunkHolder holder,
                                        CallbackInfoReturnable<ChunkAccess> cir) {
        dev.vox.lss.networking.server.LSSServerNetworking.onChunkLoad(context.level(),
                cir.getReturnValue(), !(protoChunk instanceof ImposterProtoChunk));
    }
}
