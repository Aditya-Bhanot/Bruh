package releon.ru.api.drag.impl;

import releon.ru.api.module.ModuleManager;
import releon.ru.api.module.impl.misc.ServerHelper;
import releon.ru.manager.Manager;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;

import java.util.List;

public final class Events extends HudPanel {
    public Events() {
        super("events", "Events", 140.0F, 200.0F, 104.0F, 23.0F);
    }

    @Override
    public void render() {
        EventsState state = logics();
        if (state == null) {
            return;
        }
        renderEvents(state);
    }

    private EventsState logics() {
        List<ServerHelper.HudEvent> events = events();
        boolean preview = events.isEmpty() && editPreview();
        boolean visible = !events.isEmpty() || preview;
        float alpha = contentAlpha(visible);
        if (alpha <= 0.0F) {
            return null;
        }

        int rows = preview ? 1 : Math.max(1, events.size());
        float width = 104.0F;
        if (preview) {
            width = Math.max(width, Render2D.textWidth(TEXT_FONT, "Example Event", 6.0F) + 68.0F);
        } else {
            for (ServerHelper.HudEvent event : events) {
                width = Math.max(width, Render2D.textWidth(TEXT_FONT, event.name(), 6.0F) + Render2D.textWidth(TEXT_FONT, event.timer(), 5.5F) + 38.0F);
            }
        }
        size(width, rows * 13.0F + 10.0F);

        return new EventsState(events, preview, alpha, drag.x(), drag.y(), drag.width(), drag.height());
    }

    private void renderEvents(EventsState state) {
        float rowY = state.y + 5.0F;

        HudRenderCompat.background(state.x, state.y, state.width, state.height, 4.0F, 15.0F, 1.2F, ColorUtil.rgba(0, 0, 0, Math.round(255.0F * state.alpha)));

        if (state.preview) {
            String name = "Example Event";
            String timer = "Start 1:30";
            String distanceText = distanceText(128.0D);
            float timerWidth = Render2D.textWidth(TEXT_FONT, timer, 5.5F);
            String visibleName = trimToWidth(name + " " + distanceText, TEXT_FONT, 6.0F, state.width - timerWidth - 24.0F);

            Render2D.text(TEXT_FONT, visibleName, state.x + 8.0F, rowY + 1.8F, 6.0F, ColorUtil.multAlpha(TEXT_COLOR, state.alpha));
            Render2D.text(TEXT_FONT, timer, state.x + state.width - timerWidth - 8.0F, rowY + 2.1F, 5.5F, ColorUtil.multAlpha(MUTED_COLOR, state.alpha));
            return;
        }

        for (ServerHelper.HudEvent event : state.events) {
            String name = event.name();
            String timer = event.timer();
            String distanceText = distanceText(event.distance());
            float timerWidth = Render2D.textWidth(TEXT_FONT, timer, 5.5F);
            String visibleName = trimToWidth(name + " " + distanceText, TEXT_FONT, 6.0F, state.width - timerWidth - 24.0F);

            Render2D.text(TEXT_FONT, visibleName, state.x + 8.0F, rowY + 1.8F, 6.0F, ColorUtil.multAlpha(TEXT_COLOR, state.alpha));
            Render2D.text(TEXT_FONT, timer, state.x + state.width - timerWidth - 8.0F, rowY + 2.1F, 5.5F, ColorUtil.multAlpha(MUTED_COLOR, state.alpha));
            rowY += 13.0F;
        }
    }

    private List<ServerHelper.HudEvent> events() {
        ModuleManager manager = Manager.getModules();
        if (manager == null) {
            return List.of();
        }
        return manager.getByType(ServerHelper.class)
                .map(ServerHelper::getHudEvents)
                .orElse(List.of());
    }

    private String distanceText(double distance) {
        if (!Double.isFinite(distance)) {
            return "0m";
        }
        return Math.max(0L, Math.round(distance)) + "m";
    }

    private record EventsState(List<ServerHelper.HudEvent> events, boolean preview, float alpha, float x, float y, float width, float height) {
    }
}
