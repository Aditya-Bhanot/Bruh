package attrium.ru.api.module.impl.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import attrium.ru.api.events.annotation.SubscribeEvent;
import attrium.ru.api.events.impl.DrawEvent;
import attrium.ru.api.events.impl.PacketEvent;
import attrium.ru.api.events.impl.RotationUpdateEvent;
import attrium.ru.api.events.impl.TickEvent;
import attrium.ru.api.events.types.EventPhase;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.ModuleCategory;
import attrium.ru.api.module.impl.combat.aura.Angle;
import attrium.ru.api.module.impl.combat.aura.AngleConfig;
import attrium.ru.api.module.impl.combat.aura.AngleConnection;
import attrium.ru.api.module.impl.combat.aura.MathAngle;
import attrium.ru.api.module.impl.combat.aura.impl.LinearConstructor;
import attrium.ru.api.module.impl.combat.aura.util.TaskPriority;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.ModeSetting;
import attrium.ru.api.settings.impl.MultiModeSetting;
import attrium.ru.api.settings.impl.NumberSetting;
import attrium.ru.manager.FriendManager;
import attrium.ru.utils.repository.friend.FriendUtils;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Standalone Air-Juggle AutoCrystal Module (Minecraft 1.21.11 Official Mojang Mappings):
 *
 * 1. Hitbox-Cleared Juggle Window (2 to 4 Blocks Below Feet for Obsidian -> 1 to 3 Blocks Below Feet for Crystal):
 *    - Never places Obsidian at `enemyFootY` or `enemyFootY - 1` where the Obsidian block or the 2.0x2.0
 *      End Crystal hitbox intersects the opponent's 0.6x1.8 bounding box (preventing vanishing ghost blocks).
 *    - Places Obsidian 2 to 4 blocks below the airborne opponent's feet (`by` in `[ey - 4 .. ey - 2]`),
 *      putting the End Crystal 1 to 3 blocks below their feet (`expY` in `[ey - 3 .. ey - 1]`).
 * 2. Horizontal Offset <= 1.5 Blocks & Peak-Descent/Apex Detonation:
 *    - Keeps horizontal offset between crystal center and opponent <= 1.5 blocks so knockback launches
 *      straight up instead of tilting outward.
 *    - Detonates on peak descent / apex (`vel.y <= 0.12`) and never on the same tick as crystal placement.
 * 3. Strict Hit-Crystal Gating:
 *    - `notifyMeleeHitEntity` only triggers air-juggling when `Hit Crystal` is explicitly enabled.
 * 4. 14-16 CPS Sweet Spot + Gaussian Click Variance + 6-Second Continuous Fatigue Slowdown:
 *    - Runs at 14-16 combined CPS (~7-8 cycles/sec) with Gaussian millisecond variance.
 *    - If crystals are placed/broken continuously (any gap <= 0.5s / 500ms counts as continuous) for more
 *      than 6.0 seconds, automatically slows down place and break speed until a pause > 0.5s occurs.
 */
public class AutoCrystal extends Module {
    private static AutoCrystal instance;

    private static final long CONTINUOUS_GAP_THRESHOLD_MS = 500L;    // <= 0.5s gap still counts as continuous
    private static final long CONTINUOUS_FATIGUE_TRIGGER_MS = 6000L; // > 6.0s continuous triggers slowdown

    // ==================== Settings ====================

    private final MultiModeSetting targets = register(new MultiModeSetting(
            "Targets",
            "Entity categories to target for AutoCrystal",
            new String[]{"Players", "Mobs", "Animals", "Armor Stands"},
            "Players", "Mobs"
    ));

    private final ModeSetting aimMode = register(new ModeSetting(
            "Aim Mode",
            "Legit (humanized smooth camera glide) or Silent (server-side AngleConnection rotation)",
            "Legit",
            "Legit", "Silent", "None"
    ));

    private final BooleanSetting hitCrystal = register(new BooleanSetting(
            "Hit Crystal",
            "Only activate air-juggle crystal combo after hitting an enemy into the air",
            false
    ));

    private final BooleanSetting autoObsidian = register(new BooleanSetting(
            "Auto Obsidian",
            "Place a single Obsidian base 2-4 blocks below the airborne enemy ONLY if none is placed",
            true
    ));

    private final NumberSetting obsidianDelayTicks = register(new NumberSetting(
            "Obsidian Delay (Ticks)",
            "Cooldown ticks after placing an Obsidian block so multiple Obsidians are never spammed",
            6.0D, 1.0D, 20.0D, 1.0D
    ));

    private final NumberSetting placeDelayTicks = register(new NumberSetting(
            "Place Delay (Ticks)",
            "Minimum client ticks between End Crystal placements (2-3 ticks recommended for 14-16 CPS)",
            1.0D, 1.0D, 10.0D, 1.0D
    ));

    private final NumberSetting breakDelayTicks = register(new NumberSetting(
            "Break Delay (Ticks)",
            "Minimum client ticks before detonating a placed End Crystal (2-3 ticks prevents wasted hit-stun crystals)",
            2.0D, 1.0D, 10.0D, 1.0D
    ));

    private final NumberSetting minTargetYDelta = register(new NumberSetting(
            "Min Target Y-Delta",
            "Minimum height the enemy must be above our Y-level (prevents placing crystals on the same level as us)",
            0.35D, 0.15D, 2.5D, 0.05D
    ));

    private final BooleanSetting autoSwitchCrystal = register(new BooleanSetting(
            "Auto Switch Crystal",
            "Automatically switch to End Crystal on the hotbar (0-8) when placing",
            true
    ));

    private final BooleanSetting swapBackToWeapon = register(new BooleanSetting(
            "Swap Back Weapon",
            "Restore your previous hotbar slot after placing/detonating a crystal",
            true
    ));

    private final NumberSetting crystalRange = register(new NumberSetting(
            "Crystal Range",
            "Maximum reach distance (2.0 - 6.0 blocks) to place and detonate End Crystals",
            4.5D, 2.0D, 6.0D, 0.1D
    ));

    private final NumberSetting minTargetDamage = register(new NumberSetting(
            "Min Target Damage",
            "Minimum damage required to place a crystal near the target",
            2.5D, 0.5D, 20.0D, 0.5D
    ));

    private final BooleanSetting antiSuicide = register(new BooleanSetting(
            "Anti-Suicide",
            "Never place or detonate a crystal that would lethal-damage you",
            true
    ));

    private final BooleanSetting crystalOptimizer = register(new BooleanSetting(
            "Crystal Optimizer",
            "Immediately remove attacked End Crystals client-side for faster follow-up placements",
            true
    ));

    private final NumberSetting legitAimSmoothing = register(new NumberSetting(
            "Aim Smoothness",
            "Humanized camera glide speed when Aim Mode is set to Legit (lower = smoother)",
            0.24D, 0.06D, 0.75D, 0.02D
    ));

