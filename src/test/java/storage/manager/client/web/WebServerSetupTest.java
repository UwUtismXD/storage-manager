package storage.manager.client.web;

import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import storage.manager.client.job.JobExecutor;
import storage.manager.client.job.JobQueue;
import storage.manager.client.storage.StorageIndex;
import storage.manager.client.texture.ItemTextures;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 3 tests for {@link WebServer#processSetup}. The web tier normally reaches into
 * {@code Minecraft.getInstance().player} / {@code level} via the executor, which doesn't exist
 * outside a real client - the seam is {@link WebServer.ExecutorView}, accepted by the
 * constructor so a fake can stand in here. The HTTP layer itself is exercised on the wire in
 * manual use; these tests pin the JSON parsing + index mutation since the recent NPE on
 * partial setup payloads (#9) lived in that gap.
 */
class WebServerSetupTest {

    private StorageIndex index;
    private FakeExecutor executor;
    private WebServer server;

    @BeforeEach
    void setUp() {
        index = new StorageIndex(Path.of("build", "test-setup-index.json"));
        executor = new FakeExecutor();
        // ItemTextures is unused by /api/setup; passing null would NPE on construction, so use
        // a stub that the test never exercises. JobQueue is real but the setup POST never
        // enqueues anything. Baritone settings are never reached from /api/setup either.
        server = new WebServer(new JobQueue(), index, executor, new UnusedTextures(), null);
    }

    @Test
    void emptyBodyReturnsOkAndLeavesIndexUntouched() {
        WebServer.SetupResult result = server.processSetup(new JsonObject());
        assertEquals(200, result.status);
        assertNull(result.error);
        assertNull(index.getRegion().min);
        assertNull(index.getRegion().max);
        assertNull(index.getInputChest());
        assertNull(index.getOutputChest());
    }

    @Test
    void regionMissingMaxNamesRegionMaxAndLeavesRegionUnchanged() {
        JsonObject body = bodyWithRegion(pos(0, 0, 0), null);

        WebServer.SetupResult result = server.processSetup(body);

        assertEquals(400, result.status);
        assertEquals("region.max missing", result.error);
        assertNull(index.getRegion().min);
        assertNull(index.getRegion().max);
    }

    @Test
    void regionWithBadMaxXNamesFieldAndLeavesRegionUnchanged() {
        // Pre-set a region so we can assert the rejection leaves it in place rather than
        // wiping half of it.
        index.setRegion(new BlockPos(1, 2, 3), new BlockPos(4, 5, 6));

        JsonObject max = pos(0, 0, 0);
        max.addProperty("x", "ten");
        JsonObject body = bodyWithRegion(pos(0, 0, 0), max);

        WebServer.SetupResult result = server.processSetup(body);

        assertEquals(400, result.status);
        assertEquals("region.max.x must be an integer", result.error);
        // Pre-set region must still be intact - the partial update must not touch what it
        // couldn't validate.
        assertEquals(new BlockPos(1, 2, 3), index.getRegion().min.toBlockPos());
        assertEquals(new BlockPos(4, 5, 6), index.getRegion().max.toBlockPos());
    }

    @Test
    void validInputChestSetsItOnTheIndex() {
        JsonObject body = new JsonObject();
        body.add("inputChest", pos(0, 0, 0));

        WebServer.SetupResult result = server.processSetup(body);

        assertEquals(200, result.status);
        assertNull(result.error);
        assertEquals(new BlockPos(0, 0, 0), index.getInputChest());
    }

    @Test
    void regionAsStringNamesRegionAndLeavesRegionUnchanged() {
        index.setRegion(new BlockPos(1, 2, 3), new BlockPos(4, 5, 6));

        JsonObject body = new JsonObject();
        body.addProperty("region", "not-an-object");

        WebServer.SetupResult result = server.processSetup(body);

        assertEquals(400, result.status);
        assertEquals("region must be an object", result.error);
        assertEquals(new BlockPos(1, 2, 3), index.getRegion().min.toBlockPos());
        assertEquals(new BlockPos(4, 5, 6), index.getRegion().max.toBlockPos());
    }

    private static JsonObject pos(int x, int y, int z) {
        JsonObject p = new JsonObject();
        p.addProperty("x", x);
        p.addProperty("y", y);
        p.addProperty("z", z);
        return p;
    }

    private static JsonObject bodyWithRegion(JsonObject min, JsonObject max) {
        JsonObject region = new JsonObject();
        if (min != null) {
            region.add("min", min);
        }
        if (max != null) {
            region.add("max", max);
        }
        JsonObject body = new JsonObject();
        body.add("region", region);
        return body;
    }

    /**
     * Stub for {@link WebServer.ExecutorView}. None of the methods on the view are exercised by
     * the setup POST path; the GET branch is out of scope for this issue, so the resolver
     * returns an empty record. The mutators throw - if a future test calls into a path that
     * touches the executor, the loud failure points the way there.
     */
    private static final class FakeExecutor implements WebServer.ExecutorView {
        @Override
        public JobExecutor.ReservedChests reservedChestPositions() {
            return new JobExecutor.ReservedChests(java.util.List.of(), java.util.List.of());
        }

        @Override
        public void requestStop() {
            throw new AssertionError("setup POST should not touch requestStop");
        }

        @Override
        public void setWanderEnabled(boolean enabled) {
            throw new AssertionError("setup POST should not touch wander");
        }

        @Override
        public String getStatus() {
            throw new AssertionError("setup POST should not touch getStatus");
        }

        @Override
        public String getLastWarning() {
            throw new AssertionError("setup POST should not touch getLastWarning");
        }

        @Override
        public boolean isPaused() {
            throw new AssertionError("setup POST should not touch isPaused");
        }

        @Override
        public boolean isWanderEnabled() {
            throw new AssertionError("setup POST should not touch isWanderEnabled");
        }
    }

    /** ItemTextures stub: setup POST never touches the texture pipeline, so construction is enough. */
    private static final class UnusedTextures extends ItemTextures {
        // Inherits the no-arg constructor; the parent reaches into Minecraft.getInstance() only
        // when get() is called, which these tests never do.
    }
}
