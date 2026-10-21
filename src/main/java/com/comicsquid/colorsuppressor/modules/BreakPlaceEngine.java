package com.comicsquid.colorsuppressor.modules;

import com.comicsquid.colorsuppressor.utils.ToolUtils;
import com.comicsquid.colorsuppressor.utils.Utils;
import com.comicsquid.colorsuppressor.interfaces.IClientPlayerInteractionManager;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The place/break mechanics: target pickers (nearest in-range plan position for
 * placing, distinct-position multi-send for breaking), the hybrid fastBreak/vanilla
 * sender with its confirmation bookkeeping, the adaptive pace governor, the rolling
 * hotbar lookahead, and the on-demand hotbar swap. Owns the per-position cooldown /
 * attempt maps and the break-confirmation queues.
 */
final class BreakPlaceEngine {
    final StepCheckered mod;

    // --- Engine state (moved from the module) ---
    HashMap<BlockPos, Integer> placementCooldowns;               // litematica-style: retry timer per position
    HashMap<BlockPos, Integer> placementAttempts;                // per-position placement attempts (diagnostic; gating is tick-based now)
    HashMap<BlockPos, Integer> placementRetryStart;              // pos -> tickIndex of its first pick: the print-retry-ticks window
    HashMap<BlockPos, Integer> breakRetryStart;                  // pos -> tickIndex of its first pending expiry: the break-retry-ticks window
    int placeHoldTicks;                                          // consecutive adaptive-pace hold ticks (watchdog)
    int approachHoldTicks;                                       // consecutive break approach-hold ticks (watchdog)
    HashMap<BlockPos, Integer> breakPending;                     // pos -> ticks until silently confirmed
    HashSet<BlockPos> needsVanillaBreak;                         // fastBreak failed -> use vanilla mining
    HashMap<BlockPos, Integer> vanillaBreakRetries;              // pos -> times expired into vanilla fallback
    BlockPos vanillaStickyTarget;                                // ONE multi-tick vanilla target at a time
    boolean breakSweepActedThisTick;                             // the break loop already sent packets this tick
    BlockPos splitPendingPos;                                    // live split pair: START sent, STOP awaiting its due tick
    int splitStopDueTick;                                        // absolute tickIndex of that STOP
    int tickIndex;                                               // monotonic engine tick (the split-pair clock)
    int lastSyncedSelectedSlot;                                  // selected slot the server has been told about
    int rateTick;                                                // 20-tick rate-report cycle
    int breakSentPerSec, breakConfirmedPerSec, placeSentPerSec;  // [RATE] verification counters
    int toBeSwappedSlot;                                         // pending on-demand hotbar swap
    Item lastSwappedMaterial;

    BreakPlaceEngine(StepCheckered mod) {
        this.mod = mod;
    }

    /** Full engine-state reset (module onActivation path). */
    void resetForActivation() {
        placementCooldowns = new HashMap<>();
        placementAttempts = new HashMap<>();
        placementRetryStart = new HashMap<>();
        breakRetryStart = new HashMap<>();
        placeHoldTicks = 0;
        approachHoldTicks = 0;
        breakPending = new HashMap<>();
        needsVanillaBreak = new HashSet<>();
        vanillaBreakRetries = new HashMap<>();
        vanillaStickyTarget = null;
        breakSweepActedThisTick = false;
        splitPendingPos = null;
        splitStopDueTick = -1;
        tickIndex = 0;
        lastSyncedSelectedSlot = -1;
        rateTick = 0;
        breakSentPerSec = 0;
        breakConfirmedPerSec = 0;
        placeSentPerSec = 0;
        toBeSwappedSlot = -1;
        lastSwappedMaterial = null;
    }

    /** Tick-budget check for a plan cell's print retry window (print-retry-ticks):
     *  true once tickIndex has moved print-retry-ticks past the cell's FIRST pick.
     *  Replaces the old attempts>=3 cap (live 13:24: three marginal sends inside
     *  1.5s of sprinting past burned the budget before any fair stopped retry —
     *  the repair pass then placed those cells from dz −3.4..−3.6 first try). */
    boolean printRetryExpired(BlockPos pos) {
        Integer start = placementRetryStart.get(pos);
        return start != null && tickIndex - start >= mod.printRetryTicks.get();
    }

    boolean adaptivePaceHold() {
        if (mod.state.equals(State.Walking) && mod.breakStage == 0 && mod.currentBuildPlan != null
            && !mod.checkpoints.isEmpty()) {
            String legAction = mod.checkpoints.getFirst().getRight().getLeft();
            // PRINT-LEG SCOPE (live 08:49:07-17: three 101-tick watchdogs while the
            // bot crept north on a PREP sprint leg at ~4 places/s — the hold was
            // fighting the very supply trip it exists to enable). The cadence
            // governs print legs only ("printDone" = every batch/priming/repair
            // print leg, "stepClosingReady" = the closing print); transits (sprint,
            // batchBegin, refill, deposit legs) walk full speed and still place
            // opportunistically en route — the hold only gates movement.
            if ("printDone".equals(legAction) || "printDone2".equals(legAction)
                || "stepClosingReady".equals(legAction)) {
                if (printFrontierWithinGap()) {
                    placeHoldTicks++;
                    if (placeHoldTicks > 100) {
                        // Safety net only - the attempts cap is the real bound (a block
                        // sent 10 times without standing is force-released to the repair
                        // pass, which drops it out of the hold's scan).
                        mod.warning("§ePrint pace-hold watchdog: released a " + placeHoldTicks
                            + "-tick hold - walking on (retrying blocks will trail).");
                        placeHoldTicks = 0;
                        return false;
                    }
                    return true;
                }
                placeHoldTicks = 0;
            } else {
                placeHoldTicks = 0;
            }
        } else {
            placeHoldTicks = 0;
        }
        return false;
    }

