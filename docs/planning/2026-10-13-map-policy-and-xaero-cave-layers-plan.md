# 0.19.0 map ingestion: server map policy (#316) and Xaero cave layers (#313)

Status: PLANNED v3, 2026-10-13. Plan review (4 Opus + 1 Fable) is folded
throughout; §E maps each finding to the place that resolves it. Target release:
0.19.0, which waits until both parts are done, B-II included (user decision
2026-10-13, §D).

- **Part A** (#316): a server option that asks clients not to write LOD terrain
  into map mods.
- **Part B** (#313): write LOD terrain into Xaero's cave layers, so the Nether
  map fills.

Both parts touch the client map-ingestion layer, and Part A's gate covers every
layer Part B writes. §C covers validation, docs and ports for both parts, and §D
records the user's decisions.

---

## Part A: server map-ingestion policy (#316)

### A1. Intent

User decision 2026-10-13, after the reporter's answer on #316:
- Add a server option, **default off**, that asks clients not to write LOD
  terrain into map mods (Xaero's World Map, JourneyMap).
- While it is on, the server serves LODs only to clients that declare they honor
  the request. Every older client gets no LODs until it updates.
- The reporter accepted that tradeoff ("updating is as easy as pressing a
  button"). One uniform rule, "0.19.0 or newer", is easy to explain in the
  config comment and the chat line.

Many old clients have no map ingestion at all:
- the legacy dialects v16/v18/v19 (clients older than 0.10.0);
- 0.10.x–0.11.x (the Xaero bridge arrived in 0.12.0).

They are still refused at the handshake for that uniform rule, but **never
revoked mid-session** (A3.4). Serving them leaks no map data.

**Honesty scope** (for the config comment, the README and the reload feedback).
This is a request to honest clients, not enforcement:
- The terrain still reaches updated clients, and Voxy renders it at the same
  distance.
- A modified client can declare the capability and write the map anyway.
- Map data players already saved stays.
- What players map by exploring normally is unaffected; Xaero and JourneyMap
  mapping loaded chunks is their own behavior.
- Old clients also lose far players while refused, because the whole session
  is off for them.

### A2. Wire: no protocol bump, no new channel

**C2S: the capability bit.**
- Add `CAPABILITY_MAP_POLICY = 8`, meaning "I honor the server's map-ingestion
  policy".
- One shared helper builds the capability mask for every handshake send. Those
  are the join/ladder lambda (`ClientNetGlue:51`) and the LAN-host
  self-handshake (`:180`); `BenchmarkHook` builds no handshake.
- The bit is declared unconditionally: with no map mod, honoring is trivial.
- Soak and benchmark JVMs keep the bit, because harness clients need it. Do not
  copy the far-player zeroing.
- `SoakDialectOverride` strips bits 4 and 8 when it emulates a v19 client, for
  fidelity.
- The handshake stays two VarInts. Every server back to v0.2.1 only tests
  specific bits with `&`, so an old server ignores the bit. A new client at an
  old server therefore maps as today, which is correct: an old server has no
  policy.

**S2C: the flags field.**
- The v20 SessionConfig gains a trailing `sessionFlags` VarInt after
  `serverDataVersion`. It uses `SESSION_FLAG_DENY_MAP_INGESTION = 0x1`; unknown
  bits are ignored.
- **The encoder appends it only when all of these hold:**
  `protocolVersion == PROTOCOL_VERSION && !v16Wire && session declared the bit`.
  This is the same guard as `serverDataVersion` (`SessionConfigS2CPayload:79`,
  `PaperPayloadHandler:50`).
- Two hard requirements follow:
  - Every v20 client from 0.10.0 to 0.18.0 hard-kicks on any trailing byte
    ("found N bytes extra"). A frame to a session without the bit must be
    byte-identical to 0.18.0.
  - A 0.19 client declares the bit at every ladder rung. A 19, 18 or 16 echo
    must still never carry the field, or the 0.19 client's 19 arm would read
    the flags as the data version.
- **Why an append and not a separate channel:** it is atomic with session
  establishment, so the flag is known before the first column. It also needs
  no channel registration on four server platforms.

**Client decode.**
- The v20 arm reads `dataVersion` if readable, then the flags if readable. It
  then drains any further bytes, so future appends need no new capability for
  0.19+ clients.
- **Absent flags decode as "unchanged" (−1).** The client keeps the connection's
  previous value, so a server site that forgets the field fails closed.
- A new connection starts at "allowed", and old servers never send the field.

**Construction/send sites.** Every one passes the session's capabilities and
reads the policy *when it sends*:

| Platform | Site | What it sends |
|---|---|---|
| xplat | `ServerReceiverGlue:505` | handshake reply |
| xplat | `RequestProcessingService:1025` | settings repush |
| xplat | `RequestProcessingService:1109` | revocation push |
| xplat | `RequestProcessingService:1309` | dimension change |
| Paper | `LSSPaperPlugin:463` | handshake reply |
| Paper | the `PaperRequestProcessingService:851` sender lambda | repush `:885`, revocation `:931`, dimension change `:1792` |

- The dimension-change push fires on every Nether trip at stock settings
  (512 vs 64). Missing it would silently re-allow map writes.
- On Paper, the sender lambda and `LSSPaperPlugin.SessionConfigSender` gain
  capabilities.
- The flags are computed inside the deferred reply lambda at send time, as
  `worldLod` is (`LSSPaperPlugin:673-676`).
- Sponge and NeoForge use the xplat `CODEC` (`SpongeChannels:117`), so they are
  not separate sites.

**Census and parity tests.**
- A source census covers the callers of `sessionConfigPusher.push`,
  `sendToPlayer(new SessionConfigS2CPayload…)` and
  `PaperPayloadHandler.sendSessionConfig(`. Production code may not use the
  flag-less 4- or 5-argument constructors.
- Paper's hand encoder mirrors the codec. The parity pins cover both shapes, and
  every echo dialect to a handshake that declared the bit.

**Client trap.** `ClientSessionGate.clampToProtocolBounds` rebuilds the payload
from four fields and already drops `serverDataVersion`. Replace it with a wither
that copies every component.

### A3. Server

#### A3.1 Setting

- **Key:** `privacy.request_no_map_ingestion: false` on every platform (`server.yaml`,
  `paper.yaml`). Name chosen by the user (§D2).
- **Hot timing:** edit the file, then run `/lsslod reload`.
  - There is no `set` row: `/lsslod set` was removed. That is pinned by
    `SettingsReloadGameTests:58` and
    `PaperCommandsTest.removedMutationVerbsCannotChangeSettings`.
  - The reload feedback gains a line keyed on this path and a new
    `SettingsFeedback` field.
  - Do not reuse `legacyReconnects`; that text means "reconnect for the new
    distance or generation".
- **Schema:** the `Spec` gets a `legacyKey` that never existed in JSON, which is
  what `LegacySettingsMigration` reads. `config_version` stays 1, and a file
  without the key reads false.
- **Wording updates.** Update both of these, because the option changes on
  reload:
  - the `privacy` group comment, which today is about anti-xray only;
  - README's "privacy changes need a restart" sentence.
- **Downgrade note:** an unknown key fails to load (`SettingsSchema:174`), so a
  0.19 server file containing the key will not load on 0.18. Document it the
  way CLAUDE.md documents the client `map_ingestion` case.

Config comment draft:

```yaml
  # Asks players' clients not to copy distant terrain into Xaero's World Map or
  # JourneyMap. Only clients on 0.19.0 or newer can honor this, so while it is on,
  # older clients get no distant terrain (and no far players) until they update.
  # Clients still receive and render the terrain, so a modified client can ignore
  # the request. Maps players already saved, and areas they explore normally,
  # are not affected.
  request_no_map_ingestion: false # Ask clients not to add distant terrain to their maps.
```

#### A3.2 Handshake ladder

- `HandshakeGate.evaluate` gains `mapPolicyArmed`, in a new widest overload.
- **The new rung** sits after DISABLED and before REGISTER: armed ∧
  ¬(CURRENT ∧ bit). It yields the new outcome `MAP_POLICY_REFUSED`:
  - `effectiveEnabled=false`;
  - the reply is `enabled=false` in the client's own dialect;
  - no registration.

  The permission gate's doctrine applies: silence would walk the discovery
  ladder. A player the permission gate denies still lands on DISABLED, so
  `deniedByServiceGate` is unchanged.
- **Real refusal** (the bit is absent):
  - a memo deposit with reason MAP_POLICY (A3.4);
  - the chat line (A3.3);
  - one INFO line;
  - the counter.

  If the player is already registered (a live session re-handshaking), run the
  unregistration composite: `removePlayer`, `farPlayerService.removeViewer` and
  `regionSummaries.removePlayer`.
- **Quiet refusal** (a legacy dialect *with* the bit, i.e. a 0.19 client's raced
  ladder rung): the reply only. No memo, chat line, counter or unregister. The
  client heals through the establish re-announce and the downgrade guard
  (`ClientSessionGate:390-446`), then gets its flags on the v20 echo.
- **Ladder-race test rows**, for Fabric and for Paper/Folia. On Paper, the
  refusal reply goes out inline (`LSSPaperPlugin:713`), while the v20 reply is
  deferred to the pump (`:707`).
- **Explicit `Outcome` branches on both platforms.** The call sites only use
  `==` checks (`ServerReceiverGlue:419,458,526`; `LSSPaperPlugin:612,657,691`),
  so a new value would fall silently into the generic branch.
- **Source pins:**
  - `ViaGuardWiringContractTest`'s pin on
    `ViaProbe.isMismatch(viaProtocol, nativeProtocol))` breaks once a parameter
    follows it; update it.
  - Add wiring pins that both production call sites use the widest overload.
  - The existing behavior pins are unchanged.
- **DH fallback:** clients since 0.17.0 treat a native `enabled=false` as an
  admin denial and do not fall back to the DH adapter (`DhClientSession:98`).

#### A3.3 Telling the refused player (approved, §D1)

- **Text:** "Distant terrain on this server needs `Brand.shortName()` 0.19.0 or
  newer. Update the mod to see it." Literal English, like the other
  server-originated text.
- **When:** once per connection. It uses its own per-UUID latch, cleared on
  disconnect, because `onRegistered` clears the denial-log latch. It is sent
  only from the handshake refusal and the arm sweep, never from a replay.
- **How:**
  - Fabric, NeoForge and Sponge: `sendSystemMessage` on the server thread;
  - Paper: NMS `sendSystemMessage` on the pump, which the revocation push
    already sends from.
- Only LSS clients ever see it, because vanilla clients never handshake.

#### A3.4 Live changes (`/lsslod reload`)

**Trigger.** Today's apply check, `!adoptedServicePolicy.equals(next.service())`
(`RequestProcessingService:728`), never sees a `privacy` key. So:
- Add an `adoptedMapPolicy`, initialized where `adoptedServicePolicy` is (`:173`,
  `:410`).
- Add a `runMapPolicySweep`:
  - called from `reconcileSettings` **before** `repushSessionConfig`;
  - also called on the existing 200-tick cadence, as an idempotent enforcement
    of: armed ∧ registered ∧ CURRENT ∧ ¬bit → revoke.

Why revoke first: sessions about to lose service must not first get an
`enabled=true` manager rebuild.

Why the cadence: it closes the Folia race. A region thread can decide a
handshake on the pre-arm config and enqueue `Register` after the one-shot sweep.
Paper's twin sweep gets the same treatment.

**Arm**
- **CURRENT with the bit:** the repush, whose trigger now includes the policy,
  carries the deny flag.
- **CURRENT without the bit:** the revocation composite:
  - a per-player `enabled=false` push;
  - the unregistration composite;
  - a memo deposit with reason MAP_POLICY;
  - the chat line.

  No hysteresis is needed, because a capability is a session fact.
- **Legacy dialects:** they keep serving until rejoin, and the next handshake
  refuses them. This is the service-gate precedent (`RPS:1086-1089`), and
  legacy clients have no map ingestion. The rejected alternative, a "silent
  stop", is neither silent nor leak-free:
  - v16 batches merge before the state guard into a want-set no tick sweeps,
    then bounce `RATE_LIMITED_V16` every ~1 s;
  - Paper sends its v16 re-attach prompt after 60 s.

**Disarm**
- **Capable sessions:** a repush with flags 0.
- **Players refused at handshake or revoked:** the memo replay heals them, through
  the full production ladder in their own dialect.

**Memo reasons.** `DeniedHandshake` gains a reason mask {PERMISSION,
MAP_POLICY}. In the grant sweep, a stored handshake is cleared only when every
reason it holds has cleared:
- **PERMISSION** clears as today.
- **MAP_POLICY** clears only when the policy is disarmed, or the stored handshake
  is CURRENT with the bit.

Without this, the sweep's "gate off ⇒ `cleared = true`" (`RPS:1157`,
`PRPS:973`) replays every refused player every 10 s for as long as the policy is
armed. Each replay sends `enabled=false`, re-deposits the memo, and logs
"requireServicePermission was disarmed". Also:
- `map_policy_refused` is a separate counter; `permission_denied` is unaffected.
- Policy refusals get their own log text.
- **Pin:** policy armed, gate off, N sweeps → exactly one reply.

**Client flips.** On the client, a flags-only change takes the fast path in A4,
not a manager rebuild.

#### A3.5 Observability

- **Counter:** `service.map_policy_refused` in both exporters, the rig copies
  (`RigFabricMetrics`, `RigPaperMetrics`) and all the contract fixtures.
- **Logging:** one INFO line per refusal transition, naming the player and the
  client's protocol and capabilities.
- **Diag line:** a separate `MapPolicy: on refused=<n>` line while armed. The
  existing `Gate:` line can't carry it:
  - it returns null unless `requireServicePermission` is on
    (`LSSServerCommands:321`);
  - its `denied=` is the memo size, which would mix in policy refusals.

### A4. Client

**Tracking the flag (`ClientSessionGate`, volatile).**
- It is set only on the accept path: after the foreign-version return and the
  downgrade guard, and before `managerFactory.create`. A Paper v16 re-attach
  prompt or a raced 19 echo must not clear it.
- Absent means unchanged (A2).
- It is reset at `onJoin`, because a play→config reconfiguration fires no
  disconnect (`:247-249`), and at disconnect.

**Flags-only fast path.** If version, enabled, distance and generation are
unchanged on an established session, update the flag without rebuilding the
request manager.

**Write gate.**
- Xaero and JourneyMap get a separate `serverAllowsWrites` supplier, composed
  only on the write paths: `offerColumn`, `offerEncoded`, `pump` and
  `reportBackpressure`.
  - Including `reportBackpressure` means a blocked map never throttles Voxy's
    stream.
- It is **not** composed into `enabled`, because `enabled` also drives:
  - `settleSessionEnd` deregistration;
  - `settleOfflineRegistration`;
  - `bpToken`;
  - `describe()` (`XaeroSession:731,1090`; JourneyMap `:532,577,1622`).
- While blocked, the pump clears its queue and owed debt without reporting, like
  the local-off branch, and committed rebuilds finish (the CLAUDE.md invariant).
- A census test asserts that every map backend composes the supplier.

**Map-only clients** (Xaero or JourneyMap without Voxy:
`ModCompat.isMapOnlyIngestion()`).
- While denied, treat the session as having no consumer: build no request
  manager, or retire and park it the §2.4 way when the flag arrives mid-session.
- Re-arm on a flags-0 push.
- Otherwise such a client downloads the whole radius and drops every column.
  Each drop counts as accepted and stamps the map-only cache marker, so after
  disarm the map would never get those columns.
- Clients with Voxy plus a map mod don't backfill the map after disarm. That
  matches the README rule that terrain arriving while map writing is off is not
  written later (`/lss clearcache` re-streams it). Document it.

**`isArmed()` stays on the local flag**, because cache-alias keying must not
change mid-session.

**Status, diag and UI.**
- `describe()` shows `state=blocked_by_server`.
- `ClientStatus` gains `SERVER_BLOCKED`:
  - add `lss.status.availability.server_blocked` to all three lang files
    (`ClientStatusTextTest` iterates `values()`);
  - bump the snapshot schema (it is used only in-JVM and by `DiagnosticExport`).
- The options screen shows "Turned off by this server". That needs a third
  display-time condition in the `Tooltip` model and in every renderer.
- The local setting is untouched.

### A5. Part A tests

- **common:** gate ladder rows (armed × dialect × bit × enabled × consumer ×
  permission-denied), and the settings schema, default, legacy key and
  upgrade.
- **wire:**
  - bytes to a session without the bit equal 0.18.0;
  - echoes to a handshake that declared the bit equal 0.18.0;
  - the flags append;
  - decode tolerance in both directions, absent = unchanged, and trailing
    drain;
  - Paper/xplat parity;
  - the site census.
- **server glue:**
  - real vs quiet refusal, per dialect;
  - unregistration of a live re-handshake;
  - the memo reason mask, including the replay-loop pin;
  - the chat line, once;
  - arm/disarm via reload;
  - cadence enforcement;
  - revoke-before-repush;
  - the dimension-change push carries the flags;
  - the ladder-race rows (Fabric, Paper/Folia).
- **client:**
  - flag set only on accept, reset at JOIN and disconnect;
  - the clamp wither;
  - the flags-only fast path;
  - the write gate, Xaero and JourneyMap, with a mid-session flip each way;
  - map-only park and re-arm;
  - status availability and the tooltip.

---

## Part B: Xaero cave layers, so the Nether map fills (#313)

### B1. How Xaero's cave layers work

Verified against Xaero's World Map 1.45.0 bytecode by three reviewers. Offsets
are in the review record.

**Layers**
- `MapProcessor.getCaveLayer(caveStart)` is private. It passes `MAX_VALUE`
  (surface) and `MIN_VALUE` (Full) through, and otherwise returns
  `caveStart >> 4` (arithmetic, so caveStart −5 is layer −1).
- Each layer has its own regions, on disk under `caves/<layer>/`.
  `detectRegions` finds every layer folder.
- `LayeredRegionManager.getLayer` **creates** a layer when it's missing.
- `MapLayer.caveStart` is never saved. It defaults to 0 and is only set for the
  current layer, by `updateCaveStart`.

**`updateCaveStart`** decides the layer in this order:
1. Cave mode not allowed, or the mode type is 0: surface. Types are 0 off,
   1 Layered, 2 Full, with a global default of 1.
2. `CAVE_MODE_START` is read. Its default, `MAX_VALUE`, means auto.
3. Xaero's Minimap supplies caveStart when it is installed and the map screen
   isn't overriding it.
4. Still auto:
   - viewing another dimension's map gives surface;
   - otherwise `CaveStartCalculator.getCaving`, which returns
     min(roof above the centre column, floor(playerY) + 4).

   The 1 s timer only debounces surface↔cave flips.
5. Only if the result is not surface: type 2 gives `MIN_VALUE`; otherwise the
   value is clamped to [minY, maxY].

**What this means for players**
- **In the Nether** under default settings, the map below the roof always shows a
  cave layer. Sky light 0 is below 15, so `getCaving` takes the heightmap path
  and finds the bedrock roof.
  - The band tracks the player's height (Y+4); a low roof only lowers it in
    tight tunnels.
  - Every 16-block change in height switches the band.
  - On the roof, the map shows the surface layer, which is the roof-highway view.
- **Full mode** applies only below a roof. Above ground it shows the surface.

**The native writer** writes only the layer it is currently writing. It switches
that layer with a 300 ms debounce, using the layer's caveStart and the depth
config (default 30). It rewrites a tile only for loaded chunks whose 8
neighbours are loaded, within 16 chunks in cave mode (4 while caveStart is
changing), and only when the written caveStart or depth differs or the chunk is
dirty.

**`writtenCaveStart` is a render input, not just a rewrite trigger.**
`MapTileChunk.updateBuffers` passes it to `getPixelColour`, which shades by
depth:
- surface: h/63;
- Full: 0.7 + 0.3·h/logicalHeight;
- Layered: 0.7 + 0.3·(h − (cs − depth))/depth.

**Today's bridge** writes the surface only, and its pump waits while any cave
layer shows (`XaeroSession:1346-1352`). So nothing is written in the Nether.

**Out of scope:** JourneyMap leaves dimensions with a ceiling inert by design
(JourneyMap plan §3.4).

### B2. Design and phasing

Both phases rest on one fact: LSS delivers a column once, so a band that isn't
written when the column arrives stays empty.

**Phase B-I, reliable layers (ships in 0.19.0).**
- Every column writes the surface, plus the layer Xaero is currently showing:
  the current band in Layered mode, or the Full layer.
- These tiles keep today's guarantees: backpressure, owed debt, drop reports for
  a re-serve, and ingest leases.
- **Result in the Nether, Layered mode:** terrain fills the band you were in when
  it arrived. Moving 16 blocks up or down shows a different band.
- **Result in Full mode:** the whole Nether map fills, because there is only one
  layer.
- **The Overworld or End while underground:** terrain that arrives while you're
  underground goes into the layer you're viewing. Terrain that arrived earlier
  isn't copied in; `/lss clearcache` while underground backfills it.

  This is the honest scope of the #313 promise. It goes in the README, the
  #313 reply and the release note, with no extra machinery.
- Document the tip: "for a complete Nether map now, set Xaero's cave mode type
  to Full".

**Phase B-II, best-effort bands (Nether completeness; its own PR, gated by the
B0 spike).**
- In ceiling dimensions (`DimensionType.hasCeiling`), write every band covering
  the logical height (Nether: 8 bands, y 0–127). This applies within
  `min(128, the dimension's LOD distance)` chunks of the player.
- It runs as a separate best-effort pipeline (B6).
- **Beyond the bound,** columns get only the reliable layers, so the far ring of
  a large-radius Nether behaves like B-I. Document it.
- 0.19.0 waits for B-II (§D3). If the B0 spike fails its criteria, the user
  decides how to proceed with the numbers in hand; nothing ships first.
- "Reliable" and "best-effort" are client map-layer classes. They never touch
  the wire, which is never tiered.

**Mode switches.** Switching between Layered and Full, or turning cave mode off,
does not backfill terrain already delivered into the newly selected layers.
That matches native behavior; `/lss clearcache` re-delivers it.

### B0. Spike: the gate for B-II

The spike answers: does Xaero tolerate tiles written into layers the player
isn't viewing?

**Known hazards**
- **Non-current-layer regions save every 600 ms, not every 60 s**
  (`MapSaveLoad.updateSave` 13-38). Each save triggers a re-cache, so the region
  isn't resting until the rebuild finishes.
- **A fresh band region can be removed.** It is saved on the first loader pass
  after loading. If it has no chunks yet, `countChunks()==0` makes the loader
  call `removeMapRegion`.

**Setup**
- the 26.3 line with Xaero 1.45.0;
- a stock 64-chunk Nether, 8 bands;
- a harness client under Xvfb :99, focused and unfocused at 1 FPS;
- the length of the `xaero-memory` soak;
- B-II on versus a B-I-only baseline.

**Pass criteria**

| Check | Pass |
|---|---|
| Saver, "cache not prepared" or Xaero exceptions | zero |
| Old-gen heap after GC | ≤ 1.2× baseline |
| Open regions | ≤ the window cap |
| Re-grants of the same (layer, region) | at most 2 |
| Reliable-path region-load p95 latency | ≤ 1.25× baseline |
| Voxy delivery rate during the join burst (this also covers decode-thread extraction cost) | ≥ 0.9× baseline |
| Best-effort drop rate on the stock join | < 5% |
| Region files on disk for every band | present after a save and after a restart |
| Each band's map when the player walks into it | correct |

**Also record**
- saves/s and MB written;
- `handleRefresh` count;
- decode time per column;
- whether skipping `updateBuffers` for non-rendered layers is safe;
- what the roof (surface) layer shows.

**Time box:** 2 working days. The user decides ship, slip or cut from the
numbers.

### B3. The layer plan: what extraction needs

**Band set.** It is derived on the decode thread, from the column's own level
(`hasCeiling`, minY, logical height) and the player position. It never comes
from a plan published for another dimension.

**Current-layer facts.**
- The pump publishes the mode, current layer and depth at the **top** of
  `pump()`, before its gates, tagged with the dimension. The mode is taken from
  the actual current caveStart: `MAX_VALUE` → surface only, `MIN_VALUE` → Full,
  anything else → that band.
- **On a dimension mismatch,** extract with Xaero's defaults (Layered, depth 30)
  and band (playerY+4)>>4. This covers the burst of columns on Nether entry,
  which arrives before the pump re-reads Xaero. LSS never re-sends those
  columns, so extracting them with the Overworld's surface-only plan would leave
  them unmapped.

**caveStart for every LSS band tile** is the band top, min(16b+15, maxY). That
includes the current band.
- It is deterministic, so tiles in one band agree across positions and sessions
  and shade consistently. The current band's exact caveStart moves with player
  Y, which would give a patchwork of brightness and ledges that nothing ever
  rewrites.
- **Never read `MapLayer.getCaveStart` for bands, and never call `getLayer` for
  planning:**
  - `getLayer` creates layers;
  - layers created that way, or by `detectRegions`, report caveStart 0, which
    would extract band 5 over the window [−29, 0].
- **Full tiles** use `MIN_VALUE`.
- **Accepted seam:** a visible edge where the native-written disc near the
  player meets LSS tiles. Native uses the exact caveStart there. Document it.

### B4. Extractor

Port the cave branch of `MapWriter.loadPixel` byte-exact, as the surface branch
was (`scanPixel` is "the loadPixel loop with the cave branches folded away").

**Layered**
- **Window:** highY = caveStart, lowY = max(minY, caveStart+1−depth).
- **Roof skip:** skip every non-air block down from highY. The roof ends at the
  first air **or the first fluid**. The fluid branch sets `underair`, so lava or
  water in the roof becomes the floor or an overlay.

**Full** (no depth limit)
- **Start:** the per-pixel WORLD_SURFACE height, derived from the column. When
  ignoreHeightmaps is on, or that height is below minY, use the section-based
  height instead.
- **Fluids** are ignored until ground is entered.
- **Ground** means a block that is all of: !ignitedByLava, !canBeReplaced, push
  reaction not DESTROY, and !shouldOverlay. Logs, leaves, snow layers and ocean
  water are therefore skipped.
- **After ground,** skip until the first air.

**Light**
- In a sky-lit dimension, when block light is below 15:
  - sky = 15 if (!ignoreHeightmaps ∧ !full ∧ caveStart ≥ the WORLD_SURFACE
    height);
  - otherwise sky = the sky light at h+1.
- Floor light = max(block, sky) only when the pixel has no overlays. The first
  overlay's light = max(block, sky).
- Inputs needed: the column's `SectionData.skyLight`, a WORLD_SURFACE height
  derived from the column, and a `MapWorld.isIgnoreHeightmaps` binding.

**Void pixel:** height is worldBottomY, but topHeight is lowY and the biome is
sampled at lowY. Today's `scan.reset(worldBottomY)` is wrong in cave mode.

**Writing tiles**
- Pass the real `cave` flag to `MapBlock.write`. It isn't stored on 1.45.0;
  verify per line.
- One column yields one `PreparedTile` per target layer, sharing the decode.
- **Each `PreparedTile` carries the (layer, caveStart, depth) used to extract
  it.** The commit writes `setWrittenCave(caveStart, depth)` from the tile.
  - Today `commitPixels` re-reads the depth config at commit time.
  - Trap: `setWrittenCave` takes caveStart, never the layer number.
- **Skip all-void best-effort band tiles** (bands buried in netherrack). That
  saves regions and disk.

**Per-line seam:** 1.21.1 clamps to `getMinBuildHeight`/`getMaxBuildHeight`,
whose top is exclusive.

**Cannot be reproduced from LSS column data:** the real light engine across
absent sections, biome zoom, and model translucency. These are the existing,
documented approximations. The cave scan itself needs no neighbour chunks.

### B5. Layer-keyed bookkeeping (B-I)

**Replace `SURFACE_LAYER` with the tile's layer** in all four
`getLeafMapRegion` sites:
- `XaeroTileWriter.commitEntry`;
- the `XaeroAcquisitionQueue` owed probe (`:352`);
- `probeOpenRegions` (`:1626`);
- `requestRegionLoad` (`:1733`).

`setWrittenCave` gets the caveStart, per B4.

**Add the layer to every key:**
- the queue key: (layer, packed chunk);
- the `drainEntries` bucket key (`:1427`);
- `WaitingRegion`, `grantLoads` and `requestRegionLoad`;
- `OwedKey`;
- `releaseOwed`'s queue lookup (`XaeroAcquisitionQueue:447`);
- `forgetOwed` and `shedToOwed`;
- `awaitingRegions`;
- the open-region window;
- the rebuild `PendingKey` (`hasRebuildCapacity`, `notePendingUpdate`);
- the `offerColumn` pre-gate (`:929`);
- the test seams.

**Delete the cave-layer wait gate.** A tile's class is fixed at extraction. A
queued reliable tile whose band is no longer current still commits to its own
layer, because it is valid data.

**"Natively writable" skip** (`XaeroTileWriter:24-35`, `XaeroSession:1471-1480`).
Skip only when all of these hold:
- the tile's layer is Xaero's writing layer;
- the chunk is within the native write distance (min(16, write distance) in cave
  mode);
- its neighbours are loaded.

Otherwise, surface and band tiles at loaded chunks are skipped by LSS and written
by nobody.

**Receipts** (the CLAUDE.md receipt-release invariant).
- Every reliable tile takes its own `deferAcceptance()` lease; `ColumnDelivery`
  already counts leases. A shared Origin would close on the first commit and
  orphan a later drop report.
- Best-effort tiles (B6) carry a lease-free generation token.

**Owed debt and reports.**
- Only reliable tiles owe and report.
- Reports are deduped per position: one re-serve per column, however many of
  its tiles dropped. The null-handle `dropReporter` path gets a compare-and-set
  dedupe.

**World-save mode** (singleplayer or LAN host, `isUsingWorldSave()`): cave writes
are off.
- Native `writeChunk` marks other layers outdated, and `caveStartOutdated`
  rebuilds them from the world save.
- Surface behavior is unchanged. Multiplayer is unaffected, because
  `caveStartOutdated` is always false there.

### B6. Best-effort band pipeline (B-II) — SUPERSEDED by B10 (spike failed, §B10.1)

**Isolation from the reliable path.** It has its own queue, with its own count
and byte caps.
- It sits outside the occupancy figure and the backpressure report.
- DEFERRED, AWAITING and expired entries drop oldest-first and are never kept or
  owed.
- It has its own window share, which never sets `windowBound`.
- A pending-rebuild sub-cap refuses best-effort commits past the soft cap.
- It drains only leftover pump budget, under its own commit cap.

**Load grants and batching**
- Loads are granted only when no reliable region is waiting and
  `getToSave()` is empty.
- Batches accumulate per (layer, region) until they hold K tiles or are T
  seconds old.
- A batch commits in the same pump that first sees its region at loadState 2.
  This beats the 600 ms save and the `countChunks()==0` removal (B0).
- K and T come from the spike.

**Bound:** `min(128, the dimension's LOD distance)` chunks around the player at
extraction.

**Rough cost** (Xaero lifecycle review; disk assumes 0.3–0.5 MB per
layer-region):

| | Stock 64 Nether | 512 Nether, bound 128 |
|---|---|---|
| Tile commits | ~116k (12.9k × 9), vs ~12.9k today | ~2.0M |
| Pump time | ≥ 90 s at the 1,280/s ceiling | |
| Layer-regions | ~225 | ~2.7k |
| Disk | ~70–110 MB | ~1 GB |
| Heap | window-capped | window-capped |

The README gives the real disk figure from the spike, the way the JourneyMap
bullet does.

### B7. Bindings

**New members**, all public in 1.45.0 (26.2 Fabric and 1.21.1 NeoForge), checked
with javap:
- `MapProcessor.getMapWorld` → `MapWorld.getCurrentDimension()` → `MapDimension`;
- `MapDimension.getCaveModeType`;
- `MapDimension.getLayeredMapRegions`;
- `LayeredRegionManager.getLayer`, for writing only;
- `MapWorld.isIgnoreHeightmaps()Z`;
- `WorldMapClientConfigUtils.getEffectiveCaveModeAllowed`.

`MapProcessor.getCurrentDimension()` returns a String and is already bound.
`getCaveLayer` is reimplemented in LSS.

**Optional binding.** When a member is missing, cave writes stay off, the
surface works as today, and the diag says so. No Xaero jar older than 1.45.0 is
on disk, so add the 1.42.0 floor jar as a golden.

**Golden binding-surface test.**
- `XaeroBindings` records each resolved `Member`.
- An ASM check compares that list against each line's golden Xaero jar and its
  nested jars (the `JourneyMapGolden.loader` pattern):
  - Minecraft types are erased on the intermediary lines;
  - members found by scanning are compared by name and arity.
- Today, Xaero coverage is only the hand stubs under `fabric/src/test/java/xaero`.
  The JourneyMap `reflectAs` approach doesn't fit: every Xaero handle is
  `.asType`-adapted, and the xaerolib members live in a nested jar.
- **Per-line pins:** add `xaero_golden_*` properties per line, using the lenient
  offline pattern from `fabric/build.gradle:141-144`. Today only main and
  `support/mc26.2` carry `xaero_dev_version`.

### B8. Observability

- The XaeroMap diag gains
  `layer=<current> mode=<surface|layered|full> best_effort_written=<n> best_effort_dropped=<n>`.
- `cave_layer_waits` goes away. Update the client-snapshot exporter contract to
  match.
- `XaeroSession` and the `IngestBackpressureGovernor` differential test feed
  reliable-only occupancy.

### B9. Part B tests

**Differential parity test** (replaces hand-derived fixtures as the parity
oracle).
- Load the golden `MapWriter` child-first, and subclass it to override the public
  `shouldOverlay` with the extractor's approximation.
- Mock `Level` (`hasSkyLight`, `getBrightness`, `getBiome`, fed from the column)
  and `LevelChunk` (`getBlockState`, `getPos`) with Mockito.
- Call `loadPixel` with `writeChunk`'s arguments, then compare the `MapBlock`
  fields per pixel against the `PreparedTile`.
- Overlay fixtures need a `mockStatic` of `Minecraft.getInstance().getModelManager()`;
  otherwise keep fixtures overlay-free (most Nether fixtures are).

**Fixtures**
- roofs: caveStart inside the roof, caveStart in air, a lava pocket in the roof,
  a waterlogged roof;
- the depth window: floor at lowY and at lowY−1 (void, topHeight = lowY), the
  minY clamp;
- Full mode: grass, log, leaves, snow layer and ocean columns;
- light: the sky shortcut, and sky light with and without overlays;
- band edges: caveStart −5, and the top band.

**Stub bookkeeping tests**
- every key in B5;
- leases and dedupe;
- the layer-switch commit;
- the natively-writable skip by layer;
- world-save off;
- missing-binding fallback;
- dimension-mismatch extraction;
- for B-II: isolation, drop-oldest, the batch-commit window, and the bound.

**Live Nether gate.** A harness client walks across bands and roof heights, with
and without Xaero's Minimap. Check:
- the diag shows per-layer writes;
- region files exist for every written layer;
- heap stays within the `xaero-memory` envelope.

**User visual check** of the Nether map in Layered and Full mode.

---

### B10. Fill-on-switch (replaces B-II; user decision 2026-10-13)

#### B10.1 Why B-II was dropped: the B0 spike

The xaero-nether-bands soak walked a harness client through Nether bands 2, 3, 4 and 6
for 15 minutes on two copies of the 26.3 test world. Results are under
`soak-results/xaero-nether-bands-*` on the 26.3 worktree. "Baseline" is B-I only.

| Run | Arrival | Outcome |
|---|---|---|
| B-II round 1 | Trickle (~20 columns/s, server generating) | Starved the reliable queue (1–2k tiles for ~9 min). `grant_max` 23. Loss 10.9%. Heap 1.9×. |
| B-II round 2, after decoupling | Trickle | Reliable path OK; Voxy 0.94×. Region load p95 2× (6.4 s vs 3.2 s). Heap 1.8×. Loss 30%. 1909 of 1977 grants fruitless (`defer_not_resting` 9389). |
| B-II, pre-generated world | Burst (18.2k columns in ~100 s) | Queue full in 15 s. 84k band tiles refused; the 4092 queued expired. **7 tiles written.** |

Root cause: Xaero saves and re-caches regions of layers the player isn't viewing every
600 ms, so they are almost never resting. Writes into them fight that cycle. Fast
arrival overflows any bounded queue. Every run wrote all 8 bands' region files (round 1:
21–25 regions per band, ~32 MB at radius 64), but at a cost the criteria reject.

The pre-generated runs also showed B-I's real limit. After the burst, the walk visited
bands 4, 6 and 3 with no new arrivals, because LSS sends a column once. Those bands got
nothing from LSS.

#### B10.2 Design

**Write only the layer Xaero shows,** which is the path that works in every run. Fill a
layer from a client-side copy of the columns when Xaero switches to it.

- **Store.** A bounded, disk-backed client store of received columns, for ceiling
  dimensions only.
  - Keyed like the client column cache: server identity, aliases and world
    sub-buckets, then dimension.
  - It holds what the cave/Full extraction needs, compressed.
  - It is crash-safe, does its IO off the main and render threads, and is LRU-capped
    (default 256 MiB).
  - It is cleared by `/lss clearcache` and `/lss reset`; a re-serve overwrites an entry.
  - No capture happens while the server denies map ingestion, while local map
    ingestion is off, in world-save mode, or without the cave bindings.
- **Trigger.** Xaero's current layer in a ceiling dimension changes (a Layered band or
  Full) and stays stable for a short debounce. A fill job then reads stored positions
  within min(LOD distance, cap), nearest first, skipping positions already written to
  that layer this session.
  - Coverage is session-scoped, so it heals across sessions and Xaero map resets.
  - Tiles are extracted off-thread and offered to the reliable queue for the shown
    layer as lease-free tiles that never report, owe or re-serve.
  - The job is paced below live arrivals.
  - It is cancelled by a switch, a dimension or world change, a disconnect, or a deny.
- **Overworld and End.** Unchanged (B-I): no store. Terrain that arrives underground
  goes into the band you're in; `/lss clearcache` backfills.
- **Spike law.** After each band switch in the walk, the band receives at least 90% of
  the stored positions within radius within 90 s, and there are no Xaero exceptions.
  Reliable load p95 and heap are compared by hand against the B-I arm. Run both spike
  worlds (trickle and pre-generated).

## C. Shared: validation, docs, release, ports

**Live gates on main**, on the local rig with harness clients under Xvfb :99.

Part A:
- A new client with Xaero, policy on: Voxy fills, and LSS tile writes stay at 0.
- A Xaero-only new client, policy on: no request manager, and no column traffic.
- A 0.18.0 client: no LODs, one chat line, and `refused=1`.
- Reload on and off with all of these connected. Check no replay loop for 2 min
  (counters flat).
- A Nether round trip, policy on: the flag survives the dimension-change push.
- The JourneyMap smoke variant (`scripts/journeymap_smoke.sh`).
- A v19-dialect client via `SoakDialectOverride`: refused at handshake, and kept
  serving across a live arm.

Part B: B0 (for B-II) and the B9 live gate.

**Docs**
- The config comment (A3.1).
- Regenerate the settings reference (`docs/reference/settings.md`,
  `settings-schema.json`).
- README:
  - The compatibility paragraph promises older clients keep working through
    server updates. Add a carve-out: while `request_no_map_ingestion` is on, clients
    older than 0.19.0 get no LODs.
  - Replace the Xaero bullet, which says cave layers are not written. The new
    text covers:
    - the B-I scope;
    - the Overworld/End underground scope;
    - the Full-mode tip;
    - B-II and its disk figure, if B-II ships;
    - the native-disc seam.
  - The "privacy changes need a restart" sentence (A3.1).
  - The server option's limitations (A1 honesty scope).
- CLAUDE.md's map-ingestion section, and the server-file downgrade note.
- Release-note lines: short, user-focused bullets.
- Replies on #316 and #313 when it ships.

**Ports**
- Six lines: main (26.3) first, then 26.2, 26.1, 1.21.11, 1.21.10 and 1.21.1,
  using the change-core applier.
- Include Paper, Sponge and NeoForge wherever each line ships them.
- **Validation is proportionate** (the support-line effort budget):
  - main gets the full gate above;
  - each support line gets CI, its golden binding-surface test (per-line
    `xaero_golden_*`), and the A-wire byte pins;
  - plus one Xaero smoke on the oldest line, 1.21.1, for its build-height seam.

## D. Decisions (user, 2026-10-13)

1. **A3.3 chat line to refused clients:** yes, with the drafted text.
2. **Key name:** `privacy.request_no_map_ingestion`.
3. **Release sequencing:** the 0.19.0 release waits until everything is done:
   Part A, B-I and B-II. If the B0 spike fails, the user decides the next step
   from its numbers.
4. **After the spike (B10.1):** a pre-generated rerun was requested, then B-II
   was replaced by fill-on-switch (B10). 0.19.0 waits for it.

## E. Review fold (2026-10-13: 4 Opus + 1 Fable)

| Finding (reviewers) | Severity | Folded in |
|---|---|---|
| Grant-sweep replays refused players every 10 s (Fable, Opus wire, Opus server) | MAJOR | A3.4 reason mask + pin |
| Dimension-change push missed, so the flag would re-allow writes (Opus wire, Opus server) | MAJOR | A2 site table + census, absent=unchanged belt |
| Live arm never triggered: the service-policy compare ignores `privacy.*` (Opus server) | MAJOR | A3.4 `adoptedMapPolicy` + sweep + cadence |
| Legacy "silent stop" not silent or leak-free; legacy clients can't map (Opus wire, Opus server, Fable) | MAJOR | A1, A3.4: refused at handshake, never revoked |
| Map-only clients download everything for nothing and lose it after disarm (Fable, Opus server) | MAJOR | A4 map-only park |
| Non-current-layer regions save every 600 ms; load/remove loop (Opus lifecycle) | MAJOR | B2 phasing, B0 hazards and criteria, B6 batching/grants |
| The best-effort tier shares every reliable-path resource (Opus lifecycle) | MAJOR | B6 isolation |
| Ingest receipts with several tiles per column (Opus lifecycle) | MAJOR | B5 receipts |
| Layer plan stale on Nether entry (Opus lifecycle) | MAJOR | B3 decode-side band set + top-of-pump publish |
| `MapLayer` caveStart is 0 for unvisited layers (Opus extractor, Opus lifecycle) | MAJOR | B3 band-top rule, never read layers |
| `writtenCaveStart` drives shading; the current band's exact value is a patchwork (Opus extractor) | MAJOR | B1, B3, B4 tile carries caveStart/depth |
| Overworld promise oversold (Fable) | MAJOR | B2 honest scope, C docs |
| `/lsslod set` was removed (Opus server) | MINOR | A3.1 reload feedback |
| Append guard must also require version 20 and not v16 (Opus wire, Opus server) | MINOR | A2 encoder guard + echo pins |
| Raced 0.19 rung-19 refusal unregisters the live session (Opus wire, Opus server) | MINOR | A3.2 quiet refusal + race rows |
| Folia race at arm; revoke before repush (Opus wire, Opus server) | MINOR | A3.4 |
| Client flag lifecycle, JOIN reset, clamp wither (Opus wire, Opus server) | MINOR | A2, A4 |
| Composing into `enabled` breaks registration/diag; use a write-path supplier (Opus server) | MINOR | A4 |
| Every flip rebuilds request managers (Opus server) | MINOR | A4 fast path |
| `Outcome` branches; Via source pin (Opus wire) | MINOR | A3.2 |
| Gate line null unless permission armed; rig metrics; lang keys; tooltip condition (Opus server) | MINOR | A3.5, A4 |
| Settings legacyKey, privacy comment, downgrade trap (Opus server) | MINOR | A3.1, C |
| B1 order wrong; Nether doesn't ignore heightmaps; band tracks player Y (Opus extractor, Opus lifecycle) | MINOR | B1 |
| Port list incomplete: fluid roof end, Full ground rules, light, void pixel (Opus extractor) | MINOR | B4 |
| Natively-writable skip ignores layers (Opus lifecycle, Opus extractor) | MINOR | B5 |
| World-save mode conflicts (Opus lifecycle, Opus extractor) | MINOR | B5 |
| Owed/report fan-out per layer (Opus lifecycle) | MINOR | B5 |
| Decode-thread extraction cost (Opus lifecycle) | MINOR | B0 criterion, B4 void skip |
| B7 member list; floor jar; golden test design (Opus extractor, Fable) | MINOR | B7 |
| Spike had no numbers (Fable) | MINOR | B0 |
| Beyond-radius far ring patchy; bound = min(128, LOD distance) (Fable, Opus lifecycle) | MINOR | B2, B6 |
| README compatibility carve-out, disk figure (Fable) | MINOR | C docs |
| Proportionate six-line validation (Fable) | MINOR | C ports |
| Differential parity test instead of hand fixtures (Opus extractor) | MINOR | B9 |
| Brand name in the chat line; latch; thread (Fable, Opus server) | NIT | A3.3 |
| "Tier" wording collides with "wire fidelity is never tiered" (Fable) | NIT | B2 rename |
| Handshake sites, soak dialect bits, flag constant, trailing drain, "pre-0.10" (Opus wire, Opus server) | NIT | A1, A2 |
| Sponge uses the xplat codec (Fable) | NIT | A2 |
