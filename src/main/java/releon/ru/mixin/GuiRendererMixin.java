package releon.ru.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.vertex.VertexFormat;
import releon.ru.screens.modernui.impl.WorldAnimation;
import releon.ru.utils.render.ui.blur.BlurFramebuffer;
import releon.ru.utils.render.ui.glass.GlassRenderer;
import releon.ru.utils.render.ui.image.ImageRenderer;
import releon.ru.utils.render.ui.outline.outline360.Outline360Renderer;
import releon.ru.utils.render.ui.outline.outlinedefault.DefaultOutlineRenderer;
import releon.ru.utils.render.ui.outline.outlineglass.GlassOutlineRenderer;
import releon.ru.utils.render.ui.arc.ArcOutlineRenderer;
import releon.ru.utils.render.ui.arc.ArcRenderer;
import releon.ru.utils.render.ui.rectangle.rectdefault.DefaultRectangleRenderer;
import releon.ru.utils.render.ui.rectangle.recthalficon.HalfIconRectangleRenderer;
import releon.ru.utils.render.ui.rectangle.recthalftone.HalftoneRectangleRenderer;
import releon.ru.utils.render.ui.ripple.RippleRenderer;
import releon.ru.utils.render.ui.zippy.ZippyRenderer;
import releon.ru.utils.render.item.RenderItem;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
    private RenderPass releon$currentRenderPass;
    private boolean releon$blurDrawActive;
    private boolean releon$glassDrawActive;
    private boolean releon$glassOutlineDrawActive;
    private boolean releon$rectangleDrawActive;
    private boolean releon$halfIconRectangleDrawActive;
    private boolean releon$halftoneRectangleDrawActive;
    private boolean releon$zippyDrawActive;
    private boolean releon$arcDrawActive;
    private boolean releon$arcOutlineDrawActive;
    private boolean releon$outlineDrawActive;
    private boolean releon$outline360DrawActive;
    private boolean releon$imageDrawActive;
    private boolean releon$itemDrawActive;
    private boolean releon$rippleDrawActive;

    @Redirect(method = "draw", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lcom/mojang/blaze3d/ProjectionType;)V", ordinal = 0))
    private void releon$useModernWorldProjection(GpuBufferSlice projection, ProjectionType projectionType) {
        GpuBufferSlice override = WorldAnimation.projectionOverride();
        if (override != null) {
            RenderSystem.setProjectionMatrix(override, ProjectionType.PERSPECTIVE);
            return;
        }
        RenderSystem.setProjectionMatrix(projection, projectionType);
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void releon$beginBlurFrame(GpuBufferSlice fogBuffer, CallbackInfo ci) {
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
    private void releon$preparePendingBlurResources(CallbackInfo ci) {
        BlurFramebuffer.getInstance().preparePending();
    }

    @Inject(method = "prepare", at = @At("RETURN"))
    private void releon$prepareRenderUniforms(CallbackInfo ci) {
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
    private void releon$prepareBlurCapture(GpuBufferSlice fogBuffer, CallbackInfo ci) {
        BlurFramebuffer.getInstance().prepareGuiDraw();
    }

    @Inject(method = "draw", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;processBlurEffect()V", shift = At.Shift.BEFORE))
    private void releon$prepareBlurCaptureAfterBeforeBlur(GpuBufferSlice fogBuffer, CallbackInfo ci) {
        BlurFramebuffer.getInstance().prepareGuiDraw();
    }

    @Redirect(method = "executeDraw", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"))
    private void releon$trackPipeline(RenderPass renderPass, RenderPipeline pipeline) {
        releon$currentRenderPass = renderPass;
        releon$blurDrawActive = BlurFramebuffer.getInstance().isBlurPipeline(pipeline);
        releon$glassDrawActive = GlassRenderer.getInstance().isGlassPipeline(pipeline);
        releon$glassOutlineDrawActive = GlassOutlineRenderer.getInstance().isGlassOutlinePipeline(pipeline);
        releon$rectangleDrawActive = DefaultRectangleRenderer.getInstance().isRectanglePipeline(pipeline);
        releon$halfIconRectangleDrawActive = HalfIconRectangleRenderer.getInstance().isHalfIconRectanglePipeline(pipeline);
        releon$halftoneRectangleDrawActive = HalftoneRectangleRenderer.getInstance().isHalftoneRectanglePipeline(pipeline);
        releon$zippyDrawActive = ZippyRenderer.getInstance().isZippyPipeline(pipeline);
        releon$arcDrawActive = ArcRenderer.getInstance().isArcPipeline(pipeline);
        releon$arcOutlineDrawActive = ArcOutlineRenderer.getInstance().isArcOutlinePipeline(pipeline);
        releon$outlineDrawActive = DefaultOutlineRenderer.getInstance().isOutlinePipeline(pipeline);
        releon$outline360DrawActive = Outline360Renderer.getInstance().isOutline360Pipeline(pipeline);
        releon$imageDrawActive = ImageRenderer.getInstance().isImagePipeline(pipeline);
        releon$rippleDrawActive = RippleRenderer.getInstance().isRipplePipeline(pipeline);
        releon$itemDrawActive = RenderItem.isItemPipeline(pipeline);
        renderPass.setPipeline(pipeline);
    }

    @Inject(method = "executeDraw", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderPass;drawIndexed(IIII)V", shift = At.Shift.BEFORE))
    private void releon$bindBlurParams(@Coerce Object draw, RenderPass renderPass, GpuBuffer gpuBuffer, VertexFormat.IndexType indexType, CallbackInfo ci) {
        if (releon$blurDrawActive && releon$currentRenderPass != null) {
            BlurFramebuffer.getInstance().bindBlurParams(releon$currentRenderPass);
        }
        if (releon$glassDrawActive && releon$currentRenderPass != null) {
            GlassRenderer.getInstance().bindParams(releon$currentRenderPass);
        }
        if (releon$glassOutlineDrawActive && releon$currentRenderPass != null) {
            GlassOutlineRenderer.getInstance().bindParams(releon$currentRenderPass);
        }
        if (releon$rectangleDrawActive && releon$currentRenderPass != null) {
            DefaultRectangleRenderer.getInstance().bindParams(releon$currentRenderPass);
        }
        if (releon$halfIconRectangleDrawActive && releon$currentRenderPass != null) {
            HalfIconRectangleRenderer.getInstance().bindParams(releon$currentRenderPass);
        }
        if (releon$halftoneRectangleDrawActive && releon$currentRenderPass != null) {
            HalftoneRectangleRenderer.getInstance().bindParams(releon$currentRenderPass);
        }
        if (releon$zippyDrawActive && releon$currentRenderPass != null) {
            ZippyRenderer.getInstance().bindParams(releon$currentRenderPass);
        }
        if (releon$arcDrawActive && releon$currentRenderPass != null) {
            ArcRenderer.getInstance().bindParams(releon$currentRenderPass);
        }
        if (releon$arcOutlineDrawActive && releon$currentRenderPass != null) {
            ArcOutlineRenderer.getInstance().bindParams(releon$currentRenderPass);
        }
        if (releon$outlineDrawActive && releon$currentRenderPass != null) {
            DefaultOutlineRenderer.getInstance().bindParams(releon$currentRenderPass);
        }
        if (releon$outline360DrawActive && releon$currentRenderPass != null) {
            Outline360Renderer.getInstance().bindParams(releon$currentRenderPass);
        }
        if (releon$imageDrawActive && releon$currentRenderPass != null) {
            ImageRenderer.getInstance().bindParams(releon$currentRenderPass);
        }
        if (releon$rippleDrawActive && releon$currentRenderPass != null) {
            RippleRenderer.getInstance().bindParams(releon$currentRenderPass);
        }
        if (releon$itemDrawActive && releon$currentRenderPass != null) {
            RenderItem.bindParams(releon$currentRenderPass);
        }
    }

    @Inject(method = "executeDraw", at = @At("RETURN"))
    private void releon$clearTrackedPipeline(@Coerce Object draw, RenderPass renderPass, GpuBuffer gpuBuffer, VertexFormat.IndexType indexType, CallbackInfo ci) {
        releon$currentRenderPass = null;
        releon$blurDrawActive = false;
        releon$glassDrawActive = false;
        releon$glassOutlineDrawActive = false;
        releon$rectangleDrawActive = false;
        releon$halfIconRectangleDrawActive = false;
        releon$halftoneRectangleDrawActive = false;
        releon$zippyDrawActive = false;
        releon$arcDrawActive = false;
        releon$arcOutlineDrawActive = false;
        releon$outlineDrawActive = false;
        releon$outline360DrawActive = false;
        releon$imageDrawActive = false;
        releon$itemDrawActive = false;
        releon$rippleDrawActive = false;
    }
}
