# Voxy commit ownership and completion gap

Date: 2026-10-05. This is a local investigation and proposed API contract. No
upstream contact or production patch to Voxy has been made.

**Current decision (2026-10-07):** the user
[accepts this limitation for now](2026-10-07-dh-accepted-limitations.md).
It is no longer a release blocker. The counterexample and unsupported strict
ordering/completion/persistence guarantees below remain valid; historical raw
results keep their original classification.

The pinned community Voxy port's `rawIngest(...): boolean` acknowledges enqueue
acceptance. It exposes no per-write completion result or cancellation/commit guard.
The backlog probe is the service semaphore's available permits; workers consume a
permit before committing. It therefore cannot prove completion, even at zero.

## Reproduced ordering counterexample

Evidence is under the outer checkout's
`build/dh-neoforge/2026-10-05/p2-p4-final/`, in the latest successful
`vanilla-authority-race` row of `live-matrix.json`. The exact port artifact is bound
in `p2-p4-completion/voxy-api-evidence.json`.

A test-only mixin holds an owned red DH section immediately before the real
`WorldUpdater.insertUpdate`. Ownership is identified by the production LSS ingest
handle and section object identity. Other DH sections are labelled separately and
cannot be mistaken for vanilla writes. The owned server changes the source to
yellow and the player teleports into its chunk. The probe requires vanilla authority
and reads **yellow from actual Voxy storage after a vanilla write**. It then releases
the older DH write and reads **red from the same storage** after that write completes.
The latest strengthened experiment reproduced this with a controlled 4516 ms delay.

The earlier experiment's non-held-write classification was less specific; its
record is preserved. The strengthened result adds explicit yellow-before/red-after
storage evidence and separates every DH-owned section from vanilla. Neither
experiment establishes the frequency of the artificial delay during ordinary play.

`dh-result.pass=true` means the counterexample experiment completed. Its
`correctness_gate_passed=false` and `release_blocker=true` are the product result.
This is an I1 failure under controlled ordering, not a passing correctness test.
Those fields record the original assessment; the dated acceptance above supersedes
the release-blocker classification without changing the correctness result.

## Consequence for P2

The main-thread checks correctly exclude already held or loaded vanilla columns
at conversion and handoff. Once Voxy accepts a write, LSS cannot prevent a later
vanilla arrival from overtaking that write. Native-view exclusion at handoff can
reduce the window but cannot revoke a write already queued outside that view before
a teleport. A delay or a backlog-zero heuristic would leave the same correctness gap.

The strict completion gate is also open. Session-local enqueue receipts and typed
status explicitly describe their limited meaning; persistent DH receipts remain
out of scope. A private observer reading storage is evidence for its particular
run and is not a general production acknowledgement.

## Proposed supported boundary

A cooperating Voxy API would need a token for each write carrying destination
identity, caller ownership and a completion result. Immediately before committing,
it should atomically check a caller's cancellation/authority guard under the same
ordering mechanism used for vanilla writes. An epoch or column-version change must
make the old write ineligible. The callback must distinguish committed, cancelled,
rejected and failed writes; a completed memory update still needs a separate
persistence acknowledgement before any cross-session receipt can be stored.

LSS can then provide a thread-safe ownership snapshot without reading Minecraft's
client chunk cache from a Voxy worker, invalidate tokens at vanilla arrival or epoch
retirement, and publish receipts only after all eligible column tokens complete.
Tests must cover a held old DH write overtaken by vanilla, retirement/reset while a
write is held, one failed column, destination replacement and completion reordering.

This contract needs coordination with the supported Voxy implementation. The
intrusive private queue mixin is intentionally absent from every shipping artifact.
Until a supported boundary exists, P2's absolute I1/completion guarantees remain
unavailable. The user accepts this boundary for now, so it does not block the
current feature; the supported API contract remains follow-up work.
