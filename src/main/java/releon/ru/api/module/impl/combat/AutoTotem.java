package releon.ru.api.module.impl.combat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.MultiModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.inventory.InventoryTask;

import java.util.Locale;
import java.util.function.Predicate;

public class AutoTotem extends Module {
    private static final long EQUIP_ATTEMPT_COOLDOWN_MS = 250L;
    private static final long SLOWING_DOWN_DELAY_MS = 1L;
    private static final long SWAP_DELAY_MS = 25L;
    private static final long AWAIT_SWITCH_TIMEOUT_MS = 50L;
    private static final long RESTORE_SLOT_DELAY_MS = 25L;
    private static final long SPEEDING_UP_DELAY_MS = 25L;

    private final NumberSetting healthThreshold = register(new NumberSetting("Health", "Health threshold.", 4.5, 1.0, 20.0, 0.5));
    private final NumberSetting elytraHealth = register(new NumberSetting("Elytra Health", "Elytra health threshold.", 8.5, 1.0, 20.0, 0.5));
    private final MultiModeSetting triggers = register(new MultiModeSetting("Triggers", "Totem equip triggers.", new String[]{"Crystal", "Fall", "Elytra"}, "Crystal", "Fall", "Elytra"));
    private final BooleanSetting returnItem = register(new BooleanSetting("Return Item", "Return offhand item after danger.", true));
    private final NumberSetting crystalDistance = register(new NumberSetting("Crystal Distance", "Danger crystal distance.", 4.0, 1.0, 6.0, 0.5));
    private final NumberSetting crystalHeight = register(new NumberSetting("Crystal Height", "Danger crystal vertical distance.", 2.5, 0.5, 8.0, 0.5));
    private final NumberSetting fallHeight = register(new NumberSetting("Fall Height", "Danger fall height.", 15.0, 5.0, 50.0, 1.0));

    private int savedSlot = -1;
    private int totemSlot = -1;
    private int previousOffhandSourceSlot = -1;
    private float fallStartY;
    private boolean wasFalling;
    private boolean keysOverridden;
    private boolean wasForwardPressed;
    private boolean wasBackPressed;
    private boolean wasLeftPressed;
    private boolean wasRightPressed;
    private boolean wasJumpPressed;
    private boolean wasSprintPressed;
    private ItemStack previousOffhandStack = ItemStack.EMPTY;
    private boolean needsReturn;
    private long actionStartTime;
    private long lastEquipAttemptMs;
    private Phase phase = Phase.READY;

    private enum Phase {
        READY,
        SLOWING_DOWN,
        SWAP_TOTEM,
        AWAIT_SWITCH,
        RESTORE_SLOT,
        RETURN_SLOWING_DOWN,
        RETURN_ITEM,
        SPEEDING_UP
    }

    public AutoTotem() {
        super("Auto Totem", "Automatically equips totems.", ModuleCategory.COMBAT);
        crystalDistance.visibleWhen(() -> triggers.isSelected("Crystal"));
        crystalHeight.visibleWhen(() -> triggers.isSelected("Crystal"));
        fallHeight.visibleWhen(() -> triggers.isSelected("Fall"));
        elytraHealth.visibleWhen(() -> triggers.isSelected("Elytra"));
    }

    @Override
    public void onTick(Minecraft client) {
        if (client.player == null || client.gameMode == null || client.level == null) {
            resetState();
            clearReturnState();
            return;
        }

        updateFallTracking(client);

        if (phase != Phase.READY) {
            execute(client);
            return;
        }

        boolean shouldEquip = shouldEquipTotem(client);
        if (shouldEquip) {
            long now = System.currentTimeMillis();
            if (now - lastEquipAttemptMs >= EQUIP_ATTEMPT_COOLDOWN_MS) {
                tryEquipTotem(client);
            }
            return;
        }

        if (needsReturn && returnItem.getValue() && !previousOffhandStack.isEmpty()) {
            if (isTotemInOffhand(client)) {
                startLegitReturn(client);
            } else {
                clearReturnState();
            }
        }
    }

