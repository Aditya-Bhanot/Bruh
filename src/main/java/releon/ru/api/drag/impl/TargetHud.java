package releon.ru.api.drag.impl;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.module.impl.visual.ESP;
import releon.ru.api.module.impl.visual.esp.EspHealthTracker;
import releon.ru.utils.render.ScissorUtil;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.Render2DCoordinateSpace;
import releon.ru.utils.render.ui.font.FontType;

public final class TargetHud extends HudPanel {
    private LivingEntity lastTarget;
    private final SmoothAnimation panelAnimation = new SmoothAnimation();
    private final SmoothAnimation healthAnimation = new SmoothAnimation();
    private final SmoothAnimation barAnimation = new SmoothAnimation();
    private final SmoothAnimation absorptionAnimation = new SmoothAnimation();
    private final SmoothAnimation absorptionTextAnimation = new SmoothAnimation();
    private final SmoothAnimation healthIconXAnimation = new SmoothAnimation();
    private final EspHealthTracker localHealthTracker = new EspHealthTracker();
    private int animatedTargetId = Integer.MIN_VALUE;
    private int animatedAbsorptionTargetId = Integer.MIN_VALUE;
    private boolean healthIconXReady;

    public TargetHud() {
        super("targethud", "TargetHud", 140.0F, 130.0F, 110.0F, 31.0F);
    }

    @Override
    public void render() {
        TargetHudState state = logics();
        if (state == null) {
            return;
        }
        renderTargetHud(state);
    }

    private TargetHudState logics() {
        LivingEntity target = target();
        boolean preview = target == null && editPreview() && mc.player != null;
        boolean visible = target != null || preview;

        panelAnimation.update();
        panelAnimation.run(visible ? 1.0 : 0.0, 0.24F, visible ? Easings.EXPO_OUT : Easings.EXPO_IN, true);
        float alpha = panelAnimation.get();
        contentVisible(visible || alpha > 0.01F || panelAnimation.isAlive());
        if (alpha <= 0.0F && !panelAnimation.isAlive()) {
            return null;
        }
        if (preview) {
            target = mc.player;
        } else if (target != null) {
            lastTarget = target;
        } else if (lastTarget != null && lastTarget.isAlive()) {
            target = lastTarget;
        } else if (mc.player != null) {
            target = mc.player;
        }
        if (target == null) {
            return null;
        }

        size(110.0F, 31.0F);
        return new TargetHudState(target, alpha, drag.x(), drag.y(), drag.width(), drag.height());
    }

