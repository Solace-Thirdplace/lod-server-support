package dev.vox.lss.compat;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * The JourneyMap surface the bridge binds (map-ingestion plan §3.2), resolved once, ALL OR
 * NOTHING, by exact {@link MethodType} built from LSS's own Minecraft class literals — they
 * remap with LSS's jar, so one source binds the intermediary-named Fabric 1.21.x JourneyMap
 * jars and the Mojang-named ones alike. JourneyMap's own names are never remapped.
 *
 * <p>Verified identical in JourneyMap 6.0.0 (1.21.11), 6.0.9 (1.21.1) and 6.0.10 (26.1.2, 26.2)
 * on Fabric and NeoForge. The 1.21.10 build (6.0.0-beta.58) predates the snapshot layer: its
 * missing {@code SnapshotChunkMD}/{@code JMChunkSnapshot} classes classify as unsupported.
 * {@code isMappingSuspended} is optional (absent in 6.0.0).
 */
final class JourneyMapBindings {
    /** The snapshot layer is absent: an older JourneyMap generation (state=unsupported). */
    static final class Unsupported extends Exception {
        Unsupported(String message) { super(message, null, false, false); }
    }

    final MethodHandle getInstance;
    final MethodHandle isMapping;
    final MethodHandle isMappingSuspended; // optional, null when absent
    final MethodHandle queueOneOff;
    final MethodHandle getChunkRenderController;
    final MethodHandle getMultithreadTaskController;
    final MethodHandle getCurrentWorldId;
    final MethodHandle enabled;
    final MethodHandle getCoreProperties;
    final MethodHandle getGameRenderDistance;
    final MethodHandle mapTopography;
    final MethodHandle mapBiome;
    final MethodHandle renderDelay;
    /** JourneyMap's own "mapping enabled" toggle (its fullscreen map's button). */
    final MethodHandle mappingEnabled;
    final MethodHandle renderDistanceSurfaceMax;
    final MethodHandle booleanFieldGet;
    final MethodHandle integerFieldGet;
    final MethodHandle renderChunk;
    final MethodHandle fromChunkPos;
    final MethodHandle mapTypeDay;
    final MethodHandle mapTypeTopo;
    final MethodHandle mapTypeBiome;
    final MethodHandle isAllowed;
    final MethodHandle getJMWorldDir;
    /** {@code DataCache.INSTANCE}, read lazily on JourneyMap's task thread (never at bind time:
     *  forcing JourneyMap's cache classes to initialize before JourneyMap itself is not ours to do). */
    final MethodHandle dataCache;
    final MethodHandle addChunkMD;
    final MethodHandle removeChunkMD;
    final MethodHandle getChunkMD;
    final MethodHandle getChunkMDWithIntent;
    final MethodHandle allowCached;
    final MethodHandle newSnapshotChunkMD;
    final MethodHandle decode;
    final MethodHandle hasLevelChunk;
    final MethodHandle getBlockData;
    final MethodHandle getDimension;
    final MethodHandle stopChunkRetention;
    final MethodHandle clearAll;
    final MethodHandle regionImageCache;
    final MethodHandle updateTextures;
    /** The JourneyMap version string for diagnostics, or null. */
    final String version;

    static JourneyMapBindings resolve(XaeroSession.ClassResolver resolver) throws Unsupported,
            ClassNotFoundException, NoSuchMethodException, NoSuchFieldException, IllegalAccessException {
        return new JourneyMapBindings(resolver, MethodHandles.lookup());
    }

