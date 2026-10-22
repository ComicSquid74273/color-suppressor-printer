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
import net.minecraft.item.map.MapState;
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
    final MapLifecycle maps;
    final CartographyLock lock;

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
    String lastMasterCpLog;
    int stepSavedProgressMapId = -1;
    long stepBatchStartMs, stepPhaseStartMs;
    int cachedResumeBatch = -1;
    int mapHoldWarnCooldown;
    long stepBuildMs, stepW1Ms, stepBreak2Ms, stepW2Ms, stepBreakAllMs;
    static final int[] STEP_CHECKPOINT_Z = {14, 40, 64, 88, 112};
    static final int STEP_DWELL_TICKS = 80;
    // PIXEL-ARRIVAL DWELL GATE (user request, 2026-10-22): while the map is held
    // the server re-renders and pushes every in-range pixel to the client, so
    // "no byte anywhere in MapState.colors changed for N ticks" proves the
    // pixels this standing records have ALL arrived. Full-array diff on purpose:
    // a held map only ever receives updates for pixels within record range of
    // the holder, so the whole array is the exact superset of anything this
    // dwell can record - no band model to get wrong, no late pixel can slip
    // past it. Early release clamps the countdown to 2 so the proven ==1-stow /
    // ==0-completion choreography runs verbatim; the fixed 80-tick cap stays as
    // the fail-safe. The gate can only END a dwell early, never extend one.
    static final int STEP_DWELL_FLOOR_TICKS = 10;
    byte[] stepDwellPrevPixels;
    int stepDwellQuietTicks;
    int stepDwellChangeCount;
    boolean stepDwellGateFired;

    // ECHO CHECKPOINTS (user design, 2026-10-21): slave pass progress counters
    // consumed by the sweep station gate. Written from the TCP reader thread like
    // the other stepSlave* flags; validated against the CURRENT batch there and
    // max-merged so a lagged echo can never regress a release.
    volatile int stepEchoBuildCount;
    volatile int stepEchoBreak2Count;
    volatile int stepEchoBatch = -1;
    // Progressive sweep feed: a station enters the checkpoint queue ONLY once its
    // echo release has arrived, so the walker physically cannot walk to - or dwell
    // at - an unreleased station (empty queue = the master stands still).
    boolean stepSweepFeederActive;
    int stepSweepLane, stepSweepStartSpot, stepSweepEndSpot, stepSweepNextSpot, stepSweepDir;
    int stepSweepFedOrdinal = -1;   // ordinal of the most recently queued station (the hold gate reads it)
    // BATCH 0 HOLD-THROUGH WALK (user design, 2026-10-22): the recording walk rides
    // lane 125 (the N-gate -> S-fill-checkpoint lane) with the map held the whole
    // way, ending at the S fill checkpoint. stepSweepExtraFinal = one extra final
    // dwell waypoint appended by the feeder after the last station; set only for
    // batch 0's W1. stepBatch0SouthPending gates break(2) until the master has
    // transited from the S fill checkpoint to the S normal checkpoint (129, z112).
    Vec3d stepSweepExtraFinal;
    boolean stepBatch0SouthPending;

    StepMaster(StepCheckered mod) {
        this.mod = mod;
        maps = new MapLifecycle(mod);
        lock = new CartographyLock(mod);
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
        stepDwellPrevPixels = null;
        stepDwellQuietTicks = 0;
        stepDwellChangeCount = 0;
        stepDwellGateFired = false;
        stepMasterSweepCompletionPending = false;
        stepSweepQueuePending = false;
        stepPendingSweepLane = -1;
        stepPendingSweepStart = -1;
        stepPendingSweepEnd = -1;
        stepMasterBreak2Sent = false;
        stepMasterBreakAllSent = false;
        stepMasterCloseBreakSent = false;
        stepSweepExtraFinal = null;
        stepBatch0SouthPending = false;
        maps.resetForActivation();
        lock.resetForActivation();
        lastMasterCpLog = null;
        stepSavedProgressMapId = -1;
        cachedResumeBatch = -1;
        mapHoldWarnCooldown = 0;
        stepEchoBuildCount = 0;
        stepEchoBreak2Count = 0;
        stepEchoBatch = -1;
        stepSweepFeederActive = false;
        stepSweepLane = -1;
        stepSweepStartSpot = -1;
        stepSweepEndSpot = -1;
        stepSweepNextSpot = -1;
        stepSweepDir = 1;
        stepSweepFedOrdinal = -1;
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
        stepSweepExtraFinal = null;
        stepBatch0SouthPending = false;
        maps.resetForActivation();
        lock.resetForActivation();
        stepSlaveReadyBatch = -1;
        stepSlaveBreak2DoneBatch = -1;
        stepSlaveDoneBatch = -1;
        stepSlaveReadyClosing = false;
        stepSlaveClosingDone = false;
        maps.stepStowMap();

        if (maps.stepFindWorkingMapSlot() >= 0) {
            // CRASH/RESTART/DISABLE RECOVERY: the progress file carries the last
            // in-progress batch AND the working map's id. Resume re-pins the EXACT
            // saved map (never just any filled map - a wrong map would corrupt the
            // run) and re-runs the in-progress batch. Only RESET deletes the file.
            int resumeBatch = loadStepProgress();
            if (resumeBatch > 0) {
                if (stepSavedProgressMapId >= 0) {
                    int savedSlot = maps.stepFindMapIdSlot(stepSavedProgressMapId);
                    if (savedSlot < 0) {
                        mod.error("§cProgress file says batch " + resumeBatch + " on map id " + stepSavedProgressMapId
                            + ", but that map is NOT in the inventory - the recorded pixels cannot be recovered. "
                            + "Starting a FRESH run (hold the working map to resume).");
                        clearStepProgress();
                        beginStepPrimingRun();
                        return;
                    }
                    maps.stepWorkingMapId = stepSavedProgressMapId;
                    mod.info("§aWorking map re-pinned by saved id=" + maps.stepWorkingMapId + ".");
                } else {
                    maps.stepPinWorkingMap();   // legacy progress file without map id
                }
                mod.info("§aStep-checker: resuming from batch " + resumeBatch + " (progress file).");
                stepMasterBatch = resumeBatch - 1;
                beginStepBatch(resumeBatch);
            } else {
                maps.stepPinWorkingMap();
                beginStepPrimingRun();
            }
        } else if (maps.findEmptyMapSlot() >= 0) {
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
        maps.stepStowMap();
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
        maps.stepStowMap();
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
        // BATCH 0 (user design, 2026-10-22): NO pre-build. The slave runs the plain
        // standard build(4); the master's W1 recording walk rides lane 125 holding
        // the map throughout (see the W1 lane pick), ending at the S fill checkpoint.
        mod.info("§aBatch 0: standard build + hold-the-map lane-125 walk (no pre-build).");
        beginStepBatch(0);
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
        stepEchoBuildCount = 0;
        stepEchoBreak2Count = 0;
        stepEchoBatch = batch;
        stepMasterBreak2Sent = false;
        stepMasterBreakAllSent = false;
        stepBatch0SouthPending = false;
        stepSweepExtraFinal = null;
        stepSendToSlave("build:" + batch);
        // Build wait: IN PLACE at the nearest platform (the previous batch's W2 already
        // ended on this batch's W1 lane) - map stowed, no repositioning leg
        stepQueueWaitHere("stepBatchSafe");
        maps.stepStowMap();
        mod.info("§aStep-checker batch " + batch + " build requested; waiting in place.");
    }

    void beginStepClosingRun() {
        stepMasterStage = StepMasterStage.WAIT_CLOSING_BUILD;
        stepMasterWaitTicks = 0;
        stepSlaveReadyClosing = false;
        stepSendToSlave("close");
        stepQueueWaitHere("stepClosingSafe");
        maps.stepStowMap();
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
        // SWEEP STANDING = PLATFORM CENTRE (lane + 0.5) - the EXACT position of the
        // proven python runner's cp(): (127 + 2k + 0.5), 0 mismatches across dozens
        // of verified batches. The old edge standing (lane + 0.0, from the dormant
        // batch-0 split proof) had ZERO pixel-verified normal batches behind it and
        // shaved the margin on the east pair's record band; the runner's centre is
        // the only experimentally proven standing.
        stepSweepLane = lane;
        stepSweepStartSpot = startSpot;
        stepSweepEndSpot = endSpot;
        stepSweepDir = Integer.compare(endSpot, startSpot);
        stepSweepNextSpot = startSpot;
        stepSweepFeederActive = true;
        mod.state = State.Walking;
        stepMasterDwellTicks = 0;
        stepMasterSweepCompletionPending = false;
        stepFeedSweepStations();
    }

    /** ECHO STATION FEED (user design, 2026-10-21): queues the next sweep station
     *  only once its WALK release has arrived, one station ahead at a time. The
     *  walker moves exclusively toward queued checkpoints, so the master never
     *  walks to an unreleased station - and while gated it stands with the map
     *  stowed, counting the SAME named-wait heartbeat/timeout as every other
     *  wait (a dead slave trips the 10-minute abort, not a silent hang). */
    void stepFeedSweepStations() {
        if (!stepSweepFeederActive || !mod.checkpoints.isEmpty()) return;
        if (stepSweepNextSpot == stepSweepEndSpot + stepSweepDir) {
            if (stepSweepExtraFinal != null) {
                // BATCH 0 W1 TERMINUS (user design, 2026-10-22): one extra final dwell
                // beyond the last station - the S fill checkpoint (125,115). Its walk
                // release resolves to the pass-done short-circuit (echo >= 6 is past
                // the 4-echo max), so the master can only go there once the build is
                // complete - the user ordering "S fill checkpoint reached before break(2)".
                int ordinal = Math.abs(stepSweepEndSpot - stepSweepStartSpot) + 1;
                if (!stepWalkReleased(ordinal)) {
                    stepWaitTick("echo " + (ordinal + 1) + "/4 (walk to the S fill checkpoint)");
                    return;
                }
                mod.checkpoints.add(new Pair<>(stepSweepExtraFinal, new Pair<>("stepDwellFinal", null)));
                stepSweepFedOrdinal = ordinal;
                stepMasterWaitTicks = 0;
                stepSweepExtraFinal = null;   // consumed exactly once per sweep
                return;
            }
            stepSweepFeederActive = false;   // every station queued - the walk finishes itself
            return;
        }
        int ordinal = Math.abs(stepSweepNextSpot - stepSweepStartSpot);
        if (!stepWalkReleased(ordinal)) {
            stepWaitTick("echo " + (ordinal + 1) + "/4 (walk to station " + (ordinal + 1)
                + "/5 of the " + (stepMasterStage == StepMasterStage.BATCH_WALK_B ? "break(2) shadow" : "print shadow") + ")");
            return;
        }
        // With an extra final queued behind them, the stations are plain dwells -
        // ONLY the extra final point completes the sweep.
        boolean isFinal = stepSweepNextSpot == stepSweepEndSpot && stepSweepExtraFinal == null;
        mod.checkpoints.add(new Pair<>(new Vec3d(
            mod.lowerMapCorner.getX() + stepSweepLane + 0.5,
            mod.lowerMapCorner.getY(),
            mod.lowerMapCorner.getZ() + STEP_CHECKPOINT_Z[stepSweepNextSpot] + 0.5),
            new Pair<>(isFinal ? "stepDwellFinal" : "stepDwell", null)));
        if (ordinal == 0) {
            // Approach discipline: reach the first station via LATTICE legs (gate if
            // entering from outside) - never a diagonal across the area (the resume case).
            Vec3d firstStation = mod.checkpoints.getFirst().getLeft();
            prependLatticeTransit(firstStation.x, firstStation.z);
        }
        stepSweepFedOrdinal = ordinal;
        stepMasterWaitTicks = 0;   // progress made - reset the gated-wait stall counter
        stepSweepNextSpot += stepSweepDir;
    }

    /** Walk release (user rule, tightened 2026-10-21): the master may WALK toward
     *  sweep station ordinal k once the slave's echo count >= k+1. Walking is
     *  map-STOWED transit (the live-proven hold guard), so an early walk touches
     *  no pixels - the hold is the only thing that records. */
    boolean stepWalkReleased(int ordinal) {
        return stepEchoReleased(ordinal + 1);
    }

    /** Hold release (user rule): the map is held / the dwell starts at station
     *  ordinal k only once the slave's echo count >= k+2 - the slave stays ONE
     *  travel checkpoint (~26 rows) ahead of the master's +-14-15 row record
     *  band, keeping >= ~21 rows of slack at every hold (station 1: band edge
     *  ~29 vs slave at row 52; the binding cases sit at 21-24). The echo max is
     *  4, so stations 4-5 hold only on the pass-done signals - station 5's band
     *  touches the last rows, which is exactly the old full-build semantics.
     *  With NO echoes at all the sweep degenerates to the old full-wait flow
     *  (ready / break2done release everything). */
    boolean stepHoldReleased(int ordinal) {
        return stepEchoReleased(ordinal + 2);
    }

    private boolean stepEchoReleased(int requiredEcho) {
        if (stepMasterStage == StepMasterStage.CLOSING_WALK) return true;
        if (stepMasterStage == StepMasterStage.BATCH_WALK_A) {
            return stepSlaveReadyBatch == stepMasterBatch
                || (stepEchoBatch == stepMasterBatch && stepEchoBuildCount >= requiredEcho);
        }
        if (stepMasterStage == StepMasterStage.BATCH_WALK_B) {
            return stepSlaveBreak2DoneBatch == stepMasterBatch
                || (stepEchoBatch == stepMasterBatch && stepEchoBreak2Count >= requiredEcho);
        }
        return true;
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
        if (maps.stepWorkingMapId < 0) maps.stepPinWorkingMap();
        if (maps.stepWorkingMapId < 0 || maps.stepFindWorkingMapSlot() < 0) {
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
                if (stepMasterBatch == 0) {
                    // BATCH 0 (user design, 2026-10-22): the lane-125 hold-throughout
                    // walk finished at the S fill checkpoint - pair 0 (floor + tops)
                    // and pair 1's recessives are recorded in the one pass. Before
                    // break(2) starts, transit (map stowed, normal discipline) to the
                    // S NORMAL checkpoint of the W2 lane; break(2) is sent ON ARRIVAL
                    // ("stepBatch0South"), then the standard flow takes over.
                    maps.stepStowMap();
                    stepW1Ms = System.currentTimeMillis() - stepPhaseStartMs;
                    stepPhaseStartMs = System.currentTimeMillis();
                    stepMasterStage = StepMasterStage.WAIT_BREAK_2;
                    stepMasterWaitTicks = 0;
                    stepBatch0SouthPending = true;
                    mod.checkpoints.clear();
                    Vec3d southNormal = stepCheckpoint(2 * (stepMasterBatch + 1) + 127, STEP_CHECKPOINT_Z[4]);
                    mod.checkpoints.add(new Pair<>(southNormal, new Pair<>("stepBatch0South", null)));
                    prependLatticeTransit(southNormal.x, southNormal.z);
                    mod.state = State.Walking;
                    mod.info("§aBatch 0: lane-125 walk complete - moving to the S checkpoint before break(2).");
                    return;
                }
                // Standard flow: walk 1 recorded the batch -> break(2) NOW. W2 (the
                // S->N suppression walk) waits until the slave COMPLETES break(2)
                // (user rule, live 2026-10-20): safe because W2 rides the EAST lane
                // 2k+129 - 127.5+ blocks from the west tops, beyond dominant record
                // range - so the already-recorded west pixels cannot be re-recorded.
                maps.stepStowMap();
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
                // W2 COMPLETE (and break(2) already completed before W2 started):
                // break2done has arrived (W2 was gated on it), so break-all is
                // requested immediately.
                maps.stepStowMap();
                stepW2Ms = System.currentTimeMillis() - stepPhaseStartMs;
                stepPhaseStartMs = System.currentTimeMillis();
                stepMasterStage = StepMasterStage.WAIT_BREAK_ALL;
                stepMasterWaitTicks = 0;
                if (stepSlaveBreak2DoneBatch == stepMasterBatch && !stepMasterBreakAllSent) {
                    stepMasterBreakAllSent = true;
                    stepSendToSlave("breakall");
                    mod.info("§aBatch " + stepMasterBatch + ": W2 complete - break-all requested.");
                } else {
                    requeueStepWait(stepMasterBatch + 2, "stepBreakAllPoint");
                }
            }
            case CLOSING_WALK -> {
                maps.stepStowMap();
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
            lock.tickStepLock();
            return;
        }

        // FETCH_MAP: if the empty map was taken from the chest, chain to the fill flow
        if (stepMasterStage == StepMasterStage.FETCH_MAP && mod.state != State.AwaitMapChestResponse) {
            if (maps.findEmptyMapSlot() >= 0) {
                beginStepMapFill();
                return;
            }
            if (maps.stepFindWorkingMapSlot() >= 0) {
                maps.stepPinWorkingMap();
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
            maps.stepStowMap();
            if (maps.stepPinWorkingMap()) {
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
                if (!maps.stepEnsureMapHeld()) {
                    abortStepMaster("working map disappeared during a sweep");
                    return;
                }
                // USER RULE: the map is held ONLY while dwelling at a checkpoint -
                // the moment this dwell expires, stow before moving to the next one.
                // BATCH 0 EXCEPTION (user design, 2026-10-22): its lane-125 W1 holds
                // the map THROUGHOUT the walk - dwell expiry keeps holding into the
                // next leg; the sweep-complete handler stows at the S fill checkpoint.
                if (stepMasterDwellTicks == 1
                    && !(stepMasterStage == StepMasterStage.BATCH_WALK_A && stepMasterBatch == 0)) {
                    maps.stepStowMap();
                }
            }
            // PIXEL-ARRIVAL GATE: only while above the clamp target - a release
            // sets the countdown to 2, so the ==1 stow / ==0 completion path one
            // tick later runs byte-for-byte identical to a natural expiry.
            if (stepMasterDwellTicks > 2 && mod.adaptiveDwell.get()) tickDwellGate();
            stepMasterDwellTicks--;
            if (stepMasterDwellTicks == 0) {
                if (mod.adaptiveDwell.get() && stepDwellPrevPixels != null && !stepDwellGateFired) {
                    mod.warning("§7[DWELL] §ebatch " + stepMasterBatch + " " + stepMasterStage
                        + ": released at the " + STEP_DWELL_TICKS + "-tick cap - pixel stream never went quiet ("
                        + stepDwellChangeCount + " px changed).");
                }
                if (stepMasterSweepCompletionPending) {
                    stepHandleSweepComplete();
                }
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
                // W2 (the S->N suppression walk) starts either on break2done (the
                // full-wait rule, kept as the completion path and the no-echo
                // fallback) or on break(2) echo 1/4 - the WALK level. It then
                // trails the pass: the map is only HELD at a station once the
                // slave's echo count reaches station+2 (stepHoldReleased), so the
                // record band stays >= ~21 rows behind the breaking frontier.
                // Batch 0 holds the fallback: break(2) is sent by the "stepBatch0South"
                // arrival handler, so the slave starts breaking only once the master
                // stands at the S normal checkpoint (user ordering, 2026-10-22).
                if (!stepMasterBreak2Sent && !stepBatch0SouthPending) {
                    stepMasterBreak2Sent = true;
                    stepSendToSlave("break2");
                    mod.info("§aBatch " + stepMasterBatch + ": break(2) requested.");
                } else if (stepMasterBreak2Sent
                    && (stepSlaveBreak2DoneBatch == stepMasterBatch
                    || (stepEchoBatch == stepMasterBatch && stepEchoBreak2Count >= 1))) {
                    stepBreak2Ms = System.currentTimeMillis() - stepPhaseStartMs;
                    stepPhaseStartMs = System.currentTimeMillis();
                    stepMasterStage = StepMasterStage.BATCH_WALK_B;
                    int w2Lane = 2 * (stepMasterBatch + 1) + 127;
                    stepStartSweep(w2Lane, 4, 0);   // W2: fixed S->N
                    mod.info(stepSlaveBreak2DoneBatch == stepMasterBatch
                        ? "§aBatch " + stepMasterBatch + ": break(2) complete - W2 suppression walk starting."
                        : "§aBatch " + stepMasterBatch + ": break(2) echo 1/4 - W2 walking, holds from echo 2/4.");
                }
            }
            case WAIT_BATCH_BUILD -> {
                // W1 launches on ready (the full-wait fallback) or on print echo
                // 1/4 - the WALK level. It then trails the print: the map is only
                // HELD at a station once the slave's echo count reaches station+2
                // (stepHoldReleased), keeping the record band >= ~21 rows behind the
                // completeness frontier.
                boolean echoLaunch = stepEchoBatch == stepMasterBatch && stepEchoBuildCount >= 1;
                if (stepSlaveReadyBatch == stepMasterBatch || echoLaunch) {
                    stepBuildMs = System.currentTimeMillis() - stepPhaseStartMs;
                    stepPhaseStartMs = System.currentTimeMillis();
                    stepMasterStage = StepMasterStage.BATCH_WALK_A;
                    // BATCH 0 (user design, 2026-10-22): W1 rides lane 125 - the
                    // N-gate -> S-fill-checkpoint lane - HOLDING THE MAP THROUGHOUT
                    // (dwells AND transit legs), so lanes 0-3 at 122-125 blocks record
                    // both classes in one pass; this walk replaces the old pre-build.
                    // Every other batch uses the standard lane 2k+127.
                    boolean batch0 = stepMasterBatch == 0;
                    int lane = batch0 ? 125 : 2 * stepMasterBatch + 127;
                    stepStartSweep(lane, 0, 4);   // W1: fixed N->S
                    if (batch0) {
                        stepSweepExtraFinal = stepCheckpoint(125, 115);
                        if (!maps.stepEnsureMapHeld()) {
                            abortStepMaster("working map disappeared at the batch-0 walk start");
                            return;
                        }
                        mod.info("§aBatch 0: W1 on lane 125, map held throughout; terminus = S fill checkpoint (125,115).");
                    }
                    if (!echoLaunch || stepSlaveReadyBatch == stepMasterBatch) {
                        mod.info("§aBatch " + stepMasterBatch + ": build complete - W1 starting.");
                    } else {
                        mod.info("§aBatch " + stepMasterBatch + ": print echo 1/4 - W1 walking, holds from echo 2/4.");
                    }
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
                if (!stepMasterBreakAllSent && stepSlaveBreak2DoneBatch == stepMasterBatch) {
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
            maps.stepStowMap();
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
                maps.useEmptyMapAtFillSpot();
            }
            return;
        }

        if (stepMasterStage == StepMasterStage.READY_TO_LOCK || stepMasterStage == StepMasterStage.ABORTED) {
            Utils.setForwardPressed(false);
            Utils.setSneakPressed(false);
            maps.stepStowMap();
            return;
        }

        // Echo station feed: the next sweep station enters the queue only when its
        // release has arrived. Runs before the walker's empty-queue standstill so a
        // release transitions to walking on the same tick it arrives.
        stepFeedSweepStations();

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
            String action = mod.checkpoints.getFirst().getRight().getLeft();
            // HOLD GATE (echo checkpoints): ARRIVED at a sweep station whose hold
            // release hasn't arrived yet - do NOT consume the checkpoint. Stand on
            // it with the map stowed (standing is pixel-free; only the dwell-HOLD
            // records) and count the gated wait; the station dispatches on the
            // very tick its echo lands. Feeding the next station is blocked too,
            // because this one still occupies the queue head.
            if ((action.equals("stepDwell") || action.equals("stepDwellFinal"))
                && (stepMasterStage == StepMasterStage.BATCH_WALK_A
                    || stepMasterStage == StepMasterStage.BATCH_WALK_B)
                && !stepHoldReleased(stepSweepFedOrdinal)) {
                Utils.setForwardPressed(false);
                Utils.setSneakPressed(false);
                stepWaitTick("echo " + (stepSweepFedOrdinal + 2) + "/4 (hold at station " + (stepSweepFedOrdinal + 1)
                    + "/5 of the " + (stepMasterStage == StepMasterStage.BATCH_WALK_B ? "break(2) shadow" : "print shadow") + ")");
                return;
            }
            mod.checkpoints.removeFirst();
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
        if (stepMasterDwellTicks > 0 || maps.controlHoldActive) return;
        // BATCH 0 EXCEPTION: the lane-125 W1 holds the map through its transit legs
        // too (that continuous recording is what replaces the old pre-build). Every
        // other stage - including batch 0's own S->N walk and the post-fill transit -
        // keeps the guard: moving with the map re-records pixels.
        if (stepMasterStage == StepMasterStage.BATCH_WALK_A && stepMasterBatch == 0) return;
        Integer heldId = maps.mapIdOf(mc.player.getMainHandStack());
        if (heldId != null && heldId == maps.stepWorkingMapId) {
            maps.stepStowMap();
            if (mapHoldWarnCooldown <= 0) {
                mapHoldWarnCooldown = 100;
                mod.warning("§cHolding the filled map is NOT allowed while the module is enabled - "
                    + "moving with it re-records pixels and ruins progress. Map stowed.");
            }
        }
    }

    void handleStepMasterCheckpoint(String action) {
        switch (action) {
            case "stepFillMap" -> maps.useEmptyMapAtFillSpot();
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
                    // Same pick as the tick-level consumer (arrival is only a fallback):
                    // batch 0 rides lane 125 to the S fill checkpoint; every other
                    // batch sweeps its standard W1 lane 2k+127, N->S.
                    boolean batch0 = stepMasterBatch == 0;
                    int lane = batch0 ? 125 : 2 * stepMasterBatch + 127;
                    stepStartSweep(lane, 0, 4);   // W1: fixed N->S
                    if (batch0) {
                        stepSweepExtraFinal = stepCheckpoint(125, 115);
                        maps.stepEnsureMapHeld();
                    }
                } else if (stepMasterStage == StepMasterStage.WAIT_BATCH_BUILD) {
                    // Wait IN PLACE (map stowed - safety from map-closed, not distance)
                    requeueStepWait(stepMasterBatch + 1, "stepBatchSafe");
                }
            }
            case "stepBatch0South" -> {
                // BATCH 0 (user design, 2026-10-22): the master has reached the S normal
                // checkpoint of the W2 lane - NOW break(2) may start. W2 itself still
                // launches from the tick-level consumer on break2done/echo.
                if (stepMasterStage == StepMasterStage.WAIT_BREAK_2 && stepBatch0SouthPending) {
                    stepBatch0SouthPending = false;
                    if (!stepMasterBreak2Sent) {
                        stepMasterBreak2Sent = true;
                        stepSendToSlave("break2");
                        mod.info("§aBatch 0: S checkpoint reached - break(2) requested.");
                    }
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
                lock.stepLockStep = 1;
                lock.stepLockDelay = 10;
                Utils.setForwardPressed(false);
                Utils.setSneakPressed(false);
            }
            case "stepTransit" -> {
                // Crossing a platform while the map is stowed is intentionally a no-op.
            }
            case "stepDwell", "stepDwellFinal" -> {
                if (stepMasterStage != StepMasterStage.BATCH_WALK_A
                    && stepMasterStage != StepMasterStage.BATCH_WALK_B
                    && stepMasterStage != StepMasterStage.CLOSING_WALK) return;
                if (!maps.stepEnsureMapHeld()) {
                    abortStepMaster("working map disappeared at a sweep checkpoint");
                    return;
                }
                stepMasterDwellTicks = STEP_DWELL_TICKS;
                stepDwellPrevPixels = snapshotHeldMapColors();
                stepDwellQuietTicks = 0;
                stepDwellChangeCount = 0;
                stepDwellGateFired = false;
                stepMasterWaitTicks = 0;   // the hold gate (if any) just released
                // The echo feeder keeps unreleased stations OUT of the queue, so an
                // empty queue no longer means "sweep over" - only the tagged final
                // station completes the sweep (a mid-walk empty queue is just the
                // next station waiting for its echo release).
                stepMasterSweepCompletionPending = "stepDwellFinal".equals(action);
            }
            default -> {
                if (mod.debugPrints.get()) mod.info("Unknown step master checkpoint action: " + action);
            }
        }
    }

    /** Copy of the held map's colour array (the same bytes /status dumps as
     *  mapPixels), or null when no filled map is readable right now. */
    private byte[] snapshotHeldMapColors() {
        MapState ms = Utils.getHeldMapState();
        if (ms == null) return null;
        byte[] snap = new byte[ms.colors.length];
        System.arraycopy(ms.colors, 0, snap, 0, snap.length);
        return snap;
    }

    /** PIXEL-ARRIVAL GATE: one dwell tick of the quiescence check. Any changed
     *  byte anywhere in the held map's colours resets the quiet timer; once the
     *  stream has been silent for the configured window (after a small floor so
     *  the first render packets always get a chance to arrive), the dwell
     *  releases early. Unreadable map this tick = plain countdown, never an
     *  extension and never a crash. */
    private void tickDwellGate() {
        if (stepDwellPrevPixels == null) {
            stepDwellPrevPixels = snapshotHeldMapColors();
            return;
        }
        MapState ms = Utils.getHeldMapState();
        if (ms == null) return;
        byte[] cur = ms.colors;
        int changes = 0;
        for (int i = 0; i < cur.length && i < stepDwellPrevPixels.length; i++) {
            if (cur[i] != stepDwellPrevPixels[i]) {
                stepDwellPrevPixels[i] = cur[i];
                changes++;
            }
        }
        if (changes > 0) {
            stepDwellChangeCount += changes;
            stepDwellQuietTicks = 0;
            return;
        }
        stepDwellQuietTicks++;
        // COLD-START RULE: once ANY byte has changed, the render stream provably
        // started, so silence after activity means it finished. A dwell that has
        // seen ZERO changes can also mean "the server has not started flushing
        // yet" (hold swap just landed / region hiccup) - releasing that on the
        // short window could strand un-flushed pixels outside every later
        // station's band, so the cold case demands double silence (2s).
        int quiet = stepDwellChangeCount > 0
            ? mod.dwellQuietTicks.get()
            : 2 * mod.dwellQuietTicks.get();
        int elapsed = STEP_DWELL_TICKS - stepMasterDwellTicks;
        if (elapsed >= STEP_DWELL_FLOOR_TICKS && stepDwellQuietTicks >= quiet) {
            stepDwellGateFired = true;
            mod.info("§7[DWELL] §abatch " + stepMasterBatch + " " + stepMasterStage
                + ": pixel stream quiet - released at §f" + elapsed + "/" + STEP_DWELL_TICKS
                + "t§7 (" + stepDwellChangeCount + " px arrived).");
            stepMasterDwellTicks = 2;
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












    void abortStepMaster(String reason) {
        if (stepMasterStage == StepMasterStage.ABORTED) return;
        stepMasterStage = StepMasterStage.ABORTED;
        mod.checkpoints.clear();
        mod.state = State.StandBy;
        stepSweepQueuePending = false;
        stepSweepFeederActive = false;
        stepSweepFedOrdinal = -1;
        stepSweepExtraFinal = null;
        stepBatch0SouthPending = false;
        stepMasterDwellTicks = 0;
        stepMasterSweepCompletionPending = false;
        Utils.setForwardPressed(false);
        Utils.setSneakPressed(false);
        maps.stepStowMap();
        mod.error("Step-checker master stopped: " + reason + ".");
    }


    void saveStepProgress(int batch) {
        if (StepFiles.saveProgress(mod.mapFolder, batch, maps.stepWorkingMapId)) {
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
