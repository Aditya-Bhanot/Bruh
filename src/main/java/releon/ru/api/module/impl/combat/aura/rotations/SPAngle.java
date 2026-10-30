package releon.ru.api.module.impl.combat.aura.rotations;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.MathAngle;
import releon.ru.api.module.impl.combat.aura.attack.StrikeManager;
import releon.ru.api.module.impl.combat.aura.impl.RotateConstructor;

import java.util.Random;

public class SPAngle extends RotateConstructor {
    public static final SPAngle INSTANCE = new SPAngle();

    private static final int NEURO_PROFILE_TICKS = 100_000;
    private static final long LOST_TARGET_HOLD_MS = 180L;
    private static final float EPSILON = 0.70F;
    private static final float TAU = (float) (Math.PI * 2.0D);
    private static final Random RANDOM = new Random();
    private static final int DATASET_SWITCH_MIN_HITS = 5;
    private static final int DATASET_SWITCH_MAX_HITS = 7;
    private static final RotationDataset[] DATASETS = new RotationDataset[] {
            new RotationDataset(1.00F, 1.00F, 1.00F, 1.00F, 1.00F, 1.00F, 0.00F, 1.00F, 1.00F, 45.0F, 0.85F, 500L, 35.0F, 3.18F, 5.55F, 0.43F, 2.69F, 72.87D),
            new RotationDataset(0.92F, 0.88F, 0.95F, 0.92F, 0.88F, 0.90F, 0.12F, 0.92F, 0.82F, 34.0F, 0.80F, 610L, 30.0F, 1.58F, 6.24F, 0.61F, 1.93F, 48.37D),
            new RotationDataset(1.10F, 1.05F, 1.08F, 1.04F, 1.08F, 1.04F, -0.08F, 1.08F, 1.12F, 52.0F, 0.88F, 475L, 39.0F, 3.08F, 3.68F, 1.44F, 3.27F, 72.33D),
            new RotationDataset(0.98F, 1.12F, 0.98F, 1.10F, 1.02F, 1.08F, 0.06F, 1.04F, 1.04F, 41.0F, 0.84F, 560L, 36.0F, 1.72F, 4.41F, 1.76F, 3.22F, 67.41D),
            new RotationDataset(0.84F, 0.78F, 0.90F, 0.86F, 0.82F, 0.86F, 0.18F, 0.86F, 0.76F, 29.0F, 0.76F, 665L, 28.0F, 1.85F, 3.10F, 1.32F, 1.67F, 50.69D),
            new RotationDataset(0.88F, 0.94F, 0.92F, 0.96F, 0.92F, 0.96F, 0.10F, 0.94F, 0.88F, 33.0F, 0.82F, 620L, 31.0F, 1.29F, 6.42F, 1.37F, 1.72F, 74.66D),
            new RotationDataset(1.04F, 0.96F, 1.02F, 0.94F, 1.04F, 0.98F, -0.02F, 1.02F, 1.02F, 42.0F, 0.86F, 560L, 35.0F, 2.94F, 3.54F, 1.16F, 2.66F, 61.96D),
            new RotationDataset(1.16F, 1.18F, 1.12F, 1.14F, 1.12F, 1.10F, -0.14F, 1.14F, 1.18F, 56.0F, 0.91F, 450L, 42.0F, 2.20F, 3.49F, 0.96F, 1.31F, 59.47D),
            new RotationDataset(0.90F, 1.02F, 0.94F, 1.06F, 0.96F, 1.02F, 0.08F, 0.98F, 0.94F, 37.0F, 0.89F, 650L, 33.0F, 2.85F, 5.64F, 1.16F, 3.23F, 54.62D),
            new RotationDataset(1.08F, 0.90F, 1.06F, 0.92F, 1.10F, 0.96F, -0.06F, 1.06F, 1.08F, 48.0F, 0.83F, 520L, 38.0F, 1.63F, 5.60F, 1.52F, 1.87F, 67.90D),
            new RotationDataset(0.96F, 1.08F, 1.00F, 1.10F, 0.98F, 1.06F, 0.04F, 1.00F, 0.98F, 44.0F, 0.90F, 595L, 34.0F, 1.39F, 2.70F, 0.70F, 1.87F, 47.03D),
            new RotationDataset(1.20F, 1.10F, 1.16F, 1.08F, 1.16F, 1.12F, -0.16F, 1.18F, 1.20F, 61.0F, 0.95F, 420L, 44.0F, 2.83F, 3.43F, 1.67F, 2.14F, 69.13D),
            new RotationDataset(0.86F, 0.82F, 0.91F, 0.88F, 0.86F, 0.84F, 0.16F, 0.88F, 0.80F, 31.0F, 0.78F, 690L, 29.0F, 1.87F, 3.05F, 1.73F, 2.08F, 53.03D),
            new RotationDataset(0.94F, 1.00F, 0.97F, 1.03F, 0.94F, 1.00F, 0.09F, 0.96F, 0.90F, 36.0F, 0.86F, 635L, 32.0F, 3.04F, 5.28F, 0.60F, 1.63F, 56.62D),
            new RotationDataset(1.02F, 1.15F, 1.01F, 1.13F, 1.00F, 1.12F, 0.02F, 1.04F, 1.06F, 43.0F, 0.88F, 585L, 37.0F, 1.18F, 2.56F, 1.72F, 2.07F, 73.25D),
            new RotationDataset(1.12F, 0.98F, 1.10F, 0.98F, 1.14F, 1.02F, -0.10F, 1.10F, 1.14F, 50.0F, 0.87F, 500L, 40.0F, 2.53F, 5.19F, 1.60F, 3.22F, 72.43D),
            new RotationDataset(0.80F, 0.86F, 0.86F, 0.93F, 0.80F, 0.90F, 0.20F, 0.84F, 0.72F, 27.0F, 0.74F, 720L, 27.0F, 1.99F, 2.73F, 1.35F, 2.17F, 69.63D),
            new RotationDataset(1.06F, 1.06F, 1.04F, 1.05F, 1.06F, 1.07F, -0.04F, 1.05F, 1.04F, 46.0F, 0.90F, 540L, 37.0F, 2.79F, 6.10F, 0.69F, 2.46F, 68.12D),
            new RotationDataset(1.18F, 1.24F, 1.14F, 1.18F, 1.18F, 1.16F, -0.18F, 1.20F, 1.22F, 58.0F, 0.94F, 430L, 45.0F, 2.14F, 3.92F, 1.77F, 2.12F, 48.15D),
            new RotationDataset(0.91F, 0.91F, 0.93F, 0.94F, 0.90F, 0.92F, 0.11F, 0.91F, 0.86F, 35.0F, 0.81F, 655L, 31.0F, 2.53F, 5.88F, 0.65F, 2.45F, 58.06D),
            new RotationDataset(0.99F, 1.18F, 0.96F, 1.16F, 0.98F, 1.14F, 0.03F, 1.02F, 1.02F, 40.0F, 0.92F, 575L, 36.0F, 2.49F, 4.96F, 1.17F, 2.72F, 65.62D),
            new RotationDataset(1.14F, 0.86F, 1.12F, 0.90F, 1.16F, 0.92F, -0.12F, 1.12F, 1.16F, 54.0F, 0.82F, 490L, 41.0F, 1.31F, 2.96F, 1.66F, 2.04F, 57.12D),
            new RotationDataset(0.87F, 1.10F, 0.90F, 1.08F, 0.88F, 1.10F, 0.14F, 0.95F, 0.92F, 32.0F, 0.88F, 675L, 30.0F, 1.43F, 3.20F, 0.55F, 2.39F, 56.26D),
            new RotationDataset(1.22F, 1.16F, 1.18F, 1.12F, 1.20F, 1.18F, -0.20F, 1.22F, 1.26F, 63.0F, 0.96F, 405L, 46.0F, 1.70F, 4.52F, 1.12F, 2.48F, 59.62D),
            new RotationDataset(0.82F, 0.80F, 0.88F, 0.86F, 0.82F, 0.84F, 0.19F, 0.82F, 0.74F, 28.0F, 0.76F, 705L, 28.0F, 1.19F, 4.33F, 1.24F, 2.29F, 71.76D),
            new RotationDataset(0.89F, 0.97F, 0.94F, 1.00F, 0.90F, 0.98F, 0.13F, 0.90F, 0.84F, 34.0F, 0.84F, 660L, 31.0F, 2.71F, 3.31F, 1.41F, 1.76F, 53.29D),
            new RotationDataset(0.93F, 0.84F, 0.96F, 0.89F, 0.94F, 0.88F, 0.10F, 0.93F, 0.86F, 36.0F, 0.79F, 640L, 32.0F, 3.09F, 4.37F, 0.85F, 1.96F, 52.94D),
            new RotationDataset(0.97F, 1.04F, 0.99F, 1.06F, 0.97F, 1.04F, 0.05F, 0.98F, 0.96F, 39.0F, 0.87F, 600L, 34.0F, 3.01F, 4.79F, 0.47F, 3.07F, 65.65D),
            new RotationDataset(1.01F, 0.93F, 1.02F, 0.95F, 1.03F, 0.96F, 0.00F, 1.01F, 1.00F, 43.0F, 0.83F, 565L, 35.0F, 2.52F, 6.58F, 1.54F, 1.89F, 62.49D),
            new RotationDataset(1.05F, 1.11F, 1.05F, 1.09F, 1.05F, 1.10F, -0.03F, 1.06F, 1.08F, 47.0F, 0.89F, 535L, 38.0F, 1.74F, 3.44F, 1.05F, 3.35F, 68.98D),
            new RotationDataset(1.09F, 1.01F, 1.08F, 1.00F, 1.11F, 1.02F, -0.07F, 1.09F, 1.10F, 50.0F, 0.86F, 510L, 40.0F, 1.47F, 3.53F, 1.29F, 1.64F, 55.63D),
            new RotationDataset(1.13F, 1.21F, 1.11F, 1.17F, 1.13F, 1.15F, -0.11F, 1.14F, 1.18F, 55.0F, 0.93F, 455L, 43.0F, 1.53F, 6.79F, 0.50F, 3.03F, 73.27D),
            new RotationDataset(1.17F, 0.95F, 1.15F, 0.97F, 1.18F, 0.99F, -0.15F, 1.16F, 1.20F, 59.0F, 0.84F, 440L, 44.0F, 1.17F, 4.23F, 1.36F, 1.71F, 59.91D),
            new RotationDataset(1.24F, 1.27F, 1.20F, 1.19F, 1.23F, 1.21F, -0.22F, 1.24F, 1.28F, 65.0F, 0.97F, 395L, 47.0F, 2.75F, 6.32F, 1.44F, 1.79F, 60.07D),
            new RotationDataset(0.78F, 0.83F, 0.84F, 0.91F, 0.78F, 0.87F, 0.22F, 0.80F, 0.70F, 26.0F, 0.73F, 740L, 26.0F, 2.81F, 4.28F, 1.47F, 3.39F, 69.84D),
            new RotationDataset(0.85F, 0.90F, 0.89F, 0.95F, 0.84F, 0.93F, 0.17F, 0.86F, 0.78F, 30.0F, 0.80F, 700L, 29.0F, 1.40F, 5.19F, 1.05F, 3.50F, 62.09D),
            new RotationDataset(0.90F, 1.07F, 0.93F, 1.08F, 0.91F, 1.05F, 0.12F, 0.94F, 0.91F, 35.0F, 0.88F, 625L, 33.0F, 2.19F, 5.15F, 1.63F, 2.10F, 47.14D),
            new RotationDataset(0.95F, 0.89F, 0.98F, 0.92F, 0.97F, 0.91F, 0.07F, 0.97F, 0.94F, 38.0F, 0.82F, 615L, 34.0F, 2.52F, 6.06F, 0.78F, 1.13F, 46.05D),
            new RotationDataset(1.00F, 1.13F, 1.00F, 1.12F, 1.01F, 1.11F, 0.02F, 1.03F, 1.04F, 42.0F, 0.91F, 570L, 36.0F, 3.11F, 4.45F, 0.84F, 3.24F, 63.46D),
            new RotationDataset(1.07F, 0.87F, 1.07F, 0.91F, 1.09F, 0.90F, -0.05F, 1.07F, 1.09F, 49.0F, 0.81F, 525L, 39.0F, 1.60F, 3.43F, 1.38F, 2.75F, 69.71D),
            new RotationDataset(1.11F, 1.09F, 1.09F, 1.07F, 1.12F, 1.09F, -0.09F, 1.11F, 1.13F, 51.0F, 0.88F, 500L, 41.0F, 2.08F, 4.62F, 0.70F, 1.90F, 45.38D),
            new RotationDataset(1.15F, 1.22F, 1.13F, 1.18F, 1.15F, 1.17F, -0.13F, 1.16F, 1.21F, 57.0F, 0.94F, 445L, 44.0F, 2.83F, 4.63F, 1.49F, 1.84F, 50.45D),
            new RotationDataset(1.19F, 1.04F, 1.17F, 1.03F, 1.19F, 1.05F, -0.17F, 1.18F, 1.23F, 60.0F, 0.89F, 425L, 45.0F, 2.43F, 4.64F, 0.65F, 1.38F, 57.69D),
            new RotationDataset(1.27F, 1.19F, 1.22F, 1.15F, 1.25F, 1.20F, -0.24F, 1.26F, 1.30F, 67.0F, 0.98F, 380L, 48.0F, 2.09F, 4.80F, 0.78F, 1.13F, 70.09D),
            new RotationDataset(0.81F, 0.76F, 0.87F, 0.84F, 0.81F, 0.82F, 0.21F, 0.81F, 0.72F, 27.0F, 0.75F, 730L, 27.0F, 3.22F, 5.06F, 1.43F, 1.78F, 62.51D),
            new RotationDataset(0.87F, 1.01F, 0.91F, 1.02F, 0.88F, 0.99F, 0.15F, 0.91F, 0.83F, 32.0F, 0.86F, 675L, 31.0F, 1.78F, 2.82F, 1.74F, 2.09F, 62.01D),
            new RotationDataset(0.92F, 0.95F, 0.95F, 0.97F, 0.93F, 0.95F, 0.11F, 0.94F, 0.88F, 36.0F, 0.84F, 645L, 32.0F, 3.18F, 6.59F, 1.56F, 1.91F, 48.01D),
            new RotationDataset(0.98F, 1.17F, 0.99F, 1.15F, 0.99F, 1.13F, 0.04F, 1.01F, 1.01F, 41.0F, 0.92F, 585L, 36.0F, 1.08F, 4.17F, 1.15F, 2.06F, 53.23D),
            new RotationDataset(1.03F, 0.99F, 1.04F, 1.01F, 1.05F, 1.00F, -0.01F, 1.04F, 1.05F, 45.0F, 0.87F, 545L, 37.0F, 1.41F, 2.50F, 0.48F, 3.30F, 57.39D),
            new RotationDataset(1.08F, 1.14F, 1.07F, 1.11F, 1.10F, 1.12F, -0.06F, 1.09F, 1.12F, 49.0F, 0.91F, 505L, 40.0F, 1.99F, 3.65F, 0.61F, 2.01F, 49.21D),
            new RotationDataset(1.12F, 0.91F, 1.11F, 0.94F, 1.14F, 0.94F, -0.10F, 1.12F, 1.15F, 53.0F, 0.82F, 485L, 41.0F, 2.70F, 6.78F, 1.31F, 3.22F, 70.82D),
            new RotationDataset(1.16F, 1.25F, 1.14F, 1.20F, 1.17F, 1.19F, -0.14F, 1.18F, 1.24F, 58.0F, 0.95F, 435L, 45.0F, 3.05F, 5.85F, 1.80F, 3.10F, 65.17D),
            new RotationDataset(1.21F, 1.08F, 1.19F, 1.06F, 1.21F, 1.08F, -0.19F, 1.21F, 1.27F, 62.0F, 0.90F, 410L, 46.0F, 2.62F, 4.83F, 1.15F, 2.79F, 51.96D),
            new RotationDataset(1.30F, 1.30F, 1.24F, 1.22F, 1.28F, 1.24F, -0.26F, 1.28F, 1.32F, 69.0F, 0.99F, 365L, 49.0F, 2.64F, 5.42F, 0.54F, 1.09F, 56.62D),
            new RotationDataset(0.79F, 0.88F, 0.85F, 0.94F, 0.79F, 0.91F, 0.23F, 0.83F, 0.76F, 29.0F, 0.77F, 715L, 28.0F, 1.21F, 6.95F, 1.62F, 3.13F, 64.79D),
            new RotationDataset(0.84F, 0.93F, 0.89F, 0.98F, 0.85F, 0.96F, 0.18F, 0.87F, 0.81F, 31.0F, 0.83F, 685L, 30.0F, 3.17F, 4.81F, 1.67F, 3.22F, 49.57D),
            new RotationDataset(0.91F, 1.12F, 0.94F, 1.10F, 0.92F, 1.08F, 0.10F, 0.96F, 0.95F, 37.0F, 0.90F, 620L, 34.0F, 1.16F, 6.73F, 1.20F, 1.55F, 69.82D),
            new RotationDataset(0.96F, 0.86F, 0.99F, 0.90F, 1.00F, 0.89F, 0.06F, 0.99F, 0.97F, 40.0F, 0.80F, 595L, 35.0F, 3.30F, 3.90F, 0.44F, 1.52F, 51.30D),
            new RotationDataset(1.02F, 1.20F, 1.03F, 1.17F, 1.04F, 1.15F, 0.00F, 1.05F, 1.07F, 44.0F, 0.93F, 550L, 38.0F, 1.66F, 4.18F, 1.48F, 3.01F, 62.08D),
            new RotationDataset(1.06F, 0.97F, 1.06F, 0.99F, 1.08F, 0.98F, -0.04F, 1.08F, 1.10F, 48.0F, 0.85F, 525L, 39.0F, 2.49F, 4.64F, 0.96F, 2.63F, 66.05D),
            new RotationDataset(1.10F, 1.17F, 1.09F, 1.14F, 1.13F, 1.15F, -0.08F, 1.13F, 1.17F, 52.0F, 0.92F, 485L, 42.0F, 2.78F, 3.38F, 0.52F, 3.79F, 46.53D),
            new RotationDataset(1.14F, 0.94F, 1.13F, 0.96F, 1.16F, 0.97F, -0.12F, 1.15F, 1.19F, 56.0F, 0.84F, 465L, 43.0F, 2.73F, 3.33F, 1.63F, 1.98F, 72.55D),
            new RotationDataset(1.18F, 1.28F, 1.17F, 1.21F, 1.20F, 1.22F, -0.16F, 1.21F, 1.28F, 61.0F, 0.96F, 415L, 47.0F, 1.84F, 6.29F, 0.68F, 2.66F, 63.10D),
            new RotationDataset(1.25F, 1.12F, 1.21F, 1.09F, 1.24F, 1.13F, -0.21F, 1.24F, 1.30F, 65.0F, 0.91F, 390L, 48.0F, 2.75F, 6.81F, 1.00F, 3.29F, 52.01D),
            new RotationDataset(1.33F, 1.24F, 1.27F, 1.18F, 1.31F, 1.25F, -0.28F, 1.30F, 1.34F, 72.0F, 1.00F, 350L, 50.0F, 2.04F, 5.28F, 1.65F, 3.38F, 48.07D),
            new RotationDataset(0.76F, 0.79F, 0.82F, 0.85F, 0.76F, 0.82F, 0.24F, 0.78F, 0.68F, 25.0F, 0.72F, 760L, 25.0F, 2.62F, 3.39F, 0.60F, 1.48F, 45.37D),
            new RotationDataset(0.80F, 0.92F, 0.86F, 0.96F, 0.80F, 0.94F, 0.21F, 0.84F, 0.78F, 29.0F, 0.81F, 720L, 28.0F, 2.05F, 2.97F, 0.83F, 1.19F, 56.33D),
            new RotationDataset(0.83F, 0.84F, 0.89F, 0.90F, 0.84F, 0.87F, 0.18F, 0.86F, 0.80F, 30.0F, 0.78F, 700L, 29.0F, 3.07F, 6.35F, 1.20F, 1.55F, 47.30D),
            new RotationDataset(0.86F, 1.06F, 0.90F, 1.07F, 0.86F, 1.05F, 0.15F, 0.90F, 0.88F, 33.0F, 0.87F, 665L, 31.0F, 3.11F, 4.14F, 1.64F, 1.99F, 70.34D),
            new RotationDataset(0.88F, 0.80F, 0.93F, 0.86F, 0.91F, 0.84F, 0.14F, 0.91F, 0.83F, 34.0F, 0.76F, 675L, 30.0F, 2.01F, 3.27F, 0.63F, 1.26F, 69.19D),
            new RotationDataset(0.90F, 1.19F, 0.94F, 1.13F, 0.92F, 1.12F, 0.11F, 0.98F, 0.97F, 38.0F, 0.92F, 610L, 34.0F, 2.59F, 3.19F, 1.21F, 1.95F, 52.49D),
            new RotationDataset(0.94F, 0.91F, 0.98F, 0.94F, 0.97F, 0.93F, 0.08F, 0.96F, 0.92F, 39.0F, 0.82F, 600L, 34.0F, 1.34F, 3.68F, 0.72F, 3.49F, 55.45D),
            new RotationDataset(0.97F, 1.25F, 0.99F, 1.18F, 1.00F, 1.18F, 0.05F, 1.04F, 1.04F, 43.0F, 0.94F, 555L, 37.0F, 2.23F, 6.70F, 1.35F, 1.70F, 58.91D),
            new RotationDataset(1.00F, 0.83F, 1.03F, 0.88F, 1.06F, 0.87F, 0.01F, 1.02F, 1.02F, 45.0F, 0.79F, 565L, 36.0F, 1.56F, 4.87F, 0.54F, 1.57F, 66.08D),
            new RotationDataset(1.04F, 1.03F, 1.05F, 1.04F, 1.08F, 1.05F, -0.02F, 1.06F, 1.08F, 47.0F, 0.88F, 525L, 38.0F, 3.08F, 4.16F, 0.93F, 3.07F, 54.44D),
            new RotationDataset(1.07F, 1.22F, 1.08F, 1.16F, 1.11F, 1.17F, -0.05F, 1.11F, 1.15F, 51.0F, 0.93F, 490L, 41.0F, 1.17F, 5.79F, 1.23F, 1.58F, 64.91D),
            new RotationDataset(1.10F, 0.88F, 1.12F, 0.92F, 1.15F, 0.91F, -0.08F, 1.11F, 1.13F, 52.0F, 0.81F, 500L, 40.0F, 2.18F, 2.78F, 0.43F, 2.05F, 54.68D),
            new RotationDataset(1.13F, 1.15F, 1.13F, 1.12F, 1.17F, 1.14F, -0.11F, 1.16F, 1.20F, 56.0F, 0.90F, 460L, 43.0F, 1.92F, 4.91F, 1.72F, 3.36F, 51.89D),
            new RotationDataset(1.16F, 1.31F, 1.16F, 1.23F, 1.21F, 1.24F, -0.14F, 1.22F, 1.27F, 60.0F, 0.97F, 415L, 47.0F, 2.43F, 5.31F, 1.35F, 2.94F, 61.26D),
            new RotationDataset(1.20F, 0.99F, 1.20F, 1.02F, 1.24F, 1.04F, -0.18F, 1.20F, 1.25F, 63.0F, 0.86F, 405L, 46.0F, 3.26F, 5.61F, 1.46F, 3.31F, 63.07D),
            new RotationDataset(1.24F, 1.18F, 1.22F, 1.14F, 1.27F, 1.18F, -0.22F, 1.25F, 1.31F, 67.0F, 0.94F, 375L, 49.0F, 3.07F, 3.67F, 1.71F, 2.12F, 72.08D),
            new RotationDataset(1.28F, 1.34F, 1.25F, 1.24F, 1.31F, 1.27F, -0.25F, 1.31F, 1.36F, 71.0F, 1.00F, 345L, 51.0F, 1.71F, 5.51F, 1.56F, 1.91F, 50.27D),
            new RotationDataset(1.36F, 1.28F, 1.30F, 1.20F, 1.34F, 1.29F, -0.30F, 1.33F, 1.38F, 75.0F, 1.00F, 330L, 52.0F, 1.94F, 5.54F, 0.70F, 1.57F, 70.04D),
            new RotationDataset(0.77F, 0.86F, 0.83F, 0.91F, 0.77F, 0.90F, 0.25F, 0.80F, 0.73F, 27.0F, 0.76F, 735L, 27.0F, 2.11F, 6.61F, 0.80F, 3.24F, 46.96D),
            new RotationDataset(0.81F, 0.97F, 0.87F, 1.00F, 0.82F, 0.99F, 0.20F, 0.86F, 0.82F, 31.0F, 0.85F, 690L, 30.0F, 2.60F, 5.12F, 0.75F, 1.10F, 47.40D),
            new RotationDataset(0.85F, 0.77F, 0.91F, 0.84F, 0.88F, 0.81F, 0.17F, 0.87F, 0.79F, 32.0F, 0.74F, 710L, 29.0F, 1.62F, 5.63F, 1.35F, 1.70F, 54.93D),
            new RotationDataset(0.89F, 1.14F, 0.93F, 1.11F, 0.91F, 1.10F, 0.12F, 0.96F, 0.94F, 36.0F, 0.90F, 625L, 33.0F, 1.86F, 6.90F, 1.47F, 2.09F, 67.88D),
            new RotationDataset(0.93F, 0.88F, 0.97F, 0.92F, 0.96F, 0.90F, 0.09F, 0.95F, 0.90F, 38.0F, 0.80F, 615L, 33.0F, 1.97F, 2.93F, 1.24F, 2.33F, 45.48D),
            new RotationDataset(0.99F, 1.09F, 1.01F, 1.08F, 1.03F, 1.09F, 0.03F, 1.02F, 1.06F, 43.0F, 0.89F, 560L, 37.0F, 2.66F, 5.70F, 1.66F, 2.85F, 69.59D),
            new RotationDataset(1.03F, 0.95F, 1.05F, 0.98F, 1.08F, 0.97F, -0.01F, 1.05F, 1.06F, 46.0F, 0.84F, 535L, 38.0F, 3.16F, 4.32F, 1.03F, 3.42F, 46.76D),
            new RotationDataset(1.06F, 1.28F, 1.07F, 1.20F, 1.10F, 1.21F, -0.04F, 1.13F, 1.18F, 50.0F, 0.96F, 485L, 42.0F, 1.16F, 4.30F, 1.74F, 3.12F, 72.17D),
            new RotationDataset(1.09F, 0.82F, 1.11F, 0.88F, 1.14F, 0.86F, -0.07F, 1.09F, 1.10F, 51.0F, 0.78F, 505L, 39.0F, 1.71F, 6.27F, 1.71F, 2.06F, 63.93D),
            new RotationDataset(1.12F, 1.07F, 1.12F, 1.06F, 1.16F, 1.07F, -0.10F, 1.14F, 1.18F, 55.0F, 0.88F, 470L, 42.0F, 2.39F, 6.99F, 1.27F, 2.98F, 69.00D),
            new RotationDataset(1.15F, 1.19F, 1.15F, 1.15F, 1.20F, 1.16F, -0.13F, 1.19F, 1.23F, 58.0F, 0.92F, 435L, 45.0F, 1.99F, 6.62F, 1.53F, 2.26F, 57.33D),
            new RotationDataset(1.19F, 1.36F, 1.18F, 1.25F, 1.23F, 1.28F, -0.17F, 1.25F, 1.31F, 63.0F, 0.98F, 395L, 49.0F, 1.85F, 3.87F, 1.51F, 3.19F, 55.29D),
            new RotationDataset(1.23F, 0.93F, 1.22F, 0.98F, 1.27F, 0.99F, -0.21F, 1.22F, 1.27F, 65.0F, 0.83F, 390L, 47.0F, 3.25F, 5.59F, 1.21F, 1.56F, 49.20D),
            new RotationDataset(1.27F, 1.15F, 1.24F, 1.12F, 1.30F, 1.16F, -0.24F, 1.28F, 1.34F, 69.0F, 0.91F, 365L, 50.0F, 2.97F, 3.57F, 1.53F, 1.88F, 52.91D),
            new RotationDataset(1.31F, 1.31F, 1.28F, 1.22F, 1.33F, 1.30F, -0.27F, 1.34F, 1.39F, 73.0F, 0.99F, 340L, 52.0F, 3.21F, 3.81F, 0.40F, 2.36F, 58.58D),
            new RotationDataset(1.38F, 1.40F, 1.32F, 1.27F, 1.38F, 1.34F, -0.32F, 1.36F, 1.42F, 78.0F, 1.00F, 315L, 54.0F, 2.34F, 3.23F, 0.76F, 3.40F, 57.35D),
            new RotationDataset(0.74F, 0.82F, 0.81F, 0.88F, 0.74F, 0.85F, 0.27F, 0.77F, 0.66F, 24.0F, 0.71F, 780L, 24.0F, 2.66F, 3.26F, 0.65F, 3.43F, 56.62D),
            new RotationDataset(0.79F, 1.00F, 0.85F, 1.02F, 0.80F, 1.01F, 0.23F, 0.85F, 0.81F, 30.0F, 0.86F, 705L, 30.0F, 1.05F, 5.21F, 1.77F, 2.23F, 65.73D),
            new RotationDataset(0.84F, 0.89F, 0.90F, 0.94F, 0.87F, 0.91F, 0.18F, 0.89F, 0.84F, 33.0F, 0.81F, 680L, 31.0F, 2.72F, 5.26F, 0.63F, 2.12F, 72.76D),
            new RotationDataset(0.91F, 1.24F, 0.94F, 1.17F, 0.94F, 1.17F, 0.10F, 1.00F, 1.00F, 39.0F, 0.94F, 595L, 35.0F, 2.42F, 3.62F, 1.46F, 1.81F, 68.30D),
            new RotationDataset(0.96F, 0.79F, 1.00F, 0.86F, 1.02F, 0.83F, 0.05F, 0.98F, 0.95F, 41.0F, 0.76F, 600L, 34.0F, 1.81F, 3.27F, 1.07F, 2.21F, 70.13D),
            new RotationDataset(1.01F, 1.16F, 1.03F, 1.13F, 1.06F, 1.14F, 0.00F, 1.07F, 1.11F, 46.0F, 0.92F, 535L, 39.0F, 1.94F, 6.62F, 0.82F, 1.17F, 69.82D),
            new RotationDataset(1.05F, 0.90F, 1.07F, 0.95F, 1.10F, 0.93F, -0.03F, 1.06F, 1.07F, 48.0F, 0.82F, 535L, 38.0F, 1.43F, 2.69F, 1.70F, 2.05F, 63.57D),
            new RotationDataset(1.09F, 1.33F, 1.10F, 1.24F, 1.14F, 1.25F, -0.07F, 1.17F, 1.22F, 53.0F, 0.98F, 465L, 44.0F, 3.04F, 4.75F, 1.38F, 3.20F, 48.50D),
            new RotationDataset(1.13F, 0.86F, 1.14F, 0.91F, 1.18F, 0.90F, -0.11F, 1.13F, 1.16F, 55.0F, 0.80F, 475L, 42.0F, 1.56F, 3.64F, 0.89F, 2.22F, 60.44D),
            new RotationDataset(1.17F, 1.11F, 1.17F, 1.09F, 1.21F, 1.11F, -0.15F, 1.19F, 1.25F, 60.0F, 0.89F, 425L, 46.0F, 1.79F, 6.35F, 1.21F, 2.58F, 70.06D),
            new RotationDataset(1.21F, 1.22F, 1.20F, 1.17F, 1.26F, 1.20F, -0.19F, 1.24F, 1.31F, 64.0F, 0.93F, 390L, 49.0F, 1.73F, 4.36F, 0.48F, 1.15F, 46.79D),
            new RotationDataset(1.26F, 1.39F, 1.23F, 1.28F, 1.30F, 1.33F, -0.23F, 1.30F, 1.37F, 70.0F, 0.99F, 350L, 53.0F, 2.23F, 5.24F, 1.42F, 1.77F, 74.35D),
            new RotationDataset(1.30F, 0.98F, 1.27F, 1.03F, 1.34F, 1.04F, -0.27F, 1.27F, 1.32F, 72.0F, 0.86F, 345L, 51.0F, 1.25F, 3.82F, 1.75F, 2.73F, 60.13D),
            new RotationDataset(1.35F, 1.20F, 1.30F, 1.16F, 1.38F, 1.22F, -0.31F, 1.35F, 1.42F, 76.0F, 0.94F, 320L, 55.0F, 2.58F, 5.57F, 1.50F, 3.23F, 46.15D),
            new RotationDataset(1.42F, 1.36F, 1.35F, 1.26F, 1.42F, 1.37F, -0.35F, 1.38F, 1.46F, 82.0F, 1.00F, 300L, 57.0F, 1.25F, 4.99F, 1.75F, 2.10F, 60.98D)
    };

