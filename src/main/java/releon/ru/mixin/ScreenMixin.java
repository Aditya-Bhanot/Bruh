package releon.ru.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import releon.ru.api.command.CommandManager;
import releon.ru.utils.string.chat.ChatMessage;

@Mixin(Screen.class)
public class ScreenMixin {
    @Inject(method = "defaultHandleClickEvent", at = @At("HEAD"), cancellable = true, require = 0)
    private static void releon$handleClientClickEvent(ClickEvent clickEvent, Minecraft client, Screen screen, CallbackInfo ci) {
        if (clickEvent instanceof ClickEvent.CopyToClipboard copyToClipboard) {
            String value = copyToClipboard.value();
            if (client != null && client.keyboardHandler != null && value != null && !value.isEmpty()) {
                client.keyboardHandler.setClipboard(value);
                ChatMessage.brandmessage("Copied to clipboard.");
                ci.cancel();
                return;
            }
        }

        if (clickEvent instanceof ClickEvent.RunCommand runCommand) {
            String command = runCommand.command();
            CommandManager manager = CommandManager.getInstance();
            if (manager != null && command != null && command.startsWith(manager.getPrefix())) {
                manager.execute(command.substring(manager.getPrefix().length()));
                ci.cancel();
            }
        }
    }
}
