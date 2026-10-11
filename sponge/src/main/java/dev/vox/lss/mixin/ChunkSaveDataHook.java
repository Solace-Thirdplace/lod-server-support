package dev.vox.lss.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sponge twin of the fabric {@code ChunkSaveDataHook} (issue #69).
 *
 * <p>1.21.1 line: this MC has NO {@code SerializableChunkData} (1.21.2+), so the
 * retarget lands on {@code ChunkSerializer.write(ServerLevel, ChunkAccess)} RETURN —
 * this line's static serialize-for-saving choke point (see the fabric twin's javadoc
 * for the completed vanilla/Moonrise/C2ME routing verification). Same
 * {@code require = 0} soft-fail (a missing target must degrade dirty DETECTION,
 * never crash the server); the body delegates to this loader's
 * {@code LSSServerNetworking.onChunkSaveData} → the shared {@code ServerReceiverGlue}.
 */
@Mixin(ChunkSerializer.class)
public abstract class ChunkSaveDataHook {

    @Inject(method = "write", at = @At("RETURN"), require = 0)
    private static void lss$onChunkSaveData(ServerLevel level, ChunkAccess chunk,
                                            CallbackInfoReturnable<CompoundTag> cir) {
        dev.vox.lss.networking.server.LSSServerNetworking.onChunkSaveData(level, chunk);
    }
}
