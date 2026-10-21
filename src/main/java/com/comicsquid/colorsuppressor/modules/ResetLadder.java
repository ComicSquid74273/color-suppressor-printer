package com.comicsquid.colorsuppressor.modules;

import com.comicsquid.colorsuppressor.utils.Utils;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The full-area RESET: the scoped serpentine ladder (32 four-lane columns x 16
 * z-segments, each confirmed by a bounded scan before it advances), its pass feed
 * (jam-guarded target arming), the arrival handler with the stall detector, and the
 * abort entry point. Owns the ladder position state and its segment tables.
 */
final class ResetLadder {
    final StepCheckered mod;

    int resetLadderCol;                                // 0..31 current column (32 = verify phase)
    int resetLadderSeg;                                // 0..15 current z-segment (resume anchor)
    int resetLadderEndCol;                             // last column of the scoped reset
    BlockPos jamWatchTarget;                           // feed-jam detector target
    int jamWatchTicks;

    /** RESET ladder z-segments: inclusive row ranges and station centres (relative). */
    static final int[] RESET_SEG_START = {0, 8, 16, 24, 32, 40, 48, 56, 64, 72, 80, 88, 96, 104, 112, 120};
    static final int[] RESET_SEG_END = {7, 15, 23, 31, 39, 47, 55, 63, 71, 79, 87, 95, 103, 111, 119, 127};
    static final int[] RESET_SEG_CENTER_Z = {4, 12, 20, 28, 36, 44, 52, 60, 68, 76, 84, 92, 100, 108, 116, 124};
    /** Block drops skid up to 3-4 blocks outside the segment / print box (user-measured). */
    static final int RESET_ITEM_MARGIN = 4;

    ResetLadder(StepCheckered mod) {
        this.mod = mod;
    }

    void resetForActivation() {
        resetLadderCol = 0;
        resetLadderSeg = 0;
        resetLadderEndCol = 0;
        jamWatchTarget = null;
        jamWatchTicks = 0;
    }

    void armResetFeedTarget() {
        if (mod.state != State.Mining || !mod.fullSweepActive || resetLadderCol >= 32
            || mod.checkpoints.isEmpty()
            || !"resetPass".equals(mod.checkpoints.getFirst().getRight().getLeft())
                && !"resetStation".equals(mod.checkpoints.getFirst().getRight().getLeft())) return;
        // ARM BLOCK TARGETS: scan ONLY within z±6 of the bot. Prevents
        // targeting blocks far ahead while missing blocks behind. Items
        // collected by vanilla proximity + segment confirmation + verify walk.
        if (mod.nextBlockPos == null) {
            int botZ = mc.player.getBlockZ();
            int bx0 = mod.lowerMapCorner.getX();
            int bx1 = bx0 + 127;
            int bz0 = Math.max(mod.lowerMapCorner.getZ() - 1, botZ - 6);
            int bz1 = Math.min(mod.lowerMapCorner.getZ() + 127, botZ + 6);
            mod.nextBlockPos = mod.slave.scan.findNearestBlockInArea(bx0, bx1, bz0, bz1);
        }
        // FEED-JAM recovery: an armed target beyond interaction range never turns
        // air and blocks re-arming (feed only arms when mod.nextBlockPos == null) —
        // the pass would walk on breaking nothing. After 10 stuck ticks, drop the
        // stale target so the feed re-arms (the block gets re-targeted on a later
        // pass; the arrival scan and verify walk still gate completeness).
        if (mod.nextBlockPos != null
            && mc.player.getEyePos().distanceTo(mod.nextBlockPos.toCenterPos()) > mod.interactionRange.get()) {
            if (mod.nextBlockPos.equals(jamWatchTarget)) {
                jamWatchTicks++;
                if (jamWatchTicks >= 10) {
                    mod.info("§6[RESET-VERIFY]§7 feed re-armed: dropped stale out-of-range target "
                        + mod.nextBlockPos.toShortString() + " (dist ~"
                        + Math.round(mc.player.getEyePos().distanceTo(mod.nextBlockPos.toCenterPos()))
                        + " > " + mod.interactionRange.get() + ") after " + jamWatchTicks + " ticks.");
                    mod.nextBlockPos = null;
                    jamWatchTarget = null;
                    jamWatchTicks = 0;
                }
            } else {
                jamWatchTarget = mod.nextBlockPos;
                jamWatchTicks = 1;
            }
        } else {
            jamWatchTarget = null;
            jamWatchTicks = 0;
        }
    }

