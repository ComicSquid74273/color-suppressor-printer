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
