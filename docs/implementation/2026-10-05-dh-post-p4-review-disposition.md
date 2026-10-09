# DH post-P4 review disposition

Date: 2026-10-05. Source checkout: `.worktrees/dh-client-p1`, branch
`feat/dh-codec-p1-mc1.21.1`. This addresses the
[Claude review](2026-10-05-dh-post-p4-claude-review.md) against the last packaged
candidate, `/tmp/lss-dh-voxy-cache-20261005`. The changes are shared by Fabric and
NeoForge 1.21.1. Work remains local; outreach is pending.

The corrected candidate is now built, tested and **installed for manual play**.
The normal Java-21 gauntlet passes **3308 tests** (four opt-in Fabric skips), all
78 Fabric/eight NeoForge server gametests, six LSS/VSS packaging checks, 126
packaging selftests and nine SQLite helper tests. Five fresh packaged live cells
pass: both reconnect sequences, NeoForge lifecycle, and ocean light on both loaders.
The earlier 204-case standalone run remains historical preliminary evidence.

The new Windows check demonstrated that junctions pass `isDirectory(NOFOLLOW)`
and are not symbolic links, but report `BasicFileAttributes.isOther()`. The source
cache now refuses those reparse directories in roots, adoption and quota walks,
including cache-root clearing. The first normal candidate and failed junction
premise are preserved under `before-ntfs-junction-fix/`; they are not the installed
candidate. The replacement passes the full gauntlet and native Windows regular IO,
atomic replacement, foreign-file preservation, the 121/120 quota control, junction
refusal and a 428-character cache path.

The installed NeoForge jar SHA256 is
`d0acf45774c9d61291cc2f0ff9b123dc9e2787cf0bd80a480bc1423d80be41dd`.
The selected Prism instance's previous jar is backed up at
`minecraft/lss-dh-backups/20261005T221212.874377Z`; its configurations and instance
preferences are byte-identical. The same manual world is running at `[::1]:25585`,
with OP preserved, generation bounded to 32 chunks and synchronization enabled.
Windows IPv6 TCP, protocol 767, FIFO console/save and survival after launcher exit
pass. No foreground Minecraft client was launched. Strict release/I1 gates remain open.

## Verified findings

| Review item | Correction and evidence |
| --- | --- |
| H-1 | Picked the review's preserve-on-mismatch option. Unchanged/offline cached restores reuse matches, preserve differing nondefault columns and reconstruct only fully default columns. Unavailable/failed readers defer, with status, rather than overwrite unknown content. `unchangedRestorePreservesPreviouslyVanillaRefinedDestinationAcrossDisconnect` performs vanilla ownership in connection 1, disconnects and asserts zero ingests against a different destination. Default-loss and unavailable-reader tests pass. Both fresh packaged loaders pass a real vanilla-visit/digest-preservation stage with zero target conversions. Changed/full network replies still have the separate I1 limitation below. |
| H-2 | 8192-file count headroom (256-MiB byte cap unchanged), shared quota index, LRU touches, other-bucket-first eviction, and protection of pending working-set files. Successful restores release their pins for further admissions. The 121/120 single-bucket cascade keeps 120 restores instead of zero; the same-address second-dimension pressure test retains at least 100 restores. A full protected quota may decline the new admission; it cannot evict the next pending restore. Tests also pin admission after protection is released. Real range-128/NTFS performance remains unmeasured. |
| M-1 | Remove the older source before every newer complete/partial response, absent full reply, and replacement admission. Failed replacement cannot retain its predecessor; successful-write accounting uses a boolean result. If old-file invalidation itself fails, stop DH before delivering newer data, keeping ordinary Minecraft connected. Forced AccessDenied replacement, partial response, absence and rejected-admission regressions pass. Real antivirus/disk-full behavior awaits Windows tests. |
| M-2 | Validate cached source timestamps before any Voxy conversion/ingest. Empty owned replies restore/compare; changed replies deliver only new content. The actual scanner with 100 cached candidates sends the first check before any restore, without waiting for a sweep. Request admission rechecks synchronization rate/permits after worker preparation; one cache read in flight does not block scanning other work. Per-column main-thread checks remain to guard vanilla authority; a fused whole-tile reader/handoff and full-range cost benchmark are deferred. No 35-minute or zero-IO claim is made. |
| M-3 | Accepted the review's minimum correction: partial tiles remain uncached and re-download. Documentation and corrected historical receipts explicitly limit zero payload/conversion claims to the complete target. The historical final run downloaded thirteen other full tiles per rejoin. Partial caching is not implemented or claimed. |
| M-4 | A receipt marked for one-shot revalidation retains that obligation across refresh interval changes, including Off. The interval-flip regression passes. New reconnect validation is scheduled before acceptance, avoiding the separate accepted/revalidate callback race. |
| M-5 | Verified pid 100350 is gone. Replaced terminal-dependent launch with a detached supervisor, disabled JLine/ANSI, retained FIFO console input and recorded exact owner identity. Liveness is checked at read time with `stop-server.py --status-json`, including cwd/argfile and TCP. The stale source-only ready claim was corrected first; the latest guide/receipt now bind an actually resumed server. Bash/Python syntax, detached FIFO mechanism and the actual resumed server/console/save check pass; the server survives launcher exit. |
| L-1 | Apply fluid-depth attenuation to resolved nonempty fluid states, including kelp/seagrass and flowing water. The expanded frozen producer regression checks over 7000 adjacent fluid/floor pairs, with explicit kelp/seagrass premises. Existing block-light/no-sky tests pass. New packaged live plant-light validation remains pending. |
| L-2 | Memory pressure and client-thread timeout retain valid files and reschedule bounded local tile retries. The sixty-second cache deadline cancels only the cache task, keeps the position and cools that tile, even when Voxy is not ready. Index timeout leaves network acquisition available. Budget-pressure and deadline regressions pass; no session-wide cache-timeout failure remains. |
| L-3 | Disable source reuse for unavailable or zero seed identity. Account fresh pending temporary files in quota; sweep old pending files on index rebuild. Identity and file-hygiene regressions pass. |
| L-4 | Count matches/preserved columns only after the second authority check succeeds. A test introduces vanilla ownership between checks and verifies one skip, fifteen preserved columns and no ingest; the skipped column is not counted as reuse. |

