package releon.ru.api.module.impl.combat.aura.target;

import net.minecraft.util.Tuple;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import releon.ru.IMinecraft;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.MathAngle;
import releon.ru.api.module.impl.combat.aura.attack.StrikeManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

public class MultiPoint implements IMinecraft {
    private static final int DATASET_SWITCH_MIN_HITS = 15;
    private static final int DATASET_SWITCH_MAX_HITS = 17;
    private static final int POINT_SWITCH_MIN_TICKS = 41;
    private static final int POINT_SWITCH_MAX_TICKS = 45;
    private static final int TELEPORT_TOP_CANDIDATES = 4;
    private static final double MAX_OFFSET_LENGTH = 0.5F;
    private static final double AIM_ASSIST_HEIGHT = 0.85D;
    private static final PointPreset DEFAULT_PRESET = new PointPreset(
            new AimPoint(0.00D, 0.94D, 0.00D),
            new AimPoint(0.00D, 0.86D, 0.00D),
            new AimPoint(0.00D, 0.78D, 0.00D),
            new AimPoint(0.00D, 0.70D, 0.00D),
            new AimPoint(0.00D, 0.62D, 0.00D),
            new AimPoint(0.00D, 0.54D, 0.00D),
            new AimPoint(0.00D, 0.46D, 0.00D),
            new AimPoint(0.24D, 0.86D, 0.00D),
            new AimPoint(-0.24D, 0.86D, 0.00D),
            new AimPoint(0.00D, 0.86D, 0.24D),
            new AimPoint(0.00D, 0.86D, -0.24D),
            new AimPoint(0.24D, 0.78D, 0.24D),
            new AimPoint(0.24D, 0.78D, -0.24D),
            new AimPoint(-0.24D, 0.78D, 0.24D),
            new AimPoint(-0.24D, 0.78D, -0.24D),
            new AimPoint(0.36D, 0.70D, 0.00D),
            new AimPoint(-0.36D, 0.70D, 0.00D),
            new AimPoint(0.00D, 0.70D, 0.36D),
            new AimPoint(0.00D, 0.70D, -0.36D),
            new AimPoint(0.22D, 0.62D, 0.22D),
            new AimPoint(0.22D, 0.62D, -0.22D),
            new AimPoint(-0.22D, 0.62D, 0.22D),
            new AimPoint(-0.22D, 0.62D, -0.22D)
    );
    private static final PointPreset SPOOKY_PRESET = buildSpookyPreset();
    private static final PointDataset[] DATASETS = new PointDataset[]{
            new PointDataset(0.14D, 0.94D, 0.70D, 6, 0.24D, true, 0.25D, 0.00D, 0.12D, 0.16D, 0.78D),
            new PointDataset(0.24D, 0.98D, 0.84D, 10, 0.32D, true, 0.20D, 0.01D, 0.16D, 0.20D, 0.66D),
            new PointDataset(0.10D, 0.86D, 0.54D, 8, 0.38D, true, 0.28D, 0.02D, 0.18D, 0.24D, 0.62D),
            new PointDataset(0.34D, 0.99D, 0.90D, 12, 0.28D, true, 0.18D, 0.00D, 0.14D, 0.18D, 0.70D),
            new PointDataset(0.42D, 0.96D, 0.78D, 7, 0.18D, false, 0.35D, 0.03D, 0.08D, 0.10D, 0.74D),
            new PointDataset(0.18D, 0.74D, 0.58D, 9, 0.44D, true, 0.22D, 0.01D, 0.22D, 0.26D, 0.58D),
            new PointDataset(0.52D, 1.00D, 0.92D, 11, 0.20D, true, 0.42D, 0.00D, 0.10D, 0.14D, 0.72D),
            new PointDataset(0.06D, 0.68D, 0.42D, 7, 0.34D, true, 0.30D, 0.04D, 0.20D, 0.22D, 0.60D),
            new PointDataset(0.30D, 0.88D, 0.64D, 13, 0.46D, true, 0.16D, 0.02D, 0.26D, 0.28D, 0.52D),
            new PointDataset(0.60D, 0.99D, 0.86D, 6, 0.40D, true, 0.24D, 0.00D, 0.18D, 0.20D, 0.64D),
            new PointDataset(0.12D, 0.98D, 0.76D, 15, 0.12D, false, 0.12D, 0.06D, 0.14D, 0.12D, 0.80D),
            new PointDataset(0.20D, 0.90D, 0.70D, 5, 0.50D, true, 0.26D, 0.00D, 0.30D, 0.30D, 0.48D),
            new PointDataset(0.36D, 0.82D, 0.72D, 8, 0.26D, true, 0.38D, 0.02D, 0.12D, 0.18D, 0.68D),
            new PointDataset(0.08D, 0.52D, 0.36D, 6, 0.42D, true, 0.32D, 0.03D, 0.24D, 0.24D, 0.56D),
            new PointDataset(0.70D, 1.00D, 0.94D, 9, 0.30D, true, 0.34D, 0.01D, 0.16D, 0.16D, 0.76D),
            new PointDataset(0.16D, 0.92D, 0.62D, 12, 0.56D, true, 0.18D, 0.01D, 0.34D, 0.32D, 0.44D),
            new PointDataset(0.28D, 0.94D, 0.82D, 10, 0.08D, false, 0.48D, 0.08D, 0.06D, 0.08D, 0.86D),
            new PointDataset(0.04D, 0.98D, 0.50D, 16, 0.36D, true, 0.10D, 0.03D, 0.28D, 0.26D, 0.50D),
            new PointDataset(0.48D, 0.98D, 0.88D, 14, 0.52D, true, 0.20D, 0.00D, 0.32D, 0.34D, 0.46D),
            new PointDataset(0.22D, 0.78D, 0.66D, 4, 0.16D, false, 0.55D, 0.10D, 0.04D, 0.06D, 0.90D),
            new PointDataset(0.10D, 1.00D, 0.80D, 18, 0.48D, true, 0.14D, 0.02D, 0.36D, 0.36D, 0.42D),
            new PointDataset(0.40D, 0.90D, 0.56D, 9, 0.60D, true, 0.24D, 0.00D, 0.40D, 0.38D, 0.40D),
            new PointDataset(0.58D, 0.98D, 0.74D, 7, 0.24D, true, 0.30D, 0.04D, 0.18D, 0.18D, 0.66D)
    };

