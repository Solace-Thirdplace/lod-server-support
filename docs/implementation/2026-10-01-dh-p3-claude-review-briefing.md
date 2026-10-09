# Claude Code review briefing: DH refresh, settings and diagnostics

Historical briefing for the pre-review artifact. See [P3 review corrections](2026-10-01-dh-p3-review-disposition.md) for the current follow-up; the review report is preserved unchanged.

Prepared 2026-10-01 at the user's request. Independently review the current local
DH implementation, concentrating on the refresh, Sodium settings and diagnostics
changes made after the first playable NeoForge checkpoint. Review the implementation
and its evidence; do not change production code during this review.

This briefing supersedes the earlier briefing for the current review. The older
briefing, Claude report and disposition remain historical records. Work remains
local, uncommitted and unpublished. DH-team outreach is pending; the user authorized
continuing local work while it remains unresolved.

## Checkout and requested report

Review this checkout:

```bash
cd /home/vox/projects/voxel-server-support/.worktrees/dh-client-p1
git status --short
git diff --stat 6677fe93dfc35b8ca1e158972d4cf58eb9703bd2
git diff --check
git ls-files --others --exclude-standard
```

Branch: `feat/dh-codec-p1-mc1.21.1`. Baseline: published 0.16.0 MC1.21.1 commit
`6677fe93dfc35b8ca1e158972d4cf58eb9703bd2`. This directory is a separate clone,
despite the `.worktrees` name. Inspect staged, unstaged and untracked files. In
particular, `DhStatusSnapshot.java` and the test fixture's `SendMixin.java` are
currently untracked and will not appear in an ordinary diff. The outer checkout
contains unrelated user changes; do not review its production diff.

Read the checkout's `CLAUDE.md` and these documents:

- `docs/planning/2026-09-29-dh-client-compatibility-plan.md`
- `docs/planning/2026-09-29-dh-client-compatibility-sources.json`
- `docs/implementation/2026-10-01-dh-refresh-settings.md` — current checkpoint.
- `docs/implementation/2026-10-01-dh-claude-review.md` — original review.
- `docs/implementation/2026-10-01-dh-review-disposition.md` — earlier corrections;
  its counts, artifact and “reload affects future receipts” statement predate P3.

Write the new report in the implementation checkout at:
`docs/implementation/2026-10-01-dh-p3-claude-review.md`.
Preserve the previous reports. Put verified actionable findings first, with
severity, file/line, trigger, consequence, supporting evidence and a proposed
fix/regression check. Separate suspected risks and incomplete plan gates from
demonstrated bugs. State exactly what you independently ran and what evidence you
only inspected. If no verified defects are found, say so and identify the practical
limits of the review. Do not commit, merge, publish, install mods or contact upstream.

## Current target and exact artifact

Minecraft **1.21.1**, Java **21**, NeoForge **21.1.248**. The tested client uses
the community Voxy **0.2.15-beta** port and Sodium **0.8.12-beta.1**, with the pinned
Connector/FFAPI/Xaero stack. There is **no DH client mod**. The test server runs
official **DH 3.3.3**, with **no LSS server mod**. Profile: `official_16`;
NeoForge optional-channel negotiation version: `1`.

The plan's DH 3.3.4 reference has no MC1.21.1 artifact; do not describe it as tested.
Support-15 and official-15 codec paths exist, but current packaged gameplay evidence
does not qualify those targets or other Minecraft lines.

The final jar is a normal Gradle NeoForge shadowJar, not a POC assembler output:

```text
/home/vox/projects/voxel-server-support/build/dh-neoforge/2026-10-01/p3/lod-server-support-neoforge-dh-dev-mc1.21.1.jar
SHA256 c01f259dae66025b8b7c5d418c710971e25ee6ce6557bef3830b175b935eab0b
```

Internal metadata remains 0.16.0; the external filename marks the dev build.
This exact jar was privately tested and installed in the Windows Prism target.
The separate test fixture is excluded from production jars and the Windows profile.

All current runtime/build evidence lives in the **outer** checkout:

```text
/home/vox/projects/voxel-server-support/build/dh-neoforge/2026-10-01/p3/
```

Start with `verification.json` and `source-manifest.json`. They bind the artifact
to 375 production/build inputs, suite results and two final private runs. Check
the current source against the manifest before attributing artifact evidence to
any later edits. Documentation and fixture sources are not all covered by that
production-input manifest. Older artifacts and failed/intermediate attempts remain
on disk; use the final paths below when assessing the latest checkpoint.

