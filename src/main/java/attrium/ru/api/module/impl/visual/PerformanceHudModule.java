package attrium.ru.api.module.impl.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.manager.ClientThemeManager;
import attrium.ru.manager.HudManager;
import attrium.ru.manager.PerformanceProfiler;

import java.util.Locale;

/**
 * PerfMonitor — Frosted-Glass Zero-Allocation Micro-Profiler HUD Widget.
 *
 * Disabled by default. When enabled, activates PerformanceProfiler sampling and renders
 * live FPS, frame time, 60-frame spike, client tick latency, HUD render cost,
 * hottest HUD widget, JVM heap usage, and a compact 36-bar frame-time sparkline.
 */
public class PerformanceHudModule extends HudModule {
    private static volatile PerformanceHudModule instance;

    private final BooleanSetting showGraph = register(new BooleanSetting(
            "Frame Graph",
            "Draws a compact 36-bar frame-time sparkline inside the monitor card.",
            true
    ));

    private long lastTextRebuildMs = 0L;
    private Component titleComp;
    private Component fpsBadgeComp;
    private Component row1Comp;
    private Component row2Comp;
    private Component row3Comp;

    public PerformanceHudModule() {
        super("PerfMonitor", "Live zero-allocation FPS, tick, HUD, and frame-spike profiler.",
                "P", 0.015D, 0.72D, 210.0D, false);
        instance = this;
    }

    public static PerformanceHudModule getInstance() {
        return instance;
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        PerformanceProfiler.setEnabled(true);
    }

    @Override
    protected void onDisable() {
        PerformanceProfiler.setEnabled(false);
        super.onDisable();
    }

    private void rebuildCachedComponentsIfNeeded(long nowMs) {
        if (titleComp != null && (nowMs - lastTextRebuildMs) < 200L) {
            return;
        }
        lastTextRebuildMs = nowMs;

        if (titleComp == null) {
            titleComp = Component.literal("PerfMonitor").withStyle(HudManager.getBoldStyle());
        }

        int fps = PerformanceProfiler.getSmoothedFps();
        float fMs = PerformanceProfiler.getEmaFrameMs();
        float peakMs = PerformanceProfiler.getPeakFrameMs();
        float tickMs = PerformanceProfiler.getEmaTickMs();
        float hudMs = PerformanceProfiler.getEmaHudMs();
        String hot = PerformanceProfiler.getSlowestWidgetName();
        if (hot == null || hot.isEmpty()) hot = "-";
        if (hot.length() > 11) hot = hot.substring(0, 10) + ".";
        float hotMs = PerformanceProfiler.getSlowestWidgetMs();
        int usedMb = PerformanceProfiler.getUsedHeapMb();
        int maxMb = PerformanceProfiler.getMaxHeapMb();

        fpsBadgeComp = Component.literal(fps + " FPS").withStyle(HudManager.getBoldStyle());
        row1Comp = Component.literal(String.format(Locale.ROOT, "Frame %.1fms  |  Spike %.1fms", fMs, peakMs))
                .withStyle(HudManager.getRegularStyle());
        row2Comp = Component.literal(String.format(Locale.ROOT, "Tick %.2fms  |  HUD %.2fms", tickMs, hudMs))
                .withStyle(HudManager.getRegularStyle());
        row3Comp = Component.literal(String.format(Locale.ROOT, "Hot %s (%.2fms)  |  %d/%dMB", hot, hotMs, usedMb, maxMb))
                .withStyle(HudManager.getRegularStyle());
    }

    @Override
    public void renderHud(Minecraft mc, GuiGraphics g, Font font,
                          int screenW, int screenH, float dt, float animClockSeconds,
                          boolean previewMode, boolean editorMode) {
        PerformanceProfiler.setEnabled(true);
        rebuildCachedComponentsIfNeeded(System.currentTimeMillis());

        boolean drawGraph = Boolean.TRUE.equals(showGraph.getValue());
        float sc = getHudScale();
        int cardW = 166;
        int cardH = drawGraph ? 78 : 58;

        int boxW = Math.round(cardW * sc);
        int boxH = Math.round(cardH * sc);
        int baseX = Mth.clamp(Math.round(getXRatio() * screenW), 2, Math.max(2, screenW - boxW - 2));
        int baseY = Mth.clamp(Math.round(getYRatio() * screenH), 2, Math.max(2, screenH - boxH - 2));
        this.lastBoxX = baseX;
        this.lastBoxY = baseY;
        this.lastBoxW = boxW;
        this.lastBoxH = boxH;

        int accent = ClientThemeManager.getAccentArgb();
        float frameMs = PerformanceProfiler.getEmaFrameMs();
        int statusCol = frameMs <= 16.8F ? 0xFF34D399 : (frameMs <= 33.5F ? 0xFFFBBF24 : 0xFFF87171);
        int bgCol = (Math.max(195, getBackgroundAlphaByte()) << 24) | 0x10131A;

        g.pose().pushMatrix();
        g.pose().translate(baseX, baseY);
        g.pose().scale(sc, sc);

        // Frosted acrylic glass card + subtle accent rim
        HudManager.drawRoundedRect(g, -1, -1, cardW + 2, cardH + 2, 11,
                ClientThemeManager.getAccentWithAlpha(0.18F));
        HudManager.drawGlassCard(g, 0, 0, cardW, cardH, 10, bgCol, 1.0F);

        // Header: title + color-coded FPS pill
        g.drawString(font, titleComp, 8, 6, 0xFFF8FAFC, false);
        int bw = font.width(fpsBadgeComp);
        int pillW = bw + 10;
        int pillX = cardW - pillW - 7;
        HudManager.drawRoundedRect(g, pillX, 4, pillW, 13, 6, (0x30 << 24) | (statusCol & 0x00FFFFFF));
        g.drawString(font, fpsBadgeComp, pillX + 5, 6, statusCol, false);

        g.fill(8, 20, cardW - 8, 21, 0x1AFFFFFF);

        // Metrics rows (rebuilt only at 5 Hz — zero per-frame String churn)
        g.drawString(font, row1Comp, 8, 24, 0xFFE2E8F0, false);
        g.drawString(font, row2Comp, 8, 35, 0xFFCBD5E1, false);
        g.drawString(font, row3Comp, 8, 46, 0xFF94A3B8, false);

        // Optional 36-bar frame-time sparkline
        if (drawGraph) {
            int gx = 8;
            int gy = 58;
            int gw = cardW - 16;
            int gh = 14;
            g.fill(gx, gy, gx + gw, gy + gh, 0x33000000);

            int bars = 36;
            int count = PerformanceProfiler.getRingCount();
            int startSample = Math.max(0, count - bars);
            int drawBars = Math.min(bars, count);
            int barW = Math.max(2, gw / bars);

            for (int i = 0; i < drawBars; i++) {
                float ms = PerformanceProfiler.getRingSampleChronological(startSample + i);
                float norm = Mth.clamp(ms / 40.0F, 0.08F, 1.0F);
                int bh = Math.max(1, Math.round(norm * (gh - 2)));
                int col = ms <= 16.8F ? accent : (ms <= 33.5F ? 0xFFFBBF24 : 0xFFF87171);
                int bx = gx + i * barW;
                g.fill(bx, gy + gh - bh - 1, bx + Math.max(1, barW - 1), gy + gh - 1, col);
            }
        }

        g.pose().popMatrix();
    }
}
