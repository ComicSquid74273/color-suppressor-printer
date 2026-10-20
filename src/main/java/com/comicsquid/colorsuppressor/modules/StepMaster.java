package com.comicsquid.colorsuppressor.modules;

import com.comicsquid.colorsuppressor.utils.LocalTCPTransport;
import com.comicsquid.colorsuppressor.utils.SlaveSystem;
import com.comicsquid.colorsuppressor.utils.StepFiles;
import com.comicsquid.colorsuppressor.utils.Utils;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.screen.CartographyTableScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.Pair;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import static meteordevelopment.meteorclient.MeteorClient.mc;

import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;

import java.io.File;
import java.util.ArrayList;
import java.util.function.Predicate;

/**
 * The MASTER side of the step-checker two-bot lifecycle (StepMasterStage state
 * machine): map fetch/fill/pin/stow, the W1/W2 sweep walks, break(2)/break-all
 * calls, closing, the cartography lock, waiting discipline, the lattice/map-hold
 * watchdogs, and the batch-progress persistence. The module owns settings,
 * registration, and the event surface; this class owns everything the master bot
 * does once a run starts.
 */
final class StepMaster {
    final StepCheckered mod;

    // --- Master run state (moved from the module) ---
    volatile StepMasterStage stepMasterStage = StepMasterStage.IDLE;
    int stepMasterBatch = -1;
    int stepMasterDwellTicks;
    int stepMasterWaitTicks;
    boolean stepMasterSweepCompletionPending;
    boolean stepSweepQueuePending;
    int stepPendingSweepLane;
    int stepPendingSweepStart;
    int stepPendingSweepEnd;
    boolean stepMasterBreak2Sent;
    boolean stepMasterBreakAllSent;
    boolean stepMasterCloseBreakSent;
    volatile int stepSlaveReadyBatch = -1;
    volatile int stepSlaveBreak2DoneBatch = -1;
    volatile int stepSlaveDoneBatch = -1;
    volatile boolean stepSlaveReadyClosing;
    volatile boolean stepSlaveClosingDone;
    int stepWorkingMapId = -1;
    boolean stepMapSwapPending;
    int stepMapSwapAge;
    int stepLockStep = -1;
    int stepLockDelay;
    boolean controlHoldActive;
    boolean controlSwapPending;
    int controlUseCooldown;
    String lastMasterCpLog;
    int stepSavedProgressMapId = -1;
    long stepBatchStartMs, stepPhaseStartMs;
    int cachedResumeBatch = -1;
    int mapHoldWarnCooldown;
    long stepBuildMs, stepW1Ms, stepBreak2Ms, stepW2Ms, stepBreakAllMs;
    static final int[] STEP_CHECKPOINT_Z = {14, 40, 64, 88, 112};
    static final int STEP_DWELL_TICKS = 80;

    StepMaster(StepCheckered mod) {
        this.mod = mod;
    }

    /** Full master-state reset (module onActivate path). */
    void resetForActivation() {
        stepSlaveReadyBatch = -1;
        stepSlaveBreak2DoneBatch = -1;
        stepSlaveDoneBatch = -1;
        stepSlaveReadyClosing = false;
        stepSlaveClosingDone = false;
        stepMasterStage = StepMasterStage.IDLE;
        stepMasterBatch = -1;
        stepMasterDwellTicks = 0;
        stepMasterWaitTicks = 0;
        stepMasterSweepCompletionPending = false;
        stepSweepQueuePending = false;
        stepPendingSweepLane = -1;
        stepPendingSweepStart = -1;
        stepPendingSweepEnd = -1;
        stepMasterBreak2Sent = false;
        stepMasterBreakAllSent = false;
        stepMasterCloseBreakSent = false;
        stepWorkingMapId = -1;
        stepMapSwapPending = false;
        stepMapSwapAge = 0;
        stepLockStep = -1;
        stepLockDelay = 0;
        controlHoldActive = false;
        controlSwapPending = false;
        controlUseCooldown = 0;
        lastMasterCpLog = null;
        stepSavedProgressMapId = -1;
        cachedResumeBatch = -1;
        mapHoldWarnCooldown = 0;
    }

    void beginStepMasterRun() {
        if (!mod.initialSetup()) return;
        if (SlaveSystem.slaves.size() != 1) {
            mod.warning("Step-checker requires exactly one connected slave before starting (connected: "
                + SlaveSystem.slaves.size() + ").");
            return;
        }
        if (stepMasterStage != StepMasterStage.IDLE && stepMasterStage != StepMasterStage.ABORTED) {
            mod.warning("Step-checker master is already running in " + stepMasterStage + ".");
            return;
        }

        stepMasterBatch = -1;
        stepMasterWaitTicks = 0;
        stepMasterDwellTicks = 0;
        stepMasterBreak2Sent = false;
        stepMasterBreakAllSent = false;
        stepMasterCloseBreakSent = false;
        stepSlaveReadyBatch = -1;
        stepSlaveBreak2DoneBatch = -1;
        stepSlaveDoneBatch = -1;
        stepSlaveReadyClosing = false;
        stepSlaveClosingDone = false;
        stepMapSwapPending = false;
        stepStowMap();

        if (stepFindWorkingMapSlot() >= 0) {
            // CRASH/RESTART/DISABLE RECOVERY: the progress file carries the last
            // in-progress batch AND the working map's id. Resume re-pins the EXACT
            // saved map (never just any filled map - a wrong map would corrupt the
            // run) and re-runs the in-progress batch. Only RESET deletes the file.
            int resumeBatch = loadStepProgress();
            if (resumeBatch > 0) {
                if (stepSavedProgressMapId >= 0) {
                    int savedSlot = stepFindMapIdSlot(stepSavedProgressMapId);
                    if (savedSlot < 0) {
                        mod.error("§cProgress file says batch " + resumeBatch + " on map id " + stepSavedProgressMapId
                            + ", but that map is NOT in the inventory - the recorded pixels cannot be recovered. "
                            + "Starting a FRESH run (hold the working map to resume).");
                        clearStepProgress();
                        beginStepPrimingRun();
                        return;
                    }
                    stepWorkingMapId = stepSavedProgressMapId;
                    mod.info("§aWorking map re-pinned by saved id=" + stepWorkingMapId + ".");
                } else {
                    stepPinWorkingMap();   // legacy progress file without map id
                }
                mod.info("§aStep-checker: resuming from batch " + resumeBatch + " (progress file).");
                stepMasterBatch = resumeBatch - 1;
                beginStepBatch(resumeBatch);
            } else {
                stepPinWorkingMap();
                beginStepPrimingRun();
            }
        } else if (findEmptyMapSlot() >= 0) {
            beginStepMapFill();
        } else {
            // No map in inventory: walk to the map chest and fetch one
            Pair<BlockPos, Vec3d> mapChest = mod.restock.getBestChest(Items.CARTOGRAPHY_TABLE);
            if (mapChest != null && mapChest.getLeft() != null) {
                stepMasterStage = StepMasterStage.FETCH_MAP;
                stepMasterWaitTicks = 0;
                mod.checkpoints.clear();
                mod.checkpoints.add(new Pair(mapChest.getRight(), new Pair("stepFetchMap", mapChest.getLeft())));
                prependLatticeTransit(mod.lowerMapCorner.getX() + 125.5, mod.lowerMapCorner.getZ() + 14.5);   // exit via the GATE
                mod.state = State.Walking;
                mod.info("§aStep-checker: walking to the map chest to get an empty map.");
            } else {
                mod.error("Step-checker needs a map but no map chest is registered.");
                stepMasterStage = StepMasterStage.ABORTED;
                mod.state = State.StandBy;
            }
        }
    }

