package releon.ru.api.command.impl;

import net.minecraft.ChatFormatting;
import releon.ru.api.command.Command;
import releon.ru.api.command.helpers.TabCompleteHelper;
import releon.ru.utils.repository.macro.Macro;
import releon.ru.utils.repository.macro.MacroRepository;
import releon.ru.utils.string.KeyHelper;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class MacroCommand extends Command {
    public MacroCommand() {
        super("macro", "Manage macros");
    }

    @Override
    public void execute(String label, String[] args) {
        MacroRepository repository = MacroRepository.getInstance();
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "add" -> {
                if (args.length < 4) {
                    logDirect("Usage: macro add <key> <name> <message>", ChatFormatting.RED);
                    return;
                }
                int key = KeyHelper.getKeyCode(args[1]);
                if (key < 0) {
                    logDirect("Unknown key: " + args[1], ChatFormatting.RED);
                    return;
                }
                String name = args[2];
                String message = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
                repository.addMacroAndSave(name, message, key);
                logDirect("Macro " + name + " added.", ChatFormatting.GREEN);
            }
            case "remove", "del", "delete" -> {
                if (args.length < 2) {
                    logDirect("Usage: macro remove <name>", ChatFormatting.RED);
                    return;
                }
                repository.deleteMacroAndSave(args[1]);
                logDirect("Macro " + args[1] + " removed.", ChatFormatting.GREEN);
            }
            case "clear" -> {
                int count = repository.size();
                repository.clearListAndSave();
                logDirect("Macros cleared. Removed: " + count, ChatFormatting.GREEN);
            }
            case "list" -> {
                if (repository.getMacroList().isEmpty()) {
                    logDirect("Macro list is empty.", ChatFormatting.RED);
                    return;
                }
                for (Macro macro : repository.getMacroList()) {
                    logDirect("§f" + macro.name() + " §8[" + KeyHelper.getKeyName(macro.key()) + "] §7" + macro.message());
                }
            }
            default -> logDirect("Usage: macro add/remove/list/clear");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, String[] args) {
        if (args.length == 1) {
            return new TabCompleteHelper().append("add", "remove", "list", "clear").sortAlphabetically().filterPrefix(args[0]).stream();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("add")) {
            return new TabCompleteHelper().append(KeyHelper.getAllKeyNames()).filterPrefix(args[1]).stream();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
            return new TabCompleteHelper().append(MacroRepository.getInstance().getMacroNames().toArray(new String[0])).filterPrefix(args[1]).stream();
        }
        return Stream.empty();
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList("Manages macros.", "Usage:", "> macro add <key> <name> <message>", "> macro remove <name>", "> macro list", "> macro clear");
    }
}
