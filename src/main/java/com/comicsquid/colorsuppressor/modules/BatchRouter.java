package com.comicsquid.colorsuppressor.modules;

import com.comicsquid.colorsuppressor.utils.StepCheckerLayers;
import com.comicsquid.colorsuppressor.utils.ToolUtils;
import com.comicsquid.colorsuppressor.utils.SlaveSystem;
import com.comicsquid.colorsuppressor.utils.Utils;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.apache.commons.lang3.tuple.Triple;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The slave's route builders and interrupt recovery: batch/priming/closing routes
 * (incl. the split-build halves), the shared batch-preparation trip (supply + dump +
 * tool/food rules), break-all staging, mid-run supply/deposit interruptions with
 * their resume-route rebuilds, and the north-corridor/lane-centering transit legs.
 */
final class BatchRouter {
    final StepCheckered mod;

    void resetForActivation() {
    }

    BatchRouter(StepCheckered mod) {
        this.mod = mod;
    }

    void beginBatch(int k) {
        if (!mod.initialSetup()) {
            mod.state = State.StandBy;
            return;
        }
        mod.currentBatch = k;
        mod.slave.batchPassRetries = 0;
        mod.engine.placementAttempts.clear();
        mod.engine.placePending.clear();
        mod.engine.placeRejects.clear();
        mod.engine.breakRetryStart.clear();
        mod.slave.scan.collectSkips.clear();
        mod.clearResetPending();   // a new run supersedes any interrupted reset
        mod.slave.sweepReArmSig = null;
        mod.slave.sweepWaitRounds = 0;
        mod.slave.sweepWaitLogged = false;
        mod.breakStage = 0;
        mod.slave.printRepairPending = false;
        mod.break2Permitted = false;
        mod.breakAllPermitted = false;
        mod.fullSweepActive = false;   // no reset may survive into a batch
        mod.currentBuildPlan = mod.assembleBatch(k);
        mod.info("§aBatch " + k + ": §b" + mod.currentBuildPlan.size() + "§7 blocks planned. Restocking...");

        mod.checkpoints.clear();
        mod.state = State.Walking;
        // The whole batch route walks LANE 2 (x = 2k+2.5, block 2k+2's exact center —
        // the borrow-west lane): its only blocks are borrow recessives at y2, above the
        // bot's head, so nothing ever sits at feet level on the walking lane and every
        // batch lane is within interaction range.
        double walkX = mod.lowerMapCorner.getX() + 2.0 * k + 2.5;
        double northZ = mod.lowerMapCorner.getZ() - 2;
        double southZ = mod.lowerMapCorner.getZ() + 128.7;

        // Restock materials (+ pickaxe if missing) and food BEFORE entering the print route.
        // refillBuildingInventory() adds its first chest at the head of the queue, so the
        // actual batch route is appended only after preparation is complete.
        queueStepBuildPreparation(walkX);

        // PHASE TOOL SLOTS (user rule, live 2026-10-21): printing needs NO tools -
        // the tool slots join the staging set during the print pass. The hotbar is
        // 0-8 ONLY (slot 9 is main inventory - passing it as performSwap's hotbar
        // argument crashed the client at batch 3, 02:27): tools park in hotbar
        // 7 (axe) + 8 (pickaxe); break(2)/break(4) moves them back in.
        {
            for (int ts = 7; ts <= 8; ts++) {
                ItemStack tStack = mc.player.getInventory().getStack(ts);
                if (!tStack.isEmpty() && (tStack.isIn(ItemTags.PICKAXES) || tStack.isIn(ItemTags.AXES))) {
                    int freeSlot = -1;
                    for (int fs = 9; fs < 36 && freeSlot == -1; fs++) {
                        if (mc.player.getInventory().getStack(fs).isEmpty()) freeSlot = fs;
                    }
                    if (freeSlot != -1) {
                        // hotbar -> main inventory: SWAP's button IS the hotbar index,
                        // and NO setSelectedSlot may run (it only accepts 0-8).
                        Utils.swapHotbarToInventory(ts, freeSlot);
                        if (!mod.availableHotBarSlots.contains(ts)) mod.availableHotBarSlots.add(ts);
                        mod.info("§7Print phase: " + tStack.getItem().getName().getString() + " moved out of slot " + ts + " - staging slot freed.");
                    } else {
                        mod.info("§7Print phase: slot " + ts + " tool stays put - inventory full, no free main slot.");
                    }
                }
            }
        }
        // HOTBAR LOOKAHEAD (user rule, live 2026-10-21): pre-arrange the hotbar with
        // the FIRST distinct materials of this batch's plan in WALKING ORDER, so the
        // print pass starts correctly staged. Quantity top-up is NOT done here -
        // rollingHotbarLookahead is the single supply/demand authority and fires
        // 0.5s into the pass, before the first block enters reach.
        {
            // Sort the plan by z first: currentBuildPlan is a HashMap, entrySet order
            // is hash order, and the old seed staged a RANDOM palette subset (the v3
            // rolling lesson, unfixed here until 2026-10-21 - passes started
            // mis-arranged and the roller needed seconds to correct while soon-used
            // items sat in the inventory).
            ArrayList<BlockPos> walkingOrder = new ArrayList<>(mod.currentBuildPlan.keySet());
            walkingOrder.sort(Comparator.comparingInt(BlockPos::getZ));
            LinkedHashSet<Item> coming = new LinkedHashSet<>();
            for (BlockPos wp : walkingOrder) {
                if (coming.size() >= mod.availableHotBarSlots.size()) break;
                coming.add(mod.currentBuildPlan.get(wp).asItem());
            }
            int preSwapped = 0;
            int slotCursor = 0;
            for (Item comingItem : coming) {
                if (mod.restock.isItemInHotbar(comingItem)) continue;
                int srcSlot = -1;
                for (int is = 9; is < 36 && srcSlot == -1; is++) {
                    ItemStack isStack = mc.player.getInventory().getStack(is);
                    if (!isStack.isEmpty() && isStack.getItem().equals(comingItem)) srcSlot = is;
                }
                if (srcSlot == -1) continue;
                while (slotCursor < mod.availableHotBarSlots.size()) {
                    int dest = mod.availableHotBarSlots.get(slotCursor);
                    slotCursor++;
                    ItemStack destStack = mc.player.getInventory().getStack(dest);
                    if (destStack.isEmpty() || destStack.getItem().equals(comingItem)) {
                        Utils.performSwap(srcSlot, dest);
                        preSwapped++;
                        break;
                    }
                    // skip slots holding other needed materials
                    boolean destNeeded = false;
                    for (Item otherComing : coming) {
                        if (!destStack.isEmpty() && destStack.getItem().equals(otherComing)) { destNeeded = true; break; }
                    }
                    if (!destNeeded) { Utils.performSwap(srcSlot, dest); preSwapped++; break; }
                }
            }
            if (preSwapped > 0) mod.info("§7Hotbar lookahead: pre-arranged " + preSwapped + " material slots for the print pass.");
        }

        // FIXED CYCLE (user rule, live 2026-10-20): every batch runs the same
        // directions - print N->S, break(2) S->N, break-all N->S (forced in
        // startBreakAll), collection S->N - ending each batch at the north end where
        // the next one starts. NO position-based mirroring: it flipped the parity
        // after any interrupt and manufactured dead NS walks. Repositioning happens
        // once, at the boundary, via the north corridor.
        mod.checkpoints.add(new Pair(new Vec3d(walkX, mod.lowerMapCorner.getY(), northZ), new Pair("batchBegin", null)));
        mod.checkpoints.add(new Pair(new Vec3d(walkX, mod.lowerMapCorner.getY(), southZ), new Pair("printDone", null)));
        mod.checkpoints.add(new Pair(new Vec3d(walkX, mod.lowerMapCorner.getY(), southZ), new Pair("awaitBreak2", null)));
        mod.checkpoints.add(new Pair(new Vec3d(walkX, mod.lowerMapCorner.getY(), northZ), new Pair("break2Done", null)));
        mod.checkpoints.add(new Pair(new Vec3d(walkX, mod.lowerMapCorner.getY(), northZ), new Pair("awaitBreakAll", null)));
        prependLaneCentering(walkX);
        prependNorthCorridor(walkX);
    }

