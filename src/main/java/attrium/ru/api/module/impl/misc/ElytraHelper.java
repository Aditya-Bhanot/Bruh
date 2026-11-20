package attrium.ru.api.module.impl.misc;

import attrium.ru.api.events.annotation.SubscribeEvent;
import attrium.ru.api.events.impl.DrawEvent;
import attrium.ru.api.events.impl.TickEvent;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.ModuleCategory;
import attrium.ru.api.module.impl.combat.MaceTarget;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.NumberSetting;
import attrium.ru.mixin.accessor.CarriedItemSyncInvoker;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Advanced Chestplate <-> Elytra Hotbar Macro (Minecraft 1.21.11 Official Mojang Mappings).
 * - Uses CarriedItemSyncInvoker so MultiPlayerGameMode stays 100% synced with the server.
 * - Optional Auto Glide + Auto Firework boost in mid-air.
 * - Automatic 1-tick retry if EquipmentSlot.CHEST didn't flip + 1.2s manual lock vs AutoElytra/AutoMace.
 */
public class ElytraHelper extends Module {

    private static final int BASE_SWAP_COOLDOWN_TICKS = 2;
    private static final long MANUAL_OVERRIDE_LOCK_MS = 1200L;

    private enum SwapPhase {
        IDLE,
        WAIT_BEFORE_EQUIP,
        RETRY_EQUIP,
        WAIT_BEFORE_RETURN,
        WAIT_BEFORE_GLIDE,
        WAIT_BEFORE_FIREWORK,
        RETURN_AFTER_FIREWORK
    }

    private static ElytraHelper instance;
    private static int syncedGuiBoundKey = -1;
    private static boolean callbackPressLatched = false;
    private static volatile long manualSwapLockUntilMs = 0L;

    private static boolean reflectionInitialized = false;
    private static Method moduleKeyGetterMethod = null;
    private static Field moduleKeyField = null;

    private final BooleanSetting autoStartGlide = register(new BooleanSetting(
            "Auto Glide",
            "Automatically start Elytra glide in mid-air after equipping",
            true
    ));
    private final BooleanSetting autoFirework = register(new BooleanSetting(
            "Auto Firework",
            "Use a hotbar Firework Rocket right after mid-air glide starts",
            false
    ));
    private final BooleanSetting randomization = register(new BooleanSetting(
            "Randomization",
            "Add subtle 1-2 tick timing jitter between macro stages",
            true
    ));
    private final NumberSetting minRandomTicks = register(new NumberSetting(
            "Min Random (Ticks)",
            "Minimum tick delay per stage when Randomization is enabled",
            1.0D, 1.0D, 10.0D, 1.0D
    ));
    private final NumberSetting maxRandomTicks = register(new NumberSetting(
            "Max Random (Ticks)",
            "Maximum tick delay per stage when Randomization is enabled",
            2.0D, 1.0D, 10.0D, 1.0D
    ));
    private final NumberSetting savedElytraKeyCode = register(new NumberSetting(
            "_elytraHelperKeyCode",
            "Internal saved keybind code",
            -1.0D, -200.0D, 400.0D, 1.0D
    ));

    private SwapPhase phase = SwapPhase.IDLE;
    private int targetArmorSlot = -1;
    private int previousHotbarSlot = -1;
    private boolean equippingElytra = false;
    private boolean queuedSwapPending = false;
    private int phaseTicksElapsed = 0;
    private int requiredPhaseTicks = 1;
    private long lastProcessedGameTime = Long.MIN_VALUE;
    private long clientTickCounter = 0L;
    private long nextAllowedSwapTick = 0L;
    private boolean wasBindPressedLastTick = false;

    public ElytraHelper() {
        super("ElytraHelper", "Advanced hotbar Chestplate <-> Elytra + Glide/Rocket macro", ModuleCategory.MISC);
        instance = this;
        int saved = savedElytraKeyCode.getValue().intValue();
        if (saved != -1 && saved != 0) {
            syncedGuiBoundKey = saved;
        }
    }

    public static ElytraHelper getInstance() {
        if (instance == null) {
            instance = new ElytraHelper();
        }
        return instance;
    }

    public static boolean isManualSwapLockActive() {
        if (instance != null && instance.phase != SwapPhase.IDLE) {
            return true;
        }
        return System.currentTimeMillis() < manualSwapLockUntilMs;
    }

