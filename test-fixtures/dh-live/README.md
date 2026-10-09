# Private DH live verification

This is a separate, test-only NeoForge observer and owned private rig. It never
enters the production jar or Windows Prism target. `Probe` uses production DH
transport, session, conversion and Voxy ingestion. It samples converted columns,
compares actual Voxy storage after eligible receipts, aims at a distant solid
column, exercises movement and drains/resets/reacquires the session.

The visual off control cancels actual Voxy geometry passes in a fixture mixin;
vanilla terrain and Voxy storage remain intact. Changing Voxy's config flag alone
does not disable an already-created renderer in this version. Enable statistics
before joining because Voxy bakes that shader option at renderer construction.

Use Java21 and the shared harness lock, an owned Xvfb display and llvmpipe. Prepare
only distribution libraries and pinned mods into a new run, never an existing
world/config/account context. Storage admission and runtime monitoring are required.
If the manual test server is running, stop its recorded owner before another
heavy build or live rig and restore its existing world afterward.
The authorized private Prism context is used by `launch_prism.py`, with frozen
launcher inputs; the Windows foreground client is never automated.

Export the exact classpath from the isolated build with
`bash tools/verify/run-gradle.sh -I <checkout>/test-fixtures/dh-live/classpath.gradle :neoforge:exportDhFixtureClasspath`.
Build `build.py --build <isolated-build> --artifact <normal-candidate.jar> --output <test-only.jar>` after the
normal production build. `exercise.py` requires explicit `--artifact`, `--fixture`, `--build` and
`--verification` paths; run it under `harness_run_script`. The verification JSON
must bind normal-build success and candidate/fixture SHA256s. The fixture receipt
checks the build root, compiled source hashes and ABI artifact; the production
manifest must match both the review checkout and build copy. The supervisor
owns server/display/launcher children, stops and saves the server, and terminates
only its own private client process group. See `verification.json` for exact final
fixture/artifact hashes and the selected successful run. Retained earlier observer
attempts are not passed rendering evidence.

The `--scenario lifecycle` default additionally verifies hot sync-on-load controls,
waiting for each applied preference before toggling again, conditional unchanged
replies on a new load observation, and no periodic receipt timers. It also covers
receive on/off, Nether/End, Overworld return and reconnect-scoped native source
activation against a no-LSS server. `--scenario menu` uses the installed Sodium
implementation to build controls, Apply the DH fallback permission and hot
sync/live controls, and export typed DH diagnostics. Pass explicit `--artifact`, `--fixture`
and `--base` to `exercise.py` to bind a new candidate and retain separate attempts.
The final P3 receipts are under `build/dh-neoforge/2026-10-01/p3/`; do not overwrite
the original playable proof or merge test fixtures into a shipping jar.

`--scenario boundary` checks a cold, stationary vanilla/LOD border on NeoForge,
with client render distance 12 and server effective distance 8. Generation is
disabled. A fresh tall flat world supplies an independent stone expectation at
Y=-121. Every loaded column outside the actual camera-relative drawing circle
must exist in Voxy, with nine stone samples per column; a missing section fails.
Use `--terrain-source dh` or `native`; native mode stages the same candidate on
the server too. `--boundary-expect report` records a baseline reproduction without
turning missing terrain into a correctness pass; `covered` is the default gate.

`--scenario updates` establishes known red/blue terrain after the dimension's
DH service starts, with live updates off while establishing a complete source
and destination baseline. Production full-content comparison is a premise,
alongside independent block checks. An unchanged reconnect must preserve the
target with zero payloads/conversions. Actual Sodium Apply then enables live
updates and disables sync on load: server-console edits must push new colours
and air removal without new requests. Local live-update Off must retain terrain
for 30 seconds. The case finishes with the actual Sodium menu at Auto scale;
require `dh-updates.json`, `dh-menu.json` and `dh-result.json` to all pass.

The historical timer-based P3 review follow-up is under `p3-review-fixes/`. Its lifecycle scenario uses
actual Sodium Apply while connected, exports READY-state diagnostics, and disables
refresh with successful receipts due within a 45-second observation window. It
checks beyond the ten-second limiter and verifies that receipt timers are held.
Budget values, no-sky sample counts and the llvmpipe renderer check are observations;
other boolean pass fields summarize assertions in the fixture.

`--scenario absence` uses sync range 40 with the real official server's generation
distance limited to 8. It observes empty replies beyond generation reach, rejects
repeat full requests to those positions, waits for acquisition to settle, and observes that elapsed time alone does not
retry held misses. The former periodic-refresh phase was retired with the timer. These private attempts have a deadline and never alter the manual server's
configuration or world. Pass `--build <isolated-build>` so settings staging uses the
matching common jar rather than the historical build copy.

`--scenario content` stages the same candidate on both the NeoForge server and
client, alongside official DH on the server. A private datapack defines a world
from -128 through 383. Console commands seed a 64×64 slab at Y=350 and another at
Y=-118, including the four corners of a negative-coordinate tile outside vanilla
view. Generation and unsolicited DH updates are disabled.

