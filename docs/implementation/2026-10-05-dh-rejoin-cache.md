# DH rejoin source cache

Date: 2026-10-05. User-authorized local MC1.21.1 development on Fabric and NeoForge.

The later [Voxy cache reuse correction](2026-10-05-dh-voxy-cache-reuse.md)
supersedes unconditional replay below. Matching destination columns now reuse
Voxy's stored content; this record preserves the original source-cache checkpoint.

**Historical record:** behavior, limits, installation and test results below describe
the first source-cache candidate. The [post-review disposition](2026-10-05-dh-post-p4-review-disposition.md)
supersedes restore-before-validation, the old quota/namespace, mismatch rebuilding
and setup status. Raw address hashing is not anonymization; the same manual server has now resumed with the corrected client installed. Do not use this historical installation receipt as liveness evidence.

Official DH retains local terrain and checks its timestamp when loading it on a
new connection. Missing terrain uses a separate retrieval path. Changed terrain
downloads; an unchanged reply contains no terrain. Verification used the exact
official DH 3.3.3 jar installed on the test server and its `javap` output, with
current primary source corroboration at commit
`f5788b79f0cc4d751a6c1c7d759ac926df917206`:
[RemoteFullDataSourceProvider](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/core/src/main/java/com/seibel/distanthorizons/core/file/fullDatafile/RemoteFullDataSourceProvider.java),
[SyncOnLoadRequestQueue](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/blob/f5788b79f0cc4d751a6c1c7d759ac926df917206/core/src/main/java/com/seibel/distanthorizons/core/multiplayer/client/SyncOnLoadRequestQueue.java).
The current source snapshot is corroboration, not a claim that its commit produced
the 3.3.3 artifact. Exact artifact and source hashes remain in the evidence directory.

## Behavior

The client saves complete, validated raw DH tiles independently of Voxy storage.
On reconnect, it reconstructs eligible distant columns from those tiles with the
current converter, preserving vanilla authority checks. It then submits one
timestamp check per restored tile if the server enables synchronization. This
works with periodic refresh **Off**. Continuous refresh still follows the existing
setting. Servers with synchronization disabled can reuse locally cached source
terrain without conditional requests.

Unchanged replies require no full terrain download. Offline edits download a new
tile and replace the cached source. Missing, evicted, corrupt or incompatible
source data falls back to a full request. Existing Voxy terrain from before this
feature has no matching raw backup, so the first connection using this build must
fill the new cache. Later rejoins can reuse it.

This stores source data, not durable Voxy acceptance claims. A Voxy cache wipe can
be recovered by replaying source data. A failed or partial Voxy delivery never
creates a valid conditional acceptance receipt; partial upstream coverage is not
saved as a complete source tile. All leases remain bounded and epoch-owned.
Explicit `/lss reset` and `/lss clearcache` erase retained source data before
acquisition resumes. Ordinary native timestamp invalidation preserves it.

## Cache ownership

Files live under the adopted LSS/VSS cache root's `dh-terrain-v1` directory.
Keys include exact remote address, DH server/world keys, vanilla dimension,
Minecraft biome-zoom seed, DH profile and world height/skylight shape. Unavailable
seed identity disables reuse. Hashes keep raw identities out of filenames.

Each version-1 file has a 92-byte header containing magic, version, identity hash,
position, original server timestamp, payload length and payload SHA256. Decoding
also verifies the DTO position, complete coverage and timestamp. Original server
DTO timestamps are already in server clock units; unlike official DH's locally
updated database timestamps, these must not receive another server-time offset.

Writes are forced and atomically replaced. A global quota across all source
buckets retains at most 4096 tiles / 256 MiB, evicting the oldest writes. Eviction
only causes re-fetching. Reads, writes and resets serialize on worker IO;
the Minecraft thread waits only for the existing bounded reset operation.
Symlinked roots/buckets are refused, and cache clearing owns only its named files.

## Validation

The normal Java-21 build `0.16.0-dh-rejoin.dev20261005` passes 3271 unit tests,
with four existing Fabric opt-in skips, all 78 Fabric and eight NeoForge required
server gametests, all six LSS/VSS packaging checks, 126 packaging selftests and
nine SQLite helper tests. New cases cover persistence without Voxy, all identity
axes, corrupt/truncated/unsupported files, quotas, symlink containment, rejoin
checks with refresh Off, timestamp invalidation after failures, partial coverage,
source/header timestamp mismatch, and reset versus native invalidation.

Exact packaged **NeoForge and Fabric** clients pass against official DH 3.3.3.
Each makes 2100 independent known-block comparisons in actual Voxy storage, in
a dimension spanning Y=-128 to 383. The seven stages verify cold acquisition,
unchanged rejoin, offline edits, Voxy disk/memory loss, a new Minecraft JVM,
explicit LSS reset, and deletion of the target source tile. Unchanged rejoin,
Voxy loss and process restart receive **zero full target terrain payloads**;
offline edits receive a changed reply to the old timestamp. Reset/source loss
produce timestamp-zero full requests. All run with continuous refresh Off.

The first private run was deliberately aborted because its upstream DTO contained
only air/stone/bedrock: the asynchronous startup export preceded console seeding.
Its raw bytes and classification are retained. The revised fixture primes the
source after DH is live, without changing production code or weakening the
independent block oracle. A subsequent preflight hit the stopped server's TCP
TIME_WAIT; the private availability probe now uses SO_REUSEADDR. Initial compile
and test-helper failures and the packaging rejection of copied old build jars
are also retained. Final verification binds the passing runs to exact source,
observer and candidate hashes.

The tested NeoForge jar SHA256 is
`a0bd5026e8a97ccbcd26b88a98e087c2b1d656661cc9d0b90c7522aac10d1ddb`.
It is installed in the selected Windows Prism far-testing instance, preserving
preferences byte-for-byte. The previous ocean-lighting jar is backed up under
`minecraft/lss-dh-backups/20261005T182143.197689Z`.
The [checkpoint](evidence/2026-10-05-dh-rejoin-cache-checkpoint.json) binds the
final verification and resumed manual setup; the
[manual guide](2026-10-05-dh-manual-test-guide.md) describes the reconnect check.

Evidence directory in the outer checkout:
`build/dh-neoforge/2026-10-05/rejoin-cache/`.
The existing [Voxy commit gap](2026-10-05-dh-voxy-commit-gap.md) remains open;
this feature does not establish a durable Voxy completion signal or close strict
P2/P4 release gates.
