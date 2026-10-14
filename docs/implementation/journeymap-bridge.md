# JourneyMap bridge decision record

Recorded 2026-10-11. The normative design is the [map-ingestion plan](../planning/2026-10-11-map-ingestion-journeymap-plan.md); §11 records the as-built deviations, the live findings and the implementation-review dispositions. This record keeps the decisions that a later change must not undo silently.

## Decisions

| Decision | Why | Pinned by |
| --- | --- | --- |
| One neutral setting, `integrations.map_ingestion.{enabled,backpressure}`; `integrations.xaero_map` is a read alias, canonicalized only by a save that changes something | One switch for any supported map (user request); upgrades keep their old choice; a startup save without changes must not rewrite the file | `MapIngestionSettingsTest`, the settings alias suites |
| New settings files default map ingestion ON; an upgraded file keeps its value | User decision. New modpack instances count as new files, so map-only clients now download the LOD area by default | the settings schema and fresh-file tests |
| Downgrade is not transparent: a file saved with `map_ingestion` fails on 0.17.0 and older | User decision U3 (rename and document). The fix is renaming the group back to `xaero_map` | README, troubleshooting, CLAUDE.md |
| The map paces the download (`backpressure` on by default) | User decision U2. A map that falls behind drops columns and causes re-serves; pacing fills the map completely at its own speed | `IngestBackpressureGovernor`, `JourneyMapSessionTest` |
| Reflection only, never JourneyMap code | JourneyMap is All Rights Reserved. The Tier-1 stubs under `journeymap.*` are test-only and `release_check` forbids `journeymap/` and `xaero/` entries in every shipped jar | `release_check.py` selftest |
| Classes resolve with `Class.forName(name, false, loader)` | Initializing JourneyMap's `DataCache` from LSS's client init poisoned the class and crashed JourneyMap (live smoke #1) | the InitProbe/InitFlag Tier-1 pin |
| LSS encodes JourneyMap's network snapshot format; JourneyMap's own decoder and renderer do the rest | Writing JourneyMap's tiles directly would copy its renderer; its snapshot format is the published network path | the decoder parity test against the golden jars |
| The day map decides acceptance; topography and biome are best effort | JourneyMap's topography renderer refuses every chunk until its palettes load, and its own pipeline ignores that refusal (live smoke #2) | `optional_refusals`, session tests |
| The edge sweep renders JourneyMap's own live chunks between its mapping range and game render distance + 3, each client chunk once (keyed on the client's `LevelChunk`, no JourneyMap lookup for a drawn one; at most 256 new lookups per sweep) | JourneyMap maps only within its own range; LSS's exclusion follows vanilla, which reaches further, so a permanent ring stayed unmapped. A low JourneyMap surface cap widens the band to thousands of chunks, which must not flush JourneyMap's cache | session edge tests, the L1 checker's ring leg |
| One column at a time on JourneyMap's task thread, (z, x) order with east/south batch neighbours prepared one step ahead, a 2 × renderDelay batch box and a renderDelay/2 idle gap | Decode-all-first livelocked on real terrain; the box and gap stay inside JourneyMap's own scheduling norms, so its tasks still run between batches | session pacing tests; `map-throughput` soak |
| A batch is sized to fill the box: up to box × 0.85 / (measured ms per column) columns (an average over batches, weight 0.25; 256 until a batch is measured; clamped to 16-2048) from at most 8 regions: the region the last batch drew from last first, while it has columns, then whole regions oldest entry first | One region's columns (207 on average, about 400 ms) ended a batch long before its 1000 ms box, so JourneyMap's thread idled about 43 % of the time. A fixed 1024-column pick filled the box but the box then cut most batches (61 000 requeues in one fill). The region cap keeps a scattered queue from opening hundreds of JourneyMap's off-heap region images per batch | `JourneyMapSessionTest` region-order, region-cap, adaptive-pick and pick-floor tests |
| The pure `JMChunkSnapshot.decode` runs ahead of the batch on two LSS daemon workers (`LSS-JM-DecodeAhead-n`), at most 32 decodes past the column JourneyMap's thread needs next (context neighbours included). The `SnapshotChunkMD` wrap, every `DataCache` call and every render stay on JourneyMap's thread | Decoding was about half of JourneyMap's thread time (0.84 ms of decode against 0.96 ms of render per column). The decode reads no JourneyMap state (evidence below). Unstarted jobs are cancelled at the batch end, and the batch holds the jobs only as locals, so no level is pinned past the batch. A job no worker has started, a rejected job and a null or throwing decode behave exactly as the inline decode. `-Dlss.journeymap.decodeAhead=false` is a dev-only A/B switch, not a setting | `JourneyMapSessionTest` decode-ahead tests |
| The encoder omits sections wholly more than 64 blocks below the column's lowest visible surface (invisible blocks such as a barrier roof skipped, as JourneyMap's walk skips them) | JourneyMap's surface maps never read them; this cut decode cost per column from about 1.8 ms to 1.07 ms | the encoder cull test |
| Every receipt closes exactly once: accepted after the day render, otherwise reported for re-serve | Client stamps persist per dimension; a silent drop is a permanent hole | session receipt tests |
| The server's map-ingestion request (map-policy plan A4) gates the write paths only, never registration or `enabled`. While it is on, offers drop before encoding (an encode that raced it is re-checked under the queue lock), the pump's deny branch clears the queue, and a batch still queued on JourneyMap's task thread is abandoned; all of these close their receipts unreported. `render()` checks the gate before it touches JourneyMap, so a batch the task thread starts after the deny writes nothing. A batch already writing finishes, within one 2 × renderDelay box (about 1 s at the default): its rendered columns are accepted, and its unrendered rest leaves with the queue, unreported. A blocked map reports no backpressure, so it never throttles Voxy. An allow resumes writing at the next pump of the same session (the identity is kept, so there is no new settle) | A blocked map is owed nothing: reporting would re-serve terrain it must not write, and a held stream would starve Voxy. The finishing batch is the accepted residual: its pixels are already written, and stopping it mid-column would cost more than one box | the `JourneyMapSessionTest` deny tests (receipt leases, the mid-session allow, the full-halt control); `MapPolicyWiringContractTest` |
| The late-world-id replay keeps every column rendered in the last 30 s: older renders leave the ring first, and the hard cap is the window at one full 2048-column pick per default batch cycle (box plus gap), 49 152 columns, about 1 640 columns/s | The 4096-entry ring held about 7 s at the restage's 550 columns/s, so a world id arriving later left permanent holes (O5 review). About 40 bytes per entry: about 0.7 MiB at 550 columns/s and about 2 MiB at the cap, freed at session end. The cap binds only if JourneyMap's thread averages under about 0.47 ms per column, half the fastest measured render | `aLateWorldIdReplaysEveryColumnRenderedInTheWindowAtFullThroughput` (8 100 renders), `theReplayRingHoldsTheWindowNotAFixedCount` |
| JourneyMap older than 6.0.0 is unsupported (1.21.10 ships only the 6.0.0 beta) | The bound members differ | binding-surface tests per line |

