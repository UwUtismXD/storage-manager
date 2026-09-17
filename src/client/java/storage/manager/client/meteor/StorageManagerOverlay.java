package storage.manager.client.meteor;

import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import storage.manager.client.StorageManagerClient;
import storage.manager.client.storage.StorageIndex;

/**
 * The in-world half of the panel: picking setup blocks by right-clicking them, and drawing the
 * configured blocks and region. Subscribed to Meteor's event bus for the whole session rather than
 * tied to the module being active, since the module switches itself off as soon as the panel opens.
 */
public class StorageManagerOverlay {

    enum Pick {
        INPUT_CHEST("input chest"),
        OUTPUT_CHEST("output chest"),
        CRAFTING_TABLE("crafting table"),
        FURNACE("furnace"),
        REGION_FIRST("first region corner"),
        REGION_SECOND("opposite region corner");

        final String label;

        Pick(String label) {
            this.label = label;
        }
    }

    /**
     * Right-click repeats every few ticks while held. A pick that lands then shouldn't let the
     * repeat through - it would open the chest just picked, or set both region corners to one block.
     */
    private static final long REPEAT_GUARD_MILLIS = 300;

    private static final Color INPUT_SIDES = new Color(80, 220, 120, 45);
    private static final Color INPUT_LINES = new Color(80, 220, 120, 255);
    private static final Color OUTPUT_SIDES = new Color(255, 160, 60, 45);
    private static final Color OUTPUT_LINES = new Color(255, 160, 60, 255);
    private static final Color TABLE_SIDES = new Color(90, 160, 255, 45);
    private static final Color TABLE_LINES = new Color(90, 160, 255, 255);
    private static final Color FURNACE_SIDES = new Color(200, 90, 255, 45);
    private static final Color FURNACE_LINES = new Color(200, 90, 255, 255);
    private static final Color REGION_SIDES = new Color(255, 230, 90, 12);
    private static final Color REGION_LINES = new Color(255, 230, 90, 255);

    private final StorageManagerModule module;

    private Pick pending;
    private BlockPos regionCorner;
    private long lastPickMillis;

    StorageManagerOverlay(StorageManagerModule module) {
        this.module = module;
    }

    /** Waits for the next right-clicked block, closing whatever GUI the request came from. */
    void arm(Pick pick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || StorageManagerClient.index() == null) {
            module.error("Join a world before setting the %s.", pick.label);
            return;
        }
        pending = pick;
        regionCorner = null;
        mc.setScreen(null);
        module.info("Right-click the %s.", pick.label);
    }

    void cancel() {
        if (pending != null) {
            module.info("Stopped picking the %s.", pending.label);
        }
        pending = null;
        regionCorner = null;
    }

    /** What the next right-click will set, or null when nothing is being picked. */
    String pendingLabel() {
        return pending == null ? null : pending.label;
    }

    @EventHandler
    private void onInteractBlock(InteractBlockEvent event) {
        if (pending == null) {
            if (System.currentTimeMillis() - lastPickMillis < REPEAT_GUARD_MILLIS) {
                event.setCancelled(true);
            }
            return;
        }
        // Cancelled first, so whatever gets clicked - chest, door, button - isn't used as well.
        event.setCancelled(true);
        lastPickMillis = System.currentTimeMillis();

        StorageIndex index = StorageManagerClient.index();
        BlockPos pos = event.result.getBlockPos();
        if (index == null) {
            pending = null;
            return;
        }

        switch (pending) {
            case INPUT_CHEST -> index.setInputChest(pos);
            case OUTPUT_CHEST -> index.setOutputChest(pos);
            case CRAFTING_TABLE -> index.setCraftingTable(pos);
            case FURNACE -> index.setFurnace(pos);
            case REGION_FIRST -> {
                regionCorner = pos;
                pending = Pick.REGION_SECOND;
                module.info("First corner at %s - now right-click the opposite corner.", describe(pos));
                return;
            }
            case REGION_SECOND -> {
                BlockPos min = new BlockPos(
                        Math.min(regionCorner.getX(), pos.getX()),
                        Math.min(regionCorner.getY(), pos.getY()),
                        Math.min(regionCorner.getZ(), pos.getZ()));
                BlockPos max = new BlockPos(
                        Math.max(regionCorner.getX(), pos.getX()),
                        Math.max(regionCorner.getY(), pos.getY()),
                        Math.max(regionCorner.getZ(), pos.getZ()));
                index.setRegion(min, max);
                module.info("Region set to %s - %s.", describe(min), describe(max));
                pending = null;
                regionCorner = null;
                reopenPanel();
                return;
            }
        }
        module.info("Set the %s to %s.", pending.label, describe(pos));
        pending = null;
        reopenPanel();
    }

    private void reopenPanel() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new StorageManagerScreen(GuiThemes.get(), module));
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        StorageIndex index = StorageManagerClient.index();
        if (index == null) {
            return;
        }
        if (module.render.get()) {
            block(event, index.getInputChest(), INPUT_SIDES, INPUT_LINES);
            block(event, index.getOutputChest(), OUTPUT_SIDES, OUTPUT_LINES);
            block(event, index.getCraftingTable(), TABLE_SIDES, TABLE_LINES);
            block(event, index.getFurnace(), FURNACE_SIDES, FURNACE_LINES);

            StorageIndex.Region region = index.getRegion();
            if (region.min != null && region.max != null) {
                BlockPos min = region.min.toBlockPos();
                BlockPos max = region.max.toBlockPos();
                // Corners are inclusive block positions, so the far edge is one block further out.
                event.renderer.box(min.getX(), min.getY(), min.getZ(),
                        max.getX() + 1, max.getY() + 1, max.getZ() + 1,
                        REGION_SIDES, REGION_LINES, ShapeMode.Both, 0);
            }
        }
        // Shown regardless of the render setting - mid-pick, it's the only sign the click registered.
        block(event, regionCorner, REGION_SIDES, REGION_LINES);
    }

    private static void block(Render3DEvent event, BlockPos pos, Color sides, Color lines) {
        if (pos != null) {
            event.renderer.box(pos, sides, lines, ShapeMode.Both, 0);
        }
    }

    static String describe(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
