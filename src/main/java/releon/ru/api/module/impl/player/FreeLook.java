package releon.ru.api.module.impl.player;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.CameraEvent;
import releon.ru.api.events.impl.MouseRotationEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.MathAngle;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.api.settings.impl.BindSetting;

public final class FreeLook extends Module {
    public static final BindSetting FREE_LOOK_BIND = new BindSetting("Free Look", "Free look key.", KeyBind.keyboard(GLFW.GLFW_KEY_LEFT_ALT));
    private static FreeLook instance;
    private CameraType perspective;
    private Angle angle;

    public FreeLook() {
        super("Free Look", "Free camera look while holding bind.", ModuleCategory.PLAYER);
        instance = this;
        register(FREE_LOOK_BIND);
    }

    public static Angle getActiveAngle() {
        FreeLook freeLook = instance;
        Minecraft client = Minecraft.getInstance();
        if (freeLook == null || !freeLook.isEnabled() || !freeLook.isBindDown(client)) {
            return null;
        }
        freeLook.handleFreeLookActivation(client);
        return freeLook.angle;
    }

    @Override
    protected void onDisable() {
        handleFreeLookDeactivation();
    }

    @Override
    public void onTick(Minecraft client) {
        if (isBindDown(client)) {
            handleFreeLookActivation(client);
        } else if (perspective != null) {
            handleFreeLookDeactivation();
        }
    }

    @SubscribeEvent
    private void onMouseRotation(MouseRotationEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (!isBindDown(client)) {
            angle = null;
            return;
        }
        if (angle == null) {
            angle = MathAngle.cameraAngle();
        }
        angle.setYaw(angle.getYaw() + event.getCursorDeltaX() * 0.15F);
        angle.setPitch(Mth.clamp(angle.getPitch() + event.getCursorDeltaY() * 0.15F, -90.0F, 90.0F));
        event.setCancelled(true);
    }

    @SubscribeEvent
    private void onCamera(CameraEvent event) {
        if (isBindDown(Minecraft.getInstance()) && angle != null) {
            event.setAngle(angle);
            event.setCancelled(true);
        }
    }

    private void handleFreeLookActivation(Minecraft client) {
        if (perspective == null) {
            perspective = client.options.getCameraType();
        }
        if (client.options.getCameraType().isFirstPerson()) {
            client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }
        if (angle == null) {
            angle = MathAngle.cameraAngle();
        }
    }

    private void handleFreeLookDeactivation() {
        Minecraft client = Minecraft.getInstance();
        if (perspective != null && client.options != null) {
            client.options.setCameraType(perspective);
        }
        perspective = null;
        angle = null;
    }

    private boolean isBindDown(Minecraft client) {
        return client != null && client.screen == null && client.getWindow() != null && FREE_LOOK_BIND.getValue().isDown(client.getWindow().handle());
    }
}
