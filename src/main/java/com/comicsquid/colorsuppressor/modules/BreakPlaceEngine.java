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
    HashMap<BlockPos, Integer> placementAttempts;                // per-position placement attempts (adaptive-pace cap)
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

    boolean adaptivePaceHold() {
        if (mod.state.equals(State.Walking) && mod.breakStage == 0 && mod.currentBuildPlan != null
            && !mod.checkpoints.isEmpty()
            && StepCheckered.ALLOW_PLACE_ACTIONS.contains(mod.checkpoints.getFirst().getRight().getLeft())) {
            BlockPos placeNow = getNextBatchPlacementPos();
            if (placeNow != null && placementAttempts.getOrDefault(placeNow, 0) < 5) {
                Block planned = mod.currentBuildPlan.get(placeNow);
                if (planned != null && mod.restock.isMaterialCarried(planned.asItem())
                    && printFrontierWithinGap()) {
                    placeHoldTicks++;
                    if (placeHoldTicks > 100) {
                        placementAttempts.put(placeNow, 5);   // force-release: treat as capped
                        mod.warning("§ePrint pace-hold watchdog: released a " + placeHoldTicks
                            + "-tick hold on " + placeNow.toShortString() + " - walking on.");
                        placeHoldTicks = 0;
                        return false;
                    }
                    return true;
                }
            }
            placeHoldTicks = 0;
        } else {
            placeHoldTicks = 0;
        }
        return false;
    }

    /** PRINT FORWARD GAP (user rule 2026-10-21: "reduce the print forward gap to 1
     *  or max 2 blocks so the bot can burst more"): the pace hold engages only once
     *  the bot has walked UP TO the work — nearest still-unplaced FORWARD plan block
     *  within 2 horizontal blocks. The old hold fired at first contact (interaction
     *  range 5 ≈ 4+ horizontal ahead), stopping the bot 2-3 blocks short with only
     *  the front hemisphere of the reach printable per stop; walking in first and
     *  bursting from close range covers nearly the whole reach sphere per stop, and
     *  blocks that fall behind print en route anyway (the picker is any-direction).
     *  Direction from the live route like the break cadence; 0 = at-goal (pass-end
     *  drain holds until the sphere is dry). Attempts-capped and not-carried
     *  positions never pin the bot — standing on them wastes the stop. */
    boolean printFrontierWithinGap() {
        int dir = breakTravelDir();
        int botZ = mc.player.getBlockZ();
        BlockPos nearest = null;
        double nearestH = Double.MAX_VALUE;
        for (BlockPos pos : mod.currentBuildPlan.keySet()) {
            if (!mod.miningBlockState(pos).isAir()) continue;           // placed = done
            if (placementAttempts.getOrDefault(pos, 0) >= 5) continue;  // force-released - repair owns it
            if (dir != 0 && (pos.getZ() - botZ) * dir < -1) continue;   // behind - prints en route
            double dx = pos.getX() + 0.5 - mc.player.getX();
            double dz = pos.getZ() + 0.5 - mc.player.getZ();
            double hDist = Math.sqrt(dx * dx + dz * dz);
            if (hDist < nearestH) { nearestH = hDist; nearest = pos; }
        }
        if (nearest == null) return false;
        Block planned = mod.currentBuildPlan.get(nearest);
        return planned != null && mod.restock.isMaterialCarried(planned.asItem()) && nearestH <= 2.0;
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
        for (int pick = 0; pick < maxSends * 6 && sent < maxSends; pick++) {
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
                // Direct placement via interaction manager (litematica-style air-place):
                // construct a BlockHitResult on the target position itself (inside-block
                // hit = true). This works through walls because we don't rely on a visible
                // neighbor face — soft anticheats (6b6t) accept this without rotation.
                mc.player.getInventory().setSelectedSlot(slot);
                BlockHitResult hitResult = new BlockHitResult(
                    mod.nextBlockPos.toCenterPos(), Direction.UP, mod.nextBlockPos, true);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
                mc.player.swingHand(Hand.MAIN_HAND);
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
        // Mid-print supply run: refill the missing material via the north checkpoint
        mod.slave.routes.pauseForRestockTrip(false);
        return -1;
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
                    if (expiryCount == 0
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
        for (BlockPos pos : mod.currentBuildPlan.keySet()) {
            if (!mod.miningBlockState(pos).isAir()) continue;
            if (exclude.contains(pos)) continue;
            // Skip positions on placement cooldown (litematica-style: don't hammer the
            // same failed block — let the timeout expire, then retry)
            if (placementCooldowns.getOrDefault(pos, 0) > 0) continue;
            double dist = mc.player.getEyePos().distanceTo(pos.toCenterPos());
            if (dist > mod.interactionRange.get()) continue;
            if (mc.player.getBoundingBox().intersects(new Box(pos))) continue;
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
            // BREAK(2) ABSORBS THE WEST LANES - FULL columns, tops AND floors
            // (optimization 2026-10-21): break(2) is gated on W1's completion, and the
            // only walk active during it is W2 on lane 2k+129, from which the west
            // lanes sit at index-distance 128-129 - past the dominant-record edge, so
            // the recorded west art can never be re-recorded regardless of timing.
            // Clearing the west half here moves its break volume out of the post-W2
            // serial tail into break(2)'s shadowed window; break-all air-skips what
            // is already gone and is left with the east half only (the east lanes
            // still gate on W2-complete - their borrow tops are dominant-class and
            // re-recordable from W2's lane).
            if (break2Only && pos.getX() >= westX + 2) continue;
            if (dir != 0 && (pos.getZ() - botZ) * dir < -1) continue;   // behind us (1-row grace for the standing row)
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

    /** FORWARD-ONLY BREAK CADENCE (user rule, live 2026-10-21: "go close to the next
     *  batch of blocks - closest ~1 block, not 2-3 far - break ALL in reach, verify,
     *  then move on, no skips"). Holds forward movement once the bot is ~1 row from
     *  the nearest still-standing FORWARD target, and releases only when every forward
     *  target within the server-reach clamp is confirmed air. The old continuous walk
     *  broke from 2-3 blocks out and strode past blocks whose confirms lagged - the
     *  skips the straggler sweeps then had to chase. Sends still fire while approaching
     *  (walking TOWARD targets never outruns their confirms), so each stop arrives
     *  already half-cleared; blocks beyond the 4.4 server-reach clamp are simply the
     *  next cluster after one more step. */
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
        for (BlockPos pos : mod.currentBuildPlan.keySet()) {
            if ((pos.getZ() - botZ) * dir < -1) continue;   // behind - sweep territory
            // BREAK(2) absorbs the west lanes' full columns (see getNextBatchBreakPos).
            if (break2Only && pos.getX() >= westX + 2) continue;
            if (mod.miningBlockState(pos).isAir()) continue;
            double dx = pos.getX() + 0.5 - mc.player.getX();
            double dz = pos.getZ() + 0.5 - mc.player.getZ();
            double hDist = Math.sqrt(dx * dx + dz * dz);
            if (hDist < nearestH) { nearestH = hDist; nearest = pos; }
            if (mc.player.getEyePos().distanceTo(pos.toCenterPos()) <= sendReach) reachBusy = true;
            // A forward VANILLA-queued block in reach holds the bot AT ANY distance:
            // multi-tick vanilla mining only completes while the bot stays in server
            // reach, and walking past one (the 1.3 cluster threshold never stopped
            // for side-lane stragglers) is exactly how passes ended with blocks for
            // the gate to round-trip (user: 100% cleanup in one pass).
            if (needsVanillaBreak.contains(pos)
                && mc.player.getEyePos().distanceTo(pos.toCenterPos()) <= sendReach) vanillaBusy = true;
        }
        boolean atCluster = nearest != null && nearestH <= 1.3 && reachBusy;
        if (!atCluster && !vanillaBusy) {
            approachHoldTicks = 0;   // clean ahead / still approaching / cluster verified
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
