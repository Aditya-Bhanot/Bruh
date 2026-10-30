package releon.ru.api.drag.core;

import net.minecraft.client.Minecraft;
import releon.ru.utils.render.ui.Render2DCoordinateSpace;

public record ElementScreen(float width, float height, float coordinateScale) {
    public static ElementScreen current() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getWindow() == null) {
            return new ElementScreen(1.0f, 1.0f, 1.0f);
        }

        float scale = Render2DCoordinateSpace.guiIndependentScale();
        float safeScale = Math.max(0.0001f, scale);
        return new ElementScreen(
                Math.max(1.0f, client.getWindow().getGuiScaledWidth() / safeScale),
                Math.max(1.0f, client.getWindow().getGuiScaledHeight() / safeScale),
                scale
        );
    }

    public float clampX(float x, float width, float margin) {
        float max = Math.max(margin, this.width - width - margin);
        return clamp(x, margin, max);
    }

    public float clampY(float y, float height, float margin) {
        float max = Math.max(margin, this.height - height - margin);
        return clamp(y, margin, max);
    }

    public boolean valid() {
        return width > 0.0f && height > 0.0f && Float.isFinite(width) && Float.isFinite(height);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
