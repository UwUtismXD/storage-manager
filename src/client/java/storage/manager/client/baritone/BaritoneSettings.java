package storage.manager.client.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.utils.SettingsUtil;

import net.minecraft.client.Minecraft;

import storage.manager.client.ChatFeedback;
import storage.manager.client.web.WebServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Exposes every Baritone setting to the web UI, reading and writing through the same
 * {@link SettingsUtil} paths as the in-game {@code #set} command so values round-trip in the
 * exact text format Baritone's own parser accepts, and changes persist to {@code settings.txt}.
 *
 * <p>Every call hops onto the client tick thread: pathing reads these fields every tick, and
 * swapping a value out from under it mid-calculation is exactly the race the web tier avoids.
 */
public class BaritoneSettings implements WebServer.BaritoneSettingsView {

    private static final long TICK_TIMEOUT_MILLIS = 3000L;

    @Override
    public List<WebServer.BaritoneSetting> list() {
        return onTickThread(() -> {
            List<WebServer.BaritoneSetting> result = new ArrayList<>();
            for (Settings.Setting<?> setting : BaritoneAPI.getSettings().allSettings) {
                result.add(describe(setting));
            }
            return result;
        });
    }

    @Override
    public WebServer.BaritoneSetting set(String name, String value) {
        return onTickThread(() -> {
            Settings settings = BaritoneAPI.getSettings();
            Settings.Setting<?> setting = editable(settings, name);
            try {
                // parseAndApply looks the name up in byLowerName verbatim, so it must be lowercased.
                SettingsUtil.parseAndApply(settings, setting.getName().toLowerCase(Locale.ROOT), value);
            } catch (Exception e) {
                // parseAndApply wraps parser failures in an IllegalStateException whose cause
                // carries the useful part ("Unknown block minecraft:stonee", NumberFormat, ...).
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new IllegalArgumentException("invalid value for " + setting.getName() + ": " + cause.getMessage());
            }
            SettingsUtil.save(settings);
            WebServer.BaritoneSetting updated = describe(setting);
            ChatFeedback.info("Set Baritone setting %s to %s.", updated.name(), updated.value());
            return updated;
        });
    }

    @Override
    public WebServer.BaritoneSetting reset(String name) {
        return onTickThread(() -> {
            Settings settings = BaritoneAPI.getSettings();
            Settings.Setting<?> setting = editable(settings, name);
            setting.reset();
            SettingsUtil.save(settings);
            WebServer.BaritoneSetting updated = describe(setting);
            ChatFeedback.info("Reset Baritone setting %s to its default (%s).", updated.name(), updated.value());
            return updated;
        });
    }

    private static Settings.Setting<?> editable(Settings settings, String name) {
        Settings.Setting<?> setting = settings.byLowerName.get(name.toLowerCase(Locale.ROOT));
        if (setting == null) {
            throw new IllegalArgumentException("unknown setting " + name);
        }
        if (setting.isJavaOnly()) {
            throw new IllegalArgumentException(setting.getName() + " can only be changed through the API");
        }
        return setting;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static WebServer.BaritoneSetting describe(Settings.Setting<?> setting) {
        String type;
        try {
            type = SettingsUtil.settingTypeToString((Settings.Setting) setting);
        } catch (Exception e) {
            // Java-only settings (callbacks, loggers) have no registered parser to name them.
            type = setting.getType().getTypeName();
        }
        if (setting.isJavaOnly()) {
            return new WebServer.BaritoneSetting(setting.getName(), type, null, null, false, false);
        }
        String value = SettingsUtil.settingValueToString((Settings.Setting) setting);
        String defaultValue = SettingsUtil.settingDefaultToString((Settings.Setting) setting);
        return new WebServer.BaritoneSetting(setting.getName(), type, value, defaultValue,
                !value.equals(defaultValue), true);
    }

    /**
     * Runs {@code task} on the client thread and waits for it. {@link IllegalArgumentException}s
     * are the caller's validation failures and propagate unchanged; anything else (timeout, game
     * shutting down) surfaces as {@link IllegalStateException}.
     */
    private static <T> T onTickThread(Supplier<T> task) {
        try {
            return Minecraft.getInstance().submit(task).get(TICK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IllegalArgumentException invalid) {
                throw invalid;
            }
            throw new IllegalStateException("Baritone settings unavailable: " + e.getCause(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for the client thread", e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("client thread did not respond in time", e);
        }
    }
}
