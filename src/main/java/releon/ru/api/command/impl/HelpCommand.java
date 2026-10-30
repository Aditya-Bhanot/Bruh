package releon.ru.api.command.impl;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import releon.ru.api.command.Command;
import releon.ru.api.command.CommandManager;
import releon.ru.api.command.helpers.Paginator;
import releon.ru.api.command.helpers.TabCompleteHelper;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class HelpCommand extends Command {
    public HelpCommand() {
        super("help", "Shows available commands");
    }

    @Override
    public void execute(String label, String[] args) {
        CommandManager manager = CommandManager.getInstance();
        if (args.length == 0 || isInteger(args[0])) {
            int page = args.length > 0 && isInteger(args[0]) ? Integer.parseInt(args[0]) : 1;
            List<Command> commands = manager.getCommands().stream().filter(command -> !command.hiddenFromHelp()).collect(Collectors.toList());
            Paginator<Command> paginator = new Paginator<>(commands);
            paginator.setPage(page);
            paginator.display(
                    () -> {
                        logDirectRaw(Component.literal(getLine()));
                        logDirect("Available commands");
                        logDirectRaw(Component.literal(getLine()));
                    },
                    command -> {
                        String fullName = manager.getPrefix() + command.getName();
                        MutableComponent hover = Component.literal(fullName).withStyle(ChatFormatting.WHITE)
                                .append(Component.literal("\n" + command.getShortDesc()).withStyle(ChatFormatting.GRAY));
                        return Component.literal(fullName).withStyle(ChatFormatting.WHITE)
                                .append(Component.literal(" - " + command.getShortDesc()).withStyle(ChatFormatting.GRAY))
                                .withStyle(style -> style
                                        .withHoverEvent(new HoverEvent.ShowText(hover))
                                        .withClickEvent(new ClickEvent.RunCommand(manager.getPrefix() + label + " " + command.getName())));
                    },
                    manager.getPrefix() + label
            );
            return;
        }
        Command command = manager.getCommand(args[0]);
        if (command == null) {
            logDirect("Command '" + args[0] + "' not found.", ChatFormatting.RED);
            return;
        }
        logDirectRaw(Component.literal(getLine()));
        logDirect(command.getName().toUpperCase());
        logDirectRaw(Component.literal(getLine()));
        for (String line : command.getLongDesc()) {
            if (!line.isEmpty()) {
                logDirect(line);
            }
        }
        logDirectRaw(Component.literal(getLine()));
    }

    @Override
    public Stream<String> tabComplete(String label, String[] args) {
        if (args.length == 1) {
            return new TabCompleteHelper().filterPrefix(args[0]).addCommands(CommandManager.getInstance()).stream();
        }
        return Stream.empty();
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList("Shows command help.", "Usage:", "> help", "> help <command>");
    }

    private boolean isInteger(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    public static String getLine() {
        Minecraft mc = Minecraft.getInstance();
        int dashCount = mc != null && mc.font != null ? Math.max(18, Math.min(80, mc.getWindow().getGuiScaledWidth() / Math.max(4, mc.font.width("-")) / 3)) : 24;
        return "§8§m" + "-".repeat(dashCount);
    }
}
