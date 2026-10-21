# StepCheckered — Change Log & Architecture Notes

**Purpose:** every AI agent working on this repo reads this file FIRST, before AGENTS.md.
It tracks what changed, why, and the current architecture — so no one re-derives or
accidentally undoes prior work. Update this file with every significant change.

---

## Module architecture (current state)

### Registration flow (one-time setup, saved to config)

```
Lower Map Area (1 click, auto-snaps to map tile grid)
  └─ Upper auto-derived (+2, print box is 4 tall)
Cartography Table
  └─ Finished Map Chest
     └─ Anvil (any damage state)
        └─ Food Chest (must contain ≥1 food item)
           └─ XP Bottle Chest (must contain XP bottles)
              └─ Material/Map Chests (multi-type per chest, tools included)
                 └─ Start Block → begins the run
```

### Bot-role setting

- Dropdown: **empty** (default, errors on activation) / **master** / **slave**
- Set on EACH instance before starting; optional username overrides exist
- `resolveTcpRole()`: username match → dropdown → "auto" fallback

### Master lifecycle (StepMasterStage state machine)

```
IDLE → FETCH_MAP → FILL_MAP → WAIT_FILLED_MAP → pin map id
  → WAIT_PRIMING_BUILD → PRIMING_WALK (x=125, z14→112, map held)
  → [per batch k = 0..62:]
      WAIT_BATCH_BUILD → BATCH_WALK_A (x=2k+127, z14→112, dwell 4s, map held)
                       → BATCH_WALK_B (x=2k+127, z112→14, dwell 4s, map held)
                       → WAIT_BREAK_2 (signal "step:break2" to slave, stow map, hop east)
                       → WAIT_BREAK_ALL (signal "step:breakall" when slave confirms break2done)
                       → next batch (or closing)
  → WAIT_CLOSING_BUILD → CLOSING_WALK (x=253) → WAIT_CLOSING_BREAK
  → LOCK_WALK → LOCKING (cartography table screen clicks) → READY_TO_LOCK
```

### Slave batch lifecycle (6 named phases per batch k)

All batch-route phases walk **lane 2 — x = 2k+2.5, the exact center of block 2k+2
(the borrow-west lane)**: its only blocks are borrow recessives at y2 (above the bot's
head), so nothing ever sits at feet level on the walking lane. Priming walks the same
relative lane (corner+2.5, its window = lanes 0-3); closing walks corner+127.5
(block 127 center, lanes 126-127).

| # | Phase | What happens | Lane | Direction |
|---|-------|-------------|------|-----------|
| 1 | SUPPLY | Restock materials + pickaxe (slot 8) + food (offhand) via north corridor | corridor → station | out & back |
| 2 | PRINT | **Center on lane 2 first** (lateral `centerLane` leg; placement suppressed until centered), then walk lane 2, litematica-style in-range placement (any direction, nearest first), 4-tick cooldown retry per position; repair pass also from lane 2 | lane 2 | N → S |
| 3 | SUPPRESS | Wait master break(2) at south end, then walk north breaking west Y2/Y3 only (instamine fastBreak) | lane 2 | S → N |
| 4 | DEPOSIT | Wait master break-all at north end, then deposit leftover building blocks via north corridor (also fires mid-break when inventory fills) | corridor → station | out & back |
| 5 | RECYCLE | Break-all pass over the whole 4×128×4 window + noobline, drops collected by proximity | lane 2 | N → S → N (batchExit) |
| 6 | SWEEP | `batchExit` → `finishBatch()`: combined spatial break+collect of stragglers (loops back to batchExit while any remain), then `step:done:k` | window | in place |

Mid-phase supply runs rebuild the route via `resumeBatchRoute(stage)`, which knows all
three route shapes: regular batch (2k+2.5), priming (2.5), and closing (127.5 build /
253.5 break).

### RESET (full-area clear — checkpoint-batch ladder)

The box is divided into **4-lane columns × 5 z-segments** (like a batch window with 5
checkpoints), scoped to the batch being printed (or the last one) plus one safety
column west. Each segment is cleared by **one continuous serpentine pass**: the bot
walks the column's centre lane straight through the segment in Mining state, breaking
everything within interaction range continuously (a per-tick feed keeps a break target
armed from the current segment) while drops get picked at its feet. At the pass exit a
bounded scan CONFIRMS the segment; only stragglers (bounced drops, unconfirmed breaks)
get targeted cleanup. Each pass exit is the next pass entry — the whole ladder is one
uninterrupted snake with no backtracking (greedy nearest-first hopping removed: it
ping-ponged the bot across segments). Segment 0 owns the noobline row. Interrupts
(pickaxe emergency, inventory full) resume the ladder at the current segment. After
the last segment the two-vantage spatial sweep runs as the FINAL verification, whole
box (budget renews instead of declaring complete over visible work).

### Shared helpers (reuse, don't duplicate)

- `findNearestGroundItem(margin)` — nearest pickup-able ground entity, print box ± margin
- `targetGroundItem(item, action)` — walk-to-item checkpoint for vanilla pickup
- `findNearestPrintBoxBlock()` — ring-scan for nearest non-air block in print box
- `findRegisteredPickaxeItem()` — pickaxe from toolSet or materialDict
- `assembleBatch(k)` / `assemblePriming()` / `assembleClosing()` — world-coord plans

---

## Change history (most recent first)

### 2026-10-21 (p) — AUTO-SPEED option: Meteor's Speed module plainly on for the whole run (user request)
New "auto-speed" bool in the General group, **default ON**. While the module is
active it keeps Meteor's Speed module ENABLED exactly as the user's own profile
has it — no writes to speed-mode/vanilla-speed/timer — and pins our sprint-mode
to plain Always on the first session tick (never Dynamic). Per user: "enable
the speed module, set sprint as Always, don't change anything like dynamic
sprint or else."

- **Hub** (`StepCheckered.autoSpeedTick`, first thing in onTick so both roles
  and both bots get it): session-start pins sprint-mode -> Always, then
  re-asserts Speed enabled every tick (self-heals a stray manual disable).
  Option OFF mid-run disables the module we enabled (a selected Dynamic mode
  re-takes it the same tick — no blip). Module deactivation still kills Speed
  through the existing onDeactivate -> setTransitSpeed(false).
- **Dynamic rider stands down** (`SlaveEngine.updateTransitSpeed`): with the
  option on it retires any live transit session WITHOUT disabling Speed and
  never rides phases. Option off = exactly the old behavior (Dynamic manages
  Speed on transit legs; manual Speed untouched).
- Note: always-on Speed means break passes run speed-hacked again — the
  reach-gated expiry + auto-unstick fixes carry that; the pending breaking
  rework (approach-to-1-block, burst-all-in-reach) is the durable answer.
- Structural change (new setting + field + method) → RESTART-deploy required,
  hotswap cannot carry it. UNTESTED on the server — pending user go-ahead.

### 2026-10-21 (o) — AWAIT-RESTOCK FREEZE: lost-sync self-heal for tool parking (live 06:54, batch 9, user screenshot)
Evidence: batch 9 prep queued 13 materials + "2 spare pickaxe(s) carried -
depositing them" (the user had re-added spare tools). The refill drain ran
normally (~1 entry/s, silent pops — the checkpoint dbg only prints on ACTION
change, so a same-chest drain shows no queue lines), then at 06:54:01 one
handler round ended WITHOUT setting Walking and the bot froze in
AwaitRestockResponse for 2.5 min until the user toggled off. That round was a
TOOL PARKING click: parking deposits ONE tool per chest-open and returns,
waiting for the server's post-click inventory sync to drive the next round.
If that sync is lost (screen-close race: processDeferredInventoryIO closes the
chest one tick after handling, and a click landing after the close produces no
sync), NOTHING re-opens the chest — handleRestockResponse's entry zeroes
interactTimeout, so the retry watchdog is disarmed. Pre-existing fragility in
the pickaxe parking; the axe parking (j) doubled the one-click rounds and the
every-prep tool entries widened the race window. FIX (RestockManager, both
parking blocks, body-only): after each parking click, RE-ARM
interactTimeout = retryInteractTimer (80t vs 2t dwell — no overlap). Sync
arrives → next round (zeroes it again, as before); sync lost → tickInteractRetry
re-opens the chest after 4s and the dance continues. The take path needs no
arm (restockBacklog drain is tick-driven, not sync-driven). Build green; NOT
deployed — awaiting go-ahead.

### 2026-10-21 (n) — ECHO CHECKPOINTS: master trails the slave's passes, walk at echo k+1 / hold at echo k+2 (user design)
Removes the two dead full-waits: print→ready→W1 (58s+33s serial) and break2→
break2done→W2 (35s+34s). Design (user, verbatim intent): the slave ECHOES 5
equally spaced travel-fraction points per shadowed pass — fire-and-forget, the
slave NEVER waits on them; the echo counts accumulate on the master (monotonic
max — "already crossed" knowledge that never regresses). First cut used mating
distance 2 for everything; the user tightened it the same day (slave is fast):
the master STARTS WALKING when the slave crosses echo 1, and HOLDS the map at
station k only when the slave crosses echo k+2. Walking early is pixel-free
(map-stowed transit, the live-proven hold guard) — only the dwell-HOLD records.

- **Slave** (`SlaveEngine.tickEchoCheckpoints`, called from the slave tick):
  thresholds {26, 52, 77, 103} rows of travel from the pass entry edge (print
  N→S from row 0, break(2) S→N from row 127 — the FIXED CYCLE makes directions
  static) = 4 echoes per pass. Pure position math, no latch — crash/resume
  safe; guarded to in-box positions so supply/emergency/prep trips north never
  fire progress; counters reset at pass boundaries (breakStage/batch change);
  straggler sweep-backs only lower traveled so they can never fire an unearned
  echo. Batch 0 split-build sends NO build echoes (its W1 records the floor
  half and must stay ready-gated). Messages: `step:echo:build:<batch>:<k>` /
  `step:echo:break2:<batch>:<k>`.
- **Master**: hub switch validates batch (stale echoes dropped) and max-merges
  counts (`StepCheckered.onStepSlaveMessage` case "echo"). Launch: W1 on build
  echo 1/4 OR ready; W2 on break2 echo 1/4 OR break2done (StepMaster
  WAIT_BATCH_BUILD / WAIT_BREAK_2). Walk level: `stepQueueSweep` feeds stations
  ONE at a time via `stepFeedSweepStations` — a station enters the queue only
  when `stepWalkReleased(ordinal)` = echo ≥ ordinal+1, so the master never
  walks to an unreleased station. Hold level: the walker's arrival block PEEKS
  before popping — an arrived station whose `stepHoldReleased(ordinal)` = echo
  ≥ ordinal+2 hasn't landed stays QUEUED (not consumed), the master stands on
  it with the map stowed and the station dispatches the tick its echo lands;
  the queued head also blocks feeding the next station, so no dwell is skipped.
- **Safety margin** (kernel doc §3): every hold keeps the slave one travel
  checkpoint (~26 rows) ahead of the master's ±14–15-row record band → ≥ ~21
  rows of slack at every hold (station 1: band edge ~29 vs slave at row 52;
  binding cases 21–24). Echo max is 4, so stations 4–5 hold only on pass-done —
  station 5's band touches the last rows = exactly the old full-build
  semantics. Gate A (breakall after W2 complete) untouched; completion is
  tagged `stepDwellFinal` (queue-emptiness no longer means "sweep over").
- **Degradation**: ready/break2done still arrive and still release everything —
  no echoes (bug, resume, stale jar) = exactly the old full-wait flow. Gated
  standing counts the SAME stepWaitTick heartbeat (30s) / 10-minute abort as
  named waits — a dead slave trips the timeout, no silent hang.
- NOT changed: all routes/checkpoints/stations/dwells/hold-stow discipline,
  closing walk (releases unconditionally), priming, RESET, collect/deposit,
  batch-0 split choreography, Gates A/B. [BATCH-TIME] "build"/"break(2)" phase
  metrics now mean time-to-launch (overlap-inclusive) — re-baseline after deploy.
