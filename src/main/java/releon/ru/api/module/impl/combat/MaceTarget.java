package releon.ru.api.module.impl.combat;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.inventory.ClickType;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.events.impl.DrawEvent;
import releon.ru.api.events.impl.PlayerTravelEvent;
import releon.ru.api.events.impl.RotationUpdateEvent;
import releon.ru.api.events.types.EventPhase;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.AngleConfig;
import releon.ru.api.module.impl.combat.aura.AngleConnection;
import releon.ru.api.module.impl.combat.aura.MathAngle;
import releon.ru.api.module.impl.combat.aura.impl.RotateConstructor;
import releon.ru.api.module.impl.combat.aura.util.MathUtils;
import releon.ru.api.module.impl.combat.aura.util.StopWatch;
import releon.ru.api.module.impl.combat.aura.util.TaskPriority;
import releon.ru.api.module.impl.movement.ElytraTarget;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.inventory.lookup.InventoryUtils;
import releon.ru.utils.repository.friend.FriendUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Hotbar-Safe & High-Speed Handoff AutoMace Controller:
 * 1. Hotbar Item-Holding Fix (Event-Driven / Change-Driven Slot Caching):
 *    - Removed the continuous 4Hz background hotbar scanning loop completely.
 *    - Updates cachedMaceSlot, cachedAxeSlot, and cachedSwordSlot ONLY when hotbar contents change
 *      and strictly skips indexing if (player.isUsingItem() || actionManager.isLocked()).
 *    - Only restores savedHotbarSlot if AutoMace actually switched the player's slot during a strike,
 *      eliminating held-item flickering and weapon-holding desync.
 * 2. High-Speed Elytra Unequip & Zero-Delay Handoff:
 *    - Pre-emptively triggers Tick 0 Chestplate selection the instant target distance < 4.5m and v_y < -0.1,
 *      completes the Chestplate swap + weapon handoff on Tick 1, and immediately transitions to AutoMace strike.
 * 3. Preserved Aim Assist & Rotation Math:
 *    - Keeps "Legit" (PD + 20% inner deadzone + GCD), "Strict", and "Silent" rotation math 100% untouched.
 */
public class MaceTarget extends Module {
    private static MaceTarget instance;

    private static final double INNER_20_PERCENT_HALF_RATIO = 0.10D;
    private static final double MIN_FAR_ROTATION_DEG_PER_TICK = 40.0D;
    private static final double MAX_FAR_ROTATION_DEG_PER_TICK = 60.0D;
    private static final double PD_DECELERATION_CONE_DEGREES = 32.0D;
    private static final double PRIORITY_1_ELYTRA_UNEQUIP_DIST = 4.5D;
    private static final int MIN_TICKS_BETWEEN_MACE_STEPS = 1;

    /**
     * Embedded Split-Priority ActionManager with Fast 2-Tick Unequip & Active Hand Protection.
     */
    public static final class ActionManager {
        private static final ActionManager INSTANCE = new ActionManager();

        public enum ActionPriority {
            CRITICAL(0),
            AUTO_ELYTRA_UNEQUIP(1), // Priority 1 (Highest): High-speed 2-tick Chestplate unequip (< 4.5m & v_y < -0.1)
            AUTO_MACE_STRIKE(2),    // Priority 2 (Critical): AutoMace Strike (zero-delay handoff after unequip)
            COMBAT(2),
            AUTO_ELYTRA_EQUIP(3),   // Priority 3 (Lowest): Flight Deploy (v_y > 0.6 when AutoMace is idle)
            MOVEMENT(3),
            UTILITY(4);

            private final int rank;

            ActionPriority(int rank) {
                this.rank = rank;
            }

            public boolean outranks(ActionPriority other) {
                return other == null || this.rank < other.rank;
            }

            public boolean outranksOrEquals(ActionPriority other) {
                return other == null || this.rank <= other.rank;
            }
        }

        private enum ConsolidatedSwapStage {
            IDLE,
            FAST_TICK_1_EQUIP_AND_HANDOFF, // Optimized 2-tick armor swap: right-click + restore + unlock on Tick 1
            INV_RELAY_TICK_1_EQUIP,        // Tick 1 of 3-tick main-inventory relay swap: right-click to equip
            INV_RELAY_TICK_2_RESTORE,      // Tick 2 of 3-tick main-inventory relay swap: return old armor to invSlot
            WAIT_TO_INTERACT,
            WAIT_TO_RESTORE
        }

        private static final int MIN_TICKS_PER_SWAP_STAGE = 1;
        private static final long MIN_STAGE_MS = 45L;
        private static final double GLOBAL_MIN_CPS = 10.0D;
        private static final double GLOBAL_MAX_CPS = 14.0D;
        private static final long CRITICAL_LOCK_FAILSAFE_MS = 450L;
        private static final long GENERAL_LOCK_FAILSAFE_MS = 500L;

        private volatile ActionPriority activeLockPriority = null;
        private volatile String activeLockOwnerName = "";
        private volatile long lockAcquiredAtMs = 0L;

        private ConsolidatedSwapStage swapStage = ConsolidatedSwapStage.IDLE;
        private ActionPriority queuedSwapPriority = null;
        private int targetHotbarSlot = -1;
        private int restoreHotbarSlot = -1;
        private int relayInventorySlot = -1;
        private int stageStartTick = -1;
        private long stageStartTimeMs = 0L;
        private boolean simulateUseClick = false;
        private Runnable onSlotReadyCallback = null;

        private static volatile long lastLegitArmorSwapInterceptMs = 0L;
        private static volatile double elytraHelperReequipDelayMs = 200.0D;
        private static volatile int lastObservedHotbarSlotBeforeSelect = -1;
        private static volatile int lastObservedSelectTick = -100;
        private static volatile int suppressImmediateSlotRestoreTick = -100;

        private int lastInteractionTick = -1;
        private long lastInteractionTimeMs = 0L;
        private long currentMinInteractionIntervalMs = 78L;

        private int lastKnownPlayerId = -1;
        private int lastKnownTickCount = -1;
        private int cachedTargetsTick = -1;
        private final List<LivingEntity> validTargetsMutable = new ArrayList<>(32);
        private List<LivingEntity> validTargetsView = Collections.emptyList();

        private ActionManager() {
        }

        private void scheduleClientThreadFollowUp(Minecraft client) {
            if (client == null) {
                return;
            }
            CompletableFuture.delayedExecutor(55L, TimeUnit.MILLISECONDS)
                    .execute(() -> client.execute(() -> ensureTickSynchronized(client)));
            CompletableFuture.delayedExecutor(115L, TimeUnit.MILLISECONDS)
                    .execute(() -> client.execute(() -> ensureTickSynchronized(client)));
        }


        public static ActionManager getInstance() {
            return INSTANCE;
        }

        public boolean isLocked() {
            checkLockTimeouts();
            return activeLockPriority != null || swapStage != ConsolidatedSwapStage.IDLE;
        }

        public synchronized void ensureTickSynchronized(Minecraft client) {
            if (client == null || client.player == null || client.level == null) {
                resetAll();
                return;
            }

            // Detect Match Transition / Respawn / World Change where LocalPlayer is recreated or tickCount resets to 0
            int currentPlayerId = client.player.getId();
            int currentTick = client.player.tickCount;
            if (currentPlayerId != lastKnownPlayerId || currentTick < lastKnownTickCount) {
                lastKnownPlayerId = currentPlayerId;
                lastKnownTickCount = currentTick;
                forceReleaseAllLocks();
                lastInteractionTick = -100;
                cachedTargetsTick = -100;
                CombatStateManager.forceReleaseAllMutexes();
            } else {
                lastKnownTickCount = currentTick;
            }

            checkLockTimeouts();
            detectCriticalSurvivalLock(client);

            if (cachedTargetsTick != currentTick) {
                cachedTargetsTick = currentTick;
                rebuildValidTargetsCacheOncePerTick(client);
                advanceConsolidatedSwapMachine(client);
            }
        }

        private void detectCriticalSurvivalLock(Minecraft client) {
            if (client.player == null) {
                return;
            }
            boolean criticalHealthEating = (client.player.getHealth() + client.player.getAbsorptionAmount() <= 8.0F)
                    && client.player.isUsingItem()
                    && (client.player.getUseItem().has(DataComponents.FOOD)
                    || client.player.getUseItem().getItem() == Items.GOLDEN_APPLE
                    || client.player.getUseItem().getItem() == Items.ENCHANTED_GOLDEN_APPLE);

            if (criticalHealthEating) {
                acquireLock(ActionPriority.CRITICAL, "SurvivalAutoHeal");
            } else if (activeLockPriority == ActionPriority.CRITICAL && "SurvivalAutoHeal".equals(activeLockOwnerName)) {
                releaseLock(ActionPriority.CRITICAL, "SurvivalAutoHeal");
            }
        }

        private void checkLockTimeouts() {
            long now = System.currentTimeMillis();
            if (swapStage != ConsolidatedSwapStage.IDLE && (now - stageStartTimeMs) > 250L) {
                completeConsolidatedSwap();
            }
            if (activeLockPriority == null) {
                return;
            }
            long elapsed = now - lockAcquiredAtMs;
            long limit = activeLockPriority == ActionPriority.CRITICAL ? CRITICAL_LOCK_FAILSAFE_MS : GENERAL_LOCK_FAILSAFE_MS;
            if (elapsed > limit) {
                completeConsolidatedSwap();
                activeLockPriority = null;
                activeLockOwnerName = "";
            }
        }

        public synchronized boolean acquireLock(ActionPriority priority, String ownerName) {
            checkLockTimeouts();
            if (activeLockPriority != null && !priority.outranksOrEquals(activeLockPriority)) {
                return false;
            }
            if (activeLockPriority != null && priority.outranks(activeLockPriority) && swapStage != ConsolidatedSwapStage.IDLE) {
                abortActiveConsolidatedSwap(Minecraft.getInstance());
            }
            activeLockPriority = priority;
            activeLockOwnerName = ownerName != null ? ownerName : priority.name();
            lockAcquiredAtMs = System.currentTimeMillis();
            return true;
        }

        public synchronized void releaseLock(ActionPriority priority, String ownerName) {
            if (activeLockPriority == priority
                    && (ownerName == null || ownerName.isEmpty() || ownerName.equals(activeLockOwnerName))) {
                if (swapStage == ConsolidatedSwapStage.IDLE) {
                    activeLockPriority = null;
                    activeLockOwnerName = "";
                }
            }
        }

        public boolean isBlockedByHigherPriority(ActionPriority requesterPriority) {
            checkLockTimeouts();
            if (activeLockPriority == null) {
                return false;
            }
            return activeLockPriority.outranks(requesterPriority);
        }

        public ActionPriority getActiveLockPriority() {
            checkLockTimeouts();
            return activeLockPriority;
        }

        public boolean isSwapInProgress() {
            return swapStage != ConsolidatedSwapStage.IDLE;
        }

        public synchronized boolean canDispatchInteraction(Minecraft client, ActionPriority priority) {
            if (client == null || client.player == null) {
                return false;
            }
            if (isBlockedByHigherPriority(priority)) {
                return false;
            }
            if (client.player.tickCount < lastInteractionTick) {
                lastInteractionTick = -100;
            }
            if (client.player.tickCount == lastInteractionTick) {
                return false;
            }
            long now = System.currentTimeMillis();
            return (now - lastInteractionTimeMs) >= currentMinInteractionIntervalMs;
        }

        public synchronized void recordInteractionDispatched(Minecraft client) {
            if (client != null && client.player != null) {
                lastInteractionTick = client.player.tickCount;
            }
            lastInteractionTimeMs = System.currentTimeMillis();
            double cps = ThreadLocalRandom.current().nextDouble(GLOBAL_MIN_CPS, GLOBAL_MAX_CPS);
            currentMinInteractionIntervalMs = Math.round(1000.0D / cps);
        }

        /**
         * Section 2: Optimized 2-Tick High-Speed Elytra Unequip Pipeline & Zero-Delay Handoff:
         * - Tick 0 (Immediate): Physical hotbar selector changes to the Chestplate slot.
         * - Tick 1 (Next Tick): Right-clicks to swap the Chestplate onto the body, restores/hands off
         *   the hotbar slot, and immediately releases the lock so AutoMace strikes with zero dead frames.
         */
        public synchronized boolean submitFastTwoTickUnequip(
                Minecraft client,
                int chestplateSlot,
                int handoffSlot,
                Runnable onUnequipComplete
        ) {
            return submitFastTwoTickArmorSwap(
                    client,
                    ActionPriority.AUTO_ELYTRA_UNEQUIP,
                    "AutoElytra_UNEQUIP",
                    chestplateSlot,
                    handoffSlot,
                    onUnequipComplete
            );
        }