    private static long smoothbackShakeStartMs = -1L;
    private static int datasetIndex;
    private static int lastAttackCount;
    private static int nextDatasetSwitchHitCount = -1;
    private static int lastEntityId = -1;
    private static float neuralAttention = 0.62F;
    private static float neuralFatigue;
    private static float neuralStability;
    private static float lastYawError;
    private static float lastPitchError;
    private static Angle lastHeldTargetAngle;
    private static long lostTargetAtMs = -1L;
    private static boolean hadTargetLastTick;

    private SPAngle() {
        super("SPAngle");
    }

    @Override
    public Angle limitAngleChange(Angle currentAngle, Angle targetAngle, Vec3 vec3d, Entity entity) {
        return SpookyTimeNewAngle.INSTANCE.limitAngleChange(currentAngle, targetAngle, vec3d, entity);
    }

    private NeuroProfile currentNeuroProfile(float difference, float yawDelta, float pitchDelta, Entity entity,
                                             RotationDataset dataset) {
        Minecraft client = Minecraft.getInstance();
        int tick = client.player == null ? 0 : Math.floorMod(client.player.tickCount, NEURO_PROFILE_TICKS);
        float cycle = tick / (float) NEURO_PROFILE_TICKS;
        updateNeuralState(difference, yawDelta, pitchDelta, entity);

        float shortWave = wave(tick, 37, 0.0F);
        float mediumWave = wave(tick, 211, 1.7F);
        float longWave = wave(tick, 1800, 0.8F);
        float deepWave = wave(tick, 12000, 2.4F);
        float pulse = softPulse(tick, 97, 0.18F);
        float focus = Mth.clamp(difference / 60.0F, 0.0F, 1.0F);
        float velocityFocus = entity == null ? 0.0F : Mth.clamp((float) entity.getDeltaMovement().horizontalDistance() * 2.6F, 0.0F, 1.0F);
        float distanceFocus = client.player == null || entity == null
                ? 0.35F
                : Mth.clamp(client.player.distanceTo(entity) / 5.0F, 0.0F, 1.0F);
        float correctionPressure = Mth.clamp((Math.abs(yawDelta - lastYawError) + Math.abs(pitchDelta - lastPitchError)) / 35.0F, 0.0F, 1.0F);
        float cognitiveLoad = Mth.clamp(focus * 0.45F + velocityFocus * 0.25F + correctionPressure * 0.30F, 0.0F, 1.0F);

        float yawSpeed = (8.5F
                + 5.4F * focus
                + 3.0F * neuralAttention
                + 2.0F * velocityFocus
                - 2.4F * neuralFatigue
                + 2.2F * shortWave
                + 1.6F * mediumWave
                + 2.4F * deepWave
                + pulse * 3.0F) * dataset.yawSpeedMultiplier();
        float pitchSpeed = (7.0F
                + 4.2F * focus
                + 1.8F * neuralAttention
                + 1.4F * velocityFocus
                - 1.6F * neuralFatigue
                + 1.5F * wave(tick, 53, 1.1F)
                + 1.9F * longWave
                + pulse * 1.8F) * dataset.pitchSpeedMultiplier();

        float yawLimit = (15.0F + 5.0F * wave(tick, 701, 0.3F) + 3.0F * focus + 2.0F * distanceFocus) * dataset.yawLimitMultiplier();
        float pitchLimit = (24.0F + 8.0F * wave(tick, 977, 2.0F) + 2.0F * focus + 1.4F * neuralStability) * dataset.pitchLimitMultiplier();
        float yawLimitSpeed = (46.0F + 18.0F * wave(tick, 1409, 0.7F) + 10.0F * focus + 7.0F * cognitiveLoad) * dataset.yawLimitSpeedMultiplier();
        float pitchLimitSpeed = (38.0F + 15.0F * wave(tick, 1663, 1.9F) + 8.0F * focus + 5.0F * cognitiveLoad) * dataset.pitchLimitSpeedMultiplier();
        float epsilon = EPSILON + 0.12F + dataset.epsilonAdd() + 0.22F * wave(tick, 431, 1.4F) + 0.20F * neuralStability;
        float easeGain = Mth.clamp(0.78F
                + 0.18F * wave(tick, 809, 0.5F)
                + 0.10F * focus
                + 0.08F * neuralAttention
                - 0.12F * neuralFatigue, 0.66F, 1.12F) * dataset.easeMultiplier();
        float yawWeight = Mth.clamp(0.86F + 0.18F * focus + 0.10F * velocityFocus - 0.08F * neuralStability, 0.78F, 1.12F);
        float pitchWeight = Mth.clamp(0.80F + 0.12F * focus + 0.06F * neuralAttention - 0.06F * neuralFatigue, 0.72F, 1.04F);
        float swayYaw = (Mth.sin(cycle * TAU * 37.0F + 0.4F) * 0.55F
                + Mth.sin(cycle * TAU * 311.0F + 2.1F) * 0.18F)
                * (0.65F + 0.35F * neuralStability) * dataset.swayMultiplier();
        float swayPitch = (Mth.cos(cycle * TAU * 29.0F + 1.2F) * 0.28F
                + Mth.sin(cycle * TAU * 173.0F + 0.9F) * 0.10F)
                * (0.70F + 0.30F * neuralStability) * dataset.swayMultiplier();

        lastYawError = yawDelta;
        lastPitchError = pitchDelta;

        return new NeuroProfile(
                Mth.clamp(yawSpeed, 5.5F, 19.5F),
                Mth.clamp(pitchSpeed, 4.5F, 16.5F),
                Mth.clamp(yawLimit, 10.0F, 25.0F),
                Mth.clamp(pitchLimit, 18.0F, 36.0F),
                Mth.clamp(yawLimitSpeed, 24.0F, dataset.limitRotationSpeed()),
                Mth.clamp(pitchLimitSpeed, 22.0F, dataset.limitRotationSpeed()),
                Mth.clamp(epsilon, 0.85F, 1.45F),
                Mth.clamp(easeGain, 0.62F, 1.16F),
                yawWeight,
                pitchWeight,
                swayYaw,
                swayPitch
        );
    }

