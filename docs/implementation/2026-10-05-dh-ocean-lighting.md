# DH ocean lighting correction

Date: 2026-10-05. Local MC1.21.1 development work on Fabric and NeoForge.

The manual client showed bright sand on distant ocean floors. Approaching through
vanilla corrected the appearance, and the corrected data remained after retreat.
`/lss diag` showed OFFICIAL_16 / READY, no decode, request or Voxy ingest backlog,
zero failed columns and 1619 columns enqueued. The apparent acquisition stall was
idle operation on an existing-terrain-only server; the user requested generation
within 32 chunks.

**Post-review update:** attenuation now includes resolved nonempty fluid states,
including kelp and seagrass. The expanded frozen-tile regression checks over 7000
adjacent fluid/floor pairs, with explicit kelp/seagrass premises. Packaged tests
below are historical water-only qualification; new live plant checks are pending.
The corrected post-review client is installed and the same server has resumed. The audited
`ocean-lighting/verification-corrected.json` binds each loader to its actual jar:
Fabric `01bce007…`, NeoForge `885e22e5…`. The original verification's top-level
NeoForge hash must not be applied to its Fabric row.

## Verified cause and correction

The exact official DH 3.3.3 producer samples light at the block above a run and
merges water without separating changing light values. The installed Voxy port
lights opaque faces from their neighboring voxels and stores supplied DataLayers
without relighting. Repeating a water run's incoming surface skylight therefore
illuminated the seabed even though the adjacent floor run already recorded dark
water.

Read-only reconstruction of 54 detail-0 rows from the dedicated server database
produced 178031 adjacent water/floor pairs. Every pair agrees with one skylight
level lost per water voxel; 143437 had water brighter than the floor. This is
database evidence, not a network capture. The frozen tile (4,-3), its provenance
and independent floor-run oracle are in
[the ocean corpus](../../common/src/test/resources/dh-corpus/dh-official-3.3.3-neoforge-ocean/README.md).
Exact official and Support FullBuilder bytecode corroborate the run convention.

`DhColumnConverter` now reduces skylight with depth through resolved water block
states, including flowing water. It uses this line's block opacity and includes
the first water voxel below incoming light. The shared xplat implementation
serves Fabric and NeoForge. Block light, other block materials, no-sky behavior
and normalized protocol values retain their existing behavior. Only the
converter and its private material record changed among the 985 shipping classes.

## Validation

Two new ocean regressions fail against the previously installed build and pass
with this correction. The real frozen tile compares bottom-water light against
independent upstream floor-run light, including deep dark floors. Synthetic cases
cover shallow/deep water, flowing water, section boundaries, block light and
no-sky dimensions. Existing converter tests also pass.

The normal Java-21 build is `0.16.0-dh-ocean.dev20261005`: 3254 unit tests pass
with four existing Fabric opt-in skips; all 78 Fabric and eight NeoForge required
server gametests pass. All six versioned LSS/VSS artifacts pass packaging checks,
with 126 packaging selftests and nine SQLite helper tests. The NeoForge candidate
SHA256 is `885e22e5f90695fe4db369488a039becf43da219d2e34620561cbff848a3e0ea`.

Private packaged clients pass against official DH 3.3.3 on **both loaders**.
Each checks 450 deep and 450 shallow water samples across 32 eligible distant
columns in actual Voxy storage. Deep bottom-water skylight is 0, shallow is 10,
and surface water is 14. The oracle comes from raw upstream floor spans,
independently of converted DataLayers; block light is also checked.
NeoForge additionally checks 40320 stored block samples, including 20601 solids.
The first private setup had unsupported sand floors fall under gravity; its
evidence is retained as a fixture premise failure. Supported floors and refresh
for asynchronous DH exports retain the same strict light oracle.

The exact tested jar is installed in the previously selected Windows Prism
instance, preserving preferences byte-for-byte, with the previous jar backed up
under `minecraft/lss-dh-backups/20261005T171732.077143Z`. The same manual world
is resumed at `[::1]:25585`, with generation enabled within 32 chunks using
`SURFACE_THEN_CHUNKS`. The prior server configuration is backed up.
The [manual guide](2026-10-05-dh-manual-test-guide.md) describes the ocean retest.
The [final checkpoint receipt](evidence/2026-10-05-dh-ocean-lighting-checkpoint.json)
binds private readback and manual setup. Exact receipts, source manifests, pre-fix reds, upstream bytecode
and database inspection are preserved in the outer checkout under
`build/dh-neoforge/2026-10-05/ocean-lighting/`. Private observers never ship.

The user’s Windows manual retest on 2026-10-05 confirms “lighting is fixed”
and reports that the overall appearance looks good. This closes the reported
ocean-depth visual defect by user observation. No measured frame-time result or
other manual-test outcome was supplied.

This lighting correction does not close the previously verified Voxy asynchronous
commit/vanilla-authority gap or the remaining strict P2/P4 release gates.
