package releon.ru.api.module.impl.combat.aura.attack;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import releon.ru.IMinecraft;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.events.impl.UsingItemEvent;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.module.impl.combat.Criticals;
import releon.ru.api.module.impl.combat.TriggerBot;
import releon.ru.api.module.impl.combat.aura.AngleConnection;
import releon.ru.api.module.impl.combat.aura.context.AutoRegressionContext;
import releon.ru.api.module.impl.combat.aura.target.RaycastAngle;
import releon.ru.api.module.impl.combat.aura.util.MathUtils;
import releon.ru.api.module.impl.movement.ElytraTarget;
import releon.ru.api.module.impl.player.FreeCam;
import releon.ru.mixin.accessor.LivingEntityAccessor;
import releon.ru.utils.inventory.swap.SwapExecutor;
import releon.ru.utils.inventory.interaction.PlayerInteractionHelper;
import releon.ru.utils.player.PlayerSimulation;
import releon.ru.api.module.impl.combat.aura.util.StopWatch;

public class StrikeManager implements IMinecraft {
    private static final long CRIT_STARTUP_HOLD_MS = 450L;
    private long nextAttackDelayMs = 550L;
    private final StopWatch attackTimer = new StopWatch();
    private final Pressing clickScheduler = new Pressing();
    private final SwapExecutor shieldSwapExecutor = new SwapExecutor();
    private int count = 0;
    private int tickCounter = 0;
    private int shieldSuppressedUntilTick = -1;
    private int lastShieldReleaseTick = -1;
    private boolean waitingForPostReleaseTick = false;

    void tick() {
        if (shieldSwapExecutor.isRunning()) {
            shieldSwapExecutor.tick();
        }
        tickCounter++;
        syncShieldState();
    }

    void onPacket(PacketEvent e) {
        if (!e.isSend()) {
            return;
        }

        Object packet = e.getPacket();
        if (packet instanceof ServerboundSwingPacket || packet instanceof ServerboundSetCarriedItemPacket) {
            clickScheduler.recalculate();
            return;
        }
        if (packet instanceof ServerboundMovePlayerPacket || packet instanceof ServerboundClientTickEndPacket) {
            waitingForPostReleaseTick = false;
            return;
        }
        if (packet instanceof ServerboundInteractPacket interactPacket
                && waitingForPostReleaseTick
                && isRightClickInteraction(interactPacket)) {
            e.cancel();
        }
    }

    void onUsingItem(UsingItemEvent e) {
    }

    public boolean unpressShield() {
        return false;
    }

    public void resetPendingState() {
        if (shieldSwapExecutor.isRunning()) {
            shieldSwapExecutor.cancel();
        }
        shieldSuppressedUntilTick = -1;
        lastShieldReleaseTick = -1;
        waitingForPostReleaseTick = false;
    }

    public boolean shouldCancelShieldUse(InteractionHand hand) {
        if (mc.player == null || hand == null) {
            return false;
        }
        syncShieldState();
        if (waitingForPostReleaseTick) {
            return true;
        }
        return tickCounter <= shieldSuppressedUntilTick
                && mc.player.getItemInHand(hand).getItem() == Items.SHIELD;
    }

    public boolean shouldCancelUseItemOn() {
        return waitingForPostReleaseTick;
    }

    public boolean shouldCancelEntityInteraction() {
        return waitingForPostReleaseTick;
    }

    public void handleAttack(StrikerConstructor.AttackPerpetratorConfigurable config) {
        if (canAttack(config, 1)) {
            preAttackEntity(config);
        }

        if (checkElytraMode(config) && !checkElytraRaycast(config)) {
            return;
        }
        if (shouldDelayRandomAttack(config)) {
            return;
        }

        boolean raytrace = RaycastAngle.rayTrace(config);
        boolean sprint = mc.player.isSprinting();
        if (mc.player.isSwimming()) {
            sprint = false;
        }
        PlayerSimulation simulated = PlayerSimulation.simulateLocalPlayer(0);
        boolean wallSprintBypass = sprint && shouldBypassSprintGateInTightSpace(config, simulated);
        boolean effectiveSprint = sprint && !wallSprintBypass;
        boolean canAttackNow = canAttack(config, 0);
        if (raytrace && canAttackNow && effectiveSprint && shouldDropGuiSprintForCritical(config)) {
            stopSprintForAttack();
            effectiveSprint = false;
        }

        if (raytrace && canAttackNow && !effectiveSprint) {
            if (config.isShouldUnPressShield() && waitingForPostReleaseTick) {
                return;
            }
            if (config.isShouldUnPressShield() && lastShieldReleaseTick == tickCounter) {
                return;
            }
            if (config.isShouldUnPressShield() && releaseShieldForAttack()) {
                return;
            }
            attackEntity(config);
        }
    }

