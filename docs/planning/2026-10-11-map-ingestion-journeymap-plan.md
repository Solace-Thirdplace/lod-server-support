# Map ingestion: a neutral setting, a JourneyMap backend and the Xaero memory fix (plan, 2026-10-11, v1.1)

Status: **v1.1 — four-Opus plan review folded (§10), three user decisions recorded (§0).**
Research inputs (gitignored, local): `research/journeymap/REPORT.md` (JourneyMap 6.0.x
internals from the ten released jars, decompiled) and `research/journeymap/XAERO-SURVEY.md`
(the Xaero bridge, the settings blast radius, per-line flavor). The Xaero memory mechanism is
`docs/planning/xaero-bridge-memory-plan.md` (untracked brainstorm, 2026-09-17, in the main
checkout; its §1 mechanism and approaches A/C/D are adopted here and the file is committed with
this plan). Citations use those documents' shorthands (`F26:` = decompiled JourneyMap 6.0.10
Fabric jar, `N121:` = 6.0.9 NeoForge, `S:` = `XaeroSession.java`, `AQ:` = `XaeroAcquisitionQueue.java`,
`RS:` = `XaeroRebuildScheduler.java`).

## 0. Goal and decisions

User request (2026-10-11): JourneyMap integration like the Xaero bridge; the Sodium and YAML
setting made **implementation neutral** ("map ingestion", the supported mods named only in the
details), the old YAML key still honored; **new settings files default it to enabled**; plan →
multi-Opus review → fold → implement end to end → multi-Opus implementation review.

User decisions taken after the plan review (2026-10-11):

| # | Question | Decision |
|---|---|---|
| U1 | Default-on exposes the unfixed Xaero unfocused-window OOM | **Fix Xaero memory in this program** (open-region window, bounded visits, owed-load gating, heap belt), proven by a live low-FPS test before default-on ships |
| U2 | With Voxy + a map mod, the map writer paces the shared download | **Maps pace downloads** (complete maps; LOD fill runs at the slowest armed map's rate — today's opt-in Xaero behavior, now default) |
| U3 | A renamed key makes files created/saved by ≥0.18 unreadable to ≤0.17 (they run with LODs off) | **Rename and document it** — this explicitly reverses the "main file stays readable by v0.16.0" promise (§2.4) |

Decisions this plan takes:

| # | Decision | Why |
|---|---|---|
| D1 | YAML `integrations.map_ingestion.{enabled,backpressure}`; Sodium label "Map Ingestion" | The user's phrase; later backends join without another rename |
| D2 | Old `integrations.xaero_map.*` is accepted on **every input boundary** (YAML read, `SettingsCli` and `editSplit` change sets) and canonicalized on the next **save that changes something**; never on startup | Old files and external tools keep working; a downgrade before any save still reads an upgraded file |
| D3 | Template default `enabled: true`; legacy-JSON migration keeps an absent `enableXaeroMapBridge` as **false** | "New files on"; upgrades never arm it (existing YAML carries an explicit `xaero_map.enabled: false`) |
| D4 | JourneyMap route = **JourneyMap's own snapshot render path**: LSS encodes JM's snapshot format v2 → public static `JMChunkSnapshot.decode` → `SnapshotChunkMD` → `ChunkRenderController.renderChunk`, on JM's own `JM-task` thread via `queueOneOff` | JM-identical pixels/storage; no world reads for far chunks; JM's own dead debug path is this sequence (`F26:client/event/handlers/PopupMenuEventHandler.java:354-400`, verified) |
| D5 | JourneyMap on **1.21.10 is unsupported** (only `6.0.0-beta.58`, no snapshot layer — verified) → `state=unsupported`, no Modrinth "suggests" there | One binding set covers 6.0.0/6.0.9/6.0.10; a second set for a beta-only line is not worth it |
| D6 | Overwrite policy: paint every delivered column except chunks JM holds live; a bounded **edge-context** cache shades batch/region seams (§3.6) | Same renderer, server-current data; the review confirmed this is acceptable once seams are shaded |
| D7 | The Xaero memory fix (U1) ships in this program on all five lines and gates default-on | §4 |
| D8 | All five lines; `main` (26.2) first, then ports; branches `feat/journeymap-mc<line>` off the v0.17.0 line heads, independent of the unmerged Sponge branches | Shared code is identical on all lines; JM 6.0.x exists on all five |
| D9 | Commit on local feature branches; **no push, PR, merge, tag or publish** without the user's go | Outward-facing |
| D10 | **No new YAML keys** besides the rename (dev levers are system properties) | Every new key is another downgrade break (§2.4) |

Out of scope: per-backend sub-toggles; cave/underground layers; Xaero memory-plan approaches
B, E-full, F, I and the P3 throughput items (E-lite, D(ii)); a JourneyMap native rig lane
(§6.4 is the cheaper live gate); server changes (none needed).

## 1. Background facts the design rests on

**LSS side.**
- Consumers receive `VoxelColumnData(SectionData[] sections, long columnTimestamp)`,
  `SectionData(int sectionY, LevelChunkSection section, DataLayer blockLight, DataLayer skyLight)`,
  on the `LSS-ColumnProcessor` decode thread. Sections **may include synthetic air sections**
  (`ClientColumnProcessor.withImplicitSkyAbove` / `withAirFilledAbsentSections`) and may lie
  outside the client level's range; a zero-section column is authoritative all-air.
- A registered consumer makes `LSSApi.hasVoxelConsumers()` true: a map-only client subscribes
  to and downloads the full LOD disc (`S:72-83`).
- Backpressure: `pendingIngestBacklog()` (anonymous class — a lambda keeps the default −1,
  `S:622-633`); LSS takes the max; halt at 6144. Xaero's report has a 7 s non-receding
  wedge time-box and a 10 s re-arm duty clock (`S:194-218, 679-757`).
- Settings: `integrations.xaero_map.enabled` (timing S, default false, legacy
  `enableXaeroMapBridge`), `.backpressure` (timing H, default true, legacy
  `enableXaeroMapBackpressure`) (`SettingsSchemaSpecs.java:77-78`). Unknown paths throw at
  `SettingsSchema.java:129-133`, `SettingsCli.java:52` and `YamlSettingsCodec.editSplit:91-92`;
  ≤0.17 then runs the **inactive** snapshot (no LODs) (`LSSClientConfig.java:39-48`).
- Fixture mixins and rig reflection pin exact Xaero names (`XaeroAcquisitionQueue.offerPrepared`,
  `XaeroSession.onSessionEnd`, `XaeroTileWriter.commitEntry`, `XaeroSession$Origin`,
  `XaeroSession.instance/.written`, `ModCompat.xaeroDiagLine`). **No Xaero class or member is renamed.**

**JourneyMap side (verified against the decompiled jars by the review).**
- ARR; source not public; API v2 nested in every JM jar; **no supported terrain API**.
- One single-threaded, non-daemon `JM-task` executor per mapping session (`TaskController`);
  `queueOneOff` submits to it and is a silent no-op when the controller is null; `stopMapping`
  holds the `JourneymapClient` monitor while it waits up to 5 s for the task thread.
- `SnapshotChunkMD(ClientLevel, JMChunkSnapshot, boolean)` serves blocks, biomes, the
  `WORLD_SURFACE` heightmap and per-section block light from the snapshot; no world reads.
- `JMChunkSnapshot.decode(FriendlyByteBuf, Level)` needs only `level.getSectionsCount()` and
  the biome registry; returns `null` on header/payload errors (WARN), and leaves holes without
  returning null on per-section errors.
- `renderChunk(RegionCoord, MapType, ChunkMD)` returns **false without painting** when JM is not
  mapping or the chunk's level dimension is not the player's; `day` renders night too (except the
  Nether); `topo`/`biome` are separate passes controlled by `CoreProperties.mapTopography/mapBiome`.
