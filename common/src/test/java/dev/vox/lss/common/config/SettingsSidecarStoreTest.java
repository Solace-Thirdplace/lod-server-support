package dev.vox.lss.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Experimental client keys persist in a sidecar beside the selected main file. */
class SettingsSidecarStoreTest {
    @TempDir Path dir;
    private SettingsStore<ClientSettings> client(String brand) { return new SettingsStore<>(dir,brand,SettingsSchema.client()); }
    private Path main() { return dir.resolve("lss-client-config.yaml"); }
    private Path sidecar() { return dir.resolve("lss-client-experimental.yaml"); }

    @Test void optInWritesOnlyTheSidecarAndOptOutDeletesIt() throws Exception {
        var store=client("lss");var initial=store.initialize();
        assertEquals(sidecar(),store.sidecarPath());assertFalse(Files.exists(sidecar()));
        byte[] mainBefore=Files.readAllBytes(main());
        var opted=store.saveDraft(initial.hash(),Map.of("lod.source","distant_horizons"));
        assertArrayEquals(mainBefore,Files.readAllBytes(main()),"main must not be rewritten for sidecar-only changes");
        assertTrue(Files.readString(sidecar()).contains("distant_horizons"));
        assertEquals("distant_horizons",opted.configured().lod().source());
        assertEquals(opted.hash(),store.read().hash());assertTrue(store.matches(opted.hash()));
        var back=store.saveDraft(opted.hash(),Map.of("lod.source","lss"));
        assertFalse(Files.exists(sidecar()));assertArrayEquals(mainBefore,Files.readAllBytes(main()));
        assertEquals("lss",back.configured().lod().source());assertEquals(back.hash(),store.read().hash());
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
        Files.writeString(sidecar(),"lod:\n  source: distant_horizons\n");
        assertFalse(store.matches(initial.hash()));assertFalse(store.identityMatches(identity));
        assertThrows(SettingsException.class,()->store.saveDraft(initial.hash(),Map.of("lod.receive",false)));
        assertEquals("distant_horizons",store.read().configured().lod().source());
        Files.delete(sidecar());assertTrue(store.matches(initial.hash()));
    }
    @Test void malformedSidecarIsASettingsErrorAndIsNeverRewritten() throws Exception {
        var store=client("lss");store.initialize();
        Files.writeString(sidecar(),"lod:\n  receive: false\n");
        assertThrows(SettingsException.class,store::read);
        Files.writeString(sidecar(),"lod: [broken");
        assertThrows(SettingsException.class,store::read);
        assertThrows(SettingsException.class,()->new SettingsHandle<>(client("lss")));
        assertEquals("lod: [broken",Files.readString(sidecar()));
    }
    @Test void sidecarFollowsTheSelectedMainBrand() throws Exception {
        var store=client("vss");store.initialize();
        assertEquals(dir.resolve("vss-client-experimental.yaml"),store.sidecarPath());
        Files.writeString(dir.resolve("vss-client-experimental.yaml"),"lod:\n  voxy_border_fill: false\n");
        var adopted=client("lss");assertFalse(adopted.initialize().configured().lod().voxyBorderFill());
        assertEquals(dir.resolve("vss-client-experimental.yaml"),adopted.sidecarPath());
    }
    @Test void legacyJsonMigrationKeepsExperimentalKeysOutOfMain() throws Exception {
        Files.writeString(dir.resolve("lss-client-config.json"),"{\"dhTerrainSource\":\"distant_horizons\",\"lodDistanceChunks\":64}");
        var document=client("lss").initialize();
        assertEquals("distant_horizons",document.configured().lod().source());
        var tree=YamlSettingsCodec.readTree(Files.readAllBytes(main()));
        assertNull(SettingsSchema.lookup(tree,"lod.source"));
        assertTrue(Files.readString(sidecar(),StandardCharsets.UTF_8).contains("distant_horizons"));
    }
    @Test void serverStoresHaveNoSidecar() throws Exception {
        var store=new SettingsStore<>(dir,"lss",SettingsSchema.server(false));store.initialize();
        assertNull(store.sidecarPath());
    }
}
