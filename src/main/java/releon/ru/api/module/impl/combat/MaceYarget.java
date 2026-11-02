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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.DrawEvent;
import releon.ru.api.events.impl.PlayerTravelEvent;
import releon.ru.api.events.impl.RotationUpdateEvent;
import releon.ru.api.events.impl.WorldRenderEvent;
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
import releon.ru.manager.ActionManager;
import releon.ru.manager.ActionManager.ActionPriority;
import releon.ru.utils.inventory.lookup.InventoryUtils;
import releon.ru.utils.render.Render3D;

import java.util.List;
import java.util.Optional;

/**
 * Updated AutoMace Controller:
 * 1. Split-Priority Mutex Integration:
 *    - Yields to Priority 1 (AUTO_ELYTRA_UNEQUIP) whenever the player is wearing an Elytra (< 4.5m & v_y < -0.1)
 *      so AutoElytra can cancel flight and swap to a Chestplate first without combat deadlocks.
 *    - Claims Priority 2 (AUTO_MACE_STRIKE) once the Elytra is confirmed unequipped and target is in smash range.
 * 2. Dynamic 4Hz Mace Auto-Caching (No Manual Slot Configuration):
 *    - Scans hotbar slots 0-8 at 4Hz (every 5 ticks), parses DataComponents.ENCHANTMENTS for Density and Breach,
 *      and caches cachedMaceSlot automatically. Remains dormant if no Mace is present.
 * 3. Restored Aim Mode Selector ("Legit", "Strict", "Silent"):
 *    - "Legit": Distance-scaled PD deceleration + 20% inner hitbox deadzone + GCD quantization.
 *    - "Strict": Instant mathematical center-locking onto (X, Y + Height/2, Z).
 *    - "Silent": Server-side rotation synchronization via AngleConnection.INSTANCE.rotateTo(...).
 */
public class MaceTarget extends Module {
    private static MaceTarget instance;

    private static final int TARGET_WIREFRAME_COLOR = 0xAA5EF2D6;
    private static final double INNER_20_PERCENT_HALF_RATIO = 0.10D;
    private static final double MIN_FAR_ROTATION_DEG_PER_TICK = 40.0D;
    private static final double MAX_FAR_ROTATION_DEG_PER_TICK = 60.0D;
    private static final double PD_DECELERATION_CONE_DEGREES = 32.0D;
    private static final double PRIORITY_1_ELYTRA_UNEQUIP_DIST = 4.5D;
    private static final int HOTBAR_SCAN_INTERVAL_TICKS = 5; // 4Hz (every 5 ticks at 20 TPS)
    private static final int MIN_TICKS_BETWEEN_MACE_STEPS = 1;

    /**
     * Bridge adapter delegating to the Split-Priority ActionManager.
     */
    public static final class CombatStateManager {
        private CombatStateManager() {
        }

        public static void acquireMaceSmashLock() {
            ActionManager.getInstance().acquireLock(ActionPriority.AUTO_MACE_STRIKE, "AutoMace_STRIKE");
        }

        public static void releaseMaceSmashLock() {
            ActionManager.getInstance().releaseLock(ActionPriority.AUTO_MACE_STRIKE, "AutoMace_STRIKE");
        }

        public static boolean tryAcquireElytraAscentLock() {
            return ActionManager.getInstance().acquireLock(ActionPriority.AUTO_ELYTRA_EQUIP, "AutoElytra_EQUIP");
        }

        public static void releaseElytraLock() {
            ActionManager.getInstance().releaseLock(ActionPriority.AUTO_ELYTRA_UNEQUIP, "AutoElytra_UNEQUIP");
            ActionManager.getInstance().releaseLock(ActionPriority.AUTO_ELYTRA_EQUIP, "AutoElytra_EQUIP");
        }

        public static boolean isMaceSmashLocked() {
            return ActionManager.getInstance().getActiveLockPriority() == ActionPriority.AUTO_MACE_STRIKE;
        }

        public static boolean isElytraAscentLocked() {
            ActionPriority active = ActionManager.getInstance().getActiveLockPriority();
            return active == ActionPriority.AUTO_ELYTRA_UNEQUIP || active == ActionPriority.AUTO_ELYTRA_EQUIP;
        }

