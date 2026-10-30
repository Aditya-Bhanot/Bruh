package releon.ru.api.module.impl.movement;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.InputEvent;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.events.impl.PlayerTravelEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;

public class AirStuck extends Module {
    private static final double RELEASE_FALL_VELOCITY = -0.0784D;

    private Vec3 stuckPosition;

    public AirStuck() {
        super("AirStuck", "Air Stuck", ModuleCategory.MOVEMENT);
    }

    @Override
    protected void onEnable() {
        capturePosition();
        freezePlayer();
    }

    @Override
    protected void onDisable() {
        releasePlayer();
        stuckPosition = null;
    }

    @Override
    public void onTick(Minecraft client) {
        freezePlayer();
    }

    @SubscribeEvent
    private void onPreTick(TickEvent.Pre event) {
        freezePlayer();
    }

    @SubscribeEvent
    private void onInput(InputEvent event) {
        event.inputNone();
    }

    @SubscribeEvent
    private void onTravel(PlayerTravelEvent event) {
        if (!event.isPre()) {
            return;
        }

        freezePlayer();
        event.setMotion(Vec3.ZERO);
        event.setCancelled(true);
    }

    @SubscribeEvent
    private void onPacket(PacketEvent event) {
        if (event.isSend() && event.getPacket() instanceof ServerboundMovePlayerPacket) {
            event.setCancelled(true);
        }
    }

    private void freezePlayer() {
        if (mc.player == null || mc.level == null) {
            stuckPosition = null;
            return;
        }

        if (stuckPosition == null) {
            capturePosition();
        }

        mc.player.setDeltaMovement(Vec3.ZERO);
        mc.player.setPos(stuckPosition.x, stuckPosition.y, stuckPosition.z);
        mc.player.setSprinting(false);
        mc.player.fallDistance = 0.0F;
    }

    private void capturePosition() {
        if (mc.player != null) {
            stuckPosition = mc.player.position();
        }
    }

    private void releasePlayer() {
        if (mc.player == null || mc.level == null) {
            return;
        }

        double fallVelocity = Math.min(mc.player.getDeltaMovement().y, RELEASE_FALL_VELOCITY);
        double releaseMoveY = getReleaseMoveY(fallVelocity);

        if (releaseMoveY != 0.0D) {
            mc.player.setPos(mc.player.getX(), mc.player.getY() + releaseMoveY, mc.player.getZ());
        }

        mc.player.setDeltaMovement(0.0D, fallVelocity, 0.0D);
        mc.player.setSprinting(false);
    }

    private double getReleaseMoveY(double moveY) {
        AABB movedBox = mc.player.getBoundingBox().move(0.0D, moveY, 0.0D);
        return mc.level.noCollision(mc.player, movedBox) ? moveY : 0.0D;
    }
}
