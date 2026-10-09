package dev.vox.lss.common.dh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.vox.lss.common.dh.DhMessage.*;

/** Wire-captured DH Support bytes and handwritten literal words protect protocol/family differences. */
class DhProtocolCodecTest {
    private DhProtocolCodec codec(DhProfile profile) { return new DhProtocolCodec(profile, DhLimits.DEFAULT); }
    static byte[] capture(String folder, String name) throws Exception {
        try (var in = DhProtocolCodecTest.class.getResourceAsStream("/dh-corpus/" + folder + "/" + name)) {
            assertNotNull(in); return in.readAllBytes();
        }
    }
    static byte[] hex(String value) { return HexFormat.of().parseHex(value.replace(" ", "")); }
    @Test void capturedClientAndServerControlFrames() throws Exception {
        var codec = codec(DhProfile.SUPPORT_15);
        for (String name : List.of("sent-config.bin", "sent-level-init.bin", "sent-request.bin",
                "received-0001.bin", "received-0002.bin")) {
            byte[] bytes = capture("dh-support-0.14.0-ocean", name);
            assertArrayEquals(bytes, codec.encode(codec.decode(bytes)));
        }
        var request = (Request) codec.decode(capture("dh-support-0.14.0-ocean", "sent-request.bin"));
        assertEquals(new DhPosition(6, 4, -1), request.position());
        assertNull(request.timestamp()); assertEquals(1, request.requestId());
    }
    @Test void knownCoordinateWordsAndSignedBoundaries() {
        assertEquals(6, new DhPosition(6, 0, 0).packed());
        assertEquals(0xffffffffffffff06L, new DhPosition(6, -1, -1).packed());
        assertEquals(new DhPosition(6, -1, -2), DhPosition.tileForChunk(-1, -5));
        for (int x : new int[]{-(1<<27), -1, 0, (1<<27)-1})
            for (int z : new int[]{-(1<<27), -1, 0, (1<<27)-1}) {
                var position = new DhPosition(6, x, z);
                assertEquals(position, DhPosition.unpack(position.packed()));
            }
        assertThrows(IllegalArgumentException.class, () -> new DhPosition(6, 1 << 27, 0));
        assertThrows(IllegalArgumentException.class, () -> new DhPosition(6, 0, -(1 << 27) - 1));
    }
    @Test void literalUnicodeLengthsDifferByFamily() throws Exception {
        // "雪😀": UTF-8 is seven bytes, UTF-16 is three units.
        byte[] official = hex("000f0003 0007 e99baa f09f9880");
        byte[] support = hex("000f0003 0003 e99baa f09f9880");
        assertEquals(new RequestLevelInit("雪😀"), codec(DhProfile.OFFICIAL_15).decode(official));
        assertEquals(new RequestLevelInit("雪😀"), codec(DhProfile.SUPPORT_15).decode(support));
        assertArrayEquals(official, codec(DhProfile.OFFICIAL_15).encode(new RequestLevelInit("雪😀")));
        // Support's server encoder and decoder disagree: outbound client strings must
        // follow the server decoder's byte prefix, never round-trip the server's units.
        assertArrayEquals(official, codec(DhProfile.SUPPORT_15).encode(new RequestLevelInit("雪😀")));
        assertThrows(DhCodecException.class, () -> codec(DhProfile.OFFICIAL_15).decode(support));
        assertThrows(DhCodecException.class, () -> codec(DhProfile.SUPPORT_15).decode(official));
        assertThrows(DhCodecException.class, () -> codec(DhProfile.SUPPORT_15).decode(hex("000f0003 0001 f09f9880")));
    }
    @Test void supportClientRequestUsesByteLengthAndKeepsLaterFieldsAligned() throws Exception {
        var position = new DhPosition(6, -2, 3);
        byte[] frame = codec(DhProfile.SUPPORT_15).encode(new Request(0x11223344, "雪😀", position, 123L));
        try (var in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(frame))) {
            assertEquals(15, in.readUnsignedShort()); assertEquals(7, in.readUnsignedShort());
            assertEquals(0x11223344, in.readInt());
            int length = in.readUnsignedShort(); assertEquals(7, length);
            assertEquals("雪😀", new String(in.readNBytes(length), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(position.packed(), in.readLong()); assertTrue(in.readBoolean());
            assertEquals(123L, in.readLong()); assertEquals(-1, in.read());
        }
    }
    @Test void supportCloseReasonHasWideUtf16PrefixWhileOfficialUsesShortBytes() throws Exception {
        assertEquals(new CloseReason("雪😀"), codec(DhProfile.SUPPORT_15)
                .decode(hex("000f0001 00000003 e99baa f09f9880")));
        assertEquals(new CloseReason("雪😀"), codec(DhProfile.OFFICIAL_15)
                .decode(hex("000f0001 0007 e99baa f09f9880")));
        for (String malformed : List.of("000f0001 ffffffff", "000f0001 7fffffff",
                "000f0001 00000001 f09f9880", "000f0001 00000003 e99baa"))
            assertThrows(DhCodecException.class, () -> codec(DhProfile.SUPPORT_15).decode(hex(malformed)));
        var mismatch = assertThrows(DhCodecException.class,
                () -> codec(DhProfile.SUPPORT_15).decode(hex("000e0001 7fffffff")));
        assertEquals(DhCodecException.Kind.PROTOCOL_MISMATCH, mismatch.kind());
    }
    @ParameterizedTest @EnumSource(DhProfile.class)
    void optionalResponsesRefusalsAndBeacons(DhProfile profile) throws Exception {
        var codec = codec(profile);
        var empty = new Response(-12, null, List.of());
        assertEquals(empty, codec.decode(codec.encode(empty)));
        var beams = new Response(3, 27, List.of(new Beacon(-1, -64, 90, 0x123456)));
        assertEquals(beams, codec.decode(codec.encode(beams)));
        var refusal = new Refusal(4, 88, "Unknown refusal remains data");
        assertEquals(refusal, codec.decode(codec.encode(refusal)));
        var partial = new PartialUpdate("key@minecraft:overworld", 11, beams.beacons());
        assertEquals(partial, codec.decode(codec.encode(partial)));
        var timed = new Request(4, "key@minecraft:overworld", new DhPosition(6,-2,3), -123L);
        assertEquals(timed, codec.decode(codec.encode(timed)));
        assertEquals(new Cancel(4), codec.decode(codec.encode(new Cancel(4))));
    }
    @Test void protocol16PlanAndProtocol15BooleanStayDistinct() throws Exception {
        var config = new SessionConfig(GeneratorPlan.CHUNKS_ONLY, 64, -1, 2, 0, 1, false, 0, true, 64, 1, 500);
        byte[] fifteen = codec(DhProfile.OFFICIAL_15).encode(config);
        byte[] sixteen = codec(DhProfile.OFFICIAL_16).encode(config);
        assertEquals(43, fifteen.length); assertEquals(43, sixteen.length);
        assertEquals(1, fifteen[4]); assertEquals(2, sixteen[4]);
        assertEquals(config, codec(DhProfile.OFFICIAL_16).decode(sixteen));
        sixteen[1] = 15; // Same-width payload, wrong first-field semantics.
        assertThrows(DhCodecException.class, () -> codec(DhProfile.OFFICIAL_15).decode(sixteen));
        byte[] wrong = codec(DhProfile.OFFICIAL_16).encode(config);
        var ex = assertThrows(DhCodecException.class, () -> codec(DhProfile.OFFICIAL_15).decode(wrong));
        assertEquals(DhCodecException.Kind.PROTOCOL_MISMATCH, ex.kind());
    }
    @Test void maximumMinecraftEnvelopeAndLengthRejections() throws Exception {
        var codec = codec(DhProfile.OFFICIAL_16);
        byte[] body = new byte[DhLimits.DEFAULT.frameBytes() - 13];
        assertEquals(DhLimits.DEFAULT.frameBytes(), codec.encode(new Split(3, body, true)).length);
        assertThrows(DhCodecException.class, () -> codec.decode(new byte[DhLimits.DEFAULT.frameBytes()+1]));
        assertThrows(DhCodecException.class, () -> codec.decode(hex("0010000a 00000001 7fffffff 01")));
        assertThrows(DhCodecException.class, () -> codec.decode(hex("00100008 00000001 01 00000002 7fffffff")));
    }
    @Test void malformedStringsBooleansAndTrailingBytesAreContained() throws Exception {
        var codec = codec(DhProfile.OFFICIAL_15);
        for (byte[] bytes : List.of(hex("000f0003 0002 c080"), hex("000f0003 0003 eda080"),
                hex("000f0008 00000001 02"), hex("000f0008 00000001 00 00"),
                hex("000f0064"), hex("000f"))) assertThrows(DhCodecException.class, () -> codec.decode(bytes));
        assertThrows(DhCodecException.class, () -> codec.encode(new RequestLevelInit("\ud800")));
        assertThrows(DhCodecException.class, () -> codec(DhProfile.SUPPORT_15).encode(new CloseReason("close")));
    }
    @Test void retainedFramesShareTheBudgetAndFailedParsesReleaseIt() throws Exception {
        var codec=codec(DhProfile.OFFICIAL_15);
        byte[] bytes=hex("000f0008 00000001 00");
        var budget=new DhMemoryBudget(600);
        var owned=codec.decodeOwned(bytes,budget);
        assertEquals(new Response(1,null,List.of()),owned.message());
        assertThrows(DhCodecException.class,()->codec.decodeOwned(bytes,budget));
        owned.close();owned.close();assertEquals(0,budget.used());
        assertThrows(IllegalStateException.class,owned::message);
        assertThrows(DhCodecException.class,()->codec.decodeOwned(hex("000f0008 00000001 02"),budget));
        assertEquals(0,budget.used());
    }
    @Test void productionClassesHaveNoMinecraftLoaderOrUpstreamDhReferences() throws Exception {
        for(Class<?> type:List.of(DhProtocolCodec.class,DhProfile.class,DhAssembly.class,DhTerrainDecoder.class,
                DhTerrainTile.class,DhPosition.class,DhMaterial.class,DhMemoryBudget.class)) {
            try(var in=type.getResourceAsStream(type.getSimpleName()+".class")){
                assertNotNull(in);
                String code=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.ISO_8859_1);
                for(String forbidden:List.of("net/minecraft/","net/fabricmc/","net/neoforged/","com/seibel/","no/jckf/"))
                    assertFalse(code.contains(forbidden),type.getName()+" links "+forbidden);
            }
        }
    }
}
