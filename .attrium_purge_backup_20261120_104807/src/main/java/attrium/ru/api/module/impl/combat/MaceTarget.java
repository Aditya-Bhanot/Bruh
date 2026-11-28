package attrium.ru.api.module.impl.combat;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import attrium.ru.api.events.annotation.SubscribeEvent;
import attrium.ru.api.events.impl.DrawEvent;
import attrium.ru.api.events.impl.PlayerTravelEvent;
import attrium.ru.api.events.impl.RotationUpdateEvent;
import attrium.ru.api.events.impl.WorldRenderEvent;
import attrium.ru.api.events.types.EventPhase;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.ModuleCategory;
import attrium.ru.api.module.impl.combat.aura.Angle;
import attrium.ru.api.module.impl.combat.aura.AngleConfig;
import attrium.ru.api.module.impl.combat.aura.AngleConnection;
import attrium.ru.api.module.impl.combat.aura.MathAngle;
import attrium.ru.api.module.impl.combat.aura.impl.RotateConstructor;
import attrium.ru.api.module.impl.combat.aura.util.MathUtils;
import attrium.ru.api.module.impl.combat.aura.util.StopWatch;
import attrium.ru.api.module.impl.combat.aura.util.TaskPriority;
import attrium.ru.api.module.impl.movement.ElytraTarget;
import attrium.ru.api.settings.Setting;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.ModeSetting;
import attrium.ru.api.settings.impl.MultiModeSetting;
import attrium.ru.api.settings.impl.NumberSetting;
import attrium.ru.utils.inventory.lookup.InventoryUtils;
import attrium.ru.utils.render.Render3D;
import attrium.ru.utils.repository.friend.FriendUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * GrimAC-Safe Fast Legit Hotbar AutoMace Controller (Mojang 1.21.11 Official Mappings):
 * 1. Zero Simulation, Zero BadPacketsJ, & Zero Post Flags:
 *    - Pre-tick hotbar slot transitions run at ClientTickEvents.START_CLIENT_TICK (before Minecraft.handleKeybinds()
 *      and before LocalPlayer.aiStep()).
 *    - Camera rotation updates in Legit and Strict modes run strictly in DrawEvent (DrawEvent.Layer.GAME)
 *      before the client tick begins—never between LocalPlayer.aiStep() (Entity.moveRelative) and
 *      LocalPlayer.sendPosition(), so movement physics and packet rotations match 100%.
 * 2. Zero False Aim / Unequip When Jumping Near Enemy:
 *    - Normal jumping reaches at most 1.252 blocks. AutoMace strictly requires fallDistance >= 1.60m (or active
 *      isFallFlying() Elytra/Rocket glide with speed >= 0.68 or altitude >= 1.6m) before aiming or unequipping.
 * 3. Click-to-Bind "Toggle Bind: [NONE]" to turn AutoMace ON/OFF with any key or mouse button.
 */
public class MaceTarget extends Module {
    private static MaceTarget instance;

    private static final int TARGET_WIREFRAME_COLOR = 0xAA5EF2D6;
    private static final double INNER_20_PERCENT_HALF_RATIO = 0.10D;
    private static final double MIN_FAR_ROTATION_DEG_PER_TICK = 40.0D;
    private static final double MAX_FAR_ROTATION_DEG_PER_TICK = 60.0D;
    private static final double PD_DECELERATION_CONE_DEGREES = 32.0D;
    private static final double MIN_NON_GLIDE_FALL_BLOCKS = 1.50D;
    private static final int HOTBAR_SCAN_INTERVAL_TICKS = 4;

    /**
     * GrimAC-Safe Legit Hotbar ActionManager:
     * Executes hotbar slot changes at START_CLIENT_TICK (before Minecraft.handleKeybinds() and LocalPlayer.aiStep()).
     */
    public static final class ActionManager {
        private static final ActionManager INSTANCE = new ActionManager();

        public enum ActionPriority {
            CRITICAL(0),
            AUTO_ELYTRA_UNEQUIP(1),
            AUTO_MACE_STRIKE(2),
            COMBAT(2),
            AUTO_ELYTRA_EQUIP(3),
            MOVEMENT(3),
            UTILITY(4);

            private final int rank;

            ActionPriority(int rank) {
                this.rank = rank;
            }

            public int getRank() {
                return rank;
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
            WAIT_FOR_KEYBIND_USE,
            WAIT_TO_RESTORE
        }

        private static final double GLOBAL_MIN_CPS = 11.0D;
        private static final double GLOBAL_MAX_CPS = 15.0D;
        private static final long CRITICAL_LOCK_FAILSAFE_MS = 350L;
        private static final long GENERAL_LOCK_FAILSAFE_MS = 400L;

        private volatile ActionPriority activeLockPriority = null;
        private volatile String activeLockOwnerName = "";
        private volatile long lockAcquiredAtMs = 0L;

        private ConsolidatedSwapStage swapStage = ConsolidatedSwapStage.IDLE;
        private ActionPriority queuedSwapPriority = null;
        private int targetHotbarSlot = -1;
        private int restoreHotbarSlot = -1;
        private int stageStartTick = -100;
        private long stageStartTimeMs = 0L;
        private boolean wasWearingElytraAtStart = false;
        private boolean inTickPhase = false;

        private int lastInteractionTick = -100;
        private long lastInteractionTimeMs = 0L;
        private long currentMinInteractionIntervalMs = 65L;

        private int clientTickCounter = 0;
        private long lastPreTickNanoTime = 0L;
        private int cachedTargetsTick = -100;
        private int cachedTargetMask = -1;
        private int lastPreTickHandled = -100;
        private int lastObservedPlayerId = -1;
        private final List<LivingEntity> validTargetsMutable = new ArrayList<>(32);
        private List<LivingEntity> validTargetsView = Collections.emptyList();

        private static final List<Item> CHESTPLATES = List.of(
                Items.NETHERITE_CHESTPLATE,
                Items.DIAMOND_CHESTPLATE,
                Items.IRON_CHESTPLATE,
                Items.CHAINMAIL_CHESTPLATE,
                Items.GOLDEN_CHESTPLATE,
                Items.LEATHER_CHESTPLATE
        );

        private ActionManager() {
        }

        public static ActionManager getInstance() {
            return INSTANCE;
        }

        public int getClientTick() {
            return clientTickCounter;
        }

        /**
         * Runs at ClientTickEvents.START_CLIENT_TICK (HEAD of Minecraft.tick(), BEFORE handleKeybinds() & aiStep()).
         * Increments clientTickCounter strictly once per client tick so START_CLIENT_TICK, onPlayerTravel,
         * and onRotationUpdate all observe the exact same tick number throughout the entire tick!
         */
        public synchronized void onPreTickRender(Minecraft client) {
            inTickPhase = false;
            if (client == null || client.player == null || client.level == null) {
                resetAll();
                return;
            }
            long nowNs = System.nanoTime();
            // Guard against multiple callers invoking onPreTickRender in the same START_CLIENT_TICK pass
            if (nowNs - lastPreTickNanoTime > 20_000_000L || client.player.tickCount != lastPreTickHandled) {
                clientTickCounter++;
                lastPreTickNanoTime = nowNs;
                lastPreTickHandled = client.player.tickCount;
            }
            if (client.screen != null) {
                abortActiveConsolidatedSwap(client);
                return;
            }
            checkWorldOrRespawnReset(client);
            checkLockTimeouts();
            int currentMask = MaceTarget.getTargetSelectionMask();
            if (cachedTargetsTick != clientTickCounter || cachedTargetMask != currentMask) {
                boolean newTick = (cachedTargetsTick != clientTickCounter);
                cachedTargetsTick = clientTickCounter;
                cachedTargetMask = currentMask;
                rebuildValidTargetsCacheOncePerTick(client);
                if (newTick) {
                    advanceSwapBeforeKeybinds(client);
                }
            }
        }

        public synchronized void ensureTickSynchronized(Minecraft client) {
            if (client == null || client.player == null || client.level == null) {
                resetAll();
                return;
            }
            if (client.screen != null) {
                abortActiveConsolidatedSwap(client);
                return;
            }

            checkWorldOrRespawnReset(client);
            checkLockTimeouts();
            detectCriticalSurvivalLock(client);

            int currentMask = MaceTarget.getTargetSelectionMask();
            if (cachedTargetsTick != clientTickCounter || cachedTargetMask != currentMask) {
                boolean newTick = (cachedTargetsTick != clientTickCounter);
                cachedTargetsTick = clientTickCounter;
                cachedTargetMask = currentMask;
                rebuildValidTargetsCacheOncePerTick(client);
                if (newTick) {
                    advanceSwapBeforeKeybinds(client);
                }
            }
        }

