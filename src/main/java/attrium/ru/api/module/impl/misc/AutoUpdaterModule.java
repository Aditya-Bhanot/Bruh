package attrium.ru.api.module.impl.misc;

import attrium.ru.api.module.Module;
import attrium.ru.api.module.ModuleCategory;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.NumberSetting;
import attrium.ru.manager.AutoUpdater;
import net.minecraft.client.Minecraft;

public class AutoUpdaterModule extends Module {
    private final BooleanSetting autoCheck = register(new BooleanSetting(
            "Auto Check",
            "Automatically check for client updates in the background on a timer.",
            true
    ));

    private final NumberSetting checkIntervalMin = register(new NumberSetting(
            "Interval (Min)",
            "Minutes between automatic background update checks.",
            15.0, 1.0, 120.0, 1.0
    ));

    private final BooleanSetting autoDownload = register(new BooleanSetting(
            "Auto Download",
            "Automatically download & stage updates so the Restart button lights up immediately.",
            true
    ));

    private final BooleanSetting notifyOnUpdate = register(new BooleanSetting(
            "Notify on Update",
            "Send an in-game toast notification when a new update is available or ready.",
            true
    ));

    public AutoUpdaterModule() {
        super("Auto Updater", "Schedules automatic update checks, notifications, and Restart button sync.", ModuleCategory.MISC);
        checkIntervalMin.visibleWhen(() -> Boolean.TRUE.equals(autoCheck.getValue()));
    }

    @Override
    protected void onEnable() {
        syncToUpdater();
        AutoUpdater.tickPeriodicCheck();
    }

    @Override
    protected void onDisable() {
        AutoUpdater.syncSettings(false, getIntervalMinutes(), isAutoDownload(), isNotifyOnUpdate());
    }

    @Override
    public void onTick(Minecraft client) {
        if (client == null) {
            return;
        }
        syncToUpdater();
        AutoUpdater.tickPeriodicCheck();
    }

    private void syncToUpdater() {
        AutoUpdater.syncSettings(
                Boolean.TRUE.equals(autoCheck.getValue()),
                getIntervalMinutes(),
                isAutoDownload(),
                isNotifyOnUpdate()
        );
    }

    private int getIntervalMinutes() {
        return Math.max(1, Math.min(120, checkIntervalMin.getValue().intValue()));
    }

    private boolean isAutoDownload() {
        return Boolean.TRUE.equals(autoDownload.getValue());
    }

    private boolean isNotifyOnUpdate() {
        return Boolean.TRUE.equals(notifyOnUpdate.getValue());
    }
}