    private void renderTargetHud(TargetHudState state) {
        String name = state.target.getName().getString();
        float targetHealth = displayHealth(state.target);
        float targetAbsorption = Math.max(0.0F, state.target.getAbsorptionAmount());
        float maxHealth = Math.max(1.0F, state.target.getMaxHealth());
        maxHealth = Math.max(maxHealth, targetHealth);
        float targetProgress = clamp(targetHealth / maxHealth, 0.0F, 1.0F);
        HealthState healthState = animatedHealth(state.target, targetHealth, targetProgress);
        AbsorptionState absorptionState = animatedAbsorption(state.target, targetAbsorption, maxHealth);
        float healthProgress = healthState.progress;
        float absorptionProgress = absorptionState.progress;
        String hp = formatWholeHealth(targetHealth) + "Hp";
        String absorptionText = formatWholeHealth(absorptionState.textAmount) + "Ab";
        float headX = state.x + 5.0F;
        float headY = state.y + 5.0F;
        float headSize = 21.0F;
        float barX = state.x + 32F;
        float barY = state.y + 21.0F;
        float barWidth = state.width - 39.0F;
        float iconSize = 7.5F;
        float iconX = state.x + 33.0F;
        float iconY = state.y + 5.3F;
        float nameX = iconX - 1;
        float hpRight = state.x + state.width - 7.0F;
        float hpCellWidth = 3.35F;
        float absorptionTextWidth = absorptionText.length() * hpCellWidth;
        float targetHealthIconLocalX = state.width - 7.0F - hp.length() * hpCellWidth - iconSize - 1.5F;
        healthIconXAnimation.update();
        if (!healthIconXReady || Math.abs(healthIconXAnimation.get() - targetHealthIconLocalX) > 18.0F) {
            healthIconXAnimation.set(targetHealthIconLocalX);
            healthIconXReady = true;
        } else {
            healthIconXAnimation.run(targetHealthIconLocalX, 0.24F, Easings.EXPO_OUT, true);
        }
        float healthIconX = state.x + healthIconXAnimation.get();
        float glowSize = 20.0F;
        int glowAlpha = Math.round(105.0F * state.alpha);
        int glow = ColorUtil.rgba(247, 133, 255, glowAlpha);
        int iconColor = ColorUtil.rgba(247, 133, 255, Math.round(255.0F * state.alpha));
        int hpColor = ColorUtil.rgba(255, 255, 255, Math.round(255.0F * state.alpha));
        int barBackLeft = ColorUtil.rgba(72, 34, 94, Math.round(42.0F * state.alpha));
        int barBackRight = ColorUtil.rgba(247, 133, 255, Math.round(58.0F * state.alpha));
        int barFillLeft = ColorUtil.rgba(88, 38, 132, Math.round(245.0F * state.alpha));
        int barFillRight = ColorUtil.rgba(247, 133, 255, Math.round(255.0F * state.alpha));
        int absorptionBarLeft = ColorUtil.rgba(133, 93, 13, Math.round(245.0F * state.alpha * absorptionState.barAlpha));
        int absorptionBarRight = ColorUtil.rgba(255, 220, 60, Math.round(255.0F * state.alpha * absorptionState.barAlpha));
        int absorptionTextColor = ColorUtil.rgba(255, 220, 62, Math.round(255.0F * state.alpha * absorptionState.textAlpha));
        float absorptionRight = healthIconX - 2.0F;
        float absorptionX = absorptionRight - absorptionTextWidth;
        float nameRight = absorptionState.textAlpha > 0.01F ? absorptionX - 1.5F : healthIconX - 1.5F;
        float nameSize = 7.0F;
        float nameY = state.y + 6.0F;
        float nameClipWidth = Math.max(0.0F, nameRight - nameX);
        float nameFadeWidth = Math.min(8.0F, Math.max(2.0F, nameClipWidth * 0.35F));
        float nameFadeStrength = Render2D.textWidth(TEXT_FONT, name, nameSize) > nameClipWidth + 0.5F ? 0.88F : 0.0F;

        HudRenderCompat.background(state.x, state.y, state.width, state.height, 6.0F, 15.0F, 1.2F, ColorUtil.rgba(0, 0, 0, Math.round(255.0F * state.alpha)));
        if (state.target instanceof AbstractClientPlayer player) {
            String texture = player.getSkin().body().texturePath().toString();
            int imageAlpha = Math.round(255.0F * state.alpha);

            if (imageAlpha > 3) {
                int color = ColorUtil.rgba(255, 255, 255, imageAlpha);
                boolean base = renderSkinPart(texture, headX, headY, headSize, 8.0F / 64.0F, 8.0F / 64.0F, 16.0F / 64.0F, 16.0F / 64.0F, color);
                boolean overlay = renderSkinPart(texture, headX, headY, headSize, 40.0F / 64.0F, 8.0F / 64.0F, 48.0F / 64.0F, 16.0F / 64.0F, color);
                if (!base && !overlay) {
                    Render2D.image(texture, headX, headY, headSize, 6.0F, color);
                }
            }
        } else {
            String targetName = state.target.getName().getString();
            String letter = state.target instanceof Player && !targetName.isEmpty() ? targetName.substring(0, 1).toUpperCase() : "?";
            float tw = Render2D.textWidth(TITLE_FONT, letter, 10.0F);

            Render2D.rect(headX, headY, headSize, headSize, 4.0F, ColorUtil.rgba(128, 128, 128, Math.round(24.0F * state.alpha)));
            Render2D.text(TITLE_FONT, letter, headX + (headSize - tw) * 0.5F + 0.5F, headY + 4.5F, 10.0F, ColorUtil.multAlpha(TEXT_COLOR, state.alpha));
        }
        if (glowAlpha > 3) {
            HudRenderCompat.glow("releon:textures/particles/ghost-glow.png", healthIconX - 6.5F, iconY - 4.0F, glowSize, glowSize, 0.0F, glow);
        }
        Render2D.text(FontType.MAINMENUSCREEN, "e", healthIconX, iconY + 2, iconSize, iconColor);
        if (nameClipWidth > 0.5F) {
            ScissorUtil.push(
                    Render2DCoordinateSpace.toGuiInt(nameX),
                    Render2DCoordinateSpace.toGuiInt(nameY - 4.0F),
                    Render2DCoordinateSpace.toGuiInt(nameRight),
                    Render2DCoordinateSpace.toGuiInt(nameY + nameSize + 5.0F)
            );
            if (nameFadeStrength > 0.0F) {
                Render2D.textFade(TEXT_FONT, name, nameX, nameY, nameSize, ColorUtil.multAlpha(TEXT_COLOR, state.alpha), nameX, nameRight, nameFadeWidth, 0.0F, nameFadeStrength);
            } else {
                Render2D.text(TEXT_FONT, name, nameX, nameY, nameSize, ColorUtil.multAlpha(TEXT_COLOR, state.alpha));
            }
            ScissorUtil.pop();
        }
        if ((absorptionTextColor >>> 24) > 3) {
            float absorptionY = state.y + 6.8F + absorptionState.textYOffset;
            renderStableTextRight(absorptionText, absorptionRight, absorptionY, 5.5F, hpCellWidth, absorptionTextColor);
        }
        renderStableTextRight(hp, hpRight, state.y + 6.8F, 5.5F, hpCellWidth, hpColor);
        Render2D.rect(barX, barY, barWidth, 4.0F, 1F, barBackLeft, barBackRight, barBackRight, barBackLeft);
        Render2D.rect(barX, barY, barWidth * healthProgress, 4.0F, 1F, barFillLeft, barFillRight, barFillRight, barFillLeft);
        if (absorptionState.barAlpha > 0.01F && absorptionProgress > 0.001F) {
            Render2D.rect(barX, barY - 2.0F, barWidth * absorptionProgress, 2.0F, 1F, absorptionBarLeft, absorptionBarRight, absorptionBarRight, absorptionBarLeft);
        }
    }

