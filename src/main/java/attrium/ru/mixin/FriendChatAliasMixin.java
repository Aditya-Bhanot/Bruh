package attrium.ru.mixin;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import attrium.ru.manager.FriendManager;

/**
 * Conditional Chat Friend Aliasing Mixin (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * Hooks `ChatComponent#addMessage` (Mojang mapping for `ChatHud#addMessage`) and replaces
 * friend usernames with their configured alias ONLY when `NameProtect` / `Hide Nametag` is enabled,
 * using `FriendManager`'s bounded string cache for zero per-frame GC allocations.
 */
@Mixin(ChatComponent.class)
public class FriendChatAliasMixin {

    @ModifyVariable(
            method = "addMessage(Lnet/minecraft/network/chat/Component;)V",
            at = @At("HEAD"),
            argsOnly = true,
            require = 0
    )
    private Component attrium$aliasFriendInChatMessage(Component message) {
        return FriendManager.applyConditionalFriendAlias(message);
    }
}
