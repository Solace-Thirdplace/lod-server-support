# DH P2/P4 manual test guide

Updated: 2026-10-09.

Use **LSS dev — 1.21.1 NeoForge - Copy For Far Testing** in Windows Prism.
The local server address is **[::1]:25585**. The server resumes the existing
`dh-neoforge-playable` test world with official DH 3.3.3; it has no LSS server mod.
The client must have LSS and Voxy, without a Distant Horizons client mod.
Distant generation is enabled within **32 chunks** at the user’s request.
Existing terrain synchronization remains enabled. The server uses
`SURFACE_THEN_CHUNKS`; the new setup enables server real-time updates.

The latest menu-change build/setup receipts are in the outer checkout’s
`build/dh-neoforge/2026-10-09/menu-simplification/`. They describe their capture
time; check current liveness before connecting. Previous jars have dated backups;
preferences, the test world and OP are retained. Keep Voxy’s existing cache.

Check actual liveness from the outer checkout:

```bash
python3 build/dh-neoforge/2026-10-01/stop-server.py --status-json
```

`bash build/dh-neoforge/2026-10-01/start-server.sh` uses a
detached supervisor and a console FIFO; it does not depend on an open terminal.
Use `server-console.py 'save-all flush'` in that directory for commands and
`stop-server.py --stop` for graceful shutdown. The actual detached restart, FIFO console commands and saved world have been checked.

## DH options

On **General**, **Use Distant Horizons** allows DH fallback when the server has
no native LSS service. Native LSS always wins when advertised, including a disabled
native offer. Changing this permission needs a reconnect. The protocol is automatic;
there is no server-type selector. Official 15, official 16 and DH Support 15 are
supported. Older official servers may reconnect once during discovery; this
protocol-16 manual server should connect directly. Reception still gates this
checkbox before Apply; General and Far Players share one save transaction.

Sync on load and Live updates are now **config-only**, both defaulting to on and
requiring server permission. To change them, merge a `dh` mapping into the existing
`lod` section of `minecraft/config/lss-client-config.yaml`, then run `/lss reload`:

```yaml
lod:
  dh:
    sync_on_load: true
    real_time_updates: true
```

Default DH values can be omitted during saves; their effective defaults remain
true. Existing explicit false choices are retained. The old Refresh slider/timer
and manual protocol selector remain removed; retired `lod.dh.refresh_seconds`
and `lod.dh.profile` values are ignored.

Sync on load checks terrain when it enters the LOD interest region or on rejoin,
with DH's 20-minute load debounce. Standing still never causes periodic checks.
Live updates subscribe to server-pushed edits for the negotiated radius, without
requesting a full tile for each complete edit. `/lss diag` reports whether the
requested services are actually available and how many updates were enqueued.

The historical October 6 candidate passed 3,326 unit tests (four skips), 78 Fabric and eight NeoForge
server tests, and all six LSS/VSS packaging checks. Four fresh packaged NeoForge
cases pass: automatic official-15 detection with one reconnect and DH Support-15
detection without reconnect, both with Voxy storage readback; native v20 priority with Use DH enabled and zero DH
requests; and official-16 reconnect/live-update/UI checks. The native case has
zero missing border columns and 639 independent block checks. The earlier border
candidate separately passed native and DH modes; its DH border receipt is historical.
The historical official-16 observer used a `samples × 12` estimate, not1,200
independent comparisons. The later October6 case counts11,332 actual comparisons, with zero
target payloads/conversions on unchanged rejoin, push-only edits/removal with sync
off and no new requests, and terrain preservation while live updates are off.
That historical menu had three DH checkboxes. The current menu has only General’s
Use Distant Horizons checkbox; see the October 9 menu evidence for its checks.
Earlier harness failures remain retained and are not feature passes.

## Initial-join border retest

Restart Minecraft in the selected Prism instance, then join the existing server
with your current render distance. Before moving, let LOD acquisition settle and
look around the whole vanilla/LOD boundary, especially diagonal corners. The
loaded outer fringe should fill without needing to approach it first. Move and
repeat, then disconnect and rejoin. No reset is needed for this fix: it fills
missing border data from the current vanilla chunks, preserving existing LODs.
See the [border fix and private proof](2026-10-06-vanilla-lod-border-gap.md).

## Ocean lighting retest

Manual result, 2026-10-05: the user confirms **lighting is fixed** and reports
that the overall appearance looks good. The ocean lighting visual retest passes
by user observation; the other numbered checks below still need their own results.

The ocean-lighting fix reduces skylight with water depth. Private Fabric and NeoForge clients passed actual Voxy water-light
readback at deep and shallow floors. Look at an ocean floor **before approaching
it through vanilla range**, then compare it nearby and after retreating. Deep
floors should no longer look fully lit. Run `/lss reset` after connecting to
reacquire distant data if an old bright LOD remains cached; a fresh unvisited
coast is also useful. Note position and depth if a mismatch remains.

The earlier idle diagnostics were consistent with generation being disabled and
no remaining stored terrain. Flying should now acquire and generate terrain
inside the 32-chunk bound. Use `/lss diag` if filling stops
while moving into new terrain.

## Reconnect cache check

