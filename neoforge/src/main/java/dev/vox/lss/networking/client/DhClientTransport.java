package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.dh.DhProtocolCodec;
import dev.vox.lss.common.dh.DhLimits;
import io.netty.buffer.ByteBuf;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

/**
 * Physical-client-only optional DH transport, with bounded ownership before callbacks.
 * NeoForge registers payload channels once at startup, so the DH channel exists (and is
 * advertised) only when the user allowed DH terrain when the game started; enabling it
 * later reports "restart required" instead of silently advertising for every user.
 */
public final class DhClientTransport {
    private static final Set<Payload> frames=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final class Payload implements CustomPacketPayload, AutoCloseable {
        final byte[] outbound;
        ByteBuf inbound;
        final boolean rejected;
        final long arrived=System.nanoTime();
        Payload(byte[] outbound,ByteBuf inbound,boolean rejected) {
            this.outbound=outbound; this.inbound=inbound; this.rejected=rejected;
        }
        @Override public void close() {
            synchronized(frames) { if(inbound!=null) { inbound.release(); inbound=null; } frames.remove(this); }
        }
        static final Type<Payload> TYPE=new Type<>(ResourceLocation.parse(DhProtocolCodec.CHANNEL));
        static final StreamCodec<FriendlyByteBuf,Payload> CODEC=StreamCodec.of(
                (buf,payload)->buf.writeBytes(payload.outbound),
                buf->{
                    int size=buf.readableBytes();
                    // This NeoForge version cannot register two codecs for one id.
                    // Retain the existing Netty frame until the NETWORK handler tells
                    // us its flow. Guest C2S bytes never touch the host DH session.
                    synchronized(frames) {
                        if(size>DhLimits.DEFAULT.frameBytes() || frames.size()>=32) {
                            buf.skipBytes(size); return new Payload(null,null,true);
                        }
                        var payload=new Payload(null,buf.readRetainedSlice(size),false);
                        frames.add(payload); return payload;
                    }
                });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void register(RegisterPayloadHandlersEvent event) {
        if(ModList.get().isLoaded("distanthorizons")) return;
        if(!dev.vox.lss.config.LSSClientConfig.CONFIG.dhSourceAllowed()) {
            DhClientSession.installTransport(null,"Restart the game to register the DH channel",
                    dev.vox.lss.common.diagnostics.DhStatusSnapshot.Reason.RESTART_REQUIRED);
            return;
        }
        try {
            // DH's NeoForge channel negotiation version is "1", independent of DH
            // wire profile 15/16. Optional keeps ordinary servers joinable.
            event.registrar("1").optional().executesOn(HandlerThread.NETWORK)
                    .playBidirectional(Payload.TYPE,Payload.CODEC,(payload,context)-> {
                        synchronized(frames) {
                            try {
                                if(context.flow()!=PacketFlow.CLIENTBOUND) return;
                                if(payload.rejected) {
                                    DhClientSession.rejectTransportFrame("DH transport frame/queue limit reached"); return;
                                }
                                if(payload.inbound!=null) {
                                    var owned=DhClientSession.capture(new FriendlyByteBuf(payload.inbound));
                                    if(owned!=null) owned.receive();
                                }
                            } finally { payload.close(); }
                        }
                    });
        } catch(RuntimeException failure) {
            DhClientSession.installTransport(null,"DH channel registration failed: "+failure.getMessage());
            LSSLogger.warn("DH channel registration failed; native LSS remains available: "+failure.getMessage());
        }
    }
    public static void init() {
        DhClientSession.installNativeAvailability(()-> {
            var connection=Minecraft.getInstance().getConnection();
            return connection!=null && connection.hasChannel(dev.vox.lss.networking.payloads.HandshakeC2SPayload.TYPE);
        });
        DhClientSession.installChannelAvailability(()-> {
            var connection=Minecraft.getInstance().getConnection();
            return connection!=null && connection.hasChannel(Payload.TYPE);
        });
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class,e-> {
            synchronized(frames) { for(var frame:frames.toArray(Payload[]::new)) frame.close(); }
        });
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class,e-> {
            long now=System.nanoTime();
            synchronized(frames) {
                for(var frame:frames.toArray(Payload[]::new))
                    if(now-frame.arrived>5_000_000_000L) frame.close();
            }
        });
        if(ModList.get().isLoaded("distanthorizons")) {
            DhClientSession.installTransport(null,"Remove Distant Horizons from the client to use "+dev.vox.lss.common.Brand.shortName()+"'s DH source",
                    dev.vox.lss.common.diagnostics.DhStatusSnapshot.Reason.CLIENT_MOD_CONFLICT);
            return;
        }
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Load.class,e-> {
            if(e.getLevel() instanceof ClientLevel level)
                DhClientSession.vanillaLoaded(level,e.getChunk().getPos().x,e.getChunk().getPos().z);
        });
        DhClientSession.installTransport(bytes-> {
            var connection=Minecraft.getInstance().getConnection();
            if(connection==null || !connection.hasChannel(Payload.TYPE))
                throw new IllegalStateException("DH server channel is unavailable");
            ClientPacketDistributor.sendToServer(new Payload(bytes,null,false));
        },null);
    }
    private DhClientTransport() {}
}
