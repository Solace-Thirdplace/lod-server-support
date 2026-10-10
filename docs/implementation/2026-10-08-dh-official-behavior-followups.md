# Official DH behavior follow-ups

Date: 2026-10-08. **Liveness caveat:** the r1 manual server was stopped by its storage watchdog at 15:30Z. Historical ready receipts describe their capture time. See [review fixes and current validation](2026-10-08-dh-post-review-fixes.md) for the later setup status.

M1–M4 complete: source, normal and packaged validation,
Prism installation and same-world manual restoration pass.
Scope: Minecraft 1.21.1, Java 21, Fabric and NeoForge; local work only.
This implements the [follow-up plan](../planning/2026-10-08-dh-official-behavior-improvements-plan.md)
on top of the [validated r9 baseline](2026-10-07-dh-parity-disposition.md).

## Acquisition diagnostics

`DhAcquisition` describes existing admission decisions with an allowlisted primary
reason, a bounded mask of simultaneous gates, and remaining local gate time.
`DhClientSession` records observations during the real scan and admission checks;
it does not scan terrain again for diagnostics. Cached conditional checks and
ordinary requests share the same service admission calculation. Between scans,
cheap checks retain visibility of observed service rate/slot waits alongside the
scan cadence.

The new snapshot/export fields are `acquisitionWait`, `acquisitionGates` and
`acquisitionWaitMillis`. Opt-in `dh_status` rows use `acquisition_wait`,
`acquisition_gates` and `acquisition_wait_ms`. `/lss diag` translates the primary
reason in all three locales. Existing status and trace fields remain available.
There is no new setting or command.

The reasons distinguish initialization, Voxy readiness, cache work, recovery,
decode/ingest pressure, request/service slots, server/local/manual pacing,
Support startup, refusal backoff, server restrictions, parked/deferred work,
outstanding replies and idle current interest. `REQUESTED` means local admission;
it is not a server response or destination-write receipt. Idle current interest
does not prove the whole requested world is present.

Waits describe gates, not delivery estimates. Readiness/slot dependencies have
value **-1**. Simultaneous known timer gates report the longest remaining bound;
unknown readiness dominates combined timer information. The secondary mask can include informational reasons from different tiles. After the October 8 review fix, the primary reason and wait belong to the first candidate reaching admission; unrelated parked tiles cannot mask that blocker. Terminal scan observations are retained and aged between scans.
Outside an owned session, the mask is zero because no admission observation exists;
the availability state and primary hint still describe the paused/unavailable source.
The trace keeps its existing one-second cadence, with no new per-tick transition
events, identifying text or probes. Admission and retirement clear old observations.

## Expiry under continuing traffic

A corrected deterministic regression reproduced the previous defect: unrelated
control frames kept a silent synchronization request pending beyond its deadline
and prevented cleanup of an old incomplete buffer. The first draft of the test
mistakenly registered the helper's request twice; that failure is retained separately
and is not the reproduction evidence.

Request timeout and worker-owned assembly maintenance now use the oldest
outstanding captured/processing arrival as a conservative cutoff; with no
outstanding capture they use current time. Production capture timestamps are
assigned under the same ownership lock used by cleanup, before loader callback
queuing. A new production capture cannot publish an older timestamp after cleanup
has passed it. Explicit timestamps remain a deterministic test seam.

Timely queued replies and processing frames retain their protection. Traffic
arriving after a deadline no longer requires a globally empty capture set before
eligible cleanup. Assembly mutation remains on its single worker. Normal/startup
birth lifetimes, idle deadlines, byte/count bounds and unanswered-request policy
are unchanged. A hung pre-deadline capture can still delay the cutoff: this is
protection of owned work, not a hard real-time worker-completion guarantee.

Official generation retains cancellation; a local sync timeout does not pretend
the server released its uncancellable permit. Support still gets no cancel frame.
Generation pruning retains its all-captures-drained guard: anonymous fragments
cannot safely identify which generation request they belong to, and an expiry
cutoff does not establish that a still-valid captured reply is unrelated to pruning.

## Fragment allocation

Assembly now retains `Split`'s internally immutable fragment array under its own
reservation instead of cloning it a second time. The public constructor and byte
accessor remain defensive, and the package-private borrow documents that it must
never be mutated or exposed. Codec/capture leases cover overlap; assembly reserves
before retention and owns the lease after the envelope owner closes.
Flattening, decoder/cache interfaces and all memory limits remain unchanged.

The offline Java 21 probe compares isolated baseline and proposed assembly classes
against retained actual frames. It includes 30 warmups per corpus, three alternating
before/after JVM pairs, and five samples of twelve tiles per pair. Capture, envelope,
assembly, flattening, terrain decode and source-cache write/read costs are separate.
Normalized terrain hashes and accounted peaks match before/after; ownership returns
to zero after each replay.

| Corpus | Fragments | Assembly allocation saved per tile |
| --- | ---: | ---: |
| Official 3.3.3 partial | 2 | 28,856 bytes |
| Official 3.2.0-b | 2 | 49,792 bytes |
| Support 0.14 meadow | 3 | 37,216 bytes |
| Support 0.14 ocean | 1 | 10,528 bytes |

