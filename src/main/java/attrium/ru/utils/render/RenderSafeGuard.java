package attrium.ru.utils.render;

import attrium.ru.api.module.Module;
import attrium.ru.api.module.impl.visual.NotificationsModule;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zero-Overhead Fault-Tolerant RenderSafeGuard (Minecraft 1.21.11 / FoldCraftLauncher).
 *
 * Isolates rendering exceptions per HUD widget and 3D visual module so transient
 * NullPointerExceptions during world load or dimension transitions never crash the JVM.
 *  - Zero happy-path allocations or map lookups (fast boolean guard).
 *  - Auto-disables any faulting module after 2 consecutive render errors.
 *  - Dispatches a throttled (5s) NotificationsModule toast silently without log spam.
 */
public final class RenderSafeGuard {
    private static final class ErrorTracker {
        int consecutiveErrors = 0;
        long lastToastMs = 0L;
    }

    private static final Map<Module, ErrorTracker> TRACKERS = new ConcurrentHashMap<>();
    private static volatile boolean anyModuleHasErrors = false;

    private RenderSafeGuard() {
    }

    public static void onRenderSuccess(Module module) {
        if (!anyModuleHasErrors || module == null) {
            return;
        }
        ErrorTracker tracker = TRACKERS.get(module);
        if (tracker != null && tracker.consecutiveErrors != 0) {
            tracker.consecutiveErrors = 0;
        }
    }

    public static void onRenderFailure(Module module, Throwable t) {
        if (module == null) {
            return;
        }
        anyModuleHasErrors = true;
        ErrorTracker tracker = TRACKERS.computeIfAbsent(module, m -> new ErrorTracker());
        tracker.consecutiveErrors++;
        if (tracker.consecutiveErrors >= 2) {
            tracker.consecutiveErrors = 0;
            try {
                if (module.isEnabled()) {
                    module.setEnabled(false);
                }
            } catch (Throwable ignored) {
            }
            long now = System.currentTimeMillis();
            if (now - tracker.lastToastMs > 5000L) {
                tracker.lastToastMs = now;
                String name = (module.getName() != null && !module.getName().isEmpty())
                        ? module.getName()
                        : module.getClass().getSimpleName();
                NotificationsModule.pushCustom(
                        "Widget Paused",
                        name + " disabled due to render error",
                        NotificationsModule.TYPE_DISABLED
                );
            }
        }
    }
}
