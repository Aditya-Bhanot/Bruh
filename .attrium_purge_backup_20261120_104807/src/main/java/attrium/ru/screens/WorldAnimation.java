package attrium.ru.screens;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Disabled legacy 3D WorldAnimation stub (zero overhead).
 */
public final class WorldAnimation {
    private WorldAnimation() {
    }

    public static void tick() {
    }

    public static boolean isActive() {
        return false;
    }

    public static GpuBufferSlice projectionOverride() {
        return null;
    }

    public static void render(Object renderer, GuiGraphics graphics, Object fogBuffer, float partialTick) {
    }
}
