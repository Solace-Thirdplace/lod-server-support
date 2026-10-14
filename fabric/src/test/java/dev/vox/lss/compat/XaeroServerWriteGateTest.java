package dev.vox.lss.compat;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Xaero bridge's server write gate (map-policy plan A4): the connection's
 * {@code serverAllowsWrites} supplier is composed ONLY on the write paths —
 * {@code offerColumn}, {@code pump}, {@code reportBackpressure} — never into
 * {@code enabled}, which also drives registration settling and the diag state.
 * While the server denies: offers drop unreported (no re-serve), the pump clears the
 * queue and the owed debt like the local-off branch while committed rebuilds still
 * flush, the report is -1 (a blocked map never throttles Voxy), and the diag reads
 * {@code state=blocked_by_server}. A mid-session flip works both ways. Separate from
 * {@code XaeroMapCompatTest} so the cave-layer rework there does not collide with it;
 * same stub rig ({@code fabric/src/test/java/xaero}).
 */
class XaeroServerWriteGateTest {

    private static final ResourceKey<Level> OVERWORLD =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("minecraft:overworld"));

    private MapProcessor processor;
    private final Object worldToken = new Object();
    private boolean serverAllows = true;
    private long clockMillis = 1_000_000L;
    private final List<Object[]> reports = new ArrayList<>();
    private final List<dev.vox.lss.api.VoxelColumnConsumer> registered = new ArrayList<>();
    private XaeroSession bridge;

    private final XaeroSession.LevelOps fakeLevelOps = new XaeroSession.LevelOps() {
        @Override
        public Object dimension(Object world) {
            return OVERWORLD;
        }

        @Override
        public boolean isChunkLoaded(Object world, int chunkX, int chunkZ) {
            return false;
        }
    };

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() throws Exception {
        XaeroStubEvents.clear();
        this.processor = new MapProcessor();
        this.processor.createdRegionLoadState = 2; // regions pre-loaded: plain commits
        this.processor.world = this.worldToken;
        this.processor.mainWorld = this.worldToken;
        this.processor.mapWorld.currentDimensionId = OVERWORLD;
        var session = new WorldMapSession();
        session.processor = this.processor;
        WorldMapSession.current = session;
        this.serverAllows = true;
        this.reports.clear();
        this.registered.clear();
        this.bridge = new XaeroSession(
                XaeroBindings.resolve(Class::forName),
                this.fakeLevelOps,
                () -> true,
                () -> true,
                this.registered::add,
                this.registered::remove,
                () -> true,
                (dimension, chunkX, chunkZ) ->
                        this.reports.add(new Object[]{dimension, chunkX, chunkZ}),
                () -> this.serverAllows);
        this.bridge.pumpNanosBudget = Long.MAX_VALUE;
        this.bridge.updateNanosBudget = Long.MAX_VALUE;
        this.bridge.bpClock = () -> this.clockMillis;
        this.bridge.maybeRegister();
    }

    @AfterEach
    void tearDown() {
        WorldMapSession.current = null;
        XaeroStubEvents.clear();
        XaeroSession.resetFacadeForTest();
    }

    @SuppressWarnings("unchecked")
    private XaeroTileExtractor.PreparedTile tile(int chunkX, int chunkZ) {
        var floor = new BlockState[256];
        var biome = (ResourceKey<Biome>[]) new ResourceKey[256];
        return new XaeroTileExtractor.PreparedTile(chunkX, chunkZ, -64,
                floor, new short[256], new short[256], biome, new byte[256],
                new boolean[256], new XaeroTileExtractor.OverlayRun[256][]);
    }

    private void offerColumn(int chunkX, int chunkZ) {
        this.bridge.offerColumn(OVERWORLD, chunkX, chunkZ, -64, 320,
                new dev.vox.lss.api.VoxelColumnData(
                        new dev.vox.lss.api.VoxelColumnData.SectionData[0], 1L));
    }

    @Test
    void aDeniedOfferDropsUnreportedAndAnAllowedOneQueues() {
        this.serverAllows = false;
        offerColumn(3, 3);
        assertEquals(0, this.bridge.queuedForTest(), "a server deny writes nothing");
        assertTrue(this.reports.isEmpty(), "…and reports nothing for re-serve");
        this.serverAllows = true;
        offerColumn(3, 3);
        assertEquals(1, this.bridge.queuedForTest(), "the gate reopens for terrain arriving now");
    }

    @Test
    void aMidSessionDenyClearsQueueAndDebtWithoutWritingThenAnAllowResumes() {
        this.bridge.offerPrepared(OVERWORLD, tile(100, 100));
        this.bridge.offerPrepared(OVERWORLD, tile(101, 100));
        assertEquals(2, this.bridge.queuedForTest());
        this.serverAllows = false;
        this.bridge.pump();
        assertEquals(0, this.bridge.queuedForTest(), "the local-off branch drops the backlog");
        assertEquals(0, this.bridge.counterForTest("written"), "nothing written while denied");
        assertEquals(0, this.bridge.owedForTest(), "and owes nothing");
        assertTrue(this.reports.isEmpty(), "a deny never un-stamps anything");

        this.serverAllows = true;
        this.bridge.offerPrepared(OVERWORLD, tile(100, 100));
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("written"), "an allow resumes writing");
    }

    @Test
    void committedRebuildsStillFlushWhileDenied() {
        this.bridge.updateIdlePumps = 1;
        this.bridge.offerPrepared(OVERWORLD, tile(64, 64));
        this.bridge.pump();
        assertEquals(1, this.bridge.counterForTest("pending_updates"), "premise: a rebuild is owed");
        this.serverAllows = false;
        this.bridge.pump();
        this.bridge.pump();
        assertTrue(XaeroStubEvents.snapshot().contains("tileChunk.updateBuffers 16,16"),
                "a rebuild owed to an already-written tile chunk still runs (CLAUDE.md: committed "
                        + "native rebuilds are preserved)");
        assertEquals(0, this.bridge.counterForTest("pending_updates"));
    }

    @Test
    void aBlockedMapNeverThrottlesVoxyAndTheDiagSaysWhy() {
        this.bridge.pump(); // the watchdog sees a live pump
        assertTrue(this.bridge.reportBackpressure() >= 0, "premise: governed while allowed");
        this.serverAllows = false;
        this.bridge.offerPrepared(OVERWORLD, tile(5, 5));
        assertEquals(-1, this.bridge.reportBackpressure(), "a blocked map reports no signal");
        String line = this.bridge.describe();
        assertTrue(line.startsWith("XaeroMap: state=blocked_by_server"), line);
        assertTrue(line.contains("bp=-1(blocked_by_server)"),
                "the token never contradicts the report, and names the server's block: " + line);
        XaeroSession.instance = this.bridge; // the exporter group (tearDown resets it)
        assertEquals(true, XaeroSession.metrics().get("server_blocked"));
        this.serverAllows = true;
        assertEquals(false, XaeroSession.metrics().get("server_blocked"));
    }

    @Test
    void anOfferThatRacesTheDenyIsDroppedUnderTheQueueLock() {
        // Extraction passed the consumer-side gate before the deny landed: the queue's own
        // under-lock re-check drops it (the pump's clear already ran — queued now, it
        // would be written once the gate reopened for unrelated terrain).
        this.serverAllows = false;
        this.bridge.offerPrepared(OVERWORLD, tile(7, 7));
        assertEquals(0, this.bridge.queuedForTest(), "a reliable tile is not queued into a blocked map");
        assertTrue(!this.bridge.offerFill(OVERWORLD, tile(8, 8)), "nor a fill tile");
        assertEquals(0, this.bridge.queuedForTest());
        assertTrue(this.reports.isEmpty(), "the drops are unreported");
        this.serverAllows = true;
        this.bridge.pump();
        assertEquals(0, this.bridge.counterForTest("written"), "nothing surfaces after the allow");
        assertTrue(this.bridge.offerFill(OVERWORLD, tile(8, 8)), "the gate reopens for fill too");
    }

    @Test
    void theGateIsNeverComposedIntoRegistration() {
        this.serverAllows = false;
        this.bridge.pump();
        this.bridge.onSessionEnd();
        this.bridge.pump();
        assertEquals(1, this.registered.size(),
                "a server deny is not a local disable: the consumer stays registered (a "
                        + "deregistration would advertise no LOD consumer at the next handshake)");
    }
}
