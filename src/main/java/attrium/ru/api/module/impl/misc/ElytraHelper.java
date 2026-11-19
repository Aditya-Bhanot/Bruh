package attrium.ru.api.module.impl.misc;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;
import attrium.ru.api.events.annotation.SubscribeEvent;
import attrium.ru.api.events.impl.TickEvent;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.ModuleCategory;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.NumberSetting;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

/**
 * ElytraHelper Module (Pure Keybind-Driven Action Macro — No ON/OFF Toggle, No Rocket Option)
 * (Minecraft 1.21.11 Official Mojang Mappings):
 *
 * Works identically to LungeHelper in ClickGUI (Keybind pill only, no ON/OFF switch).
 * Pressing the bound key cleanly swaps between your Chestplate and Elytra, with optional
 * automatic glide activation when equipping the Elytra in midair.
 */
public class ElytraHelper extends Module {
    private static ElytraHelper instance;

    private static final int CHEST_ARMOR_MENU_SLOT = 6;
    private static final int SWAP_COOLDOWN_TICKS = 4;

    private final BooleanSetting autoStartGlide = register(new BooleanSetting(
            "Auto Glide",
            "Automatically start gliding if you swap to an Elytra while airborne",
            true
    ));

    private final NumberSetting savedElytraKeyCode = register(new NumberSetting(
            "_elytraHelperKeyCode",
            "Internal persisted GLFW keycode for ElytraHelper action macro (default NONE = -1)",
            -1.0D,
            -200.0D,
            400.0D,
            1.0D
    ));

    private static volatile int syncedGuiBoundKey = -1;
    private static volatile boolean callbackPressLatched = false;
    private static Field cachedModuleKeyField = null;
    private static Method cachedModuleGetKeyMethod = null;
    private static boolean moduleKeyReflectionInit = false;

    private boolean wasBindPressedLastTick = false;
    private long lastTickProcessedGameTime = -1L;
    private int clientTickCounter = 0;
    private int nextAllowedSwapTick = 0;

    public ElytraHelper() {
        super("ElytraHelper", "Keybind-driven Chestplate <-> Elytra swap macro (no rocket option)", ModuleCategory.MOVEMENT);
        instance = this;
        this.savedElytraKeyCode.visibleWhen(() -> false);
    }

    public static ElytraHelper getInstance() {
        if (instance == null) {
            instance = new ElytraHelper();
        }
        return instance;
    }

    public static void setMacroBoundKey(int key) {
        int normalized = (key == 0 || key == GLFW.GLFW_KEY_UNKNOWN) ? -1 : key;
        syncedGuiBoundKey = normalized;
        if (instance != null) {
            try {
                instance.savedElytraKeyCode.setValue((double) normalized);
            } catch (Throwable ignored) {
            }
        }
    }

    public static int getMacroBoundKey() {
        if (instance != null) {
            return instance.resolveActiveBoundKey();
        }
        return syncedGuiBoundKey;
    }

    public static void onMacroKeyPressedFromCallback(int key) {
        if (key == -1 || key == 0 || key == GLFW.GLFW_KEY_UNKNOWN) {
            return;
        }
        int bound = getMacroBoundKey();
        if (bound == key) {
            callbackPressLatched = true;
        }
    }

    public static void tickMacroGlobal(Minecraft client, int guiBoundKey) {
        if (guiBoundKey != -1 && guiBoundKey != 0 && guiBoundKey != GLFW.GLFW_KEY_UNKNOWN) {
            syncedGuiBoundKey = guiBoundKey;
        }
        ElytraHelper mod = getInstance();
        if (mod != null) {
            mod.processClientTick(client);
        }
    }

    @Override
    protected void onEnable() {
    }

    @Override
    protected void onDisable() {
        wasBindPressedLastTick = false;
        callbackPressLatched = false;
    }

    @SubscribeEvent
    public void onTick(TickEvent.Pre event) {
        Minecraft mc = event != null ? event.getClient() : Minecraft.getInstance();
        if (mc != null) {
            processClientTick(mc);
        }
    }

