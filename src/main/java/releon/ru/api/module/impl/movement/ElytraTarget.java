package releon.ru.api.module.impl.movement;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.KeyEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.bind.InputType;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.api.settings.impl.BindSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.sounds.SoundManager;

public final class ElytraTarget extends Module {
    private static ElytraTarget instance;
    public static boolean shouldElytraTarget;

    private final NumberSetting elytraFindRange = register(new NumberSetting("Дистанция наводки", "Дальность поиска цели во время полета на элитре.", 32.0, 6.0, 64.0, 1.0));
    private final NumberSetting elytraForward = register(new NumberSetting("Значение перегона", "Упреждение цели во время элитра таргета.", 3.0, 0.0, 6.0, 0.1));
    private final BindSetting forward = register(new BindSetting("Кнопка вкл/выкл перегона", "", KeyBind.NONE));

    public ElytraTarget() {
        super("ElytraTarget", "Elytra Target", ModuleCategory.MOVEMENT);
        instance = this;
    }

    public static ElytraTarget getInstance() {
        return instance;
    }

    public static boolean isTargetingActive() {
        return instance != null && instance.isEnabled() && shouldElytraTarget;
    }

    @Override
    protected void onDisable() {
        shouldElytraTarget = false;
    }

    @SubscribeEvent
    private void onEventKey(KeyEvent event) {
        if (event.action() != GLFW.GLFW_PRESS || !isKeyDown(event)) {
            return;
        }
        shouldElytraTarget = !shouldElytraTarget;
        SoundManager.playSound(shouldElytraTarget ? SoundManager.MODULE_ENABLE : SoundManager.MODULE_DISABLE, 1.0F, 1.0F);
    }

    public float getRange() {
        return elytraFindRange.getFloat();
    }

    public float getForward() {
        return elytraForward.getFloat();
    }

    private boolean isKeyDown(KeyEvent event) {
        KeyBind bind = forward.getValue();
        if (!bind.isBound() || event.key() != bind.getCode()) {
            return false;
        }

        if (bind.getType() == InputType.KEYBOARD) {
            return event.type() == InputConstants.Type.KEYSYM;
        }
        if (bind.getType() == InputType.MOUSE) {
            return event.type() == InputConstants.Type.MOUSE;
        }
        return false;
    }
}