        private void checkWorldOrRespawnReset(Minecraft client) {
            int playerId = client.player.getId();
            if (playerId != lastObservedPlayerId || client.player.tickCount < cachedTargetsTick) {
                lastObservedPlayerId = playerId;
                resetAll();
                cachedTargetsTick = client.player.tickCount;
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
            if (activeLockPriority == null) {
                return;
            }
            long elapsed = System.currentTimeMillis() - lockAcquiredAtMs;
            long limit = activeLockPriority == ActionPriority.CRITICAL ? CRITICAL_LOCK_FAILSAFE_MS : GENERAL_LOCK_FAILSAFE_MS;
            if (elapsed > limit && swapStage == ConsolidatedSwapStage.IDLE) {
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

        public synchronized void forceReleaseAllLocks() {
            activeLockPriority = null;
            activeLockOwnerName = "";
            lockAcquiredAtMs = 0L;
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

        public boolean isLocked() {
            checkLockTimeouts();
            return activeLockPriority != null || swapStage != ConsolidatedSwapStage.IDLE;
        }

        public synchronized boolean canDispatchInteraction(Minecraft client, ActionPriority priority) {
            if (client == null || client.player == null || client.screen != null) {
                return false;
            }
            if (client.player.isUsingItem()) {
                return false;
            }
            if (isBlockedByHigherPriority(priority)) {
                return false;
            }
            if (clientTickCounter == lastInteractionTick) {
                return false;
            }
            long now = System.currentTimeMillis();
            return (now - lastInteractionTimeMs) >= currentMinInteractionIntervalMs;
        }

        public synchronized void recordInteractionDispatched(Minecraft client) {
            lastInteractionTick = clientTickCounter;
            lastInteractionTimeMs = System.currentTimeMillis();
            double cps = ThreadLocalRandom.current().nextDouble(GLOBAL_MIN_CPS, GLOBAL_MAX_CPS);
            currentMinInteractionIntervalMs = Math.round(1000.0D / cps);
        }

        public synchronized boolean submitFastTwoTickUnequip(
                Minecraft client,
                int chestplateHotbarSlot,
                int restoreSlotAfterUnequip,
                boolean clickSim
        ) {
            return submitFastTwoTickArmorSwap(
                    client,
                    ActionPriority.AUTO_ELYTRA_UNEQUIP,
                    "AutoElytra_UNEQUIP",
                    chestplateHotbarSlot,
                    restoreSlotAfterUnequip,
                    clickSim
            );
        }

        public synchronized boolean submitFastTwoTickArmorSwap(
                Minecraft client,
                ActionPriority priority,
                String ownerName,
                int armorHotbarSlot,
                int restoreSlotAfterEquip,
                boolean clickSim
        ) {
            if (client == null || client.player == null || client.gameMode == null || client.screen != null) {
                return false;
            }
            if (armorHotbarSlot < 0 || armorHotbarSlot > 8) {
                return false;
            }
            if (client.player.isUsingItem()) {
                return false;
            }
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
            this.restoreHotbarSlot = (restoreSlotAfterEquip >= 0 && restoreSlotAfterEquip <= 8)
                    ? restoreSlotAfterEquip
                    : client.player.getInventory().getSelectedSlot();
            this.wasWearingElytraAtStart = isWearingElytra(client);

            clearSilentRotationForUseItem();

            if (client.player.getInventory().getSelectedSlot() != armorHotbarSlot) {
                client.player.getInventory().setSelectedSlot(armorHotbarSlot);
                if (client.player.connection != null) {
                    client.player.connection.send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(armorHotbarSlot));
                }
            }

            if (client.gameMode != null) {
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
            }

            this.stageStartTick = clientTickCounter;
            this.stageStartTimeMs = System.currentTimeMillis();
            this.swapStage = ConsolidatedSwapStage.WAIT_TO_RESTORE;
            return true;
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
            if (rightClickUseOnEquip && onSlotReady == null) {
                return submitFastTwoTickArmorSwap(client, priority, ownerName, targetSlot, restoreSlot, clickSim);
            }
            if (client == null || client.player == null || client.screen != null || targetSlot < 0 || targetSlot > 8) {
                return false;
            }
            if (client.player.isUsingItem() || isBlockedByHigherPriority(priority)) {
                return false;
            }
            if (client.player.getInventory().getSelectedSlot() != targetSlot) {
                client.player.getInventory().setSelectedSlot(targetSlot);
            }
            if (onSlotReady != null) {
                onSlotReady.run();
            }
            return true;
        }

        private void advanceSwapBeforeKeybinds(Minecraft client) {
            if (swapStage == ConsolidatedSwapStage.IDLE || client.player == null) {
                return;
            }
            if (client.screen != null || client.player.isUsingItem()) {
                completeConsolidatedSwap();
                return;
            }

            int ticksElapsed = clientTickCounter - stageStartTick;
            boolean armorChanged = isWearingElytra(client) != wasWearingElytraAtStart;

            if (swapStage == ConsolidatedSwapStage.WAIT_TO_RESTORE) {
                // Wait at least 1 tick so handleKeybinds() on stageStartTick consumes keyUse and equips the armor!
                if (ticksElapsed < 1 || (!armorChanged && ticksElapsed < 2)) {
                    if (targetHotbarSlot >= 0 && targetHotbarSlot <= 8
                            && client.player.getInventory().getSelectedSlot() != targetHotbarSlot) {
                        client.player.getInventory().setSelectedSlot(targetHotbarSlot);
                    }
                    return;
                }

                if (restoreHotbarSlot >= 0 && restoreHotbarSlot <= 8
                        && restoreHotbarSlot != targetHotbarSlot
                        && client.player.getInventory().getSelectedSlot() != restoreHotbarSlot) {
                    client.player.getInventory().setSelectedSlot(restoreHotbarSlot);
                }
                completeConsolidatedSwap();
            }
        }

        private static boolean isWearingElytra(Minecraft client) {
            if (client == null || client.player == null) {
                return false;
            }
            return InventoryUtils.isElytraEquipped()
                    || client.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
        }

        private static void clearSilentRotationForUseItem() {
            try {
                if (AngleConnection.INSTANCE.getRotation() != null) {
                    AngleConnection.INSTANCE.startReturning();
                }
            } catch (Throwable ignored) {
            }
        }

        public static boolean executeLegitChestSlotToggle() {
            return executeLegitArmorSwapFromSlot(-1);
        }

        public static boolean executeLegitArmorSwapFromSlot(int preferredSlot) {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.gameMode == null || mc.screen != null || mc.player.isUsingItem()) {
                return false;
            }

            ActionManager mgr = getInstance();
            if (mgr.isSwapInProgress()) {
                return true;
            }

            int hotbarSlot = -1;
            if (preferredSlot >= 0 && preferredSlot <= 8) {
                hotbarSlot = preferredSlot;
            } else if (preferredSlot >= 36 && preferredSlot <= 44) {
                hotbarSlot = preferredSlot - 36;
            } else {
                boolean wearingElytra = isWearingElytra(mc);
                if (wearingElytra) {
                    int bestScore = -1;
                    for (int i = 0; i < 9; i++) {
                        ItemStack stack = mc.player.getInventory().getItem(i);
                        if (stack.isEmpty() || stack.is(Items.ELYTRA)) {
                            continue;
                        }
                        if (stack.is(ItemTags.CHEST_ARMOR) || CHESTPLATES.contains(stack.getItem())) {
                            int score = getChestScore(stack.getItem());
                            if (score > bestScore) {
                                bestScore = score;
                                hotbarSlot = i;
                            }
                        }
                    }
                } else {
                    for (int i = 0; i < 9; i++) {
                        ItemStack stack = mc.player.getInventory().getItem(i);
                        if (!stack.isEmpty() && stack.is(Items.ELYTRA)
                                && (stack.getMaxDamage() - stack.getDamageValue() > 2)) {
                            hotbarSlot = i;
                            break;
                        }
                    }
                }
            }

            if (hotbarSlot >= 0 && hotbarSlot <= 8) {
                int currentSlot = mc.player.getInventory().getSelectedSlot();
                return mgr.submitFastTwoTickArmorSwap(
                        mc,
                        ActionPriority.AUTO_ELYTRA_UNEQUIP,
                        "ElytraHelper_LEGIT",
                        hotbarSlot,
                        currentSlot,
                        true
                );
            }
            return false;
        }

        public static boolean onInventoryUtilsSelectSlot(int slot) {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || slot < 0 || slot > 8) {
                return false;
            }
            return getInstance().isSwapInProgress();
        }

        public static boolean interceptElytraHelperUseItem(Minecraft mc, InteractionHand hand) {
            if (mc == null || mc.player == null || mc.gameMode == null || mc.screen != null || mc.player.isUsingItem()) {
                return false;
            }
            ActionManager mgr = getInstance();
            if (mgr.isSwapInProgress()) {
                return true;
            }
            int currentSlot = mc.player.getInventory().getSelectedSlot();
            return mgr.submitFastTwoTickArmorSwap(
                    mc,
                    ActionPriority.AUTO_ELYTRA_UNEQUIP,
                    "ElytraHelper_USE",
                    currentSlot,
                    currentSlot,
                    true
            );
        }

        public static boolean shouldSuppressCloseScreen() {
            return true;
        }

        private static int getChestScore(Item item) {
            if (item == Items.NETHERITE_CHESTPLATE) return 6;
            if (item == Items.DIAMOND_CHESTPLATE) return 5;
            if (item == Items.IRON_CHESTPLATE) return 4;
            if (item == Items.CHAINMAIL_CHESTPLATE) return 3;
            if (item == Items.GOLDEN_CHESTPLATE) return 2;
            if (item == Items.LEATHER_CHESTPLATE) return 1;
            return 1;
        }

        private void abortActiveConsolidatedSwap(Minecraft client) {
            completeConsolidatedSwap();
        }

        private void completeConsolidatedSwap() {
            ActionPriority finishedPriority = queuedSwapPriority;
            swapStage = ConsolidatedSwapStage.IDLE;
            queuedSwapPriority = null;
            targetHotbarSlot = -1;
            restoreHotbarSlot = -1;
            stageStartTick = -100;
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

            for (Entity entity : client.level.entitiesForRendering()) {
                if (!(entity instanceof LivingEntity living)) {
                    continue;
                }
                if (!MaceTarget.isEntityAllowedByAutoMaceTargets(client, living)) {
                    continue;
                }
                validTargetsMutable.add(living);
            }

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

            LivingEntity best = null;
            double bestDistSqr = Double.MAX_VALUE;

            for (int i = 0, size = validTargetsView.size(); i < size; i++) {
                LivingEntity candidate = validTargetsView.get(i);
                if (!candidate.isAlive() || !MaceTarget.isEntityAllowedByAutoMaceTargets(client, candidate)) {
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

                if (distSqr > maxRangeSqr || distSqr >= bestDistSqr) {
                    continue;
                }
                if (!hasLineOfSight(client, candidate)) {
                    continue;
                }

                bestDistSqr = distSqr;
                best = candidate;
            }

            return best;
        }

        public static boolean isWhitelistedFriend(Minecraft client, Entity entity) {
            if (attrium.ru.manager.FriendManager.isFriend(entity)) {
                return true;
            }
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

        public static boolean verifyStrict3mAttackRaycast(Minecraft client, LivingEntity target, double requestedReach) {
            Vec3 defaultLook = (client != null && client.player != null) ? client.player.getViewVector(1.0F) : null;
            return verifyStrict3mAttackRaycast(client, target, requestedReach, defaultLook);
        }

        public static boolean verifyStrict3mAttackRaycast(
                Minecraft client,
                LivingEntity target,
                double requestedReach,
                Vec3 customLookVec
        ) {
            if (client == null || client.player == null || client.level == null || target == null || !target.isAlive()) {
                return false;
            }
            double legalReach = Math.min(3.0D, Math.max(1.0D, requestedReach));
            Vec3 eyePos = client.player.getEyePosition(1.0F);
            Vec3 lookVec = customLookVec != null ? customLookVec.normalize() : client.player.getViewVector(1.0F);
            Vec3 rayEnd = eyePos.add(lookVec.scale(legalReach));

            AABB targetBox = target.getBoundingBox().deflate(0.05D);
            if (targetBox.contains(eyePos)) {
                return true;
            }

            Optional<Vec3> hitOpt = targetBox.clip(eyePos, rayEnd);
            if (hitOpt.isEmpty()) {
                return false;
            }

            Vec3 hitPoint = hitOpt.get();
            if (eyePos.distanceToSqr(hitPoint) > (legalReach * legalReach)) {
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

        public synchronized void resetAll() {
            activeLockPriority = null;
            activeLockOwnerName = "";
            lockAcquiredAtMs = 0L;
            swapStage = ConsolidatedSwapStage.IDLE;
            queuedSwapPriority = null;
            targetHotbarSlot = -1;
            restoreHotbarSlot = -1;
            stageStartTick = -100;
            lastInteractionTick = -100;
            cachedTargetsTick = -100;
            lastPreTickHandled = -100;
            validTargetsMutable.clear();
            validTargetsView = Collections.emptyList();
        }
    }

    /**
     * Bridge adapter delegating to ActionManager.
     */
    public static final class CombatStateManager {
        private static int lockAcquiredTick = -100;

        private CombatStateManager() {
        }

        public static void acquireMaceSmashLock() {
            Minecraft mc = Minecraft.getInstance();
            if (ActionManager.getInstance().acquireLock(ActionManager.ActionPriority.AUTO_MACE_STRIKE, "AutoMace_STRIKE")) {
                lockAcquiredTick = (mc != null && mc.player != null) ? mc.player.tickCount : -100;
            }
        }

        public static void releaseMaceSmashLock() {
            ActionManager.getInstance().releaseLock(ActionManager.ActionPriority.AUTO_MACE_STRIKE, "AutoMace_STRIKE");
            lockAcquiredTick = -100;
        }

        public static boolean tryAcquireElytraAscentLock() {
            return ActionManager.getInstance().acquireLock(ActionManager.ActionPriority.AUTO_ELYTRA_EQUIP, "AutoElytra_EQUIP");
        }

        public static void releaseElytraLock() {
            ActionManager.getInstance().releaseLock(ActionManager.ActionPriority.AUTO_ELYTRA_UNEQUIP, "AutoElytra_UNEQUIP");
            ActionManager.getInstance().releaseLock(ActionManager.ActionPriority.AUTO_ELYTRA_EQUIP, "AutoElytra_EQUIP");
        }

        public static boolean isMaceSmashLocked() {
            checkStaleMaceLock();
            return ActionManager.getInstance().getActiveLockPriority() == ActionManager.ActionPriority.AUTO_MACE_STRIKE;
        }

        public static boolean isElytraAscentLocked() {
            ActionManager.ActionPriority active = ActionManager.getInstance().getActiveLockPriority();
            return active == ActionManager.ActionPriority.AUTO_ELYTRA_UNEQUIP
                    || active == ActionManager.ActionPriority.AUTO_ELYTRA_EQUIP;
        }

        public static void checkStaleMaceLock() {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.player.onGround() || !mc.player.isAlive()) {
                if (ActionManager.getInstance().getActiveLockPriority() == ActionManager.ActionPriority.AUTO_MACE_STRIKE) {
                    ActionManager.getInstance().forceReleaseAllLocks();
                }
                lockAcquiredTick = -100;
                return;
            }
            if (lockAcquiredTick != -100 && (mc.player.tickCount - lockAcquiredTick) > 8) {
                ActionManager.getInstance().forceReleaseAllLocks();
                lockAcquiredTick = -100;
            }
        }

        public static void reset() {
            lockAcquiredTick = -100;
            ActionManager.getInstance().resetAll();
        }
    }

    // Click-to-Bind Module Toggle Bind Setting
    private final BooleanSetting bindToggleKey = register(new BooleanSetting(
            "Toggle Bind: [NONE]",
            "Click this, then press any key or mouse button to bind AutoMace ON/OFF toggle (ESC/BACKSPACE clears).",
            false));

    // Synchronized Target Selection (Controls both AutoMace and AutoElytra)
    private final MultiModeSetting targets = register(new MultiModeSetting(
            "Targets",
            "Select which entity types AutoMace and AutoElytra will activate on (automatically synced to AutoElytra).",
            new String[]{"Players", "Mobs", "Animals"},
            "Players"
    ));

    // Core Targeting & Swing Settings
    private final NumberSetting swingRange = register(new NumberSetting(
            "Swing Range", "Maximum distance to swing and attack with the mace (capped at 3.0m vanilla reach).", 3.0D, 1.0D, 3.0D, 0.05D));
    private final NumberSetting aimRange = register(new NumberSetting(
            "Aim Range", "Maximum range to acquire and track targets.", 12.0D, 1.0D, 20.0D, 0.1D));
    private final NumberSetting maceFov = register(new NumberSetting(
            "Mace FOV", "Field of view cone in degrees for target acquisition.", 120.0D, 1.0D, 360.0D, 1.0D));
    private final NumberSetting aimInAir = register(new NumberSetting(
            "Aim In Air", "Minimum fall distance required before airborne aiming begins.", 1.5D, 0.0D, 10.0D, 0.1D));

    private final BooleanSetting autoSwitch = register(new BooleanSetting(
            "Auto Switch", "Automatically switch hotbar slot to the dynamically cached mace.", true));
    private final BooleanSetting swapBack = register(new BooleanSetting(
            "Swap Back", "Restore previous weapon slot after attacking.", true));

    private final ModeSetting aimMode = register(new ModeSetting(
            "Aim Mode", "Tracking algorithm: Legit (PD + 20% deadzone), Strict (instant center lock), or Silent.", "Legit", "Legit", "Strict", "Silent"));

    private final NumberSetting aimSpeed = register(new NumberSetting(
            "Aim Speed", "Angular rotation speed during distance-scaled PD deceleration.", 50.0D, 1.0D, 100.0D, 0.5D));
    private final NumberSetting aimSmooth = register(new NumberSetting(
            "Aim Smooth", "Smoothing derivative damping factor applied to rotation interpolation.", 0.00D, 0.00D, 10.00D, 0.01D));
    private final NumberSetting minFallDist = register(new NumberSetting(
            "Min Fall Dist", "Minimum fall distance required to trigger a mace slam (>1.25m normal jump).", 1.5D, 0.0D, 20.0D, 0.1D));
    private final NumberSetting cooldownMs = register(new NumberSetting(
            "Cooldown (ms)", "Minimum cooldown in milliseconds between slam sequences.", 100.0D, 0.0D, 1000.0D, 5.0D));
    private final NumberSetting maceSwapDelayMs = register(new NumberSetting(
            "Mace Swap Delay (ms)", "Delay in milliseconds after switching to mace before attacking.", 0.0D, 0.0D, 500.0D, 1.0D));

    private final BooleanSetting stunSlam = register(new BooleanSetting(
            "Stun Slam", "Break target shield with an axe before executing the mace slam.", true));
    private final BooleanSetting properStunslam = register(new BooleanSetting(
            "ProperStunslam", "Verify target is actively blocking with a shield before stun-slamming.", false));
    private final BooleanSetting onlyOnNonGlide = register(new BooleanSetting(
            "Only On NonGlide", "Only aim and attack when not actively gliding with an Elytra.", false));
    private final BooleanSetting weaponOnly = register(new BooleanSetting(
            "Weapon Only", "Only activate targeting and attacks while holding a weapon.", false));
    private final BooleanSetting clickSim = register(new BooleanSetting(
            "ClickSim", "Simulate client mouse click and hand swing during attacks.", true));
    private final BooleanSetting ignoreFriends = register(new BooleanSetting(
            "Ignore Friends", "Ignore players registered in the friend list.", true));
    private final BooleanSetting targetEsp = register(new BooleanSetting(
            "Target ESP", "Render a minimal single-pass wireframe outline on the active target.", true));

    private final NumberSetting savedToggleKeyCode = register(new NumberSetting(
            "_amToggleKeyCode",
            "Internal saved GLFW keycode for AutoMace Toggle Bind.",
            -1.0D,
            -100.0D,
            400.0D,
            1.0D));

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
    private int stateCommittedTick = -100;
    private int savedHotbarSlot = -1;
    private int targetMaceSlot = -1;
    private int lastTargetScanTick = -100;
    private int lastObservedPlayerId = -1;
    private int lastActiveGlideDiveTick = -100;
    private int postUnequipSmashEndTick = -100;
    private int groundedAfterDiveTicks = 0;
    private double airbornePeakY = Double.NaN;
    private boolean silentRotationActive = false;
    private String lastActiveAimMode = "Legit";

    private int cachedMaceSlot = -1;
    private int cachedAxeSlot = -1;
    private int cachedSwordSlot = -1;
    private int lastInventoryScanTick = -100;

    private double prevYawError = 0.0D;
    private double prevPitchError = 0.0D;
    private boolean hasPrevError = false;
    private boolean hitboxLocked = false;
    private int hitboxLockedTargetId = -1;
    private long lastSmoothFrameNs = 0L;

    private boolean wasTogglePressed = false;
    private boolean waitingForToggleMouseRelease = false;
    private boolean wasListeningToggle = false;
    private int lastDisplayedToggleKey = Integer.MIN_VALUE;

    public MaceTarget() {
        super("AutoMace", "Switches to Mace mid-fall for high-damage smash hits", ModuleCategory.COMBAT);
        instance = this;
        savedToggleKeyCode.visibleWhen(() -> false);
        bindToggleKey.visibleWhen(() -> {
            pollToggleBindCapture();
            return true;
        });
        refreshToggleBindLabel();

        // Pre-Keybind & Pre-Movement Execution at HEAD of Minecraft.tick()
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            pollToggleBindCapture();
            pollModuleToggleBind(client);
            if (isEnabled() && client != null && client.player != null && client.level != null && client.screen == null) {
                ActionManager.getInstance().onPreTickRender(client);
                if (isRightClickUseOrSwapActive(client)) {
                    suspendSilentRotation();
                }
                // 1-Tick (+50ms) Post-Hit Hotbar Restore (Slot 1 Mace -> Slot 4 Firework Rocket), even when ascending from Wind Burst!
                if (smashState == SmashSequenceState.STEP_2_SWAPBACK_RESTORE
                        && ActionManager.getInstance().getClientTick() > stateCommittedTick) {
                    executeSafeLegitSwapBack(client);
                    smashState = SmashSequenceState.IDLE;
                    CombatStateManager.releaseMaceSmashLock();
                }
                trackGlideAndDiveWindow(client);
                updateAirbornePeak(client);
                if (canEngageModule(client)) {
                    if (target == null || !isEntityAllowedByTargetSelection(client, target) || !ActionManager.hasLineOfSight(client, target)) {
                        findTargetFromPreFilteredCache(client);
                    }
                    // Pre-impact hitbox alignment & pre-keybind strike at HEAD of tick (before handleKeybinds & LocalPlayer.aiStep)
                    if (target != null && isEntityAllowedByTargetSelection(client, target) && !isWearingElytra(client)
                            && (isFallingForSmash(client) || isInMidOrPostUnequipSmash(client))) {
                        double dist = getPrecisionDistanceToTarget(client, target);
                        if (!"Silent".equalsIgnoreCase(aimMode.getValue()) && dist <= swingRange.getValue() + 0.75D) {
                            if (!ActionManager.verifyStrict3mAttackRaycast(client, target, swingRange.getValue(), client.player.getViewVector(1.0F))) {
                                alignCameraOntoHitboxForStrike(client, target);
                            }
                        }
                        if (!ActionManager.getInstance().isBlockedByHigherPriority(ActionManager.ActionPriority.AUTO_MACE_STRIKE)
                                && !ActionManager.getInstance().isSwapInProgress()
                                && !isManualInputBlockingSwap(client)) {
                            if (smashState != SmashSequenceState.IDLE) {
                                processSmashSequenceStateMachine(client);
                            } else if (dist <= swingRange.getValue() && cooldownTimer.finished(cooldownMs.getValue())) {
                                initiateSmashSequence(client, target);
                            }
                        }
                    }
                }
            }
        });
    }

    public static MaceTarget getInstance() {
        return instance;
    }

    public static boolean isAutoMaceEnabled() {
        return instance != null && instance.isEnabled();
    }

    public static boolean canExecuteStrike() {
        if (instance == null || !instance.isEnabled()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        return instance.isMidSlamSequence() || instance.isInMidOrPostUnequipSmash(mc);
    }

    public LivingEntity getTarget() {
        return target;
    }

    public LivingEntity getActiveTarget() {
        return target;
    }

    public boolean isMidSlamSequence() {
        return smashState != SmashSequenceState.IDLE || CombatStateManager.isMaceSmashLocked();
    }

    public boolean isInStrikeMutexLockout() {
        return smashState != SmashSequenceState.IDLE || CombatStateManager.isMaceSmashLocked();
    }

    public int getPreferredMaceHotbarSlot() {
        return cachedMaceSlot;
    }

    public boolean isInMidOrPostUnequipSmash(Minecraft client) {
        if (client == null || client.player == null) {
            return false;
        }
        int curTick = ActionManager.getInstance().getClientTick();
        return smashState != SmashSequenceState.IDLE
                || (!isWearingElytra(client) && (curTick <= postUnequipSmashEndTick || (curTick - lastActiveGlideDiveTick) <= 3));
    }

    /**
     * Called by AutoElytra when unequipping the Elytra for a smash:
     * Locks the target and activates the 25-tick post-unequip smash window WITHOUT switching to the Mace early,
     * preserving the player's current weapon/hand attack cooldown until the exact hit tick (Mace Cooldown Swap)!
     */
    public void notifyElytraUnequippedForSmash(Minecraft client, int currentHotbarSlot, LivingEntity diveTarget) {
        if (!isEnabled() || client == null || client.player == null) {
            return;
        }
        int maceSlot = resolveMaceSlot(client);
        if (maceSlot == -1 && !isMaceStack(client.player.getMainHandItem())) {
            return;
        }
        if (diveTarget != null && diveTarget.isAlive()) {
            this.target = diveTarget;
        }
        if (savedHotbarSlot == -1 && currentHotbarSlot >= 0 && currentHotbarSlot <= 8 && currentHotbarSlot != maceSlot) {
            savedHotbarSlot = currentHotbarSlot;
        }
        targetMaceSlot = maceSlot;
        int curTick = ActionManager.getInstance().getClientTick();
        lastActiveGlideDiveTick = curTick;
        postUnequipSmashEndTick = curTick + 25;
        CombatStateManager.acquireMaceSmashLock();
    }

    public int prepareDirectMaceHandoffSlot(Minecraft client, int currentHotbarSlot, LivingEntity diveTarget) {
        notifyElytraUnequippedForSmash(client, currentHotbarSlot, diveTarget);
        return currentHotbarSlot;
    }

    @Override
    protected void onEnable() {
        resetAllStates();
        refreshToggleBindLabel();
    }

    @Override
    protected void onDisable() {
        Minecraft client = Minecraft.getInstance();
        if (swapBack.getValue()) {
            executeSafeLegitSwapBack(client);
        }
        suspendSilentRotation();
        CombatStateManager.releaseMaceSmashLock();
        resetAllStates();
        refreshToggleBindLabel();
    }

    private void checkWorldOrRespawnChange(Minecraft client) {
        if (client.player == null) {
            return;
        }
        int currentId = client.player.getId();
        if (currentId != lastObservedPlayerId || client.player.tickCount < lastTargetScanTick) {
            lastObservedPlayerId = currentId;
            CombatStateManager.reset();
            resetAllStates();
            lastTargetScanTick = client.player.tickCount;
            lastInventoryScanTick = -100;
            updateDynamicMaceCacheAt4Hz(client, true);
        }
    }

    private void updateDynamicMaceCacheAt4Hz(Minecraft client, boolean forceImmediate) {
        if (client.player == null || client.screen != null) {
            return;
        }
        int currentTick = client.player.tickCount;
        if (!forceImmediate && (currentTick - lastInventoryScanTick) < HOTBAR_SCAN_INTERVAL_TICKS) {
            return;
        }
        lastInventoryScanTick = currentTick;

        int bestMaceSlot = -1;
        int bestMaceScore = -1;
        int firstAxeSlot = -1;
        int firstSwordSlot = -1;

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = client.player.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (isMaceStack(stack)) {
                int score = scoreMaceEnchantments(stack);
                if (score > bestMaceScore) {
                    bestMaceScore = score;
                    bestMaceSlot = slot;
                }
            } else if (firstAxeSlot == -1 && (stack.getItem() instanceof AxeItem || stack.is(ItemTags.AXES))) {
                firstAxeSlot = slot;
            } else if (firstSwordSlot == -1 && stack.is(ItemTags.SWORDS)) {
                firstSwordSlot = slot;
            }
        }

        cachedMaceSlot = bestMaceSlot;
        cachedAxeSlot = firstAxeSlot;
        cachedSwordSlot = firstSwordSlot;
    }

    private int scoreMaceEnchantments(ItemStack stack) {
        int score = 100;
        ItemEnchantments enchantments = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments.entrySet()) {
            String id = entry.getKey().getRegisteredName().toLowerCase();
            int level = entry.getIntValue();
            if (id.contains("density")) {
                score += level * 30;
            } else if (id.contains("breach")) {
                score += level * 25;
            } else if (id.contains("wind_burst")) {
                score += level * 20;
            }
        }
        return score;
    }

