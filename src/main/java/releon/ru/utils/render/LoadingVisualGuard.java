package releon.ru.utils.render;

import net.minecraft.client.Minecraft;

import java.util.Locale;

public final class LoadingVisualGuard {
    private LoadingVisualGuard() {
    }

    public static boolean shouldSuppressHud(Minecraft client) {
        if (client == null) {
            return true;
        }
        return isLoadingVisual(client.screen) || isLoadingVisual(client.getOverlay());
    }

    private static boolean isLoadingVisual(Object visual) {
        if (visual == null) {
            return false;
        }

        String className = visual.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        String fullName = visual.getClass().getName().toLowerCase(Locale.ROOT);

        return className.contains("loading")
                || className.contains("progress")
                || className.contains("connecting")
                || className.contains("downloading")
                || className.contains("terrain")
                || className.contains("generating")
                || className.contains("saving")
                || className.contains("reload")
                || className.contains("resource")
                || className.contains("pack")
                || className.contains("receiving")
                || className.contains("level")
                || className.contains("message")
                || fullName.contains("mojang");
    }
}
