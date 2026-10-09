# Full DH feature review: fixes and remaining qualification

Date: 2026-10-07. Applies to the local `feat/dh-codec-p1-mc1.21.1` checkout,
after the frozen r5 candidate reviewed in
[the full-feature review](2026-10-07-dh-full-feature-claude-review.md).
The staged feature and earlier evidence remain preserved. No commit, merge,
publication or Windows installation was performed in this follow-up.

**Subsequent validation:** after the user restored unrestricted access, the
[normal build and14 fresh functional cells passed](2026-10-07-dh-full-feature-validation.md).
The tested r6 jar is now installed, and the original manual world is running again.
The source-only boundary below records the earlier restricted-sandbox checkpoint;
the newer validation record supersedes its build/manual status. The user
[accepts the remaining Voxy I1/completion limitation for now](2026-10-07-dh-accepted-limitations.md);
it is no longer a release blocker. Unrun performance/proxy/fuzz qualification
remains open.

**This is a source-fix checkpoint, not a newly qualified playable artifact.**
Java 21 compilation and direct JUnit/Python checks are available; the normal
Gradle build and packaged live runs remain outstanding. The Voxy ordering
limitation was a blocker at that earlier checkpoint; the dated acceptance above
supersedes that classification. No GPU or timing-sensitive performance run was repeated;
the user can use the host for games.

## Verified findings

“Fixed” below means the demonstrated source defect is corrected, with the stated
regression or inspection evidence. It does not stand in for a new packaged game
run, a proxy test, a Windows menu screenshot or a performance measurement.

| Review item | Disposition and evidence |
| --- | --- |
| S-1 | Fixed: a client-thread transfer hook suppresses reconnecting to retained hub `ServerData`. A bounded per-address cooldown also prevents repeated automatic trials. Four transferred joins produce zero automatic retries in the session regression. Real transfer/proxy qualification remains open. |
| S-2 | Fixed: protocol families belong to the play listener. Same-connection reconfiguration restarts at 16 with automatic reconnect disabled; dimension changes retain positive family evidence. A v16→Support listener regression covers rediscovery. |
| S-3 | Fixed: hints do not choose a decoder. Ambiguous v15 controls cannot confirm the trial, open its source cache or enable pushes. A first legacy terrain response must uniquely decode under the trial family before delivery. Captured official-15 evidence and ambiguous Support controls are pinned. Wrong-family Support raw/Zstd live captures remain owed. |
| S-4 | Fixed: automatic retry execution waits at least five seconds after login; the rejection-path regression checks that boundary. |
| S-5 / S-6 | Fixed: available Voxy between sessions reports a separate state, and vanilla arrival history is recorded whenever DH fallback is enabled, including while native service is selected. Native offers still take precedence. |
| P-1 | Source scheduling changed: official synchronization has a 50 ms floor and scans again after a send/reply; generation retains 500 ms, Support 1 s, and an unavailable backlog probe 2 s. Server and manual limits still apply, with two total requests and one generation request. Historical measurements now disclose their old 500 ms floor and tiles/s. Larger warm timing is **unrun**, so no speedup is claimed. |
| P-2 | Fixed: raw-terrain recovery retains bounded push fences. A push→recovery→older owned whole-source reply regression proves no additional ingest. |
| P-3 | Fixed: queued request registration refuses IDs already removed from `pending`; deferred cancellation also runs on the assembly owner. The cancelled-before-registration test checks orphan ownership. |
| P-4 / C-4 readback | Fixed: transient pressure, client hitches, destination changes and all-vanilla eligibility use a non-exhausting backoff. A post-acceptance cache-readback timeout declines admission without revoking acceptance. Rate-limit reason 0 remains retryable; other server refusals and real sink rejection retain bounded strikes. Policy and session regressions cover both paths. |
| P-5 | Fixed: synchronization and generation have separate request clocks; a one-request/s regression permits reachable generation after a recent sync request. |
| P-6 | Fixed by inspection and fence regressions: dropping a held out-of-range/border push removes its source/receipt and raises the required timestamp. A later partial push cannot manufacture a whole-source receipt. |
| P-7 | Fixed: loss accounting is separate from backlog deferrals; recovery with live updates disabled preserves accepted receipts and the source bucket. A regression checks both survive. |
| P-8 | Fixed: assembly/decode resource limits recover or defer terrain without ending an otherwise healthy session. A ninth incomplete-buffer regression checks release, counters and continued activity. Malformed controls retain their fail-closed behavior. |
| C-1 | Fixed: eviction uses global recency across buckets and consults every live cache owner's protected set within the same JVM. Cross-JVM protection is not shared; the quota lock is. Retirement releases those pins. Tests cover cross-bucket LRU and protected sources. |
| C-2 / C-3 | Fixed: unsafe roots or persistent cache IO errors disable the optional source-cache handle and permit full acquisition. No link is followed or foreign directory deleted. The unsafe-root session regression stays active. |
| C-4 quota | Fixed: a bounded no-follow cross-process lock and revision invalidate stale indexes. Explicit clearing participates in the same ownership. A real second-JVM regression enforces the shared quota. Windows locking and new cache costs remain unmeasured. |
| C-5 | Fixed: cache identity includes fallback configuration, block-state registry order and biome keys. Converter identity is frozen from the same immutable settings snapshot as its resolver. Changing those inputs selects a new source bucket. |
| V-1 | Fixed: the first AIR voxel above a solid floor uses that floor's face light, in conversion and readback comparison. Synthetic overhang/torch/no-sky tests and the captured meadow expectations cover it; existing ocean fluid tests remain intact. Conversion version advances to 3. |
| V-2 / V-3 | Fixed in the bounded pump and deterministic tests: defer while vanilla's light queue is nonempty, retry at render end within the same tick budget, and raise lookup/submission capacity. The moving-frontier regression now includes RD12/RD32, 5.6/30 blocks/s and all four travel directions. Fresh Windows movement qualification remains owed. |
| U-1 | Fixed for defaults/native-only saves on both brands: omit default `lod.source` and `lod.dh` paths. Opt-in downgrade instructions explain removing these unknown keys before v0.16.0. Nondefault DH files are not represented as downgrade-compatible. |
| U-2 / U-3 | Fixed in both legacy twins: inspect actual child rectangles, hide donation controls when no footer space exists, and paginate overflowing tabs with visible selection/navigation. Geometry tests include narrow widths and a wide preceding tab. The observer audits every page; 341×256/320×256 Windows runs subsequently passed in r6, including exact narrow-dimension cells; see [r6 validation](2026-10-07-dh-full-feature-validation.md). The October 8 follow-up also fixes gaps between variable-width tabs. |
| U-4 / U-5 | Fixed: production snapshots expose sanitized reasons, pressure drops and current-readback reuse/preservation. Native status remains native. Layout warnings have their own latch; tooltips state reconnect requirements and the queued-write limitation; stale keys, hardcoded brand phrases and unused enum binders/stubs are removed. Legacy preparation starts Live updates true so Apply to false is meaningful. |
| K-1 | Fixed: recursive and multi-release scans inspect every class for stock XZ links and forbidden private/DH/Sodium namespaces; DH artifacts require the XZ notice paragraph. Mutation tests and release self-tests pass. Current manifests also bind the release checker and packaging tools, and the exercise wrapper rejects bindings that omit them. |
| E-1 | Corrected: the old pressure return is same-connection frontier progress, not proof of warm reacquisition. A separate range-128 physical-rejoin fixture now requires source hits and current Voxy readback reuse. It subsequently passed in r6; see [r6 validation](2026-10-07-dh-full-feature-validation.md). It does not prove a full-radius fill. |
| E-2 | Corrected reporting: unequal CPU/acquisition windows, ambient CPU, committed memory, the FPS cap/GL timer scope, timer-only idle confirmation, best-case official injection, synchronous raw capture and quota-eviction tails are disclosed. A separate correction receipt derives figures from frozen measurements; old raw results are unchanged. |
| E-3 | Corrected: oracle independence is limited to conversion, manual counts/Support reconnect expectations are corrected, Python logs and failure stacks are retained, corpus hashes are asserted, the orphan DTO is privately retained, and the DH-linked slow-config source is removed from the public fixture. A palette replay binds 68 frozen r5 Support frames and verifies nine complete bedrock/stone/water/AIR tiles, with ownership released; it shares the production decoder and has no outbound request captures. The slow-config flag requires a separately compiled private overlay. Historical user-cache cleanup receipts cannot retroactively prove an inactive-process check. |