- `setChunkImage` writes only when pixels change or the region was blank (no repaint churn).
  Region PNGs flush on a 60 s cycle once idle > 10 s, at the 30 s expiry, and on `stopMapping`.
- MP storage: `journeymap/data/mp/<ServerListName>[_<worldId>]/<dim>/<type>/<rx>,<rz>.png`; a
  JM-server `worldId` arriving after mapping starts restarts mapping in a new directory.
- Bindings identical in 6.0.0 / 6.0.9 / 6.0.10 on both loaders (MC names differ only by runtime
  namespace); beta.58 lacks `SnapshotChunkMD`, `decode`, `hasLevelChunk` and has a 4-arg `renderChunk`.
- **6.0.9 writes an exclusive `maxBuildHeight`, 6.0.0/6.0.10 an inclusive one** — JM's own
  inconsistency; the reader always treats it as exclusive (§3.3).

## 2. The neutral setting (identical on all five lines)

### 2.1 Schema and template

```java
client.add(new Spec("integrations.map_ingestion.enabled", "enableXaeroMapBridge", Kind.BOOLEAN, Timing.S, Platform.ALL, false));
client.add(new Spec("integrations.map_ingestion.backpressure", "enableXaeroMapBackpressure", Kind.BOOLEAN, Timing.H, Platform.ALL, false));
```

`ClientSettings` → `IntegrationsMapIngestion(enabled, backpressure)` / `Integrations(mapIngestion)`;
`LSSClientConfig.mapIngestionEnabled()/mapIngestionBackpressure()` replace the Xaero accessors
(every caller updated, including `XaeroMapCompatTest:274-287`).

```yaml
integrations:
  # Writes received distant terrain into installed map mods (Xaero's World Map, JourneyMap),
  # so the map shows beyond the area you have explored. See the README for supported versions.
  # Maps save these tiles like explored terrain (they stay until you revisit), and a large LOD
  # distance can add GBs of map data. Map writing paces downloads so maps stay complete; turning
  # this off stops future writes after reconnect without removing terrain already saved.
  map_ingestion:
    enabled: true # [reconnect] Write server terrain into installed map mods; off = maps show only explored terrain.
    backpressure: true # Pace downloads to map writes; requires ingest_backpressure.
```

`client.yaml` is classified `identical` on all lines, so it carries no per-line version claims.
The `lod:` header says "such as Voxy or a map mod"; the `cache.address_aliases` note says
"Aliases stay inactive while map ingestion writes into a per-server map store".

`LSSClientConfig`'s inactive fallback snapshot (the settings-failed path, `:39-43`) also forces
`integrations.map_ingestion.enabled=false`, so a broken file never registers a map consumer.

### 2.2 Aliases

A single client-level alias table in `SettingsSchema`:

```java
static final Map<String,String> CLIENT_ALIASES = Map.of(
    "integrations.xaero_map.enabled", "integrations.map_ingestion.enabled",
    "integrations.xaero_map.backpressure", "integrations.map_ingestion.backpressure");
```

- **YAML read (`flatten`)**: an alias path maps to its canonical path; alias groups count as
  known groups. If both are present the canonical value wins and the decode records
  `Normalization(aliasPath, aliasValue, canonicalValue)` (repeats on every reload until a save).
- **Change sets (`SettingsCli` change loop, `editSplit` change loop)**: alias keys are
  canonicalized at the boundary; an alias and its canonical key in one change set is a
  `SettingsException` ("conflicting keys"). `fromValues` stays strict (no alias logic).
- **Readers** (`SettingsCli read`, `values()`) return canonical keys. In-repo tools and tests
  that write or read the old path are migrated to the canonical path:
  `tools/rig/prepare_{prism,xaero_map,xaero_recipe,elytra_target,seated_targets,yaml_reload}.py`,
  `tools/rig/scenarios/{server-smoke,native-server-store-smoke}.json`, `tools/rig/test_*` readers,
  `test-fixtures/client-preset-tools/{drive,verify}.py` (+ README; `drive.py`'s `is False`
  baseline flips with the template), `test-fixtures/dh-live/prepare.py`. Scenario, runtime-settings
  and checker hashes change (they would anyway: `scenario_checker_identity.closure()` includes all
  of `common/src/main`), so the affected `required-matrix.json` rows are re-pinned in J7.
- **Save canonicalization (`editSplit`, only when `changes` is non-empty)**: before applying the
  changes, if the composed document holds `integrations.xaero_map`, compose `defaults()`
  (brand-substituted), take its `map_ingestion` key/value node pair, write the **configured**
  values into its leaves, and substitute it at the alias pair's index inside `integrations`
  (do not use `replace()` — it copies the old comments; do not `remove()` first — it deletes the
  emptied parent and its blank-line separator). If the canonical group already exists, drop the
  alias pair. Set `touched=true`.
- **No startup rewrite**: `splitDevelopmentKeys` calls `editSplit(document, Map.of())`; the
  empty-change guard keeps canonicalization out of it.
- The schema inventory (`tools/settings/inventory.py`) exports the alias map; `reference.py`
  renders an "Aliases" table and its "readable by v0.16.0" prose is rewritten (§2.4).

### 2.3 Legacy JSON migration

After the generic loop (which leaves the template default for absent keys):

```java
Object v = old.get("enableXaeroMapBridge");
values.put("integrations.map_ingestion.enabled", v != null && (Boolean) primitive(v, Kind.BOOLEAN, path));
```

(string booleans keep working, as the generic adapter allows). `backpressure` migrates
generically. Tests: `false`→false, absent/null→false, `true`→true, `"true"`→true.

### 2.4 Downgrade (U3)

A main file containing `integrations.map_ingestion` is unreadable to ≤0.17; they run the
inactive snapshot (no LODs, `Client settings inactive: Unknown settings path …` in the log).
Affected: files **created** by ≥0.18 and files **saved** by ≥0.18 (any Apply that changes a
setting). Unaffected: upgraded files never saved. The reversal is recorded explicitly:
`DhDefaultDowngradeTest`'s javadoc, the `YamlSettingsCodec` comment (`:33-35`),
`tools/settings/reference.py`'s prose, and the 26.2 `CLAUDE.md` DH paragraph state the new
rule ("readable by v0.16.0 **unless map ingestion has been written**"); README (near the
compatibility paragraph), `docs/operations/troubleshooting.md` and the release notes give the
fix: rename `map_ingestion:` back to `xaero_map:` under `integrations:` (or delete the file).

### 2.5 Sodium page and menu

- `ClientOptionCatalog.ID_MAP_INGESTION = "lss:map_ingestion"` (replaces `ID_XAERO_MAP_BRIDGE`),
  label `lss.config.map_ingestion` "Map Ingestion", `defaultValue(true)`.
- Tooltips (`.tooltip`, `.tooltip.not_installed`, `.tooltip.native_only`) name the supported
  mods and versions; the lang files are already `adapted`, so 1.21.10's text says JourneyMap is
  not supported there. `Tooltip.Condition.XAERO_PRESENT` → `MAP_MOD_SUPPORTED`;
  `MenuContext.mapModSupported` = Xaero loaded **or** JourneyMap loaded **and** its probe
  resolved (an unsupported JM beta does not count).
- Lang `en_us`, `zh_cn`, `zh_tw` (NeoForge copies them): add the `map_ingestion` keys, delete
  `xaero_map_bridge*`; rewrite the "Xaero %s" status strings.

### 2.6 Status, diagnostics, export

