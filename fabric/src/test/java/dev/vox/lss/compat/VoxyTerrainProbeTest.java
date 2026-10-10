package dev.vox.lss.compat;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** The rejoin presence probe: early-exit, reference-balanced, all four tile quarters, fail-closed on ABI drift. */
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
    @Test void absentSectionsAreNotMaterializedWhenVoxyOffersAcquireIfExists() throws Exception {
        Identifier.engine.section=null;
        assertFalse(DhVoxyBridge.openProbe(null).hasTerrain(0,0,0,64));
        assertNull(Identifier.engine.section,"acquireIfExists must not create storage");
        assertEquals(0,Identifier.engine.created);
    }
    @Test void everyQuarterOfTheTileMustHoldTerrain() throws Exception {
        var engine=Identifier.engine;engine.byXZ=new java.util.HashMap<>();
        for(int sx=0;sx<2;sx++)for(int sz=0;sz<2;sz++){
            var section=new Section();java.util.Arrays.fill(section.data,15L<<56|1L<<27);engine.byXZ.put(sx*31L+sz,section);
        }
        assertTrue(DhVoxyBridge.openProbe(null).hasTileTerrain(0,0,0,32));
        engine.byXZ.remove(1*31L+1); // the (+x,+z) quarter was lost: one sample would have vouched for it
        assertFalse(DhVoxyBridge.openProbe(null).hasTileTerrain(0,0,0,32));
        assertEquals(0,engine.refs);
    }
    @Test void olderVoxyWithoutAcquireIfExistsFallsBackToAcquire() throws Exception {
        var resolver=VoxyCompat.classResolver;Identifier.legacy=new LegacyEngine();
        VoxyCompat.classResolver=name->name.endsWith(".WorldEngine")?LegacyEngine.class:resolver.resolve(name);
        try {assertFalse(DhVoxyBridge.openProbe(null).hasTerrain(0,0,0,32));assertEquals(1,Identifier.legacy.created);}
        finally {Identifier.legacy=null;}
    }
    public static final class Identifier {
        static Engine engine;static LegacyEngine legacy;
        public static Identifier of(Level level) {return new Identifier();}
        public Object getOrCreateEngine() {return legacy!=null?legacy:engine;}
    }
    public static final class Engine {
        int refs,x,y,z,created;Section section=new Section();java.util.Map<Long,Section> byXZ;
        public void acquireRef(){refs++;}public void releaseRef(){refs--;}
        public Mapper getMapper(){return new Mapper();}
        public Section acquireIfExists(int level,int x,int y,int z){
            this.x=x;this.y=y;this.z=z;
            return byXZ!=null?byXZ.get(x*31L+z):section;
        }
        public Section acquire(int level,int x,int y,int z){
            this.x=x;this.y=y;this.z=z;
            if(section==null){created++;section=new Section();java.util.Arrays.fill(section.data,15L<<56);}
            return section;
        }
    }
    public static final class LegacyEngine {
        int refs,created;
        public void acquireRef(){refs++;}public void releaseRef(){refs--;}
        public Section acquire(int level,int x,int y,int z){created++;var s=new Section();java.util.Arrays.fill(s.data,15L<<56);return s;}
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
