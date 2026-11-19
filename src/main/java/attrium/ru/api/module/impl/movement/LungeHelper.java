package attrium.ru.api.module.impl.movement;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TridentItem;
import org.lwjgl.glfw.GLFW;
import attrium.ru.api.events.annotation.SubscribeEvent;
import attrium.ru.api.events.impl.DrawEvent;
import attrium.ru.api.events.impl.TickEvent;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.ModuleCategory;
import attrium.ru.api.module.impl.combat.MaceTarget;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.ModeSetting;
import attrium.ru.api.settings.impl.NumberSetting;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * LungeHelper Module (Single Canonical Module — AutoLunge & AutoSpear Eradicated)
 * (Minecraft 1.21.11 Official Mojang Mappings):
 *
 * Uses `AutoMaceSlotSystem.swapSlot(slot, true)` with the 3-Stage Tick-Delayed Slot Swap:
 *   1. Tick 0 (On Bind Press): Record `previousSlot = mc.player.getInventory().getSelectedSlot();`
 *      and call `AutoMaceSlotSystem.swapSlot(lungeSlot, true);`.
 *   2. Tick 1 (Next Tick): Confirm slot with `if (activeLungeSlot >= 0 && activeLungeSlot < 9 && client.player.getInventory().getSelectedSlot() != activeLungeSlot) client.player.getInventory().setSelectedSlot(activeLungeSlot);` and send
 *      `PlayerInteractItemC2SPacket` (`mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND)` + `mc.player.swing(InteractionHand.MAIN_HAND)`).
 *   3. Tick 2 & Tick 3 (Wait Strictly 2 Ticks After Use Packet): Wait 2 full ticks after the use packet
 *      is sent before calling `if (previousSlot >= 0 && previousSlot < 9 && client.player.getInventory().getSelectedSlot() != previousSlot) client.player.getInventory().setSelectedSlot(previousSlot);`.
 */
public class LungeHelper extends Module {
    private static LungeHelper instance;

    public static final class AutoMace {
        private AutoMace() {
        }

        public static boolean isEnabled() {
            return MaceTarget.isAutoMaceEnabled();
        }

        public static boolean canExecuteStrike() {
            return MaceTarget.canExecuteStrike();
        }
    }

    private enum LungePhase {
        IDLE,
        TICK_1_SEND_USE_PACKET,
        WAIT_2_TICKS_BEFORE_REVERT
    }

    // ==================== 1. LungeHelper Configuration UI ====================

    private final BooleanSetting keybindListener = register(new BooleanSetting(
            "Keybind: [NONE]",
            "Click to bind a key or mouse button for LungeHelper (ESC/DELETE to clear to NONE)",
            false
    ));

    private final BooleanSetting holdToSpam = register(new BooleanSetting(
            "Hold To Spam",
            "Continuously repeat the lunge sequence while holding the keybind (respecting tick cooldowns)",
            false
    ));

    private final BooleanSetting swapBack = register(new BooleanSetting(
            "Swap Back",
            "Restore the previously held hotbar item 2 ticks after the lunge use packet is sent",
            true
    ));

    private final BooleanSetting swapBackToSelectedSlot = register(new BooleanSetting(
            "SwapBack To Selected Slot",
            "Override standard Swap Back and force the hotbar to return to a defined slot (1-9)",
            false
    ));

    private final ModeSetting slotSelector = register(new ModeSetting(
            "Slot",
            "Target hotbar slot (1-9) when SwapBack To Selected Slot is enabled",
            "1",
            "1", "2", "3", "4", "5", "6", "7", "8", "9"
    ));

    private final BooleanSetting randomization = register(new BooleanSetting(
            "Randomization",
            "Enable tick-synced randomized delay (e.g. 2 to 5 ticks) between repeated lunges",
            false
    ));

    private final NumberSetting minRandomTicks = register(new NumberSetting(
            "Min Random (Ticks)",
            "Minimum client ticks (0 - 10) to wait between lunges",
            2.0D,
            0.0D,
            10.0D,
            1.0D
    ));

