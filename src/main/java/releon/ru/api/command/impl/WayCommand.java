package releon.ru.api.command.impl;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import releon.ru.IMinecraft;
import releon.ru.api.command.Command;
import releon.ru.api.command.helpers.TabCompleteHelper;
import releon.ru.utils.repository.way.Way;
import releon.ru.utils.repository.way.WayRepository;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class WayCommand extends Command implements IMinecraft {
    public WayCommand() {
        super("way", "Управление точками", "waypoint", "wp");
    }

    @Override
    public void execute(String label, String[] args) {
        if (mc.player == null) {
            logDirect("Игрок недоступен.", ChatFormatting.RED);
            return;
        }

        WayRepository repository = WayRepository.getInstance();
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "add" -> addWaypoint(repository, args);
            case "remove", "del", "delete" -> removeWaypoint(repository, args);
            case "clear" -> clearCurrentServer(repository);
            case "clearall" -> {
                int count = repository.size();
                repository.clearListAndSave();
                logDirect("Удалены все точки: " + count, ChatFormatting.GREEN);
            }
            case "list" -> listWaypoints(repository);
            default -> logDirect("Использование: .way add/remove/list/clear/clearall");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, String[] args) {
        WayRepository repository = WayRepository.getInstance();
        if (args.length == 1) {
            return new TabCompleteHelper()
                    .append("add", "remove", "list", "clear", "clearall")
                    .sortAlphabetically()
                    .filterPrefix(args[0])
                    .stream();
        }

        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (args.length == 2 && action.equals("add")) {
            return new TabCompleteHelper().append("Название").filterPrefix(args[1]).stream();
        }
        if (args.length == 2 && isRemoveAction(action)) {
            return new TabCompleteHelper()
                    .append(repository.getWayNamesForServer(repository.getCurrentServer()).toArray(new String[0]))
                    .filterPrefix(args[1])
                    .stream();
        }
        if (action.equals("add") && args.length >= 3 && args.length <= 5) {
            return coordinateSuggestions(args.length, args[args.length - 1]);
        }
        return Stream.empty();
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Управляет серверными точками.",
                "Использование:",
                "> way add <название> [x y z]",
                "> way remove <название>",
                "> way list",
                "> way clear"
        );
    }

    private void addWaypoint(WayRepository repository, String[] args) {
        if (args.length < 2) {
            logDirect("Использование: .way add <название> [x y z]", ChatFormatting.RED);
            return;
        }
        if (args.length != 2 && args.length != 5) {
            logDirect("Координаты указываются полностью: x y z.", ChatFormatting.RED);
            return;
        }

        String server = repository.getCurrentServer();
        if (server.isBlank()) {
            logDirect("Точку можно поставить только на сервере.", ChatFormatting.RED);
            return;
        }

        String name = args[1];
        if (repository.hasWay(name, server)) {
            logDirect("Точка с таким названием уже есть на этом сервере.", ChatFormatting.RED);
            return;
        }

        BlockPos pos = mc.player.blockPosition();
        if (args.length == 5) {
            try {
                pos = new BlockPos(Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]));
            } catch (NumberFormatException exception) {
                logDirect("Неверные координаты.", ChatFormatting.RED);
                return;
            }
        }

        repository.addWayAndSave(name, pos, server);
        logDirect("Точка " + name + " добавлена: " + pos.getX() + " " + pos.getY() + " " + pos.getZ(), ChatFormatting.GREEN);
    }

    private void removeWaypoint(WayRepository repository, String[] args) {
        if (args.length < 2) {
            logDirect("Использование: .way remove <название>", ChatFormatting.RED);
            return;
        }

        String server = repository.getCurrentServer();
        if (server.isBlank()) {
            logDirect("Точки доступны только на сервере.", ChatFormatting.RED);
            return;
        }

        String name = args[1];
        if (!repository.hasWay(name, server)) {
            logDirect("Такой точки на этом сервере нет.", ChatFormatting.RED);
            return;
        }

        repository.deleteWayAndSave(name, server);
        logDirect("Точка " + name + " удалена.", ChatFormatting.GREEN);
    }

    private void clearCurrentServer(WayRepository repository) {
        String server = repository.getCurrentServer();
        if (server.isBlank()) {
            logDirect("Точки доступны только на сервере.", ChatFormatting.RED);
            return;
        }

        int removed = repository.clearServerAndSave(server);
        logDirect("Удалено точек на этом сервере: " + removed, ChatFormatting.GREEN);
    }

    private void listWaypoints(WayRepository repository) {
        String server = repository.getCurrentServer();
        if (server.isBlank()) {
            logDirect("Точки доступны только на сервере.", ChatFormatting.RED);
            return;
        }

        List<Way> ways = repository.getWaysForServer(server);
        if (ways.isEmpty()) {
            logDirect("На этом сервере точек нет.", ChatFormatting.RED);
            return;
        }

        for (Way way : ways) {
            BlockPos pos = way.pos();
            logDirect("§f" + way.name() + " §8[" + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "]");
        }
    }

    private Stream<String> coordinateSuggestions(int argCount, String prefix) {
        TabCompleteHelper helper = new TabCompleteHelper();
        BlockPos pos = mc.player == null ? BlockPos.ZERO : mc.player.blockPosition();
        if (argCount == 3) {
            helper.append(String.valueOf(pos.getX()), "x");
        } else if (argCount == 4) {
            helper.append(String.valueOf(pos.getY()), "y");
        } else if (argCount == 5) {
            helper.append(String.valueOf(pos.getZ()), "z");
        }
        return helper.filterPrefix(prefix).stream();
    }

    private boolean isRemoveAction(String action) {
        return action.equals("remove") || action.equals("del") || action.equals("delete");
    }
}