    void beginPriming() {
        if (!mod.initialSetup()) {
            mod.state = State.StandBy;
            return;
        }
        mod.currentBatch = 0;
        mod.breakStage = 0;
        mod.slave.printRepairPending = false;
        mod.engine.placementAttempts.clear();
        mod.engine.placePending.clear();
        mod.engine.placeRejects.clear();
        mod.engine.breakRetryStart.clear();
        mod.clearResetPending();
        // A new run kills any leftover RESET mod.state - a stale mod.fullSweepActive here made
        // the first mid-run supply run resurrect the dead reset ladder over the priming
        mod.fullSweepActive = false;
        mod.checkpoints.clear();
        mod.state = State.Walking;

        // Lane 2 center, same as the regular batches: block 2 of the 4-lane window
        // (lanes 0-3), i.e. corner.x + 2.5 — the borrow-west lane, feet-level clean.
        double stagingX = mod.lowerMapCorner.getX() + 2.5;
        double northZ = mod.lowerMapCorner.getZ() - 2;
        double southZ = mod.lowerMapCorner.getZ() + 128.7;
        HashMap<BlockPos, Block> floorPlan = mod.assemblePriming();
        if (mod.stepLayers != null) {
            StepCheckerLayers.addLayerAt(floorPlan, mod.stepLayers.domLayers.get(0), mod.lowerMapCorner, 2);   // merged batch-0 supply basis
        }
        mod.currentBuildPlan = floorPlan;
        queueStepBuildPreparation(stagingX);
        mod.currentBuildPlan = mod.assemblePriming();   // the actual print plan (floor only)
        // FIXED CYCLE: print N->S like every batch (no mirroring - see beginBatch)
        mod.checkpoints.add(new Pair(new Vec3d(stagingX, mod.lowerMapCorner.getY(), northZ), new Pair("batchBegin", null)));
        mod.checkpoints.add(new Pair(new Vec3d(stagingX, mod.lowerMapCorner.getY(), southZ), new Pair("printDone", null)));
        mod.checkpoints.add(new Pair(new Vec3d(stagingX, mod.lowerMapCorner.getY(), southZ), new Pair("awaitBreak2", null)));
        prependLaneCentering(stagingX);
        prependNorthCorridor(stagingX);
        mod.info("§aBatch 0 floor half: §b" + mod.currentBuildPlan.size() + "§7 blocks planned.");
    }

