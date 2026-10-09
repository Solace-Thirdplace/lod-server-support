package dev.vox.lss.networking.client;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.dh.*;
import dev.vox.lss.common.dh.DhAcquisition.Gate;
import dev.vox.lss.common.diagnostics.DhStatusSnapshot;
import dev.vox.lss.compat.DhVoxyBridge;
import dev.vox.lss.config.LSSClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/** Experimental DH source. Loader transport is installed only by a client entrypoint. */
public final class DhClientSession {
    private static Consumer<byte[]> sender;
    private static String unavailable="DH transport is unavailable on this loader";
    private static DhStatusSnapshot.Reason transportReason=DhStatusSnapshot.Reason.TRANSPORT_UNAVAILABLE;
    private static volatile Session session;
    private static final DhConnectionState connectionState=new DhConnectionState();
    private static final AtomicInteger IDS=new AtomicInteger();
    private static final AtomicLong RANGE_EPOCHS=new AtomicLong();
    private static final Map<Object,DhBandwidthRamp> bandwidthRamps=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<Session> retiring=ConcurrentHashMap.newKeySet();
    private static String status="DH source idle";
    private static boolean resetFailed;
    private static volatile BooleanSupplier nativeAvailable=()->false,channelAvailable=()->false;
    private static volatile boolean nativeOffered;
    private static boolean retryJoinPending,retryUsed;
    private static long retryJoinDeadline;
    private static net.minecraft.client.multiplayer.ServerData retryServer;
    private static Retry pendingRetry;
    private static Object joinConnection;
    private static final Map<Object,DhProfile> confirmedProfiles=Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object,DhProfile> discoveredProfiles=Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean initialPlay=true;
    private static net.minecraft.client.multiplayer.ServerData transferredServer;
    private static long transferUntil;
    private static final Map<String,Long> retryCooldowns=new LinkedHashMap<>();
    private static DhProfile trialProfile=DhProfile.OFFICIAL_16;
    private record Retry(Session owner, Object connection, net.minecraft.client.multiplayer.ServerData server) {}
    // Bounded hints choose only a single fresh legacy trial, never a terrain receipt.
    // Fresh physical joins probe version 16 again; version-15 ASCII configs
    // cannot prove family identity after the server software changes.
    private static final Map<String,DhProfile> protocolHints=new LinkedHashMap<>();
    private DhClientSession() {}
    public static void installTransport(Consumer<byte[]> transport, String reason) {
        installTransport(transport,reason,DhStatusSnapshot.Reason.TRANSPORT_UNAVAILABLE);
    }
    public static void installTransport(Consumer<byte[]> transport, String reason, DhStatusSnapshot.Reason diagnosticReason) {
        sender=transport; unavailable=reason;transportReason=Objects.requireNonNull(diagnosticReason);
    }
    public static void installNativeAvailability(BooleanSupplier available) {nativeAvailable=available;}
    public static void installChannelAvailability(BooleanSupplier available) {channelAvailable=available;}
    private static boolean hasDhChannel() {
        try {return channelAvailable.getAsBoolean();}
        catch(RuntimeException | LinkageError unavailable) {return false;}
    }
    public static boolean fallbackEnabled() {return LSSClientConfig.CONFIG.snapshot().lod().source().equals("distant_horizons");}
    public static boolean selected() {
        if(!fallbackEnabled() || nativeOffered)return false;
        try {return !nativeAvailable.getAsBoolean();}
        catch(RuntimeException | LinkageError unavailable) {return false;}
    }
    /** A native offer wins even when it disables service; DH must not bypass it. */
    public static void nativeOffer() {
        nativeOffered=true;pendingRetry=null;
        var old=session;session=null;if(old!=null)old.beginRetire();
        connectionState.disconnect();resetFailed=false;status="Native "+dev.vox.lss.common.Brand.shortName()+" offer received; DH fallback suppressed (including disabled or incompatible native service)";
    }
    public static void onJoin() {
        var mc=Minecraft.getInstance();
        Object connection=mc.getConnection()==null?null:mc.getConnection().getConnection();
        // A slow automatic join consumes the same allowance; elapsed time is not
        // permission for a second reconnect. Same-connection reconfiguration starts
        // fresh family discovery and disables discovery reconnects during play.
        boolean retry=retryJoinPending && mc.getCurrentServer()==retryServer;
        boolean same=connection!=null && connection==joinConnection;
        disconnect(false);retryUsed=retry || same && retryUsed;retryJoinPending=false;
        initialPlay=!same && !retry && !(mc.getCurrentServer()==transferredServer && System.nanoTime()<transferUntil);
        if(same) {confirmedProfiles.remove(mc.getConnection());discoveredProfiles.remove(mc.getConnection());}
        if(!retry)trialProfile=DhProfile.OFFICIAL_16;
        joinConnection=connection;
        retryServer=null;
    }
    public static boolean running() { return session!=null && session.active; }
    /** Permission alone is not a recoverable terrain service for a destructive reset. */
    public static boolean canAcquire() {
        var s=session;var mc=Minecraft.getInstance();
        if(!selected() || !LSSClientConfig.CONFIG.receiveServerLods() || sender==null || !hasDhChannel()
                || s==null || !s.active || !s.familyConfirmed || s.config==null || s.levelKey==null
                || mc.getConnection()!=s.connection || mc.level!=s.level || DhVoxyBridge.failed())return false;
        var cfg=s.config;
        return !s.profile.support() && s.syncAvailable() && cfg.syncDistance()>0
                || cfg.plan()!=DhMessage.GeneratorPlan.DISABLED && cfg.generationRate()>0 && cfg.generationDistance()>0;
    }
    public static boolean ownsWork() {return session!=null || !retiring.isEmpty();}
    /** Transfer preserves ServerData for the hub. Never reconnect automatically to it. */
    public static void transferred() {transferred(Minecraft.getInstance().getCurrentServer());}
    public static void transferred(net.minecraft.client.multiplayer.ServerData server) {
        transferredServer=server;
        transferUntil=System.nanoTime()+300_000_000_000L;pendingRetry=null;
    }
    public static String status() { var s=session; return s==null ? status : s.describe(); }
    private static DhProfile probeProfile() {
        var mc=Minecraft.getInstance();
        Object physical=mc==null?null:mc.getConnection();
        var known=confirmedProfiles.get(physical);
        if(known==null)known=discoveredProfiles.get(physical);
        if(known!=null)return known;
        if(!retryUsed)return DhProfile.OFFICIAL_16;
        return trialProfile;
    }
    private static void rememberRetry(String address) {
        retryCooldowns.put(address,System.nanoTime()+120_000_000_000L);
        if(retryCooldowns.size()>256)retryCooldowns.remove(retryCooldowns.keySet().iterator().next());
    }
    private static void rememberProfile(String address,DhProfile profile) {
        if(address==null || address.length()>4096)return;
        synchronized(protocolHints) {
            protocolHints.put(address,profile);
            if(protocolHints.size()>256)protocolHints.remove(protocolHints.keySet().iterator().next());
        }
    }
    /** Client-owner capture; immutable allowlisted export never retains the live session. */
    public static DhStatusSnapshot diagnostics() {
        var s=session;
        if(s!=null) return s.diagnostics();
        var level=Minecraft.getInstance().level;
        var state=!LSSClientConfig.CONFIG.receiveServerLods() || level==null
                ? DhStatusSnapshot.State.PAUSED : sender==null ? DhStatusSnapshot.State.NEED_TRANSPORT
                : connectionState.blocked(level.dimension().location().toString()) ? DhStatusSnapshot.State.BLOCKED
                : resetFailed ? DhStatusSnapshot.State.STOPPED
                : !DhVoxyBridge.available()?DhStatusSnapshot.State.NEED_VOXY:DhStatusSnapshot.State.BETWEEN_SESSIONS;
        return new DhStatusSnapshot(probeProfile(),state,0,0,0,0,0,0,0,0,0,0,0,0,0,-1,0,0,0,0,
                LSSClientConfig.CONFIG.snapshot().lod().dh().syncOnLoad(),false,
                LSSClientConfig.CONFIG.snapshot().lod().dh().realTimeUpdates(),false,0,0,-1,0,0,0,0,0,
                nativeOffered?DhStatusSnapshot.Reason.NATIVE_OFFER:!selected()?DhStatusSnapshot.Reason.NATIVE_PREFERRED
                :!initialPlay && Minecraft.getInstance().getCurrentServer()==transferredServer?DhStatusSnapshot.Reason.TRANSFER_RETRY_DISABLED
                :level!=null && connectionState.blocked(level.dimension().location().toString())?DhStatusSnapshot.Reason.WORLD_CHANGED
                :resetFailed?DhStatusSnapshot.Reason.SOURCE_STOPPED:sender==null?transportReason:DhStatusSnapshot.Reason.NONE,0,0,
                state==DhStatusSnapshot.State.NEED_VOXY?Gate.VOXY:Gate.STOPPED,0,-1);
    }
    public static void disconnect() { disconnect(true); }
    private static void disconnect(boolean withdraw) {
        var old=session; session=null;
        if(old!=null) old.beginRetire(withdraw);
        connectionState.disconnect(); resetFailed=false; status="DH source disconnected";
        nativeOffered=false;pendingRetry=null;
    }
    /** Stock 3.2.0-b and 3.3.3 on MC 1.21.1 throw in their logger before a mismatch reply.
     * One fresh connection may try legacy controls after a silent version-16
     * handshake. Official DH can retire its DH state on mismatch; Support replies without closing it. Silence never authorizes terrain acceptance. */
    private static boolean probeLegacy(Session owner) {
        var mc=Minecraft.getInstance();
        if(session!=owner || !owner.active || owner.config!=null || owner.profile!=DhProfile.OFFICIAL_16
                || retryUsed || !initialPlay || confirmedProfiles.containsKey(owner.connection) || pendingRetry!=null || !selected()
                || !LSSClientConfig.CONFIG.receiveServerLods() || mc.getConnection()!=owner.connection
                || mc.level!=owner.level)return false;
        if(!hasDhChannel()) {owner.fail("DH server channel is unavailable; automatic reconnect cancelled");return false;}
        var server=mc.getCurrentServer();
        if(server==null || server!=owner.remote)return false;
        if(System.nanoTime()<retryCooldowns.getOrDefault(server.ip,0L)) {
            owner.retrySuppressed=true;owner.problem="Legacy trial suppressed after an unsuccessful recent trial";return false;
        }
        trialProfile=DhProfile.OFFICIAL_15;
        rememberProfile(server.ip,DhProfile.OFFICIAL_15);
        rememberRetry(server.ip);
        pendingRetry=new Retry(owner,mc.getConnection().getConnection(),server);
        owner.fail("No protocol-16 reply; reconnecting once to check legacy official DH");
        return true;
    }
    public static void tick() {
        if(ClientTraceLog.enabled() && selected() && DhClientTrace.sampleDue(System.nanoTime()))traceSnapshot();
        for(var old:List.copyOf(retiring))if(old.worker.isTerminated()){old.closeResources();retiring.remove(old);}
        var mc=Minecraft.getInstance();
        if(retryJoinPending && mc.getConnection()==null && mc.level==null
                && !(mc.screen instanceof net.minecraft.client.gui.screens.ConnectScreen)) {
            retryJoinPending=false;retryServer=null;
        }
        var retry=pendingRetry;
        if(retry!=null) {
            if(System.nanoTime()-retry.owner.started<5_000_000_000L)return;
            pendingRetry=null;
            if(!retryUsed && initialPlay && hasDhChannel() && selected() && LSSClientConfig.CONFIG.receiveServerLods()
                    && mc.getConnection()!=null && mc.getConnection().getConnection()==retry.connection
                    && mc.getCurrentServer()==retry.server && mc.level==retry.owner.level) {
                retryUsed=true;retryJoinPending=true;retryServer=retry.server;
                retryJoinDeadline=System.nanoTime()+60_000_000_000L;
                mc.level.disconnect();mc.disconnect();
                net.minecraft.client.gui.screens.ConnectScreen.startConnecting(
                        new net.minecraft.client.gui.screens.TitleScreen(),mc,
                        net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(retry.server.ip),
                        retry.server,false,null);
                return;
            }
        }
        if(!selected() || !LSSClientConfig.CONFIG.receiveServerLods() || mc.level==null || mc.getConnection()==null) {
            var old=session; session=null;
            if(old!=null) {if(mc.getConnection()!=null)initialPlay=false;old.beginRetire();}
            resetFailed=false;
            status=nativeOffered?"Native "+dev.vox.lss.common.Brand.shortName()+" offer received; DH fallback suppressed (including disabled or incompatible native service)"
                    :selected()?"DH source paused (receiving disabled or no world)":"DH fallback idle (native LSS preferred)"; return;
        }
        if(sender==null) { status=unavailable; return; }
        if(resetFailed) return; // Another explicit reset or a new session boundary may retry the failed sweep.
        if(!DhVoxyBridge.available()) { var old=session; session=null; if(old!=null) old.beginRetire(); status="DH source requires an initialized Voxy bridge"; return; }
        if(session!=null && session.level!=mc.level) {initialPlay=false;var old=session;session=null;old.beginRetire();}
        if(connectionState.blocked(mc.level.dimension().location().toString())) { status="DH vanilla authority limit reached; reconnect to resume"; return; }
        if(session==null) {
            try { session=new Session(mc.level); }
            catch(IllegalStateException e) { status=e.getMessage(); return; }
            // Events cover every future load. Seed chunks already held before the first tick.
            var player=mc.player;
            int radius=Math.min(64,mc.options.renderDistance().get()+2);
            if(player!=null) for(int x=player.chunkPosition().x-radius;x<=player.chunkPosition().x+radius;x++)
                for(int z=player.chunkPosition().z-radius;z<=player.chunkPosition().z+radius;z++)
                    if(loaded(mc.level,x,z)) vanillaLoaded(mc.level,x,z);
        }
        session.tick();
    }
    /** Client-owner sampling is independent of the native request manager and remains active while DH waits. */
    private static void traceSnapshot() {
        var s=session;
        int inbound=0,generation=0;long oldest=-1,losses=0,queuedBytes=0;
        boolean recovery=false,cacheReady=true;
        if(s!=null) {
            synchronized(s.frames){inbound=s.frames.size();for(var frame:s.frames)if(!frame.processing && frame.bytes!=null)queuedBytes+=frame.bytes.length;}
            long now=System.nanoTime();
            for(var request:s.pending.values()) {
                oldest=Math.max(oldest,Math.max(0,(now-request.started)/1_000_000L));
                if(request.generation)generation++;
            }
            losses=s.rawTerrainLosses.get();recovery=s.pushRecovery.get();cacheReady=s.cacheReady;
        }
        var player=Minecraft.getInstance().player;
        var chunk=player==null?null:player.chunkPosition();
        var mc=Minecraft.getInstance();
        if(player!=null && mc.level!=null) {
            int view=mc.options==null?8:mc.options.getEffectiveRenderDistance();
            ClientNetTrace.maybeEmit(mc,player,mc.level,view,s==null?0:s.worker.getQueue().size(),
                    queuedBytes,DhVoxyBridge.backlog(),s==null?0:s.pending.size(),
                    -1,-1,-1,-1,ClientNetTrace.Source.DH,s==null?mc.getConnection():s,
                    s==null?0:s.wireBytes.get(),-1);
        }
        DhClientTrace.sample(diagnostics(),inbound,losses,oldest,generation,recovery,cacheReady,
                chunk==null?null:chunk.x,chunk==null?null:chunk.z);
    }
    public static void vanillaLoaded(ClientLevel level,int x,int z) {
        if(!fallbackEnabled()) return;
        var s=session;
        try { connectionState.vanilla(level.dimension().location().toString(),x,z); }
        catch(IllegalStateException e) {
            if(s!=null && s.level==level && s.active) s.fail(e.getMessage());
        }
    }
    static boolean loaded(ClientLevel level,int x,int z) {
        return level.getChunkSource().getChunk(x,z,ChunkStatus.FULL,false)!=null;
    }
    /** Called in the payload codec, before Fabric queues a main-thread callback. */
    public static void rejectTransportFrame(String reason) { var s=session; if(s!=null) s.fail(reason); }
    public static Inbound capture(FriendlyByteBuf buffer) {
        return capture(buffer,0,false);
    }
    /** Explicit timestamps are a deterministic test seam. Production captures timestamp under the ownership lock. */
    public static Inbound capture(FriendlyByteBuf buffer,long arrivalNanos) {
        return capture(buffer,arrivalNanos,true);
    }
    private static Inbound capture(FriendlyByteBuf buffer,long arrivalNanos,boolean explicitArrival) {
        int size=buffer.readableBytes(); var s=session;
        if(size>DhLimits.DEFAULT.frameBytes()) { buffer.skipBytes(size); if(s!=null) s.fail("Oversized DH frame"); return null; }
        if(s==null || !s.active) { buffer.skipBytes(size); return null; }
        boolean terrain=size>=4 && (buffer.getUnsignedShort(buffer.readerIndex()+2)==9 || buffer.getUnsignedShort(buffer.readerIndex()+2)==10);
        synchronized(s.frames) {
            // Publication and cleanup share this lock. An actual new capture cannot
            // publish an older timestamp after a maintenance cutoff has passed it.
            if(!explicitArrival)arrivalNanos=System.nanoTime();
            if(s.active && terrain && s.frames.size()>=24) {
                DhClientTrace.pressure(DhClientTrace.Pressure.INBOUND_QUEUE);
                buffer.skipBytes(size);s.pushRecovery.set(true);s.rawTerrainLosses.incrementAndGet();s.droppedUpdateFrames.incrementAndGet();return null;
            }
            if(!s.active || s.frames.size()>=32) { buffer.skipBytes(size); s.fail("DH inbound queue limit reached"); return null; }
            try {
                var lease=s.budget.reserve(size+256L);
                try {
                    byte[] bytes=new byte[size]; buffer.readBytes(bytes);
                    var frame=new Inbound(s,bytes,lease,arrivalNanos); s.frames.add(frame); s.lastInboundArrival=arrivalNanos; return frame;
                } catch(RuntimeException|Error e) { lease.close(); throw e; }
            } catch(DhCodecException e) {
                buffer.skipBytes(buffer.readableBytes());
                if(terrain) {DhClientTrace.pressure(DhClientTrace.Pressure.OWNED_BYTES);s.pushRecovery.set(true);s.rawTerrainLosses.incrementAndGet();s.droppedUpdateFrames.incrementAndGet();}
                else s.fail(e.getMessage());
                return null;
            }
        }
    }
    /** Payload ownership is bounded even while it waits for the loader callback. */
    public static final class Inbound implements AutoCloseable {
        private final Session owner;
        private byte[] bytes;
        private final DhMemoryBudget.Lease lease;
        private boolean processing;
        private final long arrivalNanos;
        private Inbound(Session owner,byte[] bytes,DhMemoryBudget.Lease lease,long arrivalNanos) {
            this.owner=owner; this.bytes=bytes; this.lease=lease; this.arrivalNanos=arrivalNanos;
        }
        public void receive() {
            try { owner.worker.execute(() -> {
                synchronized(owner.frames) { if(bytes==null) return; processing=true; }
                try { if(owner.active) owner.receive(bytes,arrivalNanos); }
                catch(DhCodecException e) {
                    if(e.kind()==DhCodecException.Kind.RESOURCE_LIMIT)owner.recoverResourcePressure();
                    else if(e.kind()!=DhCodecException.Kind.EXPIRED && e.kind()!=DhCodecException.Kind.UNOWNED)
                        owner.fail(e.getClass().getSimpleName()+": "+e.getMessage());
                }
                catch(Exception|LinkageError e) { owner.fail(e.getClass().getSimpleName()+": "+e.getMessage()); }
                finally { close(); if(!owner.active) owner.closeResources(); }
            }); } catch(RejectedExecutionException e) { close(); if(owner.active) owner.fail("DH worker queue full"); }
        }
        @Override public void close() {
            synchronized(owner.frames) { bytes=null; lease.close(); owner.frames.remove(this); }
        }
    }
    /** Reset must abort its wipe if the conversion worker cannot retire. */
    public static void drainForReset() {
        var s=session;
        for(var old:List.copyOf(retiring)) if(!old.retire())
            throw new IllegalStateException("Previous DH conversion did not stop; reset aborted");
        if(s!=null && !s.retire()) { s.problem="DH conversion did not stop; reset aborted"; throw new IllegalStateException(s.problem); }
        try {
            invalidateCurrentNativeCache();
            var server=Minecraft.getInstance().getCurrentServer();
            if(server!=null && server.ip!=null)ColumnCacheStore.clearDhTerrain(server.ip);
        }
        catch(RuntimeException e) {
            if(s!=null) s.problem="DH reset failed: "+sanitizeReason(e.getMessage());
            status="DH reset failed: "+sanitizeReason(e.getMessage());
            session=null; resetFailed=true;
            throw e;
        }
    }
    public static void resumeAfterReset() { connectionState.resetReceipts(); session=null; resetFailed=false; status="DH reset complete; reconnecting terrain source"; }

