package com.comicsquid.colorsuppressor.utils;

import net.minecraft.block.Block;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Év step-checker layer data: the classic staircase NBT converted into the 64
 * recessive + 64 dominant per-pair layers, and the world-coordinate placement
 * plans assembled from them.
 *
 * CONVERSION (a faithful port of the PROVEN tools/manualrun/convert_step.py
 * oracle, verified block-identical against Év's own step-checker export,
 * 27,565 blocks / 65 phases). Per pair j (lanes 2j / 2j+1), pixel class =
 * (px+pz)&1 (0 = recessive, 1 = dominant):
 *   REC layer j - recessive colours at y0; Dark -> filler y1 on the north column;
 *                Flat -> the north (dominant) pixel's own material at y0
 *                (pre-placed "borrow" shading the recessive Flat pixel south of
 *                it); filler when the north is transparent or the noobline.
 *   DOM layer j - dominant colours at y0 (idempotent over borrows); Dark ->
 *                filler y1 north; Flat -> the north (recessive) pixel's colour
 *                at y0 (shade provider once its record is done); filler ditto.
 * Local coordinates: x 0..127, y 0/1, z -1..127 (z = -1 is the noobline row).
 *
 * ASSEMBLY (doc §4): batch k stacks rec(k)@0 + dom(k)@2 + rec(k+1)@2 over lanes
 * 2k..2k+3; priming is the recessive-only floor; closing is dom(63)@2.
 */
public final class StepCheckerLayers {

    /** Per-pair local-coordinate layers, index 0..63. Never null after a successful conversion. */
    public final List<HashMap<BlockPos, Block>> recLayers;
    public final List<HashMap<BlockPos, Block>> domLayers;

    /** When {@link #fromClassicStaircase} returns null: why (for the caller's log). */
    public String lastRejectReason;

    private StepCheckerLayers(List<HashMap<BlockPos, Block>> rec, List<HashMap<BlockPos, Block>> dom) {
        recLayers = rec;
        domLayers = dom;
    }

    /** 64 + 64 empty layer maps - the pre-load placeholder (nothing assembled from it). */
    public static StepCheckerLayers empty() {
        ArrayList<HashMap<BlockPos, Block>> rec = new ArrayList<>();
        ArrayList<HashMap<BlockPos, Block>> dom = new ArrayList<>();
        for (int j = 0; j < 64; j++) {
            rec.add(new HashMap<>());
            dom.add(new HashMap<>());
        }
        return new StepCheckerLayers(rec, dom);
    }

    /**
     * Convert a CLASSIC staircase export.
     * @return the layers, or null when the file is not a classic staircase export
     *         (e.g. a pre-encoded step-checker/2-layer suppress export) - those stack
     *         phases vertically and would silently produce wrong layers. See
     *         {@link #lastRejectReason}.
     */
    public static StepCheckerLayers fromClassicStaircase(NbtList blockList,
                                                         HashMap<Integer, Pair<Block, Integer>> blockPaletteDict,
                                                         Block filler) {
        // Center the schematic on the 128x129 footprint first (like the legacy
        // printer's map loader) - schematics exported with a coordinate offset would
        // index out of bounds
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < blockList.size(); i++) {
            Optional<NbtCompound> blockOpt = blockList.getCompound(i);
            if (blockOpt.isEmpty()) continue;
            NbtList pos = blockOpt.get().getList("pos").orElse(null);
            if (pos == null || pos.size() < 3) continue;
            Optional<Integer> xOpt = pos.getInt(0);
            Optional<Integer> yOpt = pos.getInt(1);
            Optional<Integer> zOpt = pos.getInt(2);
            if (xOpt.isEmpty() || zOpt.isEmpty()) continue;
            minX = Math.min(minX, xOpt.get());
            maxX = Math.max(maxX, xOpt.get());
            maxZ = Math.max(maxZ, zOpt.get());
            if (yOpt.isPresent()) {
                minY = Math.min(minY, yOpt.get());
                maxY = Math.max(maxY, yOpt.get());
            }
        }
        // Pre-encoded step-checker exports are ~254 wide (phases side by side);
        // 2-layer suppress exports lack the z=128 tail. A classic Z-129 staircase is
        // always 128 wide with z 0..128. The y-span is the art's RELIEF height (the
        // longest monotone shade climb) and is NOT a discriminator - test_3 reliefs
        // at 49, the 2026-11 gradient art at 126, both valid (false-positive fix).
        if (minX != Integer.MAX_VALUE && (maxX - minX > 160 || maxZ < 128)) {
            StepCheckerLayers reject = empty();
            reject.lastRejectReason = "x-span " + (maxX - minX + 1) + ", y-span " + (maxY - minY + 1)
                + ", z-max " + maxZ + "). Pre-encoded step-checker exports are x-stacked and "
                + "2-layer suppress exports lack the Z-129 tail - both produce WRONG layers. "
                + "Export the art in staircase mode (Z-129) and use that file.";
            return reject;   // caller logs lastRejectReason with the file name
        }
        int xOffset = minX == Integer.MAX_VALUE ? 0 : minX;
        int zOffset = maxZ == Integer.MIN_VALUE ? 0 : maxZ - 128;

