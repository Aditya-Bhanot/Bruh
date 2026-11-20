package attrium.ru.mixin;

import net.minecraft.client.gui.screens.DeathScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import attrium.ru.api.events.impl.DeathScreenEvent;
import attrium.ru.manager.Manager;

@Mixin(DeathScreen.class)
public abstract class DeathScreenMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void attrium$deathScreenTick(CallbackInfo ci) {
        Manager.postEvent(new DeathScreenEvent());
    }
}