    void beginClosing() {
        if (!mod.initialSetup()) {
            mod.state = State.StandBy;
            return;
        }
        mod.currentBatch = 63;
        mod.breakStage = 0;
        mod.slave.printRepairPending = false;
        mod.currentBuildPlan = mod.assembleClosing();
        mod.checkpoints.clear();
        mod.state = State.Walking;

        // Closing = dominant pair 63 on lanes 126-127 (2-lane window): the slave walks
        // the second lane's center (block 127, y2-only above the head), NOT the master's
        // x=253 sweep lane
        double stagingX = mod.lowerMapCorner.getX() + 127.5;
        double northZ = mod.lowerMapCorner.getZ() - 2;
        double southZ = mod.lowerMapCorner.getZ() + 128.7;
        queueStepBuildPreparation(stagingX);
        mod.checkpoints.add(new Pair(new Vec3d(stagingX, mod.lowerMapCorner.getY(), northZ), new Pair("batchBegin", null)));
        mod.checkpoints.add(new Pair(new Vec3d(stagingX, mod.lowerMapCorner.getY(), southZ), new Pair("stepClosingReady", null)));
        mod.checkpoints.add(new Pair(new Vec3d(stagingX, mod.lowerMapCorner.getY(), northZ), new Pair("stepClosingWait", null)));
        prependLaneCentering(stagingX);
        prependNorthCorridor(stagingX);
        mod.info("§aClosing: §b" + mod.currentBuildPlan.size() + "§7 dominant blocks planned; sweep must use x=253.");
    }

    void startClosingBreak() {
        mod.breakStage = 4;
        mod.checkpoints.clear();
        mod.state = State.Walking;
        double stagingX = mod.lowerMapCorner.getX() + 253.5;
        double northZ = mod.lowerMapCorner.getZ() - 2;
        double southZ = mod.lowerMapCorner.getZ() + 128.7;
        mod.checkpoints.add(new Pair(new Vec3d(stagingX, mod.lowerMapCorner.getY(), northZ), new Pair("breakAllBegin", null)));
        mod.checkpoints.add(new Pair(new Vec3d(stagingX, mod.lowerMapCorner.getY(), southZ), new Pair("breakAllDone", null)));
        mod.checkpoints.add(new Pair(new Vec3d(stagingX, mod.lowerMapCorner.getY(), northZ), new Pair("stepClosingDone", null)));
        prependNorthCorridor(stagingX);
        mod.info("§aClosing: recycling pair 63 after the final map verification.");
    }

