# Claude Code review briefing: DH client, NeoForge playable follow-up

Prepared 2026-10-01 at the user's request. Independently review the implementation
and evidence after the corrections to your earlier review. Work is local,
uncommitted and unpublished; upstream outreach remains pending.

## Correct checkout and diff

```bash
cd /home/vox/projects/voxel-server-support/.worktrees/dh-client-p1
git status --short
git diff --stat 6677fe93dfc35b8ca1e158972d4cf58eb9703bd2
git diff --check
```

Branch `feat/dh-codec-p1-mc1.21.1`, baseline published 0.16.0 MC1.21.1 commit
`6677fe93dfc35b8ca1e158972d4cf58eb9703bd2`. This directory is a separate clone,
despite its name. Inspect untracked files as well as staged/unstaged diff. The
outer repository has unrelated user changes; do not review its production diff.

Read `CLAUDE.md`, `docs/planning/2026-09-29-dh-client-compatibility-plan.md`,
`docs/implementation/2026-10-01-dh-first-playable.md` and
`docs/implementation/2026-10-01-dh-review-disposition.md`. Preserve the original
`2026-10-01-dh-claude-review.md`; write this follow-up as
`2026-10-01-dh-claude-review-followup.md`.

## Scope and current evidence

The user extended P1 until a playable build existed, then requested NeoForge1.21.1,
self-testing, a Prism update and a replacement test server. Both Fabric and NeoForge
transports are implemented; the latest private gameplay target is **NeoForge21.1.248
+ Voxy0.2.15 + Sodium0.8.12-beta.1** against **official DH3.3.3** on MC1.21.1. The
client has no DH mod and the server has no LSS mod. Profile `official_16`, channel
negotiation version `1`. DH3.3.4 has no MC1.21.1 artifact; do not infer that version
was tested. Support protocol15 retains its captured codec/converter coverage.

Normal Java21 Gradle builds and suites now pass; the earlier sandbox restriction
is historical. Fabric78/NeoForge8 required server gametests passed. Actual session
lifecycle tests cover ownership limits, delayed callbacks, stale epochs, reset,
expiry, request-local failure, captured-connection cancellation and Voxy readiness.
A private rig compares actual Voxy storage, then tests movement/reset. Final exact
counts and artifact hashes are in `build/dh-neoforge/2026-10-01/verification.json`.
It also records a settled Voxy rendering-on/off control using owned Xvfb/llvmpipe.
Native Windows GPU rendering remains the user's manual gate.

Artifacts and runtime evidence are in the **outer** repository's
`build/dh-neoforge/2026-10-01/`: production jar, `dh-artifact.json`,
`private-run-path.txt`, private run evidence, `prism-inspection.json`,
`prism-installation.json`, and server preparation/ownership/readiness receipts.
The production jar is a normal Gradle shadowJar; metadata remains0.16.0, external
filename marks dev. The test-only fixture never enters production jars or Prism.

## Changes to examine

| Area | Files |
| --- | --- |
| Codec/assembly/budget/terrain/policy | `common/src/main/java/dev/vox/lss/common/dh/`, including new connection-scoped authority state |
| Session, conversion and reset | `xplat/.../networking/client/DhClientSession.java`, `DhColumnConverter.java`, `ClientCommandActions.java`, `ColumnCacheStore.java`, `ResetCoordinator.java`, native gate/glue changes |
| Voxy readiness | `xplat/.../compat/DhVoxyBridge.java`, `VoxyCompat.java` |
| Loader registration | Fabric and NeoForge same-FQN `networking/client/DhClientTransport.java`; NeoForge client bootstrap and physical-client reflective initialization |
| Settings/packaging | common client settings/schema/template; loader build files; private relocated XZ and license; `scripts/release_check.py` |
| Regression tests | common DH/settings suites; Fabric `DhClientSessionTest`, `DhColumnConverterTest`, cache/reset/native gate tests |
| Live proof | `test-fixtures/dh-live/`: owned private rig, capture and readback observer |

Recheck the original findings against the disposition, especially:

1. **Ownership and retirement.** Stamp arrival before loader handoff; preserve FIFO
   worker ownership. Check delayed callbacks, transport32-frame cap, Neo retained
   Netty slices before bidirectional flow is known, five-second orphan cleanup,
   logout, reset and exception/linkage-error containment. Guest C2S bytes must not
   enter the host DH budget. Cancel is best effort, at most once and only on the
   original captured connection. Strict cache sweep failures remain observable.
2. **Protocol/terrain fidelity.** Support outbound strings count UTF8 bytes; inbound
   Support counts UTF16 units; mapping UTF rules remain family-specific. CloseReason
   uses32-bit length. Check unfinished XZ terminal read, aggregate mapping budget,
   real official format2 frames, `AIR`/empty-state mapping exceptions and exact H+1
   AIR clipping. Opaque/H+2/outside-height spans still reject. Unknown columns never
   become air or falsely claim complete coverage.
