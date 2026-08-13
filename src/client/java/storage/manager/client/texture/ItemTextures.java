package storage.manager.client.texture;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import storage.manager.StorageManager;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Resolves an item id to the PNG bytes of its icon, read from whatever resource pack is currently
 * active, so the web UI follows the game's textures without anything being bundled into the jar.
 *
 * <p>Resolution follows the 1.21.4+ chain: {@code items/<id>.json} names a model, the model's
 * {@code parent} chain is walked to collect its {@code textures} map, and the winning entry points
 * at {@code textures/<path>.png}.
 *
 * <p>Reads happen on the client tick thread - HTTP handler threads call {@link #get} and park on a
 * future until {@link #processPending()} fulfils it, because a resource reload can swap the pack
 * files out from under a concurrent read.
 */
public class ItemTextures {

    /** Cached "looked it up, nothing usable there" - distinct from "not looked up yet" (absent). */
    private static final byte[] MISSING = new byte[0];

    /**
     * Texture keys worth drawing, best first. {@code particle} is deliberately absent: it's the
     * representative texture for entity-modelled blocks like chests and beds, and a chest that
     * renders as oak planks is worse than one that falls back to a labelled swatch.
     */
    private static final String[] PREFERRED_KEYS = {
            "layer0", "all", "side", "north", "front", "texture", "cross", "top", "end",
            "fan", "crop", "up", "bottom", "down", "wall", "rail", "pane", "lantern"
    };

    /** Spread bursts of first-time lookups over several ticks rather than hitching one. */
    private static final int MAX_LOADS_PER_TICK = 16;
    private static final int MAX_DEPTH = 16;

    private static final Gson GSON = new Gson();

    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<byte[]>> pending = new ConcurrentHashMap<>();

    /** Drops the cache whenever packs are reloaded, so swapping packs updates the UI live. */
    public void registerReloadListener() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
                new SimpleSynchronousResourceReloadListener() {
                    @Override
                    public ResourceLocation getFabricId() {
                        return ResourceLocation.fromNamespaceAndPath(StorageManager.MOD_ID, "item-textures");
                    }

                    @Override
                    public void onResourceManagerReload(ResourceManager manager) {
                        // Anything still pending just resolves against the new pack next tick.
                        cache.clear();
                    }
                });
    }

    /**
     * PNG bytes for an item id, or null if this pack has no flat texture for it. Safe to call from
     * HTTP handler threads; blocks up to {@code timeoutMillis} waiting for the tick thread.
     */
    public byte[] get(String itemId, long timeoutMillis) {
        byte[] cached = cache.get(itemId);
        if (cached != null) {
            return cached.length == 0 ? null : cached;
        }
        CompletableFuture<byte[]> future = pending.computeIfAbsent(itemId, id -> new CompletableFuture<>());
        try {
            byte[] data = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            return data.length == 0 ? null : data;
        } catch (Exception e) {
            // Timed out, interrupted, or the game is shutting down - let the caller 404 and retry.
            return null;
        }
    }

    /** Fulfils queued lookups. Must run on the client tick thread. */
    public void processPending() {
        if (pending.isEmpty()) {
            return;
        }
        int loaded = 0;
        for (String itemId : new ArrayList<>(pending.keySet())) {
            if (loaded >= MAX_LOADS_PER_TICK) {
                break;
            }
            byte[] data = load(itemId);
            cache.put(itemId, data);
            CompletableFuture<byte[]> future = pending.remove(itemId);
            if (future != null) {
                future.complete(data);
            }
            loaded++;
        }
    }

    private byte[] load(String itemId) {
        try {
            Minecraft client = Minecraft.getInstance();
            ResourceManager resources = client.getResourceManager();
            if (resources == null) {
                return MISSING;
            }
            ResourceLocation item = ResourceLocation.parse(itemId);

            String modelRef = resolveModelRef(resources, item);
            if (modelRef == null) {
                return MISSING;
            }
            String textureRef = resolveTextureRef(resources, modelRef);
            if (textureRef == null) {
                return MISSING;
            }

            ResourceLocation texture = ResourceLocation.parse(textureRef);
            ResourceLocation path = ResourceLocation.fromNamespaceAndPath(
                    texture.getNamespace(), "textures/" + texture.getPath() + ".png");
            Optional<Resource> resource = resources.getResource(path);
            if (resource.isEmpty()) {
                return MISSING;
            }
            try (var in = resource.get().open()) {
                return in.readAllBytes();
            }
        } catch (Exception e) {
            StorageManager.LOGGER.debug("No texture for {}", itemId, e);
            return MISSING;
        }
    }

    /** {@code items/<id>.json} -> the model it points at, e.g. {@code minecraft:block/cobblestone}. */
    private String resolveModelRef(ResourceManager resources, ResourceLocation item) {
        JsonObject definition = readJson(resources, ResourceLocation.fromNamespaceAndPath(
                item.getNamespace(), "items/" + item.getPath() + ".json"));
        if (definition != null && definition.has("model") && definition.get("model").isJsonObject()) {
            String ref = findModelRef(definition.getAsJsonObject("model"), 0);
            if (ref != null) {
                return ref;
            }
        }
        // Packs from before the items/ definition layer, and anything whose definition we couldn't
        // make sense of, still tend to have a plain model under the matching name.
        return item.getNamespace() + ":item/" + item.getPath();
    }

    /**
     * Digs a model reference out of an item definition node. These nest arbitrarily - `select` has
     * cases plus a fallback, `range_dispatch` has entries, `special` wraps a renderer descriptor -
     * so anything with a concrete model underneath it counts.
     */
    private String findModelRef(JsonObject node, int depth) {
        if (node == null || depth > MAX_DEPTH) {
            return null;
        }
        if (node.has("model") && node.get("model").isJsonPrimitive()) {
            return node.get("model").getAsString();
        }
        // Checked before recursing into an object `model`: for `special` nodes the sibling `model`
        // describes the renderer (no texture in it) while `base` is the real model.
        if (node.has("base") && node.get("base").isJsonPrimitive()) {
            return node.get("base").getAsString();
        }
        // `select` nodes carry a fallback; `condition` nodes branch on a component instead, and
        // the false branch is the plain item (e.g. a compass without a lodestone tracker).
        for (String key : new String[] { "fallback", "on_false", "on_true" }) {
            if (node.has(key) && node.get(key).isJsonObject()) {
                String ref = findModelRef(node.getAsJsonObject(key), depth + 1);
                if (ref != null) {
                    return ref;
                }
            }
        }
        for (String key : new String[] { "cases", "entries" }) {
            if (!node.has(key) || !node.get(key).isJsonArray()) {
                continue;
            }
            for (JsonElement element : node.getAsJsonArray(key)) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                JsonObject inner = entry.has("model") && entry.get("model").isJsonObject()
                        ? entry.getAsJsonObject("model")
                        : entry;
                String ref = findModelRef(inner, depth + 1);
                if (ref != null) {
                    return ref;
                }
            }
        }
        if (node.has("model") && node.get("model").isJsonObject()) {
            return findModelRef(node.getAsJsonObject("model"), depth + 1);
        }
        return null;
    }

    /** Walks the model's parent chain collecting {@code textures}, then picks the best entry. */
    private String resolveTextureRef(ResourceManager resources, String modelRef) {
        Map<String, String> textures = new LinkedHashMap<>();
        String ref = modelRef;
        for (int depth = 0; ref != null && depth < MAX_DEPTH; depth++) {
            ResourceLocation model = ResourceLocation.parse(ref);
            JsonObject json = readJson(resources, ResourceLocation.fromNamespaceAndPath(
                    model.getNamespace(), "models/" + model.getPath() + ".json"));
            if (json == null) {
                break;
            }
            if (json.has("textures") && json.get("textures").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("textures").entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        // Child models are visited first, so the child's value wins over the parent's.
                        textures.putIfAbsent(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
            ref = json.has("parent") && json.get("parent").isJsonPrimitive()
                    ? json.get("parent").getAsString()
                    : null;
        }

        for (String key : PREFERRED_KEYS) {
            String value = dereference(textures.get(key), textures);
            if (value != null) {
                return value;
            }
        }
        for (Map.Entry<String, String> entry : textures.entrySet()) {
            if ("particle".equals(entry.getKey())) {
                continue;
            }
            String value = dereference(entry.getValue(), textures);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** Texture values can be {@code #other_key} aliases pointing at another entry in the same map. */
    private String dereference(String value, Map<String, String> textures) {
        String current = value;
        for (int depth = 0; current != null && current.startsWith("#") && depth < MAX_DEPTH; depth++) {
            current = textures.get(current.substring(1));
        }
        return current != null && !current.startsWith("#") ? current : null;
    }

    private JsonObject readJson(ResourceManager resources, ResourceLocation path) {
        Optional<Resource> resource = resources.getResource(path);
        if (resource.isEmpty()) {
            return null;
        }
        try (Reader reader = resource.get().openAsReader()) {
            return GSON.fromJson(reader, JsonObject.class);
        } catch (Exception e) {
            return null;
        }
    }
}
