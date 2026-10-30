package releon.ru.api.module.impl.movement;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.ClickType;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.CloseScreenEvent;
import releon.ru.api.events.impl.HotBarScrollEvent;
import releon.ru.api.events.impl.InputEvent;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.utils.inventory.InventoryFlowManager;
import releon.ru.utils.inventory.InventoryTask;
import releon.ru.utils.inventory.interaction.PlayerInteractionHelper;
import releon.ru.screens.clickgui.ClickGui;

import java.util.ArrayList;
import java.util.List;

public final class InventoryMove extends Module {
    private static final long DEFAULT_PHASE_DELAY_MS = 0;
    private static final long FUN_TIME_PHASE_DELAY_MS = 0L;
    private static final long FUN_TIME_CLOSE_DELAY_MS = 0;
    private static final long FUN_TIME_CLOSE_MAX_DELAY_MS = 0;
    private static final long FUN_TIME_RESTORE_DELAY_MS = 0;
    private static final long FUN_TIME_SOFT_CLICK_DELAY_MS = 1L;
    private static final double FUN_TIME_CLOSE_MAX_SPEED_SQ = 1.0E-4D;
    private static final int FUN_TIME_PACKETS_PER_TICK = 440;
    private static final int FUN_TIME_CLOSE_PACKETS_PER_TICK = 440;

    private static InventoryMove instance;

    private final ModeSetting mode = new ModeSetting("Mode", "Inventory move bypass mode.", "Normal",
            "Normal", "FunTime");
    private final List<Packet<?>> packets = new ArrayList<>();

    private MovePhase movePhase = MovePhase.READY;
    private long actionStartTime = 0L;
    private boolean playerFullyStopped = false;
    private boolean wasForwardPressed;
    private boolean wasBackPressed;
    private boolean wasLeftPressed;
    private boolean wasRightPressed;
    private boolean wasJumpPressed;
    private boolean keysOverridden = false;
    private boolean inventoryOpened = false;
    private boolean packetsHeld = false;
    private boolean closingScreen = false;
    private boolean delayedClosePending = false;
    private boolean bypassCloseEvent = false;
    private boolean softClickSlowdown = false;

    public InventoryMove() {
        super("Inventory Move", "Allows movement while screens are open.", ModuleCategory.MOVEMENT);
        register(mode);
        instance = this;
    }

    public static InventoryMove getInstance() {
        return instance;
    }

    public String getSelectedMode() {
        return mode.getValue();
    }

    public boolean shouldPreserveInventoryMovementInput() {
        return isEnabled()
                && !isClickGuiOpen()
                && isInventoryScreenOpen()
                && InventoryFlowManager.isMovementAllowed()
                && !shouldFreezeCloseInput();
    }

    public boolean shouldSuppressSprintInput() {
        if (softClickSlowdown && !delayedClosePending) {
            return false;
        }

        return !isClickGuiOpen() && (closingScreen || (movePhase != MovePhase.READY && !shouldForceInventorySprint()));
    }

    @Override
    protected void onDisable() {
        InventoryFlowManager.reset();
        resetState();
    }

    @Override
    public void onTick(Minecraft client) {
    }

    @SubscribeEvent
    private void onPreTick(TickEvent.Pre event) {
        Minecraft client = event.getClient();
        if (client.player == null || client.level == null) {
            resetState();
            return;
        }
        processLegitMovement();
    }

    @SubscribeEvent
    private void onPacket(PacketEvent event) {
        if (event.isSend()
                && event.getPacket() instanceof ServerboundContainerClosePacket
                && isFunTimeMode()
                && shouldDelayCloseScreen(mc.screen)
                && !bypassCloseEvent) {
            event.setCancelled(true);
            startDelayedClose();
            return;
        }

        if (event.isSend()
                && event.getPacket() instanceof ServerboundContainerClickPacket packet
                && isFunTimeMode()
                && (packetsHeld || hasDirectionalMovementInput())
                && InventoryFlowManager.shouldSkipExecution()) {
            packets.add(packet);
            event.setCancelled(true);
            packetsHeld = true;
            startSlowdownForInventoryClick(packet);
            return;
        }

        if (event.isReceive()
                && event.getPacket() instanceof ClientboundContainerClosePacket
                && shouldIgnoreServerClosePacket()) {
            event.setCancelled(true);
        }
    }