    private final NumberSetting maxRandomTicks = register(new NumberSetting(
            "Max Random (Ticks)",
            "Maximum client ticks (0 - 10) to wait between lunges",
            5.0D,
            0.0D,
            10.0D,
            1.0D
    ));

    private final ModeSetting cooldownSlotSelector = register(new ModeSetting(
            "Cooldown Slot",
            "Hotbar slot (1-9) the client swaps to for weapon attribute/cooldown while holding the bind",
            "None",
            "None", "1", "2", "3", "4", "5", "6", "7", "8", "9"
    ));

    private final NumberSetting savedLungeKeyCode = register(new NumberSetting(
            "_lungeKeyCode",
            "Internal persisted GLFW keycode for LungeHelper keybind (default NONE = -1)",
            -1.0D,
            -200.0D,
            400.0D,
            1.0D
    ));

    // ==================== 2. Multi-Tick State Machine Fields ====================

    private static volatile int syncedGuiBoundKey = -1;
    private static Field cachedModuleKeyField = null;
    private static Method cachedModuleGetKeyMethod = null;
    private static boolean moduleKeyReflectionInit = false;

    private LungePhase lungePhase = LungePhase.IDLE;
    private int activeLungeSlot = -1;
    private int previousSlot = -1;
    private int ticksSinceSlotSwap = 0;
    private int requiredPreSwingTicks = 1;
    private int ticksSinceUseSent = 0;
    private static Method cachedStartAttackMethod = null;
    private static boolean startAttackReflectionInit = false;
    private boolean pendingRevertAfterWait = false;

    private boolean wasBindPressedLastTick = false;
    private boolean singleLungeFiredThisHold = false;
    private long lastTickProcessedGameTime = -1L;

    private int clientTickCounter = 0;
    private int nextAllowedLungeTick = 0;

    private static final int POST_USE_HOLD_TICKS = 2;
    private static final int BASE_LUNGE_COOLDOWN_TICKS = 4;

    public LungeHelper() {
        super("LungeHelper", "Keybind-driven Spear/Trident/Mace Lunge macro with AutoMaceSlotSystem sync & 2-tick revert delay", ModuleCategory.COMBAT);
        instance = this;

        this.savedLungeKeyCode.visibleWhen(() -> false);
        this.slotSelector.visibleWhen(this.swapBackToSelectedSlot::getValue);
        this.minRandomTicks.visibleWhen(this.randomization::getValue);
        this.maxRandomTicks.visibleWhen(this.randomization::getValue);
        this.keybindListener.visibleWhen(() -> false);
    }

    public static LungeHelper getInstance() {
        if (instance == null) {
            instance = new LungeHelper();
        }
        return instance;
    }

