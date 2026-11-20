package attrium.ru.api.module.impl.visual;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributes;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.ModuleCategory;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.ModeSetting;
import attrium.ru.api.settings.impl.NumberSetting;

/**
 * Attrium Ambience Module (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * Full atmospheric & lighting overhaul powered natively by 1.21.11's EnvironmentAttributeProbe:
 *  - 11 handcrafted aesthetic themes (Vanilla, Golden Hour, Sakura, Cyberpunk, Aurora,
 *    Blood Moon, Pastel Dream, Midnight, Arctic, Chroma, Custom) with silky-smooth real-time
 *    color morphing and zero per-frame heap allocations.
 *  - Dynamic live animations: shimmering multi-band Aurora, tasteful low-saturation pastel Chroma,
 *    optional starfield spin, and cinematic Time Cycle timelapse.
 *  - Full control over Sky, Fog, Sunrise/Sunset Horizon Glow, Clouds, Cloud Height, Underwater Clarity,
 *    Ambient World Light Tint, Brightness Boost (keeps night/dark presets crisp & playable),
 *    Star Brightness, Time of Day, and Client Weather.
 *  - Mobile-first & Sodium/ImmediatelyFast compatible (zero custom shaders, zero FBOs, zero GC churn).
 */
public class Ambience extends Module {
    private static volatile Ambience instance;

    // =========================================================================
    // Preset Palette Table (skyRgb, fogRgb, horizonRgb, cloudRgb, lightRgb, waterRgb,
    //                       presetTimeTicks, presetStarBrightness, horizonGlowAlpha, fogStart, fogEnd)
    // =========================================================================
    private static final class AtmospherePreset {
        final int skyRgb;
        final int fogRgb;
        final int horizonRgb;
        final int cloudRgb;
        final int lightRgb;
        final int waterRgb;
        final long presetTime;
        final float starBrightness;
        final int horizonAlpha;
        final float fogStart;
        final float fogEnd;

        AtmospherePreset(
                int skyRgb,
                int fogRgb,
                int horizonRgb,
                int cloudRgb,
                int lightRgb,
                int waterRgb,
                long presetTime,
                float starBrightness,
                int horizonAlpha,
                float fogStart,
                float fogEnd
        ) {
            this.skyRgb = skyRgb;
            this.fogRgb = fogRgb;
            this.horizonRgb = horizonRgb;
            this.cloudRgb = cloudRgb;
            this.lightRgb = lightRgb;
            this.waterRgb = waterRgb;
            this.presetTime = presetTime;
            this.starBrightness = starBrightness;
            this.horizonAlpha = horizonAlpha;
            this.fogStart = fogStart;
            this.fogEnd = fogEnd;
        }
    }

    private static final AtmospherePreset PRESET_GOLDEN_HOUR = new AtmospherePreset(
            0xFF8C52, 0xFFB87A, 0xFF6B35, 0xFFE3C2, 0xFFD9B3, 0x3D8A99,
            12480L, 0.35F, 0xD8, 48.0F, 260.0F
    );
    private static final AtmospherePreset PRESET_SAKURA = new AtmospherePreset(
            0xF2A6CA, 0xFFD4E8, 0xFF8EC2, 0xFFF0F7, 0xFFE3F0, 0x7AB8D9,
            12200L, 0.48F, 0xC8, 56.0F, 280.0F
    );
    private static final AtmospherePreset PRESET_CYBERPUNK = new AtmospherePreset(
            0x140A2E, 0x26124D, 0xD92BFF, 0x6F38C5, 0xC8B8FF, 0x164E63,
            18000L, 0.92F, 0xB8, 40.0F, 220.0F
    );
    private static final AtmospherePreset PRESET_AURORA = new AtmospherePreset(
            0x081C2E, 0x0F3344, 0x2BF2B8, 0x5EEAD4, 0xBDF5EB, 0x0E5269,
            17600L, 1.00F, 0xB4, 52.0F, 260.0F
    );
    private static final AtmospherePreset PRESET_BLOOD_MOON = new AtmospherePreset(
            0x24060D, 0x4A0C18, 0xE62238, 0x7A1C2A, 0xFFC2C7, 0x4A121C,
            18000L, 0.88F, 0xD0, 32.0F, 200.0F
    );
    private static final AtmospherePreset PRESET_PASTEL_DREAM = new AtmospherePreset(
            0x9CCBFF, 0xD8C4FF, 0xFFB8DE, 0xFFFFFF, 0xF5F0FF, 0x6EC6E8,
            4500L, 0.25F, 0x90, 72.0F, 340.0F
    );
    private static final AtmospherePreset PRESET_MIDNIGHT = new AtmospherePreset(
            0x070C1F, 0x0F1938, 0x2A3F7A, 0x253566, 0xBDD0FF, 0x0E2A4D,
            18000L, 1.00F, 0x80, 64.0F, 320.0F
    );
    private static final AtmospherePreset PRESET_ARCTIC = new AtmospherePreset(
            0xB3E5FC, 0xE1F5FE, 0x90CAF9, 0xF5FBFF, 0xE8F7FF, 0x4DD0E1,
            5500L, 0.20F, 0x70, 60.0F, 300.0F
    );

