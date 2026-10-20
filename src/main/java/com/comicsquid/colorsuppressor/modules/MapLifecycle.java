package com.comicsquid.colorsuppressor.modules;

import com.comicsquid.colorsuppressor.utils.SlaveSystem;
import com.comicsquid.colorsuppressor.utils.Utils;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.Pair;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The working map's lifecycle: id resolution, slot finding, pinning (by exact id,
 * never a named reference), hold/stow discipline (held only during dwells), the
 * inventory-to-hotbar swap primitive, the empty-map fill at the gate checkpoint, and
 * the supervisor dry-run hold service (/holdmap). Owns the pinned map id + swap latches.
 */
final class MapLifecycle {
    final StepCheckered mod;

    int stepWorkingMapId = -1;
    boolean stepMapSwapPending;
    int stepMapSwapAge;                                   // retry countdown for the filled-map hotbar swap
    boolean controlHoldActive;                            // supervisor /holdmap flag (dry-run hold)
    boolean controlSwapPending;                           // empty-map swap into the hotbar in progress
    int controlUseCooldown;                               // one-shot gate for the empty-map auto-use

    MapLifecycle(StepCheckered mod) {
        this.mod = mod;
    }

    void resetForActivation() {
        stepWorkingMapId = -1;
        stepMapSwapPending = false;
        stepMapSwapAge = 0;
        controlHoldActive = false;
        controlSwapPending = false;
        controlUseCooldown = 0;
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
            mod.master.abortStepMaster("the empty map disappeared before the fill checkpoint");
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
            com.comicsquid.colorsuppressor.utils.Utils.getNextInteractID(),
            mc.player.getYaw(),
            mc.player.getPitch()));
        mod.master.stepMasterStage = StepMasterStage.WAIT_FILLED_MAP;
        mod.master.stepMasterWaitTicks = 0;
        com.comicsquid.colorsuppressor.utils.Utils.setForwardPressed(false);
        com.comicsquid.colorsuppressor.utils.Utils.setSneakPressed(false);
        mod.info("§7Empty map used at the fill checkpoint; waiting for the filled result.");
    }

    void serviceControlHold() {
        if (!controlHoldActive || SlaveSystem.isSlave || mc.player == null || mc.world == null) return;
        if (mod.master.stepMasterStage != StepMasterStage.IDLE) { controlHoldActive = false; return; }   // a real run takes over
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
                    Hand.MAIN_HAND, com.comicsquid.colorsuppressor.utils.Utils.getNextInteractID(), mc.player.getYaw(), mc.player.getPitch()));
                mod.info("§7No filled map - used one empty map at the current spot.");
            }
            if (controlUseCooldown > 0) controlUseCooldown--;
        }
    }

}
