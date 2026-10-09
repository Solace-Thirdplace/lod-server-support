# DH native parity fixes and Slow Start

Status: all seven verified native-parity findings fixed, official-client behavior
adaptations implemented, final normal build and packaged-client checks passed.
The selected Windows Prism instance has the validated r9 build; the original
manual NeoForge/DH server is restored on `[::1]:25585` with the same world, OP
record and settings.

## Verified review findings

| Finding | Correction |
| --- | --- |
| N1 reset treats permission as an active service | Production reset asks whether an established, current, enabled acquisition service can refill terrain. No-service, disabled and push-only connections retain destructive confirmation. Clearcache stays scoped to the connected address and reports paused acquisition truthfully. |
| N2 receive OFF/native takeover leaves server push subscription enabled | Once per established owned listener, retirement sends disabled live/sync preferences before local drain. Saved preferences stay intact. Replacement listeners and reconfiguration cannot receive old-family controls, including through a later retirement callback. |
| N3 manual cap charged independently | One aggregate 16-column tile charge gates generation, synchronization and cached conditional requests; server clocks remain separate. |
| N4 range refusals permanently exhaust retry budget | Range failures park separately without consuming permanent rejection strikes. Request-captured, globally unique context epochs allow changed reachable interest/bounds to revive work across delayed replies and retained-policy session replacement. Actual permanent rejections remain bounded. |
| N5 transport conflict hidden from status/export | Typed, translated `CLIENT_MOD_CONFLICT` and `TRANSPORT_UNAVAILABLE` reasons reach the shared diagnostic snapshot. Raw transport exceptions do not enter exports. |
| N6 shared network tracing absent in DH | DH emits the same opt-in vanilla ping, desired chunk rate, runway/missing chunks, speed and FPS samples, using effective render distance and explicit DH wire counters. Unavailable native raw/governor/RTT values are -1. Source/owner/world changes reset baselines; stopping releases owner references and sends no further probes. |
| N7 shared options imply unsupported DH behavior | User chose to extend Slow Start to DH. Xaero remains native-only and its tooltip reflects current source selection on both Sodium generations. All three locales and generated settings references are updated. |

Additional adopted official behavior and deliberate differences are documented in
[the official behavior review](2026-10-07-dh-official-behavior-review.md).

## Validation evidence

Current evidence root:
`build/dh-neoforge/2026-10-07/parity-fixes/final/`.

Final build: `0.16.0-dh-parity.dev20261007.9`, Java 21, MC 1.21.1.

* Production tests: **3,397 passed**, zero failures/errors, four optional skips
  (1,036 common; 1,802 Fabric passes out of 1,806 found; 536 Paper; 23 NeoForge).
* Server gametests: **78 Fabric + 8 NeoForge passed**. Tier 3 remains cut on this line.
* All six LSS/VSS artifact checks and release packaging gates passed.
* Seven actual packaged cases passed: official 3.3.3 on NeoForge and Fabric;
  Support 0.14; official 3.2.0-b; live/load/rejoin/receiver-OFF behavior and modern
  Sodium Apply/export; vanilla no-service reset protection; real-DH-client conflict.
* Opt-in network traces were populated on all three DH profiles. Support used
  its local startup gate; the observed first request gap was 2,541 ms. Both official
  versions advertised 50 KB/s initially and increased to 100 KB/s during these checks.
* Receiver-OFF edits produced zero pushed-update headers in the 30-second observation,
  saved Live remained enabled, and reception resumed on the same Minecraft connection.
  The existing `same_connection_and_session` observer field describes hot preference
  phases; receive-resume intentionally creates a new terrain session.
* All private client/server/supervisor owners retired. Existing bounded-storage
  helper tests passed 12/12. Source/test manifests and observer receipts bind current
  source, build and artifacts. Focused tests are subsets of these counts.

Final artifact SHA256:

* NeoForge: `7b5f6ffd2c0954955fa1f5df2da12a85c4a0661721834b2216a5f167a00e938f`
* Fabric: `c05766598c6649d1d53540cb714fc2bfec9769275c9eeeddb0a8072d760f0813`

`verification.json`, `live-matrix.json`, `live-retirement.json`,
`prism-installation.json` and `manual-server-restoration.json` contain receipts.
The installation preserves all client preferences and other mods, and keeps a
backup of r7. The server has fresh console list/save acknowledgements and a
Minecraft protocol-767 status reply; semantic properties and exact OP/DH config
hashes match the pre-stop snapshot.

Earlier r8 checks are retained separately. Their matrix was intentionally stopped
when the installed Support plugin proved its advertised bandwidth field unsupported;
r9 adds local pacing and reruns all seven cases. Initial observer-only compile
errors and build attempts are retained. No historical r7/r8 pass substitutes for
the final candidate checks. No new performance measurements were run.

The private updates observer now samples real DH traffic before production drops it,
so receiver-OFF verification can detect server pushes even without a live adapter.
The private service observer checks DH `net` rows and advertised startup budgets.

For these explicitly authorized bounded tests, the existing P4 policy preserves
50 GiB of host free space, allows at most 2 GiB owned growth and checks physical
capacity every five seconds. Generic rig requirements are unchanged. The manual
server receives the same opt-in monitor on restart; its world/config/OP state is
preserved. No new performance benchmarks are part of this validation.
