package com.comicsquid.colorsuppressor.modules;

import com.comicsquid.colorsuppressor.utils.StepCheckerLayers;
import com.comicsquid.colorsuppressor.utils.SlaveSystem;
import com.comicsquid.colorsuppressor.utils.ToolUtils;
import com.comicsquid.colorsuppressor.utils.LocalTCPTransport;
import com.comicsquid.colorsuppressor.utils.Utils;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.speed.Speed;
import meteordevelopment.meteorclient.systems.modules.movement.speed.SpeedModes;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.block.Block;
import org.apache.commons.lang3.tuple.Triple;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The SLAVE side of the step-checker lifecycle: the tick pipeline (guard chain,
 * confirmations, steering, work dispatch), the checkpoint-arrival dispatch with all
 * batch-phase handlers, batch/priming/closing routes, the RESET ladder, the
 * world scanners and censuses, verification gates, and transit-speed control.
 * Shared run state (state, checkpoints, currentBatch/Plan, breakStage, nextBlockPos)
 * lives on the module; placement/break mechanics live in BreakPlaceEngine, supply in
 * RestockManager, the master bot in StepMaster.
 */
final class SlaveEngine {
    final StepCheckered mod;
    final WorldScanner scan;
    final BatchRouter routes;
    final ResetLadder reset;

    // --- Slave run state (moved from the module) ---
    boolean printRepairPending;                        // print pass ended with missing blocks
    int batchPassRetries;
    int sweepBudget;                                   // tick budget for the straggler sweep
    int restockCooldown;                               // blocks repeat supply-run triggers
    String resetStallSig;                              // reset arrival stall detector signature
    int resetStallRounds;
    int sweepWaitRounds;
    boolean sweepWaitLogged;
    String sweepReArmSig;
    int heartbeatTicks;
    String debugLastAction;
    State debugPreviousState;
    int stuckCheckpointCount;
    String lastCheckpointAction;
    boolean pendingDisable;                            // deferred toggle (requestDisable)
    boolean transitSpeedOn;
    private final ArrayDeque<String> pendingStepCommands = new ArrayDeque<>();

    /** RESET ladder z-segments: inclusive row ranges and station centres (relative). */
    /** Block drops skid up to 3-4 blocks outside the segment / print box (user-measured). */

    SlaveEngine(StepCheckered mod) {
        this.mod = mod;
        scan = new WorldScanner(mod);
        routes = new BatchRouter(mod);
        reset = new ResetLadder(mod);
    }

    /** Full slave-state reset (module onActivation path). */
    void resetForActivation() {
        printRepairPending = false;
        batchPassRetries = 0;
        sweepBudget = 0;
        restockCooldown = 0;
        resetStallSig = null;
        resetStallRounds = 0;
        sweepWaitRounds = 0;
        sweepWaitLogged = false;
        sweepReArmSig = null;
        heartbeatTicks = 0;
        debugLastAction = null;
        debugPreviousState = null;
        stuckCheckpointCount = 0;
        lastCheckpointAction = null;
        pendingDisable = false;
        synchronized (pendingStepCommands) {
            pendingStepCommands.clear();
        }
        scan.resetForActivation();
        routes.resetForActivation();
        reset.resetForActivation();
    }

    /** Master->slave command queue entry (SlaveSystem contract lives on the module). */
    void onStepMasterCommand(String command) {
        if (command == null || command.isBlank()) return;
        if (mod.debugPrints.get()) mod.info("§3[dbg] TCP master→slave: §b" + command);
        // ABORT clears the entire command queue and puts itself at the head — a stuck
        // "build" command at the front would otherwise block it from ever being processed
        // (the slave's processPendingStepCommands only peeks at the HEAD of the queue,
        // so a mid-phase build that can't be consumed traps everything behind it)
        if ("abort".equals(command.split(":")[0])) {
            synchronized (pendingStepCommands) {
                pendingStepCommands.clear();
                pendingStepCommands.addLast(command);
            }
            return;
        }
        synchronized (pendingStepCommands) {
            pendingStepCommands.addLast(command);
        }
    }


    void logTickDebug() {
        if (!mod.state.equals(debugPreviousState)) {
            debugPreviousState = mod.state;
            if (mod.debugPrints.get()) mod.info("§3[dbg] state → §b" + mod.state
                + "§3 batch=§b" + mod.currentBatch + "§3 stage=§b" + (mod.breakStage == 0 ? "PRINT" : mod.breakStage == 2 ? "SUPPRESS" : mod.breakStage == 4 ? "RECYCLE" : "?")
                + "§3 plan=§b" + (mod.currentBuildPlan == null ? "null" : mod.currentBuildPlan.size()));
        }

        // Checkpoint-level debug: log every checkpoint consumed with its action + position
        if (mod.debugPrints.get() && !mod.checkpoints.isEmpty() && (mod.state.equals(State.Walking) || mod.state.equals(State.Mining))) {
            Pair<Vec3d, Pair<String, BlockPos>> next = mod.checkpoints.getFirst();
            // Log only when the ACTION changes (not every tick of walking toward the same one)
            if (!next.getRight().getLeft().equals(debugLastAction)) {
                debugLastAction = next.getRight().getLeft();
                mod.info(String.format("§3[dbg] checkpoint → §b%s§3 at §b(%.1f, %.1f, %.1f)§3 queue=§b%d",
                    debugLastAction, next.getLeft().x, next.getLeft().y, next.getLeft().z, mod.checkpoints.size()));
            }
        }
    }

    void sendSlaveHeartbeat() {
        if (!SlaveSystem.isSlave) return;
        if (++heartbeatTicks >= 40) {
            heartbeatTicks = 0;
            SlaveSystem.sendMessageToMaster("heartbeat");
            if (!LocalTCPTransport.isRunning()) {
                SlaveSystem.setTcpAddress(mod.tcpIp.get(), mod.tcpPort.get());
                if (LocalTCPTransport.isRunning()) mod.info("Reconnected to master");
            }
        }
    }

    boolean tryResumePendingReset() {
        if (mod.resetPendingScope < 0 || mod.state != State.StandBy || !mod.checkpoints.isEmpty()
            || mod.lowerMapCorner == null || mod.fullSweepActive) return false;
        int scope = mod.resetPendingScope;
        mod.info("§eRESET pending (scope cols 0-" + scope + ") - resuming the reset after re-enable.");
        reset.resetLadderEndCol = Math.min(31, Math.max(0, scope));
        reset.resetLadderSeg = mc.player.getBlockZ() - mod.lowerMapCorner.getZ() > 64 ? 4
            : (mc.player.getBlockZ() - mod.lowerMapCorner.getZ() > 26 ? 2 : 0);
        reset.queueResetLadder(reset.resetLadderCol, 0);
        routes.prependNorthCorridor(mod.checkpoints.getFirst().getLeft().x);
        mod.state = State.Mining;
        mod.breakStage = 4;
        return true;
    }


