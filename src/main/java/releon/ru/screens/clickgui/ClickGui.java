package releon.ru.screens.clickgui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import releon.ru.screens.clickgui.impl.ClickGuiController;

public final class ClickGui extends Screen {
    public ClickGui() {
        super(Component.literal("Releon ClickGui"));
    }

    @Override
    protected void init() {
        ClickGuiController.init();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (ClickGuiController.keyPressed(event)) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (ClickGuiController.charTyped(event)) {
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        return ClickGuiController.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return ClickGuiController.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return ClickGuiController.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (ClickGuiController.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        ClickGuiController.startClosing();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public static void renderPanels(GuiGraphics graphics, int screenWidth, int screenHeight) {
        ClickGuiController.renderPanels(graphics, screenWidth, screenHeight);
    }

    public static void warmupText() {
        ClickGuiController.warmupText();
    }

    public static void startClosing() {
        ClickGuiController.startClosing();
    }

    public static void resetOverlayState() {
        ClickGuiController.resetOverlayState();
    }

    public static boolean shouldRenderOverlay() {
        return ClickGuiController.shouldRenderOverlay();
    }

    public static void tickMovementKeys() {
        ClickGuiController.tickMovementKeys();
    }
}
