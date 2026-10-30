package releon.ru.api.module.impl.visual;

import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.NumberSetting;

public class AspectRatio extends Module {
    private static AspectRatio instance;

    private final NumberSetting ratio = register(new NumberSetting("Ratio", "Screen aspect ratio.", 1.5, 1.0, 2.0, 0.01));

    public AspectRatio() {
        super("Aspect Ratio", "Changes the world projection aspect ratio.", ModuleCategory.VISUAL);
        instance = this;
    }

    public static float resolveRatio(float currentWidth, float currentHeight) {
        AspectRatio module = instance;
        if (module == null || !module.isEnabled() || currentHeight <= 0.0f) {
            return currentWidth;
        }
        return currentHeight * module.ratio.getFloat();
    }
}
