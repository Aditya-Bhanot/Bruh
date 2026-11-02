package releon.ru.api.command.impl;

import net.minecraft.ChatFormatting;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.command.Command;
import releon.ru.api.command.helpers.TabCompleteHelper;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleManager;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.manager.Manager;
import releon.ru.utils.string.KeyHelper;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class BindCommand extends Command {
    public BindCommand() {
        super("bind", "Manage module binds", "b");
    }

    @Override
    public void execute(String label, String[] args) {
        ModuleManager modules = Manager.getModules();
        if (modules == null) {
            logDirect("Module manager is unavailable.", ChatFormatting.RED);
            return;
        }

        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "set", "add" -> setBind(modules, args);
            case "remove", "del", "delete" -> clearBind(modules, args);
            case "clear" -> clearAll(modules);
            case "list" -> listBinds(modules);
            default -> logDirect("Usage: bind set <module> <key> | bind remove <module> | bind list | bind clear");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, String[] args) {
        ModuleManager modules = Manager.getModules();
        if (args.length == 1) {
            return new TabCompleteHelper().append("set", "remove", "list", "clear").sortAlphabetically().filterPrefix(args[0]).stream();
        }
        if (modules == null) {
            return Stream.empty();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("remove"))) {
            return new TabCompleteHelper()
                    .append(modules.getModules().stream().map(Module::getName).toArray(String[]::new))
                    .filterPrefix(args[1])
                    .stream();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            return new TabCompleteHelper().append(KeyHelper.getAllKeyNames()).filterPrefix(args[2]).stream();
        }
        return Stream.empty();
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Manages module key binds.",
                "Usage:",
                "> bind set <module> <key>",
                "> bind remove <module>",
                "> bind list",
                "> bind clear"
        );
    }

    private void setBind(ModuleManager modules, String[] args) {
        if (args.length < 3) {
            logDirect("Usage: bind set <module> <key>", ChatFormatting.RED);
            return;
        }

        Module module = findModule(modules, args[1]);
        if (module == null) {
            logDirect("Module not found: " + args[1], ChatFormatting.RED);
            return;
        }

        int code = KeyHelper.getKeyCode(args[2]);
        if (code == GLFW.GLFW_KEY_UNKNOWN || code < 0) {
            logDirect("Unknown key: " + args[2], ChatFormatting.RED);
            return;
        }

        module.setBind(isMouseName(args[2]) ? KeyBind.mouse(code) : KeyBind.keyboard(code));
        saveConfig();
        logDirect(module.getName() + " bound to " + module.getBind().getDisplayName(), ChatFormatting.GREEN);
    }

    private void clearBind(ModuleManager modules, String[] args) {
        if (args.length < 2) {
            logDirect("Usage: bind remove <module>", ChatFormatting.RED);
            return;
        }

        Module module = findModule(modules, args[1]);
        if (module == null) {
            logDirect("Module not found: " + args[1], ChatFormatting.RED);
            return;
        }

        module.setBind(KeyBind.NONE);
        saveConfig();
        logDirect(module.getName() + " bind removed.", ChatFormatting.GREEN);
    }

    private void clearAll(ModuleManager modules) {
        for (Module module : modules.getModules()) {
            module.setBind(KeyBind.NONE);
        }
        saveConfig();
        logDirect("All binds removed.", ChatFormatting.GREEN);
    }

    private void listBinds(ModuleManager modules) {
        List<Module> bound = modules.getModules().stream()
                .filter(module -> module.getBind().isBound())
                .sorted(Comparator.comparing(Module::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        if (bound.isEmpty()) {
            logDirect("No module binds.", ChatFormatting.RED);
            return;
        }

        for (Module module : bound) {
            logDirect(module.getName() + " -> " + module.getBind().getDisplayName());
        }
    }

    private Module findModule(ModuleManager modules, String name) {
        return modules.getByName(name).orElse(null);
    }

    private boolean isMouseName(String name) {
        String value = name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
        return value.startsWith("MOUSE") || value.matches("M\\d+") || value.equals("LMB") || value.equals("RMB") || value.equals("MMB");
    }

    private void saveConfig() {
        Manager manager = Manager.getInstance();
        if (manager != null && manager.getConfigManager() != null) {
            manager.getConfigManager().saveAll();
        }
    }
}
