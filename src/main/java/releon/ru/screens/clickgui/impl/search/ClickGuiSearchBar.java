package releon.ru.screens.clickgui.impl.search;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleManager;
import releon.ru.api.settings.Setting;
import releon.ru.manager.Manager;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public final class ClickGuiSearchBar {
    private static final int MAX_RESULTS = 7;
    private static final float WIDTH = 200.0f;
    private static final float HEIGHT = 21.0f;
    private static final float RESULT_HEIGHT = 21.0f;
    private static final float TOP = 12.0f;
    private static final float OPEN_OFFSET = 18.0f;
    private static final Comparator<ScoredResult> RESULT_COMPARATOR = Comparator
            .comparingInt(ScoredResult::score).reversed()
            .thenComparing(result -> result.result().module().getName(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(result -> result.result().settingName() == null ? "" : result.result().settingName(), String.CASE_INSENSITIVE_ORDER);

    private final SmoothAnimation focusAnimation = new SmoothAnimation();
    private final List<Result> visibleResults = new ArrayList<>(MAX_RESULTS);
    private final List<ScoredResult> scoredResults = new ArrayList<>(64);
    private String query = "";
    private String lastNormalizedQuery;
    private boolean focused;
    private boolean selectedAll;
    private float x;
    private float y;
    private float width;
    private float height;
    private float scale = 1.0f;

    public ClickGuiSearchBar() {
        focusAnimation.set(0.0);
    }

    public void render(GuiGraphics graphics, int screenWidth, float openProgress) {
        render(graphics, screenWidth, openProgress, 0.0f, 0.0f);
    }

    public void render(GuiGraphics graphics, int screenWidth, float openProgress, float offsetX, float offsetY) {
        focusAnimation.run(focused ? 1.0 : 0.0, 0.18, Easings.CUBIC_OUT, true);
        focusAnimation.update();
        refreshResults();

        float alpha = clamp(openProgress);
        if (alpha <= 0.01f) {
            return;
        }

        scale = 1.08f - 0.08f * alpha;
        width = WIDTH * scale;
        height = HEIGHT * scale;
        x = (screenWidth - width) / 2.0f + offsetX;
        y = TOP - (1.0f - alpha) * OPEN_OFFSET + offsetY;

        float barAlpha = alpha;
        float radius = 5.5f * scale;
        Render2D.blur(x, y, width, height, radius, 1.0f, 1.0f, color(255, 255, 255, 255, barAlpha));

        Render2D.blur(x, y, width, height, radius, 1, 1.0f, color(0, 0, 0, 255, alpha));


        float textAlpha = alpha;
        boolean placeholderVisible = query.isEmpty() && !focused;
        String display = placeholderVisible ? "Search..." : query;
        int textColor = placeholderVisible ? color(255, 255, 255, 255, textAlpha) : color(255, 255, 255, 245, textAlpha);
        Render2D.pushScissor(graphics, x + 5.0f * scale, y, width - 25.0f * scale, height);
        try {
            if (selectedAll && focused && !query.isEmpty()) {
                float selectionX = x + 8.0f * scale;
                float textX = x + 10.0f * scale;
                float textWidth = Render2D.textWidth(FontType.SEMIBOLD, query, 8.0f * scale);
                float selectionRight = Math.min(textX + textWidth + 4.0f * scale, x + width - 25.0f * scale);
                Render2D.rect(selectionX, y + 53.0f * scale, Math.max(0.0f, selectionRight - selectionX), 13.0f * scale, 2.0f * scale, color(155, 162, 176, 80, textAlpha));
            }
            Render2D.text(FontType.SEMIBOLD, display, x + 10.0f * scale, y + 6.0f * scale, 8.0f * scale, textColor);
         
            renderCaret(display, alpha);
        } finally {
            Render2D.popScissor(graphics);
        }
        
        Render2D.text(FontType.ICONS, "U", x + width - 20.0f * scale, y + 1.5f * scale, 16.0f * scale, color(255, 255, 255, 255, alpha));


        renderDropdown(graphics, alpha);
    }

    public boolean mouseClicked(MouseButtonEvent event, boolean doubled, Consumer<Result> onSelect) {
        double mouseX = event.x();
        double mouseY = event.y();
        if (hovered(mouseX, mouseY, x, y, width, height)) {
            if (event.button() == 0) {
                focused = true;
            }
            return true;
        }

        if (dropdownOpen()) {
            float resultHeight = RESULT_HEIGHT * scale;
            float dropdownY = y + height + 4.0f * scale;
            for (int i = 0; i < visibleResults.size(); i++) {
                float rowY = dropdownY + i * resultHeight;
                if (hovered(mouseX, mouseY, x, rowY, width, resultHeight)) {
                    if (event.button() == 0) {
                        focused = false;
                        Result result = visibleResults.get(i);
                        if (onSelect != null) {
                            onSelect.accept(result);
                        }
                    }
                    return true;
                }
            }
        }

        if (event.button() != 0) {
            return false;
        }

        focused = false;
        selectedAll = false;
        return false;
    }

    public boolean keyPressed(KeyEvent event, Consumer<Result> onSelect) {
        if (!focused) {
            return false;
        }

        int key = event.key();
        boolean ctrl = (event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        if (ctrl && key == GLFW.GLFW_KEY_A) {
            selectedAll = !query.isEmpty();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            focused = false;
            selectedAll = false;
            return true;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            if (selectedAll) {
                query = "";
                selectedAll = false;
            } else if (!query.isEmpty()) {
                query = query.substring(0, query.length() - 1);
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_DELETE) {
            query = "";
            selectedAll = false;
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            refreshResults();
            if (!visibleResults.isEmpty() && onSelect != null) {
                onSelect.accept(visibleResults.getFirst());
            }
            return true;
        }
        return true;
    }

    public boolean charTyped(CharacterEvent event) {
        if (!focused || !event.isAllowedChatCharacter()) {
            return false;
        }
        String text = event.codepointAsString();
        if (text != null && !text.isBlank() && query.length() < 32) {
            if (selectedAll) {
                query = "";
                selectedAll = false;
            }
            if (query.length() + text.length() <= 32) {
                query += text;
            }
        }
        return true;
    }

    public boolean isFocused() {
        return focused;
    }

    private void renderCaret(String display, float alpha) {
        float focus = (float) focusAnimation.get();
        if (focus <= 0.01f || selectedAll) {
            return;
        }

        long now = System.currentTimeMillis();
        float blink = (float) (0.35 + 0.65 * Math.abs(Math.sin(now / 240.0)));
        float textWidth = query.isEmpty() ? 0.0f : Render2D.textWidth(FontType.SEMIBOLD, display, 8.0f * scale);
        float maxCaretX = x + width - 25.0f * scale;
        float caretX = Math.min(x + 7.0f * scale + textWidth + 1.5f * scale, maxCaretX);
        Render2D.rect(
                caretX,
                y + 5.5f * scale,
                1.0f * scale,
                11.0f * scale,
                0.5f * scale,
                color(255, 255, 255, 235, alpha * focus * blink)
        );
    }

    private void renderDropdown(GuiGraphics graphics, float alpha) {
        if (!dropdownOpen()) {
            return;
        }

        float resultHeight = RESULT_HEIGHT * scale;
        float dropdownY = y + height + 5.0f * scale;
        float dropdownHeight = visibleResults.size() * resultHeight;
        float radius = 6.0f * scale;
        float dropdownAlpha = alpha;
        Render2D.blur(x, dropdownY, width, dropdownHeight, radius, 1.0f, 1.0f, color(255, 255, 255, 255, dropdownAlpha));
        Render2D.glass(x, dropdownY, width, dropdownHeight, radius, color(0, 0, 0, 118, dropdownAlpha), dropdownAlpha, 0.1f, color(0, 0, 0, 168, dropdownAlpha), 0.98f * dropdownAlpha, false, 0.74f * dropdownAlpha, 0.002f * dropdownAlpha, 1.0f, 0.0f);
        Render2D.glassOutline(x, dropdownY, width, dropdownHeight, radius, 0.25f * scale, color(255, 255, 255, 135, dropdownAlpha), 0.75f, 35.0f, color(255, 255, 255, 170, dropdownAlpha), 0.85f * dropdownAlpha, false, 0.75f, 0.0015f, 1.0f, 0.0f);

        Render2D.pushScissor(graphics, x, dropdownY, width, dropdownHeight);
        try {
            for (int i = 0; i < visibleResults.size(); i++) {
                Result result = visibleResults.get(i);
                float rowY = dropdownY + i * resultHeight + 2.5f * scale;
                int mainColor = color(255, 255, 255, 238, dropdownAlpha);
                int metaColor = color(165, 165, 172, 205, dropdownAlpha);
                if (result.settingName() == null) {
                    String meta = result.module().getCategory().getDisplayName();
                    float metaWidth = Render2D.textWidth(FontType.SEMIBOLD, meta, 5.8f * scale);
                    Render2D.text(FontType.SEMIBOLD, result.module().getName(), x + 9.0f * scale, rowY + 4.0f * scale, 6.8f * scale, mainColor);
                    Render2D.text(FontType.SEMIBOLD, meta, x + width - metaWidth - 9.0f * scale, rowY + 4.7f * scale, 5.8f * scale, metaColor);
                } else {
                    String title = result.settingName();
                    String meta = result.module().getName() + " setting";
                    Render2D.text(FontType.SEMIBOLD, title, x + 9.0f * scale, rowY + 3.0f * scale, 6.7f * scale, mainColor);
                    Render2D.text(FontType.SEMIBOLD, meta, x + 9.0f * scale, rowY + 11.0f * scale, 5.2f * scale, metaColor);
                }
            }
        } finally {
            Render2D.popScissor(graphics);
        }
    }

    private void refreshResults() {
        String normalizedQuery = normalize(query);
        if (normalizedQuery.equals(lastNormalizedQuery)) {
            return;
        }
        lastNormalizedQuery = normalizedQuery;
        visibleResults.clear();
        scoredResults.clear();
        if (normalizedQuery.isBlank()) {
            return;
        }

        ModuleManager moduleManager = Manager.getModules();
        if (moduleManager == null) {
            return;
        }

        for (Module module : moduleManager.getModules()) {
            addScored(scoredResults, module, null, normalizedQuery, module.getName());
            for (Setting<?> setting : module.getSettings()) {
                if (setting.isVisible()) {
                    addScored(scoredResults, module, setting.getName(), normalizedQuery, setting.getName());
                }
            }
        }

        scoredResults.sort(RESULT_COMPARATOR);

        for (ScoredResult scoredResult : scoredResults) {
            if (visibleResults.size() == MAX_RESULTS) {
                break;
            }
            visibleResults.add(scoredResult.result());
        }
    }

    private void addScored(List<ScoredResult> scored, Module module, String settingName, String normalizedQuery, String candidate) {
        int score = score(normalizedQuery, normalize(candidate));
        if (score > 0) {
            scored.add(new ScoredResult(new Result(module, settingName), score + (settingName == null ? 15 : 0)));
        }
    }

    private int score(String query, String candidate) {
        if (query.isEmpty() || candidate.isEmpty()) {
            return 0;
        }
        if (candidate.equals(query)) {
            return 1000;
        }
        if (candidate.startsWith(query)) {
            return 850 - candidate.length();
        }
        int index = candidate.indexOf(query);
        if (index >= 0) {
            return 650 - index * 8 - candidate.length();
        }
        int matched = 0;
        int lastIndex = -1;
        int penalty = 0;
        for (int i = 0; i < query.length(); i++) {
            int next = candidate.indexOf(query.charAt(i), lastIndex + 1);
            if (next < 0) {
                return 0;
            }
            matched++;
            penalty += Math.max(0, next - lastIndex - 1);
            lastIndex = next;
        }
        return 420 + matched * 18 - penalty * 6 - candidate.length();
    }

    private boolean dropdownOpen() {
        return focused && !query.isBlank() && !visibleResults.isEmpty();
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").replace("-", "");
    }

    private boolean hovered(double mouseX, double mouseY, float x, float y, float width, float height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private int color(int red, int green, int blue, int alpha, float alphaMultiplier) {
        return ColorUtil.rgba(red, green, blue, (int) (alpha * alphaMultiplier));
    }

    private float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    public record Result(Module module, String settingName) {
    }

    private record ScoredResult(Result result, int score) {
    }
}
