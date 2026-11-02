package releon.ru.utils.render.ui.arc;

import net.minecraft.client.gui.GuiGraphics;

public record BuiltArcOutline(
        float x,
        float y,
        float size,
        float arcThickness,
        float degree,
        float rotation,
        float outlineThickness,
        int fillColor,
        int outlineColor
) {
    public void render(GuiGraphics graphics) {
        ArcOutlineRenderer.getInstance().draw(graphics, this);
    }

    public boolean visible() {
        return size > 0.0f
                && arcThickness > 0.0f
                && degree > 0.0f
                && outlineThickness > 0.0f
                && ((fillColor >>> 24) != 0 || (outlineColor >>> 24) != 0);
    }
}
