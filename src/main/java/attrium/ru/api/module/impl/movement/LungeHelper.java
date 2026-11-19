package attrium.ru.api.module.impl.movement;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import attrium.ru.api.events.annotation.SubscribeEvent;
import attrium.ru.api.events.impl.DrawEvent;
import attrium.ru.api.events.impl.TickEvent;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.ModuleCategory;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.ModeSetting;
import attrium.ru.api.settings.impl.NumberSetting;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * LungeHelper Module (Spear-Only — Minecraft 1.21.11 Official Mojang Mappings):
 *
 * Single, legit-looking Spear Lunge Swap (works on ground AND in midair):
 *   1. Waits until the player's attack strength bar is 100% charged (`getAttackStrengthScale(0.5F) >= 1.0F`).
 *   2. Tick 0 (Lunge Tick):
 *        a) Visibly switches client hotbar slot to the Spear (`setSelectedSlot(spearSlot)`)
 *           and sends `ServerboundSetCarriedItemPacket(spearSlot)` (C09) FIRST.
 *        b) Dispatches `ServerboundPlayerActionPacket(Action.STAB, BlockPos.ZERO, Direction.DOWN)`
 *           + `player.swing(InteractionHand.MAIN_HAND)` + `player.resetAttackStrengthTicker()`.
 *   3. Tick 1-2 (Visible Legit Hold): Keeps the Spear visibly selected in client view for 2 ticks (~100ms)
 *      so the hotbar highlight and first-person Spear jab animation clearly appear on screen.
 *   4. Tick 2 (Clean Swap-Back): Visibly switches back to the return/cooldown slot so your fast
 *      carrier slot recharges the attack bar for the next ground or midair lunge.
 */
public class LungeHelper extends Module {
    private static LungeHelper instance;

    private enum LungePhase {
        IDLE,
        HOLD_VISIBLE_SPEAR_BEFORE_SWAP_BACK
    }

    // ==================== 1. LungeHelper Configuration UI ====================

    private final BooleanSetting keybindListener = register(new BooleanSetting(
            "Keybind: [NONE]",
            "Click to bind a key or mouse button for LungeHelper (ESC/DELETE to clear to NONE)",
            false
    ));

    private final BooleanSetting holdToSpam = register(new BooleanSetting(
            "Hold To Spam",
            "Continuously repeat the Spear lunge every time your attack bar reaches 100% while holding the keybind",
            false
    ));

    private final BooleanSetting swapBack = register(new BooleanSetting(
            "Swap Back",
            "Visibly return to your previously held hotbar slot after the Spear jab",
            true
    ));

    private final BooleanSetting swapBackToSelectedSlot = register(new BooleanSetting(
            "SwapBack To Selected Slot",
            "Override standard Swap Back and always return to a specific hotbar slot (1-9)",
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
            "Minimum extra client ticks (0 - 10) to wait between repeated lunges",
            2.0D,
            0.0D,
            10.0D,
            1.0D
    ));

    private final NumberSetting maxRandomTicks = register(new NumberSetting(
            "Max Random (Ticks)",
            "Maximum extra client ticks (0 - 10) to wait between repeated lunges",
            5.0D,
            0.0D,
            10.0D,
            1.0D
    ));

