package dev.vox.lss.sponge;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.LogThrottle;
import dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.vox.lss.networking.payloads.BatchResponseS2CPayload;
import dev.vox.lss.networking.payloads.ClientInfoC2SPayload;
import dev.vox.lss.networking.payloads.ColumnStampsS2CPayload;
import dev.vox.lss.networking.payloads.DirtyColumnsS2CPayload;
import dev.vox.lss.networking.payloads.FarPlayerPrefsC2SPayload;
import dev.vox.lss.networking.payloads.FarPlayerRosterS2CPayload;
import dev.vox.lss.networking.payloads.FarPlayerUpdatesS2CPayload;
import dev.vox.lss.networking.payloads.HandshakeC2SPayload;
import dev.vox.lss.networking.payloads.RegionSummaryRequestC2SPayload;
import dev.vox.lss.networking.payloads.RegionSummaryS2CPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import dev.vox.lss.networking.server.LSSServerNetworking;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.api.ResourceKey;
import org.spongepowered.api.event.lifecycle.RegisterChannelEvent;
import org.spongepowered.api.network.ServerConnectionState;
import org.spongepowered.api.network.channel.ChannelExceptionHandler;
import org.spongepowered.api.network.channel.raw.RawDataChannel;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * The payload seam (sponge-support-plan.md §1.3): the xplat {@link CustomPacketPayload}s
 * over Sponge raw data channels — the role {@code LSSNetworking} plays on the other
 * loaders. Vanilla's {@code DiscardedPayload} drops unknown payload bytes, so frames
 * cannot go out as plain NMS custom-payload packets; each channel carries EXACTLY the
 * bytes the payload's own {@link StreamCodec} writes, which is what Fabric and NeoForge
 * put on the wire (wire compatibility is never tiered — SpongeChannelsTest.framesCarryExactlyTheCodecBytes). Registering
 * also makes Sponge announce the channels in {@code minecraft:register}, which Fabric
 * clients check before sending.
 *
 * <p>Threading: Sponge's {@code handleCustomPayload} hop already runs inbound handlers on
 * the server thread (the xplat receivers assume it). {@code sendTo} ends in
 * {@code Connection.send}, safe off-main (the processing thread and the summary lane send).
 *
 * <p>Send contract = Fabric's {@code ServerPlayNetworking.send}: NO registered-channel
 * pre-check. Fabric's client fires {@code JOIN} (where LSS sends its handshake) BEFORE it
 * sends its own {@code minecraft:register}, and Sponge runs the handshake handler at once —
 * an {@code isSupportedBy} gate here dropped every SessionConfig reply, and each client
 * fell down its discovery ladder to protocol 19 ~10 s later (the first Sponge soak's
 * session-version red; pinned by {@code SpongeChannelsTest}). Sponge's {@code sendTo} only
 * checks the play phase. LSS sends only to peers that spoke an LSS channel first, and a
 * peer without the channel discards the payload, exactly as on Fabric. A failed send
 * stays on its own future (the installed handler suppresses it).
 */
public final class SpongeChannels {

    record Entry<T extends CustomPacketPayload>(CustomPacketPayload.Type<T> type,
                                                StreamCodec<FriendlyByteBuf, T> codec,
                                                BiConsumer<T, ServerPlayer> handler) {
        static <T extends CustomPacketPayload> Entry<T> in(CustomPacketPayload.Type<T> type,
                StreamCodec<FriendlyByteBuf, T> codec, BiConsumer<T, ServerPlayer> handler) {
            return new Entry<>(type, codec, handler);
        }

        static <T extends CustomPacketPayload> Entry<T> out(CustomPacketPayload.Type<T> type,
                StreamCodec<FriendlyByteBuf, T> codec) {
            return new Entry<>(type, codec, null);
        }

        T decode(byte[] bytes) {
            var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
            try {
                return codec.decode(buf);
            } finally {
                buf.release();
            }
        }

        void receive(ServerPlayer player, byte[] bytes) {
            handler.accept(decode(bytes), player);
        }

        void handle(T payload, ServerPlayer player) {
            handler.accept(payload, player);
        }

        @SuppressWarnings("unchecked")
        byte[] encode(CustomPacketPayload payload) {
            var buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                codec.encode(buf, (T) payload);
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                return bytes;
            } finally {
                buf.release();
            }
        }