    /** PRINT FORWARD GAP — SENT-GATED (user rules 2026-10-21: "gap 1-2 then
     *  burst"; then "no walked-past misses" ((w)); then "same fix for placing
     *  too" after (ai) — the print mirror of the break approach-hold). The
     *  hold engages at ≤2.0 horizontal from the nearest UN-SENT forward plan
     *  block and releases the moment everything in reach is placed, SENT
     *  (on placement cooldown — the send already left; the server air-place
     *  needs no presence, exactly like a break pair), capped (repair owns it),
     *  body-overlapped (the picker refuses it — places en route once walked
     *  off; also the 101-tick watchdog pin), or not carried (swap/supply owns
     *  it). No echo waiting, no full-sphere drain before each step; blocks-per-
     *  tick is the drain rate per stop (the place loop sends up to that many
     *  distinct positions per tick). A failed send resurfaces after its 4-tick
     *  cooldown and re-gates movement if still ahead; anything that trails
     *  anyway places en route (any-direction picker) or in the repair pass.
     *  At-goal (dir = 0, pass end) the 2.0 gate drops — hold until the whole
     *  in-reach sphere is sent/placed (pass-end semantics). Off-lane the hold
     *  never engages (the picker's lane gate is closed — the route re-centers). */
    boolean printFrontierWithinGap() {
        // LANE-GATE MIRROR: the placement picker places nothing off the lane-2
        // centreline (wall-in guard) - the hold must not pin the bot either.
        if (mod.lowerMapCorner != null
            && Math.abs(mc.player.getX() - mod.slave.routes.currentWalkLaneX()) > 0.3) return false;
        // RETRY-RANGE GUARD - PRINT CASE, deliberately DIFFERENT from the break
        // case (user rule 2026-10-21: "never walk past a block until server
        // confirm - stop at max interaction range so a rejected print can still
        // retry"; live 12:43 pin: the old `dist >= retryEdge` had NO upper bound
        // and NO direction test, so far AHEAD cells - which exist along the whole
        // 128-row lane - pinned every print leg from tick 1; the 101-tick
        // watchdog crawled the bot ~1 walking tick per 5s with zero sends sent).
        // A placement, unlike a break pair, has no transient server-side fate:
        // an unlanded cell just stays air, and its retry is a FRESH send that
        // needs the cell inside the server-reach send clamp. So the guard pins
        // only on cells a stopped bot can still send - the [retryEdge, sendEdge)
        // shell - and that the bot is actively walking PAST (behind/abeam);
        // ahead cells are being approached (the 2.0 pacing gap below owns those),
        // and beyond sendEdge is repair-pass territory, the same doctrine as
        // attempts >= 3. The 0.6 margin = one tick of movement (creep to the
        // edge, never past). At-goal (dir = 0) the direction filter opens into
        // pass-end drain over the same sendable shell.
        double sendEdge = Math.min(mod.interactionRange.get(), 4.4);   // server survival reach: sends beyond it are silently rejected (the break side's proven law)
        double retryEdge = sendEdge - 0.6;
        int dir = breakTravelDir();
        int botZ = mc.player.getBlockZ();
        for (BlockPos pos : placementCooldowns.keySet()) {
            if (!mod.miningBlockState(pos).isAir()) continue;   // landed - confirm seen (was inverted: skipped the real unconfirmed cells and pinned placed ones instead)
            if (printRetryExpired(pos)) continue;               // tick budget spent - repair owns it, never pin on it
            double dist = mc.player.getEyePos().distanceTo(pos.toCenterPos());
            if (dist < retryEdge || dist >= sendEdge) continue;       // sendable shell only; beyond send range the repair pass owns it
            if (dir != 0 && (pos.getZ() - botZ) * dir >= 0) continue; // ahead/abeam - being approached, not walked past
            return true;
        }
        for (BlockPos pos : mod.currentBuildPlan.keySet()) {
            if (!mod.miningBlockState(pos).isAir()) continue;                    // placed = done
            if (printRetryExpired(pos)) continue;                               // repair owns it (print-retry-ticks budget spent - was attempts>=3, which three marginal sprint-past sends could burn in 1.5s without a single fair stopped retry)
            if (mc.player.getBoundingBox().intersects(new Box(pos).expand(0.3))) continue;   // unplaceable while overlapped - MUST mirror the picker's margin (the (al) mismatch pinned the bot 101 ticks on blocks the picker refused but this guard counted)
            Block planned = mod.currentBuildPlan.get(pos);
            if (planned == null || !mod.restock.isMaterialCarried(planned.asItem())) continue;  // supply owns it
            double dist = mc.player.getEyePos().distanceTo(pos.toCenterPos());
            if (dist < retryEdge || dist >= sendEdge) continue;       // about to leave send range while still placeable - hold and work it
            if (dir != 0 && (pos.getZ() - botZ) * dir >= 0) continue; // ahead/abeam - the pacing gap owns the approach, it never pins
            return true;
        }
        BlockPos nearest = null;
        double nearestH = Double.MAX_VALUE;
        boolean reachBusy = false;
        for (BlockPos pos : mod.currentBuildPlan.keySet()) {
            if (!mod.miningBlockState(pos).isAir()) continue;           // placed = done
            if (printRetryExpired(pos)) continue;                      // tick budget spent - move off or repair owns it (was attempts>=3)
            if (placementCooldowns.getOrDefault(pos, 0) > 0) continue;  // SENT - echo in flight - never gates movement
            if (dir != 0 && (pos.getZ() - botZ) * dir < -1) continue;   // behind - prints en route
            if (mc.player.getBoundingBox().intersects(new Box(pos).expand(0.3))) continue;  // picker refuses body-overlap - identical margin, or the hold pins on unsendable blocks
            double dx = pos.getX() + 0.5 - mc.player.getX();
            double dz = pos.getZ() + 0.5 - mc.player.getZ();
            double hDist = Math.sqrt(dx * dx + dz * dz);
            if (hDist < nearestH) { nearestH = hDist; nearest = pos; }
            if (mc.player.getEyePos().distanceTo(pos.toCenterPos()) <= sendEdge) reachBusy = true;   // sendable shell only - a 4.4-5.0 cell can never be sent, so it must not gate the at-goal hold either
        }
        if (nearest == null || !reachBusy) return false;
        Block planned = mod.currentBuildPlan.get(nearest);
        if (planned == null || !mod.restock.isMaterialCarried(planned.asItem())) return false;
        return dir == 0 || nearestH <= 2.0;
    }

