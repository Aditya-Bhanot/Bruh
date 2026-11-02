package releon.ru.utils.repository.macro;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.KeyEvent;
import releon.ru.utils.repository.RepositoryStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public final class MacroRepository {
    private static final MacroRepository INSTANCE = new MacroRepository();
    private final List<Macro> macroList = new ArrayList<>();
    private final Minecraft mc = Minecraft.getInstance();

    private MacroRepository() {
    }

    public static MacroRepository getInstance() {
        return INSTANCE;
    }

    public void load() {
        macroList.clear();
        JsonObject root = RepositoryStorage.readObject("macros.releon");
        JsonArray array = root.has("macros") && root.get("macros").isJsonArray() ? root.getAsJsonArray("macros") : new JsonArray();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String name = object.has("name") ? object.get("name").getAsString() : "";
            String message = object.has("message") ? object.get("message").getAsString() : "";
            int key = object.has("key") ? object.get("key").getAsInt() : GLFW.GLFW_KEY_UNKNOWN;
            if (!name.isBlank() && !message.isBlank()) {
                macroList.add(new Macro(name, message, key));
            }
        }
    }

    public void save() {
        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        for (Macro macro : macroList) {
            JsonObject object = new JsonObject();
            object.addProperty("name", macro.name());
            object.addProperty("message", macro.message());
            object.addProperty("key", macro.key());
            array.add(object);
        }
        root.add("macros", array);
        RepositoryStorage.write("macros.releon", root);
    }

    public void addMacro(String name, String message, int key) {
        macroList.add(new Macro(name, message, key));
    }

    public void addMacroAndSave(String name, String message, int key) {
        addMacro(name, message, key);
        save();
    }

    public boolean hasMacro(String name) {
        return macroList.stream().anyMatch(macro -> macro.name().equalsIgnoreCase(name));
    }

    public Optional<Macro> getMacro(String name) {
        return macroList.stream().filter(macro -> macro.name().equalsIgnoreCase(name)).findFirst();
    }

    public void deleteMacro(String name) {
        macroList.removeIf(macro -> macro.name().equalsIgnoreCase(name));
    }

    public void deleteMacroAndSave(String name) {
        deleteMacro(name);
        save();
    }

    public void clearList() {
        macroList.clear();
    }

    public void clearListAndSave() {
        clearList();
        save();
    }

    public int size() {
        return macroList.size();
    }

    public List<Macro> getMacroList() {
        return macroList;
    }

    public List<String> getMacroNames() {
        return macroList.stream().map(Macro::name).collect(Collectors.toList());
    }

    public void setMacros(List<Macro> macros) {
        macroList.clear();
        if (macros != null) {
            macroList.addAll(macros);
        }
    }

    @SubscribeEvent
    private void onKey(KeyEvent event) {
        if (mc.player == null || mc.screen != null || event.action() != GLFW.GLFW_PRESS) {
            return;
        }
        macroList.stream()
                .filter(macro -> macro.key() == event.key())
                .findFirst()
                .ifPresent(macro -> {
                    String message = macro.message();
                    if (message.startsWith("/")) {
                        mc.player.connection.sendCommand(message.substring(1));
                    } else {
                        mc.player.connection.sendChat(message);
                    }
                });
    }
}
