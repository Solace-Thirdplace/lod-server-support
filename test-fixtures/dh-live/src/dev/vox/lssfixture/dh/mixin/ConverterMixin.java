package dev.vox.lssfixture.dh.mixin;
import dev.vox.lssfixture.dh.Probe;
import dev.vox.lss.common.dh.DhTerrainTile;
import dev.vox.lss.api.VoxelColumnData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(targets="dev.vox.lss.networking.client.DhColumnConverter",remap=false)
public class ConverterMixin {
    // The private per-column overload is what production convertTile calls; a bare
    // "convert" selects the first match in class order (the unused public overload).
    @Inject(method="convert(Ldev/vox/lss/common/dh/DhTerrainTile;ILjava/util/List;)Ldev/vox/lss/api/VoxelColumnData;",at=@At("HEAD"),remap=false)
    private void timingStart(DhTerrainTile tile,int column,java.util.List<?> materials,CallbackInfoReturnable<VoxelColumnData> ci) {
        Probe.conversionStarted();
    }
    @Inject(method="convert(Ldev/vox/lss/common/dh/DhTerrainTile;ILjava/util/List;)Ldev/vox/lss/api/VoxelColumnData;",at=@At("RETURN"),remap=false)
    private void observe(DhTerrainTile tile,int column,java.util.List<?> materials,CallbackInfoReturnable<VoxelColumnData> ci) {
        Probe.conversionFinished();
        if(ci.getReturnValue()!=null) Probe.converted(tile,column,ci.getReturnValue());
    }
}
