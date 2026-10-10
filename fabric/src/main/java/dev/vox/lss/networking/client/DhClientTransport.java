package dev.vox.lss.networking.client;

import dev.vox.lss.common.dh.DhProtocolCodec;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client-only optional channel. Inbound storage is reserved before its callback is queued.
 * The receiver (which is what advertises {@code distant_horizons:msg} to a server) is
 * registered per connection, and only when the user allows DH terrain at join time, so
 * users who did not opt in never look like DH clients.
 */
public final class DhClientTransport {
    private record Payload(byte[] outbound, DhClientSession.Inbound inbound) implements CustomPacketPayload {
        static final Type<Payload> TYPE=new Type<>(Identifier.parse(DhProtocolCodec.CHANNEL));
        static final StreamCodec<FriendlyByteBuf,Payload> CODEC=StreamCodec.of(
                (buf,payload)->buf.writeBytes(payload.outbound),
                buf->new Payload(null,DhClientSession.capture(buf)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public static void init() {
        DhClientSession.installNativeAvailability(()->ClientPlayNetworking.canSend(
                dev.vox.lss.networking.payloads.HandshakeC2SPayload.TYPE));
        // Usable only where this connection also registered the receiver (opted in at join);
        // a server advertisement alone would start a session whose replies are never handled.
        DhClientSession.installChannelAvailability(()->ClientPlayNetworking.canSend(Payload.TYPE)
                && ClientPlayNetworking.getReceived().contains(Payload.TYPE.id()));
        if(FabricLoader.getInstance().isModLoaded("distanthorizons")) {
            DhClientSession.installTransport(null,"Remove Distant Horizons from the client to use "+dev.vox.lss.common.Brand.shortName()+"'s DH source",
                    dev.vox.lss.common.diagnostics.DhStatusSnapshot.Reason.CLIENT_MOD_CONFLICT);
            return;
        }
        try {
        // An integrated host must discard guest C2S data without charging the
        // host client's DH session. Only S2C decode owns inbound terrain bytes.
        PayloadTypeRegistry.serverboundPlay().register(Payload.TYPE,StreamCodec.of(
                (buf,payload)->buf.writeBytes(payload.outbound),
                buf->{ buf.skipBytes(buf.readableBytes()); return new Payload(null,null); }));
        PayloadTypeRegistry.clientboundPlay().register(Payload.TYPE,Payload.CODEC);
        ClientPlayConnectionEvents.INIT.register((handler,client)-> {
            if(!dev.vox.lss.config.LSSClientConfig.CONFIG.dhSourceAllowed())return;
            ClientPlayNetworking.registerReceiver(Payload.TYPE,(payload,context)-> {
                if(payload.inbound!=null) payload.inbound.receive();
            });
        });
        ClientChunkEvents.CHUNK_LOAD.register((level,chunk)->
                DhClientSession.vanillaLoaded(level,chunk.getPos().x(),chunk.getPos().z()));
        DhClientSession.installTransport(bytes->{
            if(!ClientPlayNetworking.canSend(Payload.TYPE))throw new IllegalStateException("DH server channel is unavailable");
            ClientPlayNetworking.send(new Payload(bytes,null));
        },null);
        } catch(RuntimeException failure) {
            DhClientSession.installTransport(null,"DH channel registration failed: "+failure.getMessage());
            dev.vox.lss.common.LSSLogger.warn("DH channel registration failed; native LSS remains available: "+failure.getMessage());
        }
    }
    private DhClientTransport() {}
}
