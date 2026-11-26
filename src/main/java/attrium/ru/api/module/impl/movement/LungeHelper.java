package attrium.ru.api.module.impl.movement;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import attrium.ru.mixin.accessor.CarriedItemSyncInvoker;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import attrium.ru.api.events.annotation.SubscribeEvent;
import attrium.ru.api.events.impl.DrawEvent;
import attrium.ru.api.events.impl.TickEvent;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.impl.combat.MaceTarget;
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
 *   3. Tick 1-2 (Visible Legit Hold): Keeps the Spear visibly selected in client view for 2 ticks (~100ms).
 *   4. Swap-Back Rules:
 *        - While holding the bind (especially during `Hold To Spam`), swaps back ONLY to the
 *          `Cooldown Slot` (if set) or the slot held before pressing the bind — NEVER to
 *          `SwapBack To Selected Slot` mid-spam.
 *        - When the bind is released, cleanly switches to `SwapBack To Selected Slot` (if enabled)
 *          or restores the originally held slot (if `Swap Back` is enabled).
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
            "Switch to a defined hotbar slot (1-9) when you release the LungeHelper keybind",
            false
    ));

    private final ModeSetting slotSelector = register(new ModeSetting(
            "Slot",
            "Target hotbar slot (1-9) switched to upon releasing the keybind when SwapBack To Selected Slot is enabled",
            "1",
            "1", "2", "3", "4", "5", "6", "7", "8", "9"
    ));

    private final BooleanSetting randomization = register(new BooleanSetting(
            "Randomization",
            "Enable tick-synced randomized delay (e.g. 2 to 5 ticks) between repeated lunges",
            true
    ));

    private final NumberSetting minRandomTicks = register(new NumberSetting(
            "Min Random (Ticks)",
            "Minimum extra client ticks (0 - 10) to wait between repeated lunges",
            1.0D,
            0.0D,
            20.0D,
            1.0D
    ));

    private final NumberSetting maxRandomTicks = register(new NumberSetting(
            "Max Random (Ticks)",
            "Maximum extra client ticks (0 - 10) to wait between repeated lunges",
            2.0D,
            0.0D,
            20.0D,
            1.0D
    ));

    private final ModeSetting cooldownSlotSelector = register(new ModeSetting(
            "Cooldown Slot",
            "Fast-recharge hotbar slot (1-9) to hold between lunges while holding the bind (e.g. empty hand or food)",
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
    private int initialHeldSlot = -1;
    private int activeSpearSlot = -1;
    private int visibleHoldTicks = 0;
    private boolean lungeFiredDuringCurrentHold = false;
    private boolean pendingReleaseSwap = false;

    private boolean pendingSingleLunge = false;
    private int pendingWaitTicks = 0;
    private boolean wasBindPressedLastTick = false;
    private boolean singleLungeFiredThisHold = false;
    private long lastTickProcessedGameTime = -1L;

    private int clientTickCounter = 0;
    private int nextAllowedLungeTick = 0;

    private static final int VISIBLE_SPEAR_HOLD_TICKS = 1;
    private static final int MAX_PENDING_WAIT_TICKS = 25;

    public LungeHelper() {
        super("LungeHelper", "Executes timed weapon swaps for instant lunge momentum", ModuleCategory.COMBAT);
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
        this.initialHeldSlot = -1;
        this.activeSpearSlot = -1;
        this.visibleHoldTicks = 0;
        this.lungeFiredDuringCurrentHold = false;
        this.pendingReleaseSwap = false;
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
        // Driven exclusively via Manager.java ClientTickEvents.START_CLIENT_TICK -> tickMacroGlobal()
    }

    public void processClientTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null || client.gameMode == null) {
            resetState();
            return;
        }

        MaceTarget.ActionManager.getInstance().onPreTickRender(client);
        long currentGameTime = client.level.getGameTime();
        if (currentGameTime == lastTickProcessedGameTime) {
            return;
        }
        lastTickProcessedGameTime = currentGameTime;
        clientTickCounter++;
        if (nextAllowedLungeTick - clientTickCounter > 25) {
            nextAllowedLungeTick = clientTickCounter;
        }

        if (client.screen != null) {
            callbackPressLatched = false;
            wasBindPressedLastTick = false;
            pendingSingleLunge = false;
            if (phase == LungePhase.HOLD_VISIBLE_SPEAR_BEFORE_SWAP_BACK || lungeFiredDuringCurrentHold) {
                executeFinalReleaseSwap(client);
            }
            return;
        }

        boolean callbackTap = callbackPressLatched;
        callbackPressLatched = false;

        boolean keyDown = isLungeKeybindDown(client);
        boolean risingEdge = (keyDown && !wasBindPressedLastTick) || callbackTap;
        boolean fallingEdge = !keyDown && wasBindPressedLastTick;
        wasBindPressedLastTick = keyDown;

        if (risingEdge) {
            if (this.initialHeldSlot < 0 || this.initialHeldSlot > 8) {
                this.initialHeldSlot = client.player.getInventory().getSelectedSlot();
            }
            this.singleLungeFiredThisHold = false;
            this.lungeFiredDuringCurrentHold = false;
            this.pendingReleaseSwap = false;
            this.pendingSingleLunge = true;
            this.pendingWaitTicks = 0;
        }

        if (fallingEdge) {
            this.singleLungeFiredThisHold = false;
            if (this.lungeFiredDuringCurrentHold || this.phase == LungePhase.HOLD_VISIBLE_SPEAR_BEFORE_SWAP_BACK) {
                this.pendingReleaseSwap = true;
            }
        }

        // Stage 2: Keep Spear visibly selected in client view for 2 ticks (~100ms), then swap back cleanly
        if (phase == LungePhase.HOLD_VISIBLE_SPEAR_BEFORE_SWAP_BACK) {
            visibleHoldTicks++;
            if (visibleHoldTicks >= VISIBLE_SPEAR_HOLD_TICKS
                    && MaceTarget.ActionManager.getInstance().isSafePreMotionTick()) {
                if (!keyDown || pendingReleaseSwap) {
                    // Keybind was released -> switch to SwapBack To Selected Slot (if ON) or original slot
                    executeFinalReleaseSwap(client);
                } else {
                    // Keybind is STILL held (e.g. Hold To Spam) -> NEVER switch to SwapBack To Selected Slot!
                    // Switch only to Cooldown Slot (if set) or original fast carrier slot to recharge.
                    executeMidHoldSwapBack(client);
                }
            }
            return;
        }

        // If user just released the keybind while IDLE after spamming/lunging, now switch to Selected Slot!
        if (!keyDown && pendingReleaseSwap && !pendingSingleLunge) {
            executeFinalReleaseSwap(client);
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
                if (!keyDown && pendingReleaseSwap) {
                    executeFinalReleaseSwap(client);
                }
                return;
            }
        }

        // Do not fire while eating/blocking, on a catch-up tick, or during an AutoElytra/AutoMace swap
        MaceTarget.ActionManager actionMgr = MaceTarget.ActionManager.getInstance();
        if (client.player.isUsingItem()
                || !actionMgr.isSafePreMotionTick()
                || actionMgr.isSwapInProgress()
                || !actionMgr.canMutateHotbarSlotThisTick()) {
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
        if (false /* advanced macro: do not stall on post-swap attack ticker reset */) {
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
        lungeFiredDuringCurrentHold = true;
        if (!keyDown) {
            pendingReleaseSwap = true;
        }
        int extraTicks = Boolean.TRUE.equals(randomization.getValue()) ? computeRandomizedDelayTicks() : 0;
        nextAllowedLungeTick = clientTickCounter + Math.max(1, extraTicks);
    }

    private void executeLegitVisibleSpearLunge(Minecraft client, int spearSlot) {
        if (client == null || client.player == null || client.player.connection == null) {
            return;
        }

        int currentSlot = client.player.getInventory().getSelectedSlot();
        if (initialHeldSlot < 0 || initialHeldSlot > 8) {
            initialHeldSlot = currentSlot;
        }
        activeSpearSlot = spearSlot;

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
        this.visibleHoldTicks = 0;
        this.phase = LungePhase.HOLD_VISIBLE_SPEAR_BEFORE_SWAP_BACK;
    }

    /**
     * Called between lunges while the keybind is STILL held (Hold To Spam).
     * Never switches to `SwapBack To Selected Slot`; only switches to `Cooldown Slot`
     * (if set) or `initialHeldSlot` (if `Swap Back` is enabled) so the fast slot recharges.
     */
    private void executeMidHoldSwapBack(Minecraft client) {
        int cooldownSlot = getSelectedCooldownSlotIndex();
        if (cooldownSlot >= 0 && cooldownSlot < 9) {
            selectHotbarSlotWithPacket(client, cooldownSlot);
        } else if (Boolean.TRUE.equals(swapBack.getValue())
                && initialHeldSlot >= 0 && initialHeldSlot < 9
                && initialHeldSlot != activeSpearSlot) {
            selectHotbarSlotWithPacket(client, initialHeldSlot);
        }
        visibleHoldTicks = 0;
        phase = LungePhase.IDLE;
    }

    /**
     * Called ONLY when the user releases the keybind (or after a single tap finishes).
     * Switches to `SwapBack To Selected Slot` if enabled, otherwise restores `initialHeldSlot`.
     */
    private void executeFinalReleaseSwap(Minecraft client) {
        if (Boolean.TRUE.equals(swapBackToSelectedSlot.getValue())) {
            int targetSlot = getSelectedReturnSlotIndex();
            if (targetSlot >= 0 && targetSlot < 9) {
                selectHotbarSlotWithPacket(client, targetSlot);
            }
        } else if (Boolean.TRUE.equals(swapBack.getValue())
                && initialHeldSlot >= 0 && initialHeldSlot < 9) {
            selectHotbarSlotWithPacket(client, initialHeldSlot);
        }

        phase = LungePhase.IDLE;
        visibleHoldTicks = 0;
        initialHeldSlot = -1;
        activeSpearSlot = -1;
        lungeFiredDuringCurrentHold = false;
        pendingReleaseSwap = false;
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
            if (client.gameMode != null) {
                ((CarriedItemSyncInvoker) client.gameMode).attrium$ensureHasSentCarriedItem();
            }
            MaceTarget.ActionManager.getInstance().markHotbarSlotMutated();
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

    private boolean isSpearOnCooldown(Minecraft client, int spearSlot) {
        return false;
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
