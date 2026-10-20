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

    // --- Slave run state (moved from the module) ---
    boolean printRepairPending;                        // print pass ended with missing blocks
    int batchPassRetries;
    int sweepBudget;                                   // tick budget for the straggler sweep
    int restockCooldown;                               // blocks repeat supply-run triggers
    HashMap<BlockPos, Integer> collectSkips;           // collection run: picks per drop (10 = blacklisted)
    int resetLadderCol;                                // 0..31 current column (32 = verify phase)
    int resetLadderSeg;                                // 0..4  current z-segment (resume anchor)
    int resetLadderEndCol;                             // last column of the scoped reset
    String resetStallSig;                              // reset arrival stall detector signature
    int resetStallRounds;
    int sweepWaitRounds;
    boolean sweepWaitLogged;
    String sweepReArmSig;
    BlockPos jamWatchTarget;                           // feed-jam detector target
    int jamWatchTicks;
    int heartbeatTicks;
    String debugLastAction;
    State debugPreviousState;
    int stuckCheckpointCount;
    String lastCheckpointAction;
    boolean pendingDisable;                            // deferred toggle (requestDisable)
    boolean transitSpeedOn;
    private final ArrayDeque<String> pendingStepCommands = new ArrayDeque<>();

    /** RESET ladder z-segments: inclusive row ranges and station centres (relative). */
    private static final int[] RESET_SEG_START = {0, 8, 16, 24, 32, 40, 48, 56, 64, 72, 80, 88, 96, 104, 112, 120};
    private static final int[] RESET_SEG_END = {7, 15, 23, 31, 39, 47, 55, 63, 71, 79, 87, 95, 103, 111, 119, 127};
    private static final int[] RESET_SEG_CENTER_Z = {4, 12, 20, 28, 36, 44, 52, 60, 68, 76, 84, 92, 100, 108, 116, 124};
    /** Block drops skid up to 3-4 blocks outside the segment / print box (user-measured). */
    private static final int RESET_ITEM_MARGIN = 4;

    SlaveEngine(StepCheckered mod) {
        this.mod = mod;
    }

    /** Full slave-state reset (module onActivation path). */
    void resetForActivation() {
        printRepairPending = false;
        batchPassRetries = 0;
        sweepBudget = 0;
        restockCooldown = 0;
        collectSkips = new HashMap<>();
        resetLadderCol = 0;
        resetLadderSeg = 0;
        resetLadderEndCol = 0;
        resetStallSig = null;
        resetStallRounds = 0;
        sweepWaitRounds = 0;
        sweepWaitLogged = false;
        sweepReArmSig = null;
        jamWatchTarget = null;
        jamWatchTicks = 0;
        heartbeatTicks = 0;
        debugLastAction = null;
        debugPreviousState = null;
        stuckCheckpointCount = 0;
        lastCheckpointAction = null;
        pendingDisable = false;
        synchronized (pendingStepCommands) {
            pendingStepCommands.clear();
        }
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
        resetLadderEndCol = Math.min(31, Math.max(0, scope));
        resetLadderCol = 0;
        resetLadderSeg = mc.player.getBlockZ() - mod.lowerMapCorner.getZ() > 64 ? 4
            : (mc.player.getBlockZ() - mod.lowerMapCorner.getZ() > 26 ? 2 : 0);
        queueResetLadder(resetLadderCol, 0);
        prependNorthCorridor(mod.checkpoints.getFirst().getLeft().x);
        mod.state = State.Mining;
        mod.breakStage = 4;
        return true;
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
            mod.nextBlockPos = findNearestBlockInArea(bx0, bx1, bz0, bz1);
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
                pauseForRestockTrip(true);
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
                finishClosing();
                return true;
            case "sweepTick":
                return handleSweepTickArrival();
            case "resetPass":
            case "resetStation":
                return handleResetArrival(checkpointAction);
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
        int remainingBlocks = countRemainingPlanBlocks();
        int[] census = itemCensusInBatchWindow();
        int bwx0 = batchWindowWestX() - 2;
        int pendBrk = countPendingInRange(bwx0, bwx0 + 7, mod.lowerMapCorner.getZ() - 3, mod.lowerMapCorner.getZ() + 129);
        int vanillaQ = countVanillaInRange(bwx0, bwx0 + 7, mod.lowerMapCorner.getZ() - 3, mod.lowerMapCorner.getZ() + 129);
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
        BlockPos straggler = nearestRemainingPlanBlock();
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
        int[] cCensus = itemCensusInBatchWindow();
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
        ItemEntity next = nearestCollectTarget();
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
        ItemEntity nearestItem = mod.restock.mod.restock.freeInventoryCapacity() > 0 ? findNearestGroundItem(3) : null;
        double itemDist = nearestItem != null
            ? mc.player.getEyePos().distanceTo(nearestItem.getEntityPos()) : Double.MAX_VALUE;
        if (sweepBudget > 0 && mod.restock.mod.restock.freeInventoryCapacity() > 0) {
            if (nearestBlock != null && (nearestItem == null || blockDist <= itemDist)) {
                mod.checkpoints.addFirst(new Pair(nearestBlock.toCenterPos(), new Pair("sweepTick", null)));
                mod.breakStage = 4;
                mod.state = State.Mining;
                return true;
            } else if (nearestItem != null) {
                targetGroundItem(nearestItem, "sweepTick");
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
            mod.restock.mod.restock.movePickaxeToSlot8();
            if (!mc.player.getInventory().getStack(8).isIn(ItemTags.PICKAXES)) {
                pauseForRestockTrip(true);
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
        if (mod.restock.mod.restock.freeInventoryCapacity() == 0 && findNearestItemInArea(ix0, ix1, iz0, iz1) != null) {
            Pair<BlockPos, Vec3d> ladderChest = mod.restock.mod.restock.getBestDepositChest();
            if (ladderChest != null) {
                mod.checkpoints.clear();
                mod.checkpoints.add(new Pair(ladderChest.getRight(), new Pair("depositBuilding", ladderChest.getLeft())));
                queueResetLadder(resetLadderCol, resetLadderSeg);
                prependNorthCorridor(ladderChest.getRight().x);
                mod.state = State.Mining;
                mod.info("§7Inventory full - depositing, then resuming the reset ladder.");
                mod.state = State.Mining;  // resume in Mining: feed stays active on the return walk
                return true;
            }
        }
        BlockPos lTarget = findNearestBlockInArea(lx0, lx1, lz0, lz1);
        ItemEntity lItem = mod.restock.mod.restock.freeInventoryCapacity() > 0 ? findNearestItemInArea(ix0, ix1, iz0, iz1) : null;
        // RESET-VERIFY census: everything the CLEAN verdict is about to be based on,
        // including content the filtered scans above cannot see.
        int[] census = itemCensusInArea(ix0, ix1, iz0, iz1);
        int pendBrk = countPendingInRange(lx0, lx1, lz0, lz1);
        int vanilla = countVanillaInRange(lx0, lx1, lz0, lz1);
        // STALL DETECTOR (replaces the generic anti-stuck force-skip for this
        // handler): the signature covers block count, every item mod.state and the
        // bot position — ANY progress changes it (blocks break, drops land,
        // states tick over, the bot walks). Only 80 consecutive FROZEN arrivals
        // force-advance, with a full dump of what is being left behind.
        String arrivalSig = resetLadderCol + ":" + resetLadderSeg + "|b" + countBlocksInArea(lx0, lx1, lz0, lz1)
            + "|i" + census[0] + "," + census[1] + "," + census[2] + "," + census[3]
            + "|p" + mc.player.getBlockX() + "," + mc.player.getBlockZ();
        boolean sigChanged = !arrivalSig.equals(resetStallSig);
        if (!sigChanged) {
            resetStallRounds++;
            if (resetStallRounds > 80) {
                mod.warning("§6[RESET-VERIFY]§c STALL: col " + resetLadderCol + " seg " + resetLadderSeg
                    + " frozen for 80+ arrival rounds - blocks=" + countBlocksInArea(lx0, lx1, lz0, lz1)
                    + " items+m" + RESET_ITEM_MARGIN + "[ready=" + census[0] + " falling=" + census[1]
                    + " pickupDelay=" + census[2] + " owned=" + census[3] + "]"
                    + " pendBrk=" + pendBrk + " - advancing WITHOUT clearing (verify walk will re-scope).");
                resetStallRounds = 0;
                resetStallSig = null;
                return true;   // no re-queue: the ladder advances
            }
        } else {
            resetStallSig = arrivalSig;
            resetStallRounds = 0;
        }
        // Census log only on mod.state change: dwell waits and in-place breaking
        // fire arrivals every tick — logging each would flood the chat.
        if (sigChanged) {
            mod.info("§6[RESET-VERIFY]§7 col " + resetLadderCol + " seg " + resetLadderSeg
                + " rows " + (lz0 - mod.lowerMapCorner.getZ()) + "-" + (lz1 - mod.lowerMapCorner.getZ())
                + " §fblocks=" + (lTarget != null ? "1+ @" + (lTarget.getX() - mod.lowerMapCorner.getX()) + "," + (lTarget.getY() - mod.lowerMapCorner.getY()) + "," + (lTarget.getZ() - mod.lowerMapCorner.getZ()) : "0")
                + " §7items+m" + RESET_ITEM_MARGIN + "[ready=" + census[0] + " falling=" + census[1] + " pickupDelay=" + census[2] + " owned=" + census[3] + "]"
                + " §3pendBrk=" + pendBrk + " vanillaQ=" + vanilla
                + (mod.restock.mod.restock.freeInventoryCapacity() == 0 ? " §cINV-FULL(item scan skipped)" : ""));
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

    void beginBatch(int k) {
        if (!mod.initialSetup()) {
            mod.state = State.StandBy;
            return;
        }
        mod.currentBatch = k;
        batchPassRetries = 0;
        mod.engine.placementAttempts.clear();
        collectSkips.clear();
        mod.clearResetPending();   // a new run supersedes any interrupted reset
        sweepReArmSig = null;
        sweepWaitRounds = 0;
        sweepWaitLogged = false;
        mod.breakStage = 0;
        printRepairPending = false;
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
        // the FIRST distinct materials of this batch's plan (walking order), so the
        // print pass rarely stalls on mid-run swaps - the old on-demand one-slot
        // swapping was the print delay. Runs before the route; one swap per material.
        {
            LinkedHashSet<Item> coming = new LinkedHashSet<>();
            for (Map.Entry<BlockPos, Block> le : mod.currentBuildPlan.entrySet()) {
                if (coming.size() >= mod.availableHotBarSlots.size()) break;
                coming.add(le.getValue().asItem());
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
        printRepairPending = false;
        mod.engine.placementAttempts.clear();
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
        printRepairPending = false;
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

    void finishBatch() {
        // COMBINED SWEEP: check both remaining blocks and ground items at once.
        // sweepTick handles whichever is nearest (block → break, item → collect).
        // No separate retry walks, no zigzag — one spatial-awareness pass.
        int remainingBlocks = countRemainingPlanBlocks();
        int groundItems = countGroundItemsInWindow();
        int[] census = itemCensusInBatchWindow();
        int pendBrk = countPendingInRange(batchWindowWestX() - 2, batchWindowWestX() + 5,
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
                prependNorthCorridor(depositChest.getRight().x);
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

    void stepAbort() {
        mod.info("§cRESET: full-area clear starting.");
        // Force-clear ALL active mod.state — any phase the slave was in is now dead
        mod.checkpoints.clear();
        printRepairPending = false;
        mod.restock.mod.restock.depositBacklogSlots.clear();
        mod.restock.restockBacklogSlots.clear();
        mod.restock.mod.restock.restockList.clear();
        batchPassRetries = 0;
        mod.currentBuildPlan = null;
        mod.breakStage = 0;
        mod.nextBlockPos = null;
        mod.engine.mod.engine.vanillaBreakRetries.clear();
        jamWatchTarget = null;
        jamWatchTicks = 0;
        resetStallSig = null;
        resetStallRounds = 0;
        sweepWaitRounds = 0;
        sweepWaitLogged = false;
        sweepReArmSig = null;
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
        queueResetLadder(resetLadderCol, 0);

        // Route via the north corridor (never cross the map area diagonally)
        double corridorZ = mod.lowerMapCorner.getZ() - 2;
        if (Math.abs(mc.player.getZ() - corridorZ) > 1.5) {
            mod.checkpoints.add(0, new Pair(new Vec3d(mc.player.getX(), mod.lowerMapCorner.getY(), corridorZ), new Pair("sprint", null)));
        }

        mod.state = State.Mining;   // Mining walks AND breaks: the serpentine pass clears as it goes
        mod.breakStage = 4;
        mod.fullSweepActive = true;
        sweepBudget = 600;
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
        Pair<BlockPos, Vec3d> doneChest = mod.restock.mod.restock.getBestDepositChest();
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

    void queueStepBuildPreparation(double stagingX) {
        mod.restock.mod.restock.refillFromCurrentPlan();
        boolean materialRestockQueued = !mod.restock.mod.restock.restockList.isEmpty();

        // UNNEEDED-MATERIAL DUMP (user rule, 2026-10-20), inlined body-only (hotswap-
        // safe): deposit carried building materials that NO remaining batch (or the
        // closing) will ever place - leftovers from finished batches otherwise squat on
        // slots for the rest of the run. Pre-fills mod.restock.mod.restock.depositBacklogSlots (the deposit
        // handler treats a pre-filled backlog at chest-open as a filtered dump) and
        // rides the SAME north-corridor trip, before the first refill checkpoint.
        if (mod.lowerMapCorner != null && mod.stepLayers != null && mod.currentBatch >= 0) {
            // QUANTITATIVE future need: per-item block counts over all remaining
            // batches + closing (nothing stands yet for future batches, so full plan
            // counts). Excess beyond that need is dead weight fragmenting slots - the
            // live batch-1 stall: inventory full of prior-run drops, every item
            // "needed" by SOME future batch, zero free slots at the chest withdrawal.
            HashMap<Item, Integer> futureNeed = new HashMap<>();
            for (int fb = mod.currentBatch; fb <= 62; fb++) {
                for (Block fbBlock : mod.assembleBatch(fb).values()) {
                    Item fbItem = fbBlock.asItem();
                    futureNeed.put(fbItem, futureNeed.getOrDefault(fbItem, 0) + 1);
                }
            }
            for (Block cb : mod.assembleClosing().values()) {
                Item cbItem = cb.asItem();
                futureNeed.put(cbItem, futureNeed.getOrDefault(cbItem, 0) + 1);
            }
            HashMap<Item, Integer> carried = new HashMap<>();
            HashMap<Item, ArrayList<Integer>> itemSlots = new HashMap<>();
            for (int dSlot = 0; dSlot < 36; dSlot++) {
                ItemStack dStack = mc.player.getInventory().getStack(dSlot);
                if (dStack.isEmpty() || ToolUtils.isTool(dStack) || dStack.contains(DataComponentTypes.FOOD)) continue;
                if (!mod.materialDict.containsKey(dStack.getItem())) continue;
                Item dItem = dStack.getItem();
                carried.put(dItem, carried.getOrDefault(dItem, 0) + dStack.getCount());
                ArrayList<Integer> listForItem = itemSlots.get(dItem);
                if (listForItem == null) { listForItem = new ArrayList<>(); itemSlots.put(dItem, listForItem); }
                listForItem.add(dSlot);
            }
            HashSet<Item> unneededItems = null;
            ArrayList<Integer> dumpSlots = new ArrayList<>();
            for (Map.Entry<Item, ArrayList<Integer>> ent : itemSlots.entrySet()) {
                int need = futureNeed.getOrDefault(ent.getKey(), 0);
                int have = carried.getOrDefault(ent.getKey(), 0);
                if (have <= need) continue;   // fully used later - keep every stack
                int toDump = have - need;     // deposit the excess, newest slots first
                ArrayList<Integer> slotsOfItem = ent.getValue();
                for (int si = slotsOfItem.size() - 1; si >= 0 && toDump > 0; si--) {
                    int dSlot = slotsOfItem.get(si);
                    dumpSlots.add(dSlot);
                    toDump -= mc.player.getInventory().getStack(dSlot).getCount();
                    if (unneededItems == null) unneededItems = new HashSet<>();
                    if (!unneededItems.contains(ent.getKey())) { unneededItems.add(ent.getKey()); }
                }
            }
            if (unneededItems != null) {
                Pair<BlockPos, Vec3d> dumpChest = mod.restock.mod.restock.getBestDepositChest();
                if (dumpChest != null) {
                    mod.restock.mod.restock.depositBacklogSlots.clear();
                    mod.restock.mod.restock.depositBacklogSlots.addAll(dumpSlots);
                    StringBuilder dumpNames = new StringBuilder();
                    for (Item dn : unneededItems) { if (dumpNames.length() > 0) dumpNames.append(", "); dumpNames.append(dn.getName().getString()); }
                    mod.info("§7Prep dump: depositing materials no future batch uses (" + dumpNames + ").");
                    int insertAt = 0;
                    for (int ci = 0; ci < mod.checkpoints.size(); ci++) {
                        String ca = mod.checkpoints.get(ci).getRight().getLeft();
                        if ("refill".equals(ca) || "mod.foodChest".equals(ca)) { insertAt = ci; break; }
                    }
                    mod.checkpoints.add(insertAt, new Pair(new Vec3d(dumpChest.getRight().x, dumpChest.getRight().y, dumpChest.getRight().z), new Pair("depositUnneeded", dumpChest.getLeft())));
                }
            }
        }
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
        // BEST-TOOL PREP — AXE with the PICKAXE'S predictive rule (user rule, live
        // 2026-10-21: "same stock restock as pickaxe - never exchange or withdraw
        // until min-durability is reached"): only when the batch has axe-class blocks
        // AND the best carried axe is missing or BELOW min-durability. A worn-but-present
        // axe above threshold is KEPT (no withdrawal); below threshold it is exchanged.
        if (mod.currentBuildPlan != null) {
            boolean needsWoodTool = false;
            for (Block b : mod.currentBuildPlan.values()) {
                if (b.getDefaultState().isIn(BlockTags.AXE_MINEABLE)) { needsWoodTool = true; break; }
            }
            if (needsWoodTool) {
                // ONE scan pass: best remaining durability + carried count (the one-axe
                // invariant's evidence)
                int bestAxeRemaining = -1;
                int axeCarried = 0;
                for (int aSlot = 0; aSlot < 36; aSlot++) {
                    ItemStack aStack = mc.player.getInventory().getStack(aSlot);
                    if (aStack.isEmpty() || !aStack.isIn(ItemTags.AXES)) continue;
                    axeCarried++;
                    bestAxeRemaining = Math.max(bestAxeRemaining, ToolUtils.getRemainingDamage(aStack));
                }
                if (bestAxeRemaining < mod.minDurability.get() && axeCarried == 0) {
                    ItemStack axe = null;
                    float axeSpeed = -1;
                    for (ItemStack tool : mod.toolSet) {
                        if (!tool.isIn(ItemTags.AXES)) continue;
                        float sp = tool.getMiningSpeedMultiplier(net.minecraft.block.Blocks.OAK_PLANKS.getDefaultState());
                        if (sp > axeSpeed) { axeSpeed = sp; axe = tool; }
                    }
                    if (axe != null) {
                        mod.info("§7Axe missing - fetching one at the batch-prep trip.");
                        mod.restock.mod.restock.restockList.add(Triple.of(axe.getItem(), 1, 1));
                    }
                } else if (axeCarried > 1) {
                    // ONE-AXE INVARIANT (mirrors the pickaxe's): the best axe survives;
                    // spares ride forever otherwise. Deposit-only visit (0 stacks = take
                    // nothing) parks the spares at the chest. NEVER withdraws a new axe
                    // while any axe above min-durability is carried.
                    Item spareAxe = null;
                    float spareSpeed = -1;
                    for (ItemStack tool : mod.toolSet) {
                        if (!tool.isIn(ItemTags.AXES)) continue;
                        float sp = tool.getMiningSpeedMultiplier(net.minecraft.block.Blocks.OAK_PLANKS.getDefaultState());
                        if (sp > spareSpeed) { spareSpeed = sp; spareAxe = tool.getItem(); }
                    }
                    if (spareAxe != null && bestAxeRemaining >= mod.minDurability.get()) {
                        mod.info("§7" + (axeCarried - 1) + " spare axe(s) carried (best at " + bestAxeRemaining
                            + " durability, above min " + mod.minDurability.get() + ") - depositing spares, NO new axe fetched.");
                        mod.restock.mod.restock.restockList.add(Triple.of(spareAxe, 0, 0));
                        if (!materialRestockQueued) mod.restock.mod.restock.addClosestRestockCheckpoint();
                    }
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
                    beginPriming();
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
                collectSkips.clear();
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
                    prependLaneCentering(walkX);
                    mod.state = State.Walking;
                    Utils.setForwardPressed(false);
                    mod.info("§aBatch 0 split TOPS: §b" + half.size() + "§7 blocks (direct pass, no resupply).");
                } else {
                    // FLOOR half: normal batch start (supply + full print route)
                    beginBatch(batch);
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
                    resumeBatchRoute(0);
                    insertNorthReEntry(0);
                    prependNorthCorridor(currentWalkLaneX());
                    mod.state = State.Walking;
                    mod.info("§eBatch " + batch + " re-armed by repeated build command (master resync).");
                } else if (mod.state == State.StandBy || mod.currentBuildPlan == null) {
                    removePendingStepCommand();
                    beginBatch(batch);
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
                    resumeBatchRoute(2);
                    insertNorthReEntry(0);
                    prependNorthCorridor(currentWalkLaneX());
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
                    mod.slave.startBreakAll();
                    return;
                }
                if (mod.state == State.AwaitBreakPermissionAll) {
                    removePendingStepCommand();
                    mod.breakAllPermitted = true;
                } else if (mod.currentBuildPlan != null) {
                    // MASTER-CRASH RESYNC: break-all is un-numbered - arm directly.
                    removePendingStepCommand();
                    mod.checkpoints.clear();
                    mod.slave.startBreakAll();
                    mod.info("§eBatch " + mod.currentBatch + " break-all armed by command (master resync).");
                }
            }
            case "close" -> {
                if (mod.state == State.StandBy || mod.currentBuildPlan == null) {
                    removePendingStepCommand();
                    beginClosing();
                }
            }
            case "closebreak" -> {
                if (mod.state == State.AwaitClosingBreak) {
                    removePendingStepCommand();
                    startClosingBreak();
                }
            }
            case "abort" -> {
                removePendingStepCommand();
                stepAbort();
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
        if (restockCooldown > 0) return;
        restockCooldown = 100;   // 5 seconds before another supply run can trigger

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
            if (resetLadderCol < 32) {
                queueResetLadder(resetLadderCol, resetLadderSeg);
            } else {
                queueVerifyWalk();
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

        Pair<BlockPos, Vec3d> chest = mod.restock.mod.restock.getBestDepositChest();
        mod.checkpoints.add(new Pair(new Vec3d(walkX, mod.lowerMapCorner.getY(), startZ), new Pair("breakAllBegin", null)));
        mod.checkpoints.add(new Pair(new Vec3d(walkX, mod.lowerMapCorner.getY(), farZ), new Pair("breakAllDone", null)));
        mod.checkpoints.add(new Pair(new Vec3d(walkX, mod.lowerMapCorner.getY(), farZ), new Pair("batchExit", null)));
        if (chest != null) {
            mod.checkpoints.addFirst(new Pair(chest.getRight(), new Pair("depositBuilding", chest.getLeft())));
            prependNorthCorridor(chest.getRight().x);
        } else {
            prependNorthCorridor(walkX);
        }
        mod.state = State.Walking;
        Utils.setForwardPressed(false);
        mod.info("§aBreak-all: depositing building blocks first, then recycling batch " + mod.currentBatch + ".");
    }

    void verifyPrintPass() {
        int[] defects = planDefects();
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
        int[] defects = planDefects();
        if (defects[0] == 0 && defects[1] == 0) return true;
        mod.error(label + " placement incomplete: " + defects[0] + " missing, " + defects[1] + " wrong. Stopped.");
        mod.state = State.StandBy;
        Utils.setForwardPressed(false);
        return false;
    }

    boolean verifyPrintPass2() {
        int[] defects = planDefects();
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

    int[] planDefects() {
        int missing = 0;
        int wrong = 0;
        for (Map.Entry<BlockPos, Block> entry : mod.currentBuildPlan.entrySet()) {
            BlockState worldState = mod.miningBlockState(entry.getKey());
            if (worldState.isAir()) missing++;
            else if (!worldState.getBlock().equals(entry.getValue())) wrong++;
        }
        return new int[]{missing, wrong};
    }

    int batchWindowWestX() {
        return mod.lowerMapCorner.getX() + 2 * Math.max(mod.currentBatch, 0);
    }

    int[] itemCensusInBatchWindow() {
        int westX = batchWindowWestX();
        return itemCensusInArea(westX - 4, westX + 6, mod.lowerMapCorner.getZ() - 3, mod.lowerMapCorner.getZ() + 129);
    }

    int[] itemCensusInArea(int x0, int x1, int z0, int z1) {
        int[] census = new int[4];   // {ready, falling, pickupDelay, owned}
        if (mc.world == null) return census;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (itemEntity.getX() < x0 || itemEntity.getX() > x1 + 1) continue;
            if (itemEntity.getZ() < z0 || itemEntity.getZ() > z1 + 1) continue;
            if (itemEntity.getY() < mod.lowerMapCorner.getY() - 3 || itemEntity.getY() > mod.lowerMapCorner.getY() + 5) continue;
            if (itemEntity.getOwner() != null) census[3]++;
            else if (!itemEntity.groundCollision) census[1]++;
            else if (itemEntity.cannotPickup()) census[2]++;
            else census[0]++;
        }
        return census;
    }

    int countPendingInRange(int x0, int x1, int z0, int z1) {
        int n = 0;
        for (BlockPos pos : mod.engine.mod.engine.breakPending.keySet()) {
            if (pos.getX() >= x0 && pos.getX() <= x1 && pos.getZ() >= z0 && pos.getZ() <= z1) n++;
        }
        return n;
    }

    int countVanillaInRange(int x0, int x1, int z0, int z1) {
        int n = 0;
        for (BlockPos pos : mod.engine.mod.engine.needsVanillaBreak) {
            if (pos.getX() >= x0 && pos.getX() <= x1 && pos.getZ() >= z0 && pos.getZ() <= z1) n++;
        }
        return n;
    }

    int countBlocksInArea(int x0, int x1, int z0, int z1) {
        if (mc.world == null || mod.lowerMapCorner == null) return -1;
        int n = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = mod.lowerMapCorner.getY(); y <= mod.lowerMapCorner.getY() + 3; y++) {
                    if (!mod.miningBlockState(new BlockPos(x, y, z)).isAir()) n++;
                }
            }
        }
        return n;
    }

    BlockPos findNearestBlockInArea(int x0, int x1, int z0, int z1) {
        if (mc.world == null || mc.player == null) return null;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        Vec3d eye = mc.player.getEyePos();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = mod.lowerMapCorner.getY(); y <= mod.lowerMapCorner.getY() + 3; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (mod.miningBlockState(pos).isAir()) continue;
                    double dist = eye.distanceTo(pos.toCenterPos());
                    if (dist < bestDist) { bestDist = dist; best = pos; }
                }
            }
        }
        return best;
    }

    ItemEntity findNearestItemInArea(int x0, int x1, int z0, int z1) {
        if (mc.world == null || mc.player == null) return null;
        ItemEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (!isPickupable(itemEntity)) continue;
            if (itemEntity.getX() < x0 || itemEntity.getX() > x1 + 1) continue;
            if (itemEntity.getZ() < z0 || itemEntity.getZ() > z1 + 1) continue;
            if (itemEntity.getY() < mod.lowerMapCorner.getY() - 3 || itemEntity.getY() > mod.lowerMapCorner.getY() + 5) continue;
            double dist = mc.player.getEyePos().distanceTo(itemEntity.getEntityPos());
            if (dist < nearestDist) { nearestDist = dist; nearest = itemEntity; }
        }
        return nearest;
    }

    boolean isPickupable(ItemEntity itemEntity) {
        return itemEntity.groundCollision && !itemEntity.cannotPickup() && itemEntity.getOwner() == null;
    }

    BlockPos findNearestPrintBoxBlock() {
        return findNearestPrintBoxBlock(Collections.emptySet());
    }

    ItemEntity nearestCollectTarget() {
        if (mc.world == null || mc.player == null) return null;
        int westX = batchWindowWestX();
        ItemEntity best = null, bestAny = null;
        double bestDist = Double.MAX_VALUE, bestAnyDist = Double.MAX_VALUE;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (!isPickupable(itemEntity)) continue;
            if (itemEntity.getX() < westX - 4 || itemEntity.getX() > westX + 7) continue;
            if (itemEntity.getZ() < mod.lowerMapCorner.getZ() - 3 || itemEntity.getZ() > mod.lowerMapCorner.getZ() + 129) continue;
            if (itemEntity.getY() < mod.lowerMapCorner.getY() - 3 || itemEntity.getY() > mod.lowerMapCorner.getY() + 5) continue;
            BlockPos key = BlockPos.ofFloored(itemEntity.getX(), itemEntity.getY(), itemEntity.getZ());
            if (collectSkips.getOrDefault(key, 0) >= 10) continue;   // blacklisted this batch
            double dist = mc.player.getEyePos().distanceTo(itemEntity.getEntityPos());
            if (dist < bestAnyDist) { bestAnyDist = dist; bestAny = itemEntity; }
            if (itemEntity.getZ() >= mc.player.getZ() - 1.5 && dist < bestDist) { bestDist = dist; best = itemEntity; }
        }
        if (best != null) {
            BlockPos key = BlockPos.ofFloored(best.getX(), best.getY(), best.getZ());
            collectSkips.merge(key, 1, Integer::sum);
        }
        return best != null ? best : bestAny;
    }

    BlockPos nearestRemainingPlanBlock() {
        if (mod.currentBuildPlan == null || mc.player == null) return null;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : mod.currentBuildPlan.keySet()) {
            if (mod.miningBlockState(pos).isAir()) continue;
            double dist = mc.player.getEyePos().distanceTo(pos.toCenterPos());
            if (dist < bestDist) { bestDist = dist; best = pos; }
        }
        return best;
    }

    int countRemainingPlanBlocks() {
        if (mod.currentBuildPlan == null) return 0;
        int count = 0;
        for (BlockPos pos : mod.currentBuildPlan.keySet()) {
            if (!mod.miningBlockState(pos).isAir()) count++;
        }
        return count;
    }

    int countGroundItemsInWindow() {
        if (mc.world == null || mc.player == null) return 0;
        int westX = batchWindowWestX();
        int count = 0;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (!isPickupable(itemEntity)) continue;
            if (itemEntity.getX() < westX - 4 || itemEntity.getX() > westX + 7) continue;
            if (itemEntity.getZ() < mod.lowerMapCorner.getZ() - 3 || itemEntity.getZ() > mod.lowerMapCorner.getZ() + 130) continue;
            if (itemEntity.getY() < mod.lowerMapCorner.getY() - 2 || itemEntity.getY() > mod.lowerMapCorner.getY() + 5) continue;
            count++;
        }
        return count;
    }

    ItemEntity findNearestGroundItem(int margin) {
        if (mc.world == null || mc.player == null) return null;
        ItemEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (!isPickupable(itemEntity)) continue;
            if (itemEntity.getX() < mod.lowerMapCorner.getX() - margin || itemEntity.getX() > mod.lowerMapCorner.getX() + 128 + margin) continue;
            if (itemEntity.getZ() < mod.lowerMapCorner.getZ() - margin || itemEntity.getZ() > mod.lowerMapCorner.getZ() + 128 + margin) continue;
            if (itemEntity.getY() < mod.lowerMapCorner.getY() - 3 || itemEntity.getY() > mod.lowerMapCorner.getY() + 5) continue;
            double dist = mc.player.getEyePos().distanceTo(itemEntity.getEntityPos());
            if (dist < nearestDist) { nearestDist = dist; nearest = itemEntity; }
        }
        return nearest;
    }

    void targetGroundItem(ItemEntity item, String reEvalAction) {
        mod.checkpoints.clear();
        mod.checkpoints.add(new Pair(new Vec3d(item.getX(), item.getY(), item.getZ()), new Pair(reEvalAction, null)));
        mod.state = State.Walking;
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

    BlockPos findNearestPrintBoxBlock(Set<BlockPos> exclude) {
        if (mc.world == null || mc.player == null || mod.lowerMapCorner == null) return null;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        Vec3d eye = mc.player.getEyePos();
        int y0 = mod.lowerMapCorner.getY();
        int y1 = mod.lowerMapCorner.getY() + 3;

        // Scan in expanding rings from the player's position for efficiency
        int centerZ = mc.player.getBlockZ();
        int centerX = mc.player.getBlockX();
        for (int radius = 0; radius <= 64; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;  // ring only
                    int x = centerX + dx;
                    int z = centerZ + dz;
                    // Only check blocks inside the print box (+ the noobline column one
                    // block north — it is printed and recycled with its batch, and a
                    // leftover noobline block walls the north corridor entry)
                    if (x < mod.lowerMapCorner.getX() || x >= mod.lowerMapCorner.getX() + 128) continue;
                    if (z < mod.lowerMapCorner.getZ() - 1 || z >= mod.lowerMapCorner.getZ() + 128) continue;
                    for (int y = y0; y <= y1; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!mod.miningBlockState(pos).isAir() && !exclude.contains(pos)) {
                            double dist = eye.distanceTo(pos.toCenterPos());
                            if (dist < bestDist) { bestDist = dist; best = pos; }
                        }
                    }
                }
            }
            if (best != null) return best;   // nearest found in this ring
        }
        return best;   // null = nothing in range
    }

    void tick(TickEvent.Pre event) {
        mod.master.serviceControlHold();
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
            pauseBreakAllForDeposit();
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

        armResetFeedTarget();

        mod.engine.tickBreakConfirmations();

        mod.engine.pumpArmedBreakTarget();

        if (mod.restock.processDeferredInventoryIO()) return;

        if (!(mod.state.equals(State.Walking) || mod.state.equals(State.Mining))) return;
        if (mod.checkpoints.isEmpty()) return;

        // ADAPTIVE PACE GOVERNOR - the ONE place that decides whether the bot may
        // advance while working. Two modes, one contract (see mod.engine.adaptivePaceHold()).
        Utils.setForwardPressed(!mod.engine.adaptivePaceHold());

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