- Structural change (new fields/methods) → RESTART-deploy required, hotswap
  cannot carry it. UNTESTED on the server — pending restart-deploy + pxwatch
  verification of the first shadowed batches. Expected: W1 ~91s → ~69s,
  break2+W2 ~69s → ~42s (re-measure from [BATCH-TIME]).

### 2026-10-21 (m) — CONFIRM WINDOW 6→10 ticks: echo-lag double-swings + the lone pass straggler
Evidence the ABORT recipe worked (slave restarted 05:56 on the new jar): [RATE]
confirm ratio jumped from 1-16 of 20-36 sent to **27 of 28** — the server now
accepts ~every pair. What remained: occasional multi-swing on instaminable
blocks and batch 6 ending blocks=1 on first verify. With rejections rare, an
unconfirmed position is almost always Folia's block-update echo STILL IN
FLIGHT — the 6-tick window expired and re-sent blocks that had already broken
(harmless packet, visible extra swing), and in the worst case cascaded the
ladder into a standing straggler. FIX: breakPending window 6→10 ticks in
sendHybridBreak (the one copy). True rejects now cost 0.5s to detect —
acceptable at a 27/28 acceptance rate. Hotswapped into both running instances
(39 classes incl. the (k) abort+crash fixes the 05:56 restart predated for the
master) + mods jars synced.

### 2026-10-21 (l) — LOOKAHEAD DEMAND INVERSION FIXED + one eviction authority (user: "lookahead is not working properly while printing")
**Root cause 1 — the roller computed demand over the WRONG blocks.**
`rollingHotbarLookahead`'s window loop skipped AIR plan blocks and counted NON-air
ones. AIR is the to-place set (same semantics as the placement picker and
`getRequiredItems`), so demand/firstUseZ were computed over ALREADY-PLACED work —
the ≤2-row tail behind the bot. Consequences: future materials never entered
demand (never pre-staged — every first use fell to a stop-and-swap), just-finished
materials sorted as most-urgent (firstUseZ ≈ botZ-2), and future residents got
`firstUseZ == null → eDist = MAX` = preferred Belady eviction victims. The roller
was actively anti-lookahead. Fix: `!isAir()` (demand = still-air window blocks).
**Root cause 2 — `swapIntoHotbar`'s eviction heuristic was dead + anti-stacking.**
Its "distance to next use" scan ran through `forEachMapBlock`, which passes the
desired block as a hardcoded `null` → every distance stayed "never used" (-1);
and its hotbar-FREQUENCY priority evicted double-staged materials first (the
opposite of the "stage two stacks" rule). Victim choice was arbitrary and
un-did correct staging at use-time.
**Fix (single authority, no duplication):** new `BreakPlaceEngine.WindowDemand`
snapshot (`computeWindowDemand()`: firstUseZ/demand/supply/botZ over the
[botZ-2, botZ+24] window) + `pickHotbarVictim(wd, inItem, srcCount, force)` used
by BOTH callers: the roller (force=false: load-bearing residents protected,
Belady farthest-first) and `swapIntoHotbar` (force=true: needed NOW, farthest-first
even when all load-bearing; roller re-stages the victim within 1 s). Victim
preference: same-item top-up trade > empty slot > farthest first use. Roller
upgrades: largest carried stack per bring (was first-slot), srcScan 9-35.
Deleted: `forEachMapBlock` + `MapBlockAction` (RestockManager) + unused
`AtomicReference` import — dead with the heuristic. `getRequiredItems` already
had correct air semantics (untouched). StepCheckered's private `MapBlockAction`
(1873) was ALREADY dead before this — left for the in-flight refactor.
Build green (compileJava + full build, jar 05:53). Structural change (new class +
methods) → hotswap IMPOSSIBLE, restart-deploy required. NOT deployed/committed —
awaiting live verification per WIP rule.

### 2026-10-21 (k) — THM SPEEDMINE ABORT RECIPE ported + 05:38 deposit-drain crash fixed
**Breaking (user: "check THM speedmine — it can break so many blocks at once"):
decompiled THM-Addons-1.21.11 (CFR) — its Speedmine PACKET mode sends per block
START_DESTROY → STOP_DESTROY → ABORT_DESTROY → swing (repeated once for
non-instant blocks). The trailing ABORT resets the server's breaking-state
machine so the NEXT block's pair validates against clean state — that is how it
breaks many blocks per tick at range 5. Our fastBreak sent only START+STOP and
left stale breaking bookkeeping behind → later pairs rejected (the ~1-2/tick
acceptance ceiling + the 4-5-attempt blocks). Port: sendHybridBreak (the ONE
break-rules copy) now sends the ABORT after every fastBreak pair; the expiry
resend was deduped onto sendHybridBreak (it had a manual copy of the trio).
Also verified in the decompile: THM has no separate AutoMine module (the
"Auto Mine" in the user's list is another addon) and a Grim-specific variant
(ABORT→START→STOP→ABORT + a vanilla-ABORT-intercept offset trick) not needed on
Folia. NOTE: THM-abort reached the live master via hotswap but the slave had
already crashed (below) — it takes effect for the slave on next start from the
synced jar.
**Crash (05:38:40, slave, batch 5 break-all → deposit)**:
IndexOutOfBoundsException Index 68 of 46 at
RestockManager.drainDepositBacklog — depositBacklogSlots holds CHEST-screen
slot indices (double-chest layout); the chest screen closed under the drain and
the current handler was the player's own (46 slots). FIX: screen-validity guard
in BOTH drains (deposit + restock): handler missing / ≤46 slots / slot out of
range → drop backlog, close any screen, re-queue the deposit leg via the
existing next-chest hop (never blacklist a chest whose screen merely closed),
Walking + postRestockDelay. Jar rebuilt + deployed both instances (md5
single-unique), NOT launched per user.

### 2026-10-21 (j) — AXE EXCHANGE = PICKAXE RULE VERBATIM (user: "axe getting restocked with one healthy axe in inventory; same logic as pickaxe")
Diagnosis across ALL retained logs (word-boundary grep, every session back to
10-19): the CURRENT code never fetched an axe while any was carried — 3bb03b3
(01:45) over-corrected the pre-fix "exchange below min" rule to "fetch only when
ZERO axes carried". What the user saw was the pre-01:45 jar's fetch (fired
whenever no ≥threshold axe was momentarily carried; silent, no chat line — which
is why no log shows any axe fetch/deposit/exchange line ever). But 3bb03b3 left
the exchange DEAD: `bestAxeRemaining < min && axeCarried == 0` can never fire
with a worn-but-present axe, so a sub-20 axe rides forever, wood breaks fall
back to ~60-tick pickaxe swings, and (live evidence) batch 4's prep printed no
"Netherite Axe moved out of slot 7" line — the break-phase staging gate
(`life >= minDurability`) skipped the worn axe entirely. Three-part fix to the
PICKAXE's exact pattern:
1. **BatchRouter prep**: fetch+exchange when `axeCarried == 0 || bestAxeRemaining
   < minDurability` (pickaxe: `pickSlot < 0 || pickRemaining < min`), queue
   chest checkpoint when no material restock queued (the old fetch branch
   missed this — a no-material batch would queue an axe with no chest visit);
   else if >1 carried → deposit spares (the redundant `best >= min` guard
   dropped — the else-if already implies it, matching the pickaxe branch).
2. **RestockManager.handleRestockResponse**: AXE PARKING mirroring the pickaxe
   chest parking — at an axe-stocking chest (`isOpenChestAnAxeChest`, new), a
   pending axe take parks ALL carried axes (swap), otherwise keep-best and park
   spares; one deposit per sync, sequenced after the pickaxe parking drains.
   Deposit-only queue advance (middle==0 head) extended from pickaxe-tag to
   pickaxe-or-axe (an axe 0-stack entry would have stalled at the chest).
