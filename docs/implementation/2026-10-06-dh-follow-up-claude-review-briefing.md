# DH follow-up review briefing

Date: 2026-10-06. Review all changes since
[the October 5 post-P4 Claude review](2026-10-05-dh-post-p4-claude-review.md),
including its corrections, the Sodium UI changes, sync/live behavior, the
vanilla/LOD border fix and automatic native/DH selection. This is a review task;
write findings without implementing, installing, publishing or altering personal
worlds, profiles, credentials or preferences. Work remains local; outreach is pending.

## Checkout and exact scope

```sh
cd /home/vox/projects/voxel-server-support/.worktrees/dh-client-p1
git status --short
git diff --check
git ls-files --others --exclude-standard
```

Branch: `feat/dh-codec-p1-mc1.21.1`. This directory is a separate clone, despite
its `.worktrees` name. The broad Git diff contains the entire uncommitted DH
feature, including previously reviewed changes. Use the new
[scoped diff](evidence/2026-10-06-dh-follow-up-review.diff) and
[file/hash manifest](evidence/2026-10-06-dh-follow-up-review-manifest.json).

The baseline is the independent reviewed snapshot
`/tmp/lss-dh-postp4-claude-build.bdinrZ`: all **75 after-hashes** in the previous
review scope manifest match it. The commonly cited implementation snapshot
`/tmp/lss-dh-voxy-cache-20261005` differs in five documentation/evidence files;
it is not the exact comparison baseline. The latest build copy is
`/tmp/lss-dh-auto-20261006`. The manifest includes tracked and untracked inputs,
checks changed production/test/fixture files against that copy, and excludes this
brief and its preparation outputs. Historical evidence and the previous review
report are inventoried but omitted from the unified diff. No producer corpus
binary changed in this increment. Do not apply the review diff as a patch.

The tested production manifest has **380 entries**; the test/fixture manifest has
**554**. They bind the packaged candidate to the exact inputs. The review-scope
manifest additionally lists documentation and historical evidence. Subsequent
briefing/evidence preparation does not change compiled production/test inputs.

Read `CLAUDE.md`, the
[compatibility plan](../planning/2026-09-29-dh-client-compatibility-plan.md),
especially I1–I4 and the P2/P4 gates, and the previous review before changing a
pinned tradeoff. Write the new report to:

`docs/implementation/2026-10-06-dh-follow-up-claude-review.md`

Lead with actionable findings. For each, give severity, current file/line,
trigger, consequence, evidence, and a proposed fix/regression. Distinguish
verified defects, suspected risks and qualification gaps. State which checks you
ran independently and the source/artifact boundary. Preserve earlier reports.

## Required behavior and production delta

| Area | Resulting behavior and review focus |
| --- | --- |
| Native/DH selection | Use DH permits fallback. A remote native handshake channel or a native session-config offer wins, including a disabled/unsupported native offer. Both loader transports install remote capability checks. Native discovery always starts on eligible joins; a late native offer retires DH and cancels a pending reconnect. Review lifecycle/source races and native protocol compatibility. |
| Automatic DH protocol | No manual protocol option. Fresh joins probe official 16; strict rejection decoding distinguishes official 15 and Support 15. Ambiguous ASCII controls never independently choose a family. Stock 3.2.0-b's logger can suppress its rejection, so after five seconds an empty probe retires and spends its one guarded reconnect on a fresh official-15 trial. A valid advertisement also permits that one retry; malformed/unsupported input cannot, and no trial accepts terrain without a successful handshake. Check leases, epoch guards, slow responses, retry continuation/reconfiguration and same-address software changes. |
| Settings/UI | Separate DH tab between General and Far Players. Three checkboxes: Use DH, Sync on load, Live updates. The final catalog has 13 controls; both Sodium generations share Apply and the cross-page receive gate. Source permission is reconnect-scoped; sync/live are hot. Retired timer/profile YAML is preserved but inactive. Enum support/stubs from the earlier UI increment remain generic, with no active protocol selector; compile-only Sodium stubs must not ship. English and both Chinese translations use short labels. |
| Source cache | Preserve differing nondefault Voxy columns when restoring unchanged/offline sources; reconstruct only fully default columns and defer unknown readers. New/partial/absent replies invalidate older retained source before conversion or replacement. Quota is 8192 files/256 MiB with shared indexing/LRU and protected working sets. Review timestamp-zero behavior, transient retry, clearing, reparse/symlink handling and limits. |
| Sync on load | Optional conditional checks on load/re-entry, with the upstream twenty-minute load debounce. Retained source is validated before restoration when sync is enabled; validation is not starved behind a restore sweep. No periodic refresh timer or stationary polling. Explicit enable/config changes create a load observation. |
| Live updates | Optional server-authorized unrequested message 9 updates and message 10 fragments. Only fully described Minecraft columns are handed off; unknown columns are preserved. Partial data invalidates the old whole-tile source entry, retires competing work and uses bounded per-column timestamp fences. Check old/equal full replies, missing stamps, unsolicited distance/key/profile checks and resource retirement. Enqueue counters are not completion receipts. |
| Lighting | Depth attenuation follows resolved fluid states, including flowing water, kelp and seagrass. Shared conversion/comparison semantics retain block light and no-sky behavior. |
| Vanilla border | `VoxyVanillaCoverage` periodically checks actual held vanilla chunks outside the effective drawing boundary, instead of assuming requested render distance. Bounded 64 lookups/tick, 8 columns/s, 128 backlog, one-second retry and weak references/maps. Review readiness/light retries, per-world ingest enablement, reset/retirement and empty producers. |
| Operations | Detached owned manual-server supervisor/FIFO console, read-time liveness and exact process identities. Never infer readiness from an old receipt alone. |