- `ClientSettingsStatus.xaeroMapEnabled` → `mapIngestionEnabled` (an exported JSON key).
- `ClientStatusSnapshot`: append `journeyMapAvailability`, `journeyMapPending` after `dh`; pass
  defaults through all positional overloads; **bump `SCHEMA_VERSION` 3 → 4** deliberately.
  `Reason` gains `JOURNEYMAP_*` mirrors with `lss.status.reason.*` text in all three locales
  (`ClientStatusTextTest:30-31`); `lss.status.journeymap`; `DiagnosticVersions.Component.JOURNEYMAP`
  (both loader services). The DH-mode "no map status line" assertion covers JourneyMap.
  (`check_client_ui.py:109`'s stale `schemaVersion==2` pin is fixed in passing.)
- `/lss diag` prints `JourneyMap:` beside `XaeroMap:` (§3.8).

## 3. The JourneyMap backend

### 3.1 Classes (xplat, `dev.vox.lss.compat`, package-private like the Xaero classes)

| Class | Role |
|---|---|
| `JourneyMapCompat` | Static facade; `ModCompat` never class-loads JM code when JM is absent |
| `JourneyMapBindings` | Resolve-once `MethodHandle`s (§3.2); all-or-nothing; `unsupported` vs `unavailable(member)` |
| `JourneyMapSnapshotEncoder` | Pure: `VoxelColumnData` + pos + client-level height facts → JM network format v2 (§3.3). No JM types |
| `JourneyMapSession` | Consumer, queue, governor, pump, batch state machine, JM-task runnable, context cache, lifecycle, counters, diag |
| `IngestBackpressureGovernor` | The report core (scaling, watchdog, wedge, re-arm, token), a new class with a **differential test** against `XaeroSession`'s report on replayed traces (Xaero keeps its own copy; review MINOR 4) |
| `JourneyMapLineSurface` | The per-line flavor file: client-level min section / min Y / height accessors and the `ResourceKey` identifier accessor (1.21.1: `getMinSection/getMinBuildHeight`, `ResourceLocation`; 1.21.10: `getMinY/getMinSectionY`, `ResourceLocation`; 1.21.11 and 26.x: `Identifier`) |

### 3.2 Bindings

Bound by exact `MethodType` from LSS's own MC class literals (they remap with LSS's jar):
`JourneymapClient.getInstance/isMapping (boxed Boolean)/queueOneOff/getChunkRenderController/getMultithreadTaskController/getCurrentWorldId/enabled/getCoreProperties`;
`CoreProperties.mapTopography/mapBiome` + `BooleanField.get`; `ChunkRenderController.renderChunk(RegionCoord, MapType, ChunkMD)Z`;
`RegionCoord.fromChunkPos(File, MapType, int, int)`; `MapType.day/topo/biome(ResourceKey)`, `MapType.isAllowed()`;
`FileHandler.getJMWorldDir(Minecraft)`; `DataCache.INSTANCE`, `addChunkMD/removeChunkMD`, `getChunkMD(long)`,
`getChunkMD(long, ChunkQueryIntent)` (+ the `ALLOW_CACHED` constant); `SnapshotChunkMD.<init>(ClientLevel, JMChunkSnapshot, boolean)`;
`JMChunkSnapshot.decode(FriendlyByteBuf, Level)`; `ChunkMD.hasLevelChunk/getChunk/getBlockData`; `BlockDataArrays.clearAll`;
`RegionImageCache.INSTANCE.updateTextures(ZZ)`; optional: `ClientEventRegistry.MAPPING_EVENT` +
`Event.subscribe(String, Consumer)` + `MappingEvent.getStage` (failure → poll-only, §3.5).

Not bound: `JMChunkCache.*`, `getChunkImage` (no ownership sidecar), `ClientAPI.getDataPath`.

Probe outcome: `SnapshotChunkMD`/`JMChunkSnapshot` absent → `unsupported`; any other failure →
`unavailable(<member>)` + one WARN; success → the session registers its consumer when enabled.

### 3.3 Encoder (new LSS code written from the format; no JM code copied)

```
FriendlyByteBuf: int32 chunkX, int32 chunkZ, VarInt payloadLen, payload
payload (big-endian DataOutput):
  int32 0x4A4D4353; u8 2; int32 minSectionY; int32 minBuildHeight; int32 maxBuildHeightEXCLUSIVE
  u8 nNonEmpty; { u8 sectionIndex; blockStates; biomes }*
  u8 1; { u8 ordinal(WORLD_SURFACE); packed(256, ceillog2(max-min+1)) of (h - min) }
  u8 nLight; { u8 sectionIndex; byte[2048] block light }*
blockStates: varint n; n × varint Block.BLOCK_STATE_REGISTRY id; u8 bits (0 if n<=1 else ceillog2(n)); packed(4096) y,z,x
biomes:      varint n; n × writeUTF(identifier); u8 bits; packed(64) y,z,x (4×4×4)
packed: ceil(size·bits/64) longs, values LSB-first, spanning long boundaries
```

- `minBuildHeight`/`maxBuildHeight` from the **client level**, max written **exclusive on every
  line** (`max = minY + height`); the heightmap bit width is derived from the written header
  (review MAJOR M2: an inclusive max on a 256-high dimension masks a top-at-limit offset to 0
  → void pixels).
- Section index = `sectionY − minSectionY`; sections outside `[0, level.getSectionsCount())`
  are clipped; **air-only sections are not emitted as block sections**, but their non-zero block
  light is still emitted in the light list.
- `WORLD_SURFACE` = top non-air y + 1, else `minBuildHeight` (mandatory; absent → void).
- Biome holders without a key → `minecraft:plains`; a block-state id of −1 → 0 (air).
- Palettes are built by reading `PalettedContainer.get` into a local palette; `nNonEmpty`,
  `nLight` ≤ 255 (asserted; a column exceeding it is refused and counted).
