package releon.ru.api.module.impl.misc;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.DrawEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.MaceTarget;
import releon.ru.api.settings.Setting;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.manager.ActionManager;
import releon.ru.utils.inventory.lookup.InventoryUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * GrimAC-Safe Click-to-Bind ElytraHelper (Mojang 1.21.11 Official Mappings):
 * - Includes Click-to-Bind "Toggle Bind: [NONE]" to turn ElytraHelper ON/OFF with any key or mouse button,
 *   plus "Swap Key: [R]" and "Firework Key: [G]".
 * - Executes hotbar slot selection + KeyMapping.click(keyUse) at START_CLIENT_TICK (before Minecraft.handleKeybinds()
 *   and before LocalPlayer.aiStep()), eliminating GrimAC Simulation, BadPacketsJ, and Post flags.
 */
public class ElytraHelper extends Module {
    private static final List<Item> CHESTPLATES = List.of(
            Items.NETHERITE_CHESTPLATE,
            Items.DIAMOND_CHESTPLATE,
            Items.IRON_CHESTPLATE,
            Items.CHAINMAIL_CHESTPLATE,
            Items.GOLDEN_CHESTPLATE,
            Items.LEATHER_CHESTPLATE
    );

    private final BooleanSetting bindToggleKey = register(new BooleanSetting(
            "Toggle Bind: [NONE]",
            "Click this, then press any key or mouse button to bind module ON/OFF toggle (ESC/BACKSPACE clears).",
            false
    ));
    private final BooleanSetting bindSwapKey = register(new BooleanSetting(
            "Swap Key: [R]",
            "Click this, then press any key or mouse button to bind Elytra/Chestplate Swap Key (ESC/BACKSPACE clears).",
            false
    ));
    private final BooleanSetting bindFireworkKey = register(new BooleanSetting(
            "Firework Key: [G]",
            "Click this, then press any key or mouse button to bind Firework Key (ESC/BACKSPACE clears).",
            false
    ));
    private final BooleanSetting restoreSlot = register(new BooleanSetting(
            "Restore Slot",
            "Restore previous weapon/hotbar slot on the tick after equipping or using a firework.",
            true
    ));

    private final NumberSetting savedToggleKeyCode = register(new NumberSetting(
            "_ehToggleKeyCode",
            "Internal saved GLFW keycode for module Toggle Bind.",
            -1.0D,
            -100.0D,
            400.0D,
            1.0D
    ));
    private final NumberSetting savedSwapKeyCode = register(new NumberSetting(
            "_ehSwapKeyCode",
            "Internal saved GLFW keycode for Swap Key.",
            (double) GLFW.GLFW_KEY_R,
            -100.0D,
            400.0D,
            1.0D
    ));
    private final NumberSetting savedFireworkKeyCode = register(new NumberSetting(
            "_ehFireworkKeyCode",
            "Internal saved GLFW keycode for Firework Key.",
            (double) GLFW.GLFW_KEY_G,
            -100.0D,
            400.0D,
            1.0D
    ));

    private boolean wasTogglePressed = false;
    private boolean wasSwapPressed = false;
    private boolean wasFireworkPressed = false;
    private boolean waitingForToggleMouseRelease = false;
    private boolean waitingForSwapMouseRelease = false;
    private boolean waitingForFireworkMouseRelease = false;
    private boolean wasListeningToggle = false;
    private boolean wasListeningSwap = false;
    private boolean wasListeningFirework = false;
    private int lastDisplayedToggleKey = Integer.MIN_VALUE;
    private int lastDisplayedSwapKey = Integer.MIN_VALUE;
    private int lastDisplayedFireworkKey = Integer.MIN_VALUE;
    private int lastProcessedTick = -100;

