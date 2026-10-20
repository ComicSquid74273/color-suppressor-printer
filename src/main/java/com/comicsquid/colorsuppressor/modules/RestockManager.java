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
import java.util.concurrent.atomic.AtomicReference;
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
                // Queue EVERY carried stack that belongs in this chest (the chest must stock
                // the item); the tick then deposits one per delay tick while the screen stays
                // open - full-inventory syncs only arrive on open, so per-packet re-triggering
                // stalls after the first stack
                if (depositBacklogSlots.isEmpty()) {
                    // Normal flow: backlog is empty at chest-open - build it from every
                    // carried stack that belongs in this chest.
                    for (int slot = packet.contents().size() - 36; slot < packet.contents().size(); slot++) {
                        ItemStack stack = packet.contents().get(slot);
                        if (stack.isEmpty() || ToolUtils.isTool(stack) || stack.contains(DataComponentTypes.FOOD)) continue;
                        ArrayList<Pair<BlockPos, Vec3d>> stocking = mod.materialDict.get(stack.getItem());
                        if (stocking == null || !containsChest(stocking, lastInteractedChest)) continue;
                        depositBacklogSlots.add(slot);
                    }
                }
                // else: PRE-FILLED backlog = the unneeded-material dump (depositUnneeded
                // action) - keep exactly those slots, deposit nothing else.
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
                // Deposit-only pickaxe entry (0 stacks to take): spent once nothing
                // is left to park here. A pending TAKE also reaches middle==0 while
                // its backlog drains - the tick's drain ends that entry itself, so
                // only advance when no backlog is in flight.
                if (restockBacklogSlots.isEmpty()
                    && new ItemStack(restockList.get(0).getLeft()).isIn(ItemTags.PICKAXES)) {
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
                    mod.pauseForRestockTrip(false);
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
        ItemStack pending = mc.player.currentScreenHandler.slots.get(slot).getStack();
        if (pending.isEmpty()) return true;
        if (!chestHasRoomFor(pending)) {
            fullDepositChests.add(lastInteractedChest);
            mod.info("§eChest " + lastInteractedChest.toShortString() + " is full - depositing in another registered chest.");
            Pair<BlockPos, Vec3d> nextDepositChest = getNextDepositChest();
            depositBacklogSlots.clear();
            if (nextDepositChest != null) queueDepositChestAtHead(nextDepositChest);
            mod.timeoutTicks = mod.postRestockDelay.get();
            mod.state = State.Walking;
            return true;
        }
        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);
        if (depositBacklogSlots.isEmpty()) {
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
        for (Map.Entry<BlockPos, Block> entry : mod.currentBuildPlan.entrySet()) {
            if (!mod.miningBlockState(entry.getKey()).isAir()) continue;
            Item material = entry.getValue().asItem();
            int oldCount = requiredItems.getOrDefault(material, 0);
            requiredItems.put(material, oldCount + 1);
            if (Utils.stacksRequired(requiredItems.values()) > mod.availableSlots.size()) {
                requiredItems.put(material, oldCount);
            }
        }
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

    void swapIntoHotbar(int slot) {
        Map<Item, Integer> itemSlot = new HashMap<>();
        Map<Item, Integer> itemDistance = new HashMap<>();
        Map<Item, Integer> itemFrequency = new HashMap<>();

        int targetSlot = mod.availableHotBarSlots.get(0);

        // Scan hotbar
        for (int hotbarSlot : mod.availableHotBarSlots) {
            ItemStack stack = mc.player.getInventory().getStack(hotbarSlot);
            if (!stack.isEmpty()) {
                Item item = stack.getItem();
                itemSlot.put(item, hotbarSlot);
                itemDistance.put(item, -1); // -1 = never used
                itemFrequency.put(item, 0);
            } else {
                targetSlot = hotbarSlot;
                break;
            }
        }

        // PRIORITY 1: empty slot → instant choice
        if (mc.player.getInventory().getStack(targetSlot).isEmpty()) {
            Utils.performSwap(slot, targetSlot);
            return;
        }

        // Get blocks until next use of items in hotbar
        AtomicReference<Integer> blockCounter = new AtomicReference<>(0);
        forEachMapBlock((x, z, y, blockState, block) -> {
            if (!Utils.isInInterval(mod.workingInterval, x)) return true;
            blockCounter.set(blockCounter.get() + 1);

            if (blockState.isAir() || block == null) return true;
            Item item = block.asItem();

            if (itemDistance.containsKey(item) &&
                itemDistance.get(item) == -1) {
                itemDistance.put(item, blockCounter.get());
            }
            return true;
        });

        // Count frequency of items in hotbar
        for (int hotbarSlot : mod.availableHotBarSlots) {
            ItemStack stack = mc.player.getInventory().getStack(hotbarSlot);
            if (!stack.isEmpty()) {
                Item item = stack.getItem();
                itemFrequency.put(item, itemFrequency.get(item) + 1);
            }
        }

        // Choose best candidate
        Item bestItem = null;
        int bestDistance = -2; // lower than -1
        int bestFrequency = -1;

        for (Item item : itemSlot.keySet()) {
            int distance = itemDistance.get(item); // -1 = never used
            int frequency = itemFrequency.get(item);

            boolean better = false;

            // PRIORITY 2: never used (-1)
            if (distance == -1 && bestDistance != -1) {
                better = true;
            }
            // PRIORITY 3: hotbar frequency
            else if (frequency > bestFrequency) {
                better = true;
            }
            // PRIORITY 4: distance to next use
            else if (frequency == bestFrequency && distance > bestDistance && bestDistance != -1) {
                better = true;
            }

            if (better) {
                bestItem = item;
                bestDistance = distance;
                bestFrequency = frequency;
            }
        }

        if (bestItem != null) {
            targetSlot = itemSlot.get(bestItem);
        }

        Utils.performSwap(slot, targetSlot);
    }

    void forEachMapBlock(MapBlockAction action) {
        for (int x = mod.workingInterval.getLeft(); x <= mod.workingInterval.getRight(); x += mod.linesPerRun) {
            for (int z = 0; z < 129; z++) {
                for (int lineBonus = 0; lineBonus < mod.linesPerRun; lineBonus++) {
                    int adjustedX = x + lineBonus;
                    if (!Utils.isInInterval(mod.workingInterval, adjustedX)) break;
                    for (int y = 0; y < 2; y++) {
                        BlockPos pos = mod.lowerMapCorner.add(adjustedX, y, z);
                        BlockState state = mc.world.getBlockState(pos);
                        if (!action.accept(adjustedX, z, y, state, null)) {
                            // Stop future iterations if false is returned
                            return;
                        }
                    }
                }
            }
        }
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
        Utils.performSwap(slot, 8);
        mod.info("Pickaxe moved to slot 8.");
    }

    void ensurePickaxeSlot8() {
        movePickaxeToSlot8();
    }

    int findPickaxeSlot() {
        int bestSlot = -1;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty() || !stack.isIn(ItemTags.PICKAXES)) continue;
            if (bestSlot == -1 || ToolUtils.getRemainingDamage(stack) > ToolUtils.getRemainingDamage(mc.player.getInventory().getStack(bestSlot))) bestSlot = slot;
        }
        return bestSlot;
    }

    int countPickaxes() {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
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

    boolean hasAnyPickaxe() {
        if (mc.player.getInventory().getStack(8).isIn(ItemTags.PICKAXES)) return true;
        for (int slot = 0; slot < 36; slot++) {
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


    /** Walks the map area's coordinate domain (working interval, 129 rows, y 0-1) in
     *  placement order - swapIntoHotbar's eviction heuristic domain. */
    @FunctionalInterface
    interface MapBlockAction {
        boolean accept(int x, int z, int y, net.minecraft.block.BlockState state, net.minecraft.block.Block desiredBlock);
    }
}
