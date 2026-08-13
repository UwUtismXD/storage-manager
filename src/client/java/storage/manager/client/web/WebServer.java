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
import storage.manager.client.texture.ItemTextures;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
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

    /** How long a texture request waits for the client tick thread before giving up and 404ing. */
    private static final long TEXTURE_TIMEOUT_MILLIS = 3000L;

    private final Gson gson = new Gson();
    private final JobQueue queue;
    private final StorageIndex index;
    private final JobExecutor executor;
    private final ItemTextures textures;
    private HttpServer server;

    public WebServer(JobQueue queue, StorageIndex index, JobExecutor executor, ItemTextures textures) {
        this.queue = queue;
        this.index = index;
        this.executor = executor;
        this.textures = textures;
    }

    public void start(int port, boolean lanAccessible) {
        try {
            String host = lanAccessible ? "0.0.0.0" : "127.0.0.1";
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.createContext("/", this::handleIndex);
            // Longest-prefix wins in HttpServer, so this takes precedence over "/" for /chests.
            server.createContext("/chests", this::handleChests);
            server.createContext("/api/inventory", this::handleInventory);
            server.createContext("/api/status", this::handleStatus);
            server.createContext("/api/withdraw", this::handleWithdraw);
            server.createContext("/api/scan", this::handleScan);
            server.createContext("/api/sort", this::handleSort);
            server.createContext("/api/randomize", this::handleRandomize);
            server.createContext("/api/texture", this::handleTexture);
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
        serveStatic(exchange, "/web/index.html");
    }

    private void handleChests(HttpExchange exchange) throws IOException {
        serveStatic(exchange, "/web/chests.html");
    }

    private void serveStatic(HttpExchange exchange, String resource) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        try (InputStream in = WebServer.class.getResourceAsStream(resource)) {
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
        if (count <= 0) {
            sendJson(exchange, 400, Map.of("error", "count must be positive"));
            return;
        }
        // The chest view drags a specific slot across, which fetches that exact stack rather than
        // "any of this item" - the plain form omits both and gets the item-wide search.
        if (body.has("chest") && body.has("slot")) {
            BlockPos chest = readPos(body.getAsJsonObject("chest"));
            queue.enqueue(Job.withdrawSlot(chest, body.get("slot").getAsInt(), item, count));
        } else {
            queue.enqueue(Job.withdraw(item, count));
        }
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

    private void handleRandomize(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        queue.enqueue(Job.randomize());
        sendJson(exchange, 200, Map.of("ok", true));
    }

    private void handleTexture(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        String itemId = queryParam(exchange, "item");
        if (itemId == null || itemId.isBlank()) {
            exchange.sendResponseHeaders(400, -1);
            return;
        }
        byte[] png = textures.get(itemId, TEXTURE_TIMEOUT_MILLIS);
        if (png == null) {
            // No flat texture in this pack (entity-modelled blocks like chests, mostly) - the page
            // falls back to its labelled swatch when the image fails to load.
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.getResponseHeaders().set("Cache-Control", "max-age=300");
        exchange.sendResponseHeaders(200, png.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(png);
        }
    }

    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && name.equals(pair.substring(0, eq))) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private void handleSetup(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("region", index.getRegion());
            payload.put("inputChest", index.getInputChestPos());
            payload.put("outputChest", index.getOutputChestPos());
            // Both halves of each, resolved against the world by the client tick thread - the
            // index keys a double chest by its LEFT half, which may not be the half the user typed.
            payload.put("resolved", executor.reservedChestPositions());
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
