package attrium.ru.api.module.impl.combat;

import attrium.ru.api.events.annotation.SubscribeEvent;
import attrium.ru.api.events.impl.PacketEvent;
import attrium.ru.api.events.impl.TickEvent;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.ModuleCategory;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.ModeSetting;
import attrium.ru.api.settings.impl.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Field;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Multi-Mode AutoTotem (Legit, Hover, Silent) for Attrium (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * - Exact InventoryMenu container slot mapping (main 9-35, hotbar 36-44, offhand 45).
 * - Instant totem pop detection via ClientboundEntityEventPacket (eventId == 35) + tick health guard.
 * - Zero-allocation primitive state machine + cursor-stack failsafe so items are never dropped.
 */
public class AutoTotem extends Module {

    // State machine constants (primitive int for zero-allocation ticks)
    private static final int STATE_IDLE = 0;
    private static final int STATE_REACTION_WAIT = 1;
    private static final int STATE_OPEN_INVENTORY = 2;
    private static final int STATE_ACTION_WAIT = 3;
    private static final int STATE_CLICK_SECOND_STEP = 4;
    private static final int STATE_CLOSE_WAIT = 5;

    private static final int OFFHAND_SLOT = 45;
    private static final int OFFHAND_SWAP_BUTTON = 40;
    private static final byte TOTEM_POP_EVENT_ID = 35;

    // One-time cached reflection handle for AbstractContainerScreen.hoveredSlot (no per-tick allocation)
    private static final Field HOVERED_SLOT_FIELD = resolveHoveredSlotField();

    private final ModeSetting mode = register(new ModeSetting(
            "Mode",
            "Legit (opens real inventory), Hover (manual inventory assist), or Silent (packet swap)",
            "Legit", "Legit", "Hover", "Silent"
    ));
    private final ModeSetting swapMethod = register(new ModeSetting(
            "Swap Method",
            "Offhand Key (F) 1-click SWAP (button 40) or 2-step Click & Move PICKUP",
            "Offhand Key (F)", "Offhand Key (F)", "Click & Move"
    ));
    private final NumberSetting minReactionDelay = register(new NumberSetting(
            "Min Reaction Delay",
            "Minimum ticks before reacting to a totem pop or low-health trigger",
            1.0D, 1.0D, 4.0D, 1.0D
    ));
    private final NumberSetting maxReactionDelay = register(new NumberSetting(
            "Max Reaction Delay",
            "Maximum ticks before reacting to a totem pop or low-health trigger",
            3.0D, 1.0D, 4.0D, 1.0D
    ));
    private final NumberSetting minActionDelay = register(new NumberSetting(
            "Min Action Delay",
            "Minimum ticks between opening inventory, clicking totem, and closing",
            1.0D, 1.0D, 3.0D, 1.0D
    ));
    private final NumberSetting maxActionDelay = register(new NumberSetting(
            "Max Action Delay",
            "Maximum ticks between opening inventory, clicking totem, and closing",
            2.0D, 1.0D, 3.0D, 1.0D
    ));
    private final NumberSetting healthThreshold = register(new NumberSetting(
            "Health Threshold",
            "Equip totem if health + absorption drops at or below this value",
            12.0D, 1.0D, 20.0D, 0.5D
    ));
    private final BooleanSetting autoClose = register(new BooleanSetting(
            "Auto Close",
            "Automatically close the inventory screen after equipping a totem in Legit/Hover modes",
            true
    ));

    // Primitive state fields (zero per-tick heap allocations)
    private int state = STATE_IDLE;
    private int delayTicks = 0;
    private int targetContainerSlot = -1;
    private boolean openedScreenOurselves = false;
    private boolean popTriggered = false;
    private int hoverWaitTicks = -1;

    public AutoTotem() {
        super("AutoTotem", "Multi-mode AutoTotem (Legit, Hover, Silent) with exact slot mapping", ModuleCategory.COMBAT);
    }

    @Override
    protected void onEnable() {
        resetState();
    }

    @Override
    protected void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null && mc.gameMode != null) {
            returnCarriedStackSafely(mc);
            if (openedScreenOurselves && mc.screen instanceof InventoryScreen) {
                mc.player.closeContainer();
            }
        }
        resetState();
    }

    private void resetState() {
        this.state = STATE_IDLE;
        this.delayTicks = 0;
        this.targetContainerSlot = -1;
        this.openedScreenOurselves = false;
        this.popTriggered = false;
        this.hoverWaitTicks = -1;
    }

    @SubscribeEvent
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event == null || event.isSend()) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (packet instanceof ClientboundEntityEventPacket entityEvent) {
            if (entityEvent.getEventId() != TOTEM_POP_EVENT_ID) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.level == null) {
                return;
            }
            if (entityEvent.getEntity(mc.level) == mc.player) {
                this.popTriggered = true;
            }
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.Pre event) {
        if (!this.isEnabled()) {
            return;
        }
        Minecraft mc = event != null ? event.getClient() : Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null || mc.gameMode == null) {
            resetState();
            return;
        }

        // If offhand already holds a totem and we're not waiting to close our own screen, stay idle
        if (hasTotemInOffhand(mc)) {
            this.popTriggered = false;
            if (state == STATE_CLOSE_WAIT) {
                tickCloseWait(mc);
            } else if (state != STATE_IDLE) {
                returnCarriedStackSafely(mc);
                resetState();
            }
            return;
        }

        String activeMode = mode.getValue();
        if ("Hover".equalsIgnoreCase(activeMode)) {
            tickHoverMode(mc);
        } else if ("Silent".equalsIgnoreCase(activeMode)) {
            tickSilentMode(mc);
        } else {
            tickLegitMode(mc);
        }
    }

    // -------------------------------------------------------------------------
    // Mode 1: LEGIT (Opens real InventoryScreen, mimics human pacing)
    // -------------------------------------------------------------------------
    private void tickLegitMode(Minecraft mc) {
        switch (state) {
            case STATE_IDLE -> {
                if (!shouldTriggerRefill(mc)) {
                    return;
                }
                int slot = findTotemContainerSlot(mc);
                if (slot == -1) {
                    popTriggered = false;
                    return;
                }
                targetContainerSlot = slot;
                delayTicks = randomRange(minReactionDelay.getValue().intValue(), maxReactionDelay.getValue().intValue());
                state = STATE_REACTION_WAIT;
            }
            case STATE_REACTION_WAIT -> {
                if (delayTicks > 0) {
                    delayTicks--;
                    return;
                }
                // Wait if another container (chest, anvil, etc.) is currently open
                if (mc.screen != null && !(mc.screen instanceof InventoryScreen)) {
                    return;
                }
                state = STATE_OPEN_INVENTORY;
            }
            case STATE_OPEN_INVENTORY -> {
                if (!(mc.screen instanceof InventoryScreen)) {
                    mc.setScreen(new InventoryScreen(mc.player));
                    openedScreenOurselves = true;
                }
                delayTicks = randomRange(minActionDelay.getValue().intValue(), maxActionDelay.getValue().intValue());
                state = STATE_ACTION_WAIT;
            }
            case STATE_ACTION_WAIT -> {
                if (!(mc.screen instanceof InventoryScreen)) {
                    // User manually closed screen early
                    returnCarriedStackSafely(mc);
                    resetState();
                    return;
                }
                if (delayTicks > 0) {
                    delayTicks--;
                    return;
                }
                targetContainerSlot = findTotemContainerSlot(mc);
                if (targetContainerSlot == -1) {
                    finishAndQueueClose(mc);
                    return;
                }
                int syncId = mc.player.inventoryMenu.containerId;
                if (isOffhandKeySwap()) {
                    mc.gameMode.handleInventoryMouseClick(
                            syncId, targetContainerSlot, OFFHAND_SWAP_BUTTON, ClickType.SWAP, mc.player
                    );
                    finishAndQueueClose(mc);
                } else {
                    mc.gameMode.handleInventoryMouseClick(
                            syncId, targetContainerSlot, 0, ClickType.PICKUP, mc.player
                    );
                    delayTicks = 1;
                    state = STATE_CLICK_SECOND_STEP;
                }
            }
            case STATE_CLICK_SECOND_STEP -> {
                if (!(mc.screen instanceof InventoryScreen)) {
                    returnCarriedStackSafely(mc);
                    resetState();
                    return;
                }
                if (delayTicks > 0) {
                    delayTicks--;
                    return;
                }
                int syncId = mc.player.inventoryMenu.containerId;
                mc.gameMode.handleInventoryMouseClick(
                        syncId, OFFHAND_SLOT, 0, ClickType.PICKUP, mc.player
                );
                // If an old item ended up on the cursor, put it back cleanly
                returnCarriedStackSafely(mc);
                finishAndQueueClose(mc);
            }
            case STATE_CLOSE_WAIT -> tickCloseWait(mc);
            default -> resetState();
        }
    }

    // -------------------------------------------------------------------------
    // Mode 2: HOVER (Manual Inventory Assist)
    // -------------------------------------------------------------------------
    private void tickHoverMode(Minecraft mc) {
        if (!(mc.screen instanceof InventoryScreen invScreen)) {
            hoverWaitTicks = -1;
            if (state != STATE_IDLE) {
                returnCarriedStackSafely(mc);
                resetState();
            }
            return;
        }

        if (state == STATE_CLICK_SECOND_STEP) {
            if (delayTicks > 0) {
                delayTicks--;
                return;
            }
            int syncId = mc.player.inventoryMenu.containerId;
            mc.gameMode.handleInventoryMouseClick(syncId, OFFHAND_SLOT, 0, ClickType.PICKUP, mc.player);
            returnCarriedStackSafely(mc);
            finishAndQueueClose(mc);
            return;
        }

        if (state == STATE_CLOSE_WAIT) {
            tickCloseWait(mc);
            return;
        }

        // First check if the player's cursor is hovering directly over a Totem slot
        int slotToSwap = getHoveredTotemContainerSlot(invScreen);
        if (slotToSwap == -1) {
            // Fall back to automatic nearest totem after Action Delay ticks
            if (hoverWaitTicks < 0) {
                hoverWaitTicks = randomRange(minActionDelay.getValue().intValue(), maxActionDelay.getValue().intValue());
                return;
            }
            if (hoverWaitTicks > 0) {
                hoverWaitTicks--;
                return;
            }
            slotToSwap = findTotemContainerSlot(mc);
            if (slotToSwap == -1) {
                return;
            }
        }

        int syncId = mc.player.inventoryMenu.containerId;
        if (isOffhandKeySwap()) {
            mc.gameMode.handleInventoryMouseClick(
                    syncId, slotToSwap, OFFHAND_SWAP_BUTTON, ClickType.SWAP, mc.player
            );
            hoverWaitTicks = -1;
            finishAndQueueClose(mc);
        } else {
            mc.gameMode.handleInventoryMouseClick(
                    syncId, slotToSwap, 0, ClickType.PICKUP, mc.player
            );
            hoverWaitTicks = -1;
            delayTicks = 1;
            state = STATE_CLICK_SECOND_STEP;
        }
    }

    // -------------------------------------------------------------------------
    // Mode 3: SILENT (No-GUI Packet Swap + Close Packet)
    // -------------------------------------------------------------------------
    private void tickSilentMode(Minecraft mc) {
        if (mc.screen != null) {
            return;
        }

        switch (state) {
            case STATE_IDLE -> {
                if (!shouldTriggerRefill(mc)) {
                    return;
                }
                int slot = findTotemContainerSlot(mc);
                if (slot == -1) {
                    popTriggered = false;
                    return;
                }
                targetContainerSlot = slot;
                delayTicks = randomRange(minReactionDelay.getValue().intValue(), maxReactionDelay.getValue().intValue());
                state = STATE_REACTION_WAIT;
            }
            case STATE_REACTION_WAIT -> {
                if (delayTicks > 0) {
                    delayTicks--;
                    return;
                }
                targetContainerSlot = findTotemContainerSlot(mc);
                if (targetContainerSlot == -1) {
                    resetState();
                    return;
                }
                int syncId = mc.player.inventoryMenu.containerId;
                if (isOffhandKeySwap()) {
                    mc.gameMode.handleInventoryMouseClick(
                            syncId, targetContainerSlot, OFFHAND_SWAP_BUTTON, ClickType.SWAP, mc.player
                    );
                    sendSilentClosePacket(mc, syncId);
                    resetState();
                } else {
                    mc.gameMode.handleInventoryMouseClick(
                            syncId, targetContainerSlot, 0, ClickType.PICKUP, mc.player
                    );
                    delayTicks = 1;
                    state = STATE_CLICK_SECOND_STEP;
                }
            }
            case STATE_CLICK_SECOND_STEP -> {
                if (delayTicks > 0) {
                    delayTicks--;
                    return;
                }
                int syncId = mc.player.inventoryMenu.containerId;
                mc.gameMode.handleInventoryMouseClick(
                        syncId, OFFHAND_SLOT, 0, ClickType.PICKUP, mc.player
                );
                returnCarriedStackSafely(mc);
                sendSilentClosePacket(mc, syncId);
                resetState();
            }
            default -> resetState();
        }
    }

    private void finishAndQueueClose(Minecraft mc) {
        popTriggered = false;
        if (Boolean.TRUE.equals(autoClose.getValue()) && mc.screen instanceof InventoryScreen) {
            delayTicks = 1;
            state = STATE_CLOSE_WAIT;
        } else {
            returnCarriedStackSafely(mc);
            resetState();
        }
    }

    private void tickCloseWait(Minecraft mc) {
        if (delayTicks > 0) {
            delayTicks--;
            return;
        }
        returnCarriedStackSafely(mc);
        if (Boolean.TRUE.equals(autoClose.getValue()) && mc.screen instanceof InventoryScreen) {
            mc.player.closeContainer();
        }
        resetState();
    }

    private void sendSilentClosePacket(Minecraft mc, int syncId) {
        if (mc.player.connection != null) {
            mc.player.connection.send(new ServerboundContainerClosePacket(syncId));
        }
    }

    private boolean shouldTriggerRefill(Minecraft mc) {
        if (popTriggered) {
            return true;
        }
        float totalHp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        return totalHp <= healthThreshold.getValue().floatValue();
    }

    private boolean hasTotemInOffhand(Minecraft mc) {
        return mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
    }

    private boolean isOffhandKeySwap() {
        return "Offhand Key (F)".equalsIgnoreCase(swapMethod.getValue());
    }

    /**
     * Exact PlayerInventory -> InventoryMenu container slot mapping:
     * - Prioritizes main inventory slots 9-35 (maps 1:1 to container slots 9-35)
     * - Falls back to hotbar slots 0-8 (maps to container slots 36-44)
     */
    private int findTotemContainerSlot(Minecraft mc) {
        for (int invSlot = 9; invSlot < 36; invSlot++) {
            ItemStack stack = mc.player.getInventory().getItem(invSlot);
            if (!stack.isEmpty() && stack.is(Items.TOTEM_OF_UNDYING)) {
                return invSlot;
            }
        }
        for (int invSlot = 0; invSlot < 9; invSlot++) {
            ItemStack stack = mc.player.getInventory().getItem(invSlot);
            if (!stack.isEmpty() && stack.is(Items.TOTEM_OF_UNDYING)) {
                return invSlot + 36;
            }
        }
        return -1;
    }

    /**
     * Cursor Stack Failsafe: returns any carried item on the mouse cursor to an empty
     * inventory slot before closing a screen so items are never accidentally dropped.
     */
    private void returnCarriedStackSafely(Minecraft mc) {
        if (mc.player == null || mc.gameMode == null || mc.player.containerMenu == null) {
            return;
        }
        if (mc.player.containerMenu.getCarried().isEmpty()) {
            return;
        }
        int emptyContainerSlot = findEmptyContainerSlot(mc);
        if (emptyContainerSlot != -1) {
            mc.gameMode.handleInventoryMouseClick(
                    mc.player.inventoryMenu.containerId,
                    emptyContainerSlot,
                    0,
                    ClickType.PICKUP,
                    mc.player
            );
        }
    }

    private int findEmptyContainerSlot(Minecraft mc) {
        for (int invSlot = 9; invSlot < 36; invSlot++) {
            if (mc.player.getInventory().getItem(invSlot).isEmpty()) {
                return invSlot;
            }
        }
        for (int invSlot = 0; invSlot < 9; invSlot++) {
            if (mc.player.getInventory().getItem(invSlot).isEmpty()) {
                return invSlot + 36;
            }
        }
        return -1;
    }

    private int getHoveredTotemContainerSlot(InventoryScreen screen) {
        if (HOVERED_SLOT_FIELD == null || screen == null) {
            return -1;
        }
        try {
            Object val = HOVERED_SLOT_FIELD.get(screen);
            if (val instanceof Slot slot && slot.hasItem() && slot.getItem().is(Items.TOTEM_OF_UNDYING)) {
                int idx = slot.index;
                if (idx >= 9 && idx <= 44) {
                    return idx;
                }
            }
        } catch (IllegalAccessException ignored) {
        }
        return -1;
    }

    private static Field resolveHoveredSlotField() {
        for (Field f : AbstractContainerScreen.class.getDeclaredFields()) {
            if (Slot.class.isAssignableFrom(f.getType())) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    private int randomRange(int minVal, int maxVal) {
        int lo = Math.min(minVal, maxVal);
        int hi = Math.max(minVal, maxVal);
        if (lo == hi) {
            return lo;
        }
        return ThreadLocalRandom.current().nextInt(lo, hi + 1);
    }
}