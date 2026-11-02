package releon.ru.api.drag.impl;

import net.minecraft.client.input.MouseButtonEvent;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

public final class MediaPlayer extends HudPanel {
    private MediaInfo media = MediaInfo.empty();
    private Object session;
    private long lastPollMs;
    private boolean unavailable;
    private boolean iconPulseWasPlaying;
    private long iconPulseStartedAt;
    private float smoothedIconAlpha = 100.0F;
    private float smoothDurationWidth = 1.0F;
    private final SmoothAnimation panelAnimation = new SmoothAnimation();

    public MediaPlayer() {
        super("mediaplayer", "MediaPlayer", 10.0F, 120.0F, 132.0F, 42.0F);
    }

    @Override
    public void render() {
        MediaPlayerState state = logics();
        if (state == null) {
            return;
        }
        renderMediaPlayer(state);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (event == null || event.button() != 0 || !drag.visible() || session == null) {
            return false;
        }

        float x = drag.x();
        float y = drag.y();
        float textX = x + 42.0F;
        float controlsCenterX = textX + ((132.0F - 50.0F) * 0.5F);
        float controlsY = y + 24.5F;
        float hitHalf = 7.0F;

        if (hit(event, controlsCenterX - 9.0F, controlsY, hitHalf)) {
            invokeSession("previous");
            return true;
        }
        if (hit(event, controlsCenterX, controlsY, hitHalf)) {
            invokeSession("playPause");
            return true;
        }
        if (hit(event, controlsCenterX + 9.0F, controlsY, hitHalf)) {
            invokeSession("next");
            return true;
        }
        return false;
    }

    private MediaPlayerState logics() {
        pollMedia();
        boolean preview = media.isEmpty() && editPreview();
        boolean visible = !media.isEmpty() || preview;

        panelAnimation.update();
        panelAnimation.run(visible ? 1.0 : 0.0, 0.24F, visible ? Easings.EXPO_OUT : Easings.EXPO_IN, true);
        float alpha = panelAnimation.get();
        contentVisible(visible || alpha > 0.01F || panelAnimation.isAlive());
        if (alpha <= 0.0F && !panelAnimation.isAlive()) {
            return null;
        }

        MediaInfo shown = preview ? new MediaInfo("No track", "No artist", 0L, 0L, false) : media;
        size(132.0F, 42.0F);

        return new MediaPlayerState(shown, alpha, drag.x(), drag.y(), drag.width(), drag.height());
    }

    private void renderMediaPlayer(MediaPlayerState state) {
        float x = state.x;
        float y = state.y;
        float w = 132.0F;
        float h = 42.0F;
        float artworkSize = 32.5F;
        float textX = x + 42.0F;
        float textWidth = w - 50.0F;
        int duration = safeDuration(state.media);
        int position = safePosition(state.media, duration);
        int iconAlpha = resolveMusicIconAlpha(state.media.playing, state.alpha);
        float targetWidth = Math.max(1.0F, (position / (float) duration) * textWidth);
        String title = trimToWidth(safe(state.media.title), TITLE_FONT, 6.0F, textWidth);
        String artist = trimToWidth(safe(state.media.artist), TEXT_FONT, 5.5F, textWidth);
        String posText = formatDuration(position);
        String durText = formatDuration(duration);
        float durWidth = Render2D.textWidth(TEXT_FONT, durText, 5.0F);
        float controlsCenterX = textX + textWidth * 0.5F;
        float controlsY = y + 26F;
        int background = ColorUtil.rgba(0, 0, 0, Math.round(255.0F * state.alpha));
        int coverColor = ColorUtil.rgba(80, 80, 80, Math.round(50.0F * state.alpha));
        int white = ColorUtil.rgba(255, 255, 255, Math.round(255.0F * state.alpha));
        int muted = ColorUtil.rgba(187, 187, 187, Math.round(255.0F * state.alpha));
        int barBack = ColorUtil.rgba(58, 58, 58, Math.round(255.0F * state.alpha));
        int purple = ColorUtil.rgba(158, 107, 255, Math.round(255.0F * state.alpha));

        smoothDurationWidth = clamp(smoothDurationWidth + (targetWidth - smoothDurationWidth) * 0.2F, 1.0F, textWidth);

        HudRenderCompat.background(x, y, w, h, 5.0F, 15.0F, 1.2F, background);
        Render2D.rect(x + 5.0F, y + 5.0F, artworkSize, artworkSize, 4.0F, coverColor);
        drawIconCentered("u", x + 21F, y + 12.5F, 20, ColorUtil.rgba(255, 255, 255, iconAlpha));

        Render2D.text(TITLE_FONT, title, textX, y + 8.0F, 6.0F, white);
        Render2D.text(TEXT_FONT, artist, textX, y + 17.0F, 5.5F, muted);

        Render2D.text(TEXT_FONT, posText, textX, y + 28.0F, 5.0F, white);
        Render2D.text(TEXT_FONT, durText, x + w - durWidth - 8.0F, y + 28.0F, 5.0F, white);

        Render2D.rect(textX, y + h - 7.0F, textWidth, 2.0F, 1.0F, barBack);
        Render2D.rect(textX, y + h - 7.0F, smoothDurationWidth, 2.0F, 1.0F, purple);

        drawIconCentered("s", controlsCenterX - 9.0F, controlsY + 0.5f, 6.0F, white);
        drawIconCentered(state.media.playing ? "p" : "o", controlsCenterX, controlsY, 7.5F, white);
        drawIconCentered("n", controlsCenterX + 9.0F, controlsY + 0.75f, 6.0F, white);
    }

