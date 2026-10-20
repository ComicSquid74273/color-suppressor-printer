package com.comicsquid.colorsuppressor.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.block.Block;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Arrays;
import java.util.Map;

/**
 * The module's render pass: map-area boxes, the batch placement-plan preview (cached
 * by a plan key), the master-area box + 325-platform grid with the sweep lane
 * highlight, registered chests with their open positions, the checkpoint queue, and
 * the special-interaction stations. All state lives on the module; this class only
 * owns the render-plan cache.
 */
final class StepRenderer {
    private final StepCheckered mod;

    private HashMap<BlockPos, Block> renderedBatchPlan;   // cached render-map plan
    private String renderedBatchKey;
    private long resumeCacheReadAt;

    StepRenderer(StepCheckered mod) {
        this.mod = mod;
    }

    /** The batch the renderer shows: the ACTIVE batch while a run is in progress,
     *  otherwise the saved-progress batch (where the run actually is / will resume),
     *  re-reading the shared file at most once per 2s while idle, else the manual
     *  render-batch setting (preview mode). */
    private int effectiveRenderBatch() {
        if (mod.currentBatch >= 0) return mod.currentBatch;
        long now = System.currentTimeMillis();
        if (now - resumeCacheReadAt > 2000) {
            resumeCacheReadAt = now;
            mod.master.cachedResumeBatch = mod.master.loadStepProgress();
        }
        if (mod.master.cachedResumeBatch > 0) return Math.min(62, mod.master.cachedResumeBatch);
        return mod.renderBatch.get();
    }

