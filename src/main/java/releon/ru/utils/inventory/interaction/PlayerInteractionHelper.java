package releon.ru.utils.inventory.interaction;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import releon.ru.IMinecraft;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.MathAngle;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.api.settings.impl.BindSetting;
import releon.ru.utils.inventory.lookup.InventoryUtils;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public final class PlayerInteractionHelper implements IMinecraft {
    private PlayerInteractionHelper() {
    }

    public static void sendSequencedPacket(PredictiveAction packetCreator) {
        if (mc.level != null && mc.getConnection() != null) {
            mc.getConnection().send(packetCreator.predict(0));
        }
    }

    public static void interactItem(InteractionHand hand) {
        interactItem(hand, MathAngle.cameraAngle());
    }

    public static void interactItem(InteractionHand hand, Angle angle) {
        if (angle == null) {
            interactItem(hand);
            return;
        }
        InventoryUtils.sendUsePacket(hand, angle.getYaw(), angle.getPitch());
    }

    public static void interactEntity(Entity entity) {
        if (mc.gameMode != null && mc.player != null && entity != null) {
            mc.gameMode.interact(mc.player, entity, InteractionHand.MAIN_HAND);
        }
    }

    public static void sendPacketWithOutEvent(Packet<?> packet) {
        mc.getConnection().getConnection().send(packet, null);
    }

    public static void grimSuperBypass$$$(double y, Angle angle) {
        mc.player.connection.send(new ServerboundMovePlayerPacket.PosRot(mc.player.getX(), mc.player.getY() + y, mc.player.getZ(), angle.getYaw(), angle.getPitch(), mc.player.onGround(), mc.player.horizontalCollision));
    }

//    public String getHealthString(LivingEntity entity) {
//        return getHealthString(getHealth(entity));
//    }

    public static String getHealthString(float hp) {
        return String.format("%.1f", hp).replace(",",".").replace(".0","");
    }

//    public float getHealth(LivingEntity entity) {
//        float hp = entity.getHealth() + entity.getAbsorptionAmount();
//        if (entity instanceof PlayerEntity player) switch (Network.server) {
//            case "FunTime", "ReallyWorld", "GulPvP" -> {
//                ScoreboardObjective scoreBoard = player.getScoreboard().getObjectiveForSlot(ScoreboardDisplaySlot.BELOW_NAME);
//                if (scoreBoard != null) {
//                    MutableText text2 = ReadableScoreboardScore.getFormattedScore(player.getScoreboard().getScore(player, scoreBoard), scoreBoard.getNumberFormatOr(StyledNumberFormat.EMPTY));
//                    try {
//                        hp = Float.parseFloat(ColorAssist.removeFormatting(text2.getString()));
//                    } catch (NumberFormatException ignored) {}
//                }
//            }
//        }
//        return MathHelper.clamp(hp,0,entity.getMaxHealth());
//    }

    public static void jump() {
        if (mc.player.isSprinting()) {
            float g = mc.player.getYRot() * ((float)Math.PI / 180F);
            mc.player.addDeltaMovement(new Vec3(-Mth.sin(g) * 0.2F, 0.0F, Mth.cos(g) * 0.2F));
        }
        mc.player.hurtMarked = true;
    }

    public static List<BlockPos> getCube(BlockPos center, float radius) {
        return getCube(center, radius,radius,true);
    }

    public static List<BlockPos> getCube(BlockPos center, float radiusXZ, float radiusY) {
        return getCube(center,radiusXZ,radiusY,true);
    }

    public static List<BlockPos> getCube(BlockPos center, float radiusXZ, float radiusY, boolean down) {
        List<BlockPos> positions = new ArrayList<>();
        int centerX = center.getX();
        int centerY = center.getY();
        int centerZ = center.getZ();
        int posY = down ? centerY - (int) radiusY : centerY;

        for (int x = centerX - (int) radiusXZ; x <= centerX + radiusXZ; x++) {
            for (int z = centerZ - (int) radiusXZ; z <= centerZ + radiusXZ; z++) {
                for (int y = posY; y <= centerY + radiusY; y++) {
                    positions.add(new BlockPos(x, y, z));
                }
            }
        }

        return positions;
    }

    public static List<BlockPos> getCube(BlockPos start, BlockPos end) {
        List<BlockPos> positions = new ArrayList<>();

        for (int x = start.getX(); x <= end.getX(); x++) {
            for (int z = start.getZ(); z <= end.getZ(); z++) {
                for (int y = start.getY(); y <= end.getY(); y++) {
                    positions.add(new BlockPos(x, y, z));
                }
            }
        }

        return positions;
    }

    public static InputConstants.Type getKeyType(int key) {
        return key < 8 ? InputConstants.Type.MOUSE : InputConstants.Type.KEYSYM;
    }

    public static Stream<Entity> streamEntities() {
        return StreamSupport.stream(mc.level.entitiesForRendering().spliterator(), false);
    }

    public static boolean canChangeIntoPose(Pose pose, Vec3 pos) {
        return mc.player.level().noCollision(mc.player, mc.player.getDimensions(pose).makeBoundingBox(pos).deflate(1.0E-7));
    }

    public static boolean isPotionActive(Holder<MobEffect> statusEffect) {
        return mc.player.getActiveEffectsMap().containsKey(statusEffect);
    }

    public static boolean isPlayerInBlock(Block block) {
        return isBoxInBlock(mc.player.getBoundingBox().inflate(-1e-3), block);
    }

    public static boolean isBoxInBlock(AABB box, Block block) {
        return isBox(box,pos -> mc.level.getBlockState(pos).getBlock().equals(block));
    }

    public static boolean isBoxInBlocks(AABB box, List<Block> blocks) {
        return isBox(box,pos -> blocks.contains(mc.level.getBlockState(pos).getBlock()));
    }

    public static boolean isBox(AABB box, Predicate<BlockPos> pos) {
        return BlockPos.betweenClosedStream(box).anyMatch(pos);
    }

    public static boolean isKey(BindSetting setting) {
        KeyBind bind = setting.getValue();
        return mc.screen == null
                && setting.isVisible()
                && bind != null
                && mc.getWindow() != null
                && bind.isDown(mc.getWindow().handle());
    }

    public static boolean isKey(KeyMapping key) {
        return isKey(key.getDefaultKey().getType(), key.getDefaultKey().getValue());
    }

    public static boolean isKey(InputConstants.Type type, int keyCode) {
        if (keyCode != -1) switch (type) {
            case InputConstants.Type.KEYSYM: return GLFW.glfwGetKey(mc.getWindow().handle(), keyCode) == 1;
            case InputConstants.Type.MOUSE: return GLFW.glfwGetMouseButton(mc.getWindow().handle(), keyCode) == 1;
        }
        return false;
    }

    public static boolean isAir(BlockPos blockPos) {
        return isAir(mc.level.getBlockState(blockPos));
    }

    public static boolean isAir(BlockState state) {
        return state.isAir() || state.getBlock().equals(Blocks.CAVE_AIR) || state.getBlock().equals(Blocks.VOID_AIR);
    }

    public static boolean isChat(Screen screen) {return screen instanceof ChatScreen;}
    public static boolean nullCheck() {return mc.player == null || mc.level == null;}
}
