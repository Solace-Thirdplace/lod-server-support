package dev.vox.lss.common.config;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SettingsHandleTest {
    @TempDir Path dir;
    private SettingsStore<ServerSettings> serverStore(){return new SettingsStore<>(dir,"lss",SettingsSchema.server(false));}
    private static <T> void edit(SettingsStore<T> store,Map<String,Object> changes)throws Exception{store.saveDraft(store.read().hash(),changes);}

    @Test void dhSynchronizationDefaultsOnAndRetiredRefreshIsNotAnActiveSetting() {
        var schema=SettingsSchema.client();
        assertTrue(schema.defaults().lod().dh().syncOnLoad());
        assertTrue(schema.defaults().lod().dh().realTimeUpdates());
        assertFalse(schema.descriptorsByPath().containsKey("lod.dh.refresh_seconds"));
        assertFalse(schema.descriptorsByPath().containsKey("lod.dh.profile"));
    }
    @Test void dhFallbackPermissionIsReconnectScopedButSynchronizationIsLive() throws Exception {
        var store=new SettingsStore<>(dir,"lss",SettingsSchema.client());
        var h=new SettingsHandle<>(store); Object connection=new Object(); h.beginSession(connection);
        assertEquals("distant_horizons",h.state().effective().lod().source(),"DH server terrain is allowed by default");
        edit(store,Map.of("lod.source","lss","lod.dh.sync_on_load",false));
        var commit=h.commit(h.prepareReload());
        assertEquals("distant_horizons",h.state().effective().lod().source());
        assertFalse(h.state().effective().lod().dh().syncOnLoad());
        assertEquals(Set.of("lod.source"),commit.pendingReconnect());
        h.endSession(connection); h.beginSession(new Object());
        assertEquals("lss",h.state().effective().lod().source());
        assertThrows(SettingsException.class,()->SettingsSchema.client().fromValues(Map.of("lod.source","automatic")));
        assertThrows(SettingsException.class,()->SettingsSchema.client().fromValues(Map.of("lod.dh.profile","guess")));
        edit(store,Map.of("lod.dh.sync_on_load",true)); h.commit(h.prepareReload());
        assertTrue(h.state().effective().lod().dh().syncOnLoad());
        h.close();
    }
    @Test void diskDraftDoesNotChangeAnyActiveSnapshot()throws Exception {
        var store=serverStore();var h=new SettingsHandle<>(store);var before=h.state();edit(store,Map.of("generation.enabled",false));
        assertSame(before,h.state());assertTrue(h.state().effective().generation().enabled());assertFalse(store.read().normalized().generation().enabled());
    }
    @Test void restartSettingsNeverLeakAndRevertingClearsPending()throws Exception {
        var store=serverStore();var h=new SettingsHandle<>(store);
        edit(store,Map.of("storage.lod_store.enabled",false,"generation.concurrency.global",17,"generation.concurrency.per_player",12));
        var first=h.commit(h.prepareReload());assertEquals(Set.of("generation.concurrency.global","generation.concurrency.per_player"),first.changedPaths());
        assertTrue(h.state().effective().storage().lodStore().enabled());assertFalse(h.state().configured().storage().lodStore().enabled());
        assertEquals(Set.of("storage.lod_store.enabled"),h.state().pendingRestart());
        edit(store,Map.of("network.send_pacing",false));h.commit(h.prepareReload());assertTrue(h.state().effective().storage().lodStore().enabled());
        edit(store,Map.of("storage.lod_store.enabled",true));h.commit(h.prepareReload());assertTrue(h.state().pendingRestart().isEmpty());assertTrue(h.state().effective().storage().lodStore().enabled());
    }
    @Test void repeatedReloadIsNoopWithSameRevisionAndObject()throws Exception {
        var store=serverStore();var h=new SettingsHandle<>(store);var original=h.state();var noOp=h.commit(h.prepareReload());
        assertTrue(noOp.unchanged());assertTrue(noOp.changedPaths().isEmpty());assertSame(original,h.state());
        edit(store,Map.of("generation.enabled",false));h.commit(h.prepareReload());var active=h.state();assertTrue(h.commit(h.prepareReload()).unchanged());assertSame(active,h.state());
    }
    @Test void commentsOnlyDiskChangesAreSettingsNoop()throws Exception {
        var store=serverStore();var h=new SettingsHandle<>(store);var original=h.state();Files.writeString(store.path(),Files.readString(store.path())+"\n# operator note\n");
        assertTrue(h.commit(h.prepareReload()).unchanged());assertSame(original,h.state());
    }
    @Test void malformedReloadPreservesStateAndReleasesBusy()throws Exception {
        var store=serverStore();var h=new SettingsHandle<>(store);var original=h.state();String good=Files.readString(store.path());Files.writeString(store.path(),"config_version: 1\nservice: {enabled: bad}\n");
        assertThrows(SettingsException.class,h::prepareReload);assertSame(original,h.state());assertFalse(h.busy());Files.writeString(store.path(),good);assertTrue(h.commit(h.prepareReload()).unchanged());
    }
    @Test void overlappingPrepareIsBusyAndStaleCancellationCannotReleaseNewRequest()throws Exception {
        var store=serverStore();var h=new SettingsHandle<>(store);var first=h.prepareReload();assertThrows(SettingsException.class,h::prepareReload);
        h.cancelPrepared(first);var second=h.prepareReload();h.cancelPrepared(first);assertTrue(h.busy());assertThrows(SettingsException.class,()->h.commit(first));assertTrue(h.busy());h.commit(second);assertFalse(h.busy());
    }
    @Test void sourceEditAndLifecycleReplacementRejectPreparedCandidate()throws Exception {
        var store=serverStore();var h=new SettingsHandle<>(store);var original=h.state();edit(store,Map.of("generation.enabled",false));var prepared=h.prepareReload();
        Files.writeString(store.path(),"config_version: 1\n# new edit\n");assertThrows(SettingsException.class,()->h.commit(prepared));assertSame(original,h.state());assertFalse(h.busy());
        var afterClose=h.prepareReload();h.close();assertThrows(SettingsException.class,()->h.commit(afterClose));assertSame(original,h.state());
    }
    @Test void normalizedCandidateAndCorrelatedGroupsPublishTogether()throws Exception {
        var store=serverStore();var h=new SettingsHandle<>(store);edit(store,Map.of("generation.concurrency.global",5,"generation.concurrency.per_player",99,"far_players.distance.max_blocks",128,"far_players.distance.min_blocks",900));
        var result=h.commit(h.prepareReload());assertEquals(5,result.effective().generation().concurrency().global());assertEquals(5,result.effective().generation().concurrency().perPlayer());assertEquals(128,result.effective().farPlayers().distance().minBlocks());assertEquals(2,result.normalizations().size());assertEquals(99,h.state().configured().generation().concurrency().perPlayer());
    }
    @Test void inactiveCopiedGroupIsRetainedButNotActivated()throws Exception {
        var store=serverStore();var h=new SettingsHandle<>(store);edit(store,Map.of("paper.update_events",List.of()));
        var result=h.commit(h.prepareReload());assertTrue(h.state().configured().paper().updateEvents().isEmpty());assertFalse(h.state().effective().paper().updateEvents().isEmpty());assertTrue(result.inactivePaths().contains("paper.update_events"));assertTrue(result.changedPaths().isEmpty());
    }
    @Test void clientSessionGroupWaitsForActualReconnectAndUsesAcceptedSnapshotOnly()throws Exception {
        var store=new SettingsStore<>(dir,"lss",SettingsSchema.client());var h=new SettingsHandle<>(store);Object connection=new Object();h.beginSession(connection);
        edit(store,Map.of("scan.quadtree",false,"integrations.map_ingestion.enabled",false,"far_players.sharing.enabled",false));
        var result=h.commit(h.prepareReload());assertFalse(result.effective().farPlayers().sharing().enabled());assertTrue(result.effective().scan().quadtree());assertTrue(result.effective().integrations().mapIngestion().enabled());
        assertEquals(Set.of("scan.quadtree","integrations.map_ingestion.enabled"),h.state().pendingReconnect());
        h.beginSession(connection);assertTrue(h.state().effective().scan().quadtree());
        // An un-reloaded disk edit does not become active at reconnect.
        edit(store,Map.of("scan.quadtree",true));h.endSession(connection);h.beginSession(new Object());
        assertFalse(h.state().effective().scan().quadtree());assertFalse(h.state().effective().integrations().mapIngestion().enabled());assertTrue(h.state().pendingReconnect().isEmpty());
    }
    @Test void disconnectedReloadAcceptsSessionChangesAndStaleDisconnectDoesNotUnfreezeNewSession()throws Exception {
        var store=new SettingsStore<>(dir,"lss",SettingsSchema.client());var h=new SettingsHandle<>(store);
        edit(store,Map.of("scan.quadtree",false));h.commit(h.prepareReload());assertFalse(h.state().effective().scan().quadtree());assertTrue(h.state().pendingReconnect().isEmpty());
        Object old=new Object(),current=new Object();h.beginSession(old);h.beginSession(current);edit(store,Map.of("scan.quadtree",true));h.commit(h.prepareReload());h.endSession(old);assertFalse(h.state().effective().scan().quadtree());
        h.endSession(current);assertTrue(h.state().effective().scan().quadtree());
    }
    @Test void connectionBoundaryCancelsParse()throws Exception {
        var store=new SettingsStore<>(dir,"lss",SettingsSchema.client());var h=new SettingsHandle<>(store);var prepared=h.prepareReload();h.beginSession(new Object());assertThrows(SettingsException.class,()->h.commit(prepared));assertFalse(h.busy());
    }
}
