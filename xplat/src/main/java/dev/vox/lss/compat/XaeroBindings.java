package dev.vox.lss.compat;

import dev.vox.lss.common.LSSLogger;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.state.BlockState;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Resolve-once handle set. All-or-nothing: any missing member throws and the
 * bridge stays off. Xaero-typed members resolve with exact types from the
 * resolved classes; the three {@code ClientLevel}-typed members
 * ({@code getWorld}, {@code mainWorld}, {@code ignoreWorld}) resolve by
 * name-scan (the {@code MoonriseReadCompat} shape-scan precedent) and are
 * handled as Objects behind {@link XaeroSession.LevelOps}, because tests cannot construct
 * a {@code ClientLevel}.
 *
 * <p>Every member is RECORDED as it resolves ({@link #boundMembers()}): the handles are
 * {@code asType}-adapted, so they cannot be cracked back afterwards, and the golden
 * binding-surface test checks this list against each line's real Xaero jar (cave-layers
 * plan §B7).
 */
final class XaeroBindings {
    final MethodHandle getCurrentSession;
    final MethodHandle sessionIsUsable;
    final MethodHandle getMapProcessor;
    final MethodHandle renderThreadPauseSync;
    final MethodHandle mainStuffSync;
    final MethodHandle mainWorld;
    final MethodHandle isWritingPaused;
    final MethodHandle isWaitingForWorldUpdate;
    final MethodHandle isCurrentMapLocked;
    final MethodHandle isCurrentMultiworldWritable;
    final MethodHandle getCurrentWorldId;
    final MethodHandle getCurrentDimension;
    final MethodHandle getWorld;
    final MethodHandle ignoreWorld;
    final MethodHandle getMapWorld;
    final MethodHandle getMapSaveLoad;
    final MethodHandle getLeafMapRegion;
    final MethodHandle getTilePool;
    final MethodHandle getOverlayManager;
    final MethodHandle getBlockStateShortShapeCache;
    final MethodHandle getMapRegionHighlightsPreparer;
    final MethodHandle getCaveModeDepthConfig;
    final MethodHandle isCacheOnlyMode;
    final MethodHandle getCurrentDimensionId;
    final MethodHandle isRegionDetectionComplete;
    final MethodHandle requestLoad;
    final MethodHandle writerThreadPauseSync;
    final MethodHandle regionIsWritingPaused;
    final MethodHandle getLoadState;
    final MethodHandle setLoadState;
    final MethodHandle isResting;
    final MethodHandle registerVisit;
    final MethodHandle setBeingWritten;
    final MethodHandle canRequestReload;
    final MethodHandle setAllCachePrepared;
    final MethodHandle regionGetChunk;
    final MethodHandle regionSetChunk;
    final MethodHandle newMapTileChunk;
    final MethodHandle tileChunkGetLoadState;
    final MethodHandle tileChunkSetLoadState;
    // ---- OPTIONAL surface (plan §16, the compatibility sweep): each group resolves
    // best-effort and is null when this Xaero lacks it — a miss never raises the
    // 1.42.0 floor, it just leaves that gate open (the pre-§16 behavior). ----
    /** {@code WorldMap.crashHandler} + {@code CrashHandler.getCrashedBy()}. */
    record CrashGate(MethodHandle crashHandler, MethodHandle getCrashedBy) {}
    /** The native ladder's settings read: {@code WorldMap.INSTANCE.getConfigs()
     *  .getClientConfigManager().getEffective(WorldMapProfiledConfigOptions.X)} —
     *  the channel/manager classes live in the jarjar'd xaerolib, bound by
     *  name+arity because its version differs per line. */
    record SettingsGate(MethodHandle instance, MethodHandle getConfigs,
                        MethodHandle getClientConfigManager, MethodHandle getEffective,
                        MethodHandle loadNewChunks, MethodHandle updateChunks,
                        MethodHandle getCurrentDimension, MethodHandle isUsingWorldSave) {}
    final CrashGate crashGate;
    final SettingsGate settingsGate;
    /**
     * The cave-layer surface (cave-layers plan §B3/§B7): {@code MapProcessor.getCurrentCaveLayer()}
     * (the layer the map shows — {@code MAX_VALUE} surface, {@code MIN_VALUE} Full, else the
     * band, i.e. the result of Xaero's own {@code updateCaveStart}), {@code MapWorld
     * .isIgnoreHeightmaps()} (an extractor input) and the current {@code MapDimension}'s
     * {@code isUsingWorldSave()} (world-save mode turns cave writes off). All-or-nothing:
     * null = cave writes OFF, the surface writes as before, and the diag names the group.
     */
    record CaveGate(MethodHandle getCurrentCaveLayer, MethodHandle isIgnoreHeightmaps,
                    MethodHandle getCurrentDimension, MethodHandle isUsingWorldSave) {}
    final CaveGate caveGate;
    /** {@code WorldMapProfiledConfigOptions.WRITING_DISTANCE}, read through
     *  {@link #settingsGate}'s {@code getEffective} — the native writer's distance input
     *  for the natively-writable skip (§B5). Null = unbound: the distance counts as
     *  unlimited, i.e. capped by the render distance only, as Xaero's own default. */
    final MethodHandle writingDistanceOption;
    /** {@code MapTile.CURRENT_WORLD_INTERPRETATION_VERSION}, read live (a javac literal
     *  would silently lag a bump); 1 when unreadable. */
    final int interpretationVersion;
    /** Which optional groups did not bind, for the diag line; null = all bound. */
    final String optionalMissing;
    /** Every Xaero member this binding set resolved, in resolution order. */
    private final List<BoundMember> bound = new ArrayList<>();

    /**
     * One resolved member as the bindings request it. {@code byScan} members are bound by
     * NAME (+ arity for methods) — their declared types are line flavor (ClientLevel-typed
     * boundaries, the xaerolib option/config surface) — so the golden check compares them
     * by name and arity only; every other member is checked by its exact descriptor.
     */
    record BoundMember(String group, String owner, String name, String descriptor,
                       boolean isStatic, boolean field, boolean byScan, int arity) {}

    /** The resolved surface, for the golden binding-surface test. */
    List<BoundMember> boundMembers() {
        return Collections.unmodifiableList(this.bound);
    }

    final MethodHandle tileChunkSetChanged;
    final MethodHandle tileChunkWasChanged;
    final MethodHandle tileChunkUpdateBuffers;
    final MethodHandle getWorldBlockTintProvider;
    final MethodHandle newMapUpdateFastConfig;
    final MethodHandle setHasHadTerrain;
    final MethodHandle includeInSave;
    final MethodHandle getLeafTexture;
    final MethodHandle shouldDownloadFromPBO;
    final MethodHandle getTile;
    final MethodHandle setTile;
    final MethodHandle poolGet;
    final MethodHandle setBlock;
    final MethodHandle getBlock;
    final MethodHandle tileIsLoaded;
    final MethodHandle setSlopeUnknown;
    final MethodHandle setWorldInterpretationVersion;
    final MethodHandle setWrittenCave;
    final MethodHandle setWrittenOnce;
    final MethodHandle setLoaded;
    final MethodHandle newMapBlock;
    final MethodHandle prepareForWriting;
    final MethodHandle blockWrite;
    final MethodHandle addOverlay;
    final MethodHandle newOverlay;
    final MethodHandle increaseOpacity;
    final MethodHandle getOriginal;
    final MethodHandle highlightsPrepare;

    static XaeroBindings resolve(XaeroSession.ClassResolver resolver) throws ClassNotFoundException,
            NoSuchMethodException, NoSuchFieldException, IllegalAccessException {
        return new XaeroBindings(resolver, MethodHandles.lookup());
    }

    private XaeroBindings(XaeroSession.ClassResolver resolver, MethodHandles.Lookup lookup)
            throws ClassNotFoundException, NoSuchMethodException, NoSuchFieldException,
            IllegalAccessException {
        Class<?> sessionClass = resolver.resolve("xaero.map.WorldMapSession");
        Class<?> processorClass = resolver.resolve("xaero.map.MapProcessor");
        Class<?> saveLoadClass = resolver.resolve("xaero.map.file.MapSaveLoad");
        Class<?> mapWorldClass = resolver.resolve("xaero.map.world.MapWorld");
        Class<?> regionClass = resolver.resolve("xaero.map.region.MapRegion");
        Class<?> tileChunkClass = resolver.resolve("xaero.map.region.MapTileChunk");
        Class<?> tileClass = resolver.resolve("xaero.map.region.MapTile");
        Class<?> blockClass = resolver.resolve("xaero.map.region.MapBlock");
        Class<?> overlayClass = resolver.resolve("xaero.map.region.Overlay");
        Class<?> overlayManagerClass = resolver.resolve("xaero.map.region.OverlayManager");
        Class<?> poolClass = resolver.resolve("xaero.map.pool.MapTilePool");
        Class<?> leafTextureClass = resolver.resolve("xaero.map.region.texture.LeafRegionTexture");
        Class<?> shapeCacheClass = resolver.resolve("xaero.map.cache.BlockStateShortShapeCache");
        Class<?> highlightsClass = resolver.resolve("xaero.map.highlight.MapRegionHighlightsPreparer");
        Class<?> tintProviderClass = resolver.resolve("xaero.map.biome.BlockTintProvider");
        Class<?> fastConfigClass = resolver.resolve("xaero.map.region.MapUpdateFastConfig");

        this.getCurrentSession = findStatic(lookup, sessionClass, "getCurrentSession",
                MethodType.methodType(sessionClass)).asType(MethodType.methodType(Object.class));
        this.sessionIsUsable = virtual(lookup, sessionClass, "isUsable",
                MethodType.methodType(boolean.class), boolean.class);
        this.getMapProcessor = virtual(lookup, sessionClass, "getMapProcessor",
                MethodType.methodType(processorClass), Object.class);

        this.renderThreadPauseSync = getter(lookup, processorClass, "renderThreadPauseSync");
        this.mainStuffSync = getter(lookup, processorClass, "mainStuffSync");
        this.mainWorld = getterByName(lookup, processorClass, "mainWorld");
        this.isWritingPaused = virtual(lookup, processorClass, "isWritingPaused",
                MethodType.methodType(boolean.class), boolean.class);
        this.isWaitingForWorldUpdate = virtual(lookup, processorClass, "isWaitingForWorldUpdate",
                MethodType.methodType(boolean.class), boolean.class);
        this.isCurrentMapLocked = virtual(lookup, processorClass, "isCurrentMapLocked",
                MethodType.methodType(boolean.class), boolean.class);
        this.isCurrentMultiworldWritable = virtual(lookup, processorClass,
                "isCurrentMultiworldWritable",
                MethodType.methodType(boolean.class), boolean.class);
        this.getCurrentWorldId = virtual(lookup, processorClass, "getCurrentWorldId",
                MethodType.methodType(String.class), Object.class);
        this.getCurrentDimension = virtual(lookup, processorClass, "getCurrentDimension",
                MethodType.methodType(String.class), String.class);
        this.getWorld = methodByName(lookup, processorClass, "getWorld", 0);
        this.ignoreWorld = methodByName(lookup, processorClass, "ignoreWorld", 1);
        this.getMapWorld = virtual(lookup, processorClass, "getMapWorld",
                MethodType.methodType(mapWorldClass), Object.class);
        this.getMapSaveLoad = virtual(lookup, processorClass, "getMapSaveLoad",
                MethodType.methodType(saveLoadClass), Object.class);
        this.getLeafMapRegion = findVirtual(lookup, processorClass, "getLeafMapRegion",
                        MethodType.methodType(regionClass, int.class, int.class, int.class, boolean.class))
                .asType(MethodType.methodType(Object.class, Object.class,
                        int.class, int.class, int.class, boolean.class));
        this.getTilePool = virtual(lookup, processorClass, "getTilePool",
                MethodType.methodType(poolClass), Object.class);
        this.getOverlayManager = virtual(lookup, processorClass, "getOverlayManager",
                MethodType.methodType(overlayManagerClass), Object.class);
        this.getBlockStateShortShapeCache = virtual(lookup, processorClass,
                "getBlockStateShortShapeCache",
                MethodType.methodType(shapeCacheClass), Object.class);
        this.getMapRegionHighlightsPreparer = virtual(lookup, processorClass,
                "getMapRegionHighlightsPreparer",
                MethodType.methodType(highlightsClass), Object.class);
        this.getCaveModeDepthConfig = virtual(lookup, processorClass, "getCaveModeDepthConfig",
                MethodType.methodType(int.class), int.class);
        this.getWorldBlockTintProvider = virtual(lookup, processorClass,
                "getWorldBlockTintProvider",
                MethodType.methodType(tintProviderClass), Object.class);
        this.newMapUpdateFastConfig = findConstructor(lookup, fastConfigClass,
                        MethodType.methodType(void.class, processorClass))
                .asType(MethodType.methodType(Object.class, Object.class));

        this.isCacheOnlyMode = virtual(lookup, mapWorldClass, "isCacheOnlyMode",
                MethodType.methodType(boolean.class), boolean.class);
        this.getCurrentDimensionId = virtual(lookup, mapWorldClass, "getCurrentDimensionId",
                MethodType.methodType(ResourceKey.class), Object.class);

        this.isRegionDetectionComplete = virtual(lookup, saveLoadClass, "isRegionDetectionComplete",
                MethodType.methodType(boolean.class), boolean.class);
        this.requestLoad = findVirtual(lookup, saveLoadClass, "requestLoad",
                        MethodType.methodType(void.class, regionClass, String.class))
                .asType(MethodType.methodType(void.class, Object.class, Object.class, String.class));

        this.writerThreadPauseSync = getter(lookup, regionClass, "writerThreadPauseSync");
        this.regionIsWritingPaused = virtual(lookup, regionClass, "isWritingPaused",
                MethodType.methodType(boolean.class), boolean.class);
        this.getLoadState = virtual(lookup, regionClass, "getLoadState",
                MethodType.methodType(byte.class), byte.class);
        this.setLoadState = findVirtual(lookup, regionClass, "setLoadState",
                        MethodType.methodType(void.class, byte.class))
                .asType(MethodType.methodType(void.class, Object.class, byte.class));
        this.isResting = virtual(lookup, regionClass, "isResting",
                MethodType.methodType(boolean.class), boolean.class);
        this.registerVisit = virtual(lookup, regionClass, "registerVisit",
                MethodType.methodType(void.class), void.class);
        this.setBeingWritten = findVirtual(lookup, regionClass, "setBeingWritten",
                        MethodType.methodType(void.class, boolean.class))
                .asType(MethodType.methodType(void.class, Object.class, boolean.class));
        this.canRequestReload = virtual(lookup, regionClass, "canRequestReload_unsynced",
                MethodType.methodType(boolean.class), boolean.class);
        this.setAllCachePrepared = findVirtual(lookup, regionClass, "setAllCachePrepared",
                        MethodType.methodType(void.class, boolean.class))
                .asType(MethodType.methodType(void.class, Object.class, boolean.class));
        this.regionGetChunk = findVirtual(lookup, regionClass, "getChunk",
                        MethodType.methodType(tileChunkClass, int.class, int.class))
                .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
        this.regionSetChunk = findVirtual(lookup, regionClass, "setChunk",
                        MethodType.methodType(void.class, int.class, int.class, tileChunkClass))
                .asType(MethodType.methodType(void.class, Object.class,
                        int.class, int.class, Object.class));

        this.newMapTileChunk = findConstructor(lookup, tileChunkClass,
                        MethodType.methodType(void.class, regionClass, int.class, int.class))
                .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
        this.tileChunkGetLoadState = virtual(lookup, tileChunkClass, "getLoadState",
                MethodType.methodType(int.class), int.class);
        this.tileChunkSetLoadState = findVirtual(lookup, tileChunkClass, "setLoadState",
                        MethodType.methodType(void.class, byte.class))
                .asType(MethodType.methodType(void.class, Object.class, byte.class));
        this.tileChunkSetChanged = findVirtual(lookup, tileChunkClass, "setChanged",
                        MethodType.methodType(void.class, boolean.class))
                .asType(MethodType.methodType(void.class, Object.class, boolean.class));
        this.tileChunkWasChanged = virtual(lookup, tileChunkClass, "wasChanged",
                MethodType.methodType(boolean.class), boolean.class);
        this.tileChunkUpdateBuffers = findVirtual(lookup, tileChunkClass, "updateBuffers",
                        MethodType.methodType(void.class, processorClass, tintProviderClass,
                                overlayManagerClass, boolean.class, shapeCacheClass,
                                fastConfigClass))
                .asType(MethodType.methodType(void.class, Object.class, Object.class,
                        Object.class, Object.class, boolean.class, Object.class, Object.class));
        this.setHasHadTerrain = virtual(lookup, tileChunkClass, "setHasHadTerrain",
                MethodType.methodType(void.class), void.class);
        this.includeInSave = virtual(lookup, tileChunkClass, "includeInSave",
                MethodType.methodType(boolean.class), boolean.class);
        this.getLeafTexture = virtual(lookup, tileChunkClass, "getLeafTexture",
                MethodType.methodType(leafTextureClass), Object.class);
        this.shouldDownloadFromPBO = virtual(lookup, leafTextureClass, "shouldDownloadFromPBO",
                MethodType.methodType(boolean.class), boolean.class);
        this.getTile = findVirtual(lookup, tileChunkClass, "getTile",
                        MethodType.methodType(tileClass, int.class, int.class))
                .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
        this.setTile = findVirtual(lookup, tileChunkClass, "setTile",
                        MethodType.methodType(void.class, int.class, int.class, tileClass,
                                shapeCacheClass, processorClass))
                .asType(MethodType.methodType(void.class, Object.class, int.class, int.class,
                        Object.class, Object.class, Object.class));

        this.poolGet = findVirtual(lookup, poolClass, "get",
                        MethodType.methodType(tileClass, String.class, int.class, int.class))
                .asType(MethodType.methodType(Object.class, Object.class,
                        String.class, int.class, int.class));
        this.getBlock = findVirtual(lookup, tileClass, "getBlock",
                        MethodType.methodType(blockClass, int.class, int.class))
                .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
        this.tileIsLoaded = virtual(lookup, tileClass, "isLoaded",
                MethodType.methodType(boolean.class), boolean.class);
        this.setSlopeUnknown = findVirtual(lookup, blockClass, "setSlopeUnknown",
                        MethodType.methodType(void.class, boolean.class))
                .asType(MethodType.methodType(void.class, Object.class, boolean.class));
        this.setBlock = findVirtual(lookup, tileClass, "setBlock",
                        MethodType.methodType(void.class, int.class, int.class, blockClass))
                .asType(MethodType.methodType(void.class, Object.class,
                        int.class, int.class, Object.class));
        this.setWorldInterpretationVersion = findVirtual(lookup, tileClass,
                        "setWorldInterpretationVersion",
                        MethodType.methodType(void.class, int.class))
                .asType(MethodType.methodType(void.class, Object.class, int.class));
        this.setWrittenCave = findVirtual(lookup, tileClass, "setWrittenCave",
                        MethodType.methodType(void.class, int.class, int.class))
                .asType(MethodType.methodType(void.class, Object.class, int.class, int.class));
        this.setWrittenOnce = findVirtual(lookup, tileClass, "setWrittenOnce",
                        MethodType.methodType(void.class, boolean.class))
                .asType(MethodType.methodType(void.class, Object.class, boolean.class));
        this.setLoaded = findVirtual(lookup, tileClass, "setLoaded",
                        MethodType.methodType(void.class, boolean.class))
                .asType(MethodType.methodType(void.class, Object.class, boolean.class));

        this.newMapBlock = findConstructor(lookup, blockClass, MethodType.methodType(void.class))
                .asType(MethodType.methodType(Object.class));
        this.prepareForWriting = findVirtual(lookup, blockClass, "prepareForWriting",
                        MethodType.methodType(void.class, int.class))
                .asType(MethodType.methodType(void.class, Object.class, int.class));
        this.blockWrite = findVirtual(lookup, blockClass, "write",
                        MethodType.methodType(void.class, BlockState.class, int.class, int.class,
                                ResourceKey.class, byte.class, boolean.class, boolean.class))
                .asType(MethodType.methodType(void.class, Object.class, BlockState.class,
                        int.class, int.class, ResourceKey.class, byte.class,
                        boolean.class, boolean.class));
        this.addOverlay = findVirtual(lookup, blockClass, "addOverlay",
                        MethodType.methodType(void.class, overlayClass))
                .asType(MethodType.methodType(void.class, Object.class, Object.class));

        this.newOverlay = findConstructor(lookup, overlayClass,
                        MethodType.methodType(void.class, BlockState.class, byte.class, boolean.class))
                .asType(MethodType.methodType(Object.class, BlockState.class,
                        byte.class, boolean.class));
        this.increaseOpacity = findVirtual(lookup, overlayClass, "increaseOpacity",
                        MethodType.methodType(void.class, int.class))
                .asType(MethodType.methodType(void.class, Object.class, int.class));
        this.getOriginal = findVirtual(lookup, overlayManagerClass, "getOriginal",
                        MethodType.methodType(overlayClass, overlayClass))
                .asType(MethodType.methodType(Object.class, Object.class, Object.class));
        this.highlightsPrepare = findVirtual(lookup, highlightsClass, "prepare",
                        MethodType.methodType(void.class, regionClass, int.class, int.class,
                                boolean.class))
                .asType(MethodType.methodType(void.class, Object.class, Object.class,
                        int.class, int.class, boolean.class));

        // ---- optional groups ----
        StringBuilder missing = new StringBuilder();
        CrashGate crash = null;
        int markCrashGate = this.beginGroup("crash-gate");
        try {
            Class<?> worldMapClass = resolver.resolve("xaero.map.WorldMap");
            Class<?> crashHandlerClass = resolver.resolve("xaero.map.CrashHandler");
            crash = new CrashGate(
                    staticFieldByName(lookup, worldMapClass, "crashHandler"),
                    methodByName(lookup, crashHandlerClass, "getCrashedBy", 0));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            this.rollbackGroup(markCrashGate);
            missing.append("crash-gate ");
        }
        SettingsGate settings = null;
        Class<?> optionsClass = null;
        int markSettingsGate = this.beginGroup("settings-gate");
        try {
            Class<?> worldMapClass = resolver.resolve("xaero.map.WorldMap");
            Class<?> channelClass = resolver.resolve("xaero.lib.common.config.channel.ConfigChannel");
            Class<?> managerClass = resolver.resolve("xaero.lib.client.config.ClientConfigManager");
            optionsClass = resolver.resolve("xaero.map.common.config.option.WorldMapProfiledConfigOptions");
            Class<?> dimensionClass = resolver.resolve("xaero.map.world.MapDimension");
            settings = new SettingsGate(
                    staticFieldByName(lookup, worldMapClass, "INSTANCE"),
                    methodByName(lookup, worldMapClass, "getConfigs", 0),
                    methodByName(lookup, channelClass, "getClientConfigManager", 0),
                    methodByName(lookup, managerClass, "getEffective", 1),
                    staticFieldByName(lookup, optionsClass, "LOAD_NEW_CHUNKS"),
                    staticFieldByName(lookup, optionsClass, "UPDATE_CHUNKS"),
                    virtual(lookup, mapWorldClass, "getCurrentDimension",
                            MethodType.methodType(dimensionClass), Object.class),
                    virtual(lookup, dimensionClass, "isUsingWorldSave",
                            MethodType.methodType(boolean.class), boolean.class));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            this.rollbackGroup(markSettingsGate);
            missing.append("settings-gate ");
        }
        int version = 1;
        int markInterpretationVersion = this.beginGroup("interpretation-version");
        try {
            var versionField = tileClass.getField("CURRENT_WORLD_INTERPRETATION_VERSION");
            version = versionField.getInt(null);
            record(versionField, false);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            this.rollbackGroup(markInterpretationVersion);
            missing.append("interpretation-version ");
        }
        CaveGate cave = null;
        int markCaveLayers = this.beginGroup("cave-layers");
        try {
            Class<?> dimensionClass = resolver.resolve("xaero.map.world.MapDimension");
            cave = new CaveGate(
                    virtual(lookup, processorClass, "getCurrentCaveLayer",
                            MethodType.methodType(int.class), int.class),
                    virtual(lookup, mapWorldClass, "isIgnoreHeightmaps",
                            MethodType.methodType(boolean.class), boolean.class),
                    virtual(lookup, mapWorldClass, "getCurrentDimension",
                            MethodType.methodType(dimensionClass), Object.class),
                    virtual(lookup, dimensionClass, "isUsingWorldSave",
                            MethodType.methodType(boolean.class), boolean.class));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            this.rollbackGroup(markCaveLayers);
            missing.append("cave-layers ");
        }
        MethodHandle writingDistance = null;
        if (settings != null) {
            int markWriteDistance = this.beginGroup("write-distance");
            try {
                writingDistance = staticFieldByName(lookup, optionsClass, "WRITING_DISTANCE");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                this.rollbackGroup(markWriteDistance);
                missing.append("write-distance ");
            }
        }
        this.caveGate = cave;
        this.writingDistanceOption = writingDistance;
        this.crashGate = crash;
        this.settingsGate = settings;
        this.interpretationVersion = version;
        this.optionalMissing = missing.isEmpty() ? null : missing.toString().trim();
        if (this.optionalMissing != null) {
            LSSLogger.warn("Xaero map bridge: optional Xaero surface not bound on this version ("
                    + this.optionalMissing + ") — those gates stay open"
                    + (cave == null ? "; cave layers are not written (the surface is)" : ""));
        }
    }

    /** The group the next recorded member belongs to ("required" until the optional ones). */
    private String group = "required";

    private int beginGroup(String name) {
        this.group = name;
        return this.bound.size();
    }

    /** A group that failed to bind leaves no partial record behind. */
    private void rollbackGroup(int mark) {
        while (this.bound.size() > mark) this.bound.remove(this.bound.size() - 1);
    }

    private void record(MethodHandle direct, MethodHandles.Lookup lookup, boolean byScan) {
        MethodHandleInfo info = lookup.revealDirect(direct);
        int kind = info.getReferenceKind();
        boolean field = kind == MethodHandleInfo.REF_getField || kind == MethodHandleInfo.REF_getStatic;
        boolean isStatic = Modifier.isStatic(info.getModifiers());
        MethodType type = info.getMethodType();
        String descriptor = field ? type.returnType().descriptorString() : type.toMethodDescriptorString();
        this.bound.add(new BoundMember(this.group, info.getDeclaringClass().getName().replace('.', '/'),
                info.getName(), descriptor, isStatic, field, byScan, field ? 0 : type.parameterCount()));
    }

    private void record(Field field, boolean byScan) {
        this.bound.add(new BoundMember(this.group, field.getDeclaringClass().getName().replace('.', '/'),
                field.getName(), field.getType().descriptorString(),
                Modifier.isStatic(field.getModifiers()), true, byScan, 0));
    }

    private MethodHandle findVirtual(MethodHandles.Lookup lookup, Class<?> owner, String name, MethodType type)
            throws NoSuchMethodException, IllegalAccessException {
        var handle = lookup.findVirtual(owner, name, type);
        record(handle, lookup, false);
        return handle;
    }

    private MethodHandle findStatic(MethodHandles.Lookup lookup, Class<?> owner, String name, MethodType type)
            throws NoSuchMethodException, IllegalAccessException {
        var handle = lookup.findStatic(owner, name, type);
        record(handle, lookup, false);
        return handle;
    }

    private MethodHandle findConstructor(MethodHandles.Lookup lookup, Class<?> owner, MethodType type)
            throws NoSuchMethodException, IllegalAccessException {
        var handle = lookup.findConstructor(owner, type);
        record(handle, lookup, false);
        return handle;
    }

    /** Exact-typed no-arg virtual, adapted to an Object receiver. */
    private MethodHandle virtual(MethodHandles.Lookup lookup, Class<?> owner,
                                 String name, MethodType type, Class<?> genericReturn)
            throws NoSuchMethodException, IllegalAccessException {
        return findVirtual(lookup, owner, name, type)
                .asType(MethodType.methodType(genericReturn, Object.class));
    }

    private MethodHandle getter(MethodHandles.Lookup lookup, Class<?> owner, String name)
            throws NoSuchFieldException, IllegalAccessException {
        var handle = lookup.findGetter(owner, name, Object.class);
        record(handle, lookup, false);
        return handle.asType(MethodType.methodType(Object.class, Object.class));
    }

    /** Field getter tolerant of the declared type (mainWorld is ClientLevel-typed). */
    private MethodHandle getterByName(MethodHandles.Lookup lookup, Class<?> owner,
                                      String name)
            throws NoSuchFieldException, IllegalAccessException {
        var field = owner.getField(name);
        var handle = lookup.unreflectGetter(field);
        record(handle, lookup, true);
        return handle.asType(MethodType.methodType(Object.class, Object.class));
    }

    /** Static field getter by name (the option constants and the singletons: their
     *  declared types are xaerolib flavor), as a no-arg {@code ()Object}. */
    private MethodHandle staticFieldByName(MethodHandles.Lookup lookup, Class<?> owner, String name)
            throws NoSuchFieldException, IllegalAccessException {
        var handle = lookup.unreflectGetter(owner.getField(name));
        record(handle, lookup, true);
        return handle.asType(MethodType.methodType(Object.class));
    }

    /**
     * Name+arity scan for the ClientLevel-typed boundary methods — the exact
     * parameter/return types stay whatever the class declares, so the stub
     * classes can declare them as Object (tests cannot construct a ClientLevel).
     */
    private MethodHandle methodByName(MethodHandles.Lookup lookup, Class<?> owner,
                                      String name, int paramCount)
            throws NoSuchMethodException, IllegalAccessException {
        Method found = null;
        for (Method m : owner.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount
                    && !m.isSynthetic() && !m.isBridge()) {
                found = m;
                break;
            }
        }
        if (found == null) {
            throw new NoSuchMethodException(owner.getName() + "." + name + "/" + paramCount);
        }
        var handle = lookup.unreflect(found);
        record(handle, lookup, true);
        var generic = MethodType.genericMethodType(paramCount + (Modifier.isStatic(found.getModifiers()) ? 0 : 1));
        if (found.getReturnType() == boolean.class) {
            generic = generic.changeReturnType(boolean.class);
        }
        return handle.asType(generic);
    }
}