    void render(Render3DEvent event) {
        if (mod.lowerMapCorner == null || !mod.render.get()) return;
        event.renderer.box(mod.lowerMapCorner.getX(), mod.lowerMapCorner.getY(), mod.lowerMapCorner.getZ(), mod.lowerMapCorner.getX() + 128, mod.lowerMapCorner.getY(), mod.lowerMapCorner.getZ() + 129, mod.color.get(), mod.color.get(), ShapeMode.Lines, 0);
        if (mod.upperMapCorner == null) return;
        event.renderer.box(mod.upperMapCorner.getX(), mod.upperMapCorner.getY(), mod.upperMapCorner.getZ(), mod.upperMapCorner.getX() + 128, mod.upperMapCorner.getY(), mod.upperMapCorner.getZ() + 129, mod.color.get(), mod.color.get(), ShapeMode.Lines, 0);

        if (mod.renderNextBlockPos.get() && mod.nextBlockPos != null) {
            event.renderer.box(mod.nextBlockPos, mod.color.get(), mod.color.get(), ShapeMode.Lines, 0);
        }

        if (mod.renderMap.get() && mod.lowerMapCorner != null && mod.stepLayers != null) {
            // The batch placement plan (assembleBatch) is the render source; cache it and only
            // rebuild when the batch index, art file or corner changes
            String planKey = effectiveRenderBatch() + "|" + (mod.mapFile == null ? "?" : mod.mapFile.getName())
                + "|" + mod.lowerMapCorner.getX() + "," + mod.lowerMapCorner.getY() + "," + mod.lowerMapCorner.getZ()
                + "|" + mod.currentBatch;   // currentBatch in the key so the plan shifts when the batch advances
            if (!planKey.equals(renderedBatchKey)) {
                renderedBatchKey = planKey;
                renderedBatchPlan = mod.assembleBatch(effectiveRenderBatch());
            }
            if (renderedBatchPlan != null) {
                for (Map.Entry<BlockPos, Block> entry : renderedBatchPlan.entrySet()) {
                    if (!mod.miningBlockState(entry.getKey()).isAir()) continue;   // already built
                    Color renderColor = mod.color.get();
                    if (entry.getValue().equals(mod.fillerBlock.get())) renderColor = Color.BLUE;
                    event.renderer.box(entry.getKey(), renderColor, renderColor, ShapeMode.Lines, 0);
                }
            }
        }

        if (mod.renderMasterArea.get() && mod.lowerMapCorner != null) {
            // MASTER AREA bounding box only - the GATE platform (125,14) renders as a
            // NORMAL checkpoint via the platform grid (user rule), no special pillar.
            double ax0 = mod.lowerMapCorner.getX() + StepCheckered.MASTER_AREA_X0, ax1 = mod.lowerMapCorner.getX() + StepCheckered.MASTER_AREA_X1;
            double az0 = mod.lowerMapCorner.getZ() + StepCheckered.MASTER_AREA_Z0, az1 = mod.lowerMapCorner.getZ() + StepCheckered.MASTER_AREA_Z1;
            double ay = mod.lowerMapCorner.getY();
            event.renderer.box(ax0, ay - 1, az0, ax1, ay, az1, mod.specialCheckpointsColor.get(), mod.specialCheckpointsColor.get(), ShapeMode.Lines, 0);
        }

        if (mod.renderCheckpointGrid.get() && mod.lowerMapCorner != null) {
            // The full 325-platform master grid; the rendered batch's sweep lane (x=2k+127)
            // is highlighted so the next 5 checkpoints stand out from the rest
            int[] spotZ = {14, 40, 64, 88, 112};
            int nextLaneX = 2 * effectiveRenderBatch() + 127;
            for (int j = 0; j <= 64; j++) {
                int x = 125 + 2 * j;
                boolean isNextLane = x == nextLaneX;
                for (int z : spotZ) {
                    BlockPos platform = new BlockPos(mod.lowerMapCorner.getX() + x, mod.lowerMapCorner.getY() - 1, mod.lowerMapCorner.getZ() + z);
                    Color gridColor = isNextLane ? mod.nextCheckpointsColor.get() : mod.color.get();
                    event.renderer.box(platform, gridColor, gridColor, isNextLane ? ShapeMode.Both : ShapeMode.Lines, 0);
                }
            }
            // Special master points, distinct from the lane grid - rendered ONLY while
            // their phase is relevant: the cartography lock position from the closing
            // phase onward. (Both used to render every frame - the lock marker at rel
            // x~98 showed up during every batch and looked like a stray checkpoint.)
            ArrayList<Vec3d> specialPoints = new ArrayList<>();
            if (mod.cartographyTable != null
                && (mod.master.stepMasterStage == StepMasterStage.CLOSING_WALK
                    || mod.master.stepMasterStage == StepMasterStage.WAIT_CLOSING_BREAK
                    || mod.master.stepMasterStage == StepMasterStage.READY_TO_LOCK
                    || mod.master.stepMasterStage == StepMasterStage.LOCK_WALK
                    || mod.master.stepMasterStage == StepMasterStage.LOCKING)) {
                specialPoints.add(mod.cartographyTable.getRight());
            }
            for (Vec3d point : specialPoints) {
                double s = mod.indicatorSize.get() + 0.15;
                event.renderer.box(point.x - s, point.y - s, point.z - s, point.x + s, point.y + s, point.z + s,
                    mod.specialCheckpointsColor.get(), mod.specialCheckpointsColor.get(), ShapeMode.Both, 0);
            }
        }

        ArrayList<Pair<BlockPos, Vec3d>> renderedPairs = new ArrayList<>();
        for (ArrayList<Pair<BlockPos, Vec3d>> list : mod.materialDict.values()) {
            renderedPairs.addAll(list);
        }
        renderedPairs.addAll(mod.mapMaterialChests);
        for (Pair<BlockPos, Vec3d> pair : renderedPairs) {
            if (mod.renderChestPositions.get())
                event.renderer.box(pair.getLeft(), mod.color.get(), mod.color.get(), ShapeMode.Lines, 0);
            if (mod.renderOpenPositions.get()) {
                Vec3d openPos = pair.getRight();
                event.renderer.box(openPos.x - mod.indicatorSize.get(), openPos.y - mod.indicatorSize.get(), openPos.z - mod.indicatorSize.get(), openPos.x + mod.indicatorSize.get(), openPos.y + mod.indicatorSize.get(), openPos.z + mod.indicatorSize.get(), mod.color.get(), mod.color.get(), ShapeMode.Both, 0);
            }
        }

        if (mod.renderCheckpoints.get()) {
            for (Pair<Vec3d, Pair<String, BlockPos>> pair : mod.checkpoints) {
                Vec3d cp = pair.getLeft();
                event.renderer.box(cp.x - mod.indicatorSize.get(), cp.y - mod.indicatorSize.get(), cp.z - mod.indicatorSize.get(), cp.x + mod.indicatorSize.get(), cp.y + mod.indicatorSize.get(), cp.z + mod.indicatorSize.get(), mod.color.get(), mod.color.get(), ShapeMode.Both, 0);
            }
        }

        if (mod.renderSpecialInteractions.get()) {
            if (mod.cartographyTable != null) {
                event.renderer.box(mod.cartographyTable.getLeft(), mod.color.get(), mod.color.get(), ShapeMode.Lines, 0);
                event.renderer.box(mod.cartographyTable.getRight().x - mod.indicatorSize.get(), mod.cartographyTable.getRight().y - mod.indicatorSize.get(), mod.cartographyTable.getRight().z - mod.indicatorSize.get(), mod.cartographyTable.getRight().x + mod.indicatorSize.get(), mod.cartographyTable.getRight().y + mod.indicatorSize.get(), mod.cartographyTable.getRight().z + mod.indicatorSize.get(), mod.color.get(), mod.color.get(), ShapeMode.Both, 0);
            }
            if (mod.finishedMapChest != null) {
                event.renderer.box(mod.finishedMapChest.getLeft(), mod.color.get(), mod.color.get(), ShapeMode.Lines, 0);
                event.renderer.box(mod.finishedMapChest.getRight().x - mod.indicatorSize.get(), mod.finishedMapChest.getRight().y - mod.indicatorSize.get(), mod.finishedMapChest.getRight().z - mod.indicatorSize.get(), mod.finishedMapChest.getRight().getX() + mod.indicatorSize.get(), mod.finishedMapChest.getRight().getY() + mod.indicatorSize.get(), mod.finishedMapChest.getRight().getZ() + mod.indicatorSize.get(), mod.color.get(), mod.color.get(), ShapeMode.Both, 0);
            }
            for (Pair<BlockPos, Vec3d> pair : Arrays.asList(mod.anvil, mod.foodChest, mod.xpBottleChest)) {
                if (pair == null) continue;
                event.renderer.box(pair.getLeft(), mod.color.get(), mod.color.get(), ShapeMode.Lines, 0);
                event.renderer.box(pair.getRight().x - mod.indicatorSize.get(), pair.getRight().y - mod.indicatorSize.get(), pair.getRight().z - mod.indicatorSize.get(), pair.getRight().x + mod.indicatorSize.get(), pair.getRight().y + mod.indicatorSize.get(), pair.getRight().z + mod.indicatorSize.get(), mod.color.get(), mod.color.get(), ShapeMode.Both, 0);
            }
        }
    }
}