    private void updateNeuralState(float difference, float yawDelta, float pitchDelta, Entity entity) {
        int entityId = entity == null ? -1 : entity.getId();
        if (entityId != lastEntityId) {
            neuralAttention = 0.76F;
            neuralFatigue = 0.0F;
            neuralStability = 0.0F;
            lastYawError = yawDelta;
            lastPitchError = pitchDelta;
            lastEntityId = entityId;
            return;
        }

        float velocityFocus = entity == null ? 0.0F : Mth.clamp((float) entity.getDeltaMovement().horizontalDistance() * 2.8F, 0.0F, 1.0F);
        float errorFocus = Mth.clamp(difference / 75.0F, 0.0F, 1.0F);
        float errorDelta = Mth.clamp((Math.abs(yawDelta - lastYawError) + Math.abs(pitchDelta - lastPitchError)) / 45.0F, 0.0F, 1.0F);
        float targetAttention = Mth.clamp(0.45F + errorFocus * 0.34F + velocityFocus * 0.18F + errorDelta * 0.12F, 0.35F, 0.95F);
        float targetFatigue = Mth.clamp(neuralFatigue + 0.012F * targetAttention - 0.018F * (1.0F - errorFocus), 0.0F, 0.42F);
        float targetStability = Mth.clamp(1.0F - errorFocus * 0.75F - errorDelta * 0.35F, 0.0F, 1.0F);

        neuralAttention = Mth.lerp(0.18F, neuralAttention, targetAttention);
        neuralFatigue = Mth.lerp(0.08F, neuralFatigue, targetFatigue);
        neuralStability = Mth.lerp(0.12F, neuralStability, targetStability);
    }

