package attrium.ru.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import attrium.ru.manager.HudManager;

/**
 * Hooks the tail of Minecraft 1.21.11's Gui#render (Yarn: InGameHud#render)
 * to render Attrium's animated glassmorphic HUD suite cleanly on mobile & desktop GPUs.
 */
@Mixin(Gui.class)
public class InGameHudMixin {

    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void attrium$renderGlassHudSuite(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        HudManager.renderInGameHud(guiGraphics, deltaTracker);
    }
}
