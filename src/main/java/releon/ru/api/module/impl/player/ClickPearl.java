package releon.ru.api.module.impl.player;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.api.settings.impl.BindSetting;
import releon.ru.utils.chat.ChatMessage;
import releon.ru.utils.inventory.lookup.InventoryUtils;

public final class ClickPearl extends Module {
    private static final long PRE_SWAP_DELAY_MS = 35L;
    private static final long PRE_USE_DELAY_MS = 35L;
    private static final long PRE_RESTORE_DELAY_MS = 35L;
    private static ClickPearl instance;

    private final BindSetting keySetting = register(new BindSetting("Кнопка", "Кнопка для броска перки.", KeyBind.keyboard(GLFW.GLFW_KEY_UNKNOWN)));

    private boolean throwPearl;
    private boolean lastBindDown;
    private long lastUseTime;
    private long actionTimer;
    private int previousSlot = -1;
    private int pendingHotbarSlot = -1;
    private int pendingInventorySlotId = -1;
    private boolean keysOverridden;
    private boolean wasForwardPressed;
    private boolean wasBackPressed;
    private boolean wasLeftPressed;
    private boolean wasRightPressed;
    private boolean wasJumpPressed;
    private ThrowState throwState = ThrowState.IDLE;
    private ThrowMode throwMode = ThrowMode.NONE;

    public ClickPearl() {
        super("ClickPearl", "Click Pearl", ModuleCategory.PLAYER);
        instance = this;
    }

    public static boolean shouldBypassInventoryMove() {
        return instance != null && instance.throwMode == ThrowMode.INVENTORY && instance.throwState != ThrowState.IDLE;
    }

    @Override
    protected void onDisable() {
        throwPearl = false;
        lastUseTime = 0L;
        lastBindDown = false;
        restoreMovement();
        resetThrowState();
    }

    @SubscribeEvent
    private void onPreTick(TickEvent.Pre event) {
        Minecraft client = event.getClient();

        if (mc.player == null || mc.level == null) {
            resetThrowState();
            return;
        }

        updateBindState(client);

        if (throwState != ThrowState.IDLE) {
            maintainMovementStop();
            processThrow();
            return;
        }

        if (!throwPearl || mc.screen != null) {
            return;
        }
        if (System.currentTimeMillis() - lastUseTime < 200L) {
            return;
        }
        throwPearl = false;

        if (mc.player.getCooldowns().isOnCooldown(Items.ENDER_PEARL.getDefaultInstance())) {
            return;
        }

        if (mc.player.getOffhandItem().getItem() == Items.ENDER_PEARL) {
            startThrow(ThrowMode.OFFHAND, -1, -1);
            return;
        }

        int hotbarSlot = findPearlInHotbar();
        if (hotbarSlot != -1) {
            startThrow(ThrowMode.HOTBAR, hotbarSlot, -1);
            return;
        }

        int inventorySlot = findPearlInInventory();
        if (inventorySlot != -1) {
            startThrow(ThrowMode.INVENTORY, -1, InventoryUtils.wrapSlot(inventorySlot));
            return;
        }

        ChatMessage.brandmessage("Нету жемчуга");
    }

    private void updateBindState(Minecraft client) {
        if (client.getWindow() == null || client.screen != null) {
            lastBindDown = false;
            return;
        }

        boolean bindDown = keySetting.getValue().isDown(client.getWindow().handle());
        if (bindDown && !lastBindDown) {
            throwPearl = true;
        }
        lastBindDown = bindDown;
    }

    private void startThrow(ThrowMode mode, int hotbarSlot, int inventorySlotId) {
        throwMode = mode;
        previousSlot = mc.player.getInventory().getSelectedSlot();
        pendingHotbarSlot = hotbarSlot;
        pendingInventorySlotId = inventorySlotId;

        wasForwardPressed = mc.options.keyUp.isDown();
        wasBackPressed = mc.options.keyDown.isDown();
        wasLeftPressed = mc.options.keyLeft.isDown();
        wasRightPressed = mc.options.keyRight.isDown();
        wasJumpPressed = mc.options.keyJump.isDown();
        keysOverridden = true;

        maintainMovementStop();
        if (mc.player.isSprinting()) {
            mc.player.setSprinting(false);
        }

        throwState = ThrowState.WAIT_BEFORE_SWAP;
        actionTimer = System.currentTimeMillis();
    }

