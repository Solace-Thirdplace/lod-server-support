# Reconnect using Voxy's existing terrain

Date: 2026-10-05. Updated after the [independent review](2026-10-05-dh-post-p4-claude-review.md).
The [review disposition](2026-10-05-dh-post-p4-review-disposition.md) is the latest
source and validation checkpoint. The corrected candidate is now built, tested
and installed in Prism, and the same manual server is running. Earlier blocked
source-only receipts remain historical.

## Current source behavior

When server synchronization is enabled with a positive rate, a cached complete
DH source tile is decoded and validated, then used to send a conditional timestamp
request **before any destination restore**. An unchanged response reads and
compares Voxy's level-zero block states, quart-center biomes and sky/block light.
A matching column needs no conversion or ingest. A different, nondefault column
is preserved: it might contain better terrain from an earlier vanilla visit.
Only an entirely default column (air with default skylight throughout the world
height) is reconstructed from unchanged source data. This also recovers a complete
Voxy cache wipe. A partially populated or damaged column is preserved, rather
than treating a difference as permission to downgrade explored terrain.

Changed server replies follow the normal network conversion/ingest path. Every
newer response invalidates the prior source file before replacement, including
partial coverage, absence on a full request and failed writes. Partial tiles
remain uncached and are downloaded again on later connections. Thus zero payloads
for a complete test target never implies zero downloads for the entire session.
The historical final run downloaded thirteen other full tiles per rejoin.

Refresh Off disables periodic checks; it does not disable sync-on-load when the
server permits that service. With synchronization disabled, source restoration
uses the same preserve/default rules without a server check. Diagnostics state
that synchronization is disabled. Timestamp-zero sources receive full requests
when synchronization is available. Missing, corrupt or incompatible source files
require full data. An unavailable destination reader defers cached restores and
reports the reason; it cannot fall through to overwrite unverified Voxy content.
Ordinary network acquisition remains available for other tiles.

The cache lives in `!dh-terrain-v1` under the adopted LSS/VSS cache root. Owned
hash-named buckets from the previous `dh-terrain-v1` directory are adopted while
foreign/native files stay in place. Identity includes address, DH server/world
keys, dimension, nonzero biome-zoom seed, profile and height/skylight shape.
SHA-256 filenames avoid raw identifiers but are **not anonymization or encryption**;
likely addresses can be guessed. The global quota is 8192 files / 256 MiB. A shared
index avoids directory enumeration on each write; successful reads/unchanged
responses touch LRU age, other buckets are evicted first, and this connection's
pending cached working set stays protected. If the protected set fills the
quota, new admissions can be declined. Both file and byte limits still apply.
Abandoned temporary files are counted and old ones are swept on index rebuild.

Resource pressure and client-thread timeouts retain valid source files and use
bounded per-tile retries. A sixty-second cache operation cancels its task, rather
than stopping the entire source. Reset refuses to clear beneath an unretired older
worker. Remote DH clearcache clears the current address; single-player reset no
longer requires a remote server. Global clearing tries native buckets even when
the source clear fails, and preserves foreign source files.

The destination reader holds an engine reference only while copying on the worker,
never across client-thread waits. It releases section references and rechecks the
current destination before accepting a comparison or handing off reconstructed
content. `/lss dhstatus` distinguishes matched and preserved columns, counts actual
conversion/enqueue work, and exposes unavailable comparison/synchronization.
Mapping copies and column snapshots have reservations. Per-column main-thread
checks remain for vanilla authority; whole-tile fusion and a full-range benchmark
have not been qualified.

**Remaining overwrite limitation:** new server payloads can replace older vanilla
refinements, and an already queued DH write can commit after newer vanilla terrain
inside Voxy. Preserving unchanged cached mismatches addresses the new reconnect
regression, not the [strict commit/I1 gap](2026-10-05-dh-voxy-commit-gap.md).
No durable completion receipt, derived-mip proof or upstream production patch is claimed.

## Historical packaged checkpoint

The preceding `0.16.0-dh-voxycache.dev20261005` candidate passed 3279 normal unit
tests (four opt-in skips), 78 Fabric/eight NeoForge gametests, six packaging checks,
126 packaging selftests and nine SQLite helper tests. Those results belong to that
older candidate, not these fixes. Its NeoForge SHA256 is
`b69426d30887f91fbc4e4d390345126df4a8c8b489c831a5a0995947d6d62609`.
It was installed with preferences preserved and a backup at
`minecraft/lss-dh-backups/20261005T192405.784270Z`; the corrected candidate now supersedes that installation.

Both older packaged clients passed eight stages with 2400 independent **block-state**
comparisons each. Unchanged rejoin/new-JVM stages had zero **target** payloads,
conversions and ingests. The older damaged-column stage reconstructed one column;
that behavior is intentionally replaced by preservation. Complete Voxy loss rebuilt
sixteen target columns. Offline edits, reset and source loss also passed.
The full-content wait used production `matchesCache`/`VoxyTerrainCache` as a
**premise**, not an independent block/biome/light oracle. Neither it nor backlog
zero proves asynchronous completion. Both fresh packaged loaders now pass the real-vanilla-visit reconnect stage,
preserving the raw destination digest with no target conversion.

Initial warm runs exposed real initialized-default semantics: `acquireIfExists`
returned null while retaining skylit air. Ordinary `acquire` corrected that mismatch.
An observer split-package/compiler failure and all pre-fix runs remain preserved;
none is relabelled as a pass. Historical evidence is under the outer checkout's
`build/dh-neoforge/2026-10-05/voxy-cache-reuse/`, with audit corrections in
`verification-corrected.json` and `fabric/verification-corrected.json`. The original
[checkpoint](evidence/2026-10-05-dh-voxy-cache-reuse-checkpoint.json) binds its original
historical receipts. Use the [manual guide](2026-10-05-dh-manual-test-guide.md) and
read-time liveness command for present setup status.
