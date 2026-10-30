package releon.ru.api.drag.impl;

import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

public final class WatermarkIcons extends HudPanel {
    private static final IconSection[] SECTIONS = {
            new IconSection("Main menu / mainmenuicons", FontType.MAINMENUSCREEN, "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0"),
            new IconSection("Watermark / watermarkicons", FontType.WATERMARK_ICONS, "abcdefghijklmnopqrstuvwxyzAB"),
            new IconSection("GUI / guiicons", FontType.GUI_ICONS, "ABCDEFGHIJKLMNOPQRSTUVWXYZ"),
            new IconSection("HUD / hudicons", FontType.HUD_ICONS, " abcdefghijklmnopqrstuvwx"),
            new IconSection("Category / categoryicons", FontType.CATEGORY_ICONS, "abcdefgjlmnuv"),
            new IconSection("Icons / icons", FontType.ICONS, "ABCDEFGHIJKLMNOPQRSTUVWX"),
            new IconSection("TypeTho / iconstypetho", FontType.ICONSTYPETHO, "abcdefghijklmnopqrstuvwxyz"),
            new IconSection("Media / icons3", FontType.MEDIA_PLAYER_ICONS, "JBETCQGRSWDNHV!\"#$%&'()*+,-./0123456789:;<=>?@FIYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~AKMLXOPU")
    };
    private static final int COLUMNS = 14;
    private static final float CELL_WIDTH = 27.0F;
    private static final float CELL_HEIGHT = 34.0F;
    private static final float SECTION_HEADER_HEIGHT = 17.0F;
    private static final float SECTION_GAP = 7.0F;

    public WatermarkIcons() {
        super("watermarkicons", "Watermark icons", 300.0F, 180.0F, 390.0F, 900.0F);
    }

    @Override
    public void render() {
        WatermarkIconsState state = logics();
        renderWatermarkIcons(state);
    }

    private WatermarkIconsState logics() {
        contentVisible(selected());

        int rows = 0;
        for (IconSection section : SECTIONS) {
            rows += rows(section.icons);
        }
        float width = 12.0F + COLUMNS * CELL_WIDTH;
        float height = 14.0F + rows * CELL_HEIGHT + SECTIONS.length * (SECTION_HEADER_HEIGHT + SECTION_GAP);
        size(width, height);

        return new WatermarkIconsState(drag.x(), drag.y(), drag.width(), drag.height());
    }

    private void renderWatermarkIcons(WatermarkIconsState state) {
        HudRenderCompat.background(state.x, state.y, state.width, state.height, 4.0F, 15.0F, 1.2F, ColorUtil.rgba(0, 0, 0, 255));
        float sectionY = state.y + 8.0F;
        for (IconSection section : SECTIONS) {
            renderSectionHeader(state.x + 8.0F, sectionY, section);
            sectionY += SECTION_HEADER_HEIGHT;

            for (int i = 0; i < section.icons.length(); i++) {
                String icon = String.valueOf(section.icons.charAt(i));
                int column = i % COLUMNS;
                int row = i / COLUMNS;
                float cellX = state.x + 8.0F + column * CELL_WIDTH;
                float cellY = sectionY + row * CELL_HEIGHT;
                renderIconCell(section.font, icon, cellX, cellY);
            }
            sectionY += rows(section.icons) * CELL_HEIGHT + SECTION_GAP;
        }
    }

    private void renderSectionHeader(float x, float y, IconSection section) {
        Render2D.text(TEXT_FONT, section.title, x, y + 2.5F, 6.0F, TEXT_COLOR);
        Render2D.text(TEXT_FONT, String.valueOf(section.icons.length()), x + 315.0F, y + 2.5F, 6.0F, MUTED_COLOR);
        Render2D.rect(x, y + 14.0F, 365.0F, 0.5F, 0.0F, ColorUtil.rgba(255, 255, 255, 26));
    }

    private void renderIconCell(FontType font, String icon, float cellX, float cellY) {
        float iconSize = 16.0F;
        String label = label(icon);
        float iconWidth = Render2D.textWidth(font, icon, iconSize);
        float labelWidth = Render2D.textWidth(TEXT_FONT, label, 6.0F);

        Render2D.rect(cellX, cellY, 22.0F, 27.0F, 5.0F, ColorUtil.rgba(255, 255, 255, 16));
        Render2D.outline(cellX, cellY, 22.0F, 27.0F, 5.0F, 0.6F, ColorUtil.rgba(255, 255, 255, 34));
        Render2D.text(font, icon, cellX + (22.0F - iconWidth) * 0.5F, cellY + 3.2F, iconSize, ColorUtil.rgba(255, 255, 255, 245));
        Render2D.text(TEXT_FONT, label, cellX + (22.0F - labelWidth) * 0.5F, cellY + 19.5F, 6.0F, MUTED_COLOR);
    }

    private static int rows(String icons) {
        return (int) Math.ceil(icons.length() / (double) COLUMNS);
    }

    private static String label(String icon) {
        return " ".equals(icon) ? "SP" : icon;
    }

    private record IconSection(String title, FontType font, String icons) {
    }

    private record WatermarkIconsState(float x, float y, float width, float height) {
    }
}