    void resetStepRun() {
        mod.info("§cStep-checker RESET: clearing everything and starting from scratch.");
        // 1) Tell the slave to clear its batch
        if (!SlaveSystem.slaves.isEmpty()) {
            stepSendToSlave("abort");
            mod.info("§7Abort command sent to the slave - it will break and deposit everything.");
        }
        // 2) Clear the progress file so the next start begins from batch 0
        clearStepProgress();
        // 3) Reset the master mod.state
        stepMasterStage = StepMasterStage.IDLE;
        stepMasterBatch = -1;
        mod.checkpoints.clear();
        mod.state = State.StandBy;
        stepStowMap();
        stepWorkingMapId = -1;
        Utils.setForwardPressed(false);
        mod.info("§aReset complete. Enable the module again to start a fresh run.");
    }

    void beginStepMapFill() {
        stepMasterStage = StepMasterStage.FILL_MAP;
        stepMasterWaitTicks = 0;
        mod.checkpoints.clear();
        // FILL AT THE GATE (user rule, 2026-10-20): the map is filled standing on the
        // north-west GATE platform - the same single checkpoint every entry and exit
        // uses. The journey starts from here; the old fill spot (125,115) is gone.
        Vec3d fillSpot = stepCheckpoint(125, 14);
        mod.checkpoints.add(new Pair<>(fillSpot, new Pair<>("stepFillMap", null)));
        prependLatticeTransit(fillSpot.x, fillSpot.z);
        mod.state = State.Walking;
        stepStowMap();
        mod.info("§aStep-checker: walking to the fill checkpoint for the working map.");
    }

    void beginStepPrimingRun() {
        int firstBatch = Math.max(0, Math.min(62, mod.startBatch.get()));
        if (firstBatch > 0) {
            // START-BATCH SKIP (user directive 2026-10-20): begin the run at the
            // configured batch - earlier batches are never built or walked.
            mod.info("§aStarting from batch " + firstBatch + " (start-batch) - earlier batches skipped.");
            beginStepBatch(firstBatch);
            return;
        }
        if (!mod.batch0SplitBuild.get()) {
            // SPLIT DISABLED (user directive 2026-10-20): batch 0 runs the STANDARD
            // flow like every other batch - build(4) -> W1 -> break(2) -> W2 ->
            // break-all. No pre-build/priming phase at all.
            mod.info("§aBatch 0: split-build disabled - running the standard batch flow.");
            beginStepBatch(0);
            return;
        }
        // UNIFIED BATCH 0, phase 1 (floor half): identical stages and signals to every
        // other batch - WAIT_BATCH_BUILD, ready:0, then W1 on lane 125 (the batch-0
        // lane pick in the standard consumer). Only the command differs: "prime" tells
        // the slave to build the floor half; W1's completion then requests the tops
        // half (the split-build physics: pair 0's floor must record with no tops above).
        stepMasterBatch = 0;
        stepMasterStage = StepMasterStage.WAIT_BATCH_BUILD;
        stepMasterWaitTicks = 0;
        stepSlaveReadyBatch = -1;
        stepSlaveBreak2DoneBatch = -1;
        stepSlaveDoneBatch = -1;
        stepMasterBreak2Sent = false;
        stepMasterBreakAllSent = false;
        stepSendToSlave("prime");
        stepQueueWaitHere("stepBatchSafe");
        stepStowMap();
        mod.info("§aBatch 0 phase 1 (floor half): build requested; waiting in place.");
    }

    void beginStepBatch(int batch) {
        if (batch < 0 || batch > 62) {
            mod.error("Invalid step-checker batch " + batch + ".");
            abortStepMaster("invalid batch");
            return;
        }
        stepMasterBatch = batch;
        stepBatchStartMs = System.currentTimeMillis();
        stepPhaseStartMs = stepBatchStartMs;
        stepBuildMs = stepW1Ms = stepBreak2Ms = stepW2Ms = stepBreakAllMs = 0;
        // PERSISTENCE: "batch k is in progress" is written on every batch start, so a
        // crash/restart/disable at ANY point resumes by re-running exactly this batch
        // (with the map id re-pinned from the file). Only RESET deletes the file.
        saveStepProgress(batch);
        stepMasterStage = StepMasterStage.WAIT_BATCH_BUILD;
        stepMasterWaitTicks = 0;
        stepSlaveReadyBatch = stepSlaveReadyBatch == batch ? batch : -1;
        stepSlaveBreak2DoneBatch = -1;
        stepSlaveDoneBatch = -1;
        stepMasterBreak2Sent = false;
        stepMasterBreakAllSent = false;
        if (batch == 0 && mod.batch0SplitBuild.get()) {
            // MERGED BATCH 0, split mode (resume safety - the fresh-run path goes through
            // the priming walk merge): floor via the split flow if entered directly.
            stepSendToSlave("buildhalf:0:f");
            stepQueueWaitHere("stepBatchSafe");
            stepStowMap();
            mod.info("§aStep-checker batch 0: SPLIT build (floor half) requested; waiting in place.");
            return;
        }
        stepSendToSlave("build:" + batch);
        // Build wait: IN PLACE at the nearest platform (the previous batch's W2 already
        // ended on this batch's W1 lane) - map stowed, no repositioning leg
        stepQueueWaitHere("stepBatchSafe");
        stepStowMap();
        mod.info("§aStep-checker batch " + batch + " build requested; waiting in place.");
    }

    void beginStepClosingRun() {
        stepMasterStage = StepMasterStage.WAIT_CLOSING_BUILD;
        stepMasterWaitTicks = 0;
        stepSlaveReadyClosing = false;
        stepSendToSlave("close");
        stepQueueWaitHere("stepClosingSafe");
        stepStowMap();
        mod.info("§aStep-checker closing pair 63 build requested; waiting in place.");
    }

