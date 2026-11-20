package attrium.ru.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import attrium.ru.api.module.impl.misc.NameProtectModule;
import attrium.ru.manager.FriendManager;

/**
 * Chat NameProtect & Friend Aliasing Mixin (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * Intercepts both ChatComponent#addMessage overloads (system messages and signed player chat)
 * and masks the local player's IGN + FriendManager allies while preserving original Style
 * colors, hover events, and click events via NameProtectModule's bounded LRU cache.
 */
@Mixin(ChatComponent.class)
public class FriendChatAliasMixin {

    @ModifyVariable(
            method = "addMessage(Lnet/minecraft/network/chat/Component;)V",
            at = @At("HEAD"),
            argsOnly = true,
            require = 0
    )
    private Component attrium$protectSimpleChatMessage(Component message) {
        if (NameProtectModule.isActive()) {
            return NameProtectModule.protectComponent(message);
        }
        return FriendManager.applyConditionalFriendAlias(message);
    }

    @ModifyVariable(
            method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            at = @At("HEAD"),
            argsOnly = true,
            require = 0
    )
    private Component attrium$protectSignedChatMessage(Component message) {
        if (NameProtectModule.isActive()) {
            return NameProtectModule.protectComponent(message);
        }
        return FriendManager.applyConditionalFriendAlias(message);
    }
}