    private int findPearlInHotbar() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).getItem() == Items.ENDER_PEARL) {
                return i;
            }
        }
        return -1;
    }

    private int findPearlInInventory() {
        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).getItem() == Items.ENDER_PEARL) {
                return i;
            }
        }
        return -1;
    }

    private void processThrow() {
        switch (throwState) {
            case WAIT_BEFORE_SWAP -> {
                if (System.currentTimeMillis() - actionTimer < PRE_SWAP_DELAY_MS) {
                    return;
                }
                switch (throwMode) {
                    case OFFHAND, HOTBAR -> {
                    }
                    case INVENTORY -> startInventoryPearlSwap();
                    default -> {
                        resetThrowState();
                        return;
                    }
                }
                throwState = ThrowState.WAIT_BEFORE_USE;
                actionTimer = System.currentTimeMillis();
            }
            case WAIT_BEFORE_USE -> {
                if (System.currentTimeMillis() - actionTimer < PRE_USE_DELAY_MS) {
                    return;
                }
                switch (throwMode) {
                    case OFFHAND -> useOffhandPearl();
                    case HOTBAR -> startHotbarPearlUse();
                    case INVENTORY -> useInventoryPearl();
                    default -> {
                        resetThrowState();
                        return;
                    }
                }
                lastUseTime = System.currentTimeMillis();
                throwState = ThrowState.WAIT_BEFORE_RESTORE;
                actionTimer = System.currentTimeMillis();
            }
            case WAIT_BEFORE_RESTORE -> {
                if (System.currentTimeMillis() - actionTimer < PRE_RESTORE_DELAY_MS) {
                    return;
                }
                switch (throwMode) {
                    case HOTBAR -> finishHotbarPearlUse();
                    case INVENTORY -> finishInventoryPearlUse();
                    default -> {
                    }
                }
                restoreMovement();
                resetThrowState();
            }
            default -> {
            }
        }
    }

    private void useOffhandPearl() {
        mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
        mc.player.swing(InteractionHand.OFF_HAND);
    }

    private void startHotbarPearlUse() {
        maintainMovementStop();
        if (pendingHotbarSlot != previousSlot) {
            InventoryUtils.syncSelectedHotbarSlot(pendingHotbarSlot);
        }
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private void finishHotbarPearlUse() {
        maintainMovementStop();
        if (pendingHotbarSlot != previousSlot && previousSlot != -1) {
            InventoryUtils.syncSelectedHotbarSlot(previousSlot);
        }
    }

    private void startInventoryPearlSwap() {
        if (pendingInventorySlotId == -1 || previousSlot == -1) {
            return;
        }
        maintainMovementStop();
        InventoryUtils.click(pendingInventorySlotId, previousSlot, ClickType.SWAP);
    }

    private void useInventoryPearl() {
        if (pendingInventorySlotId == -1 || previousSlot == -1) {
            return;
        }
        maintainMovementStop();
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private void finishInventoryPearlUse() {
        if (pendingInventorySlotId == -1 || previousSlot == -1) {
            return;
        }
        maintainMovementStop();
        InventoryUtils.click(pendingInventorySlotId, previousSlot, ClickType.SWAP);
        InventoryUtils.syncSelectedHotbarSlot(previousSlot);
    }

    private void maintainMovementStop() {
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keySprint.setDown(false);
        if (mc.player.isSprinting()) {
            mc.player.setSprinting(false);
        }
    }

    private void restoreMovement() {
        if (!keysOverridden) {
            return;
        }
        mc.options.keyUp.setDown(wasForwardPressed);
        mc.options.keyDown.setDown(wasBackPressed);
        mc.options.keyLeft.setDown(wasLeftPressed);
        mc.options.keyRight.setDown(wasRightPressed);
        mc.options.keyJump.setDown(wasJumpPressed);
        keysOverridden = false;
    }

    private void resetThrowState() {
        throwState = ThrowState.IDLE;
        throwMode = ThrowMode.NONE;
        actionTimer = 0L;
        previousSlot = -1;
        pendingHotbarSlot = -1;
        pendingInventorySlotId = -1;
        keysOverridden = false;
    }

    private enum ThrowState {
        IDLE,
        WAIT_BEFORE_SWAP,
        WAIT_BEFORE_USE,
        WAIT_BEFORE_RESTORE
    }

    private enum ThrowMode {
        NONE,
        OFFHAND,
        HOTBAR,
        INVENTORY
    }
}
