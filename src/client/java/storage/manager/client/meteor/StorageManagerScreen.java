package storage.manager.client.meteor;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;

/**
 * The panel as a standalone window, opened by toggling the module. Unlike the ClickGUI copy this
 * one is ticked, so the status line follows the bot while it works.
 */
public class StorageManagerScreen extends WindowScreen {

    private final StorageManagerModule module;
    private final StorageManagerPanel panel;

    public StorageManagerScreen(GuiTheme theme, StorageManagerModule module) {
        super(theme, "Storage Manager");

        this.module = module;
        this.panel = new StorageManagerPanel(module);
    }

    @Override
    public void initWidgets() {
        add(theme.settings(module.settings)).expandX();
        add(theme.horizontalSeparator()).expandX();
        add(panel.build(theme)).expandX();
    }

    @Override
    public void tick() {
        super.tick();
        panel.refresh();
    }
}
