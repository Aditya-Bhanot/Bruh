package attrium.ru.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.VertexFormat;
import attrium.ru.screens.WorldAnimation;
import attrium.ru.utils.render.ui.blur.BlurFramebuffer;
import attrium.ru.utils.render.ui.glass.GlassRenderer;
import attrium.ru.utils.render.ui.image.ImageRenderer;
import attrium.ru.utils.render.ui.outline.outline360.Outline360Renderer;
import attrium.ru.utils.render.ui.outline.outlinedefault.DefaultOutlineRenderer;
import attrium.ru.utils.render.ui.outline.outlineglass.GlassOutlineRenderer;
import attrium.ru.utils.render.ui.arc.ArcOutlineRenderer;
import attrium.ru.utils.render.ui.arc.ArcRenderer;
import attrium.ru.utils.render.ui.rectangle.rectdefault.DefaultRectangleRenderer;
import attrium.ru.utils.render.ui.rectangle.recthalficon.HalfIconRectangleRenderer;
import attrium.ru.utils.render.ui.rectangle.recthalftone.HalftoneRectangleRenderer;
import attrium.ru.utils.render.ui.ripple.RippleRenderer;
import attrium.ru.utils.render.ui.zippy.ZippyRenderer;
import attrium.ru.utils.render.item.RenderItem;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
    private RenderPass attrium$currentRenderPass;
    private boolean attrium$blurDrawActive;
    private boolean attrium$glassDrawActive;
    private boolean attrium$glassOutlineDrawActive;
    private boolean attrium$rectangleDrawActive;
    private boolean attrium$halfIconRectangleDrawActive;
    private boolean attrium$halftoneRectangleDrawActive;
    private boolean attrium$zippyDrawActive;
    private boolean attrium$arcDrawActive;
    private boolean attrium$arcOutlineDrawActive;
    private boolean attrium$outlineDrawActive;
    private boolean attrium$outline360DrawActive;
    private boolean attrium$imageDrawActive;
    private boolean attrium$itemDrawActive;
    private boolean attrium$rippleDrawActive;

    @Redirect(method = "draw", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V", ordinal = 0))
    private void attrium$useModernWorldProjection(GpuBufferSlice projection, ProjectionType projectionType) {
        GpuBufferSlice override = WorldAnimation.projectionOverride();
        if (override != null) {
            RenderSystem.setProjectionMatrix(override, ProjectionType.PERSPECTIVE);
            return;
        }
        RenderSystem.setProjectionMatrix(projection, projectionType);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void attrium$beginBlurFrame(GpuBufferSlice fogBuffer, CallbackInfo ci) {
        BlurFramebuffer.getInstance().beginGuiFrame();
        GlassRenderer.getInstance().beginGuiFrame();
        GlassOutlineRenderer.getInstance().beginGuiFrame();
        DefaultRectangleRenderer.getInstance().beginGuiFrame();
        HalfIconRectangleRenderer.getInstance().beginGuiFrame();
        HalftoneRectangleRenderer.getInstance().beginGuiFrame();
        ZippyRenderer.getInstance().beginGuiFrame();
        ArcRenderer.getInstance().beginGuiFrame();
        ArcOutlineRenderer.getInstance().beginGuiFrame();
        DefaultOutlineRenderer.getInstance().beginGuiFrame();
        Outline360Renderer.getInstance().beginGuiFrame();
        ImageRenderer.getInstance().beginGuiFrame();
        RippleRenderer.getInstance().beginGuiFrame();
        RenderItem.beginGuiFrame();
    }

    @Inject(method = "prepare", at = @At("HEAD"))
    private void attrium$preparePendingBlurResources(CallbackInfo ci) {
        BlurFramebuffer.getInstance().preparePending();
    }

    @Inject(method = "prepare", at = @At("RETURN"))
    private void attrium$prepareRenderUniforms(CallbackInfo ci) {
        BlurFramebuffer.getInstance().prepareBuffers();
        GlassRenderer.getInstance().prepareBuffers();
        GlassOutlineRenderer.getInstance().prepareBuffers();
        DefaultRectangleRenderer.getInstance().prepareBuffers();
        HalfIconRectangleRenderer.getInstance().prepareBuffers();
        HalftoneRectangleRenderer.getInstance().prepareBuffers();
        ZippyRenderer.getInstance().prepareBuffers();
        ArcRenderer.getInstance().prepareBuffers();
        ArcOutlineRenderer.getInstance().prepareBuffers();
        DefaultOutlineRenderer.getInstance().prepareBuffers();
        Outline360Renderer.getInstance().prepareBuffers();
        ImageRenderer.getInstance().prepareBuffers();
        RippleRenderer.getInstance().prepareBuffers();
        RenderItem.prepareBuffers();
    }

    @Inject(method = "draw", at = @At("HEAD"))
    private void attrium$prepareBlurCapture(GpuBufferSlice fogBuffer, CallbackInfo ci) {
        BlurFramebuffer.getInstance().prepareGuiDraw();
    }

    @Inject(method = "draw", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;processBlurEffect()V", shift = At.Shift.BEFORE))
    private void attrium$prepareBlurCaptureAfterBeforeBlur(GpuBufferSlice fogBuffer, CallbackInfo ci) {
        BlurFramebuffer.getInstance().prepareGuiDraw();
    }

    @Redirect(method = "executeDraw", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
    private void attrium$trackPipeline(RenderPass renderPass, RenderPipeline pipeline) {
        attrium$currentRenderPass = renderPass;
        attrium$blurDrawActive = BlurFramebuffer.getInstance().isBlurPipeline(pipeline);
        attrium$glassDrawActive = GlassRenderer.getInstance().isGlassPipeline(pipeline);
        attrium$glassOutlineDrawActive = GlassOutlineRenderer.getInstance().isGlassOutlinePipeline(pipeline);
        attrium$rectangleDrawActive = DefaultRectangleRenderer.getInstance().isRectanglePipeline(pipeline);
        attrium$halfIconRectangleDrawActive = HalfIconRectangleRenderer.getInstance().isHalfIconRectanglePipeline(pipeline);
        attrium$halftoneRectangleDrawActive = HalftoneRectangleRenderer.getInstance().isHalftoneRectanglePipeline(pipeline);
        attrium$zippyDrawActive = ZippyRenderer.getInstance().isZippyPipeline(pipeline);
        attrium$arcDrawActive = ArcRenderer.getInstance().isArcPipeline(pipeline);
        attrium$arcOutlineDrawActive = ArcOutlineRenderer.getInstance().isArcOutlinePipeline(pipeline);
        attrium$outlineDrawActive = DefaultOutlineRenderer.getInstance().isOutlinePipeline(pipeline);
        attrium$outline360DrawActive = Outline360Renderer.getInstance().isOutline360Pipeline(pipeline);
        attrium$imageDrawActive = ImageRenderer.getInstance().isImagePipeline(pipeline);
        attrium$rippleDrawActive = RippleRenderer.getInstance().isRipplePipeline(pipeline);
        attrium$itemDrawActive = RenderItem.isItemPipeline(pipeline);
        renderPass.setPipeline(pipeline);
    }

    @Inject(method = "executeDraw", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;drawIndexed(IIII)V", shift = At.Shift.BEFORE))
    private void attrium$bindBlurParams(@Coerce Object draw, RenderPass renderPass, GpuBuffer gpuBuffer, VertexFormat.IndexType indexType, CallbackInfo ci) {
        if (attrium$blurDrawActive && attrium$currentRenderPass != null) {
            BlurFramebuffer.getInstance().bindBlurParams(attrium$currentRenderPass);
        }
        if (attrium$glassDrawActive && attrium$currentRenderPass != null) {
            GlassRenderer.getInstance().bindParams(attrium$currentRenderPass);
        }
        if (attrium$glassOutlineDrawActive && attrium$currentRenderPass != null) {
            GlassOutlineRenderer.getInstance().bindParams(attrium$currentRenderPass);
        }
        if (attrium$rectangleDrawActive && attrium$currentRenderPass != null) {
            DefaultRectangleRenderer.getInstance().bindParams(attrium$currentRenderPass);
        }
        if (attrium$halfIconRectangleDrawActive && attrium$currentRenderPass != null) {
            HalfIconRectangleRenderer.getInstance().bindParams(attrium$currentRenderPass);
        }
        if (attrium$halftoneRectangleDrawActive && attrium$currentRenderPass != null) {
            HalftoneRectangleRenderer.getInstance().bindParams(attrium$currentRenderPass);
        }
        if (attrium$zippyDrawActive && attrium$currentRenderPass != null) {
            ZippyRenderer.getInstance().bindParams(attrium$currentRenderPass);
        }
        if (attrium$arcDrawActive && attrium$currentRenderPass != null) {
            ArcRenderer.getInstance().bindParams(attrium$currentRenderPass);
        }
        if (attrium$arcOutlineDrawActive && attrium$currentRenderPass != null) {
            ArcOutlineRenderer.getInstance().bindParams(attrium$currentRenderPass);
        }
        if (attrium$outlineDrawActive && attrium$currentRenderPass != null) {
            DefaultOutlineRenderer.getInstance().bindParams(attrium$currentRenderPass);
        }
        if (attrium$outline360DrawActive && attrium$currentRenderPass != null) {
            Outline360Renderer.getInstance().bindParams(attrium$currentRenderPass);
        }
        if (attrium$imageDrawActive && attrium$currentRenderPass != null) {
            ImageRenderer.getInstance().bindParams(attrium$currentRenderPass);
        }
        if (attrium$rippleDrawActive && attrium$currentRenderPass != null) {
            RippleRenderer.getInstance().bindParams(attrium$currentRenderPass);
        }
        if (attrium$itemDrawActive && attrium$currentRenderPass != null) {
            RenderItem.bindParams(attrium$currentRenderPass);
        }
    }

    @Inject(method = "executeDraw", at = @At("RETURN"))
    private void attrium$clearTrackedPipeline(@Coerce Object draw, RenderPass renderPass, GpuBuffer gpuBuffer, VertexFormat.IndexType indexType, CallbackInfo ci) {
        attrium$currentRenderPass = null;
        attrium$blurDrawActive = false;
        attrium$glassDrawActive = false;
        attrium$glassOutlineDrawActive = false;
        attrium$rectangleDrawActive = false;
        attrium$halfIconRectangleDrawActive = false;
        attrium$halftoneRectangleDrawActive = false;
        attrium$zippyDrawActive = false;
        attrium$arcDrawActive = false;
        attrium$arcOutlineDrawActive = false;
        attrium$outlineDrawActive = false;
        attrium$outline360DrawActive = false;
        attrium$imageDrawActive = false;
        attrium$itemDrawActive = false;
        attrium$rippleDrawActive = false;
    }
}
