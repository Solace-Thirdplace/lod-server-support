package dev.vox.lssfixture.dh;

import dev.vox.lss.common.dh.*;
import dev.vox.lss.compat.ModCompat;
import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.networking.client.*;
import net.minecraft.client.Minecraft;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Owned real-client reconnects: persisted DH receipts, the Voxy presence probe, and independent Voxy block readback. */
public final class RejoinProbe {
    static final Map<Object,AtomicLong> targetConversions=new java.util.concurrent.ConcurrentHashMap<>();
    public static void converted(DhTerrainTile tile) {
        if(!tile.position().equals(ContentProbe.TILE))return;
        try {targetConversions.computeIfAbsent(Probe.field(DhClientSession.class,"session"),ignored->new AtomicLong()).incrementAndGet();}
        catch(Exception e) {ContentProbe.failure(e);}
    }
    static long conversions(Object s) {var count=targetConversions.get(s);return count==null?0:count.get();}
    static int phase;
    static long deadline=System.nanoTime()+240_000_000_000L,stamp,editedStamp,lastStatus;
    static Object previous;
    static int blockComparisons;
    static boolean voxyWasCleared;
    static boolean checkedRestart;
    static java.util.concurrent.CompletableFuture<Boolean> destinationCheck;
    static long lastStoredCheck,lastNativeRead,nativeStableSince;
    static String nativeDigest;
    /**
     * Receipt for the target persisted at the expected timestamp, and Voxy holds terrain
     * in every one of its 16 columns (the production presence probe, read off-thread).
     * Blocking flushes are acceptable here: this is a private fixture, never shipped.
     */
    static boolean fullyStored(Object s,long now) throws Exception {
        if(destinationCheck!=null) {
            if(!destinationCheck.isDone())return false;
            boolean ready=destinationCheck.join();destinationCheck=null;if(ready)return true;
        }
        if(now-lastStoredCheck<1_000_000_000L)return false;lastStoredCheck=now;
        long expected=policy(s).timestamp(ContentProbe.TILE);
        if(expected<=0 || persisted(s)!=expected)return false;
        var level=net.minecraft.client.Minecraft.getInstance().level;
        var probe=dev.vox.lss.compat.DhVoxyBridge.openProbe(level);
        if(probe==null)throw new IllegalStateException("Pinned Voxy presence probe unavailable");
        int minY=level.getMinY(),height=level.getHeight();
        destinationCheck=java.util.concurrent.CompletableFuture.supplyAsync(()->{
            try {
                for(int column=0;column<16;column++)
                    if(!probe.hasTerrain(ContentProbe.TILE.x()*4+column/4,ContentProbe.TILE.z()*4+column%4,minY,height))return false;
                return true;
            } catch(Exception e) {throw new java.util.concurrent.CompletionException(e);}
        });
        return false;
    }
    /** Force-save, drain the receipt IO thread, then read the target receipt back from disk (0 = absent). */
    static long persisted(Object s) throws Exception {
        var store=(DhReceiptStore)Probe.field(s,"receiptStore");
        if(store==null)return 0;
        var save=s.getClass().getDeclaredMethod("saveReceipts",boolean.class);save.setAccessible(true);save.invoke(s,true);
        flushReceiptIo();
        var entry=store.load().get(ContentProbe.TILE);
        return entry==null || !entry.current()?0:entry.timestamp();
    }
    static void flushReceiptIo() throws Exception {
        var io=(java.util.concurrent.ExecutorService)Probe.field(DhClientSession.class,"RECEIPT_IO");
        io.submit(()->{}).get(10,java.util.concurrent.TimeUnit.SECONDS);
    }
    static boolean conditionalTarget(Object s) {
        return ContentProbe.replies.stream().anyMatch(r->r.ask.epoch()==s && r.ask.request().timestamp()!=null && r.ask.request().timestamp()>0);
    }
    static long count(Object s,String key) throws Exception {return ((AtomicLong)Probe.field(s,key)).get();}
    static DhSessionPolicy policy(Object s) throws Exception {return (DhSessionPolicy)Probe.field(s,"policy");}
    static Map<?,?> stored(Object s) throws Exception {return (Map<?,?>)Probe.field(s,"stored");}
    static void advance(int next,long now) {phase=next;ContentProbe.phase=next;deadline=now+240_000_000_000L;}
    static void disconnect(Object s,int next,long now,String id,List<String> commands) throws Exception {
        previous=s;ContentProbe.disconnectAndEdit(id,commands);advance(next,now);
        if(((DhMemoryBudget)Probe.field(s,"budget")).used()!=0)throw new IllegalStateException("Retired epoch retained source memory");
    }
    static boolean reply(Object s,long timestamp,boolean changed) throws Exception {
        var pending=(Map<?,?>)Probe.field(s,"pending");
        return ContentProbe.replies.stream().anyMatch(r->r.ask.epoch()==s && r.changed==changed
                && r.ask.request().timestamp()!=null && r.ask.request().timestamp()==timestamp && !pending.containsKey(r.id));
    }
    static boolean unchanged(Object s,long timestamp,int stage,long now) throws Exception {
        if(policy(s).timestamp(ContentProbe.TILE)!=timestamp || count(s,"restored")==0 || !reply(s,timestamp,false))return false;
        if(ContentProbe.replies.stream().anyMatch(r->r.ask.epoch()==s && r.changed))throw new IllegalStateException("Unchanged target downloaded a full payload");
        return ContentProbe.knownBlocks(stage,now);
    }
    static void evidence(String name,String details) throws Exception {
        blockComparisons+=300;Probe.write(name,"{\"pass\":true,\"block_comparisons\":300,"+details+"}");
    }
    static String nativeColumnDigest() throws Exception {
        var mc=Minecraft.getInstance();Class<?> id=Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier");
        Object engine=id.getMethod("getNullable").invoke(id.getMethod("of",net.minecraft.world.level.Level.class).invoke(null,mc.level));
        var hash=java.security.MessageDigest.getInstance("SHA-256");Class<?> ec=engine.getClass();
        ec.getMethod("acquireRef").invoke(engine);
        try {
            for(int y=mc.level.getMinY()>>5;y<(mc.level.getMaxY()+1+31)>>5;y++) {
                Object section=ec.getMethod("acquire",int.class,int.class,int.class,int.class).invoke(engine,0,-6,y,0);
                try {
                    long[] data=(long[])section.getClass().getMethod("copyData").invoke(section);
                    var words=java.nio.ByteBuffer.allocate(16*16*32*8);
                    for(int vy=0;vy<32;vy++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)words.putLong(data[(vy<<10)|(z<<5)|x]);
                    hash.update(words.array());
                } finally {section.getClass().getMethod("release").invoke(section);}
            }
        } finally {ec.getMethod("releaseRef").invoke(engine);}
        return java.util.HexFormat.of().formatHex(hash.digest());
    }
    public static void tick(long now) throws Exception {
        var mc=Minecraft.getInstance();
        if(!checkedRestart) {
            checkedRestart=true;var checkpoint=mc.gameDirectory.toPath().resolve("dh-restart.json");
            if(java.nio.file.Files.isRegularFile(checkpoint)) {
                var state=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(checkpoint)).getAsJsonObject();
                editedStamp=state.get("timestamp").getAsLong();blockComparisons=state.get("block_comparisons").getAsInt();advance(12,now);
            }
        }
        if(ContentProbe.callbackFailure.get()!=null)throw new IllegalStateException("Rejoin observer failure",ContentProbe.callbackFailure.get());
        if(now>deadline)throw new IllegalStateException("Rejoin phase "+phase+" timed out; "+DhClientSession.status());
        if(now-lastStatus>1_000_000_000L){lastStatus=now;Probe.write("dh-rejoin-status.txt",phase+" "+DhClientSession.status());}
        if(Set.of(-2,2,4,6,10,13).contains(phase)){if(ContentProbe.reconnectAfterEdit())advance(phase==-2?1:phase+1,now);return;}
        if(mc.level==null || mc.player==null)return;
        mc.player.getAbilities().flying=true;mc.player.onUpdateAbilities();mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        if(!mc.level.dimension().equals(ContentProbe.DIM)){ContentProbe.teleport(now);return;}
        Object s=Probe.field(DhClientSession.class,"session");if(s==null)return;
        if(!DhClientSession.running())throw new IllegalStateException(DhClientSession.status());
        if(ClientNetGlue.hasReceivedSessionConfig() || ClientNetGlue.getRequestManager()!=null)throw new IllegalStateException("Native pipeline active in DH reconnect test");
        if(!LSSClientConfig.CONFIG.snapshot().lod().dh().syncOnLoad())throw new IllegalStateException("Rejoin validation requires sync on load");
        if(Set.of(3,5,7,11,14).contains(phase) && s==previous)throw new IllegalStateException("Reconnect reused the old DH epoch");
        if((boolean)Probe.field(s,"receiptsUnavailable"))throw new IllegalStateException("DH receipts unavailable: "+DhClientSession.status());
        if(Probe.field(s,"receiptStore")==null || (boolean)Probe.field(s,"receiptsLoading"))return;
        if(phase==0) {
            // Startup's asynchronous DH export can precede the console seeding.
            // Apply the known planes after this dimension's DH service is live,
            // and start the cold proof with both receipts and Voxy terrain cleared.
            ClientCommandActions.runReset(ignored->{},true,false);
            disconnect(s,-2,now,"cold-source-prime",List.of(
                    "execute in lssfixture:tall run fill -192 350 0 -129 351 63 minecraft:lime_concrete",
                    "execute in lssfixture:tall run fill -192 -118 0 -129 -117 63 minecraft:cyan_concrete",
                    "execute in lssfixture:tall run fill -192 350 0 -129 351 63 minecraft:red_concrete",
                    "execute in lssfixture:tall run fill -192 -118 0 -129 -117 63 minecraft:blue_concrete"));return;
        }
        if(phase==1 && policy(s).timestamp(ContentProbe.TILE)>0
                && ContentProbe.knownBlocks(0,now) && fullyStored(s,now)) {
            stamp=policy(s).timestamp(ContentProbe.TILE);
            evidence("dh-rejoin-cold.json","\"server_timestamp\":"+stamp+",\"persisted_receipt\":true,\"voxy_presence_premise\":true");
            disconnect(s,2,now,"unchanged-rejoin",List.of());
        } else if(phase==3 && unchanged(s,stamp,0,now)) {
            if(conversions(s)!=0)throw new IllegalStateException("Warm target was reconverted instead of trusting its receipt");
            evidence("dh-rejoin-unchanged.json","\"conditional_timestamp\":"+stamp+",\"target_payloads\":0,\"target_converted_columns\":0,\"target_ingested_columns\":0,\"restored_receipts\":"+count(s,"restored"));
            advance(15,now);mc.getConnection().sendCommand("execute in lssfixture:tall run tp @s -184 355 8");
        } else if(phase==15 && !policy(s).eligible(-12,0)) {
            if(now-lastNativeRead<1_000_000_000L)return;lastNativeRead=now;
            String observed=nativeColumnDigest();
            if(!observed.equals(nativeDigest)){nativeDigest=observed;nativeStableSince=now;return;}
            if(now-nativeStableSince<5_000_000_000L)return;
            // Observe actual vanilla ownership and preserve a stable destination
            // baseline. This is a per-run preservation oracle, not completion API.
            mc.getConnection().sendCommand("execute in lssfixture:tall run tp @s 0 100 0");advance(16,now);
        } else if(phase==16 && mc.player.chunkPosition().x>=-2) {
            disconnect(s,13,now,"vanilla-refined-rejoin",List.of());
        } else if(phase==14 && unchanged(s,stamp,0,now)) {
            if(conversions(s)!=0)throw new IllegalStateException("Unchanged rejoin overwrote existing destination terrain");
            if(!nativeDigest.equals(nativeColumnDigest()))throw new IllegalStateException("Previously vanilla-held destination changed on rejoin");
            evidence("dh-rejoin-partial-receipts.json","\"previous_session_vanilla_held\":true,\"destination_digest_preserved\":true,\"target_payloads\":0,\"target_converted_columns\":0");
            disconnect(s,4,now,"offline-edit",List.of("execute in lssfixture:tall run fill -192 350 0 -129 351 63 minecraft:lime_concrete","execute in lssfixture:tall run fill -192 -118 0 -129 -117 63 minecraft:cyan_concrete"));
        } else if(phase==5 && reply(s,stamp,true) && policy(s).timestamp(ContentProbe.TILE)>stamp && ContentProbe.knownBlocks(1,now)) {
            editedStamp=policy(s).timestamp(ContentProbe.TILE);
            evidence("dh-rejoin-offline-edit.json","\"old_timestamp\":"+stamp+",\"new_timestamp\":"+editedStamp+",\"conditional_changed_reply\":true");
            // Isolate Voxy loss from an LSS reset: retire the source worker (saving
            // receipts), wipe only this run's Voxy store, then reconnect with receipts intact.
            var retire=s.getClass().getDeclaredMethod("retire");retire.setAccessible(true);
            if(!(boolean)retire.invoke(s))throw new IllegalStateException("Voxy-only reset did not retire source worker");
            var report=ModCompat.resetVoxyLods(false,null);
            if(report.outcome()!=ModCompat.VoxyResetOutcome.RESET)throw new IllegalStateException("Voxy-only wipe failed: "+report);
            voxyWasCleared=true;
            flushReceiptIo();
            var survivor=((DhReceiptStore)Probe.field(s,"receiptStore")).load().get(ContentProbe.TILE);
            if(survivor==null || survivor.timestamp()!=editedStamp)throw new IllegalStateException("Voxy wipe erased the persisted receipt");
            disconnect(s,6,now,"voxy-cache-loss",List.of());
        } else if(phase==7 && reply(s,0,true) && ContentProbe.knownBlocks(1,now) && fullyStored(s,now)) {
            // A surviving receipt over an empty destination must never be trusted.
            if(conditionalTarget(s))throw new IllegalStateException("Voxy loss still sent the stale receipt timestamp");
            if(conversions(s)!=16)throw new IllegalStateException("Voxy loss did not rebuild every target column");
            editedStamp=policy(s).timestamp(ContentProbe.TILE);
            evidence("dh-rejoin-voxy-loss.json","\"voxy_disk_and_memory_cleared\":"+voxyWasCleared+",\"stale_receipt_dropped_by_probe\":true,\"target_full_request\":true,\"target_converted_columns\":16,\"persisted_timestamp\":"+editedStamp);
            Probe.write("dh-restart.json","{\"timestamp\":"+editedStamp+",\"block_comparisons\":"+blockComparisons+"}");
            Probe.done=true;mc.stop();
        } else if(phase==12 && unchanged(s,editedStamp,1,now)) {
            if(conversions(s)!=0)throw new IllegalStateException("New JVM reconverted target instead of trusting its persisted receipt");
            evidence("dh-rejoin-process-restart.json","\"new_client_jvm\":true,\"target_payloads\":0,\"target_converted_columns\":0,\"target_ingested_columns\":0,\"restored_receipts\":"+count(s,"restored"));
            var store=(DhReceiptStore)Probe.field(s,"receiptStore");
            previous=s;ClientCommandActions.runReset(ignored->{},true,false);
            if(!stored(s).isEmpty() || !store.load().isEmpty())throw new IllegalStateException("Explicit reset retained DH receipts");
            advance(8,now);
        } else if(phase==8 && s!=previous && reply(s,0,true) && ContentProbe.knownBlocks(1,now) && fullyStored(s,now)) {
            if(count(s,"restored")!=0)throw new IllegalStateException("Explicit reset restored old receipts");
            evidence("dh-rejoin-reset.json","\"receipts_cleared\":true,\"target_full_timestamp_zero\":true");
            // Receipt loss must be a full request, even with correct Voxy terrain left.
            // Discard first so retirement cannot write this session's receipts back.
            var discard=s.getClass().getDeclaredMethod("discardReceipts");discard.setAccessible(true);discard.invoke(s);
            var retire=s.getClass().getDeclaredMethod("retire");retire.setAccessible(true);
            if(!(boolean)retire.invoke(s))throw new IllegalStateException("Receipt deletion did not retire worker");
            flushReceiptIo();
            var clear=ColumnCacheStore.class.getDeclaredMethod("clearDhReceipts",String.class);clear.setAccessible(true);
            clear.invoke(null,mc.getCurrentServer().ip);
            disconnect(s,10,now,"receipt-loss",List.of());
        } else if(phase==11 && reply(s,0,true) && ContentProbe.knownBlocks(1,now)) {
            if(conditionalTarget(s))throw new IllegalStateException("Receipt loss still sent a conditional target request");
            evidence("dh-rejoin-receipt-loss.json","\"target_missing_full_request\":true,\"receipt_refilled\":"+stored(s).containsKey(ContentProbe.TILE));
            Probe.write("dh-result.json","{\"pass\":true,\"unchanged_rejoin\":true,\"offline_edit\":true,\"voxy_loss\":true,\"explicit_reset\":true,\"receipt_loss\":true,\"block_comparisons\":"+blockComparisons+",\"refresh_off\":true}");
            Probe.done=true;mc.stop();
        }
    }
}