    public static void setMacroBoundKey(int key) {
        int normalized = (key == 0 || key == GLFW.GLFW_KEY_UNKNOWN) ? -1 : key;
        syncedGuiBoundKey = normalized;
        if (instance != null) {
            try {
                instance.savedLungeKeyCode.setValue((double) normalized);
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

    public static void tickMacroGlobal(Minecraft client, int guiBoundKey) {
        if (guiBoundKey != -1 && guiBoundKey != 0 && guiBoundKey != GLFW.GLFW_KEY_UNKNOWN) {
            syncedGuiBoundKey = guiBoundKey;
        }
        LungeHelper mod = getInstance();
        if (mod != null) {
            mod.processClientTick(client);
        }
    }

    @Override
    protected void onEnable() {
    }

    @Override
    protected void onDisable() {
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.player != null && previousSlot >= 0 && previousSlot < 9) {
            restorePreviousSlotLegit(client);
        }
        resetState();
    }

    private void resetState() {
        this.lungePhase = LungePhase.IDLE;
        this.activeLungeSlot = -1;
        this.previousSlot = -1;
        this.ticksSinceUseSent = 0;
        this.pendingRevertAfterWait = false;
        this.wasBindPressedLastTick = false;
        this.singleLungeFiredThisHold = false;
    }

    @SubscribeEvent
    public void onDraw(DrawEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) {
            return;
        }
        if (minRandomTicks.getValue() > maxRandomTicks.getValue()) {
            maxRandomTicks.setValue(minRandomTicks.getValue());
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.Pre event) {
        Minecraft mc = event != null ? event.getClient() : Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) {
            return;
        }
        processClientTick(mc);
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

        if (AutoMace.isEnabled() && AutoMace.canExecuteStrike()) {
            return;
        }

        if (client.screen != null) {
            if (lungePhase == LungePhase.IDLE && (wasBindPressedLastTick || previousSlot != -1)) {
                restorePreviousSlotLegit(client);
                resetState();
            }
            return;
        }

        boolean keyPressed = isLungeKeybindDown(client);
        boolean risingEdge = keyPressed && !wasBindPressedLastTick;
        boolean fallingEdge = !keyPressed && wasBindPressedLastTick;
        wasBindPressedLastTick = keyPressed;

        if (risingEdge) {
            if (this.previousSlot < 0 || this.previousSlot > 8) {
                this.previousSlot = client.player.getInventory().getSelectedSlot();
            }
            this.singleLungeFiredThisHold = false;
            this.pendingRevertAfterWait = false;
        }

        if (fallingEdge) {
            this.pendingRevertAfterWait = true;
        }

        if (lungePhase != LungePhase.IDLE) {
            advanceMultiTickLungeState(client, keyPressed);
            return;
        }

        if (!keyPressed) {
            if (pendingRevertAfterWait || previousSlot != -1) {
                restorePreviousSlotLegit(client);
                resetState();
            }
            return;
        }

        boolean shouldStartLunge = risingEdge || Boolean.TRUE.equals(holdToSpam.getValue());
        if (!Boolean.TRUE.equals(holdToSpam.getValue()) && singleLungeFiredThisHold) {
            int cooldownSlot = getSelectedCooldownSlotIndex();
            if (cooldownSlot >= 0 && cooldownSlot < 9) {
                if (cooldownSlot >= 0 && cooldownSlot < 9 && client.player.getInventory().getSelectedSlot() != cooldownSlot) client.player.getInventory().setSelectedSlot(cooldownSlot);
            }
            return;
        }

        if (shouldStartLunge) {
            initiateTickZeroSlotSwap(client);
        }
    }

    private void initiateTickZeroSlotSwap(Minecraft client) {
        int cooldownSlot = getSelectedCooldownSlotIndex();
        int lungeSlot = findSpearHotbarSlot(client);
        // Strictly Spear-only: if no Spear is in the hotbar (0-8), never lunge with any other item
        if (lungeSlot == -1) {
            return;
        }

        if (isSpearOnCooldown(client, lungeSlot) || clientTickCounter < nextAllowedLungeTick) {
            if (cooldownSlot >= 0 && cooldownSlot < 9 && client.player.getInventory().getSelectedSlot() != cooldownSlot) {
                client.player.getInventory().setSelectedSlot(cooldownSlot);
                if (client.player.connection != null) {
                    client.player.connection.send(new ServerboundSetCarriedItemPacket(cooldownSlot));
                }
            }
            return;
        }

        if (previousSlot < 0 || previousSlot > 8) {
            previousSlot = client.player.getInventory().getSelectedSlot();
        }
        activeLungeSlot = lungeSlot;

        // Stage 1 (Tick 0): Send UpdateSelectedSlotC2SPacket (C09) to Spear slot FIRST
        if (client.player.getInventory().getSelectedSlot() != activeLungeSlot) {
            client.player.getInventory().setSelectedSlot(activeLungeSlot);
            if (client.player.connection != null) {
                client.player.connection.send(new ServerboundSetCarriedItemPacket(activeLungeSlot));
            }
        }
        ticksSinceSlotSwap = 0;
        // 1-2 tick humanization delay between C09 slot swap and Spear jab/swing to prevent BadPackets/AutoSwitch flags
        requiredPreSwingTicks = Boolean.TRUE.equals(randomization.getValue())
                ? ThreadLocalRandom.current().nextInt(1, 3)
                : 1;
        ticksSinceUseSent = 0;
        lungePhase = LungePhase.TICK_1_SEND_USE_PACKET;
    }

    private void advanceMultiTickLungeState(Minecraft client, boolean keyStillHeld) {
        switch (lungePhase) {
            case TICK_1_SEND_USE_PACKET -> {
                if (activeLungeSlot < 0 || activeLungeSlot >= 9
                        || !isSpearItem(client.player.getInventory().getItem(activeLungeSlot))) {
                    lungePhase = LungePhase.IDLE;
                    activeLungeSlot = -1;
                    restorePreviousSlotLegit(client);
                    return;
                }

                // Wait 1-2 ticks after C09 slot swap so the server has registered the Spear before the attack arrives
                ticksSinceSlotSwap++;
                if (ticksSinceSlotSwap < requiredPreSwingTicks) {
                    return;
                }

                if (client.player.getInventory().getSelectedSlot() != activeLungeSlot) {
                    client.player.getInventory().setSelectedSlot(activeLungeSlot);
                    if (client.player.connection != null) {
                        client.player.connection.send(new ServerboundSetCarriedItemPacket(activeLungeSlot));
                    }
                }

                // Stage 2: Execute native Minecraft 1.21.11 Spear Jab Attack (piercingAttack + HandSwingC2SPacket)
                triggerNativeSpearLungeAttack(client);

                singleLungeFiredThisHold = true;
                ticksSinceUseSent = 0;
                lungePhase = LungePhase.WAIT_2_TICKS_BEFORE_REVERT;
            }

            case WAIT_2_TICKS_BEFORE_REVERT -> {
                ticksSinceUseSent++;
                if (ticksSinceUseSent < POST_USE_HOLD_TICKS) {
                    return;
                }

                int extraTicks = Boolean.TRUE.equals(randomization.getValue()) ? computeRandomizedDelayTicks() : 0;
                nextAllowedLungeTick = clientTickCounter + BASE_LUNGE_COOLDOWN_TICKS + extraTicks;
                lungePhase = LungePhase.IDLE;
                activeLungeSlot = -1;

                // Stage 3: Clean swap-back after 2-tick post-swing hold
                if (!keyStillHeld || pendingRevertAfterWait) {
                    restorePreviousSlotLegit(client);
                    pendingRevertAfterWait = false;
                } else {
                    int cooldownSlot = getSelectedCooldownSlotIndex();
                    if (cooldownSlot >= 0 && cooldownSlot < 9) {
                        if (client.player.getInventory().getSelectedSlot() != cooldownSlot) {
                            client.player.getInventory().setSelectedSlot(cooldownSlot);
                            if (client.player.connection != null) {
                                client.player.connection.send(new ServerboundSetCarriedItemPacket(cooldownSlot));
                            }
                        }
                    } else if (Boolean.TRUE.equals(swapBack.getValue()) && !Boolean.TRUE.equals(holdToSpam.getValue())) {
                        restorePreviousSlotLegit(client);
                    }
                }
            }

            default -> lungePhase = LungePhase.IDLE;
        }
    }

    /**
     * Triggers Minecraft 1.21.11's native Spear Jab / Lunge attack (`Minecraft#startAttack()`),
     * which dispatches the Spear piercing attack + `ServerboundSwingPacket` cleanly after the C09 slot packet.
     * Works on FCL/Android even when touch buttons or custom keybinds are used.
     */
    private void triggerNativeSpearLungeAttack(Minecraft client) {
        if (client == null || client.player == null) {
            return;
        }
        try {
            if (!startAttackReflectionInit) {
                startAttackReflectionInit = true;
                for (Method m : Minecraft.class.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 && m.getReturnType() == boolean.class) {
                        String n = m.getName();
                        if (n.equals("startAttack") || n.equals("method_1536")) {
                            m.setAccessible(true);
                            cachedStartAttackMethod = m;
                            break;
                        }
                    }
                }
            }
            if (cachedStartAttackMethod != null) {
                cachedStartAttackMethod.invoke(client);
                return;
            }
        } catch (Throwable ignored) {
        }

        // Fallback: click keyAttack AND ensure HandSwingC2SPacket is sent
        if (client.options != null && client.options.keyAttack != null) {
            KeyMapping.click(client.options.keyAttack.getDefaultKey());
        }
        client.player.swing(InteractionHand.MAIN_HAND);
        if (client.player.connection != null) {
            client.player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
        }
    }

