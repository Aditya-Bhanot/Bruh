package releon.ru.api.module.impl.player;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.state.BlockState;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.BlockBreakingEvent;
import releon.ru.api.events.impl.HotBarScrollEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.aura.util.StopWatch;
import releon.ru.utils.inventory.InventoryTask;
import releon.ru.utils.inventory.script.Script;
import releon.ru.utils.inventory.script.ScriptAction;

import java.util.Comparator;

public final class AutoTool extends Module {
    private final StopWatch swap = new StopWatch();
    private final StopWatch breaking = new StopWatch();
    private final Script swapBackScript = new Script();

    private BlockPos lastBreakPos;
    private int previousSelectedSlot = -1;

    public AutoTool() {
        super("Auto Tool", "Automatically selects the best tool for blocks.", ModuleCategory.PLAYER);
    }

    @Override
    protected void onEnable() {
        resetState();
    }

    @Override
    protected void onDisable() {
        resetState();
    }

    private void resetState() {
        lastBreakPos = null;
        previousSelectedSlot = -1;
        swap.reset();
        breaking.reset();
        swapBackScript.cleanup();
    }

    @SubscribeEvent
    private void onHotBarScroll(HotBarScrollEvent event) {
        if (!swapBackScript.isFinished()) {
            event.setCancelled(true);
        }
    }

    @SubscribeEvent
    private void onBlockBreaking(BlockBreakingEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }
        breaking.reset();
        lastBreakPos = event.blockPos();
        if (client.player.isCreative() || !swapBackScript.isFinished() || !swap.finished(350L)) {
            return;
        }

        Slot bestSlot = findBestTool(lastBreakPos);
        Slot mainHandSlot = InventoryTask.mainHandSlot();
        if (bestSlot == null || sameSlot(bestSlot, mainHandSlot)) {
            return;
        }

        int hotbarSlot = getHotbarSlotFromSlot(bestSlot);
        if (hotbarSlot != -1) {
            if (previousSelectedSlot == -1) {
                previousSelectedSlot = client.player.getInventory().getSelectedSlot();
            }
            InventoryTask.switchTo(hotbarSlot);
            swapBackScript.cleanup().addTickStep(0, new ScriptActionAdapter() {
                @Override
                public void perform() {
                    if (previousSelectedSlot != -1 && client.player != null) {
                        InventoryTask.switchTo(previousSelectedSlot);
                        previousSelectedSlot = -1;
                    }
                }
            });
        } else {
            InventoryTask.swapHand(bestSlot, InteractionHand.MAIN_HAND, true, true);
            swapBackScript.cleanup().addTickStep(0, new ScriptActionAdapter() {
                @Override
                public void perform() {
                    InventoryTask.swapHand(bestSlot, InteractionHand.MAIN_HAND, true, true);
                }
            });
        }
        swap.reset();
    }

    @SubscribeEvent
    private void onTick(TickEvent.Post event) {
        Minecraft client = event.getClient();
        if (client.player == null || client.level == null) {
            return;
        }
        if (!swapBackScript.isFinished() && swap.finished(350L)) {
            Slot bestSlot = findBestTool(lastBreakPos);
            Slot mainHandSlot = InventoryTask.mainHandSlot();
            if (!sameSlot(bestSlot, mainHandSlot) || breaking.finished(100L)) {
                if (previousSelectedSlot != -1) {
                    InventoryTask.switchTo(previousSelectedSlot);
                    previousSelectedSlot = -1;
                }
                swapBackScript.update();
                swap.reset();
            }
        }
    }

    private Slot findBestTool(BlockPos blockPos) {
        Minecraft client = Minecraft.getInstance();
        Slot mainHandSlot = InventoryTask.mainHandSlot();
        if (client.player == null || client.level == null || blockPos == null) {
            return mainHandSlot;
        }
        BlockState state = client.level.getBlockState(blockPos);
        if (state.isAir()) {
            return mainHandSlot;
        }

        Slot bestHotbarTool = null;
        double bestHotbarSpeed = 0.0D;
        for (int i = 0; i < 9; i++) {
            Slot hotbarSlot = client.player.inventoryMenu.getSlot(36 + i);
            if (hotbarSlot == null || hotbarSlot.getItem().isEmpty()) {
                continue;
            }
            double speed = hotbarSlot.getItem().getDestroySpeed(state);
            if (speed != 1.0D && speed > bestHotbarSpeed) {
                bestHotbarSpeed = speed;
                bestHotbarTool = hotbarSlot;
            }
        }
        if (bestHotbarTool != null && bestHotbarSpeed > 1.0D) {
            return bestHotbarTool;
        }

        return client.player.inventoryMenu.slots.stream()
                .filter(slot -> !slot.getItem().isEmpty())
                .filter(slot -> slot.getItem().getDestroySpeed(state) != 1.0F)
                .max(Comparator.comparingDouble(slot -> slot.getItem().getDestroySpeed(state)))
                .orElse(mainHandSlot);
    }

    private int getHotbarSlotFromSlot(Slot slot) {
        if (slot == null) {
            return -1;
        }
        int slotId = InventoryTask.getMenuSlotId(slot);
        return slotId >= 36 && slotId <= 44 ? slotId - 36 : -1;
    }

    private boolean sameSlot(Slot first, Slot second) {
        if (first == null || second == null) {
            return first == second;
        }
        return InventoryTask.getMenuSlotId(first) == InventoryTask.getMenuSlotId(second);
    }

    private abstract static class ScriptActionAdapter implements ScriptAction {
    }
}
