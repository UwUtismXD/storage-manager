package storage.manager.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import storage.manager.client.job.JobExecutor;
import storage.manager.client.job.JobQueue;
import storage.manager.client.storage.StorageIndex;
import storage.manager.client.texture.ItemTextures;
import storage.manager.client.web.WebServer;

public class StorageManagerClient implements ClientModInitializer {

    private static final int WEB_PORT = 8642;
    private static final float PAUSE_HEALTH_THRESHOLD = 6.0f;  // 3 hearts
    private static final float RESUME_HEALTH_THRESHOLD = 12.0f; // 6 hearts

    private StorageIndex index;
    private JobExecutor executor;
    private WebServer webServer;
    private ItemTextures textures;

    @Override
    public void onInitializeClient() {
        index = new StorageIndex();
        index.load();
        index.startAutoSave();

        textures = new ItemTextures();
        textures.registerReloadListener();

        JobQueue queue = new JobQueue();
        executor = new JobExecutor(queue, index);
        webServer = new WebServer(queue, index, executor, textures);
        webServer.start(WEB_PORT, false);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Serves texture lookups queued by web requests. Runs even with no player, so the
            // chest page still renders icons while sitting on the main menu.
            textures.processPending();
            if (client.player == null) {
                return;
            }
            if (client.player.getHealth() <= PAUSE_HEALTH_THRESHOLD) {
                executor.pause();
            } else if (executor.isPaused() && client.player.getHealth() >= RESUME_HEALTH_THRESHOLD) {
                executor.resume();
            }
            executor.tick();
        });

        // Disconnects (including crashes/kicks) just pause job execution rather than attempting
        // an automated relogin - reconnecting is left to the user or an external launcher.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> executor.pause());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> executor.resume());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            index.flush();
            webServer.stop();
        }));
    }
}
