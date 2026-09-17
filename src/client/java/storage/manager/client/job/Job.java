package storage.manager.client.job;

import net.minecraft.core.BlockPos;

public class Job {
    public enum Type {
        SCAN_REGION, SORT_INPUT, WITHDRAW, RANDOMIZE, WITHDRAW_SLOT, DUMP_INVENTORY, CRAFT,
        GATHER, EQUIP_TOOLS, STOW_TOOLS, KIT
    }

    public final Type type;
    public final String itemId;
    public final int count;
    /** WITHDRAW_SLOT only: the exact chest and slot the request came from. */
    public final BlockPos sourceChest;
    public final int sourceSlot;

    private Job(Type type, String itemId, int count) {
        this(type, itemId, count, null, -1);
    }

    private Job(Type type, String itemId, int count, BlockPos sourceChest, int sourceSlot) {
        this.type = type;
        this.itemId = itemId;
        this.count = count;
        this.sourceChest = sourceChest;
        this.sourceSlot = sourceSlot;
    }

    public static Job scanRegion() {
        return new Job(Type.SCAN_REGION, null, 0);
    }

    public static Job sortInput() {
        return new Job(Type.SORT_INPUT, null, 0);
    }

    public static Job withdraw(String itemId, int count) {
        return new Job(Type.WITHDRAW, itemId, count);
    }

    public static Job randomize() {
        return new Job(Type.RANDOMIZE, null, 0);
    }

    /** Requests that the bot craft {@code count} worth of {@code itemId} from a known recipe. */
    public static Job craft(String itemId, int count) {
        return new Job(Type.CRAFT, itemId, count);
    }

    /**
     * Mines until {@code count} of {@code itemId} have been picked up, putting everything away each
     * time the inventory fills. {@code itemId} may also name a block, e.g. an ore, in which case
     * what it drops is gathered.
     */
    public static Job gather(String itemId, int count) {
        return new Job(Type.GATHER, itemId, count);
    }

    /** Takes the best pickaxe, axe and shovel storage has and keeps them out of every deposit. */
    public static Job equipTools() {
        return new Job(Type.EQUIP_TOOLS, null, 0);
    }

    /**
     * Debug: gets the bot a diamond pickaxe, axe, shovel, sword and hoe and equips them - withdrawn,
     * crafted, or crafted from materials it gathers first.
     */
    public static Job kit() {
        return new Job(Type.KIT, null, 0);
    }

    /** Unequips every tool and puts them, with anything else the bot carries, back into storage. */
    public static Job stowTools() {
        return new Job(Type.STOW_TOOLS, null, 0);
    }

    /**
     * Unloads whatever the bot is carrying into the nearest chest with room. Queued by the stop
     * button rather than the UI directly - stopping mid-job usually leaves items in the inventory,
     * and leaving them there would strand them until the next job happened to want that item.
     */
    public static Job dumpInventory() {
        return new Job(Type.DUMP_INVENTORY, null, 0);
    }

    /**
     * Fetches one specific stack rather than "any of this item" - used by the chest view's
     * drag-to-request, where the user picked a particular slot they could see.
     */
    public static Job withdrawSlot(BlockPos chest, int slot, String itemId, int count) {
        return new Job(Type.WITHDRAW_SLOT, itemId, count, chest, slot);
    }

    @Override
    public String toString() {
        return switch (type) {
            case SCAN_REGION -> "SCAN_REGION";
            case SORT_INPUT -> "SORT_INPUT";
            case WITHDRAW -> "WITHDRAW " + count + "x " + itemId;
            case RANDOMIZE -> "RANDOMIZE";
            case DUMP_INVENTORY -> "DUMP inventory into nearest chest";
            case WITHDRAW_SLOT -> "WITHDRAW " + itemId + " from "
                    + sourceChest.getX() + "," + sourceChest.getY() + "," + sourceChest.getZ()
                    + " slot " + sourceSlot;
            case CRAFT -> "CRAFT " + count + "x " + itemId;
            case GATHER -> "GATHER " + count + "x " + itemId;
            case EQUIP_TOOLS -> "EQUIP tools";
            case STOW_TOOLS -> "STOW tools";
            case KIT -> "KIT diamond tools";
        };
    }
}
