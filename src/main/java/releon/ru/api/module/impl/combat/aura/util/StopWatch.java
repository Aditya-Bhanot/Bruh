package releon.ru.api.module.impl.combat.aura.util;

public final class StopWatch {
    private long last = System.currentTimeMillis();

    public void reset() {
        last = System.currentTimeMillis();
    }

    public long elapsedTime() {
        return System.currentTimeMillis() - last;
    }

    public boolean finished(double delayMs) {
        return elapsedTime() >= delayMs;
    }

    public boolean every(double delayMs) {
        if (!finished(delayMs)) {
            return false;
        }
        reset();
        return true;
    }
}
