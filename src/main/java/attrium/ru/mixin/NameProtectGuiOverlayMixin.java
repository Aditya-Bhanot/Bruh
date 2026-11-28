package attrium.ru.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import attrium.ru.api.module.impl.misc.NameProtectModule;

/**
 * Masks local player IGN and friends in actionbar overlay messages, titles, and subtitles.
 */
@Mixin(Gui.class)
public class NameProtectGuiOverlayMixin {

    @ModifyVariable(method = "setOverlayMessage", at = @At("HEAD"), ordinal = 0, argsOnly = true, require = 0)
    private Component attrium$protectOverlayMessage(Component message) {
        return NameProtectModule.isActive() ? NameProtectModule.protectComponent(message) : message;
    }

    @ModifyVariable(method = "setTitle", at = @At("HEAD"), ordinal = 0, argsOnly = true, require = 0)
    private Component attrium$protectTitle(Component title) {
        return NameProtectModule.isActive() ? NameProtectModule.protectComponent(title) : title;
    }

    @ModifyVariable(method = "setSubtitle", at = @At("HEAD"), ordinal = 0, argsOnly = true, require = 0)
    private Component attrium$protectSubtitle(Component subtitle) {
        return NameProtectModule.isActive() ? NameProtectModule.protectComponent(subtitle) : subtitle;
    }
}
