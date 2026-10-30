package releon.ru.api.module.impl.visual;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.WorldRenderEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.settings.impl.ColorSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.world.targetesp.ChainTargetEspRenderer;
import releon.ru.utils.render.world.targetesp.GhostTargetEspRenderer;
import releon.ru.utils.render.world.targetesp.MarkerTargetEspRenderer;
import releon.ru.utils.render.world.targetesp.RhombTargetEspRenderer;
import releon.ru.utils.render.world.targetesp.SkullTargetEspRenderer;
import releon.ru.utils.render.world.targetesp.TargetEspMath;
import releon.ru.utils.render.world.targetesp.TargetEspRenderContext;

import java.awt.Color;

public class TargetESP extends Module {
    private static TargetESP instance;

    private final SmoothAnimation alphaAnimation = new SmoothAnimation();
    private final ModeSetting mode = register(new ModeSetting("Mode", "Target highlight type.", "Ghost", "Rhomb", "Rhomb 2", "Ghost", "Chain", "Skull"));
    private final ColorSetting color1 = register(new ColorSetting("Color 1", "Primary target ESP color.", new Color(127, 242, 255, 136)));
    private final ColorSetting color2 = register(new ColorSetting("Color 2", "Secondary target ESP color.", new Color(255, 50, 150, 255)));
    private Vec3 smoothedPos;
    private LivingEntity lastTarget;
    private float hurtProgress;
    private float chainImpactProgress;
    private long lastFrameTime = System.currentTimeMillis();

    public TargetESP() {
        super("Target ESP", "Highlights the current Aura target.", ModuleCategory.VISUAL);
        instance = this;
        alphaAnimation.set(0.0);
    }

    public static TargetESP getInstance() {
        return instance;
    }

    @Override
    protected void onDisable() {
        alphaAnimation.set(0.0);
        smoothedPos = null;
        lastTarget = null;
        hurtProgress = 0.0f;
        chainImpactProgress = 0.0f;
    }

    @SubscribeEvent
    private void onRender3D(WorldRenderEvent event) {
        long frameTime = System.currentTimeMillis();
        float deltaTime = Math.max(1.0f, Math.min(frameTime - lastFrameTime, 100.0f)) / (1000.0f / 60.0f);
        lastFrameTime = frameTime;

        LivingEntity target = AuraModule.getInstance() != null && AuraModule.getInstance().isEnabled() ? AuraModule.target : null;
        if (target == null || !target.isAlive()) {
            alphaAnimation.run(0.0, 0.25, Easings.CUBIC_OUT, true);
        } else {
            if (lastTarget != target || smoothedPos == null) {
                lastTarget = target;
                smoothedPos = target.getPosition(event.getPartialTicks());
            } else {
                smoothToTarget(target, event.getPartialTicks());
            }
            alphaAnimation.run(1.0, 0.25, Easings.CUBIC_OUT, true);
        }

        alphaAnimation.update();
        float alpha = Mth.clamp(alphaAnimation.get(), 0.0f, 1.0f);
        if (alpha <= 0.01f || lastTarget == null || smoothedPos == null) {
            return;
        }

        hurtProgress = lastTarget.hurtTime > 0 ? (float) lastTarget.hurtTime / 10.0f : Math.max(0.0f, hurtProgress - 0.1f * deltaTime);
        float impactTarget = lastTarget.hurtTime > 0 ? 1.0f : 0.0f;
        float impactStep = (impactTarget > chainImpactProgress ? 0.30f : 0.10f) * deltaTime;
        chainImpactProgress = TargetEspMath.approach(chainImpactProgress, impactTarget, impactStep);

        Vec3 cameraPos = mc.gameRenderer.getMainCamera().position();
        float cameraFade = computeCameraFade(lastTarget, cameraPos, smoothedPos);
        float renderAlpha = alpha * cameraFade;
        if (renderAlpha <= 0.01f) {
            return;
        }

        PoseStack stack = event.getStack();
        MultiBufferSource.BufferSource provider = mc.renderBuffers().bufferSource();
        stack.pushPose();
        stack.translate(smoothedPos.x - cameraPos.x, smoothedPos.y - cameraPos.y, smoothedPos.z - cameraPos.z);

        TargetEspRenderContext context = new TargetEspRenderContext(
                lastTarget,
                renderAlpha,
                event.getPartialTicks(),
                frameTime,
                color1.getValue().getRGB(),
                color2.getValue().getRGB(),
                hurtProgress,
                chainImpactProgress
        );

        if (mode.is("Rhomb")) {
            RhombTargetEspRenderer.render(stack, provider, context);
            RhombTargetEspRenderer.endBatch(provider);
        } else if (mode.is("Rhomb 2")) {
            MarkerTargetEspRenderer.render(stack, provider, context);
            MarkerTargetEspRenderer.endBatch(provider);
        } else if (mode.is("Ghost")) {
            GhostTargetEspRenderer.render(stack, provider, context);
            GhostTargetEspRenderer.endBatch(provider);
        } else if (mode.is("Chain")) {
            ChainTargetEspRenderer.render(stack, provider, context);
            ChainTargetEspRenderer.endBatch(provider);
        } else if (mode.is("Skull")) {
            SkullTargetEspRenderer.render(stack, provider, context);
            SkullTargetEspRenderer.endBatch(provider);
        }
        stack.popPose();
    }

    private void smoothToTarget(LivingEntity target, float partialTicks) {
        Vec3 targetPos = target.getPosition(partialTicks);
        float smoothing = Math.min(1.0f, Math.max(0.12f, partialTicks * 1.5f));
        smoothedPos = new Vec3(
                smoothedPos.x + (targetPos.x - smoothedPos.x) * smoothing,
                smoothedPos.y + (targetPos.y - smoothedPos.y) * smoothing,
                smoothedPos.z + (targetPos.z - smoothedPos.z) * smoothing
        );
    }

    private float computeCameraFade(LivingEntity target, Vec3 cameraPos, Vec3 targetPos) {
        AABB dangerZone = target.getBoundingBox().inflate(0.35);
        if (dangerZone.contains(cameraPos)) {
            return 0.0f;
        }
        double distance = cameraPos.distanceTo(targetPos);
        float fadeStart = Math.max(1.25f, target.getBbWidth() * 1.6f);
        float fadeEnd = fadeStart + 0.9f;
        return Mth.clamp((float) ((distance - fadeStart) / (fadeEnd - fadeStart)), 0.0f, 1.0f);
    }
}
