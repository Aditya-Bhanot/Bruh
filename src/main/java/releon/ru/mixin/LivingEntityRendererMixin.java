package releon.ru.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import releon.ru.api.module.impl.combat.aura.AngleConnection;
import releon.ru.api.module.impl.visual.ESP;
import releon.ru.api.module.impl.visual.SeeInvisible;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<S extends LivingEntityRenderState, M extends EntityModel<? super S>> {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V", at = @At("TAIL"), require = 0)
    private void updateRenderStateHook(LivingEntity entity, S state, float tickDelta, CallbackInfo ci) {
        if (SeeInvisible.shouldRenderInvisible() && entity.isInvisible()) {
            state.isInvisible = false;
            state.isInvisibleToPlayer = false;
        }
        if (ESP.shouldHideVanillaName(entity)) {
            state.nameTag = null;
            if (state instanceof AvatarRenderState avatarState) {
                avatarState.scoreText = null;
            }
        }

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || entity != client.player || client.screen instanceof AbstractContainerScreen) {
            return;
        }

        AngleConnection controller = AngleConnection.INSTANCE;
        if (controller.getFakeAngle() != null) {
            float prevHeadYaw = controller.getPreviousFakeRotation().getYaw();
            float currHeadYaw = controller.getFakeRotation().getYaw();
            float prevPitch = controller.getPreviousFakeRotation().getPitch();
            float currPitch = controller.getFakeRotation().getPitch();
            float prevBodyYaw = controller.getPreviousFakeBodyYaw();
            float currBodyYaw = controller.getFakeBodyYaw();
            float headYaw = Mth.rotLerp(tickDelta, prevHeadYaw, currHeadYaw);
            float pitch = Mth.clamp(Mth.lerp(tickDelta, prevPitch, currPitch), -90.0F, 90.0F);
            float bodyYaw = Mth.rotLerp(tickDelta, prevBodyYaw, currBodyYaw);

            float maxHeadRotation = 52.0F;
            float headBodyDiff = Mth.wrapDegrees(headYaw - bodyYaw);
            if (Math.abs(headBodyDiff) > maxHeadRotation) {
                bodyYaw = headYaw - (float) Mth.sign(headBodyDiff) * maxHeadRotation;
            }

            state.bodyRot = bodyYaw;
            state.yRot = Mth.clamp(Mth.wrapDegrees(headYaw - bodyYaw), -maxHeadRotation, maxHeadRotation);
            state.xRot = pitch;
            return;
        }

        if (controller.getCurrentAngle() != null) {
            float headYaw = Mth.rotLerp(tickDelta, controller.getPreviousRotation().getYaw(), controller.getRotation().getYaw());
            float pitch = Mth.lerp(tickDelta, controller.getPreviousRotation().getPitch(), controller.getRotation().getPitch());
            float maxHeadRotation = 52.0F;
            state.yRot = Mth.clamp(Mth.wrapDegrees(headYaw - state.bodyRot), -maxHeadRotation, maxHeadRotation);
            state.xRot = Mth.clamp(pitch, -90.0F, 90.0F);
        }
    }
}
