package dev.vox.lss.config.menu;

import dev.vox.lss.common.config.ClientSettings;
import dev.vox.lss.common.config.SettingsHandle;
import dev.vox.lss.common.config.SettingsSchema;
import dev.vox.lss.common.config.SettingsStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real disk/handle behavior behind both Sodium storage adapters; no Minecraft client needed. */
class ClientMenuApplyTest {
    @TempDir Path directory;

    private ClientSettingsEditSession draft(SettingsStore<ClientSettings> store,
                                            SettingsHandle<ClientSettings> handle, AtomicInteger reloads) {
        return new ClientSettingsEditSession(store, () -> handle.state().effective(),
                () -> handle.state().configured(), () -> {
                    try {
                        reloads.incrementAndGet();
                        handle.commit(handle.prepareReload());
                    } catch (Exception failure) { throw new AssertionError(failure); }
                });
    }

    @Test void applyReloadsTheWholeSavedPatchAndMapIngestionStopsOnReconnect() throws Exception {
        var store = new SettingsStore<>(directory, "lss", SettingsSchema.client());
        var handle = new SettingsHandle<>(store);
        Object connection = new Object();
        handle.beginSession(connection);
        var reloads = new AtomicInteger();
        var draft = draft(store, handle, reloads);
        // New settings files enable map ingestion, so the reconnect-timed example turns it off.
        draft.set("integrations.map_ingestion.enabled", false);
        draft.set("far_players.sharing.enabled", false);
        draft.set("lod.download.max_columns_per_second", 240);
        assertTrue(store.read().configured().integrations().mapIngestion().enabled());
        assertTrue(handle.state().effective().farPlayers().sharing().enabled());

        SaveHook.SAVE.run(draft);
        assertEquals(1, reloads.get(), "one reload for the complete Apply transaction");
        assertFalse(draft.pendingReload());
        assertFalse(handle.state().effective().farPlayers().sharing().enabled());
        assertEquals(240, handle.state().effective().lod().download().maxColumnsPerSecond());
        assertFalse(handle.state().configured().integrations().mapIngestion().enabled());
        assertTrue(handle.state().effective().integrations().mapIngestion().enabled());
        assertTrue(handle.state().pendingReconnect().contains("integrations.map_ingestion.enabled"));

        SaveHook.SAVE.run(draft);
        assertEquals(1, reloads.get(), "no-op Apply must not request another reload");
        handle.endSession(connection);
        handle.beginSession(new Object());
        assertFalse(handle.state().effective().integrations().mapIngestion().enabled());
        assertTrue(handle.state().pendingReconnect().isEmpty());
    }

    @Test void failedSaveCannotReloadAndRetryAppliesRetainedEdits() throws Exception {
        var fail = new AtomicBoolean();
        var store = new SettingsStore<>(directory, "lss", SettingsSchema.client(), line -> {},
                (temporary, target) -> { if (fail.get()) throw new java.io.IOException("disk full"); });
        var handle = new SettingsHandle<>(store);
        var reloads = new AtomicInteger();
        var draft = draft(store, handle, reloads);
        draft.set("lod.receive", false);
        fail.set(true);
        assertFalse(draft.apply());
        assertEquals(0, reloads.get());
        assertTrue(handle.state().effective().lod().receive());
        assertTrue(draft.hasRetainedEdits());
        fail.set(false);
        assertTrue(draft.apply());
        assertEquals(1, reloads.get());
        assertFalse(handle.state().effective().lod().receive());
    }

    @Test void conflictRequiresRebaseWhichAlsoReloadsWithoutLosingExternalEdits() throws Exception {
        var store = new SettingsStore<>(directory, "vss", SettingsSchema.client());
        var handle = new SettingsHandle<>(store);
        var reloads = new AtomicInteger();
        var draft = draft(store, handle, reloads);
        draft.set("far_players.sharing.enabled", false);
        store.saveDraft(store.read().hash(), Map.of("lod.distance_chunks", 128));
        assertFalse(draft.apply());
        assertEquals(0, reloads.get());
        assertTrue(draft.rebaseAndApply());
        assertEquals(1, reloads.get());
        assertFalse(handle.state().effective().farPlayers().sharing().enabled());
        assertEquals(128, handle.state().effective().lod().distanceChunks());
    }

    @Test void applyFromTitleScreenActivatesSessionSettingsWithoutAnExtraReconnect() throws Exception {
        var store = new SettingsStore<>(directory, "lss", SettingsSchema.client());
        var handle = new SettingsHandle<>(store);
        var reloads = new AtomicInteger();
        var draft = draft(store, handle, reloads);
        draft.set("integrations.map_ingestion.enabled", false);
        assertTrue(draft.apply());
        assertFalse(handle.state().effective().integrations().mapIngestion().enabled());
        assertTrue(handle.state().pendingReconnect().isEmpty());
    }

    @Test void repeatedApplyDuringAnExistingReloadCoalescesAndReadsTheLatestFile() throws Exception {
        var store = new SettingsStore<>(directory, "lss", SettingsSchema.client());
        var handle = new SettingsHandle<>(store);
        var busy = new AtomicBoolean(true); // A command reload already owns the gate.
        var reloads = new AtomicInteger();
        var queue = new ClientMenuReload(busy::get, () -> {
            assertTrue(busy.compareAndSet(false, true));
            try { reloads.incrementAndGet(); handle.commit(handle.prepareReload()); }
            catch (Exception failure) { throw new AssertionError(failure); }
        });
        var draft = new ClientSettingsEditSession(store, () -> handle.state().effective(),
                () -> handle.state().configured(), queue::request);
        draft.set("lod.download.max_columns_per_second", 100);
        assertTrue(draft.apply());
        draft.set("lod.download.max_columns_per_second", 200);
        assertTrue(draft.apply());
        queue.drain();
        assertEquals(0, reloads.get());
        busy.set(false); // Completion, whether success or failure, releases the gate.
        queue.drain();
        assertEquals(1, reloads.get());
        assertEquals(200, handle.state().effective().lod().download().maxColumnsPerSecond());
        busy.set(false);
        queue.drain();
        assertEquals(1, reloads.get(), "no unbounded retry loop");
    }
}
