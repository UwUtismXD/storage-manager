package storage.manager.client.web;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
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
import java.io.FilterInputStream;
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
import java.util.concurrent.ThreadFactory;

/**
 * Local control surface for the bot: a static single-page UI plus a small JSON API.
 * Runs on its own executor - handlers only ever read from / enqueue onto shared state,
 * never touch Minecraft/Baritone objects directly (those are client-tick-thread only).
 */
public class WebServer {

    /** How long a texture request waits for the client tick thread before giving up and 404ing. */
    private static final long TEXTURE_TIMEOUT_MILLIS = 3000L;

    /**
     * Cap on a POST body the server will actually read. Every handler expects a tiny JSON payload
     * (single-digit bytes in practice); 16 KiB is comfortably above the largest legitimate request
     * and far below anything that would stress the heap. Requests over the cap are rejected without
     * their body being read.
     */
    private static final long MAX_REQUEST_BYTES = 16 * 1024L;

    /**
     * Worker-pool size for the HTTP server. The web UI is a localhost control surface used by a
     * single human; a few concurrent handlers are plenty, and the unbounded queue keeps dispatch
     * non-rejecting under brief spikes. Replaces the previous {@code newCachedThreadPool} which
     * would grow without limit under a slow client or a misbehaving loop.
     */
    private static final int WEB_THREAD_POOL_SIZE = 8;

    private final Gson gson = new Gson();
    private final JobQueue queue;
    private final StorageIndex index;
    private final ExecutorView executor;
    private final ItemTextures textures;
    private HttpServer server;

    public WebServer(JobQueue queue, StorageIndex index, ExecutorView executor, ItemTextures textures) {
        this.queue = queue;
        this.index = index;
        this.executor = executor;
        this.textures = textures;
    }

    /**
     * Narrow view of {@link JobExecutor} that {@link WebServer} actually depends on. Splitting
     * this out means the web tier can be exercised with a fake from a JUnit test (the live
     * executor pulls world state, which never exists outside the client tick thread) without
     * standing up a Minecraft environment. {@link JobExecutor} implements it directly.
     */
    public interface ExecutorView {
        /** Resolved input/output chest positions for the UI; lives on the executor because resolving needs world access. */
        JobExecutor.ReservedChests reservedChestPositions();
        void requestStop();
        void setWanderEnabled(boolean enabled);
        String getStatus();
        String getLastWarning();
        boolean isPaused();
        boolean isWanderEnabled();
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
            server.createContext("/api/chests/clear", this::handleClearChests);
            server.createContext("/api/sort", this::handleSort);
            server.createContext("/api/randomize", this::handleRandomize);
            server.createContext("/api/stop", this::handleStop);
            server.createContext("/api/wander", this::handleWander);
            server.createContext("/api/texture", this::handleTexture);
            server.createContext("/api/setup", this::handleSetup);
            server.setExecutor(Executors.newFixedThreadPool(WEB_THREAD_POOL_SIZE, webThreadFactory()));
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
        // Totals mean "in storage", so the input/output chests don't count towards them - the
        // resolved halves come from the tick thread, since working them out needs world access.
        payload.put("totals", index.totalCounts(executor.reservedChestPositions().positions()));
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
        payload.put("wanderEnabled", executor.isWanderEnabled());
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
            String error = readPos(body.get("chest"), "chest");
            if (error != null) {
                sendJson(exchange, 400, Map.of("error", error));
                return;
            }
            BlockPos chest = readPosPosition(body.get("chest"));
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

    /** Clears stale chest records after the room was rebuilt; setup coordinates remain intact. */
    private void handleClearChests(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        index.clearChests();
        sendJson(exchange, 200, Map.of("ok", true));
    }

    /**
     * Cancels the running job and everything queued behind it. Whatever the bot is still carrying
     * is put back into the nearest chest with room rather than left in its inventory.
     */
    private void handleStop(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        executor.requestStop();
        sendJson(exchange, 200, Map.of("ok", true));
    }

    /** Toggles the idle stroll. In-memory only - it's a preference, not part of the index. */
    private void handleWander(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        JsonObject body = readJson(exchange);
        if (body == null || !body.has("enabled")) {
            sendJson(exchange, 400, Map.of("error", "expected {enabled}"));
            return;
        }
        executor.setWanderEnabled(body.get("enabled").getAsBoolean());
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
        SetupResult result = processSetup(body);
        if (result.error != null) {
            sendJson(exchange, 400, Map.of("error", result.error));
            return;
        }
        sendJson(exchange, 200, Map.of("ok", true));
    }

    /**
     * Outcome of applying a parsed setup body. {@link #error} is non-null when the request
     * failed validation; the index is unchanged in that case. {@link #status} is always 200 for
     * the partial-update 200 case so the HTTP layer can branch on it without a separate flag.
     */
    static final class SetupResult {
        final int status;
        final String error;

        static final SetupResult OK = new SetupResult(200, null);
        static SetupResult fail(String error) {
            return new SetupResult(400, error);
        }

        private SetupResult(int status, String error) {
            this.status = status;
            this.error = error;
        }
    }