        // Classic heightmap: cell (x 0..127, z 0..128) -> (block, topmost y); z = 0 is the noobline
        Pair<Block, Integer>[][] heightMap = new Pair[128][129];
        for (int i = 0; i < blockList.size(); i++) {
            Optional<NbtCompound> blockOpt = blockList.getCompound(i);
            if (blockOpt.isEmpty()) continue;

            NbtCompound block = blockOpt.get();

            Optional<Integer> blockIdOpt = block.getInt("state");
            if (blockIdOpt.isEmpty() || !blockPaletteDict.containsKey(blockIdOpt.get())) continue;

            NbtList pos = block.getList("pos").orElse(null);
            if (pos == null || pos.size() < 3) continue;

            Optional<Integer> xOpt = pos.getInt(0);
            Optional<Integer> yOpt = pos.getInt(1);
            Optional<Integer> zOpt = pos.getInt(2);
            if (xOpt.isEmpty() || yOpt.isEmpty() || zOpt.isEmpty()) continue;

            int x = xOpt.get() - xOffset;
            int z = zOpt.get() - zOffset;
            if (x < 0 || x >= 128 || z < 0 || z >= 129) continue;
            if (heightMap[x][z] == null || heightMap[x][z].getRight() < yOpt.get()) {
                heightMap[x][z] = new Pair<>(blockPaletteDict.get(blockIdOpt.get()).getLeft(), yOpt.get());
            }
        }

        StepCheckerLayers layers = empty();
        for (int px = 0; px < 128; px++) {
            int pair = px / 2;
            for (int pz = 0; pz < 128; pz++) {
                Pair<Block, Integer> cell = heightMap[px][pz + 1];
                if (cell == null) continue;
                int y = cell.getRight();
                // North neighbour height: a missing column counts as one lower (-> Light)
                int northY = heightMap[px][pz] != null ? heightMap[px][pz].getRight() : y - 1;
                HashMap<BlockPos, Block> layer = ((px + pz) & 1) == 0 ? layers.recLayers.get(pair) : layers.domLayers.get(pair);
                layer.put(new BlockPos(px, 0, pz), cell.getLeft());
                if (y == northY) {
                    // Flat: shade with the north pixel's own colour (the "borrow"); filler when
                    // the north pixel is absent (gap or the noobline row pz = 0)
                    Block northMat = pz >= 1 && heightMap[px][pz] != null ? heightMap[px][pz].getLeft() : filler;
                    layer.put(new BlockPos(px, 0, pz - 1), northMat);
                } else if (y < northY) {
                    // Dark: north is higher - filler on the north column
                    layer.put(new BlockPos(px, 1, pz - 1), filler);
                }
                // Light: no filler
            }
        }
        return layers;
    }

    public int recTotalBlocks() {
        int total = 0;
        for (HashMap<BlockPos, Block> layer : recLayers) total += layer.size();
        return total;
    }

    public int domTotalBlocks() {
        int total = 0;
        for (HashMap<BlockPos, Block> layer : domLayers) total += layer.size();
        return total;
    }

    // --- World-coordinate plan assembly ---

    /**
     * Regular batch k (0..62): recessive pair k at corner+0 (y0-1) + dominant pair k
     * at corner+2 (y2-3) + recessive pair k+1 at corner+2 (east lanes) - the 4x128x4
     * window over lanes 2k..2k+3, noobline fillers included (converter local z = -1
     * row). Overlapping entries between the two +2 layers cannot happen (different
     * parity lanes).
     */
    public HashMap<BlockPos, Block> assembleBatch(int k, BlockPos corner) {
        HashMap<BlockPos, Block> batch = new HashMap<>();
        if (k < 0 || k > 62) return batch;
        addLayerAt(batch, recLayers.get(k), corner, 0);
        addLayerAt(batch, domLayers.get(k), corner, 2);
        addLayerAt(batch, recLayers.get(k + 1), corner, 2);
        return batch;
    }

    /** Closing build: dominant pair 63 at corner+2 (lanes 126-127) - after batch 62, swept from x=253. */
    public HashMap<BlockPos, Block> assembleClosing(BlockPos corner) {
        HashMap<BlockPos, Block> batch = new HashMap<>();
        addLayerAt(batch, domLayers.get(63), corner, 2);
        return batch;
    }

    /**
     * Maps one converted layer (local x 0..127, y 0/1, z -1..127 with z=-1 = noobline)
     * into the world at the given y base; later puts win (idempotent re-placement is
     * safe - same block).
     */
    public static void addLayerAt(HashMap<BlockPos, Block> batch, HashMap<BlockPos, Block> layer, BlockPos corner, int yBase) {
        if (layer == null || corner == null) return;
        for (Map.Entry<BlockPos, Block> entry : layer.entrySet()) {
            BlockPos local = entry.getKey();
            batch.put(new BlockPos(
                corner.getX() + local.getX(),
                corner.getY() + yBase + local.getY(),
                corner.getZ() + local.getZ()), entry.getValue());
        }
    }
}
