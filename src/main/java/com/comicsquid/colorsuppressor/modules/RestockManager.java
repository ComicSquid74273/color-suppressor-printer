package com.comicsquid.colorsuppressor.modules;

import com.comicsquid.colorsuppressor.utils.ToolUtils;
import com.comicsquid.colorsuppressor.utils.Utils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.apache.commons.lang3.tuple.Triple;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Supply subsystem: chest selection and interaction, refill/deposit backlogs and
 * their inventory-packet responses, tool/food/pickaxe management, hotbar staging
 * (swapIntoHotbar + presence checks), and inventory capacity queries. The module
 * keeps thin delegates for the members other subsystems call (interactWithBlock,
 * getBestChest, handleInventoryPacket).
 */
final class RestockManager {
    final StepCheckered mod;

    // --- Supply state (moved from the module) ---
    ArrayList<Triple<Item, Integer, Integer>> restockList;          // Material, Stacks, Raw Amount
    ArrayList<BlockPos> checkedChests;
    ArrayList<Integer> restockBacklogSlots;
    ArrayList<Integer> depositBacklogSlots;
    HashSet<BlockPos> fullDepositChests;                            // chests that ran out of room this batch
    BlockPos lastInteractedChest;
    int interactTimeout;
    InventoryS2CPacket toBeHandledInvPacket;
    boolean closeNextInvPacket;
    // FILTERED PREP DUMP (user rule, 2026-10-21): the prep-trip deposit keeps
    // exactly what the upcoming build still needs and deposits only the excess.
    // The need snapshot is taken at queue time (priming swaps the plan right
    // after queueing); the stack selection re-derives live at every chest-open.
    boolean filteredDumpActive;
    HashMap<Item, Integer> filteredDumpNeed;

    RestockManager(StepCheckered mod) {
        this.mod = mod;
    }

    /** Full supply-state reset (module onActivate path). */
    void resetForActivation() {
        restockList = new ArrayList<>();
        checkedChests = new ArrayList<>();
        restockBacklogSlots = new ArrayList<>();
        depositBacklogSlots = new ArrayList<>();
        fullDepositChests = new HashSet<>();
        lastInteractedChest = null;
        interactTimeout = 0;
        toBeHandledInvPacket = null;
        closeNextInvPacket = false;
        clearFilteredDump();
    }

    void clearFilteredDump() {
        filteredDumpActive = false;
        filteredDumpNeed = null;
    }

