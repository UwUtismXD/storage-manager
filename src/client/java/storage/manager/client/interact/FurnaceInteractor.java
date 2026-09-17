package storage.manager.client.interact;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Opens/uses a furnace with the same client-interaction primitives {@link CraftingInteractor}
 * uses. Accepts any {@link AbstractFurnaceMenu}, so a blast furnace or smoker works too - for the
 * recipes those blocks accept.
 *
 * <p>Items go in with explicit pickup/place clicks rather than shift-clicks: a quick-move picks
 * the slot for itself (logs are both smeltable and fuel, and would land in the input slot), and
 * it moves whole stacks when a job usually wants an exact count.
 */
public class FurnaceInteractor {

    public static final int INPUT_SLOT = 0;
    public static final int FUEL_SLOT = 1;
    public static final int RESULT_SLOT = 2;
    private static final int FURNACE_SLOTS = 3;

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

    /** True only for a furnace-type menu, so a stale chest or table screen isn't mistaken for one. */
    public boolean isOpen() {
        LocalPlayer player = client().player;
        return player != null && player.containerMenu instanceof AbstractFurnaceMenu;
    }

    public void close() {
        LocalPlayer player = client().player;
        if (player != null && isOpen()) {
            player.closeContainer();
        }
    }

    /** Whether the furnace is burning fuel right now, as last synced by the server. */
    public boolean isLit() {
        LocalPlayer player = client().player;
        return player != null && player.containerMenu instanceof AbstractFurnaceMenu furnace && furnace.isLit();
    }

    /** The item id in one of the furnace's own slots, or null if empty/not open. */
    public String itemAt(int slot) {
        ItemStack stack = stackAt(slot);
        return stack.isEmpty() ? null : idOf(stack);
    }

    public int countAt(int slot) {
        return stackAt(slot).getCount();
    }

    private ItemStack stackAt(int slot) {
        LocalPlayer player = client().player;
        if (player == null || !isOpen() || slot < 0 || slot >= FURNACE_SLOTS) {
            return ItemStack.EMPTY;
        }
        return player.containerMenu.getSlot(slot).getItem();
    }

    /** Total count of an item across the player-inventory side of the open furnace. */
    public int countPlayerItems(String itemId) {
        LocalPlayer player = client().player;
        if (player == null || !isOpen()) {
            return 0;
        }
        AbstractContainerMenu menu = player.containerMenu;
        int total = 0;
        for (int i = FURNACE_SLOTS; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && idOf(stack).equals(itemId)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int findPlayerSlotWithItem(String itemId) {
        LocalPlayer player = client().player;
        if (player == null || !isOpen()) {
            return -1;
        }
        AbstractContainerMenu menu = player.containerMenu;
        for (int i = FURNACE_SLOTS; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (!stack.isEmpty() && idOf(stack).equals(itemId)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Moves up to {@code amount} of {@code itemId} from the bot's inventory into {@code slot}
     * ({@link #INPUT_SLOT} or {@link #FUEL_SLOT}), taking from one inventory stack per call.
     * Returns how many actually went in - 0 when the slot holds something else or is full, or the
     * bot has none left.
     *
     * <p>When the whole source stack is wanted it's picked up and dropped in one go. Otherwise the
     * stack is picked up, one item is right-clicked in per wanted item (at most
     * {@code maxSingles} of them, to keep the packets per tick down), and the rest goes back.
     */
    public int place(int slot, String itemId, int amount, int maxSingles) {
        LocalPlayer player = client().player;
        if (player == null || !isOpen() || client().gameMode == null || amount <= 0) {
            return 0;
        }
        ItemStack target = stackAt(slot);
        if (!target.isEmpty() && !idOf(target).equals(itemId)) {
            return 0;
        }
        int sourceSlot = findPlayerSlotWithItem(itemId);
        if (sourceSlot < 0) {
            return 0;
        }
        AbstractContainerMenu menu = player.containerMenu;
        ItemStack source = menu.getSlot(sourceSlot).getItem();
        int room = source.getMaxStackSize() - target.getCount();
        int wanted = Math.min(amount, room);
        if (wanted <= 0) {
            return 0;
        }
        // Read before clicking - picking the stack up empties (or replaces) the slot's ItemStack.
        int sourceCount = source.getCount();
        int before = target.getCount();
        int containerId = menu.containerId;
        client().gameMode.handleInventoryMouseClick(containerId, sourceSlot, 0, ClickType.PICKUP, player);
        if (sourceCount <= wanted) {
            client().gameMode.handleInventoryMouseClick(containerId, slot, 0, ClickType.PICKUP, player);
        } else {
            for (int i = 0; i < Math.min(wanted, maxSingles); i++) {
                client().gameMode.handleInventoryMouseClick(containerId, slot, 1, ClickType.PICKUP, player);
            }
        }
        if (!menu.getCarried().isEmpty()) {
            client().gameMode.handleInventoryMouseClick(containerId, sourceSlot, 0, ClickType.PICKUP, player);
        }
        return stackAt(slot).getCount() - before;
    }

    /** Shift-clicks one furnace slot back into the bot's inventory. */
    public void takeOut(int slot) {
        LocalPlayer player = client().player;
        if (player == null || !isOpen() || client().gameMode == null || stackAt(slot).isEmpty()) {
            return;
        }
        client().gameMode.handleInventoryMouseClick(
                player.containerMenu.containerId, slot, 0, ClickType.QUICK_MOVE, player);
    }

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
}