    private void restorePreviousSlotLegit(Minecraft client) {
        if (client == null || client.player == null || client.player.getInventory() == null) {
            return;
        }

        if (Boolean.TRUE.equals(swapBackToSelectedSlot.getValue())) {
            int targetSlot = getSelectedReturnSlotIndex();
            if (targetSlot >= 0 && targetSlot < 9) {
                if (client.player.getInventory().getSelectedSlot() != targetSlot) {
                    client.player.getInventory().setSelectedSlot(targetSlot);
                    if (client.player.connection != null) {
                        client.player.connection.send(new ServerboundSetCarriedItemPacket(targetSlot));
                    }
                }
                previousSlot = -1;
                return;
            }
        }

        if (Boolean.TRUE.equals(swapBack.getValue()) && previousSlot >= 0 && previousSlot < 9) {
            if (client.player.getInventory().getSelectedSlot() != previousSlot) {
                client.player.getInventory().setSelectedSlot(previousSlot);
                if (client.player.connection != null) {
                    client.player.connection.send(new ServerboundSetCarriedItemPacket(previousSlot));
                }
            }
        }
        previousSlot = -1;
    }

    private int findSpearHotbarSlot(Minecraft client) {
        if (client == null || client.player == null || client.player.getInventory() == null) {
            return -1;
        }
        for (int i = 0; i < 9; i++) {
            if (isSpearItem(client.player.getInventory().getItem(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Strictly matches Minecraft 1.21.11 Spear items ONLY (wooden_spear, stone_spear, iron_spear,
     * golden_spear, diamond_spear, netherite_spear, etc.) — never Tridents, Maces, or Wind Charges.
     */
    private boolean isSpearItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Object key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (key != null) {
            String path = key.toString().toLowerCase(Locale.ROOT);
            if (path.contains("spear")) {
                return true;
            }
        }
        return false;
    }

    private boolean isSpearOnCooldown(Minecraft client, int hotbarSlot) {
        if (client == null || client.player == null || hotbarSlot < 0 || hotbarSlot >= 9) {
            return false;
        }
        ItemStack stack = client.player.getInventory().getItem(hotbarSlot);
        return !stack.isEmpty() && client.player.getCooldowns().isOnCooldown(stack);
    }

    private int computeRandomizedDelayTicks() {
        int minT = Math.max(0, (int) Math.round(minRandomTicks.getValue()));
        int maxT = Math.max(0, (int) Math.round(maxRandomTicks.getValue()));
        if (minT > maxT) {
            maxRandomTicks.setValue((double) minT);
            maxT = minT;
        }
        if (maxT <= 0) {
            return 0;
        }
        return minT == maxT ? minT : ThreadLocalRandom.current().nextInt(minT, maxT + 1);
    }

    private int getSelectedReturnSlotIndex() {
        try {
            int parsed = Integer.parseInt(slotSelector.getValue().trim());
            if (parsed >= 1 && parsed <= 9) {
                return parsed - 1;
            }
        } catch (NumberFormatException ignored) {
        }
        return 0;
    }

    private int getSelectedCooldownSlotIndex() {
        String val = cooldownSlotSelector.getValue();
        if (val == null || "None".equalsIgnoreCase(val.trim())) {
            return -1;
        }
        try {
            int parsed = Integer.parseInt(val.trim());
            if (parsed >= 1 && parsed <= 9) {
                return parsed - 1;
            }
        } catch (NumberFormatException ignored) {
        }
        return -1;
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
        int saved = (int) Math.round(savedLungeKeyCode.getValue());
        if (saved != -1 && saved != 0 && saved != GLFW.GLFW_KEY_UNKNOWN) {
            return saved;
        }
        return -1;
    }

    private boolean isLungeKeybindDown(Minecraft client) {
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
