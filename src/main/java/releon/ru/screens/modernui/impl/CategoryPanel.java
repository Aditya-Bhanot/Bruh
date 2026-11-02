package releon.ru.screens.modernui.impl;

import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.module.ModuleCategory;
import releon.ru.utils.math.MathUtils;
import releon.ru.utils.render.animation.modernfx.Decelerate;
import releon.ru.utils.render.animation.modernfx.Direction;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.awt.Color;

public final class CategoryPanel {
    private final Decelerate[] categoryAnimations = new Decelerate[ModuleCategory.values().length];
    private final Decelerate headerTransition = MathUtils.createAnimation(333, Direction.FORWARDS);
    private static ModuleCategory persistedCategory = ModuleCategory.COMBAT;
    private ModuleCategory previousCategory;
    private ModuleCategory selectedCategory = persistedCategory;

    public CategoryPanel() {
        int categoryAnimationMs = 220;
        for (ModuleCategory category : ModuleCategory.values()) {
            Direction direction = category == selectedCategory ? Direction.FORWARDS : Direction.BACKWARDS;
            categoryAnimations[category.ordinal()] = MathUtils.createAnimation(categoryAnimationMs, direction);
        }
    }

    public void render(float x, float y) {
        float categoryStartY = 70.0F;
        float categoryStepY = 22.5F;
        ModuleCategory[] categories = ModuleCategory.values();
        for (int i = 0; i < categories.length; i++) {
            ModuleCategory category = categories[i];
            renderCategoryItem(x, y + categoryStartY + i * categoryStepY, category, categoryProgress(category));
        }
    }

    public boolean mouseClicked(MouseButtonEvent event, float x, float y) {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        ModuleCategory category = categoryAt(event.x(), event.y(), x, y);
        if (category == null) {
            return false;
        }
        selectCategory(category);
        return true;
    }

    public ModuleCategory previousCategory() {
        return previousCategory;
    }

    public ModuleCategory selectedCategory() {
        return selectedCategory;
    }

    public float headerProgress() {
        return MathUtils.clamp(headerTransition.getOutput().floatValue(), 0.0F, 1.0F);
    }

    private void renderCategoryItem(float x, float itemY, ModuleCategory category, float animation) {
        int colorValue = Math.round(200.0F + 55.0F * animation);
        int alpha = Math.round(200.0F + 55.0F * animation);
        int color = new Color(colorValue, colorValue, colorValue, alpha).getRGB();
        float iconX = x + 22.5F;

        Render2D.text(FontType.MAINMENUSCREEN, category.icon(), iconX, itemY, 10F, color);
    }

    private float categoryProgress(ModuleCategory category) {
        return MathUtils.clamp(categoryAnimations[category.ordinal()].getOutput().floatValue(), 0.0F, 1.0F);
    }

    public boolean selectCategory(ModuleCategory category) {
        if (category == null || category == selectedCategory) {
            return false;
        }

        previousCategory = selectedCategory;
        selectedCategory = category;
        persistedCategory = category;
        headerTransition.setDirection(Direction.FORWARDS);
        headerTransition.reset();

        for (ModuleCategory entry : ModuleCategory.values()) {
            categoryAnimations[entry.ordinal()].setDirection(entry == selectedCategory ? Direction.FORWARDS : Direction.BACKWARDS);
        }
        return true;
    }

    private ModuleCategory categoryAt(double mouseX, double mouseY, float x, float y) {
        float categoryPanelX = 7.5F;
        float categoryPanelWidth = 83.0F;
        float categoryStartY = 70.0F;
        float categoryStepY = 22.5F;
        float categoryHitHeight = 14.0F;
        if (mouseX < x + categoryPanelX || mouseX > x + categoryPanelX + categoryPanelWidth) {
            return null;
        }

        ModuleCategory[] categories = ModuleCategory.values();
        for (int i = 0; i < categories.length; i++) {
            float categoryY = y + categoryStartY + i * categoryStepY;
            if (mouseY >= categoryY && mouseY <= categoryY + categoryHitHeight) {
                return categories[i];
            }
        }
        return null;
    }
}
