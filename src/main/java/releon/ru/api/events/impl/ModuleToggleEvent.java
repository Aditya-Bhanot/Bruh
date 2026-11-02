package releon.ru.api.events.impl;

import releon.ru.api.events.Event;
import releon.ru.api.module.Module;

public final class ModuleToggleEvent implements Event {
    private final Module module;
    private final boolean enabled;

    public ModuleToggleEvent(Module module, boolean enabled) {
        this.module = module;
        this.enabled = enabled;
    }

    public Module getModule() {
        return module;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
