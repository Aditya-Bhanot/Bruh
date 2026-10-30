package releon.ru.api.module.impl.combat.aura.target;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.impl.combat.AntiBot;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.module.impl.combat.aura.AngleConnection;
import releon.ru.api.module.impl.combat.aura.MathAngle;
import releon.ru.api.module.impl.combat.aura.impl.LinearConstructor;
import releon.ru.utils.repository.friend.FriendUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public class TargetFinder {
    private final MultiPoint pointFinder = new MultiPoint();
    private LivingEntity currentTarget;
    private List<LivingEntity> potentialTargets = List.of();

    public LivingEntity getCurrentTarget() {
        return currentTarget;
    }

    public void releaseTarget() {
        currentTarget = null;
    }

    public void validateTarget(Predicate<LivingEntity> predicate) {
        if (currentTarget != null && predicate.test(currentTarget) && potentialTargets.contains(currentTarget)) {
            return;
        }
        LivingEntity nextTarget = findFirstMatch(predicate).orElse(null);
        if (nextTarget == null) {
            if (currentTarget != null && !predicate.test(currentTarget)) {
                releaseTarget();
            }
            return;
        }
        currentTarget = nextTarget;
    }

    public void searchTargets(Iterable<Entity> entities, float maxDistance, float maxFov, boolean ignoreWalls) {
        searchTargets(entities, maxDistance, maxFov, ignoreWalls, entity -> true);
    }

    public void searchTargets(Iterable<Entity> entities, float maxDistance, float maxFov, boolean ignoreWalls,
                              Predicate<LivingEntity> predicate) {
        SortMode sortMode = resolveSortMode();
        boolean needsFov = maxFov < 360.0F || sortMode == SortMode.FOV || sortMode == SortMode.COMBINED;
        if (currentTarget != null
                && (!predicate.test(currentTarget)
                || !pointFinder.hasValidPoint(currentTarget, maxDistance, ignoreWalls)
                || (needsFov && getFov(currentTarget, maxDistance, ignoreWalls) > maxFov))) {
            releaseTarget();
        }
        potentialTargets = createTargets(entities, maxDistance, maxFov, ignoreWalls, predicate, sortMode, needsFov);
    }

    private double getFov(LivingEntity entity, float maxDistance, boolean ignoreWalls) {
        Vec3 attackVector = pointFinder.computeVector(entity, maxDistance, AngleConnection.INSTANCE.getRotation(), new LinearConstructor().randomValue(), ignoreWalls).getA();
        return RaycastAngle.rayTrace(maxDistance, entity.getBoundingBox())
                ? 0
                : AngleConnection.computeRotationDifference(MathAngle.cameraAngle(), MathAngle.calculateAngle(attackVector));
    }

    private List<LivingEntity> createTargets(Iterable<Entity> entities, float maxDistance, float maxFov,
                                             boolean ignoreWalls, Predicate<LivingEntity> predicate,
                                             SortMode sortMode, boolean needsFov) {
        List<TargetCandidate> candidates = new ArrayList<>();
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return List.of();
        }
        double maxDistanceSqr = maxDistance * maxDistance;
        for (Entity entity : entities) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            if (!predicate.test(living) || living.distanceToSqr(client.player) > maxDistanceSqr) {
                continue;
            }
            if (!pointFinder.hasValidPoint(living, maxDistance, ignoreWalls)) {
                continue;
            }
            double fov = 0.0D;
            if (needsFov) {
                fov = getFov(living, maxDistance, ignoreWalls);
                if (fov >= maxFov) {
                    continue;
                }
            }
            candidates.add(new TargetCandidate(living, living.distanceTo(client.player), living.getHealth() + living.getAbsorptionAmount(), living.getArmorValue(), fov));
        }
        PriorityBounds bounds = PriorityBounds.of(candidates);
        candidates.sort(getComparator(sortMode, bounds));
        List<LivingEntity> result = new ArrayList<>(candidates.size());
        for (TargetCandidate candidate : candidates) {
            result.add(candidate.entity());
        }
        return result;
    }

    private Optional<LivingEntity> findFirstMatch(Predicate<LivingEntity> predicate) {
        return potentialTargets.stream().filter(predicate).findFirst();
    }

    private SortMode resolveSortMode() {
        AuraModule aura = AuraModule.getInstance();
        if (aura == null || aura.getTargetPriority() == null) {
            return SortMode.DISTANCE;
        }
        return switch (aura.getTargetPriority().getValue()) {
            case "Health" -> SortMode.HEALTH;
            case "Armor" -> SortMode.ARMOR;
            case "FOV" -> SortMode.FOV;
            case "Combined" -> SortMode.COMBINED;
            default -> SortMode.DISTANCE;
        };
    }

    private Comparator<TargetCandidate> getComparator(SortMode sortMode, PriorityBounds bounds) {
        return switch (sortMode) {
            case HEALTH -> Comparator.<TargetCandidate>comparingDouble(TargetCandidate::health)
                    .thenComparing(Comparator.comparingInt(TargetCandidate::armor).reversed())
                    .thenComparingDouble(TargetCandidate::distance)
                    .thenComparingDouble(TargetCandidate::fov);
            case ARMOR -> Comparator.<TargetCandidate>comparingInt(TargetCandidate::armor).reversed()
                    .thenComparingDouble(TargetCandidate::health)
                    .thenComparingDouble(TargetCandidate::distance)
                    .thenComparingDouble(TargetCandidate::fov);
            case FOV -> Comparator.<TargetCandidate>comparingDouble(TargetCandidate::fov)
                    .thenComparingDouble(TargetCandidate::distance)
                    .thenComparingDouble(TargetCandidate::health)
                    .thenComparing(Comparator.comparingInt(TargetCandidate::armor).reversed());
            case COMBINED -> Comparator.<TargetCandidate>comparingDouble(bounds::combinedScore)
                    .thenComparingDouble(TargetCandidate::distance)
                    .thenComparingDouble(TargetCandidate::health)
                    .thenComparing(Comparator.comparingInt(TargetCandidate::armor).reversed())
                    .thenComparingDouble(TargetCandidate::fov);
            case DISTANCE -> Comparator.comparingDouble(TargetCandidate::distance);
        };
    }

    public enum SortMode {
        DISTANCE,
        HEALTH,
        ARMOR,
        FOV,
        COMBINED
    }

    private record TargetCandidate(LivingEntity entity, float distance, float health, int armor, double fov) {
    }

    private record PriorityBounds(float minDistance, float maxDistance, float minHealth, float maxHealth, int minArmor, int maxArmor, double minFov, double maxFov) {
        private static PriorityBounds of(List<TargetCandidate> candidates) {
            if (candidates.isEmpty()) {
                return new PriorityBounds(0, 0, 0, 0, 0, 0, 0, 0);
            }
            float minDistance = Float.MAX_VALUE;
            float maxDistance = -Float.MAX_VALUE;
            float minHealth = Float.MAX_VALUE;
            float maxHealth = -Float.MAX_VALUE;
            int minArmor = Integer.MAX_VALUE;
            int maxArmor = Integer.MIN_VALUE;
            double minFov = Double.MAX_VALUE;
            double maxFov = -Double.MAX_VALUE;
            for (TargetCandidate candidate : candidates) {
                minDistance = Math.min(minDistance, candidate.distance());
                maxDistance = Math.max(maxDistance, candidate.distance());
                minHealth = Math.min(minHealth, candidate.health());
                maxHealth = Math.max(maxHealth, candidate.health());
                minArmor = Math.min(minArmor, candidate.armor());
                maxArmor = Math.max(maxArmor, candidate.armor());
                minFov = Math.min(minFov, candidate.fov());
                maxFov = Math.max(maxFov, candidate.fov());
            }
            return new PriorityBounds(minDistance, maxDistance, minHealth, maxHealth, minArmor, maxArmor, minFov, maxFov);
        }

        private double combinedScore(TargetCandidate candidate) {
            return normalize(candidate.distance(), minDistance, maxDistance)
                    + normalize(candidate.health(), minHealth, maxHealth)
                    + 1.0 - normalize(candidate.armor(), minArmor, maxArmor)
                    + normalize(candidate.fov(), minFov, maxFov);
        }

        private double normalize(double value, double min, double max) {
            return Double.compare(max, min) == 0 ? 0.0 : (value - min) / (max - min);
        }
    }

    public static class EntityFilter {
        private final java.util.Set<String> targetSettings;

        public EntityFilter(java.util.Set<String> targetSettings) {
            this.targetSettings = targetSettings;
        }

        public boolean isValid(LivingEntity entity) {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null || entity == client.player || !entity.isAlive() || entity.getHealth() <= 0.0F) {
                return false;
            }
            if (entity.isInvisible() && !targetSettings.contains("Invisible")) {
                return false;
            }
            if (entity instanceof Player player) {
                if (AntiBot.shouldIgnore(player)) {
                    return false;
                }
                return FriendUtils.isFriend(player) ? targetSettings.contains("Friends") : targetSettings.contains("Players");
            }
            if (entity instanceof Animal) {
                return targetSettings.contains("Animals");
            }
            if (entity instanceof Mob) {
                return targetSettings.contains("Mobs");
            }
            if (entity instanceof ArmorStand) {
                return targetSettings.contains("Armor Stands");
            }
            return false;
        }
    }
}