The scoped manifest is the complete file list. Important shared files are
`DhClientSession`, `DhProtocolDiscovery`, `DhSessionPolicy`, `DhAssembly`,
`DhTerrainCache`, `DhColumnConverter`, `VoxyTerrainCache`, `VoxyVanillaCoverage`,
`ClientNetGlue`, `ClientOptionCatalog`, and settings/diagnostic definitions.
Loader menu/transport twins and language/template resources are part of the scope.

## Previous findings to recheck

[The disposition](2026-10-05-dh-post-p4-review-disposition.md) maps H-1/H-2,
M-1–M-5 and L-1–L-4 to corrections and tests; it is an implementation account,
not an independent verdict. Verify preservation on cached mismatch, quota/LRU
behavior, stale-source invalidation, network validation ordering, the complete-tile
cache contract, owned server launch, fluid light, transient cache deadlines,
seed/temp-file hygiene and the second authority check before accounting.

Also review undecodable-cache invalidation; current-address/global clearing with
foreign content; reset refusal when retired workers do not terminate; zero-stamp
full checks; the `!dh-terrain-v1` namespace and hash privacy; Windows junction
`isOther()` refusal; and reader/mapper/engine identity, reference lifetimes and
nonthrowing close. The earlier interval-flip tests protected M-4. The timer has
since been removed by user request, so assess the final one-shot load obligation,
not the retired periodic implementation.

## Upstream evidence and tests

The [automatic-selection account](2026-10-06-dh-automatic-selection.md) links
primary DH source/version evidence and describes the stock 3.2.0-b logger failure.
Do not assume DH negotiates older versions: official clients require one exact
wire version. Its newer server rejection closes the DH session. DH Support 0.14.0
has a distinct version-15 encoding. Existing frozen producer corpus tests remain
load-bearing. The private `--profile` flag is an expected producer assertion;
it does not configure the client. The old forced native→DH→native content-cycle
scenario is retired because native now wins on dual-service servers; its old
receipts are historical, not rerun claims.

Final qualification and installation receipts are summarized below. The local
root is `build/dh-neoforge/2026-10-06/automatic-selection/` in the outer checkout.
First failures and their inputs/artifacts are retained; a later pass does not erase
them. The private display rig uses software OpenGL and owned disposable worlds.
It cannot establish Windows GPU performance or all supported runtime combinations.

For independent full verification, use Java 21 and the repository harness wrapper:

```sh
review_root=$(mktemp -d /tmp/lss-dh-followup-review.XXXXXX)
rsync -a --exclude=.git --exclude=build --exclude=.gradle ./ "$review_root"/
cd "$review_root"
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 CI=true bash tools/verify/run-gradle.sh \
  --no-parallel -Pmod_version=0.16.0-dh-followup.review \
  -I test-fixtures/dh-live/classpath.gradle \
  -I test-fixtures/dh-live/fixture-tests.gradle \
  :fabric:build :neoforge:build :neoforge:runGameTestServer \
  :paper:test :paper:shadowJar vssJars
python3 scripts/release_check.py --version 0.16.0-dh-followup.review
python3 scripts/release_check.py --selftest
python3 -m unittest tools.packaging.test_sqlite_capsule
```

The shared harness must be idle for heavy checks/private runs. The manual server
normally owns it after setup; check `stop-server.py --status-json` under the
outer `build/dh-neoforge/2026-10-01/` setup. Do not bypass/unlink the lock, kill by
port/name or operate the user's Windows foreground. A claimed independent green
must bind its own exact sources, artifacts and run evidence.