    void stepSendToSlave(String command) {
        if (SlaveSystem.slaves.size() != 1) {
            abortStepMaster("the slave disconnected");
            return;
        }
        if (mod.debugPrints.get()) mod.info("§3[dbg] TCP send→slave: §b" + command);
        SlaveSystem.sendToSlave(SlaveSystem.slaves.get(0), "step:" + command);
    }

    Vec3d stepCheckpoint(int relativeX, int relativeZ) {
        return new Vec3d(
            mod.lowerMapCorner.getX() + relativeX + 0.5,
            mod.lowerMapCorner.getY(),
            mod.lowerMapCorner.getZ() + relativeZ + 0.5);
    }

    boolean stepInMasterArea() {
        return mod.lowerMapCorner != null && mc.player != null
            && mc.player.getX() >= mod.lowerMapCorner.getX() + StepCheckered.MASTER_AREA_X0 && mc.player.getX() <= mod.lowerMapCorner.getX() + StepCheckered.MASTER_AREA_X1
            && mc.player.getZ() >= mod.lowerMapCorner.getZ() + StepCheckered.MASTER_AREA_Z0 && mc.player.getZ() <= mod.lowerMapCorner.getZ() + StepCheckered.MASTER_AREA_Z1;
    }

    double stepNearestLaneX(double worldX) {
        int j = (int) Math.round(Math.clamp(worldX - mod.lowerMapCorner.getX() - 125, 0, 128) / 2.0);
        return mod.lowerMapCorner.getX() + 125 + 2 * j + 0.5;
    }

    void prependLatticeTransit(double targetX, double targetZ) {
        double y = mod.lowerMapCorner.getY();
        double targetLaneX = stepNearestLaneX(targetX);
        ArrayList<Pair<Vec3d, Pair<String, BlockPos>>> legs = new ArrayList<>();
        double startLaneX;
        if (stepInMasterArea()) {
            startLaneX = stepNearestLaneX(mc.player.getX());
        } else {
            startLaneX = mod.lowerMapCorner.getX() + 125.5;   // the GATE lane
            legs.add(new Pair(new Vec3d(mod.lowerMapCorner.getX() + 125.5, y, mod.lowerMapCorner.getZ() + 14.5), new Pair("sprint", null)));
        }
        if (Math.abs(startLaneX - targetLaneX) > 0.75) {
            legs.add(new Pair(new Vec3d(startLaneX, y, targetZ), new Pair("sprint", null)));   // z leg on the start lane
            legs.add(new Pair(new Vec3d(targetLaneX, y, targetZ), new Pair("sprint", null)));  // x leg at the target z
        } else if (Math.abs(mc.player.getZ() - targetZ) > 1.5 || !legs.isEmpty()) {
            legs.add(new Pair(new Vec3d(targetLaneX, y, targetZ), new Pair("sprint", null)));
        }
        for (int i = legs.size() - 1; i >= 0; i--) mod.checkpoints.addFirst(legs.get(i));
    }

    void stepQueueSweep(int lane, int startSpot, int endSpot) {
        mod.checkpoints.clear();
        int direction = Integer.compare(endSpot, startSpot);
        for (int spot = startSpot; ; spot += direction) {
            // SWEEP STANDING = PLATFORM CENTRE (lane + 0.5) - the EXACT position of the
            // proven python runner's cp(): (127 + 2k + 0.5), 0 mismatches across dozens
            // of verified batches. The old edge standing (lane + 0.0, from the dormant
            // batch-0 split proof) had ZERO pixel-verified normal batches behind it and
            // shaved the margin on the east pair's record band; the runner's centre is
            // the only experimentally proven standing.
            mod.checkpoints.add(new Pair<>(new Vec3d(
                mod.lowerMapCorner.getX() + lane + 0.5,
                mod.lowerMapCorner.getY(),
                mod.lowerMapCorner.getZ() + STEP_CHECKPOINT_Z[spot] + 0.5), new Pair<>("stepDwell", null)));
            if (spot == endSpot) break;
        }
        mod.state = State.Walking;
        stepMasterDwellTicks = 0;
        stepMasterSweepCompletionPending = false;
        // Approach discipline: reach the first station via LATTICE legs (gate if
        // entering from outside) - never a diagonal across the area (the resume case).
        Vec3d firstStation = mod.checkpoints.getFirst().getLeft();
        prependLatticeTransit(firstStation.x, firstStation.z);
    }

    void stepStartSweep(int lane, int startSpot, int endSpot) {
        // Always-on direction log: every sweep announces its lane and z-range, so a
        // wrong-direction or unexpected walk is visible in the log immediately.
        mod.info("§7Sweep: standing x=" + (mod.lowerMapCorner.getX() + lane + 0.5) + " z " + STEP_CHECKPOINT_Z[startSpot]
            + " -> " + STEP_CHECKPOINT_Z[endSpot]
            + (startSpot < endSpot ? " (N->S)" : " (S->N)") + ".");
        stepPendingSweepLane = lane;
        stepPendingSweepStart = startSpot;
        stepPendingSweepEnd = endSpot;
        stepSweepQueuePending = true;
        stepMasterWaitTicks = 0;
        stepTryQueuePendingSweep();
    }

    void stepTryQueuePendingSweep() {
        if (!stepSweepQueuePending) return;
        // PRESENCE only - the map must EXIST, never be HELD here. Holding happens at
        // the first stepDwell arrival; the walk to the station is map-free (holding
        // during transit re-records pixels - the resume-path bug, live 2026-10-20).
        if (stepWorkingMapId < 0) stepPinWorkingMap();
        if (stepWorkingMapId < 0 || stepFindWorkingMapSlot() < 0) {
            if (++stepMasterWaitTicks > 100) {
                abortStepMaster("working map is missing from the inventory");
            }
            return;
        }
        stepSweepQueuePending = false;
        stepQueueSweep(stepPendingSweepLane, stepPendingSweepStart, stepPendingSweepEnd);
    }

