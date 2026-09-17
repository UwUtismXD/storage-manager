package storage.manager.client.interact;

import it.unimi.dsi.fastutil.objects.Object2IntMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import storage.manager.client.storage.StorageIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Opens/reads/closes a chest-like container using the same client-interaction
 * primitives the vanilla GUI uses (interactBlock + shift-click quick-move).
 * Baritone only handles getting the player next to the block - everything here
 * is plain vanilla client networking.
 */
public class ChestInteractor {

    /** Every vanilla container screen appends the player's own 36 inventory slots after the container's. */
    private static final int PLAYER_INVENTORY_SLOTS = 36;

    /**
     * Where those same 36 slots start in the player's <em>own</em> inventory menu, which lays out
     * 0-4 crafting, 5-8 armour, 9-35 main, 36-44 hotbar, 45 offhand. Used to ask what the bot is
     * carrying while no container is open - worn armour deliberately doesn't count.
     */
    private static final int INVENTORY_MENU_MAIN_START = 9;

    private static final List<DataComponentType<ItemEnchantments>> ENCHANTMENT_COMPONENTS =
            List.of(DataComponents.ENCHANTMENTS, DataComponents.STORED_ENCHANTMENTS);

    /**
     * Player-inventory indices (0-35) holding the bot's equipped tools. Every "what is the bot
     * carrying" read below skips them, so no deposit, dump or delivery ever picks a tool up as
     * cargo - one place to get it right rather than a check in every job.
     */
    private Supplier<Set<Integer>> keptSlots = Set::of;

    private static Minecraft client() {
        return Minecraft.getInstance();
    }

    public void setKeptSlots(Supplier<Set<Integer>> keptSlots) {
        this.keptSlots = keptSlots;
    }

    private static boolean isKept(LocalPlayer player, Slot slot, Set<Integer> kept) {
        return !kept.isEmpty() && slot.container == player.getInventory() && kept.contains(slot.getContainerSlot());
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
                result.add(describe(i, stack));
            }
        }
        return result;
    }

    /** Captures the parts of a stack the web UI shows: id, count, enchantments, name, durability. */
    private static StorageIndex.SlotEntry describe(int slotIndex, ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        StorageIndex.SlotEntry entry = new StorageIndex.SlotEntry(slotIndex, id, stack.getCount());
        entry.enchants = readEnchantments(stack);
        Component customName = stack.get(DataComponents.CUSTOM_NAME);
        if (customName != null) {
            entry.customName = customName.getString();
        }
        if (stack.isDamaged()) {
            entry.damage = stack.getDamageValue();
            entry.maxDamage = stack.getMaxDamage();
        }
        return entry;
    }

    /**
     * Enchantments as {@code "name level"} strings. STORED_ENCHANTMENTS covers enchanted books,
     * which keep theirs separately from the ones that actually apply to the item.
     */
    private static List<String> readEnchantments(ItemStack stack) {
        List<String> names = new ArrayList<>();
        for (DataComponentType<ItemEnchantments> type : ENCHANTMENT_COMPONENTS) {
            ItemEnchantments enchantments = stack.get(type);
            if (enchantments == null) {
                continue;
            }
            for (Object2IntMap.Entry<Holder<Enchantment>> enchant : enchantments.entrySet()) {
                String name = enchant.getKey().unwrapKey()
                        .map(key -> key.location().getPath())
                        .orElse("unknown");
                names.add(name + " " + enchant.getIntValue());
            }
        }
        return names.isEmpty() ? null : names;
    }

    /** The container-side item id at this slot, or null if it's empty or out of range. */
    public String containerItemAt(int slotIndex) {
        LocalPlayer player = client().player;
        if (player == null || !isOpen() || slotIndex < 0 || slotIndex >= containerSlotCount()) {
            return null;
        }
        ItemStack stack = player.containerMenu.getSlot(slotIndex).getItem();
        return stack.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
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

    /** Finds a player-inventory-side slot (index >= containerSlotCount()) holding this item, never an equipped tool. */
    public int findPlayerSlotWithItem(String itemId) {
        LocalPlayer player = client().player;
        if (player == null || !isOpen()) {
            return -1;
        }
        AbstractContainerMenu menu = player.containerMenu;
        Set<Integer> kept = keptSlots.get();
        int containerCount = containerSlotCount();
        for (int i = containerCount; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && !isKept(player, slot, kept)
                    && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) {
                return i;
            }
        }
        return -1;
    }

    /** The player-side slot index of the first non-empty slot in the open container, or -1. Skips equipped tools. */
    public int firstNonEmptyPlayerSlot() {
        LocalPlayer player = client().player;
        if (player == null || !isOpen()) {
            return -1;
        }
        AbstractContainerMenu menu = player.containerMenu;
        Set<Integer> kept = keptSlots.get();
        for (int i = containerSlotCount(); i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            if (!slot.getItem().isEmpty() && !isKept(player, slot, kept)) {
                return i;
            }
        }
        return -1;
    }

    /** The item id held in any slot of the open container, or null if it's empty or out of range. */
    public String itemAt(int slotIndex) {
        LocalPlayer player = client().player;
        if (player == null || !isOpen() || slotIndex < 0 || slotIndex >= player.containerMenu.slots.size()) {
            return null;
        }
        ItemStack stack = player.containerMenu.getSlot(slotIndex).getItem();
        return stack.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /**
     * How many separate player-side slots hold this item. Distinct from {@link #countPlayerItems},
     * which sums the items themselves - the shuffle accounts in whole stacks, since that's the unit
     * a quick-move actually shifts.
     */
    public int countPlayerStacks(String itemId) {
        LocalPlayer player = client().player;
        if (player == null || !isOpen()) {
            return 0;
        }
        AbstractContainerMenu menu = player.containerMenu;
        Set<Integer> kept = keptSlots.get();
        int stacks = 0;
        for (int i = containerSlotCount(); i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && !isKept(player, slot, kept)
                    && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) {
                stacks++;
            }
        }
        return stacks;
    }

    /**
     * Whether the bot is holding anything at all, read from its own inventory menu rather than an
     * open container - the stop button needs to answer this with nothing open. Equipped tools
     * aren't cargo, so a bot holding only those counts as empty-handed.
     */
    public boolean hasCarriedItems() {
        LocalPlayer player = client().player;
        if (player == null) {
            return false;
        }
        AbstractContainerMenu menu = player.inventoryMenu;
        Set<Integer> kept = keptSlots.get();
        int end = Math.min(menu.slots.size(), INVENTORY_MENU_MAIN_START + PLAYER_INVENTORY_SLOTS);
        for (int i = INVENTORY_MENU_MAIN_START; i < end; i++) {
            Slot slot = menu.getSlot(i);
            if (!slot.getItem().isEmpty() && !isKept(player, slot, kept)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Total count of an item across the player-inventory side of the open container.
     *
     * <p>Comparing this before and after a {@link #quickMove(int)} is the only reliable way to tell
     * "the chest is full" from "I'm still carrying more stacks of the same item" - checking merely
     * whether the bot still holds any of it confuses the two.
     */
    public int countPlayerItems(String itemId) {
        LocalPlayer player = client().player;
        if (player == null || !isOpen()) {
            return 0;
        }
        AbstractContainerMenu menu = player.containerMenu;
        Set<Integer> kept = keptSlots.get();
        int containerCount = containerSlotCount();
        int total = 0;
        for (int i = containerCount; i < menu.slots.size(); i++) {
            Slot slot = menu.getSlot(i);
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && !isKept(player, slot, kept)
                    && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) {
                total += stack.getCount();
            }
        }
        return total;
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