        /**
         * Universal Legit 2-Tick Hotbar Right-Click Armor Swap (Used by AutoElytra & ElytraHelper):
         * - Tick 0: Physically selects the target hotbar slot (0..8) containing the Elytra or Chestplate.
         * - Tick 1: Simulates a right-click (KeyMapping.click + gameMode.useItem + swing) to natively swap
         *           with EquipmentSlot.CHEST, restores the previous hotbar slot, and releases the lock.
         */
        public synchronized boolean submitFastTwoTickArmorSwap(
                Minecraft client,
                ActionPriority priority,
                String ownerName,
                int armorHotbarSlot,
                int handoffSlot,
                Runnable onSwapComplete
        ) {
            if (client == null || client.player == null || armorHotbarSlot < 0 || armorHotbarSlot > 8) {
                return false;
            }
            ensureTickSynchronized(client);

            if (isBlockedByHigherPriority(priority)) {
                return false;
            }
            if (swapStage != ConsolidatedSwapStage.IDLE && queuedSwapPriority != null) {
                if (!priority.outranks(queuedSwapPriority)) {
                    return false;
                }
                abortActiveConsolidatedSwap(client);
            }

            acquireLock(priority, ownerName);
            this.queuedSwapPriority = priority;
            this.targetHotbarSlot = armorHotbarSlot;
            this.restoreHotbarSlot = (handoffSlot >= 0 && handoffSlot <= 8)
                    ? handoffSlot
                    : client.player.getInventory().getSelectedSlot();
            this.relayInventorySlot = -1;
            this.simulateUseClick = true;
            this.onSlotReadyCallback = onSwapComplete;

            // Tick 0: Immediately select the target armor slot on the physical hotbar
            if (client.player.getInventory().getSelectedSlot() != armorHotbarSlot) {
                client.player.getInventory().setSelectedSlot(armorHotbarSlot);
            }
            this.stageStartTick = client.player.tickCount;
            this.stageStartTimeMs = System.currentTimeMillis();
            this.swapStage = ConsolidatedSwapStage.FAST_TICK_1_EQUIP_AND_HANDOFF;
            scheduleClientThreadFollowUp(client);
            return true;
        }

        /**
         * 3-Tick Legit Hotbar-Relay Right-Click Swap (Fallback when ElytraHelper's target Elytra/Chestplate
         * is in main inventory slots 9..35 rather than hotbar 0..8):
         * - Tick 0: Swaps main inventory slot (9..35) into the selected hotbar slot via ClickType.SWAP.
         * - Tick 1: Right-clicks (useItem + swing) to legitimately equip onto EquipmentSlot.CHEST.
         * - Tick 2: Swaps the unequipped chest item back into main inventory slot (9..35) and restores hotbar.
         */
        public synchronized boolean submitThreeTickInventoryRelayArmorSwap(
                Minecraft client,
                int invSlot,
                int restoreSlot
        ) {
            if (client == null || client.player == null || client.gameMode == null || invSlot < 9 || invSlot > 35) {
                return false;
            }
            ensureTickSynchronized(client);
            if (isSwapInProgress() || isBlockedByHigherPriority(ActionPriority.MOVEMENT)) {
                return false;
            }

            int relaySlot = client.player.getInventory().getSelectedSlot();
            if (relaySlot < 0 || relaySlot > 8) {
                relaySlot = 8;
            }

            acquireLock(ActionPriority.MOVEMENT, "ElytraHelper_RelaySwap");
            this.queuedSwapPriority = ActionPriority.MOVEMENT;
            this.targetHotbarSlot = relaySlot;
            this.restoreHotbarSlot = (restoreSlot >= 0 && restoreSlot <= 8) ? restoreSlot : relaySlot;
            this.relayInventorySlot = invSlot;
            this.simulateUseClick = true;
            this.onSlotReadyCallback = null;

            // Tick 0: Swap invSlot (9..35) into relaySlot (0..8)
            client.gameMode.handleInventoryMouseClick(
                    client.player.containerMenu.containerId,
                    invSlot,
                    relaySlot,
                    ClickType.SWAP,
                    client.player
            );
            if (client.player.getInventory().getSelectedSlot() != relaySlot) {
                client.player.getInventory().setSelectedSlot(relaySlot);
            }
            this.stageStartTick = client.player.tickCount;
            this.stageStartTimeMs = System.currentTimeMillis();
            this.swapStage = ConsolidatedSwapStage.INV_RELAY_TICK_1_EQUIP;
            scheduleClientThreadFollowUp(client);
            return true;
        }

        public static boolean shouldSuppressCloseScreen() {
            return INSTANCE.isSwapInProgress()
                    || (System.currentTimeMillis() - lastLegitArmorSwapInterceptMs) <= 250L;
        }

        public static void setElytraHelperReequipDelayMs(double delayMs) {
            elytraHelperReequipDelayMs = Math.max(0.0D, delayMs);
        }

        public static double getElytraHelperReequipDelayMs() {
            return elytraHelperReequipDelayMs;
        }

        /**
         * Universal entry point for ElytraHelper / InventoryUtils / ArmorSwapHandler:
         * Converts any chestplate/elytra swap request into a legit 2-tick hotbar right-click swap
         * (or 3-tick hotbar-relay right-click swap if the armor piece is in main inventory 9..35).
         */
        public static boolean executeLegitArmorSwapFromSlot(int rawOrWrappedSlot) {
            Minecraft client = Minecraft.getInstance();
            if (client == null || client.player == null) {
                return false;
            }
            ActionManager mgr = getInstance();
            mgr.ensureTickSynchronized(client);
            lastLegitArmorSwapInterceptMs = System.currentTimeMillis();

            if (mgr.isSwapInProgress()) {
                return true;
            }

            int normalizedSlot = rawOrWrappedSlot;
            if (normalizedSlot >= 36 && normalizedSlot <= 44) {
                normalizedSlot -= 36;
            }

            boolean currentlyWearingElytra = client.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
            boolean targetWantsElytra = !currentlyWearingElytra;
            if (normalizedSlot >= 0 && normalizedSlot <= 35) {
                ItemStack candidate = client.player.getInventory().getItem(normalizedSlot);
                if (!candidate.isEmpty()) {
                    if (candidate.is(Items.ELYTRA)) {
                        targetWantsElytra = true;
                    } else if (isChestplateItem(candidate.getItem())) {
                        targetWantsElytra = false;
                    }
                }
            }

            int hotbarSlot = -1;
            if (normalizedSlot >= 0 && normalizedSlot <= 8) {
                ItemStack s = client.player.getInventory().getItem(normalizedSlot);
                if (!s.isEmpty() && (targetWantsElytra ? s.is(Items.ELYTRA) : isChestplateItem(s.getItem()))) {
                    hotbarSlot = normalizedSlot;
                }
            }
            if (hotbarSlot == -1) {
                hotbarSlot = targetWantsElytra ? findElytraInHotbar(client) : findChestplateInHotbar(client);
            }

            int originalSelected = client.player.getInventory().getSelectedSlot();
            if (hotbarSlot >= 0 && hotbarSlot <= 8) {
                ActionPriority prio = targetWantsElytra ? ActionPriority.AUTO_ELYTRA_EQUIP : ActionPriority.AUTO_ELYTRA_UNEQUIP;
                return mgr.submitFastTwoTickArmorSwap(client, prio, "ElytraHelper_LegitSwap", hotbarSlot, originalSelected, null);
            }

            int invSlot = (normalizedSlot >= 9 && normalizedSlot <= 35)
                    ? normalizedSlot
                    : (targetWantsElytra ? findElytraInMainInventory(client) : findChestplateInMainInventory(client));
            if (invSlot >= 9 && invSlot <= 35) {
                return mgr.submitThreeTickInventoryRelayArmorSwap(client, invSlot, originalSelected);
            }
            return false;
        }

        public static boolean executeLegitChestSlotToggle() {
            return executeLegitArmorSwapFromSlot(-1);
        }

        /**
         * Tracks hotbar slot changes from InventoryUtils.selectSlot(slot) so if ElytraHelper attempts
         * a 0-tick (selectSlot -> useItem -> selectSlot) sequence, ActionManager converts it into a
         * legit 2-tick swap (holding the target slot on Tick 0 and right-clicking + restoring on Tick 1).
         */
        public static boolean onInventoryUtilsSelectSlot(int targetSlot) {
            Minecraft client = Minecraft.getInstance();
            if (client == null || client.player == null || targetSlot < 0 || targetSlot > 8) {
                return false;
            }
            int nowTick = client.player.tickCount;
            if (suppressImmediateSlotRestoreTick == nowTick && INSTANCE.isSwapInProgress()) {
                INSTANCE.restoreHotbarSlot = targetSlot;
                if (INSTANCE.targetHotbarSlot >= 0 && INSTANCE.targetHotbarSlot <= 8) {
                    client.player.getInventory().setSelectedSlot(INSTANCE.targetHotbarSlot);
                }
                return true;
            }
            int currentSelected = client.player.getInventory().getSelectedSlot();
            if (lastObservedSelectTick != nowTick) {
                lastObservedHotbarSlotBeforeSelect = currentSelected;
                lastObservedSelectTick = nowTick;
            }
            return false;
        }

        public static void interceptElytraHelperUseItem(Minecraft client, InteractionHand hand) {
            if (client == null || client.player == null || client.gameMode == null) {
                return;
            }
            int currentSelected = client.player.getInventory().getSelectedSlot();
            int nowTick = client.player.tickCount;
            int restoreSlot = (lastObservedSelectTick == nowTick && lastObservedHotbarSlotBeforeSelect >= 0 && lastObservedHotbarSlotBeforeSelect <= 8)
                    ? lastObservedHotbarSlotBeforeSelect
                    : currentSelected;
            suppressImmediateSlotRestoreTick = nowTick;
            lastLegitArmorSwapInterceptMs = System.currentTimeMillis();
            INSTANCE.submitFastTwoTickArmorSwap(
                    client,
                    ActionPriority.AUTO_ELYTRA_EQUIP,
                    "ElytraHelper_2TickUse",
                    currentSelected,
                    restoreSlot,
                    null
            );
        }

        private static boolean isChestplateItem(Item item) {
            return item == Items.NETHERITE_CHESTPLATE
                    || item == Items.DIAMOND_CHESTPLATE
                    || item == Items.CHAINMAIL_CHESTPLATE
                    || item == Items.IRON_CHESTPLATE
                    || item == Items.GOLDEN_CHESTPLATE
                    || item == Items.LEATHER_CHESTPLATE;
        }

        private static int findElytraInHotbar(Minecraft client) {
            if (client.player == null) {
                return -1;
            }
            for (int i = 0; i < 9; i++) {
                ItemStack s = client.player.getInventory().getItem(i);
                if (!s.isEmpty() && s.is(Items.ELYTRA)) {
                    return i;
                }
            }
            return -1;
        }

        private static int findChestplateInHotbar(Minecraft client) {
            if (client.player == null) {
                return -1;
            }
            Item[] order = new Item[]{
                    Items.NETHERITE_CHESTPLATE,
                    Items.DIAMOND_CHESTPLATE,
                    Items.CHAINMAIL_CHESTPLATE,
                    Items.IRON_CHESTPLATE,
                    Items.GOLDEN_CHESTPLATE,
                    Items.LEATHER_CHESTPLATE
            };
            for (Item chest : order) {
                for (int i = 0; i < 9; i++) {
                    ItemStack s = client.player.getInventory().getItem(i);
                    if (!s.isEmpty() && s.getItem() == chest) {
                        return i;
                    }
                }
            }
            return -1;
        }

        private static int findElytraInMainInventory(Minecraft client) {
            if (client.player == null) {
                return -1;
            }
            for (int i = 9; i < 36; i++) {
                ItemStack s = client.player.getInventory().getItem(i);
                if (!s.isEmpty() && s.is(Items.ELYTRA)) {
                    return i;
                }
            }
            return -1;
        }

        private static int findChestplateInMainInventory(Minecraft client) {
            if (client.player == null) {
                return -1;
            }
            Item[] order = new Item[]{
                    Items.NETHERITE_CHESTPLATE,
                    Items.DIAMOND_CHESTPLATE,
                    Items.CHAINMAIL_CHESTPLATE,
                    Items.IRON_CHESTPLATE,
                    Items.GOLDEN_CHESTPLATE,
                    Items.LEATHER_CHESTPLATE
            };
            for (Item chest : order) {
                for (int i = 9; i < 36; i++) {
                    ItemStack s = client.player.getInventory().getItem(i);
                    if (!s.isEmpty() && s.getItem() == chest) {
                        return i;
                    }
                }
            }
            return -1;
        }

