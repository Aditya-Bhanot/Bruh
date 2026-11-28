package attrium.ru.mixin;

import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import attrium.ru.api.module.impl.misc.NameProtectModule;

/**
 * Masks local player IGN (including @mentions, whispers, death/join/system messages)
 * and friends across all incoming chat messages when NameProtect is active.
 */
@Mixin(ChatComponent.class)
public class NameProtectChatMixin {

    @ModifyVariable(
            method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            at = @At("HEAD"),
            ordinal = 0,
            argsOnly = true,
            require = 0
    )
    private Component attrium$protectChatMessage(Component message) {
        if (!NameProtectModule.isActive() || message == null) {
            return message;
        }
        return NameProtectModule.protectComponent(message);
    }
}
