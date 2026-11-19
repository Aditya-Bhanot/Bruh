package attrium.ru.api.module.impl.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import attrium.ru.api.events.annotation.SubscribeEvent;
import attrium.ru.api.events.impl.DrawEvent;
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
import attrium.ru.api.module.impl.combat.aura.util.MathUtils;
import attrium.ru.api.module.impl.combat.aura.util.TaskPriority;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.ModeSetting;
import attrium.ru.api.settings.impl.MultiModeSetting;
import attrium.ru.api.settings.impl.NumberSetting;
import attrium.ru.manager.FriendManager;
import attrium.ru.utils.repository.friend.FriendUtils;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Standalone SilentAura Module (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * Built with the exact independent architecture as AutoMace (`MaceTarget`):
 * - Zero dependency on RotationManager, TickPipelineManager, TargetManager, SlotManager, or ActionManager.
 * - Direct `client.level.entitiesForRendering()` target scanning with Stage-1 `FriendManager.isFriend()` filtering.
 * - Direct `AngleConnection.INSTANCE.rotateTo(...)` silent rotation and smooth `Legit` camera aim.
 * - Direct `client.gameMode.attack(client.player, target)` + `client.player.swing(InteractionHand.MAIN_HAND)` execution.
 * - Direct `getSelectedSlot()` / `setSelectedSlot()` hotbar weapon selection.
 */
public class SilentAura extends Module {
    private static SilentAura instance;

    // ==================== Settings ====================

    private final MultiModeSetting targets = register(new MultiModeSetting(
            "Targets",
            "Entity categories to target for SilentAura attacks",
            new String[]{"Players", "Mobs", "Hostile", "Animals", "Armor Stands"},
            "Players", "Mobs", "Hostile"
    ));

    private final ModeSetting rotationMode = register(new ModeSetting(
            "Rotation Mode",
            "Silent (server-side AngleConnection rotation) or Legit (smooth camera aim)",
            "Silent",
            "Silent", "Legit", "None"
    ));

    private final ModeSetting targetPriority = register(new ModeSetting(
            "Target Priority",
            "Sorting metric for selecting the active combat target",
            "Distance",
            "Distance", "Angle", "Health"
    ));

    private final NumberSetting targetRange = register(new NumberSetting(
            "Target Range",
            "Maximum reach distance (2.5 - 6.0 blocks) to attack targets",
            4.2D, 2.5D, 6.0D, 0.1D
    ));

    private final NumberSetting maxFov = register(new NumberSetting(
            "Max FOV",
            "Field-of-view cone limit (30 - 180 degrees)",
            180.0D, 30.0D, 180.0D, 5.0D
    ));

    private final NumberSetting minCps = register(new NumberSetting(
            "Min CPS",
            "Minimum randomized attacks per second when 1.9+ Cooldown Gate is off",
            10.0D, 1.0D, 20.0D, 0.5D
    ));

    private final NumberSetting maxCps = register(new NumberSetting(
            "Max CPS",
            "Maximum randomized attacks per second when 1.9+ Cooldown Gate is off",
            14.0D, 1.0D, 20.0D, 0.5D
    ));

    private final BooleanSetting respectCooldown = register(new BooleanSetting(
            "1.9+ Cooldown Gate",
            "Wait for 1.9+ weapon attack cooldown (90% charge) before striking",
            true
    ));

    private final BooleanSetting onlyCrits = register(new BooleanSetting(
            "Criticals Only",
            "When airborne and falling, wait for critical fall velocity before striking",
            false
    ));

    private final BooleanSetting moveFix = register(new BooleanSetting(
            "MoveFix",
            "Align movement inputs with silent server rotation",
            true
    ));

    private final NumberSetting aimSmoothing = register(new NumberSetting(
            "Aim Smoothing",
            "Camera tracking speed when Rotation Mode is set to Legit",
            0.65D, 0.15D, 1.0D, 0.05D
    ));

    private final BooleanSetting autoWeaponSwap = register(new BooleanSetting(
            "Auto Weapon Swap",
            "Automatically select the best Sword or Axe on the hotbar (0-8) when attacking",
            false
    ));

    private final BooleanSetting throughWalls = register(new BooleanSetting(
            "Through Walls",
            "Allow targeting and attacking entities through solid blocks",
            false
    ));

    private final BooleanSetting ignoreFriends = register(new BooleanSetting(
            "Ignore Friends",
            "Never target whitelisted friends",
            true
    ));

    // ==================== State Fields ====================

    private LivingEntity currentTarget = null;
    private float spoofedYaw = 0.0F;
    private float spoofedPitch = 0.0F;
    private boolean silentSpoofingActive = false;
    private long nextAttackTimestampMs = 0L;
    private int savedHotbarSlot = -1;
    private boolean swappedWeaponThisSession = false;

    public SilentAura() {
        super("SilentAura", "Standalone Silent/Legit Aura with direct entity scan & AutoMace slot mechanics", ModuleCategory.COMBAT);
        instance = this;
    }

    public static SilentAura getInstance() {
        if (instance == null) {
            instance = new SilentAura();
        }
        return instance;
    }

    public LivingEntity getTarget() {
        return isEnabled() ? currentTarget : null;
    }

    public LivingEntity getCurrentTarget() {
        return getTarget();
    }

    public float getSpoofedYaw() {
        return spoofedYaw;
    }

    public float getSpoofedPitch() {
        return spoofedPitch;
    }

    public boolean isSilentSpoofingActive() {
        return isEnabled() && silentSpoofingActive;
    }

    public boolean isMoveFixActive() {
        return isEnabled() && silentSpoofingActive && Boolean.TRUE.equals(moveFix.getValue());
    }

    @Override
    protected void onEnable() {
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.player != null) {
            spoofedYaw = client.player.getYRot();
            spoofedPitch = client.player.getXRot();
        }
        resetState();
    }

    @Override
    protected void onDisable() {
        Minecraft client = Minecraft.getInstance();
        restoreHotbarSlotIfNeeded(client);
        releaseSilentRotation();
        resetState();
    }

    private void resetState() {
        currentTarget = null;
        silentSpoofingActive = false;
        nextAttackTimestampMs = 0L;
        savedHotbarSlot = -1;
        swappedWeaponThisSession = false;
    }

    private void releaseSilentRotation() {
        if (silentSpoofingActive) {
            try {
                AngleConnection.INSTANCE.startReturning();
            } catch (Throwable ignored) {
            }
        }
        silentSpoofingActive = false;
    }

    // ==================== Events (Exact AutoMace Pattern) ====================

    @SubscribeEvent
    public void onTick(TickEvent.Pre event) {
        if (!this.isEnabled()) {
            return;
        }
        processAuraTick(event != null ? event.getClient() : Minecraft.getInstance());
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
        if (currentTarget == null || !currentTarget.isAlive()) {
            return;
        }
        if ("Silent".equalsIgnoreCase(rotationMode.getValue())) {
            rotateSilentTowardsTarget(client, currentTarget);
        }
    }

    @SubscribeEvent
    public void onDraw(DrawEvent event) {
        if (!this.isEnabled()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.level == null
                || currentTarget == null || !currentTarget.isAlive()) {
            return;
        }
        if ("Legit".equalsIgnoreCase(rotationMode.getValue())) {
            applyHitboxClampedCameraAim(client, currentTarget);
        }
    }

    // ==================== Core Combat Loop ====================

    public void runPhase2PreMotion(Minecraft client) {
        processAuraTick(client);
    }

    private void processAuraTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null || client.gameMode == null) {
            resetState();
            return;
        }

        if (minCps.getValue() > maxCps.getValue()) {
            maxCps.setValue(minCps.getValue());
        }

        if (client.screen instanceof AbstractContainerScreen<?> || client.screen != null || client.player.isUsingItem()) {
            currentTarget = null;
            releaseSilentRotation();
            return;
        }

        double reach = Math.max(2.5D, targetRange.getValue());
        double preAimRange = Math.min(6.0D, reach + 1.2D);

        currentTarget = findOptimalTarget(client, preAimRange);
        if (currentTarget == null || !currentTarget.isAlive()) {
            if (swappedWeaponThisSession) {
                restoreHotbarSlotIfNeeded(client);
                swappedWeaponThisSession = false;
            }
            releaseSilentRotation();
            return;
        }

        // Rotate toward target using the selected rotation mode
        if ("Silent".equalsIgnoreCase(rotationMode.getValue())) {
            rotateSilentTowardsTarget(client, currentTarget);
        } else if ("Legit".equalsIgnoreCase(rotationMode.getValue())) {
            applyHitboxClampedCameraAim(client, currentTarget);
        }

        // Check if target is within actual attack reach
        double distSq = getSquaredDistanceToEntityBox(client, currentTarget);
        if (distSq > (reach * reach)) {
            return;
        }

        if (!throughWalls.getValue() && !client.player.hasLineOfSight(currentTarget)) {
            return;
        }

        if (onlyCrits.getValue()
                && !client.player.onGround()
                && client.player.fallDistance <= 0.0F
                && client.player.getDeltaMovement().y >= -0.08D) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        if (nowMs < nextAttackTimestampMs) {
            return;
        }

        if (respectCooldown.getValue()) {
            if (client.player.getAttackStrengthScale(0.5F) < 0.90F) {
                return;
            }
            nextAttackTimestampMs = nowMs + 45L;
        } else {
            nextAttackTimestampMs = nowMs + computeNextAttackDelayMs();
        }

        executeAttack(client, currentTarget);
    }

    private void executeAttack(Minecraft client, LivingEntity target) {
        if (client.player == null || client.gameMode == null || target == null || !target.isAlive()) {
            return;
        }

        if (autoWeaponSwap.getValue()) {
            int weaponSlot = findBestWeaponHotbarSlot(client);
            if (weaponSlot >= 0 && weaponSlot < 9 && client.player.getInventory().getSelectedSlot() != weaponSlot) {
                if (!swappedWeaponThisSession) {
                    savedHotbarSlot = client.player.getInventory().getSelectedSlot();
                }
                client.player.getInventory().setSelectedSlot(weaponSlot);
                if (client.player.connection != null) {
                    client.player.connection.send(new ServerboundSetCarriedItemPacket(weaponSlot));
                }
                swappedWeaponThisSession = true;
            }
        }

        if ("Silent".equalsIgnoreCase(rotationMode.getValue())) {
            rotateSilentTowardsTarget(client, target);
        }

        client.gameMode.attack(client.player, target);
        client.player.swing(InteractionHand.MAIN_HAND);
        AutoCrystal.notifyMeleeHitEntity(target);
    }

    // ==================== Direct Hotbar Weapon Finder (AutoMace Style) ====================

    private int findBestWeaponHotbarSlot(Minecraft client) {
        if (client == null || client.player == null || client.player.getInventory() == null) {
            return -1;
        }
        int axeSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.is(ItemTags.SWORDS)) {
                return i;
            }
            if (axeSlot == -1 && stack.getItem() instanceof AxeItem) {
                axeSlot = i;
            }
        }
        return axeSlot;
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

    // ==================== Direct Target Scanner (AutoMace Style) ====================

    private LivingEntity findOptimalTarget(Minecraft client, double maxRange) {
        if (client == null || client.player == null || client.level == null) {
            return null;
        }

        double maxRangeSq = maxRange * maxRange;
        double maxFovDeg = maxFov.getValue();
        String priorityMode = targetPriority.getValue();
        boolean wallCheck = !throughWalls.getValue();

        double eyeX = client.player.getX();
        double eyeY = client.player.getEyeY();
        double eyeZ = client.player.getZ();
        float camYaw = client.player.getYRot();
        float camPitch = client.player.getXRot();

        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;

        for (Entity rawEntity : client.level.entitiesForRendering()) {
            if (!(rawEntity instanceof LivingEntity entity) || entity == client.player) {
                continue;
            }
            if (!isValidTarget(client, entity)) {
                continue;
            }

            double distSq = getSquaredDistanceToEntityBox(client, entity);
            if (distSq > maxRangeSq) {
                continue;
            }

            if (wallCheck && !client.player.hasLineOfSight(entity)) {
                continue;
            }

            AABB box = entity.getBoundingBox();
            double cx = (box.minX + box.maxX) * 0.5D - eyeX;
            double cy = (box.minY + box.maxY) * 0.5D - eyeY;
            double cz = (box.minZ + box.maxZ) * 0.5D - eyeZ;
            double horiz = Math.hypot(cx, cz);
            float tYaw = (float) (Math.toDegrees(Math.atan2(cz, cx)) - 90.0D);
            float tPitch = (float) (-Math.toDegrees(Math.atan2(cy, Math.max(1.0E-4D, horiz))));
            double fovAngle = Math.hypot(Mth.wrapDegrees(tYaw - camYaw), tPitch - camPitch);

            if (maxFovDeg < 179.0D && fovAngle > maxFovDeg) {
                continue;
            }

            double score;
            if ("Angle".equalsIgnoreCase(priorityMode)) {
                score = fovAngle;
            } else if ("Health".equalsIgnoreCase(priorityMode)) {
                score = entity.getHealth() + entity.getAbsorptionAmount();
            } else {
                score = distSq;
            }

            if (score < bestScore) {
                bestScore = score;
                best = entity;
            }
        }
        return best;
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
            if (ignoreFriends.getValue()
                    && (FriendManager.isFriend(player) || FriendUtils.isFriend(player.getName().getString()))) {
                return false;
            }
            return !AntiBot.shouldIgnore(player);
        }
        if (entity instanceof ArmorStand) {
            return targets.isSelected("Armor Stands");
        }
        if (entity instanceof Enemy || (entity instanceof NeutralMob neutral && neutral.isAngry())) {
            return targets.isSelected("Hostile") || targets.isSelected("Mobs");
        }
        if (entity instanceof Animal) {
            return targets.isSelected("Animals") || targets.isSelected("Mobs");
        }
        if (entity instanceof Mob) {
            return targets.isSelected("Mobs") || targets.isSelected("Hostile");
        }
        return false;
    }

    private double getSquaredDistanceToEntityBox(Minecraft client, LivingEntity entity) {
        double eyeX = client.player.getX();
        double eyeY = client.player.getEyeY();
        double eyeZ = client.player.getZ();
        AABB box = entity.getBoundingBox();
        double inflate = entity.getPickRadius() + 0.15D;
        double dx = Mth.clamp(eyeX, box.minX - inflate, box.maxX + inflate) - eyeX;
        double dy = Mth.clamp(eyeY, box.minY - inflate, box.maxY + inflate) - eyeY;
        double dz = Mth.clamp(eyeZ, box.minZ - inflate, box.maxZ + inflate) - eyeZ;
        return dx * dx + dy * dy + dz * dz;
    }

    // ==================== Rotation Helpers (Exact AutoMace Style) ====================

    private void rotateSilentTowardsTarget(Minecraft client, LivingEntity target) {
        if (client == null || client.player == null || target == null) {
            return;
        }
        double eyeX = client.player.getX();
        double eyeY = client.player.getEyeY();
        double eyeZ = client.player.getZ();
        AABB box = target.getBoundingBox();
        double mx = (box.maxX - box.minX) * 0.16D;
        double my = (box.maxY - box.minY) * 0.16D;
        double mz = (box.maxZ - box.minZ) * 0.16D;

        double cx = Mth.clamp(eyeX, box.minX + mx, box.maxX - mx) - eyeX;
        double cy = Mth.clamp(eyeY, box.minY + my, box.maxY - my) - eyeY;
        double cz = Mth.clamp(eyeZ, box.minZ + mz, box.maxZ - mz) - eyeZ;

        double horiz = Math.hypot(cx, cz);
        if (horiz > 1.0E-4D) {
            this.spoofedYaw = (float) (Math.toDegrees(Math.atan2(cz, cx)) - 90.0D);
            this.spoofedPitch = Mth.clamp((float) (-Math.toDegrees(Math.atan2(cy, horiz))), -89.5F, 89.5F);
            this.silentSpoofingActive = true;
        }

        boolean moveCorrection = Boolean.TRUE.equals(moveFix.getValue());
        Angle targetAngle = MathAngle.fromVec3d(new Vec3(cx, cy, cz));
        AngleConnection.INSTANCE.rotateTo(
                new Angle.VecRotation(targetAngle, targetAngle.toVector()),
                target,
                1,
                new AngleConfig(new LinearConstructor(), moveCorrection, moveCorrection),
                TaskPriority.HIGH_IMPORTANCE_1,
                this
        );
    }

    private void applyHitboxClampedCameraAim(Minecraft client, LivingEntity target) {
        if (client == null || client.player == null || target == null) {
            return;
        }
        double eyeX = client.player.getX();
        double eyeY = client.player.getEyeY();
        double eyeZ = client.player.getZ();
        AABB box = target.getBoundingBox();

        double marginX = (box.maxX - box.minX) * 0.16D;
        double marginY = (box.maxY - box.minY) * 0.16D;
        double marginZ = (box.maxZ - box.minZ) * 0.16D;

        double aimX = Mth.clamp(eyeX, box.minX + marginX, box.maxX - marginX);
        double aimY = Mth.clamp(eyeY, box.minY + marginY, box.maxY - marginY);
        double aimZ = Mth.clamp(eyeZ, box.minZ + marginZ, box.maxZ - marginZ);

        double dx = aimX - eyeX;
        double dy = aimY - eyeY;
        double dz = aimZ - eyeZ;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDist < 0.001D) {
            return;
        }

        float targetYaw = (float) (Mth.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float targetPitch = (float) (-(Mth.atan2(dy, horizontalDist) * (180.0D / Math.PI)));

        float playerYaw = client.player.getYRot();
        float playerPitch = client.player.getXRot();
        float yawDelta = Mth.wrapDegrees(targetYaw - playerYaw);
        float pitchDelta = targetPitch - playerPitch;

        float factor = Mth.clamp(aimSmoothing.getValue().floatValue(), 0.15F, 1.0F);
        float calculatedYaw = playerYaw + yawDelta * factor;
        float calculatedPitch = Mth.clamp(playerPitch + pitchDelta * factor, -90.0F, 90.0F);
        if (Float.isNaN(calculatedYaw) || Float.isNaN(calculatedPitch)
                || Float.isInfinite(calculatedYaw) || Float.isInfinite(calculatedPitch)) {
            return;
        }
        client.player.setYRot(calculatedYaw);
        client.player.setXRot(calculatedPitch);
    }

    private long computeNextAttackDelayMs() {
        double min = Math.max(1.0D, minCps.getValue());
        double max = Math.max(min, maxCps.getValue());
        double cps = min == max ? min : ThreadLocalRandom.current().nextDouble(min, max);
        return Math.max(40L, Math.round(1000.0D / cps));
    }

    // ==================== Compatibility Static Helpers ====================

    public static void computeRotatedMoveFixInputs(
            float rawForward,
            float rawSideways,
            float clientYaw,
            float spoofedYaw,
            float[] outForwardAndSideways
    ) {
        if (Math.abs(rawForward) < 1.0E-4F && Math.abs(rawSideways) < 1.0E-4F) {
            outForwardAndSideways[0] = 0.0F;
            outForwardAndSideways[1] = 0.0F;
            return;
        }
        float deltaRad = (clientYaw - spoofedYaw) * ((float) Math.PI / 180.0F);
        float cos = Mth.cos(deltaRad);
        float sin = Mth.sin(deltaRad);
        outForwardAndSideways[0] = Math.round(rawForward * cos + rawSideways * sin);
        outForwardAndSideways[1] = Math.round(rawSideways * cos - rawForward * sin);
    }

    public void applyMoveFixToLocalPlayer(LocalPlayer player) {
    }

    public static void onMovementInputUpdate(LocalPlayer player) {
    }

    public static void applyHardwareMouseGcdZeroAlloc(
            Minecraft mc,
            float deltaYaw,
            float deltaPitch,
            float[] outQuantized
    ) {
        double gcd = computeMouseSensitivityGcd(mc);
        if (gcd <= 1.0E-7D) {
            outQuantized[0] = deltaYaw;
            outQuantized[1] = deltaPitch;
            return;
        }
        float g = (float) gcd;
        outQuantized[0] = Math.round(deltaYaw / g) * g;
        outQuantized[1] = Math.round(deltaPitch / g) * g;
    }

    public static double computeMouseSensitivityGcd(Minecraft mc) {
        try {
            if (mc != null && mc.options != null && mc.options.sensitivity() != null) {
                double sens = mc.options.sensitivity().get();
                double f = sens * 0.6D + 0.2D;
                return f * f * f * 8.0D * 0.15D;
            }
        } catch (Throwable ignored) {
        }
        return MathUtils.computeGcd();
    }
}
