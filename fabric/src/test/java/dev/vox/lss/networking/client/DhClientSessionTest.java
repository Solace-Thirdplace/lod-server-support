package dev.vox.lss.networking.client;

import com.mojang.serialization.Lifecycle;
import dev.vox.lss.common.config.ClientSettings;
import dev.vox.lss.common.config.SettingsHandle;
import dev.vox.lss.common.config.SettingsSchema;
import dev.vox.lss.common.config.SettingsStore;
import dev.vox.lss.common.dh.*;
import dev.vox.lss.common.dh.DhAcquisition.Gate;
import dev.vox.lss.compat.DhVoxyBridge;
import dev.vox.lss.compat.ModCompat;
import dev.vox.lss.config.LSSClientConfig;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.VoxyInstance;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockMakers;
import org.objenesis.ObjenesisStd;
import java.lang.reflect.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Exercises the actual private session and its worker, transport leases and main-thread callbacks. */
class DhClientSessionTest {
    private static final DhPosition POSITION=new DhPosition(6,10,-10);
    private static final String DIMENSION="minecraft:overworld";
    private static final Class<?> SESSION=inner(DhClientSession.class,"Session");
    private static final Class<?> PENDING=inner(SESSION,"Pending");
    private static MappedRegistry<Biome> biomes;
    @TempDir Path dir;
    private TestMinecraft minecraft;
    private ClientLevel level;
    private ClientPacketListener connection;
    private Object previousMinecraft,previousHandle,previousSender,previousUnavailable,previousNativeAvailability,previousChannelAvailability,previousTransportReason;
    private ClassLoader previousContextLoader;
    private SettingsHandle<ClientSettings> settings;
    private final LinkedBlockingQueue<Runnable> mainTasks=new LinkedBlockingQueue<>();
    private final List<byte[]> sent=new ArrayList<>();
    private final List<Object> sessions=new ArrayList<>();
    private String cacheComponent;private Object previousCacheRoot;

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var source=VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME);
        biomes=new MappedRegistry<>(Registries.BIOME,Lifecycle.stable());
        source.listElements().forEach(h->biomes.register(h.key(),h.value(),RegistrationInfo.BUILT_IN));
        biomes.freeze();
    }

    @BeforeEach void setup() throws Exception {
        // Mockito resolves an explicit maker through TCCL. fabric-loader-junit
        // loads this test and Mockito in Knot while its thread starts with the
        // parent loader; that would produce two incompatible MockMaker types.
        previousContextLoader=Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        previousMinecraft=read(null,Minecraft.class,"instance");
        previousCacheRoot=read(null,ColumnCacheStore.class,"cacheDir");write(null,ColumnCacheStore.class,"cacheDir",dir.resolve("column-cache"));
        previousHandle=read(LSSClientConfig.CONFIG,LSSClientConfig.class,"handle");
        previousSender=read(null,DhClientSession.class,"sender");
        previousUnavailable=read(null,DhClientSession.class,"unavailable");
        previousTransportReason=read(null,DhClientSession.class,"transportReason");
        previousNativeAvailability=read(null,DhClientSession.class,"nativeAvailable");
        previousChannelAvailability=read(null,DhClientSession.class,"channelAvailable");
        DhClientSession.installChannelAvailability(()->true);
        ((Map<?,?>)read(null,DhClientSession.class,"confirmedProfiles")).clear();
        ((Map<?,?>)read(null,DhClientSession.class,"discoveredProfiles")).clear();
        write(null,DhClientSession.class,"initialPlay",true);
        write(null,DhClientSession.class,"transferredServer",null);write(null,DhClientSession.class,"trialProfile",DhProfile.OFFICIAL_16);
        ((Map<?,?>)read(null,DhClientSession.class,"retryCooldowns")).clear();
        write(null,DhClientSession.class,"joinConnection",null);
        DhClientSession.installNativeAvailability(()->false);
        write(null,DhClientSession.class,"retryJoinPending",false);
        write(null,DhClientSession.class,"retryUsed",false);
        // Allocate without Minecraft's graphics constructor. The original
        // getConnection reads LocalPlayer.connection; only execute and the
        // remote-server accessor are replaced with test-owned state.
        minecraft=new ObjenesisStd().newInstance(TestMinecraft.class);
        minecraft.mainTasks=mainTasks;
        write(minecraft,Minecraft.class,"gameDirectory",dir.toFile());
        level=mcMock(ClientLevel.class);connection=mcMock(ClientPacketListener.class);
        doReturn(mcMock(net.minecraft.network.Connection.class)).when(connection).getConnection();
        minecraft.level=level;
        minecraft.player=new ObjenesisStd().newInstance(LocalPlayer.class);
        write(minecraft.player,LocalPlayer.class,"connection",connection);
        doReturn(Level.OVERWORLD).when(level).dimension();
        doReturn(new RegistryAccess.ImmutableRegistryAccess(List.of(biomes)).freeze()).when(level).registryAccess();
        String address="test-dh-session-"+UUID.randomUUID()+":25565";
        var server=mcMock(ServerData.class);server.ip=address;
        minecraft.server=server;
        cacheComponent=CacheKeyAliases.addressComponent(address);
        write(null,Minecraft.class,"instance",minecraft);
        DhClientSession.disconnect();DestinationEngine.observedIngest=false;mainTasks.clear();
        var store=new SettingsStore<>(dir,"lss",SettingsSchema.client());
        settings=new SettingsHandle<>(store);
        store.saveDraft(store.read().hash(),Map.of("lod.source","distant_horizons"));
        settings.commit(settings.prepareReload());
        write(LSSClientConfig.CONFIG,LSSClientConfig.class,"handle",settings);
        DhClientSession.installTransport(bytes->sent.add(bytes.clone()),"test transport unavailable");
    }

    @AfterEach void cleanup() throws Exception {
        try {
            ClientTraceLog.stop();
            for(var s:sessions) invoke(s,"retire");
            DhClientSession.disconnect();
            mainTasks.clear();
            if(cacheComponent!=null) ColumnCacheStore.clearForServers(List.of(cacheComponent));
        } finally {
            if(settings!=null) settings.close();
            write(LSSClientConfig.CONFIG,LSSClientConfig.class,"handle",previousHandle);
            write(null,DhClientSession.class,"sender",previousSender);
            write(null,DhClientSession.class,"unavailable",previousUnavailable);
            write(null,DhClientSession.class,"transportReason",previousTransportReason);
            write(null,DhClientSession.class,"nativeAvailable",previousNativeAvailability);
            write(null,DhClientSession.class,"channelAvailable",previousChannelAvailability);
            ((Map<?,?>)read(null,DhClientSession.class,"confirmedProfiles")).clear();
            ((Map<?,?>)read(null,DhClientSession.class,"discoveredProfiles")).clear();
            write(null,DhClientSession.class,"retryJoinPending",false);
            write(null,DhClientSession.class,"retryUsed",false);
            write(null,Minecraft.class,"instance",previousMinecraft);
            write(null,ColumnCacheStore.class,"cacheDir",previousCacheRoot);
            Thread.currentThread().setContextClassLoader(previousContextLoader);
        }
    }

    private void setting(Map<String,Object> changes) throws Exception {
        settings.store().saveDraft(settings.store().read().hash(),changes);settings.commit(settings.prepareReload());
    }
    @Test void successfulLegacyConfirmationDoesNotSuppressAFreshTrialWithinTwoMinutes() throws Exception {
        var s=updateSession();invoke(null,DhClientSession.class,"rememberRetry",new Class<?>[]{String.class},minecraft.server.ip);
        write(s,SESSION,"profile",DhProfile.OFFICIAL_15);invoke(s,"confirmProfile");
        assertFalse(((Map<?,?>)read(null,DhClientSession.class,"retryCooldowns")).containsKey(minecraft.server.ip));
        connection=mcMock(ClientPacketListener.class);doReturn(mcMock(net.minecraft.network.Connection.class)).when(connection).getConnection();
        write(minecraft.player,LocalPlayer.class,"connection",connection);DhClientSession.onJoin();var next=session();
        assertEquals(DhProfile.OFFICIAL_16,read(next,SESSION,"profile"));
        assertEquals(true,invoke(null,DhClientSession.class,"probeLegacy",new Class<?>[]{SESSION},next));
    }
    @Test void configurationTransferUsesTheListenersServerWhenMinecraftHasNone() throws Exception {
        var server=minecraft.server;minecraft.server=null;
        DhClientSession.transferred(server);assertSame(server,read(null,DhClientSession.class,"transferredServer"));
        minecraft.server=server;DhClientSession.onJoin();assertEquals(false,read(null,DhClientSession.class,"initialPlay"));
        var s=session();assertEquals(false,invoke(null,DhClientSession.class,"probeLegacy",new Class<?>[]{SESSION},s));
    }
    @Test void cancelledOfficialGenerationTimeoutsDoNotExhaustUnsafeSyncPermits() throws Exception {
        var s=updateSession();long now=System.nanoTime();
        for(int i=1;i<=3;i++){pending(s,i,true,now-DhLimits.DEFAULT.lifetimeNanos()-1);invoke(s,"expireRequests",new Class<?>[]{long.class},now);}
        assertEquals(0,read(s,SESSION,"unanswered"));assertTrue(active(s));
        pending(s,4,false,now-DhLimits.DEFAULT.lifetimeNanos()-1);invoke(s,"expireRequests",new Class<?>[]{long.class},now);
        assertEquals(1,read(s,SESSION,"unanswered"));
        pending(s,5,false,now);capture(codec(s).encode(new DhMessage.Refusal(5,0,"busy")),now).receive();
        pumpUntil(()->frameCount(s)==0,"refusal did not settle");assertEquals(0,read(s,SESSION,"unanswered"));
    }
    @Test void answeredReplyStaysProtectedAfterFrameCloseBeforeItsClientCallback() throws Exception {
        var s=updateSession();long now=System.nanoTime();pending(s,7,true,now-3_000_000_000L);
        capture(codec(s).encode(new DhMessage.Response(7,null,List.of())),now).receive();waitWorker(s);
        assertEquals(0,frameCount(s));assertTrue(pendingMap(s).containsKey(7));
        invoke(s,"pruneGeneration",new Class<?>[]{long.class,ChunkPos.class},now,new ChunkPos(1000,1000));
        invoke(s,"expireRequests",new Class<?>[]{long.class},now+DhLimits.DEFAULT.lifetimeNanos()+1);
        assertTrue(sent.isEmpty());assertEquals(0,read(s,SESSION,"unanswered"));assertTrue(pendingMap(s).containsKey(7));
        pumpUntil(()->pendingMap(s).isEmpty(),"client reply bookkeeping did not settle");
    }
    @Test void pressureRecoveryCancelsRegistrationQueuedAfterItsInitialDiscard() throws Exception {
        var s=updateSession();pending(s,7,false,System.nanoTime());
        var pressure=(java.util.concurrent.atomic.AtomicBoolean)read(s,SESSION,"pushRecovery");pressure.set(true);
        worker(s).submit(()->invokeUnchecked(s,"recoverPushPressure")).get(5,TimeUnit.SECONDS);
        // The queued registration observes the old pending owner before its main-thread clear.
        worker(s).submit(()->{try{assembly(s).expect(8,POSITION);}catch(Exception e){throw new AssertionError(e);}}).get(5,TimeUnit.SECONDS);
        var ctor=PENDING.getDeclaredConstructor(DhPosition.class,boolean.class,long.class,long.class);ctor.setAccessible(true);
        pendingMap(s).put(8,ctor.newInstance(POSITION,false,System.nanoTime(),0L));
        pumpUntil(()->!pressure.get(),"registration cleanup did not finish");waitWorker(s);
        assertTrue(pendingMap(s).isEmpty());assertTrue(((Map<?,?>)read(assembly(s),DhAssembly.class,"requests")).isEmpty());assertEquals(0,budget(s).used());
    }
    @Test void parkedTilesDoNotMaskTheAdmissionGateAndTerminalReasonsSurviveCadence() throws Exception {
        try(var bridge=new ReadyBridge()) {
            var s=updateSession();long now=System.nanoTime();setting(Map.of("lod.download.max_columns_per_second",20));
            write(s,SESSION,"lastRequest",now);((AtomicLong)read(s,SESSION,"requested")).set(1);
            policy(s).absent(new DhPosition(6,0,0),now,false);
            invoke(s,"requestNext",new Class<?>[]{long.class,ChunkPos.class},now,new ChunkPos(2,2));
            var d=DhClientSession.diagnostics();assertEquals(Gate.MANUAL_RATE,d.acquisitionWait());
            assertTrue(d.acquisitionWaitMillis()>0);assertTrue((d.acquisitionGates()&(1L<<Gate.PARKED.ordinal()))!=0);
            setting(Map.of("lod.download.max_columns_per_second",0,"lod.distance_chunks",4));
            var tile=new DhPosition(6,0,0);policy(s).invalidate(tile);policy(s).defer(tile,now);
            for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)if(x<0||x>3||z<0||z>3)policy(s).vanilla(x,z);
            invoke(s,"requestNext",new Class<?>[]{long.class,ChunkPos.class},now,new ChunkPos(2,2));
            assertEquals(Gate.DEFERRED,DhClientSession.diagnostics().acquisitionWait());
            write(s,SESSION,"nextScanAt",now+500_000_000L);write(s,SESSION,"announced",true);write(s,SESSION,"maintenanceAt",now);
            minecraft.player=mcMock(LocalPlayer.class);write(minecraft.player,LocalPlayer.class,"connection",connection);
            doReturn(new ChunkPos(2,2)).when(minecraft.player).chunkPosition();
            invoke(s,"tick");var status=DhClientSession.diagnostics();assertEquals(Gate.DEFERRED,status.acquisitionWait());
            assertTrue((status.acquisitionGates()&(1L<<Gate.SCAN_CADENCE.ordinal()))!=0);
            assertTrue(status.acquisitionWaitMillis()>0 && status.acquisitionWaitMillis()<=2000);
        }
    }
    @Test void unconfirmedResetWithDhPermissionAndNoServicePreservesTheCacheAndSession() throws Exception {
        var cache=new DhTerrainCache(dir,new DhTerrainCache.Key("test","server","world",DIMENSION,8,DhProfile.OFFICIAL_16,
                new DhTerrainTile.WorldShape(-64,384,true)));
        cache.write(POSITION,123,new byte[]{1});var s=session();write(s,SESSION,"terrainCache",cache);
        assertFalse(DhClientSession.canAcquire());var feedback=new ArrayList<net.minecraft.network.chat.Component>();
        ClientCommandActions.runReset(feedback::add,false,false);
        assertTrue(active(s));assertEquals(Set.of(POSITION),cache.positions());
        assertTrue(feedback.getFirst().getString().contains("reset confirm"));
    }
    @Test void resetCapabilityRequiresLiveOwnedServiceAndDistinguishesPushOnly() throws Exception {
        var s=updateSession();assertTrue(DhClientSession.canAcquire());
        setting(Map.of("lod.dh.sync_on_load",false));assertFalse(DhClientSession.canAcquire());
        setting(Map.of("lod.dh.sync_on_load",true));
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,true,32,false,0,0,500));
        assertFalse(DhClientSession.canAcquire());
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.SURFACE_ONLY,32,0,0,0,1,false,0,false,0,0,500));
        assertTrue(DhClientSession.canAcquire());
        setting(Map.of("lod.receive",false));assertFalse(DhClientSession.canAcquire());
        setting(Map.of("lod.receive",true));write(s,SESSION,"familyConfirmed",false);assertFalse(DhClientSession.canAcquire());
        write(s,SESSION,"familyConfirmed",true);DhClientSession.installChannelAvailability(()->false);assertFalse(DhClientSession.canAcquire());
    }
    @Test void receiveOffWithdrawsLiveUpdatesOnceWithoutChangingSavedPreferences() throws Exception {
        var s=updateSession();write(s,SESSION,"announced",true);setting(Map.of("lod.receive",false));
        DhClientSession.tick();DhClientSession.tick();
        var cfg=sent.stream().map(b->{try{return codec(s).decode(b);}catch(Exception e){throw new AssertionError(e);}})
                .filter(DhMessage.SessionConfig.class::isInstance).map(DhMessage.SessionConfig.class::cast).toList();
        assertEquals(1,cfg.size());assertFalse(cfg.getFirst().updates());assertFalse(cfg.getFirst().synchronize());
        assertTrue(settings.state().configured().lod().dh().realTimeUpdates());assertFalse(active(s));
    }
    @Test void nativeTakeoverWithdrawsUpdatesButReplacedListenerAndReconfigurationDoNotSendOldControls() throws Exception {
        var s=updateSession();write(s,SESSION,"announced",true);DhClientSession.nativeOffer();
        assertEquals(1,sent.size());assertFalse(((DhMessage.SessionConfig)codec(s).decode(sent.getFirst())).updates());
        DhClientSession.disconnect();sent.clear();
        var replaced=updateSession();write(replaced,SESSION,"announced",true);
        write(minecraft.player,LocalPlayer.class,"connection",mcMock(ClientPacketListener.class));invoke(replaced,"beginRetire");
        assertTrue(sent.isEmpty());write(minecraft.player,LocalPlayer.class,"connection",connection);
        var reconfigured=updateSession();write(reconfigured,SESSION,"announced",true);DhClientSession.onJoin();invoke(reconfigured,"retire");assertTrue(sent.isEmpty());
    }
    @Test void unconfirmedLegacyRetirementCannotSendAmbiguousPreferences() throws Exception {
        var s=updateSession();write(s,SESSION,"announced",true);write(s,SESSION,"familyConfirmed",false);
        invoke(s,"beginRetire");assertTrue(sent.isEmpty());
    }
    @Test void unrelatedPostDeadlineFramesDoNotRetainSilentRequestsAndBuffers() throws Exception {
        var s=updateSession();long now=System.nanoTime();
        pending(s,700,false,now-61_000_000_000L);
        assembly(s).accept(new DhMessage.Split(90,new byte[]{1,2,3},true),now-61_000_000_000L);
        var unrelated=codec(s).encode(new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,false,0,true,32,1,500));
        // Keep capture ownership nonempty while unrelated work continues to drain.
        try(var held=capture(unrelated,now)) {
            for(int i=0;i<3;i++) {
                capture(unrelated,now+i).receive();waitWorker(s);
                invoke(s,"expireRequests",new Class<?>[]{long.class},now+i);
                ((ThreadPoolExecutor)read(s,SESSION,"worker")).submit(()->invokeUnchecked(s,"expireAssembly")).get(10,TimeUnit.SECONDS);
            }
            assertTrue(pendingMap(s).isEmpty(),"unrelated captured traffic retained a silent sync slot");
            assertEquals(0,assembly(s).incompleteBuffers(),"unrelated traffic retained expired fragments");
            assertEquals(1,read(s,SESSION,"unanswered"));
            assertEquals(unrelated.length+256,budget(s).used(),"only the held control frame remains");
        }
        assertEquals(0,budget(s).used());assertTrue(active(s));
    }
    @Test void timelyCaptureIncludingProcessingProtectsOnlyUntilItDrains() throws Exception {
        var s=updateSession();long now=System.nanoTime();pending(s,700,false,now-59_000_000_000L);
        try(var frame=capture(codec(s).encode(new DhMessage.Response(700,null,List.of())),now)) {
            invoke(s,"expireRequests",new Class<?>[]{long.class},now+2_000_000_000L);
            assertEquals(1,pendingMap(s).size());
            write(frame,DhClientSession.Inbound.class,"processing",true);
            assertEquals(now,invoke(s,"arrivalCutoff",new Class<?>[]{long.class},now+2_000_000_000L));
            frame.receive();pumpUntil(()->frameCount(s)==0,"timely queued reply did not drain");
            assertTrue(pendingMap(s).isEmpty());assertEquals(0,read(s,SESSION,"unanswered"));
        }
        assertEquals(0,budget(s).used());
    }
    @Test void newCaptureCannotPublishAnOlderArrivalAfterCleanup() throws Exception {
        var s=updateSession();var frames=read(s,SESSION,"frames");long cutoff;
        var attempted=new CountDownLatch(1);FutureTask<DhClientSession.Inbound> task=new FutureTask<>(()-> {
            attempted.countDown();var buffer=new FriendlyByteBuf(Unpooled.wrappedBuffer(new byte[]{0}));
            try {return DhClientSession.capture(buffer);}finally {buffer.release();}
        });
        Thread capturer=new Thread(task,"test-capture-race");
        synchronized(frames) {
            capturer.start();assertTrue(attempted.await(5,TimeUnit.SECONDS));cutoff=System.nanoTime();
            invoke(s,"expireRequests",new Class<?>[]{long.class},cutoff);
        }
        try(var frame=task.get(5,TimeUnit.SECONDS)) {
            assertNotNull(frame);assertTrue((long)read(frame,DhClientSession.Inbound.class,"arrivalNanos")-cutoff>=0);
        }finally {capturer.join(5000);}
        assertEquals(0,budget(s).used());
    }
    @Test void admissionReportsManualRateRefusalServiceSlotsAndDisabledServices() throws Exception {
        var s=updateSession();long now=System.nanoTime();setting(Map.of("lod.download.max_columns_per_second",20));
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.SURFACE_ONLY,32,0,0,0,20,false,0,true,32,50,500));
        write(s,SESSION,"lastRequest",now);((AtomicLong)read(s,SESSION,"requested")).set(1);
        write(s,SESSION,"syncBackoffUntil",now+1_000_000_000L);
        var wait=admission(s,false,now);
        assertEquals(Gate.REFUSAL_BACKOFF,wait.primary());assertEquals(1000,wait.waitMillis(0));
        assertTrue((wait.gates()&(1L<<Gate.MANUAL_RATE.ordinal()))!=0);
        assertEquals(Gate.MANUAL_RATE,admission(s,true,now).primary());
        pending(s,700,true,now);assertEquals(Gate.GENERATION_SLOT,admission(s,true,now).primary());
        pendingMap(s).clear();assembly(s).cancel(700);
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,false,0,true,32,1,500));
        pending(s,701,false,now);assertEquals(Gate.SYNC_SLOTS,admission(s,false,now).primary());
        assertEquals(-1,admission(s,false,now).waitMillis(0));assertEquals(Gate.SERVER_DISABLED,admission(s,true,now).primary());
    }
    @Test void scanObservationDistinguishesIdleDisabledParkedCacheAndAdmittedWork() throws Exception {
        try(var bridge=new ReadyBridge()) {
        var s=updateSession();long now=System.nanoTime();var player=new ChunkPos(2,2);
        // Make exactly one tile eligible, so no unrelated frontier affects the result.
        setting(Map.of("lod.distance_chunks",4));
        for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)if(x<0||x>3||z<0||z>3)policy(s).vanilla(x,z);
        var p=new DhPosition(6,0,0);policy(s).accepted(p,1,1,10,now);
        invoke(s,"requestNext",new Class<?>[]{long.class,ChunkPos.class},now,player);
        assertEquals(Gate.NO_ELIGIBLE_WORK,DhClientSession.diagnostics().acquisitionWait());
        policy(s).invalidate(p);write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,false,0,false,0,0,500));
        invoke(s,"requestNext",new Class<?>[]{long.class,ChunkPos.class},now,player);
        assertEquals(Gate.SERVER_DISABLED,DhClientSession.diagnostics().acquisitionWait());
        policy(s).rangeRefused(p,0,false);
        invoke(s,"requestNext",new Class<?>[]{long.class,ChunkPos.class},now,player);
        assertEquals(Gate.PARKED,DhClientSession.diagnostics().acquisitionWait());
        policy(s).invalidate(p);write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,false,0,true,4,20,500));
        write(s,SESSION,"cacheLoading",p);invoke(s,"requestNext",new Class<?>[]{long.class,ChunkPos.class},now,player);
        assertEquals(Gate.CACHE,DhClientSession.diagnostics().acquisitionWait());
        write(s,SESSION,"cacheLoading",null);invoke(s,"requestNext",new Class<?>[]{long.class,ChunkPos.class},now,player);
        assertEquals(Gate.REQUESTED,DhClientSession.diagnostics().acquisitionWait());assertEquals(0,DhClientSession.diagnostics().acquisitionGates());
        waitWorker(s);
        }
    }
    private static DhAcquisition admission(Object s,boolean generation,long now) throws Exception {
        return (DhAcquisition)invoke(s,"requestAdmission",new Class<?>[]{boolean.class,long.class},generation,now);
    }
    @Test void tickObservationReportsCacheRecoveryPressureSlotsAndMissingPlayer() throws Exception {
        try(var bridge=new ReadyBridge()) {
            var s=updateSession();write(s,SESSION,"announced",true);
            write(s,SESSION,"maintenanceAt",System.nanoTime());write(s,SESSION,"cacheWorkStarted",System.nanoTime());
            write(s,SESSION,"cacheReady",false);invoke(s,"tick");assertEquals(Gate.CACHE,DhClientSession.diagnostics().acquisitionWait());
            write(s,SESSION,"cacheReady",true);((java.util.concurrent.atomic.AtomicBoolean)read(s,SESSION,"pushRecovery")).set(true);
            invoke(s,"tick");assertEquals(Gate.RECOVERY,DhClientSession.diagnostics().acquisitionWait());
            ((java.util.concurrent.atomic.AtomicBoolean)read(s,SESSION,"pushRecovery")).set(false);
            me.cortex.voxy.common.world.service.VoxelIngestService.taskCount=257;
            invoke(s,"tick");assertEquals(Gate.INGEST_PRESSURE,DhClientSession.diagnostics().acquisitionWait());
            me.cortex.voxy.common.world.service.VoxelIngestService.taskCount=0;
            var release=new CountDownLatch(1);var started=new CountDownLatch(1);
            worker(s).execute(()-> {started.countDown();try {release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e) {Thread.currentThread().interrupt();}});
            assertTrue(started.await(5,TimeUnit.SECONDS));
            try {
                for(int i=0;i<5;i++)worker(s).execute(()->{});
                invoke(s,"tick");assertEquals(Gate.DECODE_PRESSURE,DhClientSession.diagnostics().acquisitionWait());
            }finally {release.countDown();}
            waitWorker(s);
            pending(s,700,false,System.nanoTime());pending(s,701,true,System.nanoTime());
            invoke(s,"tick");assertEquals(Gate.REQUEST_SLOTS,DhClientSession.diagnostics().acquisitionWait());
            assertEquals(-1,DhClientSession.diagnostics().acquisitionWaitMillis());
            pendingMap(s).clear();assembly(s).cancel(700);assembly(s).cancel(701);
            minecraft.player=null;invoke(s,"tick");assertEquals(Gate.NO_PLAYER,DhClientSession.diagnostics().acquisitionWait());
        }
    }
    @Test void scanCadenceDoesNotHideAStillBlockingManualRate() throws Exception {
        try(var bridge=new ReadyBridge()) {
            var s=updateSession();long now=System.nanoTime();setting(Map.of("lod.download.max_columns_per_second",20));
            write(s,SESSION,"lastRequest",now);((AtomicLong)read(s,SESSION,"requested")).set(1);
            invoke(s,"requestNext",new Class<?>[]{long.class,ChunkPos.class},now,new ChunkPos(2,2));
            assertEquals(Gate.MANUAL_RATE,DhClientSession.diagnostics().acquisitionWait());
            write(s,SESSION,"announced",true);write(s,SESSION,"maintenanceAt",System.nanoTime());
            write(s,SESSION,"nextScanAt",now+500_000_000L);invoke(s,"tick");
            var status=DhClientSession.diagnostics();assertEquals(Gate.MANUAL_RATE,status.acquisitionWait());
            assertTrue((status.acquisitionGates()&(1L<<Gate.SCAN_CADENCE.ordinal()))!=0);
            assertTrue(status.acquisitionWaitMillis()>0);assertTrue(status.acquisitionWaitMillis()<=800);
        }
    }
    private static void invokeUnchecked(Object s,String method) {
        try {invoke(s,method);}catch(Exception e) {throw new AssertionError(e);}
    }
    @Test void manualColumnCapIsSharedByBothServicesAndConditionalChecks() throws Exception {
        var s=updateSession();setting(Map.of("lod.download.max_columns_per_second",20));long now=System.nanoTime();
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.SURFACE_ONLY,32,0,0,0,20,false,0,true,32,50,500));
        write(s,SESSION,"lastRequest",now);((java.util.concurrent.atomic.AtomicLong)read(s,SESSION,"requested")).set(1);
        for(boolean generation:new boolean[]{false,true}) {
            assertEquals(1_000_000L,invoke(s,"requestWaitNanos",new Class<?>[]{boolean.class,long.class},generation,now+799_000_000L));
            assertEquals(0L,invoke(s,"requestWaitNanos",new Class<?>[]{boolean.class,long.class},generation,now+800_000_000L));
        }
        write(s,SESSION,"lastRequest",now+800_000_000L);
        assertEquals(800_000_000L,invoke(s,"requestWaitNanos",new Class<?>[]{boolean.class,long.class},false,now+800_000_000L));
    }
    @Test void syncConcurrencyAtRateOneKeepsTheIndependentGenerationSlotAvailable() throws Exception {
        var s=updateSession();long now=System.nanoTime();
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.SURFACE_ONLY,32,0,0,0,1,false,0,true,32,1,500));
        pending(s,700,false,now-2_000_000_000L);
        assertEquals(Long.MAX_VALUE,invoke(s,"requestWaitNanos",new Class<?>[]{boolean.class,long.class},false,now));
        assertEquals(0L,invoke(s,"requestWaitNanos",new Class<?>[]{boolean.class,long.class},true,now));
        pendingMap(s).clear();assertEquals(0L,invoke(s,"requestWaitNanos",new Class<?>[]{boolean.class,long.class},false,now));
    }
    @Test void rateRefusalPausesItsWholeServiceButNotGenerationOrPermanentRetryBudget() throws Exception {
        var s=updateSession();write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.SURFACE_ONLY,32,0,0,0,20,false,0,true,32,50,500));
        long now=System.nanoTime();pending(s,700,false,now);
        capture(codec(s).encode(new DhMessage.Refusal(700,0,"limited")),now).receive();
        pumpUntil(()->frameCount(s)==0&&pendingMap(s).isEmpty(),"refusal did not finish");now=System.nanoTime();
        assertTrue((long)invoke(s,"requestWaitNanos",new Class<?>[]{boolean.class,long.class},false,now)>0);
        assertEquals(0L,invoke(s,"requestWaitNanos",new Class<?>[]{boolean.class,long.class},true,now));
        assertEquals(0L,invoke(s,"requestWaitNanos",new Class<?>[]{boolean.class,long.class},false,now+2_000_000_000L));
        assertFalse(policy(s).retryExhausted(POSITION));
    }
    @Test void delayedRangeRefusalUsesTheRequestContextAndRetriesAtTheChangedReachableFrontier() throws Exception {
        var s=updateSession();long now=System.nanoTime();var position=new DhPosition(6,0,0);
        invoke(s,"request",new Class<?>[]{DhPosition.class,int.class,boolean.class,long.class},position,0,false,now);
        waitWorker(s);Runnable task;while((task=mainTasks.poll())!=null)task.run();var request=(DhMessage.Request)codec(s).decode(sent.getLast());
        write(s,SESSION,"rangeEpoch",1L);capture(codec(s).encode(new DhMessage.Refusal(request.requestId(),1,"out of range")),now).receive();
        pumpUntil(()->frameCount(s)==0&&pendingMap(s).isEmpty(),"range refusal did not finish");assertFalse(policy(s).retryExhausted(position));
        assertFalse(policy(s).due(position,Long.MAX_VALUE));sent.clear();
        assertNotNull(scan(s,now+3_000_000_000L));assertTrue(policy(s).due(position,now+3_000_000_000L));
    }
    @Test void rangeContextEpochsCannotCollideAcrossRetainedPolicySessions() throws Exception {
        var first=updateSession();long now=System.nanoTime();scan(first,now);long old=(long)read(first,SESSION,"rangeEpoch");
        var position=new DhPosition(6,0,0);policy(first).rangeRefused(position,old,false);invoke(first,"retire");sent.clear();
        var replacement=updateSession();assertSame(policy(first),policy(replacement));
        write(replacement,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,false,0,true,64,50,500));
        scan(replacement,now+3_000_000_000L);assertNotEquals(old,read(replacement,SESSION,"rangeEpoch"));
        assertTrue(policy(replacement).due(position,now+3_000_000_000L));
    }
    @Test void movementCancelsOnlyObsoleteOfficialGenerationAndLateFramesRemainContained() throws Exception {
        var s=updateSession();long now=System.nanoTime();pending(s,700,true,now-3_000_000_000L);pendingAt(s,701,new DhPosition(6,0,0),0);
        assembly(s).accept(new DhMessage.Split(90,new byte[]{1},true));
        invoke(s,"pruneGeneration",new Class<?>[]{long.class,ChunkPos.class},now,new ChunkPos(1000,1000));waitWorker(s);
        assertEquals(Set.of(701),pendingMap(s).keySet());assertEquals(1,sent.size());
        assertEquals(new DhMessage.Cancel(700),codec(s).decode(sent.getFirst()));assertFalse(policy(s).retryExhausted(POSITION));
        capture(codec(s).encode(new DhMessage.Response(700,90,List.of())),now).receive();
        pumpUntil(()->frameCount(s)==0,"obsolete response did not settle");assertTrue(active(s));assertEquals(0,budget(s).used());
        assertEquals(Set.of(701),pendingMap(s).keySet());
    }
    @Test void movementPruningPreservesAlreadyCapturedRepliesAndTheirLeases() throws Exception {
        var s=updateSession();long now=System.nanoTime();pending(s,700,true,now-3_000_000_000L);
        try(var frame=capture(codec(s).encode(new DhMessage.Response(700,null,List.of())),now)) {
            invoke(s,"pruneGeneration",new Class<?>[]{long.class,ChunkPos.class},now,new ChunkPos(1000,1000));
            assertTrue(sent.isEmpty());assertTrue(pendingMap(s).containsKey(700));assertTrue(budget(s).used()>0);
        }
        assertEquals(0,budget(s).used());
    }
    @Test void nearbyOrNewGenerationAndSupportGenerationAreNotMovementCancelled() throws Exception {
        var s=updateSession();long now=System.nanoTime();pending(s,700,true,now-3_000_000_000L);
        invoke(s,"pruneGeneration",new Class<?>[]{long.class,ChunkPos.class},now,new ChunkPos(42,-38));assertTrue(sent.isEmpty());
        pendingMap(s).clear();pending(s,701,true,now-1_000_000_000L);
        invoke(s,"pruneGeneration",new Class<?>[]{long.class,ChunkPos.class},now,new ChunkPos(1000,1000));assertTrue(sent.isEmpty());
        write(s,SESSION,"profile",DhProfile.SUPPORT_15);pendingMap(s).clear();pending(s,702,true,now-3_000_000_000L);
        invoke(s,"pruneGeneration",new Class<?>[]{long.class,ChunkPos.class},now,new ChunkPos(1000,1000));assertTrue(sent.isEmpty());
        write(s,SESSION,"profile",DhProfile.OFFICIAL_16);
    }
    @Test void conflictReasonIsTypedAndDoesNotExportRawTransportDetails() throws Exception {
        DhClientSession.installTransport(null,"private-path secret-value",dev.vox.lss.common.diagnostics.DhStatusSnapshot.Reason.CLIENT_MOD_CONFLICT);
        var d=DhClientSession.diagnostics();assertEquals(dev.vox.lss.common.diagnostics.DhStatusSnapshot.Reason.CLIENT_MOD_CONFLICT,d.reason());
        assertEquals(dev.vox.lss.common.diagnostics.DhStatusSnapshot.State.NEED_TRANSPORT,d.state());
        var json=new com.google.gson.Gson().toJson(d);assertTrue(json.contains("CLIENT_MOD_CONFLICT"));assertFalse(json.contains("secret-value"));
    }
    @Test void dhSlowStartUsesWirePreferencesAndSurvivesSessionReplacement() throws Exception {
        var s=updateSession();invoke(s,"sendPreferences");assertEquals(50,((DhMessage.SessionConfig)codec(s).decode(sent.getLast())).bandwidthKB());
        var ramp=(DhBandwidthRamp)read(s,SESSION,"bandwidthRamp");ramp.received(50_000,0);ramp.received(1,1_000_000_000L);
        assertEquals(100,invoke(s,"bandwidthLimit"));invoke(s,"retire");var next=updateSession();
        assertSame(ramp,read(next,SESSION,"bandwidthRamp"));assertEquals(100,invoke(next,"bandwidthLimit"));
        setting(Map.of("lod.download.slow_start_on_join",false));invoke(next,"sendPreferences");
        assertEquals(500,((DhMessage.SessionConfig)codec(next).decode(sent.getLast())).bandwidthKB());
        setting(Map.of("lod.download.slow_start_on_join",true));assertEquals(500,invoke(next,"bandwidthLimit"));
        assertEquals(60_000_000_000L,invoke(next,"requestLifetime"));
        assertEquals(120_000_000_000L,((DhLimits)read(next,SESSION,"sessionLimits")).lifetimeNanos());
    }
    @Test void supportUsesLocalJoinPacingAndOffOpensItImmediately() throws Exception {
        try(var bridge=new ReadyBridge()) {
        var s=updateSession();write(s,SESSION,"profile",DhProfile.SUPPORT_15);long now=System.nanoTime();
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.CHUNKS_ONLY,32,0,0,0,20,false,0,false,0,0,0));
        write(s,SESSION,"lastGenerationRequest",now);
        assertEquals(4_000_000_000L,invoke(s,"requestWaitNanos",new Class<?>[]{boolean.class,long.class},true,now));
        assertEquals(Gate.SUPPORT_STARTUP,admission(s,true,now).primary());
        assertEquals(60_000_000_000L,invoke(s,"requestLifetime"));
        assertEquals(0L,invoke(s,"requestWaitNanos",new Class<?>[]{boolean.class,long.class},true,now+5_000_000_000L));
        setting(Map.of("lod.download.slow_start_on_join",false));
        assertEquals(1_000_000_000L,invoke(s,"requestWaitNanos",new Class<?>[]{boolean.class,long.class},true,now));
        write(s,SESSION,"profile",DhProfile.OFFICIAL_16);
        }
    }
    @Test void dhBandwidthOnlyPreferenceChangesDoNotRestartLoadSynchronization() throws Exception {
        var s=updateSession();write(s,SESSION,"announced",true);invoke(s,"sendPreferences");sent.clear();
        var marker=Set.of(POSITION);write(s,SESSION,"visible",marker);write(s,SESSION,"visibleCenter",new ChunkPos(2,2));
        ((Map<DhPosition,Long>)read(s,SESSION,"loadedAt")).put(POSITION,System.nanoTime());
        var ramp=(DhBandwidthRamp)read(s,SESSION,"bandwidthRamp");ramp.received(50_000,0);ramp.received(1,1_000_000_000L);
        invoke(s,"tick");assertFalse(sent.isEmpty());assertSame(marker,read(s,SESSION,"visible"));
        assertEquals(1,((Map<?,?>)read(s,SESSION,"loadedAt")).size());
    }
    @Test void changingLiveUpdatesAlonePreservesLoadDebounceAndHeldReceipts() throws Exception {
        var s=updateSession();write(s,SESSION,"announced",true);invoke(s,"sendPreferences");
        var marker=Set.of(POSITION);write(s,SESSION,"visible",marker);write(s,SESSION,"visibleCenter",new ChunkPos(2,2));
        ((Map<DhPosition,Long>)read(s,SESSION,"loadedAt")).put(POSITION,System.nanoTime());policy(s).accepted(POSITION,1,1,123,0);
        setting(Map.of("lod.dh.real_time_updates",false));invoke(s,"tick");
        assertSame(marker,read(s,SESSION,"visible"));assertFalse(policy(s).due(POSITION,Long.MAX_VALUE));
        assertEquals(1,((Map<?,?>)read(s,SESSION,"loadedAt")).size());
    }
    @Test void dhNetTraceQueueBytesExcludeOtherOwnedDecodeAndCacheMemory() throws Exception {
        var s=updateSession();byte[] bytes=codec(s).encode(new DhMessage.Response(700,null,List.of()));
        try(var extra=budget(s).reserve(1_048_576);var frame=capture(bytes,System.nanoTime())) {
            var trace=ClientTraceLog.toggle();DhClientSession.tick();ClientTraceLog.stop();
            var row=Files.readAllLines(trace.path()).stream().map(line->com.google.gson.JsonParser.parseString(line).getAsJsonObject())
                    .filter(r->r.get("t").getAsString().equals("net")).findFirst().orElseThrow();
            assertEquals(bytes.length,row.get("qb").getAsLong());
        }
    }
    @Test void dhNetTraceUsesItsOwnCountersAndResetsAcrossSourceAndWorldChanges() throws Exception {
        var s=updateSession();var trace=ClientTraceLog.toggle();DhClientSession.tick();
        assertEquals(ClientNetTrace.Source.DH,read(null,ClientNetTrace.class,"lastSource"));
        write(null,ClientNetTrace.class,"nextEmitMs",0L);write(null,ClientNetTrace.class,"lastSampleMs",System.currentTimeMillis()-1000);
        ClientNetTrace.maybeEmit(minecraft,minecraft.player,level,8,1,200,3,4,-1,-1,-1,-1,ClientNetTrace.Source.DH,s,5000,-1);
        ClientNetTrace.maybeEmit(minecraft,minecraft.player,level,8,1,200,3,4,0,0,0,0,ClientNetTrace.Source.NATIVE_LSS,connection,9000,30000);
        ClientTraceLog.stop();assertNull(read(null,ClientNetTrace.class,"lastOwner"));assertNull(read(null,ClientNetTrace.class,"lastLevel"));
        var rows=Files.readAllLines(trace.path()).stream().map(line->com.google.gson.JsonParser.parseString(line).getAsJsonObject())
                .filter(r->r.get("t").getAsString().equals("net")).toList();assertEquals(3,rows.size());
        assertEquals(-1,rows.getFirst().get("wire_bps").getAsLong());assertTrue(rows.get(1).get("wire_bps").getAsLong()>0);
        assertEquals(-1,rows.get(1).get("raw_bps").getAsLong());assertEquals(-1,rows.get(1).get("gov").getAsLong());
        assertEquals(-1,rows.getLast().get("wire_bps").getAsLong());
        for(String key:List.of("ping","dcpt","runway","runway_s","miss_view","loaded","fps","spd"))assertTrue(rows.getFirst().has(key));
        assertEquals(-1,ClientNetTrace.rate(-1,0,1000));assertEquals(-1,ClientNetTrace.rate(2,4,1000));
        verify(connection,times(3)).send(any(net.minecraft.network.protocol.Packet.class));
        ClientNetTrace.maybeEmit(minecraft,minecraft.player,level,8,0,0,0,0,0,0,0,0,ClientNetTrace.Source.DH,s,20000,-1);
        verify(connection,times(3)).send(any(net.minecraft.network.protocol.Packet.class));
    }

    @Test void traceStartsWithAFlushedRecordEvenWhenNoTerrainEventsArrive() throws Exception {
        var toggle=ClientTraceLog.toggle();assertNotNull(toggle.path());assertFalse(toggle.failed());
        var rows=Files.readAllLines(toggle.path());assertEquals(1,rows.size());
        var row=com.google.gson.JsonParser.parseString(rows.getFirst()).getAsJsonObject();
        assertEquals("trace_start",row.get("t").getAsString());assertEquals(0,row.get("ms").getAsLong());
        ClientTraceLog.stop();assertFalse(ClientTraceLog.enabled());
    }
    @Test void dhTraceSamplesWhileWaitingWithoutCreatingTheNativeRequestManager() throws Exception {
        var s=session();var trace=ClientTraceLog.toggle();
        DhClientSession.tick();ClientTraceLog.stop();
        var rows=Files.readAllLines(trace.path()).stream().map(line->com.google.gson.JsonParser.parseString(line).getAsJsonObject()).toList();
        var snapshot=rows.stream().filter(r->r.get("t").getAsString().equals("dh_status")).findFirst().orElseThrow();
        assertEquals("WAIT_CONFIG",snapshot.get("state").getAsString());assertEquals(0,snapshot.get("inflight").getAsInt());
        assertEquals("CONFIG",snapshot.get("acquisition_wait").getAsString());assertEquals(-1,snapshot.get("acquisition_wait_ms").getAsLong());
        assertEquals(1L<<Gate.CONFIG.ordinal(),snapshot.get("acquisition_gates").getAsLong());
        assertTrue(snapshot.get("chunk").isJsonNull());assertFalse(snapshot.has("server"));assertFalse(snapshot.has("world_key"));
    }
    @Test void dhTraceCadenceResetsOnRestartAndStopsWhenDisabled() throws Exception {
        assertFalse(DhClientTrace.sampleDue(100));
        ClientTraceLog.toggle();
        assertTrue(DhClientTrace.sampleDue(100));assertFalse(DhClientTrace.sampleDue(999_999_999));
        assertTrue(DhClientTrace.sampleDue(1_000_000_100));
        ClientTraceLog.stop();assertFalse(DhClientTrace.sampleDue(9_000_000_000L));
        ClientTraceLog.toggle();assertTrue(DhClientTrace.sampleDue(1_000_000_101));
    }
    @Test void dhTraceRecordsActualSentRequestAndOwnedReplyWithMatchingIdentifiers() throws Exception {
        var s=session();configureFrontier(s);var trace=ClientTraceLog.toggle();
        invoke(s,"request",new Class<?>[]{DhPosition.class,int.class,boolean.class,long.class},POSITION,0,false,System.nanoTime());
        pumpUntil(()->!sent.isEmpty(),"trace request was not sent");
        var request=sent.stream().map(bytes->{try{return codec(s).decode(bytes);}catch(Exception e){throw new AssertionError(e);}})
                .filter(DhMessage.Request.class::isInstance).map(DhMessage.Request.class::cast).findFirst().orElseThrow();
        capture(codec(s).encode(new DhMessage.Response(request.requestId(),null,List.of())),System.nanoTime()).receive();
        pumpUntil(()->pendingMap(s).isEmpty()&&frameCount(s)==0,"trace response did not finish");ClientTraceLog.stop();
        var rows=Files.readAllLines(trace.path()).stream().map(line->com.google.gson.JsonParser.parseString(line).getAsJsonObject()).toList();
        var outgoing=rows.stream().filter(r->r.get("t").getAsString().equals("dh_request")).findFirst().orElseThrow();
        var incoming=rows.stream().filter(r->r.get("t").getAsString().equals("dh_reply")).findFirst().orElseThrow();
        assertEquals(request.requestId(),outgoing.get("id").getAsInt());assertEquals(outgoing.get("id"),incoming.get("id"));
        assertEquals(outgoing.get("tile"),incoming.get("tile"));assertFalse(incoming.get("payload").getAsBoolean());
        assertTrue(incoming.get("age_ms").getAsLong()>=0);
    }
    @Test void dhTraceReportsRawQueueLoss() throws Exception {
        var s=session();var trace=ClientTraceLog.toggle();var frames=new ArrayList<DhClientSession.Inbound>();
        try {
            for(int i=0;i<24;i++)frames.add(capture(codec(s).encode(new DhMessage.Split(i,new byte[8],true)),System.nanoTime()));
            assertNull(capture(codec(s).encode(new DhMessage.Split(100,new byte[8],true)),System.nanoTime()));
            ClientTraceLog.stop();
            var reasons=Files.readAllLines(trace.path()).stream().map(line->com.google.gson.JsonParser.parseString(line).getAsJsonObject())
                    .filter(r->r.get("t").getAsString().equals("dh_pressure")).map(r->r.get("reason").getAsString()).toList();
            assertEquals(List.of("INBOUND_QUEUE"),reasons);
        } finally {frames.forEach(DhClientSession.Inbound::close);}
    }
    @Test void dhTraceRefusalCannotWriteUntrustedServerText() throws Exception {
        var s=session();var trace=ClientTraceLog.toggle();
        pending(s,77,false,System.nanoTime());
        capture(codec(s).encode(new DhMessage.Refusal(77,0,"private-world-key\"\nspoofed-row")),System.nanoTime()).receive();
        pumpUntil(()->frameCount(s)==0&&pendingMap(s).isEmpty(),"refusal trace did not finish");ClientTraceLog.stop();
        var text=Files.readString(trace.path());assertFalse(text.contains("private-world-key"));assertFalse(text.contains("spoofed-row"));
        var row=Files.readAllLines(trace.path()).stream().map(line->com.google.gson.JsonParser.parseString(line).getAsJsonObject())
                .filter(r->r.get("t").getAsString().equals("dh_refusal")).findFirst().orElseThrow();
        assertEquals(77,row.get("id").getAsInt());assertEquals(0,row.get("reason").getAsInt());
    }

    @Test void transferredHubIdentityNeverArmsALegacyReconnectAcrossRepeatedTransfers() throws Exception {
        for(int cycle=0;cycle<4;cycle++) {
            DhClientSession.transferred();
            doReturn(mcMock(net.minecraft.network.Connection.class)).when(connection).getConnection();
            DhClientSession.onJoin();var owner=session();
            write(owner,SESSION,"announced",true);write(owner,SESSION,"sentAt",System.nanoTime()-16_000_000_000L);
            invoke(owner,"tick");invoke(owner,"tick");invoke(owner,"tick");
            assertNull(read(null,DhClientSession.class,"pendingRetry"));assertFalse(active(owner));
        }
    }
    @Test void reconfiguredListenerCanDiscoverSupportWithoutKeepingThePreviousOfficialConfirmation() throws Exception {
        var first=session();capture(codec(first).encode(new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,false,0,false,0,0,500)),System.nanoTime()).receive();
        pumpUntil(()->frameCount(first)==0,"official config not processed");
        var physical=connection.getConnection();connection=mcMock(ClientPacketListener.class);doReturn(physical).when(connection).getConnection();
        write(minecraft.player,LocalPlayer.class,"connection",connection);DhClientSession.onJoin();
        var probe=session();capture(rejection(DhProfile.SUPPORT_15),System.nanoTime()).receive();
        pumpUntil(()->!active(probe),"Support advertisement did not retire probe");
        assertNull(read(null,DhClientSession.class,"pendingRetry"));
        var support=session();assertEquals(DhProfile.SUPPORT_15,read(support,SESSION,"profile"));
        capture(codec(support).encode(new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,false,0,false,0,0,500)),System.nanoTime()).receive();
        pumpUntil(()->frameCount(support)==0,"Support config not processed");assertTrue(active(support));
    }
    @Test void ambiguousLegacyControlsCannotConfirmAHintedFamilyOrOpenItsCache() throws Exception {
        write(null,DhClientSession.class,"retryUsed",true);write(null,DhClientSession.class,"trialProfile",DhProfile.OFFICIAL_15);
        var owner=session();var support=new DhProtocolCodec(DhProfile.SUPPORT_15,DhLimits.DEFAULT);
        var control=support.encode(new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,false,0,true,32,20,500));
        assertNull(DhProtocolDiscovery.advertised(control));capture(control,System.nanoTime()).receive();
        pumpUntil(()->frameCount(owner)==0,"legacy control did not settle");
        assertFalse((boolean)read(owner,SESSION,"familyConfirmed"));assertTrue(((Map<?,?>)read(null,DhClientSession.class,"confirmedProfiles")).isEmpty());
        assertNull(read(owner,SESSION,"terrainCache"));assertEquals(0,counter(owner,"columns"));
    }
    @Test void rejectionReconnectWaitsAtLeastFiveSecondsAfterTheOriginalSessionStarted() throws Exception {
        var owner=session();capture(rejection(DhProfile.OFFICIAL_15),System.nanoTime()).receive();
        pumpUntil(()->!active(owner),"rejection did not settle");
        assertNotNull(read(null,DhClientSession.class,"pendingRetry"));DhClientSession.tick();
        assertNotNull(read(null,DhClientSession.class,"pendingRetry"),"minimum reconnect delay was skipped");
    }
    @Test void nativeAdvertisementDoesNotLoseEarlierVanillaAuthorityWhileFallbackIsPermitted() throws Exception {
        DhClientSession.installNativeAvailability(()->true);DhClientSession.vanillaLoaded(level,40,-40);
        DhClientSession.installNativeAvailability(()->false);var owner=session();
        assertFalse(policy(owner).eligible(40,-40));
    }
    @Test void aCancelledQueuedRequestNeverRegistersAnOrphanAssemblyOwner() throws Exception {
        var owner=session();var hold=new CountDownLatch(1);var entered=new CountDownLatch(1);
        worker(owner).execute(()->{entered.countDown();try{hold.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        assertTrue(entered.await(5,TimeUnit.SECONDS));
        try {invoke(owner,"request",new Class<?>[]{DhPosition.class,int.class,boolean.class,long.class},POSITION,0,false,System.nanoTime());pendingMap(owner).clear();}
        finally {hold.countDown();}
        waitWorker(owner);while(!mainTasks.isEmpty())mainTasks.poll().run();
        assertTrue(((Map<?,?>)read(assembly(owner),DhAssembly.class,"requests")).isEmpty());assertTrue(sent.isEmpty());assertTrue(active(owner));
    }
    @Test void unsafeSourceCacheIsDisabledWithoutFollowingItsRootOrStoppingTerrain() throws Exception {
        var owner=session();var outside=dir.resolve("foreign");Files.createDirectories(outside);Files.writeString(outside.resolve("keep"),"foreign");
        var root=dir.resolve("unsafe");Files.createDirectories(root);Files.createSymbolicLink(root.resolve(DhTerrainCache.DIRECTORY),outside);
        write(owner,SESSION,"terrainCache",new DhTerrainCache(root,new DhTerrainCache.Key("a","s","w",DIMENSION,1,DhProfile.OFFICIAL_16,new DhTerrainTile.WorldShape(-64,384,true))));
        invoke(owner,"removeCached",new Class<?>[]{DhPosition.class},POSITION);while(!mainTasks.isEmpty())mainTasks.poll().run();
        assertNull(read(owner,SESSION,"terrainCache"));assertTrue((boolean)read(owner,SESSION,"cacheDisabled"));assertTrue(active(owner));
        assertEquals("foreign",Files.readString(outside.resolve("keep")));
    }

    @Test void nativeAdvertisementWinsAndLateOfferRetiresDhWithoutTerrain() throws Exception {
        assertTrue(DhClientSession.selected());
        DhClientSession.installNativeAvailability(()->true);
        assertFalse(DhClientSession.selected());
        DhClientSession.installNativeAvailability(()->false);
        var owner=session();
        DhClientSession.nativeOffer();
        assertFalse(DhClientSession.selected());assertFalse(active(owner));
        assertNull(read(null,DhClientSession.class,"session"));
        assertNull(read(null,DhClientSession.class,"pendingRetry"));
        assertEquals(0,budget(owner).used());assertTrue(sent.isEmpty());
        // A disabled or unsupported native offer must not be bypassed via DH.
        assertFalse(DhClientSession.selected());DhClientSession.tick();
        assertTrue(DhClientSession.status().contains("including disabled or incompatible"));
        DhClientSession.onJoin();assertTrue(DhClientSession.selected());
    }
    @Test void disablingDhPermissionLeavesOnlyNativeDiscovery() throws Exception {
        settings.store().saveDraft(settings.store().read().hash(),Map.of("lod.source","lss"));
        settings.commit(settings.prepareReload());
        assertFalse(DhClientSession.fallbackEnabled());assertFalse(DhClientSession.selected());
        assertTrue(sent.isEmpty());
    }
    @Test void anUnavailableNativeCapabilityCheckDefersDhRatherThanChoosingIt() {
        DhClientSession.installNativeAvailability(()->{throw new IllegalStateException("not ready");});
        assertFalse(DhClientSession.selected());
    }
    private byte[] rejection(DhProfile profile) throws Exception {
        if(profile!=DhProfile.SUPPORT_15)return new DhProtocolCodec(profile,DhLimits.DEFAULT).encode(new DhMessage.CloseReason("Incompatible protocol version"));
        var bytes=new java.io.ByteArrayOutputStream();var out=new java.io.DataOutputStream(bytes);
        out.writeShort(15);out.writeShort(1);byte[] text="Incompatible protocol version".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(text.length);out.write(text);return bytes.toByteArray();
    }
    @Test void defensiveOfficialRejectionAndActualSupportPrefixNeverAcceptTerrainOnTheProbeEpoch() throws Exception {
        for(var profile:List.of(DhProfile.OFFICIAL_15,DhProfile.SUPPORT_15)) {
            DhClientSession.disconnect();
            @SuppressWarnings("unchecked") var hints=(Map<String,DhProfile>)read(null,DhClientSession.class,"protocolHints");
            hints.remove(minecraft.server.ip);
            var probe=session();assertEquals(DhProfile.OFFICIAL_16,read(probe,SESSION,"profile"));
            capture(rejection(profile),System.nanoTime()).receive();
            pumpUntil(()->!active(probe),"supported advertisement did not retire probe");
            assertNull(read(probe,SESSION,"config"));assertNull(read(probe,SESSION,"terrainCache"));
            assertEquals(0,counter(probe,"columns"));
            if(profile==DhProfile.SUPPORT_15) {
                assertNull(read(null,DhClientSession.class,"pendingRetry"));
                var same=session();assertEquals(profile,read(same,SESSION,"profile"));invoke(same,"retire");
                ((Map<?,?>)read(null,DhClientSession.class,"discoveredProfiles")).clear();
            } else {assertNotNull(read(null,DhClientSession.class,"pendingRetry"));assertEquals(profile,hints.get(minecraft.server.ip));}
            DhClientSession.nativeOffer();assertNull(read(null,DhClientSession.class,"pendingRetry"));
            DhClientSession.disconnect();
            hints.put(minecraft.server.ip,profile);
            write(null,DhClientSession.class,"retryJoinPending",true);
            write(null,DhClientSession.class,"retryServer",minecraft.server);
            write(null,DhClientSession.class,"retryJoinDeadline",System.nanoTime()+60_000_000_000L);
            write(null,DhClientSession.class,"trialProfile",profile);
            DhClientSession.onJoin();var next=session();assertEquals(profile,read(next,SESSION,"profile"));
            invoke(next,"retire");
            doReturn(mcMock(net.minecraft.network.Connection.class)).when(connection).getConnection();
            DhClientSession.onJoin();var fresh=session();assertEquals(DhProfile.OFFICIAL_16,read(fresh,SESSION,"profile"));
            invoke(fresh,"retire");
        }
    }
    @Test void aSilentVersion16ProbeAllowsOneFreshLegacyTrialWithoutTerrain() throws Exception {
        var first=session();write(first,SESSION,"announced",true);write(first,SESSION,"sentAt",System.nanoTime()-16_000_000_000L);
        invoke(first,"tick");invoke(first,"tick");invoke(first,"tick");assertFalse(active(first));
        assertNotNull(read(null,DhClientSession.class,"pendingRetry"));assertTrue(sent.isEmpty());
        assertEquals(0,budget(first).used());
        write(null,DhClientSession.class,"retryJoinPending",true);
        write(null,DhClientSession.class,"retryServer",minecraft.server);
        write(null,DhClientSession.class,"retryJoinDeadline",System.nanoTime()+60_000_000_000L);
        DhClientSession.onJoin();var legacy=session();assertEquals(DhProfile.OFFICIAL_15,read(legacy,SESSION,"profile"));
        write(legacy,SESSION,"announced",true);write(legacy,SESSION,"sentAt",System.nanoTime()-16_000_000_000L);
        invoke(legacy,"tick");invoke(legacy,"tick");invoke(legacy,"tick");assertFalse(active(legacy));assertNull(read(null,DhClientSession.class,"pendingRetry"));
        assertEquals(0,counter(legacy,"columns"));assertEquals(0,budget(legacy).used());
    }
    @Test void missingOrWithdrawnDhChannelCannotScheduleAnAutomaticReconnect() throws Exception {
        var s=session();write(s,SESSION,"announced",true);write(s,SESSION,"sentAt",System.nanoTime()-16_000_000_000L);
        DhClientSession.installChannelAvailability(()->false);
        invoke(s,"tick");invoke(s,"tick");invoke(s,"tick");
        assertFalse(active(s));assertNull(read(null,DhClientSession.class,"pendingRetry"));
        assertTrue(DhClientSession.status().contains("channel is unavailable"));assertTrue(sent.isEmpty());
    }
    @Test void capturedAndQueuedConfigurationSurvivesALateClientTick() throws Exception {
        var s=session();write(s,SESSION,"announced",true);write(s,SESSION,"sentAt",System.nanoTime()-16_000_000_000L);
        var cfg=new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,true,32,false,0,0,500);
        var inbound=capture(codec(s).encode(cfg),System.nanoTime());
        invoke(s,"tick");invoke(s,"tick");invoke(s,"tick");
        assertTrue(active(s));assertNull(read(null,DhClientSession.class,"pendingRetry"));
        inbound.receive();pumpUntil(()->read(s,SESSION,"config")!=null && frameCount(s)==0,"queued config not applied");
        assertTrue(active(s));assertNull(read(null,DhClientSession.class,"pendingRetry"));
    }
    @Test void aConfirmedProtocolSurvivesNullWorldAndSamePhysicalReconfiguration() throws Exception {
        var first=session();
        var cfg=new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,true,32,false,0,0,500);
        capture(codec(first).encode(cfg),System.nanoTime()).receive();pumpUntil(()->frameCount(first)==0,"config did not settle");
        minecraft.level=null;DhClientSession.tick();assertFalse(active(first));
        minecraft.level=level;DhClientSession.onJoin();
        var next=session();write(next,SESSION,"announced",true);write(next,SESSION,"sentAt",System.nanoTime()-16_000_000_000L);
        invoke(next,"tick");invoke(next,"tick");invoke(next,"tick");
        assertNull(read(null,DhClientSession.class,"pendingRetry"));
        assertEquals(DhProfile.OFFICIAL_16,read(next,SESSION,"profile"));
    }
    @Test void anAutomaticJoinPastSixtySecondsStillConsumesItsOneAllowance() throws Exception {
        write(null,DhClientSession.class,"retryJoinPending",true);write(null,DhClientSession.class,"retryServer",minecraft.server);
        write(null,DhClientSession.class,"retryJoinDeadline",System.nanoTime()-1_000_000_000L);
        DhClientSession.onJoin();assertTrue((boolean)read(null,DhClientSession.class,"retryUsed"));
    }
    @Test void malformedOrUnsupportedAdvertisementsCannotScheduleReconnects() throws Exception {
        for(byte[] frame:List.of(new byte[]{0,17,0,1},new byte[]{0,15,0,1,0,5,'x'})) {
            DhClientSession.disconnect();var s=session();capture(frame,System.nanoTime()).receive();
            pumpUntil(()->!active(s) && budget(s).used()==0,"invalid advertisement did not stop/release ownership");
            assertNull(read(null,DhClientSession.class,"pendingRetry"));assertEquals(0,budget(s).used());
        }
    }
    @Test void theAutomaticReconnectContinuationCannotRetryAgainButAManualJoinCan() throws Exception {
        write(null,DhClientSession.class,"retryJoinPending",true);
        write(null,DhClientSession.class,"retryServer",minecraft.server);
        write(null,DhClientSession.class,"retryJoinDeadline",System.nanoTime()+60_000_000_000L);
        DhClientSession.onJoin();assertTrue((boolean)read(null,DhClientSession.class,"retryUsed"));
        var s=session();capture(rejection(DhProfile.OFFICIAL_15),System.nanoTime()).receive();
        pumpUntil(()->!active(s),"second mismatch did not stop");
        assertNull(read(null,DhClientSession.class,"pendingRetry"));
        doReturn(mcMock(net.minecraft.network.Connection.class)).when(connection).getConnection();
        DhClientSession.onJoin();assertFalse((boolean)read(null,DhClientSession.class,"retryUsed"));
    }

    @Test void anUnchangedReplyWithoutASourceCannotReconfirmAnUnverifiedQueuedWrite() throws Exception {
        var s=session();long now=System.nanoTime();
        settings.store().saveDraft(settings.store().read().hash(),Map.of("lod.dh.sync_on_load",true));
        settings.commit(settings.prepareReload());invoke(s,"tick");
        policy(s).accepted(POSITION,0xffff,0xffff,123,now-31_000_000_000L);
        policy(s).revalidate(POSITION,now);assertTrue(policy(s).due(POSITION,now));
        pending(s,1,false,now);
        var ctor=PENDING.getDeclaredConstructor(DhPosition.class,boolean.class,long.class,long.class);ctor.setAccessible(true);
        pendingMap(s).put(1,ctor.newInstance(POSITION,false,now,123L));
        capture(codec(s).encode(new DhMessage.Response(1,null,List.of())),System.nanoTime()).receive();
        pumpUntil(()->pendingMap(s).isEmpty()&&frameCount(s)==0,"owned unchanged response did not settle");
        assertTrue(active(s));assertEquals(0,policy(s).syncTimestamp(POSITION));
        assertTrue(policy(s).due(POSITION,System.nanoTime()+3_000_000_000L));assertFalse(policy(s).generationNeeded(POSITION));
        assertEquals(0,((java.util.concurrent.atomic.AtomicLong)read(s,SESSION,"tiles")).get());
        assertEquals(0,((java.util.concurrent.atomic.AtomicLong)read(s,SESSION,"columns")).get());
        assertEquals(0,budget(s).used());assertTrue(sent.isEmpty());
    }


    @Test void stationaryOfficialAbsenceStopsPollingWithRefreshOff() throws Exception { stationaryAbsence(0); }

    private void stationaryAbsence(int refreshSeconds) throws Exception {
        var s=session();long origin=System.nanoTime();var player=new ChunkPos(2,2);
        minecraft.player=mcMock(LocalPlayer.class);write(minecraft.player,LocalPlayer.class,"connection",connection);
        doReturn(player).when(minecraft.player).chunkPosition();
        settings.store().saveDraft(settings.store().read().hash(),Map.of("lod.distance_chunks",48));
        settings.commit(settings.prepareReload());
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.SURFACE_THEN_CHUNKS,
                32,0,0,0,1,false,0,true,2048,50,500));
        write(s,SESSION,"levelKey","test-world");
        for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)
            policy(s).accepted(new DhPosition(6,x,z),0xffff,0xffff,123,origin);
        int absentRequests=0,refreshRequests=0;var absentTiles=new HashSet<DhPosition>();
        // Three stationary hours at the actual ten-second refresh ceiling. Drive
        // the production scanner, sender, empty-response handling and worker.
        for(int second=0;second<=10_800;second+=10) {
            int before=sent.size();invoke(s,"requestNext",new Class<?>[]{long.class,ChunkPos.class},origin+second*1_000_000_000L,player);
            if(pendingMap(s).isEmpty())continue;
            pumpUntil(()->sent.size()>before,"scanner did not send its owned request");
            var request=(DhMessage.Request)codec(s).decode(sent.getLast());
            if(request.timestamp()!=null && request.timestamp()>0)refreshRequests++;
            else {absentRequests++;assertTrue(absentTiles.add(request.position()),"Unreachable absence was re-polled");}
            capture(codec(s).encode(new DhMessage.Response(request.requestId(),null,List.of())),System.nanoTime()).receive();
            pumpUntil(()->pendingMap(s).isEmpty()&&frameCount(s)==0,"empty response did not settle");
        }
        assertEquals(625-289,absentRequests);assertEquals(0,refreshRequests);
        assertTrue(active(s));assertEquals(0,budget(s).used());
    }

    @Test void resetSweepFailureRetiresTheSessionAndReportsStoppedWithoutClearingReceipts() throws Exception {
        var s=session();policy(s).accepted(POSITION,1,1,123,System.nanoTime());
        var blocked=Files.createDirectories(ColumnCacheStore.cacheRoot().resolve(cacheComponent).resolve("foreign-child"));
        try {assertThrows(IllegalStateException.class,DhClientSession::drainForReset);}
        finally {Files.delete(blocked);Files.deleteIfExists(blocked.getParent());}
        assertFalse(active(s));assertNull(read(null,DhClientSession.class,"session"));
        assertEquals(123,policy(s).timestamp(POSITION));assertEquals(0,budget(s).used());
        assertTrue(DhClientSession.status().startsWith("DH reset failed:"));
        assertEquals(dev.vox.lss.common.diagnostics.DhStatusSnapshot.State.STOPPED,DhClientSession.diagnostics().state());
    }

    @Test void singlePlayerResetDoesNotRequireARemoteServerAddress() throws Exception {
        var s=session();minecraft.server=null;
        assertDoesNotThrow(DhClientSession::drainForReset);assertFalse(active(s));
        DhClientSession.resumeAfterReset();assertNull(read(null,DhClientSession.class,"session"));
    }








    @Test void acceptedRefreshesRequireServerSynchronizationBeforeSelection() throws Exception {
        long origin=System.nanoTime();var s=refreshFrontier(origin);
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,false,0,false,8,50,500));
        assertNull(scan(s,origin));
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,false,0,true,8,0,500));
        assertNull(scan(s,origin));
    }

    private Object refreshFrontier(long origin) throws Exception {
        settings.store().saveDraft(settings.store().read().hash(),Map.of("lod.dh.sync_on_load",true));settings.commit(settings.prepareReload());
        var s=session();configureFrontier(s);invoke(s,"tick");
        for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)
            { var p=new DhPosition(6,x,z);policy(s).accepted(p,1,1,123,origin);policy(s).revalidate(p,origin); }
        return s;
    }
    private void configureFrontier(Object s) {
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,false,0,true,8,50,500));
        write(s,SESSION,"levelKey","test-world");
    }
    private DhMessage.Request scan(Object s,long now) throws Exception {
        int before=sent.size();invoke(s,"requestNext",new Class<?>[]{long.class,ChunkPos.class},now,new ChunkPos(2,2));
        waitWorker(s);Runnable task;while((task=mainTasks.poll())!=null)task.run();
        return sent.size()==before?null:(DhMessage.Request)codec(s).decode(sent.getLast());
    }
    private void completeScan(Object s,DhMessage.Request request,long now) throws Exception {
        assembly(s).cancel(request.requestId());pendingMap(s).remove(request.requestId());
        assertTrue(policy(s).unchanged(request.position(),request.timestamp(),now));
    }

    @Test void aConnectionAuthorityBlockReportsReconnectRequiredWithoutBlamingVoxy() {
        var state=(DhConnectionState)read(null,DhClientSession.class,"connectionState");
        for(int i=0;i<16;i++)state.policy("test:dimension_"+i);
        assertThrows(IllegalStateException.class,()->state.policy(DIMENSION));
        assertEquals(dev.vox.lss.common.diagnostics.DhStatusSnapshot.State.BLOCKED,DhClientSession.diagnostics().state());
    }

    @Test void stalledMainThreadAbortsOnlyItsTileAndNeverRunsTheLateHandoff() throws Exception {
        var s=session();var position=new DhPosition(6,-1,1);int id=2;
        doReturn(-64).when(level).getMinBuildHeight();doReturn(384).when(level).getHeight();
        var dimension=VanillaRegistries.createLookup().lookupOrThrow(Registries.DIMENSION_TYPE)
                .getOrThrow(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD).value();
        doReturn(dimension).when(level).dimensionType();
        var ctor=PENDING.getDeclaredConstructor(DhPosition.class,boolean.class,long.class,long.class);ctor.setAccessible(true);
        pendingMap(s).put(id,ctor.newInstance(position,false,System.nanoTime(),0L));assembly(s).expect(id,position);
        for(int frame=4;frame<=6;frame++) {
            try(var bytes=getClass().getResourceAsStream(String.format("/dh-corpus/dh-official-3.3.3-neoforge-partial/received-%04d.bin",frame))) {
                assertNotNull(bytes);capture(bytes.readAllBytes(),System.nanoTime()).receive();
            }
        }
        var resetCounter=mainTasks.poll(10,TimeUnit.SECONDS);assertNotNull(resetCounter);resetCounter.run();
        var held=mainTasks.poll(10,TimeUnit.SECONDS);assertNotNull(held,"conversion did not reach its main-thread check");
        // Deliberately do not pump the client queue until the real five-second
        // conversion timeout has elapsed; the worker must survive this hitch.
        waitWorker(s);assertTrue(active(s));held.run();
        pumpUntil(()->pendingMap(s).isEmpty()&&frameCount(s)==0,"stalled tile was not cooled down");
        assertFalse(policy(s).due(position,System.nanoTime()));assertTrue(policy(s).due(position,System.nanoTime()+31_000_000_000L));
        assertEquals(0,((java.util.concurrent.atomic.AtomicLong)read(s,SESSION,"columns")).get());
        assertEquals(0,budget(s).used());assertTrue(active(s));
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,false,0,true,8,1,500));
        write(s,SESSION,"levelKey","test-world");
        for(int x=-3;x<=1;x++)for(int z=-1;z<=3;z++)if(!position.equals(new DhPosition(6,x,z)))
            policy(s).accepted(new DhPosition(6,x,z),1,1,1,System.nanoTime());
        invoke(s,"requestNext",new Class<?>[]{long.class,ChunkPos.class},System.nanoTime()+31_000_000_000L,new ChunkPos(-2,6));
        pumpUntil(()->!sent.isEmpty(),"stalled tile was not re-requested after cooldown");
        assertEquals(position,((DhMessage.Request)codec(s).decode(sent.getFirst())).position());
    }

    @Test void inboundQueueCapRetiresAndReleasesAllUnreceivedFrames() throws Exception {
        var s=session();var frames=new ArrayList<DhClientSession.Inbound>();
        for(int i=0;i<32;i++) frames.add(capture(new byte[]{0},System.nanoTime()));
        assertTrue(frames.stream().allMatch(Objects::nonNull));
        assertEquals(32L*257,budget(s).used());
        assertNull(capture(new byte[]{0},System.nanoTime()));
        assertFalse(active(s));
        pumpUntil(()->budget(s).used()==0,"terminal queue-cap retirement did not release its frames");
        assertEquals(0,frameCount(s));assertTrue(worker(s).isTerminated());
        for(var frame:frames) {frame.receive();frame.close();}
        assertEquals(0,budget(s).used(),"a delayed loader callback cannot reacquire a retired frame");
        assertSame(connection,minecraft.getConnection());assertSame(level,minecraft.level);
    }

    @Test void oversizedFrameIsConsumedWithoutAllocatingAnOwnedCopy() throws Exception {
        var s=session();var buffer=new FriendlyByteBuf(Unpooled.wrappedBuffer(new byte[DhLimits.DEFAULT.frameBytes()+1]));
        try {
            assertNull(DhClientSession.capture(buffer));assertEquals(0,buffer.readableBytes());
            assertEquals(0,budget(s).used());assertFalse(active(s));
        } finally {buffer.release();}
        pumpUntil(()->worker(s).isTerminated(),"oversized-frame retirement did not terminate");
    }

    @Test void retiredEpochCannotBindAnOldLevelInitAfterItsWorkerQueuedTheCallback() throws Exception {
        var old=session();
        capture(codec(old).encode(new DhMessage.LevelInit(DIMENSION,"old-server","old-world",0)),System.nanoTime()).receive();
        var obsolete=mainTasks.poll(10,TimeUnit.SECONDS);
        assertNotNull(obsolete,"old decode worker never queued its world-key bind");
        var replacement=session();
        assertTrue((boolean)invoke(old,"retire"));
        obsolete.run();
        assertEquals(0,budget(old).used());assertEquals(0,frameCount(old));
        assertNull(read(replacement,SESSION,"levelKey"));assertTrue(active(replacement));
        var state=(DhConnectionState)read(null,DhClientSession.class,"connectionState");
        assertTrue(((Map<?,?>)read(state,DhConnectionState.class,"worldKeys")).isEmpty(),
                "an obsolete epoch callback must not claim the replacement's logical world");
    }

    @Test void resetDrainsFragmentsAndQueuedFramesAndResetsReceiptsButRetainsVanillaAuthority() throws Exception {
        var s=session();var policy=policy(s);long now=System.nanoTime();
        DhClientSession.vanillaLoaded(level,40,-40);
        assertFalse(policy.eligible(40,-40));
        assertTrue(policy.accepted(POSITION,0xffff,0xffff,123,now));assertFalse(policy.due(POSITION,now));
        pending(s,7,false,now);assembly(s).accept(new DhMessage.Split(77,new byte[]{1,2,3},true),now);
        var queued=capture(new byte[]{0},now);assertNotNull(queued);
        var stamps=new Long2LongOpenHashMap();stamps.put(123,456);
        ColumnCacheStore.saveAsync(cacheComponent,Level.OVERWORLD,stamps);
        assertTrue(budget(s).used()>0);
        DhClientSession.drainForReset();
        assertEquals(0,budget(s).used());assertEquals(0,frameCount(s));
        assertEquals(0,assembly(s).incompleteBuffers());assertTrue(pendingMap(s).isEmpty());
        assertTrue(ColumnCacheStore.load(cacheComponent,Level.OVERWORLD).isEmpty(),
                "a successful reset must drain the queued native cache save before sweeping it");
        DhClientSession.resumeAfterReset();
        assertTrue(policy.due(POSITION,now));assertEquals(0,policy.timestamp(POSITION));
        var replacement=session();assertSame(policy,policy(replacement));
        assertFalse(policy(replacement).eligible(40,-40),"reset cannot let DH replace previously loaded vanilla terrain");
        queued.receive();queued.close();assertEquals(0,budget(s).used());
    }

    @Test void expiredResponseParksOnlyItsRequestWithoutFailingTheSession() throws Exception {
        transientResponse(true);
    }

    @Test void missingBufferResponseParksOnlyItsRequestWithoutFailingTheSession() throws Exception {
        transientResponse(false);
    }

    private void transientResponse(boolean expired) throws Exception {
        var s=session();long now=System.nanoTime();
        pending(s,1,false,now);pending(s,2,false,now);
        long arrival=expired?System.nanoTime()+DhLimits.DEFAULT.lifetimeNanos()+1:System.nanoTime();
        var response=new DhMessage.Response(1,expired?null:999,List.of());
        capture(codec(s).encode(response),arrival).receive();
        pumpUntil(()->!pendingMap(s).containsKey(1)&&frameCount(s)==0,"transient response was not parked");
        assertTrue(active(s));assertTrue(DhClientSession.running());assertEquals(1,pendingMap(s).size());
        assertTrue(pendingMap(s).containsKey(2));assertFalse(policy(s).due(POSITION,System.nanoTime()));
        assertEquals(0,budget(s).used());assertEquals(0,read(s,SESSION,"unanswered"));
        assertTrue(sent.isEmpty());
        var owners=(Map<?,?>)read(assembly(s),DhAssembly.class,"requests");
        assertFalse(owners.containsKey(1));assertTrue(owners.containsKey(2));
    }

    @Test void idleExpiredContinuationReleasesItsBufferAndFollowingResponseParksTheRequest() throws Exception {
        var s=session();long now=System.nanoTime();pending(s,1,false,now);
        capture(codec(s).encode(new DhMessage.Split(10,new byte[]{1,2,3},true)),now).receive();
        waitWorker(s);assertEquals(1,assembly(s).incompleteBuffers());assertTrue(budget(s).used()>0);
        long late=now+DhLimits.DEFAULT.idleNanos()+1;
        capture(codec(s).encode(new DhMessage.Split(10,new byte[]{4},false)),late).receive();
        waitWorker(s);assertTrue(active(s));assertEquals(0,assembly(s).incompleteBuffers());
        assertEquals(0,budget(s).used());assertTrue(pendingMap(s).containsKey(1));
        capture(codec(s).encode(new DhMessage.Response(1,10,List.of())),late+1).receive();
        pumpUntil(()->pendingMap(s).isEmpty()&&frameCount(s)==0,"missing expired buffer was not parked");
        assertTrue(active(s));assertFalse(policy(s).due(POSITION,System.nanoTime()));assertEquals(0,budget(s).used());
    }

    @Test void terminalFailureCancelsOfficialGenerationOnceOnTheCapturedConnection() throws Exception {
        var s=session();long now=System.nanoTime();pending(s,7,true,now);pending(s,8,false,now);
        invoke(s,"fail",new Class<?>[]{String.class},"test terminal failure");
        assertFalse(active(s));assertTrue(sent.isEmpty(),"cancel is sent by the scheduled retirement");
        pumpUntil(()->worker(s).isTerminated(),"terminal failure did not drain");
        assertEquals(1,sent.size());assertEquals(new DhMessage.Cancel(7),codec(s).decode(sent.getFirst()));
        assertTrue(pendingMap(s).isEmpty());assertEquals(0,budget(s).used());
        invoke(s,"retire");DhClientSession.disconnect();
        assertEquals(1,sent.size(),"repeat retirement and disconnect cannot duplicate the cancellation");
        assertSame(connection,minecraft.getConnection());assertSame(level,minecraft.level);
    }

    @Test void retirementDoesNotSendAnOldRequestCancelOnAReplacementConnection() throws Exception {
        var s=session();pending(s,7,true,System.nanoTime());
        var replacement=mcMock(ClientPacketListener.class);write(minecraft.player,LocalPlayer.class,"connection",replacement);
        invoke(s,"fail",new Class<?>[]{String.class},"connection replacement");
        pumpUntil(()->worker(s).isTerminated(),"replacement-connection retirement did not drain");
        assertTrue(sent.isEmpty());assertTrue(pendingMap(s).isEmpty());assertEquals(0,budget(s).used());
        assertSame(replacement,minecraft.getConnection());
    }

    @Test void readinessWaitsForWorldAndResolvedServiceButAllowsAnIngestOnlyAbi() throws Exception {
        Class<?> bridge=Class.forName("dev.vox.lss.compat.VoxyCompat");
        var fields=new LinkedHashMap<String,Object>();
        for(String name:List.of("classResolver","consumerRegistrar","worldIdentifierOf","rawIngest",
                "voxyGetInstance","getIngestService","getTaskCount")) fields.put(name,read(null,bridge,name));
        Object previousAvailable=read(null,ModCompat.class,"voxyLoaded");
        var dead=(java.util.concurrent.atomic.AtomicBoolean)read(null,bridge,"dhBridgeDead");
        boolean previousDead=dead.get();var previousWorld=WorldIdentifier.returned;var previousInstance=VoxyCommon.instance;
        try {
            Class<?> resolver=inner(bridge,"ClassResolver");
            write(null,bridge,"classResolver",Proxy.newProxyInstance(resolver.getClassLoader(),new Class<?>[]{resolver},
                    (proxy,method,args)->Class.forName((String)args[0])));
            write(null,bridge,"consumerRegistrar",(java.util.function.Consumer<Object>)consumer->{});
            var init=bridge.getDeclaredMethod("init");init.setAccessible(true);assertEquals(true,init.invoke(null));
            write(null,ModCompat.class,"voxyLoaded",true);dead.set(false);
            var s=session();
            write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.SURFACE_THEN_CHUNKS,
                    32,0,0,0,1,false,0,true,32,1,500));
            write(s,SESSION,"levelKey","test-world");write(s,SESSION,"announced",true);
            write(s,SESSION,"sentAt",System.nanoTime());
            var stamps=new Long2LongOpenHashMap();stamps.put(123,456);
            ColumnCacheStore.save(cacheComponent,Level.OVERWORLD,stamps);
            WorldIdentifier.returned=null;VoxyCommon.instance=new VoxyInstance();
            assertFalse(DhVoxyBridge.ready(level));invoke(s,"tick");
            assertEquals(false,read(s,SESSION,"cacheInvalidated"));
            assertEquals(456,ColumnCacheStore.load(cacheComponent,Level.OVERWORLD).get(123));
            WorldIdentifier.returned=new WorldIdentifier();VoxyCommon.instance=null;
            assertFalse(DhVoxyBridge.ready(level));
            VoxyCommon.instance=new VoxyInstance();VoxyCommon.instance.ingestService=null;
            assertFalse(DhVoxyBridge.ready(level));
            VoxyCommon.instance=new VoxyInstance();assertTrue(DhVoxyBridge.ready(level));
            minecraft.player=null; // readiness/cache gating runs before player acquisition
            invoke(s,"tick");assertEquals(true,read(s,SESSION,"cacheInvalidated"));
            assertTrue(ColumnCacheStore.load(cacheComponent,Level.OVERWORLD).isEmpty());
            // Resolve the actual optional-probe failure path, rather than pretending a
            // missing task-count signal means that the static rawIngest ABI is unusable.
            write(null,bridge,"classResolver",Proxy.newProxyInstance(resolver.getClassLoader(),new Class<?>[]{resolver},
                    (proxy,method,args)->{
                        String name=(String)args[0];
                        if(name.equals("me.cortex.voxy.commonImpl.VoxyCommon")) throw new ClassNotFoundException(name);
                        return Class.forName(name);
                    }));
            assertEquals(true,init.invoke(null));assertEquals(-1,DhVoxyBridge.backlog());
            assertTrue(DhVoxyBridge.ready(level),"an initialized ingest-only ABI uses conservative pacing");
            WorldIdentifier.returned=null;assertFalse(DhVoxyBridge.ready(level));
        } finally {
            for(var entry:fields.entrySet()) write(null,bridge,entry.getKey(),entry.getValue());
            write(null,ModCompat.class,"voxyLoaded",previousAvailable);dead.set(previousDead);
            WorldIdentifier.returned=previousWorld;VoxyCommon.instance=previousInstance;
        }
    }

    // Captured upstream terrain passes through the actual session worker,
    // converter, main-thread authority gates, Voxy bridge and receipt policy.
    // The stock port's boolean is queue acceptance, never a completion oracle.
    @Test void capturedCompleteTerrainRequiresAcceptanceOfEveryEligibleColumn() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=deliverySession(-128,512);
            replayContent(s,31,false);
            assertEquals(512,me.cortex.voxy.common.world.service.VoxelIngestService.calls.size());
            assertEquals(16,counter(s,"columns"));assertEquals(1,counter(s,"tiles"));
            assertTrue(policy(s).syncTimestamp(CONTENT_POSITION)>0);
            assertEquals(0,budget(s).used());assertTrue(active(s));
            var first=me.cortex.voxy.common.world.service.VoxelIngestService.calls.getFirst();
            assertEquals(-12,first.chunkX());assertEquals(0,first.chunkZ());assertEquals(-8,first.sectionY());
        }
    }

    @Test void oneRejectedColumnCannotCreateACompleteCapturedTileReceiptAndFullRetryRecovers() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=deliverySession(-128,512);
            me.cortex.voxy.common.world.service.VoxelIngestService.behavior=call->call.chunkX()!=-12||call.chunkZ()!=0;
            replayContent(s,31,false);
            assertEquals(15,counter(s,"columns"));assertEquals(1,counter(s,"failedColumns"));
            assertEquals(0,counter(s,"tiles"));assertEquals(0,policy(s).syncTimestamp(CONTENT_POSITION));
            assertFalse(policy(s).unchanged(CONTENT_POSITION,1,System.nanoTime()));
            assertEquals(0,budget(s).used());assertTrue(active(s));
            me.cortex.voxy.common.world.service.VoxelIngestService.behavior=call->true;
            replayContent(s,32,false);
            assertEquals(1,counter(s,"tiles"));assertTrue(policy(s).syncTimestamp(CONTENT_POSITION)>0);
            assertEquals(0,budget(s).used());
        }
    }

    @Test void failedRefreshCannotReuseItsPreviousConditionalTimestamp() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=deliverySession(-128,512);replayContent(s,31,false);
            long stamp=policy(s).syncTimestamp(CONTENT_POSITION);assertTrue(stamp>0);
            me.cortex.voxy.common.world.service.VoxelIngestService.behavior=call->call.sectionY()!=21;
            replayContent(s,32,true);
            assertEquals(stamp,policy(s).timestamp(CONTENT_POSITION));
            assertEquals(0,policy(s).syncTimestamp(CONTENT_POSITION));
            assertFalse(policy(s).unchanged(CONTENT_POSITION,stamp,System.nanoTime()));
            assertEquals(1,counter(s,"tiles"));assertEquals(16,counter(s,"failedColumns"));
            assertEquals(0,budget(s).used());
        }
    }

    @Test void partialCapturedCoverageNeverCreatesACompleteReceipt() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=deliverySession(-64,384);var p=new DhPosition(6,-1,1);
            pendingAt(s,2,p,0);
            for(int frame=4;frame<=6;frame++)capture(resource("dh-official-3.3.3-neoforge-partial",String.format("received-%04d.bin",frame)),System.nanoTime()).receive();
            settleDelivery(s);
            assertEquals(8,counter(s,"columns"));assertEquals(0,counter(s,"tiles"));
            assertEquals(0,policy(s).syncTimestamp(p));assertEquals(0,budget(s).used());
        }
    }

    @Test void vanillaAuthorityBeforeExpansionSkipsOnlyItsColumn() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=deliverySession(-128,512);DhClientSession.vanillaLoaded(level,-12,0);
            replayContent(s,31,false);
            assertEquals(15,counter(s,"columns"));assertEquals(1,counter(s,"skipped"));
            assertEquals(1,counter(s,"tiles"));
            assertTrue(me.cortex.voxy.common.world.service.VoxelIngestService.calls.stream()
                    .noneMatch(call->call.chunkX()==-12&&call.chunkZ()==0));
            assertEquals(0,budget(s).used());
        }
    }

    @Test void vanillaAuthorityBetweenExpansionAndHandoffCannotEnqueueTheConvertedColumn() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=deliverySession(-128,512);startContent(s,31,false);
            takeMain().run(); // response bookkeeping
            takeMain().run(); // first column's pre-expansion permission
            var handoff=takeMain();assertEquals(1,counter(s,"converted"));
            assertTrue(me.cortex.voxy.common.world.service.VoxelIngestService.calls.isEmpty());
            DhClientSession.vanillaLoaded(level,-12,0);handoff.run();settleDelivery(s);
            assertEquals(15,counter(s,"columns"));assertEquals(1,counter(s,"skipped"));
            assertTrue(me.cortex.voxy.common.world.service.VoxelIngestService.calls.stream()
                    .noneMatch(call->call.chunkX()==-12&&call.chunkZ()==0));
            assertEquals(0,budget(s).used());
        }
    }

    @Test void retirementDuringConversionRejectsTheAlreadyQueuedHandoff() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=deliverySession(-128,512);startContent(s,31,false);
            takeMain().run();takeMain().run();var handoff=takeMain();
            assertEquals(1,counter(s,"converted"));assertTrue((boolean)invoke(s,"retire"));
            handoff.run();while(!mainTasks.isEmpty())takeMain().run();
            assertTrue(me.cortex.voxy.common.world.service.VoxelIngestService.calls.isEmpty());
            assertEquals(0,policy(s).syncTimestamp(CONTENT_POSITION));assertEquals(0,budget(s).used());
        }
    }

    @Test void duplicateCompletedResponseCannotEnqueueTheCapturedTerrainTwice() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=deliverySession(-128,512);replayContent(s,31,false);
            long columns=counter(s,"columns"),stamp=policy(s).syncTimestamp(CONTENT_POSITION);
            capture(codec(s).encode(contentResponse(s,31,false)),System.nanoTime()).receive();
            pumpUntil(()->frameCount(s)==0,"duplicate response did not release");
            assertEquals(columns,counter(s,"columns"));assertEquals(stamp,policy(s).syncTimestamp(CONTENT_POSITION));
            assertEquals(1,counter(s,"tiles"));assertEquals(0,budget(s).used());assertTrue(active(s));
        }
    }

    @Test void threeWhollyRejectedTilesStopDhWithoutDisconnectingMinecraft() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=deliverySession(-128,512);
            me.cortex.voxy.common.world.service.VoxelIngestService.behavior=call->false;
            for(int id=31;id<=33;id++)replayContent(s,id,false);
            pumpUntil(()->worker(s).isTerminated(),"rejection retirement did not drain");
            assertFalse(active(s));assertEquals(0,counter(s,"tiles"));assertEquals(0,budget(s).used());
            assertEquals(0,policy(s).syncTimestamp(CONTENT_POSITION));
            assertSame(connection,minecraft.getConnection());assertSame(level,minecraft.level);
        }
    }

    @Test void refusalReleasesOnlyItsOwnedRequestAndNeverAcceptsTerrain() throws Exception {
        var s=session();long now=System.nanoTime();pending(s,1,false,now);pending(s,2,false,now);
        capture(codec(s).encode(new DhMessage.Refusal(1,1,"fixture refusal")),now).receive();
        pumpUntil(()->!pendingMap(s).containsKey(1)&&frameCount(s)==0,"refusal was not released");
        assertTrue(pendingMap(s).containsKey(2));assertEquals(1,counter(s,"refused"));
        assertEquals(0,counter(s,"tiles"));assertEquals(0,budget(s).used());assertTrue(active(s));
    }

    @Test void changedWorldKeyStopsBeforeReusingReceiptsOnTheSameConnection() throws Exception {
        var s=session();write(s,SESSION,"levelKey","old-world");
        var state=(DhConnectionState)read(null,DhClientSession.class,"connectionState");assertTrue(state.bindWorld(DIMENSION,"old-world"));
        policy(s).accepted(POSITION,1,1,123,System.nanoTime());
        capture(codec(s).encode(new DhMessage.LevelInit(DIMENSION,"fixture","new-world",0)),System.nanoTime()).receive();
        pumpUntil(()->!active(s)&&budget(s).used()==0,"world replacement did not retire");
        assertEquals("old-world",read(s,SESSION,"levelKey"));assertSame(connection,minecraft.getConnection());
        assertEquals(123,policy(s).timestamp(POSITION));
    }

    @Test void timedOutCacheOpenClosesTheWorldReferenceWhenTheClientActionEventuallyFinishes() throws Exception {
        abandonedCacheOpen(false);
    }
    @Test void interruptedCacheOpenClosesTheWorldReferenceWhenTheClientActionEventuallyFinishes() throws Exception {
        abandonedCacheOpen(true);
    }
    private void abandonedCacheOpen(boolean interrupt) throws Exception {
        var s=session();var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
        var closes=new java.util.concurrent.atomic.AtomicInteger();
        var caller=new java.util.concurrent.atomic.AtomicReference<Thread>();
        Callable<AutoCloseable> action=()->{
            entered.countDown();assertTrue(finish.await(15,TimeUnit.SECONDS));return closes::incrementAndGet;
        };
        var job=worker(s).submit(()->{caller.set(Thread.currentThread());return invoke(s,"onMain",new Class<?>[]{Callable.class},action);});
        var client=new Thread(takeMain(),"test-slow-cache-open");client.start();
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            if(interrupt)caller.get().interrupt();
            var failure=assertThrows(ExecutionException.class,()->job.get(10,TimeUnit.SECONDS));
            if(interrupt)assertInstanceOf(InterruptedException.class,failure.getCause());
            else assertInstanceOf(TimeoutException.class,failure.getCause());
        } finally {finish.countDown();client.join(10_000);}
        assertFalse(client.isAlive());assertEquals(1,closes.get());
    }
    @Test void warmSourceReplaySurvivesDisconnectAndUsesAConditionalRequestWithoutVoxyStorage() throws Exception {
        try(var voxy=new DeliveryBridge();var destination=new DestinationBridge(true)) {
            var shape=new DhTerrainTile.WorldShape(-64,384,true);var position=new DhPosition(6,4,-3);
            byte[] dto=resource("dh-official-3.3.3-neoforge-ocean","database-tile.dto");long stamp;
            var tempBudget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
            try(var tile=new DhTerrainDecoder(DhLimits.DEFAULT).decode(dto,DhProfile.OFFICIAL_16,position,shape,tempBudget)) {stamp=tile.modifiedTimestamp();}
            var key=new DhTerrainCache.Key(minecraft.server.ip,"server","world",DIMENSION,8,DhProfile.OFFICIAL_16,shape);
            new DhTerrainCache(dir.resolve("dh-cache"),key).write(position,stamp,dto);
            var old=deliverySession(-64,384);DhClientSession.disconnect();
            var s=deliverySession(-64,384);assertNotSame(old,s);

            write(s,SESSION,"terrainCache",new DhTerrainCache(dir.resolve("dh-cache"),key));
            invoke(s,"restoreCache",new Class<?>[]{DhPosition.class,long.class},position,System.nanoTime());
            pumpUntil(()->!sent.isEmpty(),"cache validation request not sent");
            assertEquals(0,counter(s,"columns"),"Source must not be enqueued before validation");
            assertEquals(0,policy(s).syncTimestamp(position),"Source proof is not a Voxy acceptance receipt");
            var request=(DhMessage.Request)codec(s).decode(sent.getLast());assertEquals(stamp,request.timestamp());
            capture(codec(s).encode(new DhMessage.Response(request.requestId(),null,List.of())),System.nanoTime()).receive();
            pumpUntil(()->pendingMap(s).isEmpty()&&frameCount(s)==0&&counter(s,"cacheHits")==1,"unchanged cached response did not settle");
            assertEquals(16,counter(s,"columns"));assertFalse(policy(s).due(position,Long.MAX_VALUE));assertEquals(0,budget(s).used());
        }
    }

    @Test void networkResponseCachesValidatedSourceAndASecondConnectionRestoresIt() throws Exception {
        try(var voxy=new DeliveryBridge();var destination=new DestinationBridge(true)) {
            DestinationEngine.observedIngest=true;
            var shape=new DhTerrainTile.WorldShape(-128,512,true);
            var key=new DhTerrainCache.Key(minecraft.server.ip,"server","world",DIMENSION,8,DhProfile.OFFICIAL_16,shape);
            var cache=new DhTerrainCache(dir.resolve("dh-cache"),key);var s=deliverySession(-128,512);
            write(s,SESSION,"terrainCache",cache);replayContent(s,1,false);
            assertEquals(1,counter(s,"cacheWrites"));assertEquals(Set.of(CONTENT_POSITION),cache.positions());
            long stamp=policy(s).syncTimestamp(CONTENT_POSITION);DhClientSession.disconnect();sent.clear();
            me.cortex.voxy.common.world.service.VoxelIngestService.committed.clear();
            var replacement=deliverySession(-128,512);write(replacement,SESSION,"terrainCache",new DhTerrainCache(dir.resolve("dh-cache"),key));
            invoke(replacement,"restoreCache",new Class<?>[]{DhPosition.class,long.class},CONTENT_POSITION,System.nanoTime());
            pumpUntil(()->!sent.isEmpty(),"network cache validation not sent");
            var request=(DhMessage.Request)codec(replacement).decode(sent.getLast());
            assertEquals(stamp,request.timestamp());assertEquals(0,counter(replacement,"columns"));
            capture(codec(replacement).encode(new DhMessage.Response(request.requestId(),null,List.of())),System.nanoTime()).receive();
            pumpUntil(()->counter(replacement,"cacheHits")==1&&frameCount(replacement)==0,"network cache not replayed");
            assertEquals(stamp,policy(replacement).syncTimestamp(CONTENT_POSITION));assertEquals(16,counter(replacement,"columns"));
        }
    }

    @Test void corruptedSourceCannotCreateATimestampReceiptOrAnUnchangedShortcut() throws Exception {
        try(var voxy=new DeliveryBridge()) {
            var shape=new DhTerrainTile.WorldShape(-64,384,true);var position=new DhPosition(6,4,-3);
            var cache=new DhTerrainCache(dir.resolve("dh-cache"),new DhTerrainCache.Key(minecraft.server.ip,"server","world",DIMENSION,8,DhProfile.OFFICIAL_16,shape));
            cache.write(position,123,new byte[]{1,2,3});var s=deliverySession(-64,384);write(s,SESSION,"terrainCache",cache);
            invoke(s,"restoreCache",new Class<?>[]{DhPosition.class,long.class},position,System.nanoTime());
            pumpUntil(()->worker(s).getCompletedTaskCount()>0&&read(s,SESSION,"cacheLoading")==null,"invalid cache did not settle");
            assertEquals(0,counter(s,"cacheHits"));assertEquals(0,counter(s,"columns"));assertEquals(0,policy(s).syncTimestamp(position));
            assertTrue(policy(s).due(position,System.nanoTime()));assertTrue(cache.positions().isEmpty());assertEquals(0,budget(s).used());
        }
    }

    @Test void rawSourceTimestampMismatchCannotBecomeAConditionalReceipt() throws Exception {
        try(var voxy=new DeliveryBridge()) {
            var shape=new DhTerrainTile.WorldShape(-64,384,true);var position=new DhPosition(6,4,-3);
            var cache=new DhTerrainCache(dir.resolve("dh-cache"),new DhTerrainCache.Key(minecraft.server.ip,"server","world",DIMENSION,8,DhProfile.OFFICIAL_16,shape));
            cache.write(position,123,resource("dh-official-3.3.3-neoforge-ocean","database-tile.dto"));
            var s=deliverySession(-64,384);write(s,SESSION,"terrainCache",cache);
            invoke(s,"restoreCache",new Class<?>[]{DhPosition.class,long.class},position,System.nanoTime());
            pumpUntil(()->worker(s).getCompletedTaskCount()>0&&read(s,SESSION,"cacheLoading")==null,"timestamp mismatch did not settle");
            assertEquals(0,counter(s,"columns"));assertEquals(0,policy(s).syncTimestamp(position));assertTrue(cache.positions().isEmpty());
        }
    }

    @Test void resetClearsPersistedSourceAndNativeInvalidationPreservesIt() throws Exception {
        var cache=new DhTerrainCache(ColumnCacheStore.cacheRoot(),new DhTerrainCache.Key(minecraft.server.ip,"server","world",DIMENSION,8,
                DhProfile.OFFICIAL_16,new DhTerrainTile.WorldShape(-64,384,true)));
        cache.write(POSITION,123,new byte[]{1});
        try {
            ColumnCacheStore.clearForServersStrict(List.of(cacheComponent));assertEquals(Set.of(POSITION),cache.positions());
            var s=session();DhClientSession.drainForReset();
            assertFalse(active(s));assertTrue(cache.positions().isEmpty());assertEquals(0,budget(s).used());
        } finally {ColumnCacheStore.clearDhTerrain(minecraft.server.ip);}
    }

    @Test void queuedButUncommittedDeliveryNeverPublishesASourceSnapshotAndReconnectFetchesItAgain() throws Exception {
        try(var bridge=new DeliveryBridge();var destination=new DestinationBridge(true)) {
            DestinationEngine.observedIngest=true;
            var key=new DhTerrainCache.Key(minecraft.server.ip,"server","world",DIMENSION,8,DhProfile.OFFICIAL_16,new DhTerrainTile.WorldShape(-128,512,true));
            var cache=new DhTerrainCache(dir.resolve("queued-cache"),key);
            var first=deliverySession(-128,512);write(first,SESSION,"terrainCache",cache);
            me.cortex.voxy.common.world.service.VoxelIngestService.commitImmediately=false;
            replayContent(first,1,false);
            assertTrue(active(first));assertEquals(16,counter(first,"columns"));
            assertEquals(0,counter(first,"cacheWrites"));assertTrue(cache.positions().isEmpty());
            assertEquals(1,counter(first,"cacheDeclined"));DhClientSession.disconnect();
            var next=deliverySession(-128,512);write(next,SESSION,"terrainCache",cache);
            me.cortex.voxy.common.world.service.VoxelIngestService.commitImmediately=true;
            replayContent(next,2,false);assertEquals(16,counter(next,"columns"));assertEquals(1,counter(next,"cacheWrites"));
        }
    }
    @Test void aRejectedChangedColumnInvalidatesTheOldSourceAndCannotFreezeItOnRejoin() throws Exception {
        try(var bridge=new DeliveryBridge();var destination=new DestinationBridge(true)) {
            DestinationEngine.observedIngest=true;
            var cache=new DhTerrainCache(dir.resolve("rejected-cache"),new DhTerrainCache.Key(minecraft.server.ip,"server","world",DIMENSION,8,DhProfile.OFFICIAL_16,new DhTerrainTile.WorldShape(-128,512,true)));
            var first=deliverySession(-128,512);write(first,SESSION,"terrainCache",cache);replayContent(first,1,false);
            assertEquals(1,counter(first,"cacheWrites"));
            me.cortex.voxy.common.world.service.VoxelIngestService.behavior=call->call.chunkX()!=-12 || call.chunkZ()!=0;
            replayContent(first,2,true);assertTrue(cache.positions().isEmpty());DhClientSession.disconnect();
            var next=deliverySession(-128,512);write(next,SESSION,"terrainCache",cache);
            me.cortex.voxy.common.world.service.VoxelIngestService.behavior=call->true;
            replayContent(next,3,true);assertEquals(16,counter(next,"columns"));assertEquals(1,counter(next,"cacheWrites"));
        }
    }
    @Test void partialTerrainCannotBePersistedAsACompleteSourceSnapshot() throws Exception {
        try(var voxy=new DeliveryBridge()) {
            var cache=new DhTerrainCache(dir.resolve("dh-cache"),new DhTerrainCache.Key(minecraft.server.ip,"server","world",DIMENSION,8,
                    DhProfile.OFFICIAL_16,new DhTerrainTile.WorldShape(-64,384,true)));
            var s=deliverySession(-64,384);write(s,SESSION,"terrainCache",cache);var p=new DhPosition(6,-1,1);
            cache.write(p,1,new byte[]{1});
            pendingAt(s,2,p,0);
            for(int frame=4;frame<=6;frame++)capture(resource("dh-official-3.3.3-neoforge-partial",String.format("received-%04d.bin",frame)),System.nanoTime()).receive();
            settleDelivery(s);assertEquals(0,counter(s,"cacheWrites"));assertTrue(cache.positions().isEmpty());assertEquals(0,budget(s).used());
        }
    }

    @Test void failedSourceReplacementInvalidatesOldDataBeforeDeliveringTheNewTile() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var real=new DhTerrainCache(dir.resolve("dh-cache"),new DhTerrainCache.Key(minecraft.server.ip,"server","world",DIMENSION,8,DhProfile.OFFICIAL_16,new DhTerrainTile.WorldShape(-128,512,true)));
            DestinationEngine.observedIngest=true;
            real.write(CONTENT_POSITION,5,new byte[]{1});var cache=spy(real);
            doThrow(new java.nio.file.AccessDeniedException("test replacement denied")).when(cache).write(any(DhPosition.class),anyLong(),any(byte[].class));
            var s=deliverySession(-128,512);write(s,SESSION,"terrainCache",cache);replayContent(s,1,false);
            assertTrue(real.positions().isEmpty());assertEquals(0,counter(s,"cacheWrites"));assertEquals(16,counter(s,"columns"));assertTrue(active(s));
        }
    }
    @Test void slowRetiredWorkerPreventsResetFromClearingUnderItsLateWork() throws Exception {
        var old=session();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        worker(old).submit(()-> {entered.countDown();while(release.getCount()>0)try {release.await();}catch(InterruptedException ignored) {}});
        assertTrue(entered.await(5,TimeUnit.SECONDS));
        try {
            assertEquals(false,invoke(old,"retire"));var replacement=session();
            assertThrows(IllegalStateException.class,DhClientSession::drainForReset);
            assertTrue(active(replacement),"Reset must abort before touching the new owner");
        } finally {release.countDown();assertTrue(worker(old).awaitTermination(5,TimeUnit.SECONDS));invoke(old,"retire");}
    }
    @Test void cacheDeadlineCoolsOnlyItsTileAndRetainsTheLocalRetry() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=deliverySession(-64,384);write(s,SESSION,"cacheInvalidated",true);
            write(s,SESSION,"cacheLoading",POSITION);write(s,SESSION,"cacheWorkStarted",System.nanoTime()-61_000_000_000L);
            assertFalse(DhVoxyBridge.ready(level),"Housekeeping must run even when Voxy is not ready");
            invoke(s,"tick");
            assertTrue(active(s));assertNull(read(s,SESSION,"cacheLoading"));
            assertTrue(((Set<?>)read(s,SESSION,"cachedPositions")).contains(POSITION));assertEquals(0,policy(s).syncTimestamp(POSITION));
        }
    }
    @Test void actualScannerOverlapsTwoCachedChecksAtTheFiftyMillisecondFloor() throws Exception {
        try(var bridge=new ReadyBridge()) {
            var shape=new DhTerrainTile.WorldShape(-128,512,true);
            var cache=new DhTerrainCache(dir.resolve("dh-cache"),new DhTerrainCache.Key(minecraft.server.ip,"server","world",DIMENSION,8,DhProfile.OFFICIAL_16,shape));
            var s=deliverySession(-128,512);write(s,SESSION,"terrainCache",cache);
            write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,false,0,true,12,20,500));
            var temp=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());long stamp;
            byte[] dto=resource("dh-official-3.3.3-neoforge-content","target-0002-split.bin");
            // Use a real complete captured tile at (-3,0).
            var producer=new DhAssembly(DhLimits.DEFAULT,temp);producer.expect(100,CONTENT_POSITION);
            producer.accept((DhMessage.Split)codec(s).decode(dto));
            var original=(DhMessage.Response)codec(s).decode(resource("dh-official-3.3.3-neoforge-content","target-0003-response.bin"));
            try(var complete=producer.response(new DhMessage.Response(100,original.bufferId(),original.beacons()));var tile=complete.decode(new DhTerrainDecoder(DhLimits.DEFAULT),DhProfile.OFFICIAL_16,shape)) {
                stamp=tile.modifiedTimestamp();complete.cache(cache,stamp);
                byte[] second=((byte[])read(complete,complete.getClass(),"data")).clone();
                var next=new DhPosition(6,-3,1);java.nio.ByteBuffer.wrap(second).putLong(next.packed());
                assertTrue(cache.write(next,stamp,second));
            } finally {producer.close();}
            ((Set<DhPosition>)read(s,SESSION,"cachedPositions")).add(CONTENT_POSITION);
            ((Set<DhPosition>)read(s,SESSION,"cachedPositions")).add(new DhPosition(6,-3,1));
            setting(Map.of("lod.distance_chunks",8));
            for(int x=-32;x<=8;x++)for(int z=-16;z<=16;z++)if(!(x>=-12 && x<=-9 && z>=0 && z<=7))policy(s).vanilla(x,z);
            write(s,SESSION,"nextScanAt",System.nanoTime()+500_000_000L);
            for(int x=1;x<100;x++)((Set<DhPosition>)read(s,SESSION,"cachedPositions")).add(new DhPosition(6,x,0));
            invoke(s,"requestNext",new Class<?>[]{long.class,ChunkPos.class},System.nanoTime(),new ChunkPos(-12,0));
            pumpUntil(()->!sent.isEmpty(),"scanner did not send first validation");
            assertEquals(0,counter(s,"columns"));assertEquals(0,counter(s,"cacheHits"));
            var request=(DhMessage.Request)codec(s).decode(sent.getFirst());assertEquals(CONTENT_POSITION,request.position());assertEquals(stamp,request.timestamp());
            assertTrue(((Set<?>)read(s,SESSION,"cachedPositions")).size()>=99);
            long at=(long)read(s,SESSION,"lastRequest");assertTrue((long)read(s,SESSION,"nextScanAt")<=at+50_000_000L);
            pumpUntil(()->read(s,SESSION,"cacheLoading")==null,"first cache task did not clear");
            long remaining=at+51_000_000L-System.nanoTime();if(remaining>0)TimeUnit.NANOSECONDS.sleep(remaining);
            invoke(s,"requestNext",new Class<?>[]{long.class,ChunkPos.class},System.nanoTime(),new ChunkPos(-12,0));
            pumpUntil(()->sent.size()>=2,"second cache check did not overlap the first request");
            assertEquals(2,pendingMap(s).size());assertEquals(new DhPosition(6,-3,1),((DhMessage.Request)codec(s).decode(sent.getLast())).position());
        }
    }
    @Test void checksumValidButUndecodableSourceIsRemovedWithoutStoppingTheSession() throws Exception {
        var cache=new DhTerrainCache(dir.resolve("dh-cache"),new DhTerrainCache.Key(minecraft.server.ip,"server","world",DIMENSION,8,DhProfile.OFFICIAL_16,new DhTerrainTile.WorldShape(-64,384,true)));
        cache.write(POSITION,123,new byte[]{1,2,3});var s=deliverySession(-64,384);write(s,SESSION,"terrainCache",cache);
        invoke(s,"restoreCache",new Class<?>[]{DhPosition.class,long.class},POSITION,System.nanoTime());
        pumpUntil(()->worker(s).getCompletedTaskCount()>0&&read(s,SESSION,"cacheLoading")==null,"bad DTO not resolved");
        assertTrue(active(s));assertTrue(cache.positions().isEmpty());assertEquals(0,budget(s).used());
    }
    @Test void restoringUnderBudgetPressureKeepsTheFileAndSchedulesALocalRetry() throws Exception {
        var shape=new DhTerrainTile.WorldShape(-64,384,true);var position=new DhPosition(6,4,-3);
        var cache=new DhTerrainCache(dir.resolve("dh-cache"),new DhTerrainCache.Key(minecraft.server.ip,"server","world",DIMENSION,8,DhProfile.OFFICIAL_16,shape));
        byte[] dto=resource("dh-official-3.3.3-neoforge-ocean","database-tile.dto");var temp=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());long stamp;
        try(var tile=new DhTerrainDecoder(DhLimits.DEFAULT).decode(dto,DhProfile.OFFICIAL_16,position,shape,temp)) {stamp=tile.modifiedTimestamp();}
        cache.write(position,stamp,dto);var s=deliverySession(-64,384);write(s,SESSION,"terrainCache",cache);
        try(var pressure=budget(s).reserve(DhLimits.DEFAULT.ownedBytes()-1)) {
            invoke(s,"restoreCache",new Class<?>[]{DhPosition.class,long.class},position,System.nanoTime());
            pumpUntil(()->read(s,SESSION,"cacheLoading")==null&&worker(s).getCompletedTaskCount()>0,"pressure retry did not settle");
            assertTrue(active(s));assertEquals(Set.of(position),cache.positions());
            assertTrue(((Set<?>)read(s,SESSION,"cachedPositions")).contains(position));assertEquals(0,policy(s).syncTimestamp(position));
        }
        assertEquals(0,budget(s).used());
    }
    @Test void unchangedRestorePreservesPreviouslyVanillaRefinedDestinationAcrossDisconnect() throws Exception {
        verifyDestinationRestore(false);
    }
    @Test void unchangedRestoreRebuildsActualDefaultDestinationAfterVoxyLoss() throws Exception {
        verifyDestinationRestore(true);
    }
    private void verifyDestinationRestore(boolean defaults) throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var old=deliverySession(-64,384);DhClientSession.vanillaLoaded(level,16,-12);
            assertFalse(policy(old).eligible(16,-12));DhClientSession.disconnect();
            var s=deliverySession(-64,384);assertTrue(policy(s).eligible(16,-12));
            var compat=Class.forName("dev.vox.lss.compat.VoxyCompat");
            var resolver=read(null,compat,"classResolver");
            var resolverType=compat.getDeclaredField("classResolver").getType();
            DestinationEngine.defaults=defaults;
            write(null,compat,"classResolver",java.lang.reflect.Proxy.newProxyInstance(resolverType.getClassLoader(),new Class<?>[]{resolverType},(proxy,method,args)->switch(((String)args[0]).substring(((String)args[0]).lastIndexOf('.')+1)) {
                case "WorldIdentifier"->DestinationIdentifier.class;case "WorldEngine"->DestinationEngine.class;
                case "WorldSection"->DestinationSection.class;case "Mapper"->DestinationMapper.class;default->throw new ClassNotFoundException((String)args[0]);
            }));
            try(var reader=DhVoxyBridge.openCache(level)) {
                assertNotNull(reader);
                var temp=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());var p=new DhPosition(6,4,-3);
                try(var tile=new DhTerrainDecoder(DhLimits.DEFAULT).decode(resource("dh-official-3.3.3-neoforge-ocean","database-tile.dto"),DhProfile.OFFICIAL_16,p,new DhTerrainTile.WorldShape(-64,384,true),temp)) {
                    var job=worker(s).submit(()->invoke(s,"deliver",new Class<?>[]{DhTerrainTile.class,DhPosition.class,boolean.class,dev.vox.lss.compat.VoxyTerrainCache.class,boolean.class},tile,p,false,reader,false));
                    pumpUntil(job::isDone,"destination reuse did not finish");assertEquals(true,job.get());
                    assertEquals(defaults?16:0,counter(s,"converted"));assertEquals(defaults?16:0,counter(s,"columns"));
                    assertEquals(defaults?0:16,counter(s,"cachePreservedColumns"));assertEquals(0,counter(s,"cacheReusedColumns"));
                }
                assertEquals(0,DestinationIdentifier.engine.refs);
            } finally {write(null,compat,"classResolver",resolver);}
        }
    }
    private final class DestinationBridge implements AutoCloseable {
        final Class<?> compat=Class.forName("dev.vox.lss.compat.VoxyCompat");
        final Object previous=read(null,compat,"classResolver");
        DestinationBridge(boolean defaults) throws Exception {
            var resolverType=compat.getDeclaredField("classResolver").getType();DestinationEngine.defaults=defaults;
            write(null,compat,"classResolver",java.lang.reflect.Proxy.newProxyInstance(resolverType.getClassLoader(),new Class<?>[]{resolverType},(proxy,method,args)->switch(((String)args[0]).substring(((String)args[0]).lastIndexOf('.')+1)) {
                case "WorldIdentifier"->DestinationIdentifier.class;case "WorldEngine"->DestinationEngine.class;
                case "WorldSection"->DestinationSection.class;case "Mapper"->DestinationMapper.class;default->throw new ClassNotFoundException((String)args[0]);
            }));
        }
        public void close(){write(null,compat,"classResolver",previous);}
    }
    @Test void unavailableDestinationComparisonDefersCachedSourceWithoutOverwritingTerrain() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var shape=new DhTerrainTile.WorldShape(-64,384,true);var p=new DhPosition(6,4,-3);
            var cache=new DhTerrainCache(dir.resolve("dh-cache"),new DhTerrainCache.Key(minecraft.server.ip,"server","world",DIMENSION,8,DhProfile.OFFICIAL_16,shape));
            var dto=resource("dh-official-3.3.3-neoforge-ocean","database-tile.dto");long stamp;
            try(var tile=new DhTerrainDecoder(DhLimits.DEFAULT).decode(dto,DhProfile.OFFICIAL_16,p,shape,new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes()))) {stamp=tile.modifiedTimestamp();}
            cache.write(p,stamp,dto);var s=deliverySession(-64,384);write(s,SESSION,"terrainCache",cache);
            assertNull(DhVoxyBridge.openCache(level),"This ABI has ingest but no destination reader");
            invoke(s,"restoreCache",new Class<?>[]{DhPosition.class,long.class},p,System.nanoTime());
            pumpUntil(()->!sent.isEmpty(),"validation not sent");var request=(DhMessage.Request)codec(s).decode(sent.getLast());
            capture(codec(s).encode(new DhMessage.Response(request.requestId(),null,List.of())),System.nanoTime()).receive();
            settleDelivery(s);
            assertTrue(active(s));assertEquals(0,counter(s,"columns"));assertEquals(0,counter(s,"converted"));
            assertEquals(0,counter(s,"cacheHits"));assertEquals(0,policy(s).syncTimestamp(p));
            assertTrue(((Set<?>)read(s,SESSION,"cachedPositions")).contains(p));assertEquals(Set.of(p),cache.positions());
            assertTrue(((String)read(s,SESSION,"cacheStatus")).contains("destination verification unavailable"));
        }
    }
    @Test void vanillaArrivalAtSecondCacheCheckDoesNotCountThatColumnAsReuse() throws Exception {
        try(var bridge=new DeliveryBridge();var destination=new DestinationBridge(false)) {
            var s=deliverySession(-64,384);var p=new DhPosition(6,4,-3);
            try(var reader=DhVoxyBridge.openCache(level);var tile=new DhTerrainDecoder(DhLimits.DEFAULT).decode(
                    resource("dh-official-3.3.3-neoforge-ocean","database-tile.dto"),DhProfile.OFFICIAL_16,p,
                    new DhTerrainTile.WorldShape(-64,384,true),new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes()))) {
                assertNotNull(reader);
                var job=worker(s).submit(()->invoke(s,"deliver",new Class<?>[]{DhTerrainTile.class,DhPosition.class,boolean.class,dev.vox.lss.compat.VoxyTerrainCache.class,boolean.class},tile,p,false,reader,false));
                takeMain().run(); // first eligibility check permits a comparison
                var authority=takeMain();DhClientSession.vanillaLoaded(level,16,-12);authority.run();
                pumpUntil(job::isDone,"second authority check did not finish");assertEquals(true,job.get());
                assertEquals(15,counter(s,"cachePreservedColumns"));assertEquals(0,counter(s,"cacheReusedColumns"));
                assertEquals(1,counter(s,"skipped"));assertEquals(0,counter(s,"columns"));assertEquals(0,counter(s,"converted"));
            }
        }
    }
    @Test void failedLruTouchPreservesAValidSourceButCannotSkipDestinationVerification() throws Exception {
        var p=new DhPosition(6,4,-3);var dto=resource("dh-official-3.3.3-neoforge-ocean","database-tile.dto");
        long stamp=java.nio.ByteBuffer.wrap(dto).getLong(dto.length-16);
        doReturn(-64).when(level).getMinBuildHeight();doReturn(384).when(level).getHeight();
        var dimension=mock(net.minecraft.world.level.dimension.DimensionType.class);doReturn(true).when(dimension).hasSkyLight();doReturn(dimension).when(level).dimensionType();
        var cache=spy(new DhTerrainCache(dir.resolve("dh-cache"),new DhTerrainCache.Key(minecraft.server.ip,"server","world",DIMENSION,8,DhProfile.OFFICIAL_16,new DhTerrainTile.WorldShape(-64,384,true))));
        cache.write(p,stamp,dto);doThrow(new java.nio.file.AccessDeniedException("busy metadata")).when(cache).touch(p);
        var owner=session();write(owner,SESSION,"terrainCache",cache);policy(owner).accepted(p,0xffff,0xffff,stamp,System.nanoTime());pendingAt(owner,1,p,stamp);
        capture(codec(owner).encode(new DhMessage.Response(1,null,List.of())),System.nanoTime()).receive();settleDelivery(owner);
        assertTrue(active(owner));assertTrue(cache.positions().contains(p));
        assertFalse(policy(owner).hasSnapshot(p),"An unavailable destination reader cannot confirm an old enqueue");
        assertTrue(policy(owner).due(p,System.nanoTime()+3_000_000_000L));
    }

    @Test void timestampZeroCachedSourceRequestsFullDataAndAnAbsentReplyInvalidatesIt() throws Exception {
        var p=new DhPosition(6,4,-3);var shape=new DhTerrainTile.WorldShape(-64,384,true);
        var dto=resource("dh-official-3.3.3-neoforge-ocean","database-tile.dto");
        // Official envelope's first final timestamp is modified time, independent
        // of compressed terrain. Model a valid producer reporting timestamp zero.
        java.nio.ByteBuffer.wrap(dto).putLong(dto.length-16,0L);
        var cache=new DhTerrainCache(dir.resolve("dh-cache"),new DhTerrainCache.Key(minecraft.server.ip,"server","world",DIMENSION,8,DhProfile.OFFICIAL_16,shape));
        cache.write(p,0,dto);var s=deliverySession(-64,384);write(s,SESSION,"terrainCache",cache);
        invoke(s,"restoreCache",new Class<?>[]{DhPosition.class,long.class},p,System.nanoTime());
        pumpUntil(()->!sent.isEmpty(),"timestamp-zero source was not checked");
        var request=(DhMessage.Request)codec(s).decode(sent.getLast());assertEquals(0,request.timestamp());assertEquals(0,counter(s,"columns"));
        capture(codec(s).encode(new DhMessage.Response(request.requestId(),null,List.of())),System.nanoTime()).receive();settleDelivery(s);
        assertTrue(cache.positions().isEmpty());assertEquals(0,counter(s,"cacheHits"));assertTrue(active(s));
    }
    @Test void clearCacheInDhModeClearsOnlyTheCurrentRemoteAddress() throws Exception {
        var shape=new DhTerrainTile.WorldShape(-64,384,true);String other="other-dh-"+UUID.randomUUID();
        var current=new DhTerrainCache(ColumnCacheStore.cacheRoot(),new DhTerrainCache.Key(minecraft.server.ip,"server","world",DIMENSION,8,DhProfile.OFFICIAL_16,shape));
        var retained=new DhTerrainCache(ColumnCacheStore.cacheRoot(),new DhTerrainCache.Key(other,"server","world",DIMENSION,8,DhProfile.OFFICIAL_16,shape));
        current.write(POSITION,123,new byte[]{1});retained.write(POSITION,123,new byte[]{1});var s=session();
        var feedback=new ArrayList<net.minecraft.network.chat.Component>();
        try {
            ClientCommandActions.clearCache(feedback::add);
            assertFalse(active(s));assertTrue(current.positions().isEmpty());assertEquals(Set.of(POSITION),retained.positions());
            assertEquals(1,feedback.size());assertTrue(feedback.getFirst().getString().contains("current server"));
        } finally {ColumnCacheStore.clearDhTerrain(other);}
    }
    public static final class DestinationIdentifier {
        static final DestinationEngine engine=new DestinationEngine();
        public static DestinationIdentifier of(Level level){return new DestinationIdentifier();}
        public DestinationEngine getOrCreateEngine(){return engine;}
    }
    public static final class DestinationEngine {
        static boolean defaults,observedIngest;int refs;
        public void acquireRef(){refs++;}public void releaseRef(){refs--;}
        public DestinationMapper getMapper(){return new DestinationMapper();}
        public DestinationSection acquire(int l,int x,int y,int z){return new DestinationSection(x,y,z);}
    }
    public static final class DestinationSection {
        final int sx,sy,sz;
        DestinationSection(int x,int y,int z){sx=x;sy=y;sz=z;}
        public long[] copyData(){
            var data=new long[32768];Arrays.fill(data,DestinationEngine.defaults?15L<<56:1L<<27);
            if(DestinationEngine.observedIngest) {
                synchronized(me.cortex.voxy.common.world.service.VoxelIngestService.committed) {
                    for(var call:me.cortex.voxy.common.world.service.VoxelIngestService.committed) {
                        if((call.chunkX()>>1)!=sx || (call.sectionY()>>1)!=sy || (call.chunkZ()>>1)!=sz)continue;
                        for(int x=0;x<16;x++)for(int z=0;z<16;z++)for(int y=0;y<16;y++) {
                            var state=call.section().getBlockState(x,y,z);
                            int block=state.isAir()?0:net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.getId(state);
                            int biome=biomes.getId(call.section().getNoiseBiome(x>>2,y>>2,z>>2).value());
                            int light=call.skyLight().get(x,y,z)|(call.blockLight().get(x,y,z)<<4);
                            data[(((call.sectionY()&1)*16+y)<<10)|(((call.chunkZ()&1)*16+z)<<5)|((call.chunkX()&1)*16+x)]
                                    =((long)block<<27)|((long)biome<<47)|((long)light<<56);
                        }
                    }
                }
            }
            return data;
        }
        public int release(){return 0;}
    }
    public static final class DestinationMapper {
        public DestinationState[] getStateEntries(){
            if(DestinationEngine.observedIngest)return java.util.stream.StreamSupport.stream(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.spliterator(),false).map(DestinationState::new).toArray(DestinationState[]::new);
            return new DestinationState[]{new DestinationState(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()),new DestinationState(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState())};
        }
        public DestinationBiome[] getBiomeEntries(){
            if(DestinationEngine.observedIngest) {
                var entries=new DestinationBiome[biomes.size()];
                for(int i=0;i<entries.length;i++)entries[i]=new DestinationBiome(biomes.getKey(biomes.byId(i)).toString());
                return entries;
            }
            return new DestinationBiome[]{new DestinationBiome("minecraft:plains")};
        }
        public static int getBlockId(long value){return (int)(value>>>27&0xfffff);}
        public static int getBiomeId(long value){return (int)(value>>>47&511);}
        public static int getLightId(long value){return (int)(value>>>56&255);}
    }
    public static final class DestinationState {public final net.minecraft.world.level.block.state.BlockState state;DestinationState(net.minecraft.world.level.block.state.BlockState state){this.state=state;}}
    public static final class DestinationBiome {public final String biome;DestinationBiome(String biome){this.biome=biome;}}
    private static final DhPosition CONTENT_POSITION=new DhPosition(6,-3,0);
    private Object deliverySession(int minY,int height) throws Exception {
        // Other suites may already have instrumented ClientLevel's final
        // accessors. Use the explicit inline maker here in isolated and whole
        // runs alike, rather than relying on a final method falling through.
        level=mock(ClientLevel.class,withSettings().mockMaker(MockMakers.INLINE));
        minecraft.level=level;
        doReturn(Level.OVERWORLD).when(level).dimension();
        doReturn(new RegistryAccess.ImmutableRegistryAccess(List.of(biomes)).freeze()).when(level).registryAccess();
        doReturn(minY).when(level).getMinBuildHeight();doReturn(height).when(level).getHeight();
        var dimension=VanillaRegistries.createLookup().lookupOrThrow(Registries.DIMENSION_TYPE)
                .getOrThrow(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD).value();
        doReturn(dimension).when(level).dimensionType();
        var cache=new ClientChunkCache(level,2);
        doReturn(cache).when(level).getChunkSource();
        var s=session();
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,false,0,true,12,1,500));
        write(s,SESSION,"levelKey","fixture-world");
        return s;
    }
    private void pendingAt(Object s,int id,DhPosition position,long stamp) throws Exception {
        var ctor=PENDING.getDeclaredConstructor(DhPosition.class,boolean.class,long.class,long.class);ctor.setAccessible(true);
        pendingMap(s).put(id,ctor.newInstance(position,false,System.nanoTime(),stamp));assembly(s).expect(id,position);
    }
    private static byte[] resource(String corpus,String name) throws Exception {
        try(var stream=DhClientSessionTest.class.getResourceAsStream("/dh-corpus/"+corpus+"/"+name)) {
            assertNotNull(stream);return stream.readAllBytes();
        }
    }
    private DhMessage.Response contentResponse(Object s,int id,boolean removal) throws Exception {
        var response=(DhMessage.Response)codec(s).decode(resource("dh-official-3.3.3-neoforge-content",removal?"target-0008-response.bin":"target-0003-response.bin"));
        return new DhMessage.Response(id,response.bufferId(),response.beacons());
    }
    private void startContent(Object s,int id,boolean removal) throws Exception {
        pendingAt(s,id,CONTENT_POSITION,removal?policy(s).syncTimestamp(CONTENT_POSITION):0);
        capture(resource("dh-official-3.3.3-neoforge-content",removal?"target-0007-split.bin":"target-0002-split.bin"),System.nanoTime()).receive();
        capture(codec(s).encode(contentResponse(s,id,removal)),System.nanoTime()).receive();
    }
    private void settleDelivery(Object s) throws Exception {
        pumpUntil(()->pendingMap(s).isEmpty()&&frameCount(s)==0,"captured session delivery did not settle");
    }
    private void replayContent(Object s,int id,boolean removal) throws Exception {startContent(s,id,removal);settleDelivery(s);}
    private Runnable takeMain() throws Exception {var task=mainTasks.poll(10,TimeUnit.SECONDS);assertNotNull(task);return task;}
    private static long counter(Object s,String name) {return ((java.util.concurrent.atomic.AtomicLong)read(s,SESSION,name)).get();}

    private final class ReadyBridge implements AutoCloseable {
        final DeliveryBridge ingest=new DeliveryBridge();
        final Map<String,Object> probes=new LinkedHashMap<>();
        final Object available=read(null,ModCompat.class,"voxyLoaded");
        final VoxyInstance instance=VoxyCommon.instance;
        ReadyBridge() throws Exception {
            for(String name:List.of("voxyGetInstance","getIngestService","getTaskCount"))probes.put(name,read(null,ingest.bridge,name));
            var lookup=MethodHandles.lookup();
            write(null,ModCompat.class,"voxyLoaded",true);VoxyCommon.instance=new VoxyInstance();
            write(null,ingest.bridge,"voxyGetInstance",lookup.findStatic(VoxyCommon.class,"getInstance",MethodType.methodType(VoxyInstance.class)).asType(MethodType.methodType(Object.class)));
            write(null,ingest.bridge,"getIngestService",lookup.findVirtual(VoxyInstance.class,"getIngestService",MethodType.methodType(me.cortex.voxy.common.world.service.VoxelIngestService.class)).asType(MethodType.methodType(Object.class,Object.class)));
            write(null,ingest.bridge,"getTaskCount",lookup.findVirtual(me.cortex.voxy.common.world.service.VoxelIngestService.class,"getTaskCount",MethodType.methodType(int.class)).asType(MethodType.methodType(int.class,Object.class)));
        }
        public void close() {
            probes.forEach((name,value)->write(null,ingest.bridge,name,value));
            VoxyCommon.instance=instance;write(null,ModCompat.class,"voxyLoaded",available);ingest.close();
        }
    }
    private final class DeliveryBridge implements AutoCloseable {
        final Class<?> bridge=Class.forName("dev.vox.lss.compat.VoxyCompat");
        final Map<String,Object> before=new LinkedHashMap<>();
        final WorldIdentifier world=WorldIdentifier.returned;
        final java.util.concurrent.atomic.AtomicBoolean dead=(java.util.concurrent.atomic.AtomicBoolean)read(null,bridge,"dhBridgeDead");
        final boolean wasDead=dead.get();
        DeliveryBridge() throws Exception {
            for(String name:List.of("worldIdentifierOf","rawIngest"))before.put(name,read(null,bridge,name));
            var lookup=MethodHandles.lookup();
            write(null,bridge,"worldIdentifierOf",lookup.findStatic(WorldIdentifier.class,"of",MethodType.methodType(WorldIdentifier.class,Level.class)));
            write(null,bridge,"rawIngest",lookup.findStatic(me.cortex.voxy.common.world.service.VoxelIngestService.class,"rawIngest",
                    MethodType.methodType(boolean.class,WorldIdentifier.class,net.minecraft.world.level.chunk.LevelChunkSection.class,
                            int.class,int.class,int.class,net.minecraft.world.level.chunk.DataLayer.class,net.minecraft.world.level.chunk.DataLayer.class)));
            dead.set(false);WorldIdentifier.returned=new WorldIdentifier();me.cortex.voxy.common.world.service.VoxelIngestService.reset();
        }
        public void close() {
            for(var entry:before.entrySet())write(null,bridge,entry.getKey(),entry.getValue());
            WorldIdentifier.returned=world;dead.set(wasDead);me.cortex.voxy.common.world.service.VoxelIngestService.reset();
        }
    }

    @Test void loadedTerrainIsCheckedOnEntryButTimeAloneNeverStartsAnotherRequest() throws Exception {
        var s=session();configureFrontier(s);long now=System.nanoTime();
        policy(s).accepted(new DhPosition(6,0,0),1,1,123,now);
        invoke(s,"observeLoaded",new Class<?>[]{long.class,ChunkPos.class},now,new ChunkPos(2,2));
        assertTrue(policy(s).due(new DhPosition(6,0,0),now));
        assertTrue(policy(s).unchanged(new DhPosition(6,0,0),123,now));
        invoke(s,"observeLoaded",new Class<?>[]{long.class,ChunkPos.class},now+30*60_000_000_000L,new ChunkPos(2,2));
        assertFalse(policy(s).due(new DhPosition(6,0,0),Long.MAX_VALUE),"Elapsed time must not poll terrain");
        invoke(s,"observeLoaded",new Class<?>[]{long.class,ChunkPos.class},now+30*60_000_000_000L,new ChunkPos(1000,1000));
        invoke(s,"observeLoaded",new Class<?>[]{long.class,ChunkPos.class},now+30*60_000_000_000L,new ChunkPos(2,2));
        assertTrue(policy(s).due(new DhPosition(6,0,0),Long.MAX_VALUE),"Re-entering terrain is a new load after DH's debounce window");
    }
    @Test void hotSynchronizationPreferencesNegotiateWithoutReplacingTheConnection() throws Exception {
        var s=session();configureFrontier(s);write(s,SESSION,"announced",true);
        settings.store().saveDraft(settings.store().read().hash(),Map.of("lod.dh.sync_on_load",false,"lod.dh.real_time_updates",false));
        settings.commit(settings.prepareReload());invoke(s,"tick");
        var preference=sent.stream().map(bytes->{try{return codec(s).decode(bytes);}catch(Exception e){throw new AssertionError(e);}})
                .filter(DhMessage.SessionConfig.class::isInstance).map(DhMessage.SessionConfig.class::cast).findFirst().orElseThrow();
        assertFalse(preference.synchronize());assertFalse(preference.updates());
        assertSame(s,read(null,DhClientSession.class,"session"));assertTrue(active(s));
    }
    @Test void aClientThreadTimeoutDuringAPushLeavesDhActiveAndSchedulesAcquisition() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=updateSession(-128,512);policy(s).accepted(CONTENT_POSITION,1,1,123,System.nanoTime());
            capture(resource("dh-official-3.3.3-neoforge-content","target-0007-split.bin"),System.nanoTime()).receive();
            var update=contentResponse(s,1,true);
            capture(codec(s).encode(new DhMessage.PartialUpdate("test-world",update.bufferId(),List.of())),System.nanoTime()).receive();
            // Deliberately leave the first client action queued past its five-second deadline.
            long deadline=System.nanoTime()+7_000_000_000L;
            while(frameCount(s)>0 && System.nanoTime()<deadline)Thread.sleep(25);
            pumpUntil(()->frameCount(s)==0,"push timeout did not settle");
            assertTrue(active(s));assertTrue(policy(s).due(CONTENT_POSITION,System.nanoTime()));
            assertEquals(0,policy(s).timestamp(CONTENT_POSITION));
            assertEquals(512,budget(s).used(),"Only the small timestamp fence remains; captured/decoded terrain is released");
        }
    }
    @Test void voxyBackpressureDefersAPushAndCancelsItsCompetingFullRequest() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var trace=ClientTraceLog.toggle();
            var before=new LinkedHashMap<String,Object>();
            for(String field:List.of("voxyGetInstance","getIngestService","getTaskCount"))before.put(field,read(null,bridge.bridge,field));
            var oldInstance=VoxyCommon.instance;
            try {
                var lookup=MethodHandles.lookup();
                write(null,bridge.bridge,"voxyGetInstance",lookup.findStatic(VoxyCommon.class,"getInstance",MethodType.methodType(VoxyInstance.class)).asType(MethodType.methodType(Object.class)));
                write(null,bridge.bridge,"getIngestService",lookup.findVirtual(VoxyInstance.class,"getIngestService",MethodType.methodType(me.cortex.voxy.common.world.service.VoxelIngestService.class)).asType(MethodType.methodType(Object.class,Object.class)));
                write(null,bridge.bridge,"getTaskCount",lookup.findVirtual(me.cortex.voxy.common.world.service.VoxelIngestService.class,"getTaskCount",MethodType.methodType(int.class)).asType(MethodType.methodType(int.class,Object.class)));
                VoxyCommon.instance=new VoxyInstance();me.cortex.voxy.common.world.service.VoxelIngestService.taskCount=257;
                assertEquals(257,DhVoxyBridge.backlog());
                var owner=updateSession(-128,512);policy(owner).accepted(CONTENT_POSITION,1,1,123,System.nanoTime());
                pendingAt(owner,31,CONTENT_POSITION,123);
                capture(resource("dh-official-3.3.3-neoforge-content","target-0007-split.bin"),System.nanoTime()).receive();
                var response=contentResponse(owner,1,true);
                capture(codec(owner).encode(new DhMessage.PartialUpdate("test-world",response.bufferId(),List.of())),System.nanoTime()).receive();
                pumpUntil(()->frameCount(owner)==0 && pendingMap(owner).isEmpty(),"deferred push did not release its competing request");
                assertTrue(active(owner));assertEquals(0,counter(owner,"columns"));assertEquals(1,counter(owner,"droppedUpdateFrames"));
                assertEquals(0,policy(owner).timestamp(CONTENT_POSITION));assertTrue(policy(owner).due(CONTENT_POSITION,System.nanoTime()));
                ClientTraceLog.stop();
                var pressure=Files.readAllLines(trace.path()).stream().map(line->com.google.gson.JsonParser.parseString(line).getAsJsonObject())
                        .filter(r->r.get("t").getAsString().equals("dh_pressure")).map(r->r.get("reason").getAsString()).toList();
                assertEquals(List.of("LIVE_BACKLOG"),pressure);
            } finally {
                before.forEach((key,value)->write(null,bridge.bridge,key,value));VoxyCommon.instance=oldInstance;
            }
        }
    }
    @Test void aTerrainBurstIsDroppedWithinTheBudgetAndCanRecoverWithoutStoppingTheSession() throws Exception {
        var s=updateSession();var owned=new ArrayList<DhClientSession.Inbound>();
        policy(s).accepted(POSITION,1,1,123,System.nanoTime());
        for(int i=0;i<40;i++) {
            var frame=capture(codec(s).encode(new DhMessage.Split(1000+i,new byte[8],true)),System.nanoTime());
            if(frame!=null)owned.add(frame);
        }
        assertTrue(active(s));assertEquals(24,owned.size());assertEquals(16,counter(s,"droppedUpdateFrames"));
        owned.forEach(DhClientSession.Inbound::close);
        var recovery=worker(s).submit(()->{try{invoke(s,"recoverPushPressure");}catch(Exception e){throw new RuntimeException(e);}});
        pumpUntil(()->recovery.isDone() && !(boolean)((java.util.concurrent.atomic.AtomicBoolean)read(s,SESSION,"pushRecovery")).get(),"pressure recovery did not finish");
        recovery.get();assertTrue(active(s));assertEquals(0,budget(s).used());assertEquals(0,policy(s).timestamp(POSITION));
        assembly(s).expect(99,POSITION);assertEquals(0,assembly(s).incompleteBuffers());
    }
    @Test void unsolicitedPartialTerrainAppliesOnlyCompletelyDescribedColumnsWithoutAnOutstandingRequest() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=updateSession();
            var splitFiles=List.of("received-0004.bin","received-0005.bin");
            for(String f:splitFiles)capture(resource("dh-official-3.3.3-neoforge-partial",f),System.nanoTime()).receive();
            var response=(DhMessage.Response)codec(s).decode(resource("dh-official-3.3.3-neoforge-partial","received-0006.bin"));
            capture(codec(s).encode(new DhMessage.PartialUpdate("test-world",response.bufferId(),List.of())),System.nanoTime()).receive();
            pumpUntil(()->frameCount(s)==0,"server update did not settle");
            assertEquals(1,counter(s,"receivedUpdates"),()->"Update rejected: "+read(s,SESSION,"problem"));
            waitWorker(s);Runnable task;while((task=mainTasks.poll())!=null)task.run();
            assertTrue(counter(s,"columns")>0 && counter(s,"columns")<16,"Partial push must preserve unobserved MC columns");
            assertEquals(1,counter(s,"enqueuedUpdates"));assertTrue(pendingMap(s).isEmpty());
            assertEquals(512,budget(s).used(),"Only the bounded update fence remains owned");
            invoke(s,"retire");assertEquals(0,budget(s).used());
        }
    }
    @Test void foreignWorldPushIsDiscardedWithoutDecodingOrDelivery() throws Exception {
        var s=updateSession();
        capture(resource("dh-official-3.3.3-neoforge-content","target-0002-split.bin"),System.nanoTime()).receive();
        var response=contentResponse(s,1,false);
        capture(codec(s).encode(new DhMessage.PartialUpdate("foreign-world",response.bufferId(),List.of())),System.nanoTime()).receive();
        pumpUntil(()->frameCount(s)==0,"foreign update did not settle");
        assertEquals(0,counter(s,"receivedUpdates"));assertEquals(0,counter(s,"columns"));assertEquals(0,budget(s).used());
    }
    @Test void aNewerServerPushRetiresAnOlderRequestSoItsLateReplyCannotOverwriteTheEdit() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=updateSession(-128,512);pendingAt(s,700,CONTENT_POSITION,0);
            capture(resource("dh-official-3.3.3-neoforge-content","target-0007-split.bin"),System.nanoTime()).receive();
            var update=contentResponse(s,1,true);
            capture(codec(s).encode(new DhMessage.PartialUpdate("test-world",update.bufferId(),List.of())),System.nanoTime()).receive();
            pumpUntil(()->frameCount(s)==0 && pendingMap(s).isEmpty(),"newer update did not retire its stale request");
            long delivered=counter(s,"columns");assertTrue(delivered>0,()->"No update delivery: "+read(s,SESSION,"problem"));
            capture(resource("dh-official-3.3.3-neoforge-content","target-0002-split.bin"),System.nanoTime()).receive();
            var old=contentResponse(s,700,false);
            capture(codec(s).encode(old),System.nanoTime()).receive();
            pumpUntil(()->frameCount(s)==0,"stale response did not settle");
            assertEquals(delivered,counter(s,"columns"));assertEquals(512,budget(s).used());
            invoke(s,"retire");assertEquals(0,budget(s).used());
        }
    }
    @Test void aLocallyDisabledLiveUpdateCannotModifyTerrainOrRetainItsBuffer() throws Exception {
        var s=updateSession();
        settings.store().saveDraft(settings.store().read().hash(),Map.of("lod.dh.real_time_updates",false));settings.commit(settings.prepareReload());
        capture(resource("dh-official-3.3.3-neoforge-content","target-0002-split.bin"),System.nanoTime()).receive();
        var update=contentResponse(s,1,false);
        capture(codec(s).encode(new DhMessage.PartialUpdate("test-world",update.bufferId(),List.of())),System.nanoTime()).receive();
        pumpUntil(()->frameCount(s)==0,"disabled update did not settle");
        assertEquals(0,counter(s,"receivedUpdates"));assertEquals(0,counter(s,"columns"));assertEquals(0,budget(s).used());
    }
    @Test void anAcceptedTileStaysHeldAfterItsLiveEditAndAnOlderNewRequestCannotReplacePushedColumns() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=updateSession(-128,512);replayContent(s,700,false);
            assertTrue(policy(s).hasSnapshot(CONTENT_POSITION));
            capture(resource("dh-official-3.3.3-neoforge-content","target-0007-split.bin"),System.nanoTime()).receive();
            var update=contentResponse(s,1,true);
            capture(codec(s).encode(new DhMessage.PartialUpdate("test-world",update.bufferId(),List.of())),System.nanoTime()).receive();
            pumpUntil(()->frameCount(s)==0,"accepted live edit did not settle");
            assertTrue(policy(s).hasSnapshot(CONTENT_POSITION));assertFalse(policy(s).due(CONTENT_POSITION,Long.MAX_VALUE));
            long delivered=counter(s,"columns");
            replayContent(s,701,false); // An older whole-source DTO, even on a newly owned request.
            assertEquals(delivered,counter(s,"columns"),"Newer pushed columns must survive an older database snapshot");
            assertTrue(policy(s).hasSnapshot(CONTENT_POSITION));
            invoke(s,"retire");assertEquals(0,budget(s).used());
        }
    }
    @Test void pressureRecoveryRetainsTheFenceAgainstAnOlderWholeSource() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=updateSession(-128,512);replayContent(s,700,false);
            capture(resource("dh-official-3.3.3-neoforge-content","target-0007-split.bin"),System.nanoTime()).receive();
            var update=contentResponse(s,1,true);
            capture(codec(s).encode(new DhMessage.PartialUpdate("test-world",update.bufferId(),List.of())),System.nanoTime()).receive();
            pumpUntil(()->frameCount(s)==0,"push did not settle");waitWorker(s);
            long delivered=counter(s,"columns");assertTrue(delivered>16);
            recoverPressure(s);
            assertFalse(policy(s).hasSnapshot(CONTENT_POSITION));assertEquals(512,budget(s).used());
            replayContent(s,701,false);
            assertEquals(delivered,counter(s,"columns"),"Recovery must not authorize an older database reply over the applied push");
            assertTrue(active(s));
        }
    }
    @Test void pressureWithLiveUpdatesDisabledPreservesSourceAndAcceptedReceipt() throws Exception {
        var s=updateSession();
        settings.store().saveDraft(settings.store().read().hash(),Map.of("lod.dh.real_time_updates",false));settings.commit(settings.prepareReload());
        var cache=new DhTerrainCache(dir,new DhTerrainCache.Key("test","test","test","test",0,DhProfile.OFFICIAL_16,new DhTerrainTile.WorldShape(-64,384,true)));
        cache.write(POSITION,123,new byte[]{1});write(s,SESSION,"terrainCache",cache);
        policy(s).accepted(POSITION,1,1,123,System.nanoTime());
        recoverPressure(s);
        assertTrue(active(s));assertTrue(policy(s).hasSnapshot(POSITION));assertEquals(123,policy(s).timestamp(POSITION));
        assertEquals(Set.of(POSITION),cache.positions());
    }
    @Test void aNinthIncompleteBufferTriggersRecoveryInsteadOfStoppingDh() throws Exception {
        var s=updateSession();
        for(int i=0;i<9;i++) {
            var frame=capture(codec(s).encode(new DhMessage.Split(100+i,new byte[8],true)),System.nanoTime());assertNotNull(frame);frame.receive();
            pumpUntil(()->frameCount(s)==0,"buffer pressure frame did not settle");
        }
        pumpUntil(()->!(boolean)((java.util.concurrent.atomic.AtomicBoolean)read(s,SESSION,"pushRecovery")).get(),"resource recovery did not settle");
        assertTrue(active(s));assertEquals(1,counter(s,"rawTerrainLosses"));assertEquals(0,assembly(s).incompleteBuffers());assertEquals(0,budget(s).used());
    }
    @Test void rateLimitedRefusalsNeverConsumePermanentRejectionStrikes() throws Exception {
        var s=updateSession();
        for(int i=1;i<=12;i++) {
            long now=System.nanoTime();pending(s,i,false,now);
            capture(codec(s).encode(new DhMessage.Refusal(i,0,"rate limit")),now).receive();
            pumpUntil(()->frameCount(s)==0,"rate limit did not settle");
            assertTrue(policy(s).due(POSITION,System.nanoTime()+3_000_000_000L));assertFalse(policy(s).retryExhausted(POSITION));
        }
        for(int i=20;i<24;i++) {
            long now=System.nanoTime();pending(s,i,false,now);
            capture(codec(s).encode(new DhMessage.Refusal(i,2,"rejected")),now).receive();
            pumpUntil(()->frameCount(s)==0,"permanent refusal did not settle");
        }
        assertTrue(active(s));assertTrue(policy(s).retryExhausted(POSITION));assertFalse(policy(s).due(POSITION,Long.MAX_VALUE));
    }
    @Test void aRecentSyncRequestDoesNotStarveReachableGenerationAtOneRequestPerSecond() throws Exception {
        var s=updateSession();long now=System.nanoTime();
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.SURFACE_ONLY,32,0,0,0,1,false,0,true,32,1,500));
        policy(s).absent(new DhPosition(6,0,0),now-3_000_000_000L,true);
        write(s,SESSION,"lastRequest",now);write(s,SESSION,"lastSyncRequest",now);write(s,SESSION,"lastGenerationRequest",0L);
        var request=scan(s,now);assertNotNull(request);assertEquals(new DhPosition(6,0,0),request.position());assertNull(request.timestamp());
    }
    private void recoverPressure(Object s) throws Exception {
        ((java.util.concurrent.atomic.AtomicBoolean)read(s,SESSION,"pushRecovery")).set(true);
        var recovery=worker(s).submit(()->{try{invoke(s,"recoverPushPressure");}catch(Exception failure){throw new RuntimeException(failure);}});
        pumpUntil(()->recovery.isDone() && !(boolean)((java.util.concurrent.atomic.AtomicBoolean)read(s,SESSION,"pushRecovery")).get(),"pressure recovery did not settle");
        recovery.get();
    }
    @Test void aPushWithoutAnOrderingTimestampCannotBeOverwrittenOrCachedByAnUnprovenWholeSourceReply() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=updateSession(-128,512);replayContent(s,700,false);
            var split=(DhMessage.Split)codec(s).decode(resource("dh-official-3.3.3-neoforge-content","target-0007-split.bin"));
            byte[] bytes=split.bytes();java.nio.ByteBuffer.wrap(bytes).putLong(bytes.length-16,0L);
            capture(codec(s).encode(new DhMessage.Split(split.bufferId(),bytes,true)),System.nanoTime()).receive();
            capture(codec(s).encode(new DhMessage.PartialUpdate("test-world",split.bufferId(),List.of())),System.nanoTime()).receive();
            pumpUntil(()->frameCount(s)==0,"unstamped live edit did not settle");
            long delivered=counter(s,"columns");assertTrue(delivered>16);
            replayContent(s,701,false);
            assertEquals(delivered,counter(s,"columns"));
            invoke(s,"retire");assertEquals(0,budget(s).used());
        }
    }
    @Test void liveUpdatesRemainUsableWhenBothSynchronizationAndGenerationAreDisabled() throws Exception {
        try(var bridge=new DeliveryBridge()) {
            var s=updateSession(-128,512);
            settings.store().saveDraft(settings.store().read().hash(),Map.of("lod.dh.sync_on_load",false));settings.commit(settings.prepareReload());
            write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,0,0,0,0,0,true,32,false,0,0,500));
            capture(resource("dh-official-3.3.3-neoforge-content","target-0007-split.bin"),System.nanoTime()).receive();
            var update=contentResponse(s,1,true);
            capture(codec(s).encode(new DhMessage.PartialUpdate("test-world",update.bufferId(),List.of())),System.nanoTime()).receive();
            pumpUntil(()->frameCount(s)==0,"push-only terrain did not settle");
            assertEquals(16,counter(s,"columns"));assertEquals(1,counter(s,"enqueuedUpdates"));
            assertTrue(sent.isEmpty(),"Push-only support must not issue a synchronization/generation request");
            invoke(s,"retire");assertEquals(0,budget(s).used());
        }
    }
    private Object updateSession() throws Exception { return updateSession(-64,384); }
    private Object updateSession(int minY,int height) throws Exception {
        minecraft.player=mcMock(LocalPlayer.class);write(minecraft.player,LocalPlayer.class,"connection",connection);
        minecraft.player=mcMock(LocalPlayer.class);write(minecraft.player,LocalPlayer.class,"connection",connection);
            doReturn(new ChunkPos(2,2)).when(minecraft.player).chunkPosition();
        var s=deliverySession(minY,height);write(s,SESSION,"levelKey","test-world");write(s,SESSION,"cacheInvalidated",true);
        write(s,SESSION,"config",new DhMessage.SessionConfig(DhMessage.GeneratorPlan.DISABLED,32,0,0,0,0,true,32,true,32,50,500));
        return s;
    }

    private Object session() throws Exception {
        var ctor=SESSION.getDeclaredConstructor(ClientLevel.class);ctor.setAccessible(true);
        Object s=ctor.newInstance(level);sessions.add(s);write(null,DhClientSession.class,"session",s);return s;
    }
    private static <T> T mcMock(Class<T> type) {return mock(type,withSettings().mockMaker(MockMakers.SUBCLASS));}
    private static final class TestMinecraft extends Minecraft {
        private LinkedBlockingQueue<Runnable> mainTasks;
        private ServerData server;
        private TestMinecraft() {super(null);} // never invoked: no native window in unit tests
        @Override public void execute(Runnable task) {mainTasks.add(task);}
        @Override public ServerData getCurrentServer() {return server;}
    }
    @SuppressWarnings("unchecked") private static ConcurrentMap<Integer,Object> pendingMap(Object s) {
        return (ConcurrentMap<Integer,Object>)read(s,SESSION,"pending");
    }
    private static void pending(Object s,int id,boolean generate,long started) throws Exception {
        var ctor=PENDING.getDeclaredConstructor(DhPosition.class,boolean.class,long.class,long.class);ctor.setAccessible(true);
        pendingMap(s).put(id,ctor.newInstance(POSITION,generate,started,0L));assembly(s).expect(id,POSITION);
    }
    private static DhMemoryBudget budget(Object s) {return (DhMemoryBudget)read(s,SESSION,"budget");}
    private static DhAssembly assembly(Object s) {return (DhAssembly)read(s,SESSION,"assembly");}
    private static DhSessionPolicy policy(Object s) {return (DhSessionPolicy)read(s,SESSION,"policy");}
    private static DhProtocolCodec codec(Object s) {return (DhProtocolCodec)read(s,SESSION,"codec");}
    private static ThreadPoolExecutor worker(Object s) {return (ThreadPoolExecutor)read(s,SESSION,"worker");}
    private static boolean active(Object s) {return (boolean)read(s,SESSION,"active");}
    private static int frameCount(Object s) {
        var frames=(Set<?>)read(s,SESSION,"frames");synchronized(frames){return frames.size();}
    }
    private static DhClientSession.Inbound capture(byte[] bytes,long arrival) {
        var buffer=new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {return DhClientSession.capture(buffer,arrival);} finally {buffer.release();}
    }
    private void waitWorker(Object s) throws Exception {
        worker(s).submit(()->{}).get(10,TimeUnit.SECONDS);
    }
    private void pumpUntil(BooleanSupplier complete,String failure) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!complete.getAsBoolean()) {
            long left=deadline-System.nanoTime();assertTrue(left>0,failure);
            var task=mainTasks.poll(Math.min(left,TimeUnit.MILLISECONDS.toNanos(50)),TimeUnit.NANOSECONDS);
            if(task!=null) task.run();
        }
        for(Runnable task;(task=mainTasks.poll())!=null;) task.run();
    }
    private static Class<?> inner(Class<?> outer,String name) {
        return Arrays.stream(outer.getDeclaredClasses()).filter(c->c.getSimpleName().equals(name)).findFirst().orElseThrow();
    }
    private static Object read(Object object,Class<?> type,String name) {
        try {var f=type.getDeclaredField(name);f.setAccessible(true);return f.get(object);}
        catch(ReflectiveOperationException e) {throw new AssertionError(e);}
    }
    private static void write(Object object,Class<?> type,String name,Object value) {
        try {var f=type.getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
        catch(ReflectiveOperationException e) {throw new AssertionError(e);}
    }
    private static Object invoke(Object s,Class<?> type,String method,Class<?>[] signature,Object...args) throws Exception {
        var m=type.getDeclaredMethod(method,signature);m.setAccessible(true);return m.invoke(s,args);
    }
    private static Object invoke(Object s,String method) throws Exception {return invoke(s,method,new Class<?>[0]);}
    private static Object invoke(Object s,String method,Class<?>[] signature,Object... args) throws Exception {
        var m=SESSION.getDeclaredMethod(method,signature);m.setAccessible(true);
        try {return m.invoke(s,args);}
        catch(InvocationTargetException e) {
            if(e.getCause() instanceof Exception failure) throw failure;
            if(e.getCause() instanceof Error failure) throw failure;
            throw e;
        }
    }
}