    void steerTowardGoal(Vec3d goal, String nextAction) {
        boolean isBreakSweep = "sweepTick".equals(nextAction) || "fullSweepTick".equals(nextAction)
            || "resetStation".equals(nextAction) || "resetPass".equals(nextAction);
        if (isBreakSweep) {
            // Lock yaw to the cardinal direction of travel (N/S on lanes, E/W on corridors)
            double dx = goal.x - mc.player.getX();
            double dz = goal.z - mc.player.getZ();
            float lockedYaw;
            if (Math.abs(dz) > Math.abs(dx)) {
                lockedYaw = dz > 0 ? 0.0f : 180.0f;   // facing south / north
            } else {
                lockedYaw = dx > 0 ? -90.0f : 90.0f;  // facing east / west
            }
            mc.player.setYaw(lockedYaw);
        } else {
            // Normal yaw steering toward the walking goal
            double lookZ = goal.z;
            if (PlayerUtils.distanceTo(goal) > 2) {
                lookZ = mc.player.getZ() + Math.max(Math.min(goal.z - mc.player.getZ(), 1), -1);
            }
            mc.player.setYaw((float) Rotations.getYaw(new Vec3d(goal.x, goal.y, lookZ)));
        }
    }

    void applySprintMode(String nextAction) {
        if (mod.sprinting.get() != StepCheckered.SprintMode.Off) mc.player.setSprinting(true);
    }

    BlockPos pickWorkTarget() {
        if (mod.state.equals(State.Walking)) {
            return (mod.currentBuildPlan != null && mod.breakStage == 0) ? mod.engine.mod.engine.getNextBatchPlacementPos() : null;
        }
        return mod.fullSweepActive ? mod.nextBlockPos
            : mod.breakStage != 0 ? mod.engine.mod.engine.getNextBatchBreakPos() : null;
    }

    void actOnWorkTarget() {
        if (mod.state.equals(State.Walking)) {
            if (mod.breakStage == 0 && mod.currentBuildPlan != null) {
                // DISTINCT-POSITION MULTI-PLACE (user target: 25-30 places/s). The old
                // loop stopped after one place: the client cannot see the server's
                // placement echo for 1-2 ticks, so the air-check always failed and
                // broke the loop (~10-12/s cap). Now: send up to mod.blocksPerTick
                // DIFFERENT positions per tick (each on a 4-tick cooldown), stop only
                // on a swap/restock need.
                HashSet<BlockPos> attempted = new HashSet<>();
                int placed = 0;
                for (int pick = 0; pick < mod.blocksPerTick.get() * 3 && placed < mod.blocksPerTick.get(); pick++) {
                    BlockPos target = mod.engine.mod.engine.getNextBatchPlacementPos(attempted);
                    if (target == null) break;
                    attempted.add(target);
                    mod.nextBlockPos = target;
                    mod.engine.placementCooldowns.put(target, 4);  // ~0.2s before retry
                    mod.engine.placementAttempts.merge(target, 1, Integer::sum);  // adaptive-pace cap
                    int code = mod.engine.mod.engine.tryPlacingBlock();
                    if (code == 1) { placed++; mod.engine.placeSentPerSec++; }
                    else break;  // swap pending or material missing — tick handles it
                }
            }
            return;
        }
        // Batch breaking always swings the reserved pickaxe slot (hotbar slot 8)
        ItemStack reserved = mc.player.getInventory().getStack(8);
        if (!reserved.isIn(ItemTags.PICKAXES)) {
            // Mid-run a worn pickaxe keeps swinging (exchanges happen ONLY at the
            // pre-batch restock) - reaching here means it is GONE (broke). Arm any
            // carried one; with none left the fetch trip is the only recovery.
            mod.restock.mod.restock.movePickaxeToSlot8();
            if (!mc.player.getInventory().getStack(8).isIn(ItemTags.PICKAXES)) {
                routes.pauseForRestockTrip(true);
            }
            return;
        }
        mc.player.getInventory().setSelectedSlot(8);

        // Target new blocks — HYBRID: fastBreak (instant) for new targets,
        // vanilla mining for blocks fastBreak already failed on (multi-tick).
        // During RESET (mod.fullSweepActive), mod.currentBuildPlan is null so
        // mod.engine.mod.engine.getNextBatchBreakPos() returns nothing — use the ring-scanner instead.
        // Skipped entirely when the Mining-mod.state loop earlier in the tick already
        // sent packets this tick: both loops acting on the same target doubles the
        // packets and re-resets server break-progress on vanilla targets.
        if (mod.engine.mod.engine.breakSweepActedThisTick) return;
        mod.engine.mod.engine.sendDistinctBreaks(mod.breaksPerTick.get(), mod.fullSweepActive && mod.currentBuildPlan == null);
    }