    @SubscribeEvent
    private void onCloseScreen(CloseScreenEvent event) {
        if (bypassCloseEvent) {
            return;
        }

        closingScreen = true;
        stopPlayerSprintBeforeClose();

        if (isFunTimeMode() && delayedClosePending) {
            event.setCancelled(true);
            return;
        }

        if (shouldDelayCloseScreen(event.getScreen())) {
            event.setCancelled(true);
            startDelayedClose();
            return;
        }

        if (packetsHeld && movePhase == MovePhase.ALLOW_MOVEMENT) {
            if (isFunTimeMode()) {
                event.setCancelled(true);
                delayedClosePending = true;
            }
            movePhase = MovePhase.SLOWING_DOWN;
            actionStartTime = System.currentTimeMillis();
        }
    }

    @SubscribeEvent
    private void onInput(InputEvent event) {
        if (event == null || mc.player == null) {
            return;
        }

        if (shouldFreezeCloseInput()) {
            event.inputNone();
            return;
        }

        if (shouldForceSoftClickSprint()) {
            event.setSprinting(true);
            return;
        }

        if (shouldForceInventorySprint()) {
            event.setSprinting(true);
        } else if (shouldSuppressSprintInput()) {
            event.setSprinting(false);
        }
    }

    @SubscribeEvent
    private void onScroll(HotBarScrollEvent event) {
        if (Minecraft.getInstance().screen != null && !(Minecraft.getInstance().screen instanceof ChatScreen)) {
            event.setCancelled(true);
        }
    }

    private void processLegitMovement() {
        if (isClickGuiOpen()) {
            return;
        }

        boolean hasOpenScreen = mc.screen != null;

        if (hasOpenScreen && !inventoryOpened && movePhase == MovePhase.READY) {
            closingScreen = false;
            startLegitMovement();
            inventoryOpened = true;
        }

        if (!hasOpenScreen && inventoryOpened) {
            stopInventorySprint();

            if (packetsHeld && movePhase == MovePhase.ALLOW_MOVEMENT) {
                movePhase = MovePhase.SLOWING_DOWN;
                actionStartTime = System.currentTimeMillis();
            } else if (!packetsHeld) {
                resetState();
            }
            inventoryOpened = false;
            closingScreen = packetsHeld || delayedClosePending;
            return;
        }

        if (movePhase != MovePhase.READY) {
            handleMovementStates();
        }
    }

    private void startLegitMovement() {
        captureMovementKeyStates();

        movePhase = MovePhase.ALLOW_MOVEMENT;
        keysOverridden = false;
        packetsHeld = false;
    }

