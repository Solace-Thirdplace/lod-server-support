package dev.vox.lss.common.config;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class SettingsStoreTest {
    @Test void freshAndMigratedVssCommentsNameItsOwnCommands() throws Exception {
        for (boolean client : List.of(false, true)) {
            var target = dir.resolve(client ? "client-brand" : "server-brand");
            Files.createDirectories(target);
            if (client) Files.writeString(target.resolve("lss-client-config.json"), "{}");
            var store = client ? new SettingsStore<>(target, "vss", SettingsSchema.client())
                    : new SettingsStore<>(target, "vss", SettingsSchema.server(false));
            String text = new String(store.initialize().bytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(text.contains(client ? "/vss reload" : "/vsslod reload"));
            assertFalse(text.contains("/lss"));
            assertEquals(client ? "lss-client-config.yaml" : "vss-server-config.yaml", store.path().getFileName().toString());
        }
    }
    @TempDir Path dir;
    private SettingsStore<ServerSettings> server(String prefix){return new SettingsStore<>(dir,prefix,SettingsSchema.server(false));}
    private Path file(String name){return dir.resolve(name);}
    @Test void freshCreationHasCommentsAndRepeatedStartupIsBytePreserving()throws Exception {
        var first=server("lss");assertTrue(first.initialize().normalized().storage().lodStore().enabled());
        byte[] bytes=Files.readAllBytes(first.path());assertTrue(new String(bytes,StandardCharsets.UTF_8).contains("comparable in size"));
        var adopted=server("vss");assertArrayEquals(bytes,adopted.initialize().bytes());assertEquals(first.path(),adopted.path());
        assertFalse(Files.exists(file("vss-server-config.yaml")));
    }
    @Test void yamlIsAuthoritativeEvenWhenInvalidAndOtherBrandIsValid()throws Exception {
        Files.writeString(file("lss-server-config.yaml"),"bad: true");Files.writeString(file("vss-server-config.yaml"),"config_version: 1\n");Files.writeString(file("lss-server-config.json"),"{}");
        assertThrows(SettingsException.class,()->server("lss").initialize());assertEquals("bad: true",Files.readString(file("lss-server-config.yaml")));
        try(var files=Files.list(dir)){assertEquals(3,files.count());}
    }
    @Test void otherBrandYamlBeatsPreferredBrandJson()throws Exception {
        Files.writeString(file("vss-server-config.yaml"),"config_version: 1\nservice: {enabled: false}\n");Files.writeString(file("lss-server-config.json"),"{}");
        var s=server("lss");assertFalse(s.initialize().normalized().service().enabled());assertEquals(file("vss-server-config.yaml"),s.path());
    }
    @Test void jsonMigrationKeepsExactSourceStemAndExclusiveVerifiedBackups()throws Exception {
        byte[] original="{\"lodDistanceChunks\": 700, \"lodStore\":\"off\"}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file("vss-server-config.json"),original);Files.writeString(file("vss-server-config.json.migrated.bak"),"older backup");
        var s=server("lss");var document=s.initialize();assertEquals(file("vss-server-config.yaml"),s.path());
        assertArrayEquals(original,Files.readAllBytes(file("vss-server-config.json")));assertArrayEquals(original,Files.readAllBytes(file("vss-server-config.json.migrated.bak.1")));
        assertEquals("older backup",Files.readString(file("vss-server-config.json.migrated.bak")));assertEquals(700,document.normalized().lod().distance().defaultChunks());
        assertEquals(Map.of("minecraft:overworld",700,"minecraft:the_nether",700,"minecraft:the_end",700),document.normalized().lod().distance().byDimension());assertFalse(document.normalized().storage().lodStore().enabled());
        byte[] before=document.bytes();Files.writeString(file("vss-server-config.json"),"{}");assertArrayEquals(before,server("lss").initialize().bytes());
    }
    @Test void corruptJsonNeverCreatesAuthoritativeYamlOrRewritesSource()throws Exception {
        byte[] original="{broken".getBytes(StandardCharsets.UTF_8);Files.write(file("lss-server-config.json"),original);
        assertThrows(SettingsException.class,()->server("lss").initialize());assertArrayEquals(original,Files.readAllBytes(file("lss-server-config.json")));assertFalse(Files.exists(file("lss-server-config.yaml")));
    }
    @Test void commentedLegacyFilesMigrateOnceAndKeepTheirOriginalBytes() throws Exception {
        for(boolean client:List.of(false,true)) {
            Path target=dir.resolve(client?"loose-client":"loose-server");Files.createDirectories(target);
            String stem=client?"lss-client-config":"lss-server-config";
            byte[] original=(client?"// client\n{farPlayersShareSelf:false}":"/* server */\n{enabled:false,lodDistanceChunks:128}").getBytes(StandardCharsets.UTF_8);
            Files.write(target.resolve(stem+".json"),original);
            var store=client?new SettingsStore<>(target,"lss",SettingsSchema.client()):new SettingsStore<>(target,"lss",SettingsSchema.server(false));
            var migrated=store.initialize();
            assertArrayEquals(original,Files.readAllBytes(target.resolve(stem+".json")));
            assertArrayEquals(original,Files.readAllBytes(target.resolve(stem+".json.migrated.bak")));
            assertArrayEquals(migrated.bytes(),store.initialize().bytes());
        }
    }
    @Test void sourceEditDuringMigrationAbortsBeforeYamlInstallation()throws Exception {
        Files.writeString(file("lss-server-config.json"),"{}");
        var s=new SettingsStore<>(dir,"lss",SettingsSchema.server(false),m->{},(temp,target)->Files.writeString(file("lss-server-config.json"),"{\"enabled\":false}"));
        assertThrows(SettingsException.class,s::initialize);assertFalse(Files.exists(file("lss-server-config.yaml")));assertEquals("{}",Files.readString(file("lss-server-config.json.migrated.bak")));
    }
    @Test void competingYamlDuringMigrationIsNeverClobbered()throws Exception {
        Files.writeString(file("lss-server-config.json"),"{}");
        var s=new SettingsStore<>(dir,"lss",SettingsSchema.server(false),m->{},(temp,target)->Files.writeString(target,"config_version: 1\nservice: {enabled: false}\n"));
        assertThrows(SettingsException.class,s::initialize);assertFalse(server("lss").initialize().normalized().service().enabled());
    }
    @Test void createOnlyPrimitiveCannotReplaceAnExistingTarget()throws Exception {
        Path temp=file("temporary"),target=file("target");Files.writeString(temp,"new");Files.writeString(target,"old");
        assertThrows(SettingsException.class,()->SettingsStore.installNew(temp,target));assertEquals("old",Files.readString(target));assertEquals("new",Files.readString(temp));
    }
    @Test void validDraftSaveIsAtomicAndRetainsComments()throws Exception {
        var s=server("lss");var first=s.initialize();var saved=s.saveDraft(first.hash(),Map.of("generation.enabled",false));
        assertFalse(saved.configured().generation().enabled());assertTrue(new String(saved.bytes(),StandardCharsets.UTF_8).contains("world-generation CPU"));
        assertEquals(saved.hash(),s.read().hash());try(var files=Files.list(dir)){assertTrue(files.noneMatch(p->p.toString().endsWith(".tmp")));}
    }
    @Test void staleDraftDoesNotReplaceExternalEdit()throws Exception {
        var s=server("lss");var first=s.initialize();Files.writeString(s.path(),"config_version: 1\n# external\n");
        assertThrows(SettingsException.class,()->s.saveDraft(first.hash(),Map.of("generation.enabled",false)));assertTrue(Files.readString(s.path()).contains("# external"));
    }
    @Test void editAtLastControllableBoundaryPreventsReplacementAndPreservesDraft()throws Exception {
        AtomicBoolean race=new AtomicBoolean();
        var s=new SettingsStore<>(dir,"lss",SettingsSchema.server(false),m->{},(temp,target)->{if(race.get())Files.writeString(target,"config_version: 1\n# later edit\n");});
        var first=s.initialize();race.set(true);var changes=Map.<String,Object>of("generation.enabled",false);
        assertThrows(SettingsException.class,()->s.saveDraft(first.hash(),changes));assertTrue(Files.readString(s.path()).contains("# later edit"));assertEquals(false,changes.get("generation.enabled"));
    }
    @Test void missingOrInvalidTargetIsNeverRecreatedByMenu()throws Exception {
        var s=server("lss");var first=s.initialize();Files.delete(s.path());assertThrows(SettingsException.class,()->s.saveDraft(first.hash(),Map.of("generation.enabled",false)));assertFalse(Files.exists(s.path()));
        Files.writeString(s.path(),"config_version: 99\n");assertThrows(SettingsException.class,()->s.saveDraft(first.hash(),Map.of("generation.enabled",false)));assertEquals("config_version: 99\n",Files.readString(s.path()));
    }
    @Test void failedWriteLeavesPriorSettingsAndAllowsRetry()throws Exception {
        AtomicBoolean fail=new AtomicBoolean();var s=new SettingsStore<>(dir,"lss",SettingsSchema.server(false),m->{},(temp,target)->{if(fail.get())throw new IOException("injected failure");});
        var first=s.initialize();fail.set(true);assertThrows(IOException.class,()->s.saveDraft(first.hash(),Map.of("generation.enabled",false)));assertEquals(first.hash(),s.read().hash());
        fail.set(false);assertFalse(s.saveDraft(first.hash(),Map.of("generation.enabled",false)).normalized().generation().enabled());
    }
    @Test void migrationCommentsSurviveSubsequentMenuSave()throws Exception {
        Files.writeString(file("lss-client-config.json"),"{\"farPlayersShareSelf\":false}");var s=new SettingsStore<>(dir,"lss",SettingsSchema.client());var migrated=s.initialize();
        var saved=s.saveDraft(migrated.hash(),Map.of("far_players.name_tags",false));assertFalse(saved.normalized().farPlayers().sharing().enabled());
        String text=new String(saved.bytes(),StandardCharsets.UTF_8);assertTrue(text.contains("Showing other players and sharing your own position are independent"));assertTrue(text.contains("without removing terrain already saved"));
    }
    @Test void migrationRetainsExplicitRecognizedInactiveGroups()throws Exception {
        Files.writeString(file("lss-server-config.json"),"{\"lodStoreBackfill\":false,\"lodStoreBackfillColumnsPerSecond\":25}");
        var paper=new SettingsStore<>(dir,"lss",SettingsSchema.server(true));var migrated=paper.initialize();
        assertFalse(migrated.configured().storage().lodStore().backfill().enabled());
        assertEquals(25,migrated.configured().storage().lodStore().backfill().columnsPerSecond());
        assertTrue(migrated.inactivePaths().contains("storage.lod_store.backfill.enabled"));
    }
}