    // =========================================================================
    // ClickGUI Settings (Clean, Scannable, Conditional Custom RGB Sliders)
    // =========================================================================
    private final ModeSetting theme = register(new ModeSetting(
            "Theme",
            "Handcrafted atmospheric theme with smooth live transitions.",
            "Sakura",
            "Vanilla", "Golden Hour", "Sakura", "Cyberpunk", "Aurora",
            "Blood Moon", "Pastel Dream", "Midnight", "Arctic", "Chroma", "Custom"
    ));

    private final NumberSetting tintStrength = register(new NumberSetting(
            "Tint Strength",
            "Blend intensity between vanilla biome colors and the Ambience theme (0.1 - 1.0).",
            0.80D, 0.10D, 1.00D, 0.05D
    ));

    private final BooleanSetting worldTint = register(new BooleanSetting(
            "World Tint",
            "Subtly tints terrain & sky lighting so the whole world harmonizes with the sky.",
            true
    ));

    private final NumberSetting saturation = register(new NumberSetting(
            "Saturation",
            "Color vibrancy multiplier for the world (0.5 - 1.8).",
            1.15D, 0.50D, 1.80D, 0.05D
    ));

    private final NumberSetting brightnessBoost = register(new NumberSetting(
            "Brightness Boost",
            "Lifts ambient sky light floor so dark/night themes stay crisp and easy to see.",
            0.35D, 0.00D, 1.00D, 0.05D
    ));

    private final ModeSetting timeMode = register(new ModeSetting(
            "Time Mode",
            "Controls client world time: Server, Theme Preset, Custom fixed tick, or live Cycle.",
            "Preset",
            "Server", "Preset", "Custom", "Cycle"
    ));

    private final NumberSetting customTime = register(new NumberSetting(
            "Custom Time",
            "Fixed client time of day in ticks (0 = Dawn, 6000 = Noon, 12500 = Sunset, 18000 = Midnight).",
            12500.0D, 0.0D, 24000.0D, 100.0D
    ));

    private final NumberSetting cycleSpeed = register(new NumberSetting(
            "Cycle Speed",
            "Timelapse speed for Cycle mode (ticks per second * 20).",
            8.0D, 1.0D, 60.0D, 1.0D
    ));

    private final ModeSetting weather = register(new ModeSetting(
            "Weather",
            "Client-side weather override without affecting the server.",
            "Clear",
            "Server", "Clear", "Rain", "Snow", "Thunder"
    ));

    private final ModeSetting fogStyle = register(new ModeSetting(
            "Fog Style",
            "Controls atmospheric fog depth: Preset, Clear, Cozy Mist, Dense, or Off.",
            "Preset",
            "Preset", "Clear", "Cozy Mist", "Dense", "Off"
    ));

    private final BooleanSetting clearWater = register(new BooleanSetting(
            "Clear Water",
            "Pushes underwater fog far back with clean tinted visibility.",
            true
    ));

    private final NumberSetting starBrightness = register(new NumberSetting(
            "Star Brightness",
            "Controls starfield intensity in twilight and night skies (0.0 - 1.0).",
            0.85D, 0.00D, 1.00D, 0.05D
    ));

    private final BooleanSetting spinStars = register(new BooleanSetting(
            "Spin Stars",
            "Gently rotates the night starfield across the sky.",
            false
    ));