    private void handleMovementStates() {
        for (int phaseBudget = 0; phaseBudget < 6; phaseBudget++) {
            long elapsed = System.currentTimeMillis() - actionStartTime;
            boolean continueSameTick = false;

            switch (movePhase) {
                case SLOWING_DOWN -> {
                    if (softClickSlowdown) {
                        keepSoftClickMovement();
                    } else if (!keysOverridden) {
                        mc.options.keyUp.setDown(false);
                        mc.options.keyDown.setDown(false);
                        mc.options.keyLeft.setDown(false);
                        mc.options.keyRight.setDown(false);
                        mc.options.keyJump.setDown(false);
                        keysOverridden = true;
                    }

                    if (!softClickSlowdown) {
                        stopInventorySprint();
                    }

                    long slowdownDelay = getSlowdownDelayMs();
                    if (!hasDelayElapsed(elapsed, slowdownDelay)) {
                        return;
                    }

                    movePhase = MovePhase.SEND_PACKETS;
                    actionStartTime = System.currentTimeMillis();
                    continueSameTick = isInstantDelay(slowdownDelay);
                }
                case ALLOW_MOVEMENT -> {
                    if (isInventoryScreenOpen()) {
                        InventoryFlowManager.updateMoveKeys();
                        forceInventorySprint();
                    }
                }
                case SEND_PACKETS -> {
                    if (softClickSlowdown) {
                        keepSoftClickMovement();
                    } else {
                        stopInventorySprint();
                    }

                    if (!packets.isEmpty()) {
                        if (isFunTimeMode()) {
                            if (!hasDelayElapsed(elapsed, FUN_TIME_PHASE_DELAY_MS)) {
                                return;
                            }

                            int packetLimit = delayedClosePending
                                    ? FUN_TIME_CLOSE_PACKETS_PER_TICK
                                    : FUN_TIME_PACKETS_PER_TICK;
                            int packetsToSend = Math.min(packetLimit, packets.size());
                            for (int i = 0; i < packetsToSend; i++) {
                                sendHeldPacket(packets.remove(0));
                            }
                            actionStartTime = System.currentTimeMillis();
                            if (!packets.isEmpty()) {
                                return;
                            }
                        }

                        if (!packets.isEmpty()) {
                            packets.forEach(this::sendHeldPacket);
                            packets.clear();
                        }
                    }

                    packetsHeld = false;
                    if (delayedClosePending) {
                        movePhase = MovePhase.CLOSE_SCREEN;
                        actionStartTime = System.currentTimeMillis();
                        continueSameTick = isInstantDelay(FUN_TIME_CLOSE_DELAY_MS) && FUN_TIME_CLOSE_MAX_DELAY_MS <= 1L;
                    } else {
                        movePhase = MovePhase.SPEEDING_UP;
                        actionStartTime = System.currentTimeMillis();
                        continueSameTick = isInstantDelay(getRestoreDelayMs());
                    }
                }
                case CLOSE_SCREEN -> {
                    stopPlayerSprintBeforeClose();

                    if (shouldWaitBeforeClose(elapsed)) {
                        return;
                    }

                    closeDelayedScreen();
                    movePhase = MovePhase.SPEEDING_UP;
                    actionStartTime = System.currentTimeMillis();
                    continueSameTick = isInstantDelay(getRestoreDelayMs());
                }
                case SPEEDING_UP -> {
                    long restoreDelay = getRestoreDelayMs();
                    if (!hasDelayElapsed(elapsed, restoreDelay)) {
                        if (softClickSlowdown) {
                            keepSoftClickMovement();
                        } else if (isFunTimeMode()) {
                            stopPlayerSprintBeforeClose();
                        }
                        return;
                    }

                    if (keysOverridden) {
                        restoreKeyStates();
                    }

                    movePhase = MovePhase.FINISHED;
                    continueSameTick = true;
                }
                case FINISHED -> {
                    resetState();
                    return;
                }
                default -> {
                    return;
                }
            }

            if (!continueSameTick) {
                return;
            }
        }
    }

    private void restoreKeyStates() {
        boolean currentForward = isKeyPressed(mc.options.keyUp);
        boolean currentBack = isKeyPressed(mc.options.keyDown);
        boolean currentLeft = isKeyPressed(mc.options.keyLeft);
        boolean currentRight = isKeyPressed(mc.options.keyRight);
        boolean currentJump = isKeyPressed(mc.options.keyJump);

        mc.options.keyUp.setDown(wasForwardPressed && currentForward);
        mc.options.keyDown.setDown(wasBackPressed && currentBack);
        mc.options.keyLeft.setDown(wasLeftPressed && currentLeft);
        mc.options.keyRight.setDown(wasRightPressed && currentRight);
        mc.options.keyJump.setDown(wasJumpPressed && currentJump);
        keysOverridden = false;
    }

    private float lerp(float start, float end, float delta) {
        return start + (end - start) * delta;
    }

    private void resetState() {
        if (keysOverridden) {
            restoreKeyStates();
        }
        movePhase = MovePhase.READY;
        playerFullyStopped = false;
        inventoryOpened = false;
        packetsHeld = false;
        closingScreen = false;
        delayedClosePending = false;
        bypassCloseEvent = false;
        softClickSlowdown = false;
        packets.clear();
        restoreSprintKeyState();
    }

