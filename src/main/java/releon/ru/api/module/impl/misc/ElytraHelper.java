package releon.ru.api.module.impl.misc;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.HotBarScrollEvent;
import releon.ru.api.events.impl.InputEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.aura.util.StopWatch;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.api.settings.impl.BindSetting;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.utils.chat.ChatMessage;
import releon.ru.utils.inventory.InventoryFlowManager;
import releon.ru.utils.inventory.InventoryTask;
import releon.ru.utils.inventory.bind.InventoryBindHelper;
import releon.ru.utils.inventory.lookup.InventoryUtils;

import java.util.List;

public final class ElytraHelper extends Module {
    private static final int HOTBAR_SELECT_DELAY_TICKS = 0;
    private static final int HOTBAR_RESTORE_DELAY_TICKS = 0;
    private static final int INVENTORY_SELECT_DELAY_TICKS = 0;
    private static final int INVENTORY_RESTORE_DELAY_TICKS = 0;

    private static final List<Item> CHESTPLATES = List.of(
            Items.NETHERITE_CHESTPLATE,
            Items.DIAMOND_CHESTPLATE,
            Items.CHAINMAIL_CHESTPLATE,
            Items.IRON_CHESTPLATE,
            Items.GOLDEN_CHESTPLATE,
            Items.LEATHER_CHESTPLATE
    );

    private final BindSetting swapBind = register(new BindSetting("Swap Bind", "Swap elytra/chestplate bind.", KeyBind.NONE));
    private final BindSetting fireworkBind = register(new BindSetting("Firework Bind", "Use firework bind.", KeyBind.NONE));
    private final BooleanSetting autoTakeoff = register(new BooleanSetting("Auto Takeoff", "Automatically toggles jump for elytra takeoff.", false));
    private final BooleanSetting autoFirework = register(new BooleanSetting("Auto Firework", "Automatically uses firework after takeoff.", false));
    private final StopWatch autoFireworkTimer = new StopWatch();
    private final StopWatch fireworkUseTimer = new StopWatch();

    private boolean autoFireworkArmed;
    private boolean lastSwapPressed;
    private boolean lastFireworkPressed;
    private FireworkPhase fireworkPhase = FireworkPhase.IDLE;
    private int fireworkPhaseTicks;
    private int previousHotbarSlot = -1;
    private int restoreInventorySlot = -1;

    public ElytraHelper() {
        super("Elytra Helper", "Helps with elytra swapping and fireworks.", ModuleCategory.MISC);
        autoFirework.visibleWhen(autoTakeoff::getValue);
    }

    @Override
    protected void onDisable() {
        resetState();
    }

