# Full-feature DH fixes: normal and live validation

Date: 2026-10-07 (local). Local MC1.21.1 validation following
[the full-feature review disposition](2026-10-07-dh-full-feature-review-disposition.md).
The user restored unrestricted build/network access and requested full validation.
Performance measurements remain deferred; the host is available for games.

**Subsequent trace update:** the [r7 trace fix](2026-10-07-dh-trace.md) adds the
missing DH event hooks, shortens the “Use DH” tooltip and removes the redundant
`/lss dhstatus` alias. Its separately tested jar is now installed; use `/lss diag`.
The counts, artifacts and broad content comparisons below remain the r6 evidence.

## Normal gates

Java21 normal Gradle build, both server gametest suites, all six LSS/VSS packaging
checks, artifact isolation and release self-tests pass. This replaces the earlier
direct-compilation-only boundary for this candidate. No Tier3 or Folia task exists
on this line.

| Module | Passing unit/observer tests |
| --- | ---: |
| Common | 1026 |
| Fabric | 1780, including four test-only observer cases |
| Paper | 536 |
| NeoForge | 23 |
| Total | 3365 |

Four optional Fabric experiment cases skip on their normal assumptions. The real
dedicated-server suites pass78 Fabric and8 NeoForge gametests. All six branded
shipping-family jars pass the expanded recursive/multi-release release checker and
the separate fixture-class isolation checker. Release self-tests pass126 cases,
with9 SQLite helper tests; the Python mutation/ownership/SQLite suite passes15 and
the P4 guard/metric suite passes12 after the monitor correction below.

Normal build copy: `/tmp/lss-dh-full-review-20261007-r6`.
Version: `0.16.0-dh-full.dev20261007.6`.

| Loader | Candidate SHA256 |
| --- | --- |
| NeoForge | `cfc752cb2ae6affdb96b01832a4eb2eb439ee5b8277655d490a61839399e7fb3` |
| Fabric | `b6e5dceb71f58cf30955c7c9afba0b346bf0b32e0f0b61ad7360066a5c754e39` |

Evidence root:

```text
/home/vox/projects/voxel-server-support/build/dh-neoforge/2026-10-07/full-review-validation/
```

The manifests bind current production/build inputs, tests, corpus, release checker,
packaging tools and rig sources to this build. Observer jars are separately rebuilt
against the matching loader artifact. XML, build logs, fixture receipts and immutable
run directories are retained there.

## Functional checks

All14 fresh functional cells pass:

- NeoForge lifecycle: actual terrain readback/rendering, movement, reset, hot sync
  on load without periodic receipt timers,45-second disabled observation, reception
  off/on, Nether/End no-sky visits, Overworld return and source switching.
- NeoForge DH Support15:73,728 storage samples,32 checked columns and zero
  negotiation reconnects.
- NeoForge official15:50,688 storage samples,22 checked columns and one guarded
  legacy negotiation reconnect.
- Fabric official16:17,856 storage samples,15 checked columns and zero reconnects.
- Native LSS with DH enabled: native protocol20, zero DH terrain requests,
 172.48 blocks of actual movement over30.82 seconds,657 independent stone
 comparisons and no missing sampled Voxy border columns. The effective vanilla
 radius is8; requested radius12 is retained separately.
- Native Windows legacy Sodium0.6.13: all seven pages, the three DH checkboxes,
  dependency handling, meaningful Apply/save, visible selected tabs/arrows and
  donation/control intersections. Auto-scale sizes427×240 (1280×720),426×240
  (1278×720),342×256 (1024×768),341×256 (1023×768) and320×256 (1280×1024) pass.
  Minecraft rounds scaled dimensions up; the old426/341 labels for1280/1024
  pixels were inaccurate. The default screenshot was visually inspected and is
  readable. Each Windows run uses an owned non-input desktop; no foreground
  input, desktop switch or clipboard operation occurs.

Official16 with sky, official16 without sky, and Support15 content comparisons
pass6,590,848 voxel checks across levels0–4, with zero block/biome/light differences.
The official cells use a512-height scene; Support uses384. Each scene covers four
biome quadrants; the official material set includes water/kelp, leaves, glass,
stairs and torch/sea-lantern light. This remains one raw64×64 tile per cell.