        public synchronized boolean submitConsolidatedSwap(
                Minecraft client,
                ActionPriority priority,
                String ownerName,
                int targetSlot,
                int restoreSlot,
                boolean rightClickUseOnEquip,
                boolean clickSim,
                Runnable onSlotReady
        ) {
            if (client == null || client.player == null || targetSlot < 0 || targetSlot > 8) {
                return false;
            }
            ensureTickSynchronized(client);

            if (isBlockedByHigherPriority(priority)) {
                return false;
            }

            if (swapStage != ConsolidatedSwapStage.IDLE && queuedSwapPriority != null) {
                if (!priority.outranks(queuedSwapPriority)) {
                    return false;
                }
                abortActiveConsolidatedSwap(client);
            }

            acquireLock(priority, ownerName);
            this.queuedSwapPriority = priority;
            this.targetHotbarSlot = targetSlot;
            this.restoreHotbarSlot = restoreSlot;
            this.simulateUseClick = clickSim && rightClickUseOnEquip;
            this.onSlotReadyCallback = onSlotReady != null ? onSlotReady : (rightClickUseOnEquip ? () -> {
                if (client.gameMode != null && client.player != null) {
                    if (this.simulateUseClick && client.options != null) {
                        KeyMapping.click(client.options.keyUse.getDefaultKey());
                    }
                    client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                    client.player.swing(InteractionHand.MAIN_HAND);
                }
            } : null);

            if (client.player.getInventory().getSelectedSlot() != targetSlot) {
                client.player.getInventory().setSelectedSlot(targetSlot);
            }
            this.stageStartTick = client.player.tickCount;
            this.stageStartTimeMs = System.currentTimeMillis();
            this.swapStage = ConsolidatedSwapStage.WAIT_TO_INTERACT;
            return true;
        }

        private void advanceConsolidatedSwapMachine(Minecraft client) {
            if (swapStage == ConsolidatedSwapStage.IDLE || client.player == null) {
                return;
            }

            long now = System.currentTimeMillis();
            if (client.player.tickCount < stageStartTick) {
                stageStartTick = client.player.tickCount - 2;
            }
            int ticksElapsed = client.player.tickCount - stageStartTick;
            long msElapsed = now - stageStartTimeMs;

            switch (swapStage) {
                case FAST_TICK_1_EQUIP_AND_HANDOFF -> {
                    // Tick 1 of Optimized 2-Tick Unequip: Wait exactly 1 tick after Tick 0 selection
                    if (ticksElapsed < 1) {
                        return;
                    }
                    if (targetHotbarSlot >= 0 && targetHotbarSlot <= 8
                            && client.player.getInventory().getSelectedSlot() != targetHotbarSlot) {
                        client.player.getInventory().setSelectedSlot(targetHotbarSlot);
                    }

                    if (client.gameMode != null) {
                        if (client.options != null) {
                            KeyMapping.click(client.options.keyUse.getDefaultKey());
                        }
                        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                        client.player.swing(InteractionHand.MAIN_HAND);
                    }

                    // Immediately restore/pre-select weapon slot and hand off control to AutoMace on Tick 1
                    if (restoreHotbarSlot >= 0 && restoreHotbarSlot <= 8
                            && client.player.getInventory().getSelectedSlot() != restoreHotbarSlot) {
                        client.player.getInventory().setSelectedSlot(restoreHotbarSlot);
                    }

                    ActionPriority finishedPriority = queuedSwapPriority;
                    Runnable callback = onSlotReadyCallback;
                    completeConsolidatedSwap();
                    if (finishedPriority == ActionPriority.AUTO_ELYTRA_UNEQUIP) {
                        CombatStateManager.markUnequipCompleted(client.player.tickCount);
                    }
                    if (callback != null) {
                        callback.run();
                    }
                    if (finishedPriority == ActionPriority.AUTO_ELYTRA_UNEQUIP) {
                        // Seamless Same-Tick Handoff: Immediately notify AutoMace on the exact tick RequiresUnequip becomes false
                        MaceTarget activeMace = MaceTarget.getInstance();
                        if (activeMace != null && activeMace.isEnabled()) {
                            activeMace.onElytraUnequipHandoffCompleted(client);
                        }
                    }
                }

                case INV_RELAY_TICK_1_EQUIP -> {
                    if (ticksElapsed < 1) {
                        return;
                    }
                    if (targetHotbarSlot >= 0 && targetHotbarSlot <= 8
                            && client.player.getInventory().getSelectedSlot() != targetHotbarSlot) {
                        client.player.getInventory().setSelectedSlot(targetHotbarSlot);
                    }
                    if (client.gameMode != null) {
                        if (client.options != null) {
                            KeyMapping.click(client.options.keyUse.getDefaultKey());
                        }
                        client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
                        client.player.swing(InteractionHand.MAIN_HAND);
                        recordInteractionDispatched(client);
                    }
                    stageStartTick = client.player.tickCount;
                    stageStartTimeMs = now;
                    swapStage = ConsolidatedSwapStage.INV_RELAY_TICK_2_RESTORE;
                }

                case INV_RELAY_TICK_2_RESTORE -> {
                    if (ticksElapsed < 1) {
                        return;
                    }
                    if (client.gameMode != null
                            && relayInventorySlot >= 9 && relayInventorySlot <= 35
                            && targetHotbarSlot >= 0 && targetHotbarSlot <= 8) {
                        client.gameMode.handleInventoryMouseClick(
                                client.player.containerMenu.containerId,
                                relayInventorySlot,
                                targetHotbarSlot,
                                ClickType.SWAP,
                                client.player
                        );
                    }
                    if (restoreHotbarSlot >= 0 && restoreHotbarSlot <= 8
                            && client.player.getInventory().getSelectedSlot() != restoreHotbarSlot) {
                        client.player.getInventory().setSelectedSlot(restoreHotbarSlot);
                    }
                    completeConsolidatedSwap();
                }

                case WAIT_TO_INTERACT -> {
                    if (ticksElapsed < MIN_TICKS_PER_SWAP_STAGE || msElapsed < MIN_STAGE_MS) {
                        return;
                    }
                    if (targetHotbarSlot >= 0 && targetHotbarSlot <= 8
                            && client.player.getInventory().getSelectedSlot() != targetHotbarSlot) {
                        client.player.getInventory().setSelectedSlot(targetHotbarSlot);
                        stageStartTick = client.player.tickCount;
                        stageStartTimeMs = now;
                        return;
                    }

                    if (onSlotReadyCallback != null) {
                        onSlotReadyCallback.run();
                        recordInteractionDispatched(client);
                    }

                    if (restoreHotbarSlot >= 0 && restoreHotbarSlot <= 8) {
                        stageStartTick = client.player.tickCount;
                        stageStartTimeMs = now;
                        swapStage = ConsolidatedSwapStage.WAIT_TO_RESTORE;
                    } else {
                        completeConsolidatedSwap();
                    }
                }

                case WAIT_TO_RESTORE -> {
                    if (ticksElapsed < MIN_TICKS_PER_SWAP_STAGE || msElapsed < MIN_STAGE_MS) {
                        return;
                    }
                    if (restoreHotbarSlot >= 0 && restoreHotbarSlot <= 8
                            && client.player.getInventory().getSelectedSlot() != restoreHotbarSlot) {
                        client.player.getInventory().setSelectedSlot(restoreHotbarSlot);
                    }
                    completeConsolidatedSwap();
                }

                default -> completeConsolidatedSwap();
            }
        }

        private void abortActiveConsolidatedSwap(Minecraft client) {
            if (client != null && client.player != null && restoreHotbarSlot >= 0 && restoreHotbarSlot <= 8
                    && client.player.getInventory().getSelectedSlot() != restoreHotbarSlot) {
                client.player.getInventory().setSelectedSlot(restoreHotbarSlot);
            }
            completeConsolidatedSwap();
        }

        private void completeConsolidatedSwap() {
            ActionPriority finishedPriority = queuedSwapPriority;
            swapStage = ConsolidatedSwapStage.IDLE;
            queuedSwapPriority = null;
            targetHotbarSlot = -1;
            restoreHotbarSlot = -1;
            relayInventorySlot = -1;
            stageStartTick = -1;
            onSlotReadyCallback = null;
            if (finishedPriority != null && activeLockPriority == finishedPriority) {
                activeLockPriority = null;
                activeLockOwnerName = "";
            }
        }

        private void rebuildValidTargetsCacheOncePerTick(Minecraft client) {
            validTargetsMutable.clear();
            if (client.player == null || client.level == null) {
                validTargetsView = Collections.emptyList();
                return;
            }

            MaceTarget activeMace = MaceTarget.getInstance();
            boolean filterPlayers = activeMace == null || activeMace.isTargetPlayersEnabled();
            boolean filterMobs = activeMace != null && activeMace.isTargetMobsEnabled();
            boolean filterAnimals = activeMace != null && activeMace.isTargetAnimalsEnabled();

            for (Entity entity : client.level.entitiesForRendering()) {
                if (!(entity instanceof LivingEntity living)) {
                    continue;
                }
                if (living == client.player || !living.isAlive() || living.getHealth() <= 0.0F) {
                    continue;
                }
                if (living instanceof ArmorStand) {
                    continue;
                }

                // Early-Exit Type Filtering (CPU Optimization before any distance/bounding-box/rotation math)
                if (living instanceof Player player) {
                    if (!filterPlayers || AntiBot.shouldIgnore(player)) {
                        continue;
                    }
                } else if (living instanceof Animal) {
                    if (!filterAnimals) {
                        continue;
                    }
                } else if (living instanceof Mob) {
                    if (!filterMobs) {
                        continue;
                    }
                } else {
                    continue;
                }

                validTargetsMutable.add(living);
            }

            // Target Prioritization: Players always targeted first (0), then non-players (1), then by distance
            validTargetsMutable.sort(
                    Comparator.comparing((LivingEntity e) -> e instanceof Player ? 0 : 1)
                            .thenComparingDouble(e -> client.player.distanceTo(e))
            );

            validTargetsView = Collections.unmodifiableList(validTargetsMutable);
        }

        public List<LivingEntity> getValidTargets(Minecraft client) {
            ensureTickSynchronized(client);
            return validTargetsView;
        }

        public LivingEntity getNearestVisibleTarget(Minecraft client, double maxRange, boolean ignoreFriends) {
            ensureTickSynchronized(client);
            if (client == null || client.player == null || validTargetsView.isEmpty()) {
                return null;
            }

            double maxRangeSqr = maxRange * maxRange;
            double eyeX = client.player.getX();
            double eyeY = client.player.getEyeY();
            double eyeZ = client.player.getZ();

            List<LivingEntity> inRangeCandidates = new ArrayList<>();

            for (int i = 0, size = validTargetsView.size(); i < size; i++) {
                LivingEntity candidate = validTargetsView.get(i);
                if (!candidate.isAlive()) {
                    continue;
                }
                if (ignoreFriends && isWhitelistedFriend(client, candidate)) {
                    continue;
                }

                AABB box = candidate.getBoundingBox();
                double dx = eyeX - Mth.clamp(eyeX, box.minX, box.maxX);
                double dy = eyeY - Mth.clamp(eyeY, box.minY, box.maxY);
                double dz = eyeZ - Mth.clamp(eyeZ, box.minZ, box.maxZ);
                double distSqr = dx * dx + dy * dy + dz * dz;

                if (distSqr > maxRangeSqr) {
                    continue;
                }
                if (!hasLineOfSight(client, candidate)) {
                    continue;
                }

                inRangeCandidates.add(candidate);
            }

            if (inRangeCandidates.isEmpty()) {
                return null;
            }

            inRangeCandidates.sort(
                    Comparator.comparing((LivingEntity e) -> e instanceof Player ? 0 : 1)
                            .thenComparingDouble(e -> client.player.distanceTo(e))
            );
            return inRangeCandidates.get(0);
        }

        public static boolean isWhitelistedFriend(Minecraft client, Entity entity) {
            if (!(entity instanceof Player player)) {
                return false;
            }
            if (FriendUtils.isFriend(player.getName().getString())) {
                return true;
            }
            return client != null && client.player != null && client.player.isAlliedTo(player);
        }

        public static boolean hasLineOfSight(Minecraft client, LivingEntity target) {
            if (client == null || client.player == null || client.level == null || target == null) {
                return false;
            }
            if (client.player.hasLineOfSight(target)) {
                return true;
            }
            Vec3 eyePos = client.player.getEyePosition(1.0F);
            Vec3 center = target.getBoundingBox().getCenter();
            BlockHitResult hit = client.level.clip(new ClipContext(
                    eyePos,
                    center,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    client.player
            ));
            return hit.getType() == HitResult.Type.MISS;
        }