## Review priorities

### 1. Conditional refresh and receipt correctness

Read `common/src/main/java/dev/vox/lss/common/dh/DhSessionPolicy.java` and
`xplat/src/main/java/dev/vox/lss/networking/client/DhClientSession.java`, particularly
`configureRefresh`, `refreshAllowed`, `refreshTimestamp`, `unchanged`, request
ownership and the response-to-ingest path.

- Refresh defaults to **0 / Off**. Applying a positive interval immediately
  schedules existing successful held receipts; applying zero stops their timers.
  Reconciliation must preserve timestamps and must not unpark failed work or
  change missing-terrain acquisition retries.
- Conditional requests may send a positive server timestamp only for a complete,
  successful session receipt. Partial or failed attempts require full data.
  Timestamps remain in **server milliseconds**; scheduling uses monotonic
  nanoseconds. Reapplying the official client's local-clock adjustment here would
  double-adjust a timestamp already received from the server. Support compares
  transmitted milliseconds after division by 1000.
- An empty response means unchanged only when it belongs to an owned conditional
  request whose timestamp still matches valid local acceptance. It renews the
  receipt without another Voxy enqueue. Empty initial, stale, reset or invalid
  responses must not falsely establish acceptance.
- Accepted snapshots require server synchronization and a positive sync rate
  for refresh. Disabling sync preserves terrain; it must not silently substitute
  generation. Support's timestamped handler is still generation-gated and may
  build missing plugin data; distinguish that server behavior from a client fallback.
- Initial acquisition has priority over refresh. Accepted refreshes are limited
  to one per ten seconds. Receipt capacity is 8192 for a maximum 4225-tile frontier;
  failures back off 30/60/120 seconds and park on the fourth failed attempt.

**Acceptance limitation:** the public Voxy API provides `rawIngest(...): boolean`
but no completion or persistence callback. A receipt means all eligible columns
were accepted by that API, as allowed by plan §5.3. It is session-only. Queue depth
zero is not an acknowledgement of completed processing. Private tests separately
read actual Voxy storage. The stricter P2 wording “accepted receipts imply completed
ingestion” remains **open**; this review must not close it by relabeling enqueue
acceptance as completion or durability.

### 2. Sodium controls and settings transactions

Read `xplat/src/main/java/dev/vox/lss/config/menu/ClientOptionCatalog.java`, the
shared settings schema/template, both Sodium page adapters and their tests.

- New controls: `lss:lod_source`, `lss:dh_profile`, `lss:dh_refresh`. Source/profile
  use labeled slider indices in the UI but persist validated **string** values.
  Indices must not leak into YAML or networking.
- Source/profile take effect on a **new physical connection**. Refresh is **hot**
  and reconciles the current session after Apply. Changing source in an open
  session must not activate two terrain sources.
- Apply uses the existing draft validation and single save/reload transaction.
  Review cancellation, failed validation/save, unrelated pending edits, receive
  gating and existing settings adoption behavior before proposing a new mechanism.
- Refresh UI uses 30-second stops up to 3600, with zero labeled Off. A manually
  configured non-stop value such as 45 may display 60, but an untouched row must
  preserve 45 when another row is applied.
- Both Sodium generations share the catalog. Native LSS remains the default.
  English, Simplified Chinese and Traditional Chinese keys/placeholders must agree.

### 3. Typed diagnostics and privacy

Read `common/src/main/java/dev/vox/lss/common/diagnostics/DhStatusSnapshot.java`,
`ClientStatusSnapshot.java`, `ClientStatusText.java`, plus the xplat client
`ClientStatus.java` and `ClientCommandActions.java`.

- Export schema **3** appends typed `dh` observations. Existing native fields
  retain their meaning. DH mode reports native protocol 0 and native negotiation/
  server-enabled false; it must not claim native Xaero/far-player services.
- DH profile/state, coverage, requests/enqueues/failures, owned memory, queues,
  effective limits and freshness must describe the current immutable snapshot,
  including teardown or export racing a source/world change.
- Export must exclude addresses, opaque server/world keys, coordinates and raw
  peer error strings. Sanitized display/log text is not permission to export it.