    public static boolean isSwapBusy() {
        return instance != null && (instance.phase != SwapPhase.IDLE || instance.queuedSwapPending);
    }

    public static void setMacroBoundKey(int key) {
        int normalized = (key == 0 || key == GLFW.GLFW_KEY_UNKNOWN) ? -1 : key;
        syncedGuiBoundKey = normalized;
        if (instance != null) {
            instance.savedElytraKeyCode.setValue((double) normalized);
        }
    }

    public static int getMacroBoundKey() {
        if (syncedGuiBoundKey != -1) {
            return syncedGuiBoundKey;
        }
        if (instance != null) {
            int saved = instance.savedElytraKeyCode.getValue().intValue();
            if (saved != -1 && saved != 0) {
                syncedGuiBoundKey = saved;
                return saved;
            }
        }
        return -1;
    }

    public static void onMacroKeyPressedFromCallback(int key) {
        int bound = getMacroBoundKey();
        if (bound != -1 && key == bound) {
            callbackPressLatched = true;
        }
    }

    public static void tickMacroGlobal(Minecraft client, int guiBoundKey) {
        ElytraHelper helper = getInstance();
        if (guiBoundKey != -1 && guiBoundKey != 0 && guiBoundKey != GLFW.GLFW_KEY_UNKNOWN) {
            syncedGuiBoundKey = guiBoundKey;
        }
        helper.processClientTick(client);
    }

