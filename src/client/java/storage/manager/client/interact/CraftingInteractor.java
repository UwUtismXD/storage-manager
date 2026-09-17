package storage.manager.client.interact;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Opens/uses a crafting table using the same client-interaction primitives {@link ChestInteractor}
 * uses for chests - a plain {@code useItemOn} to open, and {@code handleInventoryMouseClick} for
 * every slot manipulation. Kept as its own class rather than sharing {@link ChestInteractor}
 * because a {@link CraftingMenu} has a fixed, known shape (result + 3x3 grid) that the ingredient
 * placement logic depends on, unlike a chest's variable slot count.
 */
public class CraftingInteractor {

    /** {@link CraftingMenu} slot 0 is always the craft result. */
    private static final int RESULT_SLOT = 0;
    /** Slots 1-9 are the 3x3 grid, row-major starting top-left. */
    private static final int GRID_START = 1;
    private static final int GRID_SIZE = 9;
    /** Every vanilla container screen appends the player's own 36 inventory slots after the container's. */
    private static final int PLAYER_INVENTORY_SLOTS = 36;

    private static Minecraft client() {
        return Minecraft.getInstance();
    }

    /** The crafting-grid slot for a recipe cell at ({@code x}, {@code y}), each 0-2. */
    public static int gridSlot(int x, int y) {
        return GRID_START + y * 3 + x;
    }

    public void open(BlockPos pos) {
        LocalPlayer player = client().player;
        if (player == null || client().gameMode == null) {
            return;
        }
        BlockHitResult hitResult = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        client().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
    }

    /**
     * Stricter than {@link ChestInteractor#isOpen()}'s "not the inventory menu" check - a stale
     * container left open by an interrupted previous visit (chest or otherwise) must not be
     * mistaken for a freshly opened crafting table.
     */
    public boolean isOpen() {
        LocalPlayer player = client().player;
        return player != null && player.containerMenu instanceof CraftingMenu;
    }

    public void close() {
        LocalPlayer player = client().player;
        if (player != null && isOpen()) {
            player.closeContainer();
        }
    }

    private int containerSlotCount() {
        LocalPlayer player = client().player;
        if (player == null || !isOpen()) {
            return 0;
        }
        return Math.max(0, player.containerMenu.slots.size() - PLAYER_INVENTORY_SLOTS);
    }

    /** Finds a player-inventory-side slot (outside the result+grid) holding this item. */
    private int findPlayerSlotWithItem(String itemId) {
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

    /**
     * Places exactly one {@code itemId} into the given crafting-grid slot (see {@link #gridSlot}),
     * leaving the rest of its source stack in the player's inventory. Returns false if the bot
     * isn't carrying any of the item.
     *
     * <p>Three clicks, the standard vanilla sequence for moving a single item into a chosen slot:
     * pick up the whole source stack (left-click {@code PICKUP}), place one item into the target
     * (right-click {@code PICKUP} on an empty/matching slot moves exactly one), then return
     * whatever's left in hand to the now-empty source slot (left-click {@code PICKUP} again).
     */
    public boolean placeIngredient(int gridSlot, String itemId) {
        LocalPlayer player = client().player;
        if (player == null || !isOpen() || client().gameMode == null) {
            return false;
        }
        int sourceSlot = findPlayerSlotWithItem(itemId);
        if (sourceSlot < 0) {
            return false;
        }
        int containerId = player.containerMenu.containerId;
        client().gameMode.handleInventoryMouseClick(containerId, sourceSlot, 0, ClickType.PICKUP, player);
        client().gameMode.handleInventoryMouseClick(containerId, gridSlot, 1, ClickType.PICKUP, player);
        client().gameMode.handleInventoryMouseClick(containerId, sourceSlot, 0, ClickType.PICKUP, player);
        return true;
    }

    /** The item id in a grid cell (see {@link #gridSlot}), or null if empty/invalid. */
    public String gridItemAt(int gridSlot) {
        LocalPlayer player = client().player;
        if (player == null || !isOpen() || gridSlot < GRID_START || gridSlot >= GRID_START + GRID_SIZE) {
            return null;
        }
        ItemStack stack = player.containerMenu.getSlot(gridSlot).getItem();
        return stack.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** Quick-moves any leftover items out of the 3x3 grid, e.g. before placing a fresh set. */
    public void clearGrid() {
        LocalPlayer player = client().player;
        if (player == null || !isOpen() || client().gameMode == null) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        for (int slot = GRID_START; slot < GRID_START + GRID_SIZE; slot++) {
            if (!menu.getSlot(slot).getItem().isEmpty()) {
                client().gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.QUICK_MOVE, player);
            }
        }
    }

    public boolean hasResult() {
        LocalPlayer player = client().player;
        return player != null && isOpen() && !player.containerMenu.getSlot(RESULT_SLOT).getItem().isEmpty();
    }

    /**
     * Shift-clicks the result slot. Vanilla repeats the craft from the grid's ingredients as many
     * times as they allow within this single click, so one call drains a whole batch rather than
     * needing to be called once per craft.
     */
    public void collectResult() {
        LocalPlayer player = client().player;
        if (player == null || !isOpen() || client().gameMode == null) {
            return;
        }
        client().gameMode.handleInventoryMouseClick(
                player.containerMenu.containerId, RESULT_SLOT, 0, ClickType.QUICK_MOVE, player);
    }
}
