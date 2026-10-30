package releon.ru.api.module.impl.player;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.AngleConfig;
import releon.ru.api.module.impl.combat.aura.AngleConnection;
import releon.ru.api.module.impl.combat.aura.impl.LinearConstructor;
import releon.ru.api.module.impl.combat.aura.util.TaskPriority;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.api.settings.impl.BindSetting;
import releon.ru.utils.inventory.InventoryFlowManager;
import releon.ru.utils.inventory.InventoryTask;
import releon.ru.utils.inventory.lookup.InventoryUtils;

public final class WindJump extends Module {
    private enum Phase {
        IDLE,
        ROTATING,
        USING
    }

    private static final float THROW_PITCH = 90.0F;
    private static final int ROTATION_WAIT_TICKS = 2;
    private static final long USE_TIMEOUT_MS = 750L;

    private final BindSetting keySetting = register(new BindSetting("Key", "Wind charge key.", KeyBind.keyboard(GLFW.GLFW_KEY_UNKNOWN)));
    private Phase phase = Phase.IDLE;
    private int rotationTicks;
    private long lastThrowTime;
    private long useStartedAt;
    private boolean lastBindDown;

    public WindJump() {
        super("Wind Jump", "Wind charge jump helper.", ModuleCategory.PLAYER);
    }

    @Override
    protected void onDisable() {
        AngleConnection.INSTANCE.startReturning();
        resetState();
        lastBindDown = false;
    }

    @Override
    public void onTick(Minecraft client) {
        if (client.player == null || client.level == null) {
            resetState();
            return;
        }

        handleBind(client);

        if (phase == Phase.ROTATING) {
            Angle throwAngle = new Angle(client.player.getYRot(), THROW_PITCH);
            AngleConnection.INSTANCE.rotateTo(
                    throwAngle,
                    3,
                    new AngleConfig(new LinearConstructor(), true, true),
                    TaskPriority.HIGH_IMPORTANCE_1,
                    this
            );

            rotationTicks++;
            Angle currentRotation = AngleConnection.INSTANCE.getRotation();
            boolean rotationReady = currentRotation != null && currentRotation.getPitch() >= 80.0F;
            boolean waitedEnough = rotationTicks >= ROTATION_WAIT_TICKS;
            if (rotationReady && waitedEnough) {
                scheduleUse(client);
                phase = Phase.USING;
                useStartedAt = System.currentTimeMillis();
                return;
            }
            if (rotationTicks > 10) {
                finishAndReset();
            }
            return;
        }

        if (phase == Phase.USING) {
            if (InventoryTask.isSwapAndUseIdle()
                    && InventoryFlowManager.isIdle()
                    && System.currentTimeMillis() - useStartedAt >= 50L) {
                finishAndReset();
                return;
            }
            if (System.currentTimeMillis() - useStartedAt > USE_TIMEOUT_MS) {
                finishAndReset();
            }
        }
    }

    public boolean isRunning() {
        return phase != Phase.IDLE;
    }

    private void handleBind(Minecraft client) {
        if (client.screen != null || phase != Phase.IDLE || client.getWindow() == null) {
            lastBindDown = false;
            return;
        }
        boolean down = keySetting.getValue().isDown(client.getWindow().handle());
        if (down && !lastBindDown) {
            tryUseWindCharge(client);
        }
        lastBindDown = down;
    }

    private void tryUseWindCharge(Minecraft client) {
        if (System.currentTimeMillis() - lastThrowTime < 100L) {
            return;
        }
        boolean hasHotbarCharge = InventoryUtils.findItemInHotbar(Items.WIND_CHARGE) != -1;
        boolean hasInventoryCharge = InventoryUtils.findItemInInventory(Items.WIND_CHARGE) != -1;
        if (!hasHotbarCharge && !hasInventoryCharge) {
            client.player.displayClientMessage(Component.literal("Wind Charge not found"), false);
            return;
        }
        lastThrowTime = System.currentTimeMillis();
        phase = Phase.ROTATING;
        rotationTicks = 0;
        useStartedAt = 0L;
        AngleConnection.INSTANCE.forcePacketRotation(1);
    }

    private void scheduleUse(Minecraft client) {
        Angle throwAngle = new Angle(client.player.getYRot(), THROW_PITCH);
        int hotbarSlot = InventoryUtils.findItemInHotbar(Items.WIND_CHARGE);
        if (hotbarSlot != -1) {
            InventoryTask.useHotbarItem(hotbarSlot, client.player.getInventory().getSelectedSlot(), true);
            return;
        }
        InventoryTask.swapAndUse(Items.WIND_CHARGE, true, throwAngle);
    }

    private void finishAndReset() {
        AngleConnection.INSTANCE.startReturning();
        resetState();
    }

    private void resetState() {
        phase = Phase.IDLE;
        rotationTicks = 0;
        useStartedAt = 0L;
    }
}
