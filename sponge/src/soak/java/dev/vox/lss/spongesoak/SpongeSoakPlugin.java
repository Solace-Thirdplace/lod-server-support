package dev.vox.lss.spongesoak;

import com.google.inject.Inject;
import dev.vox.lss.benchmark.SpongeSoakScenarioDriver;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.api.Server;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.lifecycle.StartingEngineEvent;
import org.spongepowered.api.event.network.ServerSideConnectionEvent;
import org.spongepowered.plugin.builtin.jvm.Plugin;

/**
 * Dev-only Sponge entry for the soak harness (sponge-support-plan.md §5.4). Inert unless
 * {@code -Dlss.soak.scenario} names a timeline; never shipped (its own jar, outside
 * build/libs, and release_check forbids the package).
 */
@Plugin("lsssoak")
public class SpongeSoakPlugin {
    private final SpongeSoakScenarioDriver driver;

    @Inject
    public SpongeSoakPlugin() {
        String scenario = System.getProperty("lss.soak.scenario", "");
        this.driver = scenario.isEmpty() ? null : SpongeSoakScenarioDriver.init(scenario);
    }

    @Listener
    public void onStarting(StartingEngineEvent<Server> event) {
        if (driver != null) driver.onServerStarting((MinecraftServer) event.engine());
    }

    @Listener
    public void onJoin(ServerSideConnectionEvent.Join event) {
        if (driver != null) driver.onJoin(event.player().name());
    }
}
