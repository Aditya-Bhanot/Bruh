package releon.ru.api.module.impl.player;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.aura.MathAngle;
import releon.ru.api.settings.impl.NumberSetting;

import java.util.HashSet;
import java.util.Set;

public final class OpenWalls extends Module {
    private final NumberSetting range = register(new NumberSetting("Range", "Open range through walls.", 6.0, 3.0, 8.0, 0.1));
    private boolean usePressedLastTick;

    public OpenWalls() {
        super("Open Walls", "Opens containers through walls.", ModuleCategory.PLAYER);
    }

    @Override
    protected void onDisable() {
        usePressedLastTick = false;
    }

    @Override
    public void onTick(Minecraft client) {
        if (client.player == null || client.level == null || client.gameMode == null) {
            return;
        }
        if (client.screen != null) {
            usePressedLastTick = false;
            return;
        }
        boolean usePressed = client.options.keyUse.isDown();
        if (!usePressed) {
            usePressedLastTick = false;
            return;
        }
        if (usePressedLastTick) {
            return;
        }
        usePressedLastTick = true;

        BlockPos target = findOpenableBlockByLook(client, range.getFloat());
        if (target == null) {
            return;
        }
        Direction side = resolveClickSide(client, target);
        Vec3 hitVec = Vec3.atCenterOf(target).add(side.getStepX() * 0.5D, side.getStepY() * 0.5D, side.getStepZ() * 0.5D);
        InteractionResult result = client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, new BlockHitResult(hitVec, side, target, false));
        if (result.consumesAction()) {
            client.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private BlockPos findOpenableBlockByLook(Minecraft client, float distance) {
        Vec3 start = client.player.getEyePosition();
        Vec3 direction = MathAngle.cameraAngle().toVector();
        Set<BlockPos> visited = new HashSet<>();
        for (double current = 0.0D; current <= distance; current += 0.2D) {
            BlockPos pos = BlockPos.containing(start.add(direction.scale(current)));
            if (!visited.add(pos)) {
                continue;
            }
            if (isOpenable(client.level.getBlockState(pos).getBlock())) {
                return pos;
            }
        }
        return null;
    }

    private Direction resolveClickSide(Minecraft client, BlockPos target) {
        Vec3 toBlock = Vec3.atCenterOf(target).subtract(client.player.getEyePosition());
        return nearestDirection(toBlock.x, toBlock.y, toBlock.z).getOpposite();
    }

    private boolean isOpenable(Block block) {
        return block == Blocks.CHEST
                || block == Blocks.TRAPPED_CHEST
                || block == Blocks.ENDER_CHEST
                || block == Blocks.BARREL
                || block instanceof ShulkerBoxBlock
                || block == Blocks.HOPPER
                || block == Blocks.DISPENSER
                || block == Blocks.DROPPER
                || block == Blocks.FURNACE
                || block == Blocks.BLAST_FURNACE
                || block == Blocks.SMOKER
                || block == Blocks.BREWING_STAND
                || block == Blocks.CRAFTING_TABLE
                || block == Blocks.CARTOGRAPHY_TABLE
                || block == Blocks.SMITHING_TABLE
                || block == Blocks.STONECUTTER
                || block == Blocks.LOOM
                || block == Blocks.GRINDSTONE
                || block == Blocks.ENCHANTING_TABLE
                || block == Blocks.ANVIL
                || block == Blocks.CHIPPED_ANVIL
                || block == Blocks.DAMAGED_ANVIL;
    }

    private static Direction nearestDirection(double x, double y, double z) {
        double absX = Math.abs(x);
        double absY = Math.abs(y);
        double absZ = Math.abs(z);

        if (absX >= absY && absX >= absZ) {
            return x >= 0.0D ? Direction.EAST : Direction.WEST;
        }
        if (absY >= absX && absY >= absZ) {
            return y >= 0.0D ? Direction.UP : Direction.DOWN;
        }
        return z >= 0.0D ? Direction.SOUTH : Direction.NORTH;
    }
}
