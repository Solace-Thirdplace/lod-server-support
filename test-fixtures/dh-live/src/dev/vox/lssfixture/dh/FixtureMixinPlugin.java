package dev.vox.lssfixture.dh;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
/** Keep the intrusive queue-order counterexample out of every other private test. */
public final class FixtureMixinPlugin implements IMixinConfigPlugin {
    public boolean shouldApplyMixin(String target,String mixin) {
        String scenario=System.getProperty("lss.rig.scenario","");
        if(mixin.contains(".Measure"))return scenario.equals("movement");
        return !mixin.endsWith("VoxyIngestRaceMixin")||scenario.equals("race");
    }
    public void onLoad(String mixinPackage) {}
    public String getRefMapperConfig() {return null;}
    public void acceptTargets(Set<String> mine,Set<String> others) {}
    public List<String> getMixins() {return null;}
    public void preApply(String target,ClassNode node,String mixin,IMixinInfo info) {}
    public void postApply(String target,ClassNode node,String mixin,IMixinInfo info) {}
}
