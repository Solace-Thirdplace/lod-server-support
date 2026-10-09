package dev.vox.lss.compat;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** The rejoin presence probe: read-only, early-exit, reference-balanced, fail-closed on ABI drift. */
class VoxyTerrainProbeTest {
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
    @Test void terrainInTheProbedChunkIsDetectedAtNegativeCoordinatesAndOwnershipIsReleased() throws Exception {
        var engine=Identifier.engine;
        java.util.Arrays.fill(engine.section.data,15L<<56);
        int x=15,y=-1,z=14;
        engine.section.data[((y&31)<<10)|(30<<5)|31]=15L<<56|1L<<27; // stone in chunk (-1,-1)
        var probe=DhVoxyBridge.openProbe(null);
        assertNotNull(probe);assertEquals(0,engine.refs);
        assertTrue(probe.hasTerrain(-1,-1,-32,32));
        assertEquals(-1,engine.x);assertEquals(-1,engine.y);assertEquals(-1,engine.z);
        assertEquals(1,engine.section.released);assertEquals(0,engine.refs);
        assertFalse(probe.hasTerrain(-2,-1,-32,32),"The neighbouring chunk in the same Voxy section is still default");
    }
    @Test void missingSectionsAreVoxysSkylitAirDefaultAndNeverClaimTerrain() throws Exception {
        Identifier.engine.section=null;
        assertFalse(DhVoxyBridge.openProbe(null).hasTerrain(0,0,0,64));
        assertEquals(0,Identifier.engine.refs);
    }
    @Test void darkAirIsNotTheDefaultSoWrittenAirCounts() throws Exception {
        // Zero-lit air differs from Voxy's skylit default: something was written here.
        assertTrue(DhVoxyBridge.openProbe(null).hasTerrain(0,0,0,32));
    }
    @Test void malformedSectionsReleaseTheirReferenceAndThrowForTheCallerToTrustTheReceipt() {
        Identifier.engine.section.data=new long[1];
        assertThrows(IllegalStateException.class,()->DhVoxyBridge.openProbe(null).hasTerrain(0,0,0,32));
        assertEquals(0,Identifier.engine.refs);assertEquals(1,Identifier.engine.section.released);
    }
    @Test void optionalAbiFailureYieldsNoProbe() {
        var resolver=VoxyCompat.classResolver;
        VoxyCompat.classResolver=name->name.endsWith(".Mapper")?String.class:resolver.resolve(name);
        assertNull(DhVoxyBridge.openProbe(null));assertEquals(0,Identifier.engine.refs);
    }
    @Test void destinationReplacementIsDetected() throws Exception {
        var probe=DhVoxyBridge.openProbe(null);
        assertTrue(probe.isCurrent(null));
        Identifier.engine=new Engine();assertFalse(probe.isCurrent(null));
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
