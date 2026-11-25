package attrium.ru.api.module.impl.player;

import net.minecraft.client.Minecraft;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.ModuleCategory;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.ModeSetting;
import attrium.ru.api.settings.impl.NumberSetting;
import attrium.ru.manager.FreelookManager;

/**
 * 100% Legit Freelook Module (Minecraft 1.21.11 Official Mojang Mappings).
 * Supports Hold-to-Freelook (default) and Toggle-on-Bind modes with zero server packet changes.
 */
public class FreeLook extends Module {
    private static volatile FreeLook instance;

    private final ModeSetting activationMode = register(new ModeSetting(
            "Mode",
            "Hold activates Freelook while holding the bound key; Toggle switches ON/OFF.",
            "Hold",
            "Hold", "Toggle"
    ));

    private final BooleanSetting autoThirdPerson = register(new BooleanSetting(
            "Auto Third Person",
            "Automatically switches to third-person back view while Freelook is active.",
            true
    ));

    private final BooleanSetting invertPitch = register(new BooleanSetting(
            "Invert Pitch",
            "Inverts vertical camera look while orbiting.",
            false
    ));

    private final NumberSetting sensitivity = register(new NumberSetting(
            "Sensitivity",
            "Camera orbit sensitivity multiplier (0.25 - 2.0).",
            1.00D, 0.25D, 2.00D, 0.05D
    ));

    public FreeLook() {
        super("Freelook", "Orbits camera freely without altering movement or rotations", ModuleCategory.VISUAL);
        instance = this;
        syncSettingsToManager();
    }

    public static FreeLook getInstance() {
        return instance;
    }

    private void syncSettingsToManager() {
        FreelookManager.setHoldMode("Hold".equalsIgnoreCase(activationMode.getValue()));
        FreelookManager.setAutoThirdPerson(Boolean.TRUE.equals(autoThirdPerson.getValue()));
        FreelookManager.setInvertPitch(Boolean.TRUE.equals(invertPitch.getValue()));
        FreelookManager.setSensitivity(sensitivity.getValue().floatValue());
    }

    @Override
    protected void onEnable() {
        syncSettingsToManager();
        FreelookManager.activate(Minecraft.getInstance());
    }

    @Override
    protected void onDisable() {
        syncSettingsToManager();
        FreelookManager.deactivate(Minecraft.getInstance());
    }
}