## Suspected risks checked and addressed

- **Checksum-valid undecodable sources:** invalidate/remove and fall back to full
  acquisition without stopping the session. Resource-limit errors remain transient.
  File/identity VERSION binds decoder compatibility; its comment requires a bump
  for incompatible decoder semantics. The undecodable-source test passes.
- **Clearing composition:** remote DH clearcache clears only the current address.
  Global clear continues native buckets despite a source-root symlink or one failed
  bucket, skips both source namespaces, and preserves foreign source contents.
  Actual command/current-address and composite-clearing regressions pass.
- **Single-player/reset and late workers:** absence of remote identity is a no-op
  for remote cache invalidation. Reset refuses to proceed until every previously
  retired worker really terminates, including an interrupt-ignoring worker. It
  aborts before touching the replacement session. The tests pass; real IO hangs
  remain subject to the bounded refusal rather than pretending reset succeeded.
- **Timestamp zero / write counters:** a valid zero-time source is rechecked with a
  full request, not treated as an unchanged receipt; absent reply removes it.
  Negative/oversized source admission invalidates the old entry and returns false.
  LRU metadata failure on an otherwise accepted unchanged reply reports cache
  unavailability without stopping DH; its forced-access-denial regression passes.
- **Identity privacy/namespace:** SHA hashes are guessable, not anonymization.
  `!dh-terrain-v1` cannot collide with the native sanitizer's server bucket names.
  Adoption moves only owned legacy hash buckets, preserving foreign/native files.
  Failed symlink access is visible in cache status. A native Windows junction bypass was demonstrated and fixed using reparse
  attributes. Root refusal and regular/long-path IO pass on the selected Java runtime;
  antivirus locks and arbitrary junction race scenarios remain untested.
- **Voxy references/ABI:** mapper snapshot/open and each worker column read use
  short references; references do not span client-thread waits. Cleanup is
  nonthrowing. Destination identity is rechecked before comparison acceptance or
  restore handoff so a replaced engine cannot create a receipt for another one.
  Unavailable readers/mapping caps and disabled server sync are reported. Tests
  cover references, replacement, timeout/interruption and safety fallback.

Optional destination reads can still initialize missing Voxy sections in Voxy's
own LRU; LSS accounts retained copies, not Voxy's internal allocations. Storage
load errors may appear as defaults in that upstream API. Mapper snapshot cost,
repeated section copying, derived mips and large-cache frame/worker cost require
measurement. These are qualification/performance risks, not a proved fix here.

## Evidence corrections and validation boundary