    private final ModeSetting cooldownSlotSelector = register(new ModeSetting(
            "Cooldown Slot",
            "Fast-recharge hotbar slot (1-9) to hold between lunges (e.g. empty hand or food)",
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

    // ==================== 2. State Fields ====================

    private static volatile int syncedGuiBoundKey = -1;
    private static volatile boolean callbackPressLatched = false;
    private static Field cachedModuleKeyField = null;
    private static Method cachedModuleGetKeyMethod = null;
    private static boolean moduleKeyReflectionInit = false;

    private LungePhase phase = LungePhase.IDLE;
    private int pendingReturnSlot = -1;
    private int visibleHoldTicks = 0;

    private boolean pendingSingleLunge = false;
    private int pendingWaitTicks = 0;
    private boolean wasBindPressedLastTick = false;
    private boolean singleLungeFiredThisHold = false;
    private long lastTickProcessedGameTime = -1L;

    private int clientTickCounter = 0;
    private int nextAllowedLungeTick = 0;

    private static final int VISIBLE_SPEAR_HOLD_TICKS = 2;
    private static final int MAX_PENDING_WAIT_TICKS = 25;

    public LungeHelper() {
        super("LungeHelper", "Keybind-driven Spear-only legit Lunge swap macro for Minecraft 1.21.11", ModuleCategory.COMBAT);
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
        resetState();
    }

    private void resetState() {
        this.phase = LungePhase.IDLE;
        this.pendingReturnSlot = -1;
        this.visibleHoldTicks = 0;
        this.pendingSingleLunge = false;
        this.pendingWaitTicks = 0;
        this.wasBindPressedLastTick = false;
        this.singleLungeFiredThisHold = false;
        callbackPressLatched = false;
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

        if (client.screen != null) {
            callbackPressLatched = false;
            wasBindPressedLastTick = false;
            pendingSingleLunge = false;
            if (phase == LungePhase.HOLD_VISIBLE_SPEAR_BEFORE_SWAP_BACK) {
                completeVisibleSwapBack(client);
            }
            return;
        }

        boolean callbackTap = callbackPressLatched;
        callbackPressLatched = false;

        boolean keyDown = isLungeKeybindDown(client);
        boolean risingEdge = (keyDown && !wasBindPressedLastTick) || callbackTap;
        wasBindPressedLastTick = keyDown;

        if (risingEdge) {
            this.singleLungeFiredThisHold = false;
            this.pendingSingleLunge = true;
            this.pendingWaitTicks = 0;
        }

        if (!keyDown) {
            this.singleLungeFiredThisHold = false;
        }

        // Stage 2: Keep Spear visibly selected in client view for 2 ticks (~100ms), then swap back cleanly
        if (phase == LungePhase.HOLD_VISIBLE_SPEAR_BEFORE_SWAP_BACK) {
            visibleHoldTicks++;
            if (visibleHoldTicks >= VISIBLE_SPEAR_HOLD_TICKS) {
                completeVisibleSwapBack(client);
            }
            return;
        }

        boolean wantsSpam = keyDown && Boolean.TRUE.equals(holdToSpam.getValue());
        boolean wantsSingle = pendingSingleLunge && !singleLungeFiredThisHold;

        if (!wantsSpam && !wantsSingle) {
            return;
        }

        if (pendingSingleLunge) {
            pendingWaitTicks++;
            if (pendingWaitTicks > MAX_PENDING_WAIT_TICKS) {
                pendingSingleLunge = false;
                return;
            }
        }

        // Do not fire while eating, blocking with shield, or charging a Spear
        if (client.player.isUsingItem()) {
            return;
        }

        int spearSlot = findSpearHotbarSlot(client);
        if (spearSlot == -1) {
            pendingSingleLunge = false;
            return;
        }

        if (isSpearOnCooldown(client, spearSlot) || clientTickCounter < nextAllowedLungeTick) {
            int cooldownSlot = getSelectedCooldownSlotIndex();
            if (cooldownSlot >= 0 && cooldownSlot < 9) {
                selectHotbarSlotWithPacket(client, cooldownSlot);
            }
            return;
        }

        // Vanilla 1.21.11 requires a 100% charged attack bar for Spear STAB / Lunge (works on ground and in air)
        if (client.player.getAttackStrengthScale(0.5F) < 1.0F) {
            int cooldownSlot = getSelectedCooldownSlotIndex();
            if (cooldownSlot >= 0 && cooldownSlot < 9) {
                selectHotbarSlotWithPacket(client, cooldownSlot);
            }
            return;
        }

        // Stage 1: Visibly swap to Spear (C09) + dispatch Action.STAB + swing
        executeLegitVisibleSpearLunge(client, spearSlot);

        pendingSingleLunge = false;
        singleLungeFiredThisHold = true;
        int extraTicks = Boolean.TRUE.equals(randomization.getValue()) ? computeRandomizedDelayTicks() : 0;
        nextAllowedLungeTick = clientTickCounter + VISIBLE_SPEAR_HOLD_TICKS + extraTicks;
    }

    /**
     * Legit-looking Spear Lunge Swap (works on ground AND in midair):
     * Visibly selects the Spear slot on the client hotbar, sends C09 -> Action.STAB -> Swing,
     * and holds the Spear visibly for 2 ticks before swapping back to the carrier/return slot.
     */
    private void executeLegitVisibleSpearLunge(Minecraft client, int spearSlot) {
        if (client == null || client.player == null || client.player.connection == null) {
            return;
        }

        int currentSlot = client.player.getInventory().getSelectedSlot();
        int returnSlot = resolveReturnSlot(currentSlot, spearSlot);

        // 1. Visibly switch to Spear slot & send C09 FIRST
        if (currentSlot != spearSlot) {
            selectHotbarSlotWithPacket(client, spearSlot);
        }

        // 2. Dispatch Minecraft 1.21.11 Spear Jab / Lunge packet + swing + reset attack ticker
        client.player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.STAB,
                BlockPos.ZERO,
                Direction.DOWN
        ));
        client.player.swing(InteractionHand.MAIN_HAND);
        client.player.resetAttackStrengthTicker();

        // 3. Keep Spear visibly held for 2 ticks before swapping back so client view looks legit
        if (returnSlot >= 0 && returnSlot < 9 && returnSlot != spearSlot) {
            this.pendingReturnSlot = returnSlot;
            this.visibleHoldTicks = 0;
            this.phase = LungePhase.HOLD_VISIBLE_SPEAR_BEFORE_SWAP_BACK;
        } else {
            this.pendingReturnSlot = -1;
            this.visibleHoldTicks = 0;
            this.phase = LungePhase.IDLE;
        }
    }

