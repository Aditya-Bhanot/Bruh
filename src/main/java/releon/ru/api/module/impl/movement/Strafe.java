package releon.ru.api.module.impl.movement;

import net.minecraft.client.Minecraft;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.AngleConfig;
import releon.ru.api.module.impl.combat.aura.AngleConnection;
import releon.ru.api.module.impl.combat.aura.util.TaskPriority;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.move.MoveUtil;

public final class Strafe extends Module {
    private final ModeSetting mode = register(new ModeSetting("Mode", "Strafe mode.", "Matrix", "Matrix", "Grim"));
    private final NumberSetting speed = register(new NumberSetting("Speed", "Strafe speed.", 0.32, 0.05, 1.0, 0.01));
    private final Angle rotation = new Angle(0.0F, 0.0F);
    private float lastYaw;
    private float lastPitch;

    public Strafe() {
        super("Strafe", "Stabilizes directional movement.", ModuleCategory.MOVEMENT);
        speed.visibleWhen(() -> mode.is("Matrix"));
    }

    @Override
    protected void onEnable() {
        Minecraft client = Minecraft.getInstance();
        lastYaw = client.player != null ? client.player.getYRot() : 0.0F;
        lastPitch = client.player != null ? client.player.getXRot() : 0.0F;
    }

    @Override
    public void onTick(Minecraft client) {
        if (client.player == null || client.level == null) {
            return;
        }
        boolean moving = MoveUtil.hasPlayerMovement();
        float yaw = client.player.getYRot();
        if (mode.is("Matrix")) {
            handleMatrixMode(moving);
        } else if (mode.is("Grim")) {
            handleGrimMode(client, moving);
        }
        lastYaw = yaw;
        lastPitch = client.player.getXRot();
    }

    private void handleMatrixMode(boolean moving) {
        if (moving) {
            MoveUtil.moveYaw(Minecraft.getInstance().player.getYRot());
            MoveUtil.setVelocity(speed.getValue() * 1.5D);
        } else {
            MoveUtil.setVelocity(0.0D);
        }
    }

    private void handleGrimMode(Minecraft client, boolean moving) {
        if (!moving) {
            return;
        }
        float yaw = MoveUtil.moveYaw(client.player.getYRot());
        rotation.setYaw(yaw);
        rotation.setPitch(client.player.getXRot());
        if (AuraModule.target == null) {
            AngleConnection.INSTANCE.rotateTo(rotation, 1, AngleConfig.DEFAULT, TaskPriority.HIGH_IMPORTANCE_1, this);
        }
    }
}