    private void renderStableTextRight(String text, float rightX, float y, float size, float cellWidth, int color) {
        float x = rightX - text.length() * cellWidth;

        for (int i = 0; i < text.length(); i++) {
            String character = charText(text.charAt(i));
            float characterWidth = Render2D.textWidth(TEXT_FONT, character, size);
            float characterX = x + i * cellWidth + (cellWidth - characterWidth) * 0.5F;

            Render2D.text(TEXT_FONT, character, characterX, y, size, color);
        }
    }

    private HealthState animatedHealth(LivingEntity target, float health, float progress) {
        boolean newTarget = target.getId() != animatedTargetId;
        boolean fullHealthCorrection = progress >= 0.995F && barAnimation.get() < 0.95F;
        if (newTarget || fullHealthCorrection) {
            animatedTargetId = target.getId();
            healthAnimation.set(health);
            barAnimation.set(progress);
            return new HealthState(health, progress);
        }

        healthAnimation.update();
        barAnimation.update();
        healthAnimation.run(health, 0.34F, Easings.EXPO_OUT, true);
        barAnimation.run(progress, 0.34F, Easings.EXPO_OUT, true);
        return new HealthState(Math.max(0.0F, healthAnimation.get()), clamp(barAnimation.get(), 0.0F, 1.0F));
    }

    private AbsorptionState animatedAbsorption(LivingEntity target, float absorption, float maxHealth) {
        boolean newTarget = target.getId() != animatedAbsorptionTargetId;
        boolean hasAbsorption = absorption > 0.05F;
        if (newTarget) {
            animatedAbsorptionTargetId = target.getId();
            absorptionAnimation.set(absorption);
            absorptionTextAnimation.set(hasAbsorption ? 1.0F : 0.0F);
        } else {
            absorptionAnimation.update();
            absorptionTextAnimation.update();
            absorptionAnimation.run(absorption, hasAbsorption ? 0.28F : 0.34F, Easings.EXPO_OUT, true);
            absorptionTextAnimation.run(hasAbsorption ? 1.0F : 0.0F, hasAbsorption ? 0.20F : 0.32F, hasAbsorption ? Easings.EXPO_OUT : Easings.EXPO_IN, true);
        }

        float animatedAbsorption = Math.max(0.0F, absorptionAnimation.get());
        float textAlpha = clamp(absorptionTextAnimation.get(), 0.0F, 1.0F);
        float textAmount = hasAbsorption ? animatedAbsorption : 0.0F;
        float textYOffset = hasAbsorption ? 0.0F : -(1.0F - textAlpha) * 6.0F;
        float progress = clamp(animatedAbsorption / Math.max(1.0F, maxHealth), 0.0F, 1.0F);
        float barAlpha = Math.max(textAlpha, progress > 0.001F ? 1.0F : 0.0F);
        return new AbsorptionState(textAmount, progress, textAlpha, textYOffset, barAlpha);
    }

    private boolean renderSkinPart(String texture, float x, float y, float size, float u0, float v0, float u1, float v1, int color) {
        if (texture == null || texture.isBlank() || color >>> 24 == 0) {
            return false;
        }
        Render2D.imageUvNearest(texture, x, y, size, size, 4.0F, 1.0F, u0, v0, u1, v1, color);
        return true;
    }

    private float displayHealth(LivingEntity entity) {
        Float espHealth = resolveEspHealth(entity, false);
        if (espHealth != null) {
            return Math.max(0.0F, espHealth);
        }
        return localHealthTracker.resolveDisplayHealth(entity, false);
    }

    private String formatWholeHealth(float health) {
        if (!Float.isFinite(health)) {
            return "0";
        }
        return Integer.toString(Math.max(0, Math.round(health)));
    }

    private Float resolveEspHealth(LivingEntity entity, boolean includeAbsorption) {
        return ESP.resolveHudHealth(entity, includeAbsorption);
    }

    private LivingEntity target() {
        if (AuraModule.target != null && AuraModule.target.isAlive()) {
            return AuraModule.target;
        }
        Entity crosshair = mc.crosshairPickEntity;
        if (crosshair instanceof LivingEntity living && living.isAlive()) {
            return living;
        }
        return null;
    }

    private record TargetHudState(LivingEntity target, float alpha, float x, float y, float width, float height) {
    }

    private record HealthState(float health, float progress) {
    }

    private record AbsorptionState(float textAmount, float progress, float textAlpha, float textYOffset, float barAlpha) {
    }
}
