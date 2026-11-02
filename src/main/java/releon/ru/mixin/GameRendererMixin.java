package releon.ru.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import releon.ru.api.module.impl.player.NoEntityTrace;
import releon.ru.api.module.impl.visual.Ambience;
import releon.ru.api.module.impl.visual.AspectRatio;
import releon.ru.api.module.impl.visual.KillEffect;
import releon.ru.api.module.impl.visual.NoRender;
import releon.ru.screens.clickgui.ClickGui;
import releon.ru.screens.modernui.impl.WorldAnimation;
import releon.ru.utils.render.HudRenderLayer;
import releon.ru.utils.render.post.saturation.Saturation2D;

import static java.lang.Math.round;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private GuiRenderState guiRenderState;

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V", shift = At.Shift.BEFORE))
    private void releon$renderGuiOverlay(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        releon$applyWorldSaturation();

        if (!HudRenderLayer.shouldRender(minecraft)) {
            return;
        }

        Screen screen = minecraft.screen;
        if (screen != null && !(screen instanceof ClickGui) && !(screen instanceof ChatScreen)) {
            return;
        }
        if (screen instanceof ChatScreen) {
            HudRenderLayer.renderChatOverlay(minecraft, releon$newGuiGraphics(), deltaTracker.getGameTimeDeltaPartialTick(false));
            return;
        }

        HudRenderLayer.renderGameHud(minecraft, releon$newGuiGraphics(), deltaTracker.getGameTimeDeltaPartialTick(false));

        if (!ClickGui.shouldRenderOverlay()) {
            return;
        }

        GuiGraphics graphics = releon$newGuiGraphics();
        ClickGui.renderPanels(graphics, minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight());
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;renderWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", shift = At.Shift.BEFORE), require = 0)
    private void releon$renderHudBehindScreen(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        if (!HudRenderLayer.shouldRender(minecraft)) {
            return;
        }

        Screen screen = minecraft.screen;
        if (screen == null || screen instanceof ChatScreen || screen instanceof ClickGui) {
            return;
        }

        HudRenderLayer.renderScreenBackground(minecraft, releon$newGuiGraphics(), deltaTracker.getGameTimeDeltaPartialTick(false));
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V", shift = At.Shift.BEFORE), require = 0)
    private void releon$renderDrawLayerBelowVanillaHud(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        if (!HudRenderLayer.shouldRender(minecraft)) {
            return;
        }

        HudRenderLayer.renderGameDrawEvents(releon$newGuiGraphics(), deltaTracker.getGameTimeDeltaPartialTick(false));
    }

    private GuiGraphics releon$newGuiGraphics() {
        int mouseX = minecraft.mouseHandler == null || minecraft.getWindow() == null
                ? 0
                : (int) round(minecraft.mouseHandler.getScaledXPos(minecraft.getWindow()));
        int mouseY = minecraft.mouseHandler == null || minecraft.getWindow() == null
                ? 0
                : (int) round(minecraft.mouseHandler.getScaledYPos(minecraft.getWindow()));
        return new GuiGraphics(minecraft, guiRenderState, mouseX, mouseY);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"))
    private void releon$renderModernWorldAnimation(GuiRenderer renderer, GpuBufferSlice fogBuffer, Operation<Void> original, DeltaTracker deltaTracker, boolean tick) {
        original.call(renderer, fogBuffer);
        if (minecraft.screen == null && WorldAnimation.isActive()) {
            WorldAnimation.render(renderer, releon$newGuiGraphics(), fogBuffer, deltaTracker.getGameTimeDeltaPartialTick(false));
        }
    }

    private void releon$applyWorldSaturation() {
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            return;
        }

        float saturation = KillEffect.getWorldSaturationMultiplier();
        Ambience ambience = Ambience.getInstance();
        if (ambience != null && ambience.isEnabled()) {
            saturation *= ambience.getSaturationFactor();
        }
        if (!Float.isFinite(saturation) || Math.abs(saturation - 1.0F) <= 0.0005F) {
            return;
        }

        Saturation2D.applyWithCopy(Math.clamp(saturation, 0.0F, 2.0F));
    }

    @WrapOperation(method = "pick(F)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;raycastHitResult(FLnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/phys/HitResult;"), require = 0)
    private HitResult releon$noEntityTracePick(LocalPlayer player, float tickProgress, Entity cameraEntity, Operation<HitResult> original) {
        NoEntityTrace noEntityTrace = NoEntityTrace.getInstance();
        if (noEntityTrace != null && noEntityTrace.shouldIgnoreEntityTrace() && cameraEntity != null && minecraft.player != null) {
            double range = Math.max(player.blockInteractionRange(), player.entityInteractionRange());
            return cameraEntity.pick(range, tickProgress, false);
        }
        return original.call(player, tickProgress, cameraEntity);
    }

    @Inject(method = "getProjectionMatrix", at = @At("TAIL"), cancellable = true)
    private void releon$aspectRatio(float fovDegrees, CallbackInfoReturnable<Matrix4f> cir) {
        if (minecraft == null || minecraft.getWindow() == null) {
            return;
        }

        int width = minecraft.getWindow().getWidth();
        int height = minecraft.getWindow().getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        float adjustedWidth = AspectRatio.resolveRatio(width, height);
        if (Math.abs(adjustedWidth - width) <= 0.0001f) {
            return;
        }

        float currentAspect = (float) width / (float) height;
        float targetAspect = adjustedWidth / (float) height;
        if (targetAspect <= 0.0001f) {
            return;
        }

        Matrix4f matrix = new Matrix4f(cir.getReturnValue());
        matrix.m00(matrix.m00() * (currentAspect / targetAspect));
        cir.setReturnValue(matrix);
    }

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true, require = 0)
    private void releon$noHurtBob(com.mojang.blaze3d.vertex.PoseStack stack, float tickDelta, CallbackInfo ci) {
        if (NoRender.isActive("Damage")) {
            ci.cancel();
        }
    }

    @Inject(method = "displayItemActivation", at = @At("HEAD"), cancellable = true, require = 0)
    private void releon$noItemActivation(ItemStack stack, CallbackInfo ci) {
        if (NoRender.isActive("Totem Animation")) {
            ci.cancel();
        }
    }

}
