package attrium.ru.api.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import attrium.ru.api.config.exception.ConfigException;
import attrium.ru.api.events.annotation.SubscribeEvent;
import attrium.ru.api.events.impl.TickEvent;
import attrium.ru.api.module.ModuleManager;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigManager {
    public static final String ROOT_FOLDER = "attrium";
    public static final String USER_CONFIG_FOLDER = "config";
    public static final String SYSTEM_FOLDER = "system";
    public static final String CONFIG_EXTENSION = ".attrium";
    private static final long AUTO_SAVE_DELAY_MS = 250L;

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Path systemDirectory;
    private final Path modulesFile;
    private final ModuleConfigStorage moduleStorage;
    private boolean dirty;
    private long dirtySince;

    public ConfigManager(ModuleManager moduleManager) {
        this.systemDirectory = systemDirectory();
        this.modulesFile = systemDirectory.resolve("acc_storage.json");
        this.moduleStorage = new ModuleConfigStorage(moduleManager);
    }

    public static Path rootDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }

    public static Path userConfigDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }

    public static Path systemDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }

    public static Path legacyDirectory() {
        return FabricLoader.getInstance().getConfigDir().resolve("attrium");
    }

    public void init() {
        try {
            Files.createDirectories(userConfigDirectory());
            Files.createDirectories(systemDirectory);
        } catch (IOException exception) {
            throw new ConfigException("Failed to create config directories under: " + rootDirectory(), exception);
        }
        try {
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(client -> saveAll());
        } catch (Throwable ignored) {
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                saveAll();
            } catch (Throwable ignored) {
            }
        }, "Attrium-Config-ShutdownSave"));
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
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            saveAll();
        }
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
            return;
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            moduleStorage.load(root);
        } catch (Exception exception) {
            System.err.println("[Attrium ConfigManager] Skipped incompatible storage file " + file + ": " + exception);
        }
    }

    private void saveModules() {
        try {
            attrium.ru.screens.GlassClickGuiScreen.trySaveConfigStatic();
        } catch (Throwable ignored) {
        }
        try {
            Files.createDirectories(systemDirectory);
            Path tmpFile = modulesFile.resolveSibling(modulesFile.getFileName().toString() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmpFile, StandardCharsets.UTF_8)) {
                gson.toJson(moduleStorage.save(), writer);
            }
            try {
                Files.move(tmpFile, modulesFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicErr) {
                Files.move(tmpFile, modulesFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            System.err.println("[Attrium ConfigManager] Failed to save module storage " + modulesFile + ": " + exception);
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
