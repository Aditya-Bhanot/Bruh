package attrium.ru.manager;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.impl.visual.NotificationsModule;
import attrium.ru.screens.GlassClickGuiScreen;

import java.util.ArrayList;
import java.util.List;

/**
 * CleanState (Panic / Vanilla-Restore) Manager (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * Single-frame, zero-allocation state reset:
 *  - Suppresses NotificationsModule toggle alerts while disabling all active modules.
 *  - Snapshots active modules into a pre-allocated reusable list for optional re-press restore.
 *  - Releases RotationManager spoofing locks immediately so camera/movement return to raw vanilla.
 *  - Restores vanilla gamma (0.0 - 1.0) and closes GlassClickGuiScreen if open.
 */
public final class CleanStateManager {
    private static volatile boolean cleanStateActive = false;
    private static final List<Module> PREVIOUS_ACTIVE_MODULES = new ArrayList<>(64);
    private static double savedGamma = 1.0D;

    private static int panicKey = -1; // Unbound by default until user sets in Client tab
    private static boolean restoreOnRePress = true;
    private static long lastPanicTriggerMs = 0L;

    private CleanStateManager() {
    }

    public static boolean isCleanStateActive() {
        return cleanStateActive;
    }

    public static int getPanicKey() {
        return panicKey;
    }

    public static void setPanicKey(int key) {
        if (key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_LAST) {
            panicKey = key;
        } else {
            panicKey = -1;
        }
    }

    public static boolean isRestoreOnRePress() {
        return restoreOnRePress;
    }

    public static void setRestoreOnRePress(boolean enabled) {
        restoreOnRePress = enabled;
    }

    /**
     * Triggered from GLFW key callback at HEAD so it fires even while a GUI or inventory is open.
     */
    public static boolean onKeyPressed(int key) {
        if (panicKey <= 0 || panicKey == GLFW.GLFW_KEY_UNKNOWN || key != panicKey) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastPanicTriggerMs < 200L) {
            return true;
        }
        lastPanicTriggerMs = now;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && !mc.isSameThread()) {
            mc.execute(CleanStateManager::toggleCleanState);
        } else {
            toggleCleanState();
        }
        return true;
    }

    public static void toggleCleanState() {
        if (!cleanStateActive) {
            activateCleanState();
        } else if (restoreOnRePress) {
            restorePreviousState();
        }
    }

    /**
     * Instant 1-frame vanilla restore with zero notification spam.
     */
    public static synchronized void activateCleanState() {
        Minecraft mc = Minecraft.getInstance();
        NotificationsModule.setSuppressToggles(true);
        try {
            PREVIOUS_ACTIVE_MODULES.clear();

            if (mc != null && mc.options != null && mc.options.gamma() != null) {
                try {
                    savedGamma = mc.options.gamma().get();
                } catch (Throwable ignored) {
                    savedGamma = 1.0D;
                }
            }

            List<Module> allModules = HudManager.getAllClientModules();
            if (allModules != null) {
                for (int i = 0, size = allModules.size(); i < size; i++) {
                    Module mod = allModules.get(i);
                    if (mod != null && mod.isEnabled()) {
                        PREVIOUS_ACTIVE_MODULES.add(mod);
                        try {
                            mod.setEnabled(false);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }

            try {
                RotationManager.getInstance().forceReleaseSpoofing();
            } catch (Throwable ignored) {
            }

            if (mc != null && mc.options != null && mc.options.gamma() != null) {
                try {
                    mc.options.gamma().set(Mth.clamp(savedGamma, 0.0D, 1.0D));
                } catch (Throwable ignored) {
                }
            }

            if (mc != null && mc.screen instanceof GlassClickGuiScreen) {
                mc.setScreen(null);
            }

            cleanStateActive = true;
        } finally {
            NotificationsModule.setSuppressToggles(false);
        }
    }

    /**
     * Restores the previously active modules when Restore on Re-Press is enabled.
     */
    public static synchronized void restorePreviousState() {
        if (!cleanStateActive) {
            return;
        }
        NotificationsModule.setSuppressToggles(true);
        try {
            for (int i = 0, size = PREVIOUS_ACTIVE_MODULES.size(); i < size; i++) {
                Module mod = PREVIOUS_ACTIVE_MODULES.get(i);
                if (mod != null && !mod.isEnabled()) {
                    try {
                        mod.setEnabled(true);
                    } catch (Throwable ignored) {
                    }
                }
            }
            PREVIOUS_ACTIVE_MODULES.clear();
            cleanStateActive = false;
        } finally {
            NotificationsModule.setSuppressToggles(false);
        }
    }
}