The content range is 12 chunks. Initial native red/blue slabs warm a disk cache
bucket with real server stamps. The client physically disconnects before the
supervisor saves lime/cyan slabs, so native delivery cannot prepopulate the new
expectations. DH must convert all 16 target columns and update actual Voxy storage
with those new colours. A gold slab at Y=340 keeps each upper 32³ section present;
a missing/unreadable section is never interpreted as air. Storage checks run at
most once a second and include 300 corner-inclusive samples across the three levels.

The fixture waits for no untouched eligible frontier, no pending requests, and
five seconds of stable request counts. It explicitly toggles sync on load through Sodium Apply,
first requires an owned unchanged response for the target, then edits lime→air
and cyan→purple in the same DH epoch. The removal uses an explicit load recheck, with a bounded deadline. A pass requires a newer
accepted receipt linked to the same owned changed response and 16 converted target
columns, real air/purple storage, and intact gold sentinels. Source-specific storage
observations establish this fixture's eventual ingestion, not a public general
Voxy completion acknowledgement.

Another physical disconnect lets the supervisor save yellow upper terrain before
the native reconnect. That phase requires v20, 16 native columns containing the
new expected blocks, matching Voxy storage, no new DH asks, and zero retired DH
budget. Native suppression and DH liveness are checked continuously. Source-switch
commands are issued only against the owned fresh world and acknowledged after
save. Permission readiness/teleport retry, full seeding acknowledgement and aligned
1800/1860-second client/supervisor ceilings keep rig timing explicit. Observer
errors are contained in a fixture failure flag and fail the test promptly.

`dh-requests.jsonl` records each ask/reply's position, timestamp, monotonic and wall
time, and changed/unchanged/empty outcome. Target requests, fragments and responses
are retained separately with a 256-frame cap; content mode skips generic control
captures. Request level keys are replaced with the public fixture dimension by a
labelled reconstruction; upstream terrain/response frames remain verbatim.

The first P3 content run missed the target conditional-request deadline. Both
frontier preemption and fixed-order starvation fit its evidence; the separate
regression demonstrates the latter. Its identical native/DH storage comparisons
are not attributable to DH. See the P4 review disposition for corrected candidate
results and the preserved historical failure.

Observer failure tests run separately under the real Fabric JUnit loader:
`bash tools/verify/run-gradle.sh -I <checkout>/test-fixtures/dh-live/fixture-tests.gradle :fabric:test --tests dev.vox.lssfixture.dh.ContentProbeTest`.
The init script includes the observer only in the test source set; shipping jars
remain unchanged. Preserve the normal full-suite XML before this focused task.

## Service matrix and queue-order counterexample

`--scenario service` runs bounded packaged service checks on NeoForge or Fabric
(`--loader fabric`). Export `:fabric:exportDhFixtureClasspath` as well for Fabric;
its observer is compiled against the actual named Fabric classes/classpath and
remapped to intermediary with the pinned mappings. The private client uses Fabric
Loader 0.18.4, matching the shipping mod's minimum. No Windows instance is reused.

Select `--server-family support --profile dh_support_15` for the pinned Paper /
DH Support 0.14.0 server, and `--builder FastOverworldBuilder` for its simplified
builder. `--profile official_15 --dh <verified-3.2.0-b.jar>` tests official protocol
15. The default is official protocol 16. `--server-family vanilla --expect no-service`,
`--expect conflict`
with the real official DH client verify bounded adapter stop, continued ordinary
Minecraft connectivity, and a 30-second observation without further LSS asks.

The `--profile` argument is the expected producer, never a client setting.
Ready cases require the automatically detected production profile, at least three accepted tiles,
and actual Voxy storage comparisons with at least four columns and 100 solid
samples. Voxy canonicalizes cave/void air to its air entry. A missing all-air section
is excluded from generic storage evidence and recorded as unvalidated air samples;
any missing expected solid section still fails. It is never counted as an air match.
The custom-height/removal observer remains separate and strict: its gold sentinels
require the upper sections to exist before they can establish removal.

`dh-resources.json` records typed production diagnostics, peak owned budget,
converter CPU/wall time and allocated bytes. Server `/proc` CPU/RSS samples include
boot, generation and the full test, so they are descriptive measurements, not a
same-area comparison against an official DH client. llvmpipe frame times are not a
Windows GPU performance claim. Startup rejection/crash reports fail promptly while
preserving the owned log rather than waiting out the whole test ceiling.

`--scenario race` applies an intrusive **test-only** queue barrier to the pinned
Voxy port. It holds a real target DH write, changes the server's target to yellow,
lets vanilla load/commit the newer content, then releases the older red DH write.
`dh-race.json` records real write ordering and storage. An expected counterexample
has `dh-result.pass=true` for the experiment but `correctness_gate_passed=false`:
never count that as an I1 correctness pass. The mixin plugin excludes this barrier
from every other scenario and all shipping jars. This experiment does not measure
how frequently the artificial delay occurs during normal play.