    private float wave(int tick, int period, float phase) {
        return (Mth.sin((tick % period) / (float) period * TAU + phase) + 1.0F) * 0.5F;
    }

    private float softPulse(int tick, int period, float width) {
        float position = (tick % period) / (float) period;
        float distance = Math.abs(position - 0.5F) * 2.0F;
        return Mth.clamp((width - distance) / width, 0.0F, 1.0F);
    }

    private void applyNeuroSway(Angle angle, NeuroProfile profile) {
        angle.setYaw(angle.getYaw() + profile.swayYaw());
        angle.setPitch(Mth.clamp(angle.getPitch() + profile.swayPitch(), -89.0F, 90.0F));
    }

    private void applyDatasetShake(Angle angle, RotationDataset dataset) {
        float yaw = (float) (randomFloat(dataset.shakeYawMin(), dataset.shakeYawMax())
                * Math.sin(System.currentTimeMillis() / dataset.shakeSpeed()));
        float pitch = (float) (randomFloat(dataset.shakePitchMin(), dataset.shakePitchMax())
                * Math.cos(System.currentTimeMillis() / dataset.shakeSpeed()));

        angle.setYaw(angle.getYaw() + yaw);
        angle.setPitch(Mth.clamp(angle.getPitch() + pitch, -89.0F, 90.0F));
    }