        public static void reset() {
            ActionManager.getInstance().resetAll();
        }
    }

    // Core Targeting & Swing Settings (Manual Slot Sliders Removed -> Dynamic 4Hz Auto-Cache)
    private final NumberSetting swingRange = register(new NumberSetting(
            "Swing Range", "Maximum distance to swing and attack with the mace (capped at 3.0m vanilla reach).", 3.0D, 1.0D, 3.0D, 0.05D));
    private final NumberSetting aimRange = register(new NumberSetting(
            "Aim Range", "Maximum range to acquire and track targets.", 10.0D, 1.0D, 20.0D, 0.1D));
    private final NumberSetting maceFov = register(new NumberSetting(
            "Mace FOV", "Field of view cone in degrees for target acquisition.", 360.0D, 1.0D, 360.0D, 1.0D));
    private final NumberSetting aimInAir = register(new NumberSetting(
            "Aim In Air", "Minimum fall distance required before airborne aiming begins.", 0.0D, 0.0D, 10.0D, 0.1D));

    private final BooleanSetting autoSwitch = register(new BooleanSetting(
            "Auto Switch", "Automatically switch hotbar slot to the dynamically cached mace.", true));
    private final BooleanSetting swapBack = register(new BooleanSetting(
            "Swap Back", "Restore previous weapon slot after attacking.", true));

    // Restored Aim Mode GUI Selector ("Legit", "Strict", "Silent")
    private final ModeSetting aimMode = register(new ModeSetting(
            "Aim Mode", "Tracking algorithm: Legit (PD + 20% deadzone), Strict (instant center lock), or Silent.", "Legit", "Legit", "Strict", "Silent"));

    private final NumberSetting aimSpeed = register(new NumberSetting(
            "Aim Speed", "Angular rotation speed during distance-scaled PD deceleration.", 50.0D, 1.0D, 100.0D, 0.5D));
    private final NumberSetting aimSmooth = register(new NumberSetting(
            "Aim Smooth", "Smoothing derivative damping factor applied to rotation interpolation.", 0.00D, 0.00D, 10.00D, 0.01D));
    private final NumberSetting minFallDist = register(new NumberSetting(
            "Min Fall Dist", "Minimum fall distance required to trigger a mace slam.", 1.5D, 0.0D, 20.0D, 0.1D));
    private final NumberSetting cooldownMs = register(new NumberSetting(
            "Cooldown (ms)", "Minimum cooldown in milliseconds between slam sequences.", 125.0D, 0.0D, 1000.0D, 5.0D));
    private final NumberSetting maceSwapDelayMs = register(new NumberSetting(
            "Mace Swap Delay (ms)", "Delay in milliseconds after switching to mace before attacking.", 50.0D, 0.0D, 500.0D, 1.0D));

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
    private int lastTargetScanTick = -1;
    private double airbornePeakY = Double.NaN;
    private boolean silentRotationActive = false;

    // 2. Dynamic 4Hz Mace Auto-Caching State
    private int cachedMaceSlot = -1;
    private int cachedAxeSlot = -1;
    private int cachedSwordSlot = -1;
    private int lastInventoryScanTick = -100;

    // Proportional-Derivative (PD) Distance-Scaled Deceleration State
    private double prevYawError = 0.0D;
    private double prevPitchError = 0.0D;
    private boolean hasPrevError = false;
    private long lastSmoothFrameNs = 0L;

    public MaceTarget() {
        super("AutoMace", "Split-priority PD/Strict/Silent aim assist with 4Hz dynamic enchantment Mace caching.", ModuleCategory.COMBAT);
        instance = this;
    }

    public static MaceTarget getInstance() {
        return instance;
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

    @Override
    protected void onEnable() {
        resetAllStates();
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
        updateDynamicMaceCacheAt4Hz(client, false);

        if (isGroundedOrAscendingLockout(client)) {
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
        updateDynamicMaceCacheAt4Hz(client, false);

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

        // If no Mace is present in hotbar (cachedMaceSlot == -1) and not holding a Mace, remain completely dormant
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
                suspendSilentRotation();
                return;
            }
            Angle centerAngle = computeExactCenterAimAngle(client, target);
            if (centerAngle != null) {
                queueSilentServerRotation(centerAngle);
            } else {
                suspendSilentRotation();
            }
        } else if ("Strict".equalsIgnoreCase(mode)) {
            suspendSilentRotation();
            applyStrictCenterLockAim(client, target);
        } else {
            // "Legit" mode (default)
            suspendSilentRotation();
            long nowNs = System.nanoTime();
            if (lastSmoothFrameNs == 0L || nowNs - lastSmoothFrameNs > 22_000_000L) {
                applyDistanceScaledPdAim(client, target, 1.0D);
                lastSmoothFrameNs = nowNs;
            }
        }
    }

