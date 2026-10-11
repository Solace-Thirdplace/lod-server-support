package journeymap.client;

import journeymap.client.cartography.ChunkRenderController;
import journeymap.client.properties.CoreProperties;
import journeymap.client.task.multi.TaskController;

/** Tier-1 stub of JourneyMap 6.0.0's client singleton (the 1.21.11 line): the same members as
 *  {@link JourneymapClient} EXCEPT {@code isMappingSuspended}, which 6.0.0 lacks. Delegates to
 *  {@link JourneymapClient#INSTANCE} so the session suite's helpers drive it unchanged. A test
 *  resolver hands this class out for {@code journeymap.client.JourneymapClient}. */
public class JourneymapClientWithoutSuspend {
    public static final JourneymapClientWithoutSuspend INSTANCE = new JourneymapClientWithoutSuspend();

    private static JourneymapClient d() { return JourneymapClient.INSTANCE; }

    public static JourneymapClientWithoutSuspend getInstance() { return d() == null ? null : INSTANCE; }
    public Boolean isMapping() { return d().isMapping(); }
    public boolean enabled() { return d().enabled(); }
    public TaskController getMultithreadTaskController() { return d().getMultithreadTaskController(); }
    public String getCurrentWorldId() { return d().getCurrentWorldId(); }
    public CoreProperties getCoreProperties() { return d().getCoreProperties(); }
    public int getGameRenderDistance() { return d().getGameRenderDistance(); }
    public ChunkRenderController getChunkRenderController() { return d().getChunkRenderController(); }
    public void queueOneOff(Runnable runnable) throws Exception { d().queueOneOff(runnable); }
}
