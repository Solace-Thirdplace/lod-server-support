package dev.vox.lss.compat;

import dev.vox.lss.api.VoxelColumnData;
import net.minecraft.client.multiplayer.ClientLevel;

/** Narrow Voxy-only sink. No dispatch to native-LSS third-party consumers. */
public final class DhVoxyBridge {
    public static boolean available() { return ModCompat.isVoxyBridgeActive(); }
    public static boolean ready(ClientLevel level) { return available() && VoxyCompat.dhReady(level); }
    public static boolean failed() { return VoxyCompat.dhFailed(); }
    public static int backlog() { return VoxyCompat.dhIngestBacklog(); }
    public static VoxyTerrainCache openCache(ClientLevel level) { return VoxyTerrainCache.open(level); }
    public static void ingest(ClientLevel level,int x,int z,VoxelColumnData data) {
        VoxyCompat.ingestDistantColumn(level,x,z,data);
    }
    private DhVoxyBridge() {}
}