        String id() {
            return type.id().toString();
        }
    }

    static final List<Entry<?>> ENTRIES = List.of(
            // Client -> Server
            Entry.in(HandshakeC2SPayload.TYPE, HandshakeC2SPayload.CODEC, LSSServerNetworking::handleHandshake),
            Entry.in(BatchChunkRequestC2SPayload.TYPE, BatchChunkRequestC2SPayload.CODEC, LSSServerNetworking::handleBatchRequest),
            Entry.in(ClientInfoC2SPayload.TYPE, ClientInfoC2SPayload.CODEC, LSSServerNetworking::handleClientInfo),
            Entry.in(FarPlayerPrefsC2SPayload.TYPE, FarPlayerPrefsC2SPayload.CODEC, LSSServerNetworking::handleFarPlayerPrefs),
            Entry.in(RegionSummaryRequestC2SPayload.TYPE, RegionSummaryRequestC2SPayload.CODEC, LSSServerNetworking::handleRegionSummaryRequest),
            // Server -> Client
            Entry.out(SessionConfigS2CPayload.TYPE, SessionConfigS2CPayload.CODEC),
            Entry.out(BatchResponseS2CPayload.TYPE, BatchResponseS2CPayload.CODEC),
            Entry.out(DirtyColumnsS2CPayload.TYPE, DirtyColumnsS2CPayload.CODEC),
            Entry.out(VoxelColumnS2CPayload.TYPE, VoxelColumnS2CPayload.CODEC),
            Entry.out(FarPlayerRosterS2CPayload.TYPE, FarPlayerRosterS2CPayload.CODEC),
            Entry.out(FarPlayerUpdatesS2CPayload.TYPE, FarPlayerUpdatesS2CPayload.CODEC),
            Entry.out(RegionSummaryS2CPayload.TYPE, RegionSummaryS2CPayload.CODEC),
            Entry.out(ColumnStampsS2CPayload.TYPE, ColumnStampsS2CPayload.CODEC));

    private static final Map<CustomPacketPayload.Type<?>, Entry<?>> BY_TYPE = new ConcurrentHashMap<>();
    private static final Map<CustomPacketPayload.Type<?>, RawDataChannel> CHANNELS = new ConcurrentHashMap<>();
    private static volatile boolean receiving;

    private SpongeChannels() {}

    static void register(RegisterChannelEvent event) {
        for (var entry : ENTRIES) {
            var channel = event.register(ResourceKey.resolve(entry.id()), RawDataChannel.class);
            // A failed send stays on its own future (sendTo's caller ignores it, the
            // Fabric fire-and-forget contract) instead of logging once per column.
            channel.setExceptionHandler(ChannelExceptionHandler.logEverything().suppressAllIfFutureIsPresent());
            if (entry.handler() != null) {
                channel.play().addHandler(ServerConnectionState.Game.class, (data, state) -> {
                    if (!receiving) return;
                    var player = (ServerPlayer) (Object) state.player();
                    receive(entry, player, data.readBytes(data.available()));
                });
            }
            BY_TYPE.put(entry.type(), entry);
            CHANNELS.put(entry.type(), channel);
        }
    }

    // A hostile or buggy peer must not flood the log: one line per minute per kind, with
    // the suppressed count. A decode failure and a handler failure are told apart so a
    // service bug is never diagnosed as a malformed frame.
    private static final LogThrottle MALFORMED_WARN = new LogThrottle(60_000);
    private static final LogThrottle HANDLER_WARN = new LogThrottle(60_000);

    /** Decode, then dispatch; neither failure escapes into Sponge's channel dispatch. */
    static <T extends CustomPacketPayload> void receive(Entry<T> entry, ServerPlayer player, byte[] bytes) {
        T payload;
        try {
            payload = entry.decode(bytes);
        } catch (RuntimeException e) {
            long n = MALFORMED_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
            if (n > 0) {
                LSSLogger.warn("Dropped malformed " + entry.id() + " frame from "
                        + name(player) + " (" + n + " since last report): " + e);
            }
            return;
        }
        try {
            entry.handle(payload, player);
        } catch (RuntimeException e) {
            long n = HANDLER_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
            if (n > 0) {
                LSSLogger.error("Handler for " + entry.id() + " failed for " + name(player)
                        + " (" + n + " since last report)", e);
            }
        }
    }

    private static String name(ServerPlayer player) {
        return player == null ? "<unknown>" : player.getName().getString();
    }

    /** Set while the service runs: frames outside that window are dropped. */
    static void setReceiving(boolean value) {
        receiving = value;
    }

    /** The registered channel count (boot smoke: all 13 must register). */
    public static int registeredChannelCount() {
        return CHANNELS.size();
    }

    /** Silent no-op for an unknown type or a dropped connection. No registered-channel
     *  check — see the class javadoc for why that gate broke every v20 session. */
    public static void send(ServerPlayer player, CustomPacketPayload payload) {
        var entry = BY_TYPE.get(payload.type());
        var channel = CHANNELS.get(payload.type());
        if (entry == null || channel == null || player.connection == null) return;
        var spongePlayer = (org.spongepowered.api.entity.living.player.server.ServerPlayer) (Object) player;
        byte[] bytes = entry.encode(payload);
        channel.play().sendTo(spongePlayer, buf -> buf.writeBytes(bytes));
    }
}
