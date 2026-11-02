package releon.ru.api.command.impl;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import releon.ru.api.command.Command;
import releon.ru.api.command.helpers.TabCompleteHelper;
import releon.ru.utils.repository.staff.StaffUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class StaffCommand extends Command {
    public StaffCommand() {
        super("staff", "Manage staff list");
    }

    @Override
    public void execute(String label, String[] args) {
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "add" -> {
                if (args.length < 2) {
                    logDirect("Usage: staff add <name>", ChatFormatting.RED);
                    return;
                }
                StaffUtils.addStaffAndSave(args[1]);
                logDirect(args[1] + " added to staff.", ChatFormatting.GREEN);
            }
            case "remove", "del", "delete" -> {
                if (args.length < 2) {
                    logDirect("Usage: staff remove <name>", ChatFormatting.RED);
                    return;
                }
                StaffUtils.removeStaffAndSave(args[1]);
                logDirect(args[1] + " removed from staff.", ChatFormatting.GREEN);
            }
            case "clear" -> {
                int count = StaffUtils.size();
                StaffUtils.clearAndSave();
                logDirect("Staff list cleared. Removed: " + count, ChatFormatting.GREEN);
            }
            case "list" -> {
                List<String> staff = StaffUtils.getStaffNames();
                if (staff.isEmpty()) {
                    logDirect("Staff list is empty.", ChatFormatting.RED);
                    return;
                }
                logDirect("Staff (" + staff.size() + "): " + String.join(", ", staff));
            }
            default -> logDirect("Usage: staff add/remove/list/clear");
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
            if (action.equals("remove")) {
                return new TabCompleteHelper().append(StaffUtils.getStaffNames().toArray(new String[0])).filterPrefix(args[1]).stream();
            }
        }
        return Stream.empty();
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList("Manages staff list.", "Usage:", "> staff add <name>", "> staff remove <name>", "> staff list", "> staff clear");
    }

    private List<String> getOnlinePlayers() {
        List<String> players = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                players.add(info.getProfile().name());
            }
        }
        return players;
    }
}
