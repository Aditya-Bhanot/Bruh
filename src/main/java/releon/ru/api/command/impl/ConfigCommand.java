package releon.ru.api.command.impl;

import net.minecraft.ChatFormatting;
import releon.ru.api.command.Command;
import releon.ru.api.config.ConfigManager;
import releon.ru.manager.Manager;
import releon.ru.utils.repository.RepositoryStorage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class ConfigCommand extends Command {
    public ConfigCommand() {
        super("config", "Configuration management", "cfg");
    }

    @Override
    public void execute(String label, String[] args) {
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "save";
        switch (action) {
            case "save" -> {
                if (Manager.getInstance() != null && Manager.getInstance().getConfigManager() != null) {
                    Manager.getInstance().getConfigManager().saveAll();
                }
                logDirect("Config saved.", ChatFormatting.GREEN);
            }
            case "dir" -> {
                try {
                    openDirectory(ConfigManager.userConfigDirectory());
                    logDirect("Config folder opened.", ChatFormatting.GREEN);
                } catch (IOException exception) {
                    logDirect("Failed to open config folder: " + exception.getMessage(), ChatFormatting.RED);
                }
            }
            case "system" -> {
                try {
                    openDirectory(RepositoryStorage.root());
                    logDirect("System folder opened.", ChatFormatting.GREEN);
                } catch (IOException exception) {
                    logDirect("Failed to open system folder: " + exception.getMessage(), ChatFormatting.RED);
                }
            }
            default -> {
                logDirect("Usage: config save | config dir | config system");
            }
        }
    }

    @Override
    public Stream<String> tabComplete(String label, String[] args) {
        return args.length == 1 ? Stream.of("save", "dir", "system").filter(value -> value.startsWith(args[0].toLowerCase())) : Stream.empty();
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList("Saves current config or opens config directories.", "Usage:", "> config save", "> config dir", "> config system");
    }

    private void openDirectory(Path directory) throws IOException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        ProcessBuilder builder = os.contains("win")
                ? new ProcessBuilder("explorer.exe", directory.toAbsolutePath().toString())
                : os.contains("mac")
                ? new ProcessBuilder("open", directory.toAbsolutePath().toString())
                : new ProcessBuilder("xdg-open", directory.toAbsolutePath().toString());
        builder.start();
    }
}
