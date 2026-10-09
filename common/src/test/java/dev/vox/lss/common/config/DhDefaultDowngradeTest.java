package dev.vox.lss.common.config;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DhDefaultDowngradeTest {
    @Test void defaultsAndOrdinarySavesNeverEmitUnknownDhPathsOnEitherBrand() {
        for(String brand:new String[]{"lss","vss"}) {
            var codec=new YamlSettingsCodec<>(SettingsSchema.client(),brand);
            var defaults=codec.parse(codec.defaults());assertNoDhPaths(defaults.bytes());
            var changed=codec.edit(defaults,Map.of("lod.distance_chunks",128,"lod.source","lss","lod.dh.sync_on_load",true,"lod.dh.real_time_updates",true));
            assertNoDhPaths(changed);assertTrue(codec.parse(changed).normalized().lod().receive());
            var opted=codec.edit(defaults,Map.of("lod.source","distant_horizons","lod.dh.real_time_updates",false));
            assertTrue(new String(opted,java.nio.charset.StandardCharsets.UTF_8).contains("distant_horizons"));
            assertNoDhPaths(codec.edit(codec.parse(opted),Map.of("lod.source","lss","lod.dh.real_time_updates",true)));
        }
    }
    private void assertNoDhPaths(byte[] bytes) {
        var lod=(Map<?,?>)YamlSettingsCodec.readTree(bytes).get("lod");
        assertFalse(lod.containsKey("source"));assertFalse(lod.containsKey("dh"));
    }
}
