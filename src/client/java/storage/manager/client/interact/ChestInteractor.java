package storage.manager.client.interact;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import storage.manager.client.storage.StorageIndex;

import java.util.ArrayList;
import java.util.List;

/**
 * Opens/reads/closes a chest-like container using the same client-interaction
 * primitives the vanilla GUI uses (interactBlock + shift-click quick-move).
 * Baritone only handles getting the player next to the block - everything here
 * is plain vanilla client networking.
 */
public class ChestInteractor {

    /** Every vanilla container screen appends the player's own 36 inventory slots after the container's. */
    private static final int PLAYER_INVENTORY_SLOTS = 36;

    private static Minecraft client() {
        return Minecraft.getInstance();
    }

    public void open(BlockPos pos) {
        LocalPlayer player = client().player;
        if (player == null || client().gameMode == null) {
            return;
        }
        BlockHitResult hitResult = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        client().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
    }

    public boolean isOpen() {
        LocalPlayer player = client().player;
        return player != null && player.containerMenu != player.inventoryMenu;
    }

    public int containerSlotCount() {
        LocalPlayer player = client().player;
        if (player == null || !isOpen()) {
            return 0;
        }
        return Math.max(0, player.containerMenu.slots.size() - PLAYER_INVENTORY_SLOTS);
    }

    public List<StorageIndex.SlotEntry> snapshotContainerSlots() {
        List<StorageIndex.SlotEntry> result = new ArrayList<>();
        LocalPlayer player = client().player;
        if (player == null || !isOpen()) {
            return result;
        }
        AbstractContainerMenu menu = player.containerMenu;
        int count = containerSlotCount();
        for (int i = 0; i < count; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty()) {
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                result.add(new StorageIndex.SlotEntry(i, id, stack.getCount()));
            }
        }
        return result;
    }

    /** Finds the container slot index currently holding at least one of `itemId`, or -1. */
    public int findContainerSlot(String itemId) {
        LocalPlayer player = client().player;
        if (player == null || !isOpen()) {
            return -1;
        }
        AbstractContainerMenu menu = player.containerMenu;
        int count = containerSlotCount();
        for (int i = 0; i < count; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) {
                return i;
            }
        }
        return -1;
    }

    /** How many empty player-inventory slots are available right now, while a container is open. */
    public int countFreePlayerSlots() {
        LocalPlayer player = client().player;
        if (player == null || !isOpen()) {
            return 0;
        }
        AbstractContainerMenu menu = player.containerMenu;
        int containerCount = containerSlotCount();
        int free = 0;
        for (int i = containerCount; i < menu.slots.size(); i++) {
            if (menu.getSlot(i).getItem().isEmpty()) {
                free++;
            }
        }
        return free;
    }

    /** Finds a player-inventory-side slot (index >= containerSlotCount()) holding this item. */
    public int findPlayerSlotWithItem(String itemId) {
        LocalPlayer player = client().player;
        if (player == null || !isOpen()) {
            return -1;
        }
        AbstractContainerMenu menu = player.containerMenu;
        int containerCount = containerSlotCount();
        for (int i = containerCount; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) {
                return i;
            }
        }
        return -1;
    }

    /** Shift-clicks a slot, moving its whole stack to the other side of the open container. */
    public void quickMove(int slotIndex) {
        LocalPlayer player = client().player;
        if (player == null || !isOpen() || client().gameMode == null) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        client().gameMode.handleInventoryMouseClick(menu.containerId, slotIndex, 0, ClickType.QUICK_MOVE, player);
    }

    public void close() {
        LocalPlayer player = client().player;
        if (player != null && isOpen()) {
            player.closeContainer();
        }
    }
}