## Measured

The 0.18.0 build on a 26.2 dev client over real terrain (`map-throughput`, lod 128): about 690 columns/s with no map, 255 columns/s with JourneyMap (peak 330), decode 1.07 ms plus render 1.29 ms per column, no failures and no re-serves.

The 2026-10-14 throughput round on the 26.2 candidate (a fully generated 128-chunk area, 65 792 columns, columns/s up to 90 % received): no map 646, Xaero 612, JourneyMap 291 (all columns in 229 s). JourneyMap's thread was busy about 57 % of the time. Two uncommitted experiments: multi-region batches with a fixed 1024-column pick reached 374 columns/s, but with 61 000 requeues; adding the decode on a 2-thread worker pool reached 521 columns/s, all columns in 124 s, with JourneyMap's thread waiting 0.08 ms per column for a decode. Neither had failures, and both passed the soak laws. The production design above replaces both experiments: box-sized multi-region batches with an adaptive pick, and decode-ahead on LSS workers.

The production build, measured in the 0.19.0 restage with the same world and method (a lod-128 fill of the 65 792-column pre-generated area, 26.2 candidate):

| Client | Columns/s to 90 % | All columns |
| --- | ---: | ---: |
| No map | 646 | 102 s |
| JourneyMap, first preparation (one-region batches, inline decode) | 291 | 229 s |
| JourneyMap, restage (this design) | 550 | 118 s |

The restage run requeued 888 columns, with no failures and no re-serves. These are 26.2 numbers only. On 1.21.1 the L1 smoke measured the decode at 0.21 ms of about 1.1 ms per column on JourneyMap's thread (render 0.88 ms), against 0.84 ms of 1.8 ms on 26.2. Decode-ahead therefore removes a much smaller share there, so the 1.21.1 gain is smaller; it has not been measured. The `-Dlss.journeymap.decodeAhead=false` arm is the A/B for a future measurement. New diag fields: `batch_pick=`, `decode_ahead=on|off`, `decoded_ahead=` (batch columns whose snapshot came from a worker) and `predecode_us_per_column=` (worker time per decoded snapshot). `decode_us_per_column=` keeps its meaning: the time JourneyMap's thread spends getting a decoded column, including waits, inline decodes, context and the wrap.

## Decode-ahead purity evidence

