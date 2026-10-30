package releon.ru.api.module.impl.visual;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.AttackEvent;
import releon.ru.api.events.impl.DrawEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.events.impl.WorldRenderEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.ModuleManager;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.module.impl.visual.frageffect.FragEffectDeathMemoryTracker;
import releon.ru.api.module.impl.visual.frageffect.FragEffectEasing;
import releon.ru.api.module.impl.visual.frageffect.FragEffectParticleSystem;
import releon.ru.api.module.impl.visual.frageffect.FragEffectScanRenderer;
import releon.ru.api.module.impl.visual.frageffect.FragEffectSoundQueue;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ColorSetting;
import releon.ru.api.settings.impl.MultiModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.manager.Manager;
import releon.ru.utils.render.RenderCompatibility;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.repository.friend.FriendUtils;
import releon.ru.utils.sounds.SoundManager;

import java.awt.Color;

public class KillEffect extends Module {
    private static final long BASE_EFFECT_DURATION_MS = 5200L;
    private static final String VIGNETTE_TEXTURE = "releon:textures/effects/frag/lightarroundscreen_alpha.png";
    private static KillEffect instance;

    private final FragEffectSoundQueue soundQueue = new FragEffectSoundQueue();
    private final FragEffectDeathMemoryTracker deathMemoryTracker = new FragEffectDeathMemoryTracker();
    private final FragEffectParticleSystem particleSystem = new FragEffectParticleSystem();

    private final NumberSetting speed = register(new NumberSetting("Speed", "Controls effect playback speed.", 100.0, 25.0, 200.0, 1.0));
    private final ColorSetting effectColor = register(new ColorSetting("Color", "Primary kill effect color.", new Color(-50116, true)));
    private final MultiModeSetting effectTargets = register(new MultiModeSetting("Targets", "Entities that can trigger the kill effect.",
            new String[]{"Players", "Friends", "Mobs", "Animals"}, "Players", "Friends", "Mobs", "Animals"));
    private final BooleanSetting screenOverlay = register(new BooleanSetting("Screen Overlay", "Shows the screen vignette during the kill effect.", true));
    private final BooleanSetting scanlines = register(new BooleanSetting("Scanlines", "Shows scan stripes inside the wave.", true));
    private final BooleanSetting changeSaturation = register(new BooleanSetting("Change Saturation", "Temporarily lowers world saturation during the effect.", false));

    private boolean animate;
    private long effectStartMillis = System.currentTimeMillis();
    private float activeEffectSpeedMultiplier = 1.0F;

    public KillEffect() {
        super("Kill Effect", "Kill effect with vignette, scan wave, particles and sounds.", ModuleCategory.VISUAL);
        instance = this;
    }

    public static KillEffect getInstance() {
        return instance;
    }

    public static KillEffect getInstanceIfReady() {
        ModuleManager modules = Manager.getModules();
        if (modules == null) {
            return null;
        }
        return modules.getByType(KillEffect.class).orElse(instance);
    }

    @SubscribeEvent
    private void onAttack(AttackEvent event) {
        Entity entity = event.getTarget();
        if (!(entity instanceof LivingEntity target)) {
            return;
        }
        if (!target.isAlive() || target.getHealth() <= 0.0F || !shouldTriggerFor(target)) {
            return;
        }
        this.deathMemoryTracker.remember(target, true);
    }

