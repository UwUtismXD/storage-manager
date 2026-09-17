package storage.manager.client.mixin;

import net.fabricmc.loader.api.FabricLoader;
import storage.manager.StorageManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a "Storage Manager v&lt;version&gt;" label to the top-right corner of the title screen,
 * mirroring the small addon-credit line Meteor Client itself renders there.
 *
 * Implemented as a widget added in {@code init} (rather than a manual draw call in the render
 * method) so the same code works across Minecraft versions whose title-screen render pipeline
 * differs - widget layout/rendering is unaffected by that.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void storageManager$onInit(CallbackInfo ci) {
        String version = FabricLoader.getInstance()
            .getModContainer(StorageManager.MOD_ID)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("dev");

        Component label = Component.literal("Storage Manager v" + version).withStyle(ChatFormatting.GRAY);
        StringWidget widget = new StringWidget(label, this.font);
        // Top-left, deliberately not the top-right corner Meteor Client's own addon-credits
        // list occupies - avoids drawing on top of it when both are loaded.
        widget.setPosition(3, 3);

        this.addRenderableWidget(widget);
    }
}