    private void pollMedia() {
        if (unavailable || System.currentTimeMillis() - lastPollMs < 350L) {
            return;
        }
        lastPollMs = System.currentTimeMillis();

        try {
            Class<?> api = Class.forName("dev.redstones.mediaplayerinfo.MediaPlayerInfo");
            Field instanceField = api.getField("Instance");
            Object instance = instanceField.get(null);
            Method sessionsMethod = instance.getClass().getMethod("getMediaSessions");
            Object result = sessionsMethod.invoke(instance);
            if (!(result instanceof List<?> sessions) || sessions.isEmpty()) {
                session = null;
                media = MediaInfo.empty();
                return;
            }

            Object best = null;
            Object bestMedia = null;
            int bestPlaying = -1;
            int bestTitle = -1;
            int bestArtist = -1;
            long bestDuration = -1L;
            for (Object candidate : sessions) {
                Object candidateMedia = mediaObject(candidate);
                if (candidateMedia == null) {
                    continue;
                }

                int playingScore = bool(candidateMedia, "getPlaying") ? 1 : 0;
                int titleScore = safe(string(candidateMedia, "getTitle", "")).isEmpty() ? 0 : 1;
                int artistScore = safe(string(candidateMedia, "getArtist", "")).isEmpty() ? 0 : 1;
                long durationScore = number(candidateMedia, "getDuration");
                if (best == null
                        || playingScore > bestPlaying
                        || playingScore == bestPlaying && titleScore > bestTitle
                        || playingScore == bestPlaying && titleScore == bestTitle && artistScore > bestArtist
                        || playingScore == bestPlaying && titleScore == bestTitle && artistScore == bestArtist && durationScore > bestDuration) {
                    best = candidate;
                    bestMedia = candidateMedia;
                    bestPlaying = playingScore;
                    bestTitle = titleScore;
                    bestArtist = artistScore;
                    bestDuration = durationScore;
                }
            }
            if (bestMedia == null) {
                session = null;
                media = MediaInfo.empty();
                return;
            }

            session = best;
            media = new MediaInfo(
                    string(bestMedia, "getTitle", "No track"),
                    string(bestMedia, "getArtist", "No artist"),
                    number(bestMedia, "getPosition"),
                    number(bestMedia, "getDuration"),
                    bool(bestMedia, "getPlaying")
            );
        } catch (Throwable ignored) {
            unavailable = true;
            session = null;
            media = MediaInfo.empty();
        }
    }

    private Object mediaObject(Object session) {
        if (session == null) {
            return null;
        }
        try {
            return session.getClass().getMethod("getMedia").invoke(session);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void invokeSession(String method) {
        if (session == null) {
            return;
        }
        try {
            session.getClass().getMethod(method).invoke(session);
            lastPollMs = 0L;
        } catch (Throwable ignored) {
        }
    }

    private boolean hit(MouseButtonEvent event, float centerX, float centerY, float half) {
        return event.x() >= centerX - half && event.x() <= centerX + half && event.y() >= centerY - half && event.y() <= centerY + half;
    }

    private void drawIconCentered(String icon, float centerX, float y, float size, int color) {
        float width = Render2D.textWidth(FontType.MAINMENUSCREEN, icon, size);
        Render2D.text(FontType.MAINMENUSCREEN, icon, centerX - width * 0.5F, y, size, color);
    }

    private String formatDuration(int seconds) {
        int safe = Math.max(0, seconds);
        return twoDigits(safe / 60) + ":" + twoDigits(safe % 60);
    }

    private int safeDuration(MediaInfo info) {
        return Math.max(1, (int) (info == null ? 0L : info.duration));
    }

    private int safePosition(MediaInfo info, int duration) {
        int position = Math.max(0, (int) (info == null ? 0L : info.position));
        return Math.min(position, Math.max(1, duration));
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }

    private String string(Object object, String method, String fallback) {
        try {
            Object value = object.getClass().getMethod(method).invoke(object);
            String text = value == null ? "" : value.toString();
            return text.isBlank() ? fallback : text;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private long number(Object object, String method) {
        try {
            Object value = object.getClass().getMethod(method).invoke(object);
            return value instanceof Number number ? Math.max(0L, number.longValue()) : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private boolean bool(Object object, String method) {
        try {
            Object value = object.getClass().getMethod(method).invoke(object);
            return value instanceof Boolean bool && bool;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int resolveMusicIconAlpha(boolean playing, float widgetAlpha) {
        long now = System.currentTimeMillis();
        if (playing != iconPulseWasPlaying) {
            iconPulseWasPlaying = playing;
            iconPulseStartedAt = now;
        }

        float targetAlpha;
        if (!playing) {
            targetAlpha = 100.0F;
        } else {
            long elapsed = Math.max(0L, now - iconPulseStartedAt);
            double phase = ((elapsed % 1800L) / 1800.0D) * (Math.PI * 2.0D);
            double wave = Math.sin(phase - Math.PI / 2.0D);
            targetAlpha = 150.0F + (float) (50.0D * wave);
        }

        smoothedIconAlpha += (targetAlpha - smoothedIconAlpha) * 0.14F;
        if (Math.abs(targetAlpha - smoothedIconAlpha) < 0.5F) {
            smoothedIconAlpha = targetAlpha;
        }
        return Math.round(clamp(smoothedIconAlpha * widgetAlpha, 0.0F, 255.0F));
    }

    private record MediaInfo(String title, String artist, long position, long duration, boolean playing) {
        private static MediaInfo empty() {
            return new MediaInfo("", "", 0L, 0L, false);
        }

        private boolean isEmpty() {
            return title == null || title.isBlank();
        }
    }

    private record MediaPlayerState(MediaInfo media, float alpha, float x, float y, float width, float height) {
    }
}