- `/lss status`, `/lss diag` and `/lss diagnostics export` use existing status/export
  flows and lifecycle feedback. Labels must distinguish enqueue counts from
  completed ingestion. `/lss dhstatus` remains registered on both loaders. The follow-up routes it through the shared translated diagnostics; prefer `/lss status` or `/lss diag` for current manual instructions.

### 4. Integration invariants and prior corrections

Review the relevant changes in `ClientNetGlue`, `ClientSessionGate`,
`ClientIdentityResolver`, `ColumnCacheStore`, `ResetCoordinator`, `DhColumnConverter`,
`DhVoxyBridge`, `VoxyCompat`, and both loader-specific `DhClientTransport` twins.

Check stale callbacks and retirement, reserve-before-allocation, connection-bound
cancellation, bounded inbound/worker/assembly queues and released budgets. NeoForge
retained Netty slices have separate bounded ownership until flow is known; guest
C2S data must not enter the host client's DH budget. Logout/orphan expiry must
release retained frames. Native source behavior and strict DH cache invalidation
must remain distinct, with invalidation failures observable.

Vanilla authority is connection-scoped and partitioned by dimension; portal return
retains it. Logical world-key changes require reconnect. Reset deliberately keeps
vanilla ownership while clearing DH receipts and native aliases; tests pin this
tradeoff. Read those pins before calling the retained ownership a new defect.
Two main-thread checks around conversion/handoff do not prove ordering against
Voxy's later asynchronous writes; that live race measurement remains outstanding.

Conversion must use actual world height, complete known columns, detached section
arrays, horizontal/vertical quart biomes and explicit zero skylight in no-sky
dimensions. Complete AIR replacement must clear old blocks/light. Unknown columns
must not become fabricated air. Official `AIR`, stateless `_STATE_` mappings and
the exact evidenced H+1 ceiling AIR exception must remain profile-scoped; H+2 and
opaque/outside-height spans still reject.

The earlier review's Unicode-direction, XZ terminal-read, capture-resource,
receipt-capacity, authority/reset and lifecycle issues received corrections.
Recheck their current code and regression coverage rather than copying historical
line numbers or verdicts. Packaging must exclude fixtures, test stubs and real DH
dependencies, retain the relocated XZ license and preserve native wire/brand fidelity.

## Verification already recorded

These are recorded implementation results, not checks you have independently run:

| Suite | Passed | Skipped | Failures/errors |
| --- | ---: | ---: | ---: |
| common unit | 988 | 0 | 0 |
| Fabric unit | 1674 | 4 opt-in experiment/capture tools | 0 |
| NeoForge unit | 23 | 0 | 0 |
| Paper unit | 536 | 0 | 0 |
| Fabric required server gametests | 78 | 0 | 0 |
| NeoForge required server gametests | 8 | 0 | 0 |

Total: **3221 passed unit cases**, four skips, **86 required server gametests**.
All **12 actual `DhClientSessionTest` tests** pass without skips. Normal LSS/VSS
artifacts on all three loaders pass `release_check.py`; that is packaging evidence,
not release signoff. See `test-results/`, `lss-dh-p3-final.log` and
`lss-dh-p3-release-final.log` in the evidence directory.

- **Menu:** `final-menu-tests/attempt-ol2dc3sn`. Actual Sodium 0.8.12-beta.1 builds
  13 LSS controls, renders source/profile/refresh labels, saves string choices
  through Sodium Apply, applies refresh 30 immediately and exports the typed DH
  snapshot with native negotiation/protocol unset. Inspect `dh-menu.json`,
  `dh-result.json`, screenshot and diagnostics exports.
- **Gameplay/lifecycle:** `final-private-tests/attempt-up1fqe79`. Same production
  jar against official DH 3.3.3. Actual Voxy storage comparisons: **73,728** samples,
  **23,219** solids, **32** distant columns, **156** snapshots. Movement adds ten
  tiles. Reset, receive off/on, Nether, End and portal return pass; retired budgets
  return to zero and no-sky conversion has zero skylight.
- **Refresh:** that gameplay run enables refresh on already accepted held tiles,
  observes **3 conditional requests and 1 actual unchanged reply**, then disables
  refresh. Its three-second observation was inside the ten-second limiter and does not prove hot disable; the follow-up replaces that control. Check `dh-refresh.json` and accompanying logs.
