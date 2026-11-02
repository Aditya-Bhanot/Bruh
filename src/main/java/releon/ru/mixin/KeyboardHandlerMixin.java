package releon.ru.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import releon.ru.manager.Manager;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void releon$toggleClickGui(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (event.key() != GLFW.GLFW_KEY_UNKNOWN && window == this.minecraft.getWindow().handle() && this.minecraft.screen == null) {
            Manager.postEvent(new releon.ru.api.events.impl.KeyEvent(this.minecraft.screen, InputConstants.Type.KEYSYM, event.key(), action));
        }
        if (action == GLFW.GLFW_PRESS && event.key() == GLFW.GLFW_KEY_RIGHT_SHIFT) {
            Manager.toggleClickGui(this.minecraft);
            ci.cancel();
            return;
        }
        if (action == GLFW.GLFW_PRESS && event.key() == GLFW.GLFW_KEY_RIGHT_CONTROL) {
            Manager.toggleClickGui(this.minecraft);
            ci.cancel();
        }
    }
}
