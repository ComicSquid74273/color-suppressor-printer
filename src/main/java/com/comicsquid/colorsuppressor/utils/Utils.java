package com.comicsquid.colorsuppressor.utils;

import com.comicsquid.colorsuppressor.interfaces.IClientPlayerInteractionManager;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.map.MapState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.io.File;
import java.util.*;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class Utils {

    private static int nextInteractID = 2;

    public static int getNextInteractID() {
        return nextInteractID;
    }

    public static ArrayList<Pair<BlockPos, Vec3d>> saveAdd(ArrayList<Pair<BlockPos, Vec3d>> list, BlockPos blockPos, Vec3d openPos) {
        for (Pair<BlockPos, Vec3d> pair : list) {
            if (pair.getLeft().equals(blockPos)) {
                list.remove(pair);
                break;
            }
        }
        list.add(new Pair(blockPos, openPos));
        return list;
    }

    public static int stacksRequired(Collection<Integer> amounts) {
        //Calculates how many slots are required for the set of item amounts
        int stacks = 0;
        for (int amount : amounts) {
            if (amount == 0) continue;
            stacks += Math.ceil((float) amount / 64f);
        }
        return stacks;
    }

    public static ArrayList<Integer> getAvailableSlots(HashMap<Item, ArrayList<Pair<BlockPos, Vec3d>>> materials) {
        ArrayList<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) {
            if (mc.player.getInventory().getStack(slot).isEmpty()) {
                slots.add(slot);
                continue;
            }
            Item item = mc.player.getInventory().getStack(slot).getItem();
            if (materials.containsKey(item)) {
                slots.add(slot);
            }
        }
        return slots;
    }

    public static Pair<ArrayList<Integer>, HashMap<Item, Integer>> getInvInformation(HashMap<Item, Integer> requiredItems, ArrayList<Integer> availableSlots) {
        // Carried amounts per required item - EVERY stack counts fully. The old
        // modulus rule ignored partial stacks smaller than required%64, so the
        // refill re-withdrew materials already carried in fragments (slot
        // pressure -> full-at-withdrawal loops that looked like emergency
        // refills). The dumpSlots half of the pair is dead (no caller reads it)
        // and the deposit side now lives in RestockManager's filtered dump.
        HashMap<Item, Integer> materialInInv = new HashMap<>();
        for (int slot : availableSlots) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (!requiredItems.containsKey(item)) continue;
            materialInInv.merge(item, stack.getCount(), Integer::sum);
        }
        return new Pair(new ArrayList<>(), materialInInv);
    }

    public static File getMinecraftDirectory() {
        return FabricLoader.getInstance().getGameDir().toFile();
    }

    public static boolean createFolders(File mapFolder) {
        File finishedMapFolder = new File(mapFolder.getAbsolutePath() + File.separator + "_finished_maps");
        File configFolder = new File(mapFolder.getAbsolutePath() + File.separator + "_configs");
        if (!mapFolder.exists()) {
            if (mapFolder.mkdir()) {
                ChatUtils.info("Created color-suppressor folder in the Minecraft directory.");
            } else {
                ChatUtils.warning("Failed to create color-suppressor folder in the Minecraft directory. Try to enable customFolderPath and enter a path.");
                return false;
            }
        }
        if (!finishedMapFolder.exists()) {
            if (!finishedMapFolder.mkdir()) {
                ChatUtils.warning("Failed to create finished-map folder in the color-suppressor folder");
                return false;
            }
        }
        if (!configFolder.exists()) {
            if (!configFolder.mkdir()) {
                ChatUtils.warning("Failed to create config folder in the color-suppressor folder");
                return false;
            }
        }
        return true;
    }

    public static int getIntervalStart(int pos) {
        //Get top left corner of the map area for one dimension
        return (int) Math.floor((float) (pos + 64) / 128f) * 128 - 64;
    }

    // --- Colour-suppression map verification ---
    // The server owns the map render; the client receives every pixel update while holding
    // the map, so the live MapState is ground truth for what actually got recorded.

    /** Live MapState of the held filled map (main hand, else offhand), or null if not holding one. */
    public static MapState getHeldMapState() {
        if (mc.player == null || mc.world == null) return null;
        ItemStack stack = mc.player.getMainHandStack();
        if (!(stack.getItem() instanceof FilledMapItem)) stack = mc.player.getOffHandStack();
        if (!(stack.getItem() instanceof FilledMapItem)) return null;
        return FilledMapItem.getMapState(stack, mc.world);
    }

    public static void setForwardPressed(boolean pressed) {
        mc.options.forwardKey.setPressed(pressed);
        Input.setKeyState(mc.options.forwardKey, pressed);
    }

    public static void setSneakPressed(boolean pressed) {
        mc.options.sneakKey.setPressed(pressed);
        Input.setKeyState(mc.options.sneakKey, pressed);
    }

    public static void setJumpPressed(boolean pressed) {
        mc.options.jumpKey.setPressed(pressed);
        Input.setKeyState(mc.options.jumpKey, pressed);
    }

    public static int findHighestFreeSlot(InventoryS2CPacket packet) {
        for (int i = packet.contents().size() - 1; i > packet.contents().size() - 1 - 36; i--) {
            ItemStack stack = packet.contents().get(i);
            if (stack.isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** Swaps a main-inventory stack (fromSlot 9-35) INTO hotbar slot toSlot (0-8).
     *  Contract: toSlot MUST be a hotbar index - setSelectedSlot throws for anything
     *  else (live 2026-10-21 02:27 crash: a beginBatch tool move-out passed a
     *  main-inventory index here). Guarded no-op instead of a client crash. */
    public static void performSwap(int fromSlot, int toSlot) {
        if (toSlot < 0 || toSlot > 8) {
            System.err.println("[color-suppressor] performSwap blocked: toSlot " + toSlot + " is not a hotbar slot (0-8)");
            return;
        }
        mc.player.getInventory().setSelectedSlot(toSlot);

        IClientPlayerInteractionManager cim =
            (IClientPlayerInteractionManager) mc.interactionManager;

        cim.clickSlot(
            mc.player.currentScreenHandler.syncId,
            fromSlot,
            toSlot,
            SlotActionType.SWAP,
            mc.player
        );
    }

    /** Reverse direction: park a hotbar stack (0-8) into a free main-inventory slot
     *  (9-35) WITHOUT touching the selected slot. SWAP's button IS the hotbar index,
     *  so this must NOT setSelectedSlot - that call only accepts hotbar indices and
     *  the destination here is main inventory by design. */
    public static void swapHotbarToInventory(int hotbarSlot, int invSlot) {
        if (hotbarSlot < 0 || hotbarSlot > 8 || invSlot < 9 || invSlot > 35) {
            System.err.println("[color-suppressor] swapHotbarToInventory blocked: " + hotbarSlot + " -> " + invSlot);
            return;
        }
        IClientPlayerInteractionManager cim =
            (IClientPlayerInteractionManager) mc.interactionManager;

        cim.clickSlot(
            mc.player.currentScreenHandler.syncId,
            invSlot,
            hotbarSlot,
            SlotActionType.SWAP,
            mc.player
        );
    }

    /** Swap the OFFHAND stack with an inventory slot. In SWAP semantics button 40
     *  IS the offhand. handlerSlot is a PLAYER-screen handler id: main inventory
     *  9-35 (identical to inventory indices) or hotbar h as 36+h. Runs with no
     *  container open (prep/staging) - the chest screen has no offhand slot. */
    public static void swapOffhand(int handlerSlot) {
        IClientPlayerInteractionManager cim =
            (IClientPlayerInteractionManager) mc.interactionManager;

        cim.clickSlot(
            mc.player.currentScreenHandler.syncId,
            handlerSlot,
            40,
            SlotActionType.SWAP,
            mc.player
        );
    }

    public static HashMap<Integer, Pair<Block, Integer>> getBlockPalette(NbtList paletteList) {
        HashMap<Integer, Pair<Block, Integer>> blockPaletteDict = new HashMap<>();
        for (int i = 0; i < paletteList.size(); i++) {
            Optional<NbtCompound> block = paletteList.getCompound(i);
            if (block.isEmpty()) continue;

            // Structure-format palette key: old writer uses "Name", the updated
            // PNG-to-NBT site writes "id" (2026-11-05, imagen.nbt verified). The
            // new style may carry inline state ("id": "x:y[props]") - strip it.
            Optional<String> blockName = block.get().getString("Name");
            if (blockName.isEmpty()) blockName = block.get().getString("id");
            if (blockName.isEmpty()) continue;
            String cleanName = blockName.get().split("\\[", 2)[0];

            blockPaletteDict.put(i, new Pair(Registries.BLOCK.get(Identifier.of(cleanName)), 0));
        }
        return blockPaletteDict;
    }

    public static void getOneItem(int sourceSlot, boolean avoidFirstHotBar, ArrayList<Integer> availableSlots,
                                  ArrayList<Integer> availableHotBarSlots, InventoryS2CPacket packet) {
        int targetSlot = availableHotBarSlots.get(0);
        if (avoidFirstHotBar) {
            targetSlot = availableSlots.get(0);
            if (availableSlots.get(0) == availableHotBarSlots.get(0)) {
                targetSlot = availableSlots.get(1);
            }
        }
        if (targetSlot < 9) {
            targetSlot += 27;
        } else {
            targetSlot -= 9;
        }
        targetSlot = packet.contents().size() - 36 + targetSlot;
        mc.interactionManager.clickSlot(packet.syncId(), sourceSlot, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(packet.syncId(), targetSlot, 1, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(packet.syncId(), sourceSlot, 0, SlotActionType.PICKUP, mc.player);
    }

    public static File getNextMapFile(File mapFolder, ArrayList<File> startedFiles, boolean areMoved) {
        File[] files = mapFolder.listFiles();
        if (files == null) return null;
        Arrays.sort(files, Comparator
            .comparingInt((File f) -> f.getName().length()) // sort by name length
            .thenComparing(File::getName));                // then sort alphabetically

        for (File file : files) {
            // Only check if file was used if not moving to finished folder
            // since they are not in the map folder in that case
            if ((!startedFiles.contains(file) || areMoved) &&
                file.isFile() && file.getName().toLowerCase().endsWith(".nbt")) {
                startedFiles.add(file);
                return file;
            }
        }
        return null;
    }

    public static Direction getInteractionSide(BlockPos blockPos) {
        double minDistance = Double.MAX_VALUE;
        Direction bestSide = Direction.UP;
        for (Direction side : Direction.values()) {
            double neighbourDistance = mc.player.getEyePos().distanceTo(blockPos.offset(side).toCenterPos());
            if (neighbourDistance < minDistance) {
                minDistance = neighbourDistance;
                bestSide = side;
            }
        }
        return bestSide;
    }

    public static boolean isInInterval(Pair<Integer, Integer> interval, int number) {
        return number >= interval.getLeft() && number <= interval.getRight() && number >= 0;
    }

    @EventHandler
    public void onGameLeft(GameLeftEvent event) {
        nextInteractID = 2;
    }

    @EventHandler(priority = EventPriority.HIGHEST - 1)
    private static void onRecievePacket(PacketEvent.Receive event) {
        if (event.packet instanceof PlayerInteractItemC2SPacket packet) {
            nextInteractID = packet.getSequence() + 1;
        }

        if (event.packet instanceof PlayerInteractBlockC2SPacket packet) {
            nextInteractID = packet.getSequence() + 1;
        }
    }
}
