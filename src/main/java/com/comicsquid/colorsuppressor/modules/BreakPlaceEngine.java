package com.comicsquid.colorsuppressor.modules;

import com.comicsquid.colorsuppressor.utils.ToolUtils;
import com.comicsquid.colorsuppressor.utils.Utils;
import com.comicsquid.colorsuppressor.interfaces.IClientPlayerInteractionManager;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
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
    HashMap<BlockPos, Integer> breakPending;                     // pos -> ticks until silently confirmed
    HashSet<BlockPos> needsVanillaBreak;                         // fastBreak failed -> use vanilla mining
    HashMap<BlockPos, Integer> vanillaBreakRetries;              // pos -> times expired into vanilla fallback
    BlockPos vanillaStickyTarget;                                // ONE multi-tick vanilla target at a time
    boolean breakSweepActedThisTick;                             // the break loop already sent packets this tick
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
        breakPending = new HashMap<>();
        needsVanillaBreak = new HashSet<>();
        vanillaBreakRetries = new HashMap<>();
        vanillaStickyTarget = null;
        breakSweepActedThisTick = false;
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
                if (planned != null && mod.restock.isMaterialCarried(planned.asItem())) {
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

    int sendDistinctBreaks(int maxSends, boolean fullSweep) {
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
        int sent = 0;
        boolean vanillaSentThisTick = false;
        for (int pick = 0; pick < maxSends * 6 && sent < maxSends; pick++) {
            BlockPos breakTarget;
            if (pick == 0 && mod.nextBlockPos != null && !attempted.contains(mod.nextBlockPos)) {
                breakTarget = mod.nextBlockPos;
            } else if (fullSweep) {
                breakTarget = mod.findNearestPrintBoxBlock(attempted);
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
            if (mc.player.getEyePos().distanceTo(breakTarget.toCenterPos()) > Math.min(mod.interactionRange.get(), 4.4)) continue;
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
            sendHybridBreak(breakTarget);
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

    void sendHybridBreak(BlockPos target) {
        if (needsVanillaBreak.contains(target)) {
            mc.player.getInventory().setSelectedSlot(8);
            BlockUtils.breakBlock(target, true);
        } else {
            ((IClientPlayerInteractionManager) mc.interactionManager).fastBreak(target);
            mc.player.swingHand(Hand.MAIN_HAND);
            breakPending.put(target, 6);   // 4-tick window: Folia's block-update echo can lag 2-3 ticks
        }
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
        for (int slot : mod.availableHotBarSlots) {
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
        mod.pauseForRestockTrip(false);
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
                    // fastBreak failed — multi-tick block. Vanilla progress REQUIRES the
                    // block in server reach: at speed-hack pace the bot outruns the echo
                    // and expired blocks behind it could never progress (live 2026-10-21:
                    // 51 blocks frozen in vanillaQ). Still-in-reach -> vanilla; behind
                    // the bot -> drop it, the breakAllDone gate chases it on foot.
                    if (mc.player.getEyePos().distanceTo(pending.toCenterPos()) <= 4.4) {
                        needsVanillaBreak.add(pending);
                    }
                    int retries = vanillaBreakRetries.getOrDefault(pending, 0) + 1;
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

    void rollingHotbarLookahead() {
        HashMap<Item, Integer> firstUseZ = new HashMap<>();
        int botZNow = mc.player.getBlockZ();
        for (Map.Entry<BlockPos, Block> ue : mod.currentBuildPlan.entrySet()) {
            BlockPos up = ue.getKey();
            int uz = up.getZ();
            if (uz < botZNow - 2 || uz > botZNow + 24) continue;   // the walking window
            if (mod.miningBlockState(up).isAir()) continue;
            Item uItem = ue.getValue().asItem();
            Integer prev = firstUseZ.get(uItem);
            if (prev == null || uz < prev) firstUseZ.put(uItem, uz);
        }
        int swapsDone = 0;
        ArrayList<Item> bringOrder = new ArrayList<>();
        ArrayList<Integer> bringZs = new ArrayList<>();
        for (Map.Entry<Item, Integer> fe : firstUseZ.entrySet()) {
            if (!mod.restock.isItemInHotbar(fe.getKey())) { bringOrder.add(fe.getKey()); bringZs.add(fe.getValue()); }
        }
        for (int a = 0; a < bringOrder.size(); a++) {
            for (int b = a + 1; b < bringOrder.size(); b++) {
                if (bringZs.get(b) < bringZs.get(a)) {
                    Item tI = bringOrder.get(a); bringOrder.set(a, bringOrder.get(b)); bringOrder.set(b, tI);
                    Integer tZ = bringZs.get(a); bringZs.set(a, bringZs.get(b)); bringZs.set(b, tZ);
                }
            }
        }
        for (int bi = 0; bi < bringOrder.size() && swapsDone < 4; bi++) {
            Item inItem = bringOrder.get(bi);
            int inZ = bringZs.get(bi);
            int srcSlot = -1;
            for (int is = 9; is < 36 && srcSlot == -1; is++) {
                ItemStack iStack = mc.player.getInventory().getStack(is);
                if (!iStack.isEmpty() && iStack.getItem().equals(inItem)) srcSlot = is;
            }
            if (srcSlot == -1) continue;
            int evictSlot = -1;
            int worstZ = Integer.MIN_VALUE;
            for (int hs : mod.availableHotBarSlots) {
                ItemStack eStack = mc.player.getInventory().getStack(hs);
                if (eStack.isEmpty()) { evictSlot = hs; worstZ = Integer.MAX_VALUE; break; }
                Integer eZ = firstUseZ.get(eStack.getItem());
                int eDist = (eZ == null) ? Integer.MAX_VALUE : eZ;
                if (eDist > worstZ) { worstZ = eDist; evictSlot = hs; }
            }
            if (evictSlot == -1) break;
            if (worstZ < inZ) break;
            Utils.performSwap(srcSlot, evictSlot);
            swapsDone++;
            mod.info("§7Hotbar rolling: §f" + inItem.getName().getString() + "§7 swapped in (slot " + evictSlot
                + ", first use ~" + (inZ - botZNow) + " rows ahead).");
        }
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
                for (int as2 = 0; as2 < 36; as2++) {
                    ItemStack a3 = mc.player.getInventory().getStack(as2);
                    if (a3.isEmpty() || !a3.isIn(ItemTags.AXES)) continue;
                    int life = ToolUtils.getRemainingDamage(a3);
                    if (life >= mod.minDurability.get() && life > axeLife) { axeLife = life; axeSlot = as2; }
                }
                if (axeSlot >= 9) {
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
            mod.startBreakAll();
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
        if (mod.lowerMapCorner != null && Math.abs(mc.player.getX() - mod.currentWalkLaneX()) > 0.3) return null;
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
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : mod.currentBuildPlan.keySet()) {
            if (mod.miningBlockState(pos).isAir()) continue;
            if (exclude.contains(pos)) continue;
            if (breakPending.containsKey(pos)) continue;   // awaiting server confirm - not sendable
            if (break2Only && (pos.getX() >= westX + 2 || pos.getY() < mod.lowerMapCorner.getY() + 2)) continue;
            double dist = mc.player.getEyePos().distanceTo(pos.toCenterPos());
            if (dist > mod.interactionRange.get()) continue;
            if (dist < bestDist) { bestDist = dist; best = pos; }
        }
        return best;
    }

}