    /**
     * DrawEvent (Strictly on DrawEvent.Layer.GAME, between ticks BEFORE Minecraft.tick()):
     * - Updates Legit PD and Strict camera rotation ONLY here so that inside Minecraft.tick(),
     *   handleKeybinds(), LocalPlayer.aiStep() (Entity.moveRelative), and LocalPlayer.sendPosition()
     *   all use the exact same (yRot, xRot) -> zero Simulation and zero BadPacketsJ flags!
     */
    @SubscribeEvent
    public void onDraw(DrawEvent event) {
        pollToggleBindCapture();

        if (!isEnabled() || event.getLayer() != DrawEvent.Layer.GAME) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || client.screen != null) {
            lastSmoothFrameNs = 0L;
            return;
        }

        if ("Silent".equalsIgnoreCase(aimMode.getValue())) {
            lastSmoothFrameNs = 0L;
            return;
        }

        trackGlideAndDiveWindow(client);

        if (isGroundedOrAscendingLockout(client) || !canEngageModule(client)) {
            resetKinematicAimState();
            return;
        }

        if (target == null || !isEntityAllowedByTargetSelection(client, target) || !ActionManager.hasLineOfSight(client, target)) {
            findTargetFromPreFilteredCache(client);
        }

        if (target == null
                || !isEntityAllowedByTargetSelection(client, target)
                || !shouldAimAtTarget(client)) {
            resetKinematicAimState();
            return;
        }