    void handleTriggerAttack(StrikerConstructor.AttackPerpetratorConfigurable config, TriggerBot triggerBot) {
        if (triggerBot == null || config == null || config.getTarget() == null || !config.getTarget().isAlive()) {
            return;
        }
        if (!attackTimer.finished(triggerBot.getAttackDelayMs())) {
            return;
        }
        if (canAttackTrigger(config, triggerBot, 1)) {
            if (!preAttackEntity(config)) {
                return;
            }
        }

        boolean raytrace = RaycastAngle.rayTrace(config);
        if (raytrace && canAttackTrigger(config, triggerBot, 0)) {
            if (shouldDelayAttackAfterShieldRelease(config)) {
                return;
            }
            if (!triggerBot.shouldPassHitChance()) {
                return;
            }
            if (triggerBot.isResetSprintLegit()) {
                stopSprintForAttack();
            }
            attackEntity(config);
        }
    }

    boolean preAttackEntity(StrikerConstructor.AttackPerpetratorConfigurable config) {
        return true;
    }

    private boolean shouldDelayRandomAttack(StrikerConstructor.AttackPerpetratorConfigurable config) {
        return isRandomAttackDelayMode(config)
                && count > 0
                && !attackTimer.finished(nextAttackDelayMs);
    }

    private boolean isRandomAttackDelayMode(StrikerConstructor.AttackPerpetratorConfigurable config) {
        if (config == null || config.getAimMode() == null) {
            return false;
        }

        String aimMode = config.getAimMode().getValue();
        return "SpookyTime".equals(aimMode) || "SPAngle".equals(aimMode);
    }

    private boolean shouldDelayAttackAfterShieldRelease(StrikerConstructor.AttackPerpetratorConfigurable config) {
        if (config == null || !config.isShouldUnPressShield()) {
            return false;
        }
        if (waitingForPostReleaseTick) {
            return true;
        }
        if (lastShieldReleaseTick == tickCounter) {
            return true;
        }
        return releaseShieldForAttack();
    }

    private boolean shouldDropGuiSprintForCritical(StrikerConstructor.AttackPerpetratorConfigurable config) {
        return config != null
                && config.isOnlyCritical()
                && mc.screen != null
                && mc.player != null
                && !mc.player.isSwimming();
    }

    private void stopSprintForAttack() {
        if (mc.options != null) {
            mc.options.keySprint.setDown(false);
        }
        if (mc.player != null) {
            mc.player.setSprinting(false);
        }
    }

    public void syncShieldState() {
        if (mc.options == null || !mc.options.keyUse.isDown()) {
            shieldSuppressedUntilTick = -1;
        }
    }

    private boolean releaseShieldForAttack() {
        if (mc.player == null || mc.gameMode == null) {
            return false;
        }
        if (!mc.player.isUsingItem()
                || mc.player.getUseItem().isEmpty()
                || mc.player.getUseItem().getItem() != Items.SHIELD) {
            return false;
        }

        mc.gameMode.releaseUsingItem(mc.player);
        mc.player.stopUsingItem();
        lastShieldReleaseTick = tickCounter;
        shieldSuppressedUntilTick = tickCounter;
        waitingForPostReleaseTick = true;
        return true;
    }

    void attackEntity(StrikerConstructor.AttackPerpetratorConfigurable config) {
        mc.gameMode.attack(mc.player, config.getTarget());
        mc.player.swing(InteractionHand.MAIN_HAND);
        attackTimer.reset();
        count++;
        if (isRandomAttackDelayMode(config)) {
            nextAttackDelayMs = (long) MathUtils.getRandom(500, 600);
        }
        if (config != null && config.getAimMode() != null && "FunTime".equals(config.getAimMode().getValue())) {
            AutoRegressionContext context = AutoRegressionContext.getInstance();
            context.updateLastAttackTime();
            context.hitContentQueue();
        }
    }

