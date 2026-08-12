package storage.manager.client.web;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import net.minecraft.core.BlockPos;

import storage.manager.StorageManager;
import storage.manager.client.job.Job;
import storage.manager.client.job.JobExecutor;
import storage.manager.client.job.JobQueue;
import storage.manager.client.storage.StorageIndex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Local control surface for the bot: a static single-page UI plus a small JSON API.
 * Runs on its own executor - handlers only ever read from / enqueue onto shared state,
 * never touch Minecraft/Baritone objects directly (those are client-tick-thread only).
 */
public class WebServer {

    private final Gson gson = new Gson();
    private final JobQueue queue;
    private final StorageIndex index;
    private final JobExecutor executor;
    private HttpServer server;

    public WebServer(JobQueue queue, StorageIndex index, JobExecutor executor) {
        this.queue = queue;
        this.index = index;
        this.executor = executor;
    }

    public void start(int port, boolean lanAccessible) {
        try {
            String host = lanAccessible ? "0.0.0.0" : "127.0.0.1";
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.createContext("/", this::handleIndex);
            server.createContext("/api/inventory", this::handleInventory);
            server.createContext("/api/status", this::handleStatus);
            server.createContext("/api/withdraw", this::handleWithdraw);
            server.createContext("/api/scan", this::handleScan);
            server.createContext("/api/sort", this::handleSort);
            server.createContext("/api/setup", this::handleSetup);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            StorageManager.LOGGER.info("Storage Manager web UI listening on http://{}:{}", host, port);
        } catch (IOException e) {
            StorageManager.LOGGER.error("Failed to start web server", e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        try (InputStream in = WebServer.class.getResourceAsStream("/web/index.html")) {
            if (in == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            in.transferTo(buffer);
            byte[] bytes = buffer.toByteArray();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private void handleInventory(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totals", index.totalCounts());
        payload.put("chests", index.allChests());
        sendJson(exchange, 200, payload);
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", executor.getStatus());
        payload.put("lastWarning", executor.getLastWarning());
        payload.put("paused", executor.isPaused());
        payload.put("queueSize", queue.size());
        payload.put("queue", queue.snapshot().stream().map(Job::toString).toList());
        sendJson(exchange, 200, payload);
    }

    private void handleWithdraw(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        JsonObject body = readJson(exchange);
        if (body == null || !body.has("item") || !body.has("count")) {
            sendJson(exchange, 400, Map.of("error", "expected {item, count}"));
            return;
        }
        String item = body.get("item").getAsString();
        int count = body.get("count").getAsInt();
        queue.enqueue(Job.withdraw(item, count));
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private void handleScan(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        queue.enqueue(Job.scanRegion());
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private void handleSort(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        queue.enqueue(Job.sortInput());
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private void handleSetup(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("region", index.getRegion());
            payload.put("inputChest", index.getInputChestPos());
            payload.put("outputChest", index.getOutputChestPos());
            sendJson(exchange, 200, payload);
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        JsonObject body = readJson(exchange);
        if (body == null) {
            sendJson(exchange, 400, Map.of("error", "expected JSON body"));
            return;
        }
        if (body.has("region")) {
            JsonObject region = body.getAsJsonObject("region");
            index.setRegion(readPos(region.getAsJsonObject("min")), readPos(region.getAsJsonObject("max")));
        }
        if (body.has("inputChest")) {
            index.setInputChest(readPos(body.getAsJsonObject("inputChest")));
        }
        if (body.has("outputChest")) {
            index.setOutputChest(readPos(body.getAsJsonObject("outputChest")));
        }
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private static BlockPos readPos(JsonObject obj) {
        return new BlockPos(obj.get("x").getAsInt(), obj.get("y").getAsInt(), obj.get("z").getAsInt());
    }

    private JsonObject readJson(HttpExchange exchange) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            return gson.fromJson(reader, JsonObject.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] bytes = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
