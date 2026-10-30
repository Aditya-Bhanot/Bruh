package releon.ru.api.module.impl.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.InputEvent;
import releon.ru.api.events.impl.RotationUpdateEvent;
import releon.ru.api.events.types.EventPhase;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.AngleConfig;
import releon.ru.api.module.impl.combat.aura.AngleConnection;
import releon.ru.api.module.impl.combat.aura.impl.LinearConstructor;
import releon.ru.api.module.impl.combat.aura.target.TargetFinder;
import releon.ru.api.module.impl.combat.aura.util.TaskPriority;
import releon.ru.api.module.impl.combat.aura.util.StopWatch;
import releon.ru.api.module.impl.combat.macetarget.armor.ArmorSwapHandler;
import releon.ru.api.module.impl.combat.macetarget.armor.FireworkHandler;
import releon.ru.api.module.impl.combat.macetarget.attack.AttackHandler;
import releon.ru.api.module.impl.combat.macetarget.flight.FlightController;
import releon.ru.api.module.impl.combat.macetarget.prediction.TargetPredictor;
import releon.ru.api.module.impl.combat.macetarget.stage.StageHandler;
import releon.ru.api.module.impl.combat.macetarget.state.MaceState.Stage;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.MultiModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.inventory.lookup.InventoryUtils;
import releon.ru.utils.inventory.swap.SwapSettings;

public class MaceTarget extends Module {
    private static MaceTarget instance;

    private final ModeSetting serverMode = register(new ModeSetting("Server", "Server mode.", "Standard", "Standard", "ReallyWorld"));
    private final NumberSetting height = register(new NumberSetting("Height", "Flight height above target.", 30.0, 20.0, 60.0, 1.0));
    private final MultiModeSetting targetType = register(new MultiModeSetting("Targets", "Target type filter.", new String[]{"Players", "Mobs", "Animals"}, "Players"));
    private final BooleanSetting autoEquipChest = register(new BooleanSetting("Auto Chestplate", "Equip chestplate on disable when possible.", true));
    private final BooleanSetting predictMovement = register(new BooleanSetting("Predict", "Predict target movement.", true));
    private final TargetPredictor predictor = new TargetPredictor();
    private final FlightController flightController = new FlightController(predictor);
    private final AttackHandler attackHandler = new AttackHandler();
    private final ArmorSwapHandler armorSwapHandler = new ArmorSwapHandler(this::buildSettings);
    private final FireworkHandler fireworkHandler = new FireworkHandler(this::buildSettings);
    private final StopWatch fireworkTimer = new StopWatch();
    private final StageHandler stageHandler = new StageHandler(armorSwapHandler, fireworkHandler, attackHandler, fireworkTimer);
    private final TargetFinder targetFinder = new TargetFinder();
    private LivingEntity target;

    public MaceTarget() {
        super("Mace Target", "Mace combat target helper.", ModuleCategory.COMBAT);
        instance = this;
    }

    public static MaceTarget getInstance() {
        return instance;
    }

    @Override
    protected void onEnable() {
        stageHandler.reset();
        target = null;
        attackHandler.reset();
        armorSwapHandler.reset();
        fireworkHandler.reset();
        predictor.reset();
        fireworkTimer.reset();
    }

    @Override
    protected void onDisable() {
        if (autoEquipChest.getValue() && Minecraft.getInstance().player != null) {
            equipChestplateOnDisable();
        }
        armorSwapHandler.forceRestore();
        fireworkHandler.forceRestore();
        target = null;
        targetFinder.releaseTarget();
        armorSwapHandler.reset();
        fireworkHandler.reset();
        attackHandler.reset();
        predictor.reset();
        stageHandler.reset();
        AngleConnection.INSTANCE.startReturning();
    }

    @Override
    public void onTick(Minecraft client) {
        if (client.player == null || client.level == null) {
            resetAllStates();
            return;
        }

        armorSwapHandler.processLoop();
        fireworkHandler.processLoop();
        if (armorSwapHandler.isActive() || fireworkHandler.isActive()) {
            return;
        }

        if (target == null || !target.isAlive()) {
            return;
        }

        processStage();
    }

