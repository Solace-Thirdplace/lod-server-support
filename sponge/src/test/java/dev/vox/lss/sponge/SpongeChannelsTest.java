package dev.vox.lss.sponge;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.networking.payloads.HandshakeC2SPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

    /** The send path must never gate on the peer's registered channels. Fabric's client
     *  handshakes on JOIN before it sends minecraft:register, so an isSupportedBy check
     *  dropped every v20 SessionConfig reply and every Sponge session settled on the
     *  protocol-19 ladder rung (first Sponge soak, 2026-10-11). Classfile-level: the
     *  constant pool of every SpongeChannels class must not name the method. */
    @Test
    void sendNeverGatesOnTheClientsRegisteredChannels() throws IOException {
        var dir = SpongeTestSupport.classesDir().resolve("dev/vox/lss/sponge");
        try (var files = Files.list(dir)) {
            var classes = files.filter(f -> f.getFileName().toString().startsWith("SpongeChannels")).toList();
            assertFalse(classes.isEmpty(), "SpongeChannels classes not found under " + dir);
            for (var f : classes) {
                var text = new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);
                assertFalse(text.contains("isSupportedBy"), f.getFileName() + " references isSupportedBy");
            }
        }
    }

    /** Neither a malformed frame nor a throwing handler escapes into Sponge's dispatch, and
     *  a malformed frame never reaches the handler. */
    @Test
    void inboundFailuresAreContained() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var ok = new SpongeChannels.Entry<>(HandshakeC2SPayload.TYPE, HandshakeC2SPayload.CODEC,
                (payload, player) -> calls.incrementAndGet());
        SpongeChannels.receive(ok, null, new byte[] {(byte) 0xFF});
        assertEquals(0, calls.get(), "a truncated VarInt must not reach the handler");

        var throwing = new SpongeChannels.Entry<>(HandshakeC2SPayload.TYPE, HandshakeC2SPayload.CODEC,
                (payload, player) -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("service bug");
                });
        SpongeChannels.receive(throwing, null, new byte[] {20, 5});
        assertEquals(1, calls.get());
    }
}