    void stepHandleSweepComplete() {
        stepMasterSweepCompletionPending = false;
        if (mod.debugPrints.get()) mod.info("§3[dbg] sweep complete in stage §b" + stepMasterStage);
        switch (stepMasterStage) {
            case BATCH_WALK_A -> {
                if (stepMasterBatch == 0 && mod.batch0SplitBuild.get() && !stepMasterBreak2Sent) {
                    // BATCH 0 SPLIT (physics: pair 0's floor must record with no tops above,
                    // so the build is split around W1): W1 recorded the floor+borrow clean.
                    // Request the TOPS half now; W2 (on 127) records them. No break(2) for
                    // batch 0 - W2 IS the tops' first and only record.
                    stepStowMap();
                    stepMasterBreak2Sent = true;   // reuses the flag as "first half done"
                    stepSlaveReadyBatch = -1;
                    stepSendToSlave("buildhalf:0:t");
                    stepMasterStage = StepMasterStage.WAIT_BATCH_BUILD;
                    stepMasterWaitTicks = 0;
                    stepQueueWaitHere("stepBatchSafe");
                    mod.info("§aBatch 0: floor recorded - tops half requested.");
                    return;
                }
                // Standard flow: walk 1 recorded the batch -> break(2) NOW. W2 (the
                // S->N suppression walk) waits until the slave COMPLETES break(2)
                // (user rule, live 2026-10-20): safe because W2 rides the EAST lane
                // 2k+129 - 127.5+ blocks from the west tops, beyond dominant record
                // range - so the already-recorded west pixels cannot be re-recorded.
                stepStowMap();
                stepW1Ms = System.currentTimeMillis() - stepPhaseStartMs;
                stepPhaseStartMs = System.currentTimeMillis();
                stepMasterBreak2Sent = true;
                stepSendToSlave("break2");
                stepMasterStage = StepMasterStage.WAIT_BREAK_2;
                stepMasterWaitTicks = 0;
                stepQueueWaitHere("stepBreak2Point");
                mod.info("§aBatch " + stepMasterBatch + ": walk 1 complete - break(2) requested; W2 follows its completion.");
            }
            case BATCH_WALK_B -> {
                // W2 COMPLETE (and break(2) already completed before W2 started).
                // Batch 0 split flow: NO break(2) exists - breakall directly.
                // Standard flow: break2done has arrived (W2 was gated on it), so
                // break-all is requested immediately.
                stepStowMap();
                stepW2Ms = System.currentTimeMillis() - stepPhaseStartMs;
                stepPhaseStartMs = System.currentTimeMillis();
                stepMasterStage = StepMasterStage.WAIT_BREAK_ALL;
                stepMasterWaitTicks = 0;
                boolean batch0Split = stepMasterBatch == 0 && mod.batch0SplitBuild.get() && stepMasterBreak2Sent;
                if ((batch0Split || stepSlaveBreak2DoneBatch == stepMasterBatch) && !stepMasterBreakAllSent) {
                    stepMasterBreakAllSent = true;
                    stepSendToSlave("breakall");
                    mod.info("§aBatch " + stepMasterBatch + ": W2 complete - break-all requested.");
                } else {
                    requeueStepWait(stepMasterBatch + 2, "stepBreakAllPoint");
                }
            }
            case CLOSING_WALK -> {
                stepStowMap();
                stepMasterStage = StepMasterStage.WAIT_CLOSING_BREAK;
                stepMasterWaitTicks = 0;
                stepCloseMapReady();
            }
            default -> abortStepMaster("unexpected sweep completion in " + stepMasterStage);
        }
    }

    void stepCloseMapReady() {
        if (stepMasterStage != StepMasterStage.WAIT_CLOSING_BREAK) return;
        if (stepMasterCloseBreakSent) return;
        stepMasterCloseBreakSent = true;
        stepSendToSlave("closebreak");
        mod.info("§aFinal map walk complete; closing pair 63 recycle requested.");
    }

