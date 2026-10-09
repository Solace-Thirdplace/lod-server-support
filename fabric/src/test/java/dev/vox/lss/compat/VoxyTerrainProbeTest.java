package dev.vox.lss.compat;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class VoxyTerrainCacheTest {
    VoxyCompat.ClassResolver before;
    @BeforeAll static void bootstrap() {SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    @BeforeEach void setup() {
        before=VoxyCompat.classResolver;Identifier.engine=new Engine();
        VoxyCompat.classResolver=name->switch(name.substring(name.lastIndexOf('.')+1)) {
            case "WorldIdentifier"->Identifier.class;case "WorldEngine"->Engine.class;
            case "WorldSection"->Section.class;case "Mapper"->Mapper.class;default->throw new ClassNotFoundException(name);
        };
    }
    @AfterEach void restore() {VoxyCompat.classResolver=before;}
    @Test void readsNegativeCoordinatesBlocksBiomesAndLightWithoutWritingAndReleasesOwnership() throws Exception {
        var engine=Identifier.engine;int x=15,y=-1,z=14;
        engine.section.data[((y&31)<<10)|(30<<5)|31]=15L<<56|1L<<27;
        try(var cache=DhVoxyBridge.openCache(null)) {
            assertNotNull(cache);assertEquals(0,engine.refs);
            var column=cache.readColumn(-1,-1,-32,32);
            assertEquals(-1,engine.x);assertEquals(-1,engine.y);assertEquals(-1,engine.z);
            assertEquals(1,engine.section.released);
            assertTrue(column.matches(x,y,z,Blocks.STONE.defaultBlockState(),"minecraft:plains",15));
            assertFalse(column.matches(x,y,z,Blocks.SAND.defaultBlockState(),"minecraft:plains",15));
            assertFalse(column.matches(x,y,z,Blocks.STONE.defaultBlockState(),"minecraft:desert",15));
            assertFalse(column.matches(x,y,z,Blocks.STONE.defaultBlockState(),"minecraft:plains",0));
        }
        assertEquals(0,engine.refs);
    }
    @Test void missingSectionsUseVoxysActualDefaultSkylightAndNeverClaimDarkAirOrTerrain() throws Exception {
        Identifier.engine.section=null;
        try(var cache=DhVoxyBridge.openCache(null)) {
            var column=cache.readColumn(0,0,0,32);
            assertTrue(column.isDefault());
            assertTrue(column.matches(0,0,0,Blocks.AIR.defaultBlockState(),"minecraft:desert",15));
            assertFalse(column.matches(0,0,0,Blocks.AIR.defaultBlockState(),"minecraft:plains",0));
            assertFalse(column.matches(0,0,0,Blocks.STONE.defaultBlockState(),"minecraft:plains",0));
        }
        assertEquals(0,Identifier.engine.refs);
    }
    @Test void malformedSectionsReleaseTheirLeaseAndAllowSourceFallback() throws Exception {
        Identifier.engine.section.data=new long[1];
        try(var cache=DhVoxyBridge.openCache(null)) {assertThrows(IllegalStateException.class,()->cache.readColumn(0,0,0,32));}
        assertEquals(0,Identifier.engine.refs);assertEquals(1,Identifier.engine.section.released);
    }
    @Test void optionalAbiFailureDoesNotLeakEngineReference() {
        var resolver=VoxyCompat.classResolver;
        VoxyCompat.classResolver=name->name.endsWith(".Mapper")?String.class:resolver.resolve(name);
        assertNull(DhVoxyBridge.openCache(null));assertEquals(0,Identifier.engine.refs);
    }
    @Test void existingDifferentContentMustBePreservedRatherThanTreatedAsMissing() throws Exception {
        Identifier.engine.section.data[0]=1L<<27;
        try(var cache=DhVoxyBridge.openCache(null)) {
            assertFalse(cache.readColumn(0,0,0,32).isDefault());
        }
        assertEquals(0,Identifier.engine.refs);
    }
    @Test void destinationReplacementCannotReuseTheRetiredEngineSnapshot() throws Exception {
        try(var cache=DhVoxyBridge.openCache(null)) {
            var old=Identifier.engine;assertTrue(cache.isCurrent(null));cache.readColumn(0,0,0,32);
            Identifier.engine=new Engine();assertFalse(cache.isCurrent(null));assertEquals(0,old.refs);
        }
    }
    public static final class Identifier {
        static Engine engine;
        public static Identifier of(Level level) {return new Identifier();}
        public Engine getOrCreateEngine() {return engine;}
    }
    public static final class Engine {
        int refs,x,y,z;Section section=new Section();
        public void acquireRef(){refs++;}public void releaseRef(){refs--;}
        public Mapper getMapper(){return new Mapper();}
        public Section acquire(int level,int x,int y,int z){
            this.x=x;this.y=y;this.z=z;
            if(section==null){section=new Section();java.util.Arrays.fill(section.data,15L<<56);}
            return section;
        }
    }
    public static final class Section {
        long[] data=new long[32768];int released;
        public long[] copyData(){return data.clone();}public int release(){return ++released;}
    }
    public static final class Mapper {
        public State[] getStateEntries(){return new State[]{new State(Blocks.AIR.defaultBlockState()),new State(Blocks.STONE.defaultBlockState())};}
        public Biome[] getBiomeEntries(){return new Biome[]{new Biome()};}
        public static int getBlockId(long value){return (int)(value>>>27&0xfffff);}
        public static int getBiomeId(long value){return (int)(value>>>47&511);}
        public static int getLightId(long value){return (int)(value>>>56&255);}
    }
    public static final class State {public final BlockState state;State(BlockState state){this.state=state;}}
    public static final class Biome {public final String biome="minecraft:plains";}
}
