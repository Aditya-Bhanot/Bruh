package releon.ru.api.command.impl;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import releon.ru.api.command.Command;
import releon.ru.api.command.CommandManager;
import releon.ru.api.command.helpers.Paginator;
import releon.ru.api.command.helpers.TabCompleteHelper;
import releon.ru.utils.repository.friend.FriendUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class FriendCommand extends Command {
    public FriendCommand() {
        super("friend", "Manage friends", "f", "friends");
    }

    @Override
    public void execute(String label, String[] args) {
        CommandManager manager = CommandManager.getInstance();
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "add" -> {
                if (args.length < 2) {
                    logDirect("Usage: friend add <name>", ChatFormatting.RED);
                    return;
                }
                if (FriendUtils.isFriend(args[1])) {
                    logDirect(args[1] + " is already friend.", ChatFormatting.RED);
                    return;
                }
                FriendUtils.addFriendAndSave(args[1]);
                logDirect(args[1] + " added to friends.", ChatFormatting.GREEN);
            }
            case "remove", "del", "delete" -> {
                if (args.length < 2) {
                    logDirect("Usage: friend remove <name>", ChatFormatting.RED);
                    return;
                }
                FriendUtils.removeFriendAndSave(args[1]);
                logDirect(args[1] + " removed from friends.", ChatFormatting.GREEN);
            }
            case "clear" -> {
                int count = FriendUtils.size();
                FriendUtils.clearAndSave();
                logDirect("Friend list cleared. Removed: " + count, ChatFormatting.GREEN);
            }
            case "list" -> {
                List<String> friends = FriendUtils.getFriendNames();
                if (friends.isEmpty()) {
                    logDirect("Friend list is empty.", ChatFormatting.RED);
                    return;
                }
                int page = args.length > 1 ? parsePage(args[1]) : 1;
                Paginator<String> paginator = new Paginator<>(friends);
                paginator.setPage(page);
                paginator.display(
                        () -> {
                            logDirectRaw(Component.literal(HelpCommand.getLine()));
                            logDirect("Friends (" + friends.size() + ")");
                        },
                        friend -> Component.literal("  §a● §f" + friend).withStyle(style -> style
                                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to remove " + friend)))
                                .withClickEvent(new ClickEvent.RunCommand(manager.getPrefix() + "friend remove " + friend))),
                        manager.getPrefix() + label + " list"
                );
            }
            default -> logDirect("Usage: friend add/remove/list/clear");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, String[] args) {
        if (args.length == 1) {
            return new TabCompleteHelper().append("add", "remove", "list", "clear").sortAlphabetically().filterPrefix(args[0]).stream();
        }
        if (args.length == 2) {
            String action = args[0].toLowerCase(Locale.ROOT);
            if (action.equals("add")) {
                return new TabCompleteHelper().append(getOnlinePlayers().toArray(new String[0])).filterPrefix(args[1]).stream();
            }
            if (action.equals("remove") || action.equals("del") || action.equals("delete")) {
                return new TabCompleteHelper().append(FriendUtils.getFriendNames().toArray(new String[0])).filterPrefix(args[1]).stream();
            }
        }
        return Stream.empty();
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList("Manages friend list.", "Usage:", "> friend add <name>", "> friend remove <name>", "> friend list", "> friend clear");
    }

    private int parsePage(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private List<String> getOnlinePlayers() {
        List<String> players = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                String name = info.getProfile().name();
                if (!FriendUtils.isFriend(name)) {
                    players.add(name);
                }
            }
        }
        return players;
    }
}