    private final NumberSetting cloudHeight = register(new NumberSetting(
            "Cloud Height",
            "Custom cloud altitude in blocks (64 - 320).",
            192.0D, 64.0D, 320.0D, 4.0D
    ));

    private final BooleanSetting smoothBlend = register(new BooleanSetting(
            "Smooth Blend",
            "Smoothly morphs sky, fog, clouds, and light colors when switching themes.",
            true
    ));

    // Conditional Custom RGB Sliders (Hidden in ClickGUI unless Theme == Custom)
    private final NumberSetting skyRed = register(new NumberSetting(
            "Sky Red", "Custom Sky Red (0 - 255).", 140.0D, 0.0D, 255.0D, 1.0D
    ));
    private final NumberSetting skyGreen = register(new NumberSetting(
            "Sky Green", "Custom Sky Green (0 - 255).", 110.0D, 0.0D, 255.0D, 1.0D
    ));
    private final NumberSetting skyBlue = register(new NumberSetting(
            "Sky Blue", "Custom Sky Blue (0 - 255).", 255.0D, 0.0D, 255.0D, 1.0D
    ));
    private final NumberSetting fogRed = register(new NumberSetting(
            "Fog Red", "Custom Fog Red (0 - 255).", 210.0D, 0.0D, 255.0D, 1.0D
    ));
    private final NumberSetting fogGreen = register(new NumberSetting(
            "Fog Green", "Custom Fog Green (0 - 255).", 175.0D, 0.0D, 255.0D, 1.0D
    ));
    private final NumberSetting fogBlue = register(new NumberSetting(
            "Fog Blue", "Custom Fog Blue (0 - 255).", 255.0D, 0.0D, 255.0D, 1.0D
    ));

    // =========================================================================
    // Zero-Allocation Animated & Interpolated Runtime State
    // =========================================================================
    private long lastFrameNanos = 0L;
    private float animSeconds = 0.0F;
    private double cycleTimeTicks = 12000.0D;
    private boolean blendInitialized = false;

    private float curSkyR, curSkyG, curSkyB;
    private float curFogR, curFogG, curFogB;
    private float curHorR, curHorG, curHorB, curHorA;
    private float curCloudR, curCloudG, curCloudB;
    private float curLightR, curLightG, curLightB;
    private float curWaterR, curWaterG, curWaterB;
    private float curFogStart = 56.0F;
    private float curFogEnd = 280.0F;

    public Ambience() {
        super("Ambience", "Cinematic sky, fog, lighting, weather, and celestial atmosphere presets.", ModuleCategory.VISUAL);
        instance = this;

        this.customTime.visibleWhen(() -> "Custom".equalsIgnoreCase(this.timeMode.getValue()));
        this.cycleSpeed.visibleWhen(() -> "Cycle".equalsIgnoreCase(this.timeMode.getValue()));

        this.skyRed.visibleWhen(this::isCustomTheme);
        this.skyGreen.visibleWhen(this::isCustomTheme);
        this.skyBlue.visibleWhen(this::isCustomTheme);
        this.fogRed.visibleWhen(this::isCustomTheme);
        this.fogGreen.visibleWhen(this::isCustomTheme);
        this.fogBlue.visibleWhen(this::isCustomTheme);
    }

    public static Ambience getInstance() {
        return instance;
    }

    public static boolean isActive() {
        Ambience mod = instance;
        return mod != null && mod.isEnabled();
    }

    @Override
    protected void onEnable() {
        blendInitialized = false;
        lastFrameNanos = System.nanoTime();
    }

    @Override
    protected void onDisable() {
        blendInitialized = false;
    }

    private boolean isCustomTheme() {
        return "Custom".equalsIgnoreCase(theme.getValue());
    }

    private boolean isVanillaTheme() {
        return "Vanilla".equalsIgnoreCase(theme.getValue());
    }

    // =========================================================================
    // Per-Frame Smooth Interpolation & Live Animation Engine (Zero Allocations)
    // =========================================================================
    private void updateFrame() {
        long now = System.nanoTime();
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now;
        }
        long elapsedNanos = now - lastFrameNanos;
        if (elapsedNanos < 2_000_000L && blendInitialized) {
            return;
        }
        lastFrameNanos = now;