    private JourneyMapBindings(XaeroSession.ClassResolver resolver, MethodHandles.Lookup lookup) throws Unsupported,
            ClassNotFoundException, NoSuchMethodException, NoSuchFieldException, IllegalAccessException {
        Class<?> snapshotChunkMd;
        Class<?> snapshot;
        try {
            snapshotChunkMd = resolver.resolve("journeymap.client.model.chunk.SnapshotChunkMD");
            snapshot = resolver.resolve("journeymap.common.chunk.JMChunkSnapshot");
        } catch (ClassNotFoundException e) {
            throw new Unsupported("this JourneyMap has no chunk-snapshot layer (6.0.0 or newer is required)");
        }
        Class<?> client = resolver.resolve("journeymap.client.JourneymapClient");
        Class<?> renderController = resolver.resolve("journeymap.client.cartography.ChunkRenderController");
        Class<?> taskController = resolver.resolve("journeymap.client.task.multi.TaskController");
        Class<?> coreProperties = resolver.resolve("journeymap.client.properties.CoreProperties");
        Class<?> booleanField = resolver.resolve("journeymap.common.properties.config.BooleanField");
        Class<?> integerField = resolver.resolve("journeymap.common.properties.config.IntegerField");
        Class<?> regionCoord = resolver.resolve("journeymap.client.model.region.RegionCoord");
        Class<?> mapType = resolver.resolve("journeymap.client.model.map.MapType");
        Class<?> fileHandler = resolver.resolve("journeymap.client.io.FileHandler");
        Class<?> dataCacheClass = resolver.resolve("journeymap.client.data.DataCache");
        Class<?> queryIntent = resolver.resolve("journeymap.client.data.DataCache$ChunkQueryIntent");
        Class<?> chunkMd = resolver.resolve("journeymap.client.model.chunk.ChunkMD");
        Class<?> blockDataArrays = resolver.resolve("journeymap.client.model.block.BlockDataArrays");
        Class<?> regionImageCacheClass = resolver.resolve("journeymap.client.model.region.RegionImageCache");

        this.getInstance = lookup.findStatic(client, "getInstance", MethodType.methodType(client));
        this.isMapping = lookup.findVirtual(client, "isMapping", MethodType.methodType(Boolean.class));
        MethodHandle suspended;
        try {
            suspended = lookup.findVirtual(client, "isMappingSuspended", MethodType.methodType(boolean.class));
        } catch (NoSuchMethodException absent) {
            suspended = null;
        }
        this.isMappingSuspended = suspended;
        this.queueOneOff = lookup.findVirtual(client, "queueOneOff", MethodType.methodType(void.class, Runnable.class));
        this.getChunkRenderController = lookup.findVirtual(client, "getChunkRenderController", MethodType.methodType(renderController));
        this.getMultithreadTaskController = lookup.findVirtual(client, "getMultithreadTaskController", MethodType.methodType(taskController));
        this.getCurrentWorldId = lookup.findVirtual(client, "getCurrentWorldId", MethodType.methodType(String.class));
        this.enabled = lookup.findVirtual(client, "enabled", MethodType.methodType(boolean.class));
        this.getCoreProperties = lookup.findVirtual(client, "getCoreProperties", MethodType.methodType(coreProperties));
        this.getGameRenderDistance = lookup.findVirtual(client, "getGameRenderDistance", MethodType.methodType(int.class));
        this.mapTopography = lookup.findGetter(coreProperties, "mapTopography", booleanField);
        this.mapBiome = lookup.findGetter(coreProperties, "mapBiome", booleanField);
        this.renderDelay = lookup.findGetter(coreProperties, "renderDelay", integerField);
        this.mappingEnabled = lookup.findGetter(coreProperties, "mappingEnabled", booleanField);
        this.renderDistanceSurfaceMax = lookup.findGetter(coreProperties, "renderDistanceSurfaceMax", integerField);
        this.booleanFieldGet = lookup.findVirtual(booleanField, "get", MethodType.methodType(Boolean.class));
        this.integerFieldGet = lookup.findVirtual(integerField, "get", MethodType.methodType(Integer.class));
        this.renderChunk = lookup.findVirtual(renderController, "renderChunk",
                MethodType.methodType(boolean.class, regionCoord, mapType, chunkMd));
        this.fromChunkPos = lookup.findStatic(regionCoord, "fromChunkPos",
                MethodType.methodType(regionCoord, File.class, mapType, int.class, int.class));
        this.mapTypeDay = lookup.findStatic(mapType, "day", MethodType.methodType(mapType, ResourceKey.class));
        this.mapTypeTopo = lookup.findStatic(mapType, "topo", MethodType.methodType(mapType, ResourceKey.class));
        this.mapTypeBiome = lookup.findStatic(mapType, "biome", MethodType.methodType(mapType, ResourceKey.class));
        this.isAllowed = lookup.findVirtual(mapType, "isAllowed", MethodType.methodType(boolean.class));
        this.getJMWorldDir = lookup.findStatic(fileHandler, "getJMWorldDir", MethodType.methodType(File.class, Minecraft.class));
        this.dataCache = lookup.findStaticGetter(dataCacheClass, "INSTANCE", dataCacheClass);
        this.addChunkMD = lookup.findVirtual(dataCacheClass, "addChunkMD", MethodType.methodType(void.class, chunkMd));
        this.removeChunkMD = lookup.findVirtual(dataCacheClass, "removeChunkMD", MethodType.methodType(void.class, chunkMd));
        this.getChunkMD = lookup.findVirtual(dataCacheClass, "getChunkMD", MethodType.methodType(chunkMd, long.class));
        this.getChunkMDWithIntent = lookup.findVirtual(dataCacheClass, "getChunkMD",
                MethodType.methodType(chunkMd, long.class, queryIntent));
        this.allowCached = lookup.findStaticGetter(queryIntent, "ALLOW_CACHED", queryIntent);
        this.newSnapshotChunkMD = lookup.findConstructor(snapshotChunkMd,
                MethodType.methodType(void.class, ClientLevel.class, snapshot, boolean.class));
        this.decode = lookup.findStatic(snapshot, "decode", MethodType.methodType(snapshot, FriendlyByteBuf.class, Level.class));
        this.hasLevelChunk = lookup.findVirtual(chunkMd, "hasLevelChunk", MethodType.methodType(boolean.class));
        this.getBlockData = lookup.findVirtual(chunkMd, "getBlockData", MethodType.methodType(blockDataArrays));
        this.getDimension = lookup.findVirtual(chunkMd, "getDimension", MethodType.methodType(ResourceKey.class));
        this.stopChunkRetention = lookup.findVirtual(chunkMd, "stopChunkRetention", MethodType.methodType(void.class));
        this.clearAll = lookup.findVirtual(blockDataArrays, "clearAll", MethodType.methodType(void.class));
        this.regionImageCache = lookup.findStaticGetter(regionImageCacheClass, "INSTANCE", regionImageCacheClass);
        this.updateTextures = lookup.findVirtual(regionImageCacheClass, "updateTextures",
                MethodType.methodType(void.class, boolean.class, boolean.class));
        Package p = client.getPackage();
        this.version = p == null ? null : p.getImplementationVersion();
    }
}
