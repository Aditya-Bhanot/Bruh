package releon.ru.api.drag.impl;

import releon.ru.utils.render.animation.fx.AnimationFxMath;
import releon.ru.utils.render.animation.fx.AnimationFxProfile;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;

public final class AnimationShowcase extends HudPanel {
    private static final AnimationFxProfile[] PROFILES = AnimationFxProfile.values();
    private static final int[] COLORS = {
            ColorUtil.rgba(247, 133, 255, 255),
            ColorUtil.rgba(127, 242, 255, 255),
            ColorUtil.rgba(255, 218, 105, 255),
            ColorUtil.rgba(132, 255, 156, 255),
            ColorUtil.rgba(255, 94, 142, 255)
    };

    public AnimationShowcase() {
        super("animationshowcase", "AnimationShowcase", 430.0F, 180.0F, 158.0F, 158.0F);
    }

    @Override
    public void render() {
        AnimationShowcaseState state = logics();
        renderShowcase(state);
    }

    private AnimationShowcaseState logics() {
        contentVisible(selected());

        size(158.0F, 158.0F);
        return new AnimationShowcaseState(drag.x(), drag.y(), drag.width(), drag.height(), System.currentTimeMillis());
    }

    private void renderShowcase(AnimationShowcaseState state) {
        float centerX = state.x + state.width * 0.5F;
        float centerY = state.y + state.height * 0.5F;
        float radius = Math.min(state.width, state.height) * 0.34F;
        int centerColor = ColorUtil.rgba(255, 255, 255, 115);

        HudRenderCompat.background(state.x, state.y, state.width, state.height, 7.0F, 18.0F, 1.2F, ColorUtil.rgba(0, 0, 0, 230));
        Render2D.rect(state.x + state.width * 0.5F - 2.0F, state.y + state.height * 0.5F - 2.0F, 4.0F, 4.0F, 2.0F, centerColor);

        for (int i = 0; i < PROFILES.length; i++) {
            renderAnimatedNode(PROFILES[i], i, centerX, centerY, radius, state.millis);
        }
    }

    private void renderAnimatedNode(AnimationFxProfile profile, int index, float centerX, float centerY, float radius, long millis) {
        int total = PROFILES.length;
        float value = profile.value(millis, index, total);
        float orbit = millis * 0.00032F + index * AnimationFxMath.TAU / total;
        float radial = radius + profile.offset(millis, index, total);
        float size = 14.0F * profile.scale(millis, index, total);
        float x = centerX + (float) Math.cos(orbit) * radial - size * 0.5F;
        float y = centerY + (float) Math.sin(orbit) * radial - size * 0.5F;
        int alpha = Math.round(120.0F + value * 120.0F);
        int softAlpha = Math.round(35.0F + value * 55.0F);
        int base = COLORS[index % COLORS.length];
        int color = ColorUtil.withAlpha(base, alpha);
        int glow = ColorUtil.withAlpha(base, softAlpha);
        float inner = Math.max(3.0F, size * (0.24F + value * 0.14F));

        if (HudRenderCompat.glowEnabled()) {
            HudRenderCompat.background(x - 2.0F, y - 2.0F, size + 4.0F, size + 4.0F, 6.0F, 18.0F, 1.2F, glow);
        }
        Render2D.rect(x, y, size, size, 5.0F, color);
        Render2D.rect(x + (size - inner) * 0.5F, y + (size - inner) * 0.5F, inner, inner, inner * 0.5F, ColorUtil.rgba(255, 255, 255, Math.round(95.0F + value * 95.0F)));
    }

    private record AnimationShowcaseState(float x, float y, float width, float height, long millis) {
    }
}
