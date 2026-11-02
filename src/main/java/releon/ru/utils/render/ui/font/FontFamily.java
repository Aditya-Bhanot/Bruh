package releon.ru.utils.render.ui.font;

import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;

final class FontFamily {
    private static final FontRenderContext CHECK_CONTEXT = new FontRenderContext(
            new AffineTransform(),
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
            RenderingHints.VALUE_FRACTIONALMETRICS_ON
    );

    private final String name;
    private final Font primary;
    private final List<Font> fonts;
    private final IntFunction<List<Font>> lazyFallbacks;
    private final Map<Integer, Font> resolvedFonts = new HashMap<>();

    FontFamily(String name, Font primary, List<Font> fallbacks, IntFunction<List<Font>> lazyFallbacks) {
        this.name = name;
        this.primary = primary;
        this.fonts = new ArrayList<>(1 + fallbacks.size());
        this.fonts.add(primary);
        this.fonts.addAll(fallbacks);
        this.lazyFallbacks = lazyFallbacks;
    }

    String name() {
        return name;
    }

    Font primary() {
        return primary;
    }

    Font resolve(int codePoint) {
        return resolvedFonts.computeIfAbsent(codePoint, this::findFont);
    }

    private Font findFont(int codePoint) {
        if (!Character.isValidCodePoint(codePoint)) {
            return primary;
        }

        Font preferred = findPreferredFont(codePoint);
        if (preferred != null) {
            return preferred;
        }

        for (Font font : fonts) {
            if (canDisplay(font, codePoint)) {
                return font;
            }
        }
        if (lazyFallbacks != null) {
            for (Font font : lazyFallbacks.apply(codePoint)) {
                if (canDisplay(font, codePoint)) {
                    return font;
                }
            }
        }
        return primary;
    }

    private Font findPreferredFont(int codePoint) {
        if (isPrivateUse(codePoint)) {
            return findFontByDisplay(codePoint, "icon");
        }
        if (isHangul(codePoint)) {
            return findFontByDisplay(codePoint, "cjkkr", "cjk kr", "korean", "malgun");
        }
        if (isJapanese(codePoint)) {
            return findFontByDisplay(codePoint, "cjkjp", "cjk jp", "japanese", "meiryo");
        }
        if (isCjk(codePoint)) {
            return findFontByDisplay(codePoint, "cjksc", "cjk sc", "chinese", "yahei", "simsun");
        }
        if (isArabic(codePoint)) {
            return findFontByDisplay(codePoint, "naskh", "arabic");
        }
        if (isSymbol(codePoint)) {
            return findFontByDisplay(codePoint, "symbols2", "symbols 2", "symbol");
        }
        return null;
    }

    private Font findFontByDisplay(int codePoint, String... tokens) {
        for (String token : tokens) {
            for (Font font : fonts) {
                String key = fontKey(font).toLowerCase(Locale.ROOT);
                if (key.contains(token) && canDisplay(font, codePoint)) {
                    return font;
                }
            }
        }
        return null;
    }

    private static boolean canDisplay(Font font, int codePoint) {
        try {
            if (font == null || !font.canDisplay(codePoint)) {
                return false;
            }

            GlyphVector vector = font.createGlyphVector(CHECK_CONTEXT, new String(Character.toChars(codePoint)));
            return vector.getNumGlyphs() > 0 && vector.getGlyphCode(0) != font.getMissingGlyphCode();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static String fontKey(Font font) {
        if (font == null) {
            return "missing";
        }
        return font.getPSName() + "/" + font.getFontName(Locale.ROOT);
    }

    private static boolean isPrivateUse(int codePoint) {
        return (codePoint >= 0xE000 && codePoint <= 0xF8FF)
                || (codePoint >= 0xF0000 && codePoint <= 0xFFFFD)
                || (codePoint >= 0x100000 && codePoint <= 0x10FFFD);
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

    private static boolean isArabic(int codePoint) {
        return (codePoint >= 0x0600 && codePoint <= 0x06FF)
                || (codePoint >= 0x0750 && codePoint <= 0x077F)
                || (codePoint >= 0x08A0 && codePoint <= 0x08FF)
                || (codePoint >= 0xFB50 && codePoint <= 0xFDFF)
                || (codePoint >= 0xFE70 && codePoint <= 0xFEFF);
    }

    private static boolean isSymbol(int codePoint) {
        return (codePoint >= 0x2000 && codePoint <= 0x2BFF)
                || (codePoint >= 0x1F000 && codePoint <= 0x1FAFF);
    }
}
