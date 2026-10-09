package dev.vox.lss.common.config;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class YamlSettingsCodecTest {
    private final SettingsSchema<ServerSettings> schema=SettingsSchema.server(false);
    private final YamlSettingsCodec<ServerSettings> codec=new YamlSettingsCodec<>(schema);
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    @Test void numericDraftsEmitImplicitSchemaTypesAndKeepUnclampedRequestedValues() {
        var codec=new YamlSettingsCodec<>(SettingsSchema.server(false));
        var original=codec.parse(codec.defaults());
        for(var value:List.of(new java.math.BigDecimal("25"),new java.math.BigDecimal("0"),new java.math.BigDecimal("1E+2"),new java.math.BigDecimal("0.001"))) {
            byte[] edited=codec.edit(original,Map.of("network.bandwidth.global_mib_per_second",value));
            assertFalse(new String(edited,StandardCharsets.UTF_8).contains("!!"));
            assertEquals(value.doubleValue(),codec.parse(edited).configured().network().bandwidth().globalMibPerSecond());
        }
        var outside=codec.parse(codec.edit(original,Map.of("generation.concurrency.global",999)));
        assertEquals(999,outside.configured().generation().concurrency().global());
        assertEquals(512,outside.normalized().generation().concurrency().global());
    }
    @Test void integerMapValuesRejectFloatingPointTokensLikeScalarIntegers() {
        var codec=new YamlSettingsCodec<>(SettingsSchema.server(true));
        for(String group:List.of("by_dimension","by_world"))for(String value:List.of("12.0","1.2e1",".inf"))
            assertThrows(SettingsException.class,()->codec.parse(("config_version: 1\nlod:\n  distance:\n    "+group+": {minecraft:overworld: "+value+"}\n").getBytes(StandardCharsets.UTF_8)));
        assertEquals(12,codec.parse("config_version: 1\nlod:\n  distance:\n    by_dimension: {minecraft:overworld: 12}\n".getBytes(StandardCharsets.UTF_8)).normalized().lod().distance().byDimension().get("minecraft:overworld"));
    }
    @Test void dottedYamlKeysCannotShadowNestedSettingsOrLoseDraftEdits() {
        var client = new YamlSettingsCodec<>(SettingsSchema.client());
        for (String body : List.of("lod:\n  receive: true\nlod.receive: false\n",
                "lod.receive: false\nlod:\n  receive: true\n", "lod:\n  download.max_columns_per_second: 100\n"))
            assertThrows(SettingsException.class, () -> client.parse(bytes("config_version: 1\n" + body)));
        var map = client.parse(bytes("config_version: 1\ncache:\n  address_aliases: [[play.example.org, backup.example.org]]\n"));
        assertEquals("play.example.org", map.configured().cache().addressAliases().getFirst().getFirst());
        var paper = new YamlSettingsCodec<>(SettingsSchema.server(true));
        assertEquals(42, paper.parse(bytes("config_version: 1\nlod:\n  distance:\n    by_world: {world.with.dots: 42}\n"))
                .configured().lod().distance().byWorld().get("world.with.dots"));
    }
    @Test void approvedDefaultsMatchEveryDescriptorAndPlatform(){
        for(var s:List.of(schema,SettingsSchema.server(true))) {
            var c=new YamlSettingsCodec<>(s);var d=c.parse(c.defaults());
            assertEquals(s.defaults(),d.normalized()); assertTrue(d.normalizations().isEmpty());
            assertEquals(s.descriptors().size(),s.values(d.configured()).size());
            assertEquals(512,d.normalized().lod().distance().defaultChunks());
            assertEquals(64,d.normalized().lod().distance().byDimension().get("minecraft:the_nether"));
            assertEquals(s.isPaper()?300:0,d.normalized().storage().lodStore().resweepIntervalSeconds());
            assertFalse(new String(c.defaults(),StandardCharsets.UTF_8).contains("proposed"));
        }
        var client=SettingsSchema.client();var cc=new YamlSettingsCodec<>(client);
        assertEquals(client.defaults(),cc.parse(cc.defaults()).normalized());
        assertEquals(33,client.descriptors().size());
        assertTrue(client.descriptors().stream().allMatch(d->d.platform()==SettingsSchema.Platform.ALL));
    }
    @Test void retiredDhTimerAndProtocolSelectorLoadWithoutBecomingAnActiveSettingOrWeakeningUnknownKeyChecks() {
        var schema=SettingsSchema.client();var codec=new YamlSettingsCodec<>(schema);
        byte[] old=bytes("config_version: 1\nlod:\n  source: distant_horizons\n  dh:\n    profile: official_16\n    refresh_seconds: 30 # old preference\n");
        var document=codec.parse(old);
        assertTrue(document.configured().lod().dh().syncOnLoad());assertTrue(document.configured().lod().dh().realTimeUpdates());
        assertFalse(schema.descriptorsByPath().containsKey("lod.dh.refresh_seconds"));
        assertFalse(schema.descriptorsByPath().containsKey("lod.dh.profile"));
        // The next write removes retired and experimental keys from the main file (downgrade safety).
        var edited=codec.editSplit(document,Map.of("lod.dh.real_time_updates",false));
        assertFalse(new String(edited.main(),StandardCharsets.UTF_8).contains("refresh_seconds"));
        assertFalse(new String(edited.main(),StandardCharsets.UTF_8).contains("profile: official_16"));
        assertFalse(new String(edited.main(),StandardCharsets.UTF_8).contains("distant_horizons"));
        var reparsed=codec.parse(edited.main(),edited.sidecar()).configured();
        assertFalse(reparsed.lod().dh().realTimeUpdates());assertEquals("distant_horizons",reparsed.lod().source());
        assertThrows(SettingsException.class,()->codec.parse(bytes("config_version: 1\nlod:\n  dh:\n    refresh_secondz: 30\n")));
    }
    @Test void mapsAndNestedAliasListsAreDeeplyImmutable(){
        var groups=new ArrayList<List<String>>();var group=new ArrayList<>(List.of("Example.COM","alt"));groups.add(group);
        var values=new LinkedHashMap<>(SettingsSchema.client().defaultValues());values.put("cache.address_aliases",groups);
        var settings=SettingsSchema.client().fromValues(values).normalized();group.set(0,"changed");groups.clear();
        assertEquals("Example.COM",settings.cache().addressAliases().getFirst().getFirst());
        assertThrows(UnsupportedOperationException.class,()->settings.cache().addressAliases().getFirst().add("bad"));
        assertThrows(UnsupportedOperationException.class,()->schema.defaults().lod().distance().byDimension().clear());
    }
    @Test void missingValuesUseDefaultsButExplicitEmptyCollectionsReplaceThem(){
        var d=codec.parse(bytes("config_version: 1\nlod:\n  distance:\n    by_dimension: {}\nprivacy:\n  xray:\n    hidden_blocks: []\n"));
        assertTrue(d.normalized().lod().distance().byDimension().isEmpty());assertTrue(d.normalized().privacy().xray().hiddenBlocks().isEmpty());
        assertEquals(40,d.normalized().generation().concurrency().global());
    }
    @Test void clampsDoNotRewriteConfiguredValuesOrDocumentBytes(){
        byte[] input=bytes("# 大范围\nconfig_version: 1\ngeneration:\n  concurrency:\n    global: 999\n    per_player: 700\nfar_players:\n  distance:\n    max_blocks: 256\n    min_blocks: 999\n");
        var d=codec.parse(input);
        assertEquals(999,d.configured().generation().concurrency().global());assertEquals(512,d.normalized().generation().concurrency().global());
        assertEquals(512,d.normalized().generation().concurrency().perPlayer());assertEquals(256,d.normalized().farPlayers().distance().minBlocks());
        assertEquals(3,d.normalizations().size());assertArrayEquals(input,d.bytes());
    }
    @Test void explicitSavePreservesUnicodeCommentsOrderUntouchedScalarAndPrivateMaps(){
        var s=SettingsSchema.client();var c=new YamlSettingsCodec<>(s);
        byte[] raw=bytes("# 世界設定\nconfig_version: 1\nlod:\n  receive: true # 接收\n  download:\n    max_columns_per_second: 90000 # preserve high hand value\ncache:\n  address_aliases: [[\"Exact.EXAMPLE\", \"备用\"]] # 私密\n");
        var d=c.parse(raw);byte[] out=c.edit(d,Map.of("lod.receive",false));String text=new String(out,StandardCharsets.UTF_8);
        assertTrue(text.contains("世界設定"));assertTrue(text.contains("接收"));assertTrue(text.contains("私密"));
        assertTrue(text.indexOf("lod:")<text.indexOf("cache:"));assertEquals(90000,c.parse(out).configured().lod().download().maxColumnsPerSecond());
        assertEquals(d.configured().cache(),c.parse(out).configured().cache());
        assertFalse(c.parse(out).configured().lod().receive());assertTrue(d.configured().lod().receive());
    }
    @ParameterizedTest @ValueSource(strings={"", "# only comment\n", "{}", "[]", "config_version: 2", "service: {enabled: true}","config_version: 1.0", "config_version: \"1\"", "config_version: 1\nservice: {enabled: null}", "config_version: 1\nservice: {enabled: \"true\"}", "config_version: 1\nlod: {distance: {default_chunks: \"12\"}}", "config_version: 1\nlod: {distance: {default_chunks: 12.0}}", "config_version: 1\nlod: {distance: {default_chunks: 2147483648}}", "config_version: 1\nunknown: true", "config_version: 1\nservice: {unknown: true}", "config_version: 1\nservice: {enabled: true, enabled: false}", "config_version: 1\nservice: &s {enabled: true}", "config_version: 1\nservice: *s", "config_version: 1\nservice: {<<: {enabled: false}}", "config_version: 1\nservice: !!map {enabled: true}", "config_version: 1\n---\nconfig_version: 1", "config_version: 1\n? [a, b]\n: true", "config_version: 1\nnetwork: {bandwidth: {global_mib_per_second: .inf}}", "config_version: 1\nfar_players: {mode: unknown}", "config_version: 1\nlod: {distance: {by_dimension: {the_nether: 10}}}"})
    void rejectsMalformedOrUnsafeYaml(String input){assertThrows(SettingsException.class,()->codec.parse(bytes(input)));}
    @Test void rejectsInvalidUtf8(){assertThrows(SettingsException.class,()->codec.parse(new byte[]{(byte)0xC3,0x28}));}
    @Test void eventBudgetEnforcedBeforeTreeComposition(){
        assertDoesNotThrow(()->YamlSettingsCodec.validateStructure(bytes("[".repeat(32)+"0"+"]".repeat(32))));
        assertThrows(SettingsException.class,()->YamlSettingsCodec.validateStructure(bytes("[".repeat(33)+"0"+"]".repeat(33))));
        assertThrows(SettingsException.class,()->YamlSettingsCodec.validateStructure(bytes("[".repeat(10000)+"0"+"]".repeat(10000))));
        assertThrows(SettingsException.class,()->YamlSettingsCodec.validateStructure(bytes("["+"0,".repeat(50000)+"0]")));
        assertThrows(SettingsException.class,()->YamlSettingsCodec.validateStructure(new byte[YamlSettingsCodec.MAX_BYTES+1]));
    }
    @Test void freshFilesExplainResourceCostsAndTiming(){
        for(var c:List.of(codec,new YamlSettingsCodec<>(SettingsSchema.server(true)))) {
            String text=new String(c.defaults(),StandardCharsets.UTF_8);
            for(String phrase:List.of("uncompressed payload", "does not delete", "comparable in size", "simulation distance", "world-generation CPU", "[restart]", "freshness timestamps"))assertTrue(text.contains(phrase),phrase);
        }
    }
    @Test void inlineCommentOnEmptyCollectionSurvivesExpansionAndReemptying() {
        var c=new YamlSettingsCodec<>(SettingsSchema.client());var original=c.parse(c.defaults());
        byte[] expanded=c.edit(original,Map.of("cache.address_aliases",List.of(List.of("Exact.EXAMPLE","备用")),"compatibility.block_fallbacks.overrides",Map.of("remote:block","minecraft:stone")));
        var parsed=c.parse(expanded);assertEquals(List.of(List.of("Exact.EXAMPLE","备用")),parsed.configured().cache().addressAliases());
        String text=new String(expanded,StandardCharsets.UTF_8);assertTrue(text.contains("Ordered address groups"));assertTrue(text.contains("Remote block ID"));
        byte[] emptied=c.edit(parsed,Map.of("cache.address_aliases",List.of(),"compatibility.block_fallbacks.overrides",Map.of()));
        assertTrue(c.parse(emptied).configured().cache().addressAliases().isEmpty());assertTrue(new String(emptied,StandardCharsets.UTF_8).contains("Ordered address groups"));
    }
}
