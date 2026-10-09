package dev.vox.lssfixture.dh;

import dev.vox.lss.common.dh.DhTerrainTile;
import dev.vox.lss.compat.VoxyTerrainCache;
import net.minecraft.world.level.block.state.BlockState;
import java.lang.reflect.*;

/** Private full-content readback without adding a split production package to the NeoForge module layer. */
final class DhCacheProbe {
    static boolean matches(Object converter,DhTerrainTile tile,int column,VoxyTerrainCache cache) throws Exception {
        var snapshot=cache.readColumn(tile.position().x()*4+column/4,tile.position().z()*4+column%4,
                tile.world().minY(),tile.world().height());
        Class<?> callback=Class.forName("dev.vox.lss.networking.client.DhColumnConverter$CacheColumn");
        Object proxy=Proxy.newProxyInstance(callback.getClassLoader(),new Class<?>[]{callback},(self,method,args)->{
            if(method.getName().equals("matches"))return snapshot.matches((int)args[0],(int)args[1],(int)args[2],
                    (BlockState)args[3],(String)args[4],(int)args[5]);
            return switch(method.getName()) {
                case "toString"->"Private Voxy cache verifier";case "hashCode"->System.identityHashCode(self);
                case "equals"->self==args[0];default->throw new UnsupportedOperationException(method.getName());
            };
        });
        var method=converter.getClass().getDeclaredMethod("matchesCache",DhTerrainTile.class,int.class,callback);method.setAccessible(true);
        return (boolean)method.invoke(converter,tile,column,proxy);
    }
}
