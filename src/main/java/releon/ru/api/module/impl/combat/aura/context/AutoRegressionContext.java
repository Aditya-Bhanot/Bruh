package releon.ru.api.module.impl.combat.aura.context;

import releon.ru.api.module.impl.combat.aura.util.StopWatch;

public final class AutoRegressionContext {
    private static final AutoRegressionContext INSTANCE = new AutoRegressionContext();

    private final StopWatch attackWatch = new StopWatch();
    private long cdMinecraftMs = 1000L / 12L;
    private int queuedHits;

    private AutoRegressionContext() {
    }

    public static AutoRegressionContext getInstance() {
        return INSTANCE;
    }

    public synchronized void setCdMinecraft(long cdMinecraftMs) {
        this.cdMinecraftMs = Math.max(1L, cdMinecraftMs);
    }

    public synchronized void updateLastAttackTime() {
        attackWatch.reset();
    }

    public synchronized void hitContentQueue() {
        if (queuedHits < 256) {
            queuedHits++;
        }
    }

    public synchronized void hitContentClear() {
        queuedHits = 0;
        attackWatch.reset();
    }

    public synchronized boolean isMinecraftCooldownPassed() {
        return attackWatch.finished(cdMinecraftMs);
    }

    public synchronized int getQueuedHits() {
        return queuedHits;
    }
}