    public ElytraHelper() {
        super("ElytraHelper", "GrimAC-safe click-to-bind hotbar Elytra & Chestplate swapper.", ModuleCategory.MOVEMENT);
        savedToggleKeyCode.visibleWhen(() -> false);
        savedSwapKeyCode.visibleWhen(() -> false);
        savedFireworkKeyCode.visibleWhen(() -> false);
        bindToggleKey.visibleWhen(() -> {
            pollClickToBindCapture();
            return true;
        });
        bindSwapKey.visibleWhen(() -> {
            pollClickToBindCapture();
            return true;
        });
        refreshBindLabels();

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            pollClickToBindCapture();
            pollModuleToggleBind(client);
            if (isEnabled() && client != null && client.player != null && client.level != null) {
                if (lastProcessedTick != client.player.tickCount) {
                    lastProcessedTick = client.player.tickCount;
                    ActionManager.onPreTickRender(client);
                    pollManualKeybinds();
                }
            }
        });
    }

    @Override
    protected void onEnable() {
        wasSwapPressed = true;
        wasFireworkPressed = true;
        lastProcessedTick = -100;
        refreshBindLabels();
    }

    @Override
    protected void onDisable() {
        wasSwapPressed = false;
        wasFireworkPressed = false;
        lastProcessedTick = -100;
        if (bindSwapKey.getValue()) {
            bindSwapKey.setValue(false);
        }
        if (bindFireworkKey.getValue()) {
            bindFireworkKey.setValue(false);
        }
        refreshBindLabels();
        ActionManager.reset();
    }

    @SubscribeEvent
    public void onDraw(DrawEvent event) {
        pollClickToBindCapture();
    }

    @SubscribeEvent
    public void onTick(TickEvent.Pre event) {
        if (!isEnabled() || mc.player == null || mc.level == null) {
            return;
        }
        if (lastProcessedTick != mc.player.tickCount) {
            lastProcessedTick = mc.player.tickCount;
            ActionManager.onPreTickRender(mc);
            pollManualKeybinds();
        }
    }

    private void pollModuleToggleBind(Minecraft client) {
        if (client == null || client.player == null || client.screen != null
                || bindToggleKey.getValue() || bindSwapKey.getValue() || bindFireworkKey.getValue()) {
            wasTogglePressed = true;
            return;
        }
        int toggleCode = (int) Math.round(savedToggleKeyCode.getValue());
        boolean isDown = isBoundInputDown(toggleCode);
        if (isDown && !wasTogglePressed) {
            setEnabled(!isEnabled());
        }
        wasTogglePressed = isDown;
    }

    private void pollManualKeybinds() {
        if (mc.player == null || mc.level == null || mc.screen != null
                || bindToggleKey.getValue() || bindSwapKey.getValue() || bindFireworkKey.getValue()) {
            wasSwapPressed = true;
            wasFireworkPressed = true;
            return;
        }

        if (mc.player.isUsingItem()) {
            return;
        }

        // 1. Strictly Manual Swap Keybind: ONLY equip or unequip Elytra on a press of the Swap Key!
        int swapCode = (int) Math.round(savedSwapKeyCode.getValue());
        boolean isSwapDown = isBoundInputDown(swapCode);
        if (isSwapDown && !wasSwapPressed && !ActionManager.isBusy()) {
            triggerLegitArmorToggle();
        }
        wasSwapPressed = isSwapDown;

        // 2. Manual Firework Boost Keybind
        int fireworkCode = (int) Math.round(savedFireworkKeyCode.getValue());
        boolean isFireworkDown = isBoundInputDown(fireworkCode);
        if (isFireworkDown && !wasFireworkPressed && mc.player.isFallFlying() && !ActionManager.isBusy()) {
            triggerLegitFireworkUse();
        }
        wasFireworkPressed = isFireworkDown;
    }

    private void pollClickToBindCapture() {
        long windowHandle = GLFW.glfwGetCurrentContext();
        if (windowHandle == 0L) {
            return;
        }

        boolean listeningToggle = bindToggleKey.getValue();
        boolean listeningSwap = bindSwapKey.getValue();
        boolean listeningFirework = bindFireworkKey.getValue();

        if (listeningToggle) {
            if (listeningSwap) {
                bindSwapKey.setValue(false);
                listeningSwap = false;
            }
            if (listeningFirework) {
                bindFireworkKey.setValue(false);
                listeningFirework = false;
            }
            if (!wasListeningToggle) {
                wasListeningToggle = true;
                waitingForToggleMouseRelease = true;
                updateSettingName(bindToggleKey, "Toggle Bind: [Press any key...]");
            }
            if (waitingForToggleMouseRelease) {
                if (!isMouseLeftOrRightDown(windowHandle)) {
                    waitingForToggleMouseRelease = false;
                }
                return;
            }
            Integer captured = pollFirstPressedKeyOrMouse(windowHandle);
            if (captured != null) {
                savedToggleKeyCode.setValue((double) captured);
                bindToggleKey.setValue(false);
                wasListeningToggle = false;
                wasTogglePressed = true;
                lastDisplayedToggleKey = Integer.MIN_VALUE;
                refreshBindLabels();
            }
            return;
        } else if (wasListeningToggle) {
            wasListeningToggle = false;
            lastDisplayedToggleKey = Integer.MIN_VALUE;
            refreshBindLabels();
        }

        if (listeningSwap && listeningFirework) {
            if (wasListeningSwap) {
                bindSwapKey.setValue(false);
                listeningSwap = false;
            } else {
                bindFireworkKey.setValue(false);
                listeningFirework = false;
            }
        }

        if (listeningSwap) {
            if (!wasListeningSwap) {
                wasListeningSwap = true;
                waitingForSwapMouseRelease = true;
                updateSettingName(bindSwapKey, "Swap Key: [Press any key...]");
            }
            if (waitingForSwapMouseRelease) {
                if (!isMouseLeftOrRightDown(windowHandle)) {
                    waitingForSwapMouseRelease = false;
                }
                return;
            }
            Integer captured = pollFirstPressedKeyOrMouse(windowHandle);
            if (captured != null) {
                savedSwapKeyCode.setValue((double) captured);
                bindSwapKey.setValue(false);
                wasListeningSwap = false;
                wasSwapPressed = true;
                lastDisplayedSwapKey = Integer.MIN_VALUE;
                refreshBindLabels();
            }
            return;
        } else if (wasListeningSwap) {
            wasListeningSwap = false;
            lastDisplayedSwapKey = Integer.MIN_VALUE;
            refreshBindLabels();
        }

        if (listeningFirework) {
            if (!wasListeningFirework) {
                wasListeningFirework = true;
                waitingForFireworkMouseRelease = true;
                updateSettingName(bindFireworkKey, "Firework Key: [Press any key...]");
            }
            if (waitingForFireworkMouseRelease) {
                if (!isMouseLeftOrRightDown(windowHandle)) {
                    waitingForFireworkMouseRelease = false;
                }
                return;
            }
            Integer captured = pollFirstPressedKeyOrMouse(windowHandle);
            if (captured != null) {
                savedFireworkKeyCode.setValue((double) captured);
                bindFireworkKey.setValue(false);
                wasListeningFirework = false;
                wasFireworkPressed = true;
                lastDisplayedFireworkKey = Integer.MIN_VALUE;
                refreshBindLabels();
            }
            return;
        } else if (wasListeningFirework) {
            wasListeningFirework = false;
            lastDisplayedFireworkKey = Integer.MIN_VALUE;
            refreshBindLabels();
        }

        refreshBindLabels();
    }

    private void refreshBindLabels() {
        int toggleCode = (int) Math.round(savedToggleKeyCode.getValue());
        if (!bindToggleKey.getValue() && toggleCode != lastDisplayedToggleKey) {
            lastDisplayedToggleKey = toggleCode;
            updateSettingName(bindToggleKey, "Toggle Bind: [" + formatKeyCode(toggleCode) + "]");
        }

        int swapCode = (int) Math.round(savedSwapKeyCode.getValue());
        if (!bindSwapKey.getValue() && swapCode != lastDisplayedSwapKey) {
            lastDisplayedSwapKey = swapCode;
            updateSettingName(bindSwapKey, "Swap Key: [" + formatKeyCode(swapCode) + "]");
        }

        int fwCode = (int) Math.round(savedFireworkKeyCode.getValue());
        if (!bindFireworkKey.getValue() && fwCode != lastDisplayedFireworkKey) {
            lastDisplayedFireworkKey = fwCode;
            updateSettingName(bindFireworkKey, "Firework Key: [" + formatKeyCode(fwCode) + "]");
        }
    }

    private boolean isMouseLeftOrRightDown(long windowHandle) {
        return GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
    }

    private Integer pollFirstPressedKeyOrMouse(long windowHandle) {
        if (GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_DELETE) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_BACKSPACE) == GLFW.GLFW_PRESS) {
            return -1;
        }

        for (int btn = GLFW.GLFW_MOUSE_BUTTON_MIDDLE; btn <= GLFW.GLFW_MOUSE_BUTTON_8; btn++) {
            if (GLFW.glfwGetMouseButton(windowHandle, btn) == GLFW.GLFW_PRESS) {
                return -10 - btn;
            }
        }

        for (int key = GLFW.GLFW_KEY_SPACE; key <= GLFW.GLFW_KEY_LAST; key++) {
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) {
                continue;
            }
            if (GLFW.glfwGetKey(windowHandle, key) == GLFW.GLFW_PRESS) {
                return key;
            }
        }
        return null;
    }

    private boolean isBoundInputDown(int code) {
        if (code == -1) {
            return false;
        }
        long windowHandle = GLFW.glfwGetCurrentContext();
        if (windowHandle == 0L) {
            return false;
        }
        if (code <= -10) {
            int btn = -10 - code;
            return btn >= 0 && btn <= GLFW.GLFW_MOUSE_BUTTON_8
                    && GLFW.glfwGetMouseButton(windowHandle, btn) == GLFW.GLFW_PRESS;
        }
        return code >= GLFW.GLFW_KEY_SPACE && code <= GLFW.GLFW_KEY_LAST
                && GLFW.glfwGetKey(windowHandle, code) == GLFW.GLFW_PRESS;
    }

    private String formatKeyCode(int code) {
        if (code == -1) {
            return "NONE";
        }
        if (code <= -10) {
            int btn = -10 - code;
            return switch (btn) {
                case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> "MMB";
                case GLFW.GLFW_MOUSE_BUTTON_4 -> "MOUSE4";
                case GLFW.GLFW_MOUSE_BUTTON_5 -> "MOUSE5";
                default -> "MOUSE" + (btn + 1);
            };
        }
        if (code >= GLFW.GLFW_KEY_A && code <= GLFW.GLFW_KEY_Z) {
            return String.valueOf((char) code);
        }
        if (code >= GLFW.GLFW_KEY_0 && code <= GLFW.GLFW_KEY_9) {
            return String.valueOf((char) code);
        }
        try {
            if (GLFW.glfwGetCurrentContext() != 0L) {
                String glfwName = GLFW.glfwGetKeyName(code, 0);
                if (glfwName != null && !glfwName.isBlank()) {
                    return glfwName.toUpperCase();
                }
            }
        } catch (Throwable ignored) {
        }
        return switch (code) {
            case GLFW.GLFW_KEY_SPACE -> "SPACE";
            case GLFW.GLFW_KEY_LEFT_SHIFT -> "LSHIFT";
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> "RSHIFT";
            case GLFW.GLFW_KEY_LEFT_CONTROL -> "LCTRL";
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> "RCTRL";
            case GLFW.GLFW_KEY_LEFT_ALT -> "LALT";
            case GLFW.GLFW_KEY_RIGHT_ALT -> "RALT";
            case GLFW.GLFW_KEY_CAPS_LOCK -> "CAPSLOCK";
            case GLFW.GLFW_KEY_TAB -> "TAB";
            default -> "KEY_" + code;
        };
    }

    private void updateSettingName(Setting<?> setting, String newLabel) {
        if (setting == null || newLabel == null) {
            return;
        }
        Class<?> cls = setting.getClass();
        while (cls != null && cls != Object.class) {
            for (Field field : cls.getDeclaredFields()) {
                if (field.getType() == String.class) {
                    try {
                        field.setAccessible(true);
                        Object val = field.get(setting);
                        if (val instanceof String s
                                && (s.startsWith("Toggle Bind") || s.startsWith("Swap Key") || s.startsWith("Firework Key"))) {
                            field.set(setting, newLabel);
                            return;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            cls = cls.getSuperclass();
        }
    }

    public void triggerLegitArmorToggle() {
        if (isWearingElytra()) {
            equipChestplateLegit();
        } else {
            equipElytraLegit();
        }
    }

    public boolean equipElytraLegit() {
        int elytraSlot = findUsableElytraInHotbar();
        if (elytraSlot != -1) {
            return ActionManager.executeLegitArmorSwapFromSlot(
                    elytraSlot,
                    restoreSlot.getValue(),
                    50L,
                    ActionManager.Priority.MOVEMENT_SWAP
            );
        }
        return MaceTarget.ActionManager.executeLegitArmorSwapFromSlot(-1);
    }

    public boolean equipChestplateLegit() {
        int chestSlot = findBestChestplateInHotbar();
        if (chestSlot != -1) {
            return ActionManager.executeLegitArmorSwapFromSlot(
                    chestSlot,
                    restoreSlot.getValue(),
                    50L,
                    ActionManager.Priority.ELYTRA_UNEQUIP
            );
        }
        return MaceTarget.ActionManager.executeLegitArmorSwapFromSlot(-1);
    }

    private boolean triggerLegitFireworkUse() {
        int fireworkSlot = findFireworkInHotbar();
        if (fireworkSlot == -1) {
            return false;
        }
        return ActionManager.executeLegitArmorSwapFromSlot(
                fireworkSlot,
                restoreSlot.getValue(),
                50L,
                ActionManager.Priority.MOVEMENT_SWAP
        );
    }

    private boolean isWearingElytra() {
        if (mc.player == null) {
            return false;
        }
        return InventoryUtils.isElytraEquipped()
                || mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
    }

    private int findUsableElytraInHotbar() {
        if (mc.player == null) {
            return -1;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.ELYTRA) && (stack.getMaxDamage() - stack.getDamageValue() > 1)) {
                return i;
            }
        }
        return -1;
    }

    private int findBestChestplateInHotbar() {
        if (mc.player == null) {
            return -1;
        }
        int bestSlot = -1;
        int bestScore = -1;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty() || stack.is(Items.ELYTRA)) {
                continue;
            }
            if (isChestplate(stack)) {
                int score = evaluateChestplateScore(stack);
                if (score > bestScore) {
                    bestScore = score;
                    bestSlot = i;
                }
            }
        }
        return bestSlot;
    }

    private boolean isChestplate(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (stack.is(ItemTags.CHEST_ARMOR)) {
            return true;
        }
        return CHESTPLATES.contains(stack.getItem());
    }

    private int evaluateChestplateScore(ItemStack stack) {
        Item item = stack.getItem();
        if (item == Items.NETHERITE_CHESTPLATE) return 6;
        if (item == Items.DIAMOND_CHESTPLATE) return 5;
        if (item == Items.IRON_CHESTPLATE) return 4;
        if (item == Items.CHAINMAIL_CHESTPLATE) return 3;
        if (item == Items.GOLDEN_CHESTPLATE) return 2;
        if (item == Items.LEATHER_CHESTPLATE) return 1;
        return 1;
    }

    private int findFireworkInHotbar() {
        if (mc.player == null) {
            return -1;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.FIREWORK_ROCKET)) {
                return i;
            }
        }
        return -1;
    }
}