    @SubscribeEvent
    private void onRotationUpdate(RotationUpdateEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return;
        }

        if (event.getPhase() == EventPhase.PRE) {
            updateHandlers();

            if (target == null || !target.isAlive()) {
                findTarget();
            }

            if (target == null) {
                return;
            }

            predictor.update(target);

            Stage currentStage = stageHandler.getStage();
            if (currentStage == Stage.FLYING_UP) {
                if (InventoryUtils.hasElytra() && client.player.isFallFlying()) {
                    rotateTo(flightController.calculateAngle(target, currentStage));
                }
            } else if (currentStage == Stage.TARGETTING || currentStage == Stage.ATTACKING) {
                rotateTo(flightController.calculateAngle(target, currentStage));
            }
            return;
        }

        if (event.getPhase() == EventPhase.POST) {
            handlePostRotation();
        }
    }

    @SubscribeEvent
    private void onInput(InputEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        if (armorSwapHandler.getMovement().isBlocked() || fireworkHandler.getMovement().isBlocked()) {
            event.setDirectionalLow(false, false, false, false);
            event.setJumping(false);
            event.setSprinting(false);
            client.player.setSprinting(false);
        }
        if (target != null && InventoryUtils.hasElytra() && stageHandler.getStage() == Stage.FLYING_UP) {
            if (client.player.onGround()) {
                event.setJumping(true);
            } else if (!client.player.isFallFlying() && !client.player.getAbilities().flying) {
                event.setJumping(client.player.tickCount % 2 == 0);
            }
        }
    }

    private SwapSettings buildSettings() {
        return SwapSettings.instant();
    }

    private void updateHandlers() {
        stageHandler.setSilentMode(true);
        stageHandler.setReallyWorldMode(serverMode.is("ReallyWorld"));
        stageHandler.setHeight(height.getFloat());
        flightController.setPredictionEnabled(predictMovement.getValue());
        flightController.setHeight(height.getFloat());
    }

    private void findTarget() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            targetFinder.releaseTarget();
            target = null;
            return;
        }
        TargetFinder.EntityFilter filter = new TargetFinder.EntityFilter(targetType.getValue());
        targetFinder.searchTargets(client.level.entitiesForRendering(), 128.0F, 360.0F, true);
        targetFinder.validateTarget(filter::isValid);
        target = targetFinder.getCurrentTarget();
    }

    private void processStage() {
        boolean hasElytra = InventoryUtils.hasElytra();
        switch (stageHandler.getStage()) {
            case PREPARE -> stageHandler.handlePrepare(hasElytra);
            case FLYING_UP -> stageHandler.handleFlyingUp(target, hasElytra);
            case TARGETTING -> stageHandler.handleTargetting(target);
            case ATTACKING -> stageHandler.handleAttacking(target, hasElytra);
        }
    }

    private void handlePostRotation() {
        if (!attackHandler.isPendingAttack()) {
            return;
        }
        attackHandler.performAttack(target);
        attackHandler.setPendingAttack(false);
        if (attackHandler.isShouldDisableAfterAttack()) {
            attackHandler.setShouldDisableAfterAttack(false);
            setEnabled(false);
        }
    }

    private void rotateTo(Angle angle) {
        AngleConnection.INSTANCE.rotateTo(
                new Angle.VecRotation(angle, angle.toVector()),
                target,
                1,
                new AngleConfig(new LinearConstructor(), true, false),
                TaskPriority.HIGH_IMPORTANCE_1,
                this
        );
    }

    private void equipChestplateOnDisable() {
        if (InventoryUtils.hasElytra()) {
            int slot = InventoryUtils.findChestArmorSlot();
            if (slot != -1) {
                InventoryUtils.swap(InventoryUtils.wrapSlot(slot), 6);
                InventoryUtils.closeScreen();
            }
        }
    }

    private void resetAllStates() {
        armorSwapHandler.reset();
        fireworkHandler.reset();
        attackHandler.reset();
        predictor.reset();
        target = null;
        targetFinder.releaseTarget();
        stageHandler.reset();
    }
}