    void finishClosing() {
        ArrayList<BlockPos> remaining = new ArrayList<>();
        if (mod.currentBuildPlan != null) {
            for (BlockPos pos : mod.currentBuildPlan.keySet()) {
                if (!mod.miningBlockState(pos).isAir()) remaining.add(pos);
            }
        }
        if (!remaining.isEmpty()) {
            mod.error("Closing recycle left " + remaining.size() + " blocks standing; stopped for diagnosis.");
            mod.state = State.StandBy;
            mod.master.stepMasterStage = StepMasterStage.ABORTED;
            Utils.setForwardPressed(false);
            return;
        }
        mod.currentBuildPlan = null;
        mod.breakStage = 0;
        mod.state = State.StandBy;
        Utils.setForwardPressed(false);
        SlaveSystem.sendMessageToMaster("step:closed");
    }

    void queueStepBuildPreparation(double stagingX) {
        mod.restock.mod.restock.prepServerRecheckPending = true;   // recount deficits at the first chest-open (server truth)
        mod.restock.mod.restock.refillFromCurrentPlan();
        boolean materialRestockQueued = !mod.restock.mod.restock.restockList.isEmpty();

        // EXACT-QUANTITY PREP DUMP (user rule, 2026-10-21): keep exactly what this
        // build still needs, deposit the excess, refill the deficit - ONE trip.
        // The NEED snapshot is taken here (priming swaps the plan right after
        // queueing, and the refill above used the same world truth, so dump and
        // withdrawal agree by construction). The stack selection re-derives live
        // at every chest-open in RestockManager.buildFilteredDumpBacklog: the old
        // queue-time pre-fill used INVENTORY indices where the drain reads
        // CHEST-SCREEN indices (hotbar/main-9..26 slots pointed at CHEST stacks
        // and pulled items OUT), and the drain's strip chain then dumped
        // materials the refill leg in this same trip was about to re-withdraw -
        // the dump-then-refill churn.
        if (mod.lowerMapCorner != null && mod.currentBatch >= 0 && mod.currentBuildPlan != null) {
            mod.restock.mod.restock.filteredDumpNeed = mod.restock.mod.restock.getRequiredItems();
            HashSet<Item> excessItems = mod.restock.mod.restock.excessItemsBeyondNeed();
            if (!excessItems.isEmpty()) {
                Pair<BlockPos, Vec3d> dumpChest = mod.restock.mod.restock.getNextFilteredDepositChest(excessItems);
                StringBuilder dumpNames = new StringBuilder();
                for (Item dn : excessItems) { if (dumpNames.length() > 0) dumpNames.append(", "); dumpNames.append(dn.getName().getString()); }
                if (dumpChest != null) {
                    mod.restock.mod.restock.depositBacklogSlots.clear();   // selection happens at chest-open
                    mod.restock.mod.restock.filteredDumpActive = true;
                    mod.info("§7Prep dump planned: excess of §b" + dumpNames + "§7 beyond this build's need - depositing before the refill.");
                    int insertAt = 0;
                    for (int ci = 0; ci < mod.checkpoints.size(); ci++) {
                        String ca = mod.checkpoints.get(ci).getRight().getLeft();
                        if ("refill".equals(ca) || "mod.foodChest".equals(ca)) { insertAt = ci; break; }
                    }
                    mod.checkpoints.add(insertAt, new Pair(new Vec3d(dumpChest.getRight().x, dumpChest.getRight().y, dumpChest.getRight().z), new Pair("depositUnneeded", dumpChest.getLeft())));
                } else {
                    mod.restock.mod.restock.clearFilteredDump();
                    mod.warning("§ePrep dump: no chest with room for the excess (" + dumpNames + ") - carrying it on.");
                }
            } else {
                mod.restock.mod.restock.clearFilteredDump();
            }
        }
        // TOOLS CAN SIT ANYWHERE (user rule, live 06:49: an offhand axe read as
        // "missing" and the prep fetched a duplicate). Pull any offhand tool into
        // the main inventory FIRST, then every scan below sees one pool.
        mod.restock.mod.restock.normalizeToolsFromOffhand();
        // PICKAXE (user rule, 2026-10-20): tools are exchanged ONLY here - at the
        // pre-batch restock trip, when the best pickaxe has gone BELOW min-durability
        // (strictly below, not at the touch). Never mid-run: a worn pickaxe keeps
        // swinging until it breaks; the only mid-run trip left is fetching a
        // replacement after a pickaxe is genuinely gone.
        int pickSlot = mod.restock.mod.restock.findPickaxeSlot();
        int pickRemaining = pickSlot >= 0 ? ToolUtils.getRemainingDamage(mc.player.getInventory().getStack(pickSlot)) : 0;
        if (pickSlot < 0 || pickRemaining < mod.minDurability.get()) {
            Item pickaxe = mod.restock.mod.restock.findRegisteredPickaxeItem();
            if (pickaxe != null) {
                mod.info("§7Pickaxe " + (pickSlot < 0 ? "missing" : "at " + pickRemaining + " durability")
                    + " - exchanging at the batch-prep trip (min-durability " + mod.minDurability.get() + ").");
                mod.restock.mod.restock.restockList.add(Triple.of(pickaxe, 1, 1));
                // If materials already have a chest at the head, endRestocking() will promote
                // this entry after them.  With no material restock, add its first chest now.
                if (!materialRestockQueued) mod.restock.mod.restock.addClosestRestockCheckpoint();
            }
        } else if (mod.restock.mod.restock.countPickaxes() > 1) {
            // ONE-PICKAXE INVARIANT (user rule 2026-10-20: exactly one pickaxe in the
            // inventory): the best pickaxe survives the batch, but stale spares would ride
            // along forever - queue a deposit-only pickaxe-chest visit (0 stacks = take
            // nothing, park the spares at the chest).
            Item pickaxe = mod.restock.mod.restock.findRegisteredPickaxeItem();
            if (pickaxe != null) {
                mod.info("§7" + (mod.restock.mod.restock.countPickaxes() - 1) + " spare pickaxe(s) carried - depositing them at the batch-prep trip.");
                mod.restock.mod.restock.restockList.add(Triple.of(pickaxe, 0, 0));
                if (!materialRestockQueued) mod.restock.mod.restock.addClosestRestockCheckpoint();
            }
        }
        // BEST-TOOL PREP — AXE, the PICKAXE'S rule verbatim (user rules, live
        // 2026-10-21: "same logic as pickaxe - one axe" + "why bot not get an axe
        // when there was no axe in inventory or hotbar"): NO wood-need gate - the
        // pickaxe has no plan-content gate either. One-axe invariant at every prep:
        // missing or below min-durability -> EXCHANGE (the worn axe parks at the
        // tool chest during the same visit - handleRestockResponse's axe parking);
        // healthy -> KEPT (no withdrawal); >1 carried -> spares deposit.
        {
            // ONE scan pass: best remaining durability + carried count (the one-axe
            // invariant's evidence). 40 = offhand: tools can sit anywhere.
            int bestAxeRemaining = -1;
            int axeCarried = 0;
            for (int aSlot = 0; aSlot <= 40; aSlot++) {
                ItemStack aStack = mc.player.getInventory().getStack(aSlot);
                if (aStack.isEmpty() || !aStack.isIn(ItemTags.AXES)) continue;
                axeCarried++;
                bestAxeRemaining = Math.max(bestAxeRemaining, ToolUtils.getRemainingDamage(aStack));
            }
            if (axeCarried == 0 || bestAxeRemaining < mod.minDurability.get()) {
                ItemStack axe = null;
                float axeSpeed = -1;
                for (ItemStack tool : mod.toolSet) {
                    if (!tool.isIn(ItemTags.AXES)) continue;
                    float sp = tool.getMiningSpeedMultiplier(net.minecraft.block.Blocks.OAK_PLANKS.getDefaultState());
                    if (sp > axeSpeed) { axeSpeed = sp; axe = tool; }
                }
                if (axe != null) {
                    mod.info("§7Axe " + (axeCarried == 0 ? "missing" : "at " + bestAxeRemaining + " durability")
                        + " - exchanging at the batch-prep trip (min-durability " + mod.minDurability.get() + ").");
                    mod.restock.mod.restock.restockList.add(Triple.of(axe.getItem(), 1, 1));
                    // Without a chest at the head of the queue the exchange never
                    // visits one - the pickaxe branch's guard, mirrored.
                    if (!materialRestockQueued) mod.restock.mod.restock.addClosestRestockCheckpoint();
                }
            } else if (axeCarried > 1) {
                // ONE-AXE INVARIANT (mirrors the pickaxe's): the best axe survives;
                // spares ride forever otherwise. Deposit-only visit (0 stacks = take
                // nothing) parks the spares at the chest.
                Item spareAxe = null;
                float spareSpeed = -1;
                for (ItemStack tool : mod.toolSet) {
                    if (!tool.isIn(ItemTags.AXES)) continue;
                    float sp = tool.getMiningSpeedMultiplier(net.minecraft.block.Blocks.OAK_PLANKS.getDefaultState());
                    if (sp > spareSpeed) { spareSpeed = sp; spareAxe = tool.getItem(); }
                }
                if (spareAxe != null) {
                    mod.info("§7" + (axeCarried - 1) + " spare axe(s) carried - depositing them at the batch-prep trip.");
                    mod.restock.mod.restock.restockList.add(Triple.of(spareAxe, 0, 0));
                    if (!materialRestockQueued) mod.restock.mod.restock.addClosestRestockCheckpoint();
                }
            }
        }
        // FOOD (user rule): fetch ONLY when the OFFHAND stack is below 10 pieces, and
        // take only what fits in the offhand (never a second full stack on top of one).
        if (mod.foodChest != null && mod.restock.mod.restock.offhandFoodCount() < 10) {
            mod.checkpoints.add(new Pair(mod.foodChest.getRight(), new Pair("mod.foodChest", mod.foodChest.getLeft())));
        }
        double northZ = mod.lowerMapCorner.getZ() - 2;
        mod.checkpoints.add(new Pair(new Vec3d(stagingX, mod.lowerMapCorner.getY(), northZ), new Pair("swapOffhand", null)));
    }