The measured assembly source was the pre-final prototype (`fb3ab820…`), while the shipped r1 source was `fb028c6a…`. Its `accept()` implementation was identical, supporting the byte-saving claim. CPU ratios are noise-dominated and establish no speedup. These are per-thread offline measurements for the copy-removal decision. The
phase timing includes measurement overhead; it is not an FPS, whole-client CPU,
network, Windows-cache or server-generation improvement claim. Larger composite
decoding and full adaptive congestion control remain deferred.

## Validation and evidence

Candidate: `0.16.0-dh-followups.dev20261008.1`.
Build copy: `/tmp/lss-dh-followups-20261008-r1`.
Evidence root: `build/dh-neoforge/2026-10-08/official-followups/` in the parent
repository, not this separate clone's build directory.

Normal validation passes **3,408 production tests**, zero failures/errors,
four optional skips: 1,040 common, 1,809 Fabric passes out of 1,813 found,
536 Paper and 23 NeoForge. **78 Fabric + 8 NeoForge gametests** pass.
All six LSS/VSS artifacts, release/packaging checks and catalog checks pass.
Focused tests are subsets of these counts. Earlier test-fixture setup failures,
the red reproduction and the missing fixture-classpath export attempt are retained;
none substitutes for final validation.

Final candidate SHA256s:

* NeoForge: `f053642d09296b57f95f61521223088c5e16538c621a6bbb9deb9dbfc03c788b`
* Fabric: `cdabf6677c4e9fa34a62c96524a126337240cd1392f4375584aff85c307810f8`

`verification.json`, `source-manifest.json`, `test-source-manifest.json`, unit XML,
normal-build/packaging logs and fixture receipts bind the source and artifacts.
`m2-before-corrected.xml` records the actual pre-fix assertion failure.
`allocation-comparison.json` binds the probe, isolated classes, libraries, corpus
hashes and repeated results. The probe source lives at
`tools/dh/DhFragmentAllocationProbe.java` and is not packaged.

Ten packaged cases pass. The private matrix covers both official versions, Support, both loaders, movement,
native precedence, live/load/rejoin/reception controls, no-service reset protection
and real-DH conflict, plus bounded pressure fault injection at the actual capture
boundary. The pressure case stays on the same session/connection, clears incomplete
buffers and resumes terrain. This is synthetic fault coverage, not evidence that a
normal upstream workload produced that pressure. Four separate Java observer tests
and seven bounded-storage tests also pass; an empty Python test discovery is not
counted as validation. No new Windows GPU or server-load comparison is scheduled.
Retired dependency-copy cleanup preserves raw logs/proofs, mods, worlds and original
distributions and verifies process creation identities before deletion.

The first live-update attempt exposed an observer boundary problem: it compared
requests from before asynchronous reception OFF had applied. Revision 2 measures
the OFF interval only after application/retirement, retains the push-only checks,
and records requests while pause is pending separately. A capture observer was
also narrowed to the owning overload to avoid double counting forwarding calls.
Both revised loader observers and the affected live-update case pass against the
unchanged production artifact. The new check reports zero requests and pushed
headers during applied OFF and zero target payloads/conversions on unchanged rejoin.

Original observer bindings and failures remain under `observer-r1/` and
`observer-r1-sources/`; revised bindings are under `observer-r2/`. Each matrix row
identifies its observer. A failed private pressure overlay is retained separately;
the corrected overlay waits for capture room and preserves the normal scripted
frontier while its recovery assertion runs. Its recipe, sources/classes and result
are independently bound. Storage-rejected attempts created no run and are recorded
separately from product failures. Cleanup removed 2.04 GiB of canonical-identical
libraries from inactive old Windows benchmark attempts; their data/evidence remain.

`completion.json`, `live-matrix.json`, `live-retirement.json`,
`prism-installation.json` and `manual-server-restoration.json` close the follow-up.
The selected Windows Prism instance contains the candidate above; preferences and
other mods match the pre-install snapshot and the prior jar is backed up.
The manual server uses the same `dh-neoforge-playable` world at `[::1]:25585`;
OP/DH configuration hashes and semantic properties match. Fresh console list/save
acknowledgements and a protocol-767 status reply confirm readiness.

## Short manual course

1. Launch the selected “LSS dev — 1.21.1 NeoForge - Copy For Far Testing” instance
   and join `[::1]:25585`.
2. Run `/lss trace` to start, fly into new terrain and, during any apparent pause,
   run `/lss diag`. Stand still for about 30 seconds and run `/lss diag` again.
   The acquisition reason should identify slots, pacing, cache/recovery/pressure
   or idle interest; -1 is unknown readiness, not a promised duration.
3. Run `/lss trace` again to stop. Rejoin and check that existing Voxy terrain
   remains available while missing/changed terrain is acquired. Provide the trace
   path if loading remains stalled; no additional performance measurement is needed.

The user's movement pause has no confirmed ordinary-play cause yet. The new
diagnostics make another paused interval explainable; the timeout reproduction
does not establish that it caused the reported pause.
The [accepted Voxy ordering/completion limitation](2026-10-07-dh-accepted-limitations.md)
remains accepted, and upstream outreach/release qualification remains separate.