    int sendDistinctBreaks(int maxSends, boolean fullSweep) {
        // LIVE SPLIT SESSION: only its STOP (flushed first in tickBreakConfirmations,
        // before this runs) may touch the server's mining state this tick — any other
        // START (pair, split, or vanilla attack) aborts the live session per the
        // server's "another started" rule. With palette blocks this window is always
        // empty (extraTicks = 1, STOP flushed at the top of the very next tick).
        if (splitPendingPos != null) return 0;
        HashSet<BlockPos> attempted = new HashSet<>();
        // INSTA-BREAK REQUIRES THE PICKAXE IN HAND: the server computes break time
        // from the HELD item at packet time. With a building material selected
        // (left over from the print pass), same-tick START+STOP is rejected and
        // blocks stall into the vanilla queue (live 2026-10-20: 25/s sent but only
        // 3.8/s confirmed, 75 stragglers, batch timeout). The old second loop did
        // setSelectedSlot(8) every tick; this shared sender must too.
        if (mc.player.getInventory().getStack(8).isIn(ItemTags.PICKAXES)) {
            mc.player.getInventory().setSelectedSlot(8);
        }
        // STICKY VANILLA: multi-tick breaking is stateful single-target — a NEW
        // BlockUtils.breakBlock on a different pos resets the previous one's progress.
        // Round-robining several vanilla targets per tick = nothing ever breaks (live
        // 2026-10-20 batch 13: 68 blocks frozen 80s "trying to break"). ONE vanilla
        // target at a time, held across ticks until air; everything else fastBreak.
        if (vanillaStickyTarget != null && (mod.miningBlockState(vanillaStickyTarget).isAir()
            || mc.player.getEyePos().distanceTo(vanillaStickyTarget.toCenterPos()) > 4.4)) {
            vanillaStickyTarget = null;   // broke, or the bot outran it - never freeze the queue on it
        }
        // FORWARD-FILTER COMPATIBILITY (live 2026-10-21, the 151-tick-hold deadlock at
        // [-445,99,-299]): a sticky that sits BEHIND the travel direction is never
        // re-sent by the forward-only picker, yet the air/out-of-reach clears above
        // never fire for it - every other vanilla target then skips on sticky!=target
        // and the whole vanilla queue froze (5 blocks, server=0) while the blocked
        // lane re-triggered the approach watchdog at the same spot. Behind = the
        // pass-end sweep's territory: drop it and let the picker re-home sticky to a
        // sendable target. fullSweep's ring-scanner has no direction filter, so RESET
        // keeps the old behavior.
        if (vanillaStickyTarget != null && !fullSweep && mod.currentBuildPlan != null) {
            int stickyDir = breakTravelDir();
            if (stickyDir != 0 && (vanillaStickyTarget.getZ() - mc.player.getBlockZ()) * stickyDir < -1) {
                vanillaStickyTarget = null;
            }
        }
        int sent = 0;
        boolean vanillaSentThisTick = false;
        // ADAPTIVE SEND CLAMP (live 2026-10-21: every pass still ended with 2-3
        // stragglers for the gate to chase). While WALKING (approach phase) only send
        // WELL inside reach: a trailing-edge send whose rejection/echo lands after the
        // bot has moved on ends up behind the forward filter and becomes gate work.
        // 3.5 + one 6-tick confirm window at ~5 bps keeps every walking send ahead of
        // the bot. The HELD phase (stationary burst) keeps the full server-reach clamp
        // - its pendings resolve in place. Reads the same forward-key state the
        // movement governor drives, so it cannot disagree with the hold.
        double sendClamp = mc.options.forwardKey.isPressed() ? 3.5 : Math.min(mod.interactionRange.get(), 4.4);
        // PICK-BUDGET FLOOR (user, setting 3 skips): each pick that is examined but
        // not sent (walking-clamp rejects, vanilla-sticky skips, split-busy/vanilla
        // routing, tool-missing) burns one of the maxSends*6 tries WITHOUT counting
        // toward the send cap. At low settings 6*N shrank that headroom too (18 at
        // 3 vs 24 at 4) and dense clusters could exhaust the picks before finding
        // the sendable candidates - a floor of 24 keeps search headroom constant.
        for (int pick = 0; pick < Math.max(24, maxSends * 6) && sent < maxSends; pick++) {
            BlockPos breakTarget;
            if (pick == 0 && mod.nextBlockPos != null && !attempted.contains(mod.nextBlockPos)) {
                breakTarget = mod.nextBlockPos;
            } else if (fullSweep) {
                breakTarget = mod.slave.scan.findNearestPrintBoxBlock(attempted);
            } else {
                breakTarget = getNextBatchBreakPos(attempted);
            }
            if (breakTarget == null) break;                       // no more distinct candidates
            attempted.add(breakTarget);
            // NEAREST-FIRST + SERVER-REACH (user rule): pickers are distance-sorted
            // (nearest land first, farthest still sent when in range). Clamp the send
            // range to the SERVER's survival reach (~4.5) - client range 5.0 sends at
            // 4.5-5.0 get silently rejected, which starved the rate and caused the
            // rare stuck (block 'trying to break but nothing happens').
            if (mc.player.getEyePos().distanceTo(breakTarget.toCenterPos()) > sendClamp) continue;
            if (breakPending.containsKey(breakTarget)) continue;  // awaiting server confirm - next distinct
            boolean needsVanilla = needsVanillaBreak.contains(breakTarget);
            if (needsVanilla) {
                if (vanillaSentThisTick) continue;                 // one vanilla send per tick
                if (vanillaStickyTarget != null && !breakTarget.equals(vanillaStickyTarget)) {
                    continue;                                      // stick to the current vanilla target
                }
            }
            mod.nextBlockPos = breakTarget;
            // BEST-TOOL AUTO-SWITCH (user rule, live 2026-10-20: oak/spruce planks,
            // crimson/warped hyphae in the art need an AXE — with the pickaxe they
            // take ~60 ticks/block). The server computes break time from the HELD item.
            if (!ensureBestToolFor(breakTarget)) continue;        // no usable tool - try next candidate
            if (!sendHybridBreak(breakTarget)) continue;          // split session busy / vanilla-routed - no send slot used
            if (needsVanilla) {
                vanillaStickyTarget = breakTarget;
                vanillaSentThisTick = true;
            }
            sent++;
            breakSentPerSec++;
        }
        if (sent > 0) breakSweepActedThisTick = true;
        return sent;
    }

