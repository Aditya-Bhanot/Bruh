package releon.ru.utils.string.chat;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;

public final class ClientMessageDispatchTracker {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final Object LOCK = new Object();
    private static int globalDepth;
    private static Level previousRootLevel;

    private ClientMessageDispatchTracker() {
    }

    public static void begin() {
        synchronized (LOCK) {
            if (globalDepth++ == 0) {
                try {
                    LoggerContext context = LoggerContext.getContext(false);
                    if (context != null && context.getConfiguration() != null && context.getConfiguration().getRootLogger() != null) {
                        previousRootLevel = context.getConfiguration().getRootLogger().getLevel();
                    }
                    Configurator.setRootLevel(Level.OFF);
                } catch (Throwable ignored) {
                }
            }
        }
        DEPTH.set(DEPTH.get() + 1);
    }

    public static void end() {
        int depth = DEPTH.get() - 1;
        if (depth <= 0) {
            DEPTH.remove();
        } else {
            DEPTH.set(depth);
        }
        synchronized (LOCK) {
            if (globalDepth > 0 && --globalDepth == 0) {
                try {
                    Configurator.setRootLevel(previousRootLevel != null ? previousRootLevel : Level.INFO);
                } catch (Throwable ignored) {
                } finally {
                    previousRootLevel = null;
                }
            }
        }
    }
}
