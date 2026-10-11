package dev.vox.lss.config.menu;

import dev.vox.lss.common.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ClientSettingsEditSessionTest {
    @TempDir Path directory;
    @Test void applyWritesOnlyChangedPathsAndReloadIsTheOnlyPublisher() throws Exception {
        var store=new SettingsStore<>(directory,"lss",SettingsSchema.client());
        var handle=new SettingsHandle<>(store);
        var initial=store.read();
        store.saveDraft(initial.hash(),Map.of("lod.download.max_columns_per_second",100000));
        String text=Files.readString(store.path()).replace("receive: true", "receive: true # 保留註解");
        Files.writeString(store.path(),text);
        var draft=new ClientSettingsEditSession(store,()->handle.state().effective());
        draft.set("far_players.sharing.enabled",false);
        assertTrue(draft.activeSharing()); assertFalse(draft.draftSharing());
        assertTrue(draft.save());
        assertTrue(handle.state().effective().farPlayers().sharing().enabled(),"disk save is inert");
        assertTrue(Files.readString(store.path()).contains("保留註解"));
        assertEquals(100000,store.read().configured().lod().download().maxColumnsPerSecond(),"unrelated edit must preserve values beyond slider top");
        handle.commit(handle.prepareReload());
        assertFalse(handle.state().effective().farPlayers().sharing().enabled());
    }
    @Test void failureRetainsEditsAcrossReopenAndRetryNeedsNoReentry() throws Exception {
        var fail=new AtomicBoolean();
        var schema=SettingsSchema.client();
        var store=new SettingsStore<>(directory,"lss",schema,line->{},(temp,target)->{
            if(fail.get())throw new java.io.IOException("disk unavailable");
        });
        var handle=new SettingsHandle<>(store);
        var draft=new ClientSettingsEditSession(store,()->handle.state().effective());
        draft.set("lod.receive",false); fail.set(true);
        assertFalse(draft.save());
        assertEquals(ClientSettingsEditSession.Outcome.FAILED,draft.outcome());
        draft.open(new Object());
        assertFalse(draft.bool("lod.receive"));
        assertTrue(draft.hasRetainedEdits());
        assertTrue(handle.state().effective().lod().receive());
        fail.set(false);assertTrue(draft.save());
        assertFalse(store.read().configured().lod().receive());
        assertTrue(handle.state().effective().lod().receive());
    }
    @Test void conflictCannotBlindlyRetryAndExplicitRebasePreservesExternalChanges() throws Exception {
        var store=new SettingsStore<>(directory,"vss",SettingsSchema.client());
        var handle=new SettingsHandle<>(store);
        var draft=new ClientSettingsEditSession(store,()->handle.state().effective());
        draft.set("far_players.sharing.enabled",false);
        store.saveDraft(store.read().hash(),Map.of("lod.distance_chunks",128));
        assertFalse(draft.save());
        assertEquals(ClientSettingsEditSession.Outcome.CONFLICT,draft.outcome());
        assertFalse(draft.save(),"retry cannot discard the external writer's changes");
        assertTrue(draft.rebaseAndSave());
        assertEquals(128,store.read().configured().lod().distanceChunks());
        assertFalse(store.read().configured().farPlayers().sharing().enabled());
        assertTrue(handle.state().effective().farPlayers().sharing().enabled());
    }
    @Test void invalidOrNewerYamlNeverGetsReplacedByMenuDefaults() throws Exception {
        var store=new SettingsStore<>(directory,"lss",SettingsSchema.client());
        store.initialize();String future="config_version: 99\n";Files.writeString(store.path(),future);
        var draft=new ClientSettingsEditSession(store,SettingsSchema.client()::defaults);
        draft.set("lod.receive",false);
        assertFalse(draft.save());assertFalse(draft.rebaseAndSave());
        assertEquals(future,Files.readString(store.path()));
        assertTrue(draft.hasRetainedEdits());
    }
    @Test void sessionSettingsStayFrozenAcrossRepushWorldSwitchAndSameConnectionJoin() throws Exception {
        var store=new SettingsStore<>(directory,"lss",SettingsSchema.client());
        var handle=new SettingsHandle<>(store);Object physical=new Object();handle.beginSession(physical);
        var before=handle.state().effective();
        store.saveDraft(store.read().hash(),Map.of("scan.region_order",false,"cache.address_aliases",List.of(List.of("a.example.com","b.example.com")),"integrations.map_ingestion.enabled",false,"lod.receive",false));
        var accepted=handle.commit(handle.prepareReload());
        assertFalse(accepted.effective().lod().receive());
        assertEquals(before.cache(),accepted.effective().cache());
        assertEquals(before.scan(),accepted.effective().scan());
        assertTrue(accepted.effective().integrations().mapIngestion().enabled());
        handle.beginSession(physical); // play/configuration/play uses this same transport
        assertEquals(before.cache(),handle.state().effective().cache());
        assertTrue(handle.state().effective().integrations().mapIngestion().enabled());
        handle.endSession(physical);
        Files.writeString(store.path(),"broken disk edit must not be re-read at reconnect");
        handle.beginSession(new Object());
        assertFalse(handle.state().effective().integrations().mapIngestion().enabled());
        assertEquals(List.of(List.of("a.example.com","b.example.com")),handle.state().effective().cache().addressAliases());
    }
}
