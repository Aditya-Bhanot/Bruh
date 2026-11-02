package releon.ru.api.drag.impl;

import net.minecraft.sounds.SoundEvent;
import releon.ru.utils.sounds.SoundManager;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.util.ArrayList;
import java.util.List;

public final class Notifications extends HudPanel {

    private static final long STAY_MS = 2500L;
    private static final float ANIM_S = 0.35F;
    private static final float ROW_HEIGHT = 23.0F;
    private static final float ROW_GAP = 3.0F;

    private static final float SPRING_K = 180.0F;
    private static final float SPRING_D = 12.0F;
    private static final float SPRING_DT = 0.016F;
    private static final int MAX_NOTIFICATIONS = 8;

    private static final List<NotifEntry> NOTIFICATIONS = new ArrayList<>();

    private final SmoothAnimation previewAnim = new SmoothAnimation();
    private boolean wasPreview = false;

    public Notifications() {
        super("notifications", "Notifications", 10.0F, 330.0F, 118.0F, 24.0F);
    }

    public static void push(String title, String text) {
        push(title, text, STAY_MS);
    }

    public static void push(String title, String text, long stayMs) {
        push(title, text, stayMs, null);
    }

    public static void push(String title, String text, long stayMs, SoundEvent sound) {
        if (NOTIFICATIONS.size() >= MAX_NOTIFICATIONS) {
            NOTIFICATIONS.subList(0, NOTIFICATIONS.size() - MAX_NOTIFICATIONS + 1).clear();
        }

        NotifEntry e = new NotifEntry(title == null || title.isBlank() ? "Notification" : title, text == null ? "" : text, System.currentTimeMillis() + Math.max(1L, stayMs));
        float startY = stackHeight();
        e.currentY = Math.max(0.0F, startY - ROW_GAP);
        e.targetY = startY;
        e.velocityY = 0;

        NOTIFICATIONS.add(e);

        e.anim.run(1.0, ANIM_S, Easings.EXPO_OUT);
        if (sound != null) {
            SoundManager.playSoundDirect(sound, 0.7F, 1.0F);
        }
    }

    @Override
    public void render() {
        NotificationsState state = logics();
        if (state == null) {
            return;
        }
        renderNotifications(state);
    }

    private NotificationsState logics() {
        long now = System.currentTimeMillis();

        for (NotifEntry e : NOTIFICATIONS) {
            e.anim.update();
            if (!e.exiting && now > e.removeTime) {
                e.exiting = true;
                e.anim.run(0.0, ANIM_S, Easings.EXPO_IN);
            }
        }

        NOTIFICATIONS.removeIf(e -> e.exiting && e.anim.isFinished());

        float offsetY = 0;
        for (NotifEntry e : NOTIFICATIONS) {
            float a = clamp(e.anim.get(), 0.0F, 1.0F);
            e.targetY = offsetY;
            offsetY += (ROW_HEIGHT + ROW_GAP) * a;
        }

        for (NotifEntry e : NOTIFICATIONS) {
            float diff = e.targetY - e.currentY;
            e.velocityY += (diff * SPRING_K - e.velocityY * SPRING_D) * SPRING_DT;
            e.currentY += e.velocityY * SPRING_DT;
            if (Math.abs(diff) < 0.01F && Math.abs(e.velocityY) < 0.01F) {
                e.currentY = e.targetY;
                e.velocityY = 0;
            }
        }

        previewAnim.update();
        boolean preview = NOTIFICATIONS.isEmpty() && editPreview();
        if (preview && !wasPreview) {
            previewAnim.run(1.0, ANIM_S, Easings.EXPO_OUT);
        } else if (!preview && wasPreview) {
            previewAnim.run(0.0, ANIM_S, Easings.EXPO_IN);
        }
        wasPreview = preview;

        boolean visible = !NOTIFICATIONS.isEmpty() || previewAnim.get() > 0.01F;
        contentVisible(visible);
        if (!visible) {
            return null;
        }

        float width = 118.0F;
        if (!preview) {
            for (NotifEntry e : NOTIFICATIONS) {
                width = Math.max(width, Math.max(Render2D.textWidth(TITLE_FONT, e.title, 6.4F), Render2D.textWidth(TEXT_FONT, e.text, 5.6F)) + 20.0F);
            }
        }
        drag.size((float) Math.ceil(width), ROW_HEIGHT);

        return new NotificationsState(preview, previewAnim.get(), drag.x(), drag.y(), drag.width());
    }

