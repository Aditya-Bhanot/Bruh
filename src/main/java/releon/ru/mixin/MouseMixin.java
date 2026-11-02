package releon.ru.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import releon.ru.api.drag.core.ElementManager;
import releon.ru.api.events.impl.HotBarScrollEvent;
import releon.ru.api.events.impl.KeyEvent;
import releon.ru.api.events.impl.MouseRotationEvent;
import releon.ru.manager.Manager;
import releon.ru.screens.clickgui.ClickGui;
import releon.ru.screens.clickgui.impl.ClickGuiController;
import releon.ru.utils.render.ui.Render2DCoordinateSpace;

@Mixin(MouseHandler.class)
public class MouseMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "onButton", at = @At("HEAD"))
    private void releon$onMouseButton(long window, MouseButtonInfo buttonInfo, int action, CallbackInfo ci) {
        int button = buttonInfo.button();
        if (button != GLFW.GLFW_KEY_UNKNOWN && window == minecraft.getWindow().handle()) {
            if (minecraft.screen == null) {
                Manager.postEvent(new KeyEvent(minecraft.screen, InputConstants.Type.MOUSE, button, action));
            }
            if (action == GLFW.GLFW_RELEASE && minecraft.screen instanceof ChatScreen) {
                MouseButtonEvent event = new MouseButtonEvent(
                        minecraft.mouseHandler.getScaledXPos(minecraft.getWindow()),
                        minecraft.mouseHandler.getScaledYPos(minecraft.getWindow()),
                        buttonInfo
                );
                ElementManager.getInstance().handleMouseReleased(releon$toDragEvent(event));
            }
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void releon$onMouseScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (window == minecraft.getWindow().handle() && minecraft.screen instanceof ClickGui) {
            double mouseX = minecraft.mouseHandler.getScaledXPos(minecraft.getWindow());
            double mouseY = minecraft.mouseHandler.getScaledYPos(minecraft.getWindow());
            ClickGuiController.mouseScrolled(mouseX, mouseY, horizontal, vertical);
            ci.cancel();
            return;
        }
        if (window == minecraft.getWindow().handle() && minecraft.screen != null) {
            return;
        }

        HotBarScrollEvent event = Manager.postEvent(new HotBarScrollEvent(horizontal, vertical));
        if (event.isCancelled()) {
            ci.cancel();
        }
    }

    @WrapOperation(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"), require = 0)
    private void releon$modifyMouseRotationInput(LocalPlayer player, double cursorDeltaX, double cursorDeltaY, Operation<Void> original) {
        MouseRotationEvent event = Manager.postEvent(new MouseRotationEvent((float) cursorDeltaX, (float) cursorDeltaY));
        if (event.isCancelled()) {
            return;
        }
        original.call(player, (double) event.getCursorDeltaX(), (double) event.getCursorDeltaY());
    }

    @Unique
    private static MouseButtonEvent releon$toDragEvent(MouseButtonEvent event) {
        float scale = Render2DCoordinateSpace.guiIndependentScale();
        if (Math.abs(scale - 1.0F) <= 0.0001F) {
            return event;
        }

        MouseButtonInfo info = new MouseButtonInfo(event.button(), event.modifiers());
        return new MouseButtonEvent(event.x() / scale, event.y() / scale, info);
    }
}