        public static boolean verifyStrict3mAttackRaycast(Minecraft client, LivingEntity target, double requestedReach, Vec3 lookDirection) {
            if (client == null || client.player == null || client.level == null || target == null || !target.isAlive()) {
                return false;
            }
            double legalReach = Math.min(3.0D, Math.max(1.0D, requestedReach));
            Vec3 eyePos = client.player.getEyePosition(1.0F);
            Vec3 dir = lookDirection != null ? lookDirection : client.player.getViewVector(1.0F);
            Vec3 rayEnd = eyePos.add(dir.scale(legalReach + 0.15D));

            AABB targetBox = target.getBoundingBox().inflate(target.getPickRadius() + 0.05D);
            if (targetBox.contains(eyePos)) {
                return true;
            }

            Optional<Vec3> hitOpt = targetBox.clip(eyePos, rayEnd);
            if (hitOpt.isEmpty()) {
                return false;
            }

            Vec3 hitPoint = hitOpt.get();
            if (eyePos.distanceToSqr(hitPoint) > ((legalReach + 0.15D) * (legalReach + 0.15D))) {
                return false;
            }

            BlockHitResult blockHit = client.level.clip(new ClipContext(
                    eyePos,
                    hitPoint,
                    ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE,
                    client.player
            ));
            return blockHit.getType() == HitResult.Type.MISS;
        }

        public synchronized void forceReleaseAllLocks() {
            activeLockPriority = null;
            activeLockOwnerName = "";
            lockAcquiredAtMs = 0L;
            swapStage = ConsolidatedSwapStage.IDLE;
            queuedSwapPriority = null;
            targetHotbarSlot = -1;
            restoreHotbarSlot = -1;
            relayInventorySlot = -1;
            stageStartTick = -1;
            onSlotReadyCallback = null;
        }

        public synchronized void resetAll() {
            activeLockPriority = null;
            activeLockOwnerName = "";
            lockAcquiredAtMs = 0L;
            swapStage = ConsolidatedSwapStage.IDLE;
            queuedSwapPriority = null;
            targetHotbarSlot = -1;
            restoreHotbarSlot = -1;
            relayInventorySlot = -1;
            stageStartTick = -1;
            onSlotReadyCallback = null;
            cachedTargetsTick = -1;
            validTargetsMutable.clear();
            validTargetsView = Collections.emptyList();
        }
    }

    /**
     * Bridge adapter delegating to the Split-Priority ActionManager.
     */
    public static final class CombatStateManager {
        private static volatile boolean requiresUnequip = false;
        private static volatile int unequipCompletedTick = -100;
        private static volatile int strikeLockAcquiredTick = -100;
        private static volatile boolean attackDispatchedSinceUnequip = false;
        private static volatile long lastMaceStrikeTimeMs = 0L;
        private static volatile long lastUnequipCompletedTimeMs = 0L;
        private static volatile long lastElytraReequipTimeMs = 0L;

        private CombatStateManager() {
        }

        public static boolean isRequiresUnequip() {
            return requiresUnequip;
        }

        public static void setRequiresUnequip(boolean value) {
            requiresUnequip = value;
            if (value) {
                attackDispatchedSinceUnequip = false;
            }
        }

        public static void markUnequipCompleted(int tickCount) {
            requiresUnequip = false;
            unequipCompletedTick = tickCount;
            attackDispatchedSinceUnequip = false;
            lastUnequipCompletedTimeMs = System.currentTimeMillis();
        }

        public static void markAttackDispatched() {
            attackDispatchedSinceUnequip = true;
            requiresUnequip = false;
            unequipCompletedTick = -100;
            strikeLockAcquiredTick = -100;
            lastMaceStrikeTimeMs = System.currentTimeMillis();
            MaceTarget active = MaceTarget.getInstance();
            if (active != null) {
                active.resetAirbornePeakToCurrentPlayerY();
            }
        }

        public static void markElytraReequipped() {
            lastElytraReequipTimeMs = System.currentTimeMillis();
            requiresUnequip = false;
            MaceTarget active = MaceTarget.getInstance();
            if (active != null) {
                active.resetAirbornePeakToCurrentPlayerY();
            }
        }

        public static boolean isInPostReequipGraceWindow() {
            return (System.currentTimeMillis() - lastElytraReequipTimeMs) < 350L;
        }

        public static long getLastUnequipCompletedTimeMs() {
            return lastUnequipCompletedTimeMs;
        }

        public static long getLastMaceStrikeTimeMs() {
            return lastMaceStrikeTimeMs;
        }

        public static int getUnequipCompletedTick() {
            return unequipCompletedTick;
        }

        public static boolean hasAttackDispatchedSinceUnequip() {
            return attackDispatchedSinceUnequip;
        }

        public static void acquireMaceSmashLock() {
            Minecraft mc = Minecraft.getInstance();
            int nowTick = (mc != null && mc.player != null) ? mc.player.tickCount : -100;
            if (ActionManager.getInstance().acquireLock(ActionManager.ActionPriority.AUTO_MACE_STRIKE, "AutoMace_STRIKE")) {
                if (strikeLockAcquiredTick < 0) {
                    strikeLockAcquiredTick = nowTick;
                }
            }
        }

        public static void releaseMaceSmashLock() {
            strikeLockAcquiredTick = -100;
            ActionManager.getInstance().releaseLock(ActionManager.ActionPriority.AUTO_MACE_STRIKE, "AutoMace_STRIKE");
        }

        public static int getStrikeLockAcquiredTick() {
            return strikeLockAcquiredTick;
        }

        public static boolean tryAcquireElytraAscentLock() {
            return ActionManager.getInstance().acquireLock(ActionManager.ActionPriority.AUTO_ELYTRA_EQUIP, "AutoElytra_EQUIP");
        }

        public static void releaseElytraLock() {
            ActionManager.getInstance().releaseLock(ActionManager.ActionPriority.AUTO_ELYTRA_UNEQUIP, "AutoElytra_UNEQUIP");
            ActionManager.getInstance().releaseLock(ActionManager.ActionPriority.AUTO_ELYTRA_EQUIP, "AutoElytra_EQUIP");
        }

        public static boolean isMaceSmashLocked() {
            return ActionManager.getInstance().getActiveLockPriority() == ActionManager.ActionPriority.AUTO_MACE_STRIKE;
        }

        public static boolean isElytraAscentLocked() {
            ActionManager.ActionPriority active = ActionManager.getInstance().getActiveLockPriority();
            return active == ActionManager.ActionPriority.AUTO_ELYTRA_UNEQUIP
                    || active == ActionManager.ActionPriority.AUTO_ELYTRA_EQUIP;
        }

        public static void forceReleaseAllMutexes() {
            requiresUnequip = false;
            unequipCompletedTick = -100;
            strikeLockAcquiredTick = -100;
            attackDispatchedSinceUnequip = false;
            ActionManager.getInstance().forceReleaseAllLocks();
        }

        public static void reset() {
            requiresUnequip = false;
            unequipCompletedTick = -100;
            strikeLockAcquiredTick = -100;
            attackDispatchedSinceUnequip = false;
            ActionManager.getInstance().resetAll();
        }
    }

    // Essential Core PvP Settings Only
    private final NumberSetting swingRange = register(new NumberSetting(
            "Swing Range", "Maximum distance to swing and attack with the mace (capped at 3.0m vanilla reach).", 3.0D, 1.0D, 3.0D, 0.05D));
    private final NumberSetting aimRange = register(new NumberSetting(
            "Aim Range", "Maximum range to acquire and track targets.", 10.0D, 1.0D, 20.0D, 0.1D));
    private final NumberSetting maceFov = register(new NumberSetting(
            "Mace FOV", "Field of view cone in degrees for target acquisition.", 360.0D, 1.0D, 360.0D, 1.0D));

    private final BooleanSetting autoSwitch = register(new BooleanSetting(
            "Auto Switch", "Automatically switch hotbar slot to the dynamically cached mace.", true));
    private final BooleanSetting swapBack = register(new BooleanSetting(
            "Swap Back", "Restore previous weapon slot after attacking.", true));

    private final ModeSetting aimMode = register(new ModeSetting(
            "Aim Mode", "Tracking algorithm: Legit (PD + 20% deadzone), Strict (instant center lock), or Silent.", "Legit", "Legit", "Strict", "Silent"));
    private final NumberSetting aimSpeed = register(new NumberSetting(
            "Aim Speed", "Angular rotation speed during distance-scaled PD deceleration.", 35.0D, 1.0D, 100.0D, 0.5D));
    private final NumberSetting aimSmooth = register(new NumberSetting(
            "Aim Smooth", "Smoothing derivative damping factor applied to rotation interpolation.", 0.00D, 0.00D, 10.00D, 0.01D));

    private final NumberSetting minFallDist = register(new NumberSetting(
            "Min Fall Dist", "Minimum fall distance required to trigger aiming and mace slam.", 1.5D, 0.0D, 20.0D, 0.1D));
    private final NumberSetting cooldownMs = register(new NumberSetting(
            "Cooldown (ms)", "Minimum cooldown in milliseconds between slam sequences.", 125.0D, 0.0D, 1000.0D, 5.0D));
    private final NumberSetting maceSwapDelayMs = register(new NumberSetting(
            "Mace Swap Delay (ms)", "Delay in milliseconds after switching to mace before attacking.", 38.0D, 0.0D, 500.0D, 1.0D));

    private final BooleanSetting stunSlam = register(new BooleanSetting(
            "Stun Slam", "Break target shield with an axe before executing the mace slam.", true));
    private final BooleanSetting ignoreFriends = register(new BooleanSetting(
            "Ignore Friends", "Ignore players registered in the friend list.", true));

    // Target Selection Filter Toggles (Early-Exit CPU Optimization)
    private final BooleanSetting targetPlayers = register(new BooleanSetting(
            "Target Players", "Allow targeting player entities.", true));
    private final BooleanSetting targetMobs = register(new BooleanSetting(
            "Target Mobs", "Allow targeting hostile mob entities.", false));
    private final BooleanSetting targetAnimals = register(new BooleanSetting(
            "Target Animals", "Allow targeting passive animal entities.", false));

    private enum SmashSequenceState {
        IDLE,
        STEP_1_AXE_STUN_ATTACK,
        STEP_2_SELECT_MACE_AFTER_STUN,
        STEP_1_MACE_SMASH_ATTACK,
        STEP_2_SWAPBACK_RESTORE
    }

    private final StopWatch cooldownTimer = new StopWatch();
    private final StopWatch stateTickTimer = new StopWatch();
    private final SilentPdRotateConstructor silentRotateConstructor = new SilentPdRotateConstructor();

    private LivingEntity target;
    private SmashSequenceState smashState = SmashSequenceState.IDLE;
    private int stateCommittedTick = -1;
    private int savedHotbarSlot = -1;
    private int targetMaceSlot = -1;
    private boolean didSwitchHotbarSlot = false;
    private int lastTargetScanTick = -1;
    private double airbornePeakY = Double.NaN;
    private boolean silentRotationActive = false;
    private String lastObservedAimMode = "Legit";
    private boolean immediateHandoffStrikeArmed = false;
    private int lastMacePlayerId = -1;
    private int lastMaceTickCount = -1;

    // 1. Event-Driven / Change-Driven Weapon Slot Cache (Zero 4Hz Timer Loop)
    private int cachedMaceSlot = -1;
    private int cachedAxeSlot = -1;
    private int cachedSwordSlot = -1;
    private int lastHotbarContentsHash = 0;

    // Proportional-Derivative (PD) Distance-Scaled Deceleration State
    private double prevYawError = 0.0D;
    private double prevPitchError = 0.0D;
    private boolean hasPrevError = false;
    private long lastSmoothFrameNs = 0L;

    public MaceTarget() {
        super("AutoMace", "Hotbar-safe PD/Strict/Silent aim assist with zero-delay Elytra handoff.", ModuleCategory.COMBAT);
        instance = this;
    }

    public static MaceTarget getInstance() {
        return instance;
    }

    public LivingEntity getActiveTarget() {
        return target;
    }

    public boolean isTargetPlayersEnabled() {
        return targetPlayers.getValue();
    }

    public boolean isTargetMobsEnabled() {
        return targetMobs.getValue();
    }

    public boolean isTargetAnimalsEnabled() {
        return targetAnimals.getValue();
    }