The range-128 run exercises actual Overworld/Nether/End acquisition and retirement,
then injects8384 representative records into three owned source buckets. The quota
retains6417 files/268,419,608 bytes under the256MiB limit. These are explicitly
synthetic pressure records, not thousands of downloaded server tiles. After a real
disconnect, the observer requires a different physical connection and proves one
source-cache hit with16 current-readback Voxy columns reused. It does not prove a
full-radius fill or a new performance result.

## Manual handoff

The tested NeoForge jar is installed in **LSS dev —1.21.1 NeoForge - Copy For Far
Testing**. Its prior r5 jar is backed up at
`minecraft/lss-dh-backups/20261008T003519.671998Z` inside that instance. The installed
SHA256 is rechecked; preferences and all other mods are preserved. No foreground
client was launched.

The original **dh-neoforge-playable** world is resumed at **[::1]:25585**, with OP
and server settings preserved. Read-time owner checks are ready/reachable, fresh
owned-console `list`/`save-all flush` acknowledgements pass, and the Minecraft
status handshake reports protocol767. Generic manual-server storage admission was
not lowered. All private Linux owners are retired; all five private Windows Java
owners are verified retired by creation identity. Only the restored manual server
remains running.

Exact sources, artifacts, logs, failed attempts, semantic proofs, screenshots,
ownership and installation/restoration receipts are bound in
[the final verification record](evidence/2026-10-07-dh-full-feature-validation.json).

## Preserved first attempts

The first normal build passed78 Fabric gametests, then its Fabric test executor
ran out of heap. The expanded movement matrix retained thousands of Mockito chunk
handlers/invocations. Stub-only chunk doubles and explicit release after each
scenario fix the fixture with no heap increase and no removed movement assertion.
The focused14-case test and the full suite then pass. The original log and test
results remain retained; this is documented in the flake/environment catalog.

The first live lifecycle attempt exposed obsolete observer premises, rather than
a product timer regression: it expected the removed receipt timer and applied
sync false/true before the first asynchronous reload completed. The corrected
observer waits for the actual applied/sent false preference before enabling it,
requires conditional unchanged replies on the new load observation, and asserts
that settled snapshots have no periodic timer. The first runner was stopped only
after verifying its boot/start identity; its owners are confirmed retired. The
new immutable attempt passes, including six conditional requests, three unchanged
replies and twelve accepted snapshots before disable.

The first range attempt stopped in the bounded monitor's `du -sb` scan, without a
game assertion or crash. Its stderr was not retained by the subprocess wrapper.
A standalone same-command reproduction during atomic `.pending` replacements
produces three exit1 failures for vanished entries, while a stable tree scans
successfully. The monitor now scans logical bytes without following links, counts
hard links once, and tolerates only disappeared children. Missing roots, permission
errors, other IO errors and the20-second scan deadline still fail closed. The
physical50GiB reserve,2GiB growth cap and observation cadence are unchanged. Four
new regression cases preserve those distinctions; the new immutable run is separate
from the failed one.

## Boundaries

**Accepted limitation (2026-10-07):** the user accepts the supported Voxy API's
inability to cancel or fence an already enqueued write, or acknowledge per-write
completion/persistence. An older queued DH write can still replace newer vanilla
LOD terrain. This is no longer a release blocker; the strict guarantee remains
unproved. [Dated acceptance and scope](2026-10-07-dh-accepted-limitations.md).
Historical validation receipts retain their pre-acceptance `release_ready=false`
assessment; accepting this risk does not complete the other release prerequisites.
The content oracle independently expands conversion expectations but shares the
production decoder and uses Voxy's reducer for higher levels.

These are functional checks, not a new performance study, full-radius saturation
or general proxy/transfer qualification. Larger warm measurements, Fabric
performance, uncapped frame studies, decoder fuzzing, additional producer/mod/biome
combinations, other MC lines and upstream outreach remain pending. No merge,
publication or release is implied by this local validation.
