# Local DH client proof of concept

Date: 2026-09-29. Branch: `spike/dh-client-poc-mc1.21.1`.
Base: published `v0.16.0+mc1.21.1`, commit `6677fe93`.
Implementation checkout: `/home/vox/projects/lss-dh-poc`. Run the commands below
from that worktree; the original support checkout contains the planning notes only.

The user authorized a **local-only POC** and explicitly left DH-team outreach
pending. This narrows the first experiment from the full
[compatibility plan](2026-09-29-dh-client-compatibility-plan.md): one stationary
Fabric client, one protocol-15 DH Support server, one 64×64-block tile. No
upstream endorsement, release, external server test, or full S0–S2 completion is
implied. Independent user demand beyond this POC request remains unmeasured.

## Reproduce

Use Java 21 and local, pinned jars. The wrapper acquires the shared harness lock,
checks Linux and physical host storage, creates an immutable attempt directory,
binds Paper to loopback, and launches the client on its own Xvfb display. It keeps
all failures and shuts down only its own processes. Neither a normal world nor a
launcher account is copied. `--paper` may sit beside cached `cache/`, `libraries/`
and `versions/` directories to avoid downloading Paper's distribution again.

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  bash tools/verify/run-gradle.sh -PdhPoc=true :fabric:dhPocTest

# Network/decode proof, no Voxy or DH client needed:
bash scripts/dh-poc.sh \
  --paper /path/to/paper-1.21.1.jar \
  --dh-support /path/to/DistantHorizonsSupport-0.14.0.jar

# Add the actual Voxy bridge, using the pinned Fabric pairing below:
bash scripts/dh-poc.sh \
  --paper /path/to/paper-1.21.1.jar \
  --dh-support /path/to/DistantHorizonsSupport-0.14.0.jar \
  --voxy /path/to/voxy-0.2.15-beta-sodium0.8.12.jar \
  --sodium /path/to/sodium-fabric-0.8.13-beta.2+mc1.21.1.jar \
  --tile-x -4 --tile-z -1 \
  --gpu-adapter NVIDIA