    boolean handleCheckpointArrival(Vec3d goal) {
        Pair<String, BlockPos> checkpointAction = mod.checkpoints.get(0).getRight();
        if (mod.debugPrints.get() && checkpointAction.getLeft() != null)
            mod.info("Reached: §a" + checkpointAction.getLeft());
        if (mod.snapToCheckpoints.get()) mc.player.setPosition(goal.x, mc.player.getY(), goal.z);
        // ANTI-STUCK: if the same action fires 10+ times consecutively (handler
        // re-queuing itself without progress), force-skip to the next checkpoint.
        // EXCEPTION — sweep-family actions: resetPass/resetStation (RESET ladder),
        // sweepTick/batchExit (batch straggler sweep) and breakAllDone (break-all
        // arrival gate) legitimately re-queue themselves for every straggler block,
        // item chase, and in-flight drop wait; the generic force-skip abandoned reset
        // segments mid-clear and STRANDED batches (removing the only queued sweepTick
        // leaves an empty route -> StandBy with the batch unfinished). Loop protection
        // comes from the arrival STALL DETECTORS and the sweep budget + progress-aware
        // finishBatch retries instead.
        boolean isSweepFamilyAction = mod.fullSweepActive && ("resetPass".equals(checkpointAction.getLeft())
            || "resetStation".equals(checkpointAction.getLeft()))
            || "sweepTick".equals(checkpointAction.getLeft())
            || "batchExit".equals(checkpointAction.getLeft())
            || "breakAllDone".equals(checkpointAction.getLeft())
            || "collectRun".equals(checkpointAction.getLeft());
        if (isSweepFamilyAction) {
            stuckCheckpointCount = 0;
            lastCheckpointAction = checkpointAction.getLeft();
        } else if (checkpointAction.getLeft() != null
            && checkpointAction.getLeft().equals(lastCheckpointAction)) {
            stuckCheckpointCount++;
            if (stuckCheckpointCount > 10) {
                mod.info("§eAnti-stuck: skipping repeated checkpoint '" + checkpointAction.getLeft() + "'.");
                stuckCheckpointCount = 0;
                lastCheckpointAction = null;
                mod.checkpoints.remove(0);
                return true;
            }
        } else {
            lastCheckpointAction = checkpointAction.getLeft();
            stuckCheckpointCount = 0;
        }
        mod.checkpoints.remove(0);

        switch (checkpointAction.getLeft()) {
            case "calculateRefill":
                mod.restock.mod.restock.refillFromCurrentPlan();
                return true;
            case "refill":
                mod.state = State.AwaitRestockResponse;
                Utils.setForwardPressed(false);
                mod.restock.mod.restock.interactWithBlock(checkpointAction.getRight());
                return true;
            case "mod.foodChest":
                mod.state = State.AwaitFoodChestResponse;
                Utils.setForwardPressed(false);
                mod.restock.mod.restock.interactWithBlock(checkpointAction.getRight());
                return true;
            case "swapOffhand":
                mod.restock.mod.restock.setupHands();
                break;
            case "batchBegin":
                // Simple rule: no usable pickaxe in reserved slot 8/inventory -> walk to
                // the restock station, get one, then continue. No failure path.
                if (!mc.player.getInventory().getStack(8).isIn(ItemTags.PICKAXES)) {
                    mod.restock.mod.restock.movePickaxeToSlot8();
                    if (!mc.player.getInventory().getStack(8).isIn(ItemTags.PICKAXES)) {
                        mod.restock.mod.restock.restockPickaxeTrip(goal);
                        return true;
                    }
                }
                break;
            case "printDone":
                verifyPrintPass();
                break;
            case "printDone2":
                if (!verifyPrintPass2()) return true;
                break;
            case "awaitBreak2":
                if (printRepairPending) break;   // repair pass: walk through lane -1 placing
                mod.info("§aBatch " + mod.currentBatch + " placed. Waiting for master's break(2) permission.");
                mod.breakStage = 0;
                mod.state = State.AwaitBreakPermission2;
                Utils.setForwardPressed(false);
                SlaveSystem.sendMessageToMaster("step:ready:" + mod.currentBatch);
                return true;
            case "break2Done":
                return handleBreak2DoneArrival();
            case "awaitBreakAll":
                mod.info("§aBatch " + mod.currentBatch + " break(2) complete. Waiting for master's break-all permission.");
                mod.state = State.AwaitBreakPermissionAll;
                Utils.setForwardPressed(false);
                SlaveSystem.sendMessageToMaster("step:break2done:" + mod.currentBatch);
                return true;
            case "break2Resume":
                mod.restock.mod.restock.ensurePickaxeSlot8();
                mod.breakStage = 2;
                mod.state = State.Mining;
                break;
            case "breakAllBegin":
                mod.restock.mod.restock.ensurePickaxeSlot8();
                mod.breakStage = 4;
                mod.state = State.Mining;
                break;
            case "breakAllDone":
                return handleBreakAllDoneArrival();
            case "collectRun":
                return handleCollectRunArrival();
            case "batchExit":
                // North exit after break-all: verify the window is fully recycled
                // (combined sweep re-queues batchExit while stragglers remain), then
                // report the batch done to the master and stand by.
                finishBatch();
                return true;
            case "stepClosingReady":
                if (!verifyCurrentPlan("Closing")) return true;
                mod.info("§aClosing placement verified. Waiting for the master's final walk.");
                SlaveSystem.sendMessageToMaster("step:ready:close");
                mod.state = State.Walking;
                break;
            case "stepClosingWait":
                mod.state = State.AwaitClosingBreak;
                Utils.setForwardPressed(false);
                return true;
            case "stepClosingDone":
                routes.finishClosing();
                return true;
            case "sweepTick":
                return handleSweepTickArrival();
            case "resetPass":
            case "resetStation":
                return reset.handleResetArrival(checkpointAction);
            case "fullSweepDone":
                mod.fullSweepActive = false;
                mod.clearResetPending();   // the RESET survived to completion
                mod.state = State.StandBy;
                Utils.setForwardPressed(false);
                SlaveSystem.sendMessageToMaster("step:done:" + Math.max(mod.currentBatch, 0));
                mod.info("§6[RESET-VERIFY]§7 completion mod.state: mod.engine.mod.engine.breakPending(in-flight server confirms)=" + mod.engine.mod.engine.breakPending.size()
                    + " vanillaQueue=" + mod.engine.mod.engine.needsVanillaBreak.size()
                    + " persistentRejections=" + mod.engine.mod.engine.vanillaBreakRetries.size()
                    + " - all should be 0 at completion.");
                mod.info("§aRESET complete - print area cleared, items deposited, standby.");
                return true;
            case "depositUnneeded":
                // Prep-trip filtered dump: the backlog was PRE-FILLED with unneeded-
                // item slots at queue time - the deposit handler keeps them as-is.
            case "depositBuilding":
                mod.state = State.AwaitDepositResponse;
                Utils.setForwardPressed(false);
                mod.restock.mod.restock.interactWithBlock(checkpointAction.getRight());
                return true;
        }
        if (mod.checkpoints.isEmpty()) {
            // Route exhausted with no successor queued (e.g. end of a pass):
            // stand by cleanly instead of indexing into the empty queue
            mod.state = State.StandBy;
            Utils.setForwardPressed(false);
            return true;
        }
        return false;
    }