    /** SLOT SYNC — tell the server which hotbar slot we hold BEFORE any raw pair.
     *  Vanilla 1.21.11 (bytecode-verified): PlayerInventory.setSelectedSlot is a
     *  client-only field write; the server learns the held slot ONLY from
     *  UpdateSelectedSlotC2SPacket, sent by ClientPlayerInteractionManager's
     *  syncSelectedSlot — which runs per-tick (one tick late for us) and before
     *  vanilla interaction-manager actions, neither of which our raw fastBreak
     *  path touches. The server computes every break delta from ITS believed held
     *  stack, so a pair sent right after a slot flip was validated with the old
     *  tool and silently rejected (the wood/stone handover stragglers, and the
     *  print-material handover at pass start). Send-if-changed only: steady state
     *  costs zero packets, and vanilla's own per-tick sync may duplicate one
     *  harmless same-value packet after our flip. */
    void syncSelectedSlotNow() {
        int sel = mc.player.getInventory().getSelectedSlot();
        if (sel != lastSyncedSelectedSlot) {
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(sel));
            lastSyncedSelectedSlot = sel;
        }
    }

    /** Server-side per-tick break progress for the CURRENT held stack — the exact
     *  mirror of what ServerPlayerInteractionManager computes (verified against the
     *  1.21.11 bytecode: speed = held multiplier + efficiency attribute if >1, x
     *  haste, x fatigue, x block_break_speed, x0.2 submerged, /5 airborne; delta =
     *  speed / hardness / (canHarvest ? 30 : 100)). Using the client player's own
     *  getBlockBreakingSpeed/canHarvest keeps enchant/haste/water/onGround parity
     *  without porting the formula. */
    float serverBreakDelta(BlockState state, BlockPos pos) {
        if (state.isAir()) return 1f;   // race: already gone — the pair is a harmless no-op
        float hardness = state.getHardness(mc.world, pos);
        if (hardness < 0) return 0f;   // unbreakable — never pair/split-able
        return mc.player.getBlockBreakingSpeed(state) / hardness
            / (mc.player.canHarvest(state) ? 30f : 100f);
    }

    /** DELTA-ROUTED BREAK SEND — the ONE copy of the break rules. The server's STOP
     *  validation is delta x (elapsedServerTicks + 1) >= 0.7f (bytecode constant), so:
     *  delta >= 0.7 -> the classic same-tick pair (START+STOP+ABORT) breaks it now;
     *  0.7 > delta >= ~0.09 -> SPLIT PAIR: START now, STOP after ceil(0.7/delta)-1
     *  ticks so the elapsed term carries it over the gate (all real palette blocks
     *  with a proper tool land here at exactly 1 extra tick: concrete 0.65, wood
     *  0.58 with an eff5 netherite tool — these could NEVER break from a same-tick
     *  pair, which was the whole multi-attempt ladder: pair -> 10t -> resend pair
     *  (deterministically futile) -> 10t -> vanilla);
     *  smaller delta (wrong/missing tool) -> the vanilla ladder owns it.
     *  A new START aborts any live session (server rule), so ONE split session at a
     *  time; its STOP is flushed by tickBreakConfirmations BEFORE any new sends.
     *  @return true when packets were actually sent (consumes a send slot). */
    boolean sendHybridBreak(BlockPos target) {
        if (needsVanillaBreak.contains(target)) {
            // Vanilla progress speed = HELD tool speed. The old forced slot-8 pickaxe
            // put WOOD blocks at ~60 ticks/block - the 15s-per-straggler gate drains.
            if (!ensureBestToolFor(target)) mc.player.getInventory().setSelectedSlot(8);
            BlockUtils.breakBlock(target, true);
            return true;
        }
        syncSelectedSlotNow();
        BlockState state = mod.miningBlockState(target);
        float delta = serverBreakDelta(state, target);
        if (delta >= 0.7f) {
            ((IClientPlayerInteractionManager) mc.interactionManager).fastBreak(target);
            // THM SPEEDMINE PACKET RECIPE (decompiled from THM-Addons-1.21.11, live
            // 2026-10-21): a trailing ABORT_DESTROY after the START/STOP pair resets
            // the server's breaking-state machine, so the NEXT block's pair validates
            // against clean state - that addon breaks many blocks per tick at range 5
            // this way. Without it the server holds stale bookkeeping and rejects
            // later pairs (our ~1-2/tick acceptance ceiling + the 4-5-attempt blocks).
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, target, Direction.UP));
            mc.player.swingHand(Hand.MAIN_HAND);
            // 10-tick window: with the ABORT recipe the server accepts ~every pair
            // (live 05:56 session: 27/28 confirmed), so an unconfirmed position is
            // almost always Folia's block-update echo STILL IN FLIGHT, not a reject -
            // the 6-tick window re-sent blocks that had already broken (the visible
            // multi-swing on instaminables, and batch 6's lone blocks=1 straggler).
            breakPending.put(target, 10);
            return true;
        }
        int extraTicks = (int)Math.ceil(0.7f / delta) - 1;
        if (extraTicks > 7) {
            // No proper tool in reach of this block (e.g. wood with only a pickaxe):
            // no legal split window clears the gate — the vanilla ladder (which can
            // fetch/swap tools) owns it. Bookkeeping only; nothing sent this tick.
            needsVanillaBreak.add(target);
            return false;
        }
        if (splitPendingPos != null) return false;   // a session is live — next tick
        if (mod.debugPrints.get()) {
            mod.info(String.format("§3[dbg] SPLIT pair %s (delta=%.2f, STOP in %d tick%s)",
                target.toShortString(), delta, extraTicks, extraTicks == 1 ? "" : "s"));
        }
        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, target, Direction.UP));
        mc.player.swingHand(Hand.MAIN_HAND);
        splitPendingPos = target;
        splitStopDueTick = tickIndex + extraTicks;
        // In the pickers' skip set while in flight; the window also covers the STOP
        // wait (flush re-arms it), and the expiry ladder remains the safety net.
        breakPending.put(target, 10 + extraTicks);
        return true;
    }

    boolean ensureBestToolFor(BlockPos target) {
        BlockState state = mod.miningBlockState(target);
        ItemStack best = mod.toolSet.isEmpty() ? null : ToolUtils.getBestTool(mod.toolSet, state);
        ItemStack reserved = mc.player.getInventory().getStack(8);
        if (best == null || (reserved.isIn(ItemTags.PICKAXES)
            && new ItemStack(best.getItem()).getMiningSpeedMultiplier(state) <= reserved.getMiningSpeedMultiplier(state))) {
            best = reserved;
        }
        Item bestItem = best.getItem();
        if (mc.player.getMainHandStack().getItem().equals(bestItem)) return true;
        // Whole hotbar 0-8: the break-phase tool slots (7=axe staging, 8=pickaxe) sit
        // OUTSIDE availableHotBarSlots by design - a staging-only scan never saw the
        // staged axe and fell back to the pickaxe on wood blocks.
        for (int slot = 0; slot <= 8; slot++) {
            if (!mc.player.getInventory().getStack(slot).isEmpty()
                && mc.player.getInventory().getStack(slot).getItem().equals(bestItem)) {
                mc.player.getInventory().setSelectedSlot(slot);
                return true;
            }
        }
        for (int slot = 9; slot < 36; slot++) {
            if (!mc.player.getInventory().getStack(slot).isEmpty()
                && mc.player.getInventory().getStack(slot).getItem().equals(bestItem)) {
                Utils.performSwap(slot, mc.player.getInventory().getSelectedSlot());
                return true;
            }
        }
        if (reserved.isIn(ItemTags.PICKAXES)) {
            mc.player.getInventory().setSelectedSlot(8);
            return true;
        }
        return false;
    }

    int tryPlacingBlock() {
        if (mod.currentBuildPlan == null) return -1;
        Item material;
        Block planned = mod.currentBuildPlan.get(mod.nextBlockPos);
        if (planned == null) return -1;
        material = planned.asItem();
        // PLACEMENT DIAGNOSTIC: log every batch placement with its lane (x), layer (y,
        // 99-102) and z relative to the bot — positive dz = AHEAD (south).
        if (mod.debugPrints.get()) {
            double dz = mod.nextBlockPos.getZ() - mc.player.getZ();
            mod.info(String.format("§3[dbg] PLACE %s at x=%d y=%d z=%d (bot z=%.1f, dz=%+.1f %s)",
                material.getName().getString(), mod.nextBlockPos.getX(), mod.nextBlockPos.getY(),
                mod.nextBlockPos.getZ(), mc.player.getZ(), dz,
                dz > 0 ? "§cAHEAD" : "§abehind"));
        }
        //mod.info("Placing " + material.getName().getString() + " at: " + relativePos.toShortString());
        //Check hot-bar slots
        for (int slot : mod.availableHotBarSlots) {
            if (mc.player.getInventory().getStack(slot).isEmpty()) continue;
            Item foundMaterial = mc.player.getInventory().getStack(slot).getItem();
            if (foundMaterial.equals(material)) {
                sendAirPlace(slot, mod.nextBlockPos);
                if (material == lastSwappedMaterial) lastSwappedMaterial = null;
                return 1;
            }
        }
        for (int slot : mod.availableSlots) {
            if (mc.player.getInventory().getStack(slot).isEmpty() || mod.availableHotBarSlots.contains(slot)) continue;
            Item foundMaterial = mc.player.getInventory().getStack(slot).getItem();
            if (foundMaterial.equals(material)) {
                lastSwappedMaterial = material;
                toBeSwappedSlot = slot;
                Utils.setForwardPressed(false);
                mod.timeoutTicks = mod.preSwapDelay.get();
                return 0;
            }
        }
        if (lastSwappedMaterial == material) return 0;    //Wait for swapped material
        // ECHO-GRACE before the emergency trip (live 11:04:41, batch 20): the last
        // stack of a material can be consumed by a send whose confirmation echo is
        // still in flight - the position re-demands while the client still reads
        // air, the "missing" material trips a FULL north-corridor supply run, and
        // the post-restock re-place bounces off the block that stood all along
        // (Brown Concrete deficit 1; sends at 11:04:40 success, :41 phantom-miss,
        // 11:05:12 bounce). Arm a 2s grace: keep placing other materials; a real
        // stockout trips 40 ticks later, a phantom one never re-demands.
        if (mod.slave.restockCooldown == 0) {
            mod.slave.restockCooldown = 40;
            return 0;
        }
        // Mid-print supply run: refill the missing material via the north checkpoint
        mod.slave.routes.pauseForRestockTrip(false);
        return -1;
    }

    /** Shared air-place core (litematica-style): a BlockHitResult on the target cell
     *  itself (inside-block hit = true) works through walls because we don't rely on
     *  a visible neighbor face — soft anticheats (6b6t) accept it without rotation.
     *  interactBlock also syncs the held slot server-side (handler-coords law). */
    void sendAirPlace(int hotbarSlot, BlockPos pos) {
        mc.player.getInventory().setSelectedSlot(hotbarSlot);
        BlockHitResult hitResult = new BlockHitResult(pos.toCenterPos(), Direction.UP, pos, true);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    /** Plan-independent placement of a CARRIED item into an arbitrary empty cell,
     *  used only by the sub-floor drop recovery (exit step + exact floor restore).
     *  Hotbar-first; a main-inventory stack is swapped into the selected slot and
     *  placed on the caller's next call. Returns true when a place was sent. */
    boolean placeCarriedItem(Item item, BlockPos pos) {
        if (item == null || mc.player == null) return false;
        if (placementCooldowns.getOrDefault(pos, 0) > 0) return false;
        for (int slot = 0; slot <= 8; slot++) {
            if (mc.player.getInventory().getStack(slot).isEmpty()) continue;
            if (mc.player.getInventory().getStack(slot).getItem().equals(item)) {
                sendAirPlace(slot, pos);
                placementCooldowns.put(pos, 4);
                return true;
            }
        }
        for (int slot = 9; slot < 36; slot++) {
            if (mc.player.getInventory().getStack(slot).isEmpty()) continue;
            if (mc.player.getInventory().getStack(slot).getItem().equals(item)) {
                Utils.performSwap(slot, mc.player.getInventory().getSelectedSlot());
                return false;   // swap lands next inventory window; caller re-tries
            }
        }
        return false;
    }

    BlockPos getNextBatchPlacementPos() {
        return getNextBatchPlacementPos(Collections.emptySet());
    }

    BlockPos getNextBatchBreakPos() {
        return getNextBatchBreakPos(Collections.emptySet());
    }

    void tickBreakConfirmations() {
        breakSweepActedThisTick = false;
        tickIndex++;
        // SPLIT-PAIR STOP FLUSH — must run BEFORE any new sends this tick (a new
        // START aborts a live session, server rule): at STOP time the elapsed term
        // has accumulated ceil(0.7/delta) server ticks, so f = delta x elapsed >= 0.7
        // validates by construction. Same trailing ABORT + pending window as a pair.
        if (splitPendingPos != null && tickIndex >= splitStopDueTick) {
            BlockPos splitPos = splitPendingPos;
            splitPendingPos = null;
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, splitPos, Direction.UP));
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, splitPos, Direction.UP));
            mc.player.swingHand(Hand.MAIN_HAND);
            breakPending.put(splitPos, 10);
            breakSentPerSec++;
        }
        if (++rateTick >= 20) {
            rateTick = 0;
            if (breakSentPerSec > 0 || breakConfirmedPerSec > 0 || placeSentPerSec > 0) {
                mod.info("§d[RATE]§7 break §f" + breakSentPerSec + "/s sent §a" + breakConfirmedPerSec
                    + "/s confirmed§7 | place §f" + placeSentPerSec + "/s sent§7");
            }
            breakSentPerSec = 0; breakConfirmedPerSec = 0; placeSentPerSec = 0;
        }
        if (!breakPending.isEmpty()) {
            int confirmedThisTick = 0, expiredThisTick = 0;
            Iterator<BlockPos> pendingIt = breakPending.keySet().iterator();
            while (pendingIt.hasNext()) {
                BlockPos pending = pendingIt.next();
                int ticksLeft = breakPending.get(pending) - 1;
                if (mod.miningBlockState(pending).isAir()) {
                    pendingIt.remove();
                    confirmedThisTick++;
                    vanillaBreakRetries.remove(pending);
                } else if (ticksLeft <= 0) {
                    // FIRST expiry of an instamine-class send is usually a dropped or
                    // ceiling-rejected pair (the server accepts ~1-2 same-tick
                    // instamines - live RATE: 20-36/s sent, 1-16/s confirmed), and an
                    // unconfirmed instamine has NO server progress to reset: ONE clean
                    // resend clears most stragglers with zero stops. Only a second
                    // expiry routes to multi-tick vanilla (batch 4 ended blocks=6, all
                    // vanillaQ, 19s of gate round trips - user: 100% in one pass).
                    int expiryCount = vanillaBreakRetries.getOrDefault(pending, 0);
                    // BREAK-RETRY TICK WINDOW (break-retry-ticks): opens at the FIRST
                    // expiry; past the budget the clean fastBreak re-send is off the
                    // table and the block falls to vanilla (in reach) or the sweep —
                    // never a skip, just the end of the in-pass fast-retry budget.
                    Integer chaseStart = breakRetryStart.putIfAbsent(pending, tickIndex);
                    boolean chaseWithinBudget = tickIndex - (chaseStart == null ? tickIndex : chaseStart) < mod.breakRetryTicks.get();
                    if (expiryCount == 0 && chaseWithinBudget
                        && mc.player.getEyePos().distanceTo(pending.toCenterPos()) <= Math.min(mod.interactionRange.get(), 4.4)
                        && ensureBestToolFor(pending)) {
                        // the ONE break-rules copy (pair / split / vanilla routing)
                        if (sendHybridBreak(pending)) {
                            vanillaBreakRetries.put(pending, 1);
                        } else {
                            breakPending.put(pending, 2);   // split session was busy — retry shortly
                        }
                        continue;
                    }
                    // fastBreak failed twice — multi-tick block. Vanilla progress REQUIRES the
                    // block in server reach: at speed-hack pace the bot outruns the echo
                    // and expired blocks behind it could never progress (live 2026-10-21:
                    // 51 blocks frozen in vanillaQ). Still-in-reach -> vanilla; behind
                    // the bot -> drop it, the breakAllDone gate chases it on foot.
                    if (mc.player.getEyePos().distanceTo(pending.toCenterPos()) <= 4.4) {
                        needsVanillaBreak.add(pending);
                    }
                    int retries = expiryCount + 1;
                    vanillaBreakRetries.put(pending, retries);
                    if (retries == 4) {
                        mod.warning("§6[RESET-VERIFY]§c PERSISTENT REJECTION: " + pending.toShortString()
                            + " expired into vanilla fallback " + retries
                            + " times without ever being server-confirmed - the fastBreak/vanilla "
                            + "cycle is not making progress on this block.");
                    }
                    expiredThisTick++;
                    pendingIt.remove();
                } else {
                    breakPending.put(pending, ticksLeft);
                }
            }
            breakConfirmedPerSec += confirmedThisTick;
            if (mod.debugPrints.get() && (confirmedThisTick + expiredThisTick) > 0) {
                mod.info("§3[dbg] break-confirmation: §aserver-confirmed=" + confirmedThisTick
                    + "§3 expired-to-vanilla=§e" + expiredThisTick
                    + "§3 inFlight=" + breakPending.size() + " vanillaQ=" + needsVanillaBreak.size());
            }
        }
        // Clean vanilla-fallback set when blocks break
        if (!needsVanillaBreak.isEmpty()) {
            needsVanillaBreak.removeIf(pos -> mod.miningBlockState(pos).isAir());
        }
    }

    /** ROLLING HOTBAR LOOKAHEAD — Belady WITH stack arithmetic (user rule
     *  2026-10-21: "if we need two stacks of the same block, stage two stacks").
     *  Once per walking second, over the plan's z-window ahead [botZ-2, botZ+24]:
     *  per material, DEMAND = still-AIR window blocks and SUPPLY = blocks currently
     *  staged in the hotbar. AIR is the to-place set — the same world semantics as
     *  the placement picker; counting non-air instead counted already-placed work,
     *  so future materials never entered demand, finished ones looked most-urgent,
     *  and future residents were the preferred eviction victims (the anti-lookahead,
     *  live 2026-10-21). A material is missing when supply < demand — a 3-block
     *  remainder is not supply for a 70-block demand, and one 64-stack is not supply
     *  for a 130-block window. Missing materials stage most-urgent-first (smallest
     *  first-use z), the largest carried stack per swap. Victim choice is
     *  pickHotbarVictim — the ONE eviction authority, shared with swapIntoHotbar, so
     *  a use-time bring can never un-do what the roller staged. */
    void rollingHotbarLookahead() {
        WindowDemand wd = computeWindowDemand();
        ArrayList<Item> bringOrder = new ArrayList<>();
        ArrayList<Integer> bringZs = new ArrayList<>();
        for (Map.Entry<Item, Integer> fe : wd.firstUseZ.entrySet()) {
            if (wd.supply.getOrDefault(fe.getKey(), 0) < wd.demand.get(fe.getKey())) {
                bringOrder.add(fe.getKey());
                bringZs.add(fe.getValue());
            }
        }
        for (int a = 0; a < bringOrder.size(); a++) {
            for (int b = a + 1; b < bringOrder.size(); b++) {
                if (bringZs.get(b) < bringZs.get(a)) {
                    Item tI = bringOrder.get(a); bringOrder.set(a, bringOrder.get(b)); bringOrder.set(b, tI);
                    Integer tZ = bringZs.get(a); bringZs.set(a, bringZs.get(b)); bringZs.set(b, tZ);
                }
            }
        }
        int swapsDone = 0;
        for (int bi = 0; bi < bringOrder.size() && swapsDone < 8; bi++) {
            Item inItem = bringOrder.get(bi);
            int inZ = bringZs.get(bi);
            // Largest carried stack first: one swap moves the most supply.
            int srcSlot = -1;
            int srcCount = 0;
            for (int is = 9; is < 36; is++) {
                ItemStack iStack = mc.player.getInventory().getStack(is);
                if (!iStack.isEmpty() && iStack.getItem().equals(inItem) && iStack.getCount() > srcCount) {
                    srcCount = iStack.getCount();
                    srcSlot = is;
                }
            }
            if (srcSlot == -1) continue;   // not carried — the mid-print supply trip owns it
            int evictSlot = pickHotbarVictim(wd, inItem, srcCount, false);
            if (evictSlot == -1) break;    // every resident is load-bearing for the window
            ItemStack evictedStack = mc.player.getInventory().getStack(evictSlot);
            Item evictedItem = evictedStack.isEmpty() ? null : evictedStack.getItem();
            if (evictedItem != null) {
                Integer vz = wd.firstUseZ.get(evictedItem);
                if (vz != null && vz < inZ) break;   // Belady: nothing resident is farther-future than the bring-in
            }
            int evictedCount = evictedStack.getCount();
            Utils.performSwap(srcSlot, evictSlot);
            wd.supply.merge(inItem, srcCount, Integer::sum);
            if (evictedItem != null) wd.supply.merge(evictedItem, -evictedCount, Integer::sum);
            swapsDone++;
            mod.info("§7Hotbar rolling: §f" + inItem.getName().getString() + "§7 staged (slot " + evictSlot
                + ", window need " + wd.demand.get(inItem) + " / staged " + wd.supply.get(inItem)
                + ", first use ~" + (inZ - wd.botZ) + " rows ahead).");
        }
    }

    /** One lookahead snapshot: per-material window demand and first upcoming use
     *  over the still-air plan blocks ahead, plus the hotbar's staged supply. The
     *  staging passes mutate supply so it stays true across several swaps. */
    static final class WindowDemand {
        final HashMap<Item, Integer> firstUseZ = new HashMap<>();
        final HashMap<Item, Integer> demand = new HashMap<>();
        final HashMap<Item, Integer> supply = new HashMap<>();
        int botZ;
    }

    WindowDemand computeWindowDemand() {
        WindowDemand wd = new WindowDemand();
        wd.botZ = mc.player.getBlockZ();
        for (Map.Entry<BlockPos, Block> ue : mod.currentBuildPlan.entrySet()) {
            BlockPos up = ue.getKey();
            int uz = up.getZ();
            if (uz < wd.botZ - 2 || uz > wd.botZ + 24) continue;   // the walking window
            if (!mod.miningBlockState(up).isAir()) continue;       // placed/obstructed = not demand
            Item uItem = ue.getValue().asItem();
            wd.firstUseZ.merge(uItem, uz, Math::min);
            wd.demand.merge(uItem, 1, Integer::sum);
        }
        for (int hs : mod.availableHotBarSlots) {
            ItemStack hStack = mc.player.getInventory().getStack(hs);
            if (!hStack.isEmpty()) wd.supply.merge(hStack.getItem(), hStack.getCount(), Integer::sum);
        }
        return wd;
    }

    /** THE eviction authority for hotbar staging — the rolling lookahead and the
     *  use-time swapIntoHotbar share this one rule (the heuristic swapIntoHotbar
     *  used instead was dead code: forEachMapBlock passed a null desired block, so
     *  every distance stayed "never used", and its hotbar-frequency priority
     *  evicted double-staged materials first — the opposite of the staging rule).
     *  Victim preference: (1) a same-item resident smaller than the bring-in — a
     *  top-up trade that costs no other material its slot; (2) an empty staging
     *  slot; (3) Belady — the non-load-bearing resident with the farthest first
     *  upcoming window use (a resident is load-bearing when the rest of the hotbar
     *  no longer covers its own window demand without that stack). force=true
     *  (use-time bring: the material is needed NOW) drops the load-bearing
     *  protection — farthest-first still — and the roller re-stages the victim
     *  from main inventory within a second. Returns -1 when force=false and every
     *  resident is load-bearing. */
    int pickHotbarVictim(WindowDemand wd, Item inItem, int srcCount, boolean force) {
        int topUpSlot = -1;
        int emptySlot = -1;
        int evictSlot = -1;
        int worstZ = Integer.MIN_VALUE;
        for (int hs : mod.availableHotBarSlots) {
            ItemStack eStack = mc.player.getInventory().getStack(hs);
            if (eStack.isEmpty()) {
                if (emptySlot == -1) emptySlot = hs;
                continue;
            }
            Item eItem = eStack.getItem();
            if (eItem.equals(inItem)) {
                if (srcCount > eStack.getCount() && topUpSlot == -1) topUpSlot = hs;
                continue;
            }
            if (!force && wd.supply.getOrDefault(eItem, 0) - eStack.getCount() < wd.demand.getOrDefault(eItem, 0)) continue;
            Integer eZ = wd.firstUseZ.get(eItem);
            int eDist = (eZ == null) ? Integer.MAX_VALUE : eZ;
            if (eDist > worstZ) { worstZ = eDist; evictSlot = hs; }
        }
        if (topUpSlot != -1) return topUpSlot;
        if (emptySlot != -1) return emptySlot;
        return evictSlot;
    }

    void pumpArmedBreakTarget() {
        if (!mod.state.equals(State.Mining) || mod.nextBlockPos == null) return;
        if (mod.miningBlockState(mod.nextBlockPos).isAir()) {
            mod.nextBlockPos = null;
            mod.state = State.Mining;
        } else {
            // Distinct-position multi-send (25-30/s target): the per-tick stop-after-
            // one pacing was the rate cap - the client cannot see confirmations
            // within the same tick. See sendDistinctBreaks.
            sendDistinctBreaks(mod.breaksPerTick.get(), mod.fullSweepActive);
        }
    }

    boolean armPermittedBreakPhase() {
        if (mod.state == State.AwaitBreakPermission2 && mod.break2Permitted) {
            mod.break2Permitted = false;
            // PHASE TOOL SLOTS: breaking needs the tools - pickaxe to 8, carried axe
            // to hotbar 7 (hotbar is 0-8; the old 'axe to 9' addressed main
            // inventory and crashed performSwap's setSelectedSlot). Both leave the
            // staging set until the next print pass. Best-first like the pickaxe.
            mod.restock.ensurePickaxeSlot8();
            mod.availableHotBarSlots.remove(Integer.valueOf(8));
            {
                int axeSlot = -1;
                int axeLife = -1;
                // Best axe regardless of durability - the pickaxe's rule: a worn tool
                // keeps swinging until it breaks; exchanges happen ONLY at batch prep.
                // (The old min-durability gate here left a worn sole axe unstaged, so
                // wood fell back to 60-tick pickaxe breaks.) 40 = offhand: the tool
                // can sit anywhere (user rule, live 06:49 duplicate-fetch).
                for (int as2 = 0; as2 <= 40; as2++) {
                    ItemStack a3 = mc.player.getInventory().getStack(as2);
                    if (a3.isEmpty() || !a3.isIn(ItemTags.AXES)) continue;
                    int life = ToolUtils.getRemainingDamage(a3);
                    if (life > axeLife) { axeLife = life; axeSlot = as2; }
                }
                if (axeSlot == 40) {
                    // Offhand source: inventory index 40 is NOT a handler slot id
                    // (that is hotbar 4) - swap offhand <-> hotbar 7 directly.
                    Utils.swapOffhand(36 + 7);
                    mod.availableHotBarSlots.remove(Integer.valueOf(7));
                } else if (axeSlot >= 9) {
                    Utils.performSwap(axeSlot, 7);
                    mod.availableHotBarSlots.remove(Integer.valueOf(7));
                } else if (axeSlot >= 0) {
                    // already in the hotbar - reserve it in place so staging never evicts it
                    mod.availableHotBarSlots.remove(Integer.valueOf(axeSlot));
                }
            }
            mod.breakStage = 2;
            mod.state = State.Mining;
            return false;
        } else if (mod.state == State.AwaitBreakPermissionAll && mod.breakAllPermitted) {
            mod.breakAllPermitted = false;
            mod.slave.routes.startBreakAll();
            return true;
        }
        return false;
    }

    boolean consumeHotbarSwap() {
        if (toBeSwappedSlot == -1) return false;
        mod.restock.swapIntoHotbar(toBeSwappedSlot);
        toBeSwappedSlot = -1;
        if (mod.postSwapDelay.get() != 0) {
            mod.timeoutTicks = mod.postSwapDelay.get();
            return true;
        }
        return false;
    }

    BlockPos getNextBatchPlacementPos(Set<BlockPos> exclude) {
        if (mod.currentBuildPlan == null) return null;
        // LANE GATE: no printing unless the bot stands centered on the phase's lane-2
        // centerline. Off-center (e.g. after a sweep left it straddling lanes), freshly
        // placed floor blocks on the west lanes can catch the bot's side and wall it in.
        if (mod.lowerMapCorner != null && Math.abs(mc.player.getX() - mod.slave.routes.currentWalkLaneX()) > 0.3) return null;
        // Litematica-style: any plan block is placed the moment it enters interaction
        // range — ahead, beside, or behind. The walking lane (2, the east borrow lane)
        // only carries y2 blocks above the head, so nothing is ever placed at feet level
        // on the bot's path; blocks intersecting its body are retried once it moves on.
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        // WALKING SEND CLAMP (mirror of the break-side lesson): a placement sent
        // while WALKING at the far edge lands its echo behind the advancing bot —
        // and when the send raced the echo (52% of burst placements need >1 send),
        // its RETRY then sits behind the bot in fading reach = the trail-off
        // misses the pass-end verify reports. Walking placements only within 3.5;
        // the HELD stationary burst keeps the full range (its echoes resolve in
        // place, and the (w) hold stands until they do).
        // Standing clamp mirrors the server's survival reach: sends beyond ~4.4 are
        // silently rejected (the break side's proven law) - at range 5 they only
        // burn attempts and trail off behind the bot. Walking clamp unchanged
        // (echo-race lesson: far-edge sends while walking land behind the bot).
        double placeClamp = mc.options.forwardKey.isPressed() ? 3.5 : Math.min(mod.interactionRange.get(), 4.4);
        for (BlockPos pos : mod.currentBuildPlan.keySet()) {
            if (!mod.miningBlockState(pos).isAir()) continue;
            if (exclude.contains(pos)) continue;
            // Skip positions on placement cooldown (litematica-style: don't hammer the
            // same failed block — let the timeout expire, then retry)
            if (placementCooldowns.getOrDefault(pos, 0) > 0) continue;
            double dist = mc.player.getEyePos().distanceTo(pos.toCenterPos());
            if (dist > placeClamp) continue;
            // SELF-COLLISION MARGIN (live hammer loop: same floor block re-sent
            // every 4-tick cooldown ~15x while parked): the server's canPlace
            // rejects solid blocks clipping an ENTITY using ITS view of the bot's
            // box, which lags/rounds differently than this client check - marginal
            // dz 0.2-0.8 feet-level blocks passed here and were server-rejected in
            // a loop. expand(0.3) (one movement tick) treats anything that CLOSE to
            // the body as walk-clear territory: it places en route once the bot has
            // moved off, or the repair pass owns it - never hammered in place.
            if (mc.player.getBoundingBox().intersects(new Box(pos).expand(0.3))) continue;
            if (dist < bestDist) {
                bestDist = dist;
                best = pos;
            }
        }
        return best;
    }

    BlockPos getNextBatchBreakPos(Set<BlockPos> exclude) {
        if (mod.currentBuildPlan == null) return null;
        boolean break2Only = mod.breakStage == 2;
        int westX = mod.lowerMapCorner.getX() + 2 * mod.currentBatch;
        // FORWARD-ONLY (user rule, live 2026-10-21): break targets AHEAD in the travel
        // direction only - the any-direction nearest picker ping-ponged the bot backward
        // mid-pass. Direction comes from the live route (break2 walks S->N, break-all
        // N->S); ambiguous/at-goal = no filter (pass-end sweeps own the stragglers).
        int dir = breakTravelDir();
        int botZ = mc.player.getBlockZ();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : mod.currentBuildPlan.keySet()) {
            if (mod.miningBlockState(pos).isAir()) continue;
            if (exclude.contains(pos)) continue;
            if (breakPending.containsKey(pos)) continue;   // awaiting server confirm - not sendable
            // TOPS ONLY (reverted 2026-10-21 per user order "we need bottom part"):
            // break(2) = the window's y2/y3 (101/102) tops only - the floors stay
            // standing through W2's suppression walk and are cleared by break-all
            // after it (the proven runner flow). The (r) full-column absorption
            // is rolled back.
            if (break2Only && (pos.getX() >= westX + 2 || pos.getY() < mod.lowerMapCorner.getY() + 2)) continue;
            // Behind = sweep territory, EXCEPT outstanding vanilla-queued blocks:
            // the retry-range guard holds the bot in their 4.4 range, and the ONE
            // sticky target must stay reachable by the picker to be worked (the
            // forward rule exists to stop ping-pong WALKING, not retry sends).
            if (dir != 0 && (pos.getZ() - botZ) * dir < -1 && !needsVanillaBreak.contains(pos)) continue;
            double dist = mc.player.getEyePos().distanceTo(pos.toCenterPos());
            if (dist > mod.interactionRange.get()) continue;
            if (dist < bestDist) { bestDist = dist; best = pos; }
        }
        return best;
    }

    /** Travel direction of the current break pass from the live route: +1 south,
     *  -1 north, 0 = at/ambiguous to the goal (callers then apply no direction filter). */
    int breakTravelDir() {
        if (mod.checkpoints.isEmpty()) return 0;
        double dz = mod.checkpoints.getFirst().getLeft().z - mc.player.getZ();
        if (dz > 0.5) return 1;
        if (dz < -0.5) return -1;
        return 0;
    }

    /** FORWARD-ONLY BREAK CADENCE — SENT-GATED verify-then-advance (user rules
     *  2026-10-21: "no skips"; then "too slow, even 5 per tick"). Holds forward
     *  movement only while an UN-SENT standing FORWARD target sits within the
     *  walking send clamp (3.5) — the invariant "nothing inside send range goes
     *  un-sent" is the no-skip guarantee. Blocks already sent (pair broken
     *  server-side same tick; split STOP flushing next tick, still well inside
     *  the server's ~5.5 reach at walk pace) need no presence and never cost
     *  travel time: no echo waiting, no full-sphere drain before each step.
     *  breaks-per-tick sets the drain rate, not the skip count. Vanilla-queued
     *  blocks are the one presence-needing class (held until air). */
    boolean breakApproachHold() {
        if (mod.breakStage == 0 || mod.currentBuildPlan == null || mod.lowerMapCorner == null) return false;
        if (mod.fullSweepActive) return false;   // RESET runs its own segment feed + gates
        if (!(mod.state.equals(State.Walking) || mod.state.equals(State.Mining))) return false;
        int dir = breakTravelDir();
        if (dir == 0) { approachHoldTicks = 0; return false; }
        boolean break2Only = mod.breakStage == 2;
        int westX = mod.lowerMapCorner.getX() + 2 * mod.currentBatch;
        int botZ = mc.player.getBlockZ();
        BlockPos nearest = null;
        double nearestH = Double.MAX_VALUE;
        boolean reachBusy = false;
        boolean vanillaBusy = false;
        double sendReach = Math.min(mod.interactionRange.get(), 4.4);
        // PENDING-AWARE DRAIN GATE (user: "it became too slow... even 5 per tick"):
        // (ah)'s first cut held until every in-reach target showed confirmed AIR -
        // waiting out the block-update echo (1-3 ticks per sphere) AND the whole
        // sphere drain before every step. But a SENT block never needs the bot
        // present anymore: pairs complete same-tick server-side, and a split's
        // STOP flushes one tick later well inside the server's ~5.5 reach even at
        // full walk pace. So movement is only gated by UN-SENT standing targets
        // within the walking send clamp (3.5, mirrors sendDistinctBreaks) - the
        // invariant "nothing within send range goes un-sent" keeps the no-skip
        // guarantee, while sent-but-unconfirmed blocks stop costing travel time.
        double walkClamp = 3.5;
        for (BlockPos pos : mod.currentBuildPlan.keySet()) {
            if ((pos.getZ() - botZ) * dir < -1) continue;   // behind - sweep territory
            // TOPS ONLY, same filter as getNextBatchBreakPos (see the revert note there).
            if (break2Only && (pos.getX() >= westX + 2 || pos.getY() < mod.lowerMapCorner.getY() + 2)) continue;
            if (mod.miningBlockState(pos).isAir()) continue;
            double dx = pos.getX() + 0.5 - mc.player.getX();
            double dz = pos.getZ() + 0.5 - mc.player.getZ();
            double hDist = Math.sqrt(dx * dx + dz * dz);
            if (hDist < nearestH) { nearestH = hDist; nearest = pos; }
            double eyeDist = mc.player.getEyePos().distanceTo(pos.toCenterPos());
            // A forward VANILLA-queued block in reach holds the bot until air:
            // multi-tick vanilla mining only completes while the bot stays in
            // server reach - the one class that genuinely needs presence.
            if (needsVanillaBreak.contains(pos) && eyeDist <= sendReach) vanillaBusy = true;
            if (breakPending.containsKey(pos)) continue;   // SENT - echo/STOP in flight - never gates movement
            if (eyeDist <= walkClamp) reachBusy = true;
        }
        // LAUNCH CLEARANCE (live "misses or never breaks south most block", batch
        // 19's -410,101,-193): at break(2) start the bot stands ~1.7 rows south of
        // the southernmost west-top row and immediately sprints north. That row is
        // BEHIND the travel direction (past the 1-row grace), so the forward-only
        // scan above - and the verify-then-advance hold built on it - never covers
        // it: this pass-start hold suppresses movement until every break(2) target
        // within server reach (this row included, no behind filter) is confirmed
        // air. The watchdog below releases a never-confirming block.
        if (break2Only && dir == -1 && botZ >= mod.lowerMapCorner.getZ() + 125) {
            boolean launchBusy = false;
            for (BlockPos pos : mod.currentBuildPlan.keySet()) {
                if (pos.getX() >= westX + 2 || pos.getY() < mod.lowerMapCorner.getY() + 2) continue;
                if (mod.miningBlockState(pos).isAir()) continue;
                if (breakPending.containsKey(pos)) continue;   // sent - breaks behind us fine
                if (mc.player.getEyePos().distanceTo(pos.toCenterPos()) <= sendReach) { launchBusy = true; break; }
            }
            if (launchBusy) {
                approachHoldTicks++;
                if (approachHoldTicks > 150) {
                    mod.warning("§eBreak(2) launch watchdog: released a " + approachHoldTicks
                        + "-tick start hold - walking on (verify retry owns the rest).");
                    approachHoldTicks = 0;
                    return false;
                }
                return true;
            }
        }
        // RETRY-RANGE GUARD - BREAK CASE, deliberately DIFFERENT from the print
        // case (user rule 2026-10-21: "never walk past a block until server
        // confirm - even if the bot moves front, it stops at max interaction
        // range so a rejected break can still retry"): break pendings are
        // TRANSIENT (a pair resolves same-tick server-side, the pending window
        // is 10 ticks, expiry is reach-gated, vanilla sticky auto-clears beyond
        // reach) and direction-IGNORED by design - they trail behind/side while
        // the echo is in flight, so the any-direction rule STAYS. What the guard
        // adds here is the server-reach UPPER BOUND: beyond sendReach the
        // expiry-resend already drops the block to the pass gate/sweep and the
        // vanilla sticky already cleared it, so a pending out there can never be
        // retried - pinning on it could only crawl (the print-side 12:43 lesson).
        // The 0.6 margin = one tick of movement, so the bot CREEPS to the edge
        // instead of overshooting it. The picker's behind-filter exempts
        // vanilla-queued blocks so the held bot can still work them.
        double retryEdge = Math.min(mod.interactionRange.get(), 4.4) - 0.6;
        boolean pendingEdge = false;
        for (BlockPos pos : breakPending.keySet()) {
            double dist = mc.player.getEyePos().distanceTo(pos.toCenterPos());
            if (dist >= retryEdge && dist < sendReach) { pendingEdge = true; break; }
        }
        if (!pendingEdge) {
            for (BlockPos pos : needsVanillaBreak) {
                double dist = mc.player.getEyePos().distanceTo(pos.toCenterPos());
                if (dist >= retryEdge && dist < sendReach) { pendingEdge = true; break; }
            }
        }
        // VERIFY-THEN-ADVANCE: any standing forward target within server reach holds
        // the bot (see the method comment). vanillaBusy keeps its any-distance rule
        // for in-reach vanilla-queued blocks (multi-tick progress needs the bot in
        // server reach for the whole break).
        boolean mustDrain = reachBusy || vanillaBusy || pendingEdge;
        if (!mustDrain) {
            approachHoldTicks = 0;   // clean ahead / still approaching / sphere verified
            return false;
        }
        approachHoldTicks++;
        if (approachHoldTicks > 150) {
            mod.warning("§eBreak approach watchdog: released a " + approachHoldTicks
                + "-tick hold near " + nearest.toShortString() + " - walking on (sweep will recheck).");
            approachHoldTicks = 0;
            return false;
        }
        return true;
    }

}
