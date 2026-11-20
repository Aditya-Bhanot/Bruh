package attrium.ru.api.module.impl.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.ModuleCategory;
import attrium.ru.api.settings.impl.NumberSetting;

/**
 * Shared abstract base class for all standalone Attrium HUD widget modules.
 *
 * Provides:
 *  - Shared Scale (0.75x - 1.50x) and Background Opacity (0 - 255) settings.
 *  - Hidden normalized screen coordinate settings (xRatio, yRatio in 0.0 - 1.0) that
 *    automatically persist in Attrium's JSON config across all mobile resolutions.
 *  - Cached bounding-box hit detection for the ClickGUI interactive drag-and-drop Layout Editor.
 */
public abstract class HudModule extends Module {
    private final String iconGlyph;
    private final double defaultXRatio;
    private final double defaultYRatio;

    protected final NumberSetting scale = register(new NumberSetting(
            "Scale",
            "Widget display scale multiplier (0.75x - 1.50x).",
            1.00D, 0.75D, 1.50D, 0.05D
    ));

    protected final NumberSetting bgOpacity = register(new NumberSetting(
            "Background Opacity",
            "Glassmorphic background opacity (0 - 255).",
            204.0D, 0.0D, 255.0D, 5.0D
    ));

    // Hidden internal normalized coordinates (automatically saved/loaded by Attrium JSON config)
    protected final NumberSetting xRatio;
    protected final NumberSetting yRatio;

    // Cached screen-space bounding box for Layout Editor hit-testing & magnetic snapping
    public int lastBoxX;
    public int lastBoxY;
    public int lastBoxW;
    public int lastBoxH;

    protected HudModule(String name, String description, String iconGlyph,
                        double defaultXRatio, double defaultYRatio,
                        double defaultBgOpacity, boolean enabledByDefault) {
        super(name, description, ModuleCategory.VISUAL);
        this.iconGlyph = iconGlyph;
        this.defaultXRatio = defaultXRatio;
        this.defaultYRatio = defaultYRatio;

        this.bgOpacity.setValue(defaultBgOpacity);
        this.xRatio = register(new NumberSetting(
                "_xRatio",
                "Internal normalized X screen ratio (0.0 - 1.0).",
                defaultXRatio, 0.0D, 1.0D, 0.001D
        ));
        this.yRatio = register(new NumberSetting(
                "_yRatio",
                "Internal normalized Y screen ratio (0.0 - 1.0).",
                defaultYRatio, 0.0D, 1.0D, 0.001D
        ));
        this.xRatio.visibleWhen(() -> false);
        this.yRatio.visibleWhen(() -> false);

        if (enabledByDefault && !this.isEnabled()) {
            this.setEnabled(true);
        }
    }

    public String getIconGlyph() {
        return iconGlyph;
    }

    public float getHudScale() {
        return Mth.clamp(scale.getValue().floatValue(), 0.75F, 1.50F);
    }

    public int getBackgroundAlphaByte() {
        return Mth.clamp(bgOpacity.getValue().intValue(), 0, 255);
    }

    public float getXRatio() {
        return Mth.clamp(xRatio.getValue().floatValue(), 0.0F, 1.0F);
    }

    public float getYRatio() {
        return Mth.clamp(yRatio.getValue().floatValue(), 0.0F, 1.0F);
    }

    public void setPositionRatio(float normX, float normY) {
        this.xRatio.setValue((double) Mth.clamp(normX, 0.0F, 1.0F));
        this.yRatio.setValue((double) Mth.clamp(normY, 0.0F, 1.0F));
    }

    public void resetPosition() {
        this.xRatio.setValue(defaultXRatio);
        this.yRatio.setValue(defaultYRatio);
    }

    public boolean isHovered(double mouseX, double mouseY) {
        return lastBoxW > 0 && lastBoxH > 0
                && mouseX >= (lastBoxX - 4) && mouseX <= (lastBoxX + lastBoxW + 4)
                && mouseY >= (lastBoxY - 4) && mouseY <= (lastBoxY + lastBoxH + 4);
    }

    public boolean usesRightAnchorWhenOnRightHalf() {
        return false;
    }

    public boolean usesBottomAnchorWhenOnBottomHalf() {
        return false;
    }

    /**
     * Renders this HUD widget in 2D screen space with zero per-frame allocations.
     */
    public abstract void renderHud(
            Minecraft mc,
            GuiGraphics guiGraphics,
            Font font,
            int screenW,
            int screenH,
            float dt,
            float animClockSeconds,
            boolean previewMode,
            boolean editorMode
    );
}