    @Override
    protected void onDisable() {
        resetState();
        clearReturnState();
    }

    private void tryEquipTotem(Minecraft client) {
        if (phase != Phase.READY || client.screen != null || isPreferredTotemAlreadyInOffhand(client)) {
            return;
        }

        lastEquipAttemptMs = System.currentTimeMillis();
        Slot slot = findPreferredTotemSlot(client);
        if (slot == null) {
            return;
        }

        int slotId = getMenuSlotId(client, slot);
        if (slotId == -1) {
            return;
        }

        savedSlot = client.player.getInventory().getSelectedSlot();
        totemSlot = slotId;
        previousOffhandStack = client.player.getOffhandItem().copy();
        previousOffhandSourceSlot = slotId;
        needsReturn = !previousOffhandStack.isEmpty();
        startLegitEquip(client);
    }

    private void startLegitEquip(Minecraft client) {
        captureKeyStates(client);
        phase = Phase.SLOWING_DOWN;
        actionStartTime = System.currentTimeMillis();
        keysOverridden = false;
    }

    private void startLegitReturn(Minecraft client) {
        captureKeyStates(client);
        phase = Phase.RETURN_SLOWING_DOWN;
        actionStartTime = System.currentTimeMillis();
        keysOverridden = false;
    }

    private void execute(Minecraft client) {
        if (client.player == null || client.gameMode == null || client.screen != null) {
            resetState();
            return;
        }

        long elapsed = System.currentTimeMillis() - actionStartTime;
        switch (phase) {
            case SLOWING_DOWN -> {
                stopPlayerInput(client);
                if (elapsed > SLOWING_DOWN_DELAY_MS) {
                    phase = Phase.SWAP_TOTEM;
                    actionStartTime = System.currentTimeMillis();
                }
            }
            case SWAP_TOTEM -> {
                stopPlayerInput(client);
                if (elapsed <= SWAP_DELAY_MS) {
                    return;
                }
                if (totemSlot == -1 || !clickOffhandSwap(client, totemSlot)) {
                    resetState();
                    return;
                }
                phase = Phase.AWAIT_SWITCH;
                actionStartTime = System.currentTimeMillis();
            }
            case AWAIT_SWITCH -> {
                stopPlayerInput(client);
                if (isTotemInOffhand(client) || elapsed > AWAIT_SWITCH_TIMEOUT_MS) {
                    phase = Phase.RESTORE_SLOT;
                    actionStartTime = System.currentTimeMillis();
                }
            }
            case RESTORE_SLOT -> {
                stopPlayerInput(client);
                if (elapsed <= RESTORE_SLOT_DELAY_MS) {
                    return;
                }
                if (savedSlot >= 0 && savedSlot <= 8) {
                    InventoryTask.switchTo(savedSlot);
                }
                phase = Phase.SPEEDING_UP;
                actionStartTime = System.currentTimeMillis();
            }
            case RETURN_SLOWING_DOWN -> {
                stopPlayerInput(client);
                if (elapsed > SLOWING_DOWN_DELAY_MS) {
                    phase = Phase.RETURN_ITEM;
                    actionStartTime = System.currentTimeMillis();
                }
            }
            case RETURN_ITEM -> {
                stopPlayerInput(client);
                if (elapsed <= SWAP_DELAY_MS) {
                    return;
                }
                if (attemptReturnPreviousItem(client)) {
                    clearReturnState();
                    phase = Phase.SPEEDING_UP;
                    actionStartTime = System.currentTimeMillis();
                } else {
                    resetState();
                    clearReturnState();
                }
            }
            case SPEEDING_UP -> {
                if (elapsed >= SPEEDING_UP_DELAY_MS) {
                    restoreKeyStates(client);
                    resetState();
                }
            }
            case READY -> {
            }
        }
    }

