package releon.ru.api.module.impl.visual;

import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.NumberSetting;

public class SeeInvisible extends Module {
    private static SeeInvisible instance;

    private final NumberSetting alpha = register(new NumberSetting("Alpha", "Invisible entity alpha.", 0.5, 0.1, 1.0, 0.05));

    public SeeInvisible() {
        super("See Invisible", "Allows invisible entities to be rendered.", ModuleCategory.VISUAL);
        instance = this;
    }

    public static boolean shouldRenderInvisible() {
        return instance != null && instance.isEnabled();
    }

    public static float getAlpha() {
        return instance == null ? 0.5F : instance.alpha.getFloat();
    }
}
