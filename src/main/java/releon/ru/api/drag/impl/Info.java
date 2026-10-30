package releon.ru.api.drag.impl;

import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;

import java.util.ArrayList;
import java.util.List;

public final class Info extends HudPanel {
    private final List<String> rows = new ArrayList<>(3);
    private double lastX;
    private double lastZ;
    private long lastSampleMs;
    private double blocksPerSecond;

    public Info() {
        super("info", "Info", 10.0F, 70.0F, 92.0F, 50.0F);
    }

    @Override
    public void render() {
        InfoState state = logics();
        if (state == null) {
            return;
        }
        renderInfo(state);
    }

    private InfoState logics() {
        contentVisible(selected());
        if (mc.player == null) {
            return null;
        }

        updateSpeed();
        rows.clear();
        rows.add("FPS: " + mc.getFps());
        rows.add("XYZ: " + Math.round(mc.player.getX()) + " " + Math.round(mc.player.getY()) + " " + Math.round(mc.player.getZ()));
        rows.add("BPS: " + formatBps(blocksPerSecond));

        float width = 92.0F;
        for (String row : rows) {
            width = Math.max(width, Render2D.textWidth(TEXT_FONT, row, 6.0F) + 16.0F);
        }
        size(width, rows.size() * 11.0F + 10.0F);

        return new InfoState(rows, drag.x(), drag.y(), drag.width(), drag.height());
    }

    private void renderInfo(InfoState state) {
        float rowY = state.y + 5.0F;

        HudRenderCompat.background(state.x, state.y, state.width, state.height, 4.0F, 15.0F, 1.2F, ColorUtil.rgba(0, 0, 0, 255));
        for (String row : state.rows) {
            Render2D.text(TEXT_FONT, row, state.x + 8.0F, rowY + 1.8F, 6.0F, TEXT_COLOR);
            rowY += 11.0F;
        }
    }

    private void updateSpeed() {
        long now = System.currentTimeMillis();
        if (lastSampleMs == 0L) {
            lastSampleMs = now;
            lastX = mc.player.getX();
            lastZ = mc.player.getZ();
            return;
        }
        long deltaMs = now - lastSampleMs;
        if (deltaMs < 200L) {
            return;
        }

        double dx = mc.player.getX() - lastX;
        double dz = mc.player.getZ() - lastZ;
        double instant = Math.sqrt(dx * dx + dz * dz) / Math.max(0.001D, deltaMs / 1000.0D);
        blocksPerSecond = blocksPerSecond * 0.65D + instant * 0.35D;
        lastSampleMs = now;
        lastX = mc.player.getX();
        lastZ = mc.player.getZ();
    }

    private String formatBps(double value) {
        int scaled = Math.max(0, (int) Math.round(value * 100.0D));
        int whole = scaled / 100;
        int fraction = scaled % 100;
        return whole + "." + twoDigits(fraction);
    }

    private record InfoState(List<String> rows, float x, float y, float width, float height) {
    }
}