## Gates still open

Strict P2/I1 remains open: Voxy's supported ingest API acknowledges enqueue, and
an older queued DH write has been demonstrated to overwrite later vanilla content.
The cache-preservation correction applies to unchanged/offline restoration. New
full payloads and live pushes still have that separate ordering/completion limit.
No change here claims persistence acknowledgements or revocation of queued writes.

Still unqualified: range-128/large-cache and multidimension pressure; arbitrary
antivirus/disk-full/reparse races; sync-disabled offline-edit behavior; packaged
plant-light cases; complete biome/light/mip comparison; matched official-client
load; heterogeneous backends changing family during reconnect; Windows GPU
performance; all other Minecraft lines and all loader/Sodium runtime cells.
Earlier functional evidence is referenced for its actual boundary, not silently
promoted to a fresh test of this automatic-selection candidate.

## Final qualification and handoff

Version: `0.16.0-dh-auto.dev20261006`. Installed NeoForge SHA256:
`c0713646df836d14a76eaddf59ea7a2139312eb7c9802f9653f2cf8e507a5893`.

The normal Java-21 gauntlet passes **3326 unit tests** (four opt-in Fabric skips):
common 1012, Fabric 1755, Paper 536, NeoForge 23. All 78 Fabric and eight NeoForge
server gametests pass, as do six LSS/VSS packaging checks, 126 release selftests
and nine SQLite helper tests. The generated settings inventory/reference checks
and whitespace/Python syntax checks pass. The final correction reran affected
loader units, builds and both server suites; unchanged common/Paper tasks reused
their prior green results. There is no Tier 3/Folia task on this line.

| Fresh exact-candidate NeoForge case | Evidence |
| --- | --- |
| Official 15, `attempt-1gq67clh` | Automatically selected OFFICIAL_15 after one quiet-recovery reconnect; 12 accepted tiles, 18,432 storage samples, 13,271 solid samples across 17 columns. |
| DH Support 15, `attempt-2n_p4_ep` | Automatically selected SUPPORT_15 from the strict rejection; one reconnect, 14 tiles, 63,744 samples, 23,213 solid samples across 32 columns. |
| Dual native/DH, `attempt-kbc_g5to` | Use DH enabled; native v20, zero DH terrain requests; 71 actual vanilla chunks outside drawing, zero missing Voxy columns, 639 independent stone comparisons. |
| Official 16 updates/menu, `attempt-mgsa8p9u` | Direct protocol-16 acquisition; controlled warm rejoins with zero target payloads/conversions; hot sync/live negotiation, push-only edits and air removal, live-Off preservation, 1200 block comparisons; actual Sodium 0.8.12 Auto scale 3 with 13 controls/three DH checkboxes. |

See the copied [verification receipt](evidence/2026-10-06-dh-auto-verification.json),
[private results](evidence/2026-10-06-dh-auto-private-summary.json) and
[manual setup receipt](evidence/2026-10-06-dh-auto-manual-setup.json). Full logs,
XML, captures and fixture provenance remain under the outer runtime root above.
The initial stale schema-count assertion, direct Python import invocation failure,
and both failing official-15 discovery approaches are retained. The logger failure
was checked against stock jar bytecode; the unsuccessful same-connection trial
reached its explicit observer deadline. None is presented as a benign test flake.
All 24 recorded private process/display identities are retired.

The selected Windows Prism instance is **LSS dev — 1.21.1 NeoForge - Copy For Far
Testing**. Its prior jar is backed up under
`minecraft/lss-dh-backups/20261006T175512.262600Z`; preferences and other mods are
byte-identical. No foreground client was launched. The original
`dh-neoforge-playable` world is resumed at **[::1]:25585**, with stock DH 3.3.3,
no LSS server, OP retained, generation 32/SURFACE_THEN_CHUNKS, load sync and live
updates enabled. Fresh owner/TCP/protocol-767, Windows IPv6 TCP, FIFO list/save and
storage checks pass. At handoff the server owner is pid 215509, start 12826827;
check current liveness rather than treating that historical tuple as a ready signal.
The [manual guide](2026-10-05-dh-manual-test-guide.md) now describes automatic
selection and distinguishes the earlier two-mode border proof from this candidate.

The final scope contains **86 files**: 35 production inputs (including compile-only
Sodium stubs), 21 unit-test files, ten fixture inputs, thirteen documentation files
and seven evidence/report files. The new scoped manifest is authoritative. It covers
all compiled changes since the exact previous review, plus the new account/guide,
generated reference and retained historical/current evidence; no binary producer
corpus changed. It is a review aid, not a release-ready or strict-P2/P4 verdict.