    private final Random random = new Random();
    private Vec3 offset = Vec3.ZERO;
    private int datasetIndex = 0;
    private int lockedCandidateIndex = -1;
    private int lockedDatasetIndex = -1;
    private int lockedEntityId = -1;
    private int pointHoldTicks = 0;
    private int nextPointSwitchTicks = 0;
    private int lastAttackCount = 0;
    private int nextDatasetSwitchHitCount = -1;
    private int aimAssistEntityId = -1;
    private double aimAssistHeightNoise;
    private long aimAssistHeightNoiseUpdatedAtMs;
    private double aimAssistCurrentPointX;
    private double aimAssistCurrentPointY;
    private double aimAssistCurrentPointZ;
    private double aimAssistNextPointX;
    private double aimAssistNextPointY;
    private double aimAssistNextPointZ;
    private long aimAssistNextPointChangeAtMs;
    private double aimAssistPointLerpSpeed;

    private static PointPreset buildSpookyPreset() {
        List<AimPoint> points = new ArrayList<>();

        double[] centerHeights = {0.98D, 0.94D, 0.90D, 0.86D, 0.82D, 0.78D, 0.74D, 0.70D, 0.66D, 0.62D, 0.58D, 0.54D};
        for (double y : centerHeights) {
            points.add(new AimPoint(0.00D, y, 0.00D));
        }

        double[] upperHeights = {0.94D, 0.90D, 0.86D};
        double[] bodyHeights = {0.80D, 0.74D};
        double[] lowerHeights = {0.68D, 0.60D};

        for (double y : upperHeights) {
            addCardinalPoints(points, y, 0.12D);
            addCardinalPoints(points, y, 0.20D);
            addDiagonalPoints(points, y, 0.14D);
        }

        for (double y : bodyHeights) {
            addCardinalPoints(points, y, 0.16D);
            addCardinalPoints(points, y, 0.28D);
            addCardinalPoints(points, y, 0.38D);
            addDiagonalPoints(points, y, 0.18D);
            addDiagonalPoints(points, y, 0.28D);
        }

        for (double y : lowerHeights) {
            addCardinalPoints(points, y, 0.18D);
            addCardinalPoints(points, y, 0.32D);
            addDiagonalPoints(points, y, 0.22D);
            addDiagonalPoints(points, y, 0.32D);
        }

        return new PointPreset(points.toArray(AimPoint[]::new));
    }

