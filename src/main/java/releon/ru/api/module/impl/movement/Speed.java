package releon.ru.api.module.impl.movement;

import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.PlayerTravelEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.move.MoveUtil;

public final class Speed extends Module {
    private final ModeSetting mode = register(new ModeSetting("Mode", "Speed mode.", "Grim", "Vanilla", "Grim", "FunTime", "HolyWorld"));
    private final NumberSetting speed = register(new NumberSetting("Speed", "Vanilla speed.", 1.5, 1.0, 5.0, 0.1));

    public Speed() {
        super("Speed", "Changes player movement speed.", ModuleCategory.MOVEMENT);
        speed.visibleWhen(() -> mode.is("Vanilla"));
    }

    @SubscribeEvent
    private void onTick(TickEvent event) {
        if (mode.is("Vanilla")) {
            MoveUtil.setVelocity(speed.getValue() / 3.0D);
        }
    }

    @SubscribeEvent
    private void onMotion(PlayerTravelEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (!event.isPre() || client.player == null || client.level == null || !MoveUtil.hasPlayerMovement()) {
            return;
        }
        if (mode.is("FunTime")) {
            handleFunTimeMode(client);
        } else if (mode.is("Grim")) {
            handleGrimMode(client);
        } else if (mode.is("HolyWorld")) {
            handleHolyWorldMode(client);
        }
    }

    private void handleFunTimeMode(Minecraft client) {
        if (!client.player.isSwimming() && !client.player.isFallFlying() && !client.player.isShiftKeyDown()) {
            if (client.player.getBoundingBox().getYsize() < 1.5F) {
                MoveUtil.setVelocity(client.player.hasEffect(MobEffects.SPEED) ? 0.32F : 0.28F);
            }
        }
    }

    private void handleGrimMode(Minecraft client) {
        int collisions = 0;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (isValidCollisionBoostEntity(client, entity) && client.player.getBoundingBox().inflate(0.5F).intersects(entity.getBoundingBox())) {
                collisions++;
            }
        }
        double[] motion = MoveUtil.forward(0.07D * collisions);
        client.player.push(motion[0], 0.0D, motion[1]);
    }

    private void handleHolyWorldMode(Minecraft client) {
        int collisions = 0;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (isValidCollisionBoostEntity(client, entity) && client.player.getBoundingBox().inflate(0.35F).intersects(entity.getBoundingBox())) {
                collisions++;
            }
        }
        double[] motion = MoveUtil.forward(0.0205D * collisions);
        client.player.push(motion[0], 0.0D, motion[1]);
    }

    private boolean isValidCollisionBoostEntity(Minecraft client, Entity entity) {
        if (entity == null || entity == client.player || entity instanceof ArmorStand) {
            return false;
        }
        return entity instanceof LivingEntity || entity.getType().toString().toLowerCase(java.util.Locale.ROOT).contains("boat");
    }
}