    void resumeBatchRoute(int stage) {
        double northZ = mod.lowerMapCorner.getZ() - 2;
        double southZ = mod.lowerMapCorner.getZ() + 128.7;
        if (mod.currentBatch == 63) {
            if (stage == 4) {
                double sweepX = mod.lowerMapCorner.getX() + 253.5;
                mod.checkpoints.add(new Pair(new Vec3d(sweepX, mod.lowerMapCorner.getY(), northZ), new Pair("breakAllBegin", null)));
                mod.checkpoints.add(new Pair(new Vec3d(sweepX, mod.lowerMapCorner.getY(), southZ), new Pair("breakAllDone", null)));
                mod.checkpoints.add(new Pair(new Vec3d(sweepX, mod.lowerMapCorner.getY(), northZ), new Pair("stepClosingDone", null)));
            } else {
                double stagingX = mod.lowerMapCorner.getX() + 127.5;
                mod.checkpoints.add(new Pair(new Vec3d(stagingX, mod.lowerMapCorner.getY(), northZ), new Pair("batchBegin", null)));
                mod.checkpoints.add(new Pair(new Vec3d(stagingX, mod.lowerMapCorner.getY(), southZ), new Pair("stepClosingReady", null)));
                mod.checkpoints.add(new Pair(new Vec3d(stagingX, mod.lowerMapCorner.getY(), northZ), new Pair("stepClosingWait", null)));
            }
            return;
        }
        // FIXED CYCLE on resume too: the same directions as a fresh batch, so an
        // emergency interrupt can never flip the parity into dead NS walks.
        double walkX = mod.lowerMapCorner.getX() + 2.0 * mod.currentBatch + 2.5;
        switch (stage) {
            case 0 -> {
                mod.checkpoints.add(new Pair(new Vec3d(walkX, mod.lowerMapCorner.getY(), southZ), new Pair("printDone", null)));
                mod.checkpoints.add(new Pair(new Vec3d(walkX, mod.lowerMapCorner.getY(), southZ), new Pair("awaitBreak2", null)));
                mod.checkpoints.add(new Pair(new Vec3d(walkX, mod.lowerMapCorner.getY(), northZ), new Pair("break2Done", null)));
                mod.checkpoints.add(new Pair(new Vec3d(walkX, mod.lowerMapCorner.getY(), northZ), new Pair("awaitBreakAll", null)));
            }
            case 2 -> {
                mod.checkpoints.add(new Pair(new Vec3d(walkX, mod.lowerMapCorner.getY(), northZ), new Pair("break2Done", null)));
                mod.checkpoints.add(new Pair(new Vec3d(walkX, mod.lowerMapCorner.getY(), northZ), new Pair("awaitBreakAll", null)));
            }
            default -> {
                // break-all: N->S (forced); the batchExit queued here is stale by
                // design - the collection run replaces it with an in-place exit.
                mod.checkpoints.add(new Pair(new Vec3d(walkX, mod.lowerMapCorner.getY(), northZ), new Pair("breakAllBegin", null)));
                mod.checkpoints.add(new Pair(new Vec3d(walkX, mod.lowerMapCorner.getY(), southZ), new Pair("breakAllDone", null)));
                mod.checkpoints.add(new Pair(new Vec3d(walkX, mod.lowerMapCorner.getY(), southZ), new Pair("batchExit", null)));
            }
        }
    }

