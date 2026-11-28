package attrium.ru.manager;

/**
 * Zero-Allocation Runtime Micro-Profiler for Attrium (Fabric 1.21.11 / FCL Mobile).
 *
 * Tracks smoothed (EMA) and peak frame times, client tick pipeline latency,
 * HUD render cost, slowest active HUD widget, and throttled JVM heap usage
 * without creating any per-frame or per-tick objects.
 */
public final class PerformanceProfiler {
    private static final int RING_SIZE = 60;
    private static final float[] FRAME_MS_RING = new float[RING_SIZE];
    private static int ringIndex = 0;
    private static int ringCount = 0;

    private static volatile boolean enabled = false;
    private static long lastFrameNs = 0L;

    // Exponential Moving Averages (ms)
    private static float emaFrameMs = 16.6F;
    private static float peakFrameMs = 16.6F;
    private static float emaTickMs = 0.0F;
    private static float emaHudMs = 0.0F;

    // Per-frame HUD widget tracking (no HashMap, no String allocations)
    private static String curSlowestWidget = "-";
    private static long curSlowestWidgetNs = 0L;
    private static String lastSlowestWidget = "-";
    private static float lastSlowestWidgetMs = 0.0F;

    // Throttled heap readout (sampled every 250ms)
    private static long lastMemPollMs = 0L;
    private static int usedHeapMb = 0;
    private static int maxHeapMb = 0;

    private PerformanceProfiler() {
    }

    public static void setEnabled(boolean active) {
        enabled = active;
        if (!active) {
            lastFrameNs = 0L;
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static long beginTick() {
        return enabled ? System.nanoTime() : 0L;
    }

    public static void endTick(long startNs) {
        if (startNs == 0L || !enabled) {
            return;
        }
        float ms = (System.nanoTime() - startNs) * 1.0e-6F;
        if (ms >= 0.0F && ms < 500.0F) {
            emaTickMs = emaTickMs == 0.0F ? ms : (emaTickMs * 0.88F + ms * 0.12F);
        }
    }

    public static long beginHudFrame() {
        if (!enabled) {
            return 0L;
        }
        long now = System.nanoTime();
        if (lastFrameNs != 0L) {
            float frameMs = (now - lastFrameNs) * 1.0e-6F;
            if (frameMs > 0.1F && frameMs < 500.0F) {
                emaFrameMs = emaFrameMs * 0.90F + frameMs * 0.10F;
                FRAME_MS_RING[ringIndex] = frameMs;
                ringIndex = (ringIndex + 1) % RING_SIZE;
                if (ringCount < RING_SIZE) {
                    ringCount++;
                }
                float peak = 0.0F;
                for (int i = 0; i < ringCount; i++) {
                    if (FRAME_MS_RING[i] > peak) {
                        peak = FRAME_MS_RING[i];
                    }
                }
                peakFrameMs = peak;
            }
        }
        lastFrameNs = now;
        curSlowestWidget = "-";
        curSlowestWidgetNs = 0L;
        return now;
    }

    public static void recordHudWidget(String widgetName, long elapsedNs) {
        if (!enabled || widgetName == null) {
            return;
        }
        if (elapsedNs > curSlowestWidgetNs) {
            curSlowestWidgetNs = elapsedNs;
            curSlowestWidget = widgetName;
        }
    }

    public static void endHudFrame(long startNs) {
        if (startNs == 0L || !enabled) {
            return;
        }
        float hudMs = (System.nanoTime() - startNs) * 1.0e-6F;
        if (hudMs >= 0.0F && hudMs < 250.0F) {
            emaHudMs = emaHudMs == 0.0F ? hudMs : (emaHudMs * 0.88F + hudMs * 0.12F);
        }
        lastSlowestWidget = curSlowestWidget;
        lastSlowestWidgetMs = curSlowestWidgetNs * 1.0e-6F;

        long nowMs = System.currentTimeMillis();
        if (nowMs - lastMemPollMs >= 250L) {
            lastMemPollMs = nowMs;
            Runtime rt = Runtime.getRuntime();
            long max = rt.maxMemory();
            long used = rt.totalMemory() - rt.freeMemory();
            usedHeapMb = (int) (used >> 20);
            maxHeapMb = (int) (max >> 20);
        }
    }

    public static float getEmaFrameMs() { return emaFrameMs; }
    public static float getPeakFrameMs() { return peakFrameMs; }
    public static int getSmoothedFps() { return emaFrameMs > 0.1F ? Math.round(1000.0F / emaFrameMs) : 0; }
    public static float getEmaTickMs() { return emaTickMs; }
    public static float getEmaHudMs() { return emaHudMs; }
    public static String getSlowestWidgetName() { return lastSlowestWidget; }
    public static float getSlowestWidgetMs() { return lastSlowestWidgetMs; }
    public static int getUsedHeapMb() { return usedHeapMb; }
    public static int getMaxHeapMb() { return maxHeapMb; }
    public static int getRingSize() { return RING_SIZE; }
    public static int getRingCount() { return ringCount; }

    public static float getRingSampleChronological(int offsetFromOldest) {
        if (ringCount <= 0) return 0.0F;
        int start = (ringIndex - ringCount + RING_SIZE) % RING_SIZE;
        int idx = (start + Math.max(0, Math.min(offsetFromOldest, ringCount - 1))) % RING_SIZE;
        return FRAME_MS_RING[idx];
    }
}