    private static void addCardinalPoints(List<AimPoint> points, double y, double radius) {
        points.add(new AimPoint(radius, y, 0.00D));
        points.add(new AimPoint(-radius, y, 0.00D));
        points.add(new AimPoint(0.00D, y, radius));
        points.add(new AimPoint(0.00D, y, -radius));
    }

    private static void addDiagonalPoints(List<AimPoint> points, double y, double radius) {
        points.add(new AimPoint(radius, y, radius));
        points.add(new AimPoint(radius, y, -radius));
        points.add(new AimPoint(-radius, y, radius));
        points.add(new AimPoint(-radius, y, -radius));
    }

    public Tuple<Vec3, AABB> computeVector(LivingEntity entity, float maxDistance, Angle initialAngle, Vec3 velocity, boolean ignoreWalls) {
        if (mc.player == null) {
            return new Tuple<>(entity.getEyePosition(), entity.getBoundingBox());
        }

        if (isFunTimeMode()) {
            Tuple<List<Vec3>, AABB> candidatePoints = generateFunTimeCandidatePoints(entity, maxDistance, ignoreWalls);
            Vec3 bestVector = findTeleportVector(entity, candidatePoints.getA(), candidatePoints.getB(), initialAngle, currentDataset());
            offset = Vec3.ZERO;
            Vec3 fallback = entity.getEyePosition();
            return new Tuple<>((bestVector == null ? fallback : bestVector).add(offset), candidatePoints.getB());
        }

        if (isAimAssistMode()) {
            return computeAimAssistVector(entity, maxDistance, ignoreWalls);
        }

        PointDataset dataset = currentDataset();
        Tuple<List<Vec3>, AABB> candidatePoints = generatePresetCandidatePoints(entity, maxDistance, ignoreWalls, currentPreset());
        Vec3 bestVector = findTeleportVector(entity, candidatePoints.getA(), candidatePoints.getB(), initialAngle, dataset);
        Vec3 fallback = entity.getEyePosition();
        return new Tuple<>(bestVector == null ? fallback : bestVector, candidatePoints.getB());
    }

    public Tuple<List<Vec3>, AABB> generateCandidatePoints(LivingEntity entity, float maxDistance, boolean ignoreWalls) {
        if (isFunTimeMode()) {
            return generateFunTimeCandidatePoints(entity, maxDistance, ignoreWalls);
        }
        if (isAimAssistMode()) {
            Tuple<Vec3, AABB> point = computeAimAssistVector(entity, maxDistance, ignoreWalls);
            return new Tuple<>(List.of(point.getA()), point.getB());
        }
        return generatePresetCandidatePoints(entity, maxDistance, ignoreWalls, currentPreset());
    }

    private Tuple<Vec3, AABB> computeAimAssistVector(LivingEntity entity, float maxDistance, boolean ignoreWalls) {
        AABB entityBox = entity.getBoundingBox();
        if (mc.player == null) {
            return new Tuple<>(entity.getEyePosition(), entityBox);
        }

        updateAimAssistPoint(entity);

        double horizontalRadius = entity.getBbWidth() * 0.5D;
        double clampedHeight = Mth.clamp(AIM_ASSIST_HEIGHT + aimAssistHeightNoise + aimAssistCurrentPointY, 0.05D, 0.95D);
        Vec3 point = entity.position().add(
                aimAssistCurrentPointX * horizontalRadius,
                entity.getBbHeight() * clampedHeight,
                aimAssistCurrentPointZ * horizontalRadius
        );

        if (!isValidPoint(mc.player.getEyePosition(), point, maxDistance, ignoreWalls)) {
            Vec3 fallback = entity.getEyePosition();
            if (isValidPoint(mc.player.getEyePosition(), fallback, maxDistance, ignoreWalls)) {
                return new Tuple<>(fallback, entityBox);
            }
        }

        return new Tuple<>(point, entityBox);
    }

