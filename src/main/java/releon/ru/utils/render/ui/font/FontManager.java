package releon.ru.utils.render.ui.font;


import java.awt.Font;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class FontManager implements AutoCloseable {
    private static final int MAX_STRIKES = 96;
    private static final String FONT_ROOT = "/assets/releon/ui/fonts/";
    private static final String DEFAULT_NAME = "default";

    private final Map<String, FontFamily> families = new LinkedHashMap<>();
    private final List<Font> unicodeFallbacks = new ArrayList<>();
    private final Map<String, Font> lazyFonts = new HashMap<>();
    private final Map<StrikeKey, FontStrike> strikes = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<StrikeKey, FontStrike> eldest) {
            if (size() <= MAX_STRIKES) {
                return false;
            }
            eldest.getValue().close();
            return true;
        }
    };
    private Font bundledSansFallback;

    FontManager() {
        load();
    }

    FontStrike strike(String name, float size) {
        String normalizedName = normalizeName(name);
        FontFamily family = family(normalizedName);
        float normalizedSize = normalizeSize(size);
        StrikeKey key = new StrikeKey(family.name(), normalizedSize);
        return strikes.computeIfAbsent(key, ignored -> new FontStrike(family, normalizedSize));
    }

    FontFamily family(String name) {
        FontFamily family = families.get(normalizeName(name));
        if (family != null) {
            return family;
        }
        return families.get(DEFAULT_NAME);
    }

    private void load() {
        Font segoe = loadFont("SegoeProDisplay-Semibold.ttf", new Font("Segoe UI Semibold", Font.PLAIN, 1));
        Font semiBold = loadFont("semi_bold.otf", segoe);
        Font openSansBold = loadFont("open-sans.bold.ttf", new Font(Font.SANS_SERIF, Font.BOLD, 1));
        Font openSansSemiBold = loadFont("open-sans.semibold.ttf", openSansBold);
        Font bold = loadFont("bold.otf", openSansBold.deriveFont(Font.BOLD, 1.0f));
        Font medium = loadFont("medium.otf", semiBold);
        Font zenter = loadFont("ZenterSPDemo-Black.otf", segoe);
        Font icons = loadFont("icons.ttf", segoe);
        Font notoSans = loadFont("NotoSans-Regular.ttf", segoe);
        Font notoSymbols = loadFont("NotoSansSymbols-Regular.ttf", segoe);
        Font notoSymbols2 = loadFont("NotoSansSymbols2-Regular.ttf", segoe);
        Font notoArabic = loadFont("NotoNaskhArabic-Regular.ttf", segoe);
        bundledSansFallback = notoSans;

        loadSystemFallbacks(icons, notoSymbols, notoSymbols2, notoArabic, segoe, notoSans);

        register(DEFAULT_NAME, segoe);
        register("segoe", segoe);
        register("segoe_semibold", segoe);
        register("semi_bold", semiBold);
        register("semibold", semiBold);
        register("open_sans_bold", openSansBold);
        register("opensans_bold", openSansBold);
        register("open_sans_semibold", openSansSemiBold);
        register("opensans_semibold", openSansSemiBold);
        register("open_sans", openSansSemiBold);
        register("opensans", openSansSemiBold);
        register("bold", bold);
        register("bold_otf", bold);
        register("medium", medium);
        register("medium_otf", medium);
        register("zenter", zenter);
        register("zenter_black", zenter);
        register("noto", notoSans);
        register("noto_sans", notoSans);
        register("noto_symbols", notoSymbols);
        register("noto_symbols_1", notoSymbols);
        register("noto_symbols_2", notoSymbols2);
        register("noto_symbols2", notoSymbols2);
        register("noto_arabic", notoArabic);
        register("arabic", notoArabic);
        register("noto_cjk_sc", notoSans);
        register("noto_chinese", notoSans);
        register("chinese", notoSans);
        register("noto_cjk_kr", notoSans);
        register("noto_korean", notoSans);
        register("korean", notoSans);
        register("noto_cjk_jp", notoSans);
        register("noto_japanese", notoSans);
        register("japanese", notoSans);
        register("icons", icons);
        register("icon", icons);

        register(FontType.SEMIBOLD, semiBold);
        register(FontType.SEMI_BOLD, semiBold);
        register(FontType.BOLD, bold);
        register(FontType.ICONS, icons);
        register(FontType.ICONSTYPETHO, icons);
        register(FontType.GUI_ICONS, icons);
        register(FontType.HUD_ICONS, icons);
        register(FontType.CATEGORY_ICONS, icons);
        register(FontType.DEFAULT, segoe);
        register(FontType.REGULAR, notoSans);
        register(FontType.TEST, notoSans);
        register(FontType.REGULARNEW, notoSans);
        register(FontType.MAINMENUSCREEN, icons);
        register(FontType.MEDIA_PLAYER_ICONS, icons);
    }

    private void register(String name, Font primary) {
        families.put(normalizeName(name), new FontFamily(normalizeName(name), primary, unicodeFallbacks, this::lazyUnicodeFallbacks));
    }

    private void register(FontType font, Font primary) {
        register(font.fontName(), primary);
        if (!font.path().equals(font.fontName())) {
            register(font.path(), primary);
        }
    }

    private Font loadFont(String fileName, Font fallback) {
        try (InputStream input = FontManager.class.getResourceAsStream(FONT_ROOT + fileName)) {
            if (input == null) {
                return fallback;
            }

            return Font.createFont(Font.TRUETYPE_FONT, input).deriveFont(Font.PLAIN, 1.0f);
        } catch (Exception exception) {
            return fallback;
        }
    }

    private void loadSystemFallbacks(Font... bundledFallbacks) {
        unicodeFallbacks.clear();
        for (Font font : bundledFallbacks) {
            addFallback(font);
        }
        addFallback(new Font(Font.SANS_SERIF, Font.PLAIN, 1));
    }

    private void addFallback(Font font) {
        if (font == null) {
            return;
        }

        String key = FontFamily.fontKey(font);
        for (Font fallback : unicodeFallbacks) {
            if (FontFamily.fontKey(fallback).equals(key)) {
                return;
            }
        }
        unicodeFallbacks.add(font);
    }

    private List<Font> lazyUnicodeFallbacks(int codePoint) {
        if (isHangul(codePoint)) {
            return Collections.singletonList(lazyFont("NotoSansCJKkr-Regular.otf"));
        }
        if (isJapanese(codePoint)) {
            return Collections.singletonList(lazyFont("NotoSansCJKjp-Regular.otf"));
        }
        if (isCjk(codePoint)) {
            return Collections.singletonList(lazyFont("NotoSansCJKsc-Regular.otf"));
        }
        return List.of();
    }

    private Font lazyFont(String fileName) {
        return lazyFonts.computeIfAbsent(fileName, name -> loadFont(name, bundledSansFallback != null ? bundledSansFallback : new Font(Font.SANS_SERIF, Font.PLAIN, 1)));
    }

    private static float normalizeSize(float size) {
        return Math.max(1.0f, Math.min(512.0f, Math.round(size * 4.0f) / 4.0f));
    }

    private static String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            return DEFAULT_NAME;
        }
        return name.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private static boolean isHangul(int codePoint) {
        return (codePoint >= 0x1100 && codePoint <= 0x11FF)
                || (codePoint >= 0x3130 && codePoint <= 0x318F)
                || (codePoint >= 0xA960 && codePoint <= 0xA97F)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7AF);
    }

    private static boolean isJapanese(int codePoint) {
        return (codePoint >= 0x3040 && codePoint <= 0x30FF)
                || (codePoint >= 0x31F0 && codePoint <= 0x31FF);
    }

    private static boolean isCjk(int codePoint) {
        return (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0x20000 && codePoint <= 0x2FA1F);
    }

    @Override
    public void close() {
        for (FontStrike strike : strikes.values()) {
            strike.close();
        }
        strikes.clear();
        families.clear();
        unicodeFallbacks.clear();
        lazyFonts.clear();
    }

    private record StrikeKey(String name, float size) {
    }
}
