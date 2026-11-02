package releon.ru.api.drag.impl;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.blur.BuiltBlur;
import releon.ru.utils.render.ui.font.FontType;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class Potions extends HudPanel {
    private static final float NO_BLUR_RECT_ALPHA_SCALE = 200.0F / 255.0F;
    private static final float ROW_STEP = 11.0F;
    private static final float SECTION_STEP = 8.0F;
    private static final float GROUP_GAP = 3.0F;
    private static final float PANEL_ANIM = 0.24F;
    private static final float ROW_ANIM = 0.22F;
    private static final List<Holder<MobEffect>> PREVIEW_POSITIVE_EFFECTS = List.of(
            MobEffects.SPEED
    );
    private static final List<Holder<MobEffect>> PREVIEW_NEGATIVE_EFFECTS = List.of(
            MobEffects.POISON
    );
    private static final Comparator<MobEffectInstance> EFFECT_NAME_COMPARATOR = Comparator.comparing(effect -> effect.getEffect().value().getDisplayName().getString());
    private static final Comparator<RowState> ROW_STATE_COMPARATOR = Comparator.comparingDouble(RowState::offset);

    private final List<RowEntry> rowEntries = new ArrayList<>();
    private final List<MobEffectInstance> effects = new ArrayList<>();
    private final List<MobEffectInstance> positiveEffects = new ArrayList<>();
    private final List<MobEffectInstance> negativeEffects = new ArrayList<>();
    private final List<RowState> rowStates = new ArrayList<>();
    private final SmoothAnimation panelAnimation = new SmoothAnimation();
    private final SmoothAnimation iconAlphaAnimation = new SmoothAnimation();
    private boolean iconAlphaForward = true;
    private long previewIconSecond = -1L;
    private Holder<MobEffect> previewPositiveEffect = MobEffects.SPEED;
    private Holder<MobEffect> previewNegativeEffect = MobEffects.POISON;

    public Potions() {
        super("potions", "Potions", 300.0F, 100.0F, 80.0F, 23.0F);
    }

    @Override
    public void render() {
        PotionsState state = logics();
        if (state == null) {
            return;
        }
        renderPotions(state);
    }

    private PotionsState logics() {
        effects.clear();
        if (mc.player != null) {
            for (MobEffectInstance effect : mc.player.getActiveEffects()) {
                if (effect.showIcon()) {
                    effects.add(effect);
                }
            }
        }
        if (effects.size() > 1) {
            effects.sort(EFFECT_NAME_COMPARATOR);
        }

        positiveEffects.clear();
        negativeEffects.clear();
        for (MobEffectInstance effect : effects) {
            if (isNegativeEffect(effect)) {
                negativeEffects.add(effect);
            } else {
                positiveEffects.add(effect);
            }
        }

        boolean preview = effects.isEmpty() && editPreview();
        boolean targetVisible = !effects.isEmpty() || preview;

        drag.hitExpansion(0.0F, 17.0F, 0.0F, 0.0F);
        panelAnimation.update();
        panelAnimation.run(targetVisible ? 1.0 : 0.0, PANEL_ANIM, targetVisible ? Easings.EXPO_OUT : Easings.EXPO_IN, true);

        iconAlphaAnimation.update();
        if (!iconAlphaAnimation.isAlive()) {
            iconAlphaAnimation.run(iconAlphaForward ? 1.0 : 0.0, 1.55, Easings.EXPO_IN_OUT);
            iconAlphaForward = !iconAlphaForward;
        }

        for (RowEntry entry : rowEntries) {
            entry.active = false;
            entry.alpha.update();
            entry.y.update();
        }

        float targetY = 0.0F;
        if (preview) {
            updatePreviewEffects();

            RowEntry positiveSection = row("__section_positive", "Positive", "", "", 5.5F, targetY, null, -1, true);
            positiveSection.active = true;
            positiveSection.alpha.run(1.0, ROW_ANIM, Easings.EXPO_OUT, true);
            positiveSection.y.run(targetY, ROW_ANIM, Easings.EXPO_OUT, true);
            targetY += SECTION_STEP;

            RowEntry entry = row("__preview_positive", "Example", "", "**:**", 6.0F, targetY, previewPositiveEffect, -1, false);
            entry.active = true;
            entry.alpha.run(1.0, ROW_ANIM, Easings.EXPO_OUT, true);
            entry.y.run(targetY, ROW_ANIM, Easings.EXPO_OUT, true);
            targetY += ROW_STEP + GROUP_GAP;

            RowEntry negativeSection = row("__section_negative", "Negative", "", "", 5.5F, targetY, null, -1, true);
            negativeSection.active = true;
            negativeSection.alpha.run(1.0, ROW_ANIM, Easings.EXPO_OUT, true);
            negativeSection.y.run(targetY, ROW_ANIM, Easings.EXPO_OUT, true);
            targetY += SECTION_STEP;

            RowEntry negativeEntry = row("__preview_negative", "Example", "", "**:**", 6.0F, targetY, previewNegativeEffect, -1, false);
            negativeEntry.active = true;
            negativeEntry.alpha.run(1.0, ROW_ANIM, Easings.EXPO_OUT, true);
            negativeEntry.y.run(targetY, ROW_ANIM, Easings.EXPO_OUT, true);
            targetY += ROW_STEP;
        } else {
            if (!positiveEffects.isEmpty()) {
                RowEntry section = row("__section_positive", "Positive", "", "", 5.5F, targetY, null, -1, true);
                section.active = true;
                section.alpha.run(1.0, ROW_ANIM, Easings.EXPO_OUT, true);
                section.y.run(targetY, ROW_ANIM, Easings.EXPO_OUT, true);
                targetY += SECTION_STEP;
            }
            for (MobEffectInstance effect : positiveEffects) {
                String name = effect.getEffect().value().getDisplayName().getString();
                String level = level(effect);
                String duration = formatPotionDurationTicks(effect.getDuration());
                RowEntry entry = row("positive:" + name + ":" + effect.getAmplifier(), name, level, duration, 6.0F, targetY, effect.getEffect(), effect.getDuration(), false);
                entry.active = true;
                entry.alpha.run(1.0, ROW_ANIM, Easings.EXPO_OUT, true);
                entry.y.run(targetY, ROW_ANIM, Easings.EXPO_OUT, true);
                targetY += ROW_STEP;
            }

            if (!positiveEffects.isEmpty() && !negativeEffects.isEmpty()) {
                targetY += GROUP_GAP;
            }
            if (!negativeEffects.isEmpty()) {
                RowEntry section = row("__section_negative", "Negative", "", "", 5.5F, targetY, null, -1, true);
                section.active = true;
                section.alpha.run(1.0, ROW_ANIM, Easings.EXPO_OUT, true);
                section.y.run(targetY, ROW_ANIM, Easings.EXPO_OUT, true);
                targetY += SECTION_STEP;
            }
            for (MobEffectInstance effect : negativeEffects) {
                String name = effect.getEffect().value().getDisplayName().getString();
                String level = level(effect);
                String duration = formatPotionDurationTicks(effect.getDuration());
                RowEntry entry = row("negative:" + name + ":" + effect.getAmplifier(), name, level, duration, 6.0F, targetY, effect.getEffect(), effect.getDuration(), false);
                entry.active = true;
                entry.alpha.run(1.0, ROW_ANIM, Easings.EXPO_OUT, true);
                entry.y.run(targetY, ROW_ANIM, Easings.EXPO_OUT, true);
                targetY += ROW_STEP;
            }
        }

        for (RowEntry entry : rowEntries) {
            if (!entry.active) {
                entry.alpha.run(0.0, ROW_ANIM, Easings.EXPO_IN, true);
            }
        }
        rowEntries.removeIf(entry -> !entry.active && entry.alpha.get() <= 0.01F && !entry.alpha.isAlive());

        float panelAlpha = panelAnimation.get();
        boolean visible = targetVisible || panelAlpha > 0.01F || !rowEntries.isEmpty();
        contentVisible(visible);
        if (!visible) {
            return null;
        }

        float width = 80.0F;
        float targetHeight = Math.max(23.0F, targetY + 9.0F);
        for (RowEntry entry : rowEntries) {
            if (entry.alpha.get() > 0.01F || entry.active) {
                if (entry.section) {
                    width = Math.max(width, Render2D.textWidth(TEXT_FONT, entry.name, 5.5F) + 22.0F);
                    targetHeight = Math.max(targetHeight, entry.y.get() + 16.0F);
                } else {
                    width = Math.max(width, Render2D.textWidth(TEXT_FONT, entry.name, 6.0F) + Render2D.textWidth(TEXT_FONT, entry.level, 6.0F) + 65.0F);
                    targetHeight = Math.max(targetHeight, entry.y.get() + 20.0F);
                }
            }
        }
        size(width, targetHeight);
        drag.size(drag.width(), (float) Math.ceil(targetHeight));

        rowStates.clear();
        for (RowEntry entry : rowEntries) {
            float alpha = entry.alpha.get();
            if (alpha > 0.01F || entry.active) {
                rowStates.add(new RowState(entry.section, entry.effect, entry.name, entry.level, entry.value, entry.valueSize, entry.remainingTicks, entry.y.get(), alpha));
            }
        }
        if (rowStates.size() > 1) {
            rowStates.sort(ROW_STATE_COMPARATOR);
        }

        return new PotionsState(rowStates, iconAlphaAnimation.get(), panelAlpha, drag.x(), drag.y(), drag.width(), drag.height());
    }

    private void renderPotions(PotionsState state) {
        float headerY = state.y - 17.0F;
        float headerHeight = 17.0F;
        float rowY = state.y + 5.0F;
        String headerText = "Potions";
        float headerTextSize = 6.0F;
        float headerTextX = state.x + 10.0F;
        float headerTextY = headerY + (headerHeight - headerTextSize) * 0.5F - 0.5f;
        float headerTextWidth = Render2D.textWidth(TEXT_FONT, headerText, headerTextSize);
        float headerTextDotX = headerTextX + headerTextWidth + 3f;
        float iconSize = 8F;
        float iconWidth = Render2D.textWidth(FontType.MAINMENUSCREEN, "q", iconSize);
        float iconX = state.x + state.width - iconWidth - 7.0F;
        float iconY = headerY + (headerHeight - iconSize) * 0.5F + 0.5F;
        float glowSize = 22.0F;
        float glowX = iconX + iconWidth * 0.5F - glowSize * 0.5F;
        float glowY = headerY + (headerHeight - glowSize) * 0.5F;
        float sideRectY = headerY + (headerHeight - 9.0F) * 0.5F + 0.5f;
        float sideDotY = headerY + (headerHeight - 4.0F) * 0.5F + 0.5f;
        int iconAlpha = 255;
        int glowAlpha = Math.round(128.0F * state.alpha);
        int glow = ColorUtil.rgba(247, 133, 255, glowAlpha);
        int purple = ColorUtil.rgba(247, 133, 255, Math.round(iconAlpha * state.alpha));
        int headerTextColor = ColorUtil.rgba(255, 255, 255, Math.round(255.0F * state.alpha));
        int rowRectColor = ColorUtil.rgba(247, 133, 255, Math.round(255.0F * state.alpha));

        renderHudBackground(Render2D.blurBuilder()
                .rectangle(state.x, headerY, state.width, headerHeight)
                .radius(4.0F, 4.0F, 2.0F, 2.0F)
                .blurRadius(15.0F)
                .smoothness(1.2F)
                .color(ColorUtil.rgba(0, 0, 0, Math.round(255.0F * state.alpha)))
                .build());

        renderHudBackground(Render2D.blurBuilder()
                .rectangle(state.x, state.y, state.width, state.height)
                .radius(2.0F, 2.0F, 4.0F, 4.0F)
                .blurRadius(15.0F)
                .smoothness(1.2F)
                .color(ColorUtil.rgba(0, 0, 0, Math.round(255.0F * state.alpha)))
                .build());

        if (glowAlpha > 0) {
            renderHudGlow("releon:textures/particles/ghost-glow.png", glowX - 13f, glowY + 0.5f, glowSize + 25, glowSize, 0.0F, glow);
        }
        Render2D.rect(state.x, sideRectY, 2.5f, 9.0F, 0.0F, 2.0F, 2.0F, 0.0F, rowRectColor);
        Render2D.rect(state.x + 3.5F, sideDotY, 4.0F, 4.0F, 4.0F, rowRectColor);
        Render2D.rect(headerTextDotX, sideDotY, 4.0F, 4.0F, 4.0F, rowRectColor);
        Render2D.text(TEXT_FONT, headerText, headerTextX, headerTextY, headerTextSize, headerTextColor);
        Render2D.text(FontType.MAINMENUSCREEN, "q", iconX, iconY, iconSize, purple);

        for (RowState row : state.rows) {
            float rowAlpha = state.alpha * row.alpha * expiringAlpha(row.remainingTicks);
            float currentY = rowY + row.offset;
            if (row.section) {
                float sectionTextX = state.x + 4F;
                float sectionTextY = currentY - 0.5f;
                float sectionLineX = sectionTextX + Render2D.textWidth(TEXT_FONT, row.name, row.valueSize) + 2F;

                Render2D.text(TEXT_FONT, row.name, sectionTextX, sectionTextY, row.valueSize, ColorUtil.rgba(128, 128, 128, Math.round(245.0F * rowAlpha)));
                Render2D.rect(sectionLineX, currentY + 3f, Math.max(0.0F, state.x + state.width - sectionLineX), 1, 1.0F, ColorUtil.rgba(128, 128, 128, Math.round(35.0F * rowAlpha)));

                continue;
            }

            float effectIconSize = 8.0F;
            float effectIconX = state.x + 5.0F;
            float effectIconY = currentY + 2f;
            float timerX = state.x + state.width - 27.0F;
            float levelWidth = row.level.isBlank() ? 0.0F : Render2D.textWidth(TEXT_FONT, row.level, 6.0F);
            float nameX = state.x + 18.5F;
            float nameWidth = timerX - nameX - levelWidth - (row.level.isBlank() ? 5.0F : 8.0F);
            String visibleName = trimToWidth(row.name, TEXT_FONT, 6.0F, nameWidth);
            float levelX = nameX + Render2D.textWidth(TEXT_FONT, visibleName, 6.0F) + 2.0F;

            Render2D.effectIcon(row.effect, effectIconX, effectIconY, effectIconSize, ColorUtil.rgba(255, 255, 255, Math.round(245.0F * rowAlpha)));
            Render2D.rect(effectIconX + 9.5f, effectIconY + 2.5f, 3.0F, 3.0F, 4.0F, ColorUtil.rgba(128, 128, 128, Math.round(245.0F * rowAlpha)));
            Render2D.text(TEXT_FONT, visibleName, nameX, currentY + 1.7F, 6.0F, ColorUtil.multAlpha(TEXT_COLOR, rowAlpha));
            if (!row.level.isBlank()) {
                Render2D.text(TEXT_FONT, row.level, levelX, currentY + 1.7F, 6.0F, ColorUtil.rgba(128, 128, 128, Math.round(245.0F * rowAlpha)));
            }
            renderTimer(row.value, timerX, currentY + 1.5F, row.valueSize, rowAlpha);
        }
    }

    private RowEntry row(String key, String name, String level, String value, float valueSize, float targetY, Holder<MobEffect> effect, int remainingTicks, boolean section) {
        for (RowEntry entry : rowEntries) {
            if (entry.key.equals(key)) {
                entry.section = section;
                entry.effect = effect;
                entry.name = name;
                entry.level = level;
                entry.value = value;
                entry.valueSize = valueSize;
                entry.remainingTicks = remainingTicks;
                return entry;
            }
        }
        RowEntry entry = new RowEntry(key, section, effect, name, level, value, valueSize, remainingTicks);
        entry.alpha.set(0.0);
        entry.y.set(targetY + 4.0F);
        rowEntries.add(entry);
        return entry;
    }

    private float expiringAlpha(int remainingTicks) {
        if (remainingTicks < 0 || remainingTicks > 100) {
            return 1.0F;
        }
        float pulse = (float) ((Math.sin(System.currentTimeMillis() / 135.0D) + 1.0D) * 0.5D);
        return (155.0F + pulse * 100.0F) / 255.0F;
    }

    private void renderTimer(String text, float x, float y, float size, float alpha) {
        float cellWidth = 3.25F;
        int color = ColorUtil.rgba(255, 255, 255, Math.round(245.0F * alpha));

        for (int i = 0; i < text.length(); i++) {
            String character = timerChar(text.charAt(i));
            float characterWidth = Render2D.textWidth(TEXT_FONT, character, size);
            float characterX = x + i * cellWidth + (cellWidth - characterWidth) * 0.5F + 3;

            Render2D.text(TEXT_FONT, character, characterX, y, size, color);
        }
    }

    private void updatePreviewEffects() {
        long second = System.currentTimeMillis() / 1000L;
        if (second != previewIconSecond) {
            previewIconSecond = second;
            previewPositiveEffect = PREVIEW_POSITIVE_EFFECTS.get(ThreadLocalRandom.current().nextInt(PREVIEW_POSITIVE_EFFECTS.size()));
            previewNegativeEffect = PREVIEW_NEGATIVE_EFFECTS.get(ThreadLocalRandom.current().nextInt(PREVIEW_NEGATIVE_EFFECTS.size()));
        }
    }

    private String formatPotionDurationTicks(int ticks) {
        if (ticks < 0) {
            return "**:**";
        }
        int totalSeconds = Math.max(0, ticks / 20);
        int minutes = Math.min(99, totalSeconds / 60);
        int seconds = totalSeconds % 60;
        return twoDigitsLocal(minutes) + ":" + twoDigitsLocal(seconds);
    }

    private static void renderHudBackground(BuiltBlur blur) {
        if (blur == null) {
            return;
        }
        if (hudBoolean("blur", true)) {
            Render2D.blur(blur);
            return;
        }
        Render2D.rect(
                blur.x(),
                blur.y(),
                blur.width(),
                blur.height(),
                blur.radiusTopLeft(),
                blur.radiusTopRight(),
                blur.radiusBottomRight(),
                blur.radiusBottomLeft(),
                noBlurRectColor(blur.color())
        );
    }

    private static void renderHudGlow(String texture, float x, float y, float width, float height, float radius, int color) {
        if (hudBoolean("glow", true)) {
            Render2D.image(texture, x, y, width, height, radius, color);
        }
    }

    private static boolean hudBoolean(String fieldName, boolean fallback) {
        try {
            Class<?> hudClass = Class.forName("releon.ru.api.module.impl.visual.Hud");
            Object hud = hudClass.getMethod("getInstance").invoke(null);
            if (hud == null) {
                return fallback;
            }
            Field field = hudClass.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object setting = field.get(hud);
            Object value = setting.getClass().getMethod("getValue").invoke(setting);
            return value instanceof Boolean booleanValue ? booleanValue : fallback;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return fallback;
        }
    }

    private static int noBlurRectColor(int color) {
        return ColorUtil.multAlpha(color, NO_BLUR_RECT_ALPHA_SCALE);
    }

    private static String timerChar(char character) {
        return Character.toString(character);
    }

    private static String twoDigitsLocal(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    private String level(MobEffectInstance effect) {
        int level = effect.getAmplifier() + 1;
        return level <= 1 ? "" : Integer.toString(level);
    }

    private boolean isNegativeEffect(MobEffectInstance effect) {
        return MobEffects.SLOW_FALLING.equals(effect.getEffect()) || effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL;
    }

    private static final class RowEntry {
        private final String key;
        private final SmoothAnimation alpha = new SmoothAnimation();
        private final SmoothAnimation y = new SmoothAnimation();
        private boolean section;
        private Holder<MobEffect> effect;
        private String name;
        private String level;
        private String value;
        private float valueSize;
        private int remainingTicks;
        private boolean active;

        private RowEntry(String key, boolean section, Holder<MobEffect> effect, String name, String level, String value, float valueSize, int remainingTicks) {
            this.key = key;
            this.section = section;
            this.effect = effect;
            this.name = name;
            this.level = level;
            this.value = value;
            this.valueSize = valueSize;
            this.remainingTicks = remainingTicks;
        }
    }

    private record RowState(boolean section, Holder<MobEffect> effect, String name, String level, String value, float valueSize, int remainingTicks, float offset, float alpha) {
    }

    private record PotionsState(List<RowState> rows, float iconAlpha, float alpha, float x, float y, float width, float height) {
    }
}
