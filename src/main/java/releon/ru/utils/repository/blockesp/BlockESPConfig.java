package releon.ru.utils.repository.blockesp;

import releon.ru.utils.repository.RepositoryStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public final class BlockESPConfig {
    private static final String FILE_NAME = "blockesp";
    private static final String KEY = "blocks";

    private static BlockESPConfig instance;

    private final Set<String> blocks = new CopyOnWriteArraySet<>();

    private BlockESPConfig() {
    }

    public static BlockESPConfig getInstance() {
        if (instance == null) {
            instance = new BlockESPConfig();
        }
        return instance;
    }

    public Set<String> getBlocks() {
        return blocks;
    }

    public boolean hasBlock(String block) {
        return blocks.contains(block);
    }

    public void addBlock(String block) {
        if (block != null && !block.isBlank()) {
            blocks.add(block);
        }
    }

    public void addBlockAndSave(String block) {
        addBlock(block);
        save();
    }

    public boolean removeBlock(String block) {
        return blocks.remove(block);
    }

    public boolean removeBlockAndSave(String block) {
        boolean removed = removeBlock(block);
        if (removed) {
            save();
        }
        return removed;
    }

    public void clear() {
        blocks.clear();
    }

    public void clearAndSave() {
        clear();
        save();
    }

    public int size() {
        return blocks.size();
    }

    public List<String> getBlockList() {
        return new ArrayList<>(blocks);
    }

    public void load() {
        blocks.clear();
        blocks.addAll(RepositoryStorage.loadStringList(FILE_NAME, KEY));
    }

    public void save() {
        RepositoryStorage.saveStringList(FILE_NAME, KEY, getBlockList());
    }
}
