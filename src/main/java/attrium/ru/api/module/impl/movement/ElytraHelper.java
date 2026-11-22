package attrium.ru.api.module.impl.movement;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;
import attrium.ru.api.events.annotation.SubscribeEvent;
import attrium.ru.api.events.impl.DrawEvent;
import attrium.ru.api.events.impl.InputEvent;
import attrium.ru.api.events.impl.TickEvent;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.world.effect.MobEffects;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.ModuleCategory;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.NumberSetting;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ElytraHelper Module (Pure Keybind-Driven Action Macro — No ON/OFF Toggle, No Rocket Option)
 * (Minecraft 1.21.11 Official Mojang Mappings):
 *
 * Legit Hotbar Slot Swap Sequence (with optional tick randomization):
 *   1. If the target Elytra or Chestplate is in the hotbar (0-8):
 *        - Tick 0: Visibly switches client hotbar slot to the armor item (C09).
 *        - Tick +delay (1 tick default, or randomized Min..Max ticks): Equips via
 *          `gameMode.useItem(MAIN_HAND)` + `player.swing(MAIN_HAND)` (and starts glide if airborne).
 *        - Tick +delay: Visibly switches back to the previously held hotbar slot.
 *   2. If the target Elytra or Chestplate is in the main inventory (9-35):
 *        - Performs a clean inventory click swap into chest armor slot 6 (with optional tick delay).
 */
public class ElytraHelper extends Module {
    private static ElytraHelper instance;

    private static final int BASE_SWAP_COOLDOWN_TICKS = 4;

    private enum SwapPhase {
        IDLE,
        WAIT_BEFORE_EQUIP,
        WAIT_BEFORE_RETURN_SLOT
    }

    private final BooleanSetting autoStartGlide = register(new BooleanSetting(
            "Auto Glide",
            "Automatically start gliding if you swap to an Elytra while airborne",
            true
    ));

    private final BooleanSetting randomization = register(new BooleanSetting(
            "Randomization",
            "Randomize the tick delay between slot switch, equip, and swap-back to look legit",
            false
    ));

    private final NumberSetting minRandomTicks = register(new NumberSetting(
            "Min Random (Ticks)",
            "Minimum ticks (1 - 10) between legit hotbar swap stages",
            1.0D,
            1.0D,
            10.0D,
            1.0D
    ));