        float dt = (float) (elapsedNanos * 1.0e-9D);
        if (dt < 0.0F || dt > 0.1F) {
            dt = 0.016F;
        }
        animSeconds += dt;
        if (animSeconds > 86400.0F) {
            animSeconds -= 86400.0F;
        }

        if ("Cycle".equalsIgnoreCase(timeMode.getValue())) {
            double speed = cycleSpeed.getValue();
            cycleTimeTicks = (cycleTimeTicks + dt * speed * 45.0D) % 24000.0D;
            if (cycleTimeTicks < 0.0D) {
                cycleTimeTicks += 24000.0D;
            }
        }

        int targetSky;
        int targetFog;
        int targetHor;
        int targetCloud;
        int targetLight;
        int targetWater;
        int targetHorA;
        float targetFogStart;
        float targetFogEnd;

        String t = theme.getValue();
        if ("Chroma".equalsIgnoreCase(t)) {
            float hue = (animSeconds * 0.035F) % 1.0F;
            float hue2 = (hue + 0.08F) % 1.0F;
            float hue3 = (hue + 0.16F) % 1.0F;
            targetSky = hsbToRgb(hue, 0.42F, 0.94F);
            targetFog = hsbToRgb(hue2, 0.34F, 0.98F);
            targetHor = hsbToRgb(hue3, 0.52F, 1.00F);
            targetCloud = hsbToRgb(hue2, 0.18F, 1.00F);
            targetLight = hsbToRgb(hue, 0.16F, 1.00F);
            targetWater = hsbToRgb((hue + 0.45F) % 1.0F, 0.45F, 0.80F);
            targetHorA = 0xAA;
            targetFogStart = 60.0F;
            targetFogEnd = 300.0F;
        } else if ("Aurora".equalsIgnoreCase(t)) {
            // Smooth emerald -> teal -> cyan -> violet northern lights wave
            float wave = 0.5F + 0.5F * (float) Math.sin(animSeconds * 0.55F);
            float wave2 = 0.5F + 0.5F * (float) Math.sin(animSeconds * 0.37F + 1.4F);
            targetSky = lerpRgb(0x071A2B, 0x160D33, wave2 * 0.65F);
            targetFog = lerpRgb(0x0D3B46, 0x221B54, wave * 0.55F);
            targetHor = lerpRgb(0x2BF2B8, 0xA855F7, wave);
            targetCloud = lerpRgb(0x4FF0C8, 0x9F7AEA, wave);
            targetLight = lerpRgb(0xC2FAF0, 0xDDD6FE, wave * 0.5F);
            targetWater = PRESET_AURORA.waterRgb;
            targetHorA = 0xBC;
            targetFogStart = PRESET_AURORA.fogStart;
            targetFogEnd = PRESET_AURORA.fogEnd;
        } else if ("Custom".equalsIgnoreCase(t)) {
            int sr = Mth.clamp(skyRed.getValue().intValue(), 0, 255);
            int sg = Mth.clamp(skyGreen.getValue().intValue(), 0, 255);
            int sb = Mth.clamp(skyBlue.getValue().intValue(), 0, 255);
            int fr = Mth.clamp(fogRed.getValue().intValue(), 0, 255);
            int fg = Mth.clamp(fogGreen.getValue().intValue(), 0, 255);
            int fb = Mth.clamp(fogBlue.getValue().intValue(), 0, 255);
            targetSky = (sr << 16) | (sg << 8) | sb;
            targetFog = (fr << 16) | (fg << 8) | fb;
            targetHor = lerpRgb(targetFog, 0xFFFFFF, 0.20F);
            targetCloud = lerpRgb(targetFog, 0xFFFFFF, 0.55F);
            targetLight = lerpRgb(0xFFFFFF, targetSky, 0.28F);
            targetWater = lerpRgb(0x1B6CA8, targetFog, 0.35F);
            targetHorA = 0xA8;
            targetFogStart = 56.0F;
            targetFogEnd = 280.0F;
        } else {
            AtmospherePreset p = resolveStaticPreset(t);
            targetSky = p.skyRgb;
            targetFog = p.fogRgb;
            targetHor = p.horizonRgb;
            targetCloud = p.cloudRgb;
            targetLight = p.lightRgb;
            targetWater = p.waterRgb;
            targetHorA = p.horizonAlpha;
            targetFogStart = p.fogStart;
            targetFogEnd = p.fogEnd;
        }

