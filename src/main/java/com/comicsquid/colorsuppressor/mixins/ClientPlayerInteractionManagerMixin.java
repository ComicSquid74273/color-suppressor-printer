package com.comicsquid.colorsuppressor.mixins;

import com.comicsquid.colorsuppressor.interfaces.IClientPlayerInteractionManager;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = ClientPlayerInteractionManager.class, priority = 1002)
public abstract class ClientPlayerInteractionManagerMixin implements IClientPlayerInteractionManager {
    @Shadow
    public abstract void clickSlot(int syncId, int slotId, int button, SlotActionType actionType, PlayerEntity player);

    @Shadow
    public abstract void sendPlayerAction(PlayerActionC2SPacket.Action action, BlockPos pos, Direction direction);

    @Shadow
    private int blockBreakingCooldown;

    /**
     * Instamine: sends START_DESTROY + STOP_DESTROY in the same tick. The server
     * processes them in order — with an Efficiency V netherite pickaxe, map art
     * blocks (concrete, terracotta) have ≤1 tick hardness and break instantly.
     * Soft anticheats (6b6t) accept this without rotation or timing validation.
     */
    public void fastBreak(BlockPos pos) {
        sendPlayerAction(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP);
        sendPlayerAction(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, Direction.UP);
        blockBreakingCooldown = 0;
    }
}
