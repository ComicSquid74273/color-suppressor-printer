package com.comicsquid.colorsuppressor.modules;

import com.comicsquid.colorsuppressor.utils.Utils;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.CartographyTableScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.function.Predicate;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * The cartography-table lock machine: pinned working map + glass pane in, locked copy
 * out - one screen-click step per delay tick, aborting on any missing precondition.
 */
final class CartographyLock {
    final StepCheckered mod;

    int stepLockStep = -1;
    int stepLockDelay;

    CartographyLock(StepCheckered mod) {
        this.mod = mod;
    }

    void resetForActivation() {
        stepLockStep = -1;
        stepLockDelay = 0;
    }

    void tickStepLock() {
        Utils.setForwardPressed(false);
        Utils.setSneakPressed(false);
        if (stepLockDelay > 0) {
            stepLockDelay--;
            return;
        }
        if (!(mc.player.currentScreenHandler instanceof CartographyTableScreenHandler table)) {
            mod.master.abortStepMaster("cartography table screen did not open");
            return;
        }
        switch (stepLockStep) {
            case 1 -> {
                if (!moveIntoStepTableSlot(table, 0, stack -> {
                    Integer id = mod.master.maps.mapIdOf(stack);
                    return id != null && id == mod.master.maps.stepWorkingMapId
                        && !stack.contains(DataComponentTypes.CUSTOM_NAME);
                })) {
                    mod.master.abortStepMaster("pinned working map is not in the inventory");
                    return;
                }
                stepLockStep = 2;
                stepLockDelay = 6;
            }
            case 2 -> {
                if (!moveIntoStepTableSlot(table, 1, stack -> stack.getItem() == Items.GLASS_PANE)) {
                    mod.master.abortStepMaster("no glass pane is available for the cartography lock");
                    return;
                }
                stepLockStep = 3;
                stepLockDelay = 10;
            }
            case 3 -> {
                if (table.getSlot(2).getStack().isEmpty()) {
                    mod.master.abortStepMaster("cartography table produced no locked-map result");
                    return;
                }
                mc.interactionManager.clickSlot(table.syncId, 2, 0, SlotActionType.QUICK_MOVE, mc.player);
                stepLockStep = 4;
                stepLockDelay = 8;
            }
            default -> {
                mc.player.closeHandledScreen();
                stepLockStep = -1;
                mod.master.stepMasterStage = StepMasterStage.READY_TO_LOCK;
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

}
