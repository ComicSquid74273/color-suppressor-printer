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
        // S→N MONOTONIC SWEEP (user, 2026-10-21: "shortest path S->N, it's so
        // slow"): among drops not more than 1 block behind (touch radius), always
        // take the SOUTHERNMOST remaining one so the pass only ever advances north
        // (nearest-first zigzagged east-west and backtracked); within a same-row
        // band (±0.5 z) take the nearest x to minimize side-steps. Drops skipped by
        // the touch radius end up behind, fall out of this filter, and the
        // end-of-run gate + sweep re-verify collect them.
        if (mc.world == null || mc.player == null) return null;
        int westX = batchWindowWestX();
        ItemEntity best = null;
        double bestZ = -Double.MAX_VALUE, bestDx = Double.MAX_VALUE;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (!isPickupable(itemEntity)) continue;
            if (isSubFloorDrop(itemEntity)) continue;   // below the pickup band - recovery dig owns these
            if (itemEntity.getX() < westX - 4 || itemEntity.getX() > westX + 7) continue;
            if (itemEntity.getZ() < mod.lowerMapCorner.getZ() - 3 || itemEntity.getZ() > mod.lowerMapCorner.getZ() + 129) continue;
            if (itemEntity.getY() < mod.lowerMapCorner.getY() - 3 || itemEntity.getY() > mod.lowerMapCorner.getY() + 5) continue;
            BlockPos key = BlockPos.ofFloored(itemEntity.getX(), itemEntity.getY(), itemEntity.getZ());
            if (collectSkips.getOrDefault(key, 0) >= 10) continue;   // blacklisted this batch
            if (itemEntity.getZ() > mc.player.getZ() + 1.0) continue;   // behind (south) - monotonic north only
            boolean newSouthernmost = itemEntity.getZ() > bestZ + 0.5;
            boolean sameRow = Math.abs(itemEntity.getZ() - bestZ) <= 0.5;
            double dx = Math.abs(itemEntity.getX() - mc.player.getX());
            if (newSouthernmost || (sameRow && best != null && dx < bestDx)) {
                best = itemEntity;
                bestZ = Math.max(bestZ, itemEntity.getZ());
                bestDx = dx;
            }
        }
        if (best != null) {
            BlockPos key = BlockPos.ofFloored(best.getX(), best.getY(), best.getZ());
            collectSkips.merge(key, 1, Integer::sum);
        }
        return best;
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
        return countGroundItemsInWindow(Collections.emptySet());
    }

    int countGroundItemsInWindow(Set<BlockPos> exclude) {
        if (mc.world == null || mc.player == null) return 0;
        int westX = batchWindowWestX();
        int count = 0;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (!isPickupable(itemEntity)) continue;
            if (exclude != null && exclude.contains(BlockPos.ofFloored(itemEntity.getX(), itemEntity.getY(), itemEntity.getZ()))) continue;
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

    /** Vanilla pickup touches a drop iff the drop box intersects the player box grown
     *  by (1.0, 0.5, 1.0). From feet at the walk surface (lowerMapCorner.y) that means
     *  drop.y >= cornerY - 0.625; anything lower can NEVER be picked up by walking -
     *  it rests below the floor. Such drops are "sub-floor" and route to the recovery
     *  dig instead of a walk target (the 2026-10-21 08:54 batch-17 stall: 4 drops
     *  under the NW gate spun the sweep for minutes - the horizontal-only arrival
     *  kept "reaching" them while standing a floor above). */
    boolean isSubFloorDrop(ItemEntity itemEntity) {
        if (!isPickupable(itemEntity)) return false;
        return itemEntity.getY() < mod.lowerMapCorner.getY() - 0.625;
    }

    ItemEntity findNearestSurfaceDrop(int margin) {
        return findNearestClassifiedDrop(margin, false, null);
    }

    ItemEntity findNearestSubFloorDrop(int margin) {
        return findNearestClassifiedDrop(margin, true, null);
    }

    ItemEntity findNearestSubFloorDrop(int margin, Set<BlockPos> exclude) {
        return findNearestClassifiedDrop(margin, true, exclude);
    }

    private ItemEntity findNearestClassifiedDrop(int margin, boolean subFloor, Set<BlockPos> exclude) {
        if (mc.world == null || mc.player == null) return null;
        ItemEntity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (isSubFloorDrop(itemEntity) != subFloor) continue;
            if (exclude != null && exclude.contains(BlockPos.ofFloored(itemEntity.getX(), itemEntity.getY(), itemEntity.getZ()))) continue;
            if (itemEntity.getX() < mod.lowerMapCorner.getX() - margin || itemEntity.getX() > mod.lowerMapCorner.getX() + 128 + margin) continue;
            if (itemEntity.getZ() < mod.lowerMapCorner.getZ() - margin || itemEntity.getZ() > mod.lowerMapCorner.getZ() + 128 + margin) continue;
            if (itemEntity.getY() < mod.lowerMapCorner.getY() - 3 || itemEntity.getY() > mod.lowerMapCorner.getY() + 5) continue;
            double dist = mc.player.getEyePos().distanceTo(itemEntity.getEntityPos());
            if (dist < nearestDist) { nearestDist = dist; nearest = itemEntity; }
        }
        return nearest;
    }

    int countSubFloorDropsInWindow() {
        return countSubFloorDropsInWindow(Collections.emptySet());
    }

    int countSubFloorDropsInWindow(Set<BlockPos> exclude) {
        if (mc.world == null || mod.lowerMapCorner == null) return 0;
        int westX = batchWindowWestX();
        int n = 0;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (!isSubFloorDrop(itemEntity)) continue;
            if (exclude != null && exclude.contains(BlockPos.ofFloored(itemEntity.getX(), itemEntity.getY(), itemEntity.getZ()))) continue;
            if (itemEntity.getX() < westX - 4 || itemEntity.getX() > westX + 7) continue;
            if (itemEntity.getZ() < mod.lowerMapCorner.getZ() - 3 || itemEntity.getZ() > mod.lowerMapCorner.getZ() + 129) continue;
            n++;
        }
        return n;
    }

    /** A pickupable drop whose OWN cell is solid = encased (the frozen-sweep case):
     *  digging the containing block pops it out for normal collection. In-footprint
     *  cells only — the bounding-area rule applies to escalation digs too. */
    BlockPos findEncasedDropCellInWindow() {
        if (mc.world == null || mod.lowerMapCorner == null) return null;
        int westX = batchWindowWestX();
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (!isPickupable(itemEntity)) continue;
            if (itemEntity.getX() < westX - 4 || itemEntity.getX() > westX + 7) continue;
            if (itemEntity.getZ() < mod.lowerMapCorner.getZ() - 3 || itemEntity.getZ() > mod.lowerMapCorner.getZ() + 129) continue;
            BlockPos cell = BlockPos.ofFloored(itemEntity.getX(), itemEntity.getY(), itemEntity.getZ());
            if (cell.getX() < mod.lowerMapCorner.getX() || cell.getX() >= mod.lowerMapCorner.getX() + 128
                || cell.getZ() < mod.lowerMapCorner.getZ() || cell.getZ() >= mod.lowerMapCorner.getZ() + 128) continue;
            if (!mod.miningBlockState(cell).isAir()) return cell;
        }
        return null;
    }

    /** An ItemEntity sitting INSIDE one specific plan cell — the unplaceable-
     *  cell case (live 2026-10-22 batch 36: a reprint after an INTERRUPTED
     *  collection had ~140 drops still lying in the box; the server rejects a
     *  placement clipping an entity ((al)'s law), so every send into a
     *  drop-occupied cell fails until picked up). Deliberately UNFILTERED by
     *  isPickupable ((ci)): a drop still in pickup delay or mid-air blocks the
     *  placement just the same, and walking onto it picks it up the moment it
     *  settles. Null = clear. */
    ItemEntity findDropInCell(BlockPos cell) {
        if (mc.world == null) return null;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (BlockPos.ofFloored(itemEntity.getX(), itemEntity.getY(), itemEntity.getZ()).equals(cell)) return itemEntity;
        }
        return null;
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
            BlockPos pos = entry.getKey();
            BlockState worldState = mod.miningBlockState(pos);
            // GHOST-SUSPECT ((ce)): a non-air reading with the send's placement
            // cooldown still armed can be our own client-side placement PREDICTION —
            // never certify it. Counted missing so the verify/repair re-checks after
            // the ghost window; a real block re-verifies clean on the repair pass.
            boolean ghostSuspect = !worldState.isAir()
                && mod.engine.placePending.containsKey(pos)
                && mod.engine.placementCooldowns.getOrDefault(pos, 0) > 0;
            if (worldState.isAir() || ghostSuspect) missing++;
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
