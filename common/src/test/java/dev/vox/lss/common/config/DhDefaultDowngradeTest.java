package dev.vox.lss.common.config;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** The main client file must stay readable by v0.16.0, which rejects unknown keys —
 *  with one user-approved exception (2026-10-11, map-ingestion plan §2.4): files created or
 *  saved by ≥0.18 carry {@code integrations.map_ingestion}, which ≤0.17 rejects.
 *  Allow DH Server Terrain ({@code lod.source}) defaults to {@code distant_horizons}
 *  (user decision 2026-10-10), so the sidecar records only the opt-out {@code lss}. */
class DhDefaultDowngradeTest {
    @Test void dhServerTerrainIsAllowedByDefault() {
        var schema=SettingsSchema.client();
        assertEquals("distant_horizons",schema.defaults().lod().source());
        assertEquals("distant_horizons",schema.defaultValues().get("lod.source"));
        assertEquals("distant_horizons",schema.descriptorsByPath().get("lod.source").defaultValue());
        for(String brand:new String[]{"lss","vss"}) {
            var codec=new YamlSettingsCodec<>(SettingsSchema.client(),brand);
            var fresh=codec.parse(codec.defaults());
            assertNull(fresh.sidecar());
            assertEquals("distant_horizons",fresh.configured().lod().source());
            assertEquals("distant_horizons",fresh.normalized().lod().source());
            var untouched=codec.editSplit(fresh,Map.of("lod.distance_chunks",96));
            assertNull(untouched.sidecar(),"an unrelated save writes no sidecar");
            assertEquals("distant_horizons",codec.parse(untouched.main()).configured().lod().source());
        }
    }
    @Test void mainFileNeverCarriesExperimentalPathsOnEitherBrand() {
        for(String brand:new String[]{"lss","vss"}) {
            var codec=new YamlSettingsCodec<>(SettingsSchema.client(),brand);
            var defaults=codec.parse(codec.defaults());assertNoExperimentalPaths(defaults.bytes());
            var plain=codec.editSplit(defaults,Map.of("lod.distance_chunks",128,"lod.source","distant_horizons","lod.dh.sync_on_load",true,"lod.dh.real_time_updates",true));
            assertNoExperimentalPaths(plain.main());assertNull(plain.sidecar());
            assertTrue(codec.parse(plain.main()).normalized().lod().receive());
            var opted=codec.editSplit(defaults,Map.of("lod.source","lss","lod.dh.real_time_updates",false,"lod.voxy_border_fill",false));
            assertNoExperimentalPaths(opted.main());
            String sidecar=new String(opted.sidecar(),StandardCharsets.UTF_8);
            assertEquals("lss",SettingsSchema.lookup(YamlSettingsCodec.readTree(opted.sidecar()),"lod.source"));assertFalse(sidecar.contains("distant_horizons"));assertFalse(sidecar.contains("sync_on_load"));
            var reread=codec.parse(opted.main(),opted.sidecar()).configured();
            assertEquals("lss",reread.lod().source());assertFalse(reread.lod().dh().realTimeUpdates());
            assertFalse(reread.lod().voxyBorderFill());
            assertEquals("distant_horizons",codec.parse(opted.main()).configured().lod().source(),"a downgrade reading main alone sees the default");
            var back=codec.editSplit(codec.parse(opted.main(),opted.sidecar()),
                    Map.of("lod.source","distant_horizons","lod.dh.real_time_updates",true,"lod.voxy_border_fill",true));
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
        assertTrue(new String(moved.sidecar(),StandardCharsets.UTF_8).contains("distant_horizons"));
        var settled=codec.editSplit(codec.parse(moved.main(),moved.sidecar()),Map.of("lod.dh.sync_on_load",true));
        assertNull(settled.sidecar());
        assertEquals("distant_horizons",codec.parse(settled.main()).configured().lod().source());
        // A development-build opt-out carried in main stays an opt-out in the sidecar.
        var optedOut=codec.parse(new String(codec.defaults(),StandardCharsets.UTF_8).replace("lod:\n","lod:\n  source: lss\n")
                .getBytes(StandardCharsets.UTF_8));
        assertEquals("lss",optedOut.configured().lod().source());
        var kept=codec.editSplit(optedOut,Map.of("lod.receive",false));
        assertNoExperimentalPaths(kept.main());
        assertEquals("lss",codec.parse(kept.main(),kept.sidecar()).configured().lod().source());
        assertNotNull(codec.editSplit(codec.parse(kept.main(),kept.sidecar()),Map.of("lod.receive",true)).sidecar(),"the opt-out is nondefault and persists");
    }
    @Test void aSidecarThatOnlyRepeatsTheNewDefaultIsDroppedOnTheNextSave() {
        // Earlier 0.17.0 development builds wrote "source: distant_horizons" to opt in.
        var codec=new YamlSettingsCodec<>(SettingsSchema.client());
        var document=codec.parse(codec.defaults(),"lod:\n  source: distant_horizons\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("distant_horizons",document.configured().lod().source());
        var saved=codec.editSplit(document,Map.of("lod.distance_chunks",96));
        assertNull(saved.sidecar());assertNoExperimentalPaths(saved.main());
        assertEquals("distant_horizons",codec.parse(saved.main()).configured().lod().source());
    }
    @Test void sidecarIgnoresOrdinaryForeignAndBrokenContentAndAcceptsAnEmptyDocument() {
        // The experimental file can never disable the main settings (a newer build may have written it).
        var codec=new YamlSettingsCodec<>(SettingsSchema.client());
        byte[] main=codec.defaults();
        assertTrue(codec.parse(main,"lod:\n  receive: false\n".getBytes(StandardCharsets.UTF_8)).configured().lod().receive(),"ordinary keys stay in main");
        assertDoesNotThrow(()->codec.parse(main,"config_version: 1\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals("distant_horizons",codec.parse(main,"lod: [1]\n".getBytes(StandardCharsets.UTF_8)).configured().lod().source());
        assertEquals("lss",codec.parse(main,("lod:\n  source: lss\n  dh:\n    refresh_seconds: 30\n    future_key: 1\n"
                ).getBytes(StandardCharsets.UTF_8)).configured().lod().source(),"retired and unknown keys are dropped, known ones kept");
        assertEquals("distant_horizons",codec.parse(main,"lod:\n  source: 7\n".getBytes(StandardCharsets.UTF_8)).configured().lod().source(),"an invalid value falls back");
        var mixed=codec.parse(main,"lod:\n  source: lss\n  receive: false\n".getBytes(StandardCharsets.UTF_8)).configured();
        assertEquals("lss",mixed.lod().source(),"the opt-out survives a stray ordinary key");assertTrue(mixed.lod().receive());
        assertEquals("distant_horizons",codec.parse(main,"# all defaults\n".getBytes(StandardCharsets.UTF_8)).configured().lod().source());
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
