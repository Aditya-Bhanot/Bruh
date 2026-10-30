package releon.ru.api.command.impl;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import releon.ru.api.command.Command;
import releon.ru.api.command.CommandManager;
import releon.ru.api.command.helpers.Paginator;
import releon.ru.api.command.helpers.TabCompleteHelper;
import releon.ru.api.module.impl.visual.BlockESP;
import releon.ru.utils.repository.blockesp.BlockESPConfig;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static releon.ru.api.command.impl.HelpCommand.getLine;

public final class BlockESPCommand extends Command {
    public BlockESPCommand() {
        super("blockesp", "Manage the Block ESP block list", "besp");
    }

    @Override
    public void execute(String label, String[] args) {
        CommandManager manager = CommandManager.getInstance();
        BlockESPConfig config = BlockESPConfig.getInstance();

        String action = args.length > 0 ? args[0].toLowerCase(Locale.US) : "help";
        switch (action) {
            case "add" -> addBlock(manager, config, args);
            case "remove", "del", "delete" -> removeBlock(manager, config, args);
            case "clear" -> clearBlocks(config);
            case "list" -> listBlocks(manager, label, args);
            case "blocks", "allblocks" -> listAllBlocks(manager, label, args);
            default -> printHelp();
        }
    }

    @Override
    public Stream<String> tabComplete(String label, String[] args) {
        if (args.length == 1) {
            return new TabCompleteHelper()
                    .append("add", "remove", "del", "delete", "list", "clear", "blocks", "allblocks")
                    .filterPrefix(args[0])
                    .stream();
        }

        if (args.length == 2) {
            String action = args[0].toLowerCase(Locale.US);
            if (action.equals("add")) {
                return BuiltInRegistries.BLOCK.keySet().stream()
                        .map(Identifier::toString)
                        .filter(name -> name.toLowerCase(Locale.US).contains(args[1].toLowerCase(Locale.US)))
                        .limit(50);
            }

            if (action.equals("remove") || action.equals("del") || action.equals("delete")) {
                return new TabCompleteHelper()
                        .append(BlockESPConfig.getInstance().getBlockList().toArray(new String[0]))
                        .filterPrefix(args[1])
                        .stream();
            }
        }

        return Stream.empty();
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Manage the block list used by Block ESP.",
                "Usage:",
                "> blockesp add <block>",
                "> blockesp remove <block>",
                "> blockesp list",
                "> blockesp clear",
                "> blockesp blocks"
        );
    }

    private void addBlock(CommandManager manager, BlockESPConfig config, String[] args) {
        if (args.length < 2) {
            logDirect("Usage: blockesp add <block>", ChatFormatting.RED);
            return;
        }

        String blockId = normalizeBlockId(args[1]);
        Identifier identifier = Identifier.tryParse(blockId);
        if (identifier == null) {
            logDirect("Invalid block id: " + args[1], ChatFormatting.RED);
            return;
        }

        Block block = BuiltInRegistries.BLOCK.getValue(identifier);
        if (block == null || block == Blocks.AIR) {
            logDirect("Block not found: " + args[1], ChatFormatting.RED);
            return;
        }

        String registryName = BuiltInRegistries.BLOCK.getKey(block).toString();
        if (config.hasBlock(registryName)) {
            logDirect("Block already in the list: " + registryName, ChatFormatting.RED);
            return;
        }

        config.addBlockAndSave(registryName);
        syncModule();
        logDirect("Block added: " + registryName, ChatFormatting.GREEN);
    }

    private void removeBlock(CommandManager manager, BlockESPConfig config, String[] args) {
        if (args.length < 2) {
            logDirect("Usage: blockesp remove <block>", ChatFormatting.RED);
            return;
        }

        String blockId = normalizeBlockId(args[1]);
        Identifier identifier = Identifier.tryParse(blockId);
        String registryName = blockId;
        if (identifier != null) {
            Block block = BuiltInRegistries.BLOCK.getValue(identifier);
            if (block != null) {
                registryName = BuiltInRegistries.BLOCK.getKey(block).toString();
            }
        }

        if (!config.hasBlock(registryName)) {
            logDirect("Block not found in list: " + registryName, ChatFormatting.RED);
            return;
        }

        config.removeBlockAndSave(registryName);
        syncModule();
        logDirect("Block removed: " + registryName, ChatFormatting.GREEN);
    }

    private void clearBlocks(BlockESPConfig config) {
        int count = config.size();
        config.clearAndSave();
        syncModule();
        logDirect("BlockESP list cleared. Removed: " + count, ChatFormatting.GREEN);
    }

    private void listBlocks(CommandManager manager, String label, String[] args) {
        int page = parsePage(args);
        List<String> blocks = BlockESPConfig.getInstance().getBlockList();
        if (blocks.isEmpty()) {
            logDirect("BlockESP list is empty.", ChatFormatting.RED);
            return;
        }

        Paginator<String> paginator = new Paginator<>(blocks);
        paginator.setPage(page);
        paginator.display(
                () -> {
                    logDirectRaw(Component.literal(getLine()));
                    logDirect("Block ESP list (" + blocks.size() + ")");
                    logDirectRaw(Component.literal(getLine()));
                },
                blockName -> createBlockListRow(manager, blockName),
                manager.getPrefix() + label + " list"
        );
    }

    private void listAllBlocks(CommandManager manager, String label, String[] args) {
        int page = parsePage(args);
        List<String> allBlocks = BuiltInRegistries.BLOCK.keySet().stream()
                .map(Identifier::toString)
                .sorted()
                .toList();

        Paginator<String> paginator = new Paginator<>(allBlocks, 15);
        paginator.setPage(page);
        paginator.display(
                () -> {
                    logDirectRaw(Component.literal(getLine()));
                    logDirect("All blocks (" + allBlocks.size() + ")");
                    logDirectRaw(Component.literal(getLine()));
                },
                blockName -> createAllBlocksRow(manager, blockName),
                manager.getPrefix() + label + " blocks"
        );
    }

    private MutableComponent createBlockListRow(CommandManager manager, String blockName) {
        String shortName = blockName.replace("minecraft:", "");
        MutableComponent component = Component.literal("  * " + shortName)
                .append(Component.literal(" (" + blockName + ")"));
        MutableComponent hoverText = Component.literal("Click to remove " + shortName);
        component.setStyle(component.getStyle()
                .withHoverEvent(new HoverEvent.ShowText(hoverText))
                .withClickEvent(new ClickEvent.RunCommand(manager.getPrefix() + "blockesp remove " + blockName)));
        return component;
    }

    private MutableComponent createAllBlocksRow(CommandManager manager, String blockName) {
        boolean inList = BlockESPConfig.getInstance().hasBlock(blockName);
        String prefix = inList ? "[x]" : "[ ]";
        MutableComponent component = Component.literal("  " + prefix + " " + blockName.replace("minecraft:", ""));
        String command = inList
                ? manager.getPrefix() + "blockesp remove " + blockName
                : manager.getPrefix() + "blockesp add " + blockName;
        MutableComponent hoverText = Component.literal(inList
                ? "Click to remove"
                : "Click to add");
        component.setStyle(component.getStyle()
                .withHoverEvent(new HoverEvent.ShowText(hoverText))
                .withClickEvent(new ClickEvent.RunCommand(command)));
        return component;
    }

    private void printHelp() {
        logDirectRaw(Component.literal(getLine()));
        logDirect("BLOCK ESP");
        logDirectRaw(Component.literal(getLine()));
        logDirect("> blockesp add <block> - add block");
        logDirect("> blockesp remove <block> - remove block");
        logDirect("> blockesp list - show saved blocks");
        logDirect("> blockesp clear - clear the list");
        logDirect("> blockesp blocks - show all blocks");
        logDirectRaw(Component.literal(getLine()));
    }

    private void syncModule() {
        BlockESP module = BlockESP.getInstance();
        if (module == null) {
            return;
        }

        module.getBlocksToHighlight().clear();
        module.getBlocksToHighlight().addAll(BlockESPConfig.getInstance().getBlocks());
    }

    private int parsePage(String[] args) {
        if (args.length > 1) {
            try {
                return Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException ignored) {
            }
        }
        return 1;
    }

    private String normalizeBlockId(String raw) {
        String blockId = raw.toLowerCase(Locale.US);
        if (!blockId.contains(":")) {
            blockId = "minecraft:" + blockId;
        }
        return blockId;
    }
}
