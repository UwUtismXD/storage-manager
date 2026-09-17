package storage.manager.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Prints mod feedback into the in-game chat. When Meteor is installed its addon swaps in the
 * module's own {@code info}, so messages carry the same prefix and styling as the panel's; without
 * Meteor they fall back to a plain client-side chat line. Kept free of Meteor imports because the
 * rest of the mod has to load without it.
 *
 * <p>Must be called on the client thread.
 */
public final class ChatFeedback {

    /** Same shape as Meteor's {@code Module.info}: a {@code %s} format plus its arguments. */
    @FunctionalInterface
    public interface Sink {
        void info(String format, Object... args);
    }

    private static volatile Sink sink = ChatFeedback::vanilla;

    private ChatFeedback() {}

    public static void setSink(Sink replacement) {
        sink = replacement;
    }

    public static void info(String format, Object... args) {
        sink.info(format, args);
    }

    private static void vanilla(String format, Object... args) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        // Client-side only: LocalPlayer's override adds the line to the chat HUD, nothing is sent.
        client.player.sendSystemMessage(Component.literal("[Storage Manager] " + String.format(format, args)));
    }
}