## Ocean lighting readback

`--scenario service --expect ocean` seeds two private 64-block tiles outside vanilla
view distance with 24-deep and 5-deep water over sand. It checks upstream floor
light as an independent oracle for the neighboring water voxel, then reads
actual Voxy storage. Both depths cross section boundaries; block light and
surface water light are also checked. `dh-ocean-light.json` records the samples.
The fixture changes only its disposable server world.


## Reconnect regression prepared after the 2026-10-05 review

`RejoinProbe` now visits the target with real vanilla chunks, waits for a stable
raw destination digest, retreats, disconnects and rejoins. The unchanged target
must retain that digest and submit no conversions. This replaces deliberate
one-voxel damage/repair, because unchanged cached differences may be refined
vanilla terrain. The fixture has compiled on both named loader classpaths. The source-only
checkpoint preceded live qualification; fresh packaged clients on **both loaders
now pass** this stage. Its stable baseline tests per-run preservation, not a
general asynchronous completion acknowledgement.

`production_comparison_premise` records the production full-content wait before
warm reuse. Only the 300 known-block samples per stage are an independent oracle;
they do not validate all biomes or lighting. Zero target payloads/conversions are
target-scoped; partial/missing neighboring tiles can still download. Preserve the
historical original evidence and use its audit correction receipts for labelling.

## Sync and live updates (2026-10-06)

The timer no longer exists. `--scenario updates` connects to stock official DH
3.3.3 with generation disabled and real-time updates enabled. It seeds the same
tall-world red/blue/gold fixture, then disables sync on load through a validated config-file reload. Live lime/cyan edits and air/purple removals must arrive without any terrain
requests. The observer reads 900 independent expected blocks from actual Voxy
storage; gold in the same sections establishes that removal is real air. Before
edits, it also disconnects/rejoins the unchanged target and requires an owned
unchanged reply with zero target payloads and conversions (300 further samples).

Between edits it disables live updates, changes the server to diamond/emerald,
and observes the retained lime/cyan destination for 30 seconds with no accepted
updates or new requests. Re-enabling live updates and sync on load must renegotiate
the same session and ordinary connection. Server commands are run-owned and
acknowledged after saving. This establishes eventual block storage in this case,
not a general ingestion/persistence acknowledgement or a Windows GPU visual pass.

The same run then disconnects to the title screen and executes the real Sodium
menu scenario at Auto scale in a 1280×720 window. `dh-updates.json` and
`dh-menu.json` must both pass; the final `dh-result.json` represents menu completion.

Automatic source/protocol selection (2026-10-06): native boundary with
`--terrain-source native --allow-dh` stages both services, enables Allow DH Server Terrain and
requires native v20 with zero DH terrain requests. Older official/Support service
cases begin with the production protocol-16 probe and permit exactly one
negotiation reconnect before checking the detected profile and Voxy storage.
The prior forced native→DH→native `content` source-cycle scenario is retired;
its old receipts remain historical. No production profile override is written.

## Follow-up review qualification

`--scenario service --loader fabric --server-family vanilla --expect no-service`
checks missing remote-channel handling with no Minecraft reconnect. `--slow-config`
requires a separately compiled private overlay containing the DH-linked delay mixin;
preparation refuses the public observer instead of silently running without a delay.
The historical cell delayed the real official config by 6.5 seconds and required
both slow-config markers and zero negotiation reconnects. That DH-linked source stays
outside the public fixture. Support-15 now requires zero negotiation
reconnects; official-15 uses one quiet-probe reconnect.

`--scenario boundary --terrain-source native --allow-dh --moving-border` exercises
actual movement after a cold fill. Require more than 100 blocks of movement and
30 seconds of motion, no continuously missing border column for over five seconds,
native protocol20 and zero DH **terrain Request** frames. The observer does not
claim zero control packets. Updates comparisons use an actual AtomicLong read
comparison counter, including settling retries; earlier 1,200 labels were formulae.
Full Fabric JUnit counts with `fixture-tests.gradle` include four observer tests,
which should be identified separately from production suite counts.

The opt-in `--bounded-storage` flag reuses P4's existing 2 GiB growth policy for
these serialized functional checks. It preserves the 50 GiB reserve, verifies
actual staged copy bytes, and carries the immutable policy into the runtime
monitor. Generic admission defaults remain unchanged.

Service checks require DH network trace rows, source-specific unavailable values
and the startup bandwidth preference. Update checks include a receiver-OFF edit
observed before the adapter drops packets, followed by resuming on the same
Minecraft connection with saved update preferences intact.

## Menu simplification (2026-10-09)

The shared catalog has General and Far Players pages. General includes the
**Allow DH Server Terrain** checkbox; synchronization and live updates are file-only
preferences, both defaulting to on. Menu checks require 11 controls, no DH page,
no hidden-preference controls, the exact fallback label and preservation of both
preferences when applying the checkbox at Auto GUI scale. Functional observers
change those preferences through the production settings store and validated reload,
waiting for the first reload to be adopted before toggling it again.
