package releon.ru.utils.render.ui.font;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public enum FontType {
    SEMIBOLD("semibold", "semibold"),
    SEMI_BOLD("semi-bold", "semi-bold"),
    BOLD("bold", "bold"),
    ICONS("icons", "icons"),
    ICONSTYPETHO("iconstypetho", "iconstypetho"),
    GUI_ICONS("guiicons", "guiicons"),
    HUD_ICONS("hudicons", "hudicons"),
    CATEGORY_ICONS("categoryicons", "categoryicons"),
    WATERMARK_ICONS("watermarkicons", "watermarkicons"),
    DEFAULT("default", "default"),
    REGULAR("regular", "regular"),
    TEST("test", "test"),
    REGULARNEW("regularnew", "regularnew"),
    MAINMENUSCREEN("mainmenuicons", "mainmenuicons"),
    MEDIA_PLAYER_ICONS("mediaplayericons", "icons3");

    private static final Map<String, String> REGISTRY = new LinkedHashMap<>();

    static {
        for (FontType font : values()) {
            REGISTRY.put(font.name, font.path);
        }
    }

    private final String name;
    private final String path;

    FontType(String name, String path) {
        this.name = name;
        this.path = path;
    }

    public String fontName() {
        return name;
    }

    public String path() {
        return path;
    }

    public static Map<String, String> registry() {
        return REGISTRY;
    }

    public static String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            return DEFAULT.fontName();
        }
        return name.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    public boolean msdf() {
        return switch (this) {
            case SEMIBOLD, SEMI_BOLD, BOLD, DEFAULT, REGULAR, TEST, REGULARNEW,
                 ICONS, ICONSTYPETHO, GUI_ICONS, HUD_ICONS, CATEGORY_ICONS, WATERMARK_ICONS,
                 MAINMENUSCREEN, MEDIA_PLAYER_ICONS -> true;
            default -> false;
        };
    }
}
