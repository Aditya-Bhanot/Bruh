package releon.ru.api.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import releon.ru.api.config.exception.ConfigException;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.ModuleManager;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigManager {
    public static final String ROOT_FOLDER = "releon";
    public static final String USER_CONFIG_FOLDER = "config";
    public static final String SYSTEM_FOLDER = "system";
    public static final String CONFIG_EXTENSION = ".releon";
    private static final long AUTO_SAVE_DELAY_MS = 1000L;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path systemDirectory;
    private final Path modulesFile;
    private final ModuleConfigStorage moduleStorage;
    private boolean dirty;
    private long dirtySince;

    public ConfigManager(ModuleManager moduleManager) {
        this.systemDirectory = systemDirectory();
        this.modulesFile = systemDirectory.resolve("modules" + CONFIG_EXTENSION);
        this.moduleStorage = new ModuleConfigStorage(moduleManager);
    }

    public static Path rootDirectory() {
        return FabricLoader.getInstance().getGameDir().resolve(ROOT_FOLDER);
    }

    public static Path userConfigDirectory() {
        return rootDirectory().resolve(USER_CONFIG_FOLDER);
    }

    public static Path systemDirectory() {
        return rootDirectory().resolve(SYSTEM_FOLDER);
    }

    public static Path legacyDirectory() {
        return FabricLoader.getInstance().getConfigDir().resolve("releon");
    }

    public void init() {
        try {
            Files.createDirectories(userConfigDirectory());
            Files.createDirectories(systemDirectory);
        } catch (IOException exception) {
            throw new ConfigException("Failed to create config directories under: " + rootDirectory(), exception);
        }
    }

    public void loadAll() {
        loadModules();
        dirty = false;
    }

    public void saveAll() {
        saveModules();
        dirty = false;
    }

    public void markDirty() {
        if (!dirty) {
            dirtySince = System.currentTimeMillis();
        }
        dirty = true;
    }

    @SubscribeEvent
    private void onTick(TickEvent.Post event) {
        if (dirty && System.currentTimeMillis() - dirtySince >= AUTO_SAVE_DELAY_MS) {
            saveAll();
        }
    }

    private void loadModules() {
        Path file = resolveModulesFileForRead();
        if (!Files.exists(file)) {
            saveModules();
            return;
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            moduleStorage.load(root);
        } catch (Exception exception) {
            throw new ConfigException("Failed to load module config: " + file, exception);
        }
    }

    private void saveModules() {
        try {
            Files.createDirectories(systemDirectory);
            try (Writer writer = Files.newBufferedWriter(modulesFile, StandardCharsets.UTF_8)) {
                gson.toJson(moduleStorage.save(), writer);
            }
        } catch (IOException exception) {
            throw new ConfigException("Failed to save module config: " + modulesFile, exception);
        }
    }

    private Path resolveModulesFileForRead() {
        if (Files.exists(modulesFile)) {
            return modulesFile;
        }

        Path legacyJson = legacyDirectory().resolve("modules.json");
        if (Files.exists(legacyJson)) {
            return legacyJson;
        }

        return modulesFile;
    }
}
