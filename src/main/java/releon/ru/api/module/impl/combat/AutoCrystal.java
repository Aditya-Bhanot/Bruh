package releon.ru.api.module.impl.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.inventory.lookup.InventoryUtils;

public class AutoCrystal extends Module {
    private final BooleanSetting autoAddCrystal = register(new BooleanSetting("Auto Place", "Automatically places end crystals.", true));
    private final NumberSetting addCooldown = register(new NumberSetting("Place Delay", "Delay between crystal placements in ticks.", 0.0, 0.0, 5.0, 1.0));
    private final NumberSetting attackCooldown = register(new NumberSetting("Attack Delay", "Delay between crystal attacks in ticks.", 0.0, 0.0, 5.0, 1.0));
    private final NumberSetting attackRange = register(new NumberSetting("Attack Range", "Range for 360 crystal attacks.", 6.0, 1.0, 6.0, 0.1));

    private int attackCrystalCooldown;
    private int addCrystalCooldown;
    private int previousCrystalSlot = -1;
    private int restoreSlot = -1;
    private int restoreDelay;
    private int pendingInventorySlot = -1;
    private int pendingHotbarSlot = -1;
    private int restoreInventorySlot = -1;
    private int restoreHotbarSlot = -1;
    private int restoreInventoryDelay;

    public AutoCrystal() {
        super("Auto Crystal", "Places and attacks end crystals.", ModuleCategory.COMBAT);
    }

    @SubscribeEvent
    private void onPreTick(TickEvent.Pre event) {
        Minecraft client = event.getClient();
        if (client.player == null || client.level == null || client.gameMode == null) {
            resetCooldowns();
            return;
        }

        boolean restoring = tickRestoreInventory() | tickRestoreSlot(client);

        if (!restoring && autoAddCrystal.getValue()) {
            addCrystal(client);
        }
        attackCrystal(client);
    }

    @Override
    protected void onEnable() {
        resetCooldowns();
    }

