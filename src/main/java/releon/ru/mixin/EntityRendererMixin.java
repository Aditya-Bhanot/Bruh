package releon.ru.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import releon.ru.api.module.impl.visual.ESP;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<S extends EntityRenderState> {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V", at = @At("TAIL"), require = 0)
    private void releon$hideEspNameTags(Entity entity, S state, float tickDelta, CallbackInfo ci) {
        if (ESP.shouldHideVanillaName(entity)) {
            state.nameTag = null;
        }
    }

    @Inject(method = "shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z", at = @At("HEAD"), cancellable = true, require = 0)
    private void releon$hideEspServerTagEntities(Entity entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (ESP.shouldSuppressServerTagEntity(entity)) {
            cir.setReturnValue(false);
        }
    }
}
