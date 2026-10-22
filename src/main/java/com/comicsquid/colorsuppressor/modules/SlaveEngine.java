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
import net.minecraft.item.BlockItem;
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
import java.util.Arrays;
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

    // --- Sub-floor drop recovery (NEVER-SKIP + AUTO-CONTINUE rules, user
    // 2026-10-21) ---
    // A drop resting below the pickup band (its box can never intersect the player
    // box grown by 1.0/0.5 from the surface) is retrieved physically: walk onto its
    // column, break the floor block above it, fall in (touch pickup fires by
    // construction - the bot lands on the drop's own ground), climb out on a buried
    // step block, restore the exact floor block, then re-verify. NOTHING may ever
    // break outside the map bounding area (print-box x/z footprint) - drops outside
    // it are never dug, just named as out-of-scope remainders. No stuck state
    // exists: every failure retries (3 full attempts per drop), then declares the
    // drop out of scope and the run CONTINUES automatically.
    static final int RECOVERY_MAX_PER_BATCH = 16;
    static final int RECOVERY_WATCHDOG_TICKS = 1200;   // 60s per drop attempt, then retry/out-of-scope
    static final int RECOVERY_ATTEMPTS_PER_DROP = 3;
    enum RecoveryStage { DIG, ENTER, COLLECT, EXIT, RESTORE }
    RecoveryStage recoveryStage;
    BlockPos recoveryDropPos;                          // resting column of the target drop
    BlockPos recoveryFloorPos;                         // floor block dug above it (restored after)
    BlockPos recoveryStepPos;                          // climb-out step cell, captured at jump start
    Block recoveryFloorBlock;                          // its exact block (null = floor was already a hole)
    Item recoveryStepItem;                             // placeable item for the climb-out step (drop's own, else any block)
    int recoveryTicks;
    int recoveryRestoreTries;
    int recoveryCount;                                 // this batch (bounded)
    HashSet<BlockPos> recoveryOutOfScope;              // per batch: never-dug drops (named remainders)
    HashMap<BlockPos, Integer> recoveryAttempts;       // per batch: full attempts per drop
    BlockPos containerDigPos;                          // frozen-sweep escalation: encased drop's cell

    // ECHO CHECKPOINTS (user design, 2026-10-21): 5 equally spaced travel-fraction
    // pings per shadowed pass (print + break(2) only) - fire-and-forget telemetry,
    // the slave NEVER waits on them. The master releases its sweep stations on the
    // echo COUNT (station k once the slave's echo count >= k+2 = mating distance 2).
    private static final int[] ECHO_ROW_THRESHOLDS = {26, 52, 77, 103};
    private int echoNextBuild = 1;
    private int echoNextBreak2 = 1;
    private int echoLastBreakStage = -1;
    private int echoLastBatch = -1;

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
        echoNextBuild = 1;
        echoNextBreak2 = 1;
        echoLastBreakStage = -1;
        echoLastBatch = -1;
        debugPreviousState = null;
        stuckCheckpointCount = 0;
        lastCheckpointAction = null;
        pendingDisable = false;
        recoveryStage = null;
        recoveryDropPos = null;
        recoveryFloorPos = null;
        recoveryStepPos = null;
        recoveryFloorBlock = null;
        recoveryStepItem = null;
        recoveryTicks = 0;
        recoveryRestoreTries = 0;
        recoveryCount = 0;
        recoveryOutOfScope = new HashSet<>();
        recoveryAttempts = new HashMap<>();
        containerDigPos = null;
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

    /** ECHO CHECKPOINTS (user design, 2026-10-21): fired from the pass tick, pure
     *  position math - no latch, no state to resync, crash/resume safe. Travel is
     *  measured from the pass's entry edge (print N->S from row 0, break(2) S->N
     *  from row 127 - the FIXED CYCLE makes the directions static). Guarded to
     *  in-box positions only, so supply/emergency/prep trips north can never fire
     *  progress the window hasn't made. Counters reset at every pass boundary
     *  (breakStage or batch change); walking back for stragglers only lowers
     *  traveled, so sweeps can never fire an unearned echo. */
    void tickEchoCheckpoints() {
        if (!SlaveSystem.isSlave || mod.lowerMapCorner == null || mc.player == null) return;
        if (mod.fullSweepActive) return;   // the RESET ladder is not a shadowed pass
        if (mod.breakStage != echoLastBreakStage || mod.currentBatch != echoLastBatch) {
            echoLastBreakStage = mod.breakStage;
            echoLastBatch = mod.currentBatch;
            echoNextBuild = 1;
            echoNextBreak2 = 1;
        }
        int botZ = mc.player.getBlockZ();
        int cornerZ = mod.lowerMapCorner.getZ();
        boolean inBox = botZ >= cornerZ && botZ <= cornerZ + 127;
        if (mod.breakStage == 0 && mod.currentBuildPlan != null
            && mod.state.equals(State.Walking) && inBox) {
            int traveled = botZ - cornerZ;   // print: fixed N->S
            while (echoNextBuild <= ECHO_ROW_THRESHOLDS.length && traveled >= ECHO_ROW_THRESHOLDS[echoNextBuild - 1]) {
                SlaveSystem.sendMessageToMaster("step:echo:build:" + mod.currentBatch + ":" + echoNextBuild);
                echoNextBuild++;
            }
        }
        if (mod.breakStage == 2 && mod.currentBuildPlan != null && inBox
            && (mod.state.equals(State.Walking) || mod.state.equals(State.Mining))) {
            int traveled = (cornerZ + 127) - botZ;   // break(2): fixed S->N
            while (echoNextBreak2 <= ECHO_ROW_THRESHOLDS.length && traveled >= ECHO_ROW_THRESHOLDS[echoNextBreak2 - 1]) {
                SlaveSystem.sendMessageToMaster("step:echo:break2:" + mod.currentBatch + ":" + echoNextBreak2);
                echoNextBreak2++;
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
                // RE-SEND CADENCE (2026-10-22 (cd), the no-speed-loss half of the
                // never-miss fix): a send fired while WALKING races the server's
                // lagged view of a moving bot — that is where rejections come from
                // ((ao) forensics: 52% of burst placements need >1 send). A rejected
                // cell must be re-sent FAST, while it is still inside the walking
                // clamp, or the bot outruns it and a stop is needed. 4 ticks walking
                // (a duplicate into a still-in-flight echo is a harmless no-op —
                // canPlace on non-air just rejects); 10 ticks STANDING, the (bb)/(al)
                // hammer-loop protection while parked. The rejection LADDER window
                // (placePending) is decoupled: first send opens a 10-tick observation
                // window, expiry re-arms the next one — cadence never falsifies it.
                int cadence = mc.options.forwardKey.isPressed() ? 4 : 10;
                for (int pick = 0; pick < mod.blocksPerTick.get() * 3 && placed < mod.blocksPerTick.get(); pick++) {
                    BlockPos target = mod.engine.mod.engine.getNextBatchPlacementPos(attempted);
                    if (target == null) break;
                    attempted.add(target);
                    mod.nextBlockPos = target;
                    mod.engine.placementCooldowns.put(target, cadence);
                    mod.engine.placementAttempts.merge(target, 1, Integer::sum);  // diagnostic
                    int code = mod.engine.mod.engine.tryPlacingBlock();
                    if (code == 1) {
                        placed++;
                        mod.engine.placeSentPerSec++;
                        // Arm the ladder ONLY on a real send: code 0 (swap pending /
                        // echo-grace) sends nothing, and a pending armed for it would
                        // expire into a FALSE rejection count and phantom escalations.
                        // FIRST window 20t (ci): under load Folia's block-update echo
                        // runs 8-15t (live batch 38: confirms trailed sends 26 vs 43/s
                        // with 13 escalations in 40 rows — most were healthy cells
                        // whose echo simply outlived the old 10t window). Escalated
                        // cells re-arm at 10t in the ladder itself.
                        mod.engine.placePending.putIfAbsent(target, 20);
                    }
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
        // Snap only for genuinely close arrivals - collection legs arrive at the
        // wide 1.1 buffer and a snap from that range is a 1-block position jerk.
        if (mod.snapToCheckpoints.get()
            && Math.hypot(mc.player.getX() - goal.x, mc.player.getZ() - goal.z) < 0.5) {
            mc.player.setPosition(goal.x, mc.player.getY(), goal.z);
        }
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
            || "collectRun".equals(checkpointAction.getLeft())
            || "recoverStart".equals(checkpointAction.getLeft())
            || "containerDig".equals(checkpointAction.getLeft());
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
            case "recoverStart":
                return handleRecoverStartArrival();
            case "containerDig":
                // Frozen-sweep escalation: dig the block encasing a drop until it
                // pops out, then the normal sweep re-verifies and collects it.
                if (containerDigPos != null && !mod.miningBlockState(containerDigPos).isAir()) {
                    if (mc.player.getEyePos().distanceTo(containerDigPos.toCenterPos()) > mod.interactionRange.get()) {
                        mod.checkpoints.addFirst(new Pair<>(containerDigPos.toCenterPos(), new Pair<>("containerDig", null)));
                        mod.state = State.Walking;
                        return true;
                    }
                    mod.nextBlockPos = containerDigPos;
                    mod.engine.sendHybridBreak(containerDigPos);
                    mod.checkpoints.addFirst(new Pair<>(mc.player.getEntityPos(), new Pair<>("containerDig", null)));
                    mod.state = State.Mining;
                } else {
                    containerDigPos = null;
                    mod.checkpoints.addFirst(new Pair<>(mc.player.getEntityPos(), new Pair<>("batchExit", null)));
                    mod.state = State.Walking;
                }
                return true;
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
            // Re-arm the ladder rungs for the repair pass: rejection counts are
            // spent by the exact-count hooks (2=escalation pin, 3=break-clear,
            // 4=drop re-check) — inherited counts would silence them exactly where
            // a second attempt has the best odds (a fresh walk, fresh world state).
            mod.engine.placeRejects.clear();
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
            // only unreachable/blacklisted or below-floor drops remain - hand off to
            // the exit gate HERE (finishBatch census names them; sub-floor drops arm
            // the recovery dig there instead of burning walk attempts)
            mod.checkpoints.removeIf(cp -> "batchExit".equals(cp.getRight().getLeft()));
            mod.checkpoints.addFirst(new Pair(mc.player.getEntityPos(), new Pair("batchExit", null)));
            int subFloor = scan.countSubFloorDropsInWindow();
            mod.warning("§6[BREAKALL-VERIFY]§e batch " + mod.currentBatch + ": " + cCensus[0]
                + " drops unreachable in the collection run (" + subFloor + " below the pickup band)"
                + " - exiting to the gate in place.");
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
        ItemEntity nearestItem = mod.restock.mod.restock.freeInventoryCapacity() > 0 ? scan.findNearestSurfaceDrop(3) : null;
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
        // Out-of-scope drops (failed/never-dug recoveries, named in the log) do
        // not gate completion - the run auto-continues (user rule: never stuck).
        int remainingBlocks = scan.countRemainingPlanBlocks();
        int groundItems = scan.countGroundItemsInWindow(recoveryOutOfScope);
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
            mod.restock.mod.restock.clearFilteredDump();   // emergency strip, not a quantity dump
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
        // NEVER SKIP (user rule, 2026-10-21): drops below the pickup band route to
        // the recovery dig, not the walk sweep - no walker can ever touch them
        // (the 08:54 batch-17 stall burned 8 sweep re-arms + 44s of collection
        // attempts against 4 drops under the NW gate floor).
        int subFloorDrops = scan.countSubFloorDropsInWindow(recoveryOutOfScope);
        if (subFloorDrops > 0 && recoveryCount < RECOVERY_MAX_PER_BATCH) {
            armDropRecovery(subFloorDrops);
            return;
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
            // AUTO-RECOVERY ESCALATION (user rule: never stuck, auto-continue): an
            // encased drop gets its containing block dug out (inside the bounding
            // area only); with nothing diggable left the batch completes below with
            // the remainder NAMED - the run never stalls.
            BlockPos encased = scan.findEncasedDropCellInWindow();
            if (encased != null) {
                batchPassRetries = 0;
                sweepReArmSig = null;
                containerDigPos = encased;
                mod.checkpoints.clear();
                mod.checkpoints.add(new Pair(new Vec3d(encased.getX() + 0.5, mod.lowerMapCorner.getY(), encased.getZ() + 0.5),
                    new Pair("containerDig", null)));
                mod.state = State.Walking;
                mod.info("§6[SWEEP-VERIFY]§7 batch " + mod.currentBatch
                    + ": sweep frozen - digging the block encasing a drop at " + encased.toShortString() + ".");
                return;
            }
            mod.warning("§6[SWEEP-VERIFY]§e batch " + mod.currentBatch
                + ": sweep frozen with blocks=" + remainingBlocks + " items[ready=" + census[0]
                + " sub-floor=" + subFloorDrops + "] and every legal recovery exhausted - "
                + "completing with a NAMED remainder (see the lines above for locations).");
        }
        sweepWaitRounds = 0;
        sweepWaitLogged = false;
        sweepReArmSig = null;
        recoveryCount = 0;
        recoveryOutOfScope.clear();
        recoveryAttempts.clear();
        containerDigPos = null;
        mod.info("§aBatch " + mod.currentBatch + " COMPLETE: printed and fully recycled.");
        int doneBatch = mod.currentBatch;
        mod.currentBuildPlan = null;
        mod.breakStage = 0;
        mod.state = State.StandBy;
        Utils.setForwardPressed(false);
        SlaveSystem.sendMessageToMaster("step:done:" + doneBatch);
    }

    // ------------------------------------------------------------------
    // Sub-floor drop recovery (NEVER-SKIP + AUTO-CONTINUE). Armed by
    // finishBatch when a census drop sits below the pickup band; drives
    // itself from onTick via recoveryTick(), fully bypassing the walking/work
    // dispatch while active. NO stuck state exists: bounded retries, then the
    // drop is named out of scope and the run continues. Every break/place is
    // inside the map bounding area (print-box x/z footprint).
    // ------------------------------------------------------------------

    void armDropRecovery(int subFloorCount) {
        ItemEntity drop = scan.findNearestSubFloorDrop(3, recoveryOutOfScope);
        if (drop == null) {   // census raced (despawn/merge) - plain re-verify
            mod.checkpoints.addFirst(new Pair<>(mc.player.getEntityPos(), new Pair<>("batchExit", null)));
            mod.state = State.Walking;
            return;
        }
        BlockPos d = BlockPos.ofFloored(drop.getX(), drop.getY(), drop.getZ());
        mod.checkpoints.clear();
        mod.checkpoints.add(new Pair<>(new Vec3d(d.getX() + 0.5, mod.lowerMapCorner.getY(), d.getZ() + 0.5),
            new Pair<>("recoverStart", null)));
        mod.state = State.Walking;
        mod.breakStage = 0;   // recovery digs directly; the break-all inv-full pause must not hijack it
        mod.info("§6[DROP-RECOVERY]§7 batch " + mod.currentBatch + ": " + subFloorCount
            + " drop(s) below the pickup band (nearest at " + d.toShortString()
            + ", y=" + String.format("%.1f", drop.getY()) + ", attempt "
            + (recoveryAttempts.getOrDefault(d, 0) + 1) + "/" + RECOVERY_ATTEMPTS_PER_DROP + ") - recovery dig armed.");
    }

    boolean handleRecoverStartArrival() {
        ItemEntity drop = scan.findNearestSubFloorDrop(3, recoveryOutOfScope);
        if (drop == null) { finishDropRecovery("no in-scope sub-floor drop left (despawned/merged)"); return true; }
        BlockPos d = BlockPos.ofFloored(drop.getX(), drop.getY(), drop.getZ());
        boolean inBox = d.getX() >= mod.lowerMapCorner.getX() && d.getX() < mod.lowerMapCorner.getX() + 128
            && d.getZ() >= mod.lowerMapCorner.getZ() && d.getZ() < mod.lowerMapCorner.getZ() + 128;
        // BOUNDING-AREA RULE (user, 2026-10-21): never break outside the box - such
        // drops are never dug, named as out-of-scope remainders, and the run continues.
        if (!inBox) {
            if (recoveryOutOfScope.add(d)) {
                mod.warning("§6[DROP-RECOVERY]§e drop at " + d.toShortString()
                    + " is OUTSIDE the map bounding area - never dug, named as remainder, continuing.");
            }
            finishDropRecovery("drop outside the bounding area at " + d.toShortString());
            return true;
        }
        beginRecoveryAt(d, drop);
        return true;
    }

    private void beginRecoveryAt(BlockPos d, ItemEntity drop) {
        recoveryDropPos = d;
        recoveryStepItem = drop.getStack().getItem() instanceof BlockItem bi ? bi : null;
        recoveryFloorPos = new BlockPos(d.getX(), mod.lowerMapCorner.getY() - 1, d.getZ());
        recoveryStepPos = null;
        recoveryTicks = 0;
        recoveryRestoreTries = 0;
        int surfaceY = mod.lowerMapCorner.getY();
        // Already in the pit (walked onto an air-floor column = the escape hole
        // itself, or still falling from the dig): collect right here.
        if (mc.player.getBlockY() <= surfaceY - 2
            && Math.abs(mc.player.getX() - (d.getX() + 0.5)) < 1.2
            && Math.abs(mc.player.getZ() - (d.getZ() + 0.5)) < 1.2) {
            recoveryFloorBlock = null;
            recoveryStage = RecoveryStage.COLLECT;
            mod.state = State.Mining;
            return;
        }
        BlockState fs = mod.miningBlockState(recoveryFloorPos);
        // First attempt at this column: log the full column state - this is the
        // evidence that names the hole the drops escaped through (patch target).
        if (recoveryAttempts.getOrDefault(d, 0) == 0 && mod.debugPrints.get()) {
            mod.info(String.format("§6[DROP-RECOVERY]§7 column %s: floor=%s, below=%s/%s, dropY=%.2f",
                d.toShortString(), fs.getBlock().getName().getString(),
                mod.miningBlockState(d.down()).getBlock().getName().getString(),
                mod.miningBlockState(d.down(2)).getBlock().getName().getString(), drop.getY()));
        }
        recoveryFloorBlock = fs.isAir() ? null : fs.getBlock();
        // Air floor = the hole IS the gap: walk into it and fall; solid floor = dig.
        recoveryStage = fs.isAir() ? RecoveryStage.ENTER : RecoveryStage.DIG;
        mod.state = State.Mining;
    }

    /** One recovery step per tick; called from onTick while recoveryStage != null. */
    void recoveryTick() {
        recoveryTicks++;
        if (recoveryTicks > RECOVERY_WATCHDOG_TICKS) {
            boolean belowSurface = mc.player.getBlockY() <= mod.lowerMapCorner.getY() - 2;
            if (belowSurface) {
                // A bot inside the pit is NEVER abandoned mid-climb (the exit succeeds
                // by construction - placement). Cut whatever else is stuck and climb.
                if (recoveryTicks % 100 == 0) {
                    mod.warning("§6[DROP-RECOVERY]§e recovery over budget at " + recoveryDropPos.toShortString()
                        + " - forcing the climb-out, then re-verifying.");
                }
                recoveryStage = RecoveryStage.EXIT;
                recoveryExitTick();
                return;
            }
            failRecoveryAttempt("watchdog (" + (RECOVERY_WATCHDOG_TICKS / 20) + "s)");
            return;
        }
        switch (recoveryStage) {
            case DIG -> recoveryDigTick();
            case ENTER -> recoveryEnterTick();
            case COLLECT -> recoveryCollectTick();
            case EXIT -> recoveryExitTick();
            case RESTORE -> recoveryRestoreTick();
        }
    }

    /** Attempt failed: count it, retry while attempts remain, else name the drop
     *  out of scope and CONTINUE the batch (never stuck, never silent). */
    private void failRecoveryAttempt(String why) {
        recoveryStage = null;
        Utils.setJumpPressed(false);
        Utils.setForwardPressed(false);
        int attempts = recoveryAttempts.merge(recoveryDropPos, 1, Integer::sum);
        if (attempts >= RECOVERY_ATTEMPTS_PER_DROP) {
            if (recoveryOutOfScope.add(recoveryDropPos)) {
                mod.warning("§6[DROP-RECOVERY]§e " + attempts + " attempts failed at " + recoveryDropPos.toShortString()
                    + " (" + why + ") - drop named OUT OF SCOPE (remainder), batch continues automatically.");
            }
        } else {
            mod.warning("§6[DROP-RECOVERY]§e attempt " + attempts + "/" + RECOVERY_ATTEMPTS_PER_DROP + " at "
                + recoveryDropPos.toShortString() + " failed (" + why + ") - retrying.");
        }
        mod.checkpoints.clear();
        mod.checkpoints.add(new Pair<>(mc.player.getEntityPos(), new Pair<>("batchExit", null)));
        mod.state = State.Walking;
    }

    private void recoveryDigTick() {
        if (mod.miningBlockState(recoveryFloorPos).isAir()) {   // server confirmed - go fall in
            recoveryStage = RecoveryStage.ENTER;
            return;
        }
        mod.nextBlockPos = recoveryFloorPos;
        mod.engine.sendHybridBreak(recoveryFloorPos);   // self-routes pair/split/vanilla (obsidian-class floors)
    }

    private void recoveryEnterTick() {
        if (mc.player.getBlockY() <= mod.lowerMapCorner.getY() - 2) {   // in the pit
            recoveryStage = RecoveryStage.COLLECT;
            return;
        }
        // Walk into the hole: aim at its centre and step off the edge (this covers
        // both the air-floor gap and a dig done from an off-column stance).
        Vec3d c = new Vec3d(recoveryFloorPos.getX() + 0.5, mc.player.getY(), recoveryFloorPos.getZ() + 0.5);
        mc.player.setYaw((float) Rotations.getYaw(c));
        Utils.setForwardPressed(true);
    }

    private void recoveryCollectTick() {
        ItemEntity live = scan.findNearestSubFloorDrop(3, recoveryOutOfScope);
        boolean targetGone = live == null
            || !BlockPos.ofFloored(live.getX(), live.getY(), live.getZ()).equals(recoveryDropPos);
        if (targetGone) {
            if (mc.player.isOnGround()) {
                recoveryStage = RecoveryStage.EXIT;
                Utils.setForwardPressed(false);
            }
            return;
        }
        // Landed but laterally offset: step toward the drop inside the void (bounded
        // by the watchdog; if the pocket is too tight to walk this cuts to EXIT).
        if (mc.player.isOnGround()) {
            Vec3d to = live.getEntityPos().subtract(mc.player.getEntityPos());
            if (Math.hypot(to.x, to.z) > 0.8) {
                mc.player.setYaw((float) Rotations.getYaw(live.getEntityPos()));
                Utils.setForwardPressed(true);
            } else {
                Utils.setForwardPressed(false);
            }
        }
    }

    private void recoveryExitTick() {
        double surfaceY = mod.lowerMapCorner.getY();
        if (mc.player.isOnGround() && mc.player.getY() >= surfaceY - 0.01) {
            Utils.setJumpPressed(false);
            Utils.setForwardPressed(false);
            recoveryStepPos = null;
            recoveryStage = RecoveryStage.RESTORE;
            return;
        }
        BlockPos d = recoveryDropPos;
        // 1-deep pit (feet one below the surface): a plain jump toward an intact
        // neighbour column clears it - no placement needed.
        if (mc.player.getY() >= surfaceY - 1.01) {
            Vec3d exit = recoveryExitGoal(d, surfaceY);
            mc.player.setYaw((float) Rotations.getYaw(exit));
            Utils.setForwardPressed(true);
            Utils.setJumpPressed(true);
            return;
        }
        // Deeper: at each hop's apex place a step block into the cell under the feet
        // (the player box clears the cell top at the 1.25-block jump peak). The step
        // cell is captured at JUMP START - floor(y) flips one cell up mid-air. One
        // block per cell, climb iteratively; the step prefers the recovered stack,
        // then ANY carried block (mid-collect the bot carries dozens).
        if (mc.player.isOnGround()) {
            recoveryStepPos = new BlockPos(d.getX(), (int) Math.floor(mc.player.getY() + 0.01), d.getZ());
        } else if (recoveryStepPos != null
            && mc.player.getY() >= recoveryStepPos.getY() + 1.05) {
            if (recoveryStepItem == null) recoveryStepItem = bestCarriedBlockItem();
            if (recoveryStepItem == null) {
                if (recoveryTicks % 100 == 0) {
                    mod.warning("§6[DROP-RECOVERY]§e no placeable block carried for the climb-out step - "
                        + "keep hopping and re-scanning the inventory.");
                }
            } else {
                mod.engine.placeCarriedItem(recoveryStepItem, recoveryStepPos);
            }
        }
        Utils.setJumpPressed(true);
    }

    /** Exit landing spot: the first in-box neighbour column whose floor block is
     *  solid (an open neighbour pit would swallow the jump); east fallback. */
    private Vec3d recoveryExitGoal(BlockPos d, double surfaceY) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] dir : dirs) {
            BlockPos n = d.add(dir[0], 0, dir[1]);
            if (n.getX() < mod.lowerMapCorner.getX() || n.getX() >= mod.lowerMapCorner.getX() + 128
                || n.getZ() < mod.lowerMapCorner.getZ() || n.getZ() >= mod.lowerMapCorner.getZ() + 128) continue;
            if (!mod.miningBlockState(n.down()).isAir()) {
                return new Vec3d(n.getX() + 0.5, surfaceY, n.getZ() + 0.5);
            }
        }
        return new Vec3d(d.getX() + 1.5, surfaceY, d.getZ() + 0.5);
    }

    /** Any carried placeable block for the climb-out step; the dug floor block is
     *  kept in reserve for the exact restore when alternatives exist. */
    private Item bestCarriedBlockItem() {
        Item reserve = recoveryFloorBlock != null ? recoveryFloorBlock.asItem() : null;
        Item reserveHit = null;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem bi)) continue;
            if (reserve != null && stack.getItem().equals(reserve)) { reserveHit = bi; continue; }
            return bi;   // a block that is not the exact-restore reserve
        }
        return reserveHit;   // the reserve alone still beats nothing (exit before restore)
    }

    private void recoveryRestoreTick() {
        BlockState fs = mod.miningBlockState(recoveryFloorPos);
        if (!fs.isAir()) {
            boolean exact = recoveryFloorBlock != null && fs.getBlock().equals(recoveryFloorBlock);
            if (!exact && recoveryFloorBlock != null) {
                mod.warning("§6[DROP-RECOVERY]§e floor restore mismatch at " + recoveryFloorPos.toShortString()
                    + " (expected " + recoveryFloorBlock.getName().getString() + ", found "
                    + fs.getBlock().getName().getString() + ") - continuing.");
            }
            finishDropRecovery(exact ? "floor restored exactly"
                : "floor gap sealed with " + fs.getBlock().getName().getString() + " (was a hole)");
            return;
        }
        Item restoreItem = recoveryFloorBlock != null ? recoveryFloorBlock.asItem() : recoveryStepItem;
        if (restoreItem == null || !mod.engine.placeCarriedItem(restoreItem, recoveryFloorPos)) {
            if (++recoveryRestoreTries > 40) {
                // Open hole in the floor: safe to leave (later drops falling in are
                // auto-recovered by the air-floor path); it also gets sealed by any
                // later restore attempt that finds a carried block. Never stalls.
                mod.warning("§6[DROP-RECOVERY]§e no block to seal " + recoveryFloorPos.toShortString()
                    + " - floor hole left open (drops falling in auto-recover); continuing.");
                finishDropRecovery("floor hole left open - no carried block");
            }
        }
    }

    private void finishDropRecovery(String note) {
        recoveryStage = null;
        recoveryCount++;
        Utils.setJumpPressed(false);
        Utils.setForwardPressed(false);
        mod.info("§6[DROP-RECOVERY]§7 batch " + mod.currentBatch + " dig done (" + note
            + ") - re-verifying the window.");
        mod.checkpoints.clear();
        mod.checkpoints.add(new Pair<>(mc.player.getEntityPos(), new Pair<>("batchExit", null)));
        mod.state = State.Walking;
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
                recoveryStage = null;   // a mid-recovery abort must not keep digging
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
            // TOPS ONLY (reverted 2026-10-21 per user order): break(2) certifies the
            // window's y2/y3 tops clear; the floors are break-all's job after W2.
            if (pos.getX() >= westX + 2 || pos.getY() < mod.lowerMapCorner.getY() + 2) continue;
            if (!mod.miningBlockState(pos).isAir()) remaining.add(pos);
        }
        if (remaining.isEmpty()) {
            mod.info("§aBatch " + mod.currentBatch + " break(2) complete (west Y2/Y3 down).");
            return true;
        }
        if (batchPassRetries >= 2) {
            mod.error("Batch " + mod.currentBatch + " break(2) incomplete after retry: " + remaining.size() + " blocks left. Stopped.");
            mod.checkpoints.clear();   // a stopped batch must not reach awaitBreakAll behind us
            mod.state = State.StandBy;
            Utils.setForwardPressed(false);
            return false;
        }
        batchPassRetries++;
        mod.info("§eBatch " + mod.currentBatch + " break(2) leftover (" + remaining.size() + ") - fixed-direction retry pass (S->N).");
        double lane2X = mod.lowerMapCorner.getX() + 2.0 * mod.currentBatch + 2.5;
        // HEAD-insertion (addFirst, reversed): the queue still holds awaitBreakAll
        // (north) ahead of us - tail-appending made the bot "reach" awaitBreakAll
        // instantly, declare break(2) complete, and the retry legs were wiped by
        // startBreakAll's clear() without ever running; the leftover stood through
        // the master's W2 and the map recorded it (live 11:02:34, batch 19).
        mod.checkpoints.addFirst(new Pair(new Vec3d(lane2X, mod.lowerMapCorner.getY(), mod.lowerMapCorner.getZ() - 2), new Pair("break2Done", null)));
        mod.checkpoints.addFirst(new Pair(new Vec3d(lane2X, mod.lowerMapCorner.getY(), mod.lowerMapCorner.getZ() + 128.7), new Pair("sprint", null)));
        mod.state = State.Mining;
        return false;
    }


















    void updateTransitSpeed() {
        // auto-speed option owns the Speed module for the whole run (hub autoSpeed
        // Tick): retire any live Dynamic session WITHOUT disabling the module, and
        // never ride phases - "enable it, sprint Always, change nothing else".
        if (mod.autoSpeed.get()) {
            transitSpeedOn = false;
            return;
        }
        if (mod.sprinting.get() != StepCheckered.SprintMode.Dynamic) {
            if (transitSpeedOn) setTransitSpeed(false);
            return;
        }
        boolean printing = mod.state == State.Walking && mod.breakStage == 0 && mod.currentBuildPlan != null
            && !mod.checkpoints.isEmpty() && StepCheckered.ALLOW_PLACE_ACTIONS.contains(mod.checkpoints.getFirst().getRight().getLeft());
        // COLLECTION LEGS ARE TRANSIT (user, 2026-10-21: pickup walked at 4.3 m/s
        // because breakStage 4 from break-all kept counting as "breaking"): after
        // break-all the pass only walks and touches drops - no break packets are in
        // flight to outrun, so the transit speed applies. sweepTick is deliberately
        // NOT here: it interleaves straggler block breaks and break passes never
        // speed-hack.
        boolean collecting = mod.state == State.Walking && mod.breakStage != 0 && !mod.checkpoints.isEmpty() && collectSpeedActions.contains(mod.checkpoints.getFirst().getRight().getLeft());
        // BREAK PASSES NEVER SPEED-HACK (live 2026-10-21: at 7.12 bps the bot outruns
        // the server's echo AND reach — breaks expire out-of-reach into a frozen vanilla
        // queue (51 blocks stuck) and the server rubber-bands the movement itself. The
        // math cap is 30 bps / 5.25 blocks-per-row = 5.7 bps max useful pace; sprint
        // (~5.6) sits just under it).
        boolean breaking = mod.state == State.Mining || (mod.breakStage != 0 && mod.state != State.StandBy && !collecting);
        boolean want = !printing && !breaking && mod.state != State.StandBy;
        if (want != transitSpeedOn) setTransitSpeed(want);
    }

    private static final List<String> collectSpeedActions = Arrays.asList("collectRun", "batchExit", "recoverStart");

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
            tickEchoCheckpoints();
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

        mod.engine.tickPlaceConfirmations();

        mod.engine.pumpArmedBreakTarget();

        if (mod.restock.processDeferredInventoryIO()) return;

        // Sub-floor drop recovery drives itself (dig/collect/exit/restore) and fully
        // bypasses the walking/work dispatch below - one stage per tick, bounded by
        // its own watchdog. Runs after the IO drain so its placement swaps settle.
        if (recoveryStage != null) {
            recoveryTick();
            return;
        }

        if (!(mod.state.equals(State.Walking) || mod.state.equals(State.Mining))) return;
        if (mod.checkpoints.isEmpty()) return;

        // ADAPTIVE PACE GOVERNOR - the ONE place that decides whether the bot may
        // advance while working. Print mode: adaptivePaceHold; break mode: the
        // forward-only approach/verify cadence (see mod.engine.breakApproachHold()).
        Utils.setForwardPressed(!mod.engine.adaptivePaceHold() && !mod.engine.breakApproachHold());

        Vec3d goal = mod.checkpoints.get(0).getLeft();
        // COLLECTION LEGS arrive early (user, 2026-10-21: pickup homing to the 0.2
        // buffer decelerated the bot onto every drop): the pickup box touches at
        // ~1.4 horizontal, so arriving at 1.1 keeps the sweep at full speed and the
        // drop is collected in passing. Sweep-block targets stay precise via the
        // Mining approach gate (interaction range 4.5 >> 1.1).
        String headAction = mod.checkpoints.get(0).getRight().getLeft();
        double arrivalBuffer = mod.checkpointBuffer.get();
        if ("collectRun".equals(headAction) || "sweepTick".equals(headAction)) {
            arrivalBuffer = Math.max(arrivalBuffer, 1.1);
        }
        if (PlayerUtils.distanceTo(goal.add(0, mc.player.getY() - goal.y, 0)) < arrivalBuffer) {
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