3. **Scheduler and acceptance.** Refresh defaults0; accepted receipts held until
   reset/reconnect, explicit refresh throttled. Capacity8192 covers maximum4225 tile
   frontier. Retries30/60/120sec then park, expiry/unowned responses park one request.
   Successful partial sync can request generation for unknown columns without
   starving untouched terrain. Unsupported/out-of-range generation parks. Readiness
   must resolve actual Voxy service; absent optional backlog ABI retains conservative
   ingest-only support. Three fully rejected attempted tiles stop acquisition.
   Enqueued receipts do not prove asynchronous storage completion.
4. **Authority and native behavior.** Per-dimension authority survives portal return
   on one connection; logical world-key changes require reconnect. Reset deliberately
   retains vanilla ownership, clears receipts and strictly invalidates native alias
   cache buckets. Native non-strict cache behavior remains pinned. Native LSS stays
   default and source/profile changes are reconnect-scoped. The final asynchronous
   Voxy vanilla-overwrite race still needs the plan's live measurement.

Actual official terrain frames and provenance are committed-source inputs under
`common/src/test/resources/dh-corpus/dh-official-3.3.3-neoforge-partial/` (currently
uncommitted working files). Their capture excludes opaque handshake/world IDs.
Normalized SHA is a regression pin; independent decoded counts are separate proof.
Earlier Support captures, private upstream encoder parity and software POC records
remain available but do not substitute for missing live gates.

## Reproduce without duplicate source locators

Stop the **owned test server** cleanly before builds; it holds the shared harness
lock. Do not build beside a measured rig or the manual server. Use an isolated
source copy outside the parent repository:

```bash
rsync -a --delete --exclude=.git --exclude=.gradle --exclude=build --exclude=.worktrees ./ /tmp/lss-dh-review-build/
cd /tmp/lss-dh-review-build
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
bash tools/verify/run-gradle.sh :common:test :fabric:test :paper:test :neoforge:build :fabric:remapJar
bash tools/verify/run-gradle.sh :fabric:runGameTest :neoforge:runGameTestServer
```

Tier3 client gametests are cut on this line; their task does not exist. Existing
Sodium golden skips are not DH tests. The private fixture uses Objenesis/Mockito
only in tests; production packaging must exclude these and fixture classes.

Windows target: **LSS dev — 1.21.1 NeoForge - Copy For Far Testing**. Manually join
`[::1]:25585`, run `/lss dhstatus`, fly beyond view distance8 and reset. The installer
pins dependencies/config inputs, checks target JVM inactivity, backs up the original
jar and both legacy JSON candidates, then atomically publishes jar/YAML with rollback.
Prism credentials, existing worlds and foreground UI are untouched.

## Remaining scope and requested output

This is a local playable checkpoint, not full plan/release signoff. S0–S2/load
comparisons, native Windows rendering, vanilla-overwrite race, custom-height and
portal/source-switch gameplay, official15 packaged validation, Sodium selectors
and other MC lines remain open. Retained vanilla ownership after reset is deliberate;
state its consequence rather than silently changing it.

Report verified actionable findings first, with file/line, trigger, consequence and
suggested fix/test. Separate reasoned risks and incomplete scope. State exactly what
you ran and whether this limited NeoForge/official16 local test is ready. Do not
commit, merge, publish, contact upstream or change the implementation during review.

## Refresh/settings review extension (2026-10-01)

Review the latest [checkpoint](2026-10-01-dh-refresh-settings.md) and
`build/dh-neoforge/2026-10-01/p3/verification.json` as the current state. The artifact
SHA256 is `c01f259dae66025b8b7c5d418c710971e25ee6ce6557bef3830b175b935eab0b`.
Source/profile/refresh selectors now share the normal Sodium transaction. Inspect
string/index bindings, non-stop value preservation and reconnect timing. Inspect
`DhSessionPolicy.configureRefresh`, `refreshTimestamp` and `unchanged` plus the
actual session request/response path for stale/partial false acceptance. Disabled
sync must not regenerate accepted tiles as a refresh substitute.

Diagnostics schema 3 adds typed DH observations and keeps native handshake/protocol
fields unset. Inspect export privacy and asynchronous lifecycle feedback. Current
validation: 3221 passed unit cases, four existing skips, 86 server gametests, actual
Sodium Apply/export, and same-artifact official-16 refresh/receive/dimension/source
checks with actual Voxy storage and geometry controls. Review terminology carefully:
public Voxy enqueue acceptance does not acknowledge ingestion completion or durability;
the stricter P2 wording remains an open gate. Native Windows has a positive user
observation; the full matrix/load/custom-height/removal/race gates remain open.
Use `--no-parallel` for multi-task Gradle verification. Do not run heavy checks
while the owned manual test server holds the harness lock.