    private void addCrystal(Minecraft client) {
        if (!(client.hitResult instanceof BlockHitResult obsidianTarget)) {
            cancelPendingInventorySwap();
            return;
        }

        BlockPos targetPos = obsidianTarget.getBlockPos();
        BlockState state = client.level.getBlockState(targetPos);
        if (state.getBlock() != Blocks.OBSIDIAN && state.getBlock() != Blocks.BEDROCK) {
            cancelPendingInventorySwap();
            return;
        }
        if (addCrystalCooldown < addCooldown.getValue()) {
            addCrystalCooldown++;
            return;
        }
        if (hasCrystalOn(client, targetPos)) {
            cancelPendingInventorySwap();
            return;
        }

        int crystalSlot = InventoryUtils.findHotbarItem(Items.END_CRYSTAL);
        if (crystalSlot == -1) {
            int inventorySlot = InventoryUtils.findItemInInventory(Items.END_CRYSTAL);
            if (inventorySlot == -1) {
                cancelPendingInventorySwap();
                return;
            }

            swapCrystalFromInventory(client, inventorySlot);
            return;
        }

        int selectedSlot = client.player.getInventory().getSelectedSlot();
        if (selectedSlot != crystalSlot) {
            previousCrystalSlot = selectedSlot;
            InventoryUtils.syncSelectedHotbarSlot(crystalSlot);
            return;
        }

        InteractionResult result = client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, obsidianTarget);
        if (result.consumesAction()) {
            client.player.swing(InteractionHand.MAIN_HAND);
            scheduleRestoreSlot();
            scheduleRestoreInventory();
        }
        addCrystalCooldown = 0;
    }

    private void attackCrystal(Minecraft client) {
        if (attackCrystalCooldown < attackCooldown.getValue()) {
            attackCrystalCooldown++;
            return;
        }

        EndCrystal crystal = findCrystal360(client);
        if (crystal == null) {
            return;
        }

        client.gameMode.attack(client.player, crystal);
        client.player.swing(InteractionHand.MAIN_HAND);
        attackCrystalCooldown = 0;
    }

    private EndCrystal findCrystal360(Minecraft client) {
        double range = attackRange.getValue();
        double rangeSq = range * range;
        EndCrystal bestCrystal = null;
        double bestDistance = Double.MAX_VALUE;

        for (Entity entity : client.level.getEntities(client.player, client.player.getBoundingBox().inflate(range), entity -> entity instanceof EndCrystal)) {
            if (!(entity instanceof EndCrystal crystal) || !crystal.isAlive()) {
                continue;
            }

            double distanceSq = client.player.distanceToSqr(crystal);
            if (distanceSq > rangeSq || distanceSq >= bestDistance) {
                continue;
            }

            bestDistance = distanceSq;
            bestCrystal = crystal;
        }

        return bestCrystal;
    }

    private boolean hasCrystalOn(Minecraft client, BlockPos basePos) {
        BlockPos crystalPos = basePos.above();
        AABB searchBox = new AABB(
                crystalPos.getX(),
                crystalPos.getY(),
                crystalPos.getZ(),
                crystalPos.getX() + 1.0D,
                crystalPos.getY() + 2.0D,
                crystalPos.getZ() + 1.0D
        ).inflate(0.1D);

        for (Entity entity : client.level.getEntities(client.player, searchBox, entity -> entity instanceof EndCrystal)) {
            if (entity.isAlive()) {
                return true;
            }
        }
        return false;
    }

    private void resetCooldowns() {
        attackCrystalCooldown = 0;
        addCrystalCooldown = 0;
        previousCrystalSlot = -1;
        restoreSlot = -1;
        restoreDelay = 0;
        pendingInventorySlot = -1;
        pendingHotbarSlot = -1;
        restoreInventorySlot = -1;
        restoreHotbarSlot = -1;
        restoreInventoryDelay = 0;
    }

    private void scheduleRestoreSlot() {
        if (previousCrystalSlot == -1) {
            return;
        }

        restoreSlot = previousCrystalSlot;
        restoreDelay = 2;
        previousCrystalSlot = -1;
    }

    private boolean tickRestoreSlot(Minecraft client) {
        if (restoreSlot == -1) {
            return false;
        }
        if (restoreDelay > 0) {
            restoreDelay--;
            return true;
        }

        int slot = restoreSlot;
        restoreSlot = -1;
        if (client.player.getInventory().getSelectedSlot() != slot) {
            InventoryUtils.syncSelectedHotbarSlot(slot);
        }
        return true;
    }

    private void swapCrystalFromInventory(Minecraft client, int inventorySlot) {
        if (pendingInventorySlot != -1 || restoreInventorySlot != -1) {
            return;
        }

        int selectedSlot = client.player.getInventory().getSelectedSlot();
        int wrappedSlot = InventoryUtils.wrapSlot(inventorySlot);
        pendingInventorySlot = wrappedSlot;
        pendingHotbarSlot = selectedSlot;
        InventoryUtils.click(wrappedSlot, selectedSlot, ClickType.SWAP);
    }

    private void scheduleRestoreInventory() {
        if (pendingInventorySlot == -1 || pendingHotbarSlot == -1) {
            return;
        }

        restoreInventorySlot = pendingInventorySlot;
        restoreHotbarSlot = pendingHotbarSlot;
        restoreInventoryDelay = 2;
        pendingInventorySlot = -1;
        pendingHotbarSlot = -1;
    }

    private void cancelPendingInventorySwap() {
        if (pendingInventorySlot == -1 || pendingHotbarSlot == -1 || restoreInventorySlot != -1) {
            return;
        }

        restoreInventorySlot = pendingInventorySlot;
        restoreHotbarSlot = pendingHotbarSlot;
        restoreInventoryDelay = 1;
        pendingInventorySlot = -1;
        pendingHotbarSlot = -1;
    }

    private boolean tickRestoreInventory() {
        if (restoreInventorySlot == -1 || restoreHotbarSlot == -1) {
            return false;
        }
        if (restoreInventoryDelay > 0) {
            restoreInventoryDelay--;
            return true;
        }

        int inventorySlot = restoreInventorySlot;
        int hotbarSlot = restoreHotbarSlot;
        restoreInventorySlot = -1;
        restoreHotbarSlot = -1;
        InventoryUtils.click(inventorySlot, hotbarSlot, ClickType.SWAP);
        return true;
    }
}