- Encoding runs on the decode thread inside the consumer (like Xaero's extraction); J0 measures
  the per-column cost — if it exceeds 0.3 ms, encoding moves to batch build on `JM-task`.

### 3.4 Queue, states and backpressure

- Queue: latest-wins by packed position; bounds 8192 entries **and** 48 MiB of encoded bytes;
  occupancy = max(count, byte) fraction; oldest-evict; stale-dimension replace; overflow evicts
  are reported only while governed and not wedged (the Xaero rule, `AQ:155-172`).
- **Inert states drop offers before encoding, silently, and report −1**: `unsupported`,
  `unavailable`, `dead`, disabled, JM's own mapping disabled (`enabled()` false), the current
  dimension has a ceiling or `MapType.day(dim).isAllowed()` is false. These never hold the
  stream (review MAJOR 3 / shared-layer MAJOR 3).
- **Transient states hold the queue and keep reporting** (maps pace downloads, U2):
  `waiting(settle)`, `waiting(not_mapping)` during a dimension/world switch. The settle window is
  ≤ 3 s (§3.5), below the 7 s wedge time-box.
- Report: the `IngestBackpressureGovernor` over queue occupancy, composed under
  `lod.download.ingest_backpressure` ∧ `integrations.map_ingestion.backpressure`, plus the
  heap belt (§4.3). `bp=` diag token in Xaero's shape.

### 3.5 Pump (main client thread) and identity

1. Settle the session end, reconcile registration (add-only while connected), record
   `lastPumpMillis`.
2. Observe the identity tuple at ≤ 1 Hz and only while mapping (`getJMWorldDir` is
   static-synchronized and `mkdirs`): `isMapping()`, `getMultithreadTaskController()` identity,
   `Minecraft.level` identity and dimension, `getJMWorldDir(mc)`, `getCurrentWorldId()`; if the
   optional `MAPPING_EVENT` subscription bound, also the latest STARTED event's controller. A
   change resets the **settle clock (3 s)** and triggers the **recent-render replay**: positions
   rendered under the previous identity within the last 30 s (a bounded ring, ≤ 4096) are
   reported for re-serve, so a late `worldId` restart cannot leave holes in the real map.
3. Batch state machine (per batch, CAS on an `AtomicInteger`): `QUEUED → STARTED → DONE` or
   `QUEUED → ABANDONED`. Main thread: a batch not STARTED within 30 s, or whose identity changed
   while QUEUED, is CASed to ABANDONED (the runnable's own CAS then fails and it does nothing) and
   its columns are reported. A STARTED batch running longer than 10 s raises a `slow_batch` count
   and keeps the slot (the runnable always publishes a terminal result, §3.6).
4. Otherwise, when the duty gate passed (next submit ≥ `max(renderDelay, lastBatchWallMs)` after
   the last terminal result) and the queue holds columns: build the batch (≤ 256 columns of the
   region holding the oldest entry), capture the identity tuple, and submit with `queueOneOff`
   **inside `synchronized(JourneymapClient.getInstance())`** (`stopMapping`'s monitor).

### 3.6 The JM-task runnable

```
if !state.CAS(QUEUED, STARTED): return
try:
  if identityMismatch(): result = ABANDONED; return            // null-safe: getJMWorldDir may be null
  props = getCoreProperties()                                   // re-fetched per batch (replaced on startMapping)
  types = [day] + (topo if props.mapTopography && topo.isAllowed) + (biome likewise)
  rc = RegionCoord.fromChunkPos(worldDir, day, rx*32, rz*32)     // once per region (type-independent)
  mds = [], chunks = []                                         // chunks: strong refs to md.getChunk()
  for column in batch (abort check each column; stop at the renderDelay time box, requeue the rest):
     try:
       if live(pos): skipped_live++; continue                   // getChunkMD(long) != null && hasLevelChunk()
       snap = decode(column.bytes); if snap == null: decode_failures++; continue
       md = new SnapshotChunkMD(level, snap, true); mds += md; chunks += md.getChunk()
     catch Throwable t: column_failures++ (Error rethrown after cleanup)
  context = contextCache.neighboursOf(mds) minus positions in DataCache with live chunks
  add(mds ∪ context) to DataCache
  try:
    for md in mds:
      if getChunkMD(pos, ALLOW_CACHED) != md: skipped_live++; continue   // a live chunk replaced ours
      ok = all(renderChunk(rc_for(type), type, md) for type in types)     // M1: every type must succeed
      md.getBlockData().clearAll()
      (ok ? rendered : render_failed) += pos
  finally:
    for md in mds ∪ context: if getChunkMD(pos, ALLOW_CACHED) == md: removeChunkMD(md)
  RegionImageCache.updateTextures(false, true)
  if identityMismatch(): result = ABANDONED(rendered…)          // M1: post-loop re-check
  else result = DONE(rendered, render_failed, skipped_live, decode_failures, requeued)
  contextCache.offer(edge columns of this batch)               // bounded LRU ≤ 512 encoded columns
catch Throwable t: result = FAILED(t)                           // always terminal; Errors rethrown after publish
finally: publish(result)
```

- The main-thread reconcile closes `rendered` columns' receipts as accepted, **reports**
  `render_failed`, abandoned and requeued-then-expired columns, closes `skipped_live` as accepted
  (JM owns them), and counts `decode_failures` toward the **death latch** (5 consecutive batches
  whose non-empty columns all fail decode, or 5 consecutive FAILED results → `dead` for the
  session, one WARN).
- The **edge-context cache** keeps the encoded bytes of recently rendered columns (≤ 512, LRU);
  each batch decodes the N/W/NW neighbours of its columns that are missing from the batch and adds
  them to `DataCache` as context only (never rendered), so slope shading and biome blending match
  across batch and region seams.
- Dimensions with a ceiling are inert (§3.4) by scope (JM's Nether day requires "always map
  surface"; cave slices are never written). The End and modded surface dimensions are written.
- `getJMWorldDir` returning null (no level) counts as an identity mismatch.

### 3.7 Lifecycle

| Event | Handling |
|---|---|
| Client init | `ModCompat.init` adds `if (isModLoaded("journeymap")) JourneyMapCompat.init()` beside the Xaero line |
| Disconnect (any thread) | thread-safe half retires the acquisition generation; the main-thread half (next pump, also on the title screen) clears the queue, CAS-abandons a QUEUED batch, waits out a STARTED one, deregisters only if disabled |
| Dimension change / JM stop / world-id change | identity change → abandon/settle/replay (§3.5); foreign-dimension queue entries dropped and reported |
| Receive OFF / manager replaced | `retireAcquisition` |
| `/lss reset` | none (the cache flush re-serves; JM re-renders idempotently) |
| DH mode | inert (no LSS columns arrive) |
| JM data loss window | a client crash within ~70 s of a write can lose pixels LSS already closed (JM's own flush cadence); documented, no forced flush |

### 3.8 Diagnostics

`JourneyMap: state=active|disabled|dead|unsupported|unavailable(<member>)|waiting(settle|not_mapping)|inert(<reason>),
queued=, batches=, rendered=, skipped_live=, render_failed=, decode_failures=, column_failures=,
abandoned=, replayed=, requeued=, slow_batches=, last_batch_ms=, avg_ms_per_column=, context=, bp=, heap=`.
Client exporter (`ClientMetricsExporter`) gains a `journeymap` group with the same counters, so the
live smoke can read them from `client-run*.jsonl`.

## 4. Xaero memory fix and the shared belt (U1; Xaero code changes, names unchanged)

Mechanism (memory plan §1): each committed column allocates 256 `MapBlock`s; a full region is
≈ 12 MB of source data; release (`MapTileChunk.clean`) runs only per **rendered** frame and only
for regions not being written, unvisited for > 1 s, not refreshing and with no pending buffer
update. Dynamic FPS (1 FPS unfocused by default) starves release while the bridge keeps writing
at 20 Hz. LSS's queues stay tiny, so occupancy backpressure is blind to it.

### 4.1 Open-region window (approach A) — the fix

- Bridge-local `openRegions`: regions the bridge has committed into whose Xaero `loadState` is
  still 2 (not yet parked), observed via the already-bound `getLoadState` under the region
  monitor, probed ≤ 8 per pump (round-robin) like the owed set.
- Cap `N = clamp(maxHeapBytes / 160 MiB, 8, 32)` (6 GB → 32 regions ≈ 400 MB of source data;
  4 GB → 25; 2 GB → 12). Dev lever: `-Dlss.xaero.openRegionWindow=<n>` (0 = off, for the A/B).
- When `openRegions` is full, the drain **does not commit into a region outside the window**;
  those entries stay queued (`window_waits` counter), occupancy rises, and the report paces the
  download. A region leaves the window when Xaero parks it (loadState ≥ 3) or it is evicted.
- **Residency halt is legitimate**: while the queue is full **because of the window**, the
  wedge time-box is suspended (the stream is not stuck, Xaero is draining at its frame rate).
  It is bounded by a duty cycle: ≤ 60 s halted → 10 s released (the "may pace, never stop"
  rule), and released immediately when a window slot frees.

### 4.2 Bounded visits and owed loads (approach D(i), D(iii))

- `keepOwedRegionsVisited` keeps visited only the regions whose rebuild is due within the next
  `K = 4` frames' budget (the rebuild scheduler knows the order); the rest may park. A parked
  region's pending rebuild becomes an owed re-serve (existing machinery). The pin
  `owedRegionsAreKeptVisitedSoXaeroCannotParkThem` is re-stated in its bounded form.
- `probeOwed → grantLoads` grants a load only when the open-region window has a free slot
  (owed loads count against `N`).

### 4.3 Heap belt (approach C, redesigned per review MAJOR 1)

`MapMemoryGuard` (injected into both map sessions; tests construct it with a fixed sampler,
default `OK` — no static MXBean reads in Tier 1):
- Pools: an explicit allow-list of old/whole-heap pools — `G1 Old Gen`, `PS Old Gen`,
  `Tenured Gen`, `ZGC Old Generation`, `ZHeap`, `Shenandoah` — with `getCollectionUsage().getMax() > 0`;
  no matching pool → `n/a` (inert). J0 records the pool names on the Prism JDK (G1) and on
  ZGC/Shenandoah if present.
- Value: `collectionUsage.used / max`, sampled ≤ 1 Hz on the client tick.
- `HALT` at ≥ 0.85, `OK` at ≤ 0.70; **duty-cycled**: ≤ 60 s halted → 10 s released (a stale
  post-GC reading can never stop the stream; each release lets allocation trigger a fresh GC).
- Effect while `HALT` (and the composed kill switch is on): every armed map backend stops
  committing (Xaero: no commits, no owed grants, no keep-visited; JourneyMap: no batch submits)
  and reports the full halt backlog outside the wedge time-box. With U2 this pauses downloads for
  all consumers; the duty cycle bounds a false positive to a slow fill, never a stop.
- Composed under `lod.download.ingest_backpressure` (no new key, D10); `heap=` token on both
  map diag lines; INFO on entering/leaving `HALT` (rate-limited).
- JourneyMap's region images are off-heap (~1.3 MiB per type per region) and bounded by design
  (one region per batch, 30 s expiry); the belt does not see them, and does not need to.

### 4.4 Observability (P0)

`XaeroMap:` gains `open_regions=<n>/<N>`, `window_waits=`, `heap=`; the client exporter gains the
`xaero` group (`open_regions`, `window_waits`, `owed_grants_held`, `heap_fraction`) for the live gate.

### 4.5 `ModCompat` wiring (no new package; review MINOR 5)

`ModCompat` keeps its literal Xaero lines (`XaeroWiringContractTest` pins them) and adds
JourneyMap lines beside them for init, tick, frame, disconnect, retire, diag and status;
`isXaeroBridgeArmed()` stays and `isMapStoreArmed()` (Xaero armed ∨ JourneyMap armed and
resolved, excluding `unsupported/unavailable/dead`) feeds `AliasCorroboration`, whose rung token
becomes `map-store-armed` (`AliasCorroborationTest:100-101`, `ClientNetGlue:266-270`,
`TwoAxisWiringContractTest:52` updated). JourneyMap keys its store by **server-list name**, not
address: renaming a server entry starts a new JM directory while LSS answers up_to_date —
documented with the `/lss reset` fix.

## 5. Interactions and accepted behavior

| Interaction | Behavior |
|---|---|
| Map-only client | downloads the whole disc to fill the map (the feature; new files only) |
| Voxy + map (U2) | the slower map paces the shared download; the J4 measurement is recorded in the README and release notes |
| Both maps | both write; each has its own governor and wedge clock |
| JourneyMap disk | ≈ 4 PNGs per touched region (~0.1-0.3 MB each) — GBs at large LOD distances (disclosed) |
| JM-task sharing | one batch in flight, time-boxed to `renderDelay`, then ≥ `renderDelay` idle |
| Already-cached columns | not re-sent; `/lss reset` backfills (documented) |
| JM version drift | probe → `unavailable`; death latch on decode drift; the binding test reds on the next pinned bump |
| Licensing | reflection only; no JM code shipped or copied; recommend the user informs TeamJM |

## 6. Testing and validation

### 6.1 Tier 1 (every line)

- **Settings** (`common`): alias read (old, new, both → canonical + note), change-set
  canonicalization and the conflict error, save canonicalization with template comments and
  blank lines intact, **no** canonicalization at startup (incl. a file carrying a development key
  plus the alias), migration's four cases, template default true, the inactive fallback is off,
  inventory alias map.
- **Test determinism**: `ClientConfigTestSupport.set` rejects unknown paths; tests that depend on
  map ingestion set it explicitly (the persistent `fabric/config/` dir must not decide outcomes);
  reconnect-timing tests (`ClientMenuApplyTest`, `SettingsHandleTest`,
  `ClientSettingsEditSessionTest`) are inverted to toggle `false`.
- **Catalog/menu/status/export**: default parity, id list, tooltips (installed / not installed /
  native-only / JM-unsupported), schema version 4, new reasons' lang keys, DH-mode lines.
- **Governor**: direct tests + the differential test against `XaeroSession`'s report.
- **Xaero memory**: window cap/admission/park/probe rotation, residency-halt wedge suspension and
  its duty cycle, bounded keep-visited (restated pin), owed grants gated by the window, heap
  belt effect on commits/grants/visits, all against the existing `xaero/**` stubs (extended with
  `loadState` transitions). The full `XaeroMapCompatTest` suite stays green.
- **Heap guard**: pool allow-list, `max ≤ 0`, n/a, hysteresis, duty cycle, kill-switch composition.
- **Encoder**: golden bytes (flat; >16-state palette; single-state; water/lava; biome palettes;
  light with/without; zero sections; synthetic air sections with light; out-of-range sections;
  **a 256-high dimension with a block at the top y**); packing across long boundaries.
- **Real-decoder parity (mandatory, review M3)**: the line's pinned JourneyMap **NeoForge**
  artifact (Mojang names on every line) from a detached `journeymapGolden` configuration
  (Modrinth maven, pinned version ids below), loaded **child-first** for `journeymap.*` and
  `commonnetwork.*` together with its nested API jar; a Mockito `Level`
  (`getSectionsCount`, a real biome registry — the `ClientIdentityResolverTest` precedent).
  `JMChunkSnapshot.decode(our bytes)` must reproduce every block state, biome, height and block
  light of the source column (asymmetric fixtures so transposition fails). If J0 shows Tier 1
  cannot load it, the fallback is a soak-only client hook that runs the real `decode` on sampled
  columns and exports mismatch counts (then mandatory in the live smoke).
- **Binding surface vs real jars**: both loader jars per line, opened as zips: exact descriptors
  against the NeoForge and 26.x Fabric jars; JM-typed descriptors + name/arity against the 1.21.x
  intermediary Fabric jars; 1.21.10's beta.58 jars classify as `unsupported`.
- **Session** (real-package stubs `fabric/src/test/java/journeymap/...`; the decoder-parity
  loader is child-first so stubs never shadow the real classes): settle/replay, batch CAS and
  watchdogs, terminal-result guarantee on throw, every-type success rule, post-loop identity
  re-check, live skip and `ALLOW_CACHED` instance checks, context cache, inert-state drops and
  −1 report, transient hold, stale-dimension drop/report, `isAllowed`/topo/biome switches,
  time box + requeue, death latch, queue bounds/eviction, disconnect interleavings, the
  anonymous-class backlog override, diag shape.
- **Wiring contracts**: `ModCompat` literal lines (Xaero kept, JourneyMap added), alias-guard
  token, NeoForge seam, `fabric.mod.json` `suggests.journeymap: "*"` on every line **except
  1.21.10** (per-line `release_check.py`/`FabricModJsonContractTest` pin).

Pinned JourneyMap artifacts (Modrinth version ids, review-verified):

| Line | Fabric | NeoForge |
|---|---|---|
| 26.2 (6.0.10) | C6LURNke | S0bNIQSq |
| 26.1.2 (6.0.10) | H7ypm6CE | 9CsaQLo2 |
| 1.21.11 (6.0.0) | 6Np0S5K2 | 74jT8gKb |
| 1.21.10 (beta.58) | q7s86gFk | 3QAEEUt8 |
| 1.21.1 (6.0.9) | i7LuKuDl | CtFE7hwT |

### 6.2 Generated artifacts

`tools/settings/reference.py` + `inventory.py` (`settings.yml --check`), `catalog.py render`.

### 6.3 Per-line gates

`:fabric:build` (T1+T2), `:paper:test`, `:neoforge:build`, `release_check.py --version`.

### 6.4 Live gates

**L1 — JourneyMap smoke (26.2 and 1.21.1 required; NeoForge best effort).**
- Dev client runtime: JM added only with `-Plss.journeymapRuntime=true` — 26.2 via
  `modLocalRuntime`; 1.21.1 via the `addC2meDevRuntime` nested-jar pattern (the 1.21.x Fabric JM
  jar is intermediary and nests common-networking/API/pngj). Release jars never contain
  `journeymap/` (release_check pin).
- A dedicated soak scenario `journeymap-map` (not `fresh-backfill`): its own staged world with
  **asymmetric marker builds** placed by the timeline (`fill` of distinct blocks at known far
  chunks), lod 64, client settings staged explicitly (`integrations.map_ingestion.enabled: true`),
  the client's `journeymap/` data dir cleared before the run, a client-side Gradle lever (the
  server-only `SOAK_EXTRA_GRADLE_ARGS` does not reach the client).
- `scripts/check_journeymap_smoke.py` (stdlib, own PNG decoder, `--selftest` wired into
  `build.yml`): globbed `mp/*/overworld/day/` PNGs; **exact per-chunk checks**: every LSS-served
  chunk inside the disc and outside the vanilla radius is opaque; each marker's pixels have the
  marker's color class at the exact block coordinates (a one-chunk shift or transposition fails);
  `journeymap.decode_failures == 0`, `render_failed == 0`, `state=active`; no `JMChunkSnapshot`
  WARN/ERROR in the client log.
- **Throughput arm** (U2): the same run at lod 256 on non-flat terrain (a pre-generated normal
  world) records columns/s, ms/column, batch ms and PNG bytes; results go into the README and
  release notes.
- **World-id race arm**: JM also on the soak server (it sends `worldId`); assert no PNGs land in
  the null-id `mp/<server>/` directory after the run.
- A cropped map PNG is sent to the user for a visual check.

**L2 — Xaero memory gate (26.2 required).**
- Dev client with Xaero WM (the 26.2 build pinned in `mc262-fabric-modern.json`) via
  `modLocalRuntime`, a dev-only frame throttle (`-Dlss.soak.frameThrottleMs=1000`, simulating
  Dynamic FPS's 1 FPS unfocused default), heap 3 GB, lod 256 on a pre-generated world.
- A/B: `-Dlss.xaero.openRegionWindow=0` must show monotonic `MapBlock`/heap growth (the
  reproduction); the fixed build must plateau (`open_regions ≤ N`, heap post-GC flat) over 15 min,
  and the map must complete within a few minutes after the throttle is lifted. Sampler: the
  client exporter's `xaero`/`heap` fields + `jcmd GC.class_histogram` every 90 s.
- **1.21.1 native `xaero-map` rig lane** re-run (Xaero runtime behavior changed) — automated
  assertions; its human `visual_render` review is owed to the user.

### 6.5 Downgrade check

A Tier 1 test pins that an upgraded, unsaved file keeps `xaero_map` bytes untouched after
`initialize`, and that a save that changes anything rewrites it canonical.

## 7. Documentation

- `README.md`: the map paragraph rewritten neutral — supported mods and versions (JM 6.0.0+;
  1.21.10 JM unsupported), on by default for new installs, disk cost, maps pace downloads (the J4
  numbers), `/lss reset` backfill (fix the current `/lss clearcache` and the wrong key
  `integrations.xaero.enabled`), the JM server-name note; the downgrade note beside the
  compatibility paragraph (`README.md:46`).
- `docs/operations/installation.md` (`:5` "an explicit client choice" → default-on),
  `status-and-presets.md:31,46,67`, `troubleshooting.md` (downgrade fix, JM states),
  `performance.md` (pacing), `test-fixtures/client-preset-tools/README.md`.
- `docs/implementation/journeymap-bridge.md` (decision record), `xaero-ownership.md` (window,
  bounded visits, heap belt), `docs/architecture/ownership.md`.
- `CLAUDE.md` (26.2): the downgrade-rule exception in the DH paragraph and a short
  map-ingestion/JourneyMap entry.
- Release notes draft (all lines; 1.21.10 says JM unsupported; "new modpack instances count as
  new settings files, so map-only clients now download the LOD area by default").
- The user's Modrinth readme (`/home/vox/projects/notes/modrinth-readme.md`): minimal edit.

## 8. Phases

| Phase | Work | Exit |
|---|---|---|
| J0 | Spikes: **JM boots and maps in the dev client on 26.2 and 1.21.1** (runtime wiring); real-decoder Tier 1 loader; encoder cost; heap pool names; Xaero OOM reproduction with the frame throttle (L2's A arm) | each recorded in §10 |
| J1 | Settings (26.2) | settings/menu/status suites green |
| J2 | Xaero memory fix + heap belt + governor + `ModCompat` wiring (26.2) | Xaero suite + new suites green |
| J3 | JourneyMap backend (26.2) | T1 green incl. decoder parity + binding surface |
| J4 | L1 + L2 live gates on 26.2; throughput numbers; visual to the user | checkers PASS |
| J5 | Multi-Opus implementation review on 26.2; fold | every verified finding fixed or recorded |
| J6 | Ports to 26.1, 1.21.11, 1.21.10, 1.21.1 (literal applier for identical files, flavor by hand), per-line gates, L1 on 1.21.1, the 1.21.1 xaero-map rig lane; port-fidelity review | every line green |
| J7 | Cross-line: `classification.json` entries, `config/lines/port-batch.json` (baseline = `source-refs.json`, every changed path per target with reviewed blobs, `final_shared_invariants`) frozen **after** the J5/J6 folds, required-matrix re-pins, docs re-render, `lines.py check` clean; stop for the user's go | CI-ready branches, nothing pushed |

The user's DH test server stays stopped while gates run and is restored at program end.

## 9. Effort

J0 1.5 d · J1 1 d · J2 3 d · J3 3 d · J4 1.5 d · J5 1 d · J6 3 d · J7 1.5 d → ~16 dev-days.

## 10. Review record (2026-10-11: 4 Opus — settings, JourneyMap internals, shared layer/Xaero/memory, validation/rollout)

| Finding (lens) | Disposition |
|---|---|
| Settings M1: `SettingsCli`/`editSplit` reject alias keys before `fromValues`; readers KeyError | §2.2: canonicalize at both change-set boundaries, conflict error, `fromValues` strict; in-repo tools/readers migrated |
| Settings m2: `splitDevelopmentKeys` calls `editSplit(doc, Map.of())` at startup | §2.2: canonicalize only when changes are non-empty; §6.1/§6.5 tests |
| Settings m3: reverses the pinned "readable by v0.16.0" promise | U3 asked and decided; §2.4 records it everywhere it is stated |
| Settings m4: inactive fallback snapshot would arm map ingestion | §2.1 forces it off |
| Settings m5: persistent `fabric/config` makes T1 nondeterministic; `set` silently ignores unknown keys | §6.1 test determinism |
| Settings m6: migration special case turns `"true"` into false; ordering | §2.3 |
| Settings m7: export key rename needs a deliberate schema bump; reason lang keys | §2.6 (SCHEMA_VERSION 4) |
| Settings m8: reconnect-timing tests become no-ops | §6.1 inverted |
| Settings NITs 9-12: canonicalization recipe, normalization note, alias rendering, 1.21.10 text / unsupported JM counted as present | §2.2, §2.5 |
| JM M1: non-rendered columns closed as accepted (permanent holes) | §3.6 every-type success, post-loop identity re-check, reconcile reports failures |
| JM M2: inclusive `maxBuildHeight` on 6.0.0/6.0.10 masks top-at-limit heights → void | §3.3 exclusive max everywhere + golden |
| JM M3 / Shared M3: JM throughput paces Voxy under default-on | U2 asked (maps pace); §3.4 inert states never hold; §6.4 throughput arm |
| JM m1: a throw wedges the one-in-flight slot; strong refs | §3.6 terminal result always, chunk refs, slow-batch count |
| JM m2: timeout abandon not enforced | §3.5 per-batch CAS |
| JM m3: batch/region seams | §3.6 edge-context cache; D6 kept |
| JM m4: add/remove by coordinate | §3.6 `ALLOW_CACHED` instance checks |
| JM m5: settle heuristic; late worldId restart | §3.5 optional MAPPING_EVENT, 3 s settle, recent-render replay; ≤1 Hz `getJMWorldDir` |
| JM m6: stop-time stall, starving live mapping | §3.6 per-column abort check, `renderDelay` time box + requeue |
| JM NITs (Nether rationale, RegionCoord once, re-fetch properties, WARN not ERROR, synthetic air sections, plains/air fallbacks, off-heap images, 1.21.1 nested jars) | §1, §3.3, §3.6, §4.3, §6.4 |
| Shared M1: heap backstop pools, stale reading, over-halting, misses owed loads, test seam | §4.3 allow-list, duty cycle, effect covers owed grants and visits, injected guard |
| Shared M2: default-on Xaero not covered by a belt alone | U1 asked (fix it); §4.1-§4.2 approaches A and D(i)/(iii), L2 gate |
| Shared m4: governor not "verbatim pure" | §3.1 new governor + differential test; Xaero keeps its copy |
| Shared m5: wiring pins, package-private Xaero classes | §4.5 no new package; `ModCompat` literal lines kept |
| Shared m6: "no hash churn" false; rig readers break | §2.2 restated; readers migrated; J7 re-pins |
| Shared m7: decode-thread encode cost; synthetic air; alias guard keys by server name; armed excludes inert | §3.3 (J0 measures, fallback location), §4.5 |
| Shared NITs 8-9: two stuck maps; heap-halt ordering / "nothing dropped" | §3.4/§4.3 order: inert → heap → governor; drops on overflow acknowledged |
| Validation M1: CI red without a new `port-batch.json` | §8 J7 |
| Validation M2: old-key readers break CI test | §2.2 |
| Validation M3: encoder unverifiable if the spike fails; weak oracle | §6.1 mandatory parity test (child-first loader, nested API jar, real biome registry), fallback hook; §6.4 marker builds + exact per-chunk checks |
| Validation M4: 1.21.1 smoke cannot boot JM via `localRuntime` | §6.4 nested-jar pattern; J0 spike first |
| Validation M5: heap backstop never tested live | §6.4 L2 with the OOM reproduction A/B |
| Validation minors (descriptor namespaces + pinned version ids; Modrinth suggests on 1.21.10; smoke plumbing; Xaero suite accessor + rig lane; NeoForge/worldId/Voxy arms; docs list; effort) | §6.1 table, §6.4, §7, §9 |

## 11. As-built record (J1-J3, 26.2, 2026-10-11)

Deviations from the reviewed text above, each deliberate; the implementation review (J5)
judges them:

| Plan text | As built | Why |
|---|---|---|
| §4.1 `N = clamp(heap / 160 MiB, 8, 32)` | `N = clamp(heap / 96 MiB, 12, 64)` (`XaeroSession.openRegionCapFor`, lever unchanged) | At 1000 col/s the focused fill keeps ~58 regions open between parks; 160 MiB/8-32 would throttle a focused client, which was never the target. The unfocused OOM is still bounded (6 GB → 64 regions ≈ 770 MB of source data) and the heap belt is the backstop |
| §4.2 D(i) bounded keep-visited (K = 4 frames) | Not built; keep-visited unchanged | Letting owed regions park re-opens the retained-texture hazard the rebuild scheduler exists for; the window already bounds how many regions can be visited. D(iii) (grants count against the window) is built |
| §3.5 optional `MAPPING_EVENT` subscription | Poll-only (≤ 1 Hz identity poll incl. the task-controller identity) | The batch re-checks the controller, level, dimension and directory on JourneyMap's own thread before and after rendering; a missed restart between polls is caught there and re-served |
| §3.6 `chunks += md.getChunk()` strong refs | Not kept; `SnapshotChunkMD(…, forceRetain = true)` | With `forceRetain` the wrapper itself holds its `LevelChunk` until it leaves the cache; `getChunk` is no longer bound |
| §3.6 time box per column in the decode loop | Time box in the RENDER loop | Decoding is cheap; rendering is the cost the box exists to bound. Columns past the box are requeued (a newer delivery of the same position wins) |
| §3.6 `RegionCoord` once per region | Once per map type | `fromChunkPos` takes the map type |
| §3.8 `avg_ms_per_column` | `encode_us_per_column`, `render_us_per_column`, plus `context=` | Separates decode-thread cost from JourneyMap-thread cost for the J4 throughput arm |
| §3.4 inert drop of queued columns | Other-dimension columns are REPORTED when a dimension-scoped inert state (ceiling, not allowed) clears the queue | Client stamps persist per dimension; a silent drop there is a permanent hole on the way back |
| §3.6 context cache keyed by position | Keyed by position, tagged with the map (dimension + directory) it was rendered into | A batch racing an identity change must never lend one map's terrain to another as context |
| §6.1 binding surface "resolve against the real jars" | Classfile-level check of every bound member, the expected surface CRACKED from the bindings resolved against the stubs (`MethodHandles.reflectAs`) | The real classes cannot link in a plain test JVM (JourneyMap subclasses `NativeImage`, final without JourneyMap's access widener), and resolving them through LSS's lookup records loader constraints that poison the stub-backed suites in the same JVM. The decoder parity test does load the real reader (reflection only, no constraints) |
| (new) | `release_check` forbids `journeymap/` and `xaero/` entries in every shipped jar | The Tier-1 stubs carry All Rights Reserved package names; they must never ship |
| (new) | The JourneyMap session gains a `SnapshotDecoder` seam | Tier 1 cannot construct a `ClientLevel`; the real decode path is the parity test's |
| §6.4 L1 at lod 64 | lod 24 over the base world (no generation; markers in served chunks at distance 12-22) | The base world is generated to 24: a lod-64 run is generation-bound (~15 col/s) and measures the server, not the map |
| §6.4 `-Dlss.soak.frameThrottleMs=1000` | `-Psoak.frameThrottle=MS[:SECONDS]` (a lift after SECONDS) | L2 needs the lift to show the map completing |
| §6.4 `modLocalRuntime` | `localRuntime` | 26.x is unobfuscated (no remap); the 1.21.x lines use the nested-jar pattern |
| §6.4 throughput arm at lod 256 | lod 128 over a 16×16-region real-terrain subset (`map-throughput`, SOAK_WORLD_FROM) | Converges inside the run, so the soak laws still evaluate; the rate is measured during the fill |

### 11.1 J4 live findings (26.2), each fixed and pinned

| Run | Found | Fix |
|---|---|---|
| L1 #1 | Client crash: binding with `Class::forName` ran JourneyMap's `DataCache` static initializer at LSS's client init, before JourneyMap was up; the class was poisoned and JourneyMap crashed on its first chunk load | Classes resolve WITHOUT initialization (`PRODUCTION_RESOLVER`); Tier-1 pin |
| L1 #2 | Every column `render_failed` → a whole-disc re-serve loop: JourneyMap's `TopoRenderer` refuses every chunk until its palettes load (only `updateOptions` loads them, and it is reached only after the refusal check); JourneyMap's own pipeline ignores the result | The DAY map decides acceptance; topography and biome are best effort (`optional_refusals`). User-visible limitation: JourneyMap's topography map receives no LOD terrain (the same is true of its own chunks) |
| L1 #2 | A permanent unmapped ring between JourneyMap's area and the LOD disc: JourneyMap maps only within `min(game render distance, its surface cap)`, vanilla (and so LSS's exclusion) reaches further | The edge sweep (2 s, LSS session only): renders JourneyMap's OWN live chunks in the band past its range, each chunk instance once |
| Throughput | On real terrain the fill collapsed to ~1 column per batch (334 rendered, 80 562 requeued): the batch decoded all 256 columns before rendering, decoding real terrain costs as much as rendering, the box expired, the requeue re-decoded | One column at a time (decode → add → render) under one time box, north/west first so in-batch neighbours shade; context decoded lazily per column |
| Throughput, after the per-column fix | 64 696 of 65 792 rendered, 0 failures, but 137 col/s against 689 col/s with no map (5×): ~1.8 ms decode + ~1.3 ms render per column on JourneyMap's thread, 50 % duty | Two changes inside JourneyMap's own norms: the batch box is 2 × renderDelay (its own mapping tasks may run 3) with a renderDelay/2 idle gap (its queued tasks still run between batches); the encoder leaves out sections wholly more than 64 blocks below the column's lowest surface (JourneyMap's surface maps never read them; deep-ocean floors sit inside the margin). Result: **255 col/s (peak 330), 0 failures, 0 re-serves, decode 1.07 ms + render 1.29 ms per column** — the map paces the LOD fill to ~2.7× slower than no map (U2). Follow-up, not built: decoding on an LSS worker ahead of the batch (JourneyMap itself decodes only on its own thread) |
| L1 #3 + world-id arm | PASS: 2075 columns, 0 failures, 0 re-serves, 152 ring chunks; with JourneyMap on the server the id-less directory got no LSS chunk (the 3 s settle absorbed the late id; replay/abandon 0/0) | — |

### 11.2 J5 implementation review (3 Opus: backend; Xaero memory + settings; tests/tooling) — dispositions

| Finding | Disposition |
|---|---|
| JM MAJOR: JourneyMap's own "mapping disabled" switch held the stream as `waiting(not_mapping)` | Fixed: `CoreProperties.mappingEnabled` bound; false → `inert(mapping_disabled)` |
| JM MAJOR: the late-world-id replay keyed on the previous identity, which JourneyMap's real stop→gap→restart sequence nulls | Fixed: replay keys on the directory each column went to; test with the real sequence |
| JM/T MAJOR: a null cache lookup (eviction) read as "live" → accepted undrawn | Fixed: null → requeue; evicting stub mode + test |
| T MAJOR: the smoke's log leg read the client log; JourneyMap logs to `journeymap/journeymap.log` | Fixed: both logs scanned and archived; selftest in JourneyMap's layout |
| X MAJOR (borderline): the 60 s pace box applied to stale/too-wide window and heap flags | Fixed: flags cleared on every pump that misses the drain; window-bound only when it held back every commit AND turned over within 60 s |
| JM MINOR: edge sweep could paint another dimension | Fixed: `ChunkMD.getDimension` checked |
| JM MINOR: wrappers pinned the old level after a disconnect | Fixed: `stopChunkRetention` on every wrapper we added |
| JM MINOR: render refusals never tripped the death latch | Fixed: a batch that rendered nothing while columns failed counts |
| JM MINOR: an encode straddling a session end reached the next session | Fixed: receipt re-checked under the queue lock; `buildBatch` closes inactive receipts |
| JM MINOR: an empty sweep cost a full idle gap (~20 % throughput) | Fixed: idle charged only when the sweep drew |
| JM MINOR: sweep inner radius ignored JourneyMap's surface cap | Fixed: `min(game, renderDistanceSurfaceMax)`; the band still reaches past the vanilla area |
| JM NITs (lease on encode throw, notAllowed queue, reconcile when disabled, spurious slow batch, context byte bound, stale identity after a dimension change) | Fixed |
| X MINOR: heap halt let owed regions park mid-rebuild | Fixed: keep-visited always (the J2 `tickFlush` overload reverted) |
| X MINOR: a granted load did not hold its window slot | Fixed: grants reserve their slot (30 s TTL until the loader starts); probe keeps loading (1) and loaded (2) regions |
| X MINOR: the window throttles focused fills below ~5.5 GB of heap (21 slots at 2 GB ≈ 350 col/s) | Accepted, measured by L2; documented in the performance notes |
| X MINOR: zero frames (hidden window) → 60 s halted / 10 s released; wedged-phase drops unreported | Accepted (plan-accepted behaviour); documented |
| S MINOR: a save lost alias-only values when both groups were partial | Fixed: merged leaves written into the canonical group; test |
| X/S NITs (heap operands, equal-value note, round-robin probe) | Fixed |
| S NIT: hand-trimmed files without the key arm map ingestion on upgrade | Documented with the U3 downgrade note |
| X NIT: G1 trigger point | Left to the L2 measurement |
| T MINOR: the production decode path (bound handles) never runs in Tier 1 | Accepted: it cannot link in a plain JVM; L1 exercises it |
| T MINOR: Xaero suite runs mostly without the window | Partly: new window/heap tests run with it armed |
| T MINOR/NITs (world-id dir match, RGB path, parity loader, cold base world) | Fixed |

### 11.3 J5 focused re-review of the fold (1 Opus, 2026-10-11) — dispositions

No MAJOR. Every fold was confirmed; JourneyMap's real neighbour lookups, Xaero's load states
(1 loading, 2 loaded, 3 releasing, 4 released) and the window's slot accounting were checked
against the decompiled sources.

| Finding | Disposition |
|---|---|
| MINOR: an invisible roof (barrier, light block) 64+ blocks up made the depth cull drop the ground JourneyMap walks down to — the day map refused the column | Fixed: the cull floor uses the lowest VISIBLE surface (air and invisible-shaped non-fluid blocks skipped, as JourneyMap's walk does); the written `WORLD_SURFACE` heightmap is unchanged |
| MINOR: `commits == 0` made a trickle into open regions read as a stuck writer and wedge | Fixed: window-bound = held work back AND turned over within the pace window |
| MINOR: a low JourneyMap surface cap made the edge band thousands of chunks wide, flushing JourneyMap's cache and re-rendering every sweep | Fixed: "already drawn" keys on the client's own `LevelChunk` (weakly held, read from the client level — no JourneyMap lookup), and at most 256 new lookups per sweep |
| MINOR: east/south batch neighbours were no longer cached at render time (topography slope/shore, biome blending) | Fixed: each column's east, south-west, south and south-east batch neighbours are decoded one step ahead; a look-ahead the box cuts off is released and requeued |
| MINOR: five failing one-column batches latched the session dead | Fixed: the streak also needs 32 failed columns (a thrown batch counts as 32) |
| NIT: a column whose wrapper is always evicted requeued forever | Fixed: reported for a re-serve after 8 null lookups |
| NITs: Xaero reservations (expired reservation counted as turnover; a load queued past its reservation no longer counted against grants; no refresh; a grant onto a committed region stayed committed) | Fixed: turnover only on a committed region or a parked/released state; in-flight loads outside the window reduce the grant budget; grants always restamp |
| NIT: JourneyMap's heap hold survived pumps that never reached the belt | Fixed: cleared at the top of every pump |
| NIT: JourneyMap's server-side surface cap is not consulted by the edge sweep | Accepted: the LOD columns themselves are mapped regardless of any radius; the server's "mapping not allowed" switch is honoured |
| Test gaps (neighbour presence, look-ahead release, edge lookups, latch size, trickle, reservations, invisible roof) | Pinned |
