package dev.vox.lss.sponge;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.networking.payloads.HandshakeC2SPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Sponge payload seam (sponge-support-plan.md §5.1). The codecs themselves are the
 * shared xplat ones, byte-pinned by the fabric/paper WireParityTests; what Sponge adds is
 * the channel TABLE and the raw framing, pinned here: wire compatibility is never tiered.
 */
class SpongeChannelsTest {

    private static Set<String> channelConstants() {
        return Arrays.stream(LSSConstants.class.getFields())
                .filter(f -> f.getName().startsWith("CHANNEL_") && Modifier.isStatic(f.getModifiers())
                        && f.getType() == String.class)
                .map(f -> {
                    try {
                        return (String) f.get(null);
                    } catch (IllegalAccessException e) {
                        throw new AssertionError(e);
                    }
                })
                .collect(Collectors.toSet());
    }

    /** A channel added to LSSConstants but not to the table compiles, boots, and then
     *  silently never sends or receives a frame — both directions are pinned. */
    @Test
    void everyChannelIsRegisteredExactlyOnce() {
        var registered = SpongeChannels.ENTRIES.stream().map(SpongeChannels.Entry::id).toList();
        assertEquals(registered.size(), Set.copyOf(registered).size(), "duplicate channel");
        assertEquals(channelConstants(), Set.copyOf(registered));
        assertEquals(13, registered.size(), "the plan's 13-channel census");
    }

    @Test
    void inboundChannelsAreExactlyTheClientToServerPayloads() {
        var inbound = SpongeChannels.ENTRIES.stream().filter(e -> e.handler() != null)
                .map(SpongeChannels.Entry::id).collect(Collectors.toSet());
        assertEquals(Set.of(LSSConstants.CHANNEL_HANDSHAKE, LSSConstants.CHANNEL_CHUNK_REQUEST,
                LSSConstants.CHANNEL_CLIENT_INFO, LSSConstants.CHANNEL_FAR_PLAYER_PREFS,
                LSSConstants.CHANNEL_REGION_SUMMARY_REQ), inbound);
    }

    /** The raw frame is EXACTLY the codec's bytes — no length prefix, no tag — matching the
     *  fabric WireParityTest's explicit reference ops for the handshake. */
    @Test
    void framesCarryExactlyTheCodecBytes() {
        var sent = new HandshakeC2SPayload(20, 0b101);
        var reference = new FriendlyByteBuf(Unpooled.buffer());
        reference.writeVarInt(20);
        reference.writeVarInt(0b101);
        byte[] expected = new byte[reference.readableBytes()];
        reference.readBytes(expected);
        reference.release();

        var received = new AtomicReference<HandshakeC2SPayload>();
        var entry = new SpongeChannels.Entry<>(HandshakeC2SPayload.TYPE, HandshakeC2SPayload.CODEC,
                (payload, player) -> received.set(payload));
        assertArrayEquals(expected, entry.encode(sent));
        entry.receive(null, expected);
        assertEquals(sent, received.get());
    }
}
