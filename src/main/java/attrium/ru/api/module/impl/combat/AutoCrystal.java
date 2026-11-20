package attrium.ru.api.module.impl.combat;

import attrium.ru.api.events.annotation.SubscribeEvent;
import attrium.ru.api.events.impl.DrawEvent;
import attrium.ru.api.events.impl.PacketEvent;
import attrium.ru.api.events.impl.TickEvent;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.ModuleCategory;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.ModeSetting;
import attrium.ru.api.settings.impl.MultiModeSetting;
import attrium.ru.api.settings.impl.NumberSetting;
import attrium.ru.manager.FriendManager;
import attrium.ru.manager.RotationManager;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Fixed & Optimized AutoCrystal for Attrium (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * Key fixes over previous revision:
 * 1. Works on flat ground AND elevated targets (no hard early return blocking flat-ground PvP).
 * 2. Eliminates the 0.35-1.74 block Y-delta dead zone in obsidian/crystal candidate search.
 * 3. Never stalls placement when a crystal in range is unsafe or low-damage.
 * 4. Strict res.consumesAction() checks prevent false-positive obsidian/crystal locks.
 * 5. Crystal Optimizer works independently of Hit Crystal mode.
 * 6. Silent Aim routes through RotationManager (RotationPriority.AUTO_CRYSTAL) with NaN/Inf guards.
 * 7. Sends ServerboundSetCarriedItemPacket before updating local selectedSlot for server sync.
 * 8. Per-tick deduplication prevents double execution across TickEvent.Pre and Phase 2 pipeline.
 */
public class AutoCrystal extends Module {

    private static AutoCrystal instance;

    private final MultiModeSetting targets = register(new MultiModeSetting(
            "Targets",
            "Entity categories to target for AutoCrystal",
            new String[]{"Players", "Mobs", "Animals", "Armor Stands"},
            "Players", "Mobs"
    ));
    private final ModeSetting aimMode = register(new ModeSetting(
            "Aim Mode",
            "Legit (smooth camera glide), Silent (RotationManager spoof), or None",
            "Legit", "Legit", "Silent", "None"
    ));
    private final BooleanSetting hitCrystal = register(new BooleanSetting(
            "Hit Crystal",
            "Only activate air-juggle crystal combo after melee-hitting an enemy",
            false
    ));
    private final BooleanSetting autoObsidian = register(new BooleanSetting(
            "Auto Obsidian",
            "Place an Obsidian base near the target if no valid crystal base exists",
            true
    ));
    private final NumberSetting obsidianDelayTicks = register(new NumberSetting(
            "Obsidian Delay (Ticks)",
            "Cooldown ticks after placing an Obsidian block",
            4.0D, 1.0D, 20.0D, 1.0D
    ));
    private final NumberSetting placeDelayTicks = register(new NumberSetting(
            "Place Delay (Ticks)",
            "Minimum client ticks between End Crystal placements",
            1.0D, 0.0D, 10.0D, 1.0D
    ));
    private final NumberSetting breakDelayTicks = register(new NumberSetting(
            "Break Delay (Ticks)",
            "Minimum client ticks before detonating a placed End Crystal",
            1.0D, 0.0D, 10.0D, 1.0D
    ));
    private final NumberSetting minTargetYDelta = register(new NumberSetting(
            "Min Target Y-Delta",
            "Minimum height above player feet for Hit Crystal air-juggle mode (also boosts base priority)",
            0.25D, 0.15D, 2.5D, 0.05D
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
            4.0D, 2.0D, 6.0D, 0.1D
    ));
    private final NumberSetting minTargetDamage = register(new NumberSetting(
            "Min Target Damage",
            "Minimum damage required to place or detonate a crystal near the target",
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

    // Runtime state
    private LivingEntity currentTarget;
    private LivingEntity juggleTargetPlayer;
    private boolean isJuggling;
    private int juggleLastHitTick = -100;
    private static final int JUGGLE_TIMEOUT_TICKS = 45;

    private BlockPos lockedBasePos;
    private int lastProcessedTick = -1;
    private int placeTickTimer = 100;
    private int breakTickTimer = 100;
    private int obsidianTickTimer = 100;

    // Humanized click pacing
    private long lastActionTimeMs = 0L;
    private long nextActionDelayMs = 0L;
    private long continuousStartMs = 0L;
    private static final long CONTINUOUS_GAP_THRESHOLD_MS = 500L;
    private static final long CONTINUOUS_FATIGUE_TRIGGER_MS = 6000L;

    // Hotbar restoration
    private int savedHotbarSlot = -1;

    // Aim state
    private Vec3 activeAimPoint;
    private Entity activeAimEntity;
    private float yawVelocity = 0.0F;
    private float pitchVelocity = 0.0F;
    private long lastDrawNano = 0L;
    private boolean holdingSilentRotation = false;

    public AutoCrystal() {
        super("AutoCrystal", "Fast, reliable ground & air-juggle Crystal PvP module", ModuleCategory.COMBAT);
        instance = this;
    }

    public static AutoCrystal getInstance() {
        return instance;
    }

    public boolean isCrystalOptimizerEnabled() {
        return Boolean.TRUE.equals(crystalOptimizer.getValue());
    }

    public boolean isHitCrystalEnabled() {
        return Boolean.TRUE.equals(hitCrystal.getValue());
    }

    public static void notifyMeleeHitEntity(Entity attackedEntity) {
        AutoCrystal ac = instance;
        if (ac == null || !ac.isEnabled() || !ac.isHitCrystalEnabled()) {
            return;
        }
        if (!(attackedEntity instanceof LivingEntity living) || !living.isAlive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) {
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
        if (!isHitCrystalEnabled()) {
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
        releaseAim(client);
        resetState();
    }

    private void resetState() {
        this.currentTarget = null;
        this.juggleTargetPlayer = null;
        this.isJuggling = false;
        this.juggleLastHitTick = -100;
        this.lockedBasePos = null;
        this.lastProcessedTick = -1;
        this.placeTickTimer = 100;
        this.breakTickTimer = 100;
        this.obsidianTickTimer = 100;
        this.lastActionTimeMs = 0L;
        this.nextActionDelayMs = 0L;
        this.continuousStartMs = 0L;
        this.savedHotbarSlot = -1;
        this.activeAimPoint = null;
        this.activeAimEntity = null;
        this.yawVelocity = 0.0F;
        this.pitchVelocity = 0.0F;
        this.lastDrawNano = 0L;
        this.holdingSilentRotation = false;
    }

    @SubscribeEvent
    public void onTick(TickEvent.Pre event) {
        if (!this.isEnabled()) {
            return;
        }
        Minecraft client = event != null ? event.getClient() : Minecraft.getInstance();
        processCrystalTick(client);
    }

    public void onPreTick(TickEvent.Pre event) {
        onTick(event);
    }

    public void runPhase2PreMotion(Minecraft client) {
        if (!this.isEnabled()) {
            return;
        }
        processCrystalTick(client);
    }

    @SubscribeEvent
    public void onDraw(DrawEvent event) {
        if (!this.isEnabled() || !"Legit".equalsIgnoreCase(aimMode.getValue())) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.level == null || activeAimPoint == null) {
            lastDrawNano = 0L;
            return;
        }
        if (client.screen instanceof AbstractContainerScreen) {
            return;
        }
        long now = System.nanoTime();
        float dtSeconds = lastDrawNano == 0L ? (1.0F / 60.0F) : Mth.clamp((now - lastDrawNano) / 1.0E9F, 0.001F, 0.05F);
        lastDrawNano = now;
        applyHumanizedLegitAim(client, activeAimPoint, dtSeconds);
    }

    @SubscribeEvent
    public void onPacket(PacketEvent event) {
        if (!this.isEnabled() || event == null || !event.isSend()) {
            return;
        }
        if (!isHitCrystalEnabled() && !isCrystalOptimizerEnabled()) {
            return;
        }
        Packet<?> packet = event.getPacket();
        if (packet instanceof ServerboundInteractPacket interactPacket) {
            interactPacket.dispatch(new AutoCrystalAttackHandler(this));
        }
    }

    private void processCrystalTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null || client.gameMode == null) {
            return;
        }

        // Deduplicate if both TickEvent.Pre and TickPipelineManager invoke this on the same tick
        int tick = client.player.tickCount;
        if (tick == lastProcessedTick) {
            return;
        }
        lastProcessedTick = tick;

        placeTickTimer++;
        breakTickTimer++;
        obsidianTickTimer++;

        if (client.screen instanceof AbstractContainerScreen) {
            releaseAim(client);
            return;
        }

        // Handle Hit Crystal mode gating cleanly
        if (isHitCrystalEnabled()) {
            if (!isJuggling || juggleTargetPlayer == null || !juggleTargetPlayer.isAlive()
                    || (tick - juggleLastHitTick) > JUGGLE_TIMEOUT_TICKS) {
                isJuggling = false;
                juggleTargetPlayer = null;
                currentTarget = null;
                lockedBasePos = null;
                if (Boolean.TRUE.equals(swapBackToWeapon.getValue())) {
                    restoreHotbarSlotIfNeeded(client);
                }
                releaseAim(client);
                return;
            }
            // Relocate crystal base if target drifted horizontally beyond range
            double maxRange = getClampedRange();
            if (juggleTargetPlayer.distanceTo(client.player) > maxRange + 2.5D) {
                lockedBasePos = null;
            }
            currentTarget = juggleTargetPlayer;
        } else {
            isJuggling = false;
            juggleTargetPlayer = null;
            currentTarget = findOptimalTarget(client);
        }

        if (currentTarget == null || !currentTarget.isAlive()) {
            lockedBasePos = null;
            if (Boolean.TRUE.equals(swapBackToWeapon.getValue())) {
                restoreHotbarSlotIfNeeded(client);
            }
            releaseAim(client);
            return;
        }

        // In Hit Crystal air-juggle mode, wait until target is elevated above player feet
        if (isHitCrystalEnabled() && !isTargetElevatedAbovePlayer(client, currentTarget)) {
            releaseAim(client);
            return;
        }

        // Validate locked obsidian base
        if (lockedBasePos != null) {
            double distSq = client.player.getEyePosition().distanceToSqr(
                    lockedBasePos.getX() + 0.5D, lockedBasePos.getY() + 1.0D, lockedBasePos.getZ() + 0.5D
            );
            double range = getClampedRange();
            if (!isCrystalBaseBlock(client, lockedBasePos) || distSq > range * range) {
                lockedBasePos = null;
            }
        }

        // ------------------------------------------------------------------
        // PRIORITY 1: Detonate an existing viable End Crystal
        // ------------------------------------------------------------------
        EndCrystal bestCrystal = findBestCrystalToDetonate(client, currentTarget);
        if (bestCrystal != null) {
            boolean apexReady = !isJuggling || isEnemyInJuggleApexOrDescentWindow(currentTarget, bestCrystal);
            if (apexReady && breakTickTimer >= breakDelayTicks.getValue().intValue() && isHumanizedClickReady()) {
                if (detonateCrystal(client, bestCrystal)) {
                    breakTickTimer = 0;
                    recordActionTimestamp();
                    if (Boolean.TRUE.equals(swapBackToWeapon.getValue())) {
                        restoreHotbarSlotIfNeeded(client);
                    }
                    return;
                }
            } else if (apexReady) {
                // Keep aim locked onto the crystal while waiting strictly on break delay
                setActiveAim(client, bestCrystal.getBoundingBox().getCenter(), bestCrystal);
                return;
            }
            // If apex isn't ready in Hit Crystal mode, fall through only if a better placement is needed
        }

        // ------------------------------------------------------------------
        // PRIORITY 2: Place End Crystal on best existing Obsidian/Bedrock base
        // ------------------------------------------------------------------
        if (placeTickTimer >= placeDelayTicks.getValue().intValue() && isHumanizedClickReady()) {
            BlockPos placeBase = findBestCrystalBase(client, currentTarget);
            if (placeBase != null) {
                if (placeCrystalOnBase(client, placeBase)) {
                    placeTickTimer = 0;
                    lockedBasePos = placeBase.immutable();
                    recordActionTimestamp();
                    if (Boolean.TRUE.equals(swapBackToWeapon.getValue())) {
                        restoreHotbarSlotIfNeeded(client);
                    }
                    return;
                }
            }
        }

        // ------------------------------------------------------------------
        // PRIORITY 3: Place Obsidian base if Auto Obsidian is enabled
        // ------------------------------------------------------------------
        if (Boolean.TRUE.equals(autoObsidian.getValue())
                && obsidianTickTimer >= obsidianDelayTicks.getValue().intValue()
                && isHumanizedClickReady()) {
            ObsidianCandidate candidate = findBestNewObsidianCandidate(client, currentTarget);
            if (candidate != null) {
                if (placeObsidianOnSupport(client, candidate.basePos, candidate.supportPos, candidate.supportFace)) {
                    obsidianTickTimer = 0;
                    lockedBasePos = candidate.basePos.immutable();
                    recordActionTimestamp();
                    if (Boolean.TRUE.equals(swapBackToWeapon.getValue())) {
                        restoreHotbarSlotIfNeeded(client);
                    }
                    return;
                }
            }
        }

        // Nothing actionable this tick
        if (bestCrystal == null) {
            if (Boolean.TRUE.equals(swapBackToWeapon.getValue())) {
                restoreHotbarSlotIfNeeded(client);
            }
            releaseAim(client);
        }
    }

    private double getClampedRange() {
        return Mth.clamp(crystalRange.getValue(), 2.0D, 6.0D);
    }

    private boolean isTargetElevatedAbovePlayer(Minecraft client, LivingEntity target) {
        double targetMinY = target.getBoundingBox().minY;
        double playerMinY = client.player.getBoundingBox().minY;
        return (targetMinY - playerMinY) >= Math.max(0.15D, minTargetYDelta.getValue());
    }

    private boolean isEnemyInJuggleApexOrDescentWindow(LivingEntity target, EndCrystal crystal) {
        double dx = target.getX() - crystal.getX();
        double dz = target.getZ() - crystal.getZ();
        if ((dx * dx + dz * dz) > 4.0D) {
            return false;
        }
        double verticalAboveCrystal = target.getBoundingBox().minY - crystal.getY();
        if (verticalAboveCrystal < -0.5D || verticalAboveCrystal > 4.0D) {
            return false;
        }
        // Detonate once target slows near apex or begins falling
        return target.onGround() || target.getDeltaMovement().y <= 0.14D || verticalAboveCrystal <= 0.90D;
    }

    private LivingEntity findOptimalTarget(Minecraft client) {
        double maxSearchDist = getClampedRange() + 4.5D;
        double maxSearchDistSq = maxSearchDist * maxSearchDist;

        LivingEntity best = null;
        double bestDistSq = maxSearchDistSq;

        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            if (!isValidTarget(client, living)) {
                continue;
            }
            double dSq = client.player.distanceToSqr(living);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                best = living;
            }
        }
        return best;
    }

    private boolean isValidTarget(Minecraft client, LivingEntity entity) {
        if (entity == null || entity == client.player || !entity.isAlive() || entity.getHealth() <= 0.0F) {
            return false;
        }
        if (entity instanceof Player player) {
            if (player.isCreative() || player.isSpectator()) {
                return false;
            }
            if (FriendManager.isFriend(player)) {
                return false;
            }
            if (AntiBot.shouldIgnore(player)) {
                return false;
            }
            return targets.isSelected("Players");
        }
        if (entity instanceof ArmorStand) {
            return targets.isSelected("Armor Stands");
        }
        if (entity instanceof Animal) {
            return targets.isSelected("Animals");
        }
        if (entity instanceof Mob) {
            return targets.isSelected("Mobs");
        }
        return false;
    }

    private EndCrystal findBestCrystalToDetonate(Minecraft client, LivingEntity target) {
        double range = getClampedRange();
        double rangeSq = range * range;
        Vec3 eyePos = client.player.getEyePosition();
        float minDmg = minTargetDamage.getValue().floatValue();

        EndCrystal best = null;
        float bestScore = -Float.MAX_VALUE;

        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof EndCrystal crystal) || !crystal.isAlive()) {
                continue;
            }
            if (eyePos.distanceToSqr(crystal.getBoundingBox().getCenter()) > rangeSq) {
                continue;
            }
            if (!canSafelyDetonate(client, crystal)) {
                continue;
            }
            float targetDmg = calculateCrystalExplosionDamage(
                    client, crystal.getX(), crystal.getY(), crystal.getZ(), target, null
            );
            if (targetDmg < minDmg) {
                continue;
            }
            float selfDmg = calculateCrystalExplosionDamage(
                    client, crystal.getX(), crystal.getY(), crystal.getZ(), client.player, null
            );
            float score = targetDmg - selfDmg * 0.55F;
            if (score > bestScore) {
                bestScore = score;
                best = crystal;
            }
        }
        return best;
    }

    private boolean detonateCrystal(Minecraft client, EndCrystal crystal) {
        if (client == null || client.player == null || client.gameMode == null || crystal == null || !crystal.isAlive()) {
            return false;
        }
        Vec3 aimVec = crystal.getBoundingBox().getCenter();
        setActiveAim(client, aimVec, crystal);
        client.gameMode.attack(client.player, crystal);
        client.player.swing(InteractionHand.MAIN_HAND);

        if (isCrystalOptimizerEnabled()) {
            crystal.discard();
            crystal.setRemoved(Entity.RemovalReason.KILLED);
            crystal.onClientRemoval();
        }
        return true;
    }

    private BlockPos findBestCrystalBase(Minecraft client, LivingEntity target) {
        double range = getClampedRange();
        double rangeSq = range * range;
        Vec3 eyePos = client.player.getEyePosition();
        float minDmg = minTargetDamage.getValue().floatValue();
        double playerMinY = client.player.getBoundingBox().minY;

        // Predict target position slightly when airborne
        Vec3 targetPos = predictTargetPos(target);
        BlockPos center = BlockPos.containing(targetPos.x, targetPos.y, targetPos.z);

        BlockPos bestPos = null;
        float bestScore = -Float.MAX_VALUE;

        // Check locked base first as a fast path
        if (lockedBasePos != null && isCrystalBaseBlock(client, lockedBasePos)
                && canPlaceCrystalOnBaseWithFullHitboxClearance(client, lockedBasePos)) {
            float score = scoreCrystalBase(client, target, lockedBasePos, eyePos, rangeSq, minDmg, playerMinY);
            if (score > -Float.MAX_VALUE / 2.0F) {
                bestPos = lockedBasePos;
                bestScore = score + 0.35F; // small hysteresis bonus to avoid jitter
            }
        }

        int hRadius = 3;
        for (int dx = -hRadius; dx <= hRadius; dx++) {
            for (int dz = -hRadius; dz <= hRadius; dz++) {
                for (int dy = -3; dy <= 1; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!isCrystalBaseBlock(client, pos)) {
                        continue;
                    }
                    if (!canPlaceCrystalOnBaseWithFullHitboxClearance(client, pos)) {
                        continue;
                    }
                    float score = scoreCrystalBase(client, target, pos, eyePos, rangeSq, minDmg, playerMinY);
                    if (score > bestScore) {
                        bestScore = score;
                        bestPos = pos.immutable();
                    }
                }
            }
        }

        return bestPos;
    }

    private float scoreCrystalBase(
            Minecraft client,
            LivingEntity target,
            BlockPos basePos,
            Vec3 eyePos,
            double rangeSq,
            float minDmg,
            double playerMinY
    ) {
        double hitX = basePos.getX() + 0.5D;
        double hitY = basePos.getY() + 1.0D;
        double hitZ = basePos.getZ() + 0.5D;

        if (eyePos.distanceToSqr(hitX, hitY, hitZ) > rangeSq) {
            return -Float.MAX_VALUE;
        }

        float selfDmg = calculateCrystalExplosionDamage(client, hitX, hitY, hitZ, client.player, basePos);
        if (Boolean.TRUE.equals(antiSuicide.getValue())) {
            float myHp = client.player.getHealth() + client.player.getAbsorptionAmount();
            if (selfDmg >= (myHp - 0.5F)) {
                return -Float.MAX_VALUE;
            }
        }

        float targetDmg = calculateCrystalExplosionDamage(client, hitX, hitY, hitZ, target, basePos);
        if (targetDmg < minDmg) {
            return -Float.MAX_VALUE;
        }

        float score = targetDmg - (selfDmg * 0.65F);
        // Prefer bases where explosion is at or below enemy feet and above/safe for player
        if (hitY <= target.getBoundingBox().minY + 0.1D) {
            score += 1.25F;
        }
        if (target.getBoundingBox().minY - playerMinY >= minTargetYDelta.getValue() && hitY >= playerMinY) {
            score += 0.75F;
        }
        return score;
    }

    private ObsidianCandidate findBestNewObsidianCandidate(Minecraft client, LivingEntity target) {
        if (findHotbarSlot(client, Items.OBSIDIAN) < 0) {
            return null;
        }

        double range = getClampedRange();
        double rangeSq = range * range;
        Vec3 eyePos = client.player.getEyePosition();
        float minDmg = minTargetDamage.getValue().floatValue();
        double playerMinY = client.player.getBoundingBox().minY;

        Vec3 targetPos = predictTargetPos(target);
        BlockPos center = BlockPos.containing(targetPos.x, targetPos.y, targetPos.z);

        ObsidianCandidate best = null;
        float bestScore = -Float.MAX_VALUE;

        int hRadius = 3;
        for (int dx = -hRadius; dx <= hRadius; dx++) {
            for (int dz = -hRadius; dz <= hRadius; dz++) {
                for (int dy = -3; dy <= 0; dy++) {
                    BlockPos candidateBase = center.offset(dx, dy, dz);
                    BlockState state = client.level.getBlockState(candidateBase);
                    if (!state.canBeReplaced()) {
                        continue;
                    }
                    // Ensure no entity is standing inside the obsidian block position itself
                    AABB blockBox = new AABB(candidateBase);
                    if (!client.level.getEntities((Entity) null, blockBox, this::blocksPlacement).isEmpty()) {
                        continue;
                    }
                    // Ensure the crystal space directly above the obsidian will be clear
                    if (!canPlaceCrystalAbovePos(client, candidateBase)) {
                        continue;
                    }

                    SupportFace support = findPlaceableSupport(client, candidateBase, eyePos, rangeSq);
                    if (support == null) {
                        continue;
                    }

                    float score = scoreCrystalBase(client, target, candidateBase, eyePos, rangeSq, minDmg, playerMinY);
                    if (score > bestScore) {
                        bestScore = score;
                        best = new ObsidianCandidate(candidateBase.immutable(), support.pos, support.face);
                    }
                }
            }
        }

        return best;
    }

    private SupportFace findPlaceableSupport(Minecraft client, BlockPos targetPos, Vec3 eyePos, double rangeSq) {
        // Prefer placing against the block directly below first, then horizontal faces
        Direction[] order = new Direction[]{
                Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
        };
        for (Direction offsetDir : order) {
            BlockPos neighbor = targetPos.relative(offsetDir);
            BlockState neighborState = client.level.getBlockState(neighbor);
            if (neighborState.isAir() || neighborState.canBeReplaced()) {
                continue;
            }
            Direction clickFace = offsetDir.getOpposite();
            Vec3 hitVec = new Vec3(
                    neighbor.getX() + 0.5D + clickFace.getStepX() * 0.5D,
                    neighbor.getY() + 0.5D + clickFace.getStepY() * 0.5D,
                    neighbor.getZ() + 0.5D + clickFace.getStepZ() * 0.5D
            );
            if (eyePos.distanceToSqr(hitVec) <= rangeSq) {
                return new SupportFace(neighbor.immutable(), clickFace);
            }
        }
        return null;
    }

    private Vec3 predictTargetPos(LivingEntity target) {
        if (target.onGround()) {
            return target.position();
        }
        Vec3 vel = target.getDeltaMovement();
        // 2-tick lightweight velocity prediction for airborne targets
        return new Vec3(
                target.getX() + vel.x * 2.0D,
                target.getY() + vel.y * 2.0D - 0.08D,
                target.getZ() + vel.z * 2.0D
        );
    }

    private boolean isCrystalBaseBlock(Minecraft client, BlockPos pos) {
        BlockState state = client.level.getBlockState(pos);
        return state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK);
    }

    private boolean canPlaceCrystalOnBaseWithFullHitboxClearance(Minecraft client, BlockPos basePos) {
        if (!isCrystalBaseBlock(client, basePos)) {
            return false;
        }
        return canPlaceCrystalAbovePos(client, basePos);
    }

    private boolean canPlaceCrystalAbovePos(Minecraft client, BlockPos basePos) {
        BlockPos above = basePos.above();
        if (!client.level.getBlockState(above).isAir()) {
            return false;
        }
        // End Crystal collision check in modern Minecraft (1.13+): 1x2x1 volume above base
        AABB crystalBox = new AABB(
                above.getX(), above.getY(), above.getZ(),
                above.getX() + 1.0D, above.getY() + 2.0D, above.getZ() + 1.0D
        );
        List<Entity> blocking = client.level.getEntities((Entity) null, crystalBox, this::blocksPlacement);
        return blocking.isEmpty();
    }

    private boolean blocksPlacement(Entity entity) {
        if (entity == null || !entity.isAlive()) {
            return false;
        }
        return !(entity instanceof ItemEntity) && !(entity instanceof ExperienceOrb);
    }

    private boolean placeCrystalOnBase(Minecraft client, BlockPos basePos) {
        if (basePos == null || !canPlaceCrystalOnBaseWithFullHitboxClearance(client, basePos)) {
            return false;
        }

        InteractionHand placeHand = InteractionHand.MAIN_HAND;
        int crystalSlot = findHotbarSlot(client, Items.END_CRYSTAL);
        if (client.player.getOffhandItem().is(Items.END_CRYSTAL)) {
            placeHand = InteractionHand.OFF_HAND;
        } else if (crystalSlot >= 0 && crystalSlot < 9) {
            if (Boolean.TRUE.equals(autoSwitchCrystal.getValue())
                    || isJuggling
                    || client.player.getInventory().getSelectedSlot() == crystalSlot) {
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
        if (res.consumesAction()) {
            client.player.swing(placeHand);
            return true;
        }
        return false;
    }

    private boolean placeObsidianOnSupport(Minecraft client, BlockPos targetObsidianPos, BlockPos supportPos, Direction supportFace) {
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
        if (res.consumesAction()) {
            client.player.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        return false;
    }

    private int findHotbarSlot(Minecraft client, Item item) {
        if (client == null || client.player == null || item == null) {
            return -1;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
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
            if (client.player.connection != null) {
                client.player.connection.send(new ServerboundSetCarriedItemPacket(targetSlot));
            }
            client.player.getInventory().setSelectedSlot(targetSlot);
        }
    }

    private void restoreHotbarSlotIfNeeded(Minecraft client) {
        if (client != null && client.player != null && savedHotbarSlot >= 0 && savedHotbarSlot < 9) {
            if (client.player.getInventory().getSelectedSlot() != savedHotbarSlot) {
                if (client.player.connection != null) {
                    client.player.connection.send(new ServerboundSetCarriedItemPacket(savedHotbarSlot));
                }
                client.player.getInventory().setSelectedSlot(savedHotbarSlot);
            }
        }
        savedHotbarSlot = -1;
    }

    private boolean canSafelyDetonate(Minecraft client, EndCrystal crystal) {
        if (!Boolean.TRUE.equals(antiSuicide.getValue())) {
            return true;
        }
        float selfDmg = calculateCrystalExplosionDamage(
                client, crystal.getX(), crystal.getY(), crystal.getZ(), client.player, null
        );
        float myHp = client.player.getHealth() + client.player.getAbsorptionAmount();
        return selfDmg < (myHp - 0.5F);
    }

    private boolean isHumanizedClickReady() {
        long now = System.currentTimeMillis();
        return (now - lastActionTimeMs) >= nextActionDelayMs;
    }

    private void recordActionTimestamp() {
        long now = System.currentTimeMillis();
        if (lastActionTimeMs == 0L || (now - lastActionTimeMs) > CONTINUOUS_GAP_THRESHOLD_MS) {
            continuousStartMs = now;
        }
        lastActionTimeMs = now;
        boolean fatigued = (now - continuousStartMs) >= CONTINUOUS_FATIGUE_TRIGGER_MS;
        double baseMs = fatigued ? 120.0D : 58.0D;
        double jitter = Mth.clamp(ThreadLocalRandom.current().nextGaussian() * 10.0D, -14.0D, 22.0D);
        nextActionDelayMs = Math.max(45L, Math.round(baseMs + jitter));
    }

    private void setActiveAim(Minecraft client, Vec3 targetVec, Entity targetEntity) {
        this.activeAimPoint = targetVec;
        this.activeAimEntity = targetEntity;
        if ("Silent".equalsIgnoreCase(aimMode.getValue()) && client != null && client.player != null && targetVec != null) {
            float[] angles = computeAngles(client, targetVec);
            if (angles != null) {
                RotationManager.getInstance().submitInstantSilentRotation(
                        RotationManager.RotationPriority.AUTO_CRYSTAL,
                        angles[0],
                        angles[1],
                        true,
                        this
                );
                holdingSilentRotation = true;
            }
        }
    }

    private void releaseAim(Minecraft client) {
        this.activeAimPoint = null;
        this.activeAimEntity = null;
        if (holdingSilentRotation) {
            RotationManager.getInstance().forceReleaseSpoofing();
            holdingSilentRotation = false;
        }
    }

    private float[] computeAngles(Minecraft client, Vec3 targetVec) {
        double dx = targetVec.x - client.player.getX();
        double dy = targetVec.y - client.player.getEyeY();
        double dz = targetVec.z - client.player.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDist < 0.001D) {
            return null;
        }
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float pitch = Mth.clamp((float) (-(Mth.atan2(dy, horizontalDist) * (180.0D / Math.PI))), -89.0F, 89.0F);
        if (Float.isNaN(yaw) || Float.isNaN(pitch) || Float.isInfinite(yaw) || Float.isInfinite(pitch)) {
            return null;
        }
        return new float[]{yaw, pitch};
    }

    private void applyHumanizedLegitAim(Minecraft client, Vec3 targetVec, float dtSeconds) {
        if (client == null || client.player == null || targetVec == null) {
            return;
        }
        float[] targetAngles = computeAngles(client, targetVec);
        if (targetAngles == null) {
            return;
        }

        float currentYaw = client.player.getYRot();
        float currentPitch = client.player.getXRot();

        float yawError = Mth.wrapDegrees(targetAngles[0] - currentYaw);
        float pitchError = targetAngles[1] - currentPitch;
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

        yawVelocity = Mth.lerp(0.55F, yawVelocity, yawError * smoothStep);
        pitchVelocity = Mth.lerp(0.55F, pitchVelocity, pitchError * smoothStep);

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
        double sens = client.options.sensitivity().get() * 0.6D + 0.2D;
        return (float) (sens * sens * sens * 1.2D);
    }

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

    private record SupportFace(BlockPos pos, Direction face) {}

    private record ObsidianCandidate(BlockPos basePos, BlockPos supportPos, Direction supportFace) {}
}