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
| The edge sweep renders JourneyMap's own live chunks between its mapping range and game render distance + 3 | JourneyMap maps only within its own range; LSS's exclusion follows vanilla, which reaches further, so a permanent ring stayed unmapped | session edge tests, the L1 checker's ring leg |
| One column at a time on JourneyMap's task thread, (z, x) order, a 2 × renderDelay batch box and a renderDelay/2 idle gap | Decode-all-first livelocked on real terrain; the box and gap stay inside JourneyMap's own scheduling norms, so its tasks still run between batches | session pacing tests; `map-throughput` soak |
| The encoder omits sections wholly more than 64 blocks below the column's lowest surface | JourneyMap's surface maps never read them; this cut decode cost per column from about 1.8 ms to 1.07 ms | the encoder cull test |
| Every receipt closes exactly once: accepted after the day render, otherwise reported for re-serve | Client stamps persist per dimension; a silent drop is a permanent hole | session receipt tests |
| JourneyMap older than 6.0.0 is unsupported (1.21.10 ships only the 6.0.0 beta) | The bound members differ | binding-surface tests per line |

## Measured

On a 26.2 dev client over real terrain (`map-throughput`, lod 128): about 690 columns/s with no map, 255 columns/s with JourneyMap (peak 330), decode 1.07 ms plus render 1.29 ms per column, no failures and no re-serves. Not built: decoding on an LSS worker ahead of the batch (JourneyMap decodes only on its own thread).

## Live gates

`scripts/journeymap_smoke.sh` (client mode, and `world-id` with JourneyMap on the server for the late world-id race) judges the map JourneyMap wrote with `check_journeymap_smoke.py`. The `map-throughput` and `xaero-memory` soaks need a real-terrain `SOAK_WORLD_FROM` and the `-Plss.journeymapRuntime=true` or `-Plss.xaeroRuntime=true` client lever.