    private Tuple<List<Vec3>, AABB> generateFunTimeCandidatePoints(LivingEntity entity, float maxDistance, boolean ignoreWalls) {
        AABB entityBox = entity.getBoundingBox();
        double rawStepY = entityBox.getYsize() / 10.0F;
        double stepY = rawStepY <= 0.0D ? 0.1D : rawStepY;
        List<Vec3> list = new ArrayList<>();

        if (mc.player == null) {
            return new Tuple<>(list, entityBox);
        }

        Vec3 playerEye = mc.player.getEyePosition();
        Vec3 center = entityBox.getCenter();
        for (double y = entityBox.minY; y <= entityBox.maxY + 1.0E-4D; y += stepY) {
            Vec3 point = new Vec3(center.x, y, center.z);
            if (isValidPoint(playerEye, point, maxDistance, ignoreWalls)) {
                list.add(point);
            }
        }

        return new Tuple<>(list, entityBox);
    }

    private Tuple<List<Vec3>, AABB> generateCandidatePoints(LivingEntity entity, float maxDistance, boolean ignoreWalls, PointDataset dataset) {
        AABB entityBox = entity.getBoundingBox();
        Vec3 center = entityBox.getCenter();
        double height = Math.max(entityBox.getYsize(), 0.1D);
        double minY = entityBox.minY + height * dataset.minHeight;
        double maxY = entityBox.minY + height * dataset.maxHeight;
        double stepY = Math.max((maxY - minY) / Math.max(dataset.verticalSamples, 1), 0.05D);
        double lateralRadius = Math.min(entityBox.getXsize(), entityBox.getZsize()) * dataset.lateralRadius;

        List<Vec3> list = new ArrayList<>();
        if (mc.player == null) {
            return new Tuple<>(list, entityBox);
        }

        Vec3 playerEye = mc.player.getEyePosition();
        for (double y = minY; y <= maxY + 1.0E-4D; y += stepY) {
            addValidPoint(list, playerEye, new Vec3(center.x, y, center.z), maxDistance, ignoreWalls);
            if (lateralRadius <= 0.0D) {
                continue;
            }

            addValidPoint(list, playerEye, new Vec3(center.x + lateralRadius, y, center.z), maxDistance, ignoreWalls);
            addValidPoint(list, playerEye, new Vec3(center.x - lateralRadius, y, center.z), maxDistance, ignoreWalls);
            addValidPoint(list, playerEye, new Vec3(center.x, y, center.z + lateralRadius), maxDistance, ignoreWalls);
            addValidPoint(list, playerEye, new Vec3(center.x, y, center.z - lateralRadius), maxDistance, ignoreWalls);

            if (dataset.diagonalPoints) {
                double diagonal = lateralRadius * 0.70710678118D;
                addValidPoint(list, playerEye, new Vec3(center.x + diagonal, y, center.z + diagonal), maxDistance, ignoreWalls);
                addValidPoint(list, playerEye, new Vec3(center.x + diagonal, y, center.z - diagonal), maxDistance, ignoreWalls);
                addValidPoint(list, playerEye, new Vec3(center.x - diagonal, y, center.z + diagonal), maxDistance, ignoreWalls);
                addValidPoint(list, playerEye, new Vec3(center.x - diagonal, y, center.z - diagonal), maxDistance, ignoreWalls);
            }
        }

        return new Tuple<>(list, entityBox);
    }

    private Tuple<List<Vec3>, AABB> generatePresetCandidatePoints(LivingEntity entity, float maxDistance, boolean ignoreWalls, PointPreset preset) {
        AABB entityBox = entity.getBoundingBox();
        Vec3 center = entityBox.getCenter();
        double height = Math.max(entityBox.getYsize(), 0.1D);
        double halfX = entityBox.getXsize() * 0.5D;
        double halfZ = entityBox.getZsize() * 0.5D;

        List<Vec3> list = new ArrayList<>();
        if (mc.player == null) {
            return new Tuple<>(list, entityBox);
        }

        Vec3 playerEye = mc.player.getEyePosition();
        for (AimPoint aimPoint : preset.points) {
            Vec3 point = new Vec3(
                    center.x + halfX * aimPoint.x,
                    entityBox.minY + height * aimPoint.y,
                    center.z + halfZ * aimPoint.z
            );
            addValidPoint(list, playerEye, point, maxDistance, ignoreWalls);
        }

        return new Tuple<>(list, entityBox);
    }

    public boolean hasValidPoint(LivingEntity entity, float maxDistance, boolean ignoreWalls) {
        if (mc.player == null) {
            return false;
        }
        if (isFunTimeMode()) {
            return !generateFunTimeCandidatePoints(entity, maxDistance, ignoreWalls).getA().isEmpty();
        }
        if (isAimAssistMode()) {
            return isValidPoint(mc.player.getEyePosition(), computeAimAssistVector(entity, maxDistance, ignoreWalls).getA(), maxDistance, ignoreWalls);
        }
        return !generateCandidatePoints(entity, maxDistance, ignoreWalls, currentDataset()).getA().isEmpty();
    }

