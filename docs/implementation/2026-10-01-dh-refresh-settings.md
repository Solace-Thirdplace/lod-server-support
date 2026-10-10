# DH refresh, settings and diagnostics checkpoint

This records the pre-review P3 checkpoint. See [P3 review corrections](2026-10-01-dh-p3-review-disposition.md) for the newer build and verified findings.

The user reported that the first NeoForge Windows client works and requested
continued plan implementation. This follow-up implements the P3 settings,
refresh and diagnostics work on Minecraft 1.21.1. It remains local and uncommitted
in `.worktrees/dh-client-p1`; outreach is pending. It does not complete every
P2/P4 release gate or authorize publication.

## Changes

- Both Sodium page generations expose source, DH server profile and refresh
  controls through the shared catalog. Source/profile persist as YAML strings;
  their slider indices never enter settings or networking. Source/profile still
  require a new physical connection. Apply uses the existing single save/reload
  transaction. English, Simplified Chinese and Traditional Chinese keys agree.
- Changing refresh reconciles existing accepted snapshots immediately. Zero
  stops their timers; positive intervals enable held snapshots. Failed/parked
  work and missing-terrain acquisition keep their existing retry policies.
- Refresh sends the received server timestamp only for a complete, successful
  session receipt. An owned unchanged response renews that receipt without
  generation or another Voxy enqueue. Partial/failed refreshes require full data;
  reset/reconnect cannot reuse a timestamp without its session acceptance.
- Accepted tiles require server synchronization and a positive sync rate for
  periodic refresh. Disabled sync preserves terrain instead of substituting
  unconditional generation. DH Support still uses its generation-gated handler;
  its timestamped lookup may build missing plugin data. No extra fallback bypasses
  that server behavior.
- `/lss status`, `/lss diag` and `/lss diagnostics export` report DH profile,
  coverage observations, request/enqueue/failure counts, owned memory, queues,
  effective limits and session freshness. Export schema 3 adds a typed `dh` field;
  existing native fields retain their meaning and native protocol/handshake values
  stay unset in DH mode. Opaque world/server keys and server error strings are not
  exported. The source status is translated in all three languages.
- Conversion tests cover nonuniform horizontal/vertical biomes at negative tile
  corners and complete AIR/light replacement using detached sections.

Server-origin DTO timestamps are retained in server milliseconds. The official
client's clock offset applies to its locally stored timestamps; applying it again
here would double-adjust a timestamp already received from the server. DH Support
compares the transmitted milliseconds after division by 1000. Real official-16
conditional requests and an unchanged reply passed the private test.

## Acceptance terminology

The public Voxy port API exposes `rawIngest(...): boolean` but no completion or
persistence callback. Receipts therefore mean **every eligible column was accepted
by the ingest API**, as allowed by plan §5.3. They remain session-only. Queue-empty
observations are not treated as acknowledgements. Actual storage is independently
read back in the private test. The stricter P2 phrase “accepted receipts imply
completed ingestion” remains an open gate; this checkpoint does not claim a
production acknowledgement that the API cannot provide.

## Verification

Normal Java 21 Gradle builds and tests ran from `/tmp/lss-dh-neo-build` under the
shared harness lock. The final build disables Gradle parallel task execution. Its common and Paper test tasks were UP-TO-DATE; their recorded XML came from earlier executions on identical inputs.

| Suite | Passed | Skipped | Failed/errors |
| --- | ---: | ---: | ---: |
| common unit | 988 | 0 | 0 |
| Fabric unit | 1674 | 4 opt-in experiment/capture tools | 0 |
| NeoForge unit | 23 | 0 | 0 |
| Paper unit | 536 | 0 | 0 |
| Fabric required server gametests | 78 | 0 | 0 |
| NeoForge required server gametests | 8 | 0 | 0 |

All 12 actual DH session tests pass without skips. `release_check.py` accepts all
six normal LSS/VSS artifacts. These are packaging checks, not release signoff.

The final production NeoForge jar is
`build/dh-neoforge/2026-10-01/p3/lod-server-support-neoforge-dh-dev-mc1.21.1.jar`,
SHA256 `c01f259dae66025b8b7c5d418c710971e25ee6ce6557bef3830b175b935eab0b`.
Its `verification.json` binds 375 production/build inputs, unit XML, packaged
artifact hashes and two fresh private runs:

- `final-menu-tests/attempt-ol2dc3sn`: actual Sodium 0.8.12-beta.1 builds 13 LSS
  controls, displays translated source/profile/refresh labels, saves string choices,
  applies hot refresh and exports a DH snapshot with native protocol unset.
- `final-private-tests/attempt-up1fqe79`: actual NeoForge 21.1.248/Voxy client against
  official DH 3.3.3, with no DH client and no LSS server. It passes 73,728 actual Voxy
  block-state comparisons including 23,219 solids across 32 distant columns and
  156 snapshots. Movement acquires ten additional tiles. Reset, hot refresh enable,
  unchanged response, receive off/on, Nether, End, portal return and native source
  activation after reconnect all pass. Retired DH budgets return to zero.

The llvmpipe control renders 92,866 Voxy quads after restoration. A separate fixture
suppresses actual Voxy geometry for the off control; the distant solid hillside
vanishes. A later exact RGB audit of the lower two thirds finds 235,048 changed pixels on/off and 85,767 on/restored; temporal noise is included. The earlier 174,027 claim has no retained method and is withdrawn. Fixtures remain outside
production and the Windows profile. Both private servers save all chunks, and their
owned server/client/display/supervisor processes are stopped. Raw logs stay local.

The historical refresh-Off check observed only three seconds inside the ten-second refresh limiter; it does not prove hot disable. The follow-up replaces it with an observation beyond 45 seconds and a receipt known to be due within that window.

## Manual setup

The verified jar replaces the earlier dev jar in **LSS dev — 1.21.1 NeoForge - Copy
For Far Testing**. Installation checks the idle Windows JVM, pinned dependencies,
mod IDs and current file hashes; it stages atomically with rollback and a backup.
Existing preferences and both legacy JSON files remain intact. Only the outdated
refresh comment in YAML is corrected. The receipt is `p3/prism-installation.json`;
backup directory is `minecraft/lss-dh-backups/20261001T162307.208533Z`.

The owned official-DH server resumes its existing test world at **`[::1]:25585`**,
with view distance 8, simulation distance 4 and seed 8675309. `/lss status` opens
source-aware status; Sodium's LSS page contains the three controls. Refresh defaults
to Off. To test it, choose 30 seconds and Apply. To change source or server profile,
Apply and reconnect. `/lss reset` clears session receipts and reacquires terrain.
The server holds the shared harness lock while available for manual testing.

## Remaining gates

The four-cell Fabric/NeoForge × DH Support/official live matrix, official-15 corpus
and gameplay, real-DH-client bounded load comparison, asynchronous vanilla-overwrite
race measurement, custom-height live sessions and removal refresh still need
coverage. Their CI counterparts do not substitute for those live observations.
The first official target on this MC line remains **3.3.3**, because the plan's
3.3.4 reference has no MC1.21.1 artifact. Other Minecraft lines remain deferred.
The user's Windows observation closes the first playable manual check, not every
release or graphics-backend gate. No release, upstream contact or commit was made.

The 2026-10-05 usability update replaces the source slider with **Use DH**
(a checkbox) and the profile slider with **DH server** (an enum selector);
refresh retains its slider with compact seconds. Stored values and reconnect
semantics are unchanged. See [the current guide](2026-10-05-dh-manual-test-guide.md#dh-options).