## Validation and evidence boundary

Evidence root:

```text
/home/vox/projects/voxel-server-support/build/dh-neoforge/2026-10-07/full-review-fixes/
```

Direct compilation covers all common, Fabric+xplat and NeoForge+xplat production
sources, both loader test source trees, and the public private-test observers.
Direct JUnit execution uses the current classes, real cached MC/dependency jars,
Fabric's Knot test bootstrap and real Sodium golden jars. An isolated source copy
prevents source-contract tests walking into the parent checkout. The cache-root
backup test now stages on the same filesystem rather than attempting a nonempty
directory move into `/tmp`.

Direct JUnit totals are 1,026 common, 1,776 Fabric and 23 NeoForge tests passing;
four optional Fabric experiment cases abort on their expected assumptions. The
15 release-mutation/ownership/SQLite Python cases and eight P4 guard/metric cases
pass, as do the 126 release self-test cases. The current checker also accepts all
six frozen r5 jars: XZ diagnostic prose is distinguished from symbolic names,
descriptors and reflective class literals, with regression coverage. This checks
historical artifacts under the new gate, not artifacts built from the fixes.

Counts, exact source/class/dependency hashes, successful logs and preserved first
failures are bound by
[the verification receipt](evidence/2026-10-07-dh-full-feature-fixes-verification.json).
The normal wrapper first refused the legitimately held harness. After the user's
force-stop instruction, the known manual server was stopped through its owned
console and saved all dimensions. Subsequent Gradle attempts hit the read-only
default Gradle cache and then the sandbox's lack of a usable wildcard IP even with
a writable private Gradle home. Those are retained failures, not normal-build greens.

No shipping jar was assembled from direct classes: annotation processing, loader
remapping, game runs and full artifact gates are still required. The previous r5
Prism jar is unchanged. The manual test server is **stopped**, with a saved-world
acknowledgement; it could not be restored under the present network isolation.

## Remaining work

The integrated I1 counterexample is an accepted limitation for now: Voxy has no supported
per-write epoch, cancellation, atomic fence or typed completion/persistence receipt.
An already queued DH write can still commit over newer vanilla terrain. This pass
does stop an unchanged reply from reconfirming lost queued data without source and
destination readback, but it cannot revoke the original enqueue.
The user waived this supported-API boundary as a blocker on 2026-10-07; the
counterexample and absence of a strict completion guarantee remain documented.

Required next checks are a normal Java21 build and packaged tests, then live
transfers/proxy reconfiguration, the new range rejoin and narrow legacy layouts,
and fresh fringe/face-light playback. Full-radius performance, Fabric performance,
uncapped frame studies, multi-tile content coverage and decoder fuzzing remain
qualification work. Performance runs need a separate idle-host window; none is
reserved now. Other suspected findings in the review remain unverified; the I4
drain gate was nevertheless tightened to include retiring ownership. Outreach,
other Minecraft lines and the release decision remain pending.
