package dev.vox.lss.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Experimental client keys persist in a sidecar beside the selected main file. */
class SettingsSidecarStoreTest {
    @TempDir Path dir;
    private SettingsStore<ClientSettings> client(String brand) { return new SettingsStore<>(dir,brand,SettingsSchema.client()); }
    private Path main() { return dir.resolve("lss-client-config.yaml"); }
    private Path sidecar() { return dir.resolve("lss-client-experimental.yaml"); }

    /** Allow DH Server Terrain is ON by default (2026-10-10): a fresh client has no sidecar and
     *  allows DH; turning it off writes {@code source: lss} to the sidecar only. */
    @Test void freshClientAllowsDhAndOptOutWritesOnlyTheSidecarAndOptInDeletesIt() throws Exception {
        for(String brand:new String[]{"lss","vss"}) {
            Path root=Files.createDirectories(dir.resolve(brand));
            Path dirMain=root.resolve(brand+"-client-config.yaml"), dirSidecar=root.resolve(brand+"-client-experimental.yaml");
            var store=new SettingsStore<>(root,brand,SettingsSchema.client());var initial=store.initialize();
            assertEquals(dirSidecar,store.sidecarPath());assertFalse(Files.exists(dirSidecar));
            assertEquals("distant_horizons",initial.configured().lod().source());
            assertEquals("distant_horizons",store.read().configured().lod().source());
            assertEquals("distant_horizons",new SettingsHandle<>(new SettingsStore<>(root,brand,SettingsSchema.client())).state().effective().lod().source());
            assertNull(SettingsSchema.lookup(YamlSettingsCodec.readTree(Files.readAllBytes(dirMain)),"lod.source"));
            byte[] mainBefore=Files.readAllBytes(dirMain);
            var opted=store.saveDraft(initial.hash(),Map.of("lod.source","lss"));
            assertArrayEquals(mainBefore,Files.readAllBytes(dirMain),"main must not be rewritten for sidecar-only changes");
            assertEquals("lss",SettingsSchema.lookup(YamlSettingsCodec.readTree(Files.readAllBytes(dirSidecar)),"lod.source"));
            assertFalse(Files.readString(dirSidecar).contains("distant_horizons"));
            assertEquals("lss",opted.configured().lod().source());assertEquals("lss",new SettingsStore<>(root,brand,SettingsSchema.client()).initialize().configured().lod().source());
            assertEquals(opted.hash(),store.read().hash());assertTrue(store.matches(opted.hash()));
            var back=store.saveDraft(opted.hash(),Map.of("lod.source","distant_horizons"));
            assertFalse(Files.exists(dirSidecar));assertArrayEquals(mainBefore,Files.readAllBytes(dirMain));
            assertEquals("distant_horizons",back.configured().lod().source());assertEquals(back.hash(),store.read().hash());
        }
    }
    @Test void aDevelopmentSidecarRepeatingTheNewDefaultIsDeletedOnTheNextSave() throws Exception {
        var store=client("lss");store.initialize();
        Files.writeString(sidecar(),"lod:\n  source: distant_horizons\n");
        var read=store.read();assertEquals("distant_horizons",read.configured().lod().source());
        var saved=store.saveDraft(read.hash(),Map.of("lod.distance_chunks",96));
        assertFalse(Files.exists(sidecar()));
        assertNull(SettingsSchema.lookup(YamlSettingsCodec.readTree(Files.readAllBytes(main())),"lod.source"));
        assertEquals("distant_horizons",saved.configured().lod().source());assertEquals(96,saved.configured().lod().distanceChunks());
    }
    @Test void developmentBuildMainIsHonouredThenMigratedOnNextSave() throws Exception {
        var store=client("lss");store.initialize();
        String text=Files.readString(main()).replace("lod:\n","lod:\n  source: distant_horizons\n  dh:\n    profile: official_16\n");
        Files.writeString(main(),text);
        var dev=store.read();assertEquals("distant_horizons",dev.configured().lod().source());
        var saved=store.saveDraft(dev.hash(),Map.of("lod.distance_chunks",96));
        var tree=YamlSettingsCodec.readTree(Files.readAllBytes(main()));
        assertNull(SettingsSchema.lookup(tree,"lod.source"));assertNull(SettingsSchema.lookup(tree,"lod.dh"));
        assertTrue(Files.readString(sidecar()).contains("distant_horizons"));
        assertEquals("distant_horizons",saved.configured().lod().source());assertEquals(96,saved.configured().lod().distanceChunks());
        assertEquals(saved.hash(),store.read().hash());
    }
    @Test void aSidecarOnlyEditOnDiskIsAConflictAndReloadSeesIt() throws Exception {
        var store=client("lss");var initial=store.initialize();
        var identity=store.verifySource(initial.hash());
        Files.writeString(sidecar(),"lod:\n  source: lss\n");
        assertFalse(store.matches(initial.hash()));assertFalse(store.identityMatches(identity));
        assertThrows(SettingsException.class,()->store.saveDraft(initial.hash(),Map.of("lod.receive",false)));
        assertEquals("lss",store.read().configured().lod().source());
        Files.delete(sidecar());assertTrue(store.matches(initial.hash()));
    }
    @Test void aMalformedSidecarNeverDisablesTheMainSettingsAndIsNeverRewrittenByReading() throws Exception {
        var store=client("lss");store.initialize();
        Files.writeString(sidecar(),"lod:\n  receive: false\n");
        assertTrue(store.read().configured().lod().receive());
        Files.writeString(sidecar(),"lod: [broken");
        assertEquals("distant_horizons",store.read().configured().lod().source(),"an unreadable sidecar falls back to the default");
        assertTrue(new SettingsHandle<>(client("lss")).state().effective().lod().receive());
        assertEquals("lod: [broken",Files.readString(sidecar()));
    }
    @Test void startupMovesDevelopmentBuildKeysOutOfMainSoADowngradeReadsIt() throws Exception {
        var store=client("lss");store.initialize();
        Files.writeString(main(),Files.readString(main()).replace("lod:\n","lod:\n  source: distant_horizons\n  dh:\n    refresh_seconds: 30\n"));
        var document=client("lss").initialize();
        var tree=YamlSettingsCodec.readTree(Files.readAllBytes(main()));
        assertNull(SettingsSchema.lookup(tree,"lod.source"));assertNull(SettingsSchema.lookup(tree,"lod.dh"));
        assertEquals("distant_horizons",document.configured().lod().source());
        assertTrue(Files.readString(sidecar()).contains("distant_horizons"));
    }
    @Test void sidecarFollowsTheSelectedMainBrand() throws Exception {
        var store=client("vss");store.initialize();
        assertEquals(dir.resolve("vss-client-experimental.yaml"),store.sidecarPath());
        Files.writeString(dir.resolve("vss-client-experimental.yaml"),"lod:\n  voxy_border_fill: false\n");
        var adopted=client("lss");assertFalse(adopted.initialize().configured().lod().voxyBorderFill());
        assertEquals(dir.resolve("vss-client-experimental.yaml"),adopted.sidecarPath());
    }
    @Test void legacyJsonMigrationKeepsExperimentalKeysOutOfMain() throws Exception {
        Files.writeString(dir.resolve("lss-client-config.json"),"{\"dhTerrainSource\":\"lss\",\"lodDistanceChunks\":64}");
        var document=client("lss").initialize();
        assertEquals("lss",document.configured().lod().source());
        var tree=YamlSettingsCodec.readTree(Files.readAllBytes(main()));
        assertNull(SettingsSchema.lookup(tree,"lod.source"));
        assertEquals("lss",SettingsSchema.lookup(YamlSettingsCodec.readTree(Files.readAllBytes(sidecar())),"lod.source"));
    }
    @Test void serverStoresHaveNoSidecar() throws Exception {
        var store=new SettingsStore<>(dir,"lss",SettingsSchema.server(false));store.initialize();
        assertNull(store.sidecarPath());
    }
}
