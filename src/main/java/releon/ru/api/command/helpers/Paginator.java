package releon.ru.api.command.helpers;

import net.minecraft.network.chat.Component;
import releon.ru.api.command.CommandManager;

import java.util.List;
import java.util.function.Function;

public final class Paginator<T> {
    private final List<T> items;
    private final int pageSize;
    private int page = 1;

    public Paginator(List<T> items) {
        this(items, 8);
    }

    public Paginator(List<T> items, int pageSize) {
        this.items = items == null ? List.of() : items;
        this.pageSize = Math.max(1, pageSize);
    }

    public void setPage(int page) {
        this.page = Math.max(1, page);
    }

    public void display(Runnable header, Function<T, Component> rowFactory, String baseCommand) {
        if (header != null) {
            header.run();
        }
        int pages = Math.max(1, (int) Math.ceil(items.size() / (double) pageSize));
        int safePage = Math.min(page, pages);
        int start = (safePage - 1) * pageSize;
        int end = Math.min(items.size(), start + pageSize);
        CommandManager manager = CommandManager.getInstance();
        for (int i = start; i < end; i++) {
            Component row = rowFactory.apply(items.get(i));
            if (manager != null) {
                manager.sendRaw(row);
            }
        }
        if (manager != null && pages > 1) {
            manager.sendMessage("Page " + safePage + "/" + pages + " | " + baseCommand + " <page>");
        }
    }
}