    /**
     * Applies a parsed {@code /api/setup} POST body to the index. Package-private so the tier 3
     * test can drive it directly with a constructed {@link JsonObject} instead of standing up
     * the HTTP server and a real {@link HttpExchange}. The HTTP handler delegates here after
     * parsing the body off the wire.
     *
     * <p>Each top-level field is validated independently and only applied on success: a bad
     * {@code region} must not leave the input/output chest half-applied, and vice versa.
     */
    SetupResult processSetup(JsonObject body) {
        BlockPos[] region = null;
        BlockPos inputChest = null;
        BlockPos outputChest = null;
        if (body.has("region")) {
            String error = readPosPair(body.get("region"), "region");
            if (error != null) {
                return SetupResult.fail(error);
            }
            region = readPosPairPositions(body.get("region"));
        }
        if (body.has("inputChest")) {
            String error = readPos(body.get("inputChest"), "inputChest");
            if (error != null) {
                return SetupResult.fail(error);
            }
            inputChest = readPosPosition(body.get("inputChest"));
        }
        if (body.has("outputChest")) {
            String error = readPos(body.get("outputChest"), "outputChest");
            if (error != null) {
                return SetupResult.fail(error);
            }
            outputChest = readPosPosition(body.get("outputChest"));
        }
        if (region != null) {
            index.setRegion(region[0], region[1]);
        }
        if (inputChest != null) {
            index.setInputChest(inputChest);
        }
        if (outputChest != null) {
            index.setOutputChest(outputChest);
        }
        return SetupResult.OK;
    }

    /**
     * Reads a single {@link BlockPos} from the given JSON element, returning a non-null error
     * string on any of: missing element, JSON null, non-object, or any of x/y/z missing or
     * non-numeric. The path is dotted for nested shapes - e.g. a wrong x inside a region comes
     * back as {@code "region.max.x must be an integer"}.
     */
    static String readPos(JsonElement element, String path) {
        if (element == null || element.isJsonNull()) {
            return path + " missing";
        }
        if (!element.isJsonObject()) {
            return path + " must be an object";
        }
        JsonObject obj = element.getAsJsonObject();
        String[] names = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) {
            JsonElement value = obj.get(names[i]);
            if (value == null || value.isJsonNull()) {
                return path + "." + names[i] + " missing";
            }
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                return path + "." + names[i] + " must be an integer";
            }
        }
        return null;
    }

    /** {@link #readPos} without the validation - only safe after {@link #readPos} returns null. */
    static BlockPos readPosPosition(JsonElement element) {
        JsonObject obj = element.getAsJsonObject();
        return new BlockPos(obj.get("x").getAsInt(), obj.get("y").getAsInt(), obj.get("z").getAsInt());
    }

    /**
     * Reads a named pair of positions (e.g. {@code region.min} / {@code region.max}) from the
     * given object element. Returns null on success or a non-null error string otherwise.
     */
    static String readPosPair(JsonElement element, String path) {
        if (element == null || element.isJsonNull()) {
            return path + " missing";
        }
        if (!element.isJsonObject()) {
            return path + " must be an object";
        }
        JsonObject obj = element.getAsJsonObject();
        String minError = readPos(obj.get("min"), path + ".min");
        if (minError != null) {
            return minError;
        }
        String maxError = readPos(obj.get("max"), path + ".max");
        if (maxError != null) {
            return maxError;
        }
        return null;
    }

    /** {@link #readPosPair} without the validation - only safe after {@link #readPosPair} returns null. */
    static BlockPos[] readPosPairPositions(JsonElement element) {
        JsonObject obj = element.getAsJsonObject();
        BlockPos min = readPosPosition(obj.get("min"));
        BlockPos max = readPosPosition(obj.get("max"));
        return new BlockPos[]{min, max};
    }

    private JsonObject readJson(HttpExchange exchange) throws IOException {
        // Reject upfront on Content-Length if the client declared one above the cap, so we don't
        // even allocate a buffer for an oversized body. Chunked requests fall through to the
        // bounded stream, which throws as soon as the read exceeds MAX_REQUEST_BYTES.
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            try {
                if (Long.parseLong(contentLength) > MAX_REQUEST_BYTES) {
                    return null;
                }
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        try (InputStreamReader reader = new InputStreamReader(
                new BoundedInputStream(exchange.getRequestBody(), MAX_REQUEST_BYTES),
                StandardCharsets.UTF_8)) {
            return gson.fromJson(reader, JsonObject.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** Named daemon thread factory so pool workers show up as `storage-manager-web-N` in stack traces. */
    private static ThreadFactory webThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "storage-manager-web");
            thread.setDaemon(true);
            return thread;
        };
    }

    /** {@link InputStream} wrapper that throws once the caller has read more than {@code limit} bytes. */
    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;

        BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                throw new IOException("request body exceeded " + MAX_REQUEST_BYTES + " bytes");
            }
            int b = in.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (remaining <= 0) {
                throw new IOException("request body exceeded " + MAX_REQUEST_BYTES + " bytes");
            }
            int toRead = (int) Math.min(len, remaining);
            int n = in.read(buf, off, toRead);
            if (n > 0) {
                remaining -= n;
            }
            return n;
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