- **Source switch:** Apply remains connection-scoped; after reconnect native LSS
  is selected and DH requests stop. The server has **no LSS service**, so this
  proves source isolation and native activation only. It does **not** prove a
  successful native LSS handshake or native terrain delivery.
- **Rendering:** an owned Xvfb/llvmpipe run restores **92,866 Voxy quads**. A fixture
  suppresses actual Voxy geometry for the off control; the distant opaque hillside
  disappears. The withdrawn 174,027 count lacks a retained method. A fresh exact RGB audit of crop `(0,180,960,540)` gives 235,048 on/off and 85,767 on/restored changes, including temporal noise. Compare the
  render-on/off/restored screenshots yourself. This is software rendering evidence,
  not qualification of every Windows graphics backend.

Both final private servers saved all chunks. Their owned server/client/display/
supervisor processes stopped. Raw logs stay local. The fixture is an observer and
test control, not shipping code; inspect whether its interventions weaken a claim.
For each attempt, client JSON, diagnostics exports and screenshots are under
`instances/dh-neoforge/minecraft/`; launcher/server logs are elsewhere in the
attempt directory.

## Existing manual setup and safe reproduction

The latest jar is installed in **LSS dev — 1.21.1 NeoForge - Copy For Far Testing**,
under the user's Windows Prism instances. Installation preserves preferences and
legacy JSON files; its receipt is `prism-installation.json`. Defaults in this test
profile: DH source, official-16 profile, refresh Off, receiving enabled, distance 32.
Use `/lss status`, the Sodium LSS page and `/lss reset` for manual observations.
Source/profile Apply requires reconnect; refresh Apply is immediate.

The owned manual server is currently available at **`[::1]:25585`**, resuming its
existing test world at view distance 8 and simulation distance 4. It holds the
shared harness lock. **Leave it running during read-only review. Coordinate downtime
before new heavy verification.** Do not kill by port or broad process pattern,
reset its world, operate the Windows foreground UI or alter the Prism instance.
If downtime is authorized, use the current owned server's console to save and stop
cleanly, and respect the ownership receipts and launcher storage preflight.

Code inspection, XML/report inspection and artifact/source hash checks can proceed
without stopping that server. For a later independent build, use a fresh isolated
source copy outside the parent repository; nested source locators otherwise see
duplicate trees. Preserve `/tmp/lss-dh-neo-build`, which produced the recorded jar.

After coordinated downtime, for example:

```bash
cd /home/vox/projects/voxel-server-support/.worktrees/dh-client-p1
dh_review_build=$(mktemp -d /tmp/lss-dh-p3-claude-build.XXXXXX)
rsync -a --exclude=.git --exclude=.gradle --exclude=build --exclude=.worktrees ./ "$dh_review_build/"
cd "$dh_review_build"
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
bash tools/verify/run-gradle.sh --no-parallel :common:test :fabric:build :paper:test :paper:shadowJar :neoforge:build vssJars
bash tools/verify/run-gradle.sh --no-parallel :neoforge:runGameTestServer
```

The wrapper holds the shared harness lock and supervises owned descendants.
Keep real servers/builds sequential, bounded and logged. Tier 3 client gametests
are cut on this line and the task does not exist; Folia is unavailable here.
Read `docs/operations/test-flakes.md` before classifying a failure, and do not
treat a different assertion as a known flake.

## Remaining plan gates

- Full MC1.21.1 Fabric/NeoForge × DH Support/official live matrix. Historical
  Support POC evidence does not qualify every cell on the current artifact.
- Official-15 captured corpus and packaged gameplay; remaining official reference
  fixtures and a real-DH-client handshake/load comparison.
- Asynchronous vanilla-overwrite race measurement, custom-height live sessions and
  refresh after removal. Unit conversion tests do not replace those observations.
- The Voxy completion/persistence acknowledgement limitation described above.
- Broader CPU/allocation/frame-time/server-load measurements and protocol maintenance
  coverage. Direct partial-update ingestion, persistent DH receipts and other
  Minecraft lines remain deferred.
- Upstream outreach remains pending. The user's positive Windows observation
  applies to the earlier playable path; the final P3 artifact has private evidence
  and is installed for further manual testing, not independently Windows-qualified
  across every graphics backend.

`verification.json` explicitly records `candidate_verified: true` and
`release_ready: false`. Judge local playable readiness separately from complete
plan/release readiness, and do not infer publication authorization from this review.
