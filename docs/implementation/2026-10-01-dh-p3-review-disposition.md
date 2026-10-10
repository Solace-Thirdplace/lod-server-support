# DH P3 review corrections

This follows the preserved [Claude P3 review](2026-10-01-dh-p3-claude-review.md).
Each demonstrated finding was checked against the current implementation. Work
remains local and uncommitted on `feat/dh-codec-p1-mc1.21.1`; outreach and release
signoff remain pending.

## Verified findings and changes

| Finding | Verification and correction |
| --- | --- |
| P3-1: unreachable official absence is polled forever and starves refresh | Confirmed in the production scanner/empty-response path. One empty sync parks a tile when generation cannot resolve it. Reset/reconnect clears receipts; movement into permitted generation reach can rearm missing work without resetting exhausted rejection retries. Empty generation replies retain the bounded failure budget. After three priority-1 requests, a due refresh gets the next opportunity; untouched acquisition remains first. Actual-session regressions drive three stationary hours at range 48, for refresh Off and 30 seconds, and separately exercise retry fairness. |
| P3-2: three-second refresh-Off observation is inside the ten-second limiter | Confirmed; the historical observation is withdrawn. The fixture now requires a successful receipt due within the observation window, disables refresh through actual in-session Sodium Apply, observes more than 45 seconds and checks that no successful receipt timer remains armed. |
| P3-3: a five-second client-thread hitch terminates the DH source | Confirmed. A conversion timeout cancels its queued future and cools down only its request. The late task cannot ingest; the session stays active and can request that tile again. World initialization uses an epoch-guarded asynchronous owner callback. The regression withholds the actual main-task queue beyond the five-second timeout, then checks recovery, no late enqueue and released memory. |
| P3-4: failed post-retirement reset retains a stale session/status | Confirmed. A strict-sweep failure clears the retired session reference, reports reset failure and keeps acquisition stopped until an explicit retry or session boundary. Receipts are not cleared and the Voxy wipe remains aborted. A failure-path session test verifies retirement, status and retained receipts. |
| P3-5: authority blocks are mislabeled as waiting for Voxy | Confirmed. Typed state `BLOCKED` means reconnect required, including the no-session dimension-limit path. All three locales translate it, and the session/export tests cover the state. |
| P3-6: negative configured refresh renders 30 despite effectively being Off | Confirmed. Every nonpositive configured value renders Off. The settings regression uses -5 and proves selecting 30 creates a real persisted edit. Untouched 45 remains preserved. |
| P3-7: misleading evidence/documentation and presentation | Corrected skip labels, pixel method, hash/oracle wording and interval-change wording. The retained original observations are distinguished from new evidence below. `dhstatus` remains registered on both loaders and now uses the shared translated diagnostics. DH text exports retain settings/versions, and freshness labels show Off or a minimum interval. |

Additional review risks addressed: the test-fixture resource filter now applies only
to the imported DH corpus, so unrelated future fixture resources remain eligible.
Release checks explicitly reject `dev/vox/lssfixture/` in flat and nested artifacts.
The refresh tooltip explains the minimum interval and one-per-ten-seconds spreading.
Official captured AIR/stateless mappings now have converter unit coverage. A typed
DH privacy test rejects future opaque string/object fields and pins unset native
handshake/service fields.

## Build and regression verification

Java 21, fresh isolated source copy `/tmp/lss-dh-p3-fixes.7gigtl56`, shared harness
lock, sequential Gradle task execution. The full normal build passed:

| Suite | Passed | Skipped | Failures/errors |
| --- | ---: | ---: | ---: |
| common unit | 991 | 0 | 0 |
| Fabric unit | 1681 | 4 opt-in experiment/capture tools | 0 |
| NeoForge unit | 23 | 0 | 0 |
| Paper unit | 536 | 0 | 0 |
| Fabric required server gametests | 78 | 0 | 0 |
| NeoForge required server gametests | 8 | 0 | 0 |

Total: **3231 passed unit cases**, four skipped opt-in tools, **86 required server
gametests**. All **18 actual DH session tests** pass. Six normal LSS/VSS artifacts
pass the packaging checker. The first targeted run's one failure was an invalid
test mock of final Minecraft `DimensionType`; replacing it with the real registry
value fixed the test. Its log/XML remain preserved, rather than classified as a flake.
The packaging checker's **126** self-test cases and **9** SQLite packaging tests
also pass, including rejection of flat/nested test-fixture namespaces.

Current normal Gradle NeoForge jar:

```text
/home/vox/projects/voxel-server-support/build/dh-neoforge/2026-10-01/p3-review-fixes/lod-server-support-neoforge-dh-dev-mc1.21.1.jar
SHA256 83dc61c86a4e0601c699184beec8b218ae3b636875b58b396306825f22e8e040
```

