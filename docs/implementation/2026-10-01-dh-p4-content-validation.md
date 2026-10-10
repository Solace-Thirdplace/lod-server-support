# DH P4 content validation and refresh correction

Status: local implementation in progress. The new content scenario is prepared;
its first packaged run missed the target conditional request deadline. Both
acquisition-frontier preemption and fixed-order starvation are consistent with
that live result; the separate regression demonstrates the latter. The correction compiles and
passes the supplemental standalone session suite. The normal build, live rerun,
Prism update and manual server restart await restored execution access.

Work remains uncommitted and unpublished in `feat/dh-codec-p1-mc1.21.1`. Outreach
remains pending. This advances [P4](../planning/2026-09-29-dh-client-compatibility-plan.md#p4--packaged-functional-and-resource-validation);
it does not close the complete live matrix or release gates.

Follow-up: [P4 review disposition and current verification](2026-10-05-dh-p4-review-disposition.md).
On 2026-10-05 the corrected private content/lifecycle runs and captured-content
regression passed; Prism was updated and the same manual server world resumed.
This document's pending-check, restriction and installed-jar paragraphs describe
the original attempt, not the current candidate.

## Live finding

The private NeoForge content scenario installs the same production candidate on
the server and client. Official DH 3.3.3 is also on the server; the client has Voxy,
Sodium and no DH client. The fixture uses a fresh world, a private Xvfb display and
llvmpipe, and does not operate the Windows desktop.

A datapack defines a skylit dimension with minimum Y=-128 and height 512. Console
commands place two 64×64 slabs in tile `(detail=6,x=-3,z=0)`, covering block
coordinates X=-192..-129 and Z=0..63: red concrete at Y=350..351 and blue concrete
at Y=-118..-117. Generation and unsolicited DH updates are disabled. The terrain
is pre-existing and force loaded on the server, outside the client's vanilla view.

The first run uses the previously verified `83dc61c8…` P3 artifact:

```text
build/dh-neoforge/2026-10-01/p4-content/private-tests/attempt-3qd_07b9
```

| Check | Observed result |
| --- | --- |
| Native LSS initial connection | Protocol v20, all 16 fixture columns delivered |
| Native cache premise | 16 actual server stamps explicitly persisted; ordinary disconnect subsequently saves its wider cache |
| Native → DH reconnect | Native terrain negotiation/manager absent; no additional native fixture deliveries |
| Custom height | DH acceptance/enqueue plus 200 Voxy comparisons at Y=350 and Y=-118; identical native blocks were retained, so these comparisons cannot be attributed to DH |
| Warm native cache invalidation | Previously populated bucket has zero entries after DH activation |
| Removal followed by conditional refresh | **Failed:** no conditional request for the target tile before the 240-second phase deadline |
| Switch back to native LSS | Not reached; remains pending |

The supervisor saved all dimensions and stopped its recorded server, client,
display and supervisor owners. This is retained failure evidence, not a passing
content scenario. The first 64 generic captured frames do not contain the custom
tile's complete exchange. The prepared fixture now separately retains only this
known tile's terrain frames, capped at 64 and excluding opaque control identities.

## Correction

The scanner visited successful refresh receipts in fixed distance order. At the
30-second minimum interval and the one-request-per-ten-seconds refresh ceiling,
near tiles became due again before the scanner reached farther tiles. They could
take every refresh slot indefinitely.

The production session now selects the **oldest due eligible receipt** for a
refresh. The existing ring order breaks equal-deadline ties. Renewal moves that
tile behind older work. Initial acquisition, bounded incomplete retries, server
limits, vanilla authority, source ownership and the refresh rate ceiling retain
their existing checks. A narrow request helper shares the existing send path.

The regression drives the actual session scanner, worker and sender through 25
accepted tiles, renewing each owned receipt at a controlled monotonic time. Every
due tile must receive its first refresh before any tile receives a second one.
The previous code fails on a repeated `(6,0,0)` tile; the corrected code passes.

## Current verification and limits

Evidence directory:
`build/dh-neoforge/2026-10-01/p4-content/` in the outer checkout.

- Java 21 compilation of the changed common/session classes and regression passes.
- `offline-before-test.log` records the demonstrated starvation assertion.
  `offline-after-test.log` records one passing regression.
- `offline-session-suite.log` records **19/19 passing session cases**, zero skips.
- These are supplementary standalone JUnit executions using the previous build's
  compiled dependencies and a fresh overlay of the changed classes/tests. The
  final successful runner supplies test-owned LSS configuration/game paths and
  omits Fabric bootstrap/class transformations. Earlier bootstrap setup attempts
  used checkout cwd and leaked Sodium config, processedMods and logs; these were
  archived/removed and the launch cwd corrected on 2026-10-05. An explicit Byte Buddy Java agent
  avoids Mockito's restricted self-attachment path. This does **not** replace the
  normal Fabric/NeoForge build, native regression gauntlet or packaged live proof.
- Earlier standalone loader/agent setup failures remain preserved separately.
  They are not described as product failures or successful test executions.
- The prepared content fixture compiles against the previous production ABI.
  Python staging scripts compile and `git diff --check` passes.
- `source-manifest.json` records the new 375-input production snapshot. Exactly
  two production inputs differ from the P3 candidate: `DhSessionPolicy.java` and
  `DhClientSession.java`. The test/fixture manifest is separate.

During this turn the execution policy changed from unrestricted access to a
workspace-only writable sandbox with restricted networking. The attempted normal
Gradle run fails before compilation with “Could not determine a usable wildcard
IP for this machine.” Direct interface discovery and a loopback socket bind both
return `EPERM`. No normal build/test result is claimed for the correction.

The installed Windows jar remains the previously tested P3 artifact:
`83dc61c86a4e0601c699184beec8b218ae3b636875b58b396306825f22e8e040`.
The manual server was already stopped on entry to this turn. It cannot bind its
socket under the current policy, so restoration remains pending. Its existing
`dh-neoforge-playable` world is preserved. There is no new verified playable jar.

## Next verification

Restore local execution/network access and, for the Prism update, write access to
the selected Windows instance. Do not repeat the downtime permission request;
the user already authorized testing followed by restart of the same world.

1. Build a fresh source copy outside the parent checkout under the shared harness
   lock with Java 21. Run common tests, Fabric build, Paper tests/shadow jar,
   NeoForge build/server gametests and all VSS variants. Preserve exact XML counts.
2. Run the packaging checker/self-tests and bind the normal candidate to its
   source manifest. Rebuild the fixture against that candidate.
3. Run `exercise.py --scenario content` with explicit candidate, fixture, build
   and evidence paths. Require actual removal storage, newer stamp, changed
   conditional reply and successful native source reconnection; retain failures.
4. Freeze the known-tile upstream terrain frames with server artifact, dimension,
   seed commands and file hashes. Use the known slabs as independent expectations.
5. Run the existing lifecycle check on the same candidate; update the idle Prism
   target atomically with dependency checks/backups and preserved preferences.
6. Resume the manual official-DH server with `start-server.sh --resume`, verify
   the exact new owner and `[::1]:25585` reachability, and publish a setup receipt.

The Support/official × Fabric/NeoForge matrix, official-15 corpus, real-DH-client
load comparison, asynchronous vanilla overwrite measurement and strict Voxy
completion acknowledgement remain open. This check covers one custom height and
specific known terrain; it does not establish every custom world or graphics backend.
