package dev.vox.lss.common.config;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** The main client file must stay readable by v0.16.0, which rejects unknown keys. */
class DhDefaultDowngradeTest {
    @Test void mainFileNeverCarriesExperimentalPathsOnEitherBrand() {
        for(String brand:new String[]{"lss","vss"}) {
            var codec=new YamlSettingsCodec<>(SettingsSchema.client(),brand);
            var defaults=codec.parse(codec.defaults());assertNoExperimentalPaths(defaults.bytes());
            var plain=codec.editSplit(defaults,Map.of("lod.distance_chunks",128,"lod.source","lss","lod.dh.sync_on_load",true,"lod.dh.real_time_updates",true));
            assertNoExperimentalPaths(plain.main());assertNull(plain.sidecar());
            assertTrue(codec.parse(plain.main()).normalized().lod().receive());
            var opted=codec.editSplit(defaults,Map.of("lod.source","distant_horizons","lod.dh.real_time_updates",false,"lod.voxy_border_fill",false));
            assertNoExperimentalPaths(opted.main());
            String sidecar=new String(opted.sidecar(),StandardCharsets.UTF_8);
            assertTrue(sidecar.contains("distant_horizons"));assertFalse(sidecar.contains("sync_on_load"));
            var reread=codec.parse(opted.main(),opted.sidecar()).configured();
            assertEquals("distant_horizons",reread.lod().source());assertFalse(reread.lod().dh().realTimeUpdates());
            assertFalse(reread.lod().voxyBorderFill());
            var back=codec.editSplit(codec.parse(opted.main(),opted.sidecar()),
                    Map.of("lod.source","lss","lod.dh.real_time_updates",true,"lod.voxy_border_fill",true));
            assertNoExperimentalPaths(back.main());assertNull(back.sidecar());
        }
    }
    @Test void developmentBuildKeysInMainAreHonouredThenMovedAndRetiredKeysDropped() {
        var codec=new YamlSettingsCodec<>(SettingsSchema.client());
        String dev=new String(codec.defaults(),StandardCharsets.UTF_8).replace("lod:\n",
                "lod:\n  source: distant_horizons\n  dh:\n    sync_on_load: false\n    refresh_seconds: 60\n");
        var document=codec.parse(dev.getBytes(StandardCharsets.UTF_8));
        assertEquals("distant_horizons",document.configured().lod().source());
        assertFalse(document.configured().lod().dh().syncOnLoad());
        var moved=codec.editSplit(document,Map.of("lod.receive",false));
        assertNoExperimentalPaths(moved.main());
        assertFalse(new String(moved.main(),StandardCharsets.UTF_8).contains("refresh_seconds"));
        var after=codec.parse(moved.main(),moved.sidecar()).configured();
        assertEquals("distant_horizons",after.lod().source());assertFalse(after.lod().dh().syncOnLoad());assertFalse(after.lod().receive());
        // An unchanged default carried from main is written explicitly once, then dropped.
        var settled=codec.editSplit(codec.parse(moved.main(),moved.sidecar()),Map.of("lod.source","lss","lod.dh.sync_on_load",true));
        assertNull(settled.sidecar());
    }
    @Test void sidecarRejectsOrdinaryKeysAndAcceptsAnEmptyDocument() {
        var codec=new YamlSettingsCodec<>(SettingsSchema.client());
        byte[] main=codec.defaults();
        assertThrows(SettingsException.class,()->codec.parse(main,"lod:\n  receive: false\n".getBytes(StandardCharsets.UTF_8)));
        assertThrows(SettingsException.class,()->codec.parse(main,"config_version: 1\n".getBytes(StandardCharsets.UTF_8)));
        assertThrows(SettingsException.class,()->codec.parse(main,"lod: [1]\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals("lss",codec.parse(main,"# all defaults\n".getBytes(StandardCharsets.UTF_8)).configured().lod().source());
        assertNotEquals(codec.parse(main).hash(),codec.parse(main,"# all defaults\n".getBytes(StandardCharsets.UTF_8)).hash());
    }
    @Test void templateCarriesNoBrandTokens() {
        String template=SettingsSchema.client().template();
        assertFalse(template.matches("(?s).*\\b(LSS|VSS)\\b.*"),"client.yaml must stay brand-neutral; VSS ships it unchanged");
    }
    private void assertNoExperimentalPaths(byte[] bytes) {
        var tree=YamlSettingsCodec.readTree(bytes);
        for(String path:YamlSettingsCodec.SIDECAR_PATHS)assertNull(SettingsSchema.lookup(tree,path),path);
        for(String path:YamlSettingsCodec.RETIRED_PATHS)assertNull(SettingsSchema.lookup(tree,path),path);
        var lod=(Map<?,?>)tree.get("lod");
        assertFalse(lod.containsKey("source"));assertFalse(lod.containsKey("dh"));assertFalse(lod.containsKey("voxy_border_fill"));
    }
}
