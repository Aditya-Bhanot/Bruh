package releon.ru.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import releon.ru.utils.window.WindowStyle;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "resizeDisplay", at = @At("TAIL"))
    private void applyDarkMode(CallbackInfo ci) {
        applyWindowTitle();

        Minecraft client = Minecraft.getInstance();
        if (client != null && client.getWindow() != null) {
            WindowStyle.setDarkMode(client.getWindow().handle(), true);
        }
    }

    @Inject(method = "updateTitle", at = @At("TAIL"))
    private void applyCustomTitle(CallbackInfo ci) {
        applyWindowTitle();
    }

    private static void applyWindowTitle() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getWindow() == null) {
            return;
        }

        String user = client.getUser() == null ? "User" : client.getUser().getName();
        if (user == null || user.isBlank()) {
            user = "User";
        }
        client.getWindow().setTitle("Releon Client - (" + user + ")");
    }
}