    private void runPostRotationPhase(Minecraft client) {
        // Yield immediately if Priority 0 (CRITICAL) or Priority 1 (AUTO_ELYTRA_UNEQUIP) holds the ActionManager lock
        if (ActionManager.getInstance().isBlockedByHigherPriority(ActionPriority.AUTO_MACE_STRIKE)) {
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
     * 2. Dynamic 4Hz Mace Auto-Caching (Fabric 1.21.11 DataComponents.ENCHANTMENTS):
     * - Scans hotbar slots 0-8 every 5 ticks (4Hz) or on demand before a strike.
     * - Checks stack.getItem() == Items.MACE.
     * - Parses DataComponents.ENCHANTMENTS for Enchantments.DENSITY and Enchantments.BREACH.
     * - Stores the slot index of the highest-level Density/Breach Mace in cachedMaceSlot.
     */
    private void updateDynamicMaceCacheAt4Hz(Minecraft client, boolean forceImmediate) {
        if (client.player == null) {
            cachedMaceSlot = -1;
            cachedAxeSlot = -1;
            cachedSwordSlot = -1;
            return;
        }

        int currentTick = client.player.tickCount;
        if (!forceImmediate && (currentTick - lastInventoryScanTick) < HOTBAR_SCAN_INTERVAL_TICKS) {
            return;
        }
        lastInventoryScanTick = currentTick;

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

    /**
     * Parses DataComponents.ENCHANTMENTS on a Mace ItemStack and scores its Density and Breach levels.
     */
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
     * 1. Split-Priority Mutex Lock Management:
     * - If the player is still wearing an Elytra (< 4.5m & v_y < -0.1), AutoMace does NOT claim the lock—it
     *   yields to Priority 1 (AUTO_ELYTRA_UNEQUIP) so AutoElytra can swap the Chestplate on first.
     * - Once the Elytra is confirmed unequipped (!InventoryUtils.isElytraEquipped()) and the target is within
     *   smash range, AutoMace claims Priority 2 (AUTO_MACE_STRIKE).
     */
    private void updateSplitPriorityCombatLock(Minecraft client, LivingEntity targetEntity) {
        if (client.player == null || targetEntity == null || isGroundedOrAscendingLockout(client)) {
            if (smashState == SmashSequenceState.IDLE) {
                CombatStateManager.releaseMaceSmashLock();
            }
            return;
        }

        double currentDist = getPrecisionDistanceToTarget(client, targetEntity);
        double velY = client.player.getDeltaMovement().y;

        // If Elytra is equipped inside < 4.5m with v_y < -0.1, yield lock to Priority 1 (AutoElytra_UNEQUIP)
        if (InventoryUtils.isElytraEquipped()) {
            CombatStateManager.releaseMaceSmashLock();
            if (currentDist < PRIORITY_1_ELYTRA_UNEQUIP_DIST && velY < -0.1D) {
                ElytraTarget autoElytra = ElytraTarget.getInstance();
                if (autoElytra != null && autoElytra.isEnabled()) {
                    autoElytra.ensureUnequippedBeforeMaceAttack(targetEntity);
                }
            }
            return;
        }

        // Priority 2 (AutoMace_STRIKE): Target in smash range AND Elytra is unequipped
        if (isFallingForSmash(client) && currentDist <= swingRange.getValue() + 0.5D) {
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

        // Priority 1 Check: If Elytra is still equipped when < 4.5m and v_y < -0.1, trigger Priority 1 Unequip first
        if (InventoryUtils.isElytraEquipped()) {
            if (currentDist < PRIORITY_1_ELYTRA_UNEQUIP_DIST && velY < -0.1D) {
                CombatStateManager.releaseMaceSmashLock();
                ElytraTarget autoElytra = ElytraTarget.getInstance();
                if (autoElytra != null && autoElytra.isEnabled()) {
                    autoElytra.ensureUnequippedBeforeMaceAttack(targetEntity);
                }
            }
            return;
        }

        if (currentDist > swingRange.getValue() + 0.35D) {
            return;
        }

        // Refresh cachedMaceSlot if needed
        if (cachedMaceSlot == -1) {
            updateDynamicMaceCacheAt4Hz(client, true);
        }

        boolean holdingMace = isMaceStack(client.player.getMainHandItem());
        if (!holdingMace && (!autoSwitch.getValue() || cachedMaceSlot == -1)) {
            return;
        }

        // Priority 2 (AutoMace_STRIKE): Target in smash range AND Elytra is confirmed unequipped
        if (!ActionManager.getInstance().acquireLock(ActionPriority.AUTO_MACE_STRIKE, "AutoMace_STRIKE")) {
            return;
        }

        int currentSlot = client.player.getInventory().getSelectedSlot();
        savedHotbarSlot = currentSlot;
        targetMaceSlot = holdingMace ? currentSlot : cachedMaceSlot;

        if (shouldTriggerStunSlam(client, targetEntity) && cachedAxeSlot != -1) {
            if (currentSlot != cachedAxeSlot) {
                selectHotbarSlotLegit(client, cachedAxeSlot);
                stateCommittedTick = client.player.tickCount;
                stateTickTimer.reset();
                smashState = SmashSequenceState.STEP_1_AXE_STUN_ATTACK;
            } else if (currentDist <= swingRange.getValue() && performThrottledAttack(client, targetEntity)) {
                stateCommittedTick = client.player.tickCount;
                stateTickTimer.reset();
                smashState = SmashSequenceState.STEP_2_SELECT_MACE_AFTER_STUN;
            }
            return;
        }

        if (currentSlot != targetMaceSlot) {
            selectHotbarSlotLegit(client, targetMaceSlot);
            stateCommittedTick = client.player.tickCount;
            stateTickTimer.reset();
            smashState = SmashSequenceState.STEP_1_MACE_SMASH_ATTACK;
            return;
        }

        if (currentDist <= swingRange.getValue() && performThrottledAttack(client, targetEntity)) {
            cooldownTimer.reset();
            transitionToSwapBackRestore(client);
        }
    }

    /**
     * 3. Aim Mode: "Strict"
     * Instant mathematical center-locking onto (X, Y + Height/2, Z).
     */
    private void applyStrictCenterLockAim(Minecraft client, LivingEntity targetEntity) {
        Angle centerAngle = computeExactCenterAimAngle(client, targetEntity);
        if (centerAngle == null) {
            return;
        }
        client.player.setYRot(centerAngle.getYaw());
        client.player.setXRot(Mth.clamp(centerAngle.getPitch(), -89.5F, 89.5F));
    }

    /**
     * 3. Aim Mode: "Legit" (Default)
     * Distance-Scaled Deceleration PD Controller + 20% Inner Hitbox Deadzone + GCD quantization.
     */
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

    /**
     * 3. Aim Mode: "Silent"
     * Server-side rotation synchronization via AngleConnection.INSTANCE.rotateTo(...).
     */
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
                if (client.player.tickCount - stateCommittedTick < MIN_TICKS_BETWEEN_MACE_STEPS) {
                    return;
                }
                double dist = getPrecisionDistanceToTarget(client, target);
                if (dist <= swingRange.getValue() + 0.25D && performThrottledAttack(client, target)) {
                    stateCommittedTick = client.player.tickCount;
                    stateTickTimer.reset();
                    smashState = SmashSequenceState.STEP_2_SELECT_MACE_AFTER_STUN;
                } else if (client.player.onGround() || stateTickTimer.finished(220.0D)) {
                    transitionToSwapBackRestore(client);
                }
            }

            case STEP_2_SELECT_MACE_AFTER_STUN -> {
                if (target == null || !target.isAlive() || isWhitelistedFriend(client, target)) {
                    transitionToSwapBackRestore(client);
                    return;
                }
                if (client.player.tickCount - stateCommittedTick < MIN_TICKS_BETWEEN_MACE_STEPS) {
                    return;
                }
                int maceSlot = targetMaceSlot != -1 ? targetMaceSlot : cachedMaceSlot;
                if (maceSlot != -1) {
                    selectHotbarSlotLegit(client, maceSlot);
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
                double requiredDelayMs = Math.max(50.0D, maceSwapDelayMs.getValue());
                if (client.player.tickCount - stateCommittedTick < MIN_TICKS_BETWEEN_MACE_STEPS
                        || !stateTickTimer.finished(requiredDelayMs)) {
                    return;
                }
                double dist = getPrecisionDistanceToTarget(client, target);
                if (dist <= swingRange.getValue() + 0.20D && performThrottledAttack(client, target)) {
                    cooldownTimer.reset();
                    transitionToSwapBackRestore(client);
                } else if (client.player.onGround() || stateTickTimer.finished(requiredDelayMs + 220.0D)) {
                    transitionToSwapBackRestore(client);
                }
            }

            case STEP_2_SWAPBACK_RESTORE -> {
                if (client.player.tickCount - stateCommittedTick < MIN_TICKS_BETWEEN_MACE_STEPS) {
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
        stateCommittedTick = client.player != null ? client.player.tickCount : -1;
        stateTickTimer.reset();
        smashState = SmashSequenceState.STEP_2_SWAPBACK_RESTORE;
    }

    private void executeSafeLegitSwapBack(Minecraft client) {
        if (client == null || client.player == null) {
            savedHotbarSlot = -1;
            return;
        }

        int returnSlot = savedHotbarSlot >= 0 && savedHotbarSlot <= 8
                ? savedHotbarSlot
                : (cachedSwordSlot != -1 ? cachedSwordSlot : 0);

        selectHotbarSlotLegit(client, returnSlot);
        savedHotbarSlot = -1;
        targetMaceSlot = -1;
    }

    private boolean performThrottledAttack(Minecraft client, LivingEntity targetEntity) {
        if (client.player == null || client.gameMode == null || targetEntity == null) {
            return false;
        }
        if (isWhitelistedFriend(client, targetEntity)) {
            return false;
        }

        Vec3 activeLookVec = "Silent".equalsIgnoreCase(aimMode.getValue()) && AngleConnection.INSTANCE.getCurrentAngle() != null
                ? AngleConnection.INSTANCE.getCurrentAngle().toVector()
                : client.player.getViewVector(1.0F);

        if (!ActionManager.verifyStrict3mAttackRaycast(client, targetEntity, swingRange.getValue(), activeLookVec)) {
            return false;
        }
        if (!ActionManager.getInstance().canDispatchInteraction(client, ActionPriority.AUTO_MACE_STRIKE)) {
            return false;
        }

        if (client.player.isUsingItem() && client.player.getUseItem().getItem() == Items.SHIELD) {
            client.gameMode.releaseUsingItem(client.player);
            client.player.stopUsingItem();
        }

        if (clickSim.getValue() && client.options != null) {
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

    /**
     * If no Mace is found in the 4Hz hotbar cache (cachedMaceSlot == -1) and the player is not holding a Mace,
     * AutoMace remains completely dormant.
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
            return false;
        }

        return getEffectiveFallDistance(client) >= aimInAir.getValue();
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
        lastSmoothFrameNs = 0L;
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
        if (target == null || client.player == null || isGroundedOrAscendingLockout(client) || isWhitelistedFriend(client, target)) {
            return false;
        }
        if (!ActionManager.hasLineOfSight(client, target)) {
            return false;
        }
        if (getPrecisionDistanceToTarget(client, target) > aimRange.getValue()) {
            return false;
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
        updateDynamicMaceCacheAt4Hz(client, false);
        return cachedMaceSlot;
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
            if (!living.isAlive() || isWhitelistedFriend(client, living)) {
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

            bestDistSqr = distSqr;
            bestTarget = living;
        }

        target = bestTarget;
    }

    private void resetAllStates() {
        smashState = SmashSequenceState.IDLE;
        stateCommittedTick = -1;
        savedHotbarSlot = -1;
        targetMaceSlot = -1;
        lastTargetScanTick = -1;
        lastInventoryScanTick = -100;
        cachedMaceSlot = -1;
        cachedAxeSlot = -1;
        cachedSwordSlot = -1;
        airbornePeakY = Double.NaN;
        silentRotationActive = false;
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