3. **BreakPlaceEngine staging**: best axe stages regardless of durability (was
   `life >= minDurability` — the pickaxe's findPickaxeSlot has no such gate; a
   worn sole axe must keep swinging until it breaks, per the user's tool rule).
Mid-run behavior unchanged: no mid-batch axe trips (wood falls back to pickaxe
speed; the pickaxe's mid-run fetch exists only because instamine REQUIRES it).
Deployed by the user (restart, 05:56). Live correction (06:10, user test: they
manually removed BOTH tools and the bot fetched only the pickaxe): the WOOD-NEED
gate (carried over from the 1dc267b axe fetch) suppressed the axe exchange on
batches with no axe-mineable blocks — batch 6's palette is terracotta/concrete/
granite/resin, so "Axe missing" never fired even with ZERO axes carried. The
pickaxe has no plan-content gate, so per "same logic as pickaxe" the gate is
REMOVED entirely: one-axe invariant enforced at EVERY prep (missing/below-min →
exchange, healthy → keep, >1 → deposit spares). Session facts for the record:
exactly 1 pickaxe take and 0 axe takes in the 05:56-06:02 window; the pickaxe
swap had nothing to park (the user had already removed the worn tools).
BatchRouter delta is body-only (wood scan deleted) — hotswap-compatible; full
restart-deploy also fine. Build green; NOT deployed — awaiting go-ahead.

### 2026-10-21 (i) — ONE-PASS CLEANUP: expiry-resend, best-tool vanilla, vanilla-aware hold (batch 4: blocks=6, 19s gate round trips)
Evidence: batch 4 break-all ended blocks=6 (all vanillaQ), gate drained 6→0 over
19s of walk-backs. RATE showed 20-36/s sent vs 1-16/s server-confirmed — the
server's ~1-2-same-tick-instamine acceptance ceiling rejects a slice of sends,
and ALL rejected blocks fell into a vanilla pipeline that could not finish
in-pass. Three compounding holes, three fixes (all in BreakPlaceEngine,
body-only, HOTSWAPPED):
1. **Expiry → instant fastBreak RESEND once**: a first-expiry instamine has NO
   server progress to reset (it was dropped/ceiling-rejected) — one clean
   resend (with ensureBestToolFor held) clears most stragglers with zero stops;
   only a second expiry routes to multi-tick vanilla. Value-update-on-live-key
   is iterator-safe.
2. **Vanilla path holds the BEST tool** (was forced slot-8 pickaxe): wood in
   vanilla at ~60 ticks/block was the 15s-per-straggler gate drain; with the
   axe it is ~4-8 ticks.
3. **Vanilla-aware approach hold**: a forward vanilla-queued block within 4.4
   now holds the bot AT ANY horizontal distance (the 1.3 cluster threshold
   never stopped for side-lane stragglers — the bot walked past, the
   behind-clear dropped the sticky mid-progress, and the block stood until the
   gate round-tripped). Hold condition: (nearestH<=1.3 && reachBusy) ||
   vanillaBusy; 150-tick watchdog still applies.

### 2026-10-21 (h) — PASS-END STRAGGLERS plugged: adaptive walking send clamp (batch 3 evidence: blocks=2 gate chase 18s)
Post-(g) evidence: deadlock gone (no watchdog lines, confirms 11/s), run healthy
(batch 4 printing 23-40/s) — but each break pass still ended with 2-3 stragglers
the gate walked back for (05:24:22 blocks=2 → 05:24:40 blocks=0). Leak: during
the APPROACH phase (walking, not held) sends went out to the full 4.4 clamp at
the trailing edge of reach — a send whose rejection/6-tick-echo landed after the
bot moved on ended up BEHIND the forward filter → gate work. FIX
(BreakPlaceEngine.sendDistinctBreaks): adaptive send clamp reading the same
forward-key state the movement governor drives — walking → 3.5 (3.5 + one
6-tick window at ~5 bps keeps every walking send ahead of the bot), held
stationary burst → full 4.4 (its pendings resolve in place). Body-only,
HOTSWAPPED live into both instances (1 class, 0 failed) + mods jars synced.

### 2026-10-21 (g) — STUCK-WHILE-BREAKING fixed: vanilla-sticky vs forward-filter deadlock (live at [-445,99,-299])
Symptom (screenshot): "Break approach watchdog: released a 151-tick hold near
[-445,99,-299]" repeating at the SAME position, vanilla-queue=5 with server=0,
break rate dead. Mechanism: the vanilla-sticky target (one multi-tick vanilla
block at a time) sat BEHIND the bot — solid and within 4.4, so the only sticky
clears (air || dist>4.4) never fired — but the new forward-only picker never
re-sends behind blocks → sticky frozen forever → every other vanilla target
skips on sticky!=target → the whole vanilla queue froze solid → those blocks
kept reachBusy true → approach hold → watchdog release → but the lane was
PHYSICALLY blocked by one of the frozen blocks → re-hold at the same spot.
Pre-rework the unfiltered picker ping-ponged back to behind stickies, which is
why this deadlock is new. FIX (BreakPlaceEngine.sendDistinctBreaks): clear the
sticky when it is behind the travel direction on batch passes (fullSweep/RESET
keeps old behavior — its ring-scanner has no direction filter); the picker
immediately re-homes sticky to a sendable vanilla target and the queue drains.
Body-only edit, HOTSWAPPED live into both running instances (33 redefined,
0 failed) + mods jars synced.

### 2026-10-21 (f) — LOOKAHEAD MADE QUANTITY-AWARE (user: "if we need two stacks of same block why is only one in there / whole hotbar not arranged / item needed sits in inventory")
Four root causes, four fixes:
- **Presence-blind staging**: `rollingHotbarLookahead` staged only materials
  ABSENT from the hotbar (`isItemInHotbar` = any count > 0), so a second stack
  of a >64-block material never pre-staged; the run-out then hit
  `tryPlacingBlock`'s on-demand path (forward=false + preSwapDelay = the
  visible wait). FIX: Belady with stack arithmetic — per material in the
  z-window [botZ-2, botZ+24], DEMAND = unplaced window blocks vs SUPPLY =
  blocks staged in the hotbar; missing = supply < demand (a 3-block remainder
  is not supply for a 70-block demand). Eviction guard: a resident is
  evict-eligible only when the rest of the hotbar still covers ITS OWN window
  demand (never evict needed supply to create supply).
- **Pass-start seed in RANDOM order**: beginBatch's pre-stage built its
  "coming" set from `currentBuildPlan.entrySet()` — a plain HashMap, hash
  order — the exact v3 bug left in the pass-start path. FIX: sort plan keys by
  z (walking order N->S) before collecting first-use materials.
- **Cascade cap 4→8 swaps/s**: 4/s was an arbitrary brake; each swap is one
  clickSlot packet. Corrections now converge within one cadence tick.
- **Supply bookkeeping**: the swap loop now tracks supply deltas (brought-in
  count, evicted count) so later evictions in the same cascade see post-swap
  state; swap log now prints "window need N / staged M".
- Single authority: the pass-start arrange stays ORDER-only; quantity top-up
  lives solely in the roller (fires 0.5 s into the pass, before the first
  block enters reach). On-demand stop-swap remains as the emergency path only.
- Jar deployed both instances (md5 verified single-unique), game not launched
  (managed by another agent per user).

### 2026-10-21 (e) — 02:27 CRASH fixed + FORWARD-ONLY BREAK CADENCE (user: "game crashed... also while breaking bot skips so many blocks")
**Crash** (`crash-2026-10-21_02.27.25`, slave): `IllegalArgumentException: Invalid
selected slot` — `beginBatch`'s print-phase tool move-out called
`performSwap(ts, freeSlot)` with a MAIN-INVENTORY index as the hotbar argument;
`performSwap` runs `setSelectedSlot(toSlot)` which only accepts hotbar 0–8.
Batches 1–2 survived only because the inventory was full (no free slot → skip).
Root design error: the phase-slot scheme assumed a hotbar slot 9 exists — it does
NOT (hotbar is 0–8; slot 9 is main inventory). Fixes (survived the pass-6 refactor
into BatchRouter/BreakPlaceEngine):
- `Utils.performSwap`: toSlot guard (0–8, else stderr + no-op — this class of bug
  can never crash the client again) + new `Utils.swapHotbarToInventory(hotbar, inv)`
  which parks a hotbar stack into main inventory via SWAP button semantics with NO
  setSelectedSlot.
- Tools now park in hotbar **7 (axe) + 8 (pickaxe)**: beginBatch frees both into
  staging for the print pass; break(2) entry stages pickaxe→8 + best axe→7
  (previously `performSwap(axeSlot, 9)` = the same latent crash).
- `movePickaxeToSlot8`: handler-coords conversion (`slot < 9 → 36+slot`) — a
  pickaxe found in hotbar 0–7 would have clicked CRAFTING/ARMOR handler slots.
- `ensureBestToolFor`: hotbar scan widened to 0–8 (staged axe in 7 is outside
  availableHotBarSlots by design; the staging-only scan fell back to the pickaxe
  on wood blocks).

**Breaking rework** (user rule: walk to ~1 block from the next cluster, break ALL
in reach, verify, then advance — no ping-pong, no skips), all in
`BreakPlaceEngine` + one line in `SlaveEngine`:
- `getNextBatchBreakPos`: FORWARD-ONLY filter — targets behind the travel
  direction (derived from the live route goal; break2 S→N, break-all N→S) are
  never picked (1-row grace for the standing row). Old any-direction nearest
  picker ping-ponged backward mid-pass.
- `breakApproachHold()`: movement gate wired as
  `setForwardPressed(!adaptivePaceHold() && !breakApproachHold())`. Within ~1.3
  blocks (horizontal) of the nearest forward still-standing target, forward is
  HELD until every forward target within the 4.4 server-reach clamp is confirmed
  air (echo-lag pendings keep it held; vanilla-queued blocks hold it while sticky
  mining). Between clusters the bot walks (and sends opportunistically toward
  targets — walking toward never outruns confirms). 150-tick watchdog releases a
  stuck hold for the sweep to recheck. RESET/fullSweep excluded (own feed).
- Approach-phase sends unchanged: burst rate still breaksPerTick (3/tick = 60/s
  cap), 6-tick confirm window, sticky-vanilla single-target, no speed-hack during
  break passes.
- NOT yet live-verified (game closed per user instruction; jar deployed to both
  instances, md5 d5a666a6415fe53f1aee2deceaefb719, NOT launched).

### 2026-10-21 (d) — Pass 6: MASTER + SLAVE DE-PILED (user: "refactor master and slave codes, its currently piled up")
SlaveEngine 2041 → **1114** and StepMaster 1169 → **952**; new focused collaborators
(commits 27157b8 / 9066a18 / 33f58f6):
- SlaveEngine keeps ONLY the tick pipeline, checkpoint-arrival dispatch + case handlers,
  batch gates (finishBatch, verify*), command queue, steering/sprint, transit speed.
  → `WorldScanner` (263): area/print-box block+item finders, censuses, plan-defect
  counting, pickup filter, walk-to-item, collect-skip blacklist.
  → `BatchRouter` (584): batch/priming/closing routes, split-build halves, the batch-prep
  trip (supply dump + tool/food rules), break-all staging, mid-run supply/deposit
  interruptions + resume rebuilds, corridor/lane legs.
  → `ResetLadder` (343): the scoped serpentine RESET FSM, feed + jam guard, arrival
  stall detector, abort entry; owns ladder state + segment tables.
- StepMaster keeps the run lifecycle (begin/batch/closing/reset), tickStepMaster
  scheduler, sweeps/waits, lattice + map-hold watchdogs, progress persistence.
  → `MapLifecycle` (206): map id resolution, slot finders, pin/hold/stow discipline,
  inv-to-hotbar swap, empty-map fill, supervisor hold service.
  → `CartographyLock` (97): the lock machine.
- Pattern: sub-objects constructed in the OWNER's constructor (field initializers would
  capture a null mod — caught in review), reset via their resetForActivation()
  (master's inline duplicate reset replaced by the sub-calls). @EventHandler count
  re-verified (4) after every move.
Current file map: hub 1900, SlaveEngine 1114, StepMaster 952, RestockManager 827,
BatchRouter 584, BreakPlaceEngine 482, ResetLadder 343, WorldScanner 263, MapLifecycle
206, StepRenderer 159, CartographyLock 97, State/StepMasterStage 42.
Deploy: restart; watch one full batch cycle live before unattended runs.

### 2026-10-21 (c) — Pass 5: THE CLASS SPLIT (user: "Main Step Checkered class is so big... create subclass, make it lightweight")
`StepCheckered` 6256 → **1897 lines**, now a lightweight hub. Five commits (2bfe36b → 13c0769),
one subsystem each, full build green after each:

| Class | Lines | Owns |
|---|---|---|
| StepCheckered (hub) | 1897 | settings, registration/config/NBT load, lifecycle (onActivate/onDeactivate), the 4 @EventHandler dispatchers (thin), control-endpoint + SlaveSystem contract surface, HUD widget |
| SlaveEngine | 2041 | slave FSM: tick() pipeline, checkpoint-arrival dispatch + all case handlers, batch/priming/closing routes, RESET ladder, scanners/censuses, verify gates, transit speed, command queue |
| StepMaster | 1169 | master FSM: sweeps/waits, map fetch/fill/pin/stow, cartography lock, lattice + map-hold watchdogs, batch-progress persistence |
| RestockManager | 827 | chests/refill/deposit backlogs + inventory-packet responses, tool/food/pickaxe management, hotbar staging, capacity queries |
| BreakPlaceEngine | 482 | target pickers, distinct multi-place/break, hybrid fastBreak+vanilla sender + confirmations, pace governor, rolling hotbar lookahead |
| StepRenderer | 159 | render pass + plan cache |
| State / StepMasterStage | 42 | top-level enums |

Design: same-package collaborators holding `final StepCheckered mod`; SHARED run state
(state, checkpoints, currentBatch/currentBuildPlan, breakStage, nextBlockPos,
fullSweepActive, corners, chests, slots) stays on the module so both bots' paths read
one truth; each subsystem owns its private state and resets via `resetForActivation()`
(called from onActivate). Wire protocol, BotControlEndpoint reflection contract, and
SlaveSystem signatures unchanged (in-tree compile proves them).
CAUGHT IN REVIEW: the onTick body was initially lost to a case-sensitive extraction
("tick(" vs "onTick(") — recovered from git and restored as module delegate + SlaveEngine.tick.
Deploy: restart; first live run needs a full watched batch cycle (this was the biggest
code-movement of the series — compile-verified, not yet live-verified).

### 2026-10-21 (b) — Pass 4: LEGACY TWO-LAYER ENGINE REMOVED (user: "Stepcheckered class has so much duplicacy... dead code")
`StepCheckered` 7108 → **6256 lines**; total src 9337 → 8178. Reachability proof in the
commit message: the legacy engine computed over `lower/upperMapLayer` arrays that were
NEVER populated in step-checker mode, and its wire commands were sent only by the legacy
master + HUD legacy buttons (both gone) — every legacy path was an unreachable no-op,
and "Pause all" parked a slave in `AwaitSlaveContinue` forever (`oldState` written,
never read).
- Removed from the module: the whole legacy engine (buildPath/minePath/fillMapPath/
  suppressPath/pickers/refillMining/finishMineLine/setMiningFinished/serviceMineEndJobs/
  delegateMiningCommands/advanceSuppressionFlow/checkAwaitMinedSuppression/
  tryLoadNextNbtFile/mineLine/slaveFinished/pause/start/skipBuilding/addError/setLayer),
  14 dead checkpoint cases, 2 dead inventory-response handlers, 25 dead fields,
  5 dead settings (durability-buffer, master-help-min-lines, collect-mined-blocks,
  collect-both-directions, map-load-timeout), 6 dead State constants.
- `forEachMapBlock` now walks coordinates directly (layer arrays deleted);
  `getBestChest` layer preference folded to its always-live LOWER-chest behavior;
  ALLOW_PLACE_ACTIONS/no-sprint pruned of unqueueable action names.
- Shared infra: `SlaveSystem` = live protocol only (heartbeat/register/step; module
  typed `StepCheckered`); `SlaveTableController` = status list only (legacy
  start/pause/skip/remove HUD buttons removed with the dead commands — the module's own
  START ALL / RESET RUN buttons remain THE controls); `MapPrinter` interface +
  `MapCompletionState` DELETED; `LocalTCPTransport.sendToAllSlaves` removed.
- Post-removal sweeps: ZERO zero-reference private methods, ZERO write-only fields,
  ZERO stragglers tree-wide. Both bots run this jar → legacy wire messages disappear
  from both sides together. **Restart deploy required; watch one full batch cycle.**

### 2026-10-21 (a) — WHOLE-PROJECT REFACTOR (user goal: code quality, maintainability, no duplication, reusable code)
Three passes, each compile-verified and committed separately; behavior preserved by
construction AND by token-level equivalence proofs (multiset diff of every moved token).

**Pass 1 (ac9d787) — dead code + duplication dedup.**
Dead (zero-reference, grep-proven): fields `minedLines`/`collectingStragglers`/
`fullSweepBudget`/`fullSweepCenterChecked` (write-only); State constants
`MasterWaitBuild`/`MasterDwell`/`AwaitMasterAllCleared`; `AwaitBlockBreak` was read but
NEVER assigned (unreachable branches removed); methods `printBoxEmptyInRange`/
`hasFoodInInventory`/`carriesAxe`; dead v3-lookahead locals `bringIn`/`bringInZ`; dead
`bestAxe` local; 7 dead Utils methods (`hasTool`, `iterateBlocks`, `generateMapArray`,
`getInvalidPlacements`, `readMapColorId`, `setBackwardPressed`, `setJumpPressed`).
Dedup — ONE shared copy each: `planDefects()` (was 3 copy-pasted scans),
`isPickupable()` (4 item scanners), `batchWindowWestX()` (5 hand-rolled expressions),
`isItemInHotbar()` (2), `queueDepositChestAtHead()` (3), `refillFromCurrentPlan()` (3),
`sendPlaceStatus()` 7/4-field builders (4), merged double axe scan in prep.
Cosmetics: all redundant `java.util.*`/`java.io.*` FQNs → imports.

**Pass 2 (3b13db0) — onTick decomposition.**
onTick 1280 → 114 lines of linear orchestration; ~30 named pipeline methods in the SAME
order with the SAME early-return contract (see the commit for the token-diff proof:
only guard negations (De Morgan), return-mapping (`return;`→`return true;`,
switch-`break;`→`return false;`), two identical-case merges, and new signatures/call
glue differ). tickStepMaster: lattice watchdog + map-hold guard extracted.
handleInventoryPacket: the 90-line AwaitRestockResponse case → handleRestockResponse.
NOTE: this commit also carries an external mid-session live fix that landed on disk
during the refactor: break(2) axe arming is best-first into hotbar slot 7 (old 'slot 9'
addressed the main inventory and crashed performSwap).

**Pass 3 (b4ef21a) — reusable subsystems extracted.**
`utils/StepFiles.java` (progress/reset persistence; byte-identical file formats,
read order preserved so a readable mapId survives a corrupt batch field) and
`utils/StepCheckerLayers.java` (the PROVEN converter + all plan assembly incl. the
batch-0 split halves; conversion token-identical to the original). Module keeps thin
delegates + one `stepLayers` field (`StepCheckerLayers.empty()` placeholder until the
first NBT load).

**Sizes:** StepCheckered 7205 → 7108 lines BUT with onTick at 114 (was 1280), 2 new
focused utils (~460 lines moved out), ~340 lines of dead code deleted, every remaining
duplication documented. `swapIntoHotbar` (legacy heuristic swap) is still LIVE code —
it serves on-demand swaps when the rolling lookahead misses; do not delete.
Known remaining intentional non-unifications (documented, not drift): the chest-search
trio (getBestChest / getBestDepositChest / getNextDepositChest — genuinely different
semantics: layer preference + checked-set cycling vs carried-item vs exclusion +
registration side effect) and the legacy two-layer engine (buildPath/suppressPath/
fillMapPath/...) which stays reachable through the SlaveSystem protocol (mine:/start/
skip) and the shared State machine — removing it is a behavior decision, not a refactor.

**Deploy note:** all three passes are STRUCTURAL (new methods/classes) — hotswap
cannot load them; the next deploy is restart-deploy (close → replace jar → relaunch).
First live run after deploy should be watched for one full batch cycle as smoke test.

### 2026-10-20 (t) — FINAL tool-exchange design (user rule): ONE setting, prep-only, strictly-below
User rejected the (r)/(s) two-setting + destruction-guard design: "no mid-run emergency
restock for pickaxe or any tool; use only min-durability; not a hard cap at the touch —
if it's gone below this, the slave exchanges it whenever it does a restock before
starting a batch."
- **`pickaxe-change-durability` DELETED** (never deployed, no stale config). The single
  setting is **`min-durability`** (default 20, slider widened 0-2031 so ~800 is
  reachable): tools are exchanged at the **batch-prep restock trip only**, when the best
  pickaxe has gone STRICTLY below it (old one deposited into its chest, fresh taken,
  spares returned — the (q) swap-not-add deposit is unchanged).
- **NO mid-run durability actions**: the mining branch and the RESET ladder check no
  longer look at durability at all — a worn pickaxe keeps swinging until it breaks. The
  only remaining mid-run tool trip fires when a pickaxe is genuinely GONE (broke
  mid-batch): arm any carried one, else the fetch trip (unavoidable — no tool = no
  progress). `hasGoodPickaxe` (≥-floor test) replaced by `hasAnyPickaxe`.
- **Tradeoff (told to user, their call on the number)**: at 20, a fresh diamond pickaxe
  (1561) survives ~2 measured batches (~700-800 breaks each), then breaks mid-batch and
  triggers one gone-tool fetch trip per pickaxe. min-durability ≥ ~800 (one batch's
  worst-case breaking) makes mid-batch breakage — and thus that trip — impossible,
  because the prep swap always fires first.
- Saved config verified earlier: min-durability was never user-modified on either bot
  (Meteor persists only changed settings), so the default 20 loads automatically.

### 2026-10-20 (s) — min-durability IS the destruction guard; default 20 (user rule: "I don't want my tool to get destroyed")
User clarified the two settings' jobs: `pickaxe-change-durability` (default 20) decides
WHEN the planned batch-prep exchange happens; **`min-durability` is the hard floor — a
tool is exchanged the moment its remaining durability reaches this value, MID-BATCH
included, so it is never destroyed.** Its default is raised 5 → 20 (description
rewritten to say exactly this).
- **Boundary made consistent everywhere**: "worn" now means `remaining <= min-durability`
  at ALL exchange gates (mining branch, RESET ladder check, legacy tool scan, prep fetch
  bar) and "good" means `> min-durability` in `hasGoodPickaxe`. Reason: with the old
  strict `<`, a tool sitting at EXACTLY the floor passed the check and could be swung —
  at breaks-per-tick 20 that is 20 durability in one tick = destruction. At the floor it
  is now always exchanged first; with any breaks-per-tick ≤ 20 the tool can never reach
  0 (from 21: one tick max 20 breaks → 1 → exchanged).
- **Effective behavior at both defaults = 20**: prep swaps the pickaxe when it drops to
  20 (planned, at the chest); if it reaches 20 mid-batch instead, the emergency swap
  run fires (north corridor → deposit the old one at the pickaxe chest → take fresh →
  resume) — the tool is saved with ~17-20 remaining, never broken. Roughly one
  emergency swap run per pickaxe (~every 2 batches with fresh diamond picks).
- **Saved config verified**: Meteor only persists CHANGED settings — `min-durability`
  was never modified on either bot (checked both instances' `modules.nbt`; probe
  settings like bot-role ARE present, min-durability is not), so the new default 20
  loads automatically after deploy. No manual UI change needed.

### 2026-10-20 (r) — pickaxe change bar is now a SETTING (pickaxe-change-durability)
User: "make the minimum durability of pickaxe for change configurable instead of hard
coding to 10 in code." Note: no 10 was ever hardcoded — the deposit rule returns ALL
spares structurally (see (q)); the hardcoded number governing WHEN a pickaxe is changed
was the prep swap bar `PREP_PICKAXE_BUFFER = 800`. That bar is now the
**`pickaxe-change-durability`** setting (Mining group, slider 0-2031): below it the
batch-prep trip deposits the old pickaxe into its chest and takes a fresh one. The
constant is deleted (single source of truth); the swap log line prints the configured
bar. **Default 20 (user-ordered after being shown the tradeoff)** — pickaxes run to
near-death before swapping (~half the pickaxe consumption of the 800 bar); consequence:
a fresh diamond pickaxe survives ~2 batches, the second death happens MID-batch and
triggers one emergency swap run (~30s) — the emergency path stays as the safety net.
Setting it ≥800 restores "a pickaxe never dies mid-batch" (emergency restock stays for
emergencies only). NOT a duplicate of the two existing durability settings:
`min-durability` (default 5) is the mid-batch usability floor for ALL tools (mining
branch, hasGoodPickaxe, legacy tool selection) — reusing it as the change bar would
demand a new pickaxe the moment the active one dips below 20 MID-batch (constant
emergency runs); `durability-buffer` is a percentage on the legacy mining-refill
prediction. Setting lives in the EXISTING Mining group (no new group/HUD).

### 2026-10-20 (q) — PICKAXE LIFECYCLE FIXED: swap-not-add, one-pickaxe invariant
User: "bot should deposit the pickaxe if durability is below 10, in the restock phase
only before starting it, and not cause an unnecessary emergency refill; it is getting
so many pickaxes from the chest and not putting back the less durable one — 5 pickaxes
in inventory; only one pickaxe in inventory."
- **Root cause (why pickaxes piled up)**: the batch-prep predictive fetch
  (`queueStepBuildPreparation`, buffer `PREP_PICKAXE_BUFFER=800`) measured the
  FIRST-FOUND pickaxe, so a stale pickaxe in a low slot kept re-triggering a fetch
  every batch even when a fresh one was carried; and the return-deposit at the
  chest (`AwaitRestockResponse`) only parked pickaxes below `min-durability`
  (default 5) — replaced pickaxes at a few hundred durability NEVER went back.
  Net +1 pickaxe per batch → 5 carried. First-found arming also burned stale ones
  mid-batch until every carried pickaxe was worn, THEN tripped the emergency
  supply run (`pauseForRestockTrip`) — the "unnecessary emergency refill".
- **`findPickaxeSlot` is now best-first** (highest remaining durability): the prep
  check measures the best pickaxe and slot 8 always arms the freshest one.
- **Swap-not-add deposit** (`AwaitRestockResponse`, only when the OPEN chest is a
  registered pickaxe chest — `isOpenChestAPickaxeChest()`, never a material chest):
  a pending pickaxe take parks ALL carried pickaxes (the fetch only queues when the
  best is below the buffer, so all of them are), then the fresh one is taken — net
  exactly one. With no take pending it keeps exactly ONE pickaxe (the best) and
  returns every spare. A sub-10-durability pickaxe is never the kept one: it is
  either a spare, or the sole pickaxe — in which case the 800-buffer fetch has
  already queued its replacement.
- **Pile drain at prep**: `queueStepBuildPreparation` queues a deposit-only
  pickaxe-chest visit (restockList entry with 0 stacks = take nothing, park the
  spares) whenever more than one pickaxe is carried and no fetch is needed — the
  current 5-pickaxe state cleans itself on the first prep after deploy.
- **Deposit-only entries advance**: the take loop's middle==0 no-op previously had
  no exit for a 0-stack entry; it now calls `endRestocking()` when no take backlog
  is in flight (a pending TAKE also passes through middle==0 while its backlog
  drains — the tick's drain still ends that entry itself).
- Emergency paths unchanged: mid-batch trips remain the safety net for a genuinely
  dead/missing pickaxe; the invariant + best-first arming is what stops them from
  firing on a bot that carries fresh spares it never arms.

### 2026-10-20 (p) — RESET scoped to the PRINTED batches (progress-driven, no whole-map pass)
User: "we now know what batch was printed — in RESET, instead of walking and
rendering the whole map to find what to clear, only do it for the printed batches."
- **Scope sources**: max(live in-memory batch, progress-file batch) + 1 safety col
  east, clamped 0-31; batches print west→east so the scope always starts at col 0.
  Logged at RESET entry: "RESET scoped to cols 0-N (live batch X, progress batch Y)".
- **Ladder** already covered the scope; it now starts at col 0 explicitly.
- **Verify walk scoped too**: stations sit on the SCOPED columns' centre line (not
  the fixed whole-box x=66), the content scan uses the new
  `printBoxContentRange(x0, x1)` window (printed cols + 1 east, items with the ±4
  bounce margin), and the item census is scoped to the same window. After a re-scope
  the stations re-centre on the found range. No whole-map walk or scan remains in
  RESET. Boundary finds still re-ladder via the existing found[] path.

### 2026-10-20 (o) — DURABLE PROGRESS: crash/restart/disable resume with the SAME map
User: "whenever bot crashes, restarts, pauses or disables, progress must be saved and
only reset by RESET ALL." The progress file (`_configs/step_checkered_progress.json`)
existed but had two holes: the saved mapId was never read back (a restart re-pinned
WHATEVER filled map it found — wrong-map corruption risk with old maps in inventory),
and progress was only saved after a batch COMPLETED. Now:
- **saveStepProgress(k) on EVERY batch start** — "batch k in progress" is the file's
  semantic; a crash at any point resumes by re-running exactly the in-progress batch
  (idempotent: the build's world-verify/repair re-places, the walks re-record the
  same standing content). The old post-done save was removed (beginStepBatch(k+1)
  writes the same value).
- **Resume re-pins the EXACT saved map**: `loadStepProgress` parses the stored mapId;
  START ALL with a progress file finds that precise map in the inventory
  (`stepFindMapIdSlot`) and pins it. If the saved map is missing from the inventory
  the recorded pixels are unrecoverable — loud error + fresh start (progress cleared)
  instead of silently recording into the wrong map.
- Module disable/pause already kept the file (only `resetStepRun`/RESET deletes it);
  onDeactivate stages ABORTED, which START ALL accepts for resume.
- Slave restart mid-batch: the master's wait heartbeat/timeout aborts cleanly; the
  next START ALL resumes at the in-progress batch.

### 2026-10-20 (n) — CAUGHT: master waits queued at the BOX EDGE (stepNearestPlatform z bug)
User: "master making a checkpoint around x 98, z -320 ±2 every batch, just before the
slave starts a new batch." The new always-on master checkpoint log caught it verbatim:
`Master checkpoint: stepBatchSafe at (-316, 99, -319)` every requeue second = relative
(131.5, 0.5) — the north edge of the print box, NOT a platform. Root cause: a
one-liner in `stepNearestPlatform` — it passed the z array INDEX (0..4) into
`stepCheckpoint` instead of the VALUE (14/40/64/88/112), so every "wait in place"
landed at z_rel ≈ 0.5. Consequences: the master stood inside the box's north edge
during every wait (map stowed, so no recording harm) and hiked back to the sweep
stations afterward — the recurring dead walk. Fixed to `STEP_CHECKPOINT_Z[zIdx]`.
With the fix, every wait snaps to a real platform: post-W2 the master waits on the
next batch's W1 lane north station (zero walk to W1), post-W1 it waits at the south
station where W2 starts (zero walk). Also de-duplicated the always-on checkpoint log
(it printed once per requeue second).

### 2026-10-20 (m) — FIXED master cycle: W1 always N→S, W2 always S→N (auto-direction removed)
User: "MASTER STILL WALKS S→N BEFORE SLAVE SENDS BREAK2DONE." The master log PROVED
the (k) gate held (batch 4: break(2) requested → 41s of break2done heartbeats → W2
started only on break2done). What the user saw was W1 itself running S→N: the
auto-direction sweep picks its direction from the master's position, so W1 sometimes
ran S→N and looked exactly like an early W2. All master sweeps are now pinned:
**W1 always N→S** (0→4), **W2 always S→N** (4→0), closing N→S — the doc's fixed walk
pair. Chains with zero dead legs: W1 ends south → wait there through break(2) → W2
S→N from that exact platform → ends north → next batch's W1 starts north. All six
sweep-start sites pinned (tick consumer + arrival fallbacks); no auto-direction
remains on the master. Matches the slave's fixed cycle (l).

### 2026-10-20 (l) — FIXED CYCLE: all slave passes pinned to fixed directions (no mirroring)
User: "both master and slave are doing dead walks N->S or S->N at random times, not
even in order." Two causes: (1) the pre-(j) jar's stale south batchExit cascade
(fixed earlier); (2) a REAL remaining flaw — every slave route mirrored its direction
based on where the bot happened to stand (`slaveStartsFromSouth()`), so any interrupt
or odd position flipped the parity and manufactured dead NS walks + reversed phase
order. All passes are now pinned to the FIXED CYCLE the user prescribed:
**print N→S → break(2) S→N → break-all N→S (forced) → collection S→N → exit in
place at north → next batch** — identical every batch, ending where the next begins.
Repositioning happens once, at a phase boundary, via the north corridor. Fixed at:
beginBatch, beginPriming, the tops route, resumeBatchRoute (all stages), the
verifyBreak2 retry, the print-repair pass; `slaveStartsFromSouth()` deleted.

### 2026-10-20 (k) — FINAL walk order: W1 → break(2) COMPLETE → W2 → break-all (user rule)
User (second, clarifying directive): "master will do the S->N walk, but NOT before
slave completes break(2)." Supersedes (i)'s both-walks-first order and the original
concurrent design. Sequence per batch: build → W1 (records) → break(2) requested at
W1's completion; master waits in place (map stowed, heartbeat) → slave completes
break(2) (break2done) → W2 (the S->N suppression walk) starts — never earlier →
W2 completes → break-all immediately (break2done already arrived). Safety: W2 rides
the EAST lane 2k+129, 127.5+ blocks from the west tops (beyond dominant record
range 127.2), so it cannot re-record over the broken west columns — the doc's §4
same-lane re-record hazard does not apply to the east lane. `stepBreak2Point` is now
a pure position holder (the tick-level consumer sends break2 and starts W2; an
arrival-driven path would have skipped W2).

### 2026-10-20 (j) — stale south-end batchExit: exit gate now runs where collection ended
User: "after picking dropped blocks the slave was correctly at N — why did it walk back
to the S checkpoint instead of starting the next batch?" `startBreakAll` queues
`batchExit` beside `breakAllDone` at the SOUTH end; the collection run ends at the
NORTH end, so the bot walked a full lane back south just to reach a gate that is
position-independent. Fix: the collection run's completion (and unreachable-drops
handoff) replaces the queued batchExit with one at the bot's current position — the
bot exits in place at the north end, exactly where the next batch's north-corridor
supply run starts.

