package dev.vox.lss.compat;

import dev.vox.lss.api.VoxelColumnData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.TallFlowerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.PushReaction;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure section→map-pixel computation for the Xaero's World Map bridge
 * (docs/planning/xaero-map-bridge-plan.md §5) — zero Xaero types, fully
 * unit-testable. Mirrors the decompiled {@code MapWriter.loadPixel} surface-mode
 * semantics (cave=false) against LSS's received {@link VoxelColumnData} instead of
 * a loaded {@code LevelChunk}:
 * <ul>
 *   <li>scan each (x,z) top-down from the highest non-air section's top;</li>
 *   <li>{@code topHeight} = Y of the first VISIBLE block (overlay or floor —
 *       invisible-skipped blocks never raise it; a void pixel keeps world
 *       bottom, the native shape);</li>
 *   <li>translucent runs (water, {@code TransparentBlock}s) become overlay runs
 *       with per-block light-dampening opacity, one run per contiguous same-state
 *       stretch, capped at {@value #MAX_OVERLAYS} — including the native "≥5
 *       transparent blocks → extend the run to the bottom of the transparency in
 *       one step" fast path;</li>
 *   <li>the floor is the first opaque block with a nonzero vanilla map color;
 *       its Y is the pixel height, block light at floor+1 is the pixel light
 *       (sky light is cave-mode-only in the native writer, so it never
 *       contributes here);</li>
 *   <li>biome sampled at {@code topHeight}; void pixels write AIR at world
 *       bottom with light 0 — which is also what ERASES stale map terrain when
 *       a resync serves an all-air column.</li>
 * </ul>
 * Two documented approximations of the native behavior (accepted by the plan
 * review): translucency is decided by {@code instanceof HalfTransparentBlock}
 * (ice/slime/honey + the glass family) / water-by-fluid-TYPE instead of Xaero's
 * model-quad sprite-alpha analysis (which needs the live model manager) — a
 * translucent-rendered block outside that family (nether portal is the vanilla
 * case) floors the pixel here where native overlays it; and Xaero's "flowers"
 * map option is mirrored at its default (flowers visible). Sky light is never
 * read by the SURFACE scan — the native writer uses it only in cave mode — so
 * no-sky dimensions (nether/End) need no special casing there.
 *
 * <p><b>Cave layers</b> (cave-layers plan §B4): {@link #extract(int, int, LevelFacts,
 * VoxelColumnData, List, boolean)} extracts one tile per {@link LayerTarget} from ONE decode
 * of the column — the surface through the scan above (byte-identical), every cave target
 * through {@link #scanCavePixel}, the port of {@code loadPixel}'s cave and Full branches:
 * the Layered window [max(minY, caveStart+1−depth), caveStart] with the roof skip ending at
 * the first air OR fluid, Full mode's start at the WORLD_SURFACE height derived from the
 * column (section-based when Xaero ignores heightmaps or the column is empty) with its
 * ground rules, the sky-light rules (the 15 shortcut, the first overlay's and an
 * overlay-free floor's max(block, sky)), and the void pixel's topHeight = lowY with the biome
 * sampled there. A tile carries the (layer, caveStart, depth) it was extracted with: the
 * commit writes {@code setWrittenCave(caveStart, depth)} from it — never the layer number.
 * Approximation added by the cave path: sky light across sections the column does not carry
 * ({@link ColumnAccess#skyAt}) — the real light engine is not reproducible from LSS data.
 */
final class XaeroTileExtractor {

    /** Native {@code OverlayBuilder.MAX_OVERLAYS}. */
    static final int MAX_OVERLAYS = 10;
    /** Native threshold: a transparent run this deep extends to its bottom in one step. */
    private static final int EXTEND_RUN_DEPTH = 5;

    /** One translucent run above the floor — the inputs of Xaero's {@code Overlay}. */
    record OverlayRun(BlockState state, byte light, boolean glowing, int opacity) {}

    /**
     * One extracted chunk column, ready for a main-thread commit. Arrays are
     * 256-entry, x-major ({@code index = x * 16 + z}); {@code overlays} rows are
     * null for overlay-free pixels. Immutable by discipline — built once on the
     * decode thread, read once on the main client thread.
     */
    record PreparedTile(int chunkX, int chunkZ, int worldBottomY,
                        BlockState[] floorState, short[] floorY, short[] topY,
                        ResourceKey<Biome>[] biome, byte[] light, boolean[] glowing,
                        OverlayRun[][] overlays, int layer, int caveStart, int depth,
                        TileClass tileClass) {
        /** A surface tile at Xaero's default depth (the pre-cave-layers shape). */
        PreparedTile(int chunkX, int chunkZ, int worldBottomY,
                     BlockState[] floorState, short[] floorY, short[] topY,
                     ResourceKey<Biome>[] biome, byte[] light, boolean[] glowing,
                     OverlayRun[][] overlays) {
            this(chunkX, chunkZ, worldBottomY, floorState, floorY, topY, biome, light, glowing,
                    overlays, SURFACE, SURFACE, DEFAULT_CAVE_DEPTH, TileClass.RELIABLE);
        }

        /** Extracted in a cave mode — the {@code cave} argument of {@code MapBlock.write}. */
        boolean cave() {
            return this.caveStart != SURFACE;
        }
    }

    /** Xaero's surface sentinel for both the layer and the caveStart ({@code MAX_VALUE}). */
    static final int SURFACE = Integer.MAX_VALUE;
    /** Xaero's Full-mode sentinel for both the layer and the caveStart ({@code MIN_VALUE}). */
    static final int FULL = Integer.MIN_VALUE;
    /** Xaero's default cave-mode depth (its config default; the commit writes the tile's own). */
    static final int DEFAULT_CAVE_DEPTH = 30;

    /**
     * A map-layer class (cave-layers plan §B2 — a client map concept, never a wire one).
     * RELIABLE tiles keep every guarantee of the bridge (backpressure, owed debt, drop
     * reports, one ingest lease each); BEST_EFFORT tiles are B-II's bands outside the layer
     * Xaero shows, which never owe or report. Fixed at extraction.
     */
    enum TileClass { RELIABLE, BEST_EFFORT }

    /**
     * One extraction target: the Xaero layer the tile commits to and the (caveStart, depth)
     * it is extracted with. Bands use the band TOP as caveStart (§B3: deterministic, so tiles
     * of one band shade consistently), Full uses {@link #FULL}, the surface {@link #SURFACE}.
     */
    record LayerTarget(int layer, int caveStart, int depth, TileClass tileClass) {
        static LayerTarget surface(int depth) {
            return new LayerTarget(SURFACE, SURFACE, depth, TileClass.RELIABLE);
        }

        static LayerTarget full(int depth) {
            return new LayerTarget(FULL, FULL, depth, TileClass.RELIABLE);
        }

        /** Band {@code band}'s tile: caveStart = its top, min(16·band+15, maxY). */
        static LayerTarget band(int band, int maxY, int depth, TileClass tileClass) {
            return new LayerTarget(band, Math.min(band * 16 + 15, maxY), depth, tileClass);
        }

        boolean cave() {
            return this.caveStart != SURFACE;
        }
    }

    /** The column's own level facts (decode thread): heights (top EXCLUSIVE), sky light,
     *  and the ceiling/logical height B-II's all-band set derives from. */
    record LevelFacts(int minY, int topY, boolean skyLight, boolean ceiling, int logicalHeight) {}

    /** Xaero's private {@code MapProcessor.getCaveLayer}, reimplemented: the sentinels pass
     *  through, anything else is {@code caveStart >> 4} (arithmetic — −5 is layer −1). */
    static int caveLayerOf(int caveStart) {
        return caveStart == SURFACE || caveStart == FULL ? caveStart : caveStart >> 4;
    }

    private XaeroTileExtractor() {}

    /**
     * Extract a {@link PreparedTile} from a delivered column. Decode thread; touches
     * only the column's own sections/light. An all-air column yields a full tile of
     * void pixels (never null — the erase case is load-bearing).
     */
    static PreparedTile extract(int chunkX, int chunkZ, int worldBottomY, int worldTopY,
                                VoxelColumnData columnData) {
        return extractSurface(chunkX, chunkZ, worldBottomY, Column.of(columnData, worldBottomY, worldTopY),
                DEFAULT_CAVE_DEPTH);
    }

    /**
     * Extract one tile per target from ONE decode of the column (decode thread). Reliable
     * tiles are always returned — an all-void reliable tile is the erase case; an all-void
     * BEST_EFFORT band tile (a band buried in netherrack) is skipped (§B4: no region, no disk
     * for nothing). {@code ignoreHeightmaps} is Xaero's {@code MapWorld.isIgnoreHeightmaps()}.
     */
    static List<PreparedTile> extract(int chunkX, int chunkZ, LevelFacts level, VoxelColumnData columnData,
                                      List<LayerTarget> targets, boolean ignoreHeightmaps) {
        var column = Column.of(columnData, level.minY(), level.topY());
        var out = new ArrayList<PreparedTile>(targets.size());
        for (var target : targets) {
            if (!target.cave()) {
                out.add(extractSurface(chunkX, chunkZ, level.minY(), column, target.depth()));
                continue;
            }
            var tile = extractCave(chunkX, chunkZ, level, column, target, ignoreHeightmaps);
            if (tile != null) out.add(tile);
        }
        return out;
    }

    /** One decoded column: the section-indexed access plus the heights both scans start from. */
    private static final class Column {
        final ColumnAccess access;
        /** Highest non-air section, or {@code Integer.MIN_VALUE} for a void column. */
        final int highestNonAir;
        /** Per-pixel WORLD_SURFACE height (top non-{@code isAir()} block, else minY−1) — lazily. */
        private short[] worldSurface;
        private final int minY;

        private Column(ColumnAccess access, int highestNonAir, int minY) {
            this.access = access;
            this.highestNonAir = highestNonAir;
            this.minY = minY;
        }

        static Column of(VoxelColumnData columnData, int worldBottomY, int worldTopY) {
            // sectionY-indexed lookup (sparse columns are normal — air sections are culled).
            int minSecY = worldBottomY >> 4;
            int maxSecY = (worldTopY - 1) >> 4;
            var byY = new VoxelColumnData.SectionData[maxSecY - minSecY + 1];
            int highestNonAir = Integer.MIN_VALUE;
            int highestSky = Integer.MIN_VALUE;
            for (var s : columnData.sections()) {
                if (s == null || s.section() == null) continue;
                int idx = s.sectionY() - minSecY;
                if (idx < 0 || idx >= byY.length) continue;
                byY[idx] = s;
                if (!s.section().hasOnlyAir() && s.sectionY() > highestNonAir) {
                    highestNonAir = s.sectionY();
                }
                if (s.skyLight() != null && s.sectionY() > highestSky) highestSky = s.sectionY();
            }
            return new Column(new ColumnAccess(byY, minSecY, Math.max(highestNonAir, highestSky)),
                    highestNonAir, worldBottomY);
        }

        /** {@code MapWriter.getSectionBasedHeight(chunk, 64)}: the top of the highest non-air
         *  section (its scan from y=64 up, then down, finds exactly that), or 0. */
        int sectionBasedHeight() {
            return this.highestNonAir == Integer.MIN_VALUE ? 0 : (this.highestNonAir << 4) + 15;
        }

        /** {@code LevelChunk.getHeight(WORLD_SURFACE, x, z)} derived from the column. */
        int worldSurface(int x, int z) {
            if (this.worldSurface == null) {
                var heights = new short[256];
                int top = this.highestNonAir == Integer.MIN_VALUE ? this.minY - 1 : (this.highestNonAir << 4) + 15;
                for (int px = 0; px < 16; px++) {
                    for (int pz = 0; pz < 16; pz++) {
                        int y = top;
                        while (y >= this.minY && this.access.blockAt(px, y, pz).isAir()) y--;
                        heights[px * 16 + pz] = (short) (y < this.minY ? this.minY - 1 : y);
                    }
                }
                this.worldSurface = heights;
            }
            return this.worldSurface[x * 16 + z];
        }
    }

    @SuppressWarnings("unchecked")
    private static PreparedTile extractSurface(int chunkX, int chunkZ, int worldBottomY, Column decoded, int depth) {
        int highestNonAir = decoded.highestNonAir;

        var floorState = new BlockState[256];
        var floorY = new short[256];
        var topY = new short[256];
        var biome = (ResourceKey<Biome>[]) new ResourceKey[256];
        var light = new byte[256];
        var glowing = new boolean[256];
        var overlays = new OverlayRun[256][];

        // Void column: startY < lowY, the scan body never runs, every pixel is void.
        int startY = highestNonAir == Integer.MIN_VALUE
                ? worldBottomY - 1
                : (highestNonAir << 4) + 15;

        var column = decoded.access;
        var scan = new PixelScan();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                scan.reset(worldBottomY);
                scanPixel(column, x, z, startY, scan);
                int i = x * 16 + z;
                BlockState finalState = scan.floorState == null ? AIR : scan.floorState;
                floorState[i] = finalState;
                floorY[i] = (short) (scan.floorState == null ? worldBottomY : scan.floorY);
                // Xaero persists topHeight as ONE UNSIGNED BYTE (MapSaveLoad.savePixel) — a
                // negative topY (a visible block below y=0, common in far End/void terrain)
                // reloads wrong there. Native's own writer feeds the same field; cosmetic
                // (slope shading only) — sweep B m6, recorded, not worked around.
                topY[i] = (short) scan.topH;
                biome[i] = column.biomeAt(x, scan.topH, z);
                light[i] = scan.floorState == null ? 0 : scan.floorLight;
                glowing[i] = isGlowing(finalState);
                overlays[i] = scan.finishedRuns();
            }
        }
        return new PreparedTile(chunkX, chunkZ, worldBottomY,
                floorState, floorY, topY, biome, light, glowing, overlays,
                SURFACE, SURFACE, depth, TileClass.RELIABLE);
    }

    /**
     * A cave-mode tile: {@code writeChunk}'s per-pixel window feeding the {@code loadPixel}
     * cave branches. Layered: highY = caveStart, lowY = max(minY, caveStart+1−depth); Full:
     * highY = the WORLD_SURFACE height (section-based when heightmaps are ignored or the column
     * is empty), lowY = minY; either clamped below the world top.
     */
    @SuppressWarnings("unchecked")
    private static PreparedTile extractCave(int chunkX, int chunkZ, LevelFacts level, Column decoded,
                                            LayerTarget target, boolean ignoreHeightmaps) {
        int minY = level.minY();
        boolean fullCave = target.caveStart() == FULL;
        int lowY = minY;
        if (!fullCave) {
            lowY = target.caveStart() + 1 - target.depth();
            if (lowY < minY) lowY = minY;
        }
        var floorState = new BlockState[256];
        var floorY = new short[256];
        var topY = new short[256];
        var biome = (ResourceKey<Biome>[]) new ResourceKey[256];
        var light = new byte[256];
        var glowing = new boolean[256];
        var overlays = new OverlayRun[256][];
        var column = decoded.access;
        var scan = new PixelScan();
        boolean anyFloor = false;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int mappedHeight = decoded.worldSurface(x, z);
                int highY;
                if (!fullCave) {
                    highY = target.caveStart();
                } else if (ignoreHeightmaps || mappedHeight < minY) {
                    highY = decoded.sectionBasedHeight();
                } else {
                    highY = mappedHeight;
                }
                if (highY >= level.topY()) highY = level.topY() - 1;
                scan.reset(lowY);
                scanCavePixel(column, x, z, highY, fullCave, mappedHeight, ignoreHeightmaps,
                        level.skyLight(), scan);
                int i = x * 16 + z;
                BlockState finalState = scan.floorState == null ? AIR : scan.floorState;
                floorState[i] = finalState;
                floorY[i] = (short) (scan.floorState == null ? minY : scan.floorY);
                // A void pixel keeps topHeight = lowY (the window bottom) and samples its
                // biome there — loadPixel's topH starts at lowY, not at the world bottom.
                topY[i] = (short) scan.topH;
                biome[i] = column.biomeAt(x, scan.topH, z);
                byte pixelLight = 0;
                OverlayRun[] runs = scan.finishedRuns();
                if (scan.floorState != null) {
                    anyFloor = true;
                    pixelLight = scan.floorLight;
                    // The overlay-free floor's light rule (loadPixel pc 755-789).
                    if (pixelLight < 15 && runs == null && scan.floorSky > pixelLight) {
                        pixelLight = scan.floorSky;
                    }
                }
                light[i] = pixelLight;
                glowing[i] = isGlowing(finalState);
                overlays[i] = runs;
                if (runs != null) anyFloor = true;
            }
        }
        if (!anyFloor && target.tileClass() == TileClass.BEST_EFFORT) return null;
        return new PreparedTile(chunkX, chunkZ, minY, floorState, floorY, topY, biome, light, glowing,
                overlays, target.layer(), target.caveStart(), target.depth(), target.tileClass());
    }

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** Top-down scan of one pixel — the loadPixel loop with the cave branches folded away. */
    private static void scanPixel(ColumnAccess column, int x, int z, int startY, PixelScan scan) {
        int lowY = scan.lowY;
        boolean extendedLastStep = false;
        int h = startY;
        while (h >= lowY) {
            BlockState state = column.blockAt(x, h, z);
            FluidState fluid = state.getFluidState();
            BlockState fluidLegacyBlock = fluid.isEmpty() ? null : fluid.createLegacyBlock();

            // The native ONE-SHOT deep-run extension (the !shouldExtendTillTheBottom
            // term): once the pixel has overlays and the run is ≥5 blocks deep, trace
            // to the bottom of the transparency and account the remainder's opacity in
            // one step. One-shot matters: without it the pixel stays in extend mode for
            // the rest of the column and no new overlay run can ever open below the
            // first extension (review MINOR — deep-ocean multi-run collapse).
            boolean extendThisStep = !extendedLastStep && scan.hasOverlays()
                    && scan.firstTransparentY - h >= EXTEND_RUN_DEPTH;
            extendedLastStep = extendThisStep;
            int skipY = h;
            if (extendThisStep) {
                skipY = h - 1;
                while (skipY >= lowY) {
                    BlockState trace = column.blockAt(x, skipY, z);
                    FluidState traceFluid = trace.getFluidState();
                    if (!traceFluid.isEmpty()) {
                        if (!fluidOverlayable(traceFluid)) break;
                        if (!(trace.getBlock() instanceof AirBlock)
                                && trace.getBlock() == traceFluid.createLegacyBlock().getBlock()) {
                            skipY--;
                            continue;
                        }
                    }
                    if (!blockOverlayable(trace)) break;
                    skipY--;
                }
            }

            byte blockLight = column.lightAt(x, h + 1, z);

            if (fluidLegacyBlock != null) {
                // Native fluid branch: the overlay/floor decision runs on the fluid's
                // legacy BLOCK state, but a floor hit records the ORIGINAL state.
                if (scan.step(fluidLegacyBlock, fluidOverlayable(fluid), blockLight, h,
                        extendThisStep, skipY)) {
                    scan.floor(state, h, blockLight);
                    return;
                }
            }
            Block b = state.getBlock();
            if (!(b instanceof AirBlock)
                    && (fluidLegacyBlock == null || b != fluidLegacyBlock.getBlock())) {
                if (scan.step(state, blockOverlayable(state), blockLight, h,
                        extendThisStep, skipY)) {
                    scan.floor(state, h, blockLight);
                    return;
                }
            }
            h = extendThisStep ? skipY : h - 1;
        }
    }

    /**
     * The cave and Full branches of {@code MapWriter.loadPixel} (cave=true), ported in its
     * order: underair starts true only in Full mode; a non-empty fluid enters the help step
     * (and sets underair) unless Full mode is still looking for ground; air sets underair; a
     * solid block above the first air (the roof) is skipped; Full mode's ground — not
     * ignited by lava, not replaceable, push reaction not DESTROY, not an overlay — ends the
     * look-for-ground phase and starts a new roof; everything else is the surface scan's help
     * step. The deep-run extension is shared with the surface scan verbatim.
     */
    private static void scanCavePixel(ColumnAccess column, int x, int z, int highY, boolean fullCave,
                                      int mappedHeight, boolean ignoreHeightmaps, boolean skyLit,
                                      PixelScan scan) {
        int lowY = scan.lowY;
        boolean underair = fullCave;
        boolean shouldEnterGround = fullCave;
        byte skyLight = (byte) (skyLit ? 15 : 0);
        boolean extendedLastStep = false;
        int h = highY;
        while (h >= lowY) {
            BlockState state = column.blockAt(x, h, z);
            FluidState fluid = state.getFluidState();
            BlockState fluidLegacyBlock = fluid.isEmpty() ? null : fluid.createLegacyBlock();

            boolean extendThisStep = !extendedLastStep && scan.hasOverlays()
                    && scan.firstTransparentY - h >= EXTEND_RUN_DEPTH;
            extendedLastStep = extendThisStep;
            int skipY = h;
            if (extendThisStep) {
                skipY = h - 1;
                while (skipY >= lowY) {
                    BlockState trace = column.blockAt(x, skipY, z);
                    FluidState traceFluid = trace.getFluidState();
                    if (!traceFluid.isEmpty()) {
                        if (!fluidOverlayable(traceFluid)) break;
                        if (!(trace.getBlock() instanceof AirBlock)
                                && trace.getBlock() == traceFluid.createLegacyBlock().getBlock()) {
                            skipY--;
                            continue;
                        }
                    }
                    if (!blockOverlayable(trace)) break;
                    skipY--;
                }
            }

            byte blockLight = column.lightAt(x, h + 1, z);
            if (blockLight < 15 && skyLit) {
                // The 15 shortcut: Layered with the window top at or above the pixel's
                // WORLD_SURFACE height (loadPixel pc 381-434).
                skyLight = !ignoreHeightmaps && !fullCave && highY >= mappedHeight
                        ? 15 : column.skyAt(x, h + 1, z);
            }

            if (fluidLegacyBlock != null && !shouldEnterGround) {
                underair = true;
                if (scan.step(fluidLegacyBlock, fluidOverlayable(fluid), blockLight, h,
                        extendThisStep, skipY, true, skyLight)) {
                    scan.floor(state, h, blockLight, skyLight);
                    return;
                }
            }
            Block b = state.getBlock();
            if (b instanceof AirBlock) {
                underair = true;
            } else if (!underair) {
                // the roof: skipped until the first air or fluid
            } else if (fluidLegacyBlock != null && b == fluidLegacyBlock.getBlock()) {
                // the fluid's own block: the fluid branch above handled it
            } else if (shouldEnterGround) {
                if (!state.ignitedByLava() && !state.canBeReplaced()
                        && state.getPistonPushReaction() != PushReaction.DESTROY
                        && !blockOverlayable(state)) {
                    underair = false;
                    shouldEnterGround = false;
                }
            } else if (scan.step(state, blockOverlayable(state), blockLight, h,
                    extendThisStep, skipY, true, skyLight)) {
                scan.floor(state, h, blockLight, skyLight);
                return;
            }
            h = extendThisStep ? skipY : h - 1;
        }
    }

    /** Mutable per-pixel scan state — reset and reused across the 256 pixels. */
    private static final class PixelScan {
        int lowY;
        int topH;
        BlockState floorState;
        int floorY;
        byte floorLight;
        /** The sky light the cave scan held at the floor (its overlay-free light rule). */
        byte floorSky;
        private List<OverlayRun> runs;
        private BlockState runState;
        private byte runLight;
        private int runOpacity;
        private boolean capReached;
        int firstTransparentY;

        void reset(int lowY) {
            this.lowY = lowY;
            this.topH = lowY;
            this.floorState = null;
            this.floorY = 0;
            this.floorLight = 0;
            this.floorSky = 0;
            this.runs = null;
            this.runState = null;
            this.runOpacity = 0;
            this.capReached = false;
            this.firstTransparentY = 0;
        }

        boolean hasOverlays() {
            return this.runState != null || this.runs != null && !this.runs.isEmpty();
        }

        /**
         * One scan step for a candidate state (the loadPixelHelp ladder, surface
         * mode). Returns true when the state is the pixel's FLOOR (stop the scan);
         * false to keep scanning (invisible/colorless skip, or overlay recorded).
         */
        boolean step(BlockState state, boolean overlayable, byte blockLight, int h,
                     boolean extendThisStep, int skipY) {
            return step(state, overlayable, blockLight, h, extendThisStep, skipY, false, (byte) 0);
        }

        /** The help step with the cave-mode light input: the FIRST overlay of a cave-mode
         *  pixel takes max(block, sky) (loadPixelHelp pc 68-104); later runs keep block light. */
        boolean step(BlockState state, boolean overlayable, byte blockLight, int h,
                     boolean extendThisStep, int skipY, boolean cave, byte skyLight) {
            if (isInvisible(state)) return false;
            if (overlayable) {
                if (h > this.topH) this.topH = h;
                if (extendThisStep) {
                    // Deep-run remainder, accounted onto the CURRENT run in one step —
                    // native uses the RUN state's dampening, not the current block's.
                    if (this.runState != null) {
                        this.runOpacity += this.runState.getLightDampening() * (h - skipY);
                    }
                    return false;
                }
                if (this.runState != state && !this.capReached) {
                    boolean first = !this.hasOverlays();
                    if (first) this.firstTransparentY = h;
                    flushRun();
                    if (this.runs != null && this.runs.size() >= MAX_OVERLAYS) {
                        this.capReached = true; // native builder caps at 10; extras are dropped
                    } else {
                        this.runState = state;
                        this.runLight = cave && first && skyLight > blockLight ? skyLight : blockLight;
                        this.runOpacity = 0;
                    }
                }
                if (this.runState != null) {
                    this.runOpacity += state.getLightDampening();
                }
                return false;
            }
            if (!hasVanillaColor(state)) return false;
            if (h > this.topH) this.topH = h;
            return true;
        }

        void floor(BlockState originalState, int h, byte blockLight) {
            floor(originalState, h, blockLight, (byte) 0);
        }

        void floor(BlockState originalState, int h, byte blockLight, byte skyLight) {
            this.floorState = originalState;
            this.floorY = h;
            this.floorLight = blockLight;
            this.floorSky = skyLight;
        }

        private void flushRun() {
            if (this.runState == null) return;
            if (this.runs == null) this.runs = new ArrayList<>(4);
            this.runs.add(new OverlayRun(this.runState, this.runLight,
                    isGlowing(this.runState), this.runOpacity));
            this.runState = null;
        }

        OverlayRun[] finishedRuns() {
            flushRun();
            return this.runs == null || this.runs.isEmpty()
                    ? null : this.runs.toArray(new OverlayRun[0]);
        }
    }

    /** Section-indexed access to one column's blocks/light/biomes; absent sections read as air.
     *  {@code highestLit} = the highest section with non-air blocks or a sky layer. */
    private record ColumnAccess(VoxelColumnData.SectionData[] byY, int minSecY, int highestLit) {

        private LevelChunkSection sectionFor(int y) {
            int idx = (y >> 4) - this.minSecY;
            if (idx < 0 || idx >= this.byY.length || this.byY[idx] == null) return null;
            return this.byY[idx].section();
        }

        BlockState blockAt(int x, int y, int z) {
            var section = sectionFor(y);
            if (section == null) return AIR;
            var state = section.getBlockState(x, y & 15, z);
            return state == null ? AIR : state;
        }

        byte lightAt(int x, int y, int z) {
            int idx = (y >> 4) - this.minSecY;
            if (idx < 0 || idx >= this.byY.length || this.byY[idx] == null) return 0;
            DataLayer blockLight = this.byY[idx].blockLight();
            return blockLight == null ? 0 : (byte) blockLight.get(x, y & 15, z);
        }

        /**
         * Sky light for the cave scan (sky-lit dimensions only). The section's own sky layer
         * when the column carries one; otherwise open sky (15) above every section with
         * blocks or sky data — vanilla's top-of-column rule — and dark (0) below: LSS culls
         * only all-dark air sections, and serves a section's sky layer null when it holds
         * no light. The approximation of the real light engine (cave-layers plan §B4).
         */
        byte skyAt(int x, int y, int z) {
            int sectionY = y >> 4;
            int idx = sectionY - this.minSecY;
            if (idx >= 0 && idx < this.byY.length && this.byY[idx] != null) {
                DataLayer skyLight = this.byY[idx].skyLight();
                if (skyLight != null) return (byte) skyLight.get(x, y & 15, z);
            }
            return (byte) (sectionY > this.highestLit ? 15 : 0);
        }

        ResourceKey<Biome> biomeAt(int x, int y, int z) {
            var section = sectionFor(y);
            if (section == null) return null;
            try {
                Holder<Biome> holder = section.getNoiseBiome(x >> 2, (y & 15) >> 2, z >> 2);
                return holder == null ? null : holder.unwrapKey().orElse(null);
            } catch (Throwable t) {
                return null; // a biome-less section degrades to "no biome", never fails the tile
            }
        }
    }

    /** Mirror of the native {@code MapWriter.isInvisible} (flowers option at its default). */
    private static boolean isInvisible(BlockState state) {
        Block b = state.getBlock();
        if (!(b instanceof LiquidBlock) && state.getRenderShape() == RenderShape.INVISIBLE) return true;
        if (b == Blocks.TORCH || b == Blocks.SHORT_GRASS || b == Blocks.GLASS || b == Blocks.GLASS_PANE) {
            return true;
        }
        boolean isFlower = b instanceof PitcherCropBlock || b instanceof TallFlowerBlock
                || b instanceof FlowerBlock
                || taggedFlowerNotLeaves(state);
        return b instanceof DoublePlantBlock && !isFlower;
    }

    /** The native flower-tag term, contained: unbound tags (possible mid-reload, and
     *  the fabric-loader-junit state) degrade to the instanceof checks above. */
    private static boolean taggedFlowerNotLeaves(BlockState state) {
        try {
            return state.is(BlockTags.FLOWERS) && !state.is(BlockTags.LEAVES);
        } catch (IllegalStateException tagsNotBound) {
            return false;
        }
    }

    /** Approximation of Xaero's model-alpha translucency (see class javadoc).
     *  HalfTransparentBlock is the translucent family root on every line: ice, slime, honey, and (via
     *  the TransparentBlock subclass) plain + stained glass. */
    static boolean blockOverlayable(BlockState state) {
        Block b = state.getBlock();
        return b instanceof AirBlock || b instanceof HalfTransparentBlock;
    }

    static boolean fluidOverlayable(FluidState fluid) {
        // Water by TYPE, not tag (tag lookups throw while tags are unbound): the
        // approximation of Xaero's fluid-translucency test — water overlays, lava floors.
        var type = fluid.getType();
        return type == Fluids.WATER || type == Fluids.FLOWING_WATER;
    }

    /** The native buggedStates memo: a throwing getMapColor is remembered so a bugged
     *  block in a wall costs one exception, not 256 per column on the decode thread.
     *  Bounded (a hostile registry can't grow it unboundedly); decode-thread-confined
     *  today, CHM-backed so a future second reader stays safe. */
    private static final java.util.Set<BlockState> BUGGED_STATES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final int MAX_BUGGED_STATES = 256;

    private static boolean hasVanillaColor(BlockState state) {
        if (BUGGED_STATES.contains(state)) return false;
        try {
            var color = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            return color != null && color.col != 0;
        } catch (Throwable t) {
            // Native adds throwing states to a bugged list and skips them (the scan
            // continues to the next block down, exactly like a colorless block).
            if (BUGGED_STATES.size() < MAX_BUGGED_STATES) BUGGED_STATES.add(state);
            return false;
        }
    }

    private static boolean isGlowing(BlockState state) {
        return (double) state.getLightEmission() >= 0.5;
    }
}
