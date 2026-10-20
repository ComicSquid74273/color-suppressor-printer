package com.comicsquid.colorsuppressor.modules;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * World-truth scans and censuses: nearest block/item finders over bounded areas and
 * the whole print box, batch-window geometry, item-state censuses, plan-defect
 * counting, and the shared pickup-ability filter. Pure reads (plus the walk-to-item
 * target action and the collection-run skip blacklist).
 */
final class WorldScanner {
    final StepCheckered mod;
    HashMap<BlockPos, Integer> collectSkips;           // collection run: picks per drop (10 = blacklisted)

    WorldScanner(StepCheckered mod) {
        this.mod = mod;
        collectSkips = new HashMap<>();
    }

    void resetForActivation() {
        collectSkips = new HashMap<>();
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

}
