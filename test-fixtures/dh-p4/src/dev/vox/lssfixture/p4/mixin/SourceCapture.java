package dev.vox.lssfixture.p4.mixin;
import dev.vox.lssfixture.p4.ContentOracle;
import dev.vox.lss.common.dh.DhTerrainTile;
import dev.vox.lss.api.VoxelColumnData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(targets="dev.vox.lss.networking.client.DhColumnConverter",remap=false)
public class SourceCapture {
 @Inject(method="convert(Ldev/vox/lss/common/dh/DhTerrainTile;ILjava/util/List;)Ldev/vox/lss/api/VoxelColumnData;",at=@At("RETURN"),remap=false)
 private void source(DhTerrainTile tile,int column,java.util.List<?> materials,CallbackInfoReturnable<VoxelColumnData> ci){if(ci.getReturnValue()!=null)ContentOracle.capture(tile,column);}
}
