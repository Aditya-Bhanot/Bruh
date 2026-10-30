package releon.ru.api.module.impl.combat.macetarget.armor;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.AngleConnection;
import releon.ru.api.module.impl.combat.aura.MathAngle;
import releon.ru.api.module.impl.combat.macetarget.state.MaceState.FireworkPhase;
import releon.ru.utils.inventory.lookup.InventoryUtils;
import releon.ru.utils.inventory.movement.MovementController;
import releon.ru.utils.inventory.swap.SwapSettings;

public class FireworkHandler {
    private static final Minecraft MC = Minecraft.getInstance();
    private final MovementController movement = new MovementController();
    private final SwapSettingsProvider settingsProvider;
    private FireworkPhase phase = FireworkPhase.IDLE;
    private int slot = -1;
    private int savedSlot = -1;
    private boolean fromInventory;
    private long phaseStartTime;
    private int currentDelay;

    @FunctionalInterface
    public interface SwapSettingsProvider {
        SwapSettings get();
    }

    public FireworkHandler(SwapSettingsProvider settingsProvider) {
        this.settingsProvider = settingsProvider;
    }

    public MovementController getMovement() {
        return movement;
    }

    public boolean isActive() {
        return phase != FireworkPhase.IDLE;
    }

    public void useFirework(boolean silent) {
        if (silent) {
            useSilent();
        } else {
            startLegit();
        }
    }

    private void useSilent() {
        if (MC.player == null || MC.getConnection() == null) {
            return;
        }
        int hotbarSlot = InventoryUtils.findHotbarItem(Items.FIREWORK_ROCKET);
        if (hotbarSlot != -1) {
            int currentSlot = MC.player.getInventory().getSelectedSlot();
            if (hotbarSlot != currentSlot) {
                MC.getConnection().send(new ServerboundSetCarriedItemPacket(hotbarSlot));
            }
            sendUse();
            if (hotbarSlot != currentSlot) {
                MC.getConnection().send(new ServerboundSetCarriedItemPacket(currentSlot));
            }
            return;
        }
        int invSlot = InventoryUtils.findItemInInventory(Items.FIREWORK_ROCKET);
        if (invSlot != -1) {
            int currentHotbarSlot = MC.player.getInventory().getSelectedSlot();
            int wrappedSlot = InventoryUtils.wrapSlot(invSlot);
            InventoryUtils.click(wrappedSlot, currentHotbarSlot, ClickType.SWAP);
            sendUse();
            InventoryUtils.click(wrappedSlot, currentHotbarSlot, ClickType.SWAP);
            InventoryUtils.closeScreen();
        }
    }

    private void startLegit() {
        if (MC.player == null) {
            return;
        }
        savedSlot = MC.player.getInventory().getSelectedSlot();
        int hotbarSlot = InventoryUtils.findHotbarItem(Items.FIREWORK_ROCKET);
        if (hotbarSlot != -1) {
            slot = hotbarSlot;
            fromInventory = false;
            InventoryUtils.selectSlot(slot);
            startPhase(FireworkPhase.AWAIT_ITEM, 0);
            return;
        }
        int invSlot = InventoryUtils.findItemInInventory(Items.FIREWORK_ROCKET);
        if (invSlot != -1) {
            slot = invSlot;
            fromInventory = true;
            SwapSettings settings = settingsProvider.get();
            startPhase(settings.shouldStopMovement() ? FireworkPhase.PRE_STOP : FireworkPhase.SWAP_TO_HAND, settings.shouldStopMovement() ? settings.randomPreStopDelay() : 0);
        }
    }

    public void processLoop() {
        int iterations = 0;
        while (phase != FireworkPhase.IDLE && iterations++ < 10 && processTick()) {
            // Process zero-delay phases immediately.
        }
    }

    private boolean processTick() {
        if (MC.player == null) {
            reset();
            return false;
        }
        long elapsed = System.currentTimeMillis() - phaseStartTime;
        SwapSettings settings = settingsProvider.get();
        switch (phase) {
            case PRE_STOP -> {
                if (elapsed >= currentDelay) {
                    movement.saveState();
                    movement.block();
                    if (settings.shouldStopSprint()) {
                        movement.stopSprint();
                    }
                    startPhase(FireworkPhase.STOPPING, 0);
                    return true;
                }
            }
            case STOPPING -> {
                movement.block();
                if (settings.shouldStopSprint()) {
                    movement.stopSprint();
                }
                startPhase(FireworkPhase.WAIT_STOP, settings.randomWaitStopDelay());
                return currentDelay == 0;
            }
            case WAIT_STOP -> {
                movement.block();
                if (movement.isPlayerStopped(settings.getVelocityThreshold()) || elapsed >= currentDelay) {
                    startPhase(FireworkPhase.PRE_SWAP, settings.randomPreSwapDelay());
                    return currentDelay == 0;
                }
            }
            case PRE_SWAP -> {
                movement.block();
                if (elapsed >= currentDelay) {
                    startPhase(FireworkPhase.SWAP_TO_HAND, 0);
                    return true;
                }
            }
            case SWAP_TO_HAND -> {
                InventoryUtils.click(InventoryUtils.wrapSlot(slot), MC.player.getInventory().getSelectedSlot(), ClickType.SWAP);
                startPhase(FireworkPhase.AWAIT_ITEM, 0);
                return true;
            }
            case AWAIT_ITEM -> {
                if (MC.player.getMainHandItem().getItem() == Items.FIREWORK_ROCKET) {
                    startPhase(FireworkPhase.USE, 0);
                    return true;
                }
            }
            case USE -> {
                sendUse();
                MC.player.swing(InteractionHand.MAIN_HAND);
                startPhase(FireworkPhase.POST_USE, settings.randomPostSwapDelay());
                return currentDelay == 0;
            }
            case POST_USE -> {
                if (elapsed >= currentDelay) {
                    if (fromInventory) {
                        startPhase(FireworkPhase.SWAP_BACK, 0);
                    } else {
                        InventoryUtils.selectSlot(savedSlot);
                        startPhase(FireworkPhase.RESUMING, settings.randomResumeDelay());
                    }
                    return true;
                }
            }
            case SWAP_BACK -> {
                InventoryUtils.click(InventoryUtils.wrapSlot(slot), MC.player.getInventory().getSelectedSlot(), ClickType.SWAP);
                InventoryUtils.selectSlot(savedSlot);
                if (settings.shouldCloseInventory()) {
                    InventoryUtils.closeScreen();
                }
                startPhase(FireworkPhase.RESUMING, settings.randomResumeDelay());
                return currentDelay == 0;
            }
            case RESUMING -> {
                if (elapsed >= currentDelay) {
                    movement.restoreFromCurrent();
                    reset();
                }
            }
        }
        return false;
    }

    private void sendUse() {
        Angle rotation = AngleConnection.INSTANCE.getRotation();
        if (rotation == null) {
            rotation = MathAngle.cameraAngle();
        }
        InventoryUtils.sendUsePacket(InteractionHand.MAIN_HAND, rotation.getYaw(), rotation.getPitch());
    }

    private void startPhase(FireworkPhase phase, int delay) {
        this.phase = phase;
        phaseStartTime = System.currentTimeMillis();
        currentDelay = delay;
    }

    public void reset() {
        movement.reset();
        phase = FireworkPhase.IDLE;
        slot = -1;
        savedSlot = -1;
        fromInventory = false;
        phaseStartTime = 0L;
        currentDelay = 0;
    }

    public void forceRestore() {
        if (movement.isBlocked()) {
            movement.restoreFromCurrent();
        }
    }
}
