package journeymap.client;

import java.util.ArrayList;
import java.util.List;
import journeymap.client.cartography.ChunkRenderController;
import journeymap.client.properties.CoreProperties;
import journeymap.client.task.multi.TaskController;

/** Tier-1 stub of JourneyMap's client singleton: the members the LSS map-ingestion bindings
 *  resolve, with the exact descriptors of JourneyMap 6.0.x (verified against the real jars by
 *  JourneyMapBindingSurfaceTest). Test-controlled public state; {@link #queueOneOff} records. */
public class JourneymapClient {
    public static JourneymapClient INSTANCE = new JourneymapClient();

    public Boolean mapping = Boolean.TRUE;
    public boolean suspended;
    public boolean enabled = true;
    public TaskController controller = new TaskController();
    public String worldId;
    public CoreProperties properties = new CoreProperties();
    public int gameRenderDistance = 7;
    public ChunkRenderController renderController = new ChunkRenderController();
    public final List<Runnable> queued = new ArrayList<>();

    public static JourneymapClient getInstance() { return INSTANCE; }
    public Boolean isMapping() { return this.mapping; }
    public boolean isMappingSuspended() { return this.suspended; }
    public boolean enabled() { return this.enabled; }
    public TaskController getMultithreadTaskController() { return this.controller; }
    public String getCurrentWorldId() { return this.worldId; }
    public CoreProperties getCoreProperties() { return this.properties; }
    public int getGameRenderDistance() { return this.gameRenderDistance; }
    public ChunkRenderController getChunkRenderController() { return this.renderController; }

    public void queueOneOff(Runnable runnable) throws Exception {
        if (this.controller != null) this.queued.add(runnable);
    }

    /** Runs (and clears) everything queued so far — the JM task thread, in-line. */
    public int runQueued() {
        var batch = new ArrayList<>(this.queued);
        this.queued.clear();
        batch.forEach(Runnable::run);
        return batch.size();
    }
}
