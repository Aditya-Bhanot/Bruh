package releon.ru.api.command.impl;

import net.minecraft.ChatFormatting;
import releon.ru.api.command.Command;
import releon.ru.api.command.CommandManager;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public final class PrefixCommand extends Command {
    public PrefixCommand() {
        super("prefix", "Changes command prefix");
    }

    @Override
    public void execute(String label, String[] args) {
        CommandManager manager = CommandManager.getInstance();
        if (args.length == 0) {
            logDirect("Current prefix: " + manager.getPrefix());
            logDirect("Usage: prefix set <symbol>");
            return;
        }
        if (!args[0].equalsIgnoreCase("set") || args.length < 2) {
            logDirect("Usage: prefix set <symbol>", ChatFormatting.RED);
            return;
        }
        String newPrefix = args[1];
        if (newPrefix.length() > 3 || newPrefix.contains(" ")) {
            logDirect("Prefix must be 1-3 chars without spaces.", ChatFormatting.RED);
            return;
        }
        manager.setPrefix(newPrefix);
        logDirect("Prefix changed to: " + newPrefix, ChatFormatting.GREEN);
    }

    @Override
    public Stream<String> tabComplete(String label, String[] args) {
        if (args.length == 1) {
            return Stream.of("set").filter(value -> value.startsWith(args[0].toLowerCase()));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            return Stream.of(".", "!", "$", "#", "-", "/").filter(value -> value.startsWith(args[1]));
        }
        return Stream.empty();
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList("Changes command prefix.", "Usage:", "> prefix", "> prefix set <symbol>");
    }
}
