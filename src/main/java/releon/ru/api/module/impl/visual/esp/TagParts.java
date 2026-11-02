package releon.ru.api.module.impl.visual.esp;

import releon.ru.utils.render.ui.font.FontType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class TagParts {
    private TagParts() {
    }

    static void addTagPart(List<TagPart> parts, FontType font, String text, int color) {
        addTagPart(parts, font, text, color, 0.0F);
    }

    static void addTagPart(List<TagPart> parts, FontType font, String text, int color, float yOffset) {
        if (text == null || text.isBlank()) {
            return;
        }

        boolean requestedLeadingSpace = Character.isWhitespace(text.charAt(0));
        String next = text.trim();
        if (parts.isEmpty()) {
            requestedLeadingSpace = false;
        }
        if (requestedLeadingSpace) {
            next = " " + next;
        } else if (!parts.isEmpty() && !parts.get(parts.size() - 1).text().endsWith(" ") && !next.startsWith("[") && !next.startsWith("x")) {
            next = " " + next;
        }
        parts.add(new TagPart(font, next, color, yOffset));
    }

    static void addTagParts(List<TagPart> parts, List<TagPart> nextParts) {
        List<TagPart> next = trim(nextParts);
        if (next.isEmpty()) {
            return;
        }

        if (!parts.isEmpty() && shouldInsertSpaceBefore(parts.get(parts.size() - 1).text(), next.get(0).text())) {
            TagPart first = next.get(0);
            parts.add(new TagPart(first.font(), " ", first.color()));
        }
        parts.addAll(next);
    }

    static List<TagPart> withYOffset(List<TagPart> parts, float yOffset) {
        if (parts == null || parts.isEmpty()) {
            return Collections.emptyList();
        }
        if (yOffset == 0.0F) {
            return parts;
        }

        List<TagPart> result = new ArrayList<>(parts.size());
        for (TagPart part : parts) {
            if (part != null) {
                result.add(part.withYOffset(yOffset));
            }
        }
        return result;
    }

    static List<TagPart> list(FontType font, String text, int color) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        return List.of(new TagPart(font, text.trim(), color));
    }

    static List<TagPart> join(List<TagPart> left, List<TagPart> right) {
        List<TagPart> result = new ArrayList<>();
        addTagParts(result, left);
        addTagParts(result, right);
        return result;
    }

    static List<TagPart> concat(List<TagPart> left, List<TagPart> right) {
        List<TagPart> result = new ArrayList<>();
        appendAllRaw(result, left);
        appendAllRaw(result, right);
        return result;
    }

    static List<TagPart> retagFont(List<TagPart> parts, FontType font) {
        if (parts == null || parts.isEmpty()) {
            return Collections.emptyList();
        }

        List<TagPart> result = new ArrayList<>(parts.size());
        for (TagPart part : parts) {
            if (part != null && part.text() != null && !part.text().isBlank()) {
                result.add(new TagPart(font, part.text(), part.color(), part.yOffset()));
            }
        }
        return result;
    }

    static List<TagPart> trim(List<TagPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return Collections.emptyList();
        }

        int start = 0;
        int length = textLength(parts);
        int end = length;
        while (start < end && Character.isWhitespace(charAt(parts, start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(charAt(parts, end - 1))) {
            end--;
        }
        if (start == 0 && end == length) {
            return parts;
        }
        return slice(parts, start, end);
    }

    static List<TagPart> stripOuterDecorators(List<TagPart> parts) {
        List<TagPart> result = trim(parts);
        boolean changed;
        do {
            changed = false;
            String plain = plainText(result);
            if (plain.length() >= 2 && isOpeningDecorator(plain.charAt(0)) && isClosingDecorator(plain.charAt(plain.length() - 1))) {
                result = trim(slice(result, 1, plain.length() - 1));
                changed = true;
                continue;
            }
            while (!plain.isEmpty() && isLooseDecorator(plain.charAt(0))) {
                result = trim(slice(result, 1, plain.length()));
                plain = plainText(result);
                changed = true;
            }
            while (!plain.isEmpty() && isLooseDecorator(plain.charAt(plain.length() - 1))) {
                result = trim(slice(result, 0, plain.length() - 1));
                plain = plainText(result);
                changed = true;
            }
        } while (changed);
        return result;
    }

    static List<TagPart> compactLetterSpaced(List<TagPart> parts) {
        List<TagPart> source = trim(parts);
        if (!isLetterSpaced(plainText(source))) {
            return source;
        }

        List<TagPart> result = new ArrayList<>();
        for (TagPart part : source) {
            if (part == null || part.text() == null) {
                continue;
            }

            String text = part.text();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (!Character.isWhitespace(c)) {
                    appendRaw(result, part.font(), String.valueOf(c), part.color(), part.yOffset());
                }
            }
        }
        return result;
    }

    static List<TagPart> compactToSingleTextPart(List<TagPart> parts, FontType font, int fallbackColor) {
        List<TagPart> source = trim(stripOuterDecorators(parts));
        if (source.isEmpty()) {
            return Collections.emptyList();
        }

        String plain = plainText(source);
        if (!isLetterSpaced(plain) && !isSplitIntoSingleCharacters(source)) {
            return source;
        }

        String compact = compactWhitespace(plain);
        if (compact.isBlank()) {
            return Collections.emptyList();
        }
        return List.of(new TagPart(font, compact, firstVisibleColor(source, fallbackColor), firstVisibleYOffset(source)));
    }

    static List<TagPart> slice(List<TagPart> parts, int start, int end) {
        if (parts == null || parts.isEmpty() || end <= start) {
            return Collections.emptyList();
        }

        List<TagPart> result = new ArrayList<>();
        int index = 0;
        for (TagPart part : parts) {
            if (part == null || part.text() == null || part.text().isEmpty()) {
                continue;
            }

            String text = part.text();
            int partStart = index;
            int partEnd = index + text.length();
            if (partEnd <= start) {
                index = partEnd;
                continue;
            }
            if (partStart >= end) {
                break;
            }

            int localStart = Math.max(0, start - partStart);
            int localEnd = Math.min(text.length(), end - partStart);
            if (localStart < localEnd) {
                appendRaw(result, part.font(), text.substring(localStart, localEnd), part.color(), part.yOffset());
            }
            index = partEnd;
        }
        return result;
    }

    static int textLength(List<TagPart> parts) {
        int length = 0;
        if (parts == null) {
            return length;
        }
        for (TagPart part : parts) {
            if (part != null && part.text() != null) {
                length += part.text().length();
            }
        }
        return length;
    }

    static String plainText(List<TagPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder(textLength(parts));
        for (TagPart part : parts) {
            if (part != null && part.text() != null) {
                builder.append(part.text());
            }
        }
        return builder.toString();
    }

    static int colorAt(List<TagPart> parts, int targetIndex, int fallbackColor) {
        int index = 0;
        for (TagPart part : parts) {
            if (part == null || part.text() == null) {
                continue;
            }

            String text = part.text();
            int nextIndex = index + text.length();
            if (targetIndex >= index && targetIndex < nextIndex) {
                return part.color();
            }
            index = nextIndex;
        }
        return fallbackColor;
    }

    static String clean(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        StringBuilder builder = new StringBuilder(text.length());
        boolean skipFormatting = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (skipFormatting) {
                skipFormatting = false;
                continue;
            }
            if (c == '\u00A7') {
                skipFormatting = true;
                continue;
            }
            if (!Character.isISOControl(c)) {
                builder.append(c);
            }
        }
        return builder.toString().trim();
    }

    static void appendRaw(List<TagPart> parts, FontType font, String text, int color) {
        appendRaw(parts, font, text, color, 0.0F);
    }

    static void appendRaw(List<TagPart> parts, FontType font, String text, int color, float yOffset) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (!parts.isEmpty()) {
            TagPart last = parts.get(parts.size() - 1);
            if (last.font() == font && last.color() == color && last.yOffset() == yOffset) {
                parts.set(parts.size() - 1, new TagPart(font, last.text() + text, color, yOffset));
                return;
            }
        }
        parts.add(new TagPart(font, text, color, yOffset));
    }

    private static void appendAllRaw(List<TagPart> target, List<TagPart> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (TagPart part : source) {
            if (part != null) {
                appendRaw(target, part.font(), part.text(), part.color());
            }
        }
    }

    static boolean isOpeningDecorator(char c) {
        return c == '[' || c == '(' || c == '{' || c == '<';
    }

    static boolean isClosingDecorator(char c) {
        return c == ']' || c == ')' || c == '}' || c == '>';
    }

    static boolean isLooseDecorator(char c) {
        return isOpeningDecorator(c) || isClosingDecorator(c) || c == '|' || c == ':' || c == '-' || c == '\u00BB' || c == '\u00AB';
    }

    private static boolean shouldInsertSpaceBefore(String previous, String next) {
        if (previous == null || previous.isEmpty() || next == null || next.isEmpty()) {
            return false;
        }
        if (previous.endsWith(" ") || Character.isWhitespace(next.charAt(0))) {
            return false;
        }

        char first = next.charAt(0);
        return first != ']'
                && first != ')'
                && first != '}'
                && first != '>'
                && first != ','
                && first != '.'
                && first != ':'
                && first != ';';
    }

    private static boolean isLetterSpaced(String text) {
        if (text == null || text.isBlank() || !containsWhitespace(text)) {
            return false;
        }

        String[] tokens = text.trim().split("\\s+");
        if (tokens.length < 2) {
            return false;
        }

        for (String token : tokens) {
            if (token.length() != 1 || !isCompactPrefixChar(token.charAt(0))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSplitIntoSingleCharacters(List<TagPart> parts) {
        int visibleParts = 0;
        int visibleCharacters = 0;
        if (parts == null || parts.isEmpty()) {
            return false;
        }

        for (TagPart part : parts) {
            if (part == null || part.text() == null || part.text().isBlank()) {
                continue;
            }

            int partCharacters = 0;
            String text = part.text();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (Character.isWhitespace(c)) {
                    continue;
                }
                if (!isCompactPrefixChar(c)) {
                    return false;
                }
                partCharacters++;
                visibleCharacters++;
            }
            if (partCharacters > 1) {
                return false;
            }
            if (partCharacters == 1) {
                visibleParts++;
            }
        }
        return visibleParts >= 2 && visibleCharacters >= 2;
    }

    private static String compactWhitespace(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private static int firstVisibleColor(List<TagPart> parts, int fallbackColor) {
        if (parts == null || parts.isEmpty()) {
            return fallbackColor;
        }
        for (TagPart part : parts) {
            if (part != null && part.text() != null && !part.text().isBlank()) {
                return part.color();
            }
        }
        return fallbackColor;
    }

    private static float firstVisibleYOffset(List<TagPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return 0.0F;
        }
        for (TagPart part : parts) {
            if (part != null && part.text() != null && !part.text().isBlank()) {
                return part.yOffset();
            }
        }
        return 0.0F;
    }

    private static boolean containsWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    static boolean isCompactPrefixChar(char c) {
        return Character.isLetterOrDigit(c) || c == '.' || c == '+' || c == '_' || c == '-';
    }

    private static char charAt(List<TagPart> parts, int targetIndex) {
        int index = 0;
        for (TagPart part : parts) {
            if (part == null || part.text() == null) {
                continue;
            }

            String text = part.text();
            int nextIndex = index + text.length();
            if (targetIndex >= index && targetIndex < nextIndex) {
                return text.charAt(targetIndex - index);
            }
            index = nextIndex;
        }
        return 0;
    }
}