        long nowNs = System.nanoTime();
        double frameTicks = lastSmoothFrameNs == 0L
                ? (1.0D / 3.0D)
                : Mth.clamp((nowNs - lastSmoothFrameNs) / 50_000_000.0D, 0.05D, 1.00D);
        lastSmoothFrameNs = nowNs;

        if ("Strict".equalsIgnoreCase(aimMode.getValue())) {
            suspendSilentRotation();
            applyStrictCenterLock(client, target);
        } else {
            suspendSilentRotation();
            applyLegitPdSmoothAim(client, target, frameTicks);
        }
    }

    @SubscribeEvent
    public void onRotationUpdate(RotationUpdateEvent event) {
        if (!isEnabled() || event.getPhase() != EventPhase.PRE) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            resetAllStates();
            return;
        }

        checkWorldOrRespawnChange(client);
        ActionManager.getInstance().ensureTickSynchronized(client);

        if (!client.player.isAlive() || client.player.getHealth() <= 0.0F || client.screen != null || isPlayerInCobweb(client)) {
            if (smashState != SmashSequenceState.IDLE) {
                executeSafeLegitSwapBack(client);
                smashState = SmashSequenceState.IDLE;
            }
            CombatStateManager.releaseMaceSmashLock();
            releaseCameraAndClearTargetLock();
            return;
        }

        trackGlideAndDiveWindow(client);
        updateAirbornePeak(client);
        updateDynamicMaceCacheAt4Hz(client, false);

        if (client.player.onGround()) {
            // NEVER swap back on the same tick an attack was committed (prevents 0-tick swap-attack-swap kick!)
            if (smashState == SmashSequenceState.STEP_2_SWAPBACK_RESTORE) {
                if (ActionManager.getInstance().getClientTick() > stateCommittedTick) {
                    executeSafeLegitSwapBack(client);
                    smashState = SmashSequenceState.IDLE;
                    CombatStateManager.releaseMaceSmashLock();
                }
                return;
            }
            // Allow up to 3 grounded ticks right after an Elytra/Rocket unequip to finish hitting a standing enemy!
            if (isInMidOrPostUnequipSmash(client) && groundedAfterDiveTicks <= 3) {
                return;
            }
            if (smashState != SmashSequenceState.IDLE && ActionManager.getInstance().getClientTick() > stateCommittedTick) {
                executeSafeLegitSwapBack(client);
                smashState = SmashSequenceState.IDLE;
            }
            postUnequipSmashEndTick = -100;
            lastActiveGlideDiveTick = -100;
            CombatStateManager.releaseMaceSmashLock();
            releaseCameraAndClearTargetLock();
            return;
        }

        if (!canEngageModule(client)) {
            if (smashState != SmashSequenceState.IDLE && ActionManager.getInstance().getClientTick() > stateCommittedTick) {
                executeSafeLegitSwapBack(client);
                smashState = SmashSequenceState.IDLE;
            }
            CombatStateManager.releaseMaceSmashLock();
            releaseCameraAndClearTargetLock();
            return;
        }

        if (lastTargetScanTick != client.player.tickCount) {
            lastTargetScanTick = client.player.tickCount;
            findTargetFromPreFilteredCache(client);
        }

        if (target == null || !isEntityAllowedByTargetSelection(client, target) || !ActionManager.hasLineOfSight(client, target)) {
            if (smashState != SmashSequenceState.IDLE) {
                transitionToSwapBackRestore(client);
            } else {
                CombatStateManager.releaseMaceSmashLock();
            }
            releaseCameraAndClearTargetLock();
            return;
        }

        if ("Silent".equalsIgnoreCase(aimMode.getValue()) && shouldAimAtTarget(client)) {
            if (isRightClickUseOrSwapActive(client)) {
                suspendSilentRotation();
            } else {
                Angle exactCenterAngle = computeExactCenterAimAngle(client, target);
                queueSilentServerRotation(exactCenterAngle);
            }
        } else {
            suspendSilentRotation();
        }
    }

    @SubscribeEvent
    public void onPlayerTravel(PlayerTravelEvent event) {
        if (!isEnabled()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            CombatStateManager.releaseMaceSmashLock();
            resetAllStates();
            return;
        }

        checkWorldOrRespawnChange(client);
        ActionManager.getInstance().ensureTickSynchronized(client);
        CombatStateManager.checkStaleMaceLock();

        if (!client.player.isAlive() || client.player.getHealth() <= 0.0F || client.screen != null) {
            if (smashState != SmashSequenceState.IDLE) {
                executeSafeLegitSwapBack(client);
                smashState = SmashSequenceState.IDLE;
            }
            CombatStateManager.releaseMaceSmashLock();
            releaseCameraAndClearTargetLock();
            return;
        }

        trackGlideAndDiveWindow(client);
        updateAirbornePeak(client);
        updateDynamicMaceCacheAt4Hz(client, false);

        if (client.player.onGround()) {
            groundedAfterDiveTicks++;
            // 1. If waiting to restore slot after a strike, NEVER restore on the same tick as the attack!
            if (smashState == SmashSequenceState.STEP_2_SWAPBACK_RESTORE) {
                if (ActionManager.getInstance().getClientTick() > stateCommittedTick) {
                    executeSafeLegitSwapBack(client);
                    smashState = SmashSequenceState.IDLE;
                    postUnequipSmashEndTick = -100;
                    lastActiveGlideDiveTick = -100;
                    CombatStateManager.releaseMaceSmashLock();
                    releaseCameraAndClearTargetLock();
                }
                return;
            }
            // 2. If we just unequipped Elytra for a Rocket/Elytra Mace and touched the ground right next to a
            //    standing enemy (groundedAfterDiveTicks <= 3), complete the Mace strike before resetting!
            if (isInMidOrPostUnequipSmash(client) && groundedAfterDiveTicks <= 3) {
                if (smashState != SmashSequenceState.IDLE) {
                    processSmashSequenceStateMachine(client);
                    return;
                }
                if (target == null || !isEntityAllowedByTargetSelection(client, target)) {
                    findTargetFromPreFilteredCache(client);
                }
                if (target != null && isEntityAllowedByTargetSelection(client, target) && !isWearingElytra(client)
                        && !ActionManager.getInstance().isSwapInProgress()
                        && getPrecisionDistanceToTarget(client, target) <= swingRange.getValue()
                        && cooldownTimer.finished(cooldownMs.getValue())) {
                    initiateSmashSequence(client, target);
                    return;
                }
                if (groundedAfterDiveTicks <= 2) {
                    return;
                }
            }
            if (smashState != SmashSequenceState.IDLE && ActionManager.getInstance().getClientTick() > stateCommittedTick) {
                executeSafeLegitSwapBack(client);
                smashState = SmashSequenceState.IDLE;
            }
            postUnequipSmashEndTick = -100;
            lastActiveGlideDiveTick = -100;
            CombatStateManager.releaseMaceSmashLock();
            releaseCameraAndClearTargetLock();
            return;
        } else {
            groundedAfterDiveTicks = 0;
        }

        if (ActionManager.getInstance().isBlockedByHigherPriority(ActionManager.ActionPriority.AUTO_MACE_STRIKE)) {
            return;
        }

        if (smashState != SmashSequenceState.IDLE) {
            processSmashSequenceStateMachine(client);
            return;
        }

        if (!canEngageModule(client)) {
            CombatStateManager.releaseMaceSmashLock();
            releaseCameraAndClearTargetLock();
            return;
        }

        if (lastTargetScanTick != client.player.tickCount) {
            lastTargetScanTick = client.player.tickCount;
            findTargetFromPreFilteredCache(client);
        }

        if (target == null || !isEntityAllowedByTargetSelection(client, target) || !ActionManager.hasLineOfSight(client, target)) {
            CombatStateManager.releaseMaceSmashLock();
            releaseCameraAndClearTargetLock();
            return;
        }

        // Priority 1 Check: Respect AutoElytra's exact Mace Pre-Hit Range (2.7m) and Height Range (6.0m) / Height Diff (7.0m)
        double distToTarget = getPrecisionDistanceToTarget(client, target);
        double predictedDist = getPredictedDistanceToTarget(client, target, 1.2D);
        if (isWearingElytra(client)) {
            ElytraTarget autoElytra = ElytraTarget.getInstance();
            if (autoElytra != null && autoElytra.isEnabled()) {
                if (autoElytra.shouldTriggerPriority1Unequip(target)) {
                    yieldToPriority1AutoElytraUnequip(client);
                }
            } else {
                boolean genuineDive = isInElytraOrRocketDive(client)
                        || (client.player.getDeltaMovement().y < -0.12D
                        && getEffectiveFallDistance(client) >= minFallDist.getValue());
                if (genuineDive && distToTarget <= 2.7D) {
                    yieldToPriority1AutoElytraUnequip(client);
                }
            }
            return;
        }

        if (ActionManager.getInstance().isSwapInProgress() || isManualInputBlockingSwap(client)) {
            return;
        }

        // Mace Cooldown Swap for Maximum Damage:
        // Do NOT switch to the Mace early in mid-air! Keep the player's current slot (Sword / Hand / Wind Charge)
        // until the exact tick the target is within swingRange (3.0m), then swap to Mace and attack immediately!
        if (isFallingForSmash(client) && cooldownTimer.finished(cooldownMs.getValue())) {
            if (distToTarget <= swingRange.getValue()) {
                initiateSmashSequence(client, target);
            }
        }
    }