    void pauseForRestockTrip(boolean needPickaxe) {
        // Cooldown: prevent the supply-run trigger from re-firing every tick while the
        // bot is still Walking toward the chest (mod.engine.tryPlacingBlock sees no materials →
        // calls this again → clears the route → loops forever without moving)
        if (mod.slave.restockCooldown > 0) return;
        mod.slave.restockCooldown = 100;   // 5 seconds before another supply run can trigger

        mod.info("§eMid-batch supply run via the north checkpoint.");
        int savedStage = mod.breakStage;
        mod.breakStage = 0;
        mod.checkpoints.clear();

        if (savedStage == 0) {
            mod.restock.mod.restock.refillFromCurrentPlan();
        }
        if (needPickaxe && !mod.restock.mod.restock.hasAnyPickaxe() && !mod.toolSet.isEmpty()) {
            for (ItemStack tool : mod.toolSet) {
                if (tool.isIn(ItemTags.PICKAXES)) {
                    mod.restock.mod.restock.restockList.add(Triple.of(tool.getItem(), 1, 1));
                    // Without a chest at the head of the queue the trip never visits one:
                    // the emergency "restock" walked the corridor, resumed breaking with
                    // the same worn pickaxe and re-triggered every cooldown (observed loop)
                    if (mod.restock.mod.restock.restockList.size() == 1) mod.restock.mod.restock.addClosestRestockCheckpoint();
                    break;
                }
            }
        }
        if (mod.fullSweepActive) {
            // RESET interrupted by the emergency: resume the LADDER at the current
            // station - never a batch route
            mod.checkpoints.clear();
            if (mod.slave.reset.resetLadderCol < 32) {
                mod.slave.reset.queueResetLadder(mod.slave.reset.resetLadderCol, mod.slave.reset.resetLadderSeg);
            } else {
                mod.slave.reset.queueVerifyWalk();
            }
        } else {
            int chestSectionSize = mod.checkpoints.size();   // chest trip queued above
            resumeBatchRoute(savedStage);                // route appended at the tail
            // Re-entry BETWEEN chest and route: corridor back to the batch lane's
            // north staging, then south along the lane (head-insertion landed it
            // BEFORE the chest visit and the return leg stayed a diagonal beeline -
            // live 2026-10-20 mid-print emergency restock).
            insertNorthReEntry(chestSectionSize);
        }
        // Exit north via the corridor (head legs)
        prependNorthCorridor(mod.checkpoints.isEmpty() ? mod.lowerMapCorner.getX() + 2.5 : mod.checkpoints.getFirst().getLeft().x);
        mod.state = State.Walking;
        Utils.setForwardPressed(false);
    }

