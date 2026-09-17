package storage.manager.client.meteor;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;

/**
 * The in-game control panel. Toggling the module (or pressing its keybind) opens the panel as its
 * own screen and switches straight back off - there's no background behaviour to keep running,
 * since the bot itself is driven by the mod's client tick handler either way.
 *
 * <p>The same controls are also returned from {@link #getWidget} so they show up inline when the
 * module is expanded in Meteor's ClickGUI.
 */
public class StorageManagerModule extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<String> item = sgGeneral.add(new StringSetting.Builder()
        .name("item")
        .description("Item id the withdraw, craft and gather buttons act on, e.g. minecraft:stick.")
        .defaultValue("minecraft:stick")
        .build()
    );

    public final Setting<Integer> count = sgGeneral.add(new IntSetting.Builder()
        .name("count")
        .description("How many of that item to withdraw, craft or gather.")
        .defaultValue(64)
        .min(1)
        .sliderMax(640)
        .build()
    );

    public final Setting<Boolean> render = sgGeneral.add(new BoolSetting.Builder()
        .name("render")
        .description("Outline the input chest, output chest, crafting table and storage region in the world.")
        .defaultValue(false)
        .build()
    );

    final StorageManagerOverlay overlay = new StorageManagerOverlay(this);

    public StorageManagerModule() {
        super(StorageManagerAddon.CATEGORY, "storage-manager",
                "Set up and drive the storage bot without leaving the game.");
        // The panel reports status and edits saved setup, both of which are worth reaching from
        // the title screen. Without this, toggling out of a world would leave the module stuck on.
        runInMainMenu = true;
    }

    @Override
    public void onActivate() {
        mc.setScreen(new StorageManagerScreen(GuiThemes.get(), this));
        toggle();
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        return new StorageManagerPanel(this).build(theme);
    }
}