### 2026-10-20 (i) — break(2) moved AFTER both walks (doc §4 order restored, user callout)
User: "master is doing SN run even before slave completes break(2)." The code sent
break(2) at W1's completion and ran W2 CONCURRENT with the slave's break(2) — a
module-era optimization that contradicts the doc's load-bearing rule (§4: "break(2)
must come AFTER both walks — a walk over broken dominant columns re-records borrow
values / terrain over them") and was never among the 0-mismatch-verified orderings.
New order per batch: build -> W1 -> W2 (immediately, back-to-back) -> break(2)
requested at W2's completion (WAIT_BREAK_2, tick-level send with the
stepBreak2Point arrival as fallback) -> break2done -> breakall. Batch-0 split mode
keeps its direct breakall (no break(2) exists there). Cost: W2 is no longer overlapped
with break(2) (~40s serial per batch).

### 2026-10-20 (h) — break(4) collection redesigned: single south→north run (user spec)
User spec: "slave should not collect any block till break(4) phase is complete; the
bot is at the SOUTH checkpoint when break(4) ends; after break(4) it collects every
block in one optimized run south→north — no multiple NS passes."
- **Break pass forced north → south ALWAYS** (was direction-mirrored): breakAllBegin
  at the north end, breakAllDone at the south end, corridor-staged from anywhere.