    /**
     * Tracks ONLY genuine Elytra / Firework Rocket flight dives (mc.player.isFallFlying() == true),
     * NEVER normal ground jumps while merely wearing an Elytra!
     */
    /**
     * Checks whether the player is intentionally flying TOWARD the target (not flying away or climbing into the sky).
     */
    public boolean isGlidingTowardTarget(Minecraft client, LivingEntity candidate) {
        if (client.player == null || candidate == null) {
            return false;
        }
        Vec3 vel = client.player.getDeltaMovement();
        double dist = getPrecisionDistanceToTarget(client, candidate);

        // Always allow targeting when within close strike/pre-hit range (<= 3.8m) unless pitching steeply into the sky
        if (dist <= swingRange.getValue() + 0.85D) {
            return !(client.player.getXRot() < -25.0F && vel.y > 0.15D);
        }

        // If pitching upward into the sky to rocket away, NEVER lock aim!
        if (client.player.getXRot() < -15.0F && vel.y > 0.06D) {
            return false;
        }

        Vec3 eyePos = client.player.getEyePosition(1.0F);
        Vec3 targetCenter = getExactBoundingBoxCenter(candidate.getBoundingBox());
        Vec3 toTarget = targetCenter.subtract(eyePos);
        double centerDist = toTarget.length();
        if (centerDist < 1.0E-4D) {
            return true;
        }
        Vec3 unitToTarget = toTarget.scale(1.0D / centerDist);

        // Check horizontal look & velocity direction so looking horizontally while a standing enemy is below you
        // NEVER drops target lock, while turning >75 deg horizontally away lets you fly away freely!
        Vec3 horizToTarget = new Vec3(toTarget.x, 0.0D, toTarget.z);
        double horizDist = horizToTarget.length();
        if (horizDist > 0.25D) {
            Vec3 unitHorizToTarget = horizToTarget.scale(1.0D / horizDist);
            Vec3 lookVec = client.player.getViewVector(1.0F);
            Vec3 horizLook = new Vec3(lookVec.x, 0.0D, lookVec.z);
            if (horizLook.lengthSqr() > 1.0E-4D) {
                double horizLookDot = horizLook.normalize().dot(unitHorizToTarget);
                if (horizLookDot < 0.20D) {
                    return false;
                }
            }
            Vec3 horizVel = new Vec3(vel.x, 0.0D, vel.z);
            if (horizVel.lengthSqr() > 0.01D) {
                double horizClosing = horizVel.dot(unitHorizToTarget);
                if (horizClosing < -0.05D) {
                    return false;
                }
            }
        }

        double closingSpeed = vel.dot(unitToTarget);
        return closingSpeed > -0.02D || vel.y < -0.08D;
    }

    private void trackGlideAndDiveWindow(Minecraft client) {
        if (client.player == null) {
            return;
        }
        if (client.player.isInWater() || client.player.isInLava() || isPlayerInCobweb(client)) {
            lastActiveGlideDiveTick = -100;
            postUnequipSmashEndTick = -100;
            return;
        }
        if (client.player.isFallFlying()) {
            Vec3 vel = client.player.getDeltaMovement();
            boolean climbingAway = client.player.getXRot() < -15.0F && vel.y > 0.06D;
            if (!climbingAway && (vel.length() >= 0.35D || getEffectiveFallDistance(client) >= 0.5D)) {
                lastActiveGlideDiveTick = ActionManager.getInstance().getClientTick();
            }
        } else if (!client.player.onGround() && client.player.getDeltaMovement().y < -0.08D
                && getEffectiveFallDistance(client) >= Math.max(MIN_NON_GLIDE_FALL_BLOCKS, minFallDist.getValue())) {
            lastActiveGlideDiveTick = ActionManager.getInstance().getClientTick();
        }
    }

    private boolean isInElytraOrRocketDive(Minecraft client) {
        if (client.player == null) {
            return false;
        }
        // Once the Elytra has been unequipped for a smash, stay active for the full post-unequip smash window!
        if (isInMidOrPostUnequipSmash(client)) {
            return true;
        }
        if (client.player.onGround()) {
            return false;
        }
        if (client.player.isFallFlying()) {
            if (target != null && !isGlidingTowardTarget(client, target)) {
                return false;
            }
            double speed = client.player.getDeltaMovement().length();
            double effectiveFall = getEffectiveFallDistance(client);
            double altOverTarget = target != null ? (client.player.getY() - target.getY()) : 0.0D;
            return speed >= 0.35D || effectiveFall >= minFallDist.getValue() || altOverTarget >= minFallDist.getValue();
        }
        return !isWearingElytra(client) && (ActionManager.getInstance().getClientTick() - lastActiveGlideDiveTick) <= 25;
    }

    private boolean isRightClickUseOrSwapActive(Minecraft client) {
        if (client == null || client.player == null) {
            return false;
        }
        return ActionManager.getInstance().isSwapInProgress()
                || client.player.isUsingItem()
                || (client.options != null && client.options.keyUse.isDown());
    }

    private void preSelectWeaponBeforeStrikeZone(Minecraft client, LivingEntity targetEntity) {
        int maceSlot = resolveMaceSlot(client);
        if (maceSlot == -1) {
            return;
        }
        int currentSlot = client.player.getInventory().getSelectedSlot();
        int desiredSlot = (shouldTriggerStunSlam(client, targetEntity) && cachedAxeSlot != -1)
                ? cachedAxeSlot
                : maceSlot;

        if (currentSlot != desiredSlot) {
            if (savedHotbarSlot == -1) {
                savedHotbarSlot = currentSlot;
            }
            targetMaceSlot = maceSlot;
            selectHotbarSlotLegit(client, desiredSlot);
        }
    }

    private void yieldToPriority1AutoElytraUnequip(Minecraft client) {
        CombatStateManager.releaseMaceSmashLock();
        ElytraTarget autoElytra = ElytraTarget.getInstance();
        if (autoElytra != null && autoElytra.isEnabled()) {
            autoElytra.ensureUnequippedBeforeMaceAttack(target);
            return;
        }

        if (client != null && client.player != null && !ActionManager.getInstance().isSwapInProgress()) {
            int chestSlot = findHotbarChestplateFallback(client);
            if (chestSlot != -1) {
                int currentSlot = client.player.getInventory().getSelectedSlot();
                int nextSlot = prepareDirectMaceHandoffSlot(client, currentSlot, target);
                ActionManager.getInstance().submitFastTwoTickUnequip(
                        client,
                        chestSlot,
                        nextSlot,
                        clickSim.getValue()
                );
            }
        }
    }