    private void updateFallTracking(Minecraft client) {
        boolean falling = client.player.getDeltaMovement().y < -0.1 && !client.player.onGround() && !client.player.onClimbable() && !client.player.isInWater();
        if (falling && !wasFalling) {
            fallStartY = (float) client.player.getY();
        }
        if (client.player.onGround() || client.player.isInWater() || client.player.onClimbable()) {
            fallStartY = (float) client.player.getY();
        }
        wasFalling = falling;
    }

    private boolean shouldEquipTotem(Minecraft client) {
        if (client.player.getHealth() <= healthThreshold.getFloat()) {
            return true;
        }
        if (triggers.isSelected("Elytra") && client.player.isFallFlying() && client.player.getHealth() <= elytraHealth.getFloat()) {
            return true;
        }
        if (triggers.isSelected("Fall") && fallStartY - (float) client.player.getY() >= fallHeight.getFloat() && client.player.getDeltaMovement().y < -0.1) {
            return true;
        }
        return triggers.isSelected("Crystal") && checkCrystalDanger(client);
    }

    private boolean checkCrystalDanger(Minecraft client) {
        double maxHorizontal = crystalDistance.getValue();
        double maxVertical = crystalHeight.getValue();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof EndCrystal)) {
                continue;
            }
            double vertical = Math.abs(entity.getY() - client.player.getY());
            if (vertical > maxVertical) {
                continue;
            }
            double horizontal = Math.hypot(entity.getX() - client.player.getX(), entity.getZ() - client.player.getZ());
            if (horizontal <= maxHorizontal) {
                return true;
            }
        }
        return false;
    }

    private Slot findPreferredTotemSlot(Minecraft client) {
        Slot plain = findSlot(client, this::isPlainTotem);
        if (plain != null) {
            return plain;
        }

        Slot nonSphere = findSlot(client, this::isTotemButNotSphere);
        if (nonSphere != null) {
            return nonSphere;
        }

        return findSlot(client, stack -> stack.getItem() == Items.TOTEM_OF_UNDYING);
    }

    private Slot findSlot(Minecraft client, Predicate<ItemStack> predicate) {
        for (int i = 0; i < client.player.inventoryMenu.slots.size(); i++) {
            Slot slot = client.player.inventoryMenu.getSlot(i);
            if (!isValidInventorySlot(client, i, slot)) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && predicate.test(stack)) {
                return slot;
            }
        }
        return null;
    }

    private boolean isValidInventorySlot(Minecraft client, int slotId, Slot slot) {
        return slot != null
                && slot.container == client.player.getInventory()
                && slotId != 45
                && slotId != 46;
    }

    private boolean isPlainTotem(ItemStack stack) {
        return isTotemButNotSphere(stack) && !stack.isEnchanted();
    }

    private boolean isTotemButNotSphere(ItemStack stack) {
        return stack.getItem() == Items.TOTEM_OF_UNDYING && !isSphereTotem(stack);
    }

    private boolean isSphereTotem(ItemStack stack) {
        if (stack.getItem() != Items.TOTEM_OF_UNDYING) {
            return false;
        }
        String name = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
        return name.contains("sphere");
    }

    private boolean isTotemInOffhand(Minecraft client) {
        return client.player.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING;
    }

    private boolean isPreferredTotemAlreadyInOffhand(Minecraft client) {
        if (!isTotemInOffhand(client)) {
            return false;
        }
        ItemStack offhand = client.player.getOffhandItem();
        return isPlainTotem(offhand) || findSlot(client, this::isPlainTotem) == null;
    }

    private boolean attemptReturnPreviousItem(Minecraft client) {
        if (!returnItem.getValue() || previousOffhandStack.isEmpty() || !isTotemInOffhand(client)) {
            return false;
        }

        if (isValidMenuSlot(client, previousOffhandSourceSlot)) {
            Slot restoreSlot = client.player.inventoryMenu.getSlot(previousOffhandSourceSlot);
            if (stackMatchesForReturn(restoreSlot.getItem())) {
                return clickOffhandSwap(client, previousOffhandSourceSlot);
            }
        }

        Slot exactSlot = findSlot(client, this::stackMatchesForReturn);
        if (exactSlot != null) {
            return clickOffhandSwap(client, getMenuSlotId(client, exactSlot));
        }

        Slot sameItemSlot = findSlot(client, stack -> !stack.isEmpty() && stack.getItem() == previousOffhandStack.getItem());
        return sameItemSlot != null && clickOffhandSwap(client, getMenuSlotId(client, sameItemSlot));
    }

    private boolean stackMatchesForReturn(ItemStack stack) {
        if (stack == null || stack.isEmpty() || previousOffhandStack.isEmpty()) {
            return false;
        }
        return stack.getItem() == previousOffhandStack.getItem()
                && stack.isEnchanted() == previousOffhandStack.isEnchanted()
                && stack.getHoverName().getString().equals(previousOffhandStack.getHoverName().getString());
    }

    private boolean clickOffhandSwap(Minecraft client, int slotId) {
        if (!isValidMenuSlot(client, slotId)) {
            return false;
        }
        client.gameMode.handleInventoryMouseClick(client.player.inventoryMenu.containerId, slotId, 40, ClickType.SWAP, client.player);
        return true;
    }

    private boolean isValidMenuSlot(Minecraft client, int slotId) {
        return slotId >= 0 && slotId < client.player.inventoryMenu.slots.size();
    }

    private int getMenuSlotId(Minecraft client, Slot slot) {
        if (slot == null) {
            return -1;
        }
        for (int i = 0; i < client.player.inventoryMenu.slots.size(); i++) {
            if (client.player.inventoryMenu.getSlot(i) == slot) {
                return i;
            }
        }
        return -1;
    }

    private void captureKeyStates(Minecraft client) {
        wasForwardPressed = isCurrentlyPressed(client, client.options.keyUp);
        wasBackPressed = isCurrentlyPressed(client, client.options.keyDown);
        wasLeftPressed = isCurrentlyPressed(client, client.options.keyLeft);
        wasRightPressed = isCurrentlyPressed(client, client.options.keyRight);
        wasJumpPressed = isCurrentlyPressed(client, client.options.keyJump);
        wasSprintPressed = isCurrentlyPressed(client, client.options.keySprint);
    }

    private void stopPlayerInput(Minecraft client) {
        client.player.setSprinting(false);
        if (client.options == null) {
            return;
        }
        client.options.keyUp.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keySprint.setDown(false);
        keysOverridden = true;
    }

    private void restoreKeyStates(Minecraft client) {
        if (!keysOverridden || client.options == null) {
            return;
        }
        client.options.keyUp.setDown(wasForwardPressed && isCurrentlyPressed(client, client.options.keyUp));
        client.options.keyDown.setDown(wasBackPressed && isCurrentlyPressed(client, client.options.keyDown));
        client.options.keyLeft.setDown(wasLeftPressed && isCurrentlyPressed(client, client.options.keyLeft));
        client.options.keyRight.setDown(wasRightPressed && isCurrentlyPressed(client, client.options.keyRight));
        client.options.keyJump.setDown(wasJumpPressed && isCurrentlyPressed(client, client.options.keyJump));
        client.options.keySprint.setDown(wasSprintPressed && isCurrentlyPressed(client, client.options.keySprint));
        keysOverridden = false;
    }

    private boolean isCurrentlyPressed(Minecraft client, KeyMapping key) {
        if (client.getWindow() == null || key == null) {
            return false;
        }
        return InputConstants.isKeyDown(client.getWindow(), InputConstants.getKey(key.saveString()).getValue());
    }

    private void clearReturnState() {
        previousOffhandStack = ItemStack.EMPTY;
        previousOffhandSourceSlot = -1;
        needsReturn = false;
    }

    private void resetState() {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            restoreKeyStates(client);
        }
        savedSlot = -1;
        totemSlot = -1;
        actionStartTime = 0L;
        phase = Phase.READY;
        keysOverridden = false;
    }
}
