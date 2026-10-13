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

| # | Phase | What happens | Lane | Direction |
|---|-------|-------------|------|-----------|
| 1 | SUPPLY | Restock materials + pickaxe (slot 8) + food (offhand) via north corridor | corridor → station | out & back |
| 2 | PRINT | Center-lane (x=2k+2.0), no-rotate placement, repair from lane -1 + lane 2 | center lane | N → S |
| 3 | SUPPRESS | Wait master break(2), break west Y2/Y3 from lane -1, no collection | lane -1 | S → N |
| 4 | DEPOSIT | Wait master break-all, deposit ALL building blocks to correct chests | corridor → station | out & back |
| 5 | RECYCLE | Center-lane south, break everything, items auto-collect by proximity | center lane | N → S → N |
| 6 | SWEEP | Combined spatial break+collect (nearest target = block OR item) | window | in place |

### RESET (full-area clear)

Slave walks to north-center vantage (x=64, z=-2), scans print box, breaks nearest
blocks / collects nearest items. When north is clean → moves to map-center vantage
(x=64, z=64) to scan south half. Only declares complete when BOTH vantages confirm
empty (unloaded chunks read as air). Then deposits and returns to standby.

### Shared helpers (reuse, don't duplicate)

- `findNearestGroundItem(margin)` — nearest pickup-able ground entity, print box ± margin
- `targetGroundItem(item, action)` — walk-to-item checkpoint for vanilla pickup
- `findNearestPrintBoxBlock()` — ring-scan for nearest non-air block in print box
- `findRegisteredPickaxeItem()` — pickaxe from toolSet or materialDict
- `assembleBatch(k)` / `assemblePriming()` / `assembleClosing()` — world-coord plans

---

## Change history (most recent first)

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

1. **Hotswap only works for method bodies** — new fields/methods = restart required
2. **PrismLauncher refuses `--launch` while instance is running** — kill first
   (use `tools/manualrun/gameprocs.ps1 -Action kill`)
3. **Unloaded chunks read as air** — the RESET vantage progression handles this,
   but any code scanning the world must account for render distance
4. **`pauseForRestockTrip` had a trigger loop** — fixed with 100-tick cooldown;
   don't remove the cooldown check
5. **Batch placement never rotates** — the plan reaches around/behind the bot;
   rotated placements against occluded faces get server-rejected
6. **`stepAbort` must NOT send "done" immediately** — finishBatch verifies all
   blocks gone before reporting; premature signals mark incomplete batches as done
7. **Deposit backlog**: one stack per delay tick while chest screen stays open;
   full-inventory syncs only arrive on chest open (not per-move)
8. **The legacy `SuppressionPrinter` is unregistered** — its code stays in the tree
   untouched; the reflection hook for BotControlEndpoint is in the git-ignored
   local tool

## Test rig

- Server: Folia 1.21.11 at `D:\Project\Foliaserver` (RCON 127.0.0.1:25575)
- Master: PrismLauncher instance `1.21.11`, player `Comic_Squid`
- Slave: instance `1.21.11 (1)`, player `DrStrangeJr`
- Shared config: `C:\Users\ComicSquid\Desktop\nerv-printer\`
- Control endpoints: localhost HTTP, ports 42070/42071 (first-come, check /status "player")
- Deploy: restart (close → replace jar → staggered relaunch ~30s apart with --server)
