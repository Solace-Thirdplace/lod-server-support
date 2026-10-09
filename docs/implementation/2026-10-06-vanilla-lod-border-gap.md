# Cold-join vanilla / LOD border coverage

Date: 2026-10-06. Local MC 1.21.1 Fabric/NeoForge development; no publication.

## Reproduction and cause

The old packaged NeoForge client has 62 missing Voxy columns among 71 loaded
vanilla columns outside Sodium's drawing radius after 36 seconds stationary.
All 71 have vanilla stone and sky-light data. The independent fixture reads nine
stone samples per column (639 total); absent/default Voxy sections fail the check.
The requested client render distance is 12 and the server's effective distance is 8.
The fixture uses a new world and an empty Voxy cache, not the personal Prism world.

Installed Sodium and Voxy use the same camera-relative, block-space drawing test.
`ChunkTrackingView`'s buffered chunk-delivery circle and the client cache extend
beyond that drawing boundary. The native scanner's pinned exclusion avoids asks
inside the delivery circle; DH correctly protects every received vanilla column.
Being received does not prove that Voxy has ingested it. Voxy's Sodium-driven
chunk-added/upload hooks do not ensure coverage of this whole fringe, and its
whole-chunk producer can reject an initial attempt before lighting/world readiness.
The gap is missing border storage, not a reason to allow coarse DH writes over vanilla.

The installed Voxy SHA256 is
`024d58076649dbd348ad75c3778f90916ec35b7b9a1196b0662823a72f071a49`.
The baseline candidate is
`6d7fe2d8da5301a7c9593cec8a68eff7a9b58d8150430554624a3424f932ad13`.
Raw bytecode and shaders were inspected; the external source checkouts differ
from this installed jar and are not treated as its exact source provenance.

## Fix

`VoxyVanillaCoverage`, pumped by the existing shared client tick, supplements the
loaded border with the current vanilla chunk through Voxy's whole-chunk producer.
It uses effective distance and camera coordinates, including a one-chunk inward
band. This repairs received/unrendered corners without changing the native scan
shape, renderer clipping, DH authority, remote timestamps or wire protocol.

The initial pump checked at most 64 grid positions per tick and used a 125 ms
cooldown (6.67 submissions/s at 20 TPS). That historical bound did not keep up
with movement. The current pump does at most 128 actual chunk lookups per tick,
has 256 submissions/s credit with at most 16 submissions per tick, pauses above
2048 queued Voxy ingest jobs (above the DH adapter's 1024 gate, so vanilla border
terrain never waits behind DH approximations), and retries declined nonempty
chunks after one second. Since 2026-10-10 it runs only while a native or DH terrain
session is active and `lod.voxy_border_fill` is on. It only calls cached `getChunk(..., FULL, false)`; it never loads or
generates terrain. Weak references avoid retaining unloaded chunks. Maps are bounded
and pruned on movement/range changes, with retirement on world/engine replacement,
disconnect, reset or reception Off. Submitted chunks do not repeat merely because
time passes. The same path serves native LSS and DH on both loaders.

The optional API domain respects Voxy's per-world ingest-enabled policy. Missing
optional methods or a failed supplemental bridge leave ordinary raw ingestion
active. Entirely empty columns are attempted once: the supported producer queues
all-air/no-light clears but reports false afterward, so treating that result like
ordinary lighting rejection would cause repeated empty writes. No successful remote
receipt or persistence acknowledgement is inferred from this special case.

## Verification

The initial candidate passes 3,315 unit tests, 78 Fabric and eight NeoForge server
tests, all six LSS/VSS packaging checks and release self-tests. Packaged private
clients in **both native LSS and DH modes** have zero missing columns among the same
71-column fringe, with 639 independent block comparisons each. Those exact artifact
results are preserved in `first-passing-candidate/`; the baseline is explicitly a
reproduced product failure, not a correctness pass.

The final candidate adds per-world ingest-policy and empty-clear protections.
It passes **3,316 unit tests** (four skips), **78 Fabric and eight NeoForge server
tests**, all six LSS/VSS packaging checks, 126 release self-tests and nine SQLite
helper tests. Its packaged private clients pass the same stationary fringe test in
**both native LSS and official DH modes**: 71 columns, zero missing Voxy columns,
639 independent block comparisons per mode. The final artifact SHA256 is
`1dec0001b839aa241cb1a8ac0428a8f3937c15b9b01b4910a3265ebb721f1cb1`,
version `0.16.0-dh-boundary.dev20261006`.

The ten initial regressions cover geometry/overlap, late readiness, current chunk identity, no
speculative loads, backpressure, no repeated successful writes, world/engine reset,
pruning, optional ABI failure and reflective invocation. An additional regression
pins the empty-clear behavior. Existing authority/completion limitations are unchanged.

The same final jar also passes the supplemental packaged NeoForge unchanged-rejoin,
push-only edit/removal, live-update Off/On and actual Sodium Auto-scale case, with
1,200 derived block comparisons (the historical `samples × 12` formula). That fixture's earlier failed premises and
test-only corrections are retained separately; they did not change the production
jar or the already completed boundary results. Before/after boundary screenshots
and the final Sodium screenshot were inspected.

The selected Windows **LSS dev — 1.21.1 NeoForge - Copy For Far Testing** instance
now contains the final jar, with a dated backup and unchanged preferences/other
mods. The existing **dh-neoforge-playable** world is resumed at **[::1]:25585**.
Fresh current-owner list/save acknowledgements, Minecraft protocol 767 and Windows
IPv6 TCP pass; OP and generation32/SURFACE_THEN_CHUNKS are retained. All 36 recorded
private process/display identities are retired. The
[manual guide](2026-10-05-dh-manual-test-guide.md#initial-join-border-retest)
starts with a stationary initial-join border retest; no reset is needed.

Evidence is in the outer checkout's
`build/dh-neoforge/2026-10-06/vanilla-boundary/`. No Windows foreground automation,
personal world reset, other-mod replacement or automatic evidence upload occurs.

The follow-up retains its sweep in absolute world coordinates across recentering,
queues client chunk-load events and avoids resubmitting accepted chunk identities.
A relative cursor, even without resetting it, skipped rows as the camera moved;
the moving regression caught that before deployment. Unit cases cover sprint at
RD12/RD32 and 30-block/s flight at RD12, with a three-second continuously exposed
border bound after cold fill. Transient Voxy exceptions defer the pump; only ABI
linkage errors disable this optional bridge for the launch. The pump also covers
loaded vanilla fringe on single-player and servers without LSS. All its content
comes from current vanilla chunks. Partial-light and late queued remote-write
authority remain unqualified beyond the separate I1 boundary. See the
[follow-up disposition](2026-10-06-dh-follow-up-review-disposition.md) for the actual
packaged moving-client results and current manual setup.