- **No collection before blocks=0**: the gate chases straggler blocks only; item
  collection starts exclusively after the window is server-confirmed air. In-flight
  drops are dwelt for.
- **ZIGZAG + PER-ITEM CHASE DELETED** (the NSNS passes): replaced by `collectRun` — a
  single monotone collector. Each arrival re-targets the nearest ready drop AT/AHEAD
  of the bot (z never moves more than 1.5 blocks south), so the run sweeps the window
  south → north with east-west side-steps only, one run, ending at the north end
  (where the next batch's supply run starts). A drop re-picked 10x without
  disappearing is blacklisted for the batch (unreachable) and the run hands off to
  the exit gate, whose census names what remains. collectRun is exempt from the
  generic anti-stuck force-skip; `collectSkips` cleared at batch starts.

### 2026-10-20 (g) — collection zigzag: once per gate, 2 legs, outliers to per-item chase
Live 03:38 run: the paced break-all arrived blocks=0 (pace fix works), zigzag 1
collected 137→34 drops — then the gate queued a SECOND zigzag for the 34, which sat
there while the user watched drops remain on the ground. Root cause: the margin-4
census SEES drops 3-4 blocks outside the window, but the zigzag lanes only REACH
~1 block past it — a repeat zigzag can never collect them (unreachable-outlier loop).
Fixes: (1) the zigzag runs at most ONCE per gate sequence (`batchZigzagDone`, reset
when blocks get chased or at batch start) — remaining drops, regardless of count, go
to the per-item chase which walks to each drop; (2) the zigzag itself was walking one
lane twice (4 NS legs with a redundant same-lane return) — now a proper 2-leg zigzag
(near end → far on wx+1, hop east, back on wx+3), roughly half the time.

### 2026-10-20 (f) — drop-scan margin widened to 4 (worst-case bounce, user-measured)
User: "sometimes in even worst case dropped blocks can drop to 3-4 blocks far." The
margin-2 item windows couldn't even SEE such drops (gates declared CLEAN with drops
lying outside), and the zigzag lanes reach only ~1 block past the window. Changes:
- `RESET_ITEM_MARGIN` 2 -> 4 (one constant: reset segment scans, verify walk,
  print-box content range, all keyed on it).
- Batch-window census + finishBatch's ground-items counter + the break-all gate's
  item chase all widened to x = west-4 .. west+7 (matching each other exactly).
- Collection self-corrects: the zigzag sweeps the bulk (2 lanes cover the window);
  far-bounce outliers are now VISIBLE in the census, so they fall to the per-item
  chase (<=8 threshold) and CLEAN only fires when the margin-4 census is empty.

### 2026-10-20 (e) — break-all missed blocks + didn't collect: pass pace + gate priority fixed
Live 03:12 run (batch 1, standard flow): the break-all pass covered the lane in 23s at
sprint speed and arrived with **86 of ~490 blocks standing** and 199 drops; the gate
then launched the item zigzag (items outranked standing blocks) whose first leg walked
27s straight INTO the standing west-pair blocks before the user killed it. Two fixes:

1. **ADAPTIVE BREAK PACE** (the structural one — this is what RESET always had and
   break-all lacked): while walking a break(2)/break-all pass (Mining + breakStage!=0
   + plan present), the bot holds forward whenever a plan block in range still needs
   breaking — the pass clears rows as it walks instead of sprinting past them
   (~490 blocks at the ~10-20/s hybrid rate => the pass now takes 1-2 min instead of
   23s, and arrives ~clean). Watchdog: a 10s hold releases and suppresses re-holding
   for 10s (a stubborn block can neither stall the pass nor spam). RESET is excluded
   (plan null there — the serpentine feed + segment gates already pace it).
2. **GATE PRIORITY — blocks first, unconditionally**: `straggler != null` now always
   takes the block chase; the zigzag collection pass and per-item chases only run on
   a fully-air window (their stated precondition, now enforced).
3. **DEDUPLICATION** (user callout — "same code or duplication?"): the hybrid
   break-send rules existed in 3 drifted copies and the two pace-holds were parallel
   copies of one pattern. Now: `sendHybridBreak(target)` is the ONE break sender
   (used by the Mining loop, the second target loop, the resetPass arrival handler,
   and the legacy mining tail — zero raw fastBreak sends outside it), and
   `adaptivePaceHold()` is the ONE pace governor (print mode + break mode, one call
   site). Behavior identical; compile-verified.