    public boolean isEntityAllowedByTargetFilters(Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return false;
        }
        if (!targetPlayers.getValue() && living instanceof Player) {
            return false;
        }
        if (!targetAnimals.getValue() && living instanceof Animal) {
            return false;
        }
        if (!targetMobs.getValue() && (living instanceof Mob && !(living instanceof Animal))) {
            return false;
        }
        return (living instanceof Player) || (living instanceof Mob) || (living instanceof Animal);
    }

    public boolean isMidSlamSequence() {
        return smashState != SmashSequenceState.IDLE || CombatStateManager.isMaceSmashLocked();
    }

    public boolean isInStrikeMutexLockout() {
        return smashState != SmashSequenceState.IDLE || CombatStateManager.isMaceSmashLocked();
    }

    public int getPreferredMaceHotbarSlot() {
        return getValidatedCachedMaceSlot(Minecraft.getInstance());
    }

    @Override
    protected void onEnable() {
        resetAllStates();
        refreshSlotCacheOnInventoryChange(Minecraft.getInstance());
    }

    @Override
    protected void onDisable() {
        suspendSilentRotation();
        CombatStateManager.releaseMaceSmashLock();
        resetAllStates();
    }

    @Override
    public void onTick(Minecraft client) {
        if (!isEnabled()) {
            return;
        }
        if (client.player == null || client.level == null) {
            suspendSilentRotation();
            CombatStateManager.releaseMaceSmashLock();
            resetAllStates();
            return;
        }

        ActionManager.getInstance().ensureTickSynchronized(client);
        syncAimModeTransitionWithoutClearingState(aimMode.getValue());
        enforceTwoTickFailsafeMutexRelease(client);
        // Only updates cache if hotbar item contents changed AND player is not using an item or locked
        refreshSlotCacheOnInventoryChange(client);

        if (isGroundedOrAscendingLockout(client)) {
            CombatStateManager.setRequiresUnequip(false);
            immediateHandoffStrikeArmed = false;
            releaseCameraAndClearTargetLock();
        }
    }

    @SubscribeEvent
    public void onPlayerTravel(PlayerTravelEvent event) {
        if (!isEnabled()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && isGroundedOrAscendingLockout(client)) {
            releaseCameraAndClearTargetLock();
        }
    }

    @SubscribeEvent
    public void onDraw(DrawEvent event) {
        if (!isEnabled() || event.getLayer() != DrawEvent.Layer.GAME) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || client.screen != null) {
            lastSmoothFrameNs = 0L;
            return;
        }

        syncAimModeTransitionWithoutClearingState(aimMode.getValue());

        if ("Silent".equalsIgnoreCase(aimMode.getValue())) {
            lastSmoothFrameNs = 0L;
            return;
        }

        if (isGroundedOrAscendingLockout(client)) {
            releaseCameraAndClearTargetLock();
            return;
        }

        if (target == null || !target.isAlive() || isWhitelistedFriend(client, target)
                || !canEngageModule(client) || !shouldAimAtTarget(client)) {
            resetKinematicAimState();
            return;
        }

        long nowNs = System.nanoTime();
        double frameScale = lastSmoothFrameNs > 0L ? (nowNs - lastSmoothFrameNs) / 50_000_000.0D : (1.0D / 3.0D);
        frameScale = Mth.clamp(frameScale, 0.05D, 1.0D);
        lastSmoothFrameNs = nowNs;

        if ("Strict".equalsIgnoreCase(aimMode.getValue())) {
            applyStrictCenterLockAim(client, target);
        } else {
            applyDistanceScaledPdAim(client, target, frameScale);
        }
    }

    @SubscribeEvent
    public void onRotationUpdate(RotationUpdateEvent event) {
        if (!isEnabled()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }

        ActionManager.getInstance().ensureTickSynchronized(client);

        if (event.getPhase() == EventPhase.PRE) {
            updateAirbornePeak(client);
            runPreRotationPhase(client);
            return;
        }

        if (event.getPhase() == EventPhase.POST) {
            runPostRotationPhase(client);
        }
    }

    private void runPreRotationPhase(Minecraft client) {
        if (isGroundedOrAscendingLockout(client)) {
            releaseCameraAndClearTargetLock();
            return;
        }

        if (smashState == SmashSequenceState.STEP_2_SWAPBACK_RESTORE) {
            return;
        }

        if (!canEngageModule(client)) {
            if (smashState != SmashSequenceState.IDLE) {
                transitionToSwapBackRestore(client);
            } else {
                CombatStateManager.releaseMaceSmashLock();
            }
            releaseCameraAndClearTargetLock();
            return;
        }

        if (lastTargetScanTick != client.player.tickCount) {
            lastTargetScanTick = client.player.tickCount;
            findTargetFromPreFilteredCache(client);
        }

        if (target == null || !target.isAlive() || isWhitelistedFriend(client, target) || !ActionManager.hasLineOfSight(client, target)) {
            if (smashState != SmashSequenceState.IDLE) {
                transitionToSwapBackRestore(client);
            } else {
                CombatStateManager.releaseMaceSmashLock();
            }
            releaseCameraAndClearTargetLock();
            return;
        }

        syncAimModeTransitionWithoutClearingState(aimMode.getValue());
        enforceTwoTickFailsafeMutexRelease(client);

        // Pre-emptive Tick 0 Elytra unequip trigger & Split-Priority lock management
        updateSplitPriorityCombatLock(client, target);

        if (!shouldAimAtTarget(client)) {
            suspendSilentRotation();
            resetKinematicAimState();
            return;
        }

        String mode = aimMode.getValue();
        if ("Silent".equalsIgnoreCase(mode)) {
            Angle currentRef = AngleConnection.INSTANCE.getRotation() != null
                    ? AngleConnection.INSTANCE.getRotation()
                    : MathAngle.cameraAngle();
            if (isCrosshairInsideInner20PercentDeadzone(client, target, currentRef)) {
                // Keep silent server rotation locked inside the 20% deadzone without resetting back to camera!
                queueSilentServerRotation(currentRef);
                return;
            }
            Angle centerAngle = computeExactCenterAimAngle(client, target);
            if (centerAngle != null) {
                queueSilentServerRotation(centerAngle);
            }
        } else if ("Strict".equalsIgnoreCase(mode)) {
            suspendSilentRotation();
            applyStrictCenterLockAim(client, target);
        } else {
            suspendSilentRotation();
            long nowNs = System.nanoTime();
            if (lastSmoothFrameNs == 0L || nowNs - lastSmoothFrameNs > 22_000_000L) {
                applyDistanceScaledPdAim(client, target, 1.0D);
                lastSmoothFrameNs = nowNs;
            }
        }
    }

    /**
     * Decouples UI Aim Mode dropdown changes from CombatState and ActionManager:
     * Changing Aim Mode in the GUI never resets target locks, RequiresUnequip, smashState, or cooldowns.
     */
    private void syncAimModeTransitionWithoutClearingState(String currentMode) {
        if (currentMode == null) {
            return;
        }
        if (!currentMode.equalsIgnoreCase(lastObservedAimMode)) {
            if ("Silent".equalsIgnoreCase(lastObservedAimMode) && !"Silent".equalsIgnoreCase(currentMode)) {
                suspendSilentRotation();
            }
            resetKinematicAimState();
            lastObservedAimMode = currentMode;
        }
    }

    /**
     * 2-Tick Failsafe Mutex Watchdog:
     * If the Elytra is unequipped (or a strike lock is acquired) and the attack packet is not sent
     * within 2 ticks, force-release all mutex locks so the player can manually swing or recover.
     */
    private void enforceTwoTickFailsafeMutexRelease(Minecraft client) {
        if (client == null || client.player == null) {
            return;
        }
        int nowTick = client.player.tickCount;
        int playerId = client.player.getId();

        // Detect new match / respawn / tickCount reset and clear all stale combat locks & caches
        if (playerId != lastMacePlayerId || nowTick < lastMaceTickCount) {
            lastMacePlayerId = playerId;
            lastMaceTickCount = nowTick;
            smashState = SmashSequenceState.IDLE;
            stateCommittedTick = -100;
            lastTargetScanTick = -100;
            didSwitchHotbarSlot = false;
            savedHotbarSlot = -1;
            targetMaceSlot = -1;
            immediateHandoffStrikeArmed = false;
            invalidateCache();
            CombatStateManager.forceReleaseAllMutexes();
            return;
        }
        lastMaceTickCount = nowTick;

        int unequipTick = CombatStateManager.getUnequipCompletedTick();
        if (unequipTick > 0
                && !CombatStateManager.hasAttackDispatchedSinceUnequip()
                && (nowTick < unequipTick || (nowTick - unequipTick) > 2)) {
            immediateHandoffStrikeArmed = false;
            if (smashState != SmashSequenceState.IDLE) {
                executeSafeLegitSwapBack(client);
                smashState = SmashSequenceState.IDLE;
            }
            CombatStateManager.forceReleaseAllMutexes();
            return;
        }

        int strikeLockTick = CombatStateManager.getStrikeLockAcquiredTick();
        if (strikeLockTick > 0
                && CombatStateManager.isMaceSmashLocked()
                && (nowTick < strikeLockTick || (nowTick - strikeLockTick) > 2)) {
            immediateHandoffStrikeArmed = false;
            if (smashState != SmashSequenceState.IDLE) {
                executeSafeLegitSwapBack(client);
                smashState = SmashSequenceState.IDLE;
            }
            CombatStateManager.forceReleaseAllMutexes();
        }
    }

    /**
     * Seamless Handoff Callback (Invoked on the EXACT tick RequiresUnequip becomes false):
     * Instantly dispatches the InteractEntity (Attack) packet without waiting an extra validation tick.
     */
    public void onElytraUnequipHandoffCompleted(Minecraft client) {
        if (!isEnabled() || client == null || client.player == null || client.level == null) {
            return;
        }

        if (target == null || !target.isAlive() || isWhitelistedFriend(client, target)) {
            findTargetFromPreFilteredCache(client);
        }
        if (target == null || !target.isAlive() || isWhitelistedFriend(client, target)) {
            return;
        }

        // Strict Elytra Unequip Assertion: Never initiate weapon swap or strike if Elytra is still equipped
        if (client.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            requireElytraUnequip(client, target);
            return;
        }

        CombatStateManager.setRequiresUnequip(false);
        immediateHandoffStrikeArmed = true;

        // JIT Cache Verification before swapping to cachedMaceSlot
        if (cachedMaceSlot < 0 || cachedMaceSlot > 8
                || !client.player.getInventory().getItem(cachedMaceSlot).is(Items.MACE)) {
            invalidateCache();
        }
        int maceSlot = getValidatedCachedMaceSlot(client);
        int currentSlot = client.player.getInventory().getSelectedSlot();
        boolean holdingMace = isMaceStack(client.player.getMainHandItem());

        if (!holdingMace && (!autoSwitch.getValue() || maceSlot == -1)) {
            CombatStateManager.releaseMaceSmashLock();
            return;
        }

        if (!holdingMace && autoSwitch.getValue() && maceSlot >= 0 && maceSlot <= 8) {
            if (!didSwitchHotbarSlot) {
                savedHotbarSlot = currentSlot;
            }
            selectHotbarSlotLegit(client, maceSlot);
            didSwitchHotbarSlot = true;
            targetMaceSlot = maceSlot;
            holdingMace = isMaceStack(client.player.getMainHandItem());
        }

        double currentDist = getPrecisionDistanceToTarget(client, target);
        if (holdingMace && currentDist <= swingRange.getValue() + 0.25D) {
            CombatStateManager.acquireMaceSmashLock();
            if (performThrottledAttack(client, target, true)) {
                CombatStateManager.markAttackDispatched();
                immediateHandoffStrikeArmed = false;
                cooldownTimer.reset();
                transitionToSwapBackRestore(client);
            }
        }
    }

    private void invalidateCache() {
        cachedMaceSlot = -1;
        cachedAxeSlot = -1;
        cachedSwordSlot = -1;
        lastHotbarContentsHash = 0;
    }

    private void requireElytraUnequip(Minecraft client, LivingEntity targetEntity) {
        CombatStateManager.releaseMaceSmashLock();
        CombatStateManager.setRequiresUnequip(true);
        immediateHandoffStrikeArmed = true;
        if (smashState != SmashSequenceState.IDLE) {
            smashState = SmashSequenceState.IDLE;
        }
        ElytraTarget autoElytra = ElytraTarget.getInstance();
        if (autoElytra != null && autoElytra.isEnabled()) {
            autoElytra.ensureUnequippedBeforeMaceAttack(targetEntity);
        }
        // Fallback: if AutoElytra did not start a swap (e.g. disabled or cooldown), directly submit the 2-tick Chestplate swap
        if (client != null && client.player != null && !ActionManager.getInstance().isSwapInProgress()) {
            int chestSlot = findHotbarChestplateSlot(client);
            if (chestSlot != -1) {
                int maceSlot = getValidatedCachedMaceSlot(client);
                int handoffSlot = (maceSlot >= 0 && maceSlot <= 8) ? maceSlot : client.player.getInventory().getSelectedSlot();
                ActionManager.getInstance().submitFastTwoTickUnequip(
                        client,
                        chestSlot,
                        handoffSlot,
                        () -> {
                            if (client.player != null) {
                                CombatStateManager.markUnequipCompleted(client.player.tickCount);
                            }
                            ElytraTarget ae = ElytraTarget.getInstance();
                            if (ae != null && ae.isEnabled()) {
                                ae.enterState3RecoveryLock();
                            }
                        }
                );
            }
        }
    }

    private int findHotbarChestplateSlot(Minecraft client) {
        if (client == null || client.player == null) {
            return -1;
        }
        Item[] chestplates = {
                Items.NETHERITE_CHESTPLATE,
                Items.DIAMOND_CHESTPLATE,
                Items.CHAINMAIL_CHESTPLATE,
                Items.IRON_CHESTPLATE,
                Items.GOLDEN_CHESTPLATE,
                Items.LEATHER_CHESTPLATE
        };
        for (Item chest : chestplates) {
            for (int i = 0; i < 9; i++) {
                ItemStack s = client.player.getInventory().getItem(i);
                if (!s.isEmpty() && s.is(chest)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void runPostRotationPhase(Minecraft client) {
        if (ActionManager.getInstance().isBlockedByHigherPriority(ActionManager.ActionPriority.AUTO_MACE_STRIKE)) {
            return;
        }

        if (smashState != SmashSequenceState.IDLE) {
            processSmashSequenceStateMachine(client);
            return;
        }

        if (isGroundedOrAscendingLockout(client)) {
            CombatStateManager.releaseMaceSmashLock();
            return;
        }

        if (target == null || !target.isAlive() || isWhitelistedFriend(client, target) || !ActionManager.hasLineOfSight(client, target)) {
            CombatStateManager.releaseMaceSmashLock();
            return;
        }

        if (isManualInputBlockingSwap(client)) {
            return;
        }

        if (!cooldownTimer.finished(cooldownMs.getValue())) {
            return;
        }

        tryInitiateSplitPriorityMaceSmash(client, target);
    }

    /**
     * 1. Hotbar Item-Holding Fix (Event-Driven / Change-Driven Slot Caching):
     * - No continuous 4Hz timer loop.
     * - Active Hand Protection: Immediately returns if (player.isUsingItem() || actionManager.isLocked() || player.swinging).
     * - Only re-evaluates DataComponents.ENCHANTMENTS when the hotbar's item contents hash actually changes!
     */
    public void refreshSlotCacheOnInventoryChange(Minecraft client) {
        if (client == null || client.player == null) {
            return;
        }
        // Active Hand Protection: Never scan or touch cached indices while using items, swinging, or locked
        if (client.player.isUsingItem()
                || client.player.swinging
                || client.player.attackAnim > 0.0F
                || ActionManager.getInstance().isLocked()) {
            return;
        }

        int currentHash = 1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                currentHash = 31 * currentHash;
            } else {
                currentHash = 31 * currentHash + System.identityHashCode(stack.getItem()) + stack.getCount();
            }
        }

        if (currentHash == lastHotbarContentsHash) {
            return;
        }
        lastHotbarContentsHash = currentHash;

        int bestMaceSlot = -1;
        int bestMaceScore = Integer.MIN_VALUE;
        int foundAxeSlot = -1;
        int foundSwordSlot = -1;

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }

            if (stack.getItem() == Items.MACE) {
                int score = scoreMaceEnchantments(stack);
                if (score > bestMaceScore) {
                    bestMaceScore = score;
                    bestMaceSlot = slot;
                }
            } else if (foundAxeSlot == -1 && (stack.getItem() instanceof AxeItem || stack.is(ItemTags.AXES))) {
                foundAxeSlot = slot;
            } else if (foundSwordSlot == -1 && stack.is(ItemTags.SWORDS)) {
                foundSwordSlot = slot;
            }
        }

        cachedMaceSlot = bestMaceSlot;
        cachedAxeSlot = foundAxeSlot;
        cachedSwordSlot = foundSwordSlot;
    }

    private int scoreMaceEnchantments(ItemStack stack) {
        ItemEnchantments enchantments = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        int densityLevel = 0;
        int breachLevel = 0;
        int windBurstLevel = 0;

        for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments.entrySet()) {
            String registeredName = entry.getKey().getRegisteredName().toLowerCase();
            int level = entry.getIntValue();
            if (registeredName.contains("density")) {
                densityLevel = Math.max(densityLevel, level);
            } else if (registeredName.contains("breach")) {
                breachLevel = Math.max(breachLevel, level);
            } else if (registeredName.contains("wind_burst")) {
                windBurstLevel = Math.max(windBurstLevel, level);
            }
        }

        int highestPrimary = Math.max(densityLevel, breachLevel);
        return (highestPrimary * 100) + (densityLevel * 25) + (breachLevel * 20) + (windBurstLevel * 10) + 1;
    }

    /**
     * 2. Pre-emptive Slot Pre-selection (< 4.5m & v_y < -0.1) & Zero-Delay Handoff:
     * - The moment target distance < 4.5m and v_y < -0.1 while wearing an Elytra, immediately triggers
     *   AutoElytra's Tick 0 Chestplate selection.
     * - The millisecond the 2-tick unequip completes on Tick 1 (isReadyForZeroDelayMaceHandoff()),
     *   AutoMace claims AUTO_MACE_STRIKE with zero dead frames.
     */
    private void updateSplitPriorityCombatLock(Minecraft client, LivingEntity targetEntity) {
        if (client.player == null || targetEntity == null || isGroundedOrAscendingLockout(client)) {
            CombatStateManager.setRequiresUnequip(false);
            if (smashState == SmashSequenceState.IDLE) {
                CombatStateManager.releaseMaceSmashLock();
            }
            return;
        }

        double currentDist = getPrecisionDistanceToTarget(client, targetEntity);
        double velY = client.player.getDeltaMovement().y;

        // Strict Elytra Unequip Assertion: If Chest slot holds Items.ELYTRA, route priority to AutoElytra_UNEQUIP
        // (unless the Elytra was just re-equipped within the 350ms post-strike grace window and has not gained new smash height)
        if (client.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            if (!CombatStateManager.isInPostReequipGraceWindow()
                    && isFallingForSmash(client)
                    && currentDist < PRIORITY_1_ELYTRA_UNEQUIP_DIST
                    && velY < -0.05D) {
                requireElytraUnequip(client, targetEntity);
            } else {
                CombatStateManager.releaseMaceSmashLock();
            }
            return;
        }

        CombatStateManager.setRequiresUnequip(false);
        if (isFallingForSmash(client) && currentDist <= swingRange.getValue() + 0.35D) {
            CombatStateManager.acquireMaceSmashLock();
        } else if (smashState == SmashSequenceState.IDLE) {
            CombatStateManager.releaseMaceSmashLock();
        }
    }

    private void tryInitiateSplitPriorityMaceSmash(Minecraft client, LivingEntity targetEntity) {
        if (targetEntity == null || isWhitelistedFriend(client, targetEntity)) {
            return;
        }
        if (isGroundedOrAscendingLockout(client) || !isFallingForSmash(client)) {
            return;
        }

        double currentDist = getPrecisionDistanceToTarget(client, targetEntity);
        double velY = client.player.getDeltaMovement().y;

        // Strict Elytra Unequip Assertion right before initiating weapon swap to strike:
        // Forces ActionManager to halt Mace sequence and route priority back to AutoElytra_UNEQUIP
        if (client.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            if (!CombatStateManager.isInPostReequipGraceWindow()
                    && currentDist < PRIORITY_1_ELYTRA_UNEQUIP_DIST
                    && velY < -0.05D) {
                requireElytraUnequip(client, targetEntity);
            }
            return;
        }

        CombatStateManager.setRequiresUnequip(false);

        if (currentDist > swingRange.getValue() + 0.35D) {
            return;
        }

        // JIT (Just-In-Time) Cache Verification before swapping to cachedMaceSlot
        if (cachedMaceSlot < 0 || cachedMaceSlot > 8
                || !client.player.getInventory().getItem(cachedMaceSlot).is(Items.MACE)) {
            invalidateCache();
        }
        int maceSlot = getValidatedCachedMaceSlot(client);
        boolean holdingMace = isMaceStack(client.player.getMainHandItem());
        if (!holdingMace && (!autoSwitch.getValue() || maceSlot == -1)) {
            CombatStateManager.releaseMaceSmashLock();
            return;
        }

        CombatStateManager.acquireMaceSmashLock();

        int currentSlot = client.player.getInventory().getSelectedSlot();
        if (!didSwitchHotbarSlot) {
            savedHotbarSlot = currentSlot;
        }
        targetMaceSlot = holdingMace ? currentSlot : maceSlot;

        boolean fastPostUnequipStrike = immediateHandoffStrikeArmed
                || (client.player.tickCount >= CombatStateManager.getUnequipCompletedTick() && (client.player.tickCount - CombatStateManager.getUnequipCompletedTick()) <= 2);

        int axeSlot = getValidatedCachedAxeSlot(client);
        if (!fastPostUnequipStrike && shouldTriggerStunSlam(targetEntity) && axeSlot != -1) {
            if (currentSlot != axeSlot) {
                selectHotbarSlotLegit(client, axeSlot);
                didSwitchHotbarSlot = true;
                stateCommittedTick = client.player.tickCount;
                stateTickTimer.reset();
                smashState = SmashSequenceState.STEP_1_AXE_STUN_ATTACK;
            } else if (currentDist <= swingRange.getValue() && performThrottledAttack(client, targetEntity, false)) {
                CombatStateManager.markAttackDispatched();
                stateCommittedTick = client.player.tickCount;
                stateTickTimer.reset();
                smashState = SmashSequenceState.STEP_2_SELECT_MACE_AFTER_STUN;
            }
            return;
        }

        if (currentSlot != targetMaceSlot) {
            selectHotbarSlotLegit(client, targetMaceSlot);
            didSwitchHotbarSlot = true;
            // Step 3: IfRequiresUnequip just became false, do NOT wait an extra tick—attack immediately on this tick!
            if (fastPostUnequipStrike && currentDist <= swingRange.getValue() + 0.25D
                    && performThrottledAttack(client, targetEntity, true)) {
                CombatStateManager.markAttackDispatched();
                immediateHandoffStrikeArmed = false;
                cooldownTimer.reset();
                transitionToSwapBackRestore(client);
                return;
            }
            stateCommittedTick = client.player.tickCount;
            stateTickTimer.reset();
            smashState = SmashSequenceState.STEP_1_MACE_SMASH_ATTACK;
            return;
        }

        // Step 3: Zero-Delay Strike on the exact tick RequiresUnequip is false (or already holding Mace)
        if (currentDist <= (fastPostUnequipStrike ? swingRange.getValue() + 0.25D : swingRange.getValue())
                && performThrottledAttack(client, targetEntity, fastPostUnequipStrike)) {
            CombatStateManager.markAttackDispatched();
            immediateHandoffStrikeArmed = false;
            cooldownTimer.reset();
            transitionToSwapBackRestore(client);
        }
    }

    private int getValidatedCachedMaceSlot(Minecraft client) {
        if (client == null || client.player == null) {
            invalidateCache();
            return -1;
        }
        if (cachedMaceSlot >= 0 && cachedMaceSlot <= 8
                && client.player.getInventory().getItem(cachedMaceSlot).is(Items.MACE)) {
            return cachedMaceSlot;
        }
        invalidateCache();
        int bestMaceSlot = -1;
        int bestMaceScore = Integer.MIN_VALUE;
        int foundAxeSlot = -1;
        int foundSwordSlot = -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.is(Items.MACE)) {
                int score = scoreMaceEnchantments(stack);
                if (score > bestMaceScore) {
                    bestMaceScore = score;
                    bestMaceSlot = slot;
                }
            } else if (foundAxeSlot == -1 && (stack.getItem() instanceof AxeItem || stack.is(ItemTags.AXES))) {
                foundAxeSlot = slot;
            } else if (foundSwordSlot == -1 && stack.is(ItemTags.SWORDS)) {
                foundSwordSlot = slot;
            }
        }
        cachedMaceSlot = bestMaceSlot;
        cachedAxeSlot = foundAxeSlot;
        cachedSwordSlot = foundSwordSlot;
        return cachedMaceSlot;
    }

    private int getValidatedCachedAxeSlot(Minecraft client) {
        if (client.player == null) {
            return -1;
        }
        if (cachedAxeSlot >= 0 && cachedAxeSlot <= 8) {
            ItemStack s = client.player.getInventory().getItem(cachedAxeSlot);
            if (!s.isEmpty() && (s.getItem() instanceof AxeItem || s.is(ItemTags.AXES))) {
                return cachedAxeSlot;
            }
        }
        return -1;
    }

    private void applyStrictCenterLockAim(Minecraft client, LivingEntity targetEntity) {
        Angle centerAngle = computeExactCenterAimAngle(client, targetEntity);
        if (centerAngle == null) {
            return;
        }
        client.player.setYRot(centerAngle.getYaw());
        client.player.setXRot(Mth.clamp(centerAngle.getPitch(), -89.5F, 89.5F));
    }

    private void applyDistanceScaledPdAim(Minecraft client, LivingEntity targetEntity, double tickFraction) {
        float currentYaw = client.player.getYRot();
        float currentPitch = client.player.getXRot();
        Angle currentCamera = new Angle(currentYaw, currentPitch);

        if (isCrosshairInsideInner20PercentDeadzone(client, targetEntity, currentCamera)) {
            hasPrevError = false;
            prevYawError = 0.0D;
            prevPitchError = 0.0D;
            return;
        }

        Angle centerAngle = computeExactCenterAimAngle(client, targetEntity);
        if (centerAngle == null) {
            return;
        }

        double yawError = Mth.wrapDegrees(centerAngle.getYaw() - currentYaw);
        double pitchError = centerAngle.getPitch() - currentPitch;
        double angularDistance = Math.hypot(yawError, pitchError);

        if (angularDistance < 0.04D) {
            return;
        }

        boolean insideOuterHitbox = doesRayIntersectBox(
                client.player.getEyePosition(1.0F),
                currentCamera,
                targetEntity.getBoundingBox(),
                aimRange.getValue() + 1.5D
        );

        double userSpeedRatio = Mth.clamp(aimSpeed.getValue() / 50.0D, 0.35D, 1.50D);
        double maxSpeedPerTick = Mth.lerp(
                Mth.clamp(angularDistance / 45.0D, 0.0D, 1.0D),
                MIN_FAR_ROTATION_DEG_PER_TICK,
                MAX_FAR_ROTATION_DEG_PER_TICK
        ) * userSpeedRatio;

        double distNorm = Mth.clamp(angularDistance / PD_DECELERATION_CONE_DEGREES, 0.08D, 1.0D);
        double smoothDecelCurve = distNorm * (2.0D - distNorm);
        double hitboxProximityScale = insideOuterHitbox ? 0.38D : 1.0D;

        double smoothFactor = Mth.clamp(aimSmooth.getValue() / 10.0D, 0.0D, 0.85D);
        double kp = Mth.clamp((0.68D * smoothDecelCurve * hitboxProximityScale) * (1.0D - smoothFactor * 0.45D), 0.14D, 0.82D);
        double kd = 0.18D + smoothFactor * 0.15D;

        double dYaw = hasPrevError ? (yawError - prevYawError) : 0.0D;
        double dPitch = hasPrevError ? (pitchError - prevPitchError) : 0.0D;
        prevYawError = yawError;
        prevPitchError = pitchError;
        hasPrevError = true;

        double rawYawStep = (kp * yawError + kd * dYaw) * (tickFraction * 2.2D);
        double rawPitchStep = (kp * pitchError + kd * dPitch) * (tickFraction * 2.2D);

        double maxStepThisFrame = maxSpeedPerTick * smoothDecelCurve * hitboxProximityScale * tickFraction;
        double yawWeight = Math.abs(yawError) / angularDistance;
        double pitchWeight = Math.abs(pitchError) / angularDistance;
        double maxYawStep = Math.max(0.25D, maxStepThisFrame * yawWeight);
        double maxPitchStep = Math.max(0.20D, maxStepThisFrame * pitchWeight);

        double clampedYawStep = Mth.clamp(rawYawStep, -maxYawStep, maxYawStep);
        double clampedPitchStep = Mth.clamp(rawPitchStep, -maxPitchStep, maxPitchStep);

        if (Math.abs(clampedYawStep) > Math.abs(yawError)) {
            clampedYawStep = yawError;
        }
        if (Math.abs(clampedPitchStep) > Math.abs(pitchError)) {
            clampedPitchStep = pitchError;
        }

        float nextYaw = currentYaw + (float) clampedYawStep;
        float nextPitch = Mth.clamp(currentPitch + (float) clampedPitchStep, -89.5F, 89.5F);

        double gcd = MathUtils.computeGcd();
        if (gcd > 0.0D) {
            nextYaw = currentYaw + Math.round((nextYaw - currentYaw) / (float) gcd) * (float) gcd;
            nextPitch = Mth.clamp(currentPitch + Math.round((nextPitch - currentPitch) / (float) gcd) * (float) gcd, -89.5F, 89.5F);
        }

        client.player.setYRot(nextYaw);
        client.player.setXRot(nextPitch);
    }

    private void queueSilentServerRotation(Angle targetAngle) {
        silentRotationActive = true;
        AngleConnection.INSTANCE.rotateTo(
                new Angle.VecRotation(targetAngle, targetAngle.toVector()),
                target,
                2,
                new AngleConfig(silentRotateConstructor, true, true),
                TaskPriority.HIGH_IMPORTANCE_1,
                this
        );
    }

    private void suspendSilentRotation() {
        if (silentRotationActive) {
            AngleConnection.INSTANCE.startReturning();
            silentRotationActive = false;
        }
    }

    private boolean isCrosshairInsideInner20PercentDeadzone(Minecraft client, LivingEntity entity, Angle currentAngle) {
        Vec3 eyePos = client.player.getEyePosition(1.0F);
        AABB fullBox = entity.getBoundingBox();
        Vec3 exactCenter = getExactBoundingBoxCenter(fullBox);

        double innerHalfX = fullBox.getXsize() * INNER_20_PERCENT_HALF_RATIO;
        double innerHalfY = fullBox.getYsize() * INNER_20_PERCENT_HALF_RATIO;
        double innerHalfZ = fullBox.getZsize() * INNER_20_PERCENT_HALF_RATIO;

        AABB inner20PercentBox = new AABB(
                exactCenter.x - innerHalfX, exactCenter.y - innerHalfY, exactCenter.z - innerHalfZ,
                exactCenter.x + innerHalfX, exactCenter.y + innerHalfY, exactCenter.z + innerHalfZ
        );

        return doesRayIntersectBox(eyePos, currentAngle, inner20PercentBox, aimRange.getValue() + 2.0D);
    }

    private Vec3 getExactBoundingBoxCenter(AABB box) {
        return new Vec3(
                (box.minX + box.maxX) * 0.5D,
                box.minY + (box.getYsize() * 0.5D),
                (box.minZ + box.maxZ) * 0.5D
        );
    }

    private Angle computeExactCenterAimAngle(Minecraft client, LivingEntity entity) {
        Vec3 actualEye = client.player.getEyePosition(1.0F);
        Vec3 exactCenter = getExactBoundingBoxCenter(entity.getBoundingBox());
        return MathAngle.fromTo(actualEye, exactCenter).adjustSensitivity();
    }

    private void processSmashSequenceStateMachine(Minecraft client) {
        if (client.player == null) {
            CombatStateManager.releaseMaceSmashLock();
            smashState = SmashSequenceState.IDLE;
            return;
        }

        switch (smashState) {
            case STEP_1_AXE_STUN_ATTACK -> {
                if (target == null || !target.isAlive() || isWhitelistedFriend(client, target)) {
                    transitionToSwapBackRestore(client);
                    return;
                }
                if (client.player.tickCount >= stateCommittedTick && (client.player.tickCount - stateCommittedTick) < MIN_TICKS_BETWEEN_MACE_STEPS) {
                    return;
                }
                double dist = getPrecisionDistanceToTarget(client, target);
                if (dist <= swingRange.getValue() + 0.25D && performThrottledAttack(client, target, false)) {
                    CombatStateManager.markAttackDispatched();
                    stateCommittedTick = client.player.tickCount;
                    stateTickTimer.reset();
                    smashState = SmashSequenceState.STEP_2_SELECT_MACE_AFTER_STUN;
                } else if (client.player.onGround() || (client.player.tickCount < stateCommittedTick || (client.player.tickCount - stateCommittedTick) > 2)) {
                    transitionToSwapBackRestore(client);
                }
            }

            case STEP_2_SELECT_MACE_AFTER_STUN -> {
                if (target == null || !target.isAlive() || isWhitelistedFriend(client, target)) {
                    transitionToSwapBackRestore(client);
                    return;
                }
                if (client.player.tickCount >= stateCommittedTick && (client.player.tickCount - stateCommittedTick) < MIN_TICKS_BETWEEN_MACE_STEPS) {
                    return;
                }
                int maceSlot = targetMaceSlot != -1 ? targetMaceSlot : getValidatedCachedMaceSlot(client);
                if (maceSlot != -1) {
                    selectHotbarSlotLegit(client, maceSlot);
                    didSwitchHotbarSlot = true;
                }
                stateCommittedTick = client.player.tickCount;
                stateTickTimer.reset();
                smashState = SmashSequenceState.STEP_1_MACE_SMASH_ATTACK;
            }

            case STEP_1_MACE_SMASH_ATTACK -> {
                if (target == null || !target.isAlive() || isWhitelistedFriend(client, target)) {
                    transitionToSwapBackRestore(client);
                    return;
                }
                double dist = getPrecisionDistanceToTarget(client, target);
                if (dist <= swingRange.getValue() + 0.25D && performThrottledAttack(client, target, true)) {
                    CombatStateManager.markAttackDispatched();
                    immediateHandoffStrikeArmed = false;
                    cooldownTimer.reset();
                    transitionToSwapBackRestore(client);
                } else if (client.player.onGround() || (client.player.tickCount < stateCommittedTick || (client.player.tickCount - stateCommittedTick) > 2)) {
                    // 2-Tick Failsafe Release: never hold lock for more than 2 ticks if attack did not fire
                    transitionToSwapBackRestore(client);
                }
            }

            case STEP_2_SWAPBACK_RESTORE -> {
                if (client.player.tickCount >= stateCommittedTick && (client.player.tickCount - stateCommittedTick) < MIN_TICKS_BETWEEN_MACE_STEPS) {
                    return;
                }
                executeSafeLegitSwapBack(client);
                smashState = SmashSequenceState.IDLE;
                CombatStateManager.releaseMaceSmashLock();
            }

            default -> {
                smashState = SmashSequenceState.IDLE;
                CombatStateManager.releaseMaceSmashLock();
            }
        }
    }

    private void selectHotbarSlotLegit(Minecraft client, int slot) {
        if (client.player == null || slot < 0 || slot > 8) {
            return;
        }
        if (client.player.getInventory().getSelectedSlot() != slot) {
            client.player.getInventory().setSelectedSlot(slot);
        }
    }

    private void transitionToSwapBackRestore(Minecraft client) {
        CombatStateManager.markAttackDispatched();
        ElytraTarget autoElytra = ElytraTarget.getInstance();
        if (autoElytra != null && autoElytra.isEnabled()) {
            autoElytra.enterState3RecoveryLock();
        }
        if (!swapBack.getValue() || !didSwitchHotbarSlot) {
            savedHotbarSlot = -1;
            targetMaceSlot = -1;
            didSwitchHotbarSlot = false;
            smashState = SmashSequenceState.IDLE;
            CombatStateManager.releaseMaceSmashLock();
            return;
        }
        stateCommittedTick = client.player != null ? client.player.tickCount : -1;
        stateTickTimer.reset();
        smashState = SmashSequenceState.STEP_2_SWAPBACK_RESTORE;
    }

    /**
     * Only restores the hotbar slot if AutoMace actually switched it during this strike sequence,
     * and never overrides the player's hand while they are actively using an item.
     */
    private void executeSafeLegitSwapBack(Minecraft client) {
        if (client == null || client.player == null) {
            savedHotbarSlot = -1;
            targetMaceSlot = -1;
            didSwitchHotbarSlot = false;
            return;
        }

        if (didSwitchHotbarSlot && !client.player.isUsingItem() && savedHotbarSlot >= 0 && savedHotbarSlot <= 8) {
            selectHotbarSlotLegit(client, savedHotbarSlot);
        }

        savedHotbarSlot = -1;
        targetMaceSlot = -1;
        didSwitchHotbarSlot = false;
    }

    private boolean performThrottledAttack(Minecraft client, LivingEntity targetEntity, boolean immediateHandoff) {
        if (client.player == null || client.gameMode == null || targetEntity == null) {
            return false;
        }
        if (isWhitelistedFriend(client, targetEntity)) {
            return false;
        }
        // Hard assertion: Mace attack cannot fire while Elytra is still equipped in EquipmentSlot.CHEST
        if (client.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)) {
            requireElytraUnequip(client, targetEntity);
            return false;
        }

        boolean raycastValid = ActionManager.verifyStrict3mAttackRaycast(
                client, targetEntity, swingRange.getValue(), client.player.getViewVector(1.0F));

        if (!raycastValid && AngleConnection.INSTANCE.getCurrentAngle() != null) {
            raycastValid = ActionManager.verifyStrict3mAttackRaycast(
                    client, targetEntity, swingRange.getValue(), AngleConnection.INSTANCE.getCurrentAngle().toVector());
        }
        if (!raycastValid && AngleConnection.INSTANCE.getRotation() != null) {
            raycastValid = ActionManager.verifyStrict3mAttackRaycast(
                    client, targetEntity, swingRange.getValue(), AngleConnection.INSTANCE.getRotation().toVector());
        }
        if (!raycastValid && (immediateHandoff || !"Legit".equalsIgnoreCase(aimMode.getValue()))) {
            Angle centerAim = computeExactCenterAimAngle(client, targetEntity);
            if (centerAim != null) {
                raycastValid = ActionManager.verifyStrict3mAttackRaycast(
                        client, targetEntity, swingRange.getValue(), centerAim.toVector());
            }
        }

        if (!raycastValid) {
            return false;
        }
        if (!immediateHandoff
                && !ActionManager.getInstance().canDispatchInteraction(client, ActionManager.ActionPriority.AUTO_MACE_STRIKE)) {
            return false;
        }

        if (client.player.isUsingItem() && client.player.getUseItem().getItem() == Items.SHIELD) {
            client.gameMode.releaseUsingItem(client.player);
            client.player.stopUsingItem();
        }

        if (client.options != null) {
            KeyMapping.click(client.options.keyAttack.getDefaultKey());
        }

        client.gameMode.attack(client.player, targetEntity);
        client.player.swing(InteractionHand.MAIN_HAND);
        ActionManager.getInstance().recordInteractionDispatched(client);
        return true;
    }

    private boolean isGroundedOrAscendingLockout(Minecraft client) {
        if (client.player == null) {
            return true;
        }
        return client.player.onGround()
                || client.player.getDeltaMovement().y >= 0.0D
                || client.player.isInWater()
                || client.player.isInLava()
                || client.player.getAbilities().flying;
    }

    private void releaseCameraAndClearTargetLock() {
        suspendSilentRotation();
        resetKinematicAimState();
        target = null;
    }

    private boolean canEngageModule(Minecraft client) {
        if (client.player == null || client.level == null || isGroundedOrAscendingLockout(client)) {
            return false;
        }
        if (smashState != SmashSequenceState.IDLE) {
            return true;
        }

        boolean holdingMace = isMaceStack(client.player.getMainHandItem());
        if (!holdingMace && getValidatedCachedMaceSlot(client) == -1) {
            return false;
        }

        return getEffectiveFallDistance(client) >= minFallDist.getValue();
    }

    private boolean isManualInputBlockingSwap(Minecraft client) {
        if (client.player == null || client.options == null) {
            return false;
        }
        if (client.player.isUsingItem() && client.options.keyUse.isDown()) {
            return true;
        }
        if (client.options.keyUse.isDown()) {
            Item mainItem = client.player.getMainHandItem().getItem();
            Item offItem = client.player.getOffhandItem().getItem();
            return mainItem == Items.GOLDEN_APPLE
                    || mainItem == Items.ENCHANTED_GOLDEN_APPLE
                    || mainItem == Items.WIND_CHARGE
                    || offItem == Items.GOLDEN_APPLE
                    || offItem == Items.ENCHANTED_GOLDEN_APPLE;
        }
        return false;
    }

    private boolean isWhitelistedFriend(Minecraft client, Entity entity) {
        return ignoreFriends.getValue() && ActionManager.isWhitelistedFriend(client, entity);
    }

    private void resetKinematicAimState() {
        hasPrevError = false;
        prevYawError = 0.0D;
        prevPitchError = 0.0D;
        lastSmoothFrameNs = 0L;
    }

    public void resetAirbornePeakToCurrentPlayerY() {
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.player != null) {
            airbornePeakY = client.player.getY();
        } else {
            airbornePeakY = Double.NaN;
        }
    }

    private void updateAirbornePeak(Minecraft client) {
        if (client.player == null) {
            airbornePeakY = Double.NaN;
            return;
        }
        if (client.player.onGround() || client.player.isInWater() || client.player.isInLava()) {
            airbornePeakY = client.player.getY();
        } else {
            double y = client.player.getY();
            if (Double.isNaN(airbornePeakY) || y > airbornePeakY) {
                airbornePeakY = y;
            }
        }
    }

    private double getEffectiveFallDistance(Minecraft client) {
        if (client.player == null) {
            return 0.0D;
        }
        double vanillaFall = client.player.fallDistance;
        double trackedFall = Double.isNaN(airbornePeakY) ? 0.0D : Math.max(0.0D, airbornePeakY - client.player.getY());
        return Math.max(vanillaFall, trackedFall);
    }

    private boolean shouldAimAtTarget(Minecraft client) {
        if (target == null || client.player == null || isGroundedOrAscendingLockout(client)
                || !isEntityAllowedByTargetFilters(target) || isWhitelistedFriend(client, target)) {
            return false;
        }
        if (!ActionManager.hasLineOfSight(client, target)) {
            return false;
        }
        if (getPrecisionDistanceToTarget(client, target) > aimRange.getValue()) {
            return false;
        }
        return getEffectiveFallDistance(client) >= minFallDist.getValue();
    }

    private boolean isFallingForSmash(Minecraft client) {
        if (client.player == null || isGroundedOrAscendingLockout(client)) {
            return false;
        }
        return getEffectiveFallDistance(client) >= minFallDist.getValue();
    }

    private boolean doesRayIntersectBox(Vec3 origin, Angle angle, AABB box, double maxDist) {
        if (box.contains(origin)) {
            return true;
        }
        Vec3 dir = angle.toVector();
        Vec3 end = origin.add(dir.scale(maxDist));
        Optional<Vec3> hit = box.clip(origin, end);
        return hit.isPresent();
    }

    private boolean shouldTriggerStunSlam(LivingEntity targetEntity) {
        if (!stunSlam.getValue() || targetEntity == null) {
            return false;
        }
        if (targetEntity.isBlocking()) {
            return true;
        }
        return targetEntity.isUsingItem() && targetEntity.getUseItem().getItem() == Items.SHIELD;
    }

    private boolean isMaceStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.getItem() == Items.MACE;
    }

    private double getPrecisionDistanceToTarget(Minecraft client, LivingEntity entity) {
        double eyeX = client.player.getX();
        double eyeY = client.player.getEyeY();
        double eyeZ = client.player.getZ();
        AABB box = entity.getBoundingBox();
        double dx = eyeX - Mth.clamp(eyeX, box.minX, box.maxX);
        double dy = eyeY - Mth.clamp(eyeY, box.minY, box.maxY);
        double dz = eyeZ - Mth.clamp(eyeZ, box.minZ, box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void findTargetFromPreFilteredCache(Minecraft client) {
        if (isGroundedOrAscendingLockout(client)) {
            target = null;
            return;
        }

        List<LivingEntity> candidates = ActionManager.getInstance().getValidTargets(client);
        if (candidates.isEmpty()) {
            target = null;
            return;
        }

        boolean allowPlayers = targetPlayers.getValue();
        boolean allowMobs = targetMobs.getValue();
        boolean allowAnimals = targetAnimals.getValue();
        if (!allowPlayers && !allowMobs && !allowAnimals) {
            target = null;
            return;
        }

        double maxRange = aimRange.getValue();
        double maxRangeSqr = maxRange * maxRange;
        double maxFov = maceFov.getValue();
        double eyeX = client.player.getX();
        double eyeY = client.player.getEyeY();
        double eyeZ = client.player.getZ();

        List<LivingEntity> validStrikeCandidates = new ArrayList<>();

        for (int i = 0, size = candidates.size(); i < size; i++) {
            LivingEntity living = candidates.get(i);

            // Early-Exit Type Filtering BEFORE any distance, bounding-box, or rotation calculations
            if (!allowPlayers && living instanceof Player) {
                continue;
            }
            if (!allowAnimals && living instanceof Animal) {
                continue;
            }
            if (!allowMobs && (living instanceof Mob && !(living instanceof Animal))) {
                continue;
            }

            if (!living.isAlive() || isWhitelistedFriend(client, living)) {
                continue;
            }

            AABB box = living.getBoundingBox();
            double dx = eyeX - Mth.clamp(eyeX, box.minX, box.maxX);
            double dy = eyeY - Mth.clamp(eyeY, box.minY, box.maxY);
            double dz = eyeZ - Mth.clamp(eyeZ, box.minZ, box.maxZ);
            double distSqr = dx * dx + dy * dy + dz * dz;

            if (distSqr > maxRangeSqr) {
                continue;
            }

            if (maxFov < 360.0D) {
                Angle angleToTarget = MathAngle.calculateAngle(getExactBoundingBoxCenter(box));
                double fovDiff = MathAngle.difference(MathAngle.cameraAngle(), angleToTarget);
                if (fovDiff > maxFov * 0.5D) {
                    continue;
                }
            }

            if (!ActionManager.hasLineOfSight(client, living)) {
                continue;
            }

            validStrikeCandidates.add(living);
        }

        if (validStrikeCandidates.isEmpty()) {
            target = null;
            return;
        }

        // Target Prioritization: Players are ALWAYS targeted first (0), regardless of proximity
        validStrikeCandidates.sort(
                Comparator.comparing((Entity e) -> e instanceof Player ? 0 : 1)
                        .thenComparing(e -> client.player.distanceTo(e))
        );

        target = validStrikeCandidates.get(0);
    }

    private void resetAllStates() {
        smashState = SmashSequenceState.IDLE;
        stateCommittedTick = -1;
        savedHotbarSlot = -1;
        targetMaceSlot = -1;
        didSwitchHotbarSlot = false;
        lastTargetScanTick = -1;
        lastHotbarContentsHash = 0;
        cachedMaceSlot = -1;
        cachedAxeSlot = -1;
        cachedSwordSlot = -1;
        airbornePeakY = Double.NaN;
        silentRotationActive = false;
        immediateHandoffStrikeArmed = false;
        CombatStateManager.setRequiresUnequip(false);
        resetKinematicAimState();
        target = null;
    }

    private final class SilentPdRotateConstructor extends RotateConstructor {
        private SilentPdRotateConstructor() {
            super("SilentPdMace");
        }

        @Override
        public Angle limitAngleChange(Angle currentAngle, Angle targetAngle, Vec3 vec3d, Entity entity) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null && entity instanceof LivingEntity living) {
                if (isCrosshairInsideInner20PercentDeadzone(client, living, currentAngle)) {
                    return currentAngle;
                }
            }

            Angle angleDelta = MathAngle.calculateDelta(currentAngle, targetAngle);
            float yawDelta = angleDelta.getYaw();
            float pitchDelta = angleDelta.getPitch();
            double angularDistance = Math.hypot(yawDelta, pitchDelta);
            if (angularDistance < 0.05D) {
                return currentAngle;
            }

            double distNorm = Mth.clamp(angularDistance / PD_DECELERATION_CONE_DEGREES, 0.12D, 1.0D);
            double decelFactor = distNorm * (2.0D - distNorm);
            double maxTickSpeed = Mth.lerp(
                    Mth.clamp(angularDistance / 45.0D, 0.0D, 1.0D),
                    MIN_FAR_ROTATION_DEG_PER_TICK,
                    MAX_FAR_ROTATION_DEG_PER_TICK
            ) * decelFactor;

            float stepYaw = (float) Mth.clamp(yawDelta * 0.65D, -maxTickSpeed, maxTickSpeed);
            float stepPitch = (float) Mth.clamp(pitchDelta * 0.65D, -maxTickSpeed, maxTickSpeed);

            return new Angle(
                    currentAngle.getYaw() + stepYaw,
                    Mth.clamp(currentAngle.getPitch() + stepPitch, -89.5F, 89.5F)
            ).adjustSensitivity();
        }

        @Override
        public Vec3 randomValue() {
            return Vec3.ZERO;
        }
    }
}
