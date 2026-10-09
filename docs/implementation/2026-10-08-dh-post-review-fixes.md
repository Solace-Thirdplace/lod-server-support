# October 8 DH review fixes

Scope: Minecraft 1.21.1, Java 21, Fabric and NeoForge. Local candidate
`0.16.0-dh-post-review.dev20261008.2`, built in
`/tmp/lss-dh-post-review-20261008-r2`. This is the disposition of
[Claude's review](2026-10-08-dh-post-review-claude-review.md).

## Verified findings

| Review ID | Resolution and regression evidence |
| --- | --- |
| V1 | Successful family confirmation clears the address's trial cooldown. Fresh physical joins still probe 16 first and may use one bounded legacy trial. Suppressed failed trials have an allowlisted diagnostic reason. The regression relogs immediately after legacy confirmation. |
| D1-R5 | The transfer hook passes the listener's actual `serverData`, including during configuration when Minecraft's accessor is null. Its protected/final descriptor was checked against MC 1.21.1. A null-accessor regression preserves transfer suppression. |
| V2 | Cancelled official generation timeouts do not consume the two unsafe unanswered allowances. Sync and uncancellable Support timeouts retain that bound. An owned refusal resets the count. Tests cover repeated generation timeouts and refusal recovery. |
| R1-D1 | Each pending request has a worker-published answered flag. Expiry and pruning skip answered requests while their client callbacks remain queued; the pending entry still holds its acquisition slot until bookkeeping finishes. A regression closes the frame before running either cleanup path or the client callback. |
| R1-D2 | NeoForge calls the production capture overload, which stamps arrival under the session ownership lock. Its separate transport-retention timestamp remains for the five-second transport watchdog. |
| R3-1 | Every admitted request, including asynchronous cache checks, lowers the next scan deadline to the 50 ms floor. A complete captured source and a second relocated source both enter flight before either reply, under a server permitting two sync requests. |
| R3-2 / R3-3 | Temporary deferral retains permanent-rejection strikes and valid source snapshots. A distinct unverified deferral removes a conditional timestamp when the source is absent or destination readback is unavailable, while retaining strikes. Tests keep both the transient/permanent budget and the existing Voxy-loss safeguards. |
| R2-1 | Recovery queues cancellation of the cleared request IDs after the client clears pending ownership. The recovery gate remains closed until that worker cleanup finishes. A queued registration between the original discard and client clear leaves no assembly owner. |
| Related failed `take()` reservation | Independently confirmed: the completed-buffer reservation could fail while retaining its fragments. That specific buffer is now discarded immediately. The existing join-peak regression asserts zero retained bytes and incomplete buffers after failure. |
| R4-1 | The first candidate reaching admission owns the primary reason and wait. Parked/deferred information from other tiles remains in the secondary mask. A multi-tile regression checks that parked terrain cannot mask manual admission pacing. |
| R4-2 | Combined gates select the owner of the longest wait, with stable tie-breaking; unknown readiness dominates finite timers. The reason and displayed wait come from the same observation. |
| R4-3 | Terminal scan observations are retained and aged between scans, with cadence added as a secondary bit. Movement, service/preferences changes and completed cache work invalidate stale observations. Expired rate waits do not reappear after the cheap admission recheck. No diagnostic-only scans were added. |
| R6-D1 | Quota trimming skips individual undeletable files and continues across buckets. Writes reserve quota before committing, so a declined write cannot leave another over-quota file. A real read-only-directory regression checks repeated writes, global limits and protected-file admission failure. |
| R6-D2 | Explicit clearing skips refused linked/reparse roots and foreign buckets. A damaged owned regular revision file is repaired under its existing cross-process lock; its inode is preserved. Refused foreign targets survive. |
| R7-D1 | Vanilla fringe ingestion checks light readiness per chunk/section instead of waiting for the whole world's light queue to empty. Unready chunks remain eligible for a later tick/render pass without spending ingest credit. Deterministic 20/30 fps travel cases keep unrelated light work permanently queued and verify ready chunks still progress. Existing lookup, ingest and backlog budgets remain. |
| R7-D2 | Air above a fluid floor copies the floor's block light while retaining its own skylight. Source conversion and destination comparison agree, and conversion identity advances to version 4. Water/lava regressions cover sky and no-sky worlds; ocean depth attenuation remains pinned. |
| R7-D3 | Retained and documented approximation: in a one-voxel air gap, both neighboring faces share Voxy's single voxel light value. The floor rule remains the chosen approximation; a complete floor/air/ceiling fixture pins it. This is an engineering tradeoff, not a new claim of exact official-DH lighting or a newly claimed user waiver. |
| R8-1 | Both legacy twins stop the visible tab row at the first tab that does not fit. Variable-width regression cases ensure a later narrow tab cannot skip an earlier wide page. |
| R8-2 | Sync on load and Live updates require staged Use DH and its reception-master dependency, on both menu generations and loaders. Native-only users cannot accidentally edit those opt-in values through enabled controls. |
| F1-1 | Historical ready receipts are explicitly point-in-time. The prior server stopped at 15:30Z on October 8. The watchdog now requests console `save-all`/`stop`, uses an owned pidfd SIGTERM fallback, and records stop reason and runtime exit code. Console-first and fallback tests pass. Current restoration is recorded separately after validation. |
| F1-2 | The unused status path and client-conflict messages use runtime branding instead of literal LSS phrases. Wire identity remains unchanged. |
| F1-3 | Catalog validation/render logs are retained for this candidate. |
| F1-4 | Original normal-build timestamps and later tooling/observer recomposition are distinguished. The prior r1/r2 observer split is documented by case; historical receipts are preserved. |
| F1-5 | Prior E-1 and U-2/U-3 rows now point to their subsequent r6 validation instead of saying unrun. Cache working-set protection is correctly described as shared within one JVM. |
| F1-6 | The generated default checkout config and empty cache marker are quarantined with hashes. Session tests now isolate their cache under a temp directory. Only the absent, empty staged ocean DTO orphan was removed from the index; its prior index entry is recorded and all other staged work is preserved. |
| R2-2 | The prior allocation probe's pre-final assembly hash is disclosed. Its identical `accept()` implementation supports the byte-saving claim. CPU ratios are treated as noise, with no CPU speedup claim. |
| F2-V3 | Sparse/bursty delivery can leave the one-way official ramp below 500 KB/s for the entire connection. New requests then retain a bounded 120-second birth lifetime. The 10-second idle deadline and count/byte limits still apply. The pinned controller was not changed. |

## Validation and local setup

The normal build passes **3,421 production unit tests**, with four optional Fabric
tests skipped, **78 Fabric and eight NeoForge server gametests**, and all six
LSS/VSS artifact and release gates. Four separately compiled observer tests,
nine storage tests, two watchdog-stop tests and 126 release self-tests pass.
Failed exploratory attempts and their XML/logs are retained.

The first packaged observer still expected the old private `Pending` record's
accessor. Observer revision 3 reads its owned position field instead; production
jars are unchanged. Original observer bindings and failed results are archived.
Recomposition recounts production tests from the retained full normal-build XML,
since a later four-test observer run replaces the build copy's Fabric XML output.
Normal-build time, observer recomposition time and case inputs remain distinct.
The later full Fabric run validates the final stronger cadence test without
rebuilding or changing the already-tested production jars.

Evidence root:
`/home/vox/projects/voxel-server-support/build/dh-neoforge/2026-10-08/post-review-fixes`.
Exact production/test/tool source hashes, jar hashes and build results are bound in
`verification.json`; functional cases and later installation/restoration have
separate time-stamped receipts. Source-copy catalog checks run against the real
checkout's Git history and retain their logs; the isolated copy runs the same full
Gradle task list through the shared harness wrapper.

**Ten packaged checks passed:** official 16 on both loaders, Support, official 15
with one discovery reconnect, moving DH, native precedence, live updates/load
sync/rejoin, vanilla no-service, and conflicting official client mod. The moving
course covered 173 blocks with zero missing Voxy columns. Native precedence used
protocol 20 with zero DH requests. The unchanged rejoin target caused zero payload
downloads and zero conversions. Live updates and Sodium Apply/export also passed.
The additional pressure check injects nine synthetic fragments at the packaged
capture boundary, observes one raw loss, and verifies resumed terrain on the same
connection/session with zero incomplete buffers. It is controlled fault injection,
not a naturally observed upstream workload.

The final full Fabric suite passed again after strengthening the M1 test to drive
an actual deferred scan through a following tick. The production jars remain
unchanged. [The completion record](evidence/2026-10-08-dh-post-review-fixes.json)
distinguishes the full build, final test refinement and observer revision.

At **19:26Z**, the session switched to a managed workspace-write sandbox. Both
IPv4 and IPv6 loopback socket creation now return `EPERM`, Windows Prism lies
outside the writable roots, and the PID namespace no longer exposes host owners.
Consequently the additional packaged pressure test, Prism installation and manual
server restoration were deferred at that time. The pressure observer was compiled and bound;
the actual worker recovery race and resource cleanup regressions already pass.
All nine earlier private runs completed and retired their owners before this
restriction. The manual server was last independently verified stopped before
the switch; current host liveness cannot be audited from this namespace. No
installation or restart was claimed during that restricted interval.

**Recovery at 20:06Z:** unrestricted host access returned. The pressure test passed,
all private test owners were retired, and the candidate was installed into
**LSS dev — 1.21.1 NeoForge - Copy For Far Testing** while Minecraft was closed.
The prior jar was backed up, and preferences and other mods are hash-preserved.
Installed NeoForge SHA-256:
`96f982a5027bc2a3d20e79f5e88324e057b414fd811eccbaa31128998627b538`.

The original **dh-neoforge-playable** world is running at **[::1]:25585**, with OP
and server/DH configuration preserved. A real rehearsal of the watchdog helper
logged console `save-all` and `stop`, exited with code 0 without signal fallback,
persisted its stop receipt and released the harness. The same world was resumed
again. Final owner/TCP checks, fresh console list/save acknowledgements and the
Minecraft protocol-767 handshake pass. These are time-stamped readiness checks;
future storage stops write their own owner-bound receipts. No foreground client
or new GPU/server-load benchmark was launched.

The following recovery commands completed. They remain a reproducible recipe;
do not rerun the completed matrix or performance comparisons unnecessarily:

```sh
python3 /home/vox/projects/voxel-server-support/build/dh-neoforge/2026-10-08/post-review-fixes/run-pressure.py
python3 /home/vox/projects/voxel-server-support/build/dh-neoforge/2026-10-08/post-review-fixes/install-prism.py
bash /home/vox/projects/voxel-server-support/build/dh-neoforge/2026-10-01/start-server.sh --resume --bounded-storage
python3 /home/vox/projects/voxel-server-support/build/dh-neoforge/2026-10-08/post-review-fixes/verify-restored-server.py
```

The installer independently checks that selected Minecraft is closed, backs up
the previous jar and preserves preferences/other mods. Resume keeps the existing
`dh-neoforge-playable` world and OP/config. The retained
`rehearse-storage-stop.py` recipe and `watchdog-rehearsal.json` bind the successful
shutdown exercise to its actual owner. The final restoration receipt identifies
the subsequent live owner.

The user's standing cleanup/storage authorization was used to retire another
324 MiB of canonical-identical old Windows dependency copies and 1.511 GiB of
retired disposable Linux dependency copies before the interruption, followed by
another 0.172 GiB after the pressure check, preserving logs/proofs/mods/worlds.
The user subsequently freed more space; C: showed about 98 GiB after interruption.
New DH bounded profile v2
reserves **40 GiB** plus **2 GiB** growth, plus explicit staging costs on creation.
Existing v1 receipts keep their original 50 GiB reserve. Generic rigs retain their
50 GiB reserve and at least 8 GiB growth. Capacity is checked every five seconds
and owned growth every thirty seconds. No personal worlds or profiles are removed.

## Concerns without a verified failure

The movement pause is not proven fixed. The 256-section ingest gate, range-refusal
rearming during movement, one-off registry hashing, cache readback polling, and
same-connection backend ramp identity still need representative movement evidence
before scheduler changes. The private movement course can establish progress, but
does not reproduce every host FPS/render-distance workload. Iris/Sodium Extra
live layouts remain unmeasured; the variable-width pagination defect itself is
fixed and tested. No new Windows GPU or server-load comparison is run here.

The previously [accepted Voxy ordering/completion/persistence limitation](2026-10-07-dh-accepted-limitations.md)
is unchanged. DH terrain remains a block-resolution approximation. Outreach is
still pending; this work remains local.
