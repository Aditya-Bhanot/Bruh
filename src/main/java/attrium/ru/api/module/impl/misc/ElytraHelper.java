package attrium.ru.api.module.impl.misc;

import attrium.ru.api.events.annotation.SubscribeEvent;
import attrium.ru.api.events.impl.DrawEvent;
import attrium.ru.api.events.impl.TickEvent;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.ModuleCategory;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.NumberSetting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
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

public class ElytraHelper extends Module {

    private static final int BASE_SWAP_COOLDOWN_TICKS = 4;
    private static final int POST_EQUIP_GLIDE_DELAY_TICKS = 3;

    private enum SwapPhase {
        IDLE,
        WAIT_BEFORE_EQUIP,
        WAIT_BEFORE_RETURN_SLOT,
        WAIT_BEFORE_GLIDE
    }

    private static ElytraHelper instance;
    private static int syncedGuiBoundKey = -1;
    private static boolean callbackPressLatched = false;

    private static boolean reflectionInitialized = false;
    private static Method moduleKeyGetterMethod = null;
    private static Field moduleKeyField = null;

    private final BooleanSetting autoStartGlide = register(new BooleanSetting(
            "Auto Glide",
            "Automatically start Elytra glide safely after equipping in mid-air",
            true
    ));
    private final BooleanSetting randomization = register(new BooleanSetting(
            "Randomization",
            "Randomize tick delays between hotbar switch, equip, and slot restore",
            false
    ));
    private final NumberSetting minRandomTicks = register(new NumberSetting(
            "Min Random (Ticks)",
            "Minimum tick delay per swap stage when Randomization is enabled",
            1.0D, 1.0D, 10.0D, 1.0D
    ));
    private final NumberSetting maxRandomTicks = register(new NumberSetting(
            "Max Random (Ticks)",
            "Maximum tick delay per swap stage when Randomization is enabled",
            3.0D, 1.0D, 10.0D, 1.0D
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
    private int phaseTicksElapsed = 0;
    private int requiredPhaseTicks = 1;
    private long lastProcessedGameTime = Long.MIN_VALUE;
    private long clientTickCounter = 0L;
    private long nextAllowedSwapTick = 0L;
    private boolean wasBindPressedLastTick = false;

    public ElytraHelper() {
        super("ElytraHelper", "Grim-safe hotbar Chestplate <-> Elytra swap macro", ModuleCategory.MISC);
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
            selectHotbarSlotSafe(client, previousHotbarSlot);
        }
        resetState();
    }

    private void resetState() {
        this.phase = SwapPhase.IDLE;
        this.targetArmorSlot = -1;
        this.previousHotbarSlot = -1;
        this.equippingElytra = false;
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
            if (phase == SwapPhase.WAIT_BEFORE_RETURN_SLOT && previousHotbarSlot >= 0 && previousHotbarSlot < 9) {
                selectHotbarSlotSafe(client, previousHotbarSlot);
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

        if (phase != SwapPhase.IDLE) {
            advanceSwapSequence(client);
            return;
        }

        if (risingEdge && clientTickCounter >= nextAllowedSwapTick && !client.player.isUsingItem()) {
            beginLegitChestplateElytraSwap(client);
        }
    }

    private void beginLegitChestplateElytraSwap(Minecraft client) {
        boolean wearingElytra = client.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
        int slot = wearingElytra ? findBestChestplateSlot(client) : findBestElytraSlot(client);
        if (slot < 0 || slot >= 9) {
            return;
        }

        this.equippingElytra = !wearingElytra;
        this.targetArmorSlot = slot;
        this.previousHotbarSlot = client.player.getInventory().getSelectedSlot();
        this.phaseTicksElapsed = 0;
        this.requiredPhaseTicks = computeStageDelayTicks();

        selectHotbarSlotSafe(client, slot);
        this.phase = SwapPhase.WAIT_BEFORE_EQUIP;
    }

    private void advanceSwapSequence(Minecraft client) {
        phaseTicksElapsed++;

        switch (phase) {
            case WAIT_BEFORE_EQUIP -> {
                if (phaseTicksElapsed < requiredPhaseTicks) {
                    return;
                }
                if (targetArmorSlot < 0 || targetArmorSlot >= 9) {
                    resetState();
                    return;
                }
                if (client.player.getInventory().getSelectedSlot() != targetArmorSlot) {
                    selectHotbarSlotSafe(client, targetArmorSlot);
                }
                InteractionResult result = client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                phaseTicksElapsed = 0;
                requiredPhaseTicks = computeStageDelayTicks();
                if (result.consumesAction()) {
                    phase = SwapPhase.WAIT_BEFORE_RETURN_SLOT;
                } else {
                    phase = SwapPhase.WAIT_BEFORE_RETURN_SLOT;
                }
            }
            case WAIT_BEFORE_RETURN_SLOT -> {
                if (phaseTicksElapsed < requiredPhaseTicks) {
                    return;
                }
                if (previousHotbarSlot >= 0 && previousHotbarSlot < 9) {
                    selectHotbarSlotSafe(client, previousHotbarSlot);
                }
                if (equippingElytra && Boolean.TRUE.equals(autoStartGlide.getValue())) {
                    phaseTicksElapsed = 0;
                    phase = SwapPhase.WAIT_BEFORE_GLIDE;
                } else {
                    nextAllowedSwapTick = clientTickCounter + BASE_SWAP_COOLDOWN_TICKS;
                    resetState();
                }
            }
            case WAIT_BEFORE_GLIDE -> {
                if (phaseTicksElapsed < POST_EQUIP_GLIDE_DELAY_TICKS) {
                    return;
                }
                tryStartAirGlide(client);
                nextAllowedSwapTick = clientTickCounter + BASE_SWAP_COOLDOWN_TICKS;
                resetState();
            }
            default -> resetState();
        }
    }

    private void tryStartAirGlide(Minecraft client) {
        if (!equippingElytra || !Boolean.TRUE.equals(autoStartGlide.getValue())) {
            return;
        }
        if (client.getConnection() == null || client.player == null) {
            return;
        }
        if (!client.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            return;
        }
        if (client.player.onGround()
                || client.player.isFallFlying()
                || client.player.isInWater()
                || client.player.getDeltaMovement().y >= -0.04D) {
            return;
        }
        client.getConnection().send(new ServerboundPlayerCommandPacket(
                client.player,
                ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
        ));
    }

    private void selectHotbarSlotSafe(Minecraft client, int targetSlot) {
        if (client == null || client.player == null || targetSlot < 0 || targetSlot >= 9) {
            return;
        }
        int current = client.player.getInventory().getSelectedSlot();
        if (current == targetSlot) {
            return;
        }
        if (client.player.connection != null) {
            client.player.connection.send(new ServerboundSetCarriedItemPacket(targetSlot));
        }
        client.player.getInventory().setSelectedSlot(targetSlot);
    }

    private int computeStageDelayTicks() {
        if (!Boolean.TRUE.equals(randomization.getValue())) {
            return 1;
        }
        int lo = Math.max(1, minRandomTicks.getValue().intValue());
        int hi = Math.max(lo, maxRandomTicks.getValue().intValue());
        return lo == hi ? lo : ThreadLocalRandom.current().nextInt(lo, hi + 1);
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