    @Override
    protected void onDisable() {
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.player != null && previousHotbarSlot >= 0 && previousHotbarSlot < 9) {
            selectSlotSynced(client, previousHotbarSlot);
        }
        resetState();
    }

    private void resetState() {
        this.phase = SwapPhase.IDLE;
        this.targetArmorSlot = -1;
        this.previousHotbarSlot = -1;
        this.equippingElytra = false;
        this.queuedSwapPending = false;
        this.phaseTicksElapsed = 0;
        this.requiredPhaseTicks = 1;
        callbackPressLatched = false;
    }

    @SubscribeEvent
    public void onDraw(DrawEvent event) {
        if (maxRandomTicks.getValue() < minRandomTicks.getValue()) {
            maxRandomTicks.setValue(minRandomTicks.getValue());
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.Pre event) {
        Minecraft client = event != null ? event.getClient() : Minecraft.getInstance();
        processClientTick(client);
    }

    private void processClientTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null || client.gameMode == null) {
            resetState();
            return;
        }

        long gameTime = client.level.getGameTime();
        if (gameTime == lastProcessedGameTime) {
            return;
        }
        lastProcessedGameTime = gameTime;
        clientTickCounter++;

        if (client.screen != null) {
            if (phase != SwapPhase.IDLE && previousHotbarSlot >= 0 && previousHotbarSlot < 9) {
                selectSlotSynced(client, previousHotbarSlot);
            }
            resetState();
            wasBindPressedLastTick = false;
            return;
        }

        boolean keyDown = isMacroKeyDown(client);
        boolean callbackTap = callbackPressLatched;
        callbackPressLatched = false;
        boolean risingEdge = callbackTap || (keyDown && !wasBindPressedLastTick);
        wasBindPressedLastTick = keyDown;

        if (risingEdge) {
            queuedSwapPending = true;
            manualSwapLockUntilMs = System.currentTimeMillis() + MANUAL_OVERRIDE_LOCK_MS;
        }

        if (phase != SwapPhase.IDLE) {
            advanceMacro(client);
            return;
        }

        if (queuedSwapPending && clientTickCounter >= nextAllowedSwapTick && !client.player.isUsingItem()) {
            if (MaceTarget.ActionManager.getInstance().isSwapInProgress()) {
                return;
            }
            queuedSwapPending = false;
            startSwapMacro(client);
        }
    }

    private void startSwapMacro(Minecraft client) {
        boolean wearingElytra = client.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
        int slot = wearingElytra ? findBestChestplateSlot(client) : findBestElytraSlot(client);
        if (slot < 0 || slot >= 9) {
            return;
        }

        this.equippingElytra = !wearingElytra;
        this.targetArmorSlot = slot;
        this.previousHotbarSlot = client.player.getInventory().getSelectedSlot();
        this.phaseTicksElapsed = 0;
        manualSwapLockUntilMs = System.currentTimeMillis() + MANUAL_OVERRIDE_LOCK_MS;

        selectSlotSynced(client, slot);

        // Equip immediately on Tick 0 (fast & synced via CarriedItemSyncInvoker)
        InteractionResult res = client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
        boolean flipped = client.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA) == equippingElytra;
        if (flipped || res.consumesAction()) {
            this.requiredPhaseTicks = computeReturnDelayTicks();
            this.phase = SwapPhase.WAIT_BEFORE_RETURN;
        } else {
            this.phase = SwapPhase.RETRY_EQUIP;
        }
    }

    private void advanceMacro(Minecraft client) {
        phaseTicksElapsed++;

        switch (phase) {
            case RETRY_EQUIP -> {
                if (targetArmorSlot >= 0 && targetArmorSlot < 9) {
                    boolean flipped = client.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA) == equippingElytra;
                    if (!flipped) {
                        selectSlotSynced(client, targetArmorSlot);
                        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                    }
                }
                phaseTicksElapsed = 0;
                requiredPhaseTicks = 1;
                phase = SwapPhase.WAIT_BEFORE_RETURN;
            }
            case WAIT_BEFORE_RETURN -> {
                if (phaseTicksElapsed < requiredPhaseTicks) {
                    return;
                }
                if (previousHotbarSlot >= 0 && previousHotbarSlot < 9) {
                    selectSlotSynced(client, previousHotbarSlot);
                }
                manualSwapLockUntilMs = System.currentTimeMillis() + MANUAL_OVERRIDE_LOCK_MS;
                if (equippingElytra && Boolean.TRUE.equals(autoStartGlide.getValue()) && !client.player.onGround()) {
                    phaseTicksElapsed = 0;
                    phase = SwapPhase.WAIT_BEFORE_GLIDE;
                } else {
                    finishMacroCooldown();
                }
            }
            case WAIT_BEFORE_GLIDE -> {
                if (phaseTicksElapsed < 1) {
                    return;
                }
                boolean sentGlide = tryStartAirGlide(client);
                if (sentGlide && Boolean.TRUE.equals(autoFirework.getValue()) && findHotbarItem(client, Items.FIREWORK_ROCKET) >= 0) {
                    phaseTicksElapsed = 0;
                    phase = SwapPhase.WAIT_BEFORE_FIREWORK;
                } else {
                    finishMacroCooldown();
                }
            }
            case WAIT_BEFORE_FIREWORK -> {
                if (phaseTicksElapsed < 1) {
                    return;
                }
                int fwSlot = findHotbarItem(client, Items.FIREWORK_ROCKET);
                if (fwSlot >= 0 && fwSlot < 9 && client.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
                    selectSlotSynced(client, fwSlot);
                    client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                    phaseTicksElapsed = 0;
                    phase = SwapPhase.RETURN_AFTER_FIREWORK;
                } else {
                    finishMacroCooldown();
                }
            }
            case RETURN_AFTER_FIREWORK -> {
                if (previousHotbarSlot >= 0 && previousHotbarSlot < 9) {
                    selectSlotSynced(client, previousHotbarSlot);
                }
                finishMacroCooldown();
            }
            default -> finishMacroCooldown();
        }
    }

    private void finishMacroCooldown() {
        int jitter = Boolean.TRUE.equals(randomization.getValue()) ? ThreadLocalRandom.current().nextInt(2) : 0;
        nextAllowedSwapTick = clientTickCounter + BASE_SWAP_COOLDOWN_TICKS + jitter;
        resetState();
    }

    private boolean tryStartAirGlide(Minecraft client) {
        if (!equippingElytra || !Boolean.TRUE.equals(autoStartGlide.getValue())) {
            return false;
        }
        if (client.getConnection() == null || client.player == null) {
            return false;
        }
        if (!client.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            return false;
        }
        if (client.player.onGround()
                || client.player.isFallFlying()
                || client.player.isInWater()
                || client.player.getDeltaMovement().y >= -0.04D) {
            return false;
        }
        client.getConnection().send(new ServerboundPlayerCommandPacket(
                client.player,
                ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
        ));
        return true;
    }

    /**
     * Syncs selectedSlot through MultiPlayerGameMode's CarriedItemSyncInvoker so client,
     * gameMode.carriedIndex, and server never desync or emit duplicate slot packets.
     */
    private void selectSlotSynced(Minecraft client, int targetSlot) {
        if (client == null || client.player == null || client.gameMode == null || targetSlot < 0 || targetSlot >= 9) {
            return;
        }
        if (client.player.getInventory().getSelectedSlot() != targetSlot) {
            client.player.getInventory().setSelectedSlot(targetSlot);
            ((CarriedItemSyncInvoker) client.gameMode).attrium$ensureHasSentCarriedItem();
        }
    }

    private int computeReturnDelayTicks() {
        if (!Boolean.TRUE.equals(randomization.getValue())) {
            return 1;
        }
        int lo = Math.max(1, minRandomTicks.getValue().intValue());
        int hi = Math.max(lo, maxRandomTicks.getValue().intValue());
        return lo == hi ? lo : ThreadLocalRandom.current().nextInt(lo, hi + 1);
    }

    private int findHotbarItem(Minecraft client, net.minecraft.world.item.Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                return i;
            }
        }
        return -1;
    }

    private int findBestElytraSlot(Minecraft client) {
        int brokenFallback = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(Items.ELYTRA)) {
                if (stack.getDamageValue() < stack.getMaxDamage() - 1) {
                    return i;
                }
                if (brokenFallback == -1) {
                    brokenFallback = i;
                }
            }
        }
        return brokenFallback;
    }

    private int findBestChestplateSlot(Minecraft client) {
        int bestSlot = -1;
        int bestTier = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            int tier = getChestplateTier(stack);
            if (tier > bestTier) {
                bestTier = tier;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private int getChestplateTier(ItemStack stack) {
        String id = stack.getItem().toString().toLowerCase(Locale.ROOT);
        if (!id.contains("chestplate")) {
            return -1;
        }
        if (id.contains("netherite")) return 6;
        if (id.contains("diamond")) return 5;
        if (id.contains("iron")) return 4;
        if (id.contains("chainmail")) return 3;
        if (id.contains("golden") || id.contains("copper")) return 2;
        if (id.contains("leather")) return 1;
        return 1;
    }

    private int resolveActiveBoundKey() {
        if (syncedGuiBoundKey != -1) {
            return syncedGuiBoundKey;
        }
        initModuleKeyReflectionOnce();
        try {
            if (moduleKeyGetterMethod != null) {
                Object val = moduleKeyGetterMethod.invoke(this);
                if (val instanceof Integer k && k != 0 && k != GLFW.GLFW_KEY_UNKNOWN) {
                    return k;
                }
            } else if (moduleKeyField != null) {
                int k = moduleKeyField.getInt(this);
                if (k != 0 && k != GLFW.GLFW_KEY_UNKNOWN) {
                    return k;
                }
            }
        } catch (Throwable ignored) {
        }
        int saved = savedElytraKeyCode.getValue().intValue();
        return (saved == 0 || saved == GLFW.GLFW_KEY_UNKNOWN) ? -1 : saved;
    }

    private static void initModuleKeyReflectionOnce() {
        if (reflectionInitialized) {
            return;
        }
        reflectionInitialized = true;
        for (Method m : Module.class.getDeclaredMethods()) {
            String n = m.getName().toLowerCase(Locale.ROOT);
            if (m.getParameterCount() == 0 && (m.getReturnType() == int.class || m.getReturnType() == Integer.class)
                    && (n.contains("key") || n.contains("bind"))) {
                m.setAccessible(true);
                moduleKeyGetterMethod = m;
                return;
            }
        }
        for (Field f : Module.class.getDeclaredFields()) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (f.getType() == int.class && (n.contains("key") || n.contains("bind"))) {
                f.setAccessible(true);
                moduleKeyField = f;
                return;
            }
        }
    }

    private boolean isMacroKeyDown(Minecraft client) {
        int code = resolveActiveBoundKey();
        if (code == -1 || code == 0 || code == GLFW.GLFW_KEY_UNKNOWN) {
            return false;
        }
        long handle = client.getWindow().handle();
        if (code < -1) {
            int mouseBtn = code <= -100 ? (-100 - code) : (-10 - code);
            return mouseBtn >= 0 && mouseBtn <= GLFW.GLFW_MOUSE_BUTTON_LAST
                    && GLFW.glfwGetMouseButton(handle, mouseBtn) == GLFW.GLFW_PRESS;
        }
        if (code >= GLFW.GLFW_KEY_SPACE && code <= GLFW.GLFW_KEY_LAST) {
            return InputConstants.isKeyDown(client.getWindow(), code)
                    || GLFW.glfwGetKey(handle, code) == GLFW.GLFW_PRESS;
        }
        return false;
    }
}