        float alpha = (!blendInitialized || !Boolean.TRUE.equals(smoothBlend.getValue()))
                ? 1.0F
                : 1.0F - (float) Math.exp(-dt * 6.5F);

        curSkyR = Mth.lerp(alpha, curSkyR, (targetSky >>> 16) & 0xFF);
        curSkyG = Mth.lerp(alpha, curSkyG, (targetSky >>> 8) & 0xFF);
        curSkyB = Mth.lerp(alpha, curSkyB, targetSky & 0xFF);

        curFogR = Mth.lerp(alpha, curFogR, (targetFog >>> 16) & 0xFF);
        curFogG = Mth.lerp(alpha, curFogG, (targetFog >>> 8) & 0xFF);
        curFogB = Mth.lerp(alpha, curFogB, targetFog & 0xFF);

        curHorR = Mth.lerp(alpha, curHorR, (targetHor >>> 16) & 0xFF);
        curHorG = Mth.lerp(alpha, curHorG, (targetHor >>> 8) & 0xFF);
        curHorB = Mth.lerp(alpha, curHorB, targetHor & 0xFF);
        curHorA = Mth.lerp(alpha, curHorA, targetHorA);

        curCloudR = Mth.lerp(alpha, curCloudR, (targetCloud >>> 16) & 0xFF);
        curCloudG = Mth.lerp(alpha, curCloudG, (targetCloud >>> 8) & 0xFF);
        curCloudB = Mth.lerp(alpha, curCloudB, targetCloud & 0xFF);

        curLightR = Mth.lerp(alpha, curLightR, (targetLight >>> 16) & 0xFF);
        curLightG = Mth.lerp(alpha, curLightG, (targetLight >>> 8) & 0xFF);
        curLightB = Mth.lerp(alpha, curLightB, targetLight & 0xFF);

        curWaterR = Mth.lerp(alpha, curWaterR, (targetWater >>> 16) & 0xFF);
        curWaterG = Mth.lerp(alpha, curWaterG, (targetWater >>> 8) & 0xFF);
        curWaterB = Mth.lerp(alpha, curWaterB, targetWater & 0xFF);

        curFogStart = Mth.lerp(alpha, curFogStart, targetFogStart);
        curFogEnd = Mth.lerp(alpha, curFogEnd, targetFogEnd);

