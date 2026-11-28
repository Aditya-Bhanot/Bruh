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

    private static void applyCameraTypeWithRefresh(Minecraft mc, CameraType targetType) {
        if (mc == null || mc.options == null || targetType == null) {
            return;
        }
        CameraType prev = mc.options.getCameraType();
        if (prev == targetType) {
            return;
        }
        mc.options.setCameraType(targetType);
        try {
            if (prev.isFirstPerson() != targetType.isFirstPerson() && mc.gameRenderer != null) {
                mc.gameRenderer.checkEntityPostEffect(targetType.isFirstPerson() ? mc.getCameraEntity() : null);
            }
            if (mc.levelRenderer != null) {
                mc.levelRenderer.needsUpdate();
            }
        } catch (Throwable ignored) {
        }
    }

    public static void activate(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null) {
            return;
        }
        if (!active) {
            cameraYaw = mc.player.getYRot();
            cameraPitch = mc.player.getXRot();
            if (autoThirdPerson && mc.options != null) {
                CameraType current = mc.options.getCameraType();
                if (savedCameraType == null) {
                    savedCameraType = current;
                }
                applyCameraTypeWithRefresh(mc, CameraType.THIRD_PERSON_BACK);
            } else {
                savedCameraType = null;
            }
            active = true;
        } else if (autoThirdPerson && mc.options != null && mc.options.getCameraType() != CameraType.THIRD_PERSON_BACK) {
            if (savedCameraType == null) {
                savedCameraType = mc.options.getCameraType();
            }
            applyCameraTypeWithRefresh(mc, CameraType.THIRD_PERSON_BACK);
        }
    }

    public static void deactivate(Minecraft mc) {
        if (!active) {
            return;
        }
        active = false;
        if (savedCameraType != null && mc != null && mc.options != null) {
            applyCameraTypeWithRefresh(mc, savedCameraType);
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
