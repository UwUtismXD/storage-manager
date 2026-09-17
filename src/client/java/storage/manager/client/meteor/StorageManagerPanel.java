package storage.manager.client.meteor;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;

import storage.manager.client.StorageManagerClient;
import storage.manager.client.job.Job;
import storage.manager.client.job.JobExecutor;
import storage.manager.client.job.JobQueue;
import storage.manager.client.storage.StorageIndex;

/**
 * The buttons themselves, built once per screen or ClickGUI panel. Everything it touches is
 * looked up through {@link StorageManagerClient} at click time rather than held onto, so a panel
 * built before the mod finished starting up still works once it has.
 *
 * <p>Nothing here keeps its own copy of bot state: the labels are filled from the executor and
 * index on {@link #refresh()}, which the screen calls every tick and the buttons call after acting.
 */
class StorageManagerPanel {

    private final StorageManagerModule module;

    private WLabel status;
    private WLabel warning;
    private WLabel picking;
    private WLabel inputChest;
    private WLabel outputChest;
    private WLabel craftingTable;
    private WLabel furnace;
    private WLabel region;
    private WButton wander;

    StorageManagerPanel(StorageManagerModule module) {
        this.module = module;
    }

    WWidget build(GuiTheme theme) {
        WVerticalList list = theme.verticalList();

        WTable readout = list.add(theme.table()).expandX().widget();
        status = readoutRow(theme, readout, "Status:");
        warning = readoutRow(theme, readout, "Warning:");
        picking = readoutRow(theme, readout, "Picking:");

        list.add(theme.horizontalSeparator()).expandX();
        list.add(theme.label("Jobs", true));

        WTable jobs = list.add(theme.table()).expandX().widget();
        button(theme, jobs, "Scan region", () -> enqueue(Job.scanRegion()));
        button(theme, jobs, "Sort input", () -> enqueue(Job.sortInput()));
        button(theme, jobs, "Randomize", () -> enqueue(Job.randomize()));
        jobs.row();
        button(theme, jobs, "Withdraw", () -> enqueue(Job.withdraw(module.item.get(), module.count.get())));
        button(theme, jobs, "Craft", () -> enqueue(Job.craft(module.item.get(), module.count.get())));
        button(theme, jobs, "Stop", this::stop);
        jobs.row();
        button(theme, jobs, "Gather", () -> enqueue(Job.gather(module.item.get(), module.count.get())));
        button(theme, jobs, "Equip tools", () -> enqueue(Job.equipTools()));
        button(theme, jobs, "Stow tools", () -> enqueue(Job.stowTools()));
        jobs.row();
        button(theme, jobs, "Kit", () -> enqueue(Job.kit()));
        jobs.row();
        wander = jobs.add(theme.button("Wander")).expandX().minWidth(90).widget();
        wander.action = this::toggleWander;
        button(theme, jobs, "Open web UI", StorageManagerPanel::openWebUi);

        list.add(theme.horizontalSeparator()).expandX();
        list.add(theme.label("Setup - click, then right-click the block", true));

        WTable configured = list.add(theme.table()).expandX().widget();
        inputChest = readoutRow(theme, configured, "Input chest:");
        outputChest = readoutRow(theme, configured, "Output chest:");
        craftingTable = readoutRow(theme, configured, "Crafting table:");
        furnace = readoutRow(theme, configured, "Furnace:");
        region = readoutRow(theme, configured, "Region:");

        WTable setupTable = list.add(theme.table()).expandX().widget();
        button(theme, setupTable, "Input chest", () -> module.overlay.arm(StorageManagerOverlay.Pick.INPUT_CHEST));
        button(theme, setupTable, "Output chest", () -> module.overlay.arm(StorageManagerOverlay.Pick.OUTPUT_CHEST));
        button(theme, setupTable, "Crafting table", () -> module.overlay.arm(StorageManagerOverlay.Pick.CRAFTING_TABLE));
        setupTable.row();
        button(theme, setupTable, "Furnace", () -> module.overlay.arm(StorageManagerOverlay.Pick.FURNACE));
        button(theme, setupTable, "Region", () -> module.overlay.arm(StorageManagerOverlay.Pick.REGION_FIRST));
        button(theme, setupTable, "Cancel pick", () -> {
            module.overlay.cancel();
            refresh();
        });

        refresh();
        return list;
    }

    private static WLabel readoutRow(GuiTheme theme, WTable table, String name) {
        table.add(theme.label(name));
        WLabel value = table.add(theme.label("")).expandCellX().widget();
        table.row();
        return value;
    }

    private void button(GuiTheme theme, WTable table, String text, Runnable action) {
        WButton button = table.add(theme.button(text)).expandX().minWidth(90).widget();
        button.action = action;
    }

    /** Pulls every label back in line with the bot's actual state. Safe to call before startup. */
    void refresh() {
        JobExecutor executor = StorageManagerClient.executor();
        if (executor == null) {
            status.set("not started yet");
            warning.set("");
        } else {
            status.set(executor.getStatus());
            warning.set(executor.getLastWarning());
            wander.set("Wander: " + (executor.isWanderEnabled() ? "on" : "off"));
        }

        String pending = module.overlay.pendingLabel();
        picking.set(pending == null ? "-" : "right-click the " + pending);

        StorageIndex index = StorageManagerClient.index();
        if (index == null) {
            return;
        }
        inputChest.set(describe(index.getInputChest()));
        outputChest.set(describe(index.getOutputChest()));
        craftingTable.set(describe(index.getCraftingTable()));
        furnace.set(describe(index.getFurnace()));
        StorageIndex.Region box = index.getRegion();
        region.set(box.min == null || box.max == null ? "not set"
                : describe(box.min.toBlockPos()) + "  to  " + describe(box.max.toBlockPos()));
    }

    private static String describe(BlockPos pos) {
        return pos == null ? "not set" : StorageManagerOverlay.describe(pos);
    }

    private void enqueue(Job job) {
        JobQueue queue = StorageManagerClient.queue();
        if (queue == null) {
            module.error("Storage Manager hasn't finished starting up.");
            return;
        }
        queue.enqueue(job);
        module.info("Queued %s.", job);
        refresh();
    }

    private void stop() {
        JobExecutor executor = StorageManagerClient.executor();
        if (executor == null) {
            module.error("Storage Manager hasn't finished starting up.");
            return;
        }
        executor.requestStop();
        module.info("Stopping - the bot will put away anything it's carrying.");
        refresh();
    }

    private void toggleWander() {
        JobExecutor executor = StorageManagerClient.executor();
        if (executor == null) {
            module.error("Storage Manager hasn't finished starting up.");
            return;
        }
        executor.setWanderEnabled(!executor.isWanderEnabled());
        refresh();
    }

    private static void openWebUi() {
        Util.getPlatform().openUri("http://localhost:" + StorageManagerClient.WEB_PORT);
    }
}
