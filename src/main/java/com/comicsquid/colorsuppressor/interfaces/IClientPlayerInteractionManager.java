package com.comicsquid.colorsuppressor.interfaces;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;

public interface IClientPlayerInteractionManager {
    void setBlockBreakingCooldown(int cooldown);

    float getCurrentBreakingProgress();

    void clickSlot(int syncId, int slotId, int button, SlotActionType actionType, PlayerEntity player);

    void fastBreak(BlockPos pos);
}
