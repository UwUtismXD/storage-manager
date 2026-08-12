package storage.manager.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import storage.manager.client.job.JobExecutor;
import storage.manager.client.job.JobQueue;
import storage.manager.client.storage.StorageIndex;
import storage.manager.client.web.WebServer;

public class StorageManagerClient implements ClientModInitializer {

    private static final int WEB_PORT = 8642;
    private static final float PAUSE_HEALTH_THRESHOLD = 6.0f;  // 3 hearts
    private static final float RESUME_HEALTH_THRESHOLD = 12.0f; // 6 hearts

    private StorageIndex index;
    private JobExecutor executor;
    private WebServer webServer;

    @Override
    public void onInitializeClient() {
        index = new StorageIndex();
        index.load();

        JobQueue queue = new JobQueue();
        executor = new JobExecutor(queue, index);
        webServer = new WebServer(queue, index, executor);
        webServer.start(WEB_PORT, false);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
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
            index.save();
            webServer.stop();
        }));
    }
}