Recorded 2026-10-14 with `javap -c -p -v` on every golden JourneyMap jar that the six lines bind against (`journeymap_golden_fabric`, `journeymap_golden_neoforge`; `journeymap_dev_version` equals the Fabric id on every line). Method: walk the transitive closure of JourneyMap methods reachable from `JMChunkSnapshot.decode(FriendlyByteBuf, Level)` through every `invoke*` and `invokedynamic` bootstrap target. Then list every field access, every external call, every JourneyMap class touched, and every `putstatic`, `monitorenter` and synchronized method. Intermediary names in the 1.21.x Fabric jars are translated through Loom's tiny mappings and compared with the NeoForge jar of the same JourneyMap version.

| Line | Fabric id | NeoForge id | JourneyMap | Result |
| --- | --- | --- | --- | --- |
| 26.3 | `2zmj8ela` | `9UsAGltN` | 6.0.10 | Pure (closure A) |
| 26.2 | `C6LURNke` | `S0bNIQSq` | 6.0.10 | Pure (closure A) |
| 26.1 | `H7ypm6CE` | `9CsaQLo2` | 6.0.10 | Pure (closure A) |
| 1.21.11 | `6Np0S5K2` | `74jT8gKb` | 6.0.0 | Pure (closure A, identical after the intermediary translation) |
| 1.21.10 | `q7s86gFk` | `3QAEEUt8` | 6.0.0-beta.58 | No snapshot layer (`JMChunkSnapshot` absent): unsupported, so decode-ahead never runs |
| 1.21.1 | `i7LuKuDl` | `CtFE7hwT` | 6.0.9 | Pure (closure B: closure A in the 1.21.1 API generation) |

Fabric and NeoForge jars of the same line have identical closures.

Closure A: `decode` is `public static`. It reaches only private static methods of `JMChunkSnapshot` itself: `readNetworkBinary`, `readSectionBlockStatesFromNetwork`, `readSectionBiomesFromNetwork`, the `skip*FromNetwork` pair, `readPackedValues`, `skipPackedValues`, `readPackedOffsets`, `readVarInt`, `getPackedLongCount`, and its private constructor. No other JourneyMap class is referenced. The only JourneyMap static it reads is `JMChunkSnapshot.LOGGER`, a `private static final` log4j `Logger` that is thread-safe. The only writes are the new snapshot's own final fields. There is no `putstatic`, no `monitorenter` and no synchronized method. Everything else is vanilla parsing over caller-private data and immutable level facts:

- `DataInputStream` over a private `ByteArrayInputStream`, and reads of the caller's private `FriendlyByteBuf`.
- Fresh `ChunkPos`, `PalettedContainer` (plus `set` on that fresh container), `LevelChunkSection` and `DataLayer` objects; `Strategy.createForBlockStates` and `createForBiomes`; `Heightmap.Types.values()`; `Mth.ceillog2`.
- `Block.BLOCK_STATE_REGISTRY.byId` (an `IdMapper`, read-only after bootstrap) and `Blocks.AIR.defaultBlockState()`.
- `Identifier.parse` and `ResourceKey.create` (vanilla's concurrent interner), and `Biomes.PLAINS`.
- The level's biome registry: `Level.registryAccess()` (a final field, the connection's frozen registries), `lookupOrThrow(BIOME)`, `Registry.get` and `asHolderIdMap`.
- `Level.getSectionsCount()`, from the level's final dimension type.

It catches `Exception` itself and returns null, so only Errors escape.

Closure B (6.0.9) differs only by the 1.21.1 names: `ResourceLocation.parse`, `registryOrThrow`, `getHolder` and `getHolderOrThrow`, the `PalettedContainer(IdMap, T, Strategy)` constructor, and the `PalettedContainer.Strategy.SECTION_STATES` and `SECTION_BIOMES` constants.

The decode does NOT go through `SerializableChunkData.parse` or `PalettedContainerFactory`: it reads JourneyMap's own network binary format; `fromMinecraftNbt` is the NBT path.

Class initialization: `JMChunkSnapshot.<clinit>` only builds an `EnumSet` of heightmap types and calls `Journeymap.getLogger()`, which is `LogManager.getLogger("journeymap")`. LSS still initializes the class on JourneyMap's own thread before the first decode-ahead of a session, so its static initializer never runs on an LSS worker.

## Live gates

`scripts/journeymap_smoke.sh` (client mode, and `world-id` with JourneyMap on the server for the late world-id race) judges the map JourneyMap wrote with `check_journeymap_smoke.py`. The `map-throughput` and `xaero-memory` soaks need a real-terrain `SOAK_WORLD_FROM` and the `-Plss.journeymapRuntime=true` or `-Plss.xaeroRuntime=true` client lever.
