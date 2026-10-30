package releon.ru.api.module.impl.movement;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.VoxelShape;
import releon.ru.api.events.impl.PlayerTravelEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.AngleConfig;
import releon.ru.api.module.impl.combat.aura.AngleConnection;
import releon.ru.api.module.impl.combat.aura.MathAngle;
import releon.ru.api.module.impl.combat.aura.rotations.SnapAngle;
import releon.ru.api.module.impl.combat.aura.target.RaycastAngle;
import releon.ru.api.module.impl.combat.aura.util.StopWatch;
import releon.ru.api.module.impl.combat.aura.util.TaskPriority;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.utils.inventory.lookup.InventoryUtils;

public final class Spider extends Module {
    private final ModeSetting mode = register(new ModeSetting("Mode", "Spider mode.", "Blocks", "Blocks", "FunTimeButtons"));
    private final StopWatch placeTimer = new StopWatch();
    private final StopWatch ftbutton = new StopWatch();
    Minecraft client;
    PlayerTravelEvent motion;
    public Spider() {
        super("Spider", "Climbs walls.", ModuleCategory.MOVEMENT);
    }

    @Override
    protected void onDisable() {
        Minecraft client = Minecraft.getInstance();
        if (client.options != null) {
            client.options.keyJump.setDown(false);
        }
    }

    @Override
    public void onTick(Minecraft client) {
        if (client.player == null || client.level == null) {
            return;
        }
        if (!mode.is("Blocks")) {
            return;
        }
        if (!mode.is("FunTimeButtons")) {
            FuntimeButtons(motion);
            return;
        }
        boolean offHand = client.player.getOffhandItem().getItem() instanceof BlockItem;
        int slot = findHotbarBlockSlot(client);
        BlockPos blockPos = findPos(client);
        if ((!offHand && slot == -1) || blockPos.equals(BlockPos.ZERO)) {
            return;
        }

        ItemStack stack = offHand ? client.player.getOffhandItem() : client.player.getInventory().getItem(slot);
        if (!(stack.getItem() instanceof BlockItem)) {
            return;
        }

        Vec3 vec = blockPos.getCenter();
        Direction direction = nearestDirection(vec.x - client.player.getX(), vec.y - client.player.getY(), vec.z - client.player.getZ());
        Angle angle = MathAngle.calculateAngle(vec.subtract(Vec3.atLowerCornerOf(direction.getUnitVec3i()).scale(0.1F)));
        AngleConnection.INSTANCE.rotateTo(
                new Angle.VecRotation(angle, angle.toVector()),
                client.player,
                1,
                new AngleConfig(new SnapAngle(), true, true),
                TaskPriority.HIGH_IMPORTANCE_1,
                this
        );

        if (placeTimer.finished(75.0D) && canPlace(client, (BlockItem) stack.getItem())) {
            InteractionHand hand = offHand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            int previous = client.player.getInventory().getSelectedSlot();
            if (!offHand) {
                client.player.getInventory().setSelectedSlot(slot);
                client.player.connection.send(new ServerboundSetCarriedItemPacket(slot));
            }
            client.gameMode.useItemOn(client.player, hand, new BlockHitResult(vec, direction.getOpposite(), blockPos, false));
            client.player.connection.send(new ServerboundSwingPacket(hand));
            if (!offHand) {
                InventoryUtils.selectSlot(previous);
                client.player.connection.send(new ServerboundSetCarriedItemPacket(previous));
            }
            placeTimer.reset();
        }
    }

    private int findHotbarBlockSlot(Minecraft client) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.getItem() instanceof BlockItem) {
                return i;
            }
        }
        return -1;
    }
    private void FuntimeButtons(PlayerTravelEvent motion){
        if (!mc.player.horizontalCollision) {
            return;
        }
        int buttonSlot = InventoryUtils.findItemInInventory(Items.ACACIA_BUTTON);

        if (buttonSlot == -1) {
            setEnabled(false);
            return;
        }
 if (!ftbutton.finished(110))return;
      if(mc.player.horizontalCollision){
       mc.player.setJumping(true);
        mc.player.setOnGround(true);
        place(buttonSlot);
          mc.player.fallDistance = 0f;

      }
       }
       private void place(int slot){
      int prev = mc.player.getInventory().getSelectedSlot();
      mc.player.getInventory().setSelectedSlot(slot);
      float yaw = getWallYaw(getWallDirection());
      float pitch = 90f;
           mc.player.setYRot(yaw);
           mc.player.setXRot(pitch);
           BlockHitResult hit = getTargetedBlock(yaw, pitch, 4.0D);

           if (hit.getType() == HitResult.Type.BLOCK) {
               client.player.swing(InteractionHand.MAIN_HAND);
               mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
           }

           mc.player.getInventory().setSelectedSlot(prev);
           mc.player.fallDistance = 0.0F;

}
    private Direction getWallDirection() {
        Direction forward = mc.player.getDirection();
        BlockPos playerPos = mc.player.blockPosition();

        if (!mc.level.getBlockState(playerPos.relative(forward)).isAir()) {
            return forward;
        }

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!mc.level.getBlockState(playerPos.relative(direction)).isAir()) {
                return direction;
            }
        }

        return forward;
    }
    private BlockHitResult getTargetedBlock(float yaw, float pitch, double distance) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 dir = Vec3.directionFromRotation(pitch, yaw).normalize();
        Vec3 end = eye.add(dir.scale(distance));

        ClipContext context = new ClipContext(
                eye,
                end,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                mc.player
        );

        return mc.level.clip(context);
    }
    private float getWallYaw(Direction direction) {
        return switch (direction) {
            case NORTH -> 180f;
            case SOUTH -> 0f;
            case WEST -> 90f;
            case EAST -> -90f;
            default -> mc.player.getYRot();
        };
    }
    private BlockPos findPos(Minecraft client) {
        BlockPos blockPos = getBlockPos(client);
        if (client.level.getBlockState(blockPos).isCollisionShapeFullBlock(client.level, blockPos)) {
            return BlockPos.ZERO;
        }
        for (BlockPos pos : new BlockPos[]{blockPos.west(), blockPos.east(), blockPos.south(), blockPos.north()}) {
            if (client.level.getBlockState(pos).isCollisionShapeFullBlock(client.level, pos)) {
                return pos;
            }
        }
        return BlockPos.ZERO;
    }

    private BlockPos getBlockPos(Minecraft client) {
        Vec3 predicted = client.player.position().add(client.player.getDeltaMovement()).add(0.0D, -1.0E-3D, 0.0D);
        return BlockPos.containing(predicted);
    }

    private boolean canPlace(Minecraft client, BlockItem blockItem) {
        BlockPos blockPos = getBlockPos(client);
        if (blockPos.getY() >= BlockPos.containing(client.player.getX(), client.player.getY(), client.player.getZ()).getY()) {
            return false;
        }
        VoxelShape shape = blockItem.getBlock().defaultBlockState().getCollisionShape(client.level, blockPos);
        if (shape.isEmpty()) {
            return false;
        }
        AABB box = shape.bounds().move(blockPos);
        return !box.intersects(client.player.getBoundingBox());
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
