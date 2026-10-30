package releon.ru.api.module;

public enum ModuleCategory {
    COMBAT("Combat", "r"),
    MOVEMENT("Movement", "j"),
    VISUAL("Visual", "a"),
    PLAYER("Player", "t"),
    MISC("Misc", "d");

    private final String displayName;
    private final String icon;

    ModuleCategory(String displayName, String icon) {
        this.displayName = displayName;
        this.icon = icon;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String icon() {
        return icon;
    }
}