    public void processClientTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null || client.gameMode == null) {
            wasBindPressedLastTick = false;
            callbackPressLatched = false;
            return;
        }

        long currentGameTime = client.level.getGameTime();
        if (currentGameTime == lastTickProcessedGameTime) {
            return;
        }
        lastTickProcessedGameTime = currentGameTime;
        clientTickCounter++;

        if (client.screen != null) {
            wasBindPressedLastTick = false;
            callbackPressLatched = false;
            return;
        }

        boolean callbackTap = callbackPressLatched;
        callbackPressLatched = false;

        boolean keyDown = isMacroKeyDown(client);
        boolean risingEdge = (keyDown && !wasBindPressedLastTick) || callbackTap;
        wasBindPressedLastTick = keyDown;

        if (!risingEdge || clientTickCounter < nextAllowedSwapTick) {
            return;
        }

        if (performChestplateElytraSwap(client)) {
            nextAllowedSwapTick = clientTickCounter + SWAP_COOLDOWN_TICKS;
        }
    }

    private boolean performChestplateElytraSwap(Minecraft client) {
        ItemStack chest = client.player.getItemBySlot(EquipmentSlot.CHEST);
        boolean wearingElytra = !chest.isEmpty() && chest.is(Items.ELYTRA);

        int targetInvSlot = wearingElytra
                ? findBestChestplateSlot(client)
                : findBestElytraSlot(client);

        if (targetInvSlot < 0 || targetInvSlot >= 36) {
            return false;
        }

        int containerId = client.player.inventoryMenu.containerId;
        if (targetInvSlot < 9) {
            // 1-click legit hotbar swap directly into chest armor slot 6
            client.gameMode.handleInventoryMouseClick(containerId, CHEST_ARMOR_MENU_SLOT, targetInvSlot, ClickType.SWAP, client.player);
        } else {
            // Standard 3-click inventory swap between slot targetInvSlot (9..35) and chest slot 6
            client.gameMode.handleInventoryMouseClick(containerId, targetInvSlot, 0, ClickType.PICKUP, client.player);
            client.gameMode.handleInventoryMouseClick(containerId, CHEST_ARMOR_MENU_SLOT, 0, ClickType.PICKUP, client.player);
            client.gameMode.handleInventoryMouseClick(containerId, targetInvSlot, 0, ClickType.PICKUP, client.player);
        }

        // If we just equipped an Elytra while airborne and Auto Glide is enabled, start gliding
        if (!wearingElytra && Boolean.TRUE.equals(autoStartGlide.getValue())
                && !client.player.onGround()
                && !client.player.isFallFlying()
                && !client.player.isInWater()
                && client.player.connection != null) {
            client.player.connection.send(new ServerboundPlayerCommandPacket(
                    client.player,
                    ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
            ));
        }
        return true;
    }

    private int findBestElytraSlot(Minecraft client) {
        int fallbackBroken = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.ELYTRA)) {
                if (stack.getDamageValue() < stack.getMaxDamage() - 1) {
                    return i;
                }
                if (fallbackBroken == -1) {
                    fallbackBroken = i;
                }
            }
        }
        return fallbackBroken;
    }

    private int findBestChestplateSlot(Minecraft client) {
        int bestSlot = -1;
        int bestTier = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            int tier = getChestplateTier(stack);
            if (tier > bestTier) {
                bestTier = tier;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private int getChestplateTier(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return -1;
        }
        Object key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (key == null) {
            return -1;
        }
        String id = key.toString().toLowerCase(Locale.ROOT);
        if (!id.endsWith("_chestplate")) {
            return -1;
        }
        if (id.contains("netherite")) return 6;
        if (id.contains("diamond")) return 5;
        if (id.contains("iron")) return 4;
        if (id.contains("chainmail")) return 3;
        if (id.contains("golden")) return 2;
        if (id.contains("copper")) return 2;
        if (id.contains("leather")) return 1;
        return 1;
    }

    private static void initModuleKeyReflectionOnce() {
        if (moduleKeyReflectionInit) {
            return;
        }
        moduleKeyReflectionInit = true;
        try {
            for (Method m : Module.class.getDeclaredMethods()) {
                String n = m.getName().toLowerCase(Locale.ROOT);
                if (m.getParameterCount() == 0 && m.getReturnType() == int.class
                        && (n.contains("key") || n.contains("bind"))) {
                    m.setAccessible(true);
                    cachedModuleGetKeyMethod = m;
                    break;
                }
            }
            for (Field f : Module.class.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) {
                    f.setAccessible(true);
                    cachedModuleKeyField = f;
                    String fn = f.getName().toLowerCase(Locale.ROOT);
                    if (fn.contains("key") || fn.contains("bind")) {
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private int resolveActiveBoundKey() {
        if (syncedGuiBoundKey != -1 && syncedGuiBoundKey != 0 && syncedGuiBoundKey != GLFW.GLFW_KEY_UNKNOWN) {
            return syncedGuiBoundKey;
        }
        initModuleKeyReflectionOnce();
        try {
            if (cachedModuleGetKeyMethod != null) {
                Object v = cachedModuleGetKeyMethod.invoke(this);
                if (v instanceof Number n) {
                    int k = n.intValue();
                    if (k != -1 && k != 0 && k != GLFW.GLFW_KEY_UNKNOWN) {
                        return k;
                    }
                }
            }
            if (cachedModuleKeyField != null) {
                int k = cachedModuleKeyField.getInt(this);
                if (k != -1 && k != 0 && k != GLFW.GLFW_KEY_UNKNOWN) {
                    return k;
                }
            }
        } catch (Throwable ignored) {
        }
        int saved = (int) Math.round(savedElytraKeyCode.getValue());
        if (saved != -1 && saved != 0 && saved != GLFW.GLFW_KEY_UNKNOWN) {
            return saved;
        }
        return -1;
    }

    private boolean isMacroKeyDown(Minecraft client) {
        if (client == null || client.getWindow() == null) {
            return false;
        }
        int code = resolveActiveBoundKey();
        if (code == -1 || code == 0 || code == GLFW.GLFW_KEY_UNKNOWN) {
            return false;
        }
        long windowHandle = client.getWindow().handle();
        if (windowHandle == 0L) {
            return false;
        }
        if (code < -1) {
            int btn = code <= -100 ? (-100 - code) : (-10 - code);
            return btn >= 0 && btn <= GLFW.GLFW_MOUSE_BUTTON_8
                    && GLFW.glfwGetMouseButton(windowHandle, btn) == GLFW.GLFW_PRESS;
        }
        if (code >= GLFW.GLFW_KEY_SPACE && code <= GLFW.GLFW_KEY_LAST) {
            return InputConstants.isKeyDown(client.getWindow(), code)
                    || GLFW.glfwGetKey(windowHandle, code) == GLFW.GLFW_PRESS;
        }
        return false;
    }
}
