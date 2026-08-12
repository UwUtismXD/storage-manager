package storage.manager.client.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalNear;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Thin wrapper around Baritone's public API for walking to a block position.
 * Non-blocking: callers must poll {@link #isBusy()} / {@link #hasArrived(BlockPos, double)}
 * from a tick handler rather than waiting for arrival synchronously.
 */
public class BotNavigator {

    /** How close (in blocks) the goal is considered "close enough" to interact. */
    public static final int INTERACT_GOAL_RANGE = 2;

    private static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    public void goTo(BlockPos pos) {
        baritone().getCustomGoalProcess().setGoalAndPath(new GoalNear(pos, INTERACT_GOAL_RANGE));
    }

    public boolean isBusy() {
        return baritone().getPathingBehavior().isPathing();
    }

    public boolean hasArrived(BlockPos target, double maxDistance) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return false;
        }
        return client.player.blockPosition().distSqr(target) <= maxDistance * maxDistance;
    }

    public void cancel() {
        baritone().getPathingBehavior().cancelEverything();
        // cancelEverything() stops it from issuing new movement, but doesn't necessarily release
        // inputs it already forced down (e.g. sneaking near a ledge for edge safety) - a forced
        // sneak makes a right-click try to place the held item instead of opening a container.
        baritone().getInputOverrideHandler().clearAllKeys();
    }
}
