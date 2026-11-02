package releon.ru.utils.string.chat.helper;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import releon.ru.utils.color.ColorAssist;

public class TextHelper {
    public enum GradientStyle {
        HALF_SPLIT,
        FULL_GRADIENT,
        ASTOLFO,
        TWO_COLOR_FADE
    }

    public static Component applyGradient(String text, GradientStyle style, int color1, int color2, boolean bold) {
        String value = text == null ? "" : text;
        return switch (style) {
            case HALF_SPLIT -> halfSplitGradient(value, color1, color2, bold);
            case FULL_GRADIENT -> fullGradient(value, color1, color2, bold);
            case ASTOLFO -> astolfoGradient(value, bold);
            case TWO_COLOR_FADE -> twoColorFade(value, color1, color2, bold);
        };
    }

    private static Component halfSplitGradient(String text, int color1, int color2, boolean bold) {
        MutableComponent result = Component.empty();
        int midPoint = text.length() / 2;
        for (int i = 0; i < text.length(); i++) {
            int color = i < midPoint ? color1 : color2;
            result.append(Component.literal(String.valueOf(text.charAt(i))).withStyle(style -> style.withColor(color).withBold(bold)));
        }
        return result;
    }

    private static Component fullGradient(String text, int color1, int color2, boolean bold) {
        MutableComponent result = Component.empty();
        int divisor = Math.max(1, text.length() - 1);
        for (int i = 0; i < text.length(); i++) {
            float ratio = (float) i / divisor;
            int color = ColorAssist.interpolate(color1, color2, ratio);
            result.append(Component.literal(String.valueOf(text.charAt(i))).withStyle(style -> style.withColor(color).withBold(bold)));
        }
        return result;
    }

    private static Component astolfoGradient(String text, boolean bold) {
        MutableComponent result = Component.empty();
        for (int i = 0; i < text.length(); i++) {
            int color = ColorAssist.astolfo(10, i, 0.7f, 0.7f, 1.0f);
            result.append(Component.literal(String.valueOf(text.charAt(i))).withStyle(style -> style.withColor(color).withBold(bold)));
        }
        return result;
    }

    private static Component twoColorFade(String text, int color1, int color2, boolean bold) {
        MutableComponent result = Component.empty();
        int divisor = Math.max(1, text.length() - 1);
        for (int i = 0; i < text.length(); i++) {
            float ratio = (float) i / divisor;
            int color = ColorAssist.interpolateColor(color1, color2, ratio);
            result.append(Component.literal(String.valueOf(text.charAt(i))).withStyle(style -> style.withColor(color).withBold(bold)));
        }
        return result;
    }

    public static Component applyPredefinedGradient(String text, String gradientName, boolean bold) {
        String gradient = gradientName == null ? "" : gradientName.toLowerCase();
        return switch (gradient) {
            case "red_blue" -> applyGradient(text, GradientStyle.HALF_SPLIT, ColorAssist.red, ColorAssist.toColor("#0000FF"), bold);
            case "green_purple" -> applyGradient(text, GradientStyle.HALF_SPLIT, ColorAssist.green, ColorAssist.toColor("#800080"), bold);
            case "yellow_cyan" -> applyGradient(text, GradientStyle.FULL_GRADIENT, ColorAssist.yellow, ColorAssist.toColor("#00FFFF"), bold);
            case "orange_magenta" -> applyGradient(text, GradientStyle.FULL_GRADIENT, ColorAssist.orange, ColorAssist.toColor("#FF00FF"), bold);
            case "astolfo" -> applyGradient(text, GradientStyle.ASTOLFO, 0, 0, bold);
            case "blue_green_fade" -> applyGradient(text, GradientStyle.TWO_COLOR_FADE, ColorAssist.toColor("#0000FF"), ColorAssist.green, bold);
            case "purple_red_fade" -> applyGradient(text, GradientStyle.TWO_COLOR_FADE, ColorAssist.toColor("#800080"), ColorAssist.red, bold);
            case "cyan_orange_fade" -> applyGradient(text, GradientStyle.TWO_COLOR_FADE, ColorAssist.toColor("#00FFFF"), ColorAssist.orange, bold);
            case "white_black" -> applyGradient(text, GradientStyle.FULL_GRADIENT, ColorAssist.colorForTextWhite$(), ColorAssist.colorForRectsBlack$(), bold);
            case "custom_purple" -> applyGradient(text, GradientStyle.FULL_GRADIENT, ColorAssist.colorForTextCustom$(), ColorAssist.colorForRectsCustom$(), bold);
            case "black_light_purple" -> applyGradient(text, GradientStyle.FULL_GRADIENT, ColorAssist.colorForRectsBlack$(), ColorAssist.toColor("#DA70D6"), bold);
            case "dark_red_bright_red" -> applyGradient(text, GradientStyle.FULL_GRADIENT, ColorAssist.toColor("#8B0000"), ColorAssist.red, bold);
            case "dark_red" -> applyGradient(text, GradientStyle.HALF_SPLIT, ColorAssist.toColor("#8B0000"), ColorAssist.toColor("#8B0000"), bold);
            case "red_white" -> applyGradient(text, GradientStyle.HALF_SPLIT, ColorAssist.red, ColorAssist.colorForTextWhite$(), bold);
            case "purple_bright_pink" -> applyGradient(text, GradientStyle.FULL_GRADIENT, ColorAssist.toColor("#800080"), ColorAssist.toColor("#FF69B4"), bold);
            case "pink_dark_pink" -> applyGradient(text, GradientStyle.FULL_GRADIENT, ColorAssist.toColor("#FFC1CC"), ColorAssist.toColor("#C71585"), bold);
            case "bright_red" -> applyGradient(text, GradientStyle.HALF_SPLIT, ColorAssist.red, ColorAssist.red, bold);
            case "dark_green_bright_green" -> applyGradient(text, GradientStyle.FULL_GRADIENT, ColorAssist.toColor("#006400"), ColorAssist.green, bold);
            case "red_orange" -> applyGradient(text, GradientStyle.FULL_GRADIENT, ColorAssist.red, ColorAssist.orange, bold);
            case "turquoise_blue" -> applyGradient(text, GradientStyle.FULL_GRADIENT, ColorAssist.toColor("#40E0D0"), ColorAssist.toColor("#0000FF"), bold);
            default -> Component.literal(text == null ? "" : text).withStyle(style -> style.withColor(ColorAssist.colorForTextWhite$()).withBold(bold));
        };
    }
}