    /** Items currently carried BEYOND the snapshotted need (tools/food never
     *  count). Derived from the live inventory so drops picked up en route
     *  simply join the excess. */
    HashSet<Item> excessItemsBeyondNeed() {
        HashSet<Item> excess = new HashSet<>();
        if (filteredDumpNeed == null) return excess;
        HashMap<Item, Integer> carried = new HashMap<>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty() || ToolUtils.isTool(stack) || stack.contains(DataComponentTypes.FOOD)) continue;
            if (!mod.materialDict.containsKey(stack.getItem())) continue;
            carried.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        for (Map.Entry<Item, Integer> ent : carried.entrySet()) {
            if (ent.getValue() > filteredDumpNeed.getOrDefault(ent.getKey(), 0)) excess.add(ent.getKey());
        }
        return excess;
    }

    /** Nearest chest stocking any of the given excess items, skipping the
     *  last-interacted and known-full ones. Deliberately NOT getNextDepositChest:
     *  that one chains while ANY stocking material is carried - correct for the
     *  emergency full strip, wrong for a quantity dump (it would strip exactly
     *  the materials the refill leg is about to count on). */
    Pair<BlockPos, Vec3d> getNextFilteredDepositChest(HashSet<Item> items) {
        Pair<BlockPos, Vec3d> best = null;
        double bestDist = Double.MAX_VALUE;
        for (Item item : items) {
            for (Pair<BlockPos, Vec3d> chest : mod.materialDict.getOrDefault(item, new ArrayList<>())) {
                if (lastInteractedChest != null && chest.getLeft().equals(lastInteractedChest)) continue;
                if (fullDepositChests.contains(chest.getLeft())) continue;
                double dist = PlayerUtils.distanceTo(chest.getRight());
                if (dist < bestDist) { bestDist = dist; best = chest; }
            }
        }
        return best;
    }

    /** CHEST-OPEN selection for the filtered dump: re-derive the excess live and
     *  queue whole stacks as CHEST-SCREEN indices - the packet's last 36 slots
     *  are the player inventory in screen space, the exact indexing
     *  drainDepositBacklog clicks. (The old queue-time pre-fill used INVENTORY
     *  indices: 0-26 pointed at CHEST stacks and quick-moved them OUT.) Screen
     *  order visits main inventory before the hotbar, so the pre-staged hotbar
     *  survives whenever the excess reaches into main inventory. */
    void buildFilteredDumpBacklog(InventoryS2CPacket packet) {
        depositBacklogSlots.clear();
        if (filteredDumpNeed == null) {
            clearFilteredDump();
            mod.timeoutTicks = mod.postRestockDelay.get();
            mod.state = State.Walking;
            return;
        }
        HashMap<Item, Integer> surplus = new HashMap<>();
        for (int slot = packet.contents().size() - 36; slot < packet.contents().size(); slot++) {
            ItemStack stack = packet.contents().get(slot);
            if (stack.isEmpty() || ToolUtils.isTool(stack) || stack.contains(DataComponentTypes.FOOD)) continue;
            if (!mod.materialDict.containsKey(stack.getItem())) continue;
            surplus.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        for (Map.Entry<Item, Integer> ent : new ArrayList<>(surplus.entrySet())) {
            int over = ent.getValue() - filteredDumpNeed.getOrDefault(ent.getKey(), 0);
            if (over > 0) surplus.put(ent.getKey(), over);
            else surplus.remove(ent.getKey());
        }
        for (int slot = packet.contents().size() - 36; slot < packet.contents().size(); slot++) {
            ItemStack stack = packet.contents().get(slot);
            if (stack.isEmpty() || ToolUtils.isTool(stack) || stack.contains(DataComponentTypes.FOOD)) continue;
            int rem = surplus.getOrDefault(stack.getItem(), 0);
            if (rem <= 0) continue;
            depositBacklogSlots.add(slot);
            surplus.put(stack.getItem(), rem - stack.getCount());
        }
        if (depositBacklogSlots.isEmpty()) {
            // Nothing beyond need (any more) - the dump leg is done, route continues
            clearFilteredDump();
            mod.timeoutTicks = mod.postRestockDelay.get();
            mod.state = State.Walking;
            return;
        }
        mod.info("§7Prep dump: depositing §b" + depositBacklogSlots.size() + "§7 excess stack(s) beyond this build's need.");
    }

    Pair<BlockPos, Vec3d> getBestChest(Item item) {
        ArrayList<Pair<BlockPos, Vec3d>> list = new ArrayList<>();
        if (item.equals(Items.CARTOGRAPHY_TABLE)) {
            list = mod.mapMaterialChests;
        } else if (mod.materialDict.containsKey(item)) {
            list = mod.materialDict.get(item);
        } else {
            mod.warning("No chest found for " + item.getName().getString());
            mod.toggle();
            return new Pair<>(new BlockPos(0, 0, 0), new Vec3d(0, 0, 0));
        }
        ArrayList<Pair<BlockPos, Vec3d>> bestList = new ArrayList<>();
        // Lowest chest as 1. criterion (fixed preference since the legacy layer engine went away)
        for (Pair<BlockPos, Vec3d> p : list) {
            //Skip chests that have already been checked
            if (checkedChests.contains(p.getLeft())) continue;
            if (bestList.isEmpty() || bestList.getFirst().getLeft().getY() >= p.getLeft().getY()) {   // prefer the LOWEST chest (layer preference is fixed since the legacy engine went away)
                // Remove all previous results if new one is truly better
                if (!bestList.isEmpty() && bestList.getFirst().getLeft().getY() != p.getLeft().getY()) {
                    bestList.clear();
                }
                bestList.add(p);
            }
        }
        if (bestList.isEmpty()) {
            if (checkedChests.isEmpty()) {
                // The source list itself has no usable chest - retrying cannot help (was: infinite recursion)
                mod.warning("No available chest found for " + item.getName().getString());
                mod.toggle();
                return new Pair<>(new BlockPos(0, 0, 0), new Vec3d(0, 0, 0));
            }
            checkedChests.clear();
            return getBestChest(item);
        }
        // Get nearest chest as 2. criteria
        Vec3d bestPos = null;
        BlockPos bestChestPos = null;
        for (Pair<BlockPos, Vec3d> p : bestList) {
            //Skip chests that have already been checked
            if (checkedChests.contains(p.getLeft())) continue;
            if (bestPos == null || PlayerUtils.distanceTo(p.getRight()) < PlayerUtils.distanceTo(bestPos)) {
                bestPos = p.getRight();
                bestChestPos = p.getLeft();
            }
        }
        return new Pair(bestChestPos, bestPos);
    }

    Pair<BlockPos, Vec3d> getBestDepositChest() {
        Pair<BlockPos, Vec3d> best = null;
        double bestDist = Double.MAX_VALUE;
        for (Item item : mod.materialDict.keySet()) {
            boolean carrying = false;
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = mc.player.getInventory().getStack(slot);
                if (!stack.isEmpty() && stack.getItem().equals(item)) { carrying = true; break; }
            }
            if (!carrying) continue;
            for (Pair<BlockPos, Vec3d> chest : mod.materialDict.get(item)) {
                double dist = PlayerUtils.distanceTo(chest.getRight());
                if (dist < bestDist) { bestDist = dist; best = chest; }
            }
        }
        if (best != null) return best;
        for (ArrayList<Pair<BlockPos, Vec3d>> chests : mod.materialDict.values()) {
            for (Pair<BlockPos, Vec3d> chest : chests) {
                double dist = PlayerUtils.distanceTo(chest.getRight());
                if (dist < bestDist) { bestDist = dist; best = chest; }
            }
        }
        return best;
    }

    Pair<BlockPos, Vec3d> getNextDepositChest() {
        ArrayList<Item> carried = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty() || ToolUtils.isTool(stack) || stack.contains(DataComponentTypes.FOOD)) continue;
            if (mod.materialDict.containsKey(stack.getItem()) && !carried.contains(stack.getItem())) carried.add(stack.getItem());
        }
        Pair<BlockPos, Vec3d> best = null;
        double bestDist = Double.MAX_VALUE;
        for (Item item : carried) {
            for (Pair<BlockPos, Vec3d> chest : mod.materialDict.get(item)) {
                if (lastInteractedChest != null && chest.getLeft().equals(lastInteractedChest)) continue;
                if (fullDepositChests.contains(chest.getLeft())) continue;
                double dist = PlayerUtils.distanceTo(chest.getRight());
                if (dist < bestDist) { bestDist = dist; best = chest; }
            }
        }
        if (best != null) return best;
        // Fallback: any registered chest with (hopefully) space - remember it for the item
        for (Item item : carried) {
            for (ArrayList<Pair<BlockPos, Vec3d>> chests : mod.materialDict.values()) {
                for (Pair<BlockPos, Vec3d> chest : chests) {
                    if (fullDepositChests.contains(chest.getLeft())) continue;
                    if (!containsChest(mod.materialDict.getOrDefault(item, new ArrayList<>()), chest.getLeft())) {
                        mod.materialDict.computeIfAbsent(item, k -> new ArrayList<>()).add(chest);
                        mod.info("§7Registered " + chest.getLeft().toShortString() + " as a new location for " + item.getName().getString() + ".");
                    }
                    return chest;
                }
            }
        }
        return null;
    }

    boolean containsChest(ArrayList<Pair<BlockPos, Vec3d>> chests, BlockPos pos) {
        for (Pair<BlockPos, Vec3d> chest : chests) {
            if (chest.getLeft().equals(pos)) return true;
        }
        return false;
    }

    boolean chestHasRoomFor(ItemStack stack) {
        if (stack.isEmpty()) return true;
        int chestSlots = mc.player.currentScreenHandler.slots.size() - 36;
        for (int i = 0; i < chestSlots; i++) {
            ItemStack chestStack = mc.player.currentScreenHandler.slots.get(i).getStack();
            if (chestStack.isEmpty()) return true;
            if (chestStack.isStackable() && chestStack.getItem().equals(stack.getItem())
                && chestStack.getCount() < chestStack.getMaxCount()) return true;
        }
        return false;
    }

    void queueDepositChestAtHead(Pair<BlockPos, Vec3d> chest) {
        mod.checkpoints.add(0, new Pair(chest.getRight(), new Pair("depositBuilding", chest.getLeft())));
    }

    void refillBuildingInventory(HashMap<Item, Integer> invMaterial) {
        //Fills restockList with required build materials
        restockList.clear();
        HashMap<Item, Integer> requiredItems = getRequiredItems();
        for (Item item : invMaterial.keySet()) {
            int oldAmount = requiredItems.remove(item);
            requiredItems.put(item, oldAmount - invMaterial.get(item));
        }

        for (Item item : requiredItems.keySet()) {
            if (requiredItems.get(item) <= 0) continue;
            int stacks = (int) Math.ceil((float) requiredItems.get(item) / 64f);
            if(mod.debugPrints.get()) mod.info("Restocking §a" + stacks + " stacks " + item.getName().getString() + " (" + requiredItems.get(item) + ")");
            restockList.add(0, Triple.of(item, stacks, requiredItems.get(item)));
        }
        addClosestRestockCheckpoint();
    }

    void refillFromCurrentPlan() {
        HashMap<Item, Integer> requiredItems = getRequiredItems();
        Pair<ArrayList<Integer>, HashMap<Item, Integer>> invInfo =
            Utils.getInvInformation(requiredItems, mod.availableSlots);
        refillBuildingInventory(invInfo.getRight());
    }

    void addClosestRestockCheckpoint() {
        //Determine closest restock chest for material in restock list
        if (restockList.isEmpty()) return;
        double smallestDistance = Double.MAX_VALUE;
        Triple<Item, Integer, Integer> closestEntry = null;
        Pair<BlockPos, Vec3d> restockPos = null;
        for (Triple<Item, Integer, Integer> entry : restockList) {
            Pair<BlockPos, Vec3d> bestRestockPos = getBestChest(entry.getLeft());
            if (bestRestockPos.getLeft() == null) {
                mod.warning("No chest found for " + entry.getLeft().getName().getString());
                mod.toggle();
                return;
            }
            double chestDistance = PlayerUtils.distanceTo(bestRestockPos.getRight());
            if (chestDistance < smallestDistance) {
                smallestDistance = chestDistance;
                closestEntry = entry;
                restockPos = bestRestockPos;
            }
        }
        //Set closest material as first and as checkpoint
        restockList.remove(closestEntry);
        restockList.addFirst(closestEntry);
        mod.checkpoints.addFirst(new Pair(restockPos.getRight(), new Pair("refill", restockPos.getLeft())));
    }

    void endRestocking() {
        if (restockList.get(0).getMiddle() > 0) {
            checkedChests.add(lastInteractedChest);
            // Stop the re-search cycle once every chest stocking this item has been tried
            List<Pair<BlockPos, Vec3d>> stockingChests =
                mod.materialDict.getOrDefault(restockList.get(0).getLeft(), new ArrayList<>());
            int untriedChests = 0;
            for (Pair<BlockPos, Vec3d> p : stockingChests) {
                if (!checkedChests.contains(p.getLeft())) untriedChests++;
            }
            if (untriedChests == 0) {
                mod.error("No registered chest has enough " + restockList.get(0).getLeft().getName().getString()
                    + " (need " + restockList.get(0).getMiddle() + " more). Restock the chests and restart the module.");
                mod.toggle();
                return;
            }
            mod.warning("Not all necessary stacks restocked. Searching for another chest stocking "
                + restockList.get(0).getLeft().getName().getString() + "...");
            //Search for the next best chest that still stocks the item currently being restocked
            Pair<BlockPos, Vec3d> bestRestockPos = getBestChest(restockList.get(0).getLeft());
            mod.checkpoints.addFirst(new Pair<>(bestRestockPos.getRight(), new Pair<>("refill", bestRestockPos.getLeft())));
        } else {
            checkedChests.clear();
            restockList.remove(0);
            addClosestRestockCheckpoint();
        }
        mod.timeoutTicks = mod.postRestockDelay.get();
        mod.state = State.Walking;
    }

    void interactWithBlock(BlockPos chestPos) {
        Utils.setForwardPressed(false);
        mc.player.setYaw((float) Rotations.getYaw(chestPos.toCenterPos()));
        mc.player.setPitch((float) Rotations.getPitch(chestPos.toCenterPos()));

        BlockHitResult hitResult = new BlockHitResult(chestPos.toCenterPos(), Utils.getInteractionSide(chestPos), chestPos, false);
        BlockUtils.interact(hitResult, Hand.MAIN_HAND, true);

        //Set timeout for chest interaction
        interactTimeout = mod.retryInteractTimer.get();
        lastInteractedChest = chestPos;
    }

    void handleInventoryPacket(InventoryS2CPacket packet) {
        if (mod.debugPrints.get()) mod.info("Handling InvPacket for: " + mod.state);
        closeNextInvPacket = true;
        switch (mod.state) {
            case AwaitRestockResponse:
                handleRestockResponse(packet);
                break;
            case AwaitMapChestResponse:
                int mapSlot = -1;
                int paneSlot = -1;
                //Search for map and glass pane
                for (int slot = 0; slot < packet.contents().size() - 36; slot++) {
                    ItemStack stack = packet.contents().get(slot);
                    if (stack.getItem() == Items.MAP) mapSlot = slot;
                    if (stack.getItem() == Items.GLASS_PANE) paneSlot = slot;
                }
                if (mapSlot == -1 || paneSlot == -1) {
                    mod.warning("Not enough Empty Maps/Glass Panes in Map Material Chest");
                    return;
                }
                interactTimeout = 0;
                mod.timeoutTicks = mod.postRestockDelay.get();
                Utils.getOneItem(mapSlot, false, mod.availableSlots, mod.availableHotBarSlots, packet);
                Utils.getOneItem(paneSlot, true, mod.availableSlots, mod.availableHotBarSlots, packet);
                mc.player.getInventory().setSelectedSlot(mod.availableHotBarSlots.get(0));
                mod.state = State.Walking;
                break;
            case AwaitFoodChestResponse:
                interactTimeout = 0;
                mod.timeoutTicks = mod.postRestockDelay.get();
                // Take only what FITS in the offhand (user rule): offhand empty -> one
                // full stack (= capacity); partially filled (<10 trigger) -> HALF a
                // chest stack, which always fits beside the remainder. Never a second
                // full stack on top of an existing one.
                int offFood = offhandFoodCount();
                for (int slot = 0; slot < packet.contents().size() - 36; slot++) {
                    ItemStack stack = packet.contents().get(slot);
                    if (!stack.isEmpty() && stack.contains(DataComponentTypes.FOOD)) {
                        if (offFood == 0) {
                            mc.interactionManager.clickSlot(packet.syncId(), slot, 0, SlotActionType.QUICK_MOVE, mc.player);
                        } else {
                            mc.interactionManager.clickSlot(packet.syncId(), slot, 1, SlotActionType.PICKUP, mc.player);
                            mc.interactionManager.clickSlot(packet.syncId(), slot, 0, SlotActionType.PICKUP, mc.player);
                        }
                        break;
                    }
                }
                mod.state = State.Walking;
                break;
            case AwaitDepositResponse:
                interactTimeout = 0;
                // FILTERED PREP DUMP: select the excess live at every chest-open
                // (screen-index truth, self-correcting); when nothing is beyond
                // need the leg finishes and the route continues to the refill.
                if (filteredDumpActive) {
                    buildFilteredDumpBacklog(packet);
                    if (!depositBacklogSlots.isEmpty()) mod.timeoutTicks = mod.invActionDelay.get();
                    break;
                }
                // Normal (strip) flow: queue EVERY carried stack that belongs in this
                // chest (the chest must stock the item); the tick then deposits one per
                // delay tick while the screen stays open - full-inventory syncs only
                // arrive on open, so per-packet re-triggering stalls after the first stack
                if (depositBacklogSlots.isEmpty()) {
                    for (int slot = packet.contents().size() - 36; slot < packet.contents().size(); slot++) {
                        ItemStack stack = packet.contents().get(slot);
                        if (stack.isEmpty() || ToolUtils.isTool(stack) || stack.contains(DataComponentTypes.FOOD)) continue;
                        ArrayList<Pair<BlockPos, Vec3d>> stocking = mod.materialDict.get(stack.getItem());
                        if (stocking == null || !containsChest(stocking, lastInteractedChest)) continue;
                        depositBacklogSlots.add(slot);
                    }
                }
                if (depositBacklogSlots.isEmpty()) {
                    // Nothing belongs in THIS chest: hop to the next correct one or continue
                    Pair<BlockPos, Vec3d> nextDepositChest = getNextDepositChest();
                    if (nextDepositChest != null) queueDepositChestAtHead(nextDepositChest);
                    mod.timeoutTicks = mod.postRestockDelay.get();
                    mod.state = State.Walking;
                } else {
                    mod.timeoutTicks = mod.invActionDelay.get();
                }
                break;
        }
    }

    void handleRestockResponse(InventoryS2CPacket packet) {
        interactTimeout = 0;
        // TOOL RETURN (user rule 2026-10-20): used pickaxes go back to their OWN
        // chest ONLY at this restock-phase visit, one deposit per sync - never
        // mid-batch, never into a material chest. A pending take is a SWAP: the
        // fetch only queues when the best carried pickaxe is below the buffer, so
        // park ALL of them and take the fresh one below (the old deposit took only
        // below-min-durability ones, letting replaced pickaxes ride along forever).
        // With no take pending, keep exactly ONE pickaxe - the best - and return
        // every spare (a sub-10-durability pickaxe is never the kept one: it is
        // either a spare, or the sole pickaxe, in which case the 800-buffer fetch
        // above has already queued its replacement).
        if (isOpenChestAPickaxeChest()) {
            boolean swapTake = !restockList.isEmpty() && restockList.get(0).getMiddle() > 0
                && new ItemStack(restockList.get(0).getLeft()).isIn(ItemTags.PICKAXES);
            int keepSlot = -1;
            if (!swapTake) {
                // Keep-best scan in screen-handler slot space (last 36 slots are
                // the player inventory) - inventory indices don't map 1:1 here.
                int keepRemaining = -1;
                for (int slot = packet.contents().size() - 36; slot < packet.contents().size(); slot++) {
                    ItemStack invStack = packet.contents().get(slot);
                    if (invStack.isIn(ItemTags.PICKAXES) && ToolUtils.getRemainingDamage(invStack) > keepRemaining) {
                        keepSlot = slot;
                        keepRemaining = ToolUtils.getRemainingDamage(invStack);
                    }
                }
            }
            for (int slot = packet.contents().size() - 36; slot < packet.contents().size(); slot++) {
                if (slot == keepSlot) continue;
                ItemStack invStack = packet.contents().get(slot);
                if (invStack.isIn(ItemTags.PICKAXES)) {
                    mc.interactionManager.clickSlot(packet.syncId(), slot, 0, SlotActionType.QUICK_MOVE, mc.player);
                    mod.timeoutTicks = mod.invActionDelay.get();
                    // LOST-SYNC SELF-HEAL (live 06:54, batch 9): this return waits for
                    // the server's post-click inventory sync to drive the next parking
                    // round - but the entry of this method zeroes interactTimeout, so
                    // if that sync is lost (screen-close race on Folia) NOBODY ever
                    // re-opens the chest and the bot freezes in AwaitRestockResponse
                    // forever. Re-arm the retry: sync arrives -> next round (zeroes it
                    // again); no sync -> tickInteractRetry re-opens the chest.
                    interactTimeout = mod.retryInteractTimer.get();
                    return;
                }
            }
        }
        // AXE PARKING - the pickaxe rule verbatim (user rule 2026-10-21: same logic
        // as pickaxe): at an axe-stocking chest a pending axe take is a SWAP - park
        // ALL carried axes, the fresh one is taken below. With no take pending keep
        // exactly ONE axe - the best - and return every spare. One deposit per sync,
        // after the pickaxe parking above has drained.
        if (isOpenChestAnAxeChest()) {
            boolean swapTakeAxe = !restockList.isEmpty() && restockList.get(0).getMiddle() > 0
                && new ItemStack(restockList.get(0).getLeft()).isIn(ItemTags.AXES);
            int keepAxeSlot = -1;
            if (!swapTakeAxe) {
                int keepAxeRemaining = -1;
                for (int slot = packet.contents().size() - 36; slot < packet.contents().size(); slot++) {
                    ItemStack invStack = packet.contents().get(slot);
                    if (invStack.isIn(ItemTags.AXES) && ToolUtils.getRemainingDamage(invStack) > keepAxeRemaining) {
                        keepAxeSlot = slot;
                        keepAxeRemaining = ToolUtils.getRemainingDamage(invStack);
                    }
                }
            }
            for (int slot = packet.contents().size() - 36; slot < packet.contents().size(); slot++) {
                if (slot == keepAxeSlot) continue;
                ItemStack invStack = packet.contents().get(slot);
                if (invStack.isIn(ItemTags.AXES)) {
                    mc.interactionManager.clickSlot(packet.syncId(), slot, 0, SlotActionType.QUICK_MOVE, mc.player);
                    mod.timeoutTicks = mod.invActionDelay.get();
                    interactTimeout = mod.retryInteractTimer.get();   // lost-sync self-heal (see pickaxe parking)
                    return;
                }
            }
        }
        boolean foundMaterials = false;
        List<Integer> slots = IntStream.rangeClosed(0, packet.contents().size() - 37)
            .boxed()
            .collect(Collectors.toList());
        Collections.shuffle(slots);
        for (int slot : slots) {
            ItemStack stack = packet.contents().get(slot);

            if (restockList.get(0).getMiddle() == 0) {
                // Deposit-only TOOL entry (0 stacks to take): spent once nothing
                // is left to park here. A pending TAKE also reaches middle==0 while
                // its backlog drains - the tick's drain ends that entry itself, so
                // only advance when no backlog is in flight.
                if (restockBacklogSlots.isEmpty()
                    && (new ItemStack(restockList.get(0).getLeft()).isIn(ItemTags.PICKAXES)
                        || new ItemStack(restockList.get(0).getLeft()).isIn(ItemTags.AXES))) {
                    endRestocking();
                }
                foundMaterials = true;
                break;
            }
            // Mixed chests: only take stacks of the material currently being restocked.
            // Partial stacks are fine - the remaining count is recomputed from the
            // leftover stack size, so any stack size makes progress.
            if (!stack.isEmpty() && stack.getItem().equals(restockList.get(0).getLeft())) {
                //mod.info("Taking Stack of " + restockList.get(0).getLeft().getName().getString());
                foundMaterials = true;
                int highestFreeSlot = Utils.findHighestFreeSlot(packet);
                if (highestFreeSlot == -1) {
                    // RECOVER instead of disable (live 2026-10-20: full inventory
                    // of prior-run drops killed the run here): close the chest and
                    // re-run the prep path - its dump now frees the excess slots.
                    mod.warning("§eInventory full at withdrawal - running the excess dump, then retrying the restock.");
                    if (mc.player.currentScreenHandler != null) mc.player.closeHandledScreen();
                    restockBacklogSlots.clear();
                    mod.timeoutTicks = mod.postRestockDelay.get();
                    mod.slave.routes.pauseForRestockTrip(false);
                    return;
                }
                restockBacklogSlots.add(slot);
                Triple<Item, Integer, Integer> oldTriple = restockList.remove(0);
                if (stack.isStackable()) {
                    int remainingCount = oldTriple.getRight() - stack.getCount();
                    int remainingStacks = Math.max(0, (int) Math.ceil(remainingCount / 64f));
                    restockList.add(0, Triple.of(oldTriple.getLeft(), remainingStacks, remainingCount));
                } else {
                    restockList.add(0, Triple.of(oldTriple.getLeft(), oldTriple.getMiddle() - 1, oldTriple.getRight() - 64));
                }
            }
        }
        if (!foundMaterials) endRestocking();
    }

    boolean drainRestockBacklog() {
        if (restockBacklogSlots.size() == 0) return false;
        // Same screen guard as drainDepositBacklog: chest-relative indices are
        // unclickworthy once the chest screen is gone - drop the backlog and let the
        // restock-retry paths (prep/emergency) re-open a chest cleanly.
        if (mc.player.currentScreenHandler == null
            || mc.player.currentScreenHandler.slots.size() <= 46) {
            restockBacklogSlots.clear();
            if (mc.currentScreen != null) mc.player.closeHandledScreen();
            mod.timeoutTicks = mod.postRestockDelay.get();
            mod.state = State.Walking;
            return true;
        }
        int slot = restockBacklogSlots.remove(0);
        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot, 1, SlotActionType.QUICK_MOVE, mc.player);
        if (restockBacklogSlots.isEmpty()) {
            if (mod.state.equals(State.AwaitRestockResponse)) {
                endRestocking();
            }
        } else {
            mod.timeoutTicks = mod.invActionDelay.get();
        }
        return true;
    }

    boolean drainDepositBacklog() {
        if (mod.state != State.AwaitDepositResponse || depositBacklogSlots.isEmpty()) return false;
        int slot = depositBacklogSlots.remove(0);
        // SCREEN GUARD (live 05:38:40 crash: Index 68 out of 46): the backlog holds
        // CHEST-screen slot indices; if that screen closed under us the current
        // handler is the player's own (46 slots) and .get(slot) throws. A gone screen
        // means the deposit leg ended through no fault of the chest - re-queue the leg
        // and let the chest-open flow rebuild the backlog (never blacklist, never click).
        if (mc.player.currentScreenHandler == null
            || mc.player.currentScreenHandler.slots.size() <= 46
            || slot >= mc.player.currentScreenHandler.slots.size()) {
            mod.warning("§eDeposit chest screen closed mid-drain (slot " + slot + " unreachable) - re-queueing the deposit leg.");
            depositBacklogSlots.clear();
            if (mc.currentScreen != null) mc.player.closeHandledScreen();
            Pair<BlockPos, Vec3d> redoChest;
            if (filteredDumpActive) {
                HashSet<Item> excess = excessItemsBeyondNeed();
                redoChest = excess.isEmpty() ? null : getNextFilteredDepositChest(excess);
                if (redoChest == null) clearFilteredDump();   // nowhere to put it - carry the excess
            } else {
                redoChest = getNextDepositChest();
            }
            if (redoChest != null) queueDepositChestAtHead(redoChest);
            mod.timeoutTicks = mod.postRestockDelay.get();
            mod.state = State.Walking;
            return true;
        }
        ItemStack pending = mc.player.currentScreenHandler.slots.get(slot).getStack();
        if (pending.isEmpty()) return true;
        if (!chestHasRoomFor(pending)) {
            fullDepositChests.add(lastInteractedChest);
            mod.info("§eChest " + lastInteractedChest.toShortString() + " is full - depositing in another registered chest.");
            depositBacklogSlots.clear();
            if (filteredDumpActive) {
                // Quantity dump: re-derive the still-carried excess at the next chest
                HashSet<Item> excess = excessItemsBeyondNeed();
                Pair<BlockPos, Vec3d> nextFiltered = excess.isEmpty() ? null : getNextFilteredDepositChest(excess);
                if (nextFiltered != null) queueDepositChestAtHead(nextFiltered);
                else clearFilteredDump();   // nowhere to put it - carry the excess, prep accounting tolerates it
            } else {
                Pair<BlockPos, Vec3d> nextDepositChest = getNextDepositChest();
                if (nextDepositChest != null) queueDepositChestAtHead(nextDepositChest);
            }
            mod.timeoutTicks = mod.postRestockDelay.get();
            mod.state = State.Walking;
            return true;
        }
        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);
        if (depositBacklogSlots.isEmpty()) {
            if (filteredDumpActive) {
                // Quantity dump done at this chest: only chain while excess is still
                // carried (e.g. drops picked up en route); NEVER the strip chain -
                // the refill leg later in this same trip counts on the kept stacks.
                HashSet<Item> excess = excessItemsBeyondNeed();
                if (!excess.isEmpty()) {
                    Pair<BlockPos, Vec3d> nextFiltered = getNextFilteredDepositChest(excess);
                    if (nextFiltered != null) {
                        queueDepositChestAtHead(nextFiltered);
                        mod.timeoutTicks = mod.postRestockDelay.get();
                        mod.state = State.Walking;
                        return true;
                    }
                    mod.warning("§ePrep dump: no chest with room for the remaining excess - carrying it on.");
                }
                clearFilteredDump();
                mod.timeoutTicks = mod.postRestockDelay.get();
                mod.state = State.Walking;
                return true;
            }
            Pair<BlockPos, Vec3d> nextDepositChest = getNextDepositChest();
            if (nextDepositChest != null) queueDepositChestAtHead(nextDepositChest);
            mod.timeoutTicks = mod.postRestockDelay.get();
            mod.state = State.Walking;
        } else {
            mod.timeoutTicks = mod.invActionDelay.get();
        }
        return true;
    }

    boolean processDeferredInventoryIO() {
        if (toBeHandledInvPacket != null) {
            handleInventoryPacket(toBeHandledInvPacket);
            toBeHandledInvPacket = null;
            return true;
        }
        if (closeNextInvPacket) {
            if (mc.currentScreen != null) {
                mc.player.closeHandledScreen();
            }
            closeNextInvPacket = false;
        }
        return false;
    }

    void tickInteractRetry() {
        if (interactTimeout > 0) {
            interactTimeout--;
            if (interactTimeout == 0) {
                mod.info("Interaction timed out. Interacting again...");
                interactWithBlock(lastInteractedChest);
            }
        }
    }

    HashMap<Item, Integer> getRequiredItems() {
        //Calculate the next items to restock: count one per still-missing plan block
        //(live world truth), capped by the available inventory slots
        HashMap<Item, Integer> requiredItems = new HashMap<>();
        if (mod.currentBuildPlan == null) return requiredItems;
        boolean capped = false;
        for (Map.Entry<BlockPos, Block> entry : mod.currentBuildPlan.entrySet()) {
            if (!mod.miningBlockState(entry.getKey()).isAir()) continue;
            Item material = entry.getValue().asItem();
            int oldCount = requiredItems.getOrDefault(material, 0);
            requiredItems.put(material, oldCount + 1);
            if (Utils.stacksRequired(requiredItems.values()) > mod.availableSlots.size()) {
                requiredItems.put(material, oldCount);
                capped = true;
            }
        }
        // Silent truncation here starved hash-order item types of their withdrawal
        // and surfaced later as mid-print supply runs (user-diagnosed, 2026-10-21)
        if (capped) mod.warning("§eRestock capped by slot count - some plan materials left unwithdrawn; a mid-pass supply run may fire.");
        return requiredItems;
    }

    boolean setupSlots() {
        mod.availableSlots = Utils.getAvailableSlots(mod.materialDict);
        // Hotbar slot 8 is RESERVED for the pickaxe - never used for materials or swaps
        mod.availableSlots.remove(Integer.valueOf(8));
        for (int slot : mod.availableSlots) {
            if (slot < 9) {
                mod.availableHotBarSlots.add(slot);
            }
        }
        if (mod.debugPrints.get()) mod.info("Inventory slots available for building: " + mod.availableSlots);
        if (mod.availableHotBarSlots.isEmpty()) {
            mod.warning("No free slots found in hot-bar!");
            mod.availableSlots.clear();
            mod.toggle();
            return false;
        }
        if (mod.availableSlots.size() < 2) {
            mod.warning("You need at least 2 free inventory slots!");
            mod.availableSlots.clear();
            mod.toggle();
            return false;
        }
        return true;
    }

    int freeInventoryCapacity() {
        int capacity = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty()) { capacity += 64; continue; }
            if (stack.isStackable()) capacity += stack.getMaxCount() - stack.getCount();
        }
        return capacity;
    }

    boolean isInventoryFull() {
        for (int slot : mod.availableSlots) {
            if (mc.player.getInventory().getStack(slot).isEmpty()) return false;
        }
        return true;
    }

    boolean isMaterialCarried(Item needed) {
        for (int slot : mod.availableSlots) {
            if (!mc.player.getInventory().getStack(slot).isEmpty()
                && mc.player.getInventory().getStack(slot).getItem().equals(needed)) return true;
        }
        return false;
    }

    boolean isItemInHotbar(Item item) {
        for (int slot : mod.availableHotBarSlots) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (!stack.isEmpty() && stack.getItem().equals(item)) return true;
        }
        return false;
    }

    /** Use-time hotbar entry (consumeHotbarSwap path): victim choice delegates to
     *  BreakPlaceEngine.pickHotbarVictim — the same window supply/demand authority
     *  as the rolling lookahead — with force=true: the material is needed NOW, so
     *  when every resident is load-bearing the farthest-first resident still gives
     *  way (the roller re-stages it from main inventory within a second). Without a
     *  live plan there is no window to reason with — first staging slot. */
    void swapIntoHotbar(int slot) {
        int targetSlot = -1;
        if (mod.currentBuildPlan != null) {
            BreakPlaceEngine.WindowDemand wd = mod.engine.computeWindowDemand();
            ItemStack srcStack = mc.player.getInventory().getStack(slot);
            targetSlot = mod.engine.pickHotbarVictim(wd, srcStack.getItem(), srcStack.getCount(), true);
        }
        if (targetSlot == -1) targetSlot = mod.availableHotBarSlots.get(0);
        Utils.performSwap(slot, targetSlot);
    }

    int offhandFoodCount() {
        ItemStack off = mc.player.getOffHandStack();
        if (off.isEmpty() || !off.contains(DataComponentTypes.FOOD)) return 0;
        return off.getCount();
    }

    void moveFoodToOffhand() {
        if (mc.player.getOffHandStack().contains(DataComponentTypes.FOOD)) return;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (!stack.isEmpty() && stack.contains(DataComponentTypes.FOOD)) {
                int screenSlot = slot < 9 ? 36 + slot : slot;
                mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, screenSlot, 40, SlotActionType.SWAP, mc.player);
                mod.info("Food to the offhand.");
                return;
            }
        }
    }

    void setupHands() {
        movePickaxeToSlot8();
        moveFoodToOffhand();
    }

    void movePickaxeToSlot8() {
        if (mc.player.getInventory().getStack(8).isIn(ItemTags.PICKAXES)) return;
        int slot = findPickaxeSlot();
        if (slot == -1) return;
        if (slot == 40) {
            // Offhand source: inventory index 40 is NOT a handler slot id (that is
            // hotbar 4) - swap offhand <-> hotbar 8 directly via button 40.
            Utils.swapOffhand(36 + 8);
            mod.info("Pickaxe moved to slot 8.");
            return;
        }
        if (slot < 9) slot += 36;   // clickSlot is HANDLER-indexed: hotbar h = 36+h (0-8 are crafting/armor)
        Utils.performSwap(slot, 8);
        mod.info("Pickaxe moved to slot 8.");
    }

    /** Tools never live in the offhand (food owns it) - but a player can park one
     *  there, and EVERY tool scan used to read only slots 0-35: an offhand axe
     *  read as "no axe carried" and the prep fetched a duplicate (live 06:49,
     *  batch 8, user: "tool can be anywhere in inventory"). Pull any offhand tool
     *  into a free main-inventory slot BEFORE the prep's fetch/deposit decisions.
     *  No free slot: no-op - the scans are offhand-aware and decide truthfully. */
    void normalizeToolsFromOffhand() {
        ItemStack off = mc.player.getInventory().getStack(40);
        if (off.isEmpty() || !ToolUtils.isTool(off)) return;
        for (int slot = 9; slot < 36; slot++) {
            if (mc.player.getInventory().getStack(slot).isEmpty()) {
                Utils.swapOffhand(slot);
                mod.info("§7" + off.getItem().getName().getString() + " normalized out of the offhand (slot " + slot + ").");
                return;
            }
        }
    }

    void ensurePickaxeSlot8() {
        movePickaxeToSlot8();
    }

    int findPickaxeSlot() {
        int bestSlot = -1;
        for (int slot = 0; slot <= 40; slot++) {   // 40 = offhand: tools can sit anywhere (user rule)
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty() || !stack.isIn(ItemTags.PICKAXES)) continue;
            if (bestSlot == -1 || ToolUtils.getRemainingDamage(stack) > ToolUtils.getRemainingDamage(mc.player.getInventory().getStack(bestSlot))) bestSlot = slot;
        }
        return bestSlot;
    }

    int countPickaxes() {
        int count = 0;
        for (int slot = 0; slot <= 40; slot++) {   // 40 = offhand
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (!stack.isEmpty() && stack.isIn(ItemTags.PICKAXES)) count++;
        }
        return count;
    }

    boolean isOpenChestAPickaxeChest() {
        if (lastInteractedChest == null) return false;
        for (HashMap.Entry<Item, ArrayList<Pair<BlockPos, Vec3d>>> entry : mod.materialDict.entrySet()) {
            if (!new ItemStack(entry.getKey()).isIn(ItemTags.PICKAXES)) continue;
            if (containsChest(entry.getValue(), lastInteractedChest)) return true;
        }
        return false;
    }

    boolean isOpenChestAnAxeChest() {
        if (lastInteractedChest == null) return false;
        for (HashMap.Entry<Item, ArrayList<Pair<BlockPos, Vec3d>>> entry : mod.materialDict.entrySet()) {
            if (!new ItemStack(entry.getKey()).isIn(ItemTags.AXES)) continue;
            if (containsChest(entry.getValue(), lastInteractedChest)) return true;
        }
        return false;
    }

    boolean hasAnyPickaxe() {
        if (mc.player.getInventory().getStack(8).isIn(ItemTags.PICKAXES)) return true;
        for (int slot = 0; slot <= 40; slot++) {   // 40 = offhand
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (!stack.isEmpty() && stack.isIn(ItemTags.PICKAXES)) return true;
        }
        return false;
    }

    Item findRegisteredPickaxeItem() {
        for (ItemStack tool : mod.toolSet) {
            if (tool.isIn(ItemTags.PICKAXES)) return tool.getItem();
        }
        for (Item item : mod.materialDict.keySet()) {
            if (new ItemStack(item).isIn(ItemTags.PICKAXES)) return item;
        }
        return null;
    }

    void restockPickaxeTrip(Vec3d stagingPos) {
        mod.info("§eNo pickaxe - fetching one from the restock station.");
        Item pickaxe = findRegisteredPickaxeItem();
        if (pickaxe == null) {
            mod.warning("No pickaxe registered in any chest - continuing without one.");
            return;
        }
        Pair<BlockPos, Vec3d> chest = getBestChest(pickaxe);
        if (chest.getLeft() == null || chest.getLeft().equals(BlockPos.ORIGIN)) {
            mod.warning("No chest stocks " + pickaxe.getName().getString() + " - cannot fetch a pickaxe.");
            return;
        }
        restockList.add(Triple.of(pickaxe, 1, 1));
        mod.checkpoints.add(0, new Pair(new Vec3d(stagingPos.x, stagingPos.y, stagingPos.z), new Pair("batchBegin", null)));
        mod.checkpoints.add(0, new Pair(chest.getRight(), new Pair("refill", chest.getLeft())));
        mod.state = State.Walking;
    }


}
