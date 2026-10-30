package releon.ru.api.module.impl.combat.aura.attack;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import releon.ru.IMinecraft;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.events.impl.UsingItemEvent;
import releon.ru.api.module.impl.combat.TriggerBot;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.settings.impl.ModeSetting;

import java.util.Set;

public class StrikerConstructor implements IMinecraft {
    private StrikeManager attackHandler = new StrikeManager();

    public void tick() {
        getAttackHandler().tick();
    }

    public void onPacket(PacketEvent event) {
        getAttackHandler().onPacket(event);
    }

    public void performAttack(AttackPerpetratorConfigurable configurable) {
        getAttackHandler().handleAttack(configurable);
    }

    public void performTriggerAttack(AttackPerpetratorConfigurable configurable, TriggerBot triggerBot) {
        getAttackHandler().handleTriggerAttack(configurable, triggerBot);
    }

    public void onUsingItem(UsingItemEvent event) {
        getAttackHandler().onUsingItem(event);
    }

    public StrikeManager getAttackHandler() {
        if (attackHandler == null) {
            attackHandler = new StrikeManager();
        }
        return attackHandler;
    }

    public static class AttackPerpetratorConfigurable {
        private final LivingEntity target;
        private final Angle angle;
        private final float maximumRange;
        private final boolean onlyCritical;
        private final boolean shouldBreakShield;
        private final boolean shouldUnPressShield;
        private final boolean eatAndAttack;
        private final boolean multiPoints;
        private final boolean ignoreWalls;
        private final boolean tpsSync;
        private final boolean legacyPvp;
        private final AABB box;
        private final ModeSetting aimMode;

        public AttackPerpetratorConfigurable(LivingEntity target, Angle angle, float maximumRange,
                                             Set<String> options, ModeSetting aimMode, AABB box) {
            this(target, angle, maximumRange, options, aimMode, box, options.contains("Only Crits"), false, false);
        }

        public AttackPerpetratorConfigurable(LivingEntity target, Angle angle, float maximumRange,
                                             Set<String> options, ModeSetting aimMode, AABB box, boolean onlyCritical) {
            this(target, angle, maximumRange, options, aimMode, box, onlyCritical, false, false);
        }

        public AttackPerpetratorConfigurable(LivingEntity target, Angle angle, float maximumRange,
                                             Set<String> options, ModeSetting aimMode, AABB box,
                                             boolean onlyCritical, boolean tpsSync, boolean legacyPvp) {
            Set<String> safeOptions = options == null ? Set.of() : options;
            this.target = target;
            this.angle = angle;
            this.maximumRange = maximumRange;
            this.onlyCritical = onlyCritical;
            this.shouldBreakShield = safeOptions.contains("Break Shield");
            this.shouldUnPressShield = safeOptions.contains("Release Shield");
            this.eatAndAttack = safeOptions.contains("Pause While Using");
            this.multiPoints = safeOptions.contains("Multi Points");
            this.ignoreWalls = safeOptions.contains("Ignore Walls");
            this.tpsSync = tpsSync;
            this.legacyPvp = legacyPvp;
            this.box = box;
            this.aimMode = aimMode;
        }

        public LivingEntity getTarget() {
            return target;
        }

        public Angle getAngle() {
            return angle;
        }

        public float getMaximumRange() {
            return maximumRange;
        }

        public boolean isOnlyCritical() {
            return onlyCritical;
        }

        public boolean isShouldBreakShield() {
            return shouldBreakShield;
        }

        public boolean isShouldUnPressShield() {
            return shouldUnPressShield;
        }

        public boolean isEatAndAttack() {
            return eatAndAttack;
        }

        public boolean isMultiPoints() {
            return multiPoints;
        }

        public boolean isIgnoreWalls() {
            return ignoreWalls;
        }

        public boolean isTpsSync() {
            return tpsSync;
        }

        public boolean isLegacyPvp() {
            return legacyPvp;
        }

        public AABB getBox() {
            return box;
        }

        public ModeSetting getAimMode() {
            return aimMode;
        }
    }
}
