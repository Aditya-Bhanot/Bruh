package releon.ru.screens.modernui.impl;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.screens.modernui.settings.SettingCardController;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.awt.Color;
import java.util.List;

public final class PanelBackground {
    private final SettingCardController settings = new SettingCardController();

    public void render(GuiGraphics graphics, float x, float y, float panelWidth, float panelHeight, ModuleCategory category, boolean searchMode, List<Module> modules, int mouseX, int mouseY) {
        renderShell(x, y, panelWidth, panelHeight);
        settings.render(graphics, x, y, panelWidth, panelHeight, category, searchMode, modules, mouseX, mouseY);
    }

    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        return settings.mouseClicked(event, doubled);
    }

    public boolean mouseReleased(MouseButtonEvent event) {
        return settings.mouseReleased(event);
    }

    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        return settings.mouseDragged(event, dragX, dragY);
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return settings.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    public boolean keyPressed(KeyEvent event) {
        return settings.keyPressed(event);
    }

    public boolean charTyped(CharacterEvent event) {
        return settings.charTyped(event);
    }

    private void renderShell(float x, float y, float panelWidth, float panelHeight) {
        Render2D.blur(
                x,
                y - 0.5f,
                panelWidth,
                panelHeight,
                11,
                50.0F,
                1.5F,
                new Color(255, 255, 255, 255).getRGB(),
                new Color(255, 255, 255, 255).getRGB(),
                new Color(255, 255, 255, 255).getRGB(),
                new Color(255, 255, 255, 255).getRGB()
        );

        Render2D.liquidGlass(x,
                y - 0.5f,
                panelWidth,
                panelHeight, 2.0f, 0.075f, 11, new Color(255, 255, 255, 255).getRGB());

        Render2D.rect(x,
                y - 0.5f,
                panelWidth,
                panelHeight, 11, new Color(0, 0, 0, 75).getRGB());

        Render2D.rect(
                x + 7.5F,
                y + 7.5F,
                40F,
                panelHeight - 15.0F,
                8,
                new Color(255, 255, 255, 50).getRGB(),
                new Color(255, 255, 255, 50).getRGB(),
                new Color(85, 85, 85, 50).getRGB(),
                new Color(85, 85, 85, 50).getRGB()
        );

        Render2D.outline(
                x + 7.5F,
                y + 7.5F,
                40F,
                panelHeight - 15.0F,
                8,
                0.25f,
                new Color(255, 255, 255, 100).getRGB(),
                new Color(255, 255, 255, 100).getRGB(),
                new Color(85, 85, 85, 150).getRGB(),
                new Color(85, 85, 85, 150).getRGB()
        );

        Render2D.rect(
                x + 15F,
                y + 15F,
                25F,
                25F,
                5,
                new Color(128, 128, 128, 64).getRGB()
        );

        Render2D.outline(
                x + 15F,
                y + 15F,
                25F,
                25F,
                5,
                0.15f,
                new Color(255, 255, 255, 255).getRGB(),
                new Color(255, 255, 255, 5).getRGB(),
                new Color(255, 255, 255, 255).getRGB(),
                new Color(255, 255, 255, 5).getRGB()
        );

        float logoSize = 18.0F;
        float logoWidth = Render2D.textWidth(FontType.MAINMENUSCREEN, "f", logoSize);
        Render2D.text(
                FontType.MAINMENUSCREEN,
                "f",
                x + 15F + (25F - logoWidth) * 0.5F,
                y + 16F + (25F - logoSize) * 0.5F,
                logoSize,
                new Color(255, 255, 255, 255).getRGB()
        );

        Render2D.rect(
                x + 53.0F, y + 41.5F, panelWidth - 60.0F, panelHeight - 49.0F,
                6,
                new Color(185, 185, 185, 50).getRGB(),
                new Color(185, 185, 185, 50).getRGB(),
                new Color(85, 85, 85, 50).getRGB(),
                new Color(85, 85, 85, 50).getRGB()
        );

        Render2D.outline(
                x + 53.0F, y + 41.5F, panelWidth - 60.0F, panelHeight - 49.0F,
                6,
                0.25f,
                new Color(255, 255, 255, 100).getRGB(),
                new Color(255, 255, 255, 100).getRGB(),
                new Color(85, 85, 85, 150).getRGB(),
                new Color(85, 85, 85, 150).getRGB()
        );

        Render2D.rect(
                x + 53.0F,
                y + 7.5F,
                panelWidth - 60.0F,
                30,
                6,
                new Color(185, 185, 185, 50).getRGB(),
                new Color(185, 185, 185, 50).getRGB(),
                new Color(185, 185, 185, 50).getRGB(),
                new Color(185, 185, 185, 50).getRGB()
        );

        Render2D.outline(
                x + 53.0F,
                y + 7.5F,
                panelWidth - 60.0F,
                30,
                6,
                0.25f,
                new Color(255, 255, 255, 100).getRGB(),
                new Color(255, 255, 255, 100).getRGB(),
                new Color(255, 255, 255, 100).getRGB(),
                new Color(255, 255, 255, 100).getRGB()
        );
    }
}