    private void stopInventorySprint() {
        if (mc.options != null) {
            mc.options.keySprint.setDown(false);
        }
    }

    private void forceInventorySprint() {
        if (!hasDirectionalMovementInput()) {
            stopPlayerSprintBeforeClose();
            return;
        }

        if (mc.options != null) {
            mc.options.keySprint.setDown(true);
        }

        if (mc.player != null && !mc.player.isSprinting()) {
            mc.player.setSprinting(true);
        }
    }

    private void stopPlayerSprintBeforeClose() {
        stopInventorySprint();

        if (mc.player != null) {
            mc.player.setSprinting(false);
        }
    }

    private void keepSoftClickMovement() {
        if (isInventoryScreenOpen()) {
            InventoryFlowManager.updateMoveKeys();
            forceInventorySprint();
        }
    }

    private void restoreSprintKeyState() {
        if (mc.options != null) {
            mc.options.keySprint.setDown(isKeyPressed(mc.options.keySprint));
        }
    }

    private boolean isInventoryScreenOpen() {
        return mc.screen != null && !(mc.screen instanceof ChatScreen);
    }

    private boolean isClickGuiOpen() {
        return mc.screen instanceof ClickGui;
    }

    private boolean shouldForceInventorySprint() {
        return !closingScreen
                && InventoryFlowManager.isMovementAllowed()
                && isInventoryScreenOpen()
                && hasDirectionalMovementInput()
                && (movePhase == MovePhase.READY || movePhase == MovePhase.ALLOW_MOVEMENT);
    }

    private boolean shouldForceSoftClickSprint() {
        return softClickSlowdown
                && !delayedClosePending
                && InventoryFlowManager.isMovementAllowed()
                && isInventoryScreenOpen()
                && hasDirectionalMovementInput();
    }

    private boolean shouldFreezeCloseInput() {
        if (softClickSlowdown && !delayedClosePending) {
            return false;
        }

        return isFunTimeMode()
                && (delayedClosePending
                || movePhase == MovePhase.SLOWING_DOWN
                || movePhase == MovePhase.SEND_PACKETS
                || movePhase == MovePhase.CLOSE_SCREEN
                || movePhase == MovePhase.SPEEDING_UP);
    }

    private long getSlowdownDelayMs() {
        if (softClickSlowdown) {
            return FUN_TIME_SOFT_CLICK_DELAY_MS;
        }

        return isFunTimeMode() ? FUN_TIME_PHASE_DELAY_MS : DEFAULT_PHASE_DELAY_MS;
    }

    private long getRestoreDelayMs() {
        return isFunTimeMode() ? FUN_TIME_RESTORE_DELAY_MS : DEFAULT_PHASE_DELAY_MS;
    }

    private boolean hasDelayElapsed(long elapsed, long delayMs) {
        return isInstantDelay(delayMs) || elapsed >= delayMs;
    }

    private boolean isInstantDelay(long delayMs) {
        return delayMs <= 1L;
    }

    private boolean shouldWaitBeforeClose(long elapsed) {
        if (!hasDelayElapsed(elapsed, FUN_TIME_CLOSE_DELAY_MS)) {
            return true;
        }

        return FUN_TIME_CLOSE_MAX_DELAY_MS > 1L
                && elapsed < FUN_TIME_CLOSE_MAX_DELAY_MS
                && hasCloseMovement();
    }

    private boolean isFunTimeMode() {
        return "FunTime".equals(mode.getValue());
    }

    private boolean shouldDelayCloseScreen(Screen screen) {
        return isFunTimeMode()
                && screen != null
                && !(screen instanceof ChatScreen)
                && !(screen instanceof ClickGui);
    }

