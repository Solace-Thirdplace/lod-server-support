package dev.vox.lss.networking.client;

import dev.vox.lss.common.dh.*;
import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import org.junit.jupiter.api.*;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DhColumnConverterTest {
    @Test void fluidFloorCopiesBlockLightWithoutReplacingTheAirSkylight() throws Exception {
        var budget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
        for(String fluid:new String[]{"water","lava"})for(boolean sky:new boolean[]{false,true})try(var tile=floorPattern(sky,budget,fluid,32)) {
            var converter=new DhColumnConverter(biomes);var data=converter.convert(tile,0);
            assertEquals(10,data.sections()[2].blockLight().get(0,0,0));
            assertEquals(sky?1:0,data.sections()[2].skyLight().get(0,0,0));
            assertTrue(converter.matchesCache(tile,0,(x,y,z,state,biome,light)-> {
                var section=data.sections()[(y-tile.world().minY())/16];
                return light==(section.skyLight().get(x,y&15,z)|(section.blockLight().get(x,y&15,z)<<4));
            }));
        }
        assertEquals(0,budget.used());
    }
    @Test void aOneVoxelAirGapUsesTheFloorLightForItsSharedNeighborVoxel() throws Exception {
        var budget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
        try(var tile=floorPattern(true,budget,"stone",1)) {
            var data=new DhColumnConverter(biomes).convert(tile,0);
            // Voxy stores one light value per voxel, so both adjacent faces share this approximation.
            assertEquals(12,data.sections()[2].skyLight().get(0,0,0));
            assertEquals(10,data.sections()[2].blockLight().get(0,0,0));
        }
        assertEquals(0,budget.used());
    }
    @Test void firstAirVoxelUsesTheFloorsFaceLightAndLeavesTheRestOfTheAirRunAlone() throws Exception {
        var budget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
        for(boolean sky:new boolean[]{true,false})try(var tile=floorPattern(sky,budget)) {
            var converter=new DhColumnConverter(biomes);var data=converter.convert(tile,0);
            assertTrue(data.sections()[2].section().getBlockState(0,0,0).isAir());
            assertEquals(sky?12:0,data.sections()[2].skyLight().get(0,0,0));
            assertEquals(10,data.sections()[2].blockLight().get(0,0,0));
            assertEquals(sky?1:0,data.sections()[2].skyLight().get(0,1,0));
            assertEquals(0,data.sections()[2].blockLight().get(0,1,0));
            assertTrue(converter.matchesCache(tile,0,(x,y,z,state,biome,light)-> {
                var section=data.sections()[(y-tile.world().minY())/16];
                return state==section.section().getBlockState(x,y&15,z)
                        && light==(section.skyLight().get(x,y&15,z)|(section.blockLight().get(x,y&15,z)<<4));
            }));
        }
        assertEquals(0,budget.used());
    }
    private static DhTerrainTile floorPattern(boolean sky,DhMemoryBudget budget) throws Exception {return floorPattern(sky,budget,"stone",32);}
    private static DhTerrainTile floorPattern(boolean sky,DhMemoryBudget budget,String floor,int gap) throws Exception {
        byte[][] blobs=new byte[8][];var raw=new ByteArrayOutputStream();var points=new DataOutputStream(raw);
        for(int i=0;i<4096;i++) {
            points.writeShort(gap==32?2:3);points.writeLong(32L<<32|12L<<56|10L<<60);
            points.writeLong(1L|(long)gap<<32|32L<<44|1L<<56);
            if(gap<32)points.writeLong((long)(32-gap)<<32|(long)(32+gap)<<44|3L<<56|2L<<60);
        }
        blobs[0]=raw.toByteArray();for(int i=1;i<=4;i++)blobs[i]=new byte[0];
        blobs[5]=new byte[4096];Arrays.fill(blobs[5],(byte)9);blobs[6]=new byte[4096];
        var map=new ByteArrayOutputStream();var strings=new DataOutputStream(map);strings.writeInt(2);
        strings.writeUTF("minecraft:plains_DH-BSW_minecraft:"+floor);strings.writeUTF("minecraft:plains_DH-BSW_minecraft:air");blobs[7]=map.toByteArray();
        var dto=new ByteArrayOutputStream();var out=new DataOutputStream(dto);var position=new DhPosition(6,-1,-1);
        out.writeLong(position.packed());out.writeInt(0);for(var blob:blobs){out.writeInt(blob.length);out.write(blob);}
        out.writeByte(1);out.writeByte(0);out.writeBoolean(false);out.writeBoolean(false);out.writeLong(100);out.writeLong(200);
        return new DhTerrainDecoder(DhLimits.DEFAULT).decode(dto.toByteArray(),DhProfile.SUPPORT_15,position,new DhTerrainTile.WorldShape(-32,64,sky),budget);
    }
    @Test void cacheComparisonMatchesWaterDepthLightsGapsAndQuartCenterBiomesWithoutSections() throws Exception {
        var budget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
        try(var water=waterPattern(true,budget);var noSky=waterPattern(false,budget);var varied=pattern(false,budget)) {
            for(var tile:List.of(water,noSky,varied))for(int c:new int[]{0,3,12,15}) {
                var converter=new DhColumnConverter(biomes);var data=converter.convert(tile,c);
                assertTrue(converter.matchesCache(tile,c,(x,y,z,state,biome,light)-> {
                    var section=data.sections()[(y-tile.world().minY())/16];
                    var actual=section.section().getBlockState(x,y&15,z);
                    return state==actual && light==(section.skyLight().get(x,y&15,z)|(section.blockLight().get(x,y&15,z)<<4))
                            && (state.isAir() || biome.equals(section.section().getNoiseBiome(x/4,(y&15)/4,z/4).unwrapKey().orElseThrow().location().toString()));
                }));
            }
        }
        assertEquals(0,budget.used());
    }
    @Test void cacheComparisonChecksEveryVoxelAndRejectsOneMissingOrWrongSample() throws Exception {
        var budget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
        try(var tile=pattern(false,budget)) {
            var converter=new DhColumnConverter(biomes);var samples=new java.util.concurrent.atomic.AtomicInteger();
            assertTrue(converter.matchesCache(tile,15,(x,y,z,s,b,l)->{samples.incrementAndGet();return true;}));
            assertEquals(16*16*tile.world().height(),samples.get());
            assertFalse(converter.matchesCache(tile,15,(x,y,z,s,b,l)->!(x==15&&z==15&&y==tile.world().minY()+31)));
        }
        assertEquals(0,budget.used());
    }
    @Test void officialOceanFloorUsesDepthAttenuatedNeighborLightInsteadOfWaterSurfaceLight() throws Exception {
        var budget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
        try(var bytes=getClass().getResourceAsStream("/dh-corpus/dh-official-3.3.3-neoforge-ocean/database-tile.dto")) {
            assertNotNull(bytes);
            try(var tile=new DhTerrainDecoder(DhLimits.DEFAULT).decode(bytes.readAllBytes(),DhProfile.OFFICIAL_16,
                    new DhPosition(6,4,-3),new DhTerrainTile.WorldShape(-64,384,true),budget)) {
                var converter=new DhColumnConverter(biomes);int floors=0,darkFloors=0;
                for(int column=0;column<16;column++) {
                    var data=converter.convert(tile,column);if(data==null)continue;
                    for(int x=0;x<16;x++)for(int z=0;z<16;z++) {
                        int index=(column/4*16+x)*64+column%4*16+z;
                        for(int i=0;i<tile.spanCount(index);i++) {
                            var water=tile.span(index,i);
                            int bottom=water.bottom();
                            var bottomSection=data.sections()[bottom/16];
                            if(bottomSection.section().getBlockState(x,bottom&15,z).getFluidState().isEmpty())continue;
                            for(int j=0;j<tile.spanCount(index);j++) {
                                var floor=tile.span(index,j);var material=tile.materials().get(floor.material());
                                if(floor.bottom()+floor.height()!=water.bottom() || material.air()
                                        || material.block().equals("minecraft:water"))continue;
                                int y=water.bottom();var section=data.sections()[y/16];
                                assertFalse(section.section().getBlockState(x,y&15,z).getFluidState().isEmpty());
                                // DH samples the floor's light at y+1; Voxy uses that same neighboring water voxel.
                                assertEquals(floor.skyLight(),section.skyLight().get(x,y&15,z),"Ocean floor's neighboring water light");
                                assertEquals(water.blockLight(),section.blockLight().get(x,y&15,z));
                                floors++;if(floor.skyLight()==0 && water.skyLight()>0)darkFloors++;
                            }
                        }
                    }
                }
                assertTrue(floors>=7000,"Water, kelp and seagrass must all follow the upstream floor oracle");
                assertTrue(darkFloors>=100,"Deep floors must be dark despite skylit surface water");
            }
        }
        assertEquals(0,budget.used());
    }
    @Test void waterSkylightFallsWithDepthAcrossSectionBoundariesAndPreservesBlockLight() throws Exception {
        assertEquals(1,net.minecraft.world.level.block.Blocks.WATER.defaultBlockState().getLightBlock(
                net.minecraft.world.level.EmptyBlockGetter.INSTANCE,net.minecraft.core.BlockPos.ZERO));
        var budget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
        try(var tile=waterPattern(true,budget)) {
            var converter=new DhColumnConverter(biomes);
            for(int c:new int[]{0,3,12,15}) {
                var data=converter.convert(tile,c);assertNotNull(data);assertEquals(-2,data.sections()[0].sectionY());
                int depth=c/4<2?5:20,bottom=64-depth;
                for(int y=bottom;y<64;y++) {
                    var section=data.sections()[y/16];
                    assertEquals(Math.max(0,15-(64-y)),section.skyLight().get(2,y&15,2));
                    assertEquals(6,section.blockLight().get(2,y&15,2));
                    assertEquals(c/4<2?0:3,section.section().getBlockState(2,y&15,2).getValue(
                            net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL));
                }
                assertEquals(15,data.sections()[4].skyLight().get(2,0,2),"Air above the water stays skylit");
                assertEquals(Math.max(0,15-depth),data.sections()[(bottom-1)/16].skyLight().get(2,(bottom-1)&15,2),
                        "The source floor light remains unchanged");
            }
        }
        assertEquals(0,budget.used());
    }
    @Test void waterInANoSkyWorldRemainsDarkAndKeepsItsBlockLight() throws Exception {
        var budget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
        try(var tile=waterPattern(false,budget)) {
            var data=new DhColumnConverter(biomes).convert(tile,15);assertNotNull(data);
            for(var section:data.sections())for(int y=0;y<16;y++)assertEquals(0,section.skyLight().get(2,y,2));
            assertEquals(6,data.sections()[3].blockLight().get(2,15,2));
        }
        assertEquals(0,budget.used());
    }
    private static DhTerrainTile waterPattern(boolean sky,DhMemoryBudget budget) throws Exception {
        byte[][] blobs=new byte[8][];var raw=new ByteArrayOutputStream();var points=new DataOutputStream(raw);
        for(int x=0;x<64;x++)for(int z=0;z<64;z++) {
            int depth=x<32?5:20,bottom=64-depth;
            points.writeShort(3);
            points.writeLong((long)bottom<<32 | (long)Math.max(0,15-depth)<<56);
            points.writeLong((x<32?1L:2L) | (long)depth<<32 | (long)bottom<<44 | 15L<<56 | 6L<<60);
            points.writeLong(3L | 32L<<32 | 64L<<44 | 15L<<56);
        }
        blobs[0]=raw.toByteArray();for(int i=1;i<=4;i++)blobs[i]=new byte[0];
        blobs[5]=new byte[4096];Arrays.fill(blobs[5],(byte)9);blobs[6]=new byte[4096];
        var map=new ByteArrayOutputStream();var strings=new DataOutputStream(map);strings.writeInt(4);
        for(var material:List.of(new DhMaterial("minecraft:plains","minecraft:sand",Map.of()),
                new DhMaterial("minecraft:plains","minecraft:water",Map.of()),
                new DhMaterial("minecraft:plains","minecraft:water",Map.of("level","3")),
                new DhMaterial("minecraft:plains","minecraft:air",Map.of())))strings.writeUTF(material.serialized());
        blobs[7]=map.toByteArray();var dto=new ByteArrayOutputStream();var out=new DataOutputStream(dto);
        var position=new DhPosition(6,-1,-1);out.writeLong(position.packed());out.writeInt(0);
        for(var blob:blobs){out.writeInt(blob.length);out.write(blob);}
        out.writeByte(1);out.writeByte(0);out.writeBoolean(false);out.writeBoolean(false);out.writeLong(100);out.writeLong(200);
        return new DhTerrainDecoder(DhLimits.DEFAULT).decode(dto.toByteArray(),DhProfile.SUPPORT_15,position,
                new DhTerrainTile.WorldShape(-32,96,sky),budget);
    }
    @Test void officialCapturedAirAndStatelessMappingsConvertToActualBlocksWithoutFillingUnknownColumns() throws Exception {
        var budget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());var position=new DhPosition(6,-1,1);
        var profile=DhProfile.OFFICIAL_16;var codec=new DhProtocolCodec(profile,DhLimits.DEFAULT);
        try(var assembly=new DhAssembly(DhLimits.DEFAULT,budget)) {
            assembly.expect(2,position);
            for(int frame=4;frame<=5;frame++)try(var bytes=getClass().getResourceAsStream(
                    String.format("/dh-corpus/dh-official-3.3.3-neoforge-partial/received-%04d.bin",frame))) {
                assertNotNull(bytes);assembly.accept((DhMessage.Split)codec.decode(bytes.readAllBytes()));
            }
            try(var bytes=getClass().getResourceAsStream("/dh-corpus/dh-official-3.3.3-neoforge-partial/received-0006.bin")) {
                assertNotNull(bytes);
                try(var response=assembly.response((DhMessage.Response)codec.decode(bytes.readAllBytes()));
                    var tile=response.decode(new DhTerrainDecoder(DhLimits.DEFAULT),profile,new DhTerrainTile.WorldShape(-64,384,true))) {
                    var converter=new DhColumnConverter(biomes);long air=0,stone=0;
                    for(int column=0;column<16;column++) {
                        var data=converter.convert(tile,column);
                        if(column<8){assertNull(data);continue;}
                        assertNotNull(data);
                        for(int x=0;x<16;x++)for(int z=0;z<16;z++) {
                            int index=(column/4*16+x)*64+column%4*16+z;
                            for(int i=0;i<tile.spanCount(index);i++) {
                                var span=tile.span(index,i);var material=tile.materials().get(span.material());
                                for(int y=span.bottom();y<span.bottom()+span.height();y++) {
                                    var state=data.sections()[y/16].section().getBlockState(x,y&15,z);
                                    if(material.air()){assertTrue(state.isAir());air++;}
                                    if(material.block().equals("minecraft:stone")) {
                                        assertEquals(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),state);stone++;
                                    }
                                }
                            }
                        }
                    }
                    assertTrue(air>1000);assertTrue(stone>1000);
                }
            }
        }
        assertEquals(0,budget.used());
    }
    @Test void nonuniformBiomesUseEachQuartCellCenterAcrossNegativeTileCorners() throws Exception {
        var budget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
        try(var tile=pattern(false,budget)) {
            var converter=new DhColumnConverter(biomes);
            for(int c:new int[]{0,3,12,15}) {
                var data=converter.convert(tile,c);assertNotNull(data);
                for(int sy=0;sy<2;sy++) for(int x=0;x<4;x++) for(int z=0;z<4;z++) for(int y=0;y<4;y++) {
                    String expected=((c/4*4+x+c%4*4+z+sy)&1)==0?"minecraft:plains":"minecraft:desert";
                    assertEquals(expected,data.sections()[sy].section().getNoiseBiome(x,y,z).unwrapKey().orElseThrow().location().toString());
                    assertEquals(5+sy*6,data.sections()[sy].blockLight().get(x*4+2,y*4+2,z*4+2));
                }
            }
        }
        assertEquals(0,budget.used());
    }
    @Test void anAirRefreshReplacesEverySectionAndLightWithoutMutatingEarlierHandoff() throws Exception {
        var budget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
        try(var first=pattern(false,budget);var next=pattern(true,budget)) {
            var converter=new DhColumnConverter(biomes);var before=converter.convert(first,15);var after=converter.convert(next,15);
            for(int sy=0;sy<2;sy++) {
                assertNotSame(before.sections()[sy].section(),after.sections()[sy].section());
                assertFalse(before.sections()[sy].section().hasOnlyAir());assertTrue(after.sections()[sy].section().hasOnlyAir());
                assertEquals(5+sy*6,before.sections()[sy].blockLight().get(2,2,2));
                assertEquals(0,after.sections()[sy].blockLight().get(2,2,2));
                assertEquals(0,after.sections()[sy].skyLight().get(2,2,2));
            }
        }
        assertEquals(0,budget.used());
    }
    private static DhTerrainTile pattern(boolean air,DhMemoryBudget budget) throws Exception {
        byte[][] blobs=new byte[8][];
        var raw=new ByteArrayOutputStream();var points=new DataOutputStream(raw);
        for(int x=0;x<64;x++) for(int z=0;z<64;z++) {
            points.writeShort(2);
            for(int sy=0;sy<2;sy++) points.writeLong(((x/4+z/4+sy)&1)|((long)16<<32)|((long)(sy*16)<<44)
                    |(air?0:((long)(5+sy*6)<<60)));
        }
        blobs[0]=raw.toByteArray();for(int i=1;i<=4;i++)blobs[i]=new byte[0];
        blobs[5]=new byte[4096];Arrays.fill(blobs[5],(byte)9);blobs[6]=new byte[4096];
        var map=new ByteArrayOutputStream();var strings=new DataOutputStream(map);strings.writeInt(2);
        for(String biome:List.of("plains","desert")) strings.writeUTF("minecraft:"+biome+"_DH-BSW_minecraft:"+(air?"air":"stone"));
        blobs[7]=map.toByteArray();var dto=new ByteArrayOutputStream();var out=new DataOutputStream(dto);
        var position=new DhPosition(6,-1,-1);out.writeLong(position.packed());out.writeInt(0);
        for(var blob:blobs){out.writeInt(blob.length);out.write(blob);}
        out.writeByte(1);out.writeByte(0);out.writeBoolean(false);out.writeBoolean(false);out.writeLong(100);out.writeLong(200);
        return new DhTerrainDecoder(DhLimits.DEFAULT).decode(dto.toByteArray(),DhProfile.SUPPORT_15,position,
                new DhTerrainTile.WorldShape(-32,32,false),budget);
    }
    private static MappedRegistry<Biome> biomes;
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var source=VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME);
        biomes=new MappedRegistry<>(Registries.BIOME,Lifecycle.stable());
        source.listElements().forEach(h->biomes.register(h.key(),h.value(),RegistrationInfo.BUILT_IN));
        biomes.freeze();
    }
    private static DhTerrainTile captured(String fixture,DhPosition position,DhTerrainTile.WorldShape shape,DhMemoryBudget budget) throws Exception {
        var codec=new DhProtocolCodec(DhProfile.SUPPORT_15,DhLimits.DEFAULT);
        try(var assembly=new DhAssembly(DhLimits.DEFAULT,budget)) {
            int request=1;
            assembly.expect(request,position);
            for(int i=1;i<=6;i++) {
                var stream=DhColumnConverterTest.class.getResourceAsStream("/dh-corpus/"+fixture+String.format("/received-%04d.bin",i));
                if(stream==null) continue;
                var message=codec.decode(stream.readAllBytes()); stream.close();
                if(message instanceof DhMessage.Split split) assembly.accept(split);
                if(message instanceof DhMessage.Response response) try(var result=assembly.response(response)) {
                    return result.decode(new DhTerrainDecoder(DhLimits.DEFAULT),DhProfile.SUPPORT_15,shape);
                }
            }
        }
        throw new AssertionError("Missing capture response");
    }
    @Test void capturedLandExpandsSolidBlocksPropertiesAndBoundaryLight() throws Exception {
        var budget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
        try(var tile=captured("dh-support-0.14.0-meadow",new DhPosition(6,-4,-1),new DhTerrainTile.WorldShape(-64,384,true),budget)) {
            var converter=new DhColumnConverter(biomes); long solids=0;
            for(int column=0;column<16;column++) {
                var data=converter.convert(tile,column); assertNotNull(data); assertEquals(24,data.sections().length);
                assertEquals(-4,data.sections()[0].sectionY());
                for(int x=0;x<16;x++) for(int z=0;z<16;z++) {
                    int index=(column/4*16+x)*64+column%4*16+z;
                    for(int i=0;i<tile.spanCount(index);i++) {
                        var span=tile.span(index,i); var material=tile.materials().get(span.material());
                        var expected=BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(material.block()));
                        for(int y=span.bottom();y<span.bottom()+span.height();y++) {
                            var section=data.sections()[y/16]; var state=section.section().getBlockState(x,y&15,z);
                            assertEquals(expected,state.getBlock());
                            int expectedSky=span.skyLight(),expectedBlock=span.blockLight();
                            if(material.air() && y==span.bottom())for(int lowerIndex=0;lowerIndex<tile.spanCount(index);lowerIndex++) {
                                var lower=tile.span(index,lowerIndex);var lowerMaterial=tile.materials().get(lower.material());
                                var lowerBlock=BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse(lowerMaterial.block()));
                                if(lower.bottom()+lower.height()==y && !lowerMaterial.air() && lowerBlock.defaultBlockState().getFluidState().isEmpty()) {
                                    expectedSky=lower.skyLight();expectedBlock=lower.blockLight();break;
                                }
                            }
                            assertEquals(expectedSky,section.skyLight().get(x,y&15,z));
                            assertEquals(expectedBlock,section.blockLight().get(x,y&15,z));
                            if(!state.isAir() && !state.liquid()) solids++;
                            if(y==span.bottom()) for(var prop:material.properties().entrySet()) {
                                var property=state.getBlock().getStateDefinition().getProperty(prop.getKey());
                                if(property!=null) assertEquals(prop.getValue(),state.getValue(property).toString());
                            }
                        }
                    }
                }
            }
            assertTrue(solids>100_000,"Captured land must include its opaque body");
        }
        assertEquals(0,budget.used());
    }
    @Test void conversionUsesActualMinHeightAndExplicitZeroSky() throws Exception {
        var budget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
        try(var tile=captured("dh-support-0.14.0-ocean",new DhPosition(6,4,-1),new DhTerrainTile.WorldShape(32,384,false),budget)) {
            var data=new DhColumnConverter(biomes).convert(tile,0);
            assertNotNull(data); assertEquals(2,data.sections()[0].sectionY());
            for(var section:data.sections()) {
                assertNotNull(section.skyLight());
                for(int y=0;y<16;y++) assertEquals(0,section.skyLight().get(2,y,2));
            }
        }
        assertEquals(0,budget.used());
    }
    @Test void dhPropertyValuesUseLocalDefaultsWithoutNativeWireGrammar() {
        var resolver=new ClientIdentityResolver(biomes,biomes.asHolderIdMap());
        var unusual=new DhMaterial("minecraft:plains","minecraft:water",Map.of("level","雪😀"));
        assertEquals(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.getId(
                net.minecraft.world.level.block.Blocks.WATER.defaultBlockState()),resolver.blockIdForDh(unusual));
        var valid=new DhMaterial("minecraft:plains","minecraft:water",Map.of("level","3","unknown","雪😀"));
        var state=net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.byId(resolver.blockIdForDh(valid));
        assertEquals(3,state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL));
    }
    @Test void anUnknownVerticalColumnCannotClearKnownTerrain() throws Exception {
        var blobs=new byte[8][];
        var raw=new ByteArrayOutputStream(); var points=new DataOutputStream(raw);
        for(int c=0;c<4096;c++) {
            points.writeShort(c==0?0:1);
            if(c!=0) points.writeLong((long)32<<32);
        }
        blobs[0]=raw.toByteArray(); for(int i=1;i<=4;i++) blobs[i]=new byte[0];
        blobs[5]=new byte[4096]; Arrays.fill(blobs[5],(byte)9); blobs[6]=new byte[4096];
        var map=new ByteArrayOutputStream(); var strings=new DataOutputStream(map);
        strings.writeInt(1); strings.writeUTF("minecraft:plains_DH-BSW_minecraft:stone"); blobs[7]=map.toByteArray();
        var dto=new ByteArrayOutputStream(); var out=new DataOutputStream(dto);
        var position=new DhPosition(6,0,0); out.writeLong(position.packed()); out.writeInt(0);
        for(var blob:blobs) {out.writeInt(blob.length);out.write(blob);}
        out.writeByte(1);out.writeByte(0);out.writeBoolean(false);out.writeBoolean(false);out.writeLong(0);out.writeLong(0);
        var budget=new DhMemoryBudget(DhLimits.DEFAULT.ownedBytes());
        try(var tile=new DhTerrainDecoder(DhLimits.DEFAULT).decode(dto.toByteArray(),DhProfile.SUPPORT_15,position,
                new DhTerrainTile.WorldShape(-32,32,false),budget)) {
            var converter=new DhColumnConverter(biomes);
            assertNull(converter.convert(tile,0));
            var data=converter.convert(tile,1); assertNotNull(data); assertEquals(-2,data.sections()[0].sectionY());
            assertFalse(data.sections()[0].section().hasOnlyAir());
        }
        assertEquals(0,budget.used());
    }
}