    void tickStepMaster() {
        if (mapHoldWarnCooldown > 0) mapHoldWarnCooldown--;
        // Wait bookkeeping FIRST, before every pause gate: the requeue cycle keeps
        // mod.timeoutTicks > 0 for ~20 of 21 ticks, so counting the wait further down
        // stretched a "30s" heartbeat to ~10 real minutes (observed live 2026-10-20:
        // a stalled run produced no heartbeat at all).
        String waitSignal = switch (stepMasterStage) {
            case WAIT_BREAK_2 -> "break2done:" + stepMasterBatch;
            case WAIT_BATCH_BUILD -> "ready:" + stepMasterBatch;
            case WAIT_CLOSING_BUILD -> "ready:close";
            case WAIT_BREAK_ALL -> stepMasterBreakAllSent ? "done:" + stepMasterBatch : "break2done:" + stepMasterBatch;
            default -> null;
        };
        if (waitSignal != null) stepWaitTick(waitSignal);
        // Process any pending chest-inventory response FIRST - the step scheduler's early
        // return skips the legacy mod.restock.toBeHandledInvPacket block, so without this the master
        // opens a chest and never takes the items (the "empty map not withdrawn" bug)
        if (mod.restock.toBeHandledInvPacket != null) {
            if (mod.timeoutTicks > 0) {
                mod.timeoutTicks--;
                Utils.setForwardPressed(false);
                return;
            }
            mod.restock.handleInventoryPacket(mod.restock.toBeHandledInvPacket);
            mod.restock.toBeHandledInvPacket = null;
            if (mod.restock.closeNextInvPacket) {
                if (mc.currentScreen != null) mc.player.closeHandledScreen();
                mod.restock.closeNextInvPacket = false;
            }
            return;
        }

        // General pause timer (set by requeueStepWait, interactions, etc.)
        if (mod.timeoutTicks > 0) {
            mod.timeoutTicks--;
            Utils.setForwardPressed(false);
            Utils.setSneakPressed(false);
            return;
        }

        if (mc.player == null || mc.world == null) {
            Utils.setForwardPressed(false);
            Utils.setSneakPressed(false);
            return;
        }
        if (SlaveSystem.slaves.size() != 1 && stepMasterStage != StepMasterStage.ABORTED
            && stepMasterStage != StepMasterStage.READY_TO_LOCK
            && stepMasterStage != StepMasterStage.LOCK_WALK
            && stepMasterStage != StepMasterStage.LOCKING) {
            abortStepMaster("the slave disconnected");
            return;
        }

        if (stepMasterStage == StepMasterStage.LOCKING) {
            tickStepLock();
            return;
        }

        // FETCH_MAP: if the empty map was taken from the chest, chain to the fill flow
        if (stepMasterStage == StepMasterStage.FETCH_MAP && mod.state != State.AwaitMapChestResponse) {
            if (findEmptyMapSlot() >= 0) {
                beginStepMapFill();
                return;
            }
            if (stepFindWorkingMapSlot() >= 0) {
                stepPinWorkingMap();
                int resumeBatch = loadStepProgress();
                // start-batch is a FLOOR: a stale saved progress file can never drag
                // the run back before the configured starting batch.
                int firstBatch = Math.max(resumeBatch, Math.max(0, Math.min(62, mod.startBatch.get())));
                if (firstBatch > 0) {
                    mod.info("§aStep-checker: starting from batch " + firstBatch
                        + (resumeBatch > firstBatch - 1 ? " (resumed progress)" : " (start-batch)") + ".");
                    beginStepBatch(firstBatch);
                } else {
                    beginStepPrimingRun();
                }
                return;
            }
        }

        if (stepMasterStage == StepMasterStage.WAIT_FILLED_MAP) {
            Utils.setForwardPressed(false);
            Utils.setSneakPressed(false);
            stepStowMap();
            if (stepPinWorkingMap()) {
                beginStepPrimingRun();
            } else if (++stepMasterWaitTicks > 300) {
                abortStepMaster("empty map did not produce a filled map within 15 seconds");
            }
            return;
        }

        if (stepSweepQueuePending) {
            Utils.setForwardPressed(false);
            Utils.setSneakPressed(false);
            stepTryQueuePendingSweep();
            return;
        }

        if (stepMasterDwellTicks > 0) {
            Utils.setForwardPressed(false);
            Utils.setSneakPressed(false);
            if (stepMasterStage == StepMasterStage.BATCH_WALK_A
                || stepMasterStage == StepMasterStage.BATCH_WALK_B
                || stepMasterStage == StepMasterStage.CLOSING_WALK) {
                if (!stepEnsureMapHeld()) {
                    abortStepMaster("working map disappeared during a sweep");
                    return;
                }
                // USER RULE: the map is held ONLY while dwelling at a checkpoint -
                // the moment this dwell expires, stow before moving to the next one
                if (stepMasterDwellTicks == 1) stepStowMap();
            }
            stepMasterDwellTicks--;
            if (stepMasterDwellTicks == 0 && stepMasterSweepCompletionPending) {
                stepHandleSweepComplete();
            }
            return;
        }

        // WAIT stages consume their slave signals HERE, at tick level. The checkpoint
        // arrival chain was previously the ONLY consumer, and any broken arrival
        // (position just outside the tight 0.2-block buffer, a wedged pause gate)
        // deadlocked runs silently - diagnosed live 2026-10-19 (known issue #11). The
        // arrival handlers keep their stage guards and act as fallbacks only.
        // (Heartbeat/timeout counting lives at the TOP of this method, before the
        // pause gates, so it counts real ticks.)
        // FIXED CYCLE (user rule): W1 always N->S (records), W2 always S->N
        // (suppression) - identical every batch like the doc's walk pair, and it
        // chains perfectly: W1 ends south (wait there through break(2)), W2 runs
        // S->N from that end and ends north, where the next batch's W1 starts.
        switch (stepMasterStage) {
            case WAIT_BREAK_2 -> {
                // break(2) in progress. Sent at W1's completion (fallback: here).
                // When the slave reports break2done, W2 (the S->N suppression walk)
                // starts - NEVER before break(2) completes (user rule, live 2026-10-20).
                if (!stepMasterBreak2Sent) {
                    stepMasterBreak2Sent = true;
                    stepSendToSlave("break2");
                    mod.info("§aBatch " + stepMasterBatch + ": break(2) requested.");
                } else if (stepSlaveBreak2DoneBatch == stepMasterBatch) {
                    stepBreak2Ms = System.currentTimeMillis() - stepPhaseStartMs;
                    stepPhaseStartMs = System.currentTimeMillis();
                    stepMasterStage = StepMasterStage.BATCH_WALK_B;
                    int w2Lane = 2 * (stepMasterBatch + 1) + 127;
                    stepStartSweep(w2Lane, 4, 0);   // W2: fixed S->N
                    mod.info("§aBatch " + stepMasterBatch + ": break(2) complete - W2 suppression walk starting.");
                }
            }
            case WAIT_BATCH_BUILD -> {
                if (stepSlaveReadyBatch == stepMasterBatch) {
                    stepBuildMs = System.currentTimeMillis() - stepPhaseStartMs;
                    stepPhaseStartMs = System.currentTimeMillis();
                    stepMasterStage = StepMasterStage.BATCH_WALK_A;
                    // Split mode only: batch 0's W1 rides lane 125 (floor record); every
                    // other case uses the standard lane 2k+127.
                    int lane = stepMasterBatch == 0 && mod.batch0SplitBuild.get() && !stepMasterBreak2Sent
                        ? 125 : 2 * stepMasterBatch + 127;
                    stepStartSweep(lane, 0, 4);   // W1: fixed N->S
                }
            }
            case WAIT_CLOSING_BUILD -> {
                if (stepSlaveReadyClosing) {
                    stepSlaveReadyClosing = false;
                    stepMasterStage = StepMasterStage.CLOSING_WALK;
                    stepStartSweep(253, 0, 4);   // closing sweep: fixed N->S
                }
            }
            case WAIT_BREAK_ALL -> {
                boolean batch0Split = stepMasterBatch == 0 && mod.batch0SplitBuild.get() && stepMasterBreak2Sent;
                if (!stepMasterBreakAllSent && (batch0Split || stepSlaveBreak2DoneBatch == stepMasterBatch)) {
                    stepMasterBreakAllSent = true;
                    stepSendToSlave("breakall");
                    mod.info("§aBatch " + stepMasterBatch + ": break-all requested.");
                }
            }
            default -> { }
        }

        // LATTICE WATCHDOG + MAP-HOLD GUARD (user rule, 2026-10-20)
        checkLatticeWatchdog();
        checkMapHoldGuard();

        if (stepMasterStage == StepMasterStage.WAIT_CLOSING_BREAK && stepSlaveClosingDone) {
            stepSlaveClosingDone = false;
            stepMasterStage = StepMasterStage.READY_TO_LOCK;
            stepStowMap();
            mod.state = State.StandBy;
            mod.info("§aStep-checker print complete. Map is stowed and ready for cartography locking.");
            return;
        }

        if (stepMasterStage == StepMasterStage.WAIT_BREAK_ALL
            && stepMasterBreakAllSent
            && stepSlaveDoneBatch == stepMasterBatch) {
            int completedBatch = stepMasterBatch;
            stepSlaveDoneBatch = -1;
            stepBreakAllMs = System.currentTimeMillis() - stepPhaseStartMs;
            mod.info("§6[BATCH-TIME]§7 batch " + completedBatch + ": total §f" + fmtMs(System.currentTimeMillis() - stepBatchStartMs)
                + "§7 | build §f" + fmtMs(stepBuildMs)
                + "§7 | W1 §f" + fmtMs(stepW1Ms)
                + "§7 | break(2) §f" + fmtMs(stepBreak2Ms)
                + "§7 | W2 §f" + fmtMs(stepW2Ms)
                + "§7 | break-all+collect §f" + fmtMs(stepBreakAllMs));
            if (completedBatch < 62) {
                beginStepBatch(completedBatch + 1);
            } else {
                beginStepClosingRun();
            }
            return;
        }

        if (stepMasterStage == StepMasterStage.FILL_MAP && mod.checkpoints.isEmpty()) {
            if (++stepMasterWaitTicks > 100) {
                abortStepMaster("could not select the empty map at the fill checkpoint");
            } else {
                useEmptyMapAtFillSpot();
            }
            return;
        }

        if (stepMasterStage == StepMasterStage.READY_TO_LOCK || stepMasterStage == StepMasterStage.ABORTED) {
            Utils.setForwardPressed(false);
            Utils.setSneakPressed(false);
            stepStowMap();
            return;
        }

        if (mod.checkpoints.isEmpty()) {
            Utils.setForwardPressed(false);
            Utils.setSneakPressed(false);
            return;
        }

        Utils.setForwardPressed(true);
        Utils.setSneakPressed(false);
        Vec3d goal = mod.checkpoints.getFirst().getLeft();
        Vec3d horizontalGoal = goal.add(0, mc.player.getY() - goal.y, 0);
        if (PlayerUtils.distanceTo(horizontalGoal) < mod.checkpointBuffer.get()) {
            String action = mod.checkpoints.removeFirst().getRight().getLeft();
            String cpLog = action + "@" + (int) goal.x + "," + (int) goal.y + "," + (int) goal.z;
            if (!cpLog.equals(lastMasterCpLog)) {
                lastMasterCpLog = cpLog;
                mod.info("§7Master checkpoint: §f" + action + "§7 at (" + (int) goal.x + ", " + (int) goal.y + ", " + (int) goal.z + ")");
            }
            handleStepMasterCheckpoint(action);
            return;
        }

        double lookZ = goal.z;
        if (PlayerUtils.distanceTo(goal) > 2) {
            lookZ = mc.player.getZ() + Math.max(Math.min(goal.z - mc.player.getZ(), 1), -1);
        }
        mc.player.setYaw((float) Rotations.getYaw(new Vec3d(goal.x, goal.y, lookZ)));
        if (mod.sprinting.get() != StepCheckered.SprintMode.Off) mc.player.setSprinting(true);
    }