    private boolean shouldIgnoreServerClosePacket() {
        return isFunTimeMode()
                && (delayedClosePending
                || closingScreen
                || packetsHeld
                || movePhase == MovePhase.SLOWING_DOWN
                || movePhase == MovePhase.SEND_PACKETS
                || movePhase == MovePhase.CLOSE_SCREEN
                || movePhase == MovePhase.SPEEDING_UP);
    }

    private void startSlowdownForInventoryClick(ServerboundContainerClickPacket packet) {
        if (!isFunTimeMode()
                || movePhase != MovePhase.ALLOW_MOVEMENT
                || !hasDirectionalMovementInput()) {
            return;
        }

        ClickType clickType = packet.clickType();
        if (clickType != ClickType.PICKUP
                && clickType != ClickType.QUICK_CRAFT
                && clickType != ClickType.PICKUP_ALL
                && clickType != ClickType.QUICK_MOVE
                && clickType != ClickType.THROW) {
            return;
        }

        softClickSlowdown = true;
        keepSoftClickMovement();
        movePhase = MovePhase.SLOWING_DOWN;
        actionStartTime = System.currentTimeMillis();
    }

    private void sendHeldPacket(Packet<?> packet) {
        if (packet instanceof ServerboundContainerClickPacket clickPacket
                && mc.player != null
                && clickPacket.containerId() != mc.player.containerMenu.containerId) {
            return;
        }
        PlayerInteractionHelper.sendPacketWithOutEvent(packet);
    }

    private boolean hasCloseMovement() {
        if (mc.player == null) {
            return false;
        }

        double motionX = mc.player.getDeltaMovement().x;
        double motionZ = mc.player.getDeltaMovement().z;
        return motionX * motionX + motionZ * motionZ > FUN_TIME_CLOSE_MAX_SPEED_SQ;
    }

    private void startDelayedClose() {
        delayedClosePending = true;
        closingScreen = true;
        softClickSlowdown = false;
        stopPlayerSprintBeforeClose();

        if (movePhase == MovePhase.READY) {
            captureMovementKeyStates();
            keysOverridden = false;
            inventoryOpened = true;
            movePhase = MovePhase.ALLOW_MOVEMENT;
        }

        if (movePhase == MovePhase.ALLOW_MOVEMENT) {
            movePhase = MovePhase.SLOWING_DOWN;
            actionStartTime = System.currentTimeMillis();
        }
    }

    private void captureMovementKeyStates() {
        wasForwardPressed = isKeyPressed(mc.options.keyUp);
        wasBackPressed = isKeyPressed(mc.options.keyDown);
        wasLeftPressed = isKeyPressed(mc.options.keyLeft);
        wasRightPressed = isKeyPressed(mc.options.keyRight);
        wasJumpPressed = isKeyPressed(mc.options.keyJump);
    }

    private boolean hasDirectionalMovementInput() {
        return mc.options != null
                && (isKeyPressed(mc.options.keyUp)
                || isKeyPressed(mc.options.keyDown)
                || isKeyPressed(mc.options.keyLeft)
                || isKeyPressed(mc.options.keyRight));
    }

    private void closeDelayedScreen() {
        if (!delayedClosePending || mc.player == null) {
            return;
        }

        bypassCloseEvent = true;
        try {
            InventoryTask.closeScreen(false);
        } finally {
            bypassCloseEvent = false;
            delayedClosePending = false;
        }
    }

    private boolean isKeyPressed(KeyMapping key) {
        if (mc.getWindow() == null) {
            return false;
        }

        InputConstants.Key inputKey = InputConstants.getKey(key.saveString());
        long handle = mc.getWindow().handle();

        return switch (inputKey.getType()) {
            case KEYSYM -> InputConstants.isKeyDown(mc.getWindow(), inputKey.getValue());
            case MOUSE -> GLFW.glfwGetMouseButton(handle, inputKey.getValue()) == GLFW.GLFW_PRESS;
            default -> false;
        };
    }

    private enum MovePhase {
        READY,
        SLOWING_DOWN,
        ALLOW_MOVEMENT,
        CLOSE_SCREEN,
        SPEEDING_UP,
        SEND_PACKETS,
        FINISHED
    }
}
