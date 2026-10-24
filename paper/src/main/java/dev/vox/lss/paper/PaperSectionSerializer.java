package dev.vox.lss.paper;

import dev.vox.lss.common.processing.LoadedColumnData;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Serializes loaded chunk columns into MC-native wire format for Paper.
 * Uses {@link LevelChunkSection#write(FriendlyByteBuf)} for block states + biomes,
 * plus raw DataLayer nibble bytes for light data.
 *
 * <p>Thread contract: called concurrently from chunk-load completion threads (the owning
 * region thread on Folia, the main thread on Paper — {@code completeAsyncLoad}) and from the
 * pump's loaded-chunk probes (on Folia probing moved to the chunk's OWNING region thread via
 * the EntityScheduler hold-release — the pump no longer reads foreign-region palettes, but
 * the audit still covers completion threads vs region ticks). All state is method-local, so
 * the class must stay stateless/reentrant; the MC reads
 * are legal and tear-free off-thread — getChunkNow is a concurrent-map lookup, light
 * listeners clone SWMR state, PalettedContainer.write is synchronized (audited for the Folia
 * port, spec §3/§5).
 *
 * <p>Deferred masking (ThirdplaceMC fork): when x-ray masking has work to do in a column,
 * the calling thread only snapshots it ({@link LevelChunkSection#copy()} and
 * {@link DataLayer#copy()}) and returns a {@link LoadedColumnData#deferred deferred} column.
 * The mask, the section writes and the v20 translation run on the processing thread, which
 * is the only reader of the bytes. Masking was most of the probe's server-thread cost
 * (21.8 s of 23.4 s in an hour-long Backend profile). The bytes are identical to the eager
 * path's: the same sections, in the same state, go through the same code. Columns with
 * nothing to mask, and every column while masking is off, serialize eagerly as before.
 */
final class PaperSectionSerializer {
    private PaperSectionSerializer() {}

    /**
     * Serialize all non-air sections of a loaded chunk column into MC-native wire format.
     * Returns a {@link LoadedColumnData} with pre-serialized bytes.
     */
    private record SectionInfo(int index, int sectionY, SectionPos sectionPos,
                               DataLayer blLayer, boolean hasBlockLight,
                               DataLayer slLayer, boolean hasSkyLight) {}

    static LoadedColumnData serializeColumn(ServerLevel level, LevelChunk chunk, int cx, int cz) {
        return serializeColumn(level, chunk, cx, cz, PaperXrayMaskManager.entryForActive(level));
    }

    /** As above with the mask entry supplied (null = unmasked): the seam Tier 1 drives. */
    static LoadedColumnData serializeColumn(ServerLevel level, LevelChunk chunk, int cx, int cz,
                                            PaperXrayMaskManager.MaskEntry maskEntry) {
        int minSectionY = level.getMinSectionY();
        var sections = chunk.getSections();
        var lightEngine = level.getLightEngine();
        var blockLightListener = lightEngine.getLayerListener(LightLayer.BLOCK);
        var skyLightListener = lightEngine.getLayerListener(LightLayer.SKY);

        // First pass: collect non-air sections and cache light results. Air-only sections
        // WITH stored non-zero sky light are served too (2026-07-27, black-boundary-faces
        // fix — see the Fabric twin for the full rationale): those boundary air layers are
        // what lights top/side faces of adjacent terrain at chunk borders.
        var includedSections = new ArrayList<SectionInfo>(sections.length);
        for (int i = 0; i < sections.length; i++) {
            var section = sections[i];
            if (section == null) continue;
            int sectionY = minSectionY + i;
            var sectionPos = SectionPos.of(cx, sectionY, cz);
            var blLayer = blockLightListener.getDataLayerData(sectionPos);
            boolean hasBlockLight = blLayer != null && hasNonZeroData(blLayer);
            var slLayer = skyLightListener.getDataLayerData(sectionPos);
            boolean hasSkyLight = slLayer != null && hasNonZeroData(slLayer);

            if (section.hasOnlyAir() && !hasBlockLight && !hasSkyLight) continue;

            includedSections.add(new SectionInfo(i, sectionY, sectionPos,
                    blLayer, hasBlockLight, slLayer, hasSkyLight));
        }

        // Band rule, same as the NBT path (disk/live byte parity): SKY-lit air serves only
        // within one section of the content band — vanilla's own stored-light coverage —
        // so a void/cleared column's ambient sky can never turn a zero-section CLEAR into
        // a data column. BLOCK-lit air keeps its long-standing unconditional serve.
        int minContent = Integer.MAX_VALUE, maxContent = Integer.MIN_VALUE;
        for (var info : includedSections) {
            if (!sections[info.index()].hasOnlyAir()) {
                minContent = Math.min(minContent, info.sectionY());
                maxContent = Math.max(maxContent, info.sectionY());
            }
        }
        final boolean noContent = minContent == Integer.MAX_VALUE;
        final int lo = minContent - 1, hi = maxContent + 1;
        includedSections.removeIf(info -> sections[info.index()].hasOnlyAir()
                && !info.hasBlockLight()
                && (noContent || info.sectionY() < lo || info.sectionY() > hi));

        if (includedSections.isEmpty()) {
            return new LoadedColumnData(cx, cz, null, 0);
        }

        var registryAccess = level.registryAccess();
        var maskFactory = maskEntry != null ? PaperNbtSectionSerializer.factoryFor(registryAccess) : null;
        int capacityHint = sections.length * 1024;
        var toWrite = new LevelChunkSection[includedSections.size()];
        boolean anyToMask = false;
        for (int i = 0; i < toWrite.length; i++) {
            var info = includedSections.get(i);
            toWrite[i] = sections[info.index()];
            if (maskEntry != null && !anyToMask) {
                anyToMask = PaperXrayMaskFilter.needsMasking(toWrite[i], info.sectionY(), maskEntry.mask());
            }
        }

        if (!anyToMask) {
            byte[] serialized = writeColumn(includedSections, toWrite, maskEntry, maskFactory,
                    registryAccess, capacityHint);
            return new LoadedColumnData(cx, cz, serialized, serialized.length);
        }

        // Snapshot on this thread; everything after it runs on the processing thread.
        var infos = new ArrayList<SectionInfo>(includedSections.size());
        for (int i = 0; i < toWrite.length; i++) {
            var info = includedSections.get(i);
            toWrite[i] = toWrite[i].copy();
            infos.add(new SectionInfo(info.index(), info.sectionY(), info.sectionPos(),
                    info.hasBlockLight() ? info.blLayer().copy() : null, info.hasBlockLight(),
                    info.hasSkyLight() ? info.slLayer().copy() : null, info.hasSkyLight()));
        }
        return LoadedColumnData.deferred(cx, cz, () -> writeColumn(infos, toWrite, maskEntry,
                maskFactory, registryAccess, capacityHint));
    }

    // LevelChunkSection.write(buf) is @Deprecated on Paper (an anti-xray overload was added),
    // but the 1-arg form is the canonical vanilla serialization and is byte-identical to the
    // Fabric path. The wire format must match Fabric exactly, so keep this call (do not migrate).
    @SuppressWarnings("deprecation")
    private static byte[] writeColumn(List<SectionInfo> includedSections, LevelChunkSection[] toWrite,
                                      PaperXrayMaskManager.MaskEntry maskEntry,
                                      PalettedContainerFactory maskFactory,
                                      RegistryAccess registryAccess, int capacityHint) {
        var buf = new FriendlyByteBuf(Unpooled.buffer(capacityHint));
        try {
            buf.writeVarInt(includedSections.size());

            for (int i = 0; i < toWrite.length; i++) {
                var info = includedSections.get(i);
                var section = toWrite[i];
                if (maskEntry != null) {
                    // Masking INSIDE the choke point: probe, generation, and every consumer
                    // see identical masked bytes by construction.
                    int[] replacedCells = new int[1];
                    var masked = PaperXrayMaskFilter.mask(section, info.sectionY,
                            maskEntry.mask(), maskEntry.kind(), maskFactory, replacedCells);
                    if (masked != section) {
                        section = masked;
                        // Count only when cells were actually hidden — see the Fabric twin.
                        if (replacedCells[0] > 0) {
                            var manager = PaperXrayMaskManager.current();
                            if (manager != null) manager.countMaskedSection();
                        }
                    }
                }

                buf.writeByte(info.sectionY);
                section.write(buf);

                // Block light (cached from pass 1)
                buf.writeBoolean(info.hasBlockLight);
                if (info.hasBlockLight) {
                    buf.writeBytes(info.blLayer.getData());
                }

                // Sky light (cached from pass 1)
                buf.writeBoolean(info.hasSkyLight);
                if (info.hasSkyLight) {
                    buf.writeBytes(info.slLayer.getData());
                }
            }

            byte[] serialized = new byte[buf.readableBytes()];
            buf.readBytes(serialized);
            // C1 produce-path v20 hook (progress-doc decision 2026-08-07): native emit
            // unchanged, canonical translation at the boundary — live, generation, and
            // the dirty-detection consumers all see the same translated bytes.
            return PaperNbtSectionSerializer.toV20(serialized, registryAccess);
        } finally {
            buf.release();
        }
    }

    private static boolean hasNonZeroData(DataLayer layer) {
        for (byte b : layer.getData()) {
            if (b != 0) return true;
        }
        return false;
    }
}