### 2026-10-20 (d) — start-batch setting: batch 0 skipped for pipeline testing
User directive: "skip building for batch0, will start testing from batch1, for now."
New Advanced setting `start-batch` (0-62, **default 1**): a fresh run begins at that
batch - earlier batches are never built or walked. The resume path takes
max(savedProgress, start-batch) so a stale progress file can never drag the run back.
No `step_checkered_progress.json` exists yet, so the first run starts cleanly at 1.
Set it back to 0 for full runs. Build green; UNCOMMITTED.

### 2026-10-20 (c) — batch-0 pre-build DISABLED by default (user directive): standard flow for batch 0
"BOT DID AGAIN PREBUILD THING FOR BATCH0, WHICH I DONT WANT, DISABLE/SKIP IT" - the
floor-half/priming pre-build phase is now behind a setting, **OFF by default**:

- New setting `batch0-split-build` (Advanced group). OFF (default): the master goes
  straight from map-fill to `beginStepBatch(0)` - batch 0 runs the STANDARD flow
  exactly like batches 1-62: `build:0` (full 681) -> W1 (lane 127) -> break(2) ->
  W2 (lane 129) -> break-all. No pre-build phase, no prime command, no buildhalf
  halves. ON: the split flow (floor -> W1@125 -> tops -> W2@127, no break(2)).
- Gated on the setting: beginStepPrimingRun's split entry, beginStepBatch(0)'s
  buildhalf resume path, the BATCH_WALK_A tops-request branch, both lane picks
  (W1@125 is split-only), and the batch0Split break-all shortcuts in BATCH_WALK_B
  and the WAIT_BREAK_ALL consumer.
- KNOWN PIXEL RISK (documented, user-accepted): the split existed because pair 0's
  floor cannot record cleanly from a full build (R_dom - R_rec = 1.74 > 1 row
  interleave - the impossibility theorem; the pre-split era showed 53 rec gaps on
  batch 0). With the split OFF, pair 0's floor may again record corrupted - flip
  `batch0-split-build` back on to restore the proven flow.

### 2026-10-20 (b) — BATCH 0 UNIFIED with the standard batch flow + bulk drop collection
Rollback anchor: `fdbcb86` (everything through the stall fixes). User directive: batch 0
must flow like every other batch. Post-(d) the standard stages already expressed the
merged sequence, so the priming special-casing is now DELETED:

- **Master**: `beginStepPrimingRun` now uses the standard `WAIT_BATCH_BUILD` with
  `stepMasterBatch = 0` and the standard wait/heartbeat machinery — the only difference
  from `build:k` is the command ("prime" = floor half). The priming walk IS W1 through
  the standard `BATCH_WALK_A` lane pick (batch 0 -> lane 125), and W1's completion
  requests the tops half via the existing split branch. REMOVED: `WAIT_PRIMING_BUILD`
  and `PRIMING_WALK` stages, `stepPrimingSafe` handler, `stepSlaveReadyPriming`
  field/signal, `ready:prime` parsing, the PRIMING_WALK sweep-complete case.
- **Slave**: `beginPriming` now sets `currentBatch = 0` and runs the STANDARD route
  (`batchBegin -> printDone -> awaitBreak2`, sends `ready:0`, direction-mirrored).
  REMOVED: `stepPrimingReady`/`stepPrimingWait` cases, the ready:prime send, the
  `currentBatch == -1` special branch in `resumeBatchRoute`.
- **Remaining batch-0 deltas (physics-mandated, expressed as data not separate paths)**:
  (1) the build is split around W1 (floor -> W1 -> tops - pair 0's floor must record
  with no tops above; oracle-verified split), (2) no break(2) (W2 is the tops' first
  and only record), (3) W1 rides lane 125 instead of 127. Everything else — commands
  consumed, stages, signals, waits, routes, gates — is the same code as batches 1-62.
- **BULK DROP COLLECTION PASS (the 94-item stall)**: live run 2026-10-20 01:52 showed
  the break-all gate facing 94 ready drops (west-pair drops land 1.5-3.5 blocks off the
  breaking lane - outside vanilla pickup radius; per-item chasing costs minutes per
  batch). When the gate sees >8 ready drops (and inventory has room) it now queues one
  zigzag walk over the window's two collection lanes (x = west+1 and west+3 - the
  window is confirmed air first), sweeping the drops by proximity, then re-verifies.
  Straggler chase remains for <=8 items; blocks are always chased first.

### 2026-10-20 — live stall diagnosed + fixed: print-pass deadlock at pass start
User ran START ALL with the (d) build; the slave froze at the start of the priming
phase twice (01:01 run: 2 PLACE lines at the staging then minutes of silence; 01:36
run: froze on the first refill-chest walk). THREE defects found and fixed from the
logs:

1. **RANGE-METRIC MISMATCH deadlock (the stall)**: the walk-act gate measured range
   from the FEET (PlayerUtils.distanceTo, full 3D) while the target picker arms with
   EYE distance. An upper-layer block (y+2.5 over the feet) at picker eye-range 4.4
   is ~5.0 feet-range -> the placement branch refused to attempt the very target the
   new adaptive pace was holding the bot on -> attempts never incremented -> the hold
   never released -> total silent stall. Fix: the gate now uses eye distance (the
   picker's own metric). The mismatch predates (d) — it silently skipped placements
   before; only the pace-hold turned it into a freeze.
2. **Wait heartbeat counted pause-gate ticks**: the wait bookkeeping sat behind the
   20-tick requeue pause (~1 reachable tick in 21), stretching the "30s" heartbeat to
   ~10 real minutes and the timeout to hours — which is why the master showed NO
   heartbeat during the stall. Fix: bookkeeping moved to the top of tickStepMaster,
   before every pause gate; the consuming switch stays where it is.

3. **Pace-hold fired on non-placement legs (the 01:36 stall)**: the hold only checked
   "placeable target in range" — but "refill"/"centerLane" legs are NOT in
   ALLOW_PLACE_ACTIONS, so the placement branch never runs on them, attempts never
   increment, and the hold never released: the bot froze at the staging with the
   first refill chest queued. Fix: the hold now mirrors the placement branch's FULL
   preconditions (same allowlist, same picker, material carried via
   `isMaterialCarried`, attempt cap) PLUS a 100-tick watchdog that force-releases
   any hold and logs it — no future gap in these conditions can freeze the bot again.

Also hardened: the adaptive pace hold now requires the target's material to be
carried — a missing-material target never holds, so the placement attempt routes it
straight into the emergency mid-batch supply run (the 01:01 run also showed an
under-supplied "Not all necessary stacks restocked" refill immediately before the
stall). Build green; UNCOMMITTED.

### 2026-10-19 (d) — FLOW OPTIMIZATION: merged batch 0, zero repositioning legs, tick-level waits
User-directed ("optimize the flow, reduce unwanted walks; slave fixes in real time;
carry batch-0 supply once, emergency refill only; master maps from any direction").
Build green; UNCOMMITTED pending approval.

- **MERGED BATCH 0** (~1.5 min/run saved): the priming build IS the floor half
  (identical plans) and the priming walk IS W1 (identical world state at record
  time). PRIMING_WALK completion now requests `buildhalf:0:t` directly and waits in
  place — the floor-half request, its supply trips, its verification pass, and the
  W1 re-sweep are all gone. The old split entry (buildhalf:0:f) remains as a
  direct-resume safety path.
- **ONE-TRIP BATCH-0 SUPPLY**: the priming supply run now computes requirements from
  the MERGED plan (priming recessives + dominant pair 0 tops) — the tops pass needs
  no refill walk; mid-print shortages fall back to the existing emergency restock.
- **ZERO REPOSITIONING LEGS on the master**: waits now happen IN PLACE
  (`stepQueueWaitHere` — nearest platform to the current position, map stowed =
  safe anywhere), and EVERY sweep (priming / W1 / W2 / closing) starts from
  whichever end the master is at (record content is direction-independent; the doc's
  own W2 is S->N). Result: W2 of batch k runs on lane 2k+129 = W1 lane of batch k+1,
  so the master's total movement is exactly the record sweeps — the dead north-only
  `stepQueueSafeRoute` was removed. Chain check: fill(south) -> priming S->N ->
  W2 N->S -> W1 S->N ... -> batch 62's W2 lands on lane 253 = the closing lane.
- **TICK-LEVEL WAIT CONSUMPTION (fixes the START ALL deadlock class, known issue
  #11)**: WAIT_PRIMING_BUILD / WAIT_BATCH_BUILD / WAIT_CLOSING_BUILD / WAIT_BREAK_ALL
  consume their slave signals directly in tickStepMaster every tick — the fragile
  checkpoint-arrival re-check chain is now a fallback only. Every wait heartbeats
  every 30s ("Still waiting for ready:N (Xs)...") and aborts with a named signal
  after 10 minutes — a silent hang is no longer possible.
- **ADAPTIVE PRINT PACE (slave real-time repair)**: while walking a print pass, the
  slave holds forward whenever an in-range plan block is placeable right now (air,
  off-cooldown) — the placement retries immediately instead of being outpaced and
  left for an end-of-pass repair walk. Per-position attempt cap (5) so a permanently
  rejected block cannot stall the pass; capped blocks fall through to the rare
  end-of-pass repair. `placementAttempts` cleared at every plan reset.
- **break(2) pickup**: verified no targeted pickup exists in break(2) in any recent
  jar — what was observed is vanilla proximity passively collecting drops that pop
  1.5-3.5 blocks from the mandatory breaking lane (zero walking cost, and it reduces
  break(4)'s sweep work). No change made.

### 2026-10-19 (c) — batch-0 flow audit: priming/floor redundancy + break(2) pickup verdict
User asked for a doc-vs-code-vs-logs comparison of all batch-0 phases and the
unnecessary NS walking named. Findings (no code changed — merge proposal awaits go-ahead):

- **The priming build and the batch-0 FLOOR half are the SAME 450 blocks** —
  `assemblePriming()` = `addLayerAt(rec[0], 0) + addLayerAt(rec[1], 2)` = exactly the
  buildhalf:0:f plan (verified identical at lines 5819/3992). Log-proven in the 21:24
  run: priming placed 450 fresh (2m26s), then the floor-half phase ran 4 refill chest
  cycles + a 24s verification pass that placed nothing new — followed by W1 sweeping
  lane 125 over the SAME world state the priming walk had already recorded (no tops
  standing in both cases). Net waste per run: the floor-half request + supply trips +
  verification pass + the W1 sweep + one extra south->north return ~= 1.5 min.
  **Proposed merge:** treat the priming walk AS W1 — after PRIMING_WALK completes, send
  `buildhalf:0:t` directly (master already supports this: the split lane logic at the
  stepBatchSafe case picks lane 127 for W2 when stepMasterBreak2Sent is set). The tops
  half would need a supply run added (in the merged flow no floor-half beginBatch(0)
  fetches the dominant materials — today's "materials at hand" assumption breaks).
- **Standard batches (k>=1) match the doc exactly**: master W1 N->S on 2k+127, W2 S->N
  on 2k+129 (= next batch's W1 lane, so the chain never walks a dead NS leg; all
  south->north returns are parallel with slave builds). Slave does 3 NS passes
  (print / break(2) / break(4)) — the minimum the doc's build->break(2)->break(4)
  ordering allows. No dead walks found for k>=1.
- **break(2) item pickup verdict: the current code already complies** — the only item
  chase path is sweepTick (reachable only through finishBatch at batchExit, i.e. after
  break(4)), my breakAllDone gate is break(4)-only, the legacy mineEnd collection is
  unreachable in the step flow, and no inventory/deposit logic triggers at breakStage 2.
  Drops landing at the slave's feet during break(2) are collected by vanilla proximity
  passively (no walking, cannot be disabled) — targeted pickup in break(2) existed only
  in pre-34481ca jars (192d4f7's per-tick side-steps, since removed).
- Minor: the PRIMING_WALK completion queues two transit checkpoints that
  `beginStepBatch(0)`'s `stepQueueSafeRoute` clears in the same tick — dead code, no
  runtime cost.

### 2026-10-19 (b) — BREAK-ALL phase: arrival gate + sweep exit discipline (same logic as RESET)
Reset work committed as `c9afad0`. User clarification: the advancement problems live in
the BREAK-ALL phase (break(4)) — the slave's single breaking pass down the batch lane.
Both the pass-end gate and the exit sweep now carry the RESET verification logic:

- **`breakAllDone` was an EMPTY case** (`case "breakAllDone": break;`) — the breaking
  pass handed straight off to batchExit with ZERO verification of what it just walked.
  It is now a full ARRIVAL GATE (same shape as resetPass): census over the batch
  window incl. margin ({ready, falling, pickupDelay, owned} + pendBrk + vanillaQ),
  straggler blocks chased through the hybrid Mining loop (nextBlockPos armed; pending
  guard + vanilla continuation apply), in-flight drops DWELT for, CLEAN only on
  blocks=0 + ready=0 + in-flight=0, STALL DETECTOR (same signature discipline, 80
  frozen rounds -> dump + advance to the exit gate). Applies to the closing break
  route too (same action). NOTE exposed while porting: `startClosingBreak` walks
  staging x_rel=253.5 (the MASTER's final-walk lane) while the closing pair sits at
  lanes 126-127 — the pass end is ~125 blocks out of breaking range; the gate's
  straggler chase now walks to the blocks instead of error-stopping, but the route
  itself is a suspected pre-existing bug in the UNTESTED closing flow.
- **finishBatch census gate**: completion now requires blocks=0 AND ready-items=0 AND
  no in-flight drops over the batch window (census via `itemCensusInBatchWindow` —
  same states as the RESET census).
- **In-flight dwell** at the exit: a clean-looking batch with falling/pickup-delayed
  drops WAITS (bounded 100 rounds, logged) instead of completing and leaving them
  behind — the RESET premature-CLEAN fix.
- **Inventory-full deposit trip**: inv full with drops pending queues a deposit chest
  then re-verifies — previously every sweep re-arm exited instantly and burned all
  8 retries.
- **Progress-aware retries**: a finishBatch re-arm only consumes a retry when the
  sweep state signature (blocks + item states + position) is FROZEN since the last
  re-arm; progress refunds retries. 8 consecutive frozen re-arms give up with a
  CRITICAL warning naming what remains.
- **Anti-stuck exemption**: sweepTick/batchExit/breakAllDone joined resetPass/
  resetStation outside the generic 10-round force-skip — long re-queue sequences had
  their only queued checkpoint removed mid-clear, leaving an EMPTY route -> StandBy
  with the batch unfinished and the master waiting forever.
- **Logs**: [BREAKALL-VERIFY] pass-end census (on state change), CLEAN, STALL;
  [SWEEP-VERIFY] early-exit reason (budget/inv), dwell entry, gate census, re-arm
  with FROZEN marker + retry count, give-up warning.
State fields `sweepWaitRounds/sweepWaitLogged/sweepReArmSig` cleared on activation,
batch begin, tops-half begin, and stepAbort; `nearestRemainingPlanBlock` helper added.
Build green; UNCOMMITTED pending approval.

### 2026-10-19 — RESET-VERIFY logging + segment-decode fix + item scan margin + full fix pass
User report: during RESET the bot walks to the next checkpoint before all blocks are
broken/confirmed and before dropped items are collected — including drops that skid
1-2 blocks OUTSIDE the print box and are ignored forever. Root-cause analysis (code
audit) found SIX defects; user gave the fix go-ahead — ALL FIXED this session:

1. **`resetZToSeg` still used the OLD 5-segment bound** (`for seg = 4..0`) while the
   redesigned ladder has 16 segments of 8 rows. Every checkpoint anchored in rows
   >= 32 (segments 5-15 = 75pct of every column) decoded to segment 4, so the arrival
   scan verified rows 32-39 instead of the segment just walked -> premature CLEAN ->
   advance with blocks standing. **FIXED** (loop now runs over the full array); the
   decode-mismatch warning in the logs stays as live proof (it must never fire).
2. **Item census filters hide in-transit drops**: `findNearestItemInArea` requires
   `groundCollision && !cannotPickup()`. Fresh drops have a 10-tick pickup delay plus
   fall/bounce time — invisible at arrival -> CLEAN declared while items exist ->
   left behind. **FIXED**: the CLEAN verdict now DWELLS (re-queues resetStation in
   place) while falling/pickup-delayed drops are in the margin window; landing flips
   them to ready -> chased and collected. Frozen waits still advance via the stall
   detector with a dump.
3. **Anti-stuck force-skip abandoned segments**: >10 consecutive same-action arrivals
   force-advanced regardless of remaining content. **FIXED**: resetPass/resetStation
   are exempt from the generic force-skip; a per-arrival STALL DETECTOR (signature =
   block count + every item state + bot position, 80 frozen rounds) provides loop
   protection that cannot fire while any progress is happening.
4. **Stale out-of-range feed target jammed the pass**: `nextBlockPos` was only nulled
   when the block turned air; a target armed beyond interaction range blocked
   re-arming — the bot walked on breaking nothing. **FIXED**: after 10 out-of-range
   ticks the feed drops the stale target and re-arms (logged).
5. **DOUBLE breakPending processor**: a silent processor at the top of the tick
   decremented and removed expired entries, and the routing processor further down
   decremented AGAIN — on every tick where the flow returned early (all checkpoint
   arrival ticks!), expired entries were silently swallowed BEFORE the vanilla
   routing could flag them. **FIXED**: single unified processor at the top of the
   tick, runs unconditionally.
6. **fastBreak hammering starved the vanilla fallback on multi-tick blocks**: the
   Mining-state break loop (path A), the path-B loop, AND the arrival handler each
   raw-sent START/STOP every tick without consulting `needsVanillaBreak` or
   `breakPending` — re-sends reset the server's elapsed-time validation so a
   multi-tick block could never complete, piling up rounds into the force-skip.
   Also: path B removed the vanilla flag after ONE call, dropping the block back
   into fastBreak next tick. **FIXED** everywhere: pending-confirmation guard (one
   send per pos per 2-tick window), vanilla-flagged blocks go through
   `BlockUtils.breakBlock` continuously (removal only via the air-cleanup), path B
   skips re-targeting entirely when path A already sent this tick, and the arrival
   handler follows the same hybrid rules. Side effect: instamine sends serialize to
   ~1 per 2 ticks (before: duplicate sends per tick) — no throughput regression on
   localhost since confirmations arrive in 1-2 ticks anyway.

**ITEM SCAN MARGIN (user-directed, this session):** drops ejected at the box edge
skid outside and every item scan was box-bounded. All reset item scans now extend
`RESET_ITEM_MARGIN = 2` blocks past the block bounds in every horizontal direction:
- resetPass arrival: item target, deposit-full check, census, and the item chase all
  use the segment bounds +-2 (catches drops that left the segment AND the box).
- printBoxContentRange (verify walk): item band widened to x corner-2..corner+129,
  z corner-3..corner+129; re-scoped ladder columns clamped to 0-31 so the walk never
  routes outside the box — the boundary columns' own +-2 arrival scans reach the
  outside drops instead.
Blocks are never margin-scanned (they cannot move).

**Added `[RESET-VERIFY]` logs** (all `info`/`warning`, always-on unless noted):
- resetPass/resetStation arrival: decode-mismatch WARNING (segment bug, live proof),
  full census line (blocks + item states ready/falling/pickupDelay/owned over the
  margin window + in-flight breakPending + vanilla queue), PREMATURE-CLEAN warning
  when the verdict fires with falling/pickup-delayed drops present, INV-FULL marker
  when the item scan was skipped.
- Anti-stuck force-skip: WARNING with exact blocks/items/pending left behind in the
  abandoned window (items over the margin window).
- Feed-jam detector: WARNING when the armed target sits out of range 40+ ticks.
- Break-confirmation processor (debug-prints gated per tick): server-confirmed vs
  expired-to-vanilla counts + in-flight size; PERSISTENT-REJECTION warning when one
  block expires into vanilla fallback 4x without ever confirming.
- verifyWalk stations: box+margin item census including filtered states + warning
  when the station passes with drops still on/in flight over the box.
- fullSweepDone: completion-state counters (breakPending / vanilla queue /
  persistent rejections — all should be 0).
Implementation: census helpers (`itemCensusInArea`, `countPendingInRange`,
`countVanillaInRange`, `countBlocksInArea`), `vanillaBreakRetries` map, feed-jam
watch fields; state cleared on activation + stepAbort. Compiles clean; NOT committed
(awaiting live verification per commit discipline).

### 2026-10-18 — CALIBRATED CHECKPOINT + step-reduced batch 0 (THE breakthrough)
- **THE CALIBRATED CHECKPOINT (user-directed, math-proven):** the doc's kernel table
  reads "sweep lane x=2k+127" as the LITERAL standing coordinate - the block EDGE,
  not the centre. Standing at lane+0.5 (centre) put the near east lane at 125.0
  (inter-spot rows frozen = the batch-0 "53 rec gaps") and the far dominant into the
  marginal band. Proof: the three constraints (dominants fully record S<=2k+126.98,
  floor dormant S>=2k+127.0, east rec full S<=2k+127.27) intersect at the SINGLE
  point S=2k+127.0; every doc probe number (125.46=124.5+13.5, 127.2, 125.5 freeze)
  is reproduced exactly from S=2k+127.0 and nothing else. Ev's suppressLoadMarkers.ts
  confirms: marker block = loadLine+124, phase 1 (our batch 0) = block 127.
- **THE IMPOSSIBILITY THEOREM (floor vs dominants from one build state):**
  R_dom - R_rec = 1.74 > 1 = the row spacing of the pixel checkerboard, so ANY master
  position reaching a floor cell (<=125.46) also records the adjacent odd cell
  (<=126.46 < 127.2). No checkpoint separates them in one world state. Measured from
  all four directions (blank / darkened 91 / dom corrupt 97 / floor partial 59).
- **STEP-REDUCED BATCH 0 (split-build flow, creative-verified 0+0+0):** build the
  floor+borrow half -> W1@125.0 (floor records clean - no tops exist yet) -> build
  the tops half (slave stays in place, materials at hand) -> W2@127.0 (tops record,
  floor frozen at 125.5+) -> break(all) - NO break(2) at all. Same step count as a
  normal batch (3 slave passes, 2 master walks), no pre-build, no repair. Each
  pixel's last write provably occurs with the correct blocks standing; deductively
  guaranteed and oracle-confirmed: DOM p0 -> 0, REC p1 -> 0, FLOOR p0 -> 0/128.
- **Hold-replace variant (superseded by split-build):** W2@125 + master holds at
  127.0 while slave re-places tops. Same pixel guarantee, +1 slave pass.
- Also this session: master ladder flow (W1 -> break2 at south -> east return ->
  breakall at N), direction-agnostic slave routes, hold-at-dwell-only map
  discipline, RESET as scoped serpentine ladder + centre verification walk,
  module start-state bug fixes (beginStepPrimingRun never armed the stage;
  duplicate wait route), MapAreaCache removed, Dynamic sprint via Meteor Speed
  module, instamine fastBreak, /holdmap dry-run restored for creative runner.

### 2026-10-18 — User-directed master ladder flow + direction-agnostic slave passes
- **Master per batch k (user spec, dormant-lane variant):** wait at the batch's sweep-lane
  north cp(k+1), map stowed, signal build(4) -> walk 1 N->S on x=2k+127 (map held,
  records dom k + rec k+1) -> AT SOUTH: stow + request break(2) (west lanes become
  DORMANT) -> return north on the dormant east lane x=2k+129 (= next batch's sweep
  lane; everything >= frozen distance from the broken west columns) -> at north request
  break(all) -> on done, next batch builds with the master already in place. The whole
  run is a clean eastward ladder; walk B (the S->N confirm pass) is REPLACED by the
  dormant-lane return per user directive - note this drops the doc's second recording
  pass, making the (still-missing) per-batch pixel verification more important.
- **Slave direction-agnostic passes (user spec):** every slave route (batch, resume,
  repair, break(2) retry, break-all) is mirrored when the bot already stands on the
  south half - it prints/breaks from whichever side it is on; no dead travel to a fixed
  start. The north corridor is skipped for south-started routes.
- **Creative runner fixes:** the module's /holdmap dry-run (pin -> hold unnamed filled;
  auto-use one empty map at the current spot incl. main-inventory swap) was restored -
  freshmap depends on it. Creative test cycle 0-2: priming 0 + all batches 0+0
  mismatches; cycle 60 62 ABORTED (44+45 DOM mismatches at lane 120, 0x65 terrain bytes
  - dx-127 marginal band after partial-run close sweeps; diagnosis parked).

### 2026-10-18 — RESET reworked into the checkpoint-batch ladder (user-directed)
- 32 columns × 5 z-segments, snake order; each segment worked to 100% (break + pick)
  and CONFIRMED by a bounded scan before advancing; noobline belongs to segment 0
- Pickaxe / inventory-full interrupts rebuild the ladder from the current station
- The old vantage sweep survives only as the final verifier (fixed meanwhile: budget
  exhaustion no longer declares "complete" over standing work; full-inventory item
  blindness triggers a deposit trip instead of ignoring drops)

### 2026-10-14 — Dynamic transit speed via Meteor's Speed module + pickaxe emergency fix
- **Sprint setting gained "Dynamic"**: transit legs (corridor, supply trips, waits,
  any non-print/non-break walking) enable Meteor's Speed module programmatically —
  vanilla mode, 7.192 b/s, timer 1 — via `Modules.get().get(Speed.class)`; printing
  and breaking always use default walk speed (module off). Force-disabled on module
  deactivate. The module is USED, never copied.
- **Pickaxe emergency restock never visited a chest**: `pauseForRestockTrip(true)`
  added the pickaxe to `restockList` but only the stage-0 material path queued a
  chest — the emergency trip walked the corridor, resumed breaking with the same
  worn pickaxe and re-triggered every 5s cooldown (the observed loop). Now the first
  restockList entry always gets `addClosestRestockCheckpoint()`. The worn pickaxe
  was already parked back into the chest by the withdraw handler.

### 2026-10-14 — Lane-2 centering before printing (user-directed)
- **Problem:** when batch k's sweep left the slave off the lane-2 centerline, batch k+1's
  in-range print (by design) placed the new window's y99 floor right into the bot's exit
  path — the bot walled itself in mid-transit (observed live: stuck at x=-438 straddling
  lanes 1/2 of batch 4 while placing its floor)
- **Fix (user spec):** before printing a new batch/priming/closing, the slave makes sure
  it IS on lane 2 — a lateral `centerLane` leg walks to the centerline (nearest distance
  at current z) and the north exit runs on that centerline; placement is hard-gated on
  `|botX - lane2X| <= 0.3` (picker returns nothing until centered), and `centerLane` is
  not a placement-allowed action — double enforcement of "no printing until on lane 2"

### 2026-10-14 — Classic-NBT input fix (build(4) was wrong) + format guard
- **What:** the module had been loading Év's PRE-ENCODED step-checker export
  (`test_3-suppress_step_checker_WE (1).nbt`, 254×130×129 — 65 phases stacked
  vertically at y=2j/2j+1) into the classic-staircase heightmap derivation →
  rec 14,336 instead of 13,764 (+572 wrong recessive blocks), priming 401 vs 450,
  batch 0 = 632 vs 681
- **Fix:** map folder now holds `test_3-classic.nbt` (the protocol input); loader
  gained a format guard — x-span > 160 or y-span > 100 is rejected with a clear
  "export in staircase mode (Z-129)" error instead of silently wrong layers
- **Verified on boot:** rec 13,764 / dom 13,801 / grand 27,565 — exactly the proven
  converter's numbers (historical PASS 27,565/27,565)

### 2026-10-14 — Lane-2 entry (user-directed), in-range printing
- **All batch routes walk lane 2 (x = 2k+2.5)** — user: "enter through lane 2, exactly
  center, not the corner block". Includes: batch route, break(2) wait/retry, break-all,
  repair pass, mid-batch resumes, north-corridor entry, priming (2.5), closing build
  (127.5). History: original walkway 2k+4.0 → 1.5 → 3.5 → final 2.5 (= block 2k+2 center)
- **Print is litematica-style in-range** (user: "remove print only from back"): any plan
  block within interaction range is placed, nearest first; no behind-gate, no entry
  gate, no jump logic (lane 2 is feet-clean)
- **Root cause of the "lane 0" sightings:** `resumeBatchRoute` computed
  `2k+2.5` with `currentBatch = -1` during priming → corner+0.5 (lane 0) with regular
  checkpoint names. Now special-cases priming (-1) and closing (63)

### 2026-10-14 — Crash fixes
- **Join crash:** `@Shadow sendPlayerAction`/`blockBreakingCooldown` in
  ClientPlayerInteractionManagerMixin don't resolve at runtime (no refmap) — mixin
  apply failed when the interaction manager class loaded on server join. `fastBreak()`
  now sends START/STOP destroy packets directly via the network handler
- **batchExit crash + missing wiring:** the `batchExit` checkpoint had NO handler and
  `finishBatch()` had ZERO callers — every completed batch crashed the slave with
  IndexOutOfBoundsException at route end. Wired `case "batchExit": finishBatch()`, and
  the empty-route fallthrough now stands by cleanly instead of crashing

### 2026-10-14 — Instamine + placement mechanics
- `fastBreak()` (START+STOP destroy in one tick) replaces `BlockUtils.breakBlock()`
  at all three break sites
- Air-place via `interactBlock` with inside-block hit (prints through walls),
  no rotation anywhere, blocks-per-tick / breaks-per-tick loops (default 4, no hard cap)
- Litematica-style 4-tick placement cooldown per position (failed/occupied positions
  retried after the bot moves off them)
- PLACE debug line now prints x, y and z of every placement

### 2026-10-14 — Noobline in RESET scan
- `findNearestPrintBoxBlock()` scanned z ≥ corner.z only — leftover noobline blocks
  (z = corner.z-1) were invisible to the reset sweep and would wall the north
  corridor entry after an abort. Scan now includes the noobline column

### 2026-10-13 — `3ccf2cf` Shared item helpers + expanded bounds
- **What:** extracted `findNearestGroundItem(margin)` + `targetGroundItem()` as shared
  helpers; item scan expanded to ±3 blocks around the print box
- **Why:** items bouncing outside the exact 128×128 box (noobline, edges) were missed;
  the SWEEP and RESET had duplicated item-scan code
- **Files:** StepCheckered.java

### 2026-10-13 — `fd142f4` Full 2-bot engine (major commit, +2402 lines)
- **Master:** StepMasterStage lifecycle, map fill/pin/hold/stow/lock, checkpoint walks
  with dwells, TCP coordination, progress persistence, START ALL + RESET buttons
- **Slave:** 6-phase batch lifecycle, center-lane walking, no-rotate placement,
  repair passes, pickaxe slot 8 + food offhand, chest-aware depositing, spatial sweep
- **RESET:** full-area vantage-point progression (north → center → complete)
- **Settings:** bot-role dropdown, defaults (range 5, delay 10, sprint Always),
  removed min-mining-range and min-z-collection-distance
- **Breaking:** all sites use `BlockUtils.breakBlock()` (same as proven snapshot)

### 2026-10-13 — `161ff74` Corner convention fix
- Corner = pixel (0,0) = map tile NW block (not the noobline)
- Saved config migrated z -321 → -320

### 2026-10-13 — Phase fixes (uncommitted into fd142f4)
- **PRINT center-lane:** x = 2k+2.0 (boundary lanes 1&2), not lane 2 (2k+2.5)
- **Repair pass 2:** uses lane 2 (repairX = 2k+2.5) — center blocks already placed
- **Closing:** staging at x=127.0 (center of lanes 126-127), not 253.5 (master sweep)
- **Supply-run cooldown:** 100-tick pause prevents infinite restock trigger loop
- **tickStepMaster processes inventory:** chest responses handled inside master scheduler
- **requeueStepWait pause:** 20-tick timeout prevents checkpoint oscillation
- **Mining range-gated:** break packets only sent within interaction-range
- **Breaking rotation:** gated by rotate-place setting (both sites)

### 2026-10-13 — Debug logging
- State changes with phase names + batch + plan size
- Checkpoint consumption with action + position + queue depth
- TCP messages both directions (master→slave and slave→master)
- All toggleable via existing `debug-prints` setting

### 2026-10-13 — Hot-reload tooling restored
- `tools/hotswap/hotswap.sh` + agent from reference snapshot
- Works for method-body-only changes; structural changes need restart
- JVM limitation: cannot add/remove fields, methods, or change signatures

---

## Known issues & gotchas

1. **The module needs the CLASSIC staircase NBT** (staircase mode + Z-129 export).
   Év's pre-encoded step-checker/suppress exports stack the 65 phases vertically and
   produce silently-wrong layers — the loader's format guard rejects them now, but
   only feed it classic exports
2. **No per-batch pixel verification yet** (doc §7/§11): the master's held-map
   pixels are available as the oracle, but nothing byte-compares per batch or aborts
   on mismatch — the biggest missing safety net
3. **Hotswap only works for method bodies** — new fields/methods = restart required
4. **PrismLauncher refuses `--launch` while instance is running** — kill first
   (use `tools/manualrun/gameprocs.ps1 -Action kill`)
5. **PowerShell `Start-Process -ArgumentList` splits args containing spaces** — the
   slave instance name `1.21.11 (1)` must be quoted inside the argument string or the
   launch silently targets the master instance
6. **Unloaded chunks read as air** — the RESET vantage progression handles this,
   but any code scanning the world must account for render distance
7. **`pauseForRestockTrip` had a trigger loop** — fixed with 100-tick cooldown;
   don't remove the cooldown check
8. **Batch placement never rotates** — the plan reaches around/behind the bot;
   rotated placements against occluded faces get server-rejected
9. **`stepAbort` must NOT send "done" immediately** — finishBatch verifies all
   blocks gone before reporting; premature signals mark incomplete batches as done
10. **Deposit backlog**: one stack per delay tick while chest screen stays open;
   full-inventory syncs only arrive on chest open (not per-move)
11. **START ALL deadlocks on the MASTER's wait stages (diagnosed 2026-10-19 22:00 from
   live logs, fix pending)**: the master's waits (WAIT_PRIMING_BUILD / WAIT_BATCH_BUILD
   / WAIT_BREAK_ALL) re-check slave signals ONLY when a checkpoint "arrival" fires
   (requeueStepWait = re-queued checkpoint + 20-tick pause). That arrival chain has NO
   timeout, NO watchdog, NO snap/generous radius (0.2-block buffer vs ~0.28 blocks/tick
   sprint steps), and NO always-on logging. Two START ALL runs tonight deadlocked
   exactly there while the slave behaved correctly: 21:24 run — priming ok, floor half
   printed, slave sent ready:0 at 21:27:23, master silent until killed at 21:27:39;
   21:52 run — slave sent ready:prime at 21:53:37, master NEVER consumed it (and even
   the slave's 21:54:03 disconnect produced no abort line — the scheduler was wedged
   below the disconnect check for 14 minutes). Everything on the master is debug-gated,
   so a wedged wait is indistinguishable from working. Planned fix: tick-level signal
   consumption in wait stages (not arrival-driven), wait timeouts with named-stage
   aborts, snap/generous arrival radius + walk-progress detector, always-on stage
   transition + heartbeat logs.
   **FIXED 2026-10-19 (d)**: tick-level signal consumption in all wait stages +
   30s heartbeat + 10-minute named timeout (see change history (d)).

## Test rig

- Server: Folia 1.21.11 at `D:\Project\Foliaserver` (RCON 127.0.0.1:25575)
- Master: PrismLauncher instance `1.21.11`, player `Comic_Squid`
- Slave: instance `1.21.11 (1)`, player `DrStrangeJr`
- Shared config: `C:\Users\ComicSquid\Desktop\nerv-printer\`
- Control endpoints: localhost HTTP, ports 42070/42071 (first-come, check /status "player")
- Deploy: restart (close → replace jar → staggered relaunch ~30s apart with --server)