    private static String sanitizeReason(String text) {
        if(text==null) return "Unknown DH failure";
        String clean=text.replaceAll("§.", "").replaceAll("[\\p{Cntrl}]", " ");
        return clean.substring(0,Math.min(512,clean.length()));
    }
    private static void invalidateCurrentNativeCache() {
        var server=Minecraft.getInstance().getCurrentServer();
        if(server==null || server.ip==null) return; // No remote cache exists in single-player.
        ColumnCacheStore.clearForServersStrict(ClientNetGlue.dhCacheSweep(server.ip));
    }

    private static final class Session {
        final ClientLevel level;
        final Object connection=Minecraft.getInstance().getConnection();
        final Object physicalConnection=Minecraft.getInstance().getConnection().getConnection();
        final net.minecraft.client.multiplayer.ServerData remote=Minecraft.getInstance().getCurrentServer();
        final DhProfile profile;
        final DhProtocolCodec codec;
        // Physical-connection ownership preserves earned ramp progress across dimensions and reception toggles.
        final DhBandwidthRamp bandwidthRamp=bandwidthRamps.computeIfAbsent(physicalConnection,
                key->new DhBandwidthRamp(LSSClientConfig.CONFIG.enableAdaptiveTransferRate(),
                        LSSClientConfig.CONFIG.enableJoinSlowStart()));
        final DhLimits sessionLimits=bandwidthRamp.startedSlow()?DhLimits.DEFAULT.withLifetime(120_000_000_000L):DhLimits.DEFAULT;
        final DhMemoryBudget budget=new DhMemoryBudget(sessionLimits.ownedBytes());
        final DhAssembly assembly=new DhAssembly(sessionLimits,budget,System::nanoTime,this::requestLifetime);
        final DhSessionPolicy policy;
        final Set<Inbound> frames=new HashSet<>();
        final ThreadPoolExecutor worker;
        final ConcurrentHashMap<Integer,Pending> pending=new ConcurrentHashMap<>();
        final DhColumnConverter converter;
        volatile boolean active=true;
        volatile DhMessage.SessionConfig config;
        volatile String levelKey,serverKey;
        volatile DhTerrainCache terrainCache;
        final Set<DhPosition> cachedPositions=new HashSet<>(),rejoinChecks=new HashSet<>();
        final ConcurrentHashMap<DhPosition,Long> cacheChecks=new ConcurrentHashMap<>();
        volatile boolean cacheReady=true;
        volatile String cacheStatus="waiting for world identity";
        volatile DhPosition cacheLoading;
        long cacheWorkStarted,cacheJob;
        Future<?> cacheTask;
        final AtomicLong cacheHits=new AtomicLong(),cacheWrites=new AtomicLong(),cacheDeclined=new AtomicLong();
        volatile Set<DhPosition> protectedPositions=Set.of();
        final AtomicLong cacheReusedColumns=new AtomicLong(),cachePreservedColumns=new AtomicLong();
        final AtomicBoolean cacheWarned=new AtomicBoolean();
        volatile String problem="waiting for DH config";
        // Client-thread observations use primitives even while a large scan visits blocked tiles.
        long acquisitionGates=1L<<Gate.CONFIG.ordinal(),acquisitionWaitNanos=Long.MAX_VALUE,acquisitionAt=System.nanoTime();
        boolean scanGenerationBlocked,scanSyncBlocked,scanning,candidateObserved;
        Gate acquisitionPrimary=Gate.CONFIG;
        DhAcquisition terminalObservation=DhAcquisition.OPEN;long terminalAt;ChunkPos terminalCenter;
        final long started=System.nanoTime();
        int sentBandwidth;
        long sentAt,lastRequest,lastGenerationRequest,lastSyncRequest,maintenanceAt,nextScanAt;
        long generationBackoffUntil,syncBackoffUntil;
        volatile long lastInboundArrival;
        int handshakeIdleTicks;
        boolean sentSync=true,sentUpdates=true;
        final AtomicBoolean preferencesWithdrawn=new AtomicBoolean();
        final AtomicLong receivedUpdates=new AtomicLong(),enqueuedUpdates=new AtomicLong(),droppedUpdateFrames=new AtomicLong();
        final AtomicBoolean pushRecovery=new AtomicBoolean(),recoveryQueued=new AtomicBoolean();
        final AtomicLong rawTerrainLosses=new AtomicLong();
        volatile boolean cacheDisabled;
        boolean familyConfirmed;
        boolean retrySuppressed;
        final Map<DhPosition,Long> loadedAt=new java.util.LinkedHashMap<>();
        Set<DhPosition> visible=Set.of();
        ChunkPos visibleCenter;
        record RangeContext(int x,int z,int range,int generationDistance,int syncDistance,boolean generation,
                            boolean synchronize,int centerX,int centerZ,int borderRadius) {}
        RangeContext rangeContext;
        long rangeEpoch;
        int visibleRange=-1;
        final Map<DhPosition,UpdateFence> updateFences=new java.util.LinkedHashMap<>();
        int partialAcceptedMask;
        static final class UpdateFence implements AutoCloseable {
            final long[] timestamp=new long[16];
            final DhMemoryBudget.Lease lease;
            int mask;
            long requiredTimestamp;
            UpdateFence(DhMemoryBudget.Lease lease) {this.lease=lease;}
            long latest() {
                long latest=requiredTimestamp;
                for(int c=0;c<16;c++)if((mask&(1<<c))!=0)latest=Math.max(latest,timestamp[c]);
                return latest;
            }
            boolean supersededBy(long stamp) {
                if(stamp<=0 || stamp<=latest())return false;
                for(int c=0;c<16;c++)if((mask&(1<<c))!=0 && timestamp[c]<=0)return false;
                return true;
            }
            boolean protects(int c,long stamp) {return (mask&(1<<c))!=0 && (timestamp[c]<=0 || stamp<=timestamp[c]);}
            public void close(){lease.close();}
        }
        void closeResources() {
            assembly.close();updateFences.values().forEach(UpdateFence::close);updateFences.clear();
        }
        UpdateFence updateFence(DhPosition position) throws DhCodecException {
            var fence=updateFences.get(position);
            if(fence!=null)return fence;
            if(updateFences.size()>=DhSessionPolicy.MAX_RECEIPTS) {
                var victim=updateFences.keySet().stream().filter(p -> pending.values().stream().noneMatch(r -> r.position.equals(p))).findFirst().orElse(null);
                if(victim==null)throw new DhCodecException(DhCodecException.Kind.RESOURCE_LIMIT,"DH live update history is full");
                updateFences.remove(victim).close();
            }
            fence=new UpdateFence(budget.reserve(512));updateFences.put(position,fence);return fence;
        }
        final AtomicBoolean maintenanceQueued=new AtomicBoolean();
        boolean announced,cacheInvalidated;
        final AtomicLong tiles=new AtomicLong(),columns=new AtomicLong(),wireBytes=new AtomicLong();
        final AtomicLong requested=new AtomicLong(),incomplete=new AtomicLong(),refused=new AtomicLong();
        final AtomicLong converted=new AtomicLong(),skipped=new AtomicLong(),failedColumns=new AtomicLong();
        final AtomicLong completeCoverage=new AtomicLong(),partialCoverage=new AtomicLong(),unknownCoverage=new AtomicLong();
        volatile long lastSync;
        int unanswered,rejectedTiles,retryBurst;
        static final class Pending {
            final DhPosition position;final boolean generation;final long started,timestamp,rangeEpoch,lifetime;
            // Worker completion remains protected until the client applies its queued bookkeeping.
            volatile boolean answered;
            Pending(DhPosition position,boolean generation,long started,long timestamp,long rangeEpoch,long lifetime) {
                this.position=position;this.generation=generation;this.started=started;this.timestamp=timestamp;
                this.rangeEpoch=rangeEpoch;this.lifetime=lifetime;
            }
            Pending(DhPosition position,boolean generation,long started,long timestamp) {this(position,generation,started,timestamp,0,DhLimits.DEFAULT.lifetimeNanos());}
            boolean generation(){return generation;}
        }
        Session(ClientLevel level) {
            this.level=level;
            policy=connectionState.policy(level.dimension().location().toString());
            profile=probeProfile();
            familyConfirmed=profile!=DhProfile.OFFICIAL_15 || !retryUsed
                    || discoveredProfiles.get(connection)==profile || confirmedProfiles.get(connection)==profile;
            codec=new DhProtocolCodec(profile,DhLimits.DEFAULT);
            converter=new DhColumnConverter(level);
            worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(40),r->{
                var t=new Thread(r,"LSS-DH-Decode"); t.setDaemon(true); return t;
            });
        }
        String describe() { return "DH "+profile+": "+problem+"; enqueued tiles="+tiles+", columns="+columns
                +", cache admissions declined="+cacheDeclined+", cached tiles="+cacheHits+", Voxy reused columns="+cacheReusedColumns+", preserved columns="+cachePreservedColumns+", wire="+wireBytes+" bytes, owned="+budget.used()+", requests="+pending.size()+", cache="+cacheStatus; }
        void confirmProfile() {
            confirmedProfiles.put(connection,profile);
            if(remote!=null)retryCooldowns.remove(remote.ip);
            retrySuppressed=false;
        }
        int effectiveRange() {
            var cfg=config;
            if(cfg==null) return 0;
            int maximum=profile.support()?Math.min(32,cfg.generationDistance())
                    :Math.min(128,cfg.synchronize()?cfg.syncDistance():cfg.generationDistance());
            if(updatesAllowed())maximum=Math.max(maximum,Math.min(128,cfg.updateDistance()));
            int override=LSSClientConfig.CONFIG.lodDistanceChunks();
            return override==0?maximum:Math.min(override,maximum);
        }
        DhStatusSnapshot diagnostics() {
            var cfg=config;
            var state=!LSSClientConfig.CONFIG.receiveServerLods()?DhStatusSnapshot.State.PAUSED
                    :connectionState.blocked(level.dimension().location().toString())?DhStatusSnapshot.State.BLOCKED
                    :!active?DhStatusSnapshot.State.STOPPED:cfg==null?DhStatusSnapshot.State.WAIT_CONFIG
                    :levelKey==null?DhStatusSnapshot.State.WAIT_WORLD:!familyConfirmed?DhStatusSnapshot.State.WAIT_FAMILY:!DhVoxyBridge.ready(level)
                    ?DhStatusSnapshot.State.NEED_VOXY:DhStatusSnapshot.State.READY;
            var wait=switch(state) {
                case PAUSED,BLOCKED,STOPPED -> DhAcquisition.waiting(Gate.STOPPED);
                case WAIT_CONFIG -> DhAcquisition.waiting(!announced&&!hasDhChannel()?Gate.CHANNEL:Gate.CONFIG);
                case WAIT_WORLD -> DhAcquisition.waiting(Gate.WORLD);
                case WAIT_FAMILY -> DhAcquisition.waiting(Gate.FAMILY);
                case NEED_VOXY -> DhAcquisition.waiting(Gate.VOXY);
                default -> acquisitionGates==0?DhAcquisition.OPEN:new DhAcquisition(
                        acquisitionPrimary,acquisitionGates,acquisitionWaitNanos);
            };
            return new DhStatusSnapshot(profile,state,requested.get(),tiles.get(),incomplete.get(),refused.get(),
                    converted.get(),columns.get(),skipped.get(),failedColumns.get(),wireBytes.get(),budget.used(),budget.peak(),
                    worker.getQueue().size(),pending.size(),DhVoxyBridge.backlog(),effectiveRange(),
                    cfg==null?0:cfg.generationRate(),cfg==null?0:cfg.syncRate(),cfg==null?0:cfg.bandwidthKB(),
                    LSSClientConfig.CONFIG.snapshot().lod().dh().syncOnLoad(),syncAvailable(),
                    LSSClientConfig.CONFIG.snapshot().lod().dh().realTimeUpdates(),updatesAllowed(),receivedUpdates.get(),enqueuedUpdates.get(),
                    lastSync==0?-1:Math.max(0,(System.nanoTime()-lastSync)/1_000_000L),
                    completeCoverage.get(),partialCoverage.get(),unknownCoverage.get(),cacheDeclined.get(),droppedUpdateFrames.get(),
                    connectionState.blocked(level.dimension().location().toString())?DhStatusSnapshot.Reason.WORLD_CHANGED
                    :retrySuppressed?DhStatusSnapshot.Reason.LEGACY_TRIAL_SUPPRESSED:!active?DhStatusSnapshot.Reason.SOURCE_STOPPED:!familyConfirmed?DhStatusSnapshot.Reason.FAMILY_UNCONFIRMED
                    :cacheDisabled?DhStatusSnapshot.Reason.LOCAL_CACHE_UNAVAILABLE:retryUsed?DhStatusSnapshot.Reason.LEGACY_TRIAL
                    :!initialPlay && remote==transferredServer?DhStatusSnapshot.Reason.TRANSFER_RETRY_DISABLED:DhStatusSnapshot.Reason.NONE,
                    cacheReusedColumns.get(),cachePreservedColumns.get(),wait.primary(),wait.gates(),wait.waitMillis(System.nanoTime()-acquisitionAt));
        }
        void beginObservation(long now) {candidateObserved=false;acquisitionGates=0;acquisitionWaitNanos=0;acquisitionAt=now;acquisitionPrimary=Gate.REQUESTED;}
        void waitFor(Gate gate) {waitFor(gate,Long.MAX_VALUE);}
        void waitFor(Gate gate,long nanos) {waitFor(DhAcquisition.waiting(gate,nanos));}
        void waitFor(DhAcquisition wait) {
            if(wait.gates()==0)return;
            if(scanning) {
                if(candidateObserved){acquisitionGates|=wait.gates();return;}
                candidateObserved=true;acquisitionPrimary=wait.primary();acquisitionWaitNanos=wait.waitNanos();
                acquisitionGates|=wait.gates();return;
            }
            combineWait(wait);
        }
        void combineWait(DhAcquisition wait) {
            var combined=(acquisitionGates==0?DhAcquisition.OPEN:new DhAcquisition(acquisitionPrimary,acquisitionGates,acquisitionWaitNanos)).and(wait);
            acquisitionPrimary=combined.primary();acquisitionGates=combined.gates();acquisitionWaitNanos=combined.waitNanos();
        }
        void informationalWait(Gate gate,long nanos) {
            if(candidateObserved){acquisitionGates|=1L<<gate.ordinal();return;}
            combineWait(DhAcquisition.waiting(gate,nanos));
        }
        void openCache() {
            if(terrainCache!=null || !cacheReady || cacheDisabled || !familyConfirmed) return;
            var mc=Minecraft.getInstance();var server=mc.getCurrentServer();
            if(mc.gameDirectory==null || server==null || server.ip==null) {cacheStatus="disabled: no remote identity";return;}
            var seed=dev.vox.lss.seed.ClientWorldSeed.context(true);
            if(!seed.seedAvailable() || seed.seed()==0L) {cacheStatus="disabled: ambiguous seed";return;} // Never reuse a source cache under an ambiguous world identity.
            var shape=new DhTerrainTile.WorldShape(level.getMinBuildHeight(),level.getHeight(),level.dimensionType().hasSkyLight());
            var cache=new DhTerrainCache(ColumnCacheStore.cacheRoot(),new DhTerrainCache.Key(server.ip,serverKey,levelKey,
                    level.dimension().location().toString(),seed.seed(),profile,shape,converter.cacheIdentity()));
            terrainCache=cache;
            cacheReady=false;cacheWorkStarted=System.nanoTime();cacheStatus=syncAvailable()?"validate on load":"server synchronization disabled";
            var player=Minecraft.getInstance().player;
            if(player!=null && player.chunkPosition()!=null)observeLoaded(System.nanoTime(),player.chunkPosition());
            long job=++cacheJob;
            try { cacheTask=worker.submit(() -> {
                Set<DhPosition> positions;
                cache.protect(protectedPositions);
                try { positions=cache.positions(); }
                catch(java.io.IOException e) { disableCache(e); positions=Set.of(); }
                var found=positions;
                main(() -> {if(job==cacheJob){cachedPositions.addAll(found);cacheReady=true;}});
            }); } catch(RejectedExecutionException e) { fail("DH worker queue full"); }
        }
        void restoreCache(DhPosition position,long now) {
            var cache=terrainCache;
            if(cache==null) {cachedPositions.remove(position);policy.defer(position,now);return;}
            cachedPositions.remove(position);cacheLoading=position;cacheWorkStarted=now;
            long job=++cacheJob;
            try { cacheTask=worker.submit(() -> {
                cache.protect(protectedPositions);
                try(var entry=cache.read(position,budget)) {
                    if(entry!=null) {
                        var shape=new DhTerrainTile.WorldShape(level.getMinBuildHeight(),level.getHeight(),level.dimensionType().hasSkyLight());
                        try(var tile=entry.decode(new DhTerrainDecoder(DhLimits.DEFAULT),profile,shape)) {
                            if(!completeTile(tile) || tile.modifiedTimestamp()!=entry.timestamp()) removeCached(position);
                            else if(syncAvailable()) {
                                // An owned validated SOURCE is sufficient for this request.
                                // It is not a destination acceptance receipt. Do not enqueue
                                // old terrain before the server decides changed vs unchanged.
                                long stamp=entry.timestamp();
                                main(() -> {
                                    if(job!=cacheJob)return;
                                    long at=System.nanoTime();
                                    var wait=requestAdmission(false,at);
                                    if(pending.size()>=2 || wait.waitNanos()>0) {
                                        beginObservation(at);waitFor(pending.size()>=2?DhAcquisition.waiting(Gate.REQUEST_SLOTS):wait);
                                        cachedPositions.add(position);return;
                                    }
                                    cacheChecks.put(position,stamp);
                                    request(position,0,false,at);
                                });
                            } else if(deliver(tile,position,false,true)) {cache.unprotect(position);cacheHits.incrementAndGet();}
                        }
                    }
                } catch(DhCodecException e) {
                    if(e.kind()==DhCodecException.Kind.RESOURCE_LIMIT)retryCache(position,"cached terrain memory pressure");
                    else invalidateCache(position,e);
                } catch(TimeoutException e) {
                    retryCache(position,"client thread was busy; cached terrain retry scheduled");
                } catch(java.io.IOException e) { invalidateCache(position,e); }
                catch(InterruptedException | CancellationException e) { /* retired owner */ }
                catch(Exception | LinkageError e) { if(active)fail("DH cached terrain delivery failed: "+e.getClass().getSimpleName()); }
                finally { main(() -> {if(job==cacheJob){cacheLoading=null;terminalObservation=DhAcquisition.OPEN;}}); }
            }); } catch(RejectedExecutionException e) { fail("DH worker queue full"); }
        }
        void retryCache(DhPosition position,String reason) {retryCache(position,reason,false);}
        void retryCache(DhPosition position,String reason,boolean unverified) {
            main(() -> {cacheChecks.remove(position);if(!cacheDisabled)cachedPositions.add(position);
                if(unverified)policy.deferUnverified(position,System.nanoTime());else policy.defer(position,System.nanoTime());
                problem=reason;});
        }
        void invalidateCache(DhPosition position,Exception error) {
            cacheWarning(error);
            removeCached(position);
            main(() -> {cacheChecks.remove(position);cachedPositions.remove(position);policy.invalidate(position);nextScanAt=0;});
        }
        void restoreUnchanged(DhPosition position,long stamp) throws Exception {
            var cache=terrainCache;
            if(cache==null) {retryCache(position,"source cache unavailable; full request scheduled",true);return;}
            try(var entry=cache.read(position,budget)) {
                if(entry==null || entry.timestamp()!=stamp) {retryCache(position,"cached source disappeared; retry scheduled",true);return;}
                var shape=new DhTerrainTile.WorldShape(level.getMinBuildHeight(),level.getHeight(),level.dimensionType().hasSkyLight());
                try(var tile=entry.decode(new DhTerrainDecoder(DhLimits.DEFAULT),profile,shape)) {
                    if(!completeTile(tile) || tile.modifiedTimestamp()!=stamp) {invalidateCache(position,new java.io.IOException("Invalid cached DTO"));return;}
                    if(deliver(tile,position,false,true)) {cache.unprotect(position);cacheHits.incrementAndGet();}
                }
            } catch(DhCodecException e) {
                if(e.kind()==DhCodecException.Kind.RESOURCE_LIMIT)retryCache(position,"cached terrain memory pressure");
                else invalidateCache(position,e);
            } catch(TimeoutException e) {retryCache(position,"client thread was busy; cached terrain retry scheduled");}
            catch(java.io.IOException e) {invalidateCache(position,e);}
        }
        static boolean completeTile(DhTerrainTile tile) {
            for(int c=0;c<4096;c++) if(tile.coverage(c)!=DhTerrainTile.Coverage.COMPLETE) return false;
            return true;
        }
        void removeCached(DhPosition position) {
            var cache=terrainCache;if(cache==null)return;
            try {cache.remove(position);}catch(java.io.IOException e){disableCache(e);}
        }
        void disableCache(Exception e) {
            cacheDisabled=true;var cache=terrainCache;terrainCache=null;if(cache!=null)cache.close();
            cacheWarning(e);
            main(()->{++cacheJob;cacheLoading=null;cacheReady=true;cachedPositions.clear();cacheChecks.clear();});
        }
        void cacheWarning(Exception e) {
            cacheStatus="unavailable: "+e.getClass().getSimpleName();
            if(cacheWarned.compareAndSet(false,true))
                LSSLogger.warn("DH local terrain cache unavailable ("+e.getClass().getSimpleName()+"); full terrain requests remain available");
        }
        void fail(String reason) {
            if(!active) return;
            problem=sanitizeReason(reason); active=false;
            LSSLogger.warn("DH source stopped: "+problem+(pendingRetry!=null && pendingRetry.owner==this?" (one automatic reconnect scheduled)":" (ordinary Minecraft remains connected)"));
            // Drain even on terminal failures; an inactive tick cannot expire its buffers.
            Minecraft.getInstance().execute(this::retire);
        }
        void beginRetire() { beginRetire(true); }
        void beginRetire(boolean withdraw) {
            if(!withdraw)preferencesWithdrawn.set(true); // Reconfiguration permanently disarms old-family controls.
            // Reception OFF and native takeover leave Minecraft connected. Withdraw
            // pushes on this listener only; a replacement may speak another family.
            if(withdraw && announced && familyConfirmed && config!=null && Minecraft.getInstance().getConnection()==connection
                    && preferencesWithdrawn.compareAndSet(false,true)) {
                try {sender.accept(codec.encode(preferences(false,false,sentBandwidth>0?sentBandwidth:DhBandwidthRamp.OPEN_KB)));}
                catch(Exception|LinkageError ignored) { /* connection may already be closed */ }
            }
            if(withdraw && !profile.support() && Minecraft.getInstance().getConnection()==connection)
                for(var entry:pending.entrySet()) if(entry.getValue().generation) {
                    try { sender.accept(codec.encode(new DhMessage.Cancel(entry.getKey()))); }
                    catch(Exception|LinkageError ignored) { /* connection may already be closed */ }
                }
            pending.clear();
            active=false;
            worker.shutdownNow();
            if(terrainCache!=null)terrainCache.close();
            synchronized(frames) { for(var f:List.copyOf(frames)) if(!f.processing) f.close(); }
            retiring.add(this);
        }
        boolean retire() {
            beginRetire();
            try {
                if(worker.awaitTermination(2,TimeUnit.SECONDS)) { closeResources(); retiring.remove(this); return true; }
            } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            retiring.add(this);
            return false;
        }
        void send(DhMessage message) {
            if(!active) return;
            try { sender.accept(codec.encode(message)); }
            catch(Exception e) { fail("DH send failed: "+e.getMessage()); }
        }
        void tick() {
            if(!active) return;
            long now=System.nanoTime();
            beginObservation(now);
            if(!announced && now-started>=1_000_000_000L) {
                // Paper must receive Fabric's minecraft:register announcement first.
                if(!hasDhChannel()) {
                    waitFor(Gate.CHANNEL);
                    problem="waiting for advertised DH server channel";
                    if(now-started>15_000_000_000L)fail("DH server channel is unavailable");
                    return;
                }
                announced=true; sentAt=now;
                sendPreferences();
                send(new DhMessage.RequestLevelInit(level.dimension().location().toString()));
            }
            var preference=LSSClientConfig.CONFIG.snapshot().lod().dh();
            boolean syncActivated=!sentSync && preference.syncOnLoad();
            boolean sourcePrefsChanged=sentSync!=preference.syncOnLoad() || sentUpdates!=preference.realTimeUpdates();
            if(announced && (sourcePrefsChanged || sentBandwidth!=0 && sentBandwidth!=bandwidthLimit())) {
                sendPreferences();nextScanAt=0;
                if(syncActivated) {visibleCenter=null;visible=Set.of();loadedAt.clear();}
            }
            if(config==null) {
                waitFor(Gate.CONFIG);
                boolean idle;
                synchronized(frames){idle=frames.isEmpty();}
                idle &= worker.getActiveCount()==0 && worker.getQueue().isEmpty();
                if(!idle || now-Math.max(sentAt,lastInboundArrival)<=15_000_000_000L)handshakeIdleTicks=0;
                else if(announced && ++handshakeIdleTicks>=3) {
                    if(wireBytes.get()==0 && probeLegacy(this))return;
                    fail(wireBytes.get()>0?"DH replied but did not establish a session config":retryUsed
                            ?"Advertised DH channel did not reply to the legacy trial; networking may be disabled or incompatible"
                            :"Advertised DH channel did not establish a session config");
                }
                return;
            }
            if(levelKey==null && now-sentAt>=15_000_000_000L) { fail("No DH world initialization reply after automatic discovery"); return; }
            if(levelKey==null) {waitFor(Gate.WORLD);return;}
            expireRequests(now);
            // A silent official sync request may retain its upstream permit forever.
            if(unanswered>=2) { fail("Two unanswered DH requests; reconnect before retrying"); return; }
            if(now-maintenanceAt>1_000_000_000L && maintenanceQueued.compareAndSet(false,true)) {
                maintenanceAt=now;
                try { worker.execute(() -> {
                    try {
                        if(pushRecovery.get())recoverPushPressure();
                        expireAssembly();
                    } catch(Exception error) {if(active)fail("Unable to recover DH push pressure: "+error.getClass().getSimpleName());}
                    finally { maintenanceQueued.set(false); }
                }); }
                catch(RejectedExecutionException e) { fail("DH worker queue full"); }
            }
            if((!cacheReady || cacheLoading!=null) && now-cacheWorkStarted>60_000_000_000L) {
                var timedOut=cacheLoading;++cacheJob;
                if(cacheTask!=null)cacheTask.cancel(true);
                cacheLoading=null;cacheReady=true;
                if(timedOut!=null) {cachedPositions.add(timedOut);cacheChecks.remove(timedOut);policy.defer(timedOut,now);}
                else {cacheStatus="index timed out; network acquisition remains available";}
                problem="cached terrain operation timed out; local retry scheduled";
            }
            if(DhVoxyBridge.failed()) { fail("Voxy DH bridge is incompatible; restart with a compatible Voxy build"); return; }
            if(!DhVoxyBridge.ready(level)) {waitFor(Gate.VOXY);problem="waiting for Voxy world/ingest service"; return; }
            if(!cacheInvalidated) {
                try { invalidateNativeCache(); } catch(RuntimeException e) { fail(e.getMessage()); return; }
            }
            var player=Minecraft.getInstance().player;
            if(player!=null && player.chunkPosition()!=null) {
                observeLoaded(now,player.chunkPosition());pruneGeneration(now,player.chunkPosition());
            }
            if(!cacheReady)waitFor(Gate.CACHE);
            if(pushRecovery.get())waitFor(Gate.RECOVERY);
            if(acquisitionGates!=0)return;
            int backlog=DhVoxyBridge.backlog();
            if(backlog>256)waitFor(Gate.INGEST_PRESSURE);
            if(worker.getQueue().size()>4)waitFor(Gate.DECODE_PRESSURE);
            if(pending.size()>=2)waitFor(Gate.REQUEST_SLOTS);
            if(Minecraft.getInstance().player==null)waitFor(Gate.NO_PLAYER);
            if(acquisitionGates!=0)return;
            if(now<nextScanAt) {
                // Recheck observed service gates cheaply, so cadence does not hide
                // a long manual/rate/slot wait between terrain scans.
                if(terminalCenter!=null && !terminalCenter.equals(player.chunkPosition()))nextScanAt=0;
                else {
                    if(scanGenerationBlocked)waitFor(requestAdmission(true,now));
                    if(scanSyncBlocked)waitFor(requestAdmission(false,now));
                    if(acquisitionGates==0 && !scanGenerationBlocked && !scanSyncBlocked && terminalObservation.gates()!=0
                            && (terminalObservation.waitNanos()==Long.MAX_VALUE || terminalObservation.waitNanos()>now-terminalAt)) {
                        acquisitionPrimary=terminalObservation.primary();acquisitionGates=terminalObservation.gates();
                        acquisitionWaitNanos=terminalObservation.waitNanos()==Long.MAX_VALUE?Long.MAX_VALUE
                                :Math.max(0,terminalObservation.waitNanos()-(now-terminalAt));
                    }
                    if(acquisitionGates==0)waitFor(Gate.SCAN_CADENCE,nextScanAt-now);
                    else acquisitionGates|=1L<<Gate.SCAN_CADENCE.ordinal();
                    return;
                }
            }
            nextScanAt=now+500_000_000L;
            var center=Minecraft.getInstance().player.chunkPosition();
            requestNext(now,center);
            if(lastRequest==now)nextScanAt=now+50_000_000L;
        }
        void expireRequests(long now) {
            synchronized(frames) {
                long cutoff=arrivalCutoff(now);
                for(var entry:pending.entrySet()) if(!entry.getValue().answered && cutoff-entry.getValue().started>entry.getValue().lifetime) {
                    if(pending.remove(entry.getKey(),entry.getValue())) {
                        DhClientTrace.timeout(entry.getKey(),entry.getValue().position);
                        policy.defer(entry.getValue().position,now);
                        if(!entry.getValue().generation || profile.support())unanswered++;
                        if(entry.getValue().generation && !profile.support()) send(new DhMessage.Cancel(entry.getKey()));
                    }
                }
            }
        }
        /** Worker only. */
        void expireAssembly() {
            synchronized(frames) {assembly.expire(arrivalCutoff(System.nanoTime()));}
        }
        /** Caller holds frames. Include captures awaiting the loader callback AND processing frames. */
        long arrivalCutoff(long now) {
            long cutoff=now;
            for(var frame:frames)if(cutoff-frame.arrivalNanos>0)cutoff=frame.arrivalNanos;
            return cutoff;
        }
        /** Cancel only official generation outside interest, with spatial/time hysteresis.
         * Synchronization permits and anonymous fragments retain their existing bounded owners. */
        void pruneGeneration(long now,ChunkPos player) {
            if(profile.support() || config==null)return;
            int range=Math.min(effectiveRange(),Math.min(32,config.generationDistance()));
            synchronized(frames) {
            if(!frames.isEmpty())return; // Let captured/processing replies finish under their existing ownership.
            for(var entry:pending.entrySet()) {
                var request=entry.getValue();
                int distance=Math.max(Math.abs(request.position.x()*4+2-player.x),Math.abs(request.position.z()*4+2-player.z));
                if(request.answered || !request.generation || now-request.started<2_000_000_000L || distance<=range+12)continue;
                if(!pending.remove(entry.getKey(),request))continue;
                send(new DhMessage.Cancel(entry.getKey()));policy.defer(request.position,now);
                DhClientTrace.cancel(entry.getKey(),request.position);
                try {worker.execute(()->assembly.cancel(entry.getKey()));}
                catch(RejectedExecutionException full) {fail("DH worker queue full while cancelling obsolete generation");return;}
                nextScanAt=0;
            }
            }
        }
        long requestInterval(boolean generation) {
            long interval=generation?500_000_000L:50_000_000L;
            if(profile.support())interval=Math.max(interval,1_000_000_000L);
            if(DhVoxyBridge.backlog()<0)interval=Math.max(interval,2_000_000_000L);
            return interval;
        }
        long requestWaitNanos(boolean generation,long now) {
            return requestAdmission(generation,now).waitNanos();
        }
        DhAcquisition requestAdmission(boolean generation,long now) {
            int rate=generation?config.generationRate():config.syncRate();
            var wait=DhAcquisition.OPEN;
            if(rate<=0)return DhAcquisition.waiting(Gate.SERVER_DISABLED);
            if(pending.values().stream().filter(p->p.generation==generation).count()>=(generation?1:Math.min(2,rate)))
                return DhAcquisition.waiting(generation?Gate.GENERATION_SLOT:Gate.SYNC_SLOTS);
            long elapsed=now-(generation?lastGenerationRequest:lastSyncRequest);
            long interval=requestInterval(generation);
            long localWait=Math.max(0,interval-elapsed),serverWait=Math.max(0,1_000_000_000L/rate-elapsed);
            if(localWait>0)wait=wait.and(DhAcquisition.waiting(Gate.LOCAL_PACING,localWait));
            if(serverWait>0)wait=wait.and(DhAcquisition.waiting(Gate.SERVER_RATE,serverWait));
            interval=Math.max(interval,1_000_000_000L/rate);
            if(profile.support()) {
                bandwidthLimit();long paced=bandwidthRamp.supportRequestInterval(interval,now);
                if(paced>interval && paced>elapsed)wait=wait.and(DhAcquisition.waiting(Gate.SUPPORT_STARTUP,paced-elapsed));
            }
            int manual=LSSClientConfig.CONFIG.lodColumnsPerSecondLimit();
            long manualWait=manual>0 && requested.get()>0
                    ?Math.max(0,16_000_000_000L/manual-(now-lastRequest)):0;
            if(manualWait>0)wait=wait.and(DhAcquisition.waiting(Gate.MANUAL_RATE,manualWait));
            long backoff=Math.max(0,(generation?generationBackoffUntil:syncBackoffUntil)-now);
            if(backoff>0)wait=wait.and(DhAcquisition.waiting(Gate.REFUSAL_BACKOFF,backoff));
            return wait;
        }
        boolean syncAvailable() {
            return config!=null && config.synchronize() && config.syncRate()>0
                    && LSSClientConfig.CONFIG.snapshot().lod().dh().syncOnLoad();
        }
        boolean updatesAllowed() {
            return familyConfirmed && config!=null && config.updates() && config.updateDistance()>0
                    && LSSClientConfig.CONFIG.snapshot().lod().dh().realTimeUpdates();
        }
        long requestLifetime() {return !profile.support() && bandwidthLimit()<DhBandwidthRamp.OPEN_KB
                ?120_000_000_000L:DhLimits.DEFAULT.lifetimeNanos();}
        int bandwidthLimit() {
            return bandwidthRamp.limit(LSSClientConfig.CONFIG.enableAdaptiveTransferRate(),
                    LSSClientConfig.CONFIG.snapshot().lod().download().slowStartOnJoin());
        }
        void sendPreferences() {
            var setting=LSSClientConfig.CONFIG.snapshot().lod().dh();
            sentSync=setting.syncOnLoad();sentUpdates=setting.realTimeUpdates();
            sentBandwidth=bandwidthLimit();
            send(preferences(sentSync,sentUpdates,sentBandwidth));
        }
        DhMessage.SessionConfig preferences(boolean synchronize,boolean updates,int bandwidth) {
            return new DhMessage.SessionConfig(profile==DhProfile.OFFICIAL_16
                    ?DhMessage.GeneratorPlan.SURFACE_THEN_CHUNKS:DhMessage.GeneratorPlan.CHUNKS_ONLY,
                    2048,0,0,0,20,updates,128,synchronize,2048,50,bandwidth);
        }
        /** A load transition triggers synchronization; elapsed time alone never polls terrain. */
        void observeLoaded(long now,ChunkPos player) {
            int range=effectiveRange();
            if(player.equals(visibleCenter) && range==visibleRange)return;
            var next=new HashSet<DhPosition>();
            int cx=Math.floorDiv(player.x,4),cz=Math.floorDiv(player.z,4),rings=(range+3)/4;
            for(int x=cx-rings;x<=cx+rings;x++)for(int z=cz-rings;z<=cz+rings;z++) {
                var p=new DhPosition(6,x,z);
                if(Math.max(Math.abs(x*4+2-player.x),Math.abs(z*4+2-player.z))>range)continue;
                next.add(p);
                if(!visible.contains(p) && syncAvailable() && now-loadedAt.getOrDefault(p,Long.MIN_VALUE/2)>=20*60_000_000_000L) {
                    policy.revalidate(p,now);
                    loadedAt.remove(p);loadedAt.put(p,now);
                    if(loadedAt.size()>DhSessionPolicy.MAX_RECEIPTS)loadedAt.remove(loadedAt.keySet().iterator().next());
                }
            }
            visible=Set.copyOf(next);visibleCenter=player;visibleRange=range;
            // Protect interest, not every historical file. Pending validation remains
            // protected even if the camera just left its tile.
            next.addAll(cacheChecks.keySet());pending.values().forEach(p->next.add(p.position));
            if(cacheLoading!=null)next.add(cacheLoading);
            protectedPositions=Set.copyOf(next);
        }
        static boolean completeColumn(DhTerrainTile tile,int column) {
            int x=column/4*16,z=column%4*16;
            for(int dx=0;dx<16;dx++)for(int dz=0;dz<16;dz++)
                if(tile.coverage((x+dx)*64+z+dz)!=DhTerrainTile.Coverage.COMPLETE)return false;
            return true;
        }
        void recoverResourcePressure() {
            DhClientTrace.pressure(DhClientTrace.Pressure.ASSEMBLY_OR_DECODE);
            rawTerrainLosses.incrementAndGet();droppedUpdateFrames.incrementAndGet();pushRecovery.set(true);
            try {recoverPushPressure();}catch(Exception busy) {problem="terrain resource pressure; recovery pending";}
        }
        void recoverPushPressure() throws Exception {
            if(!pushRecovery.get() || !recoveryQueued.compareAndSet(false,true))return;
            long loss=rawTerrainLosses.get();
            // A dropped raw fragment has no trustworthy tile identity. Purge this
            // source bucket before any unchanged restore; defer the policy clear to
            // its owner without turning a client-thread hitch into a session failure.
            assembly.discardAll();
            // Applied push fences survive pressure: an older database reply cannot overwrite them.
            if(updatesAllowed() && terrainCache!=null)try {terrainCache.invalidateAll();}catch(java.io.IOException e){disableCache(e);}
            main(()-> {
                ++cacheJob;if(cacheTask!=null)cacheTask.cancel(false);
                cacheLoading=null;cacheReady=true;cacheChecks.clear();rejoinChecks.clear();
                var cancelled=new ArrayList<>(pending.keySet());
                pending.forEach((id,p)->{if(p.generation && !profile.support())send(new DhMessage.Cancel(id));});
                pending.clear();
                try {worker.execute(()-> {
                    cancelled.forEach(assembly::cancel);
                    main(()->{pushRecovery.set(rawTerrainLosses.get()!=loss);recoveryQueued.set(false);});
                });} catch(RejectedExecutionException full) {recoveryQueued.set(false);pushRecovery.set(true);}

                if(updatesAllowed()) {policy.invalidateAll();cachedPositions.clear();}
                visibleCenter=null;nextScanAt=0;
                problem="DH terrain burst deferred; missing data will be reacquired when permitted";
                // Registration cleanup above owns release of the recovery gate.
            });
        }
        void deferUpdate(DhPosition position,long stamp,String reason) throws Exception {
            removeCached(position);
            updateFence(position).requiredTimestamp=Math.max(updateFence(position).requiredTimestamp,stamp);
            main(()-> {
                cachedPositions.remove(position);cacheChecks.remove(position);policy.invalidate(position);nextScanAt=0;
                var cancelled=new ArrayList<Integer>();
                pending.forEach((id,p)->{if(p.position.equals(position) && pending.remove(id,p)) {
                    cancelled.add(id);if(p.generation && !profile.support())send(new DhMessage.Cancel(id));
                }});
                try {worker.execute(()->cancelled.forEach(assembly::cancel));}
                catch(RejectedExecutionException full) {DhClientTrace.pressure(DhClientTrace.Pressure.RECOVERY_QUEUE);pushRecovery.set(true);rawTerrainLosses.incrementAndGet();droppedUpdateFrames.incrementAndGet();}
                problem=reason;
            });
        }
        void receiveUpdate(DhMessage.PartialUpdate update,long arrival) throws Exception {
            if(!updatesAllowed() || levelKey==null || !levelKey.equals(update.level())) {assembly.discard(update.bufferId());return;}
            assembly.allowPush(true);
            DhPosition affected=null;long affectedStamp=0;
            try(var completed=assembly.push(update,arrival)) {
                var shape=new DhTerrainTile.WorldShape(level.getMinBuildHeight(),level.getHeight(),level.dimensionType().hasSkyLight());
                try(var tile=completed.decode(new DhTerrainDecoder(DhLimits.DEFAULT),profile,shape)) {
                    var p=tile.position();affected=p;affectedStamp=tile.modifiedTimestamp();
                    boolean allowed=onMain(() -> {
                        var player=Minecraft.getInstance().player;
                        return updatesAllowed() && player!=null && DhSessionPolicy.inBorder(profile,p,config.centerX(),config.centerZ(),config.borderRadius())
                                && Math.max(Math.abs(p.x()*4+2-player.chunkPosition().x),Math.abs(p.z()*4+2-player.chunkPosition().z))
                                <=Math.min(effectiveRange(),config.updateDistance());
                    });
                    if(!allowed) {
                        boolean held=onMain(()->policy.hasSnapshot(p));
                        if(held || updateFences.containsKey(p))deferUpdate(p,tile.modifiedTimestamp(),"out-of-range update deferred; source must be reacquired");
                        return;
                    }
                    long stamp=onMain(()->policy.timestamp(p));
                    if(stamp>0 && tile.modifiedTimestamp()>0 && tile.modifiedTimestamp()<stamp)return;
                    if(DhVoxyBridge.backlog()>256 || worker.getQueue().size()>4) {
                        DhClientTrace.pressure(DhClientTrace.Pressure.LIVE_BACKLOG);
                        droppedUpdateFrames.incrementAndGet();deferUpdate(p,tile.modifiedTimestamp(),"Voxy backlog; live update deferred for acquisition");return;
                    }
                    receivedUpdates.incrementAndGet();lastSync=System.nanoTime();
                    // A partial DTO cannot replace the whole cached source snapshot.
                    removeCached(p);
                    boolean hadSnapshot=onMain(() -> policy.hasSnapshot(p));
                    long previousStamp=onMain(() -> policy.timestamp(p));
                    var cancelled=onMain(() -> {
                        cachedPositions.remove(p);cacheChecks.remove(p);rejoinChecks.remove(p);
                        if(!hadSnapshot)policy.invalidate(p);nextScanAt=0;
                        var ids=new ArrayList<Integer>();
                        pending.forEach((id,request) -> {if(request.position.equals(p) && pending.remove(id,request)) {
                            ids.add(id);if(request.generation && !profile.support())send(new DhMessage.Cancel(id));
                        }});
                        return ids;
                    });
                    cancelled.forEach(assembly::cancel);
                    partialAcceptedMask=0;
                    boolean delivered=deliver(tile,p,false,null,true);
                    var fence=updateFence(p);
                    int touched=0,described=0;
                    for(int c=0;c<16;c++) {
                        if(completeColumn(tile,c))described|=1<<c;
                        int x=c/4*16,z=c%4*16;
                        for(int dx=0;dx<16;dx++)for(int dz=0;dz<16;dz++) {
                            int column=(x+dx)*64+z+dz;
                            if(tile.spanCount(column)>0 || tile.generation(column)>0)touched|=1<<c;
                        }
                        if((partialAcceptedMask&(1<<c))!=0) {
                            fence.mask|=1<<c;fence.timestamp[c]=Math.max(fence.timestamp[c],tile.modifiedTimestamp());
                        }
                    }
                    if((touched&~described)!=0)fence.requiredTimestamp=Math.max(fence.requiredTimestamp,tile.modifiedTimestamp());
                    boolean completeEdit=delivered && (touched&~described)==0;
                    onMain(() -> {
                        if(hadSnapshot && completeEdit)policy.accepted(p,1,1,Math.max(previousStamp,tile.modifiedTimestamp()),System.nanoTime());
                        else policy.invalidate(p); // Incomplete source is filled by ordinary acquisition, never a periodic timer.
                        return null;
                    });
                }
            } catch(TimeoutException busy) {
                assembly.discard(update.bufferId());
                if(affected!=null) {
                    deferUpdate(affected,affectedStamp,"client thread was busy; live update acquisition scheduled");
                }
            } catch(DhCodecException e) {
                assembly.discard(update.bufferId());
                if(e.kind()==DhCodecException.Kind.RESOURCE_LIMIT) {
                    DhClientTrace.pressure(DhClientTrace.Pressure.LIVE_RESOURCE);
                    if(affected!=null)deferUpdate(affected,affectedStamp,"live update resource pressure; retry scheduled");
                    else recoverResourcePressure();
                } else if(e.kind()!=DhCodecException.Kind.UNOWNED && e.kind()!=DhCodecException.Kind.EXPIRED && e.kind()!=DhCodecException.Kind.UNSUPPORTED)throw e;
            }
        }
        boolean generationAvailable(DhPosition position,ChunkPos player) {
            return player!=null && config.plan()!=DhMessage.GeneratorPlan.DISABLED && config.generationRate()>0
                    && Math.max(Math.abs(position.x()*4+2-player.x),Math.abs(position.z()*4+2-player.z))
                    <=Math.min(32,config.generationDistance());
        }
        void requestNext(long now,ChunkPos player) {
            beginObservation(now);
            scanGenerationBlocked=false;scanSyncBlocked=false;scanning=true;candidateObserved=false;
            try {
            int range=effectiveRange();
            var context=new RangeContext(player.x,player.z,range,config.generationDistance(),config.syncDistance(),
                    config.plan()!=DhMessage.GeneratorPlan.DISABLED && config.generationRate()>0,
                    config.synchronize() && config.syncRate()>0,config.centerX(),config.centerZ(),config.borderRadius());
            if(!context.equals(rangeContext)){rangeContext=context;rangeEpoch=RANGE_EPOCHS.incrementAndGet();}
            int cx=Math.floorDiv(player.x,4),cz=Math.floorDiv(player.z,4),rings=(range+3)/4;
            // Bound retry preference while retaining the untouched frontier's first priority.
            for(int priority:retryBurst>=3?new int[]{0,2,1}:new int[]{0,1,2}) {
                DhPosition oldest=null;long oldestDue=Long.MAX_VALUE;
                for(int ring=0;ring<=rings;ring++) for(int dx=-ring;dx<=ring;dx++) for(int dz=-ring;dz<=ring;dz++) {
                    if(Math.max(Math.abs(dx),Math.abs(dz))!=ring) continue;
                    var p=new DhPosition(6,cx+dx,cz+dz);
                    int distance=Math.max(Math.abs(p.x()*4+2-player.x),Math.abs(p.z()*4+2-player.z));
                    boolean reachable=distance<=range && DhSessionPolicy.inBorder(profile,p,config.centerX(),config.centerZ(),config.borderRadius())
                            && (policy.rangeNeedsGeneration(p) || !policy.hasSnapshot(p) && !config.synchronize()?generationAvailable(p,player)
                            :config.synchronize() && config.syncRate()>0 && distance<=config.syncDistance());
                    policy.reconcileRange(p,rangeEpoch,reachable,now);
                    policy.reconcileGeneration(p,generationAvailable(p,player),now);
                    // A missing sync result is acquisition work, so let its bounded
                    // generation join the frontier instead of waiting for every miss.
                    int acquisitionPriority=rejoinChecks.contains(p)?1:policy.acquisitionPriority(p);
                    if(acquisitionPriority!=priority)continue;
                    int x=p.x()*4,z=p.z()*4;
                    if(Math.max(Math.abs(x+2-player.x),Math.abs(z+2-player.z))>range) continue;
                    boolean any=false;
                    for(int ox=0;ox<4;ox++) for(int oz=0;oz<4;oz++) any|=policy.eligible(x+ox,z+oz);
                    if(!any) continue;
                    if(!policy.due(p,now)) {
                        if(policy.parked(p))informationalWait(Gate.PARKED,Long.MAX_VALUE);
                        else if(policy.dueNanos(p)!=Long.MAX_VALUE)informationalWait(Gate.DEFERRED,Math.max(0,policy.dueNanos(p)-now));
                        continue;
                    }
                    if(p.equals(cacheLoading)) {waitFor(Gate.CACHE);continue;}
                    if(pending.values().stream().anyMatch(r->r.position.equals(p))) {informationalWait(Gate.IN_FLIGHT,Long.MAX_VALUE);continue;}
                    if(!DhSessionPolicy.inBorder(profile,p,config.centerX(),config.centerZ(),config.borderRadius())) {waitFor(Gate.SERVER_RANGE);continue;}
                    if(priority!=2 && cachedPositions.contains(p)) {
                        if(cacheLoading!=null) {waitFor(Gate.CACHE);continue;}
                        if(syncAvailable()) {var wait=requestAdmission(false,now);if(wait.waitNanos()>0) {scanSyncBlocked=true;waitFor(wait);continue;}}
                        beginObservation(now);waitFor(Gate.CACHE);
                        restoreCache(p,now);return;
                    }
                    if(!policy.syncAllowed(p,syncAvailable(),config.syncRate())) {waitFor(Gate.SERVER_DISABLED);continue;}
                    boolean gen=profile.support() || !config.synchronize() || policy.generationNeeded(p);
                    if(gen && (!generationAvailable(p,player)
                            || pending.values().stream().anyMatch(Pending::generation))) {
                        if(config.plan()==DhMessage.GeneratorPlan.DISABLED || config.generationRate()==0)waitFor(Gate.SERVER_DISABLED);
                        else if(!generationAvailable(p,player))waitFor(Gate.SERVER_RANGE);
                        else {scanGenerationBlocked=true;waitFor(Gate.GENERATION_SLOT);}
                        if(!profile.support() && policy.generationNeeded(p)
                                && (config.plan()==DhMessage.GeneratorPlan.DISABLED || config.generationRate()==0
                                || Math.max(Math.abs(x+2-player.x),Math.abs(z+2-player.z))>Math.min(32,config.generationDistance())))
                            policy.absent(p,now,false);
                        continue;
                    }
                    int rate=gen?config.generationRate():config.syncRate();
                    if(rate==0) {waitFor(Gate.SERVER_DISABLED);continue;}
                    if(pending.size()>=2) {waitFor(Gate.REQUEST_SLOTS);continue;}
                    var wait=requestAdmission(gen,now);
                    if(wait.waitNanos()>0) {if(gen)scanGenerationBlocked=true;else scanSyncBlocked=true;waitFor(wait);continue;}
                    // A minimum interval shorter than a sweep must still service
                    // every due tile. Stable ring order breaks equal-deadline ties;
                    // renewal then moves the tile behind older outstanding work.
                    if(priority==2) {
                        long due=policy.dueNanos(p);
                        if(oldest==null || due<oldestDue) { oldest=p;oldestDue=due; }
                        continue;
                    }
                    request(p,priority,gen,now);return;
                }
                if(oldest!=null) {
                    request(oldest,priority,profile.support() || !config.synchronize() || policy.generationNeeded(oldest),now);return;
                }
            }
            if(!pending.isEmpty())informationalWait(Gate.IN_FLIGHT,Long.MAX_VALUE);
            if(acquisitionGates==0)waitFor(Gate.NO_ELIGIBLE_WORK);
            } finally {
                scanning=false;terminalAt=now;terminalCenter=player;
                terminalObservation=new DhAcquisition(acquisitionPrimary,acquisitionGates,acquisitionWaitNanos);
            }
        }
        void request(DhPosition p,int priority,boolean gen,long now) {
            beginObservation(now);candidateObserved=false;
            scanGenerationBlocked=false;scanSyncBlocked=false;
            int id=IDS.incrementAndGet();
            if(id<=0) { fail("DH request ID space exhausted; restart client"); return; }
            long stamp=cacheChecks.containsKey(p)?cacheChecks.get(p):priority==2 || rejoinChecks.contains(p)?policy.syncTimestamp(p):0;
            rejoinChecks.remove(p); // The owned request now carries the conditional timestamp and normal retry policy.
            long lifetime=requestLifetime();
            pending.put(id,new Pending(p,gen,now,stamp,rangeEpoch,lifetime)); lastRequest=now;
            nextScanAt=Math.min(nextScanAt,now+50_000_000L);
            if(gen)lastGenerationRequest=now;else lastSyncRequest=now;
            if(priority==1) retryBurst=Math.min(3,retryBurst+1); else retryBurst=0;
            requested.incrementAndGet();
            try { worker.execute(() -> {
                try {
                    if(!active || !pending.containsKey(id)) return; assembly.expect(id,p,lifetime);
                    main(() -> {if(pending.containsKey(id)) {
                        DhClientTrace.request(id,p,gen,stamp);
                        send(new DhMessage.Request(id,levelKey,p,gen && stamp==0?null:stamp));
                    }});
                } catch(Exception e) { fail(e.getMessage()); }
            }); } catch(RejectedExecutionException e) { fail("DH worker queue full"); }
        }
        void main(Runnable task) { Minecraft.getInstance().execute(() -> { if(active && session==this && Minecraft.getInstance().level==level) task.run(); }); }
        void receive(byte[] bytes,long arrivalNanos) throws Exception {
            if(pushRecovery.get())recoverPushPressure();
            wireBytes.addAndGet(bytes.length);
            if(config==null || !familyConfirmed) {
                DhProfile advertised;
                try(var discovery=budget.reserve(bytes.length*8L+1024)) {
                    advertised=DhProtocolDiscovery.advertised(bytes);
                }
                if(advertised!=null && advertised!=profile) {
                    main(() -> {
                        var mc=Minecraft.getInstance();var server=mc.getCurrentServer();
                        if(server!=remote || mc.getConnection()!=connection)return;
                        if(advertised==DhProfile.SUPPORT_15) {
                            confirmedProfiles.remove(connection);discoveredProfiles.put(connection,advertised);
                            initialPlay=false;session=null;beginRetire();
                            status="Detected DH Support; negotiating on the existing Minecraft connection";
                            return;
                        }
                        if(server!=null)rememberProfile(server.ip,advertised);
                        trialProfile=advertised;
                        if(!retryUsed && initialPlay && !confirmedProfiles.containsKey(connection)
                                && server!=null && mc.getConnection()==connection && System.nanoTime()>=retryCooldowns.getOrDefault(server.ip,0L)) {
                            rememberRetry(server.ip);
                            pendingRetry=new Retry(this,mc.getConnection().getConnection(),server);
                        }
                        fail("Detected "+advertised+"; "+(pendingRetry!=null?"reconnecting once to finish DH negotiation":"rejoin to finish DH negotiation"));
                    });
                    return;
                }
                if(DhProtocolDiscovery.version(bytes)!=profile.version()) {
                    fail("Unsupported or ambiguous DH protocol advertisement; ordinary Minecraft remains connected");
                    return;
                }
                if(advertised==profile)familyConfirmed=true;
            }
            try(var owned=codec.decodeOwned(bytes,budget)) {
                var message=owned.message();
                if(message instanceof DhMessage.Split split)bandwidthRamp.received(split.length(),arrivalNanos);
                if(message instanceof DhMessage.CloseReason close) { fail(close.reason()); return; }
                if(message instanceof DhMessage.SessionConfig cfg) {
                    if(familyConfirmed && active && session==this)main(this::confirmProfile);
                    if(remote!=null)rememberProfile(remote.ip,profile);
                    boolean priorSync=syncAvailable();config=cfg;assembly.allowPush(updatesAllowed());
                    if(!priorSync && syncAvailable())main(() -> {visibleCenter=null;visible=Set.of();loadedAt.clear();});
                    main(()->nextScanAt=0);problem="acquiring terrain";return;
                }
                if(message instanceof DhMessage.LevelInit init) {
                    if(!init.dimension().equals(level.dimension().location().toString())) return;
                    String key=init.levelKey().isEmpty()?init.dimension():init.levelKey();
                    if(levelKey!=null && !levelKey.equals(key) && !pending.isEmpty()) {
                        fail("DH world key changed; reconnect to bind the new world"); return;
                    }
                    main(() -> {
                        if(!connectionState.bindWorld(init.dimension(),key)) fail("DH logical world changed; reconnect before acquiring terrain");
                        else if(serverKey!=null && !serverKey.equals(init.serverKey())) fail("DH server key changed; reconnect to bind the new source");
                        else {
                            levelKey=key; serverKey=init.serverKey();
                            openCache();
                        }
                    }); return;
                }
                if(message instanceof DhMessage.Split split) {
                    assembly.allowPush(updatesAllowed());
                    if(!pending.isEmpty() || updatesAllowed()) assembly.accept(split,arrivalNanos);
                    return;
                }
                if(message instanceof DhMessage.Refusal refusal) {
                    var request=pending.remove(refusal.requestId()); assembly.cancel(refusal.requestId());
                    if(request!=null) {
                        DhClientTrace.refusal(refusal.requestId(),refusal.reason());
                        refused.incrementAndGet();
                        // ExceptionMessage's pinned list: 0 rate limit, 1 range,
                        // 2 rejected, 3 requires splitting (already at leaf detail).
                        // Unknown reasons remain data and get the bounded retry policy.
                        main(() -> {unanswered=0;if(refusal.reason()==0) {
                                long now=System.nanoTime();policy.defer(request.position,now);
                                // Official DH drains the affected queue's request allowance after rate limiting.
                                if(request.generation)generationBackoffUntil=Math.max(generationBackoffUntil,now+1_000_000_000L);
                                else syncBackoffUntil=Math.max(syncBackoffUntil,now+1_000_000_000L);
                            }
                            else if(refusal.reason()==1)policy.rangeRefused(request.position,request.rangeEpoch,request.generation);
                            else policy.cooldown(request.position,System.nanoTime());});
                    }
                    problem="server refusal "+refusal.reason()+": "+sanitizeReason(refusal.message()); return;
                }
                if(message instanceof DhMessage.PartialUpdate update) { receiveUpdate(update,arrivalNanos); return; }
                if(!(message instanceof DhMessage.Response response)) return;
                var request=pending.get(response.requestId());
                if(request==null) { if(response.bufferId()!=null)assembly.discard(response.bufferId()); return; } // obsolete request ID
                try(var completed=assembly.response(response,arrivalNanos)) {
                    request.answered=true;
                    DhClientTrace.reply(response.requestId(),request.position,completed!=null,
                            Math.max(0,(arrivalNanos-request.started)/1_000_000L));
                    main(() -> { unanswered=0; nextScanAt=0; problem="acquiring terrain"; });
                    lastSync=System.nanoTime();
                    if(completed==null) {
                        Long cached=cacheChecks.remove(request.position);
                        if(request.timestamp>0 && terrainCache!=null) {
                            restoreUnchanged(request.position,request.timestamp);
                            main(() -> pending.remove(response.requestId())); return;
                        }
                        if(request.timestamp==0)removeCached(request.position);
                        else if(terrainCache!=null)try {terrainCache.touch(request.position);}
                        catch(java.io.IOException e) {cacheWarning(e);} // LRU metadata failure does not invalidate acceptance.
                        main(() -> {
                            long now=System.nanoTime();
                            // Without a source we cannot verify an unchanged enqueue
                            // after Voxy dropped queued writes. Reacquire full data.
                            if(request.timestamp>0)policy.deferUnverified(request.position,now);
                            else {
                                if(request.generation) policy.defer(request.position,now);
                                else {
                                    var player=Minecraft.getInstance().player;
                                    policy.absent(request.position,now,generationAvailable(request.position,player==null?null:player.chunkPosition()));
                                }
                            }
                            rejoinChecks.remove(request.position);
                            pending.remove(response.requestId());
                        }); return;
                    }
                    var shape=new DhTerrainTile.WorldShape(level.getMinBuildHeight(),level.getHeight(),level.dimensionType().hasSkyLight());
                    if(!familyConfirmed) {
                        var evidence=completed.legacyEvidence(new DhTerrainDecoder(DhLimits.DEFAULT),shape);
                        if(evidence!=profile) {
                            fail("Legacy family remains ambiguous; no terrain accepted. Rejoin with a verified service");return;
                        }
                        familyConfirmed=true;
                        onMain(()->{confirmProfile();openCache();return null;});
                    }
                    try(var tile=completed.decode(new DhTerrainDecoder(DhLimits.DEFAULT),profile,shape)) {
                        cacheChecks.remove(request.position);
                        var fence=updateFences.get(request.position);
                        if(fence!=null && tile.modifiedTimestamp()<fence.requiredTimestamp) {
                            main(() -> {pending.remove(response.requestId());policy.defer(request.position,System.nanoTime());});
                            return;
                        }
                        if(active)removeCached(request.position);
                        boolean accepted=deliver(tile,request.position,request.generation);
                        if(accepted && terrainCache!=null && completeTile(tile)
                                && (fence==null || fence.supersededBy(tile.modifiedTimestamp()))) {
                            try {
                                if(verifyCacheDestination(tile) && active) {
                                    terrainCache.protect(protectedPositions);
                                    if(completed.cache(terrainCache,tile.modifiedTimestamp()))cacheWrites.incrementAndGet();
                                    else {cacheDeclined.incrementAndGet();cacheStatus="quota admission declined; network acquisition remains available";}
                                } else {cacheDeclined.incrementAndGet();cacheStatus="destination readback not confirmed; source admission deferred";}
                            } catch(java.io.IOException e) {cacheDeclined.incrementAndGet();cacheWarning(e);}
                        }
                        if(accepted && fence!=null && completeTile(tile) && fence.supersededBy(tile.modifiedTimestamp())) {
                            updateFences.remove(request.position);fence.close();
                        }
                        main(() -> { rejoinChecks.remove(request.position); pending.remove(response.requestId()); });
                    }
                } catch(TimeoutException e) {
                    request.answered=true;assembly.cancel(response.requestId());
                    main(() -> {
                        if(pending.remove(response.requestId(),request)) {
                            policy.defer(request.position,System.nanoTime());
                            if(request.generation && !profile.support()) send(new DhMessage.Cancel(response.requestId()));
                            problem="client thread was busy; terrain retry scheduled";
                        }
                    });
                } catch(DhCodecException e) {
                    if(e.kind()!=DhCodecException.Kind.EXPIRED && e.kind()!=DhCodecException.Kind.UNOWNED && e.kind()!=DhCodecException.Kind.RESOURCE_LIMIT) throw e;
                    request.answered=true;assembly.cancel(response.requestId());
                    main(() -> {
                        if(pending.remove(response.requestId(),request)) {
                            policy.defer(request.position,System.nanoTime());
                            if(request.generation && !profile.support()) send(new DhMessage.Cancel(response.requestId()));
                        }
                    });
                }
            }
        }