    boolean handleResetArrival(Pair<String, BlockPos> checkpointAction) {
        mod.state = State.Mining;
        // PASS ARRIVAL: decode the (col, seg) anchor the queue attached, with a decode
        // cross-check - a mismatch means the arrival scan verifies the WRONG rows.
        if (checkpointAction.getRight() != null) {
            resetLadderCol = Math.min(31, (checkpointAction.getRight().getX() - mod.lowerMapCorner.getX()) / 4);
            int anchorRelZ = checkpointAction.getRight().getZ() - mod.lowerMapCorner.getZ();
            int actualSeg = Math.min(RESET_SEG_START.length - 1, Math.max(0, anchorRelZ / 8));
            resetLadderSeg = resetZToSeg(anchorRelZ);
            if (resetLadderSeg != actualSeg) {
                mod.warning("§6[RESET-VERIFY]§e SEGMENT DECODE BUG at col " + resetLadderCol
                    + ": checkpoint is segment " + actualSeg + " (rows " + (actualSeg * 8) + "-" + (actualSeg * 8 + 7)
                    + ") but resetZToSeg decoded " + resetLadderSeg
                    + " (rows " + (RESET_SEG_START[resetLadderSeg]) + "-" + RESET_SEG_END[resetLadderSeg]
                    + ") - the arrival scan below verifies the WRONG rows, the walked segment is NOT verified.");
            }
        }
        ItemStack ladderPick = mc.player.getInventory().getStack(8);
        if (!ladderPick.isIn(ItemTags.PICKAXES)) {
            // Worn pickaxes keep working mid-run (exchange only at the pre-batch
            // restock) - fetch only when none is left at all
            mod.restock.movePickaxeToSlot8();
            if (!mc.player.getInventory().getStack(8).isIn(ItemTags.PICKAXES)) {
                mod.slave.routes.pauseForRestockTrip(true);
                return true;
            }
        }
        int lx0 = mod.lowerMapCorner.getX() + 4 * resetLadderCol;
        int lx1 = lx0 + 3;
        int lz0 = mod.lowerMapCorner.getZ() + RESET_SEG_START[resetLadderSeg] - (resetLadderSeg == 0 ? 1 : 0);  // segment 0 owns the noobline row too
        int lz1 = mod.lowerMapCorner.getZ() + RESET_SEG_END[resetLadderSeg];
        // ITEM MARGIN window: blocks never move, but their drops skid — scan
        // (and chase) items this far past the segment in every horizontal
        // direction, including outside the print box at its edges.
        int ix0 = lx0 - RESET_ITEM_MARGIN, ix1 = lx1 + RESET_ITEM_MARGIN;
        int iz0 = lz0 - RESET_ITEM_MARGIN, iz1 = lz1 + RESET_ITEM_MARGIN;
        if (mod.restock.freeInventoryCapacity() == 0 && mod.slave.scan.findNearestItemInArea(ix0, ix1, iz0, iz1) != null) {
            mod.restock.clearFilteredDump();   // emergency strip, not a quantity dump
            Pair<BlockPos, Vec3d> ladderChest = mod.restock.getBestDepositChest();
            if (ladderChest != null) {
                mod.checkpoints.clear();
                mod.checkpoints.add(new Pair(ladderChest.getRight(), new Pair("depositBuilding", ladderChest.getLeft())));
                mod.slave.reset.queueResetLadder(resetLadderCol, resetLadderSeg);
                mod.slave.routes.prependNorthCorridor(ladderChest.getRight().x);
                mod.state = State.Mining;
                mod.info("§7Inventory full - depositing, then resuming the reset ladder.");
                mod.state = State.Mining;  // resume in Mining: feed stays active on the return walk
                return true;
            }
        }
        BlockPos lTarget = mod.slave.scan.findNearestBlockInArea(lx0, lx1, lz0, lz1);
        ItemEntity lItem = mod.restock.freeInventoryCapacity() > 0 ? mod.slave.scan.findNearestItemInArea(ix0, ix1, iz0, iz1) : null;
        // RESET-VERIFY census: everything the CLEAN verdict is about to be based on,
        // including content the filtered scans above cannot see.
        int[] census = mod.slave.scan.itemCensusInArea(ix0, ix1, iz0, iz1);
        int pendBrk = mod.slave.scan.countPendingInRange(lx0, lx1, lz0, lz1);
        int vanilla = mod.slave.scan.countVanillaInRange(lx0, lx1, lz0, lz1);
        // STALL DETECTOR (replaces the generic anti-stuck force-skip for this
        // handler): the signature covers block count, every item mod.state and the
        // bot position — ANY progress changes it (blocks break, drops land,
        // states tick over, the bot walks). Only 80 consecutive FROZEN arrivals
        // force-advance, with a full dump of what is being left behind.
        String arrivalSig = resetLadderCol + ":" + resetLadderSeg + "|b" + mod.slave.scan.countBlocksInArea(lx0, lx1, lz0, lz1)
            + "|i" + census[0] + "," + census[1] + "," + census[2] + "," + census[3]
            + "|p" + mc.player.getBlockX() + "," + mc.player.getBlockZ();
        boolean sigChanged = !arrivalSig.equals(mod.slave.resetStallSig);
        if (!sigChanged) {
            mod.slave.resetStallRounds++;
            if (mod.slave.resetStallRounds > 80) {
                mod.warning("§6[RESET-VERIFY]§c STALL: col " + resetLadderCol + " seg " + resetLadderSeg
                    + " frozen for 80+ arrival rounds - blocks=" + mod.slave.scan.countBlocksInArea(lx0, lx1, lz0, lz1)
                    + " items+m" + RESET_ITEM_MARGIN + "[ready=" + census[0] + " falling=" + census[1]
                    + " pickupDelay=" + census[2] + " owned=" + census[3] + "]"
                    + " pendBrk=" + pendBrk + " - advancing WITHOUT clearing (verify walk will re-scope).");
                mod.slave.resetStallRounds = 0;
                mod.slave.resetStallSig = null;
                return true;   // no re-queue: the ladder advances
            }
        } else {
            mod.slave.resetStallSig = arrivalSig;
            mod.slave.resetStallRounds = 0;
        }
        // Census log only on mod.state change: dwell waits and in-place breaking
        // fire arrivals every tick — logging each would flood the chat.
        if (sigChanged) {
            mod.info("§6[RESET-VERIFY]§7 col " + resetLadderCol + " seg " + resetLadderSeg
                + " rows " + (lz0 - mod.lowerMapCorner.getZ()) + "-" + (lz1 - mod.lowerMapCorner.getZ())
                + " §fblocks=" + (lTarget != null ? "1+ @" + (lTarget.getX() - mod.lowerMapCorner.getX()) + "," + (lTarget.getY() - mod.lowerMapCorner.getY()) + "," + (lTarget.getZ() - mod.lowerMapCorner.getZ()) : "0")
                + " §7items+m" + RESET_ITEM_MARGIN + "[ready=" + census[0] + " falling=" + census[1] + " pickupDelay=" + census[2] + " owned=" + census[3] + "]"
                + " §3pendBrk=" + pendBrk + " vanillaQ=" + vanilla
                + (mod.restock.freeInventoryCapacity() == 0 ? " §cINV-FULL(item scan skipped)" : ""));
        }
        if (lTarget == null && lItem == null) {
            if (census[1] + census[2] > 0) {
                // WAIT for in-flight drops instead of advancing: they land and
                // become pickup-able within ~10-20 ticks, and a mod.sprinting bot
                // never returns for them. Dwell in place and re-check — the
                // mod.state transitions keep the stall detector fed; a genuinely
                // frozen wait still force-advances after 80 rounds.
                mod.checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("resetStation", null)));
                return true;
            }
            mod.info("§aReset col " + resetLadderCol + " segment " + resetLadderSeg + " CLEAN - fully confirmed.");
            return true;   // ladder queue advances on its own
        }
        double bDist = lTarget != null ? mc.player.getEyePos().distanceTo(lTarget.toCenterPos()) : Double.MAX_VALUE;
        double iDist = lItem != null ? mc.player.getEyePos().distanceTo(lItem.getEntityPos()) : Double.MAX_VALUE;
        if (lTarget != null && bDist <= iDist) {
            if (bDist <= mod.interactionRange.get()) {
                mod.nextBlockPos = lTarget;
                // Same hybrid rules as the per-tick break loop - via the ONE
                // shared sender (pending-guarded: no re-send while a
                // confirmation is in flight).
                if (!mod.engine.mod.engine.breakPending.containsKey(lTarget)) mod.engine.mod.engine.sendHybridBreak(lTarget);
                mod.checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("resetStation", null)));
                mod.state = State.Mining;
                mod.breakStage = 4;
            } else {
                // Keep Mining mod.state — the feed arms targets during the walk,
                // so blocks in range get broken continuously. Setting Walking
                // here was the bug: no blocks broken while walking to targets.
                mod.checkpoints.addFirst(new Pair(lTarget.toCenterPos(), new Pair("resetStation", null)));
                mod.state = State.Mining;
            }
        } else {
            // in-cell item chase WITHOUT clearing the ladder queue
            mod.checkpoints.addFirst(new Pair(new Vec3d(lItem.getX(), lItem.getY(), lItem.getZ()), new Pair("resetStation", null)));
            mod.state = State.Mining;  // Mining: feed stays active, blocks in path get broken
        }
        return true;
    }

    void stepAbort() {
        mod.info("§cRESET: full-area clear starting.");
        // Force-clear ALL active mod.state — any phase the slave was in is now dead
        mod.checkpoints.clear();
        mod.slave.printRepairPending = false;
        mod.restock.depositBacklogSlots.clear();
        mod.restock.clearFilteredDump();
        mod.restock.restockBacklogSlots.clear();
        mod.restock.restockList.clear();
        mod.slave.batchPassRetries = 0;
        mod.currentBuildPlan = null;
        mod.breakStage = 0;
        mod.nextBlockPos = null;
        mod.engine.mod.engine.vanillaBreakRetries.clear();
        jamWatchTarget = null;
        jamWatchTicks = 0;
        mod.slave.resetStallSig = null;
        mod.slave.resetStallRounds = 0;
        mod.slave.sweepWaitRounds = 0;
        mod.slave.sweepWaitLogged = false;
        mod.slave.sweepReArmSig = null;
        mod.restock.toBeHandledInvPacket = null;
        mod.restock.closeNextInvPacket = false;

        mod.fullSweepActive = false;
        mod.timeoutTicks = 0;
        mod.restock.interactTimeout = 0;
        Utils.setForwardPressed(false);
        Utils.setSneakPressed(false);

        // Full-area sweep: the slave walks to vantage points where its render distance
        // covers the 128x128 print box and breaks every non-air block (spatial awareness,
        // nearest-first). Vantage points: north center (covers the north half) → map
        // center (covers everything). Entry always from the north side.
        // Checkpoint-batch ladder SCOPED to what was actually printed: the batch being
        // built (or the last one) plus one safety column west for its borrow leftovers.
        // One batch window = exactly one 4-lane ladder column. The two-vantage verify
        // after the ladder still covers the whole box as the backstop for older strays.
        // PROGRESS-DRIVEN SCOPE (user rule, 2026-10-20): only the PRINTED batches are
        // cleared - the ladder AND the verify walk stay inside them (+1 safety col
        // east for borrow/drop bounce). Sources: the live in-memory batch (a running
        // flow) and the shared progress file (a crashed/restarted run - batches 0..k
        // printed = ladder cols 0..k). No whole-map walks or scans.
        int progressBatch = mod.master.loadStepProgress();   // shared _configs file; -1-safe on the slave
        int liveBatch = mod.currentBatch >= 0 ? mod.currentBatch : 0;
        int scopeCol = Math.min(31, Math.max(liveBatch, Math.max(0, progressBatch)) + 1);
        resetLadderEndCol = scopeCol;
        resetLadderCol = 0;   // batches print west->east: everything up to the scope col
        mod.resetPendingScope = scopeCol;
        mod.saveResetPending(scopeCol);   // survive disable/crash: re-enable resumes the reset
        mod.info("§7RESET scoped to cols 0-" + scopeCol + " (live batch " + liveBatch
            + ", progress batch " + progressBatch + ") - no whole-map pass.");
        // Start from the segment closest to the bot's current position (not always 0):
        // the bot might be at the south end after a print pass — walking to segment 0
        // (north) wastes transit time. Snake direction adapts per column anyway.
        int botRelZ = mc.player != null ? mc.player.getBlockZ() - mod.lowerMapCorner.getZ() : 0;
        resetLadderSeg = botRelZ > 64 ? 4 : (botRelZ > 26 ? 2 : 0);   // nearest of {0, 2, 4}
        mod.slave.reset.queueResetLadder(resetLadderCol, 0);

        // Route via the north corridor (never cross the map area diagonally)
        double corridorZ = mod.lowerMapCorner.getZ() - 2;
        if (Math.abs(mc.player.getZ() - corridorZ) > 1.5) {
            mod.checkpoints.add(0, new Pair(new Vec3d(mc.player.getX(), mod.lowerMapCorner.getY(), corridorZ), new Pair("sprint", null)));
        }

        mod.state = State.Mining;   // Mining walks AND breaks: the serpentine pass clears as it goes
        mod.breakStage = 4;
        mod.fullSweepActive = true;
        mod.slave.sweepBudget = 600;
        mod.info("§7RESET ladder (scoped to cols " + resetLadderCol + "-" + resetLadderEndCol
            + "): serpentine passes, each segment confirmed fully clean before the next.");
    }

    void queueResetLadder(int fromCol, int fromSeg) {
        mod.state = State.Mining;   // the ladder IS breaking — always Mining
        mod.breakStage = 4;
        for (int col = fromCol; col <= resetLadderEndCol; col++) {
            for (int i = 0; i < RESET_SEG_START.length; i++) {
                int seg = (col % 2 == 0) ? i : RESET_SEG_START.length - 1 - i;
                if (col == fromCol && ((col % 2 == 0 && seg < fromSeg) || (col % 2 == 1 && seg > fromSeg))) continue;
                // Each pass walks from one segment boundary to the other — the entry
                // point is whichever end the bot is closer to (handled by the snake
                // direction per column). The serpentine feed breaks blocks along the way.
                // One PASS per segment: the exit end of the serpentine walk on the
                // centre lane. The pass itself clears everything in range continuously;
                // the arrival handler confirms + cleans up stragglers only.
                boolean southward = (col % 2 == 0);
                double sx = mod.lowerMapCorner.getX() + 4.0 * col + 2.0;
                double sz = mod.lowerMapCorner.getZ() + (southward ? RESET_SEG_END[seg] : RESET_SEG_START[seg]) + 0.5;
                BlockPos anchor = new BlockPos(mod.lowerMapCorner.getX() + 4 * col, mod.lowerMapCorner.getY(),
                    mod.lowerMapCorner.getZ() + RESET_SEG_CENTER_Z[seg]);
                mod.checkpoints.add(new Pair(new Vec3d(sx, mod.lowerMapCorner.getY(), sz), new Pair("resetPass", anchor)));
            }
        }
        // Reset finish: deposit + park at the north staging point
        queueVerifyWalk();
    }

    void queueVerifyWalk() {
        // The finish block is APPENDED with its own corridor legs INSIDE it (live-
        // caught 2026-10-20: a prepend here ran at LADDER-BUILD time - its legs led
        // the whole queue and were consumed at the reset START, so when the ladder
        // finished at the SOUTH end the bare deposit checkpoint was next and the bot
        // beelined diagonally to the north chest, queue=2 in the log).
        Pair<BlockPos, Vec3d> doneChest = mod.restock.getBestDepositChest();
        mod.restock.clearFilteredDump();   // abort-flow dump is a full strip
        double corridorZ = mod.lowerMapCorner.getZ() - 2;
        double y = mod.lowerMapCorner.getY();
        if (doneChest != null) {
            double lastLadderX = mod.checkpoints.isEmpty() ? mod.lowerMapCorner.getX() + 2.5 : mod.checkpoints.getLast().getLeft().x;
            mod.checkpoints.add(new Pair(new Vec3d(lastLadderX, y, corridorZ), new Pair("sprint", null)));       // exit north on the final column
            mod.checkpoints.add(new Pair(new Vec3d(doneChest.getRight().x, y, corridorZ), new Pair("sprint", null)));   // corridor to the chest
            mod.checkpoints.add(new Pair(doneChest.getRight(), new Pair("depositBuilding", doneChest.getLeft())));
        }
        Vec3d park = new Vec3d(mod.lowerMapCorner.getX() + 2.5, y, corridorZ);
        mod.checkpoints.add(new Pair(park, new Pair("fullSweepDone", null)));
    }

    int resetZToSeg(int relZ) {
        for (int seg = RESET_SEG_START.length - 1; seg >= 0; seg--) if (relZ >= RESET_SEG_START[seg]) return seg;
        return 0;
    }

}