    private float easeTowardsTarget(float value) {
        return value * (0.5F + 0.5F * value);
    }

    private RotationDataset currentDataset(int attackCount) {
        if (nextDatasetSwitchHitCount < 0 || attackCount < lastAttackCount) {
            lastAttackCount = attackCount;
            nextDatasetSwitchHitCount = attackCount + randomHitsUntilSwitch();
            return DATASETS[datasetIndex];
        }

        lastAttackCount = attackCount;
        if (attackCount < nextDatasetSwitchHitCount) {
            return DATASETS[datasetIndex];
        }

        int step = 1 + RANDOM.nextInt(DATASETS.length - 1);
        datasetIndex = (datasetIndex + step) % DATASETS.length;
        nextDatasetSwitchHitCount = attackCount + randomHitsUntilSwitch();
        return DATASETS[datasetIndex];
    }

    private int randomHitsUntilSwitch() {
        return DATASET_SWITCH_MIN_HITS + RANDOM.nextInt(DATASET_SWITCH_MAX_HITS - DATASET_SWITCH_MIN_HITS + 1);
    }

    private Angle smoothBackToPlayerView(Angle currentAngle, RotationDataset dataset) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return currentAngle;
        }

        Angle playerViewAngle = new Angle(client.player.getYRot(), client.player.getXRot());
        Angle angleToPlayerView = MathAngle.calculateDelta(currentAngle, playerViewAngle);
        float yawDelta = angleToPlayerView.getYaw();
        float pitchDelta = angleToPlayerView.getPitch();
        float rotationDifference = safeRotationDifference(yawDelta, pitchDelta);

        float yaw = (float) (randomFloat(6.0F, 8.0F) * Math.sin(System.currentTimeMillis() / 60.0D));
        float pitch = (float) (randomFloat(3.0F, 8.0F) * Math.cos(System.currentTimeMillis() / 60.0D));
        AuraModule aura = AuraModule.getInstance();
        if (aura == null || !aura.isEnabled() || AuraModule.target == null) {
            if (smoothbackShakeStartMs < 0L) {
                smoothbackShakeStartMs = System.currentTimeMillis();
            }
            float shakeFade = 1.0F - Mth.clamp((System.currentTimeMillis() - smoothbackShakeStartMs) / 500F, 0.0F, 1.0F);
            yaw *= shakeFade;
            pitch *= shakeFade;
        } else {
            smoothbackShakeStartMs = -1L;
        }
        StrikeManager attackHandler = aura == null ? null : aura.getAttackHandlerRaw();
        float speedYaw = Math.abs(yawDelta / rotationDifference) * (attackHandler != null && !attackHandler.getAttackTimer().finished(535) ? 0.0F : 45.0F);
        float speedPitch = Math.abs(pitchDelta / rotationDifference) * (attackHandler != null && !attackHandler.getAttackTimer().finished(535) ? 0.0F : 45.0F);

        return new Angle(
                Mth.lerp(
                        0.85F,
                        currentAngle.getYaw(),
                        currentAngle.getYaw() + Mth.clamp(yawDelta, -speedYaw, speedYaw) + yaw
                ),
                Mth.lerp(
                        0.85F,
                        currentAngle.getPitch(),
                        currentAngle.getPitch() + Mth.clamp(pitchDelta, -speedPitch, speedPitch) + pitch
                )
        );
    }

    private Angle holdLastTargetPoint(Angle currentAngle, RotationDataset dataset) {
        if (lastHeldTargetAngle == null) {
            return currentAngle;
        }

        Angle delta = MathAngle.calculateDelta(currentAngle, lastHeldTargetAngle);
        float rotationDifference = safeRotationDifference(delta.getYaw(), delta.getPitch());
        float maxRotation = Math.min(dataset.backMaxRotation(), dataset.limitRotationSpeed());
        float straightLineYaw = Math.abs(delta.getYaw() / rotationDifference) * maxRotation;
        float straightLinePitch = Math.abs(delta.getPitch() / rotationDifference) * maxRotation;

        return new Angle(
                currentAngle.getYaw() + Mth.clamp(delta.getYaw(), -straightLineYaw, straightLineYaw),
                currentAngle.getPitch() + Mth.clamp(delta.getPitch(), -straightLinePitch, straightLinePitch)
        );
    }

    @Override
    public Vec3 randomValue() {
        return new Vec3(0.04D, 0.06D, 0.04D);
    }

    private Angle copyAngle(Angle angle) {
        return new Angle(angle.getYaw(), angle.getPitch());
    }

    private float randomFloat(float min, float max) {
        return min + RANDOM.nextFloat() * (max - min);
    }

    private float safeRotationDifference(float yawDelta, float pitchDelta) {
        return Math.max((float) Math.hypot(Math.abs(yawDelta), Math.abs(pitchDelta)), 1.0E-4F);
    }

    private record NeuroProfile(float yawSpeed, float pitchSpeed, float yawLimit, float pitchLimit,
                                float yawLimitSpeed, float pitchLimitSpeed, float epsilon, float easeGain,
                                float yawWeight, float pitchWeight, float swayYaw, float swayPitch) {
    }

    private record RotationDataset(float yawSpeedMultiplier, float pitchSpeedMultiplier,
                                   float yawLimitMultiplier, float pitchLimitMultiplier,
                                   float yawLimitSpeedMultiplier, float pitchLimitSpeedMultiplier,
                                   float epsilonAdd, float easeMultiplier, float swayMultiplier,
                                   float backMaxRotation, float backLerp, long backDelayMs,
                                   float limitRotationSpeed, float shakeYawMin,
                                   float shakeYawMax, float shakePitchMin,
                                   float shakePitchMax, double shakeSpeed) {
    }
}