        /** Cache admission observes actual destination data, never an enqueue or
         * persistence acknowledgement. An unfinished/rejected handoff stays uncached
         * and is fetched again after reconnect, preserving the known I1 boundary. */
        boolean verifyCacheDestination(DhTerrainTile tile) throws Exception {
            long deadline=System.nanoTime()+2_000_000_000L;
            var shape=tile.world();
            do {
                if(!active || pushRecovery.get() || Thread.currentThread().isInterrupted())return false;
                try(var mapping=budget.reserve(8L<<20);var reader=onMain(()->DhVoxyBridge.openCache(level))) {
                    if(reader==null)return false;
                    boolean matches=true;
                    for(int c=0;c<16 && matches;c++) {
                        int column=c,x=tile.position().x()*4+c/4,z=tile.position().z()*4+c%4;
                        if(!onMain(()->policy.eligible(x,z) && !loaded(level,x,z)))continue;
                        try(var copy=budget.reserve(((long)shape.height()+31)/32*262_144+262_144
                                +(long)tile.materials().size()*128+1_048_576)) {
                            matches=converter.matchesCache(tile,column,reader.readColumn(x,z,shape.minY(),shape.height())::matches);
                        }
                    }
                    if(matches && onMain(()->reader.isCurrent(level)))return true;
                } catch(TimeoutException | ReflectiveOperationException | RuntimeException | LinkageError unavailable) {return false;}
                Thread.sleep(25);
            } while(System.nanoTime()<deadline);
            return false;
        }
        boolean deliver(DhTerrainTile tile,DhPosition position,boolean generation) throws Exception {
            return deliver(tile,position,generation,false);
        }
        boolean deliver(DhTerrainTile tile,DhPosition position,boolean generation,boolean reuseCache) throws Exception {
            try(var mappings=reuseCache?budget.reserve(8L<<20):null;
                var cached=reuseCache?onMain(() -> DhVoxyBridge.openCache(level)):null) {
                if(reuseCache && cached==null) {
                    cacheStatus="destination verification unavailable; cached terrain deferred";
                    retryCache(position,"Voxy comparison unavailable; existing terrain preserved",true);
                    return false;
                }
                return deliver(tile,position,generation,cached,false);
            }
        }
        boolean deliver(DhTerrainTile tile,DhPosition position,boolean generation,
                        dev.vox.lss.compat.VoxyTerrainCache cached, boolean partial) throws Exception {
            if(pushRecovery.get())return false;
            var shape=tile.world();
            for(int c=0;c<4096;c++) switch(tile.coverage(c)) {
                case COMPLETE -> completeCoverage.incrementAndGet();
                case PARTIAL -> partialCoverage.incrementAndGet();
                case UNKNOWN -> unknownCoverage.incrementAndGet();
            }
            int eligible=0,accepted=0,attempted=0,reused=0;
            var fence=updateFences.get(position);
            for(int c=0;c<16 && active;c++) {
                final int column=c,x=tile.position().x()*4+c/4,z=tile.position().z()*4+c%4;
                if(partial && (!completeColumn(tile,c) || fence!=null && (fence.mask&(1<<c))!=0
                        && tile.modifiedTimestamp()>0 && tile.modifiedTimestamp()<fence.timestamp[c]))continue;
                // Main-thread authority check before expansion and again at handoff.
                boolean allowed=onMain(() -> !pushRecovery.get() && (!partial || updatesAllowed()) && policy.eligible(x,z) && !loaded(level,x,z));
                if(!allowed) { skipped.incrementAndGet(); continue; }
                eligible|=1<<c;
                if(!partial && fence!=null && fence.protects(c,tile.modifiedTimestamp())) {
                    accepted|=1<<c;attempted|=1<<c;reused|=1<<c;continue;
                }
                if(cached!=null) {
                    boolean matches=false,preserve=false;
                    try(var verification=budget.reserve(((long)shape.height()+31)/32*262_144+262_144
                            +(long)tile.materials().size()*128+1_048_576)) {
                        var snapshot=cached.readColumn(x,z,shape.minY(),shape.height());
                        matches=converter.matchesCache(tile,column,snapshot::matches);
                        preserve=!matches && !snapshot.isDefault();
                    } catch(ReflectiveOperationException | RuntimeException | LinkageError e) {
                        cacheStatus="destination verification unavailable; cached terrain deferred";
                        retryCache(position,"Voxy comparison failed; existing terrain preserved",true);
                        return false;
                    }
                    if(matches || preserve) {
                        int authority=onMain(() -> !cached.isCurrent(level)?-1:policy.eligible(x,z) && !loaded(level,x,z)?1:0);
                        if(authority<0){retryCache(position,"Voxy destination changed; cached retry scheduled",true);return false;}
                        if(authority==0) {skipped.incrementAndGet();accepted|=1<<c;attempted|=1<<c;continue;}
                        if(matches)cacheReusedColumns.incrementAndGet();
                        else cachePreservedColumns.incrementAndGet();
                        accepted|=1<<c;attempted|=1<<c;reused|=1<<c;
                        continue;
                    }
                }
                try(var conversion=budget.reserve((long)shape.height()/16*65_536 + (long)tile.materials().size()*128)) {
                    var data=converter.convert(tile,column);
                    if(data==null) continue;
                    converted.incrementAndGet();
                    attempted|=1<<c;
                    var destinationChanged=new AtomicBoolean();
                    boolean ok=onMain(() -> {
                        if(cached!=null && !cached.isCurrent(level)) {
                            destinationChanged.set(true);
                            retryCache(position,"Voxy destination changed; cached retry scheduled",true);
                            return false;
                        }
                        if(partial && !updatesAllowed())return false;
                        if(!policy.eligible(x,z) || loaded(level,x,z)) { skipped.incrementAndGet(); return true; } // vanilla now resolves this column
                        invalidateNativeCache();
                        var rejected=new AtomicBoolean();
                        var handle=new LSSApi.IngestFailureHandle() {
                            public void report() { rejected.set(true); }
                            public boolean isActive() { return active && session==Session.this; }
                            public Runnable deferAcceptance() { throw new IllegalStateException("Voxy sink cannot defer DH acceptance"); }
                        };
                        LSSApi.withIngestFailureHandle(level.dimension(),x,z,handle,
                                () -> DhVoxyBridge.ingest(level,x,z,data));
                        if(!rejected.get()) columns.incrementAndGet();
                        else failedColumns.incrementAndGet();
                        return !rejected.get();
                    });
                    if(destinationChanged.get())return false;
                    if(ok) accepted|=1<<c;
                }
            }
            int eligibleMask=eligible,acceptedMask=accepted,attemptedMask=attempted,reusedMask=reused;
            long stamp=fence==null?tile.modifiedTimestamp():Math.max(tile.modifiedTimestamp(),fence.latest());
            return onMain(() -> {
                if(pushRecovery.get())return false;
                if(cached!=null && !cached.isCurrent(level)) {
                    cachedPositions.add(position);policy.deferUnverified(position,System.nanoTime());return false;
                }
                if(partial) {
                    partialAcceptedMask=acceptedMask;
                    boolean success=(acceptedMask&eligibleMask)==eligibleMask;
                    if(success && attemptedMask!=0 && acceptedMask!=0)enqueuedUpdates.incrementAndGet();
                    return success;
                }
                boolean success=policy.accepted(position,eligibleMask,acceptedMask,stamp,System.nanoTime());
                if(success) {
                    if((acceptedMask&~reusedMask)!=0)tiles.incrementAndGet(); rejectedTiles=0;
                } else {
                    incomplete.incrementAndGet();
                    if(attemptedMask!=0 && (acceptedMask&attemptedMask)==0 && ++rejectedTiles>=3)
                        fail("Voxy rejected three whole DH tiles; check ingest settings and restart/reset");
                }
                if(active && eligibleMask!=attemptedMask && !generation
                        && !policy.retryExhausted(position)) {
                    var player=Minecraft.getInstance().player;
                    boolean generationAllowed=player!=null && config.plan()!=DhMessage.GeneratorPlan.DISABLED
                            && config.generationRate()>0 && Math.max(Math.abs(position.x()*4+2-player.chunkPosition().x),
                            Math.abs(position.z()*4+2-player.chunkPosition().z))<=Math.min(32,config.generationDistance());
                    policy.absent(position,System.nanoTime(),generationAllowed);
                }
                return success;
            });
        }

        <T> T onMain(Callable<T> action) throws Exception {
            var result=new CompletableFuture<T>();
            Minecraft.getInstance().execute(() -> {
                if(result.isDone()) return; // A timed-out tile must not execute a late handoff.
                if(!active || session!=this || Minecraft.getInstance().level!=level) {
                    result.completeExceptionally(new CancellationException("DH epoch retired")); return;
                }
                try {
                    var value=action.call();
                    if(!result.complete(value))closeUnused(value);
                } catch(Exception e) { result.completeExceptionally(e); }
            });
            try { return result.get(5,TimeUnit.SECONDS); }
            catch(TimeoutException | InterruptedException e) {
                // Cancellation and completion may cross after action.call acquired an engine.
                // Exactly one side closes a resource which the waiting worker never received.
                if(!result.cancel(false) && !result.isCompletedExceptionally())closeUnused(result.getNow(null));
                throw e;
            }
        }
        static void closeUnused(Object value) throws Exception {if(value instanceof AutoCloseable owned)owned.close();}
        void invalidateNativeCache() {
            if(cacheInvalidated) return;
            invalidateCurrentNativeCache(); cacheInvalidated=true;
        }
    }
}