    void checkLatticeWatchdog() {
        if (!stepInMasterArea()) return;
        double px = mc.player.getX() - mod.lowerMapCorner.getX() - 125.5;
        double laneOff = Math.abs(px / 2.0 - Math.round(px / 2.0)) * 2.0;   // distance to nearest lane center
        boolean onRow = false;
        for (int rz : STEP_CHECKPOINT_Z) {
            if (Math.abs(mc.player.getZ() - (mod.lowerMapCorner.getZ() + rz + 0.5)) <= 0.9) { onRow = true; break; }
        }
        if (laneOff > 0.9 && !onRow && mapHoldWarnCooldown <= 0) {
            mapHoldWarnCooldown = 100;   // reuse the 5s warn throttle
            mod.warning("§cLATTICE VIOLATION: master is off the platform lattice at ("
                + (int) mc.player.getX() + ", " + (int) mc.player.getZ() + ") - inside the area it may only move "
                + "along lane/row lines, entering/exiting via the north-west gate.");
        }
    }

    void checkMapHoldGuard() {
        if (stepMasterDwellTicks > 0 || controlHoldActive) return;
        Integer heldId = mapIdOf(mc.player.getMainHandStack());
        if (heldId != null && heldId == stepWorkingMapId) {
            stepStowMap();
            if (mapHoldWarnCooldown <= 0) {
                mapHoldWarnCooldown = 100;
                mod.warning("§cHolding the filled map is NOT allowed while the module is enabled - "
                    + "moving with it re-records pixels and ruins progress. Map stowed.");
            }
        }
    }

    void handleStepMasterCheckpoint(String action) {
        switch (action) {
            case "stepFillMap" -> useEmptyMapAtFillSpot();
            case "stepFetchMap" -> {
                // Open the map chest; the AwaitMapChestResponse handler takes the map + pane.
                // After the map is taken, tickStepMaster sees FETCH_MAP + an empty map and
                // chains to beginStepMapFill (walk to the fill spot and use it).
                if (stepMasterStage == StepMasterStage.FETCH_MAP) {
                    Pair<BlockPos, Vec3d> chest = mod.restock.getBestChest(Items.CARTOGRAPHY_TABLE);
                    if (chest != null && chest.getLeft() != null) {
                        mod.state = State.AwaitMapChestResponse;
                        mod.restock.interactWithBlock(chest.getLeft());
                    } else {
                        abortStepMaster("map chest not found");
                    }
                }
            }
            case "stepBatchSafe" -> {
                if (stepMasterStage == StepMasterStage.WAIT_BATCH_BUILD && stepSlaveReadyBatch == stepMasterBatch) {
                    stepMasterStage = StepMasterStage.BATCH_WALK_A;
                    // Batch 0 merged flow: the priming walk was W1, so the first sweep
                    // triggered from this wait is W2 (tops) on lane 127 (direct-resume
                    // safety: without the flag it is W1 (floor) on 125). Every other
                    // batch sweeps its standard W1 lane. Direction: from whichever end
                    // the master is at (record content is direction-independent).
                    int lane = stepMasterBatch == 0 && mod.batch0SplitBuild.get() && !stepMasterBreak2Sent
                        ? 125 : 2 * stepMasterBatch + 127;
                    stepStartSweep(lane, 0, 4);   // W1: fixed N->S
                } else if (stepMasterStage == StepMasterStage.WAIT_BATCH_BUILD) {
                    // Wait IN PLACE (map stowed - safety from map-closed, not distance)
                    requeueStepWait(stepMasterBatch + 1, "stepBatchSafe");
                }
            }
            case "stepBreak2Point" -> {
                // Position holder for the WAIT_BREAK_2 stage - the tick-level consumer
                // sends break(2) and starts W2 on break2done. Doing either here would
                // race it (an arrival-driven breakall would skip W2 entirely).
            }
            case "stepBreakAllPoint" -> {
                if (stepMasterStage != StepMasterStage.WAIT_BREAK_ALL) return;
                if (stepSlaveBreak2DoneBatch == stepMasterBatch && !stepMasterBreakAllSent) {
                    stepMasterBreakAllSent = true;
                    stepSendToSlave("breakall");
                    mod.info("§aBatch " + stepMasterBatch + ": break-all requested.");
                } else if (stepMasterStage == StepMasterStage.WAIT_BREAK_ALL) {
                    requeueStepWait(stepMasterBatch + 2, "stepBreakAllPoint");
                }
            }
            case "stepClosingSafe" -> {
                if (stepMasterStage == StepMasterStage.WAIT_CLOSING_BUILD && stepSlaveReadyClosing) {
                    stepMasterStage = StepMasterStage.CLOSING_WALK;
                    stepStartSweep(253, 0, 4);   // closing sweep: fixed N->S
                } else if (stepMasterStage == StepMasterStage.WAIT_CLOSING_BUILD) {
                    requeueStepWait(64, "stepClosingSafe");
                }
            }
            case "stepLockTable" -> {
                if (stepMasterStage != StepMasterStage.LOCK_WALK) return;
                if (mod.cartographyTable == null) {
                    abortStepMaster("cartography table disappeared before locking");
                    return;
                }
                BlockHitResult hit = new BlockHitResult(
                    mod.cartographyTable.getLeft().toCenterPos(),
                    Utils.getInteractionSide(mod.cartographyTable.getLeft()),
                    mod.cartographyTable.getLeft(),
                    false);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                mc.player.swingHand(Hand.MAIN_HAND);
                stepMasterStage = StepMasterStage.LOCKING;
                stepLockStep = 1;
                stepLockDelay = 10;
                Utils.setForwardPressed(false);
                Utils.setSneakPressed(false);
            }
            case "stepTransit" -> {
                // Crossing a platform while the map is stowed is intentionally a no-op.
            }
            case "stepDwell" -> {
                if (stepMasterStage != StepMasterStage.BATCH_WALK_A
                    && stepMasterStage != StepMasterStage.BATCH_WALK_B
                    && stepMasterStage != StepMasterStage.CLOSING_WALK) return;
                if (!stepEnsureMapHeld()) {
                    abortStepMaster("working map disappeared at a sweep checkpoint");
                    return;
                }
                stepMasterDwellTicks = STEP_DWELL_TICKS;
                stepMasterSweepCompletionPending = mod.checkpoints.isEmpty();
            }
            default -> {
                if (mod.debugPrints.get()) mod.info("Unknown step master checkpoint action: " + action);
            }
        }
    }