    @SubscribeEvent
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) {
            resetState();
            return;
        }

        updateFireworkUse();

        boolean swapPressed = InventoryBindHelper.isHeld(mc, swapBind);
        boolean fireworkPressed = InventoryBindHelper.isHeld(mc, fireworkBind);

        if (mc.screen != null) {
            lastSwapPressed = swapPressed;
            lastFireworkPressed = fireworkPressed;
            return;
        }

        if (!lastSwapPressed && swapPressed && !isBusy()) {
            startArmorSwap();
        }
        if (!lastFireworkPressed && fireworkPressed && !isBusy()) {
            useFirework();
        }

        processAutoFirework();
        lastSwapPressed = swapPressed;
        lastFireworkPressed = fireworkPressed;
    }

    @SubscribeEvent
    private void onInput(InputEvent event) {
        if (mc.player == null) {
            return;
        }
        if (!autoTakeoff.getValue() || !InventoryUtils.isElytraEquipped() || !InventoryUtils.isElytraUsable()) {
            return;
        }

        event.setJumping(mc.player.tickCount % 2 == 0);
        if (!mc.player.isFallFlying()) {
            autoFireworkTimer.reset();
        }
    }

    @SubscribeEvent
    private void onScroll(HotBarScrollEvent event) {
        if (mc.player == null || mc.level == null || mc.screen != null || isBusy()) {
            return;
        }
        if (InventoryBindHelper.matchesScroll(event, swapBind)) {
            event.setCancelled(true);
            startArmorSwap();
        }
        if (InventoryBindHelper.matchesScroll(event, fireworkBind)) {
            event.setCancelled(true);
            useFirework();
        }
    }

    private void startArmorSwap() {
        Slot slot = findChestSwapSlot();
        boolean swappingToElytra = !InventoryUtils.isElytraEquipped();

        if (slot == null) {
            ChatMessage.brandmessage(swappingToElytra ? "No elytra found." : "No chestplate found.");
            return;
        }

        InventoryTask.moveItem(slot, 6, shouldQueueSwap(), true);
        ChatMessage.brandmessage(swappingToElytra ? "Swapped to elytra." : "Swapped to chestplate.");

        if (swappingToElytra) {
            armAutoFirework();
        } else {
            autoFireworkArmed = false;
        }
    }

    private Slot findChestSwapSlot() {
        return InventoryUtils.isElytraEquipped() ? InventoryTask.getSlot(CHESTPLATES) : InventoryTask.getSlot(Items.ELYTRA);
    }

    private void useFirework() {
        if (mc.player == null || !InventoryUtils.isElytraEquipped()) {
            return;
        }
        if (!fireworkUseTimer.finished(1)) {
            return;
        }
        if (mc.player.getCooldowns().isOnCooldown(Items.FIREWORK_ROCKET.getDefaultInstance())) {
            return;
        }

        previousHotbarSlot = mc.player.getInventory().getSelectedSlot();
        restoreInventorySlot = -1;

        if (mc.player.getMainHandItem().is(Items.FIREWORK_ROCKET)) {
            startFireworkPhase(FireworkPhase.USE, 0);
            return;
        }

        int hotbarSlot = InventoryTask.findHotbarSlot(Items.FIREWORK_ROCKET);
        if (hotbarSlot != -1) {
            InventoryUtils.syncSelectedHotbarSlot(hotbarSlot);
            startFireworkPhase(FireworkPhase.WAIT_SELECTED, HOTBAR_SELECT_DELAY_TICKS);
            return;
        }

        int inventorySlot = InventoryTask.findInventorySlot(Items.FIREWORK_ROCKET);
        if (inventorySlot != -1) {
            restoreInventorySlot = inventorySlot;
            queueFireworkSwap(inventorySlot, previousHotbarSlot);
            startFireworkPhase(FireworkPhase.WAIT_SWAP, INVENTORY_SELECT_DELAY_TICKS);
        }
    }

    private void updateFireworkUse() {
        if (fireworkPhase == FireworkPhase.IDLE) {
            return;
        }
        if (mc.player == null || mc.gameMode == null || mc.screen != null) {
            resetFireworkUse();
            return;
        }
        if (fireworkPhaseTicks > 0) {
            fireworkPhaseTicks--;
            return;
        }

        switch (fireworkPhase) {
            case WAIT_SWAP -> {
                if (!InventoryFlowManager.isIdle()) {
                    return;
                }
                startFireworkPhase(FireworkPhase.WAIT_SELECTED, INVENTORY_SELECT_DELAY_TICKS);
            }
            case WAIT_SELECTED -> startFireworkPhase(FireworkPhase.USE, 0);
            case USE -> {
                if (mc.player.getMainHandItem().is(Items.FIREWORK_ROCKET)) {
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    fireworkUseTimer.reset();
                }
                startFireworkPhase(FireworkPhase.RESTORE, restoreInventorySlot != -1
                        ? INVENTORY_RESTORE_DELAY_TICKS
                        : HOTBAR_RESTORE_DELAY_TICKS);
            }
            case RESTORE -> {
                if (restoreInventorySlot != -1) {
                    queueFireworkSwap(restoreInventorySlot, previousHotbarSlot);
                    startFireworkPhase(FireworkPhase.WAIT_RESTORE, 0);
                } else if (previousHotbarSlot >= 0 && previousHotbarSlot <= 8) {
                    InventoryUtils.syncSelectedHotbarSlot(previousHotbarSlot);
                    resetFireworkUse();
                } else {
                    resetFireworkUse();
                }
            }
            case WAIT_RESTORE -> {
                if (!InventoryFlowManager.isIdle()) {
                    return;
                }
                resetFireworkUse();
            }
            default -> resetFireworkUse();
        }
    }

    private void startFireworkPhase(FireworkPhase phase, int ticks) {
        fireworkPhase = phase;
        fireworkPhaseTicks = Math.max(0, ticks);
    }

    private void queueFireworkSwap(int inventorySlot, int hotbarSlot) {
        InventoryFlowManager.addTask(() -> InventoryTask.swap(inventorySlot, hotbarSlot, false));
    }

    private void resetFireworkUse() {
        fireworkPhase = FireworkPhase.IDLE;
        fireworkPhaseTicks = 0;
        previousHotbarSlot = -1;
        restoreInventorySlot = -1;
    }

    private void processAutoFirework() {
        if (mc.player == null) {
            return;
        }
        if (!autoTakeoff.getValue() || !autoFirework.getValue()) {
            autoFireworkArmed = false;
            return;
        }
        if (!autoFireworkArmed || mc.screen != null || isBusy()) {
            return;
        }
        if (!InventoryUtils.isElytraEquipped() || !mc.player.isFallFlying() || !autoFireworkTimer.finished(75L)) {
            return;
        }

        useFirework();
        autoFireworkArmed = false;
        autoFireworkTimer.reset();
    }

    private void armAutoFirework() {
        autoFireworkArmed = autoTakeoff.getValue() && autoFirework.getValue();
        autoFireworkTimer.reset();
    }

    private boolean shouldQueueSwap() {
        return !InventoryTask.isMoveMode("ReallyWorld");
    }

    private boolean isBusy() {
        return !InventoryFlowManager.script.isFinished()
                || !InventoryFlowManager.postScript.isFinished()
                || !InventoryTask.isSwapAndUseIdle()
                || fireworkPhase != FireworkPhase.IDLE;
    }

    private void resetState() {
        autoFireworkArmed = false;
        lastSwapPressed = false;
        lastFireworkPressed = false;
        autoFireworkTimer.reset();
        fireworkUseTimer.reset();
        resetFireworkUse();
    }

    private enum FireworkPhase {
        IDLE,
        WAIT_SWAP,
        WAIT_SELECTED,
        USE,
        RESTORE,
        WAIT_RESTORE
    }
}