Evidence lives under the outer checkout's
`build/dh-neoforge/2026-10-05/post-p4-review-fixes/`.
The [checkpoint](evidence/2026-10-05-dh-post-p4-review-fixes-checkpoint.json) binds
production/test manifests, changed source and the selected-run evidence. The
scoped diff compares this fix increment against the last packaged source snapshot,
not the broad staged Git feature diff.

The first sandboxed run selected all `common.dh` suites plus session, converter,
destination-reader and native-cache suites: 204 passed. Its source-only checkpoint
and exact bound inputs are preserved in `source-only-checkpoint-before-unrestricted/`.
The broad standalone attempt's seven failures and the original wrapper/socket
failures remain archived as described below; they do not describe the current
normal build or environment.

The normal build used Java 21 and the intended version in the frozen source copy,
with the fixture test source set kept outside production. XML recount: Common
1012, Fabric 1737 passed/four skipped (includes four fixture tests), Paper 536,
NeoForge 23. Both gametest sets and all packaging/selftests pass. After the NTFS
finding, the replacement normal gauntlet passed again; counts are per final suite,
not an addition of repeated attempts. Old candidate/observer/source/XML inputs are
retained under `before-ntfs-junction-fix/`.

Each fresh packaged reconnect cell makes 2400 independent known-block comparisons
and passes the real vanilla-visit digest preservation, unchanged rejoin, offline
edit, Voxy loss, new JVM, reset and source loss stages with refresh Off. Voxy loss
reconstructs sixteen target columns; unchanged rejoin/JVM/vanilla-visit stages
convert zero. These payload claims remain target-scoped. Both ocean cells read 450
deep and 450 shallow light samples using the independent floor-run oracle. The
NeoForge lifecycle cell passes movement, reset, refresh/receive toggles, Nether/End,
portal return, source switch and ordinary connection survival. Every cell binds its
actual installed candidate and observer SHA256. Software rendering does not measure
Windows GPU frame time. Full-content comparison remains a production premise.

Native Windows filesystem results are in `ntfs-probe.json`; its owned temporary
controls test structural source IO, not terrain content. First-run `refused:false`
on a junction is retained beside the failing candidate. Final `refused:true`,
atomic replacement/readback, foreign preservation, 120 retained entries at a
120/121 count boundary and long-path IO pass. No antivirus concurrency claim is made.

Historical original receipts are preserved; separately named
`ocean-lighting/verification-corrected.json`, `voxy-cache-reuse/verification-corrected.json`
and `voxy-cache-reuse/fabric/verification-corrected.json` carry these audit corrections:

- Each ocean row binds its actual candidate **and installed run mod**: Fabric
  `01bce007…`, NeoForge `885e22e5…`. The original top-level hash was NeoForge-only.
- `full_destination_content_verified` is labelled `production_comparison_premise`.
  Only 300 known-block samples per stage were independent, with no full biome/light
  or completion oracle. Counts and original failing evidence are preserved.
- Partial/missing neighbor downloads are disclosed. The failed first Fabric Support
  cell (`Insufficient solid readback: 0 columns`) is now disclosed in the continuation
  prose and remains excluded from its passing cells.
- Historical ready receipts are timestamps, not current liveness. The new setup
  receipt and manual guide bind the current running server.

## Manual setup and remaining qualification

`verification.json`, `fabric/verification.json`, `live-matrix.json`,
`prism-installation.json` and `test-setup-completed.json` bind the installed
candidate, passing cases and resumed manual setup. `resume-deployment.md` preserves
the earlier prepared sequence; deployment is now complete. Read-time liveness is
available from `stop-server.py --status-json`. The source copy remains
`/tmp/lss-dh-postp4-fixes-20261005`, version `0.16.0-dh-postp4fix.dev20261005`.

Range-128 real-client quota/load benchmarking, multi-dimension cache pressure,
real antivirus/disk-full replacement, sync-disabled offline edits and new packaged
kelp/seagrass readback remain qualification work. The fixed quota/failed-write/
fluid cases have permanent source/unit controls, but no live result is substituted
for those remaining cells. Derived mips, official-client matched load, Windows GPU
frame times, other support lines and upstream outreach remain open.

Strict P2 completion and I1 remain open: Voxy's raw ingest acknowledges enqueue,
not completion, and already queued DH writes can overwrite newer vanilla data.
Unchanged-cache preservation fixes the added reconnect regression; it is not a
persistent vanilla ledger or a fix for newer/full network payloads. The plan and
manual docs warn about those remaining overwrite paths. The current candidate is
ready for supervised local manual testing, not a completed release milestone.
