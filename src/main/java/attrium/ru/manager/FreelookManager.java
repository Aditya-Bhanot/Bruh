package attrium.ru.manager;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * 100% Legit Client-Only Freelook Camera Controller (Minecraft 1.21.11 Mojang Mappings).
 *
 * - Never modifies player yRot, xRot, head rotation, movement vector, or server packets.
 * - Cancels LocalPlayer Entity#turn while active and routes mouse deltas purely to cameraYaw/cameraPitch.
 * - Uses vanilla Camera#setup -> setRotation(FF)V so vanilla wall-collision (getMaxZoom) stays intact.
 * - Saves & restores the exact previous CameraType on activate/deactivate/world-leave.
 */
public final class FreelookManager {
    private static volatile boolean active = false;
    private static volatile boolean holdMode = true;
    private static volatile boolean autoThirdPerson = true;
    private static volatile boolean invertPitch = false;
    private static volatile float sensitivity = 1.0F;

    private static float cameraYaw = 0.0F;
    private static float cameraPitch = 0.0F;
    private static CameraType savedCameraType = null;

    private FreelookManager() {
    }

    public static boolean isFreelooking() {
        return active;
    }

    public static boolean isHoldMode() {
        return holdMode;
    }

    public static void setHoldMode(boolean hold) {
        holdMode = hold;
    }

    public static void setAutoThirdPerson(boolean enabled) {
        autoThirdPerson = enabled;
    }

    public static void setInvertPitch(boolean invert) {
        invertPitch = invert;
    }

    public static void setSensitivity(float sens) {
        sensitivity = Mth.clamp(sens, 0.25F, 2.0F);
    }

    public static float getCameraYaw() {
        return cameraYaw;
    }

    public static float getCameraPitch() {
        return cameraPitch;
    }

    public static void activate(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null) {
            return;
        }
        if (!active) {
            cameraYaw = mc.player.getYRot();
            cameraPitch = mc.player.getXRot();
            if (autoThirdPerson && mc.options != null) {
                savedCameraType = mc.options.getCameraType();
                if (savedCameraType != CameraType.THIRD_PERSON_BACK) {
                    mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                }
            } else {
                savedCameraType = null;
            }
            active = true;
        }
    }

    public static void deactivate(Minecraft mc) {
        if (!active) {
            return;
        }
        active = false;
        if (savedCameraType != null && mc != null && mc.options != null) {
            mc.options.setCameraType(savedCameraType);
        }
        savedCameraType = null;
    }

    public static void onMouseTurn(double yRotDelta, double xRotDelta) {
        if (!active) {
            return;
        }
        float mult = 0.15F * sensitivity;
        cameraYaw += (float) (yRotDelta * mult);
        float pitchStep = (float) (xRotDelta * mult);
        if (invertPitch) {
            pitchStep = -pitchStep;
        }
        cameraPitch = Mth.clamp(cameraPitch + pitchStep, -90.0F, 90.0F);
    }

    public static void tickSafety(Minecraft mc) {
        if (active && (mc == null || mc.player == null || mc.level == null)) {
            deactivate(mc);
        }
    }
}