    private final NumberSetting maxRandomTicks = register(new NumberSetting(
            "Max Random (Ticks)",
            "Maximum ticks (1 - 10) between legit hotbar swap stages",
            3.0D,
            1.0D,
            10.0D,
            1.0D
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

    private SwapPhase phase = SwapPhase.IDLE;
    private int targetArmorSlot = -1;
    private int previousHotbarSlot = -1;
    private boolean equippingElytra = false;
    private int phaseTicksElapsed = 0;
    private int requiredPhaseTicks = 1;

    private boolean wasBindPressedLastTick = false;
    private long lastTickProcessedGameTime = -1L;
    private int clientTickCounter = 0;
    private int nextAllowedSwapTick = 0;
    private int pendingAutoGlideUntilTick = -100;
    private int earliestGlidePulseTick = -100;
    private int nextGlidePulseTick = 0;

    public ElytraHelper() {
        super("ElytraHelper", "Keybind-driven legit Chestplate <-> Elytra swap macro with tick randomization", ModuleCategory.MOVEMENT);
        instance = this;
        this.savedElytraKeyCode.visibleWhen(() -> false);
        this.minRandomTicks.visibleWhen(this.randomization::getValue);
        this.maxRandomTicks.visibleWhen(this.randomization::getValue);
        // Run strictly before handleKeybinds() & aiStep() to eliminate Post flags
        ClientTickEvents.START_CLIENT_TICK.register(this::processClientTick);
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
        // Ensure instance & START_CLIENT_TICK listener are initialized without post-tick execution
        getInstance();
    }

    @Override
    protected void onEnable() {
    }

    @Override
    protected void onDisable() {
        resetState();
    }

    private void resetState() {
        phase = SwapPhase.IDLE;
        targetArmorSlot = -1;
        previousHotbarSlot = -1;
        equippingElytra = false;
        phaseTicksElapsed = 0;
        wasBindPressedLastTick = false;
        callbackPressLatched = false;
        pendingAutoGlideUntilTick = -100;
        earliestGlidePulseTick = -100;
    }

    @SubscribeEvent
    public void onDraw(DrawEvent event) {
        if (minRandomTicks.getValue() > maxRandomTicks.getValue()) {
            maxRandomTicks.setValue(minRandomTicks.getValue());
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.Pre event) {
        // Handled exclusively in ClientTickEvents.START_CLIENT_TICK to prevent Post flags
    }

    public void processClientTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null || client.gameMode == null) {
            resetState();
            return;
        }

        long currentGameTime = client.level.getGameTime();
        if (currentGameTime == lastTickProcessedGameTime) {
            return;
        }
        lastTickProcessedGameTime = currentGameTime;
        clientTickCounter++;

        if (client.screen != null) {
            if (phase == SwapPhase.WAIT_BEFORE_RETURN_SLOT && previousHotbarSlot >= 0 && previousHotbarSlot < 9) {
                selectHotbarSlotWithPacket(client, previousHotbarSlot);
            }
            resetState();
            return;
        }

        boolean callbackTap = callbackPressLatched;
        callbackPressLatched = false;

        boolean keyDown = isMacroKeyDown(client);
        boolean risingEdge = (keyDown && !wasBindPressedLastTick) || callbackTap;
        wasBindPressedLastTick = keyDown;

        // Advance active legit multi-tick hotbar swap sequence
        if (phase != SwapPhase.IDLE) {
            advanceSwapSequence(client);
            return;
        }

        if (!risingEdge || clientTickCounter < nextAllowedSwapTick || client.player.isUsingItem()) {
            return;
        }

        beginLegitChestplateElytraSwap(client);
    }

    private void beginLegitChestplateElytraSwap(Minecraft client) {
        ItemStack chest = client.player.getItemBySlot(EquipmentSlot.CHEST);
        boolean wearingElytra = !chest.isEmpty() && chest.is(Items.ELYTRA);

        int slot = wearingElytra
                ? findBestChestplateSlot(client)
                : findBestElytraSlot(client);

        // Strictly hotbar-only (slots 0..8): never pull or swap items from main inventory (9..35)
        if (slot < 0 || slot >= 9) {
            return;
        }

        this.equippingElytra = !wearingElytra;
        this.targetArmorSlot = slot;
        this.previousHotbarSlot = client.player.getInventory().getSelectedSlot();

        // Legit visible hotbar swap: switch to armor slot in client view first
        selectHotbarSlotWithPacket(client, slot);
        this.phaseTicksElapsed = 0;
        this.requiredPhaseTicks = computeStageDelayTicks();
        this.phase = SwapPhase.WAIT_BEFORE_EQUIP;
    }

    private void advanceSwapSequence(Minecraft client) {
        switch (phase) {
            case WAIT_BEFORE_EQUIP -> {
                phaseTicksElapsed++;
                if (phaseTicksElapsed < requiredPhaseTicks) {
                    return;
                }
                if (targetArmorSlot >= 0 && targetArmorSlot < 9) {
                    selectHotbarSlotWithPacket(client, targetArmorSlot);
                    // Right-click equip the held Elytra / Chestplate legitimately
                    client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                    client.player.swing(InteractionHand.MAIN_HAND);
                    if (equippingElytra && Boolean.TRUE.equals(autoStartGlide.getValue())) {
                        earliestGlidePulseTick = clientTickCounter + 2;
                        pendingAutoGlideUntilTick = clientTickCounter + 16;
                    }
                }
                phaseTicksElapsed = 0;
                requiredPhaseTicks = computeStageDelayTicks();
                phase = SwapPhase.WAIT_BEFORE_RETURN_SLOT;
            }

            case WAIT_BEFORE_RETURN_SLOT -> {
                phaseTicksElapsed++;
                if (phaseTicksElapsed < requiredPhaseTicks) {
                    return;
                }
                if (previousHotbarSlot >= 0 && previousHotbarSlot < 9) {
                    selectHotbarSlotWithPacket(client, previousHotbarSlot);
                }
                nextAllowedSwapTick = clientTickCounter + BASE_SWAP_COOLDOWN_TICKS;
                targetArmorSlot = -1;
                previousHotbarSlot = -1;
                phaseTicksElapsed = 0;
                phase = SwapPhase.IDLE;
            }

            default -> phase = SwapPhase.IDLE;
        }
    }

    @SubscribeEvent
    public void onInput(InputEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.screen != null) {
            return;
        }
        if (clientTickCounter > pendingAutoGlideUntilTick) {
            return;
        }
        if (client.player.isFallFlying() || client.player.onGround()) {
            pendingAutoGlideUntilTick = -100;
            return;
        }
        if (clientTickCounter < earliestGlidePulseTick || clientTickCounter < nextGlidePulseTick) {
            return;
        }

        ItemStack chest = client.player.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.isEmpty() || !chest.is(Items.ELYTRA) || chest.getDamageValue() >= chest.getMaxDamage() - 1) {
            return;
        }
        if (client.player.isInWater()
                || client.player.isInLava()
                || client.player.isPassenger()
                || client.player.onClimbable()
                || client.player.getAbilities().flying
                || client.player.hasEffect(MobEffects.LEVITATION)
                || client.player.hurtTime > 0) {
            return;
        }

        // Strict vanilla fall preconditions to prevent Elytra A / B flags
        if (client.player.getDeltaMovement().y < -0.08D && client.player.fallDistance >= 0.25F) {
            event.setJumping(true);
            nextGlidePulseTick = clientTickCounter + 5;
        }
    }

    private int computeStageDelayTicks() {
        if (!Boolean.TRUE.equals(randomization.getValue())) {
            return 1;
        }
        int minT = Math.max(1, (int) Math.round(minRandomTicks.getValue()));
        int maxT = Math.max(minT, (int) Math.round(maxRandomTicks.getValue()));
        return minT == maxT ? minT : ThreadLocalRandom.current().nextInt(minT, maxT + 1);
    }

    private void selectHotbarSlotWithPacket(Minecraft client, int targetSlot) {
        if (client == null || client.player == null || client.player.getInventory() == null) {
            return;
        }
        if (targetSlot < 0 || targetSlot >= 9) {
            return;
        }
        if (client.player.getInventory().getSelectedSlot() != targetSlot) {
            // Let MultiPlayerGameMode.ensureHasSentCarriedItem() send the single C09 packet
            client.player.getInventory().setSelectedSlot(targetSlot);
        }
    }

    private int findBestElytraSlot(Minecraft client) {
        int fallbackBroken = -1;
        // Strictly hotbar-only (0..8): never use main inventory (9..35)
        for (int i = 0; i < 9; i++) {
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
        int bestHotbarSlot = -1;
        int bestHotbarTier = -1;
        // Strictly hotbar-only (0..8): never use main inventory (9..35)
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            int tier = getChestplateTier(stack);
            if (tier > bestHotbarTier) {
                bestHotbarTier = tier;
                bestHotbarSlot = i;
            }
        }
        return bestHotbarSlot;
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
