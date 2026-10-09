package dev.vox.lss.compat;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import java.lang.reflect.*;
import java.lang.invoke.*;

/** Optional, read-only destination verification. No persisted enqueue receipts or Voxy writes. */
public final class VoxyTerrainCache implements AutoCloseable {
    private final Object engine;
    private final Method acquire, copy, release, acquireRef, releaseRef;
    private final MethodHandle blockId,biomeId,lightId;
    private final BlockState[] states;
    private final String[] biomes;

    /** Snapshot the mapper on the client thread; hold engine references only during worker reads. */
    static VoxyTerrainCache open(ClientLevel level) {
        Object engine=null; Method releaseRef=null;boolean held=false;
        try {
            var resolver=VoxyCompat.classResolver;
            Class<?> identifier=resolver.resolve("me.cortex.voxy.commonImpl.WorldIdentifier");
            Object id=identifier.getMethod("of",Level.class).invoke(null,level);
            if(id==null)return null;
            engine=identifier.getMethod("getOrCreateEngine").invoke(id);
            if(engine==null)return null;
            Class<?> ec=resolver.resolve("me.cortex.voxy.common.world.WorldEngine");
            releaseRef=ec.getMethod("releaseRef");
            ec.getMethod("acquireRef").invoke(engine);
            held=true;
            return new VoxyTerrainCache(engine,ec,resolver);
        } catch(ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null; // Optional ABI failure must never disable the ordinary ingest bridge.
        } finally {
            if(held)try {releaseRef.invoke(engine);}catch(ReflectiveOperationException ignored) {}
        }
    }
    private VoxyTerrainCache(Object engine,Class<?> ec,VoxyCompat.ClassResolver resolver) throws ReflectiveOperationException {
        this.engine=engine;
        Class<?> section=resolver.resolve("me.cortex.voxy.common.world.WorldSection"),mapper=resolver.resolve("me.cortex.voxy.common.world.other.Mapper");
        // acquireIfExists returns null on the first miss but leaves an initialized
        // section in Voxy's secondary cache. Read acquire's actual defaults instead:
        // the pinned port initializes missing sections to skylit air, not zero-lit air.
        acquire=ec.getMethod("acquire",int.class,int.class,int.class,int.class);
        copy=section.getMethod("copyData");release=section.getMethod("release");
        acquireRef=ec.getMethod("acquireRef");releaseRef=ec.getMethod("releaseRef");
        var lookup=MethodHandles.publicLookup();
        blockId=lookup.unreflect(mapper.getMethod("getBlockId",long.class));
        biomeId=lookup.unreflect(mapper.getMethod("getBiomeId",long.class));lightId=lookup.unreflect(mapper.getMethod("getLightId",long.class));
        Object mappings=ec.getMethod("getMapper").invoke(engine);
        Object[] blocks=(Object[])mapper.getMethod("getStateEntries").invoke(mappings);
        Object[] entries=(Object[])mapper.getMethod("getBiomeEntries").invoke(mappings);
        if(blocks.length>65_536 || entries.length>65_536)throw new IllegalStateException("Voxy mapping snapshot too large");
        states=new BlockState[blocks.length];biomes=new String[entries.length];
        for(int i=0;i<blocks.length;i++)states[i]=(BlockState)blocks[i].getClass().getField("state").get(blocks[i]);
        for(int i=0;i<entries.length;i++)biomes[i]=(String)entries[i].getClass().getField("biome").get(entries[i]);
    }
    /** Recheck on the client thread after reads; an external Voxy reset may replace the engine. */
    public boolean isCurrent(ClientLevel level) {
        try {
            Class<?> identifier=VoxyCompat.classResolver.resolve("me.cortex.voxy.commonImpl.WorldIdentifier");
            Object id=identifier.getMethod("of",Level.class).invoke(null,level);
            if(id==null)return false;
            return identifier.getMethod("getOrCreateEngine").invoke(id)==engine;
        } catch(ReflectiveOperationException | RuntimeException | LinkageError e) {return false;}
    }
    /** At most one column's vertical section copies are retained; caller reserves their bytes first. */
    public Column readColumn(int chunkX,int chunkZ,int minY,int height) throws ReflectiveOperationException {
        int bottom=minY>>5,top=(minY+height-1)>>5;
        long[][] data=new long[top-bottom+1][];
        acquireRef.invoke(engine);
        try { for(int y=bottom;y<=top;y++) {
            if(Thread.currentThread().isInterrupted())throw new IllegalStateException("Voxy cache read cancelled");
            Object section=acquire.invoke(engine,0,chunkX>>1,y,chunkZ>>1);
            if(section==null)throw new IllegalStateException("Voxy did not initialize a section");
            try {
                var values=(long[])copy.invoke(section);
                if(values.length!=32*32*32)throw new IllegalStateException("Unsupported Voxy section shape");
                data[y-bottom]=values;
            } finally {release.invoke(section);}
        }
        } finally { releaseEngine(); }
        return new Column(data,bottom,(chunkX&1)*16,(chunkZ&1)*16,minY,height);
    }
    public final class Column {
        private final long[][] data;
        private final int bottom,offsetX,offsetZ,minY,height;
        private Column(long[][] data,int bottom,int offsetX,int offsetZ,int minY,int height) {
            this.data=data;this.bottom=bottom;this.offsetX=offsetX;this.offsetZ=offsetZ;this.minY=minY;this.height=height;
        }
        /** Only fully initialized default content is safe to replace on unchanged restore.
         * A difference may be higher-quality vanilla terrain from an earlier connection. */
        public boolean isDefault() throws ReflectiveOperationException {
            for(int y=minY;y<minY+height;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                long value=data[(y>>5)-bottom][((y&31)<<10)|((offsetZ+z)<<5)|(offsetX+x)];
                if(decode(blockId,value)!=0 || decode(lightId,value)!=15)return false;
            }
            return true;
        }
        public boolean matches(int x,int y,int z,BlockState state,String biome,int light) throws ReflectiveOperationException {
            var section=data[(y>>5)-bottom];
            long value=section[((y&31)<<10)|((offsetZ+z)<<5)|(offsetX+x)];
            int block=decode(blockId,value),actualLight=decode(lightId,value);
            if(actualLight!=light)return false;
            if(state.isAir())return block==0;
            int actualBiome=decode(biomeId,value);
            return block>=0 && block<states.length && states[block]==state
                    && actualBiome>=0 && actualBiome<biomes.length && biomes[actualBiome].equals(biome);
        }
    }
    private static int decode(MethodHandle handle,long value) throws ReflectiveOperationException {
        try {return (int)handle.invokeExact(value);}
        catch(RuntimeException | Error e) {throw e;}
        catch(Throwable e) {throw new ReflectiveOperationException(e);}
    }
    private void releaseEngine() {
        try {releaseRef.invoke(engine);}catch(ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Optional reads must not stop acquisition during a destination shutdown.
        }
    }
    /** References span worker reads only, never a wait for the client thread. */
    @Override public void close() {}
}