    private void completeVisibleSwapBack(Minecraft client) {
        if (pendingReturnSlot >= 0 && pendingReturnSlot < 9) {
            selectHotbarSlotWithPacket(client, pendingReturnSlot);
        }
        pendingReturnSlot = -1;
        visibleHoldTicks = 0;
        phase = LungePhase.IDLE;
    }

    private int resolveReturnSlot(int previousHeldSlot, int spearSlot) {
        if (Boolean.TRUE.equals(swapBackToSelectedSlot.getValue())) {
            return getSelectedReturnSlotIndex();
        }
        int cooldownSlot = getSelectedCooldownSlotIndex();
        if (cooldownSlot >= 0 && cooldownSlot < 9) {
            return cooldownSlot;
        }
        if (Boolean.TRUE.equals(swapBack.getValue()) && previousHeldSlot >= 0 && previousHeldSlot < 9) {
            return previousHeldSlot;
        }
        return spearSlot;
    }

    private void selectHotbarSlotWithPacket(Minecraft client, int targetSlot) {
        if (client == null || client.player == null || client.player.getInventory() == null) {
            return;
        }
        if (targetSlot < 0 || targetSlot >= 9) {
            return;
        }
        if (client.player.getInventory().getSelectedSlot() != targetSlot) {
            client.player.getInventory().setSelectedSlot(targetSlot);
            if (client.player.connection != null) {
                client.player.connection.send(new ServerboundSetCarriedItemPacket(targetSlot));
            }
        }
    }

    private int findSpearHotbarSlot(Minecraft client) {
        if (client == null || client.player == null || client.player.getInventory() == null) {
            return -1;
        }
        int fallbackSpear = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (isSpearItem(stack)) {
                if (!stack.getEnchantments().isEmpty()) {
                    return i;
                }
                if (fallbackSpear == -1) {
                    fallbackSpear = i;
                }
            }
        }
        return fallbackSpear;
    }

    private boolean isSpearItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            if (stack.get(DataComponents.PIERCING_WEAPON) != null) {
                return true;
            }
        } catch (Throwable ignored) {
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