Evidence directory:
`/home/vox/projects/voxel-server-support/build/dh-neoforge/2026-10-01/p3-review-fixes/`.
The old `p3/verification.json` remains bound to its original `c01f259d…` artifact;
it does not attest to these later changes.

## Same-artifact private client verification

- `lifecycle-tests/attempt-3rcdgvpt`: **36,864** actual Voxy block-state comparisons,
  including **11,728** solids across **16** distant columns and **72** snapshots.
  The llvmpipe geometry control renders **97,831** quads; restored/off screenshots
  were visually inspected and show the distant solid hillside disappear in the
  control. Movement, reset, receive off/on, Nether, End and portal return pass.
- Actual in-session Sodium Apply enables/disables refresh and later changes source
  with reconnect-pending semantics. The refresh run records **7 conditional
  requests**, **1 unchanged reply**, **14 successful receipts due before disable**,
  and **45,005 ms** of observation after disable with no new conditional request.
  No successful receipt timer remains armed. A READY-state DH diagnostics export
  retains native protocol 0 and negotiation false.
- Retirement budgets are measured as zero. Nether/End record **2304 / 15,872**
  sampled zero-skylight values. Native activation after reconnect is still against
  a no-LSS server and does not qualify native terrain delivery.
- `absence-tests/attempt-o3x6nwb7`: actual official server generation distance **8**,
  effective sync range **40**, **381** observed empty tiles beyond generation reach.
  Repeat full requests to those tiles would fail the fixture. With refresh Off,
  acquisition settles after **407** requests, with **241,661 ms** elapsed since
  the oldest observed absence. Enabling refresh produces **4 conditional requests**
  and **1 unchanged reply**, proving refresh proceeds after the missing frontier.

Both private servers saved all chunks. Recorded server, client, display and
supervisor owners are stopped, and no Java process retains either private run ID.
`verification.json` binds these results to the artifact and the 375-input source
manifest; it records candidate verification true and release readiness false.

## Manual setup restored

The tested jar replaces the prior `c01f259d…` jar in **LSS dev — 1.21.1 NeoForge -
Copy For Far Testing**, with dependency/mod checks, an idle-client check, atomic
publication and rollback. Preferences and both legacy JSON files are preserved;
only the refresh YAML comment changes. Backup:
`minecraft/lss-dh-backups/20261001T180547.185587Z`.

The manual official-DH server resumes the same world at **`[::1]:25585`**. Minecraft
status protocol 767 and Windows IPv6 TCP reachability are verified. See
`prism-installation.json` and `test-setup-completed.json` in the new evidence directory.
Use `/lss status`, `/lss diag`, or the Sodium LSS page. Source/profile require
reconnect; refresh changes apply during the session. No foreground client is launched.

## Audit of the older checkpoint

- The four skips are `CompressedColumnsExperimentTool`, `LodStoreExperimentTool`,
  `XverLiveCorpusCaptureTool` and `V20SettlingExperimentTool`, not Sodium cases.
- The older final build reused common/Paper test XML through UP-TO-DATE tasks on
  identical inputs. The counts remain legitimate recorded results, not fresh
  executions by that final invocation.
- The unsupported 174,027-pixel claim is withdrawn. `prior-evidence-audit.json`
  records RGB conversion, crop `(0,180,960,540)`, exact tuple inequality with no
  threshold: on/off 235,048; off/restored 247,167; on/restored 85,767. Temporal noise
  is included; this metric alone does not attribute every changed pixel to Voxy.
- Normalized terrain SHA values are regression pins. The originally reported
  Python oracle was not retained, so provenance now labels its counts as historical
  observations rather than a reproducible in-repository oracle.
- Earlier fixture booleans were asserted predicates, not additional measurements:
  authority followed eligible-target selection; zero-sky followed sampled-light
  assertions; zero budgets followed retirement checks. The new fixture emits actual
  budget values, zero-sky sample counts, computed authority and an actual GL-renderer
  check for llvmpipe. Successful receipts still mean enqueue acceptance only.
- An unchanged interval reload does not restart timers. Changing the interval
  reschedules only successful receipts. The YAML/reference wording now says this.
- The connection authority cap is 262,144 vanilla columns in aggregate across at
  most 16 dimensions, not 262,144 independently for each dimension.

## Remaining gates

Full Fabric/NeoForge × Support/official live coverage, official-15 corpus/gameplay,
the real-DH-client load comparison, asynchronous vanilla-overwrite race, custom-height
live sessions and refresh after removal remain open. Voxy exposes no completion or
persistence acknowledgement; the stricter P2 completion gate remains open. Other
Minecraft lines, broader performance measurements, graphics-backend qualification
and upstream outreach are unchanged. Native activation on the no-LSS server is
source-isolation evidence, not a successful native LSS live handshake.