        blendInitialized = true;
    }

    private AtmospherePreset resolveStaticPreset(String name) {
        if (name == null) return PRESET_SAKURA;
        return switch (name) {
            case "Golden Hour" -> PRESET_GOLDEN_HOUR;
            case "Cyberpunk" -> PRESET_CYBERPUNK;
            case "Aurora" -> PRESET_AURORA;
            case "Blood Moon" -> PRESET_BLOOD_MOON;
            case "Pastel Dream" -> PRESET_PASTEL_DREAM;
            case "Midnight" -> PRESET_MIDNIGHT;
            case "Arctic" -> PRESET_ARCTIC;
            default -> PRESET_SAKURA;
        };
    }

    // =========================================================================
    // 1.21.11 EnvironmentAttributeProbe Hook Entry Point
    // =========================================================================
    @SuppressWarnings("unchecked")
    public static <Value> Value modifyEnvironmentAttribute(EnvironmentAttribute<Value> attribute, Value original) {
        Ambience mod = instance;
        if (mod == null || !mod.isEnabled() || attribute == null || original == null) {
            return original;
        }
        mod.updateFrame();

        boolean vanilla = mod.isVanillaTheme();
        float strength = Mth.clamp(mod.tintStrength.getValue().floatValue(), 0.10F, 1.00F);

        // --- Colors (Integer ARGB/RGB) ---
        if (!vanilla && original instanceof Integer origInt) {
            if (attribute == EnvironmentAttributes.SKY_COLOR) {
                int target = packRgb(mod.curSkyR, mod.curSkyG, mod.curSkyB);
                return (Value) Integer.valueOf(blendPreserveAlpha(origInt, target, strength, true));
            }
            if (attribute == EnvironmentAttributes.FOG_COLOR) {
                int target = packRgb(mod.curFogR, mod.curFogG, mod.curFogB);
                return (Value) Integer.valueOf(blendPreserveAlpha(origInt, target, strength, true));
            }
            if (attribute == EnvironmentAttributes.SUNRISE_SUNSET_COLOR) {
                int origA = (origInt >>> 24) & 0xFF;
                int targetA = Mth.clamp(Math.round(mod.curHorA * strength), 0, 255);
                int outA = Math.max(origA, targetA);
                int target = packRgb(mod.curHorR, mod.curHorG, mod.curHorB);
                int blendedRgb = lerpRgb(origInt & 0xFFFFFF, target, strength);
                return (Value) Integer.valueOf((outA << 24) | blendedRgb);
            }
            if (attribute == EnvironmentAttributes.CLOUD_COLOR) {
                int origA = (origInt >>> 24) & 0xFF;
                int outA = origA > 0 ? origA : 0xCC;
                int target = packRgb(mod.curCloudR, mod.curCloudG, mod.curCloudB);
                int blendedRgb = lerpRgb(origInt & 0xFFFFFF, target, strength * 0.90F);
                return (Value) Integer.valueOf((outA << 24) | blendedRgb);
            }
            if (attribute == EnvironmentAttributes.SKY_LIGHT_COLOR && Boolean.TRUE.equals(mod.worldTint.getValue())) {
                int target = packRgb(mod.curLightR, mod.curLightG, mod.curLightB);
                // Keep world lighting tasteful and readable (max 55% tint weight)
                return (Value) Integer.valueOf(blendPreserveAlpha(origInt, target, strength * 0.55F, true));
            }
            if (attribute == EnvironmentAttributes.WATER_FOG_COLOR && Boolean.TRUE.equals(mod.clearWater.getValue())) {
                int target = packRgb(mod.curWaterR, mod.curWaterG, mod.curWaterB);
                return (Value) Integer.valueOf(blendPreserveAlpha(origInt, target, strength * 0.75F, true));
            }
        }

        // --- Float Attributes (Fog Distances, Stars, Cloud Height, Ambient Light Floor) ---
        if (original instanceof Float origFloat) {
            if (attribute == EnvironmentAttributes.STAR_BRIGHTNESS) {
                float userStars = Mth.clamp(mod.starBrightness.getValue().floatValue(), 0.0F, 1.0F);
                return (Value) Float.valueOf(Math.max(origFloat, userStars));
            }
            if (attribute == EnvironmentAttributes.STAR_ANGLE && Boolean.TRUE.equals(mod.spinStars.getValue())) {
                float spun = (origFloat + mod.animSeconds * 0.08F) % (float) (Math.PI * 2.0D);
                return (Value) Float.valueOf(spun);
            }
            if (attribute == EnvironmentAttributes.CLOUD_HEIGHT) {
                return (Value) Float.valueOf(mod.cloudHeight.getValue().floatValue());
            }
            if (attribute == EnvironmentAttributes.SKY_LIGHT_FACTOR) {
                float boost = Mth.clamp(mod.brightnessBoost.getValue().floatValue(), 0.0F, 1.0F);
                if (boost > 0.001F) {
                    float floor = 0.20F + boost * 0.80F;
                    return (Value) Float.valueOf(Math.max(origFloat, floor));
                }
            }

            // Fog Distance Styling
            if (attribute == EnvironmentAttributes.FOG_START_DISTANCE
                    || attribute == EnvironmentAttributes.FOG_END_DISTANCE
                    || attribute == EnvironmentAttributes.SKY_FOG_END_DISTANCE
                    || attribute == EnvironmentAttributes.CLOUD_FOG_END_DISTANCE) {
                String style = mod.fogStyle.getValue();
                if ("Off".equalsIgnoreCase(style)) {
                    return (Value) Float.valueOf(Math.max(origFloat, 2048.0F));
                }
                if ("Clear".equalsIgnoreCase(style)) {
                    float mult = (attribute == EnvironmentAttributes.FOG_START_DISTANCE) ? 1.8F : 2.2F;
                    return (Value) Float.valueOf(Math.max(origFloat * mult, 320.0F));
                }
                if ("Cozy Mist".equalsIgnoreCase(style)) {
                    if (attribute == EnvironmentAttributes.FOG_START_DISTANCE) {
                        return (Value) Float.valueOf(24.0F);
                    }
                    return (Value) Float.valueOf(140.0F);
                }
                if ("Dense".equalsIgnoreCase(style)) {
                    if (attribute == EnvironmentAttributes.FOG_START_DISTANCE) {
                        return (Value) Float.valueOf(8.0F);
                    }
                    return (Value) Float.valueOf(72.0F);
                }
                // "Preset" mode: apply preset's balanced atmospheric distance unless Vanilla theme
                if (!vanilla) {
                    if (attribute == EnvironmentAttributes.FOG_START_DISTANCE) {
                        return (Value) Float.valueOf(mod.curFogStart);
                    }
                    return (Value) Float.valueOf(Math.max(origFloat, mod.curFogEnd));
                }
            }

            if (Boolean.TRUE.equals(mod.clearWater.getValue())) {
                if (attribute == EnvironmentAttributes.WATER_FOG_START_DISTANCE) {
                    return (Value) Float.valueOf(Math.max(origFloat, 24.0F));
                }
                if (attribute == EnvironmentAttributes.WATER_FOG_END_DISTANCE) {
                    return (Value) Float.valueOf(Math.max(origFloat, 192.0F));
                }
            }
        }

        return original;
    }

    // =========================================================================
    // Client-Side Time & Weather Hooks
    // =========================================================================
    public static boolean shouldOverrideTime() {
        Ambience mod = instance;
        if (mod == null || !mod.isEnabled()) return false;
        String mode = mod.timeMode.getValue();
        if ("Server".equalsIgnoreCase(mode)) return false;
        if ("Preset".equalsIgnoreCase(mode) && mod.isVanillaTheme()) return false;
        return true;
    }

    public static long getOverriddenDayTime(long originalDayTime) {
        Ambience mod = instance;
        if (mod == null || !mod.isEnabled()) return originalDayTime;
        mod.updateFrame();

        String mode = mod.timeMode.getValue();
        if ("Custom".equalsIgnoreCase(mode)) {
            return Math.round(mod.customTime.getValue());
        }
        if ("Cycle".equalsIgnoreCase(mode)) {
            return (long) mod.cycleTimeTicks;
        }
        if ("Preset".equalsIgnoreCase(mode)) {
            String t = mod.theme.getValue();
            if ("Vanilla".equalsIgnoreCase(t)) return originalDayTime;
            if ("Chroma".equalsIgnoreCase(t)) return 6000L;
            if ("Custom".equalsIgnoreCase(t)) return Math.round(mod.customTime.getValue());
            return mod.resolveStaticPreset(t).presetTime;
        }
        return originalDayTime;
    }

    public static boolean shouldOverrideWeather() {
        Ambience mod = instance;
        return mod != null && mod.isEnabled() && !"Server".equalsIgnoreCase(mod.weather.getValue());
    }

    public static float getOverriddenRainLevel(float original) {
        Ambience mod = instance;
        if (mod == null || !mod.isEnabled()) return original;
        String w = mod.weather.getValue();
        if ("Clear".equalsIgnoreCase(w)) return 0.0F;
        if ("Rain".equalsIgnoreCase(w) || "Snow".equalsIgnoreCase(w) || "Thunder".equalsIgnoreCase(w)) return 1.0F;
        return original;
    }

    public static float getOverriddenThunderLevel(float original) {
        Ambience mod = instance;
        if (mod == null || !mod.isEnabled()) return original;
        String w = mod.weather.getValue();
        if ("Thunder".equalsIgnoreCase(w)) return 1.0F;
        if ("Clear".equalsIgnoreCase(w) || "Rain".equalsIgnoreCase(w) || "Snow".equalsIgnoreCase(w)) return 0.0F;
        return original;
    }

    // =========================================================================
    // Zero-Allocation Color Math Helpers
    // =========================================================================
    private static int packRgb(float r, float g, float b) {
        int ri = Mth.clamp(Math.round(r), 0, 255);
        int gi = Mth.clamp(Math.round(g), 0, 255);
        int bi = Mth.clamp(Math.round(b), 0, 255);
        return (ri << 16) | (gi << 8) | bi;
    }

    private static int lerpRgb(int rgbA, int rgbB, float t) {
        float c = Mth.clamp(t, 0.0F, 1.0F);
        int rA = (rgbA >>> 16) & 0xFF;
        int gA = (rgbA >>> 8) & 0xFF;
        int bA = rgbA & 0xFF;
        int rB = (rgbB >>> 16) & 0xFF;
        int gB = (rgbB >>> 8) & 0xFF;
        int bB = rgbB & 0xFF;
        int r = Math.round(rA + (rB - rA) * c);
        int g = Math.round(gA + (gB - gA) * c);
        int b = Math.round(bA + (bB - bA) * c);
        return (r << 16) | (g << 8) | b;
    }

    private static int blendPreserveAlpha(int origColor, int targetRgb, float t, boolean forceOpaqueIfZeroAlpha) {
        int alpha = origColor & 0xFF000000;
        if (alpha == 0 && forceOpaqueIfZeroAlpha) {
            alpha = 0xFF000000;
        }
        int blendedRgb = lerpRgb(origColor & 0xFFFFFF, targetRgb & 0xFFFFFF, t);
        return alpha | blendedRgb;
    }

    private static int hsbToRgb(float hue, float sat, float bri) {
        float h = (hue - (float) Math.floor(hue)) * 6.0F;
        int sector = (int) h;
        float f = h - sector;
        float p = bri * (1.0F - sat);
        float q = bri * (1.0F - sat * f);
        float t = bri * (1.0F - sat * (1.0F - f));
        float r, g, b;
        switch (sector % 6) {
            case 0 -> { r = bri; g = t; b = p; }
            case 1 -> { r = q; g = bri; b = p; }
            case 2 -> { r = p; g = bri; b = t; }
            case 3 -> { r = p; g = q; b = bri; }
            case 4 -> { r = t; g = p; b = bri; }
            default -> { r = bri; g = p; b = q; }
        }
        return packRgb(r * 255.0F, g * 255.0F, b * 255.0F);
    }

    // =========================================================================
    // Compatibility Bridges for Existing Attrium World/Fog/Weather/Light Mixins
    // =========================================================================
    public Biome.Precipitation getForcedPrecipitation() {
        if (!isEnabled()) {
            return null;
        }
        String w = weather.getValue();
        if ("Clear".equalsIgnoreCase(w)) {
            return Biome.Precipitation.NONE;
        }
        if ("Snow".equalsIgnoreCase(w)) {
            return Biome.Precipitation.SNOW;
        }
        if ("Rain".equalsIgnoreCase(w) || "Thunder".equalsIgnoreCase(w)) {
            return Biome.Precipitation.RAIN;
        }
        return null;
    }

    public long getInternalTime() {
        updateFrame();
        return getOverriddenDayTime(12000L);
    }

    public void syncWeather(ClientLevel level, ClientLevel.ClientLevelData clientLevelData) {
        if (!isEnabled() || level == null) {
            return;
        }
        String w = weather.getValue();
        if ("Server".equalsIgnoreCase(w)) {
            return;
        }
        if ("Clear".equalsIgnoreCase(w)) {
            level.setRainLevel(0.0F);
            level.setThunderLevel(0.0F);
        } else if ("Rain".equalsIgnoreCase(w) || "Snow".equalsIgnoreCase(w)) {
            level.setRainLevel(1.0F);
            level.setThunderLevel(0.0F);
        } else if ("Thunder".equalsIgnoreCase(w)) {
            level.setRainLevel(1.0F);
            level.setThunderLevel(1.0F);
        }
    }

    public boolean hasCustomFog() {
        return isEnabled() && !isVanillaTheme();
    }

    public int getCustomFogColor() {
        updateFrame();
        return 0xFF000000 | packRgb(curFogR, curFogG, curFogB);
    }

    public float getSaturationFactor() {
        if (!isEnabled()) {
            return 1.0F;
        }
        return Mth.clamp(saturation.getValue().floatValue(), 0.50F, 1.80F);
    }

    public float getBrightnessValue() {
        if (!isEnabled()) {
            return 0.0F;
        }
        return Mth.clamp(brightnessBoost.getValue().floatValue(), 0.0F, 1.0F);
    }

}