    private int findHotbarChestplateFallback(Minecraft client) {
        int bestSlot = -1;
        int bestScore = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = client.player.getInventory().getItem(i);
            if (s.isEmpty() || s.is(Items.ELYTRA)) {
                continue;
            }
            if (s.is(ItemTags.CHEST_ARMOR) || ActionManager.CHESTPLATES.contains(s.getItem())) {
                int score = ActionManager.getChestScore(s.getItem());
                if (score > bestScore) {
                    bestScore = score;
                    bestSlot = i;
                }
            }
        }
        return bestSlot;
    }

    /**
     * True 1-Tick Mace Cooldown Swap Execution:
     * Keeps the player on their current weapon/hand slot (preserving 100% charged attack cooldown)
     * until verifyStrict3mAttackRaycast confirms the crosshair is on the target hitbox right now.
     * Only on the exact tick the attack will fire does it switch to the Mace and strike immediately!
     */
    private void initiateSmashSequence(Minecraft client, LivingEntity targetEntity) {
        if (isWearingElytra(client)) {
            yieldToPriority1AutoElytraUnequip(client);
            return;
        }

        int maceSlot = resolveMaceSlot(client);
        int currentSlot = client.player.getInventory().getSelectedSlot();
        boolean holdingMace = isMaceStack(client.player.getMainHandItem());

        if (!holdingMace && (!autoSwitch.getValue() || maceSlot == -1)) {
            return;
        }

        Vec3 activeLookVec = "Silent".equalsIgnoreCase(aimMode.getValue()) && AngleConnection.INSTANCE.getCurrentAngle() != null
                ? AngleConnection.INSTANCE.getCurrentAngle().toVector()
                : client.player.getViewVector(1.0F);

        // Wait to swap until the crosshair raycast is confirmed on the target hitbox so we never waste
        // weapon cooldown holding the Mace in mid-air before the hit!
        if (!ActionManager.verifyStrict3mAttackRaycast(client, targetEntity, swingRange.getValue(), activeLookVec)) {
            return;
        }
        if (!ActionManager.getInstance().canDispatchInteraction(client, ActionManager.ActionPriority.AUTO_MACE_STRIKE)) {
            return;
        }

        CombatStateManager.acquireMaceSmashLock();
        if (savedHotbarSlot == -1) {
            savedHotbarSlot = currentSlot;
        }
        targetMaceSlot = holdingMace ? currentSlot : maceSlot;

        boolean useAxeStunFirst = shouldTriggerStunSlam(client, targetEntity);
        if (useAxeStunFirst && cachedAxeSlot != -1) {
            selectHotbarSlotLegit(client, cachedAxeSlot);
            if (performThrottledAttack(client, targetEntity)) {
                stateCommittedTick = ActionManager.getInstance().getClientTick();
                stateTickTimer.reset();
                smashState = SmashSequenceState.STEP_2_SELECT_MACE_AFTER_STUN;
            } else {
                stateCommittedTick = ActionManager.getInstance().getClientTick();
                stateTickTimer.reset();
                smashState = SmashSequenceState.STEP_1_AXE_STUN_ATTACK;
            }
            return;
        }

        // Swap to Mace on the exact hit tick for maximum Mace Cooldown Swap damage!
        if (!holdingMace && targetMaceSlot != -1) {
            selectHotbarSlotLegit(client, targetMaceSlot);
        }

        if (maceSwapDelayMs.getValue() <= 0.0D || holdingMace || isInElytraOrRocketDive(client)) {
            if (performThrottledAttack(client, targetEntity)) {
                cooldownTimer.reset();
                transitionToSwapBackRestore(client);
                return;
            }
        }

        stateCommittedTick = ActionManager.getInstance().getClientTick();
        stateTickTimer.reset();
        smashState = SmashSequenceState.STEP_1_MACE_SMASH_ATTACK;
    }

    /**
     * Runs strictly at START_CLIENT_TICK (before handleKeybinds & LocalPlayer.aiStep) when the player is
     * already inside <= 3.2m strike range of an enemy and the camera ray is slightly outside the hitbox.
     * Steps the camera onto the hitbox within a safe <= 34 deg GCD-quantized turn so the hit never misses!
     */
    private void alignCameraOntoHitboxForStrike(Minecraft client, LivingEntity targetEntity) {
        if (client.player == null || targetEntity == null) {
            return;
        }
        Angle centerAngle = computeExactCenterAimAngle(client, targetEntity);
        float currentYaw = client.player.getYRot();
        float currentPitch = client.player.getXRot();
        float yawDelta = Mth.wrapDegrees(centerAngle.getYaw() - currentYaw);
        float pitchDelta = centerAngle.getPitch() - currentPitch;
        double angularDist = Math.hypot(yawDelta, pitchDelta);
        if (angularDist > 85.0D || angularDist < 0.02D) {
            return;
        }

        double maxSnapDeg = 24.0D;
        double scale = angularDist > maxSnapDeg ? (maxSnapDeg / angularDist) : 0.95D;
        float nextYaw = currentYaw + (float) (yawDelta * scale);
        float nextPitch = Mth.clamp(currentPitch + (float) (pitchDelta * scale), -89.5F, 89.5F);

        double gcd = MathUtils.computeGcd();
        if (gcd > 0.0D) {
            float g = (float) gcd;
            nextYaw = currentYaw + Math.round((nextYaw - currentYaw) / g) * g;
            nextPitch = Mth.clamp(currentPitch + Math.round((nextPitch - currentPitch) / g) * g, -89.5F, 89.5F);
        }

        client.player.setYRot(nextYaw);
        client.player.setXRot(nextPitch);
        hitboxLocked = true;
        hitboxLockedTargetId = targetEntity.getId();
    }

    private void applyStrictCenterLock(Minecraft client, LivingEntity targetEntity) {
        Angle currentCamera = MathAngle.cameraAngle();
        if (isCrosshairInsideInner20PercentDeadzone(client, targetEntity, currentCamera)) {
            return;
        }
        Angle centerAngle = computeExactCenterAimAngle(client, targetEntity);
        float currentYaw = client.player.getYRot();
        float currentPitch = client.player.getXRot();
        float yawDelta = Mth.wrapDegrees(centerAngle.getYaw() - currentYaw);
        float pitchDelta = centerAngle.getPitch() - currentPitch;

        float nextYaw = currentYaw + yawDelta;
        float nextPitch = Mth.clamp(currentPitch + pitchDelta, -89.5F, 89.5F);

        double gcd = MathUtils.computeGcd();
        if (gcd > 0.0D) {
            nextYaw = currentYaw + Math.round((nextYaw - currentYaw) / (float) gcd) * (float) gcd;
            nextPitch = Mth.clamp(currentPitch + Math.round((nextPitch - currentPitch) / (float) gcd) * (float) gcd, -89.5F, 89.5F);
        }

        client.player.setYRot(nextYaw);
        client.player.setXRot(nextPitch);
    }

    /**
     * Zero-Overshoot PD Aim with Hard Hitbox Lock:
     * 1. Zero Inertia Overshoot: Never carries momentum past the target center, so the crosshair
     *    NEVER slides through or away from the enemy's hitbox.
     * 2. Hard Hitbox Containment: Once the crosshair ray enters the enemy's bounding box, it is
     *    clamped inside the enemy's safe inner core so your falling/diving velocity can never pull
     *    the crosshair off a standing enemy's hitbox.
     */
    private void applyLegitPdSmoothAim(Minecraft client, LivingEntity targetEntity, double tickFraction) {
        if (hitboxLockedTargetId != targetEntity.getId()) {
            hitboxLocked = false;
            hitboxLockedTargetId = targetEntity.getId();
        }

        Vec3 eyePos = client.player.getEyePosition(1.0F);
        AABB fullBox = targetEntity.getBoundingBox();
        Angle currentCamera = MathAngle.cameraAngle();
        float currentYaw = client.player.getYRot();
        float currentPitch = client.player.getXRot();
        double gcd = MathUtils.computeGcd();
        float gcdStep = gcd > 0.0D ? (float) gcd : 0.05F;

        Angle centerAngle = computeExactCenterAimAngle(client, targetEntity);
        double yawError = Mth.wrapDegrees(centerAngle.getYaw() - currentYaw);
        double pitchError = centerAngle.getPitch() - currentPitch;
        double angularDistance = Math.hypot(yawError, pitchError);

        boolean currentlyInsideBox = doesRayIntersectBox(eyePos, currentCamera, fullBox, aimRange.getValue() + 2.0D);
        if (currentlyInsideBox) {
            hitboxLocked = true;
        }

        // Inside the inner 20% center deadzone: NEVER drift away!
        // Only apply a gentle center-keeping correction when falling/moving close to the enemy so parallax never slides it out.
        if (isCrosshairInsideInner20PercentDeadzone(client, targetEntity, currentCamera)) {
            hasPrevError = false;
            prevYawError = 0.0D;
            prevPitchError = 0.0D;
            if (angularDistance > 0.8D && getPrecisionDistanceToTarget(client, targetEntity) <= 5.0D) {
                double holdScale = Mth.clamp(tickFraction * 0.45D, 0.10D, 0.50D);
                float holdYaw = currentYaw + (float) (yawError * holdScale);
                float holdPitch = Mth.clamp(currentPitch + (float) (pitchError * holdScale), -89.5F, 89.5F);
                if (gcd > 0.0D) {
                    holdYaw = currentYaw + Math.round((holdYaw - currentYaw) / gcdStep) * gcdStep;
                    holdPitch = Mth.clamp(currentPitch + Math.round((holdPitch - currentPitch) / gcdStep) * gcdStep, -89.5F, 89.5F);
                }
                client.player.setYRot(holdYaw);
                client.player.setXRot(holdPitch);
            }
            return;
        }

        if (angularDistance < 0.04D) {
            return;
        }

        // Anti-180 Whip Guard: Only skip if we already flew past the enemy (> 105 deg behind us) and are moving away
        if (angularDistance > 105.0D) {
            Vec3 toTarget = getExactBoundingBoxCenter(fullBox).subtract(eyePos).normalize();
            double closingSpeed = client.player.getDeltaMovement().dot(toTarget);
            if (closingSpeed < -0.05D && getPrecisionDistanceToTarget(client, targetEntity) > 2.8D) {
                resetKinematicAimState();
                return;
            }
        }

        double distToTarget = getPrecisionDistanceToTarget(client, targetEntity);
        boolean isCloseStrikeWindow = distToTarget <= 4.5D;

        double userSpeedRatio = Mth.clamp(aimSpeed.getValue() / 50.0D, 0.35D, 1.50D);
        double maxSpeedPerTick = Mth.lerp(
                Mth.clamp(angularDistance / 40.0D, 0.0D, 1.0D),
                MIN_FAR_ROTATION_DEG_PER_TICK,
                isCloseStrikeWindow ? 64.0D : MAX_FAR_ROTATION_DEG_PER_TICK
        ) * userSpeedRatio;

        double distNorm = Mth.clamp(angularDistance / PD_DECELERATION_CONE_DEGREES, isCloseStrikeWindow ? 0.35D : 0.14D, 1.0D);
        double smoothDecelCurve = distNorm * (2.0D - distNorm);
        // When close (<= 4.5m), keep tracking responsive enough to counter our own falling/diving speed so aim never slides off!
        double hitboxProximityScale = currentlyInsideBox ? (isCloseStrikeWindow ? 0.65D : 0.45D) : 1.0D;

        double smoothFactor = Mth.clamp(aimSmooth.getValue() / 10.0D, 0.0D, 0.85D);
        double kp = Mth.clamp((0.72D * smoothDecelCurve * hitboxProximityScale) * (1.0D - smoothFactor * 0.45D), 0.22D, 0.88D);
        double kd = 0.16D + smoothFactor * 0.14D;

        double dYaw = hasPrevError ? (yawError - prevYawError) : 0.0D;
        double dPitch = hasPrevError ? (pitchError - prevPitchError) : 0.0D;
        prevYawError = yawError;
        prevPitchError = pitchError;
        hasPrevError = true;

        // Only allow derivative term to DAMP movement, never amplify or push opposite to error!
        double pdYaw = kp * yawError;
        if (dYaw * yawError < 0.0D) {
            pdYaw += kd * dYaw * 0.5D;
            if (pdYaw * yawError < 0.0D) {
                pdYaw = kp * yawError * 0.35D;
            }
        }
        double pdPitch = kp * pitchError;
        if (dPitch * pitchError < 0.0D) {
            pdPitch += kd * dPitch * 0.5D;
            if (pdPitch * pitchError < 0.0D) {
                pdPitch = kp * pitchError * 0.35D;
            }
        }

        double rawYawStep = pdYaw * (tickFraction * 2.2D);
        double rawPitchStep = pdPitch * (tickFraction * 2.2D);

        double maxStepThisFrame = maxSpeedPerTick * smoothDecelCurve * hitboxProximityScale * tickFraction;
        double yawWeight = Math.abs(yawError) / angularDistance;
        double pitchWeight = Math.abs(pitchError) / angularDistance;
        double maxYawStep = Math.max(0.35D, maxStepThisFrame * yawWeight);
        double maxPitchStep = Math.max(0.30D, maxStepThisFrame * pitchWeight);

        double clampedYawStep = Mth.clamp(rawYawStep, -maxYawStep, maxYawStep);
        double clampedPitchStep = Mth.clamp(rawPitchStep, -maxPitchStep, maxPitchStep);

        // Strict zero-overshoot clamp: step can NEVER exceed remaining error!
        if (Math.abs(clampedYawStep) > Math.abs(yawError)) {
            clampedYawStep = yawError;
        }
        if (Math.abs(clampedPitchStep) > Math.abs(pitchError)) {
            clampedPitchStep = pitchError;
        }

        float nextYaw = currentYaw + (float) clampedYawStep;
        float nextPitch = Mth.clamp(currentPitch + (float) clampedPitchStep, -89.5F, 89.5F);

        // Hard Hitbox Containment: If the crosshair was already inside the enemy hitbox (hitboxLocked),
        // never allow player fall/dive parallax to let the ray slide outside the shrunk hitbox!
        if (hitboxLocked) {
            AABB safeCoreBox = fullBox.deflate(
                    Math.min(0.10D, fullBox.getXsize() * 0.18D),
                    Math.min(0.12D, fullBox.getYsize() * 0.12D),
                    Math.min(0.10D, fullBox.getZsize() * 0.18D)
            );
            Angle candidateAngle = new Angle(nextYaw, nextPitch);
            if (!doesRayIntersectBox(eyePos, candidateAngle, safeCoreBox, aimRange.getValue() + 2.0D)) {
                nextYaw = currentYaw + (float) (yawError * 0.85D);
                nextPitch = Mth.clamp(currentPitch + (float) (pitchError * 0.85D), -89.5F, 89.5F);
            }
        }

        if (gcd > 0.0D) {
            nextYaw = currentYaw + Math.round((nextYaw - currentYaw) / gcdStep) * gcdStep;
            nextPitch = Mth.clamp(currentPitch + Math.round((nextPitch - currentPitch) / gcdStep) * gcdStep, -89.5F, 89.5F);
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
                box.minY + (box.getYsize() * 0.62D),
                (box.minZ + box.maxZ) * 0.5D
        );
    }

    private Angle computeExactCenterAimAngle(Minecraft client, LivingEntity entity) {
        Vec3 actualEye = client.player.getEyePosition(1.0F);
        AABB box = entity.getBoundingBox();
        Vec3 exactCenter = getExactBoundingBoxCenter(box);

        // If the target hitbox is already within swingRange (<= 3.0m), verify whether the ray to exactCenter
        // enters the hitbox within <= 3.0m; if we are steeply above the target so only their head/shoulders
        // are within <= 3.0m, aim at the closest core point inside the hitbox so the mid-air hit never misses!
        double maxReach = Math.min(3.0D, swingRange.getValue());
        if (getPrecisionDistanceToTarget(client, entity) <= maxReach) {
            Angle centerAngle = MathAngle.fromTo(actualEye, exactCenter).adjustSensitivity();
            if (ActionManager.verifyStrict3mAttackRaycast(client, entity, maxReach, centerAngle.toVector())) {
                return centerAngle;
            }
            AABB safeCore = box.deflate(
                    Math.min(0.12D, box.getXsize() * 0.20D),
                    Math.min(0.12D, box.getYsize() * 0.10D),
                    Math.min(0.12D, box.getZsize() * 0.20D)
            );
            Vec3 closestInCore = new Vec3(
                    Mth.clamp(actualEye.x, safeCore.minX, safeCore.maxX),
                    Mth.clamp(actualEye.y, safeCore.minY, safeCore.maxY),
                    Mth.clamp(actualEye.z, safeCore.minZ, safeCore.maxZ)
            );
            return MathAngle.fromTo(actualEye, closestInCore).adjustSensitivity();
        }

        return MathAngle.fromTo(actualEye, exactCenter).adjustSensitivity();
    }

    private void processSmashSequenceStateMachine(Minecraft client) {
        if (client.player == null) {
            CombatStateManager.releaseMaceSmashLock();
            smashState = SmashSequenceState.IDLE;
            return;
        }

        if (isWearingElytra(client)) {
            smashState = SmashSequenceState.IDLE;
            yieldToPriority1AutoElytraUnequip(client);
            return;
        }

        switch (smashState) {
            case STEP_1_AXE_STUN_ATTACK -> {
                if (target == null || !target.isAlive() || isWhitelistedFriend(client, target)) {
                    transitionToSwapBackRestore(client);
                    return;
                }
                double dist = getPrecisionDistanceToTarget(client, target);
                if (dist <= swingRange.getValue() && performThrottledAttack(client, target)) {
                    stateCommittedTick = ActionManager.getInstance().getClientTick();
                    stateTickTimer.reset();
                    smashState = SmashSequenceState.STEP_2_SELECT_MACE_AFTER_STUN;
                } else if (client.player.onGround() || stateTickTimer.finished(200.0D)) {
                    transitionToSwapBackRestore(client);
                }
            }

            case STEP_2_SELECT_MACE_AFTER_STUN -> {
                if (target == null || !target.isAlive() || isWhitelistedFriend(client, target)) {
                    transitionToSwapBackRestore(client);
                    return;
                }
                int maceSlot = targetMaceSlot != -1 ? targetMaceSlot : cachedMaceSlot;
                if (maceSlot != -1) {
                    selectHotbarSlotLegit(client, maceSlot);
                }
                double dist = getPrecisionDistanceToTarget(client, target);
                if (dist <= swingRange.getValue() && performThrottledAttack(client, target)) {
                    cooldownTimer.reset();
                    transitionToSwapBackRestore(client);
                } else {
                    stateCommittedTick = ActionManager.getInstance().getClientTick();
                    stateTickTimer.reset();
                    smashState = SmashSequenceState.STEP_1_MACE_SMASH_ATTACK;
                }
            }

            case STEP_1_MACE_SMASH_ATTACK -> {
                if (target == null || !target.isAlive() || isWhitelistedFriend(client, target)) {
                    transitionToSwapBackRestore(client);
                    return;
                }
                int maceSlot = targetMaceSlot != -1 ? targetMaceSlot : cachedMaceSlot;
                if (maceSlot != -1 && !isMaceStack(client.player.getMainHandItem())) {
                    selectHotbarSlotLegit(client, maceSlot);
                }
                double requiredDelayMs = isInElytraOrRocketDive(client) ? 0.0D : maceSwapDelayMs.getValue();
                if (requiredDelayMs > 0.0D && !stateTickTimer.finished(requiredDelayMs)) {
                    return;
                }
                double dist = getPrecisionDistanceToTarget(client, target);
                if (dist <= swingRange.getValue() && performThrottledAttack(client, target)) {
                    cooldownTimer.reset();
                    transitionToSwapBackRestore(client);
                } else if (client.player.onGround() || stateTickTimer.finished(250.0D)) {
                    transitionToSwapBackRestore(client);
                }
            }

            case STEP_2_SWAPBACK_RESTORE -> {
                if (ActionManager.getInstance().getClientTick() <= stateCommittedTick) {
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
        postUnequipSmashEndTick = -100;
        lastActiveGlideDiveTick = -100;
        suspendSilentRotation();
        resetKinematicAimState();
        ElytraTarget autoElytra = ElytraTarget.getInstance();
        if (autoElytra != null && autoElytra.isEnabled()) {
            autoElytra.enterState3RecoveryLock();
        }
        if (!swapBack.getValue()) {
            savedHotbarSlot = -1;
            targetMaceSlot = -1;
            smashState = SmashSequenceState.IDLE;
            CombatStateManager.releaseMaceSmashLock();
            return;
        }
        stateCommittedTick = ActionManager.getInstance().getClientTick();
        stateTickTimer.reset();
        smashState = SmashSequenceState.STEP_2_SWAPBACK_RESTORE;
    }

    private void executeSafeLegitSwapBack(Minecraft client) {
        if (client == null || client.player == null || client.player.isUsingItem()) {
            savedHotbarSlot = -1;
            return;
        }

        int returnSlot = savedHotbarSlot >= 0 && savedHotbarSlot <= 8
                ? savedHotbarSlot
                : (cachedSwordSlot != -1 ? cachedSwordSlot : -1);

        if (returnSlot >= 0 && returnSlot <= 8) {
            selectHotbarSlotLegit(client, returnSlot);
        }
        savedHotbarSlot = -1;
        targetMaceSlot = -1;
    }

    private boolean performThrottledAttack(Minecraft client, LivingEntity targetEntity) {
        if (client.player == null || client.gameMode == null || targetEntity == null || client.screen != null) {
            return false;
        }
        if (isWhitelistedFriend(client, targetEntity)) {
            return false;
        }
        if (client.player.isUsingItem() || client.player.isFallFlying() || isWearingElytra(client)) {
            return false;
        }

        Vec3 activeLookVec = "Silent".equalsIgnoreCase(aimMode.getValue()) && AngleConnection.INSTANCE.getCurrentAngle() != null
                ? AngleConnection.INSTANCE.getCurrentAngle().toVector()
                : client.player.getViewVector(1.0F);

        if (!ActionManager.verifyStrict3mAttackRaycast(client, targetEntity, swingRange.getValue(), activeLookVec)) {
            return false;
        }
        if (!ActionManager.getInstance().canDispatchInteraction(client, ActionManager.ActionPriority.AUTO_MACE_STRIKE)) {
            return false;
        }

        client.gameMode.attack(client.player, targetEntity);
        client.player.swing(InteractionHand.MAIN_HAND);
        ActionManager.getInstance().recordInteractionDispatched(client);
        return true;
    }

    /**
     * Prevents aiming or attacking when grounded or ascending on a normal jump,
     * while allowing genuine Elytra / Rocket Mace dives.
     */
    // [ATTRIUM-COBWEB-LOCKOUT-FIX]
    private final net.minecraft.core.BlockPos.MutableBlockPos cobwebProbePos = new net.minecraft.core.BlockPos.MutableBlockPos();
    private int lastCobwebCheckTick = -1;
    private boolean cachedInCobweb = false;

    /**
     * Zero-allocation once-per-tick cobweb check across the player's bounding box.
     * Prevents endless dive/attack/raycast loops and FPS drops when stuck in cobwebs.
     */
    private boolean isPlayerInCobweb(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            return false;
        }
        int tick = client.player.tickCount;
        if (tick == lastCobwebCheckTick) {
            return cachedInCobweb;
        }
        lastCobwebCheckTick = tick;
        AABB box = client.player.getBoundingBox();
        int minX = Mth.floor(box.minX + 0.001D);
        int maxX = Mth.floor(box.maxX - 0.001D);
        int minY = Mth.floor(box.minY + 0.001D);
        int maxY = Mth.floor(box.maxY - 0.001D);
        int minZ = Mth.floor(box.minZ + 0.001D);
        int maxZ = Mth.floor(box.maxZ - 0.001D);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cobwebProbePos.set(x, y, z);
                    if (client.level.getBlockState(cobwebProbePos).is(net.minecraft.world.level.block.Blocks.COBWEB)) {
                        cachedInCobweb = true;
                        return true;
                    }
                }
            }
        }
        cachedInCobweb = false;
        return false;
    }

    private boolean isGroundedOrAscendingLockout(Minecraft client) {
        if (client.player == null) {
            return true;
        }
        if (client.player.isInWater()
                || client.player.isInLava()
                || client.player.getAbilities().flying
                || isPlayerInCobweb(client)) {
            return true;
        }
        // Never aim while ascending (e.g. Wind Burst upward launch or Rocket climb)
        if (client.player.getDeltaMovement().y >= -0.04D) {
            return true;
        }
        if (isInMidOrPostUnequipSmash(client) && (!client.player.onGround() || groundedAfterDiveTicks <= 3)) {
            return false;
        }
        if (client.player.onGround()) {
            return true;
        }
        if (isInElytraOrRocketDive(client)) {
            return false;
        }
        return client.player.getDeltaMovement().y >= -0.05D;
    }

    private void releaseCameraAndClearTargetLock() {
        suspendSilentRotation();
        resetKinematicAimState();
        target = null;
    }

    /**
     * Never engages on a normal 1.252-block jump!
     * Requires either an active Elytra/Rocket dive OR >= 1.60m fall distance.
     */
    private boolean canEngageModule(Minecraft client) {
        if (client.player == null || client.level == null || isGroundedOrAscendingLockout(client)) {
            return false;
        }
        if (onlyOnNonGlide.getValue() && client.player.isFallFlying()) {
            return false;
        }
        if (weaponOnly.getValue() && !isHoldingWeapon(client.player.getMainHandItem())) {
            return false;
        }
        if (smashState != SmashSequenceState.IDLE) {
            return true;
        }

        boolean holdingMace = isMaceStack(client.player.getMainHandItem());
        if (!holdingMace && cachedMaceSlot == -1) {
            updateDynamicMaceCacheAt4Hz(client, true);
            holdingMace = isMaceStack(client.player.getMainHandItem());
            if (!holdingMace && cachedMaceSlot == -1) {
                return false;
            }
        }

        if (isInElytraOrRocketDive(client)) {
            return true;
        }

        double minRequiredFall = Math.max(MIN_NON_GLIDE_FALL_BLOCKS, Math.max(minFallDist.getValue(), aimInAir.getValue()));
        return getEffectiveFallDistance(client) >= minRequiredFall;
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

    /**
     * Returns a bitmask of currently selected Target categories in AutoMace (Players=1, Mobs=2, Animals=4)
     * so ActionManager and AutoElytra stay 100% synchronized in real time when the user changes Targets.
     */
    public static int getTargetSelectionMask() {
        MaceTarget mod = getInstance();
        if (mod == null || mod.targets == null) {
            return 1; // Default: Players
        }
        int mask = 0;
        if (mod.targets.isSelected("Players")) mask |= 1;
        if (mod.targets.isSelected("Mobs")) mask |= 2;
        if (mod.targets.isSelected("Animals")) mask |= 4;
        if (mod.ignoreFriends != null && mod.ignoreFriends.getValue()) mask |= 8;
        return mask;
    }

    /**
     * Global synchronized target filter used by AutoMace, ActionManager, and AutoElytra.
     * Selecting Mobs or Animals in AutoMace automatically makes both AutoMace and AutoElytra work on them.
     */
    public static boolean isEntityAllowedByAutoMaceTargets(Minecraft client, LivingEntity living) {
        MaceTarget mod = getInstance();
        if (mod != null) {
            return mod.isEntityAllowedByTargetSelection(client, living);
        }
        if (client == null || client.player == null || living == null || living == client.player || !living.isAlive() || living.getHealth() <= 0.0F) {
            return false;
        }
        if (living instanceof ArmorStand) {
            return false;
        }
        if (living instanceof Player player) {
            return !AntiBot.shouldIgnore(player) && !ActionManager.isWhitelistedFriend(client, player);
        }
        return false;
    }

    /**
     * Evaluates whether a LivingEntity matches the user-selected Targets in AutoMace ("Players", "Mobs", "Animals").
     * Note: In Minecraft 1.21.11, Animal extends Mob, so Animal is checked BEFORE Mob.
     */
    public boolean isEntityAllowedByTargetSelection(Minecraft client, LivingEntity living) {
        if (client == null || client.player == null || living == null || living == client.player || !living.isAlive() || living.getHealth() <= 0.0F) {
            return false;
        }
        if (living instanceof ArmorStand) {
            return false;
        }
        if (living instanceof Player player) {
            if (!targets.isSelected("Players")) {
                return false;
            }
            if (AntiBot.shouldIgnore(player)) {
                return false;
            }
            return !isWhitelistedFriend(client, player);
        }
        if (living instanceof Animal) {
            return targets.isSelected("Animals");
        }
        if (living instanceof Mob) {
            return targets.isSelected("Mobs");
        }
        return false;
    }

    @SubscribeEvent
    public void onWorldRender(WorldRenderEvent event) {
        if (!isEnabled() || !targetEsp.getValue() || target == null || !target.isAlive()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || isGroundedOrAscendingLockout(client)) {
            return;
        }
        if (client.player.distanceToSqr(target) <= 4096.0D) {
            Render3D.drawBox(target.getBoundingBox(), TARGET_WIREFRAME_COLOR, 1.0F, true, false, false);
        }
    }

    private void resetKinematicAimState() {
        hasPrevError = false;
        prevYawError = 0.0D;
        prevPitchError = 0.0D;
        hitboxLocked = false;
        hitboxLockedTargetId = -1;
        lastSmoothFrameNs = 0L;
    }

    private void updateAirbornePeak(Minecraft client) {
        if (client.player == null) {
            airbornePeakY = Double.NaN;
            return;
        }
        if (client.player.onGround() || client.player.isInWater() || client.player.isInLava() || isPlayerInCobweb(client)) {
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
        if (target == null || client.player == null || isGroundedOrAscendingLockout(client) || isWhitelistedFriend(client, target)) {
            return false;
        }
        if (!ActionManager.hasLineOfSight(client, target)) {
            return false;
        }
        if (getPrecisionDistanceToTarget(client, target) > aimRange.getValue()) {
            return false;
        }
        // If actively gliding with an Elytra, ONLY aim when genuinely diving toward the target (never when flying away!)
        if (client.player.isFallFlying()) {
            return isGlidingTowardTarget(client, target);
        }
        if (isInMidOrPostUnequipSmash(client) || isInElytraOrRocketDive(client)) {
            return true;
        }
        double requiredFall = Math.max(minFallDist.getValue(), aimInAir.getValue());
        return getEffectiveFallDistance(client) >= requiredFall;
    }

    private boolean isFallingForSmash(Minecraft client) {
        if (client.player == null || isGroundedOrAscendingLockout(client)) {
            return false;
        }
        if (onlyOnNonGlide.getValue() && client.player.isFallFlying()) {
            return false;
        }
        if (isInElytraOrRocketDive(client)) {
            return true;
        }
        return getEffectiveFallDistance(client) >= Math.max(MIN_NON_GLIDE_FALL_BLOCKS, minFallDist.getValue());
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

    private boolean shouldTriggerStunSlam(Minecraft client, LivingEntity targetEntity) {
        if (!stunSlam.getValue()) {
            return false;
        }
        boolean blocking = isTargetBlockingWithShield(targetEntity);
        if (properStunslam.getValue()) {
            return blocking && isTargetFacingPlayer(client, targetEntity);
        }
        return blocking;
    }

    private boolean isTargetBlockingWithShield(LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        if (entity.isBlocking()) {
            return true;
        }
        return entity.isUsingItem() && entity.getUseItem().getItem() == Items.SHIELD;
    }

    private boolean isTargetFacingPlayer(Minecraft client, LivingEntity targetEntity) {
        Vec3 toPlayer = client.player.position().subtract(targetEntity.position());
        toPlayer = new Vec3(toPlayer.x, 0.0D, toPlayer.z).normalize();
        Vec3 targetLook = targetEntity.getViewVector(1.0F);
        targetLook = new Vec3(targetLook.x, 0.0D, targetLook.z).normalize();
        return toPlayer.dot(targetLook) > 0.0D;
    }

    private int resolveMaceSlot(Minecraft client) {
        if (cachedMaceSlot >= 0 && cachedMaceSlot <= 8 && client.player != null) {
            if (isMaceStack(client.player.getInventory().getItem(cachedMaceSlot))) {
                return cachedMaceSlot;
            }
        }
        updateDynamicMaceCacheAt4Hz(client, true);
        return cachedMaceSlot;
    }

    private boolean isWearingElytra(Minecraft client) {
        if (client == null || client.player == null) {
            return false;
        }
        return InventoryUtils.isElytraEquipped()
                || client.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
    }

    private boolean isMaceStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.getItem() == Items.MACE;
    }

    private boolean isHoldingWeapon(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return isMaceStack(stack)
                || stack.getItem() instanceof AxeItem
                || stack.getItem() instanceof TridentItem
                || stack.is(ItemTags.SWORDS)
                || stack.is(ItemTags.AXES);
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

    private double getPredictedDistanceToTarget(Minecraft client, LivingEntity entity, double leadTicks) {
        Vec3 eye = client.player.getEyePosition(1.0F).add(client.player.getDeltaMovement().scale(leadTicks));
        AABB box = entity.getBoundingBox();
        double dx = eye.x - Mth.clamp(eye.x, box.minX, box.maxX);
        double dy = eye.y - Mth.clamp(eye.y, box.minY, box.maxY);
        double dz = eye.z - Mth.clamp(eye.z, box.minZ, box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void findTargetFromPreFilteredCache(Minecraft client) {
        if (isGroundedOrAscendingLockout(client)) {
            target = null;
            return;
        }

        // Preserve locked target throughout the post-unequip Mace smash window so aim never drops mid-smash!
        if (isInMidOrPostUnequipSmash(client) && target != null && target.isAlive()
                && isEntityAllowedByTargetSelection(client, target)
                && getPrecisionDistanceToTarget(client, target) <= aimRange.getValue() + 2.0D) {
            return;
        }

        List<LivingEntity> candidates = ActionManager.getInstance().getValidTargets(client);
        if (candidates.isEmpty()) {
            target = null;
            return;
        }

        double maxRange = aimRange.getValue();
        double maxRangeSqr = maxRange * maxRange;
        double maxFov = maceFov.getValue();
        double eyeX = client.player.getX();
        double eyeY = client.player.getEyeY();
        double eyeZ = client.player.getZ();

        LivingEntity bestTarget = null;
        double bestDistSqr = Double.MAX_VALUE;

        for (int i = 0, size = candidates.size(); i < size; i++) {
            LivingEntity living = candidates.get(i);
            if (!isEntityAllowedByTargetSelection(client, living)) {
                continue;
            }

            AABB box = living.getBoundingBox();
            double dx = eyeX - Mth.clamp(eyeX, box.minX, box.maxX);
            double dy = eyeY - Mth.clamp(eyeY, box.minY, box.maxY);
            double dz = eyeZ - Mth.clamp(eyeZ, box.minZ, box.maxZ);
            double distSqr = dx * dx + dy * dy + dz * dz;

            if (distSqr > maxRangeSqr || distSqr >= bestDistSqr) {
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

            // When gliding with Elytra/Rockets, skip targets you are looking away from or flying away from
            if (client.player.isFallFlying() && !isGlidingTowardTarget(client, living)) {
                continue;
            }

            bestDistSqr = distSqr;
            bestTarget = living;
        }

        target = bestTarget;
    }

    private void resetAllStates() {
        smashState = SmashSequenceState.IDLE;
        stateCommittedTick = -100;
        savedHotbarSlot = -1;
        targetMaceSlot = -1;
        lastTargetScanTick = -100;
        lastInventoryScanTick = -100;
        lastActiveGlideDiveTick = -100;
        postUnequipSmashEndTick = -100;
        cachedMaceSlot = -1;
        cachedAxeSlot = -1;
        cachedSwordSlot = -1;
        airbornePeakY = Double.NaN;
        silentRotationActive = false;
        resetKinematicAimState();
        target = null;
    }

    // =========================================================================
    // Click-to-Bind Module Toggle Bind System
    // =========================================================================
    private void pollModuleToggleBind(Minecraft client) {
        if (client == null || client.player == null || client.screen != null || bindToggleKey.getValue()) {
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

    private void pollToggleBindCapture() {
        long windowHandle = GLFW.glfwGetCurrentContext();
        if (windowHandle == 0L) {
            return;
        }

        if (bindToggleKey.getValue()) {
            if (!wasListeningToggle) {
                wasListeningToggle = true;
                waitingForToggleMouseRelease = true;
                updateSettingName(bindToggleKey, "Toggle Bind: [Press any key...]");
            }
            if (waitingForToggleMouseRelease) {
                if (GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS
                        && GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) != GLFW.GLFW_PRESS) {
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
                refreshToggleBindLabel();
            }
            return;
        } else if (wasListeningToggle) {
            wasListeningToggle = false;
            lastDisplayedToggleKey = Integer.MIN_VALUE;
            refreshToggleBindLabel();
        }

        refreshToggleBindLabel();
    }

    private void refreshToggleBindLabel() {
        int code = (int) Math.round(savedToggleKeyCode.getValue());
        if (!bindToggleKey.getValue() && code != lastDisplayedToggleKey) {
            lastDisplayedToggleKey = code;
            updateSettingName(bindToggleKey, "Toggle Bind: [" + formatKeyCode(code) + "]");
        }
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
                        if (val instanceof String s && s.startsWith("Toggle Bind")) {
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

    private final class SilentPdRotateConstructor extends RotateConstructor {
        private SilentPdRotateConstructor() {
            super("SilentPdMace");
        }

        @Override
        public Angle limitAngleChange(Angle currentAngle, Angle targetAngle, Vec3 vec3d, Entity entity) {
            double t = System.nanoTime() * 1.0E-9D;
            float microYaw = (float) (Math.sin(t * 11.3D) * 0.14D + ThreadLocalRandom.current().nextGaussian() * 0.04D);
            float microPitch = (float) (Math.cos(t * 13.1D) * 0.12D + ThreadLocalRandom.current().nextGaussian() * 0.04D);

            Minecraft client = Minecraft.getInstance();
            if (client.player != null && entity instanceof LivingEntity living) {
                if (isCrosshairInsideInner20PercentDeadzone(client, living, currentAngle)) {
                    return new Angle(
                            currentAngle.getYaw() + microYaw,
                            Mth.clamp(currentAngle.getPitch() + microPitch, -89.5F, 89.5F)
                    ).adjustSensitivity();
                }
            }

            float yawDelta = Mth.wrapDegrees((targetAngle.getYaw() + microYaw) - currentAngle.getYaw());
            float pitchDelta = (targetAngle.getPitch() + microPitch) - currentAngle.getPitch();
            double angularDistance = Math.hypot(yawDelta, pitchDelta);
            if (angularDistance < 0.04D) {
                return currentAngle;
            }

            double farRatio = Mth.clamp(angularDistance / 30.0D, 0.0D, 1.0D);
            double centerRatio = Mth.clamp(angularDistance / 18.0D, 0.12D, 1.0D);
            double decelFactor = Math.pow(centerRatio, 1.25D);
            double maxTickSpeed = Mth.lerp(
                    farRatio,
                    16.0D,
                    38.0D
            ) * decelFactor;

            float stepYaw = (float) Mth.clamp(yawDelta * 0.72D, -maxTickSpeed, maxTickSpeed);
            float stepPitch = (float) Mth.clamp(pitchDelta * 0.72D, -maxTickSpeed, maxTickSpeed);

            return new Angle(
                    currentAngle.getYaw() + stepYaw,
                    Mth.clamp(currentAngle.getPitch() + stepPitch, -89.5F, 89.5F)
            ).adjustSensitivity();
        }

        @Override
        public Vec3 randomValue() {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            return new Vec3(
                    rng.nextDouble(-0.03D, 0.03D),
                    rng.nextDouble(-0.03D, 0.03D),
                    rng.nextDouble(-0.03D, 0.03D)
            );
        }
    }
}