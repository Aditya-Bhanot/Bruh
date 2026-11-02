package releon.ru.api.command;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import releon.ru.utils.string.chat.ChatMessage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public abstract class Command {
    private final String name;
    private final String description;
    private final List<String> aliases;

    protected Command(String name, String description, String... aliases) {
        this.name = name;
        this.description = description == null ? "" : description;
        this.aliases = Arrays.asList(aliases == null ? new String[0] : aliases);
    }

    public abstract void execute(String label, String[] args);

    public Stream<String> tabComplete(String label, String[] args) {
        return Stream.empty();
    }

    public String getShortDesc() {
        return description;
    }

    public List<String> getLongDesc() {
        return Arrays.asList(description, "", "Usage:", "> " + name + " - " + description);
    }

    public boolean hiddenFromHelp() {
        return false;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public List<String> getAllNames() {
        List<String> names = new ArrayList<>();
        names.add(name);
        names.addAll(aliases);
        return names;
    }

    public boolean matches(String input) {
        return name.equalsIgnoreCase(input) || aliases.stream().anyMatch(alias -> alias.equalsIgnoreCase(input));
    }

    protected void logDirect(String message) {
        ChatMessage.brandmessage(message);
    }

    protected void logDirect(String message, ChatFormatting formatting) {
        CommandManager manager = CommandManager.getInstance();
        if (manager == null) {
            ChatMessage.brandmessage(message);
            return;
        }
        if (formatting == ChatFormatting.RED) {
            manager.sendError(message);
        } else if (formatting == ChatFormatting.GREEN) {
            manager.sendSuccess(message);
        } else {
            manager.sendMessage(message);
        }
    }

    protected void logDirect(Component text) {
        ChatMessage.brandmessage(text);
    }

    protected void logDirect(MutableComponent text) {
        ChatMessage.brandmessage(text);
    }

    protected void logDirectRaw(Component text) {
        CommandManager.getInstance().sendRaw(text);
    }

    protected void logDirectRaw(MutableComponent text) {
        CommandManager.getInstance().sendRaw(text);
    }
}
