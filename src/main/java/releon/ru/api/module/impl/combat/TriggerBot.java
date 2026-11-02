package releon.ru.api.module.impl.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.events.impl.RotationUpdateEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.events.types.EventPhase;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.AngleConnection;
import releon.ru.api.module.impl.combat.aura.MathAngle;
import releon.ru.api.module.impl.combat.aura.attack.StrikeManager;
import releon.ru.api.module.impl.combat.aura.attack.StrikerConstructor;
import releon.ru.api.module.impl.combat.aura.impl.LinearConstructor;
import releon.ru.api.module.impl.combat.aura.impl.RotateConstructor;
import releon.ru.api.module.impl.combat.aura.target.MultiPoint;
import releon.ru.api.module.impl.combat.aura.target.TargetFinder;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.MultiModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.player.BaritoneMovementHelper;

public class TriggerBot extends Module {
    private static TriggerBot instance;

    private final TargetFinder targetSelector = new TargetFinder();
    private final MultiPoint pointFinder = new MultiPoint();
    private final StrikerConstructor attackPerpetrator = new StrikerConstructor();

    public LivingEntity target;
    private int sprintSuppressUntilTick = -1;

    private final NumberSetting attackRange = register(new NumberSetting("Attack Range", "Attack distance to the target.", 3.0D, 1.0D, 6.0D, 0.1D));
    private final NumberSetting attackDelay = register(new NumberSetting("Attack Delay", "Minimum delay between attacks.", 0.0D, 0.0D, 1000.0D, 10.0D));
    private final MultiModeSetting targetType = register(new MultiModeSetting("Targets", "Target type filter.", new String[]{"Players", "Friends", "Mobs", "Animals", "Invisible", "Armor Stands"}, "Players", "Mobs", "Animals"));
    private final MultiModeSetting attackSetting = register(new MultiModeSetting("Options", "Attack options.", new String[]{"Only Critical", "Break Shield", "Release Shield", "Pause While Using", "Ignore Walls", "Hit Chance"}, "Only Critical", "Break Shield"));
    private final NumberSetting hitChance = register(new NumberSetting("Hit Chance", "Chance to attack the target.", 100.0D, 1.0D, 100.0D, 1.0D));
    private final BooleanSetting smartCrits = register(new BooleanSetting("Smart Crits", "Allow smart ground hits while waiting for critical timing.", false));
    private final BooleanSetting tpsSync = register(new BooleanSetting("Sync TPS", "Adjust attack timing to current server TPS.", true));
    private final ModeSetting sprintReset = register(new ModeSetting("Sprint Reset", "Sprint reset mode before attack.", "Legit", "Legit", "SpookyTime"));

    public TriggerBot() {
        super("TriggerBot", "Trigger Bot", ModuleCategory.COMBAT);
        instance = this;
        hitChance.visibleWhen(() -> attackSetting.isSelected("Hit Chance"));
        smartCrits.visibleWhen(() -> attackSetting.isSelected("Only Critical"));
    }

    public static TriggerBot getInstance() {
        return instance;
    }

    @Override
    protected void onDisable() {
        target = null;
        sprintSuppressUntilTick = -1;
        attackPerpetrator.getAttackHandler().resetPendingState();
    }

    @SubscribeEvent
    private void onPreTick(TickEvent.Pre event) {
        Minecraft client = event.getClient();
        if (client == null || client.player == null || client.level == null) {
            targetSelector.releaseTarget();
            target = null;
            return;
        }
        attackPerpetrator.tick();
    }

    @SubscribeEvent
    private void onPacket(PacketEvent event) {
        attackPerpetrator.onPacket(event);
    }

    @SubscribeEvent
    private void onRotationUpdate(RotationUpdateEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            targetSelector.releaseTarget();
            target = null;
            return;
        }
        if (BaritoneMovementHelper.isBaritoneActive(client.player)) {
            targetSelector.releaseTarget();
            target = null;
            return;
        }

        if (event.getPhase() == EventPhase.PRE) {
            target = updateTarget(client);
            return;
        }
        if (event.getPhase() == EventPhase.POST && target != null) {
            attackPerpetrator.performTriggerAttack(getConfig(client), this);
        }
    }

    private LivingEntity updateTarget(Minecraft client) {
        TargetFinder.EntityFilter filter = new TargetFinder.EntityFilter(targetType.getValue());
        float range = attackRange.getFloat();
        targetSelector.searchTargets(client.level.entitiesForRendering(), range, 360.0F, isAttackSettingSelected("Ignore Walls"), filter::isValid);
        targetSelector.validateTarget(filter::isValid);
        return targetSelector.getCurrentTarget();
    }

    public StrikerConstructor.AttackPerpetratorConfigurable getConfig(Minecraft client) {
        float range = attackRange.getFloat();
        Tuple<Vec3, AABB> point = pointFinder.computeVector(
                target,
                range,
                AngleConnection.INSTANCE.getRotation(),
                getSmoothMode().randomValue(),
                isAttackSettingSelected("Ignore Walls")
        );
        Vec3 computedPoint = point.getA();
        AABB hitbox = point.getB();
        Angle angle = MathAngle.fromVec3d(computedPoint.subtract(client.player.getEyePosition()));

        return new StrikerConstructor.AttackPerpetratorConfigurable(
                target,
                angle,
                range,
                attackSetting.getValue(),
                null,
                hitbox,
                isAttackSettingSelected("Only Critical"),
                tpsSync.getValue(),
                false
        );
    }

    public RotateConstructor getSmoothMode() {
        return LinearConstructor.INSTANCE;
    }

    private boolean isAttackSettingSelected(String... names) {
        for (String name : names) {
            if (attackSetting.isSelected(name)) {
                return true;
            }
        }
        return false;
    }

    public boolean shouldPreventSprinting() {
        Minecraft client = Minecraft.getInstance();
        if (!isEnabled() || target == null || client.player == null || !isOnlyCrits()) {
            return false;
        }
        if (client.player.tickCount <= sprintSuppressUntilTick) {
            return true;
        }

        StrikerConstructor.AttackPerpetratorConfigurable config = getConfig(client);
        StrikeManager attackHandler = attackPerpetrator.getAttackHandler();
        boolean shouldPrevent = attackHandler.canAttack(config, 4) && !attackHandler.canCrit(config, 4);
        if (shouldPrevent) {
            sprintSuppressUntilTick = client.player.tickCount + 1;
        }
        return shouldPrevent;
    }

    public boolean isResetSprintLegit() {
        return sprintReset.is("Legit");
    }

    public boolean isResetSprintPacket() {
        return false;
    }

    public boolean isRandomizeCrit() {
        return false;
    }

    public boolean isSmartCritsEnabled() {
        return isOnlyCrits() && smartCrits.getValue();
    }

    public boolean isOnlyCrits() {
        return isAttackSettingSelected("Only Critical");
    }

    public int getAttackDelayMs() {
        return Math.max(0, attackDelay.getValue().intValue());
    }

    public boolean shouldPassHitChance() {
        return !isAttackSettingSelected("Hit Chance")
                || releon.ru.api.module.impl.combat.aura.util.MathUtils.getRandom(0.0F, 100.0F) < hitChance.getFloat();
    }

    public ModeSetting getSprintReset() {
        return sprintReset;
    }

    public long getActivationTimeMs() {
        return 0L;
    }
}