    @SubscribeEvent
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) {
            resetState();
            return;
        }

        LivingEntity auraTarget = AuraModule.target;
        if (auraTarget != null && auraTarget.isAlive() && auraTarget.getHealth() > 0.0F && shouldTriggerFor(auraTarget)) {
            this.deathMemoryTracker.remember(auraTarget, true);
        }

        this.deathMemoryTracker.tick(this::onTrackedKill);
        this.particleSystem.tick();
        this.soundQueue.tick();
    }

    @SubscribeEvent
    private void onWorldRender(WorldRenderEvent event) {
        this.particleSystem.render(event.getPartialTicks());
    }

    @SubscribeEvent
    private void onDraw(DrawEvent event) {
        drawVignette();
    }

    @Override
    protected void onDisable() {
        resetState();
    }

    public void onAfterTranslucent(RenderTarget renderTarget, Matrix4f projectionMatrix, Matrix4f positionMatrix, Vec3 cameraPos) {
        if (!isEnabled()) {
            return;
        }
        if (RenderCompatibility.shouldDisableFragEffectScanShader() || FragEffectScanRenderer.isDisabledAfterError()) {
            return;
        }
        FragEffectScanRenderer.render(renderTarget, projectionMatrix, positionMatrix, cameraPos, this.activeEffectSpeedMultiplier, c1(), c2(), c3(), c4(), c5(), c6(), c7(), c8());
    }

    public static float getWorldSaturationMultiplier() {
        KillEffect module = KillEffect.getInstanceIfReady();
        if (module == null || !module.isEnabled() || !module.changeSaturation.getValue()) {
            return 1.0F;
        }

        float pulse = module.transientEnvelope(1950L, 260L, 760L);
        if (pulse <= 0.0F) {
            return 1.0F;
        }
        return Math.clamp(1.0F - pulse, 0.0F, 1.0F);
    }

    private void drawVignette() {
        if (!this.screenOverlay.getValue()) {
            return;
        }

        float effectProgress = effectProgress();
        if (effectProgress <= 0.0F) {
            return;
        }

        float alphaProgress = effectEnvelope(380L, 1100L);
        if (alphaProgress <= 0.0F) {
            return;
        }

        float scaleAnimation = FragEffectEasing.quintOut(effectProgress);
        float scaleProgress = Math.min((scaleAnimation > 0.5F ? 1.0F - scaleAnimation : scaleAnimation) * 4.25F, 1.0F);
        float width = mc.getWindow().getGuiScaledWidth();
        float height = mc.getWindow().getGuiScaledHeight();
        float extendX = width * 0.5F * (1.0F - scaleProgress);
        float extendY = height * 0.5F * (1.0F - scaleProgress);
        int color = ColorUtil.withAlpha(ColorUtil.interpolateColor(lighten(accent(), 0.22F), ColorUtil.WHITE, effectProgress), Math.round(80.0F * alphaProgress));
        Render2D.image(VIGNETTE_TEXTURE, -extendX, -extendY, width + extendX * 2.0F, height + extendY * 2.0F, 0.0F, color);
    }

    private void onTrackedKill(LivingEntity entity) {
        if (!matchesEffectTarget(entity)) {
            return;
        }

        Vec3 killPosition = entity.getEyePosition();
        float effectSpeed = speedMultiplier();
        restartEffect(effectSpeed);
        FragEffectScanRenderer.ping(killPosition, effectSpeed);
        this.particleSystem.spawnBurst(killPosition, 480, 900, 1.9F, 22, 0.012F);
        scheduleCharmSounds();
    }

    private void scheduleCharmSounds() {
        long offset = scaleDuration(150L);
        this.soundQueue.schedule(SoundManager.FRAG_EFFECT_PULSE, 0L, 1.0F);
        this.soundQueue.schedule(SoundManager.FRAG_EFFECT_KNOCK_MAIN, offset, 1.0F);
        this.soundQueue.schedule(SoundManager.FRAG_EFFECT_SPARKS_COLLISION, offset * 2L, 0.2F);
        this.soundQueue.schedule(SoundManager.FRAG_EFFECT_ECHO_MAIN, offset * 3L, 0.6F);
    }

    private void restartEffect(float effectSpeedMultiplier) {
        this.animate = true;
        this.activeEffectSpeedMultiplier = Math.clamp(effectSpeedMultiplier, 0.25F, 2.0F);
        this.effectStartMillis = System.currentTimeMillis();
    }

    private boolean shouldTriggerFor(LivingEntity entity) {
        return entity != null && entity != mc.player && entity.isAlive() && entity.getHealth() > 0.0F && matchesEffectTarget(entity);
    }

    private boolean matchesEffectTarget(LivingEntity entity) {
        if (entity == null || entity == mc.player) {
            return false;
        }
        if (entity instanceof Player player) {
            if (FriendUtils.isFriend(player)) {
                return this.effectTargets.isSelected("Friends");
            }
            return this.effectTargets.isSelected("Players");
        }
        if (entity instanceof Animal) {
            return this.effectTargets.isSelected("Animals");
        }
        if (entity instanceof Mob) {
            return this.effectTargets.isSelected("Mobs");
        }
        return false;
    }

    private float effectProgress() {
        long duration = effectDurationMs();
        float progress = this.animate ? Math.min((float) elapsedMillis() / (float) duration, 1.0F) : 0.0F;
        if (progress >= 1.0F) {
            this.animate = false;
        }
        return progress;
    }

    private float effectEnvelope(long attackMs, long releaseMs) {
        if (!this.animate) {
            return 0.0F;
        }

        long duration = effectDurationMs();
        long elapsed = Math.min(elapsedMillis(), duration);
        if (elapsed <= 0L) {
            return 0.0F;
        }

        long attack = Math.clamp(scaleDuration(attackMs), 1L, duration);
        long release = Math.clamp(scaleDuration(releaseMs), 1L, duration);
        long releaseStart = Math.max(duration - release, attack);
        if (elapsed < attack) {
            return FragEffectEasing.sineOut((float) elapsed / (float) attack);
        }
        if (elapsed >= releaseStart) {
            float releaseProgress = Math.clamp((float) (elapsed - releaseStart) / (float) release, 0.0F, 1.0F);
            return 1.0F - FragEffectEasing.sineInOut(releaseProgress);
        }
        return 1.0F;
    }

    private float transientEnvelope(long totalMs, long attackMs, long releaseMs) {
        if (!this.animate) {
            return 0.0F;
        }

        long duration = effectDurationMs();
        long total = Math.clamp(scaleDuration(totalMs), 1L, duration);
        long elapsed = Math.min(elapsedMillis(), total);
        if (elapsed <= 0L) {
            return 0.0F;
        }

        long attack = Math.clamp(scaleDuration(attackMs), 1L, total);
        long release = Math.clamp(scaleDuration(releaseMs), 1L, total);
        long releaseStart = Math.max(total - release, attack);
        if (elapsed < attack) {
            return FragEffectEasing.sineOut((float) elapsed / (float) attack);
        }
        if (elapsed >= releaseStart) {
            float releaseProgress = Math.clamp((float) (elapsed - releaseStart) / (float) release, 0.0F, 1.0F);
            return 1.0F - FragEffectEasing.sineInOut(releaseProgress);
        }
        return 1.0F;
    }

    private void resetState() {
        this.animate = false;
        this.activeEffectSpeedMultiplier = speedMultiplier();
        this.soundQueue.clear();
        this.deathMemoryTracker.clear();
        this.particleSystem.clear();
        FragEffectScanRenderer.clear();
    }

    private long elapsedMillis() {
        return System.currentTimeMillis() - this.effectStartMillis;
    }

    private int accent() {
        Color color = this.effectColor.getValue();
        return ColorUtil.rgba(color.getRed(), color.getGreen(), color.getBlue(), 255);
    }

    private float speedMultiplier() {
        return Math.clamp(this.speed.getFloat() / 100.0F, 0.25F, 2.0F);
    }

    private long effectDurationMs() {
        return scaleDuration(BASE_EFFECT_DURATION_MS);
    }

    private long scaleDuration(long durationMs) {
        return scaleDuration(durationMs, this.activeEffectSpeedMultiplier);
    }

    private long scaleDuration(long durationMs, float speedMultiplier) {
        if (durationMs <= 0L) {
            return 0L;
        }
        return Math.max(1L, Math.round(durationMs / Math.clamp(speedMultiplier, 0.25F, 2.0F)));
    }

    private int c1() {
        return lighten(accent(), 0.45F);
    }

    private int c2() {
        return lighten(accent(), 0.18F);
    }

    private int c3() {
        return darken(accent(), 0.18F);
    }

    private int c4() {
        return this.scanlines.getValue() ? lighten(accent(), 0.6F) : 0;
    }

    private int c5() {
        return lighten(accent(), 0.7F);
    }

    private int c6() {
        return ColorUtil.interpolateColor(accent(), ColorUtil.WHITE, 0.28F);
    }

    private int c7() {
        return darken(accent(), 0.08F);
    }

    private int c8() {
        return this.scanlines.getValue() ? darken(accent(), 0.35F) : 0;
    }

    private static int lighten(int color, float amount) {
        float clamped = Math.clamp(amount, 0.0F, 1.0F);
        int red = ColorUtil.getRed(color);
        int green = ColorUtil.getGreen(color);
        int blue = ColorUtil.getBlue(color);
        int alpha = ColorUtil.getAlpha(color);
        return ColorUtil.rgba(
                Math.clamp(Math.round(red + (255 - red) * clamped), 0, 255),
                Math.clamp(Math.round(green + (255 - green) * clamped), 0, 255),
                Math.clamp(Math.round(blue + (255 - blue) * clamped), 0, 255),
                alpha
        );
    }

    private static int darken(int color, float amount) {
        float factor = 1.0F - Math.clamp(amount, 0.0F, 1.0F);
        return ColorUtil.rgba(
                Math.clamp(Math.round(ColorUtil.getRed(color) * factor), 0, 255),
                Math.clamp(Math.round(ColorUtil.getGreen(color) * factor), 0, 255),
                Math.clamp(Math.round(ColorUtil.getBlue(color) * factor), 0, 255),
                ColorUtil.getAlpha(color)
        );
    }
}
