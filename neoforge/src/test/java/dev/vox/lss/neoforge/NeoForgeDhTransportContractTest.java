package dev.vox.lss.neoforge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The DH channel must register a CLIENTBOUND handler on 26.x NeoForge.
 *
 * <p>On this NeoForge line {@code PayloadRegistrar.playBidirectional(type, codec, handler)}
 * forwards to the four-argument overload with a {@code null} client handler, so the
 * handler only serves the serverbound flow. NeoForge then refuses client startup with
 * "Some clientbound payloads are missing client-side handlers: [distant_horizons:msg]"
 * for every user who allowed DH server terrain (found by the 26.2 private live rig,
 * 2026-10-10). Classfile pin: the test JVM cannot link NeoForge networking, so it reads
 * the compiled call's descriptor from the constant pool without loading the class.
 */
class NeoForgeDhTransportContractTest {

    private static final String THREE_ARG = "(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$Type;"
            + "Lnet/minecraft/network/codec/StreamCodec;Lnet/neoforged/neoforge/network/handling/IPayloadHandler;)"
            + "Lnet/neoforged/neoforge/network/registration/PayloadRegistrar;";
    private static final String FOUR_ARG = "(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$Type;"
            + "Lnet/minecraft/network/codec/StreamCodec;Lnet/neoforged/neoforge/network/handling/IPayloadHandler;"
            + "Lnet/neoforged/neoforge/network/handling/IPayloadHandler;)"
            + "Lnet/neoforged/neoforge/network/registration/PayloadRegistrar;";

    @Test
    void dhChannelRegistersAClientboundHandler() throws IOException {
        String pool;
        try (var in = getClass().getClassLoader()
                .getResourceAsStream("dev/vox/lss/networking/client/DhClientTransport.class")) {
            assertNotNull(in, "compiled DhClientTransport must be on the test classpath");
            // Descriptors are ASCII, so their modified-UTF-8 constant-pool bytes are plain text.
            pool = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
        assertTrue(pool.contains("playBidirectional"), "the DH channel must stay bidirectional");
        assertTrue(pool.contains(FOUR_ARG),
                "playBidirectional must pass an explicit client handler (four-argument overload)");
        assertFalse(pool.contains(THREE_ARG),
                "the three-argument playBidirectional leaves distant_horizons:msg without a client handler"
                        + " and crashes NeoForge client startup");
    }
}
