package releon.ru.api.module.impl.visual;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.world.level.biome.Biome;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ColorSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.NumberSetting;

import java.awt.Color;

public class Ambience extends Module {
    private static Ambience instance;

    private final ModeSetting mode = register(new ModeSetting("Mode", "World time mode.", "Day", "Day", "Midday", "Night", "Midnight", "Custom"));
    private final NumberSetting customTime = register(new NumberSetting("Time", "Custom day time.", 1000.0, 0.0, 24000.0, 100.0));
    private final ModeSetting weather = register(new ModeSetting("Weather", "Client weather override.", "Sunny", "Sunny", "Rain", "Thunder", "Snow"));
    private final NumberSetting saturation = register(new NumberSetting("Saturation", "World saturation multiplier offset.", 0.0, -1.0, 1.0, 0.05));
    private final NumberSetting brightness = register(new NumberSetting("Brightness", "World brightness offset.", 0.0, -1.0, 1.0, 0.05));
    private final BooleanSetting customFog = register(new BooleanSetting("Custom Fog", "Use custom fog color.", false));
    private final ColorSetting customFogColor = register(new ColorSetting("Fog Color", "Custom fog color.", new Color(200, 214, 229, 255)));

    private boolean weatherOverrideActive;
    private boolean cachedServerRaining;
    private float cachedServerRainLevel;
    private float cachedServerThunderLevel;
    private ClientLevel weatherSnapshotLevel;

    public Ambience() {
        super("Ambience", "Changes time, weather and world atmosphere.", ModuleCategory.VISUAL);
        customTime.visibleWhen(() -> mode.is("Custom"));
        customFogColor.visibleWhen(customFog::getValue);
        instance = this;
    }

    public static Ambience getInstance() {
        return instance;
    }

    public float getBrightnessValue() {
        return brightness.getFloat();
    }

    public float getSaturationFactor() {
        return Math.clamp(1.0f + saturation.getFloat(), 0.0f, 2.0f);
    }

    public boolean hasCustomFog() {
        return isEnabled() && customFog.getValue();
    }

    public int getCustomFogColor() {
        return customFogColor.getValue().getRGB();
    }

    public long getInternalTime() {
        if (mode.is("Day")) {
            return 1000L;
        }
        if (mode.is("Midday")) {
            return 6000L;
        }
        if (mode.is("Night")) {
            return 13000L;
        }
        if (mode.is("Midnight")) {
            return 18000L;
        }
        return (long) customTime.getValue().doubleValue();
    }

    public void syncWeather(ClientLevel level, ClientLevel.ClientLevelData levelData) {
        if (level == null || levelData == null) {
            clearWeatherSnapshot();
            return;
        }

        if (weatherSnapshotLevel != level) {
            clearWeatherSnapshot();
        }

        if (!weatherOverrideActive) {
            cachedServerRaining = levelData.isRaining();
            cachedServerRainLevel = level.getRainLevel(1.0f);
            cachedServerThunderLevel = level.getThunderLevel(1.0f);
            weatherOverrideActive = true;
            weatherSnapshotLevel = level;
        }

        boolean raining = shouldForcePrecipitation();
        levelData.setRaining(raining);
        level.setRainLevel(raining ? 1.0f : 0.0f);
        level.setThunderLevel(weather.is("Thunder") ? 1.0f : 0.0f);
    }

    public Biome.Precipitation getForcedPrecipitation() {
        if (!isEnabled()) {
            return null;
        }
        if (weather.is("Snow")) {
            return Biome.Precipitation.SNOW;
        }
        if (weather.is("Rain") || weather.is("Thunder")) {
            return Biome.Precipitation.RAIN;
        }
        return Biome.Precipitation.NONE;
    }

    public boolean shouldForceSnow() {
        return getForcedPrecipitation() == Biome.Precipitation.SNOW;
    }

    @Override
    protected void onDisable() {
        if (mc.level instanceof ClientLevel level) {
            restoreWeather(level, level.getLevelData());
        } else {
            clearWeatherSnapshot();
        }
    }

    @SubscribeEvent
    private void onPacket(PacketEvent event) {
        if (!event.isReceive()) {
            return;
        }
        if (event.getPacket() instanceof ClientboundSetTimePacket && isEnabled()) {
            event.cancel();
            return;
        }
        if (!(event.getPacket() instanceof ClientboundGameEventPacket packet)) {
            return;
        }
        ClientboundGameEventPacket.Type type = packet.getEvent();
        if (!isWeatherPacket(type)) {
            return;
        }
        updateCachedWeather(packet);
        if (isEnabled()) {
            event.cancel();
        }
    }

    private boolean shouldForcePrecipitation() {
        return weather.is("Rain") || weather.is("Thunder") || weather.is("Snow");
    }

    private void restoreWeather(ClientLevel level, ClientLevel.ClientLevelData levelData) {
        if (!weatherOverrideActive) {
            return;
        }
        levelData.setRaining(cachedServerRaining);
        level.setRainLevel(cachedServerRainLevel);
        level.setThunderLevel(cachedServerThunderLevel);
        clearWeatherSnapshot();
    }

    private void clearWeatherSnapshot() {
        weatherOverrideActive = false;
        weatherSnapshotLevel = null;
    }

    private void updateCachedWeather(ClientboundGameEventPacket packet) {
        ClientboundGameEventPacket.Type type = packet.getEvent();
        if (type == ClientboundGameEventPacket.START_RAINING) {
            cachedServerRaining = true;
            cachedServerRainLevel = Math.max(cachedServerRainLevel, 1.0f);
            return;
        }
        if (type == ClientboundGameEventPacket.STOP_RAINING) {
            cachedServerRaining = false;
            cachedServerRainLevel = 0.0f;
            cachedServerThunderLevel = 0.0f;
            return;
        }
        if (type == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE) {
            cachedServerRainLevel = Math.clamp(packet.getParam(), 0.0f, 1.0f);
            cachedServerRaining = cachedServerRainLevel > 0.0001f;
            return;
        }
        if (type == ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE) {
            cachedServerThunderLevel = Math.clamp(packet.getParam(), 0.0f, 1.0f);
        }
    }

    private static boolean isWeatherPacket(ClientboundGameEventPacket.Type type) {
        return type == ClientboundGameEventPacket.START_RAINING
                || type == ClientboundGameEventPacket.STOP_RAINING
                || type == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE
                || type == ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE;
    }
}