```

The GPU selector is for this WSL machine; choose the actual Mesa D3D12 adapter on
another WSL machine, or omit it on native Linux. The default endpoint is
`127.0.0.1:25575`. Default tile `(4,-1)` covers chunk X `16..19`, Z `-4..-1`,
beyond the two-chunk vanilla view distance. `--tile-x` and `--tile-z` select
another single tile; each attempt uses a fresh seeded world.

**Known rendering limitation on this WSL machine:** the D3D12/NVIDIA path omits
most opaque terrain. For the software diagnostic that rendered the full land
tile, omit `--gpu-adapter` and prefix the same command with
`GALLIUM_DRIVER=llvmpipe MESA_GL_VERSION_OVERRIDE=4.6 MESA_GLSL_VERSION_OVERRIDE=460`.
This is a visual diagnostic, not a performance measurement or a hardware fix.

Evidence lives under `fabric/build/dh-poc/attempt-*/`: artifact hashes, server and
client logs, every DH payload, the reassembled DTO, decoded identities, and
`client/dh-poc-evidence/result.json`. The optional Voxy arm records accepted
columns/sections, waits for its ingest queue to drain, verifies sampled blocks
inside Voxy, and captures the client
framebuffer before and after handoff at `client/screenshots/dh-poc-before.png`
and `client/screenshots/dh-poc.png`. Queue acceptance is distinct from visual
correctness or persistence verification. `voxy-readback.json` records comparisons
of the bottom, middle and top of every decoded span against Voxy's level-zero
section snapshots. Repeated positions in short spans count as separate samples.
This checks stored block states, not disk reload, biome/light fidelity or pixels.

## Wire facts and first findings

All fixed-width fields are big-endian. The independent probe links no DH code.
Protocol facts were checked against the pinned DH Support 0.14.0 source recorded
in the plan's source ledger; only actual network bytes enter the fixture corpus.

- DH positions carry detail in bits 0–7, signed X in bits 8–35, signed Z in
  bits 36–63. This probe requests detail 6, not 0.
- Request 7 contains request ID, short-prefixed level string, packed position,
  and a timestamp-presence byte; this acquisition sends false.
- Response 8 contains request ID and **a payload-presence byte**, then buffer ID
  and the beacon count/list. Each beacon has three int coordinates and int RGB.
  The design plan's original response summary omitted that presence byte.
- The DTO is position, data hash, eight int-length-prefixed blobs (main, four
  edges, generation, world compression, mapping), format/compression bytes,
  parent/child flags, creation/update longs. This POC accepts only format 1,
  compression 3, empty edge blobs, and ASCII identities/level strings.
- Fabric sends `minecraft:register` after invoking its JOIN callbacks. The first
  attempt sent configuration from JOIN and got no replies; deferring the
  handshake until after registration produced configuration and level replies.
- On this MC client `level.hasChunk` is insufficient to establish a loaded
  vanilla chunk. Use the existing Xaero bridge's full-chunk lookup and reject
  null/`EmptyLevelChunk` sentinels.
- The captured FullBuilder tile has **385 blocks of vertical span coverage for
  a 384-block world**. Every top air span ends at relative Y 385. The decoder
  clips only that exact one-block AIR excess at the exclusive ceiling; solid
  overruns, deeper overruns, overlaps and bad IDs still fail. The unmodified
  capture is a regression fixture, including its hash and provenance.
- Loom's named development runtime strips nested libraries from local mod jars.
  The first Voxy arm failed on missing RocksDB before any DH ingestion. The POC
  runtime now includes the exact nested libraries from the pinned Voxy jar.
  This does not add them to LSS's shipping artifact or use Voxy's DH importer.

## Scope limits

The `dhPoc` source set and XZ dependency exist only with `-PdhPoc=true`. They do
not enter production jars. Native LSS acquisition is disabled in the disposable
client YAML. The server has DH Support only. A real DH client causes the POC to
fail before channel registration. There are no fabricated LSS packets, native
timestamps, persisted receipts, or deliveries to Xaero/other API consumers.

The optional conversion uses `SectionConstruction` and invokes LSS's existing
private Voxy ingest body through a temporary development-only reflective call,
inside a DH-owned `IngestFailureHandle`. No production API was widened. Unknown
materials fail the proof. Each accepted MC column has complete vertical coverage;
biomes use the 4×4×4 cell-center sample; both lighting layers are filled, including
air. Sections are never reused after handoff.

This is deliberately **not a production session implementation**: one request,
no retries, no movement, no refresh, no source switching, no reset support, and
no other dimensions, loaders, official-DH protocols or server-load comparison.
Conversion runs one MC column per client tick; bounded frame assembly/decoding
still runs on the client thread. The full plan needs its dedicated worker and
shared-state invariants before feature integration. Fresh disposable caches and
a stationary player keep those unresolved lifecycle cases outside this proof.

## Results

**Transport and Voxy ingestion pass; complete solid terrain renders in the
software graphics control. The WSL D3D12/NVIDIA path has an unresolved opaque-face
rendering failure.** No DH client is installed. The original ocean screenshots
were insufficient to establish solid-terrain rendering; the user's water-only
observation correctly exposed that gap in the first conclusion.

The original ocean runs are `attempt-x8z_yw2w` and `attempt-8alvkpv8`. The land
controls are `attempt-etlr9jq_` (D3D12), `attempt-7g442gge` (software), and
`attempt-_t5iq4xc` (D3D12 with block readback). Their private logs remain under
the local build directory. Each client and server shut down after capture.

| Observation | Result |
| --- | --- |
| Server | Paper 1.21.1 build 133, DH Support 0.14.0, default FullBuilder; no LSS plugin |
| Client | Fabric 0.19.3, LSS 0.16.0 + dev POC, Voxy 0.2.15-beta, Sodium 0.8.13-beta.2; no DH client |
| Ocean tile | `(4,-1)`, beyond vanilla distance; 4,096 block columns, 21 identities, 21,177 spans |
| Ocean transport | One request; four reply frames; 10,624 DH payload bytes; 10,509-byte DTO |
| Land tile | `(-4,-1)`, beyond vanilla distance; 4,096 block columns, 38 identities, 35,209 spans |
| Land transport | One request; six reply frames including three fragments; 37,309 DH payload bytes; 37,168-byte DTO |
| Voxy | 16 MC columns / 384 sections accepted; ingest backlog drained |
| Land readback | 105,627 block-state sample comparisons passed, including 86,955 solid samples, across 38 section snapshots |
| Automated checks | 10 POC tests, 0 failures/errors/skips; includes both captured-frame replays, land material counts and malformed/unfinished-XZ cases |
| Packaging | `:fabric:remapJar` passes with the POC enabled; all 3 outer/nested jars checked, no POC classes, captures or XZ dependency |

[Sanitized result and artifact hashes](evidence/dh-poc-2026-09-29/result.json).

Before handoff, the distant tile is absent:

![Before DH ingestion](evidence/dh-poc-2026-09-29/before.png)

After handoff, the isolated ocean tile's water appears at the crosshair. Its
434,701 solid voxels include a seafloor at Y=40–57, but opaque faces are missing
from this image. The ocean location alone does not explain the result.

![After DH ingestion](evidence/dh-poc-2026-09-29/after.png)

The land tile makes the failure clear on D3D12 (NVIDIA GeForce RTX 3080 Ti,
Mesa 25.2.8): small plants appear, while most opaque terrain does not.

![Land tile with missing opaque geometry on D3D12](evidence/dh-poc-2026-09-29/land-d3d12.png)

With llvmpipe software graphics on the same Mesa version, the full stone body and
grass surface appear. The isolated tile's exposed sides are expected because
only one tile was requested.

![Land tile rendered with software graphics](evidence/dh-poc-2026-09-29/land-software.png)

All eight compressed terrain/mapping blobs are byte-identical between these two
land runs; DTO timestamps differ. The separate D3D12 readback run passes the
block-state comparisons despite its incomplete image. Together these isolate the
failure to the graphics path; the exact upstream/driver/renderer cause remains
unproven. This is not a verdict on native Windows rendering, and no hardware
rendering fix has been made. [Comparison receipt and image hashes](evidence/dh-poc-2026-09-29/land-comparison.json).

These controls prove the narrow DH-to-Voxy data path and software-rendered solid
terrain, not fidelity for all blocks, lighting, caves or tile edges. No native
regression gauntlet or full S0 load comparison was run; production Java sources
remain unchanged. Hardware rendering needs its own resolution before claiming
that environment passes. Connected tiles and moving-player ownership remain
follow-up work before a production scheduler or second protocol/loader is added.
