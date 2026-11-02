package releon.ru.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import releon.ru.api.module.impl.visual.Hud;
import releon.ru.api.module.impl.visual.NoRender;

@Mixin(Gui.class)
public abstract class GuiNoRenderMixin {
    @Inject(method = "renderConfusionOverlay", at = @At("HEAD"), cancellable = true, require = 0)
    private void releon$confusion(GuiGraphics graphics, float nauseaStrength, CallbackInfo ci) {
        if (NoRender.isActive("Nausea")) {
            ci.cancel();
        }
    }

    @Inject(method = "renderScoreboardSidebar", at = @At("HEAD"), cancellable = true, require = 0)
    private void releon$scoreboard(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (NoRender.isActive("Scoreboard")) {
            ci.cancel();
        }
    }

    @Inject(method = "renderBossOverlay", at = @At("HEAD"), cancellable = true, require = 0)
    private void releon$bossOverlay(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (NoRender.isActive("Bossbar")) {
            ci.cancel();
        }
    }

    @Inject(method = "renderItemHotbar", at = @At("HEAD"), cancellable = true)
    private void releon$hotbar(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!Hud.shouldRenderCustomHotbar()) {
            return;
        }
        ci.cancel();
    }
}