    private void addValidPoint(List<Vec3> points, Vec3 startPoint, Vec3 endPoint, float maxDistance, boolean ignoreWalls) {
        if (isValidPoint(startPoint, endPoint, maxDistance, ignoreWalls)) {
            points.add(endPoint);
        }
    }

    private boolean isValidPoint(Vec3 startPoint, Vec3 endPoint, float maxDistance, boolean ignoreWalls) {
        if (startPoint.distanceToSqr(endPoint) > maxDistance * maxDistance) {
            return false;
        }
        if (ignoreWalls) {
            return true;
        }
        HitResult result = RaycastAngle.raycast(startPoint, endPoint, ClipContext.Block.COLLIDER);
        return result == null || result.getType() != HitResult.Type.BLOCK;
    }

    private Vec3 findBestVector(List<Vec3> candidatePoints, AABB entityBox, Angle initialAngle, PointDataset dataset) {
        if (mc.player == null) {
            return null;
        }
        Vec3 playerEye = mc.player.getEyePosition();
        return candidatePoints.stream()
                .min(Comparator.comparingDouble(point -> calculatePointScore(playerEye, point, entityBox, initialAngle, dataset)))
                .orElse(null);
    }

    private Vec3 findTeleportVector(LivingEntity entity, List<Vec3> candidatePoints, AABB entityBox, Angle initialAngle, PointDataset dataset) {
        if (candidatePoints.isEmpty()) {
            resetLockedPoint();
            return null;
        }

        pointHoldTicks++;
        boolean entityChanged = lockedEntityId != entity.getId();
        boolean shouldSwitchPoint = nextPointSwitchTicks <= 0 || pointHoldTicks >= nextPointSwitchTicks;

        if (entityChanged || lockedDatasetIndex != datasetIndex || lockedCandidateIndex < 0 || lockedCandidateIndex >= candidatePoints.size() || shouldSwitchPoint) {
            lockedCandidateIndex = findTeleportVectorIndex(candidatePoints, entityBox, initialAngle, dataset, lockedCandidateIndex);
            lockedDatasetIndex = datasetIndex;
            lockedEntityId = entity.getId();
            pointHoldTicks = 0;
            nextPointSwitchTicks = randomPointHoldTicks();
        }

        return candidatePoints.get(lockedCandidateIndex);
    }

    private int findTeleportVectorIndex(List<Vec3> candidatePoints, AABB entityBox, Angle initialAngle, PointDataset dataset, int previousIndex) {
        List<PointScore> scores = new ArrayList<>();

        if (mc.player == null) {
            return 0;
        }

        Vec3 playerEye = mc.player.getEyePosition();
        for (int i = 0; i < candidatePoints.size(); i++) {
            double score = calculatePointScore(playerEye, candidatePoints.get(i), entityBox, initialAngle, dataset);
            scores.add(new PointScore(i, score));
        }

        scores.sort(Comparator.comparingDouble(PointScore::score));
        int limit = Math.min(TELEPORT_TOP_CANDIDATES, scores.size());
        if (limit <= 1) {
            return scores.getFirst().index();
        }

        List<Integer> selectable = new ArrayList<>();
        Vec3 previousPoint = previousIndex >= 0 && previousIndex < candidatePoints.size()
                ? candidatePoints.get(previousIndex)
                : null;

        for (int i = 0; i < limit; i++) {
            int index = scores.get(i).index();
            if (index == previousIndex) {
                continue;
            }
            if (previousPoint != null && previousPoint.distanceTo(candidatePoints.get(index)) < 0.045D) {
                continue;
            }
            selectable.add(index);
        }

        if (selectable.isEmpty()) {
            for (int i = 0; i < limit; i++) {
                int index = scores.get(i).index();
                if (index != previousIndex) {
                    selectable.add(index);
                }
            }
        }

        if (selectable.isEmpty()) {
            return scores.getFirst().index();
        }

        return selectable.get(random.nextInt(selectable.size()));
    }

    private Vec3 findFunTimeBestVector(List<Vec3> candidatePoints, Angle initialAngle) {
        if (mc.player == null) {
            return null;
        }
        Vec3 playerEye = mc.player.getEyePosition();
        return candidatePoints.stream()
                .min(Comparator.comparingDouble(point -> calculateRotationDifference(playerEye, point, initialAngle)))
                .orElse(null);
    }

