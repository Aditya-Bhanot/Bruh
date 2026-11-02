package releon.ru.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import releon.ru.IMinecraft;
import releon.ru.api.events.impl.WorldRenderEvent;
import releon.ru.api.module.impl.visual.Ambience;
import releon.ru.api.module.impl.visual.FogBlur;
import releon.ru.api.module.impl.visual.KillEffect;
import releon.ru.api.module.impl.visual.NoRender;
import releon.ru.manager.Manager;
import releon.ru.utils.render.Render3D;
import releon.ru.utils.render.RenderCompatibility;
import releon.ru.utils.render.ui.blur.BlurFramebuffer;
import releon.ru.utils.render.post.fogblur.FogBlurRenderer;
import releon.ru.utils.render.world.starsparkle.StarSparkle3D;
import releon.ru.utils.render.world.watercaustic.WaterCaustic3D;

@Mixin(LevelRenderer.class)
public class WorldRendererMixin implements IMinecraft {

    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private RenderBuffers renderBuffers;
    @Shadow @Final private SkyRenderer skyRenderer;
    @Shadow private ClientLevel level;

    @Inject(method = "addSkyPass", at = @At("HEAD"), cancellable = true)
    private void onRenderSky(FrameGraphBuilder frameGraphBuilder, Camera camera, GpuBufferSlice fog, CallbackInfo ci) {
        NoRender noRender = NoRender.getInstance();
        if (noRender == null || !noRender.shouldHideSkyBackground() || level == null) {
            return;
        }

        ci.cancel();
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void onRenderHead(GraphicsResourceAllocator allocator, DeltaTracker tickCounter, boolean renderBlockOutline, Camera camera, Matrix4f positionMatrix, Matrix4f projectionMatrix, Matrix4f frustumMatrix, GpuBufferSlice fog, Vector4f fogColor, boolean renderSky, CallbackInfo ci) {
        PoseStack captureStack = new PoseStack();
        captureStack.mulPose(new Matrix4f(positionMatrix));
        Render3D.lastProjMat.set(projectionMatrix);
        Render3D.lastModMat.set(positionMatrix);
        Render3D.lastWorldSpaceMatrix.set(captureStack.last().pose());
        Render3D.setLastWorldSpaceEntry(captureStack.last());
        Render3D.setLastTickDelta(tickCounter.getGameTimeDeltaPartialTick(true));
        Render3D.setLastCameraPos(camera.position());
        Render3D.setLastCameraRotation(new Quaternionf(camera.rotation()));
        WaterCaustic3D.setMatrices(projectionMatrix, positionMatrix);
        StarSparkle3D.setMatrices(projectionMatrix, positionMatrix);

        if (fogColor == null) {
            return;
        }

        Ambience ambience = Ambience.getInstance();
        if (ambience != null && ambience.isEnabled() && ambience.hasCustomFog()) {
            int customColor = ambience.getCustomFogColor();
            fogColor.set(
                    ((customColor >> 16) & 0xFF) / 255.0f,
                    ((customColor >> 8) & 0xFF) / 255.0f,
                    (customColor & 0xFF) / 255.0f,
                    fogColor.w
            );
        }

        float saturation = KillEffect.getWorldSaturationMultiplier();
        if (ambience != null && ambience.isEnabled()) {
            saturation *= ambience.getSaturationFactor();
        }
        if (Float.isFinite(saturation) && Math.abs(saturation - 1.0F) > 0.0005F) {
            saturation = Math.clamp(saturation, 0.0F, 2.0F);
            float luminance = fogColor.x * 0.2126F + fogColor.y * 0.7152F + fogColor.z * 0.0722F;
            fogColor.set(
                    luminance + (fogColor.x - luminance) * saturation,
                    luminance + (fogColor.y - luminance) * saturation,
                    luminance + (fogColor.z - luminance) * saturation,
                    fogColor.w
            );
        }

        BlurFramebuffer.setSkyFallbackColor(fogColor.x, fogColor.y, fogColor.z);
        FogBlurRenderer.setFallbackColor(fogColor.x, fogColor.y, fogColor.z);
    }

    @Inject(method = "doesMobEffectBlockSky", at = @At("HEAD"), cancellable = true)
    private void onHasBlindnessOrDarkness(Camera camera, CallbackInfoReturnable<Boolean> cir) {
        NoRender noRender = NoRender.getInstance();
        if (noRender == null || !noRender.isEnabled()) return;

        Entity entity = camera.entity();
        if (!(entity instanceof LivingEntity livingEntity)) return;

        boolean hasBlindness = livingEntity.hasEffect(MobEffects.BLINDNESS);
        boolean hasDarkness = livingEntity.hasEffect(MobEffects.DARKNESS);

        if (NoRender.isActive("Bad Effects") && hasBlindness && !hasDarkness) {
            cir.setReturnValue(false);
        }

        if (NoRender.isActive("Darkness") && hasDarkness && !hasBlindness) {
            cir.setReturnValue(false);
        }

        if (NoRender.isActive("Bad Effects") && NoRender.isActive("Darkness")) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void onRenderWorld(GraphicsResourceAllocator allocator, DeltaTracker tickCounter, boolean renderBlockOutline, Camera camera, Matrix4f positionMatrix, Matrix4f projectionMatrix, Matrix4f frustumMatrix, GpuBufferSlice fog, Vector4f fogColor, boolean renderSky, CallbackInfo ci) {
        if (mc.level == null || mc.player == null) {
            return;
        }
        RenderCompatibility.primeFromCurrentContext();

        PoseStack stack = new PoseStack();
        stack.mulPose(new Matrix4f(positionMatrix));

        boolean prevDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean prevBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean prevCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean prevScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean prevDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int prevDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        int prevBlendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int prevBlendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int prevBlendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int prevBlendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int[] prevScissorBox = new int[4];
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, prevScissorBox);

        try {
            WorldRenderEvent event = new WorldRenderEvent(stack, tickCounter.getGameTimeDeltaPartialTick(true));
            Manager.postEvent(event);
            Render3D.onWorldRender(event);
        } finally {
            restoreRenderState(
                    prevDepthTest,
                    prevBlend,
                    prevCull,
                    prevScissor,
                    prevDepthMask,
                    prevDepthFunc,
                    prevBlendSrcRgb,
                    prevBlendDstRgb,
                    prevBlendSrcAlpha,
                    prevBlendDstAlpha,
                    prevScissorBox
            );
        }
    }

    @Inject(
            method = "method_62214",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V",
                    ordinal = 1,
                    shift = At.Shift.AFTER
            ),
            require = 0
    )
    private void releon$fragEffectAfterTranslucent(GpuBufferSlice fog, LevelRenderState levelRenderState, ProfilerFiller profiler, Matrix4f positionMatrix,
                                                  ResourceHandle<RenderTarget> mainTarget, ResourceHandle<RenderTarget> translucentTarget, boolean renderBlockOutline,
                                                  ResourceHandle<RenderTarget> itemEntityTarget, ResourceHandle<RenderTarget> entityOutlineTarget, CallbackInfo ci) {
        if (mc.level == null || mc.player == null) {
            return;
        }

        RenderTarget target = mainTarget != null ? mainTarget.get() : minecraft.getMainRenderTarget();
        FogBlur fogBlur = FogBlur.getInstance();
        if (fogBlur != null && fogBlur.isEnabled()) {
            fogBlur.onAfterTranslucent(target);
        }

        KillEffect killEffect = KillEffect.getInstanceIfReady();
        if (killEffect != null && killEffect.isEnabled()) {
            killEffect.onAfterTranslucent(target, Render3D.lastProjMat, positionMatrix, Render3D.lastCameraPos);
        }
    }

    private void restoreRenderState(boolean depthTest, boolean blend, boolean cull, boolean scissor, boolean depthMask, int depthFunc, int blendSrcRgb, int blendDstRgb, int blendSrcAlpha, int blendDstAlpha, int[] scissorBox) {
        if (depthTest) {
            GlStateManager._enableDepthTest();
        } else {
            GlStateManager._disableDepthTest();
        }
        GlStateManager._depthMask(depthMask);
        GlStateManager._depthFunc(depthFunc);
        GlStateManager._colorMask(true, true, true, true);

        if (blend) {
            GlStateManager._enableBlend();
            GlStateManager._blendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
        } else {
            GlStateManager._disableBlend();
        }

        if (cull) {
            GlStateManager._enableCull();
        } else {
            GlStateManager._disableCull();
        }

        if (scissor) {
            GlStateManager._enableScissorTest();
            GlStateManager._scissorBox(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        } else {
            GlStateManager._disableScissorTest();
        }
    }
}
