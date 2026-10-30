package releon.ru.api.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleManager;
import releon.ru.api.settings.Setting;
import releon.ru.api.settings.bind.KeyBind;

public final class ModuleConfigStorage {
    private final ModuleManager moduleManager;

    public ModuleConfigStorage(ModuleManager moduleManager) {
        this.moduleManager = moduleManager;
    }

    public JsonObject save() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject modules = new JsonObject();
        for (Module module : moduleManager.getModules()) {
            modules.add(module.getName(), saveModule(module));
        }
        root.add("modules", modules);
        return root;
    }

    public void load(JsonObject root) {
        if (root == null || !root.has("modules") || !root.get("modules").isJsonObject()) {
            return;
        }

        JsonObject modules = root.getAsJsonObject("modules");
        for (String moduleName : modules.keySet()) {
            moduleManager.getByName(moduleName).ifPresent(module -> loadModule(module, modules.getAsJsonObject(moduleName)));
        }
    }

    private JsonObject saveModule(Module module) {
        JsonObject object = new JsonObject();
        object.addProperty("enabled", module.isEnabled());
        object.addProperty("hidden", module.isHidden());
        object.addProperty("starred", module.isStarred());
        object.add("bind", module.getBind().toJson());

        JsonObject settings = new JsonObject();
        for (Setting<?> setting : module.getSettings()) {
            if (setting.isPersistent()) {
                settings.add(setting.getName(), setting.toJson());
            }
        }
        object.add("settings", settings);
        return object;
    }

    private void loadModule(Module module, JsonObject object) {
        if (object == null) {
            return;
        }

        if (object.has("bind")) {
            module.setBind(KeyBind.fromJson(object.get("bind")));
        }
        if (object.has("hidden")) {
            module.setHidden(object.get("hidden").getAsBoolean());
        }
        if (object.has("starred")) {
            module.setStarred(object.get("starred").getAsBoolean());
        }
        if (object.has("settings") && object.get("settings").isJsonObject()) {
            JsonObject settings = object.getAsJsonObject("settings");
            for (Setting<?> setting : module.getSettings()) {
                JsonElement saved = settings.get(setting.getName());
                if (saved != null) {
                    setting.fromJson(saved);
                }
            }
        }
        if (object.has("enabled")) {
            module.setEnabled(object.get("enabled").getAsBoolean());
        }
    }
}