The current build retains complete validated DH terrain. Let distant
terrain settle, disconnect, and rejoin at the same position. With server sync
available, timestamp validation should precede cached destination restoration,
when Sync on load is enabled. Matching Voxy columns need no conversion/ingest; different
existing columns are preserved, including terrain visited with vanilla in the
previous connection. Only fully default destination columns rebuild from unchanged
source. A partial hole may therefore remain until a changed server reply or reset.

`/lss diag` reports matched/preserved columns and cache availability. Inspect
an area you visited before disconnecting: it should retain its refined appearance.
Partial upstream tiles and missing source tiles still download again. A live push
invalidates its old complete source snapshot, so that affected tile can download
again next connection; it does not cause every cached tile to replay into Voxy. Zero target
payloads in the old private run was never a zero-download claim for the session.
`/lss reset` explicitly discards retained data and reacquires it.

Before the UI update, the post-P4 packaged Fabric/NeoForge clients passed unchanged target reuse, offline
edits, Voxy loss and a JVM restart, each with 2400 independent block-state checks.
Both also visit the target with vanilla, retreat, reconnect and preserve its raw
destination digest with zero target conversion. Production full-content comparison
remains a premise, not an independent light/biome or completion oracle. The older
damaged-column repair is replaced by preservation. Both loaders additionally pass
900 independent ocean light samples; NeoForge lifecycle/source toggles pass. See the
[review disposition](2026-10-05-dh-post-p4-review-disposition.md).

## Start with untouched distant terrain

Before connecting, set vanilla render distance to **4 chunks**. Keep Voxy enabled,
On **General**, enable LSS reception and set LOD distance to **32 chunks**.
On **General**, enable **Use Distant Horizons**; its protocol is detected automatically.
Changing it needs a reconnect. Leave the config-only sync/live preferences on
for the first two checks; missing values use those defaults.

Run these commands after connecting:

```text
/gamemode spectator
/tp @s -16576 195 -16288 -90 4
/lss diag
```

The fresh course is a 192×192 platform split into nine coloured 64×64 tiles,
including negative-coordinate tile edges and corners. The platform is at Y=180.
It contains a water pool, glass enclosure, leaves, stairs/slabs, a hollow stone
building, sea lanterns and a gold cube. This region was unused before preparation.
Starting at the viewing position keeps it outside vanilla view, so distant terrain
here must come through DH. Allow acquisition to settle before assessing holes.

## Checks that need your Windows renderer

1. **Distant appearance.** From the viewing position, confirm all nine coloured
   tiles and the large landmarks become visible. Look across tile boundaries and
   corners for missing strips. Confirm solid terrain appears alongside water.
   Rotate the camera and note persistent transparency, missing faces or lighting
   problems. DH supplies an approximation; small block shapes and internal caves
   may differ from vanilla.
2. **Movement and vanilla transition.** Fly into the course, inspect the same
   features nearby, then return to the viewing position. Also try a fast flight
   across its edges. Look for holes, flicker, or a stale distant copy replacing
   terrain you just visited. Record whether it persists after a short wait.
3. **Live edits and removal on an unvisited tile.** Return to the viewing position
   **before disconnecting**, then reconnect there to start a fresh DH connection.
   This prevents a login near the landmark from giving it vanilla authority.
   Set `lod.dh.real_time_updates: true` and `lod.dh.sync_on_load: false`
   in the client YAML, then run `/lss reload`. Wait for initial
   acquisition to settle. While staying at the viewing position, run:

   ```text
   /fill -16368 181 -16240 -16353 188 -16225 minecraft:diamond_block
   /save-all flush
   ```

   The gold cube on the southwest tile should become diamond through a push. Then:

   ```text
   /fill -16368 181 -16240 -16353 188 -16225 minecraft:air
   /save-all flush
   ```

   The cube should disappear while its coloured floor remains. Server LOD processing
   is asynchronous; allow time for the edit to be processed and record the delay.
   Stay distant: visiting the edited chunk grants vanilla authority for that
   connection. Optionally turn Live updates Off, edit again, and confirm the distant
   copy stays unchanged. Re-enable Live updates and make another edit to test its
   hot subscription. Apply these file edits with `/lss reload`; re-enable Sync on
   load before the next check.
4. **Reset/rejoin.** Use `/lss reset` and let the distant course fill again. Disconnect
   and rejoin once. Confirm the removed cube stays removed and the platform returns.
5. **Responsiveness.** During first fill and fast flight, watch F3/frame times and
   note stutters, duration and whether they continue once acquisition settles.
   Software rendering in the private rig cannot establish your Windows GPU behavior.

These are visual checks. A successful run does not prove Voxy's general asynchronous
write ordering or provide a completion/persistence acknowledgement.

## Useful evidence

Send the failed step, expected/observed result, position, approximate delay and
whether reconnect/reset changes it. Include a screenshot for rendering problems
and `/lss diag` output. `/lss diagnostics export` writes a local diagnostic report;
share it only if you choose to. The instance's `minecraft/logs/latest.log` is also
useful. Keep Use Distant Horizons enabled on this server: it has no native LSS service.

To restore the removable landmark afterward:

```text
/fill -16368 181 -16240 -16353 188 -16225 minecraft:gold_block
/save-all flush
```
