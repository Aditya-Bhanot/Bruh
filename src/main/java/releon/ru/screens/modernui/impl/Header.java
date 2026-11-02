package releon.ru.screens.modernui.impl;

import net.minecraft.client.gui.GuiGraphics;
import releon.ru.api.module.ModuleCategory;
import releon.ru.utils.math.MathUtils;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.awt.Color;

public final class Header {
    public void render(GuiGraphics graphics, float x, float y, float panelWidth, ModuleCategory previousCategory, ModuleCategory selectedCategory, float transition, SearchHandler searchHandler) {
        renderSearch(graphics, x, y, panelWidth, searchHandler);
        renderHeaderCategory(x, y, previousCategory, selectedCategory, transition);
    }

    private void renderSearch(GuiGraphics graphics, float x, float y, float panelWidth, SearchHandler searchHandler) {
        Render2D.rect(x + 287F, y + 29F, 70, 0.5F, 3, new Color(159, 159, 159, 75).getRGB());

        Render2D.text(FontType.ICONS, "U", x + 344F, y + 14F, 15.0F, new Color(255, 255, 255, 255).getRGB());
        renderSearchText(graphics, x + 289.0F, y + 16.0F, 52.0F, 18.0F, searchHandler);
    }

    private void renderSearchText(GuiGraphics graphics, float boxX, float boxY, float boxWidth, float boxHeight, SearchHandler searchHandler) {
        float textX = boxX;
        float textY = boxY + 3.0F;
        float textSize = 7.0F;
        String text = searchHandler.getSearchText();
        float focus = MathUtils.clamp(searchHandler.getSearchFocusAnimation(), 0.0F, 1.0F);

        if (text.isEmpty()) {
            if (searchHandler.isSearchActive()) {
                renderSearchCursor(textX, boxY, boxHeight, searchHandler);
            } else {
                int alpha = Math.round(255.0F * (1.0F - focus));
                if (alpha > 0) {
                    Render2D.text(FontType.SEMIBOLD, "Search", textX, textY, textSize, new Color(128, 128, 128, alpha).getRGB());
                }
            }
            return;
        }

        float textWidth = Render2D.textWidth(FontType.SEMIBOLD, text, textSize);
        searchHandler.updateSearchTextScroll(boxWidth, textWidth);
        Render2D.pushScissor(graphics, boxX, boxY, boxWidth, boxHeight);
        Render2D.text(
                FontType.SEMIBOLD,
                text,
                textX - searchHandler.getSearchTextScrollOffset(),
                textY,
                textSize,
                new Color(255, 255, 255, 255).getRGB()
        );
        if (searchHandler.isSearchActive()) {
            renderSearchCursor(textX - searchHandler.getSearchTextScrollOffset() + cursorTextWidth(searchHandler, textSize), boxY, boxHeight, searchHandler);
        }
        Render2D.popScissor(graphics);
    }

    private void renderSearchCursor(float textAreaX, float boxY, float boxHeight, SearchHandler searchHandler) {
        float cursorAlpha = (float) (Math.sin(searchHandler.getSearchCursorBlink() * Math.PI * 2.0D) * 0.5D + 0.5D);
        if (cursorAlpha <= 0.3F) {
            return;
        }
        Render2D.rect(textAreaX, boxY + 2.0F, 0.5F, boxHeight - 8.0F, 0.0F, new Color(255, 255, 255, Math.round(255.0F * cursorAlpha)).getRGB());
    }

    private void renderHeaderCategory(float x, float y, ModuleCategory previousCategory, ModuleCategory selectedCategory, float transition) {
        float headerSlideDistance = 8.0F;
        float normalAlpha = 1.0F;

        if (previousCategory != null && transition < 1.0F && normalAlpha > 0.01F) {
            float alpha = (1.0F - transition) * normalAlpha;
            float offsetY = transition * headerSlideDistance;
            renderHeaderCategoryLabel(previousCategory, x + 100.0F, y + 16.0F + offsetY, alpha);
        }

        if (normalAlpha > 0.01F) {
            float currentAlpha = (previousCategory == null ? 1.0F : transition) * normalAlpha;
            float currentOffsetY = previousCategory == null ? 0.0F : (1.0F - transition) * -headerSlideDistance;
            renderHeaderCategoryLabel(selectedCategory, x + 100.0F, y + 16.0F + currentOffsetY, currentAlpha);
        }

    }

    private void renderHeaderCategoryLabel(ModuleCategory category, float x, float y, float alphaMultiplier) {
        if (category == null) {
            return;
        }
        int alpha = Math.round(255F * MathUtils.clamp(alphaMultiplier, 0.0F, 1.0F));
        if (alpha <= 0) {
            return;
        }

        int color = new Color(255, 255, 255, alpha).getRGB();
        float iconSize = 8.5F;
        float iconWidth = Render2D.textWidth(FontType.MAINMENUSCREEN, category.icon(), iconSize);
        float iconOffsetX = -37.0F;
        float iconOffsetY = 4.5F;
        Render2D.text(FontType.MAINMENUSCREEN, category.icon(), x + iconOffsetX, y + 7.5F - iconOffsetY, iconSize, color);
        Render2D.rect(x + iconWidth + 2.5F + iconOffsetX, y + 10.0F - iconOffsetY, 4.0F, 4.0F, 1.5F, color);
        Render2D.text(FontType.BOLD, category.getDisplayName(), x + iconWidth + 10.0F + iconOffsetX, y + 7.5F - iconOffsetY, 7.0F, color);
    }

    public boolean isSearchBoxHovered(double mouseX, double mouseY, float x, float y) {
        return mouseX >= x + 287.0F
                && mouseX <= x + 357.0F
                && mouseY >= y + 16.0F
                && mouseY <= y + 34.0F;
    }

    private float cursorTextWidth(SearchHandler searchHandler, float textSize) {
        String text = searchHandler.getSearchText();
        int cursor = Math.max(0, Math.min(searchHandler.getSearchCursorPosition(), text.length()));
        return Render2D.textWidth(FontType.SEMIBOLD, text.substring(0, cursor), textSize) + 1.0F;
    }
}
