package releon.ru.mixin;

import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import releon.ru.api.module.impl.visual.ESP;

@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"), require = 0)
    private void releon$hideEspAvatarLabels(Avatar entity, AvatarRenderState state, float tickDelta, CallbackInfo ci) {
        if (ESP.shouldHideVanillaName(entity)) {
            ESP.captureServerHealth(entity, state.scoreText);
            state.nameTag = null;
            state.scoreText = null;
        }
    }
}