    private boolean isRightClickInteraction(ServerboundInteractPacket packet) {
        final boolean[] interaction = {false};
        packet.dispatch(new ServerboundInteractPacket.Handler() {
            @Override
            public void onInteraction(InteractionHand hand) {
                interaction[0] = true;
            }

            @Override
            public void onInteraction(InteractionHand hand, Vec3 pos) {
                interaction[0] = true;
            }

            @Override
            public void onAttack() {
                interaction[0] = false;
            }
        });
        return interaction[0];
    }

    private int findAxeInHotbar() {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof AxeItem) {
                return i;
            }
        }
        return -1;
    }

    private Slot findAxeInInventory() {
        for (int i = 9; i <= 35; i++) {
            Slot slot = mc.player.inventoryMenu.getSlot(i);
            if (slot != null && !slot.getItem().isEmpty() && slot.getItem().getItem() instanceof AxeItem) {
                return slot;
            }
        }
        return null;
    }

    public boolean canAttack(StrikerConstructor.AttackPerpetratorConfigurable config, int ticks) {
        for (int i = 0; i <= ticks; i++) {
            if (canCrit(config, i)) {
                return true;
            }
        }
        return false;
    }

    public boolean canCrit(StrikerConstructor.AttackPerpetratorConfigurable config, int ticks) {
        if (mc.player == null || mc.options == null || config == null) {
            return false;
        }

        if (mc.player.isUsingItem()
                && mc.player.getUseItem().getItem() != Items.SHIELD
                && config.isEatAndAttack()) {
            return false;
        }

        if (!clickScheduler.isCooldownComplete(config.isTpsSync(), ticks)) {
            return false;
        }
        if (config.isOnlyCritical() && !hasCriticalAttackStrength(ticks)) {
            return false;
        }

        PlayerSimulation simulated = PlayerSimulation.simulateLocalPlayer(ticks);
        if (!config.isOnlyCritical()) {
            return true;
        }
        if (isAirAttackMode(simulated)) {
            return true;
        }
        if (hasMovementRestrictions(simulated)) {
            return true;
        }

        boolean tightSpaceCritical = isInsideTightSpace(simulated);
        boolean smartCritJumpRequired = isSmartCritJumpRequired();
        boolean smartCritJumpIntent = smartCritJumpRequired && isSmartCritJumpIntent();
        boolean debuffFallback = hasDebuffThatBreaksCrit(simulated);
        if (shouldHoldCriticalOnStartup(simulated, smartCritJumpRequired, smartCritJumpIntent)) {
            return false;
        }
        if (smartCritJumpRequired && !smartCritJumpIntent && !debuffFallback) {
            return !tightSpaceCritical;
        }
        if (debuffFallback) {
            return true;
        }
        if (tightSpaceCritical) {
            return isTightSpaceCriticalWindow(simulated);
        }
        return isPlayerInCriticalState(simulated, ticks);
    }

    private boolean canAttackTrigger(StrikerConstructor.AttackPerpetratorConfigurable config, TriggerBot triggerBot,
                                     int ticks) {
        for (int i = 0; i <= ticks; i++) {
            if (canCritTrigger(config, triggerBot, i)) {
                return true;
            }
        }
        return false;
    }

    private boolean canCritTrigger(StrikerConstructor.AttackPerpetratorConfigurable config, TriggerBot triggerBot, int ticks) {
        if (mc.player.isUsingItem()
                && !mc.player.getUseItem().getItem().equals(Items.SHIELD)
                && config.isEatAndAttack()) {
            return false;
        }

        if (!clickScheduler.isCooldownComplete(false, ticks)) {
            return false;
        }
        if (triggerBot.isOnlyCrits() && !hasCriticalAttackStrength(ticks)) {
            return false;
        }

        PlayerSimulation simulated = PlayerSimulation.simulateLocalPlayer(ticks);
        if (isFreeCamMode()) {
            if (triggerBot.isOnlyCrits()) {
                return isFreeCamCritWindow(simulated);
            }
            return true;
        }
        if (isAirAttackMode(simulated)) {
            return true;
        }

        if (triggerBot.isOnlyCrits()) {
            if (hasMovementRestrictions(simulated)) {
                return true;
            }

            boolean tightSpaceCritical = isInsideTightSpace(simulated);
            boolean debuffFallback = hasDebuffThatBreaksCrit(simulated);
            boolean smartCritJumpRequired = triggerBot.isSmartCritsEnabled();
            boolean smartCritJumpIntent = smartCritJumpRequired && isSmartCritJumpIntent();

            if (shouldHoldCriticalOnStartupTrigger(simulated, triggerBot, smartCritJumpRequired, smartCritJumpIntent)) {
                return false;
            }

            if (smartCritJumpRequired && !smartCritJumpIntent && !debuffFallback) {
                return !tightSpaceCritical;
            }

            if (debuffFallback) {
                return true;
            }
            if (tightSpaceCritical) {
                return isTightSpaceCriticalWindow(simulated);
            }
            return isPlayerInCriticalState(simulated);
        }

        return true;
    }

    private boolean hasMovementRestrictions(PlayerSimulation simulated) {
        return simulated.hasStatusEffect(MobEffects.BLINDNESS)
                || simulated.hasStatusEffect(MobEffects.LEVITATION)
                || PlayerInteractionHelper.isBoxInBlock(simulated.boundingBox.inflate(-1e-3), Blocks.COBWEB)
                || simulated.isSubmergedInWater()
                || simulated.isInLava()
                || simulated.isClimbing()
                || simulated.player.getAbilities().flying;
    }

    private boolean isInsideTightSpace(PlayerSimulation simulated) {
        return !PlayerInteractionHelper.canChangeIntoPose(Pose.STANDING, simulated.pos)
                || simulated.horizontalCollision
                || hasHorizontalBoxPressure(simulated)
                || countBlockingSides(simulated) >= 2;
    }

    private boolean isTightSpaceCriticalWindow(PlayerSimulation simulated) {
        if (isPvpTradeCriticalWindow(simulated)) {
            return true;
        }

        float requiredFallDistance = resolveCriticalFallDistance(simulated, true);
        double requiredVelocity = resolveCriticalVelocity(simulated, true);
        if (simulated.horizontalCollision || hasHorizontalBoxPressure(simulated)) {
            requiredFallDistance = Math.min(requiredFallDistance, 0.004F);
            requiredVelocity = Math.max(requiredVelocity, -0.010D);
        }
        return !simulated.onGround
                && simulated.fallDistance > requiredFallDistance
                && simulated.velocity.y < requiredVelocity;
    }

    private boolean hasHorizontalBoxPressure(PlayerSimulation simulated) {
        if (mc.level == null || simulated == null) {
            return false;
        }
        AABB expandedBox = simulated.boundingBox.inflate(0.22D, 0.0D, 0.22D).deflate(1.0E-7D);
        return !mc.level.noCollision(simulated.player, expandedBox);
    }

    private int countBlockingSides(PlayerSimulation simulated) {
        int blockingSides = 0;
        if (isLateralSideBlocked(simulated, 0.72D, 0.0D)) {
            blockingSides++;
        }
        if (isLateralSideBlocked(simulated, -0.72D, 0.0D)) {
            blockingSides++;
        }
        if (isLateralSideBlocked(simulated, 0.0D, 0.72D)) {
            blockingSides++;
        }
        if (isLateralSideBlocked(simulated, 0.0D, -0.72D)) {
            blockingSides++;
        }
        return blockingSides;
    }

    private boolean isLateralSideBlocked(PlayerSimulation simulated, double offsetX, double offsetZ) {
        if (mc.level == null || simulated == null) {
            return false;
        }

        double footY = simulated.boundingBox.minY + 0.10D;
        double bodyY = Math.min(simulated.boundingBox.maxY - 0.10D, simulated.boundingBox.minY + 0.95D);
        return isSolidCollisionBlock(BlockPos.containing(simulated.pos.x + offsetX, footY, simulated.pos.z + offsetZ))
                || isSolidCollisionBlock(BlockPos.containing(simulated.pos.x + offsetX, bodyY, simulated.pos.z + offsetZ));
    }

    private boolean isSolidCollisionBlock(BlockPos pos) {
        if (mc.level == null) {
            return false;
        }
        BlockState state = mc.level.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(mc.level, pos).isEmpty();
    }

    private boolean shouldBypassSprintGateInTightSpace(StrikerConstructor.AttackPerpetratorConfigurable config,
                                                       PlayerSimulation simulated) {
        if (mc.player == null || config == null || simulated == null || config.getTarget() == null) {
            return false;
        }

        if (!isInsideTightSpace(simulated)) {
            return false;
        }

        boolean wallPressure = simulated.horizontalCollision
                || hasHorizontalBoxPressure(simulated)
                || countBlockingSides(simulated) >= 1;
        if (!wallPressure) {
            return false;
        }

        double distance = mc.player.distanceTo(config.getTarget());
        return distance <= Math.min(config.getMaximumRange() + 0.35F, 2.35F);
    }

    private boolean hasCriticalAttackStrength(float ticks) {
        return mc.player != null && mc.player.getAttackStrengthScale(ticks) > 0.88F;
    }

    private boolean hasCriticalRestrictions(PlayerSimulation simulated) {
        return simulated.hasStatusEffect(MobEffects.BLINDNESS)
                || simulated.hasStatusEffect(MobEffects.LEVITATION)
                || simulated.isSubmergedInWater()
                || simulated.isInLava()
                || simulated.isClimbing()
                || simulated.isSwimming
                || simulated.isFallFlying
                || simulated.player.getAbilities().flying;
    }

    private boolean hasDebuffThatBreaksCrit(PlayerSimulation simulated) {
        return simulated.hasStatusEffect(MobEffects.BLINDNESS)
                || simulated.hasStatusEffect(MobEffects.LEVITATION);
    }

    private boolean isSmartCritJumpRequired() {
        AuraModule aura = AuraModule.getInstance();
        return aura != null
                && aura.isEnabled()
                && aura.getOnlyCriticals().getValue()
                && aura.getSmartCriticals().getValue();
    }

    private boolean isSmartCritJumpIntent() {
        if (mc.player == null) {
            return false;
        }
        if (mc.options.keyJump.isDown()) {
            return true;
        }
        LivingEntityAccessor accessor = (LivingEntityAccessor) mc.player;
        if (accessor.releon$isJumping()) {
            return true;
        }
        if (accessor.releon$getJumpingCooldown() > 0 && !mc.player.onGround()) {
            return true;
        }
        return !mc.player.onGround() && mc.player.getDeltaMovement().y > 0.08D;
    }

    private boolean isAirAttackMode(PlayerSimulation simulated) {
        return simulated.isFallFlying
                || simulated.player.getAbilities().flying
                || mc.player.isFallFlying();
    }

    private boolean shouldHoldCriticalOnStartup(PlayerSimulation simulated, boolean smartCritJumpRequired, boolean smartCritJumpIntent) {
        AuraModule aura = AuraModule.getInstance();
        if (aura == null || !aura.isEnabled() || !aura.getOnlyCriticals().getValue()) {
            return false;
        }
        if (smartCritJumpRequired && !smartCritJumpIntent) {
            return false;
        }
        long activatedAt = aura.getActivationTimeMs();
        if (activatedAt <= 0L || System.currentTimeMillis() - activatedAt > CRIT_STARTUP_HOLD_MS) {
            return false;
        }
        if (simulated.onGround) {
            return false;
        }
        if (simulated.fallDistance > 0.0F) {
            return false;
        }
        return simulated.velocity.y > -0.03D;
    }

    private boolean shouldHoldCriticalOnStartupTrigger(PlayerSimulation simulated, TriggerBot triggerBot,
                                                       boolean smartCritJumpRequired, boolean smartCritJumpIntent) {
        if (triggerBot == null || !triggerBot.isEnabled() || !triggerBot.isOnlyCrits()) {
            return false;
        }
        if (smartCritJumpRequired && !smartCritJumpIntent) {
            return false;
        }

        long activatedAt = triggerBot.getActivationTimeMs();
        if (activatedAt <= 0L || System.currentTimeMillis() - activatedAt > CRIT_STARTUP_HOLD_MS) {
            return false;
        }
        if (simulated.onGround) {
            return false;
        }
        if (simulated.fallDistance > 0.0F) {
            return false;
        }
        return simulated.velocity.y > -0.03D;
    }

    private boolean isFreeCamMode() {
        FreeCam freeCam = FreeCam.getInstance();
        return freeCam != null && freeCam.isEnabled();
    }

    private boolean isFreeCamCritWindow(PlayerSimulation simulated) {
        return !simulated.onGround
                && simulated.velocity.y <= -0.01D
                && !hasCriticalRestrictions(simulated);
    }

    private boolean checkElytraMode(StrikerConstructor.AttackPerpetratorConfigurable config) {
        return AuraModule.target != null
                && AuraModule.target.isFallFlying()
                && mc.player.isFallFlying()
                && ElytraTarget.getInstance() != null
                && ElytraTarget.getInstance().isEnabled();
    }

    private boolean checkElytraRaycast(StrikerConstructor.AttackPerpetratorConfigurable config) {
        Vec3 targetVelocity = config.getTarget().getDeltaMovement();
        float leadTicks = 0;
        ElytraTarget elytraTarget = ElytraTarget.getInstance();
        if (ElytraTarget.shouldElytraTarget && elytraTarget != null) {
            leadTicks = elytraTarget.getForward();
        }
        Vec3 predictedPos = config.getTarget().position().add(targetVelocity.scale(leadTicks));
        AABB predictedBox = new AABB(
                predictedPos.x - config.getTarget().getBbWidth() / 2,
                predictedPos.y,
                predictedPos.z - config.getTarget().getBbWidth() / 2,
                predictedPos.x + config.getTarget().getBbWidth() / 2,
                predictedPos.y + config.getTarget().getBbHeight(),
                predictedPos.z + config.getTarget().getBbWidth() / 2);
        Vec3 eyePos = mc.player.getEyePosition();
        Vec3 lookVec = AngleConnection.INSTANCE.getRotation().toVector();
        return predictedBox.clip(eyePos, eyePos.add(lookVec.scale(config.getMaximumRange()))).isPresent();
    }

    private boolean isPlayerInCriticalState(PlayerSimulation simulated, int ticks) {
        if (simulated.onGround || hasCriticalRestrictions(simulated)) {
            return false;
        }

        if (isPvpTradeCriticalWindow(simulated)) {
            return true;
        }

        boolean fallingByDistance = simulated.fallDistance > resolveCriticalFallDistance(simulated, false);
        boolean fallingByVelocity = simulated.velocity.y < resolveCriticalVelocity(simulated, false);
        boolean leavingGroundSoon = ticks > 0 ? !PlayerSimulation.simulateLocalPlayer(ticks).onGround : !simulated.onGround;
        return fallingByDistance && fallingByVelocity && leavingGroundSoon;
    }

    private boolean isPlayerInCriticalState(PlayerSimulation simulated) {
        boolean inCobweb = PlayerInteractionHelper.isBoxInBlock(simulated.boundingBox.inflate(-1e-3), Blocks.COBWEB);
        Criticals criticals = Criticals.getInstance();
        if (criticals != null && criticals.isEnabled() && inCobweb) {
            return !simulated.onGround || inCobweb || simulated.fallDistance > 0.001F;
        }
        if (simulated.onGround || hasCriticalRestrictions(simulated)) {
            return false;
        }

        if (isPvpTradeCriticalWindow(simulated)) {
            return true;
        }

        boolean fallingByDistance = simulated.fallDistance > resolveCriticalFallDistance(simulated, false);
        boolean fallingByVelocity = simulated.velocity.y < resolveCriticalVelocity(simulated, false);
        return !simulated.onGround && fallingByDistance && fallingByVelocity;
    }

    private boolean isPvpTradeCriticalWindow(PlayerSimulation simulated) {
        return isPvpPressureState(simulated)
                && !simulated.onGround
                && simulated.fallDistance > 0.0F
                && simulated.velocity.y < -0.01D;
    }

    private boolean isPvpPressureState(PlayerSimulation simulated) {
        return (mc.player != null && mc.player.hurtTime > 0)
                || simulated.hasStatusEffect(MobEffects.SLOWNESS);
    }

    private float resolveCriticalFallDistance(PlayerSimulation simulated, boolean tightSpace) {
        float required = tightSpace ? 0.01F : 0.03F;
        if (isPvpPressureState(simulated)) {
            required = Math.min(required, tightSpace ? 0.008F : 0.012F);
        }
        if (simulated.horizontalCollision) {
            required = Math.min(required, 0.012F);
        }
        return required;
    }

    private double resolveCriticalVelocity(PlayerSimulation simulated, boolean tightSpace) {
        double required = tightSpace ? -0.02D : -0.03D;
        if (isPvpPressureState(simulated)) {
            required = Math.max(required, tightSpace ? -0.012D : -0.018D);
        }
        if (simulated.horizontalCollision) {
            required = Math.max(required, -0.015D);
        }
        return required;
    }

    public StopWatch getAttackTimer() {
        return attackTimer;
    }

    public int getCount() {
        return count;
    }

}