    // ==================== State Fields ====================

    private LivingEntity currentTarget = null;
    private LivingEntity juggleTargetPlayer = null;
    private boolean isJuggling = false;
    private int juggleLastHitTick = -100;

    private BlockPos lockedBasePos = null;
    private int lockedBasePlacedTick = -100;

    private Vec3 activeAimPoint = null;
    private Entity activeAimEntity = null;
    private long lastDrawTimeNs = 0L;
    private float yawVelocity = 0.0F;
    private float pitchVelocity = 0.0F;

    private int savedHotbarSlot = -1;
    private int obsidianTickTimer = 100;
    private int placeTickTimer = 100;
    private int breakTickTimer = 100;
    private int lastCrystalPlacedTick = -100;
    private EndCrystal lastAttackedCrystal = null;

    // 14-16 CPS Variance & 6-Second Continuous Fatigue Slowdown State
    private long continuousChainStartMs = 0L;
    private long lastCrystalActionMs = 0L;
    private long nextAllowedPlaceMs = 0L;
    private long nextAllowedBreakMs = 0L;

    public AutoCrystal() {
        super("AutoCrystal", "Air-Juggle AutoCrystal (2-4 block hitbox clearance, <=1.5m offset, 14-16 CPS variance & 6s continuous slowdown)", ModuleCategory.COMBAT);
        instance = this;
    }

    public static AutoCrystal getInstance() {
        if (instance == null) {
            instance = new AutoCrystal();
        }
        return instance;
    }

    public boolean isJugglingActive() {
        return isJuggling;
    }

    /**
     * Triggered on melee hit ONLY when AutoCrystal AND the `Hit Crystal` setting are enabled.
     */
    public static void notifyMeleeHitEntity(Entity attackedEntity) {
        AutoCrystal ac = getInstance();
        if (ac == null || !ac.isEnabled() || !Boolean.TRUE.equals(ac.hitCrystal.getValue())) {
            return;
        }
        if (!(attackedEntity instanceof LivingEntity living)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null || living == mc.player || !living.isAlive()) {
            return;
        }
        if (!ac.isValidTarget(mc, living)) {
            return;
        }
        ac.initiateHitCrystalJuggleSequence(mc, living);
    }

    public void initiateHitCrystalJuggleSequence(Minecraft client, LivingEntity target) {
        if (client == null || client.player == null || target == null || !target.isAlive()) {
            return;
        }
        if (!Boolean.TRUE.equals(this.hitCrystal.getValue())) {
            return;
        }
        this.isJuggling = true;
        this.juggleTargetPlayer = target;
        this.juggleLastHitTick = client.player.tickCount;
        this.currentTarget = target;
    }

    @Override
    protected void onEnable() {
        resetState();
    }

    @Override
    protected void onDisable() {
        Minecraft client = Minecraft.getInstance();
        restoreHotbarSlotIfNeeded(client);
        resetState();
    }

    private void resetState() {
        currentTarget = null;
        juggleTargetPlayer = null;
        isJuggling = false;
        juggleLastHitTick = -100;
        lockedBasePos = null;
        lockedBasePlacedTick = -100;
        clearAimTarget();
        savedHotbarSlot = -1;
        obsidianTickTimer = 100;
        placeTickTimer = 100;
        breakTickTimer = 100;
        lastCrystalPlacedTick = -100;
        lastAttackedCrystal = null;
        continuousChainStartMs = 0L;
        lastCrystalActionMs = 0L;
        nextAllowedPlaceMs = 0L;
        nextAllowedBreakMs = 0L;
    }

    private void clearAimTarget() {
        activeAimPoint = null;
        activeAimEntity = null;
        yawVelocity = 0.0F;
        pitchVelocity = 0.0F;
        lastDrawTimeNs = 0L;
    }

    // ==================== 6-Second Continuous Fatigue & 14-16 CPS Pacing ====================

    /**
     * Records a crystal place/break action. Any gap <= 500ms (0.5s) between crystal actions
     * counts as continuous; a gap > 500ms resets the continuous timer.
     */
    private void recordCrystalActionTimestamp(long nowMs) {
        if (lastCrystalActionMs == 0L || (nowMs - lastCrystalActionMs) > CONTINUOUS_GAP_THRESHOLD_MS) {
            continuousChainStartMs = nowMs;
        }
        lastCrystalActionMs = nowMs;
    }

    /**
     * Returns true if crystals have been placed/broken continuously (gaps <= 0.5s) for more than 6.0 seconds.
     */
    private boolean isContinuousFatigueSlowdownActive(long nowMs) {
        if (lastCrystalActionMs == 0L || (nowMs - lastCrystalActionMs) > CONTINUOUS_GAP_THRESHOLD_MS) {
            return false;
        }
        return (nowMs - continuousChainStartMs) > CONTINUOUS_FATIGUE_TRIGGER_MS;
    }

