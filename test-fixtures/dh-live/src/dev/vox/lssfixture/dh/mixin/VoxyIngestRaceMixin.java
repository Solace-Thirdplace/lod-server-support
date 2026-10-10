package dev.vox.lssfixture.dh.mixin;
import dev.vox.lssfixture.dh.RaceProbe;
import java.util.concurrent.ConcurrentLinkedDeque;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.DataLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Test-only scheduling barrier around the exact installed port's real writes. */
@Mixin(targets="me.cortex.voxy.common.world.service.VoxelIngestService",remap=false)
public class VoxyIngestRaceMixin {
    @Inject(method="rawIngest0",at=@At("HEAD"),remap=false)
    private void capture(@Coerce Object world,LevelChunkSection section,int x,int y,int z,
                         DataLayer block,DataLayer sky,CallbackInfoReturnable<Boolean> result) {
        RaceProbe.enqueued(section,x,y,z);
    }
    @Redirect(method="processJob",at=@At(value="INVOKE",target="Ljava/util/concurrent/ConcurrentLinkedDeque;pop()Ljava/lang/Object;"),remap=false)
    private Object identify(ConcurrentLinkedDeque<?> queue) throws Exception {
        Object job=queue.pop();RaceProbe.beginJob(job);return job;
    }
    @Redirect(method="processJob",at=@At(value="INVOKE",target="Lme/cortex/voxy/common/world/WorldUpdater;insertUpdate(Lme/cortex/voxy/common/world/WorldEngine;Lme/cortex/voxy/common/voxelization/VoxelizedSection;)V"),remap=false)
    private void commit(@Coerce Object world,@Coerce Object section) throws Exception {
        RaceProbe.insert(world,section);
    }
}
