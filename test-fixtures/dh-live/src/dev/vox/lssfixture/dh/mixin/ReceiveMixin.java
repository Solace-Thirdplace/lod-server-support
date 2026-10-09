package dev.vox.lssfixture.dh.mixin;
import dev.vox.lssfixture.dh.Probe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
@Mixin(targets="dev.vox.lss.networking.client.DhClientSession$Session",remap=false)
public class ReceiveMixin {
    private static final AtomicInteger sequence=new AtomicInteger();
    @Inject(method="receive",at=@At("HEAD"),remap=false)
    private void capture(byte[] bytes,long arrived,CallbackInfo ci) throws Exception {
        Probe.received(bytes);
        if(java.util.Set.of("content","rejoin").contains(System.getProperty("lss.rig.scenario",""))) return;
        int id=sequence.incrementAndGet();
        if(id<=64) Files.write(Path.of(System.getProperty("lss.rig.captureDir"),String.format("received-%04d.bin",id)),bytes);
    }
}