    private void renderNotifications(NotificationsState state) {
        if (state.previewAlpha > 0.01F) {
            String title = "Notification";
            String text = "Example notification";
            float x = state.x;
            float y = state.y;
            float width = state.width;
            float alpha = clamp(state.previewAlpha, 0.0F, 1.0F);
            int background = ColorUtil.rgba(0, 0, 0, Math.round(255.0F * alpha));
            int glow = ColorUtil.rgba(247, 133, 255, Math.round(130.0F * alpha));
            int icon = ColorUtil.rgba(247, 133, 255, Math.round(255.0F * alpha));
            int titleColor = ColorUtil.rgba(255, 255, 255, Math.round(245.0F * alpha));
            int textColor = ColorUtil.rgba(183, 190, 202, Math.round(215.0F * alpha));
            String titleText = trimToWidth(title, TITLE_FONT, 6F, width - 20.0F);
            String bodyText = trimToWidth(text, TEXT_FONT, 5F, width - 20.0F);

            HudRenderCompat.background(x, y, width, 23.0F, 4.0F, 15.0F, 1.2F, background);
            HudRenderCompat.glow("releon:textures/particles/ghost-glow.png", x - 20, y - 20, 64.0f, 64.0f, 0.0f, glow);
            Render2D.text(FontType.MAINMENUSCREEN, "v", x + 5, y + 5, 14f, icon);
            Render2D.text(TITLE_FONT, titleText, x + 20.0F, y + 4.5F, 6.4F, titleColor);
            Render2D.text(TEXT_FONT, bodyText, x + 19.85F, y + 11.5F, 5.6F, textColor);
        }
        if (state.preview) {
            return;
        }

        for (NotifEntry e : NOTIFICATIONS) {
            float anim = e.anim.get();
            if (anim < 0.01F) {
                continue;
            }

            String title = e.title;
            String text = e.text;
            float x = state.x;
            float y = state.y + e.currentY;
            float width = state.width;
            float alpha = clamp(anim, 0.0F, 1.0F);
            int background = ColorUtil.rgba(0, 0, 0, Math.round(255.0F * alpha));
            int glow = ColorUtil.rgba(247, 133, 255, Math.round(130.0F * alpha));
            int icon = ColorUtil.rgba(247, 133, 255, Math.round(255.0F * alpha));
            int titleColor = ColorUtil.rgba(255, 255, 255, Math.round(245.0F * alpha));
            int textColor = ColorUtil.rgba(183, 190, 202, Math.round(215.0F * alpha));
            String titleText = trimToWidth(title, TITLE_FONT, 6F, width - 20.0F);
            String bodyText = trimToWidth(text, TEXT_FONT, 5F, width - 20.0F);

            HudRenderCompat.background(x, y, width, 23.0F, 4.0F, 15.0F, 1.2F, background);
            HudRenderCompat.glow("releon:textures/particles/ghost-glow.png", x - 20, y - 20, 64.0f, 64.0f, 0.0f, glow);
            Render2D.text(FontType.MAINMENUSCREEN, "v", x + 5, y + 5, 14f, icon);
            Render2D.text(TITLE_FONT, titleText, x + 20.0F, y + 4.5F, 6.4F, titleColor);
            Render2D.text(TEXT_FONT, bodyText, x + 19.85F, y + 11.5F, 5.6F, textColor);
        }
    }

    private static float stackHeight() {
        float height = 0.0F;
        for (NotifEntry e : NOTIFICATIONS) {
            height += (ROW_HEIGHT + ROW_GAP) * clamp(e.anim.get(), 0.0F, 1.0F);
        }
        return height;
    }

    private static final class NotifEntry {
        final String title;
        final String text;
        final long removeTime;
        final SmoothAnimation anim = new SmoothAnimation();
        boolean exiting = false;
        float currentY = 0;
        float targetY = 0;
        float velocityY = 0;

        NotifEntry(String title, String text, long removeTime) {
            this.title = title;
            this.text = text;
            this.removeTime = removeTime;
        }
    }

    private record NotificationsState(boolean preview, float previewAlpha, float x, float y, float width) {
    }
}
