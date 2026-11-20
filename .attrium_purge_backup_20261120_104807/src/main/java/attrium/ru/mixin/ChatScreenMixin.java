package attrium.ru.mixin;

import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import attrium.ru.api.drag.core.ElementManager;
import attrium.ru.utils.render.ui.Render2DCoordinateSpace;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, require = 0)
    private void attrium$dragMouseClicked(MouseButtonEvent event, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
        return;
    }

    @Inject(method = "removed", at = @At("HEAD"), require = 0)
    private void attrium$cancelDragOnRemoved(CallbackInfo ci) {
        ElementManager.getInstance().cancelActiveElement();
    }

    @Inject(method = "onClose", at = @At("HEAD"), require = 0)
    private void attrium$cancelDragOnClose(CallbackInfo ci) {
        ElementManager.getInstance().cancelActiveElement();
    }

    @Unique
    private MouseButtonEvent attrium$toDragEvent(MouseButtonEvent event) {
        float scale = Render2DCoordinateSpace.guiIndependentScale();
        if (Math.abs(scale - 1.0F) <= 0.0001F) {
            return event;
        }

        MouseButtonInfo info = new MouseButtonInfo(event.button(), event.modifiers());
        return new MouseButtonEvent(event.x() / scale, event.y() / scale, info);
    }
}