    boolean handleBreak2DoneArrival() {
        if (printRepairPending) {
            // Repair pass 2: re-print any still-missing blocks from LANE 2
            // (same clean feet-level corridor as the main print pass), then
            // proceed to the normal break(2) wait.
            printRepairPending = false;
            double repairX = mod.lowerMapCorner.getX() + 2 * mod.currentBatch + 2 + 0.5;  // lane 2 (borrow-west) center
            // FIXED CYCLE: repair pass N->S like the print pass (no mirroring)
            double rStartZ = mod.lowerMapCorner.getZ() - 2;
            double rFarZ = mod.lowerMapCorner.getZ() + 128.7;
            mod.checkpoints.add(0, new Pair(new Vec3d(repairX, mod.lowerMapCorner.getY(), rStartZ), new Pair("break2Done", null)));
            mod.checkpoints.add(0, new Pair(new Vec3d(repairX, mod.lowerMapCorner.getY(), rFarZ), new Pair("awaitBreak2", null)));
            mod.checkpoints.add(0, new Pair(new Vec3d(repairX, mod.lowerMapCorner.getY(), rFarZ), new Pair("printDone2", null)));
            mod.checkpoints.add(0, new Pair(new Vec3d(repairX, mod.lowerMapCorner.getY(), rStartZ), new Pair("sprint", null)));
            mod.state = State.Walking;
            return true;
        }
        if (!verifyBreak2()) return true;
        mod.state = State.Walking;
        return false;
    }

