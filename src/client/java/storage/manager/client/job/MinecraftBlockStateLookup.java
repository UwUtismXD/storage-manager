package storage.manager.client.job;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * Live client world - the default lookup {@link JobExecutor} drives a real scan with.
 * Reads as {@link ScanResult#EMPTY} when no level is loaded so an in-progress scan
 * survives a dimension change without throwing; the next scan picks up the new world
 * on its next chunk.
 *
 * <p>This is the only lookup that touches {@link BuiltInRegistries}, so it's also the
 * only one that needs MC bootstrap - tests use {@code FakeLookup} instead and avoid the
 * registry entirely.
 */
final class MinecraftBlockStateLookup implements BlockStateLookup {
    @Override
    public ScanResult scanAt(BlockPos pos) {
        ClientLevel world = Minecraft.getInstance().level;
        if (world == null) {
            return ScanResult.EMPTY;
        }
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        ScanKind kind;
        if (block instanceof ChestBlock) {
            kind = state.getValue(ChestBlock.TYPE) == ChestType.RIGHT
                    ? ScanKind.CHEST_RIGHT
                    : ScanKind.CHEST_SINGLE_OR_LEFT;
        } else if (block instanceof BarrelBlock) {
            kind = ScanKind.BARREL;
        } else if (block instanceof ShulkerBoxBlock) {
            kind = ScanKind.SHULKER_BOX;
        } else {
            return ScanResult.EMPTY;
        }
        return ScanResult.of(kind, BuiltInRegistries.BLOCK.getKey(block).toString());
    }
}