    void stepQueueWaitHere(String action) {
        mod.checkpoints.clear();
        Vec3d platform = stepNearestPlatform();
        mod.checkpoints.add(new Pair<>(platform, new Pair<>(action, null)));
        mod.state = State.Walking;
        mod.info("§7Master queued: §f" + action + "§7 at (" + (int) platform.x + ", " + (int) platform.y + ", " + (int) platform.z + ")");
    }

    Vec3d stepNearestPlatform() {
        int laneIdx = Math.clamp((int) Math.round((mc.player.getX() - mod.lowerMapCorner.getX() - 125.5) / 2.0), 0, 64);
        int zIdx = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < STEP_CHECKPOINT_Z.length; i++) {
            double d = Math.abs(mc.player.getZ() - (mod.lowerMapCorner.getZ() + STEP_CHECKPOINT_Z[i]));
            if (d < best) { best = d; zIdx = i; }
        }
        // THE VALUE, not the index: passing zIdx (0..4) here queued every wait at
        // z_rel ~0.5 - the BOX'S NORTH EDGE - instead of the platform (live-caught
        // 2026-10-20: "stepBatchSafe at (-316, 99, -319)" every batch).
        return stepCheckpoint(125 + 2 * laneIdx, STEP_CHECKPOINT_Z[zIdx]);
    }

    void requeueStepWait(int checkpointIndex, String action) {
        mod.checkpoints.addFirst(new Pair<>(stepNearestPlatform(), new Pair<>(action, null)));
        mod.state = State.Walking;
        // Pause 1 second between re-checks - without this the master is already AT the
        // checkpoint when it re-queues, consumes it instantly, and oscillates back and forth
        mod.timeoutTicks = 20;
    }

    void stepWaitTick(String signal) {
        stepMasterWaitTicks++;
        if (stepMasterWaitTicks % 600 == 0) {
            mod.info("§7Still waiting for §f" + signal + "§7 (" + (stepMasterWaitTicks / 20) + "s)...");
        }
        if (stepMasterWaitTicks > 12000) {
            abortStepMaster("timed out after 10 minutes waiting for " + signal);
        }
    }

    void tickStepLock() {
        Utils.setForwardPressed(false);
        Utils.setSneakPressed(false);
        if (stepLockDelay > 0) {
            stepLockDelay--;
            return;
        }
        if (!(mc.player.currentScreenHandler instanceof CartographyTableScreenHandler table)) {
            abortStepMaster("cartography table screen did not open");
            return;
        }
        switch (stepLockStep) {
            case 1 -> {
                if (!moveIntoStepTableSlot(table, 0, stack -> {
                    Integer id = mapIdOf(stack);
                    return id != null && id == stepWorkingMapId
                        && !stack.contains(DataComponentTypes.CUSTOM_NAME);
                })) {
                    abortStepMaster("pinned working map is not in the inventory");
                    return;
                }
                stepLockStep = 2;
                stepLockDelay = 6;
            }
            case 2 -> {
                if (!moveIntoStepTableSlot(table, 1, stack -> stack.getItem() == Items.GLASS_PANE)) {
                    abortStepMaster("no glass pane is available for the cartography lock");
                    return;
                }
                stepLockStep = 3;
                stepLockDelay = 10;
            }
            case 3 -> {
                if (table.getSlot(2).getStack().isEmpty()) {
                    abortStepMaster("cartography table produced no locked-map result");
                    return;
                }
                mc.interactionManager.clickSlot(table.syncId, 2, 0, SlotActionType.QUICK_MOVE, mc.player);
                stepLockStep = 4;
                stepLockDelay = 8;
            }
            default -> {
                mc.player.closeHandledScreen();
                stepLockStep = -1;
                stepMasterStage = StepMasterStage.READY_TO_LOCK;
                mod.state = State.StandBy;
                mod.info("§aCartography lock complete: the locked copy was taken from the table.");
            }
        }
    }

    boolean moveIntoStepTableSlot(CartographyTableScreenHandler table,
                                          int targetSlot,
                                          Predicate<ItemStack> predicate) {
        for (int slot = 3; slot < table.slots.size(); slot++) {
            ItemStack stack = table.getSlot(slot).getStack();
            if (stack.isEmpty() || !predicate.test(stack)) continue;
            mc.interactionManager.clickSlot(table.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
            mc.interactionManager.clickSlot(table.syncId, targetSlot, 0, SlotActionType.PICKUP, mc.player);
            return true;
        }
        return false;
    }

    Integer mapIdOf(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof FilledMapItem)) return null;
        var id = stack.get(DataComponentTypes.MAP_ID);
        return id == null ? null : id.id();
    }

    int findEmptyMapSlot() {
        for (int slot = 0; slot < 36; slot++) {
            if (mc.player.getInventory().getStack(slot).getItem() == Items.MAP) return slot;
        }
        return -1;
    }

    int stepFindWorkingMapSlot() {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            Integer id = mapIdOf(stack);
            if (id == null || stack.contains(DataComponentTypes.CUSTOM_NAME)) continue;
            if (stepWorkingMapId < 0 || id == stepWorkingMapId) return slot;
        }
        return -1;
    }

    int stepFindMapIdSlot(int wantedId) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            Integer id = mapIdOf(stack);
            if (id != null && id == wantedId && !stack.contains(DataComponentTypes.CUSTOM_NAME)) return slot;
        }
        return -1;
    }

    boolean stepPinWorkingMap() {
        if (stepWorkingMapId >= 0) return stepFindWorkingMapSlot() >= 0;
        int slot = stepFindWorkingMapSlot();
        if (slot < 0) return false;
        Integer id = mapIdOf(mc.player.getInventory().getStack(slot));
        if (id == null) return false;
        stepWorkingMapId = id;
        mod.info("§aWorking map pinned: id=" + id + ".");
        return true;
    }

    boolean stepEnsureMapHeld() {
        if (stepWorkingMapId < 0 && !stepPinWorkingMap()) return false;
        Integer held = mapIdOf(mc.player.getMainHandStack());
        if (held != null && held == stepWorkingMapId) return true;

        int slot = stepFindWorkingMapSlot();
        if (slot < 0) return false;
        if (slot < 9) {
            mc.player.getInventory().setSelectedSlot(slot);
            stepMapSwapPending = false;
            return mapIdOf(mc.player.getMainHandStack()) != null
                && mapIdOf(mc.player.getMainHandStack()) == stepWorkingMapId;
        }
        if (!stepMapSwapPending) {
            swapPlayerInventoryToHotbar(slot, mc.player.getInventory().getSelectedSlot());
            stepMapSwapPending = true;
            stepMapSwapAge = 5;   // ticks until a retry (the filled result can land late)
        } else if (--stepMapSwapAge <= 0) {
            stepMapSwapPending = false;   // stale latch - retry the swap next tick
        }
        return false;
    }

    void stepStowMap() {
        if (mc.player == null) return;
        if (mapIdOf(mc.player.getMainHandStack()) == null) {
            stepMapSwapPending = false;   // nothing to holster - still clear a stale latch
            return;
        }
        int selected = mc.player.getInventory().getSelectedSlot();
        for (int slot = 0; slot < 9; slot++) {
            if (mapIdOf(mc.player.getInventory().getStack(slot)) == null) {
                mc.player.getInventory().setSelectedSlot(slot);
                stepMapSwapPending = false;
                return;
            }
        }
        for (int slot = 9; slot < 36; slot++) {
            if (mapIdOf(mc.player.getInventory().getStack(slot)) == null) {
                swapPlayerInventoryToHotbar(slot, selected);
                stepMapSwapPending = true;
                return;
            }
        }
        mod.warning("No non-map inventory slot is available to stow the working map.");
    }

    void swapPlayerInventoryToHotbar(int inventorySlot, int hotbarSlot) {
        if (inventorySlot < 9 || inventorySlot >= 36 || hotbarSlot < 0 || hotbarSlot >= 9) return;
        int handlerSlot = inventorySlot;
        mc.interactionManager.clickSlot(
            mc.player.currentScreenHandler.syncId,
            handlerSlot,
            hotbarSlot,
            SlotActionType.SWAP,
            mc.player);
    }

    void useEmptyMapAtFillSpot() {
        int slot = findEmptyMapSlot();
        if (slot < 0) {
            abortStepMaster("the empty map disappeared before the fill checkpoint");
            return;
        }
        if (slot >= 9) {
            if (!stepMapSwapPending) {
                swapPlayerInventoryToHotbar(slot, mc.player.getInventory().getSelectedSlot());
                stepMapSwapPending = true;
            }
            return;
        }
        mc.player.getInventory().setSelectedSlot(slot);
        mc.getNetworkHandler().sendPacket(new PlayerInteractItemC2SPacket(
            Hand.MAIN_HAND,
            Utils.getNextInteractID(),
            mc.player.getYaw(),
            mc.player.getPitch()));
        stepMasterStage = StepMasterStage.WAIT_FILLED_MAP;
        stepMasterWaitTicks = 0;
        Utils.setForwardPressed(false);
        Utils.setSneakPressed(false);
        mod.info("§7Empty map used at the fill checkpoint; waiting for the filled result.");
    }

    void abortStepMaster(String reason) {
        if (stepMasterStage == StepMasterStage.ABORTED) return;
        stepMasterStage = StepMasterStage.ABORTED;
        mod.checkpoints.clear();
        mod.state = State.StandBy;
        stepSweepQueuePending = false;
        stepMasterDwellTicks = 0;
        stepMasterSweepCompletionPending = false;
        Utils.setForwardPressed(false);
        Utils.setSneakPressed(false);
        stepStowMap();
        mod.error("Step-checker master stopped: " + reason + ".");
    }

    void serviceControlHold() {
        if (!controlHoldActive || SlaveSystem.isSlave || mc.player == null || mc.world == null) return;
        if (stepMasterStage != StepMasterStage.IDLE) { controlHoldActive = false; return; }   // a real run takes over
        if (stepWorkingMapId < 0) stepPinWorkingMap();
        if (stepWorkingMapId >= 0) {
            // A pinned id whose map left the inventory (RCON clear, drop) is stale -
            // drop it so the empty-map auto-use branch can run again
            if (stepFindWorkingMapSlot() < 0) {
                mod.info("§7Pinned map " + stepWorkingMapId + " is gone - unpinning.");
                stepWorkingMapId = -1;
                return;
            }
            stepEnsureMapHeld();
        } else {
            // No filled map yet: use one EMPTY map at the current spot. The given map
            // usually lands in the main inventory (hotbar full) - swap it in first.
            int slot = findEmptyMapSlot();
            if (slot >= 9) {
                if (!controlSwapPending) {
                    swapPlayerInventoryToHotbar(slot, mc.player.getInventory().getSelectedSlot());
                    controlSwapPending = true;
                }
            } else if (slot >= 0 && controlUseCooldown <= 0) {
                controlUseCooldown = 40;   // ~2s: one use attempt, then wait for the result
                controlSwapPending = false;
                mc.player.getInventory().setSelectedSlot(slot);
                mc.getNetworkHandler().sendPacket(new PlayerInteractItemC2SPacket(
                    Hand.MAIN_HAND, Utils.getNextInteractID(), mc.player.getYaw(), mc.player.getPitch()));
                mod.info("§7No filled map - used one empty map at the current spot.");
            }
            if (controlUseCooldown > 0) controlUseCooldown--;
        }
    }

    void saveStepProgress(int batch) {
        if (StepFiles.saveProgress(mod.mapFolder, batch, stepWorkingMapId)) {
            cachedResumeBatch = batch;
        } else if (mod.debugPrints.get()) {
            mod.warning("Could not save step progress.");
        }
    }


    void clearStepProgress() {
        cachedResumeBatch = -1;
        StepFiles.clearProgress(mod.mapFolder);
    }

    String fmtMs(long ms) {
        long sec = Math.max(0, ms / 1000);
        return (sec / 60) + "m" + String.format("%02ds", sec % 60);
    }


    int loadStepProgress() {
        int[] progress = StepFiles.loadProgress(mod.mapFolder);
        stepSavedProgressMapId = progress[1];
        return progress[0];
    }

}