    /**
     * Computes the randomized delay (ms) before the next place or break click:
     * - Normal (< 6s continuous): 14-16 combined CPS sweet spot (~62-72ms per click +/- Gaussian variance).
     * - Fatigue (> 6s continuous with <= 0.5s gaps): Slows down to ~8-10 combined CPS (~115-155ms per click)
     *   with larger variance until a pause > 0.5s occurs.
     */
    private long computeNextRandomizedClickDelayMs(long nowMs, boolean isBreakAction) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        boolean fatigued = isContinuousFatigueSlowdownActive(nowMs);
        double baseMs;
        double stdDevMs;
        if (fatigued) {
            baseMs = isBreakAction ? 135.0D : 125.0D;
            stdDevMs = 24.0D;
        } else {
            baseMs = isBreakAction ? 68.0D : 64.0D;
            stdDevMs = 14.0D;
        }
        double gaussianJitter = Mth.clamp(rng.nextGaussian() * stdDevMs, -28.0D, 38.0D);
        return Math.max(48L, Math.round(baseMs + gaussianJitter));
    }

    // ==================== Events ====================

    @SubscribeEvent
    public void onTick(TickEvent.Pre event) {
        if (!this.isEnabled()) {
            return;
        }
        processCrystalTick(event != null ? event.getClient() : Minecraft.getInstance());
    }

    public void onPreTick(TickEvent.Pre event) {
        onTick(event);
    }

    public void runPhase2PreMotion(Minecraft client) {
        processCrystalTick(client);
    }

    @SubscribeEvent
    public void onRotationUpdate(RotationUpdateEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (event == null || event.getPhase() != EventPhase.PRE
                || client == null || client.player == null || client.level == null) {
            return;
        }
        if ("Silent".equalsIgnoreCase(aimMode.getValue()) && activeAimPoint != null) {
            rotateSilentTowardsVec(client, activeAimPoint, activeAimEntity);
        }
    }

    @SubscribeEvent
    public void onDraw(DrawEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.level == null || activeAimPoint == null) {
            lastDrawTimeNs = 0L;
            return;
        }
        if ("Legit".equalsIgnoreCase(aimMode.getValue())) {
            long nowNs = System.nanoTime();
            float dt = (lastDrawTimeNs == 0L)
                    ? 0.016F
                    : Mth.clamp((nowNs - lastDrawTimeNs) / 1_000_000_000.0F, 0.001F, 0.08F);
            lastDrawTimeNs = nowNs;
            applyHumanizedLegitAim(client, activeAimPoint, dt);
        }
    }

    @SubscribeEvent
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event == null || !event.isSend()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.level == null) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (packet instanceof ServerboundInteractPacket) {
            if (Boolean.TRUE.equals(hitCrystal.getValue())
                    && client.hitResult instanceof EntityHitResult ehr
                    && ehr.getEntity() instanceof LivingEntity hitLiving) {
                if (hitLiving != client.player) {
                    notifyMeleeHitEntity(hitLiving);
                }
            }
            if (crystalOptimizer.getValue() && lastAttackedCrystal != null && lastAttackedCrystal.isAlive()) {
                lastAttackedCrystal.discard();
                lastAttackedCrystal.setRemoved(Entity.RemovalReason.KILLED);
                lastAttackedCrystal.onClientRemoval();
                lastAttackedCrystal = null;
            }
        }
    }

    // ==================== Core AutoCrystal & Air-Juggle Loop ====================

    private void processCrystalTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null || client.gameMode == null) {
            resetState();
            return;
        }

        if (client.screen instanceof AbstractContainerScreen<?> || client.screen != null || client.player.isUsingItem()) {
            clearAimTarget();
            return;
        }

        long nowMs = System.currentTimeMillis();
        // Reset continuous chain if more than 0.5 seconds (500ms) have passed without a crystal action
        if (lastCrystalActionMs > 0L && (nowMs - lastCrystalActionMs) > CONTINUOUS_GAP_THRESHOLD_MS) {
            continuousChainStartMs = 0L;
            lastCrystalActionMs = 0L;
        }

        obsidianTickTimer++;
        placeTickTimer++;
        breakTickTimer++;

        // Clear juggle state if Hit Crystal was toggled OFF
        if (!Boolean.TRUE.equals(hitCrystal.getValue())) {
            isJuggling = false;
            juggleTargetPlayer = null;
        }

        double reach = Math.max(2.0D, crystalRange.getValue());
        currentTarget = findOptimalTarget(client, Math.max(7.5D, reach + 3.0D));

        if (isJuggling && juggleTargetPlayer != null) {
            int ticksSinceHit = client.player.tickCount - juggleLastHitTick;
            boolean landedAndSettled = juggleTargetPlayer.onGround()
                    && ticksSinceHit > 8
                    && !isTargetElevatedAbovePlayer(client, juggleTargetPlayer);
            if (!juggleTargetPlayer.isAlive()
                    || client.player.distanceToSqr(juggleTargetPlayer) > 64.0D
                    || ticksSinceHit > 32
                    || landedAndSettled) {
                isJuggling = false;
                juggleTargetPlayer = null;
                clearAimTarget();
                if (swapBackToWeapon.getValue()) {
                    restoreHotbarSlotIfNeeded(client);
                }
            }
        }

        // Strict Hit-Crystal Gate: When Hit Crystal is ON, ONLY run after we melee-hit the target (`isJuggling == true`)!
        if (Boolean.TRUE.equals(hitCrystal.getValue()) && !isJuggling) {
            clearAimTarget();
            if (swapBackToWeapon.getValue()) {
                restoreHotbarSlotIfNeeded(client);
            }
            return;
        }

        if (currentTarget == null || !currentTarget.isAlive()) {
            clearAimTarget();
            lockedBasePos = null;
            if (swapBackToWeapon.getValue()) {
                restoreHotbarSlotIfNeeded(client);
            }
            return;
        }

        // STRICT Y-LEVEL GUARD: Never place or detonate crystals if the enemy is on the same Y-level (or below) as us!
        if (!isTargetElevatedAbovePlayer(client, currentTarget)) {
            clearAimTarget();
            if (swapBackToWeapon.getValue()) {
                restoreHotbarSlotIfNeeded(client);
            }
            return;
        }

        // Compute effective tick delays (adds +1..2 extra ticks when continuous > 6s fatigue is active)
        boolean fatigued = isContinuousFatigueSlowdownActive(nowMs);
        int extraFatigueTicks = fatigued ? 2 : 0;
        int reqPlaceDelay = Math.max(1, placeDelayTicks.getValue().intValue()) + extraFatigueTicks;
        int reqBreakDelay = Math.max(1, breakDelayTicks.getValue().intValue()) + extraFatigueTicks;

        // Validate lockedBasePos so it stays within 1.5 blocks horizontally and 2-4 blocks below enemy feet
        if (lockedBasePos != null) {
            double bx = lockedBasePos.getX() + 0.5D;
            double by = lockedBasePos.getY() + 1.0D;
            double bz = lockedBasePos.getZ() + 0.5D;
            boolean stillValidBlock = isCrystalBaseBlock(client, lockedBasePos)
                    || (client.player.tickCount - lockedBasePlacedTick) <= 3;
            double dx = currentTarget.getX() - bx;
            double dz = currentTarget.getZ() - bz;
            double horizDistSq = dx * dx + dz * dz;
            if (!stillValidBlock
                    || horizDistSq > 3.24D // > 1.8 blocks horizontal drift
                    || client.player.getEyePosition(1.0F).distanceToSqr(bx, by, bz) > (reach * reach)) {
                lockedBasePos = null;
            }
        }

        // 1. Priority 1: Detonate any placed EndCrystal under the airborne/elevated enemy
        //    (Never on the exact same tick the crystal was placed — prevents BadPackets/Post flags!)
        EndCrystal placedCrystal = findBestCrystalToDetonate(client, currentTarget, reach);
        if (placedCrystal != null) {
            Vec3 crystalAim = placedCrystal.getBoundingBox().getCenter();
            setActiveAim(client, crystalAim, placedCrystal);

            boolean sameTickAsPlace = (client.player.tickCount <= lastCrystalPlacedTick);
            if (!sameTickAsPlace
                    && nowMs >= nextAllowedBreakMs
                    && breakTickTimer >= reqBreakDelay
                    && isEnemyInJuggleApexOrDescentWindow(currentTarget, placedCrystal)
                    && canSafelyDetonate(client, placedCrystal)) {
                lastAttackedCrystal = placedCrystal;
                client.gameMode.attack(client.player, placedCrystal);
                client.player.swing(InteractionHand.MAIN_HAND);

                breakTickTimer = 0;
                recordCrystalActionTimestamp(nowMs);
                nextAllowedBreakMs = nowMs + computeNextRandomizedClickDelayMs(nowMs, true);
                nextAllowedPlaceMs = Math.max(nextAllowedPlaceMs, nowMs + computeNextRandomizedClickDelayMs(nowMs, false));

                if (isJuggling) {
                    juggleLastHitTick = client.player.tickCount;
                }
                if (swapBackToWeapon.getValue()) {
                    restoreHotbarSlotIfNeeded(client);
                }
            }
            return;
        }

        // 2. Priority 2: Reuse existing Obsidian/Bedrock base (1-3 blocks below feet, <= 1.5 blocks horizontal offset)
        BlockPos existingBase = findExistingCrystalBaseUnderTarget(client, currentTarget, reach);
        if (existingBase != null) {
            lockedBasePos = existingBase;
            Vec3 preAimVec = new Vec3(existingBase.getX() + 0.5D, existingBase.getY() + 1.0D, existingBase.getZ() + 0.5D);
            setActiveAim(client, preAimVec, currentTarget);

            if (nowMs >= nextAllowedPlaceMs && placeTickTimer >= reqPlaceDelay) {
                if (placeCrystalOnBase(client, existingBase)) {
                    placeTickTimer = 0;
                    lastCrystalPlacedTick = client.player.tickCount;
                    recordCrystalActionTimestamp(nowMs);
                    nextAllowedPlaceMs = nowMs + computeNextRandomizedClickDelayMs(nowMs, false);
                    nextAllowedBreakMs = Math.max(nextAllowedBreakMs, nowMs + computeNextRandomizedClickDelayMs(nowMs, true));
                }
            }
            return;
        }

        // 3. Priority 3: Place ONE Obsidian block 2 to 4 blocks below the enemy's feet (ONLY if none exists)
        if (!autoObsidian.getValue()) {
            clearAimTarget();
            if (swapBackToWeapon.getValue()) {
                restoreHotbarSlotIfNeeded(client);
            }
            return;
        }

        int reqObsidianDelay = Math.max(2, obsidianDelayTicks.getValue().intValue()) + extraFatigueTicks;
        if (obsidianTickTimer < reqObsidianDelay || nowMs < nextAllowedPlaceMs) {
            return;
        }

        PlacementCandidate newObsidianCandidate = findBestNewObsidianCandidate(client, currentTarget, reach);
        if (newObsidianCandidate == null) {
            clearAimTarget();
            if (swapBackToWeapon.getValue()) {
                restoreHotbarSlotIfNeeded(client);
            }
            return;
        }

        if (placeObsidianOnSupport(client, newObsidianCandidate.basePos, newObsidianCandidate.supportPos, newObsidianCandidate.supportFace)) {
            lockedBasePos = newObsidianCandidate.basePos.immutable();
            lockedBasePlacedTick = client.player.tickCount;
            obsidianTickTimer = 0;
            recordCrystalActionTimestamp(nowMs);
            nextAllowedPlaceMs = nowMs + computeNextRandomizedClickDelayMs(nowMs, false);
        }
    }

    /**
     * Strict Y-Level Guard:
     * Returns true ONLY if the target's feet are higher than our feet by at least `minTargetYDelta`.
     */
    private boolean isTargetElevatedAbovePlayer(Minecraft client, LivingEntity target) {
        if (client == null || client.player == null || target == null) {
            return false;
        }
        double playerMinY = client.player.getBoundingBox().minY;
        double targetMinY = target.getBoundingBox().minY;
        double requiredDelta = Math.max(0.15D, minTargetYDelta.getValue());
        return (targetMinY - playerMinY) >= requiredDelta;
    }

    /**
     * Peak Descent / Apex Juggle Detonation Window:
     * - Crystal must be 0.5 to 3.6 blocks below the enemy's feet (`verticalAboveCrystal` in `[0.5 .. 3.6]`)
     *   and within 1.65 blocks horizontally so the blast vector launches straight upward.
     * - Detonates right as the opponent's upward momentum stalls at apex or starts descending (`vel.y <= 0.12D`).
     */
    private boolean isEnemyInJuggleApexOrDescentWindow(LivingEntity target, EndCrystal crystal) {
        if (target == null || crystal == null) {
            return false;
        }
        double dx = target.getX() - crystal.getX();
        double dz = target.getZ() - crystal.getZ();
        if ((dx * dx + dz * dz) > 2.75D) { // Keep horizontal offset tight so knockback doesn't push sideways
            return false;
        }

        double verticalAboveCrystal = target.getBoundingBox().minY - crystal.getY();
        if (verticalAboveCrystal < -0.35D || verticalAboveCrystal > 3.65D) {
            return false;
        }

        Vec3 vel = target.getDeltaMovement();
        // Wait while opponent is still accelerating upward fast; break at apex (vel.y <= 0.12) or during descent
        if (!target.onGround() && vel.y > 0.12D && verticalAboveCrystal > 0.90D) {
            return false;
        }
        return true;
    }

    // ==================== Direct Placement Actions ====================

    private boolean placeObsidianOnSupport(
            Minecraft client,
            BlockPos targetObsidianPos,
            BlockPos supportPos,
            Direction supportFace
    ) {
        if (targetObsidianPos == null || supportPos == null || supportFace == null) {
            return false;
        }
        int obsidianSlot = findHotbarSlot(client, Items.OBSIDIAN);
        if (obsidianSlot < 0 || obsidianSlot >= 9) {
            return false;
        }

        selectHotbarSlotDirect(client, obsidianSlot);

        Vec3 hitVec = new Vec3(
                supportPos.getX() + 0.5D + supportFace.getStepX() * 0.5D,
                supportPos.getY() + 0.5D + supportFace.getStepY() * 0.5D,
                supportPos.getZ() + 0.5D + supportFace.getStepZ() * 0.5D
        );
        setActiveAim(client, hitVec, currentTarget);

        BlockHitResult hitResult = new BlockHitResult(hitVec, supportFace, supportPos, false);
        InteractionResult res = client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hitResult);
        client.player.swing(InteractionHand.MAIN_HAND);
        return res.consumesAction() || client.player.getInventory().getSelectedSlot() == obsidianSlot;
    }

    private boolean placeCrystalOnBase(Minecraft client, BlockPos basePos) {
        if (basePos == null || !isCrystalBaseBlock(client, basePos)) {
            return false;
        }
        if (!canPlaceCrystalOnBaseWithFullHitboxClearance(client, basePos)) {
            return false;
        }

        InteractionHand placeHand = InteractionHand.MAIN_HAND;
        int crystalSlot = findHotbarSlot(client, Items.END_CRYSTAL);

        if (client.player.getOffhandItem().is(Items.END_CRYSTAL)) {
            placeHand = InteractionHand.OFF_HAND;
        } else if (crystalSlot >= 0 && crystalSlot < 9) {
            if (autoSwitchCrystal.getValue() || isJuggling || client.player.getInventory().getSelectedSlot() == crystalSlot) {
                selectHotbarSlotDirect(client, crystalSlot);
            } else {
                return false;
            }
        } else {
            return false;
        }

        Vec3 hitVec = new Vec3(basePos.getX() + 0.5D, basePos.getY() + 1.0D, basePos.getZ() + 0.5D);
        setActiveAim(client, hitVec, currentTarget);

        BlockHitResult placeHit = new BlockHitResult(hitVec, Direction.UP, basePos, false);
        InteractionResult res = client.gameMode.useItemOn(client.player, placeHand, placeHit);
        client.player.swing(placeHand);
        return res.consumesAction() || placeHand == InteractionHand.OFF_HAND || client.player.getInventory().getSelectedSlot() == crystalSlot;
    }

    // ==================== Direct Hotbar Slot Mechanics (Exact AutoMace Pattern) ====================

    private int findHotbarSlot(Minecraft client, Item item) {
        if (client == null || client.player == null || client.player.getInventory() == null) {
            return -1;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && (stack.is(item) || stack.getItem() == item)) {
                return i;
            }
        }
        return -1;
    }

    private void selectHotbarSlotDirect(Minecraft client, int targetSlot) {
        if (client == null || client.player == null || targetSlot < 0 || targetSlot >= 9) {
            return;
        }
        int current = client.player.getInventory().getSelectedSlot();
        if (current != targetSlot) {
            if (savedHotbarSlot == -1) {
                savedHotbarSlot = current;
            }
            client.player.getInventory().setSelectedSlot(targetSlot);
            if (client.player.connection != null) {
                client.player.connection.send(new ServerboundSetCarriedItemPacket(targetSlot));
            }
        }
    }

    private void restoreHotbarSlotIfNeeded(Minecraft client) {
        if (client != null && client.player != null && savedHotbarSlot >= 0 && savedHotbarSlot < 9) {
            if (client.player.getInventory().getSelectedSlot() != savedHotbarSlot) {
                client.player.getInventory().setSelectedSlot(savedHotbarSlot);
                if (client.player.connection != null) {
                    client.player.connection.send(new ServerboundSetCarriedItemPacket(savedHotbarSlot));
                }
            }
        }
        savedHotbarSlot = -1;
    }

    // ==================== Direct Target & Crystal Scanning ====================

    private LivingEntity findOptimalTarget(Minecraft client, double maxRange) {
        if (isJuggling && juggleTargetPlayer != null && juggleTargetPlayer.isAlive()
                && isValidTarget(client, juggleTargetPlayer)
                && client.player.distanceToSqr(juggleTargetPlayer) <= (maxRange * maxRange)) {
            return juggleTargetPlayer;
        }

        double maxRangeSq = maxRange * maxRange;
        LivingEntity bestPlayer = null;
        double bestPlayerDistSq = Double.MAX_VALUE;
        LivingEntity bestOther = null;
        double bestOtherDistSq = Double.MAX_VALUE;

        for (Entity rawEntity : client.level.entitiesForRendering()) {
            if (!(rawEntity instanceof LivingEntity entity) || entity == client.player) {
                continue;
            }
            if (!isValidTarget(client, entity)) {
                continue;
            }
            double distSq = client.player.distanceToSqr(entity);
            if (distSq > maxRangeSq) {
                continue;
            }
            if (entity instanceof Player) {
                if (distSq < bestPlayerDistSq) {
                    bestPlayerDistSq = distSq;
                    bestPlayer = entity;
                }
            } else if (distSq < bestOtherDistSq) {
                bestOtherDistSq = distSq;
                bestOther = entity;
            }
        }
        return bestPlayer != null ? bestPlayer : bestOther;
    }

    public boolean isValidTarget(Minecraft client, LivingEntity entity) {
        if (entity == null || client == null || entity == client.player || !entity.isAlive() || entity.isSpectator()) {
            return false;
        }
        if (entity.getHealth() <= 0.0F || entity.isDeadOrDying()) {
            return false;
        }
        if (entity instanceof Player player) {
            if (!targets.isSelected("Players")) {
                return false;
            }
            if (FriendManager.isFriend(player) || FriendUtils.isFriend(player.getName().getString())) {
                return false;
            }
            return !AntiBot.shouldIgnore(player);
        }
        if (entity instanceof ArmorStand) {
            return targets.isSelected("Armor Stands");
        }
        if (entity instanceof Animal) {
            return targets.isSelected("Animals") || targets.isSelected("Mobs");
        }
        if (entity instanceof Mob) {
            return targets.isSelected("Mobs");
        }
        return false;
    }

    private EndCrystal findBestCrystalToDetonate(Minecraft client, LivingEntity target, double reach) {
        if (target == null || !target.isAlive() || !isTargetElevatedAbovePlayer(client, target)) {
            return null;
        }
        double reachSq = reach * reach;
        Vec3 eyePos = client.player.getEyePosition(1.0F);
        EndCrystal best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (Entity rawEntity : client.level.entitiesForRendering()) {
            if (!(rawEntity instanceof EndCrystal crystal) || !crystal.isAlive()) {
                continue;
            }
            double distToEyeSq = eyePos.distanceToSqr(crystal.position());
            if (distToEyeSq > reachSq) {
                continue;
            }
            if (!canSafelyDetonate(client, crystal)) {
                continue;
            }

            if (lockedBasePos != null && crystal.blockPosition().equals(lockedBasePos.above())) {
                return crystal;
            }

            double dx = target.getX() - crystal.getX();
            double dz = target.getZ() - crystal.getZ();
            double horizDistSq = dx * dx + dz * dz;
            double vertGap = target.getBoundingBox().minY - crystal.getY();
            if (horizDistSq <= 2.75D && vertGap >= -0.35D && vertGap <= 3.65D && horizDistSq < bestDistSq) {
                bestDistSq = horizDistSq;
                best = crystal;
            }
        }
        return best;
    }

    private boolean canSafelyDetonate(Minecraft client, EndCrystal crystal) {
        if (!antiSuicide.getValue()) {
            return true;
        }
        float selfDmg = calculateCrystalExplosionDamage(
                client, crystal.getX(), crystal.getY(), crystal.getZ(), client.player, null
        );
        float myHp = client.player.getHealth() + client.player.getAbsorptionAmount();
        return selfDmg < (myHp - 0.5F);
    }

    // ==================== 2-4 Block Vertical Window & <= 1.5m Horizontal Offset Search ====================

    private static final class PlacementCandidate {
        final BlockPos basePos;
        final BlockPos supportPos;
        final Direction supportFace;

        PlacementCandidate(BlockPos basePos, BlockPos supportPos, Direction supportFace) {
            this.basePos = basePos;
            this.supportPos = supportPos;
            this.supportFace = supportFace;
        }
    }

    /**
     * Checks if an existing Obsidian/Bedrock base is positioned 2 to 4 blocks below the enemy's feet
     * (spawning the End Crystal 1 to 3 blocks below their feet) with horizontal offset <= 1.5 blocks.
     */
    private BlockPos findExistingCrystalBaseUnderTarget(Minecraft client, LivingEntity enemy, double reach) {
        boolean hasCrystal = findHotbarSlot(client, Items.END_CRYSTAL) != -1
                || client.player.getOffhandItem().is(Items.END_CRYSTAL);
        if (!hasCrystal) {
            return null;
        }

        double reachSq = reach * reach;
        Vec3 eyePos = client.player.getEyePosition(1.0F);
        double playerMinY = client.player.getBoundingBox().minY;
        double enemyFootY = enemy.getBoundingBox().minY;

        // 1. Check lockedBasePos first if it has full 2.0x2.0 crystal hitbox clearance and <= 1.5m horizontal offset
        if (lockedBasePos != null && isCrystalBaseBlock(client, lockedBasePos)) {
            double expX = lockedBasePos.getX() + 0.5D;
            double expY = lockedBasePos.getY() + 1.0D;
            double expZ = lockedBasePos.getZ() + 0.5D;
            double dx = enemy.getX() - expX;
            double dz = enemy.getZ() - expZ;
            double horizSq = dx * dx + dz * dz;
            double crystalBelowFeet = enemyFootY - expY;
            if (expY >= playerMinY - 0.1D
                    && crystalBelowFeet >= 0.75D && crystalBelowFeet <= 3.5D
                    && horizSq <= 2.25D // <= 1.5 blocks horizontal offset
                    && eyePos.distanceToSqr(expX, expY, expZ) <= reachSq
                    && canPlaceCrystalOnBaseWithFullHitboxClearance(client, lockedBasePos)) {
                return lockedBasePos;
            }
        }

        Vec3 vel = enemy.getDeltaMovement();
        double predX = enemy.getX() + (enemy.onGround() ? 0.0D : Mth.clamp(vel.x * 1.5D, -0.8D, 0.8D));
        double predZ = enemy.getZ() + (enemy.onGround() ? 0.0D : Mth.clamp(vel.z * 1.5D, -0.8D, 0.8D));

        int ex = Mth.floor(predX);
        int ey = Mth.floor(enemyFootY);
        int ez = Mth.floor(predZ);

        BlockPos bestBase = null;
        float bestScore = -10000.0F;

        // Scan dy from -4 to -2 (Obsidian 2 to 4 blocks below enemy's feet -> Crystal 1 to 3 blocks below feet!)
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -4; dy <= -2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int bx = ex + dx;
                    int by = ey + dy;
                    int bz = ez + dz;

                    double expX = bx + 0.5D;
                    double expY = by + 1.0D;
                    double expZ = bz + 0.5D;

                    // Crystal must be 1 to 3 blocks below enemy feet and never below our own ground level
                    double crystalBelowFeet = enemyFootY - expY;
                    if (expY < playerMinY - 0.1D || crystalBelowFeet < 0.75D || crystalBelowFeet > 3.5D) {
                        continue;
                    }
                    if (eyePos.distanceToSqr(expX, expY, expZ) > reachSq) {
                        continue;
                    }

                    // Keep horizontal offset under 1.5 blocks (1.5^2 = 2.25) so knockback goes straight up
                    double horizDistSq = (expX - predX) * (expX - predX) + (expZ - predZ) * (expZ - predZ);
                    if (horizDistSq > 2.25D) {
                        continue;
                    }

                    BlockPos candidateBase = new BlockPos(bx, by, bz);
                    if (!isCrystalBaseBlock(client, candidateBase)
                            || !canPlaceCrystalOnBaseWithFullHitboxClearance(client, candidateBase)) {
                        continue;
                    }

                    float score = evaluateJugglePlacementScore(client, enemy, expX, expY, expZ, horizDistSq, crystalBelowFeet, true);
                    if (score > bestScore) {
                        bestScore = score;
                        bestBase = candidateBase;
                    }
                }
            }
        }

        return (bestBase != null && bestScore > -9000.0F) ? bestBase : null;
    }

    /**
     * Finds the single best spot to place 1 Obsidian block 2 to 4 blocks below the airborne enemy's feet
     * (`dy` in `-4 .. -2`, horizontal offset `<= 1.5` blocks) with strict player/enemy hitbox clearance
     * so the block NEVER vanishes!
     */
    private PlacementCandidate findBestNewObsidianCandidate(Minecraft client, LivingEntity enemy, double reach) {
        boolean hasCrystal = findHotbarSlot(client, Items.END_CRYSTAL) != -1
                || client.player.getOffhandItem().is(Items.END_CRYSTAL);
        boolean hasObsidian = findHotbarSlot(client, Items.OBSIDIAN) != -1;
        if (!hasCrystal || !hasObsidian) {
            return null;
        }

        double reachSq = reach * reach;
        Vec3 eyePos = client.player.getEyePosition(1.0F);
        double playerMinY = client.player.getBoundingBox().minY;
        double enemyFootY = enemy.getBoundingBox().minY;

        Vec3 vel = enemy.getDeltaMovement();
        double predX = enemy.getX() + (enemy.onGround() ? 0.0D : Mth.clamp(vel.x * 1.5D, -0.8D, 0.8D));
        double predZ = enemy.getZ() + (enemy.onGround() ? 0.0D : Mth.clamp(vel.z * 1.5D, -0.8D, 0.8D));

        int ex = Mth.floor(predX);
        int ey = Mth.floor(enemyFootY);
        int ez = Mth.floor(predZ);

        BlockPos bestBase = null;
        BlockPos bestSupport = null;
        Direction bestFace = Direction.UP;
        float bestScore = -10000.0F;

        // Strictly dy in -4 .. -2 (Obsidian 2 to 4 blocks below enemy's feet -> Crystal 1 to 3 blocks below feet!)
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -4; dy <= -2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int bx = ex + dx;
                    int by = ey + dy;
                    int bz = ez + dz;

                    double expX = bx + 0.5D;
                    double expY = by + 1.0D;
                    double expZ = bz + 0.5D;

                    double obsidianBelowFeet = enemyFootY - by;
                    double crystalBelowFeet = enemyFootY - expY;
                    // Enforce 2 to 4 blocks below feet for Obsidian (1 to 3 blocks below feet for Crystal)
                    if (by < Mth.floor(playerMinY)
                            || obsidianBelowFeet < 1.75D || obsidianBelowFeet > 4.5D
                            || crystalBelowFeet < 0.75D || crystalBelowFeet > 3.5D) {
                        continue;
                    }
                    if (eyePos.distanceToSqr(expX, expY, expZ) > reachSq) {
                        continue;
                    }

                    // Keep horizontal offset under 1.5 blocks (1.5^2 = 2.25)
                    double horizDistSq = (expX - predX) * (expX - predX) + (expZ - predZ) * (expZ - predZ);
                    if (horizDistSq > 2.25D) {
                        continue;
                    }

                    BlockPos candidateBase = new BlockPos(bx, by, bz);
                    if (!canPlaceObsidianAndCrystalWithFullClearance(client, enemy, candidateBase)) {
                        continue;
                    }

                    SupportFacePair support = findSolidSupportForObsidian(client, candidateBase);
                    if (support == null) {
                        continue;
                    }

                    float score = evaluateJugglePlacementScore(client, enemy, expX, expY, expZ, horizDistSq, crystalBelowFeet, false);
                    if (support.face == Direction.UP) {
                        score += 3.0F;
                    }
                    if (score > bestScore) {
                        bestScore = score;
                        bestBase = candidateBase;
                        bestSupport = support.pos;
                        bestFace = support.face;
                    }
                }
            }
        }

        if (bestBase == null || bestScore <= -9000.0F) {
            return null;
        }
        return new PlacementCandidate(bestBase, bestSupport, bestFace);
    }

    /**
     * Scores a juggle crystal base:
     * - Sweet spot is Obsidian 2 blocks below feet (Crystal 1 block below feet, `crystalBelowFeet ~ 1.0 - 1.5`).
     * - Strongly rewards tight horizontal alignment (`horizDistSq <= 1.0`) so knockback is purely vertical.
     */
    private float evaluateJugglePlacementScore(
            Minecraft client,
            LivingEntity enemy,
            double expX,
            double expY,
            double expZ,
            double horizDistToEnemySq,
            double crystalBelowFeet,
            boolean alreadyBase
    ) {
        float selfDmg = calculateCrystalExplosionDamage(client, expX, expY, expZ, client.player, null);
        if (antiSuicide.getValue()) {
            float myHp = client.player.getHealth() + client.player.getAbsorptionAmount();
            if (selfDmg >= (myHp - 0.5F)) {
                return -10000.0F;
            }
        }

        float targetDmg = calculateCrystalExplosionDamage(client, expX, expY, expZ, enemy, null);
        float verticalSweetSpotBonus = (float) Math.max(0.0D, 14.0D - Math.abs(crystalBelowFeet - 1.25D) * 4.5D);
        float horizontalAlignmentBonus = (float) Math.max(0.0D, 10.0D - horizDistToEnemySq * 4.5D);
        float effectiveDamage = Math.max(targetDmg, verticalSweetSpotBonus);

        if (effectiveDamage < minTargetDamage.getValue().floatValue()) {
            return -10000.0F;
        }

        float score = effectiveDamage + horizontalAlignmentBonus + verticalSweetSpotBonus - (selfDmg * 0.45F);
        if (alreadyBase) {
            score += 5.0F;
        }
        return score;
    }

    private static final class SupportFacePair {
        final BlockPos pos;
        final Direction face;

        SupportFacePair(BlockPos pos, Direction face) {
            this.pos = pos;
            this.face = face;
        }
    }

    private SupportFacePair findSolidSupportForObsidian(Minecraft client, BlockPos obsidianPos) {
        BlockPos below = obsidianPos.below();
        BlockState belowState = client.level.getBlockState(below);
        if (!belowState.isAir() && !belowState.canBeReplaced()) {
            return new SupportFacePair(below, Direction.UP);
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = obsidianPos.relative(dir);
            BlockState neighborState = client.level.getBlockState(neighbor);
            if (!neighborState.isAir() && !neighborState.canBeReplaced()) {
                return new SupportFacePair(neighbor, dir.getOpposite());
            }
        }
        return null;
    }

    private boolean isCrystalBaseBlock(Minecraft client, BlockPos pos) {
        Block block = client.level.getBlockState(pos).getBlock();
        return block == Blocks.OBSIDIAN || block == Blocks.BEDROCK;
    }

    /**
     * Verifies that the End Crystal's full 2.0 x 2.0 x 2.0 bounding box above `basePos`
     * does NOT intersect any living entity (including the airborne opponent's 0.6 x 1.8 hitbox)
     * so the server never rejects the crystal placement packet.
     */
    private boolean canPlaceCrystalOnBaseWithFullHitboxClearance(Minecraft client, BlockPos basePos) {
        BlockPos above = basePos.above();
        BlockState aboveState = client.level.getBlockState(above);
        if (!aboveState.isAir() && !aboveState.canBeReplaced()) {
            return false;
        }
        double bx = above.getX();
        double by = above.getY();
        double bz = above.getZ();
        // End Crystal has a 2.0 x 2.0 horizontal footprint centered at (bx + 0.5, bz + 0.5) -> [bx - 0.5 .. bx + 1.5]
        AABB crystalBox = new AABB(bx - 0.5D, by, bz - 0.5D, bx + 1.5D, by + 2.0D, bz + 1.5D);
        List<Entity> blocking = client.level.getEntities(
                (Entity) null,
                crystalBox,
                e -> e != null && e.isAlive() && !(e instanceof ItemEntity) && !(e instanceof ExperienceOrb)
        );
        return blocking.isEmpty();
    }

    /**
     * Verifies that BOTH the Obsidian block (1x1x1, plus server velocity tolerance) AND the
     * 2.0 x 2.0 x 2.0 End Crystal hitbox above it are completely clear of the opponent's and our
     * player's hitboxes — preventing vanishing ghost blocks after a hit!
     */
    private boolean canPlaceObsidianAndCrystalWithFullClearance(
            Minecraft client,
            LivingEntity enemy,
            BlockPos obsidianPos
    ) {
        BlockState state = client.level.getBlockState(obsidianPos);
        if (!state.isAir() && !state.canBeReplaced()) {
            return false;
        }
        BlockPos above = obsidianPos.above();
        BlockState aboveState = client.level.getBlockState(above);
        if (!aboveState.isAir() && !aboveState.canBeReplaced()) {
            return false;
        }

        double ox = obsidianPos.getX();
        double oy = obsidianPos.getY();
        double oz = obsidianPos.getZ();

        // Check Obsidian block box inflated by 0.25m against enemy and local player (including 1-tick backtrack)
        AABB obsidianCheckBox = new AABB(ox - 0.20D, oy - 0.15D, oz - 0.20D, ox + 1.20D, oy + 1.25D, oz + 1.20D);
        if (client.player.getBoundingBox().intersects(obsidianCheckBox)) {
            return false;
        }
        if (enemy != null) {
            if (enemy.getBoundingBox().intersects(obsidianCheckBox)) {
                return false;
            }
            Vec3 vel = enemy.getDeltaMovement();
            if (enemy.getBoundingBox().move(-vel.x, -vel.y, -vel.z).intersects(obsidianCheckBox)) {
                return false;
            }
        }

        // Check full 2.0 x 2.0 x 2.0 End Crystal hitbox above the Obsidian block
        AABB crystalBox = new AABB(ox - 0.5D, oy + 1.0D, oz - 0.5D, ox + 1.5D, oy + 3.0D, oz + 1.5D);
        List<Entity> blocking = client.level.getEntities(
                (Entity) null,
                obsidianCheckBox.minmax(crystalBox),
                e -> e != null && e.isAlive() && !(e instanceof ItemEntity) && !(e instanceof ExperienceOrb)
        );
        return blocking.isEmpty();
    }

    // ==================== Explosion Damage Calculation ====================

    public float calculateCrystalExplosionDamage(
            Minecraft client,
            double expX,
            double expY,
            double expZ,
            LivingEntity entity,
            BlockPos ignoredSimulatedBase
    ) {
        if (client == null || client.level == null || entity == null) {
            return 0.0F;
        }
        if (entity instanceof Player p && p.getAbilities().invulnerable) {
            return 0.0F;
        }

        final float explosionDiameter = 12.0F;
        AABB box = entity.getBoundingBox();
        double dx = entity.getX() - expX;
        double dy = box.minY - expY;
        double dz = entity.getZ() - expZ;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq > 144.0D) {
            return 0.0F;
        }

        double normalizedDist = Math.sqrt(distSq) / (double) explosionDiameter;
        if (normalizedDist > 1.0D) {
            return 0.0F;
        }

        Vec3 raisedExplosionPos = new Vec3(expX, expY + 0.20D, expZ);
        Vec3 chestPoint = new Vec3(entity.getX(), box.minY + (box.maxY - box.minY) * 0.65D, entity.getZ());
        boolean blocked = client.level.clip(new ClipContext(
                chestPoint, raisedExplosionPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity
        )).getType() != HitResult.Type.MISS;

        double exposure = blocked ? 0.45D : 0.85D;
        double impact = (1.0D - normalizedDist) * exposure;
        if (impact <= 0.0D) {
            return 0.0F;
        }

        float rawDamage = (float) Math.floor(((impact * impact + impact) / 2.0D) * 7.0D * (double) explosionDiameter + 1.0D);
        Difficulty difficulty = client.level.getDifficulty();
        if (entity instanceof Player) {
            rawDamage = switch (difficulty) {
                case PEACEFUL -> 0.0F;
                case EASY -> Math.min(rawDamage / 2.0F + 1.0F, rawDamage);
                case NORMAL -> rawDamage;
                case HARD -> rawDamage * 1.5F;
            };
        }

        float armor = (float) entity.getArmorValue();
        float toughness = (float) entity.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        float f = 2.0F + toughness / 4.0F;
        float g = Mth.clamp(armor - rawDamage / f, armor * 0.2F, 20.0F);
        return Math.max(0.0F, rawDamage * (1.0F - g / 25.0F));
    }

    // ==================== Rotation & Humanized Smooth Legit Aim ====================

    private void setActiveAim(Minecraft client, Vec3 targetVec, Entity targetEntity) {
        this.activeAimPoint = targetVec;
        this.activeAimEntity = targetEntity;
        if ("Silent".equalsIgnoreCase(aimMode.getValue())) {
            rotateSilentTowardsVec(client, targetVec, targetEntity);
        }
    }

    private void rotateSilentTowardsVec(Minecraft client, Vec3 targetVec, Entity targetEntity) {
        if (client == null || client.player == null || targetVec == null) {
            return;
        }
        Vec3 eyePos = client.player.getEyePosition(1.0F);
        Vec3 diff = targetVec.subtract(eyePos);
        if (diff.lengthSqr() < 1.0E-5D) {
            return;
        }
        Angle targetAngle = MathAngle.fromVec3d(diff);
        LivingEntity livingTarget = (targetEntity instanceof LivingEntity living) ? living : currentTarget;
        AngleConnection.INSTANCE.rotateTo(
                new Angle.VecRotation(targetAngle, targetAngle.toVector()),
                livingTarget,
                1,
                new AngleConfig(new LinearConstructor(), true, true),
                TaskPriority.HIGH_IMPORTANCE_1,
                this
        );
    }

    private void applyHumanizedLegitAim(Minecraft client, Vec3 targetVec, float dtSeconds) {
        if (client == null || client.player == null || targetVec == null) {
            return;
        }
        double dx = targetVec.x - client.player.getX();
        double dy = targetVec.y - client.player.getEyeY();
        double dz = targetVec.z - client.player.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDist < 0.001D) {
            return;
        }

        float targetYaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float targetPitch = (float) (-(Mth.atan2(dy, horizontalDist) * (180.0D / Math.PI)));
        if (Float.isNaN(targetYaw) || Float.isNaN(targetPitch)
                || Float.isInfinite(targetYaw) || Float.isInfinite(targetPitch)) {
            return;
        }

        float currentYaw = client.player.getYRot();
        float currentPitch = client.player.getXRot();

        float yawError = Mth.wrapDegrees(targetYaw - currentYaw);
        float pitchError = Mth.clamp(targetPitch, -89.0F, 89.0F) - currentPitch;
        float totalError = (float) Math.hypot(yawError, pitchError);

        if (totalError < 0.45F) {
            yawVelocity *= 0.4F;
            pitchVelocity *= 0.4F;
            return;
        }

        float smoothness = Mth.clamp(legitAimSmoothing.getValue().floatValue(), 0.06F, 0.75F);
        float omega = 8.0F + smoothness * 22.0F;

        float ease = Mth.clamp(totalError / 28.0F, 0.35F, 1.0F);
        float smoothStep = 1.0F - (float) Math.exp(-omega * ease * dtSeconds);

        float desiredStepYaw = yawError * smoothStep;
        float desiredStepPitch = pitchError * smoothStep;

        yawVelocity = Mth.lerp(0.55F, yawVelocity, desiredStepYaw);
        pitchVelocity = Mth.lerp(0.55F, pitchVelocity, desiredStepPitch);

        float gcd = computeMouseGcd(client);
        float stepYaw = gcd > 1.0E-6F ? Math.round(yawVelocity / gcd) * gcd : yawVelocity;
        float stepPitch = gcd > 1.0E-6F ? Math.round(pitchVelocity / gcd) * gcd : pitchVelocity;

        float nextYaw = currentYaw + stepYaw;
        float nextPitch = Mth.clamp(currentPitch + stepPitch, -89.0F, 89.0F);
        if (Float.isNaN(nextYaw) || Float.isNaN(nextPitch)
                || Float.isInfinite(nextYaw) || Float.isInfinite(nextPitch)) {
            return;
        }

        client.player.setYRot(nextYaw);
        client.player.setXRot(nextPitch);
    }

    private float computeMouseGcd(Minecraft client) {
        try {
            if (client != null && client.options != null && client.options.sensitivity() != null) {
                double sens = client.options.sensitivity().get();
                double f = sens * 0.6D + 0.2D;
                return (float) (f * f * f * 1.2D);
            }
        } catch (Throwable ignored) {
        }
        return 0.0F;
    }
}
