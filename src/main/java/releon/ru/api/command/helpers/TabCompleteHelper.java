package releon.ru.api.command.helpers;

import releon.ru.api.command.Command;
import releon.ru.api.command.CommandManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class TabCompleteHelper {
    private final List<String> values = new ArrayList<>();

    public TabCompleteHelper append(String... entries) {
        if (entries != null) {
            for (String entry : entries) {
                if (entry != null) {
                    values.add(entry);
                }
            }
        }
        return this;
    }

    public TabCompleteHelper addCommands(CommandManager manager) {
        if (manager != null) {
            for (Command command : manager.getCommands()) {
                values.add(command.getName());
                values.addAll(command.getAliases());
            }
        }
        return this;
    }

    public TabCompleteHelper sortAlphabetically() {
        values.sort(Comparator.comparing(String::toLowerCase));
        return this;
    }

    public TabCompleteHelper filterPrefix(String prefix) {
        String lowered = prefix == null ? "" : prefix.toLowerCase();
        values.removeIf(value -> !value.toLowerCase().startsWith(lowered));
        return this;
    }

    public Stream<String> stream() {
        return values.stream().distinct();
    }
}