    void pauseBreakAllForDeposit() {
        mod.info("§eInventory full - depositing building blocks before continuing the break.");
        mod.restock.mod.restock.clearFilteredDump();   // emergency strip, not a quantity dump
        mod.breakStage = 0;
        mod.checkpoints.clear();
        // Explicit order: chest visit -> north re-entry on the batch lane -> resumed
        // route; exit legs prepended last (north on the lane, then the corridor).
        Pair<BlockPos, Vec3d> chest = mod.restock.mod.restock.getBestDepositChest();
        if (chest != null) {
            mod.checkpoints.add(new Pair(chest.getRight(), new Pair("depositBuilding", chest.getLeft())));
        }
        insertNorthReEntry(mod.checkpoints.size());
        resumeBatchRoute(4);
        prependNorthCorridor(chest != null ? chest.getRight().x : currentWalkLaneX());
        mod.state = State.Walking;
    }

    void insertNorthReEntry(int index) {
        mod.checkpoints.add(Math.max(0, Math.min(index, mod.checkpoints.size())), new Pair(
            new Vec3d(currentWalkLaneX(), mod.lowerMapCorner.getY(), mod.lowerMapCorner.getZ() - 2), new Pair("sprint", null)));
    }

    void prependNorthCorridor(double targetX) {
        double corridorZ = mod.lowerMapCorner.getZ() - 2;
        // When a lane-centering leg was just queued, the exit must run north on the
        // lane-2 centerline (its x) - not from the bot's current off-lane x
        double exitX = mc.player.getX();
        if (!mod.checkpoints.isEmpty() && "centerLane".equals(mod.checkpoints.getFirst().getRight().getLeft())) {
            exitX = mod.checkpoints.getFirst().getLeft().x;
        }
        mod.checkpoints.addFirst(new Pair(new Vec3d(targetX, mod.lowerMapCorner.getY(), corridorZ), new Pair("sprint", null)));
        if (Math.abs(mc.player.getZ() - corridorZ) > 1.5) {
            mod.checkpoints.addFirst(new Pair(new Vec3d(exitX, mod.lowerMapCorner.getY(), corridorZ), new Pair("sprint", null)));
        }
    }