    boolean handleBreakAllDoneArrival() {
        mod.restock.mod.restock.ensurePickaxeSlot8();
        mod.breakStage = 4;
        mod.state = State.Mining;
        int remainingBlocks = scan.countRemainingPlanBlocks();
        int[] census = scan.itemCensusInBatchWindow();
        int bwx0 = scan.batchWindowWestX() - 2;
        int pendBrk = scan.countPendingInRange(bwx0, bwx0 + 7, mod.lowerMapCorner.getZ() - 3, mod.lowerMapCorner.getZ() + 129);
        int vanillaQ = scan.countVanillaInRange(bwx0, bwx0 + 7, mod.lowerMapCorner.getZ() - 3, mod.lowerMapCorner.getZ() + 129);
        // STALL DETECTOR (same signature discipline as resetPass): blocks +
        // item states + position — any progress changes it. 80 frozen rounds
        // advance to the exit gate with a dump (the sweep there re-verifies).
        String baSig = "ba" + mod.currentBatch + "|b" + remainingBlocks
            + "|i" + census[0] + "," + census[1] + "," + census[2] + "," + census[3]
            + "|p" + mc.player.getBlockX() + "," + mc.player.getBlockZ();
        boolean baChanged = !baSig.equals(resetStallSig);
        if (!baChanged) {
            resetStallRounds++;
            if (resetStallRounds > 80) {
                mod.warning("§6[BREAKALL-VERIFY]§c STALL: batch " + mod.currentBatch
                    + " exit frozen for 80+ rounds - blocks=" + remainingBlocks
                    + " items[ready=" + census[0] + " falling=" + census[1]
                    + " pickupDelay=" + census[2] + " owned=" + census[3] + "]"
                    + " pendBrk=" + pendBrk + " - advancing to the exit gate (sweep re-verifies).");
                resetStallRounds = 0;
                resetStallSig = null;
                return false;   // queue advances to batchExit
            }
        } else {
            resetStallSig = baSig;
            resetStallRounds = 0;
        }
        if (baChanged) {
            mod.info("§6[BREAKALL-VERIFY]§7 batch " + mod.currentBatch + " pass end: blocks=" + remainingBlocks
                + " items[ready=" + census[0] + " falling=" + census[1] + " pickupDelay=" + census[2]
                + " owned=" + census[3] + "] pendBrk=" + pendBrk + " vanillaQ=" + vanillaQ
                + (mod.restock.mod.restock.freeInventoryCapacity() == 0 ? " §cINV-FULL(item chase skipped)" : ""));
        }
        BlockPos straggler = scan.nearestRemainingPlanBlock();
        int totalDrops = census[0] + census[1] + census[2];
        if (straggler == null && totalDrops == 0) {
            mod.info("§aBreak-all batch " + mod.currentBatch + " CLEAN - fully confirmed.");
            return false;   // queue advances to batchExit (finishBatch re-verifies + completes)
        }
        if (straggler != null) {
            // BLOCKS FIRST, always - no collection happens until break(4) has
            // actually finished (blocks = 0, server-confirmed).
            mod.nextBlockPos = straggler;
            mod.checkpoints.addFirst(new Pair(straggler.toCenterPos(), new Pair("breakAllDone", null)));
            return true;
        }
        if (census[0] == 0) {
            // Only in-flight drops remain: they land within ~10-20 ticks and a
            // departing bot never returns for them - dwell and re-check.
            mod.checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("breakAllDone", null)));
            return true;
        }
        // break(4) COMPLETE (blocks=0) and ready drops on the ground: ONE
        // single south-to-north collection run. The break pass always ends at
        // the SOUTH end, so the run starts there and only ever advances
        // north (side-steps to drops are east-west; no NS reversals).
        mod.info("§6[BREAKALL-VERIFY]§7 batch " + mod.currentBatch + ": blocks=0, " + census[0]
            + " ready drops - single south-to-north collection run.");
        mod.checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("collectRun", null)));
        mod.state = State.Walking;
        return true;
    }

    boolean handleCollectRunArrival() {
        mod.state = State.Walking;
        int[] cCensus = scan.itemCensusInBatchWindow();
        if (cCensus[0] + cCensus[1] + cCensus[2] == 0) {
            // The run ends at the NORTH end - replace the stale south-end
            // batchExit (queued by startBreakAll next to breakAllDone) with
            // one HERE: the exit gate is position-independent, and walking a
            // full lane back south just to reach it is pure waste.
            mod.checkpoints.removeIf(cp -> "batchExit".equals(cp.getRight().getLeft()));
            mod.checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("batchExit", null)));
            mod.info("§aBatch " + mod.currentBatch + " collection run complete - window clean, exiting in place.");
            return true;
        }
        if (cCensus[0] == 0) {
            // everything left is in flight - dwell one re-check
            mod.checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("collectRun", null)));
            return true;
        }
        ItemEntity next = scan.nearestCollectTarget();
        if (next == null) {
            // only unreachable/blacklisted drops remain - hand off to the exit
            // gate HERE (finishBatch census will name them)
            mod.checkpoints.removeIf(cp -> "batchExit".equals(cp.getRight().getLeft()));
            mod.checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("batchExit", null)));
            mod.warning("§6[BREAKALL-VERIFY]§e batch " + mod.currentBatch + ": " + cCensus[0]
                + " drops unreachable in the collection run - exiting to the gate in place.");
            return true;
        }
        mod.checkpoints.addFirst(new Pair(new Vec3d(next.getX(), next.getY(), next.getZ()), new Pair("collectRun", null)));
        return true;
    }

    boolean handleSweepTickArrival() {
        sweepBudget--;
        BlockPos nearestBlock = null;
        double blockDist = Double.MAX_VALUE;
        if (mod.currentBuildPlan != null) {
            for (BlockPos pos : mod.currentBuildPlan.keySet()) {
                if (mod.miningBlockState(pos).isAir()) continue;
                double dist = mc.player.getEyePos().distanceTo(pos.toCenterPos());
                if (dist < blockDist) { blockDist = dist; nearestBlock = pos; }
            }
        }
        ItemEntity nearestItem = mod.restock.mod.restock.freeInventoryCapacity() > 0 ? scan.findNearestGroundItem(3) : null;
        double itemDist = nearestItem != null
            ? mc.player.getEyePos().distanceTo(nearestItem.getEntityPos()) : Double.MAX_VALUE;
        if (sweepBudget > 0 && mod.restock.mod.restock.freeInventoryCapacity() > 0) {
            if (nearestBlock != null && (nearestItem == null || blockDist <= itemDist)) {
                mod.checkpoints.addFirst(new Pair(nearestBlock.toCenterPos(), new Pair("sweepTick", null)));
                mod.breakStage = 4;
                mod.state = State.Mining;
                return true;
            } else if (nearestItem != null) {
                scan.targetGroundItem(nearestItem, "sweepTick");
                return true;
            }
        }
        // Exit with visible content remaining = budget or inventory driven:
        // name the reason — the finishBatch gate re-verifies and takes over.
        if (nearestBlock != null || nearestItem != null) {
            mod.info("§6[SWEEP-VERIFY]§7 batch " + mod.currentBatch + " sweep pass ending early: "
                + (mod.restock.mod.restock.freeInventoryCapacity() == 0 ? "inventory full" : "budget exhausted")
                + " with " + (nearestBlock != null ? "blocks " : "") + (nearestItem != null ? "+ items " : "")
                + "still visible - exit gate will re-verify.");
        }
        mod.checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("batchExit", null)));
        mod.state = State.Walking;
        return true;
    }







    void finishBatch() {
        // COMBINED SWEEP: check both remaining blocks and ground items at once.
        // sweepTick handles whichever is nearest (block → break, item → collect).
        // No separate retry walks, no zigzag — one spatial-awareness pass.
        int remainingBlocks = scan.countRemainingPlanBlocks();
        int groundItems = scan.countGroundItemsInWindow();
        int[] census = scan.itemCensusInBatchWindow();
        int pendBrk = scan.countPendingInRange(scan.batchWindowWestX() - 2, scan.batchWindowWestX() + 5,
            mod.lowerMapCorner.getZ() - 3, mod.lowerMapCorner.getZ() + 129);
        int inFlight = census[1] + census[2];
        if (remainingBlocks == 0 && groundItems == 0 && census[0] == 0) {
            if (inFlight > 0 && sweepWaitRounds < 100) {
                // WAIT for in-flight drops instead of completing: they land and become
                // pickup-able within ~10-20 ticks, and the next batch's window may not
                // cover them. Bounded at 100 rounds; landing flips groundItems > 0 and
                // takes the re-arm path below.
                sweepWaitRounds++;
                if (!sweepWaitLogged) {
                    mod.info("§6[SWEEP-VERIFY]§7 batch " + mod.currentBatch + ": clean but " + census[1]
                        + " falling + " + census[2] + " pickup-delayed drops in flight - waiting."
                        + " pendBrk=" + pendBrk);
                    sweepWaitLogged = true;
                }
                mod.checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("batchExit", null)));
                mod.state = State.Walking;
                return;
            }
            if (inFlight > 0) {
                mod.warning("§6[SWEEP-VERIFY]§e batch " + mod.currentBatch + ": drops still in flight after 100 wait rounds"
                    + " (falling=" + census[1] + " pickupDelay=" + census[2] + ") - completing anyway.");
            }
            sweepWaitRounds = 0;
            sweepWaitLogged = false;
            sweepReArmSig = null;
            mod.info("§6[SWEEP-VERIFY]§7 batch " + mod.currentBatch + " gate: blocks=0 items=0 census[ready="
                + census[0] + " falling=" + census[1] + " pickupDelay=" + census[2] + " owned=" + census[3]
                + "] pendBrk=" + pendBrk + " vanillaQ=" + mod.engine.mod.engine.needsVanillaBreak.size());
            mod.info("§aBatch " + mod.currentBatch + " COMPLETE: printed and fully recycled.");
            int finishedBatch = mod.currentBatch;
            mod.currentBuildPlan = null;
            mod.breakStage = 0;
            mod.state = State.StandBy;
            Utils.setForwardPressed(false);
            SlaveSystem.sendMessageToMaster("step:done:" + finishedBatch);
            return;
        }
        // Inventory full with content remaining: deposit first, then re-verify —
        // otherwise every sweepTick re-arm exits instantly and burns all retries.
        if (mod.restock.mod.restock.freeInventoryCapacity() == 0 && (groundItems > 0 || inFlight > 0)) {
            Pair<BlockPos, Vec3d> depositChest = mod.restock.mod.restock.getBestDepositChest();
            if (depositChest != null) {
                mod.checkpoints.clear();
                mod.checkpoints.add(new Pair(depositChest.getRight(), new Pair("depositBuilding", depositChest.getLeft())));
                mod.checkpoints.add(new Pair(depositChest.getRight(), new Pair("batchExit", null)));
                routes.prependNorthCorridor(depositChest.getRight().x);
                mod.state = State.Mining;
                mod.info("§6[SWEEP-VERIFY]§7 batch " + mod.currentBatch + ": inventory full with "
                    + (groundItems + inFlight) + " drops pending - depositing, then re-verifying.");
                return;
            }
        }
        // Re-arm the sweep. PROGRESS-AWARE: a retry is only consumed when the sweep
        // mod.state (blocks + item states + position) is unchanged since the last re-arm —
        // a sweep that is still breaking/collecting re-arms freely instead of racing
        // a blind 8-cap to a force-complete with content standing.
        String reArmSig = remainingBlocks + "|" + census[0] + "," + census[1] + "," + census[2] + "," + census[3]
            + "|p" + mc.player.getBlockX() + "," + mc.player.getBlockZ();
        boolean frozen = reArmSig.equals(sweepReArmSig);
        sweepReArmSig = reArmSig;
        if (frozen) batchPassRetries++;
        else batchPassRetries = Math.max(0, batchPassRetries - 1);
        if (remainingBlocks > 0 || groundItems > 0 || inFlight > 0) {
            if (batchPassRetries < 8) {
                sweepBudget = 200;   // ~10 seconds of combined sweep
                mod.checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("sweepTick", null)));
                mod.state = State.Walking;
                mod.info("§6[SWEEP-VERIFY]§7 batch " + mod.currentBatch + " SWEEP: blocks=" + remainingBlocks
                    + " items[ready=" + census[0] + " falling=" + census[1] + " pickupDelay=" + census[2]
                    + " owned=" + census[3] + "] pendBrk=" + pendBrk
                    + " vanillaQ=" + mod.engine.mod.engine.needsVanillaBreak.size()
                    + " - re-arm " + (frozen ? "(FROZEN) " : "") + "retry " + batchPassRetries + "/8.");
                return;
            }
            mod.warning("§6[SWEEP-VERIFY]§c batch " + mod.currentBatch + ": sweep FROZEN after " + batchPassRetries
                + " re-arms with blocks=" + remainingBlocks + " items ready=" + census[0]
                + " - completing with content remaining (PERSISTENT REJECTION logs should name the culprit).");
        }
        sweepWaitRounds = 0;
        sweepWaitLogged = false;
        sweepReArmSig = null;
        mod.info("§aBatch " + mod.currentBatch + " COMPLETE: printed and fully recycled.");
        int doneBatch = mod.currentBatch;
        mod.currentBuildPlan = null;
        mod.breakStage = 0;
        mod.state = State.StandBy;
        Utils.setForwardPressed(false);
        SlaveSystem.sendMessageToMaster("step:done:" + doneBatch);
    }






    void processPendingStepCommands() {
        String command;
        synchronized (pendingStepCommands) {
            command = pendingStepCommands.peekFirst();
        }
        if (command == null) return;

        String[] parts = command.split(":");
        String verb = parts[0];
        switch (verb) {
            case "prime" -> {
                if (mod.currentBuildPlan != null && mod.currentBatch < 0) {
                    removePendingStepCommand();
                } else if (mod.state == State.StandBy || mod.currentBuildPlan == null) {
                    removePendingStepCommand();
                    routes.beginPriming();
                }
            }
            case "buildhalf" -> {
                // SPLIT-BUILD batch 0 (creative-verified 0+0+0): "buildhalf:0:f" places
                // floor+borrow (no tops - W1@125 records the floor clean), "buildhalf:0:t"
                // places the west tops (W2@127 records them; floor frozen at 125.5+).
                // Works from ANY mod.state - the tops half arrives while the slave waits
                // in AwaitBreakPermission2 after the floor pass.
                if (parts.length < 3) { removePendingStepCommand(); return; }
                Integer batch = parseStepBatch(parts[1]);
                if (batch == null || batch != 0) { removePendingStepCommand(); return; }
                boolean tops = "t".equals(parts[2]);
                removePendingStepCommand();
                mod.checkpoints.clear();
                printRepairPending = false;
                mod.breakStage = 0;
                mod.break2Permitted = false;
                mod.breakAllPermitted = false;
                mod.fullSweepActive = false;
                HashMap<BlockPos, Block> half = tops
                    ? mod.stepLayers.assembleTopsHalf(batch, mod.lowerMapCorner)
                    : mod.stepLayers.assembleFloorHalf(batch, mod.lowerMapCorner);
                mod.currentBuildPlan = half;
                mod.currentBatch = batch;
                batchPassRetries = 0;
                mod.engine.placementAttempts.clear();
                mod.slave.scan.collectSkips.clear();
                mod.clearResetPending();
                sweepReArmSig = null;
                sweepWaitRounds = 0;
                sweepWaitLogged = false;
                double walkX = mod.lowerMapCorner.getX() + 2.0 * batch + 2.5;
                if (tops) {
                    // TOPS half: MINIMAL route - no supply (materials at hand), no break2
                    // chain. Print pass from whichever side the bot is on, then wait for
                    // breakall directly. The bot just finished the floor pass, so it's
                    // already near lane 2 and materials are in inventory.
                    // FIXED CYCLE: print N->S like every batch (no mirroring)
                    double startZ = mod.lowerMapCorner.getZ() - 2;
                    double farZ = mod.lowerMapCorner.getZ() + 128.7;
                    mod.checkpoints.add(new Pair(new Vec3d(walkX, mod.lowerMapCorner.getY(), startZ), new Pair("batchBegin", null)));
                    mod.checkpoints.add(new Pair(new Vec3d(walkX, mod.lowerMapCorner.getY(), farZ), new Pair("printDone", null)));
                    // "awaitBreak2" (NOT "awaitBreakAll") - only this sends step:ready:N,
                    // which the master needs to start W2. The breakall handler's batch-0
                    // split path then picks the slave up from AwaitBreakPermission2.
                    mod.checkpoints.add(new Pair(new Vec3d(walkX, mod.lowerMapCorner.getY(), farZ), new Pair("awaitBreak2", null)));
                    routes.prependLaneCentering(walkX);
                    mod.state = State.Walking;
                    Utils.setForwardPressed(false);
                    mod.info("§aBatch 0 split TOPS: §b" + half.size() + "§7 blocks (direct pass, no resupply).");
                } else {
                    // FLOOR half: normal batch start (supply + full print route)
                    routes.beginBatch(batch);
                    mod.currentBuildPlan = half;
                    mod.info("§aBatch 0 split FLOOR: §b" + half.size() + "§7 blocks.");
                }
            }
            case "build" -> {
                if (parts.length < 2) {
                    removePendingStepCommand();
                    return;
                }
                Integer batch = parseStepBatch(parts[1]);
                if (batch == null) {
                    removePendingStepCommand();
                    return;
                }
                if (mod.currentBuildPlan != null && mod.currentBatch == batch) {
                    // MASTER-CRASH RESYNC: the plan already exists but the master
                    // resumed batch k and re-sent the command - re-arm the route from
                    // here (idempotent: the print pass world-verifies standing blocks).
                    // Without this the slave sat in its await mod.state consuming the
                    // command while the master waited for a ready:k that never re-fired.
                    removePendingStepCommand();
                    mod.checkpoints.clear();
                    routes.resumeBatchRoute(0);
                    routes.insertNorthReEntry(0);
                    routes.prependNorthCorridor(routes.currentWalkLaneX());
                    mod.state = State.Walking;
                    mod.info("§eBatch " + batch + " re-armed by repeated build command (master resync).");
                } else if (mod.state == State.StandBy || mod.currentBuildPlan == null) {
                    removePendingStepCommand();
                    routes.beginBatch(batch);
                } else if (mod.currentBatch != batch) {
                    removePendingStepCommand();
                    mod.info("§7Stale build:" + batch + " dropped (working batch " + mod.currentBatch + ").");
                }
            }
            case "break2" -> {
                if (mod.state == State.AwaitBreakPermission2) {
                    removePendingStepCommand();
                    mod.break2Permitted = true;
                } else if (mod.currentBuildPlan != null) {
                    // MASTER-CRASH RESYNC: break(2) is un-numbered - arm the phase
                    // directly from whatever mod.state the slave was left in.
                    removePendingStepCommand();
                    mod.checkpoints.clear();
                    mod.restock.mod.restock.ensurePickaxeSlot8();
                    mod.breakStage = 2;
                    mod.state = State.Mining;
                    routes.resumeBatchRoute(2);
                    routes.insertNorthReEntry(0);
                    routes.prependNorthCorridor(routes.currentWalkLaneX());
                    mod.info("§eBatch " + mod.currentBatch + " break(2) armed by command (master resync).");
                }
            }
            case "breakall" -> {
                // Batch 0 split flow: breakall arrives while the slave waits at
                // awaitBreak2 (no break(2) was ever sent). Skip straight to break-all.
                if (mod.currentBatch == 0 && mod.state == State.AwaitBreakPermission2) {
                    removePendingStepCommand();
                    mod.breakAllPermitted = true;
                    mod.checkpoints.clear();
                    mod.slave.routes.startBreakAll();
                    return;
                }
                if (mod.state == State.AwaitBreakPermissionAll) {
                    removePendingStepCommand();
                    mod.breakAllPermitted = true;
                } else if (mod.currentBuildPlan != null) {
                    // MASTER-CRASH RESYNC: break-all is un-numbered - arm directly.
                    removePendingStepCommand();
                    mod.checkpoints.clear();
                    mod.slave.routes.startBreakAll();
                    mod.info("§eBatch " + mod.currentBatch + " break-all armed by command (master resync).");
                }
            }
            case "close" -> {
                if (mod.state == State.StandBy || mod.currentBuildPlan == null) {
                    removePendingStepCommand();
                    routes.beginClosing();
                }
            }
            case "closebreak" -> {
                if (mod.state == State.AwaitClosingBreak) {
                    removePendingStepCommand();
                    routes.startClosingBreak();
                }
            }
            case "abort" -> {
                removePendingStepCommand();
                reset.stepAbort();
            }
            default -> removePendingStepCommand();
        }
    }

    void removePendingStepCommand() {
        synchronized (pendingStepCommands) {
            if (!pendingStepCommands.isEmpty()) pendingStepCommands.removeFirst();
        }
    }

    Integer parseStepBatch(String value) {
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }








    void verifyPrintPass() {
        int[] defects = scan.planDefects();
        if (defects[0] == 0 && defects[1] == 0) {
            mod.info("§aBatch " + mod.currentBatch + " printed: §b" + mod.currentBuildPlan.size() + "§7 blocks placed (world-verified).");
            return;
        }
        printRepairPending = true;
        mod.info("§eBatch " + mod.currentBatch + " print has §c" + defects[0] + "§e missing / §c" + defects[1] + "§e wrong - repairing from lane -1 on the way north, then one more lane 2 pass.");
    }

    boolean verifyCurrentPlan(String label) {
        if (mod.currentBuildPlan == null) {
            mod.error(label + " has no active placement plan.");
            mod.state = State.StandBy;
            return false;
        }
        int[] defects = scan.planDefects();
        if (defects[0] == 0 && defects[1] == 0) return true;
        mod.error(label + " placement incomplete: " + defects[0] + " missing, " + defects[1] + " wrong. Stopped.");
        mod.state = State.StandBy;
        Utils.setForwardPressed(false);
        return false;
    }

    boolean verifyPrintPass2() {
        int[] defects = scan.planDefects();
        if (defects[0] == 0 && defects[1] == 0) {
            mod.info("§aBatch " + mod.currentBatch + " repair complete - plan fully placed.");
            return true;
        }
        mod.error("Batch " + mod.currentBatch + " print still incomplete after the repair passes: " + defects[0] + " missing, " + defects[1] + " wrong. Stopped for diagnosis.");
        mod.state = State.StandBy;
        Utils.setForwardPressed(false);
        return false;
    }

    boolean verifyBreak2() {
        int westX = mod.lowerMapCorner.getX() + 2 * mod.currentBatch;
        ArrayList<BlockPos> remaining = new ArrayList<>();
        for (BlockPos pos : mod.currentBuildPlan.keySet()) {
            if (pos.getX() >= westX + 2 || pos.getY() < mod.lowerMapCorner.getY() + 2) continue;
            if (!mod.miningBlockState(pos).isAir()) remaining.add(pos);
        }
        if (remaining.isEmpty()) {
            mod.info("§aBatch " + mod.currentBatch + " break(2) complete (west Y2/Y3 down).");
            return true;
        }
        if (batchPassRetries >= 2) {
            mod.error("Batch " + mod.currentBatch + " break(2) incomplete after retry: " + remaining.size() + " blocks left. Stopped.");
            mod.state = State.StandBy;
            Utils.setForwardPressed(false);
            return false;
        }
        batchPassRetries++;
        mod.info("§eBatch " + mod.currentBatch + " break(2) leftover (" + remaining.size() + ") - fixed-direction retry pass (S->N).");
        double lane2X = mod.lowerMapCorner.getX() + 2.0 * mod.currentBatch + 2.5;
        mod.checkpoints.add(new Pair(new Vec3d(lane2X, mod.lowerMapCorner.getY(), mod.lowerMapCorner.getZ() + 128.7), new Pair("sprint", null)));
        mod.checkpoints.add(new Pair(new Vec3d(lane2X, mod.lowerMapCorner.getY(), mod.lowerMapCorner.getZ() - 2), new Pair("break2Done", null)));
        mod.state = State.Mining;
        return false;
    }


















    void updateTransitSpeed() {
        if (mod.sprinting.get() != StepCheckered.SprintMode.Dynamic) {
            if (transitSpeedOn) setTransitSpeed(false);
            return;
        }
        boolean printing = mod.state == State.Walking && mod.breakStage == 0 && mod.currentBuildPlan != null
            && !mod.checkpoints.isEmpty() && StepCheckered.ALLOW_PLACE_ACTIONS.contains(mod.checkpoints.getFirst().getRight().getLeft());
        // BREAK PASSES NEVER SPEED-HACK (live 2026-10-21: at 7.12 bps the bot outruns
        // the server's echo AND reach — breaks expire out-of-reach into a frozen vanilla
        // queue (51 blocks stuck) and the server rubber-bands the movement itself. The
        // math cap is 30 bps / 5.25 blocks-per-row = 5.7 bps max useful pace; sprint
        // (~5.6) sits just under it).
        boolean breaking = mod.state == State.Mining || (mod.breakStage != 0 && mod.state != State.StandBy);
        boolean want = !printing && !breaking && mod.state != State.StandBy;
        if (want != transitSpeedOn) setTransitSpeed(want);
    }

    void setTransitSpeed(boolean on) {
        try {
            Speed speed = Modules.get().get(Speed.class);
            if (on) {
                speed.speedMode.set(SpeedModes.Vanilla);
                speed.vanillaSpeed.set(7.192);
                speed.timer.set(1.0);
                if (!speed.isActive()) speed.toggle();
            } else if (speed.isActive()) {
                speed.toggle();   // onDeactivate clears the Timer override
            }
        } catch (Exception e) {
            mod.warning("Speed module control failed: " + e.getMessage());
        }
        transitSpeedOn = on;
    }

    boolean consumeStartBuildingRequest() {
        if (!mod.startBuildingRequested) return false;
        mod.startBuildingRequested = false;
        if (mod.isActive() && !SlaveSystem.isSlave && !SlaveSystem.slaves.isEmpty()) {
            mod.startBuilding();
        }
        return true;
    }

    boolean consumePendingDisable() {
        if (!pendingDisable) return false;
        pendingDisable = false;
        if (mod.isActive()) mod.toggle();
        return true;
    }


    void tick(TickEvent.Pre event) {
        mod.master.maps.serviceControlHold();
        // START ALL deferred trigger + deferred disable both run through the tick:
        // calling toggle() from inside onActivate/onTick handlers makes Meteor re-enter
        // the activation path recursively (StackOverflowError -> client death)
        if (consumeStartBuildingRequest()) return;

        // Supply-run cooldown ticks down; mod.engine.tryPlacingBlock can't re-trigger mid-trip
        if (restockCooldown > 0) restockCooldown--;

        // Placement cooldowns tick down (litematica-style): expired positions become
        // eligible for retry; successfully placed positions are already skipped by the
        // world-state check in mod.engine.getNextBatchPlacementPos
        if (!mod.engine.placementCooldowns.isEmpty()) {
            mod.engine.placementCooldowns.replaceAll((pos, ticks) -> ticks - 1);
            mod.engine.placementCooldowns.values().removeIf(ticks -> ticks <= 0);
        }

        if (consumePendingDisable()) return;

        if (SlaveSystem.isSlave) {
            if (tryResumePendingReset()) return;
            processPendingStepCommands();
        } else if (mod.master.stepMasterStage != StepMasterStage.IDLE) {
            mod.master.tickStepMaster();
            return;
        }

        // Manual break calls remain available for isolated slave diagnostics; the normal run
        // reaches these flags through the step-checker TCP protocol.
        if (mod.engine.armPermittedBreakPhase()) return;

        // Inventory truly out of space mid-break-all (partial-stack headroom included):
        // deposit the building blocks, then resume
        if (mod.breakStage == 4 && mod.state.equals(State.Mining) && mod.restock.freeInventoryCapacity() == 0) {
            routes.pauseBreakAllForDeposit();
            return;
        }

        if (mod.state == null) return;

        logTickDebug();
        sendSlaveHeartbeat();

        mod.restock.tickInteractRetry();

        updateTransitSpeed();

        if (mod.timeoutTicks > 0) {
            if (mc.player.isOnGround()) mod.timeoutTicks--;
            Utils.setForwardPressed(false);
            return;
        }

        if (mod.engine.consumeHotbarSwap()) return;

        if (mod.restock.drainRestockBacklog()) return;

        if (mod.restock.drainDepositBacklog()) return;

        reset.armResetFeedTarget();

        mod.engine.tickBreakConfirmations();

        mod.engine.pumpArmedBreakTarget();

        if (mod.restock.processDeferredInventoryIO()) return;

        if (!(mod.state.equals(State.Walking) || mod.state.equals(State.Mining))) return;
        if (mod.checkpoints.isEmpty()) return;

        // ADAPTIVE PACE GOVERNOR - the ONE place that decides whether the bot may
        // advance while working. Print mode: adaptivePaceHold; break mode: the
        // forward-only approach/verify cadence (see mod.engine.breakApproachHold()).
        Utils.setForwardPressed(!mod.engine.adaptivePaceHold() && !mod.engine.breakApproachHold());

        Vec3d goal = mod.checkpoints.get(0).getLeft();
        if (PlayerUtils.distanceTo(goal.add(0, mc.player.getY() - goal.y, 0)) < mod.checkpointBuffer.get()) {
            if (handleCheckpointArrival(goal)) return;
            goal = mod.checkpoints.get(0).getLeft();
        }

        String nextAction = mod.checkpoints.get(0).getRight().getLeft();

        steerTowardGoal(goal, nextAction);
        applySprintMode(nextAction);
        if (!StepCheckered.ALLOW_PLACE_ACTIONS.contains(nextAction)) return;

        BlockPos tempPos = pickWorkTarget();

        // ROLLING HOTBAR LOOKAHEAD (user rule: "always, for the whole batch") - runs
        // BEFORE the range gate (v2 fix: the first version sat after it and was
        // unreachable exactly when a swap was needed).
        if (mod.state.equals(State.Walking) && mod.breakStage == 0 && mod.currentBuildPlan != null && mod.engine.rateTick == 10) {
            mod.engine.rollingHotbarLookahead();
        }
        // Range gate uses EYE distance - the same metric the target pickers use. A
        // feet-based 3D check here rejects upper-layer blocks (y+2.5 above the feet)
        // that the picker armed: the adaptive print pace then held the bot on a target
        // this gate refused to attempt - the total silent stall of the 2026-10-20
        // 01:01 run (2 PLACE lines at the staging, then nothing for minutes).
        if (tempPos == null || mc.player.getEyePos().distanceTo(tempPos.toCenterPos()) > mod.interactionRange.get()) return;
        mod.nextBlockPos = tempPos;

        actOnWorkTarget();
    }
}
