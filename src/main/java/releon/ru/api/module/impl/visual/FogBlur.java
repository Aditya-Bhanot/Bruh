package releon.ru.api.module.impl.visual;

import com.mojang.blaze3d.pipeline.RenderTarget;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.render.post.fogblur.FogBlurRenderer;

public class FogBlur extends Module {
    private static FogBlur instance;

    private final NumberSetting strength = register(new NumberSetting("Strength", "Strength of the distance blur.", 15.0, 5.0, 20.0, 1.0));
    private final NumberSetting distance = new NumberSetting("Distance", "Distance in blocks where the fog blur becomes visible.", 250.0, 100.0, 250.0, 1.0);
    private final ModeSetting quality = register(new ModeSetting("Quality", "Render scale and pass limit.", "Balanced", "Balanced", "Quality"));

    public FogBlur() {
        super("Fog Blur", "Blurs distant world pixels using depth, like Schizoid fog blur.", ModuleCategory.VISUAL);
        instance = this;
    }

    public static FogBlur getInstance() {
        return instance;
    }

    @Override
    protected void onDisable() {
        FogBlurRenderer.clear();
    }

    public void onAfterTranslucent(RenderTarget renderTarget) {
        if (mc.player == null || mc.level == null || mc.gameRenderer == null) {
            return;
        }
        if (FogBlurRenderer.isDisabledAfterError()) {
            return;
        }
        FogBlurRenderer.apply(renderTarget, strength.getFloat(), distance.getFloat(), 100, renderScale(), maxPasses());
    }

    private float renderScale() {
        if (quality.is("Quality")) {
            return 0.68f;
        }
        return 0.52f;
    }

    private int maxPasses() {
        if (quality.is("Quality")) {
            return 3;
        }
        return 2;
    }
}
