package releon.ru.api.module.impl.visual;

import net.minecraft.client.Minecraft;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ColorSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.hands.HandsFlameRenderer;

import java.awt.Color;

public class Hands extends Module {
    private static final int CLIENT_COLOR_FIRST = ColorUtil.rgba(127, 242, 255, 230);
    private static final int CLIENT_COLOR_SECOND = ColorUtil.rgba(255, 50, 150, 230);

    private final BooleanSetting onlyItems = new BooleanSetting("Only Items", "Draw hands flame only when an item is held.", false);
    private final ModeSetting colorMode = register(new ModeSetting("Color Mode", "Hands flame color mode.", "Item", "Item", "Custom", "Client", "Rainbow"));
    private final ColorSetting color = register(new ColorSetting("Color", "Custom hands flame color.", new Color(255, 255, 255, 230)));
    private final NumberSetting strength = register(new NumberSetting("Strength", "Hands flame intensity.", 0.85, 0.0, 2.0, 0.05));
    private final NumberSetting riseSpeed = register(new NumberSetting("Rise Speed", "Hands flame upward speed.", 0.0, 0.0, 2.0, 0.05));
    private final NumberSetting wobble = register(new NumberSetting("Wobble", "Hands flame side turbulence.", 0.65, 0.0, 2.0, 0.05));
    private final NumberSetting length = register(new NumberSetting("Length", "Hands flame trail length.", 0.95, 0.1, 2.5, 0.05));
    private final NumberSetting brightness = register(new NumberSetting("Brightness", "Hands flame brightness.", 0.9, 0.0, 2.0, 0.05));

    public Hands() {
        super("Hands", "Draws a shader flame effect around first-person hands.", ModuleCategory.VISUAL);
        color.visibleWhen(() -> colorMode.is("Custom"));
    }

    @Override
    protected void onEnable() {
        syncRenderer();
        HandsFlameRenderer.setFlameEnabled(true);
    }

    @Override
    protected void onDisable() {
        HandsFlameRenderer.setFlameEnabled(false);
    }

    @Override
    public void onTick(Minecraft client) {
        syncRenderer();
    }

    private void syncRenderer() {
        int colorModeId = colorMode.is("Item") ? 0 : 1;
        HandsFlameRenderer.configure(
                strength.getFloat(),
                riseSpeed.getFloat(),
                wobble.getFloat(),
                length.getFloat(),
                brightness.getFloat(),
                colorModeId,
                resolveColor(),
                onlyItems.getValue()
        );
    }

    private int resolveColor() {
        if (colorMode.is("Custom")) {
            return color.getValue().getRGB();
        }
        if (colorMode.is("Client")) {
            float pulse = (float) ((Math.sin(System.currentTimeMillis() / 520.0) + 1.0) * 0.5);
            return ColorUtil.lerpColor(CLIENT_COLOR_FIRST, CLIENT_COLOR_SECOND, pulse);
        }
        if (colorMode.is("Rainbow")) {
            float hue = (System.currentTimeMillis() % 4000L) / 4000.0f;
            return 0xE6000000 | (Color.HSBtoRGB(hue, 0.72f, 1.0f) & 0x00FFFFFF);
        }
        return color.getValue().getRGB();
    }
}
