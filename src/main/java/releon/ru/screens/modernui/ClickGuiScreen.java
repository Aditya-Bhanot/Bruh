package releon.ru.screens.modernui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.ModuleManager;
import releon.ru.screens.modernui.impl.*;
import releon.ru.utils.math.MathUtils;
import releon.ru.utils.render.animation.modernfx.Decelerate;
import releon.ru.utils.render.animation.modernfx.Direction;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.manager.Manager;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ClickGuiScreen extends Screen {
    private static final int OPEN_ANIMATION_MS = 250;
    private static final float OPEN_SCALE_START = 0.92F;
    private static final float BACKGROUND_DIM_MAX_ALPHA = 100.0F;

    private final PanelBackground background = new PanelBackground();
    private final Header header = new Header();
    private final CategoryPanel categoryPanel = new CategoryPanel();
    private final SearchHandler searchHandler = new SearchHandler();
    private final Decelerate openAnimation = MathUtils.createAnimation(OPEN_ANIMATION_MS, Direction.FORWARDS);

    private boolean closing;

    public ClickGuiScreen() {
        super(Component.literal("ClickGui"));
    }

    @Override
    protected void init() {
        super.init();
        closing = false;
        WorldAnimation.stop();
        openAnimation.setDirection(Direction.FORWARDS);
        openAnimation.reset();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        float progress = openProgress();
        Render2D.beginFrame(graphics);
        renderBackgroundDim(progress, width, height);
        Render2D.flush();

        graphics.nextStratum();
        // Blur removed due to Minecraft 1.21.11 limitation (can only blur once per frame)

        Render2D.beginFrame(graphics);
        float scale = OPEN_SCALE_START + (1.0F - OPEN_SCALE_START) * progress;
        float centerX = width * 0.5F;
        float centerY = height * 0.5F;
        graphics.pose().pushMatrix();
        graphics.pose().translate(centerX, centerY);
        graphics.pose().scale(scale, scale);
        graphics.pose().translate(-centerX, -centerY);
        renderContent(graphics, mouseX, mouseY);
        Render2D.flush();
        graphics.pose().popMatrix();
    }

    public void renderDetachedBackground(GuiGraphics graphics, float progress, float screenWidth, float screenHeight) {
        Render2D.beginFrame(graphics);
        renderBackgroundDim(progress, screenWidth, screenHeight);
        Render2D.flush();
    }

    public void renderDetached(GuiGraphics graphics, float partialTick) {
        graphics.nextStratum();
        Render2D.beginFrame(graphics);
        renderContent(graphics, Integer.MIN_VALUE, Integer.MIN_VALUE);
        Render2D.flush();
    }

    private void renderBackgroundDim(float progress, float screenWidth, float screenHeight) {
        float alpha = BACKGROUND_DIM_MAX_ALPHA * clamp(progress, 0.0F, 1.0F);
        if (alpha <= 0.5F) {
            return;
        }
        Render2D.rect(
                -5.0F,
                -5.0F,
                screenWidth + 10.0F,
                screenHeight + 10.0F,
                0.0F,
                new Color(0, 0, 0, Math.round(alpha)).getRGB()
        );
    }

    private void renderContent(GuiGraphics graphics, int mouseX, int mouseY) {
        float panelWidth = 380.0F;
        float panelHeight = 240.0F;
        float x = (width - panelWidth) * 0.5F;
        float y = (height - panelHeight) * 0.5F;

        searchHandler.updateAnimations();
        background.render(graphics, x, y, panelWidth, panelHeight, categoryPanel.selectedCategory(), !normalizeSearch(searchHandler.getSearchText()).isEmpty(), currentModules(), mouseX, mouseY);
        Render2D.beginFrame(graphics);
        header.render(graphics, x, y, panelWidth, categoryPanel.previousCategory(), categoryPanel.selectedCategory(), categoryPanel.headerProgress(), searchHandler);
        categoryPanel.render(x, y);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (closing) {
            return false;
        }
        if (searchHandler.isSearchActive() && searchHandler.handleSearchKey(event)) {
            return true;
        }
        if (background.keyPressed(event)) {
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_RIGHT_CONTROL || event.key() == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (closing) {
            return false;
        }
        if (searchHandler.isSearchActive() && searchHandler.handleSearchChar(event)) {
            return true;
        }
        if (background.charTyped(event)) {
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (closing) {
            return false;
        }
        float panelWidth = 380.0F;
        float panelHeight = 240.0F;
        float x = (width - panelWidth) * 0.5F;
        float y = (height - panelHeight) * 0.5F;

        boolean searchHovered = header.isSearchBoxHovered(event.x(), event.y(), x, y);
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && searchHovered) {
            searchHandler.setSearchActive(true);
            return true;
        }
        if (searchHandler.isSearchActive() && !searchHovered) {
            searchHandler.setSearchActive(false);
        }
        if (background.mouseClicked(event, doubled)) {
            return true;
        }
        if (categoryPanel.mouseClicked(event, x, y)) {
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (closing) {
            return false;
        }
        if (background.mouseReleased(event)) {
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (closing) {
            return false;
        }
        if (background.mouseDragged(event, dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (closing) {
            return false;
        }
        if (background.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (closing) {
            return;
        }
        closing = true;
        float progress = openProgress();
        WorldAnimation.start(this, progress, progress);
        minecraft.setScreen(null);
    }

    private List<Module> currentModules() {
        String query = normalizeSearch(searchHandler.getSearchText());
        if (query.isEmpty()) {
            return modulesForCategory(categoryPanel.selectedCategory());
        }

        ModuleManager manager = Manager.getModules();
        if (manager == null) {
            return List.of();
        }

        List<Module> filtered = new ArrayList<>();
        for (Module module : manager.getModules()) {
            if (normalizeSearch(module.getName()).contains(query)) {
                filtered.add(module);
            }
        }
        return filtered;
    }

    private List<Module> modulesForCategory(ModuleCategory category) {
        ModuleManager manager = Manager.getModules();
        if (manager == null || category == null) {
            return List.of();
        }
        return manager.getByCategory(category);
    }


    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private String normalizeSearch(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private float openProgress() {
        return clamp(openAnimation.getOutput().floatValue(), 0.0F, 1.0F);
    }
}