    private double calculatePointScore(Vec3 startPoint, Vec3 endPoint, AABB entityBox, Angle initialAngle, PointDataset dataset) {
        double rotationDifference = calculateRotationDifference(startPoint, endPoint, initialAngle);
        double height = Math.max(entityBox.getYsize(), 0.1D);
        double heightFactor = (endPoint.y - entityBox.minY) / height;
        double heightPenalty = Math.abs(heightFactor - dataset.preferredHeight) * dataset.heightBias;
        double lateralDistance = Math.hypot(endPoint.x - entityBox.getCenter().x, endPoint.z - entityBox.getCenter().z);
        double lateralPenalty = lateralDistance * dataset.lateralBias;
        double noise = random.nextDouble() * dataset.randomScore;
        return rotationDifference + heightPenalty + lateralPenalty + noise;
    }

    private double calculateRotationDifference(Vec3 startPoint, Vec3 endPoint, Angle initialAngle) {
        Angle targetAngle = MathAngle.fromVec3d(endPoint.subtract(startPoint));
        Angle delta = MathAngle.calculateDelta(initialAngle, targetAngle);
        return Math.hypot(delta.getYaw(), delta.getPitch());
    }

    private void updateOffset(Vec3 velocity, PointDataset dataset) {
        Vec3 drift = new Vec3(
                random.nextGaussian() * dataset.offsetScale * 0.45D,
                random.nextGaussian() * dataset.offsetScale * 0.22D,
                random.nextGaussian() * dataset.offsetScale * 0.45D
        );

        if (velocity == null) {
            offset = clampOffset(offset.scale(dataset.offsetRetention).add(drift));
            return;
        }

        Vec3 velocityPush = new Vec3(
                random.nextGaussian() * velocity.x * dataset.offsetScale * 2.0D,
                random.nextGaussian() * velocity.y * dataset.offsetScale * 1.35D,
                random.nextGaussian() * velocity.z * dataset.offsetScale * 2.0D
        );
        offset = clampOffset(offset.scale(dataset.offsetRetention).add(velocityPush).add(drift));
    }

    private void updateFunTimeOffset(Vec3 velocity) {
        if (velocity == null) {
            offset = Vec3.ZERO;
            return;
        }
        offset = new Vec3(
                random.nextGaussian() * velocity.x,
                random.nextGaussian() * velocity.y,
                random.nextGaussian() * velocity.z
        );
    }

    private boolean isFunTimeMode() {
        AuraModule aura = AuraModule.getInstance();
        return aura != null && aura.isEnabled() && aura.getMode().is("FunTime");
    }

    private boolean isAimAssistMode() {
        AuraModule aura = AuraModule.getInstance();
        return aura != null && aura.isEnabled() && aura.getMode().is("AimAssist");
    }

    private PointPreset currentPreset() {
        AuraModule aura = AuraModule.getInstance();
        if (aura != null && aura.isEnabled() && (aura.getMode().is("SpookyTime") || aura.getMode().is("SPAngle"))) {
            return SPOOKY_PRESET;
        }

        return DEFAULT_PRESET;
    }

    private Vec3 clampOffset(Vec3 value) {
        double length = value.length();
        if (length <= MAX_OFFSET_LENGTH || length == 0.0D) {
            return value;
        }

        return value.scale(MAX_OFFSET_LENGTH / length);
    }

    private void updateAimAssistPoint(LivingEntity entity) {
        long now = System.currentTimeMillis();
        if (aimAssistEntityId != entity.getId()) {
            aimAssistEntityId = entity.getId();
            aimAssistCurrentPointX = 0.0D;
            aimAssistCurrentPointY = 0.0D;
            aimAssistCurrentPointZ = 0.0D;
            aimAssistHeightNoise = randomDouble(-0.02D, 0.02D);
            aimAssistHeightNoiseUpdatedAtMs = now;
            aimAssistPointLerpSpeed = randomDouble(0.025D, 0.065D);
            randomizeAimAssistPoint(now);
        }

        if (now - aimAssistHeightNoiseUpdatedAtMs > randomLong(180L, 450L)) {
            aimAssistHeightNoise = Mth.clamp(aimAssistHeightNoise + randomDouble(-0.006D, 0.006D), -0.025D, 0.025D);
            aimAssistHeightNoiseUpdatedAtMs = now;
        }

        if (now >= aimAssistNextPointChangeAtMs) {
            randomizeAimAssistPoint(now);
        }

        double lerpSpeed = aimAssistPointLerpSpeed + randomDouble(-0.002D, 0.002D);
        aimAssistCurrentPointX += (aimAssistNextPointX - aimAssistCurrentPointX) * lerpSpeed;
        aimAssistCurrentPointY += (aimAssistNextPointY - aimAssistCurrentPointY) * lerpSpeed;
        aimAssistCurrentPointZ += (aimAssistNextPointZ - aimAssistCurrentPointZ) * lerpSpeed;
    }

