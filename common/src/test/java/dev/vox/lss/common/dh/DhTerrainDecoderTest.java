package dev.vox.lss.common.dh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.vox.lss.common.dh.DhMessage.*;
import static dev.vox.lss.common.dh.DhTerrainTile.*;
import static dev.vox.lss.common.dh.DhTerrainFixtures.*;

class DhTerrainDecoderTest {
    static final DhPosition NEGATIVE=new DhPosition(6,-1,-1);
    static final WorldShape SMALL=new WorldShape(-64,64,true);
    private static DhMemoryBudget budget(){return new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());}

    @Test void wireCapturedTilesReplayAndPreserveEveryColumn() throws Exception {
        for(var row:List.of(Map.entry("dh-support-0.14.0-ocean",new DhPosition(6,4,-1)),
                Map.entry("dh-support-0.14.0-meadow",new DhPosition(6,-4,-1)))) {
            var budget=budget();var codec=new DhProtocolCodec(DhProfile.SUPPORT_15,DhLimits.DEFAULT);
            try(var assembly=new DhAssembly(DhLimits.DEFAULT,budget)) {
                assembly.expect(1,row.getValue());
                int last=row.getKey().endsWith("ocean")?4:6;
                for(int i=3;i<last;i++)assembly.accept((Split)codec.decode(
                        DhProtocolCodecTest.capture(row.getKey(),String.format("received-%04d.bin",i))));
                var response=(Response)codec.decode(DhProtocolCodecTest.capture(row.getKey(),String.format("received-%04d.bin",last)));
                try(var complete=assembly.response(response);
                    var tile=complete.decode(new DhTerrainDecoder(DhLimits.DEFAULT),DhProfile.SUPPORT_15,new WorldShape(-64,384,true))) {
                    for(int c=0;c<4096;c++)assertEquals(Coverage.COMPLETE,tile.coverage(c));
                    assertEquals(last==4?21:38,tile.materials().size());
                    assertEquals(last==4?21177:35209,java.util.stream.IntStream.range(0,4096).map(tile::spanCount).sum());
                    var counts=new HashMap<String,Long>();
                    for(int c=0;c<4096;c++)for(int i=0;i<tile.spanCount(c);i++){
                        var p=tile.span(c,i);counts.merge(tile.materials().get(p.material()).block(),(long)p.height(),Long::sum);
                    }
                    assertEquals(64L*64*384,counts.values().stream().mapToLong(Long::longValue).sum());
                    if(last==6){assertEquals(571780L,counts.get("minecraft:stone"));assertEquals(4422L,counts.get("minecraft:grass_block"));}
                    // The normalized hash is an implementation regression pin, not an independent byte oracle.
                    var props=new Properties();
                    try(var in=getClass().getResourceAsStream("/dh-corpus/"+row.getKey()+"/normalized.properties")){assertNotNull(in);props.load(in);}
                    assertEquals(props.getProperty("sha256"),tile.normalizedSha256());
                }
            }
            assertEquals(0,budget.used());
        }
    }
    @Test void officialSurfaceCaptureClipsOnlyTheVerifiedUpperAirBand() throws Exception {
        String fixture="dh-official-3.3.3-surface-only-ceiling";
        var position=new DhPosition(6,-3,-1);var memory=budget();
        var codec=new DhProtocolCodec(DhProfile.OFFICIAL_16,DhLimits.DEFAULT);
        var response=(Response)codec.decode(DhProtocolCodecTest.capture(fixture,"received-0004.bin"));
        try(var assembly=new DhAssembly(DhLimits.DEFAULT,memory)) {
            assembly.expect(response.requestId(),position);
            assembly.accept((Split)codec.decode(DhProtocolCodecTest.capture(fixture,"received-0003.bin")));
            try(var completed=assembly.response(response);var tile=completed.decode(new DhTerrainDecoder(DhLimits.DEFAULT),DhProfile.OFFICIAL_16,new WorldShape(-64,384,true))) {
                assertEquals(2,tile.format());assertEquals(4,tile.compression());
                for(int c=0;c<4096;c++) {
                    assertEquals(5,tile.generation(c));assertEquals(Coverage.COMPLETE,tile.coverage(c));assertEquals(2,tile.spanCount(c));
                    int covered=0;
                    for(int n=0;n<2;n++) {var span=tile.span(c,n);covered+=span.height();assertTrue(span.bottom()+span.height()<=384);
                        if(tile.materials().get(span.material()).air()){assertEquals(384,span.bottom()+span.height());assertEquals(15,span.skyLight());assertEquals(0,span.blockLight());}}
                    assertEquals(384,covered);
                }
            }
        }
        assertEquals(0,memory.used());
    }

    @Test void surfaceAirBandDoesNotRelaxOtherQualityMaterialOrHeightBounds() throws Exception {
        var shape=new WorldShape(-64,384,true);
        for(var profile:DhProfile.values())for(int generation:new int[]{5,6,9})for(int material:new int[]{0,1,2})for(int top:new int[]{448,449}) {
            var cols=new long[4096][];Arrays.fill(cols,new long[]{point(1,0,384,0)});
            cols[0]=new long[]{point(material,100,top-100,15),point(1,0,100,0)};
            var blobs=rawBlobs(2,cols,new String[]{"minecraft:plains_DH-BSW_minecraft:air","minecraft:plains_DH-BSW_minecraft:stone","minecraft:plains_DH-BSW_minecraft:water"});
            Arrays.fill(blobs[5],(byte)generation);var bytes=dto(2,4,profile,blobs,false);var memory=budget();
            boolean verified=profile==DhProfile.OFFICIAL_16 && generation==5 && material==0 && top==448;
            if(verified)try(var tile=new DhTerrainDecoder(DhLimits.DEFAULT).decode(bytes,profile,NEGATIVE,shape,memory)) {
                assertEquals(Coverage.COMPLETE,tile.coverage(0));assertEquals(5,tile.generation(0));
                assertEquals(284,tile.span(0,0).height());assertEquals(15,tile.span(0,0).skyLight());
            } else assertThrows(DhCodecException.class,()->new DhTerrainDecoder(DhLimits.DEFAULT).decode(bytes,profile,NEGATIVE,shape,memory));
            assertEquals(0,memory.used());
        }
    }

    @Test void officialNeoForgeCapturePreservesItsCompleteAndUnknownHalves() throws Exception {
        String fixture="dh-official-3.3.3-neoforge-partial";
        var position=new DhPosition(6,-1,1);var budget=budget();
        var codec=new DhProtocolCodec(DhProfile.OFFICIAL_16,DhLimits.DEFAULT);
        try(var assembly=new DhAssembly(DhLimits.DEFAULT,budget)) {
            assembly.expect(2,position);
            for(int i=4;i<=5;i++)assembly.accept((Split)codec.decode(
                    DhProtocolCodecTest.capture(fixture,String.format("received-%04d.bin",i))));
            var response=(Response)codec.decode(DhProtocolCodecTest.capture(fixture,"received-0006.bin"));
            try(var complete=assembly.response(response);
                var tile=complete.decode(new DhTerrainDecoder(DhLimits.DEFAULT),DhProfile.OFFICIAL_16,
                        new WorldShape(-64,384,true))) {
                assertEquals(72,tile.materials().size());
                assertTrue(tile.materials().getFirst().air());
                assertEquals("minecraft:stone",tile.materials().get(6).block());
                var counts=new HashMap<String,Long>();int spans=0;
                for(int c=0;c<4096;c++) {
                    boolean known=c>=2048;
                    assertEquals(known?Coverage.COMPLETE:Coverage.UNKNOWN,tile.coverage(c),"column "+c);
                    assertEquals(known?9:0,tile.generation(c));
                    assertEquals(known?1:0,tile.worldCompression(c));
                    spans+=tile.spanCount(c);
                    for(int i=0;i<tile.spanCount(c);i++) {
                        var span=tile.span(c,i);
                        assertTrue(span.bottom()+span.height()<=384);
                        counts.merge(tile.materials().get(span.material()).block(),(long)span.height(),Long::sum);
                    }
                }
                assertEquals(24389,spans);
                assertEquals(2048L*384,counts.values().stream().mapToLong(Long::longValue).sum());
                assertEquals(548284L,counts.get("minecraft:air"));
                assertEquals(44087L,counts.get("minecraft:stone"));
                assertEquals(73181L,counts.get("minecraft:deepslate"));
                assertEquals(5464L,counts.get("minecraft:bedrock"));
                var props=new Properties();
                try(var in=getClass().getResourceAsStream("/dh-corpus/"+fixture+"/normalized.properties")) {
                    assertNotNull(in);props.load(in);
                }
                assertEquals(props.getProperty("sha256"),tile.normalizedSha256());
            }
        }
        assertEquals(0,budget.used());
    }
    @Test void stockOfficial16CeilingSentinelIsNotTerrainOrKnownAir() throws Exception {
        var owned=budget();var codec=new DhProtocolCodec(DhProfile.OFFICIAL_16,DhLimits.DEFAULT);
        var reply=(Response)codec.decode(DhProtocolCodecTest.capture("dh-official-3.3.3-ceiling-sentinel","received-0010.bin"));
        try(var assembly=new DhAssembly(DhLimits.DEFAULT,owned)) {
            assembly.expect(reply.requestId(),new DhPosition(6,-2,-1));
            assembly.accept((Split)codec.decode(DhProtocolCodecTest.capture("dh-official-3.3.3-ceiling-sentinel","received-0009.bin")));
            try(var completed=assembly.response(reply);var tile=completed.decode(new DhTerrainDecoder(DhLimits.DEFAULT),
                    DhProfile.OFFICIAL_16,new WorldShape(-128,512,true))) {
                int unknown=0,known=0;
                for(int c=0;c<4096;c++) {
                    if(tile.coverage(c)==Coverage.UNKNOWN){unknown++;assertEquals(0,tile.spanCount(c));}
                    else known++;
                    for(int i=0;i<tile.spanCount(c);i++)assertTrue(tile.span(c,i).bottom()+tile.span(c,i).height()<=512);
                }
                assertEquals(3072,unknown);assertEquals(1024,known);
            }
        }
        assertEquals(0,owned.used());
    }
    @Test void stockOfficial15CeilingSentinelKeepsItsInWorldData() throws Exception {
        var owned=budget();var codec=new DhProtocolCodec(DhProfile.OFFICIAL_15,DhLimits.DEFAULT);
        var reply=(Response)codec.decode(DhProtocolCodecTest.capture("dh-official-3.2.0-b-ceiling-sentinel","received-0004.bin"));
        try(var assembly=new DhAssembly(DhLimits.DEFAULT,owned)) {
            assembly.expect(reply.requestId(),new DhPosition(6,-1,1));
            assembly.accept((Split)codec.decode(DhProtocolCodecTest.capture("dh-official-3.2.0-b-ceiling-sentinel","received-0003.bin")));
            try(var completed=assembly.response(reply);var tile=completed.decode(new DhTerrainDecoder(DhLimits.DEFAULT),
                    DhProfile.OFFICIAL_15,new WorldShape(-128,512,true))) {
                int unknown=0,known=0;
                for(int c=0;c<4096;c++) {
                    if(tile.coverage(c)==Coverage.UNKNOWN){unknown++;assertEquals(0,tile.spanCount(c));}
                    else known++;
                    for(int i=0;i<tile.spanCount(c);i++)assertTrue(tile.span(c,i).bottom()+tile.span(c,i).height()<=512);
                }
                assertEquals(1024,unknown);assertEquals(3072,known);assertEquals(18652,java.util.stream.IntStream.range(0,4096).map(tile::spanCount).sum());
            }
        }
        assertEquals(0,owned.used());
    }
    @Test void customHeightCaptureReplaysSeededPlanesAndOwnedConditionalRemoval() throws Exception {
        String fixture="dh-official-3.3.3-neoforge-content";
        var position=new DhPosition(6,-3,0);var owned=budget();var codec=new DhProtocolCodec(DhProfile.OFFICIAL_16,DhLimits.DEFAULT);
        var requests=new HashMap<Integer,Request>();int changed=0,unchanged=0;long previous=0;
        var hashes=new Properties();
        try(var in=getClass().getResourceAsStream("/dh-corpus/"+fixture+"/normalized.properties")) {assertNotNull(in);hashes.load(in);}
        try(var assembly=new DhAssembly(DhLimits.DEFAULT,owned)) {
            for(String name:List.of("target-0001-request.bin","target-0002-split.bin","target-0003-response.bin",
                    "target-0004-request.bin","target-0005-response.bin","target-0006-request.bin","target-0007-split.bin","target-0008-response.bin")) {
                var message=codec.decode(DhProtocolCodecTest.capture(fixture,name));
                if(message instanceof Request request) {
                    assertEquals(position,request.position());assertEquals("lssfixture:tall",request.level(),"Opaque request key is sanitized");
                    requests.put(request.requestId(),request);assembly.expect(request.requestId(),position);
                } else if(message instanceof Split split)assembly.accept(split);
                else if(message instanceof Response response) {
                    var request=requests.remove(response.requestId());assertNotNull(request,"Every response is owned");
                    try(var completed=assembly.response(response)) {
                        if(completed==null) {
                            assertNotNull(request.timestamp());assertEquals(previous,request.timestamp());assertTrue(previous>0);unchanged++;continue;
                        }
                        try(var tile=completed.decode(new DhTerrainDecoder(DhLimits.DEFAULT),DhProfile.OFFICIAL_16,new WorldShape(-128,512,true))) {
                            assertTrue(changed<2);assertTrue(tile.modifiedTimestamp()>previous);
                            if(changed==1) {assertNotNull(request.timestamp());assertEquals(previous,request.timestamp());}
                            for(int c=0;c<4096;c++) {
                                assertEquals(Coverage.COMPLETE,tile.coverage(c));
                                for(int y:new int[]{350,351,-118,-117,340,-128,-127}) {
                                    String expected=y==340?"minecraft:gold_block":y==-128?"minecraft:bedrock":y==-127?"minecraft:stone"
                                            :y<0?(changed==0?"minecraft:cyan_concrete":"minecraft:purple_concrete"):(changed==0?"minecraft:lime_concrete":"minecraft:air");
                                    assertEquals(expected,capturedBlock(tile,c,y),"column "+c+" y="+y);
                                }
                            }
                            // Known seed/edit planes above are the independent oracle;
                            // these hashes additionally pin this decoder's normalized output.
                            assertEquals(hashes.getProperty(changed==0?"initial.sha256":"removed.sha256"),tile.normalizedSha256());
                            previous=tile.modifiedTimestamp();changed++;
                        }
                    }
                } else fail("Unexpected opaque/control frame in known-content corpus");
            }
            assertEquals(2,changed);assertEquals(1,unchanged);assertTrue(requests.isEmpty());
        }
        assertEquals(0,owned.used());
    }
    private static String capturedBlock(DhTerrainTile tile,int column,int y) {
        int relative=y-tile.world().minY();
        for(int i=0;i<tile.spanCount(column);i++) {
            var span=tile.span(column,i);
            if(relative>=span.bottom() && relative<span.bottom()+span.height())return tile.materials().get(span.material()).block();
        }
        return null; // Missing coverage never stands in for air.
    }

    @ParameterizedTest @CsvSource({"1,0","1,3","1,4","2,0","2,3","2,4"})
    void formatsAndCompressionRetainPredictionEdgesLightsAndUnknowns(int format,int mode) throws Exception {
        var budget=budget();
        try(var tile=new DhTerrainDecoder(DhLimits.DEFAULT).decode(tile(format,mode,DhProfile.OFFICIAL_16),
                DhProfile.OFFICIAL_16,NEGATIVE,SMALL,budget)) {
            var expected=columns();
            for(int c=0;c<4096;c++) {
                assertEquals(expected[c].length,tile.spanCount(c),"column "+c);
                for(int i=0;i<expected[c].length;i++){
                    long p=expected[c][i];var actual=tile.span(c,i);
                    assertEquals((int)p,actual.material());
                    assertEquals((int)(p>>>44&4095),actual.bottom(),"bottom at "+c);
                    assertEquals((int)(p>>>32&4095),actual.height());
                    assertEquals((int)(p>>>56&15),actual.skyLight());
                    assertEquals((int)(p>>>60&15),actual.blockLight());
                }
            }
            assertEquals(Coverage.UNKNOWN,tile.coverage(1000));
            assertEquals(Coverage.COMPLETE,tile.coverage(2000));
            assertTrue(tile.materials().get(tile.span(2000,0).material()).air());
            assertEquals(Coverage.PARTIAL,tile.coverage(77));
            assertTrue(tile.regenerate());assertEquals(123,tile.modifiedTimestamp());assertEquals(456,tile.createdTimestamp());
        }
        assertEquals(0,budget.used());
    }
    @Test void formatOneInsideBothProtocolEnvelopes() throws Exception {
        for(var profile:DhProfile.values()){
            var budget=budget();
            try(var tile=new DhTerrainDecoder(DhLimits.DEFAULT).decode(tile(1,0,profile),profile,NEGATIVE,SMALL,budget)){
                assertEquals(profile.regenerationFlag(),tile.regenerate());
                assertEquals(123,tile.modifiedTimestamp());assertEquals(456,tile.createdTimestamp());
            }
            assertEquals(0,budget.used());
        }
        assertFailure(tile(1,0,DhProfile.OFFICIAL_16),DhProfile.OFFICIAL_15,DhLimits.DEFAULT,SMALL);
        assertFailure(tile(1,0,DhProfile.OFFICIAL_15),DhProfile.OFFICIAL_16,DhLimits.DEFAULT,SMALL);
    }
    @Test void noSkyNormalizationPreservesBlockLight() throws Exception {
        var budget=budget();
        try(var tile=new DhTerrainDecoder(DhLimits.DEFAULT).decode(tile(2,4,DhProfile.OFFICIAL_16),
                DhProfile.OFFICIAL_16,NEGATIVE,new WorldShape(0,64,false),budget)){
            assertEquals(0,tile.span(1,0).skyLight());assertEquals(3,tile.span(1,0).blockLight());
        }
        assertEquals(0,budget.used());
    }
    @Test void differingTimestampSlotsFollowServerFamilyInsteadOfProtocolNumber() throws Exception {
        byte[] dto=tile(1,0,DhProfile.OFFICIAL_15);
        // Handwritten final words: 0x0102030405060708, then 0x1112131415161718.
        byte[] words=hex("0102030405060708"+"1112131415161718");
        System.arraycopy(words,0,dto,dto.length-16,16);
        for(var profile:List.of(DhProfile.OFFICIAL_15,DhProfile.SUPPORT_15)){
            var budget=budget();
            try(var tile=new DhTerrainDecoder(DhLimits.DEFAULT).decode(dto,profile,NEGATIVE,SMALL,budget)){
                assertEquals(profile.support()?0x1112131415161718L:0x0102030405060708L,tile.modifiedTimestamp());
                assertEquals(profile.support()?0x0102030405060708L:0x1112131415161718L,tile.createdTimestamp());
            }
            assertEquals(0,budget.used());
        }
    }
    @Test void mappingUsesModifiedUtfAndBoundedStateParsing() throws Exception {
        var columns=new long[4096][];Arrays.setAll(columns,c->new long[]{point(0,0,64,15)});
        var blobs=rawBlobs(2,columns,new String[]{"minecraft:plains_DH-BSW_minecraft:stone_STATE_{name:雪😀}"});
        var budget=budget();
        try(var tile=new DhTerrainDecoder(DhLimits.DEFAULT).decode(dto(2,0,DhProfile.OFFICIAL_16,blobs,false),
                DhProfile.OFFICIAL_16,NEGATIVE,SMALL,budget)){
            assertEquals("雪😀",tile.materials().getFirst().properties().get("name"));
        }
        assertEquals(0,budget.used());
        for(String bad:List.of("invalid","minecraft:plains_DH-BSW_java.lang.Runtime",
                "minecraft:plains_DH-BSW_minecraft:stone_STATE_{a:b}{a:c}",
                "minecraft:plains_DH-BSW_minecraft:stone_STATE_{a:b}trailing"))
            assertThrows(DhCodecException.class,()->DhMaterial.parse(bad));
    }
    @Test void officialWrapperAirAndStatelessSuffixNormalizeWithoutRelaxingOtherIdentities() throws Exception {
        for(var profile:List.of(DhProfile.OFFICIAL_15,DhProfile.OFFICIAL_16)) {
            var air=DhMaterial.parse("minecraft:forest_DH-BSW_AIR",profile);
            assertTrue(air.air());assertEquals("minecraft:air",air.block());
            var stone=DhMaterial.parse("minecraft:forest_DH-BSW_minecraft:stone_STATE_",profile);
            assertEquals("minecraft:stone",stone.block());assertTrue(stone.properties().isEmpty());
            for(String invalid:List.of("AIR_STATE_","AIR_STATE_{a:b}","AIRx","minecraft:Stone_STATE_",
                    "minecraft:stone_STATE_{a:b}trailing","minecraft:stone_STATE_{}"))
                assertThrows(DhCodecException.class,()->DhMaterial.parse("minecraft:forest_DH-BSW_"+invalid,profile));
        }
        for(String entry:List.of("AIR","minecraft:stone_STATE_")) {
            String encoded="minecraft:forest_DH-BSW_"+entry;
            assertThrows(DhCodecException.class,()->DhMaterial.parse(encoded,DhProfile.SUPPORT_15));
            assertThrows(DhCodecException.class,()->DhMaterial.parse(encoded));
        }
    }
    @Test void supportMappingsUseUtf16UnitsAndPlainUtf8WithoutConsumingTheNextEntry() throws Exception {
        var columns=new long[4096][];Arrays.setAll(columns,c->new long[]{point(0,0,64,15)});
        var blobs=rawBlobs(1,columns,new String[0]);
        String material="minecraft:plains_DH-BSW_minecraft:stone_STATE_{name:雪😀}";
        String air="minecraft:plains_DH-BSW_minecraft:air";
        blobs[7]=bytes(out->{out.writeInt(2); for(String entry:List.of(material,air)) {
            out.writeShort(entry.length());out.write(entry.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }});
        var budget=budget();
        try(var tile=new DhTerrainDecoder(DhLimits.DEFAULT).decode(dto(1,0,DhProfile.SUPPORT_15,blobs,false),
                DhProfile.SUPPORT_15,NEGATIVE,SMALL,budget)) {
            assertEquals("雪😀",tile.materials().getFirst().properties().get("name"));
            assertTrue(tile.materials().get(1).air());
        }
        assertEquals(0,budget.used());
    }
    @Test void supportMappingBudgetCountsUtf8BytesAndRejectsInvalidScalars() throws Exception {
        var blobs=rawBlobs(1,columns(),new String[0]);
        String material="minecraft:plains_DH-BSW_minecraft:stone_STATE_{name:雪😀}";
        byte[] encoded=material.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        blobs[7]=bytes(out->{out.writeInt(1);out.writeShort(material.length());out.write(encoded);});
        var d=DhLimits.DEFAULT;
        var limited=new DhLimits(d.frameBytes(),d.tileBytes(),d.decodedBytes(),d.ownedBytes(),d.decompressorKiB(),
                d.buffers(),d.spans(),d.mappings(),encoded.length-1,d.stringBytes(),d.worldHeight(),d.idleNanos(),d.lifetimeNanos());
        assertEquals(DhCodecException.Kind.RESOURCE_LIMIT,assertFailure(dto(1,0,DhProfile.SUPPORT_15,blobs,false),
                DhProfile.SUPPORT_15,limited,SMALL).kind());
        for(byte[] bad:List.of(hex("000000010001f09f9880"),hex("000000010001c080"))) {
            blobs[7]=bad;
            assertEquals(DhCodecException.Kind.MALFORMED,assertFailure(dto(1,0,DhProfile.SUPPORT_15,blobs,false),
                    DhProfile.SUPPORT_15,DhLimits.DEFAULT,SMALL).kind());
        }
    }
    @Test void missingEdgesAndInconsistentRepeatedCornersAreRejected() throws Exception {
        var blobs=rawBlobs(2,columns(),new String[]{"minecraft:plains_DH-BSW_minecraft:stone","minecraft:plains_DH-BSW_minecraft:air"});
        var different=columns();different[0]=new long[]{point(0,0,64,0x1f)};
        blobs[4]=rawBlobs(2,different,new String[0])[4];
        assertEquals(DhCodecException.Kind.MALFORMED,assertFailure(dto(2,0,DhProfile.OFFICIAL_16,blobs,false),
                DhProfile.OFFICIAL_16,DhLimits.DEFAULT,SMALL).kind());
        blobs[4]=new byte[0];
        assertFailure(dto(2,0,DhProfile.OFFICIAL_16,blobs,false),DhProfile.OFFICIAL_16,DhLimits.DEFAULT,SMALL);
    }
    @Test void spanBoundsOverlapAndMappingIdsCannotInventCoverage() throws Exception {
        for(long[] bad:new long[][]{{point(10,0,64,15)},{point(0,0,0,15)},
                {point(0,0,64,15),point(0,2,1,15)},{point(0,63,2,15)}}) {
            var columns=columns();columns[0]=bad;
            assertFailure(dto(1,0,DhProfile.OFFICIAL_15,rawBlobs(1,columns,new String[]{
                    "minecraft:plains_DH-BSW_minecraft:stone","minecraft:plains_DH-BSW_minecraft:air"}),false),
                    DhProfile.OFFICIAL_15,DhLimits.DEFAULT,SMALL);
        }
    }
    @Test void officialProtocol15CapturedBuilderCeilingIsNormalizedWithoutInventingCoverage() throws Exception {
        var profile=DhProfile.OFFICIAL_15;var budget=budget();
        var codec=new DhProtocolCodec(profile,DhLimits.DEFAULT);
        var position=new DhPosition(6,-1,1);
        try(var assembly=new DhAssembly(DhLimits.DEFAULT,budget)) {
            assembly.expect(2,position);
            for(int frame:List.of(4,5))assembly.accept((Split)codec.decode(DhProtocolCodecTest.capture(
                    "dh-official-3.2.0-b-neoforge",String.format("received-%04d.bin",frame))));
            var response=(Response)codec.decode(DhProtocolCodecTest.capture("dh-official-3.2.0-b-neoforge","received-0006.bin"));
            try(var complete=assembly.response(response);var tile=complete.decode(new DhTerrainDecoder(DhLimits.DEFAULT),
                    profile,new WorldShape(-64,384,true))) {
                assertEquals(2,tile.format());assertEquals(4,tile.compression());
                for(int column=0;column<4096;column++) {
                    assertEquals(Coverage.COMPLETE,tile.coverage(column));
                    boolean ceiling=false;
                    for(int i=0;i<tile.spanCount(column);i++) {
                        var span=tile.span(column,i);int top=span.bottom()+span.height();
                        assertTrue(top<=384);
                        if(top==384){assertTrue(tile.materials().get(span.material()).air());ceiling=true;}
                    }
                    assertTrue(ceiling);
                }
            }
        }
        assertEquals(0,budget.used());
    }
    @Test void ceilingAirAccommodationFollowsVerifiedBuilderFamilies() throws Exception {
        var columns=new long[4096][];Arrays.setAll(columns,c->new long[]{point(0,0,65,15)});
        var blobs=rawBlobs(1,columns,new String[]{"minecraft:plains_DH-BSW_minecraft:air"});
        for(var profile:List.of(DhProfile.SUPPORT_15,DhProfile.OFFICIAL_15,DhProfile.OFFICIAL_16)) {
            var budget=budget();
            try(var tile=new DhTerrainDecoder(DhLimits.DEFAULT).decode(dto(1,0,profile,blobs,false),profile,NEGATIVE,SMALL,budget)){
                assertEquals(64,tile.span(0,0).height());assertEquals(Coverage.COMPLETE,tile.coverage(0));
            }
            assertEquals(0,budget.used());
        }
        blobs=rawBlobs(1,columns,new String[]{"minecraft:plains_DH-BSW_minecraft:stone"});
        for(var profile:List.of(DhProfile.SUPPORT_15,DhProfile.OFFICIAL_15,DhProfile.OFFICIAL_16))
            assertFailure(dto(1,0,profile,blobs,false),profile,DhLimits.DEFAULT,SMALL);
    }
    @Test void verifiedCeilingAirOnlyClipsExactlyOneVoxelFromAnInWorldSpan() throws Exception {
        for(var profile:List.of(DhProfile.SUPPORT_15,DhProfile.OFFICIAL_15,DhProfile.OFFICIAL_16))
        for(String block:List.of("air","cave_air","void_air")) {
            var columns=new long[4096][];Arrays.setAll(columns,c->new long[]{point(0,63,2,15)});
            var blobs=rawBlobs(1,columns,new String[]{"minecraft:plains_DH-BSW_minecraft:"+block});
            var budget=budget();
            try(var tile=new DhTerrainDecoder(DhLimits.DEFAULT).decode(dto(1,0,profile,blobs,false),
                    profile,NEGATIVE,SMALL,budget)) {
                assertEquals(1,tile.span(0,0).height());assertEquals(Coverage.PARTIAL,tile.coverage(0));
            }
            assertEquals(0,budget.used());
            for(long bad: new long[]{point(0,0,66,15),point(0,65,1,15)}) {
                Arrays.setAll(columns,c->new long[]{bad});
                assertFailure(dto(1,0,profile,rawBlobs(1,columns,
                                new String[]{"minecraft:plains_DH-BSW_minecraft:"+block}),false),
                        profile,DhLimits.DEFAULT,SMALL);
            }
        }
    }
    @Test void ceilingOnlyOfficialAirRemainsUnknownButSolidsAndSupportStillFail() throws Exception {
        for(var official:List.of(DhProfile.OFFICIAL_15,DhProfile.OFFICIAL_16))
        for(String block:List.of("air","cave_air","void_air")) {
            var columns=new long[4096][];Arrays.setAll(columns,c->new long[]{point(0,64,1,15)});
            var blobs=rawBlobs(1,columns,new String[]{"minecraft:plains_DH-BSW_minecraft:"+block});
            var owned=budget();
            try(var tile=new DhTerrainDecoder(DhLimits.DEFAULT).decode(dto(1,0,official,blobs,false),
                    official,NEGATIVE,SMALL,owned)) {
                for(int c=0;c<4096;c++){assertEquals(0,tile.spanCount(c));assertEquals(Coverage.UNKNOWN,tile.coverage(c));}
            }
            assertEquals(0,owned.used());
            for(var other:List.of(DhProfile.SUPPORT_15))
                assertFailure(dto(1,0,other,blobs,false),other,DhLimits.DEFAULT,SMALL);
        }
        for(String block:List.of("water","stone")) {
            var columns=new long[4096][];Arrays.setAll(columns,c->new long[]{point(0,64,1,15)});
            assertFailure(dto(1,0,DhProfile.OFFICIAL_16,rawBlobs(1,columns,
                    new String[]{"minecraft:plains_DH-BSW_minecraft:"+block}),false),DhProfile.OFFICIAL_16,DhLimits.DEFAULT,SMALL);
        }
    }
    @Test void hostileEnvelopeLengthsAndUnsupportedModesFailBeforeAllocation() throws Exception {
        byte[] good=tile(1,0,DhProfile.OFFICIAL_15);
        byte[] bad=good.clone();Arrays.fill(bad,12,16,(byte)0x7f);
        assertFailure(bad,DhProfile.OFFICIAL_15,DhLimits.DEFAULT,SMALL);
        for(int mode:new int[]{1,2,99}){
            bad=good.clone();bad[bad.length-19]=(byte)mode;
            assertEquals(DhCodecException.Kind.UNSUPPORTED,assertFailure(bad,DhProfile.OFFICIAL_15,DhLimits.DEFAULT,SMALL).kind());
        }
        assertFailure(Arrays.copyOf(good,good.length-1),DhProfile.OFFICIAL_15,DhLimits.DEFAULT,SMALL);
        var budget=budget();
        assertThrows(DhCodecException.class,()->new DhTerrainDecoder(DhLimits.DEFAULT).decode(good,
                DhProfile.OFFICIAL_15,new DhPosition(6,0,0),SMALL,budget));
        assertEquals(0,budget.used());
    }
    @Test void resourceLimitsReleaseWorkingMemoryOnEveryFailure() throws Exception {
        var d=DhLimits.DEFAULT;
        for(var limits:List.of(
                new DhLimits(d.frameBytes(),d.tileBytes(),8,d.ownedBytes(),d.decompressorKiB(),8,d.spans(),d.mappings(),d.mappingBytes(),4096,4096,d.idleNanos(),d.lifetimeNanos()),
                new DhLimits(d.frameBytes(),d.tileBytes(),d.decodedBytes(),d.ownedBytes(),64,8,d.spans(),d.mappings(),d.mappingBytes(),4096,4096,d.idleNanos(),d.lifetimeNanos()),
                new DhLimits(d.frameBytes(),d.tileBytes(),d.decodedBytes(),d.ownedBytes(),d.decompressorKiB(),8,1,d.mappings(),d.mappingBytes(),4096,4096,d.idleNanos(),d.lifetimeNanos()))) {
            var ex=assertFailure(tile(2,3,DhProfile.OFFICIAL_16),DhProfile.OFFICIAL_16,limits,SMALL);
            // Decoded-byte and dictionary budgets are memory pressure; the per-tile span
            // ceiling is tile-intrinsic, so it is a malformed tile (strike), never pressure.
            assertEquals(limits.spans()==1?DhCodecException.Kind.MALFORMED:DhCodecException.Kind.RESOURCE_LIMIT,ex.kind());
        }
        var budget=new DhMemoryBudget(100);
        assertThrows(DhCodecException.class,()->new DhTerrainDecoder(d).decode(tile(1,0,DhProfile.OFFICIAL_15),
                DhProfile.OFFICIAL_15,NEGATIVE,SMALL,budget));assertEquals(0,budget.used());
    }
    @Test void overflowingVarintsAndHostileMappingLengthsAreBounded() throws Exception {
        var blobs=rawBlobs(2,columns(),new String[]{"minecraft:plains_DH-BSW_minecraft:stone","minecraft:plains_DH-BSW_minecraft:air"});
        byte[] old=blobs[0];blobs[0]=new byte[old.length+4];
        System.arraycopy(old,1,blobs[0],5,old.length-1);
        Arrays.fill(blobs[0],0,4,(byte)0xff);blobs[0][4]=0x10;
        assertFailure(dto(2,0,DhProfile.OFFICIAL_16,blobs,false),DhProfile.OFFICIAL_16,DhLimits.DEFAULT,SMALL);
        blobs[7]=hex("7fffffff");
        assertFailure(dto(2,0,DhProfile.OFFICIAL_16,blobs,false),DhProfile.OFFICIAL_16,DhLimits.DEFAULT,SMALL);
    }
    @Test void officialXzRequiresItsFooterWhileSupportReadsExactCounts() throws Exception {
        var blobs=rawBlobs(1,columns(),new String[]{"minecraft:plains_DH-BSW_minecraft:stone","minecraft:plains_DH-BSW_minecraft:air"});
        byte[] unfinished=dto(1,3,DhProfile.OFFICIAL_15,blobs,true);
        assertFailure(unfinished,DhProfile.OFFICIAL_15,DhLimits.DEFAULT,SMALL);
        var budget=budget();
        try(var tile=new DhTerrainDecoder(DhLimits.DEFAULT).decode(unfinished,DhProfile.SUPPORT_15,NEGATIVE,SMALL,budget)){
            assertEquals(Coverage.UNKNOWN,tile.coverage(1000));
        }
        assertEquals(0,budget.used());
    }
    @Test void supportUnfinishedXzCannotHideTrailingDecodedData() throws Exception {
        var blobs=rawBlobs(1,columns(),new String[]{"minecraft:plains_DH-BSW_minecraft:stone","minecraft:plains_DH-BSW_minecraft:air"});
        for(int slot:new int[]{0,5,6,7}) {
            var extra=blobs.clone();extra[slot]=Arrays.copyOf(blobs[slot],blobs[slot].length+1);
            assertEquals(DhCodecException.Kind.MALFORMED,assertFailure(dto(1,3,DhProfile.SUPPORT_15,extra,true),
                    DhProfile.SUPPORT_15,DhLimits.DEFAULT,SMALL).kind());
        }
    }
    @Test void rawAndZstdTrailingDecodedDataAndDamagedFramesAreRejected() throws Exception {
        for(int mode:new int[]{0,4}){
            var blobs=rawBlobs(2,columns(),new String[]{"minecraft:plains_DH-BSW_minecraft:stone","minecraft:plains_DH-BSW_minecraft:air"});
            blobs[5]=Arrays.copyOf(blobs[5],4097);
            assertFailure(dto(2,mode,DhProfile.OFFICIAL_16,blobs,false),DhProfile.OFFICIAL_16,DhLimits.DEFAULT,SMALL);
        }
        byte[] damaged=tile(2,4,DhProfile.OFFICIAL_16);
        damaged[16]=0; // Damage the first Zstd magic before native decompression.
        assertFailure(damaged,DhProfile.OFFICIAL_16,DhLimits.DEFAULT,SMALL);
    }
    @Test void unknownColumnsCanHaveAnEmptyMapAndAreNeverAir() throws Exception {
        var columns=new long[4096][];Arrays.setAll(columns,c->new long[0]);
        var data=dto(2,0,DhProfile.OFFICIAL_16,rawBlobs(2,columns,new String[0]),false);var budget=budget();
        var tile=new DhTerrainDecoder(DhLimits.DEFAULT).decode(data,DhProfile.OFFICIAL_16,NEGATIVE,SMALL,budget);
        assertTrue(tile.materials().isEmpty());
        for(int c=0;c<4096;c++)assertEquals(Coverage.UNKNOWN,tile.coverage(c));
        tile.close();tile.close();assertEquals(0,budget.used());
        assertThrows(IllegalStateException.class,()->tile.spanCount(0));
    }
    @Test void deterministicHostileMutationsContainFailureAndReleaseOwnership() throws Exception {
        byte[] original=tile(2,0,DhProfile.OFFICIAL_16);var random=new Random(20260930);
        for(int round=0;round<80;round++){
            byte[] modified=round<40?Arrays.copyOf(original,random.nextInt(original.length)):original.clone();
            if(round>=40)modified[random.nextInt(modified.length)]^=(byte)(1<<random.nextInt(8));
            var budget=budget();
            try(var tile=new DhTerrainDecoder(DhLimits.DEFAULT).decode(modified,DhProfile.OFFICIAL_16,NEGATIVE,SMALL,budget)){
                assertEquals(NEGATIVE,tile.position()); // Some mutations are structurally valid metadata/content.
            }catch(DhCodecException expected){}
            assertEquals(0,budget.used(),"mutation "+round+" leaked ownership");
        }
    }
    private static byte[] hex(String value){return HexFormat.of().parseHex(value);}
    private static DhCodecException assertFailure(byte[] data,DhProfile profile,DhLimits limits,WorldShape world){
        var budget=budget();
        var result=assertThrows(DhCodecException.class,()->new DhTerrainDecoder(limits).decode(data,profile,NEGATIVE,world,budget));
        assertEquals(0,budget.used(),"codec failure leaked memory ownership");return result;
    }
    /** D-12: zstd-jni throws on corrupt input; it must surface as a contained MALFORMED, never a RuntimeException. */
    @Test void corruptZstdBlocksAreContainedAsMalformed() throws Exception {
        byte[] good;
        try(var in=getClass().getResourceAsStream("/dh-corpus/dh-official-3.3.3-neoforge-ocean/database-tile.dto")) {good=in.readAllBytes();}
        var position=new DhPosition(6,4,-3);var shape=new DhTerrainTile.WorldShape(-64,384,true);
        int malformed=0;
        for(int offset=24;offset<Math.min(good.length-32,4000);offset+=7) {
            byte[] bad=good.clone();bad[offset]^=(byte)0x5a;
            var budget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
            try(var tile=new DhTerrainDecoder(DhLimits.DEFAULT).decode(bad,DhProfile.OFFICIAL_16,position,shape,budget)) { /* some flips are benign */ }
            catch(DhCodecException e) {malformed++;}
            assertEquals(0,budget.used(),"offset "+offset);
        }
        assertTrue(malformed>0);
    }
    /** D-13: more spans than voxels in a column is structurally impossible: malformed, not pressure. */
    @Test void moreSpansThanWorldHeightIsMalformedNotAResourceLimit() throws Exception {
        var tiny=new DhTerrainTile.WorldShape(0,2,true);
        var ex=assertThrows(DhCodecException.class,()->new DhTerrainDecoder(DhLimits.DEFAULT).decode(tile(2,3,DhProfile.OFFICIAL_16),
                DhProfile.OFFICIAL_16,new DhPosition(6,0,0),tiny,budget()));
        assertNotEquals(DhCodecException.Kind.RESOURCE_LIMIT,ex.kind());
    }
}
