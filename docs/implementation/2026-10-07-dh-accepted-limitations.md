# Accepted DH compatibility limitation

Decision date: 2026-10-07 (local). Scope: the current experimental Minecraft
1.21.1 DH compatibility feature on Fabric and NeoForge.

The user accepted the known Voxy queued-write limitation for now: “just document
the known limitation and that we are ok with it for now.” **This limitation is an
accepted risk, not a release blocker.** The earlier I1 kill criterion and strict
per-write completion/persistence gates are waived at this supported-API boundary.
Other correctness checks and release prerequisites retain their existing scope.

## What can happen

Voxy's supported `rawIngest` API confirms that a write was queued. It cannot cancel
that write, fence it against newer vanilla terrain, or report per-write completion
and persistence. A DH write queued before a vanilla chunk arrives can therefore
finish afterward and replace newer, more accurate terrain in Voxy's LOD storage.
Queue acceptance and backlog zero also do not prove that the write completed or
was saved.

The [controlled ordering experiment](2026-10-05-dh-voxy-commit-gap.md) reproduced
an older DH write replacing newer vanilla data after an artificial 4516 ms delay.
That proves the race exists; it does not establish its frequency during ordinary
play. Acceptance does not turn this failed absolute-ordering guarantee into a
passing correctness result.

## Current handling and follow-up

Keep the current vanilla ownership checks at admission and delivery, session and
dimension retirement guards, source-cache validation and Voxy readback checks.
They reduce exposure and prevent unsupported cache/completion claims, but cannot
revoke an already queued write. No production behavior changes are required by
this decision, and persistent successful-write receipts remain unsupported.

Revisit this decision when a supported Voxy cancellation/fencing/completion API
becomes available, or if ordinary play reports make the visual effect unacceptable.
The proposed API contract and counterexample remain recorded for that work.

The [latest validation](2026-10-07-dh-full-feature-validation.md) still passes its
recorded normal and functional checks. Historical reviews and raw receipts retain
their original blocker/correctness fields as evidence of the assessment before
this acceptance; this dated decision supersedes their blocker classification only.
