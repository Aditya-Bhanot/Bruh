package attrium.ru.screens;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Lightweight static bridge routing ClickGui directly to GlassClickGuiScreen.
 */
public class ClickGui extends GlassClickGuiScreen {
    private static ClickGui instance;

    public ClickGui() {
        super();
        instance = this;
    }

    public static ClickGui getInstance() {
        if (instance == null) {
            instance = new ClickGui();
        }
        return instance;
    }

    public static void tickMovementKeys() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.getWindow() == null) {
            return;
        }
        if (!(mc.screen instanceof GlassClickGuiScreen gui) || gui.isSearchBarFocused()) {
            return;
        }
        KeyMapping[] moveKeys = new KeyMapping[]{
                mc.options.keyUp,
                mc.options.keyDown,
                mc.options.keyLeft,
                mc.options.keyRight,
                mc.options.keyJump,
                mc.options.keySprint,
                mc.options.keyShift
        };
        for (int i = 0; i < moveKeys.length; i++) {
            KeyMapping key = moveKeys[i];
            if (key != null && !key.isUnbound()) {
                int code = key.getDefaultKey().getValue();
                if (code >= 32) {
                    key.setDown(InputConstants.isKeyDown(mc.getWindow(), code));
                }
            }
        }
    }

    public static void warmupText() {
    }

    public static boolean shouldRenderOverlay() {
        return false;
    }

    public static void renderPanels(GuiGraphics graphics, int scaledWidth, int scaledHeight) {
    }
}