    void prependLaneCentering(double laneX) {
        if (Math.abs(mc.player.getX() - laneX) > 0.3) {
            mod.checkpoints.addFirst(new Pair(new Vec3d(laneX, mod.lowerMapCorner.getY(), mc.player.getZ()), new Pair("centerLane", null)));
        }
    }

    double currentWalkLaneX() {
        if (mod.currentBatch == -1) return mod.lowerMapCorner.getX() + 2.5;
        if (mod.currentBatch == 63) return mod.lowerMapCorner.getX() + 127.5;
        return mod.lowerMapCorner.getX() + 2.0 * mod.currentBatch + 2.5;
    }

    void startBreakAll() {
        mod.breakStage = 4;
        mod.checkpoints.clear();
        double walkX = mod.lowerMapCorner.getX() + 2.0 * mod.currentBatch + 2.5;
        // ALWAYS north -> south: the pass must END at the south end so the single
        // south-to-north collection run starts exactly where the bot stands.
        double startZ = mod.lowerMapCorner.getZ() - 2;
        double farZ = mod.lowerMapCorner.getZ() + 128.7;

        // NO pre-break dump (user rule, 2026-10-21): the old deposit tour here
        // (out to the chests, strip every carried material, walk back) only made
        // the next batch's prep trip withdraw the same items again - prep already
        // dumps/recounts on its own trip. Carried materials ride into break-all
        // (drops merge into the same partial stacks); a true overflow stays
        // covered by the exact triggers: mid-break capacity==0
        // (pauseBreakAllForDeposit), finishBatch's full-with-drops deposit, and
        // the prep trip's quantitative dump.
        mod.checkpoints.add(new Pair(new Vec3d(walkX, mod.lowerMapCorner.getY(), startZ), new Pair("breakAllBegin", null)));
        mod.checkpoints.add(new Pair(new Vec3d(walkX, mod.lowerMapCorner.getY(), farZ), new Pair("breakAllDone", null)));
        mod.checkpoints.add(new Pair(new Vec3d(walkX, mod.lowerMapCorner.getY(), farZ), new Pair("batchExit", null)));
        prependNorthCorridor(walkX);
        mod.state = State.Walking;
        Utils.setForwardPressed(false);
        mod.info("§aBreak-all: recycling batch " + mod.currentBatch + " (materials kept for the next prep).");
    }

}