    private void randomizeAimAssistPoint(long now) {
        aimAssistNextPointX = randomDouble(-0.35D, 0.35D);
        aimAssistNextPointY = randomDouble(-0.12D, 0.12D);
        aimAssistNextPointZ = randomDouble(-0.35D, 0.35D);
        aimAssistNextPointChangeAtMs = now + randomLong(600L, 1900L);
        aimAssistPointLerpSpeed = randomDouble(0.008D, 0.025D);
    }

    private double randomDouble(double min, double max) {
        return min + random.nextDouble() * (max - min);
    }

    private long randomLong(long min, long max) {
        return min + (long) (random.nextDouble() * (max - min + 1L));
    }

    private PointDataset currentDataset() {
        AuraModule aura = AuraModule.getInstance();
        StrikeManager attackHandler = aura == null ? null : aura.getAttackHandlerRaw();
        int attackCount = attackHandler == null ? lastAttackCount : attackHandler.getCount();
        if (nextDatasetSwitchHitCount < 0 || attackCount < lastAttackCount) {
            lastAttackCount = attackCount;
            nextDatasetSwitchHitCount = attackCount + randomHitsUntilSwitch();
            return DATASETS[datasetIndex];
        }

        lastAttackCount = attackCount;
        if (attackCount < nextDatasetSwitchHitCount) {
            return DATASETS[datasetIndex];
        }

        int step = 1 + random.nextInt(DATASETS.length - 1);
        datasetIndex = (datasetIndex + step) % DATASETS.length;
        resetLockedPoint();
        nextDatasetSwitchHitCount = attackCount + randomHitsUntilSwitch();
        return DATASETS[datasetIndex];
    }

    private void resetLockedPoint() {
        lockedCandidateIndex = -1;
        lockedDatasetIndex = -1;
        lockedEntityId = -1;
        pointHoldTicks = 0;
        nextPointSwitchTicks = 0;
    }

    private int randomPointHoldTicks() {
        return POINT_SWITCH_MIN_TICKS + random.nextInt(POINT_SWITCH_MAX_TICKS - POINT_SWITCH_MIN_TICKS + 1);
    }

    private int randomHitsUntilSwitch() {
        return DATASET_SWITCH_MIN_HITS + random.nextInt(DATASET_SWITCH_MAX_HITS - DATASET_SWITCH_MIN_HITS + 1);
    }

    private record PointScore(int index, double score) {
    }

    private static class PointDataset {
        private final double minHeight;
        private final double maxHeight;
        private final double preferredHeight;
        private final int verticalSamples;
        private final double lateralRadius;
        private final boolean diagonalPoints;
        private final double heightBias;
        private final double lateralBias;
        private final double randomScore;
        private final double offsetScale;
        private final double offsetRetention;

        private PointDataset(double minHeight, double maxHeight, double preferredHeight, int verticalSamples,
                             double lateralRadius, boolean diagonalPoints, double heightBias, double lateralBias,
                             double randomScore, double offsetScale, double offsetRetention) {
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
            this.preferredHeight = preferredHeight;
            this.verticalSamples = verticalSamples;
            this.lateralRadius = lateralRadius;
            this.diagonalPoints = diagonalPoints;
            this.heightBias = heightBias;
            this.lateralBias = lateralBias;
            this.randomScore = randomScore;
            this.offsetScale = offsetScale;
            this.offsetRetention = offsetRetention;
        }
    }

    private static class PointPreset {
        private final AimPoint[] points;

        private PointPreset(AimPoint... points) {
            this.points = points;
        }
    }

    private static class AimPoint {
        private final double x;
        private final double y;
        private final double z;

        private AimPoint(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
