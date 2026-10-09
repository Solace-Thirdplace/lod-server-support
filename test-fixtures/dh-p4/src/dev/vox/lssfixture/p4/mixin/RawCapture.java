package dev.vox.lssfixture.p4.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
/** Observe bytes without parsing under an assumed protocol or changing production behavior. */
@Mixin(targets="dev.vox.lss.networking.client.DhClientSession$Session",remap=false)
public class RawCapture {
 private static final AtomicInteger SEQUENCE=new AtomicInteger();
 @Inject(method="receive",at=@At("HEAD"),remap=false)
 private void capture(byte[] bytes,long arrived,long lossEpoch,CallbackInfo ci)throws Exception {
  String root=System.getProperty("lss.rig.captureDir");if(root!=null)Files.write(Path.of(root,String.format("received-%04d.bin",SEQUENCE.incrementAndGet())),bytes);
 }
}
