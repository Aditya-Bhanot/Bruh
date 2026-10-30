package releon.ru.api.module;

import net.minecraft.client.Minecraft;
import releon.ru.IMinecraft;
import releon.ru.api.events.bus.EventBus;
import releon.ru.api.events.impl.ModuleToggleEvent;
import releon.ru.api.module.exception.ModuleException;
import releon.ru.api.settings.Setting;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.api.settings.impl.BindSetting;
import releon.ru.api.drag.impl.Notifications;
import releon.ru.manager.Manager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class Module implements IMinecraft {
    private String name;
    private String description;
    private ModuleCategory category;
    private final List<Setting<?>> settings = new ArrayList<>();
    private final List<Setting<?>> settingsView = Collections.unmodifiableList(settings);
    private final List<ModuleStateListener> listeners = new ArrayList<>();
    private final BindSetting bindSetting = new BindSetting("Bind", "Module key bind.", KeyBind.NONE, false);
    private EventBus eventBus;
    private boolean enabled;
    private boolean hidden;
    private boolean starred;

    protected Module() {
        bindSetting.addListener((changedSetting, oldValue, newValue) -> notifyChanged());
    }

    protected Module(String name, String description, ModuleCategory category) {
        this();
        configure(name, description, category);
    }

    final void configure(String name, String description, ModuleCategory category) {
        if (name == null || name.isBlank()) {
            throw new ModuleException("Module name cannot be empty");
        }
        this.name = name;
        this.description = description == null ? "" : description;
        this.category = category == null ? ModuleCategory.MISC : category;
    }

    public final String getName() {
        return name;
    }

    public final String getDescription() {
        return description;
    }

    public final ModuleCategory getCategory() {
        return category;
    }

    public final List<Setting<?>> getSettings() {
        return settingsView;
    }

    public final BindSetting getBindSetting() {
        return bindSetting;
    }

    public final KeyBind getBind() {
        return bindSetting.getValue();
    }

    public final void setBind(KeyBind bind) {
        bindSetting.setValue(bind);
    }

    public final boolean isEnabled() {
        return enabled;
    }

    public final void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        if (enabled) {
            if (eventBus != null) {
                eventBus.register(this);
            }
            onEnable();
        } else {
            onDisable();
            if (eventBus != null) {
                eventBus.unregister(this);
            }
        }
        Manager.postEvent(new ModuleToggleEvent(this, enabled));
        Notifications.push("Notification", "Модуль " + name + (enabled ? " включён" : " выключён"));
        notifyChanged();
    }

    public final void toggle() {
        setEnabled(!enabled);
    }

    public final boolean isHidden() {
        return hidden;
    }

    public final void setHidden(boolean hidden) {
        if (this.hidden == hidden) {
            return;
        }
        this.hidden = hidden;
        notifyChanged();
    }

    public final boolean isStarred() {
        return starred;
    }

    public final void setStarred(boolean starred) {
        if (this.starred == starred) {
            return;
        }
        this.starred = starred;
        notifyChanged();
    }

    public final void addStateListener(ModuleStateListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public final void initialize(EventBus eventBus) {
        this.eventBus = eventBus;
        if (enabled && eventBus != null) {
            eventBus.register(this);
        }
    }

    public void onTick(Minecraft client) {
    }

    protected void onEnable() {
    }

    protected void onDisable() {
    }

    protected final <S extends Setting<?>> S register(S setting) {
        if (setting == null) {
            throw new ModuleException("Cannot register null setting in " + name);
        }
        settings.add(setting);
        setting.addListener((changedSetting, oldValue, newValue) -> notifyChanged());
        return setting;
    }

    private void notifyChanged() {
        for (ModuleStateListener listener : listeners) {
            listener.onChanged(this);
        }
    }
}
