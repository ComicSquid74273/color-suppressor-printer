package com.comicsquid.colorsuppressor.mixins;

import com.comicsquid.colorsuppressor.interfaces.IClientPlayerInteractionManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
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

    /**
     * Instamine: sends START_DESTROY + STOP_DESTROY in the same tick. The server
     * processes them in order — with an Efficiency V netherite pickaxe, map art
     * blocks (concrete, terracotta) have ≤1 tick hardness and break instantly.
     * Soft anticheats (6b6t) accept this without rotation or timing validation.
     * Packets go straight through the network handler: the addon ships no
     * refmap, so @Shadow-ing the interaction manager's own packet-sender (or
     * its cooldown field) fails to resolve at runtime and crashes on join.
     */
    @Override
    public void fastBreak(BlockPos pos) {
        ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
        if (handler == null) return;
        handler.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP));
        handler.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, Direction.UP));
    }
}
