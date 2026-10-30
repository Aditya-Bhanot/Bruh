package releon.ru.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import releon.ru.api.events.impl.HandledScreenEvent;
import releon.ru.manager.Manager;

@Mixin(AbstractContainerScreen.class)
public abstract class HandledScreenMixin {
    @Shadow
    @Final
    protected int imageWidth;

    @Shadow
    @Final
    protected int imageHeight;

    @Shadow
    @Nullable
    protected Slot hoveredSlot;

    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void releon$handledScreenRender(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        Manager.postEvent(new HandledScreenEvent(graphics, hoveredSlot, imageWidth, imageHeight));
    }
}
