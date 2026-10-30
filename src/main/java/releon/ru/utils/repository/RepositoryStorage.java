package releon.ru.utils.repository;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import releon.ru.api.config.ConfigManager;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class RepositoryStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private RepositoryStorage() {
    }

    public static Path root() {
        return ConfigManager.systemDirectory();
    }

    public static List<String> loadStringList(String fileName, String key) {
        List<String> result = new ArrayList<>();
        Path file = resolveForRead(fileName);
        if (!Files.exists(file)) {
            return result;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject object = JsonParser.parseReader(reader).getAsJsonObject();
            JsonArray array = object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray();
            for (JsonElement element : array) {
                if (element.isJsonPrimitive()) {
                    result.add(element.getAsString());
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    public static void saveStringList(String fileName, String key, List<String> values) {
        JsonObject object = new JsonObject();
        JsonArray array = new JsonArray();
        if (values != null) {
            for (String value : values) {
                array.add(value);
            }
        }
        object.add(key, array);
        write(fileName, object);
    }

    public static JsonObject readObject(String fileName) {
        Path file = resolveForRead(fileName);
        if (!Files.exists(file)) {
            return new JsonObject();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    public static void write(String fileName, JsonObject object) {
        try {
            Files.createDirectories(root());
            try (Writer writer = Files.newBufferedWriter(resolveForWrite(fileName), StandardCharsets.UTF_8)) {
                GSON.toJson(object, writer);
            }
        } catch (Exception ignored) {
        }
    }

    public static String releonFile(String baseName) {
        String normalized = baseName == null ? "" : baseName.trim();
        if (normalized.endsWith(ConfigManager.CONFIG_EXTENSION)) {
            return normalized;
        }
        int dot = normalized.lastIndexOf('.');
        if (dot >= 0) {
            normalized = normalized.substring(0, dot);
        }
        return normalized + ConfigManager.CONFIG_EXTENSION;
    }

    private static Path resolveForWrite(String fileName) {
        return root().resolve(releonFile(fileName));
    }

    private static Path resolveForRead(String fileName) {
        Path releonFile = resolveForWrite(fileName);
        if (Files.exists(releonFile)) {
            return releonFile;
        }
        Path legacyFile = root().resolve(fileName);
        if (Files.exists(legacyFile)) {
            return legacyFile;
        }
        Path oldConfigFile = ConfigManager.legacyDirectory().resolve(fileName);
        if (Files.exists(oldConfigFile)) {
            return oldConfigFile;
        }
        Path oldConfigReleonFile = ConfigManager.legacyDirectory().resolve(releonFile(fileName));
        if (Files.exists(oldConfigReleonFile)) {
            return oldConfigReleonFile;
        }
        return releonFile;
    }
}
