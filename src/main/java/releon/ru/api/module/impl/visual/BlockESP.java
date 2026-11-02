package releon.ru.api.module.impl.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.WorldRenderEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ColorSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.render.Render3D;
import releon.ru.utils.repository.blockesp.BlockESPConfig;
import releon.ru.utils.string.chat.ChatMessage;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public final class BlockESP extends Module {
    private static BlockESP instance;

    private final ColorSetting color = register(new ColorSetting("Color", "Block highlight color.", new Color(127, 242, 255, 136)));
    private final NumberSetting range = register(new NumberSetting("Range", "Search radius for blocks.", 32.0, 1.0, 128.0, 1.0));
    private final BooleanSetting notifyInChat = register(new BooleanSetting("Notify", "Show found blocks in chat.", false));

    private final Set<String> blocksToHighlight = new CopyOnWriteArraySet<>();
    private final Map<BlockPos, BlockState> renderBlocks = new HashMap<>();
    private final Set<BlockPos> notifiedBlocks = new CopyOnWriteArraySet<>();

    private long lastScanTime;
    private int checkCounter;

    public BlockESP() {
        super("Block ESP", "Highlights selected blocks in the world.", ModuleCategory.VISUAL);
        instance = this;
    }

    public static BlockESP getInstance() {
        return instance;
    }

    public Set<String> getBlocksToHighlight() {
        return blocksToHighlight;
    }

    @Override
    protected void onEnable() {
        blocksToHighlight.clear();
        blocksToHighlight.addAll(BlockESPConfig.getInstance().getBlocks());
        renderBlocks.clear();
        notifiedBlocks.clear();
        lastScanTime = 0L;
        checkCounter = 0;
    }

    @Override
    protected void onDisable() {
        renderBlocks.clear();
        notifiedBlocks.clear();
    }

    @Override
    public void onTick(Minecraft client) {
        if (client == null || client.level == null || client.player == null) {
            renderBlocks.clear();
            return;
        }
        if (blocksToHighlight.isEmpty()) {
            renderBlocks.clear();
            return;
        }

        BlockPos playerPos = client.player.blockPosition();
        long currentTime = System.nanoTime() / 1_000_000L;

        if (currentTime - lastScanTime >= 2000L) {
            scanChunkArea(playerPos, 2, 48, true);
            lastScanTime = currentTime;
            checkCounter = 0;
        }

        if (checkCounter % 5 == 0) {
            scanChunkArea(playerPos, 1, 24, false);
        }

        if (checkCounter % 60 == 0) {
            renderBlocks.entrySet().removeIf(entry -> {
                BlockPos pos = entry.getKey();
                Block block = client.level.getBlockState(pos).getBlock();
                String blockName = BuiltInRegistries.BLOCK.getKey(block).toString();
                boolean shouldRemove = !blocksToHighlight.contains(blockName);
                if (shouldRemove) {
                    notifiedBlocks.remove(pos);
                }
                return shouldRemove;
            });
        }

        checkCounter++;
    }

    @SubscribeEvent
    private void onRender3D(WorldRenderEvent event) {
        if (!isEnabled() || mc.level == null || mc.player == null) {
            renderBlocks.clear();
            return;
        }
        if (blocksToHighlight.isEmpty()) {
            renderBlocks.clear();
            return;
        }

        renderHighlightedBlocks(color.getValue().getRGB());
    }

    private void renderHighlightedBlocks(int highlightColor) {
        for (Map.Entry<BlockPos, BlockState> entry : renderBlocks.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();

            VoxelShape shape = state.getShape(mc.level, pos);
            if (shape.isEmpty()) {
                shape = state.getCollisionShape(mc.level, pos);
            }

            if (shape.isEmpty()) {
                AABB box = new AABB(pos);
                Render3D.drawBox(box, highlightColor, 1.0f);
                Render3D.drawBoxOverlay(box, highlightColor, 1.25f);
            } else if (shape == Shapes.block()) {
                AABB box = new AABB(pos);
                Render3D.drawBox(box, highlightColor, 1.0f);
                Render3D.drawBoxOverlay(box, highlightColor, 1.25f);
            } else {
                Render3D.drawShapeAlternative(pos, shape, highlightColor, 1.0f, true, false);
                Render3D.drawShapeOverlay(pos, shape, highlightColor, 1.25f);
            }
        }
    }

    private void scanChunkArea(BlockPos playerPos, int chunkRange, int yRange, boolean fullRefresh) {
        if (mc.level == null || mc.player == null) {
            return;
        }

        if (fullRefresh) {
            renderBlocks.clear();
        }

        double maxDistanceSqr = (double) range.getFloat() * range.getFloat();
        int minYBase = mc.level.getMinY();
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        for (int x = -chunkRange; x <= chunkRange; x++) {
            for (int z = -chunkRange; z <= chunkRange; z++) {
                int chunkX = (playerPos.getX() >> 4) + x;
                int chunkZ = (playerPos.getZ() >> 4) + z;
                if (!mc.level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                    continue;
                }

                LevelChunk chunk = mc.level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }

                int cx = chunk.getPos().x << 4;
                int cz = chunk.getPos().z << 4;
                for (int bx = 0; bx < 16; bx++) {
                    for (int bz = 0; bz < 16; bz++) {
                        int columnX = cx + bx;
                        int columnZ = cz + bz;
                        int minY = Math.max(minYBase, playerPos.getY() - yRange);
                        int maxY = Math.min(mc.level.getHeight(Heightmap.Types.WORLD_SURFACE, columnX, columnZ), playerPos.getY() + yRange);
                        for (int by = minY; by <= maxY; by++) {
                            mutablePos.set(columnX, by, columnZ);
                            double dist = mc.player.distanceToSqr(columnX + 0.5D, by + 0.5D, columnZ + 0.5D);
                            if (dist > maxDistanceSqr) {
                                continue;
                            }

                            BlockState state = mc.level.getBlockState(mutablePos);
                            String blockName = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                            if (!blocksToHighlight.contains(blockName)) {
                                continue;
                            }

                            if (!renderBlocks.containsKey(mutablePos)) {
                                BlockPos immutablePos = mutablePos.immutable();
                                renderBlocks.put(immutablePos, state);
                                if (notifyInChat.getValue() && !notifiedBlocks.contains(mutablePos)) {
                                    notifyBlockFound(immutablePos, blockName);
                                    notifiedBlocks.add(immutablePos);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void notifyBlockFound(BlockPos pos, String blockName) {
        if (mc.player == null) {
            return;
        }

        mc.player.displayClientMessage(
                ChatMessage.blockesp().copy().append(Component.literal(" -> " + blockName + " @ " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ())),
                false
        );
    }
}
