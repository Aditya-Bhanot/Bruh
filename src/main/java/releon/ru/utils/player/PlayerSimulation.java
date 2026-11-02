package releon.ru.utils.player;

import it.unimi.dsi.fastutil.objects.Object2DoubleArrayMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import releon.ru.IMinecraft;
import releon.ru.mixin.accessor.EntityAccessor;
import releon.ru.mixin.accessor.LivingEntityAccessor;
import releon.ru.utils.move.MoveUtil;

@SuppressWarnings("deprecation")
public class PlayerSimulation implements Simulation, IMinecraft {

    public final Player player;
    public final SimulatedPlayerInput input;
    public Vec3 pos;
    public Vec3 velocity;
    public AABB boundingBox;
    public float yaw;
    public float pitch;
    public boolean sprinting;
    public float fallDistance;
    public int jumpingCooldown;
    public boolean isJumping;
    public boolean isFallFlying;
    public boolean onGround;
    public boolean horizontalCollision;
    public boolean verticalCollision;
    public boolean touchingWater;
    public boolean isSwimming;
    public boolean submergedInWater;
    private final Object2DoubleMap<TagKey<Fluid>> fluidHeight;
    private final HashSet<TagKey<Fluid>> submergedFluidTag;

    private int simulatedTicks = 0;
    private boolean clipLedged = false;

    private static final double STEP_HEIGHT = 0.5;

    public PlayerSimulation(Player player,
                            SimulatedPlayerInput input,
                            Vec3 pos,
                            Vec3 velocity,
                            AABB boundingBox,
                            float yaw,
                            float pitch,
                            boolean sprinting,
                            float fallDistance,
                            int jumpingCooldown,
                            boolean isJumping,
                            boolean isFallFlying,
                            boolean onGround,
                            boolean horizontalCollision,
                            boolean verticalCollision,
                            boolean touchingWater,
                            boolean isSwimming,
                            boolean submergedInWater,
                            Object2DoubleMap<TagKey<Fluid>> fluidHeight,
                            HashSet<TagKey<Fluid>> submergedFluidTag) {
        this.player = player;
        this.input = input;
        this.pos = pos;
        this.velocity = velocity;
        this.boundingBox = boundingBox;
        this.yaw = yaw;
        this.pitch = pitch;
        this.sprinting = sprinting;
        this.fallDistance = fallDistance;
        this.jumpingCooldown = jumpingCooldown;
        this.isJumping = isJumping;
        this.isFallFlying = isFallFlying;
        this.onGround = onGround;
        this.horizontalCollision = horizontalCollision;
        this.verticalCollision = verticalCollision;
        this.touchingWater = touchingWater;
        this.isSwimming = isSwimming;
        this.submergedInWater = submergedInWater;
        this.fluidHeight = fluidHeight;
        this.submergedFluidTag = submergedFluidTag;
    }

    public static PlayerSimulation simulateLocalPlayer(int ticks) {
        PlayerSimulation simulatedPlayer = PlayerSimulation.fromClientPlayer(PlayerSimulation.SimulatedPlayerInput.fromClientPlayer(mc.player.input.keyPresses));

        for (int i = 0; i < ticks; i++) {
            simulatedPlayer.tick();
        }

        return simulatedPlayer;
    }

    public static PlayerSimulation simulateOtherPlayer(Player player, int ticks) {
        PlayerSimulation simulatedPlayer = PlayerSimulation.fromOtherPlayer(player, PlayerSimulation.SimulatedPlayerInput.guessInput(player));

        for (int i = 0; i < ticks; i++) {
            simulatedPlayer.tick();
        }

        return simulatedPlayer;
    }

    public static PlayerSimulation fromClientPlayer(SimulatedPlayerInput input) {
        LocalPlayer player = mc.player;
        LivingEntityAccessor livingAccessor = (LivingEntityAccessor) player;
        EntityAccessor entityAccessor = (EntityAccessor) player;
        return new PlayerSimulation(
                player,
                input,
                player.position(),
                player.getDeltaMovement(),
                player.getBoundingBox(),
                player.getYRot(),
                player.getXRot(),
                player.isSprinting(),
                (float) player.fallDistance,
                livingAccessor.releon$getJumpingCooldown(),
                livingAccessor.releon$isJumping(),
                player.isFallFlying(),
                player.onGround(),
                entityAccessor.releon$isHorizontalCollision(),
                entityAccessor.releon$isVerticalCollision(),
                player.isInWater(),
                player.isSwimming(),
                player.isUnderWater(),
                new Object2DoubleArrayMap<>(entityAccessor.releon$getFluidHeight()),
                new HashSet<>(entityAccessor.releon$getSubmergedFluidTag())
        );
    }

    public static PlayerSimulation fromOtherPlayer(Player player, SimulatedPlayerInput input) {
        LivingEntityAccessor livingAccessor = (LivingEntityAccessor) player;
        EntityAccessor entityAccessor = (EntityAccessor) player;
        return new PlayerSimulation(
                player,
                input,
                player.position(),
                player.position().subtract(new Vec3(player.xo, player.yo, player.zo)),
                player.getBoundingBox(),
                player.getYRot(),
                player.getXRot(),
                player.isSprinting(),
                (float) player.fallDistance,
                livingAccessor.releon$getJumpingCooldown(),
                livingAccessor.releon$isJumping(),
                player.isFallFlying(),
                player.onGround(),
                entityAccessor.releon$isHorizontalCollision(),
                entityAccessor.releon$isVerticalCollision(),
                player.isInWater(),
                player.isSwimming(),
                player.isUnderWater(),
                new Object2DoubleArrayMap<>(entityAccessor.releon$getFluidHeight()),
                new HashSet<>(entityAccessor.releon$getSubmergedFluidTag())
        );
    }

    @Override
    public Vec3 pos() {
        return player.position();
    }

    @Override
    public void tick() {
        simulatedTicks++;
        clipLedged = false;
        if (pos.y <= -70) {
            return;
        }
        input.update();
        checkWaterState();
        updateSubmergedInWaterState();
        updateSwimming();

        if (jumpingCooldown > 0) {
            jumpingCooldown--;
        }
        isJumping = input.keyPresses.jump();
        double newX = velocity.x;
        double newY = velocity.y;
        double newZ = velocity.z;
        if (Math.abs(velocity.x) < 0.003) newX = 0.0;
        if (Math.abs(velocity.y) < 0.003) newY = 0.0;
        if (Math.abs(velocity.z) < 0.003) newZ = 0.0;
        if (onGround) {
            isFallFlying = false;
        }
        velocity = new Vec3(newX, newY, newZ);

        if (isJumping) {
            double fluidLevel = isInLava() ? getFluidHeight(FluidTags.LAVA) : getFluidHeight(FluidTags.WATER);
            boolean inWater = isTouchingWater() && fluidLevel > 0.0;
            double swimHeight = getSwimHeight();
            if (inWater && (!onGround || fluidLevel > swimHeight)) {
                swimUpward(FluidTags.WATER);
            } else if (isInLava() && (!onGround || fluidLevel > swimHeight)) {
                swimUpward(FluidTags.LAVA);
            } else if ((onGround || (inWater && fluidLevel <= swimHeight)) && jumpingCooldown == 0) {
                jump();
                if (player.equals(mc.player)) {
                    jumpingCooldown = 10;
                }
            }
        }

        float sidewaysSpeed = input.movementSideways * 0.98f;
        float forwardSpeed = input.movementForward * 0.98f;
        float upwardsSpeed = 0.0f;

        if (hasStatusEffect(MobEffects.SLOW_FALLING) || hasStatusEffect(MobEffects.LEVITATION)) {
            onLanding();
        }

        travel(new Vec3(sidewaysSpeed, upwardsSpeed, forwardSpeed));
    }

    private void travel(Vec3 movementInput) {
        if (isSwimming && !player.isPassenger()) {
            double g = getRotationVector().y;
            double h = (g < -0.2) ? 0.085 : 0.06;
            BlockPos posAbove = new BlockPos(Mth.floor(pos.x),
                    Mth.floor(pos.y + 1.0 - 0.1),
                    Mth.floor(pos.z));
            if (g <= 0.0 || input.keyPresses.jump() ||
                    !player.level().getBlockState(posAbove).getFluidState().isEmpty()) {
                velocity = velocity.add(0.0, (g - velocity.y) * h, 0.0);
            }
        }

        double beforeTravelVelocityY = velocity.y;
        double d = 0.08;
        boolean falling = velocity.y <= 0.0;
        if (velocity.y <= 0.0 && hasStatusEffect(MobEffects.SLOW_FALLING)) {
            d = 0.01;
            onLanding();
        }

        if (isTouchingWater() && player.isAffectedByFluids()) {
            double e = pos.y;
            float f = isSprinting() ? 0.9f : 0.8f;
            float g = 0.02f;
            float h = (float) getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY);
            if (!onGround) {
                h *= 0.5f;
            }
            if (h > 0.0f) {
                f += (0.54600006f - f) * h / 3.0f;
                g += (getMovementSpeed() - g) * h / 3.0f;
            }
            if (hasStatusEffect(MobEffects.DOLPHINS_GRACE)) {
                f = 0.96f;
            }
            updateVelocity(g, movementInput);
            move(velocity);
            Vec3 tempVel = velocity;
            if (horizontalCollision && isClimbing()) {
                tempVel = new Vec3(tempVel.x, 0.2, tempVel.z);
            }
            velocity = tempVel.multiply(f, 0.8, f);
            Vec3 vec3d2 = player.getFluidFallingAdjustedMovement(d, falling, velocity);
            velocity = vec3d2;
            if (horizontalCollision && doesNotCollide(vec3d2.x, vec3d2.y + 0.6 - pos.y + e, vec3d2.z)) {
                velocity = new Vec3(vec3d2.x, 0.3, vec3d2.z);
            }
        } else if (isInLava() && player.isAffectedByFluids()) {
            double e = pos.y;
            updateVelocity(0.02f, movementInput);
            move(velocity);
            if (getFluidHeight(FluidTags.LAVA) <= getSwimHeight()) {
                velocity = velocity.multiply(0.5, 0.8, 0.5);
                velocity = player.getFluidFallingAdjustedMovement(d, falling, velocity);
            } else {
                velocity = velocity.scale(0.5);
            }
            if (!player.isNoGravity()) {
                velocity = velocity.add(0.0, -d / 4.0, 0.0);
            }
            if (horizontalCollision && doesNotCollide(velocity.x, velocity.y + 0.6 - pos.y + e, velocity.z)) {
                velocity = new Vec3(velocity.x, 0.3, velocity.z);
            }
        } else if (isFallFlying) {
            double k;
            Vec3 e = velocity;
            if (e.y > -0.5) {
                fallDistance = 1.0f;
            }
            Vec3 vec3d3 = getRotationVector();
            float f = pitch * ((float) Math.PI / 180f);
            double g = Math.sqrt(vec3d3.x * vec3d3.x + vec3d3.z * vec3d3.z);
            double horizontalSpeed = velocity.horizontalDistance();
            double i = vec3d3.length();
            float j = Mth.cos(f);
            j = (float) (j * (j * Math.min(1.0, i / 0.4)));
            e = velocity.add(0.0, d * (-1.0 + j * 0.75), 0.0);
            if (e.y < 0.0 && g > 0.0) {
                k = e.y * -0.1 * j;
                e = e.add(vec3d3.x * k / g, k, vec3d3.z * k / g);
            }
            if (f < 0.0f && g > 0.0) {
                k = horizontalSpeed * (-Mth.sin(f)) * 0.04;
                e = e.add(-vec3d3.x * k / g, k * 3.2, -vec3d3.z * k / g);
            }
            if (g > 0.0) {
                e = e.add((vec3d3.x / g * horizontalSpeed - e.x) * 0.1, 0.0, (vec3d3.z / g * horizontalSpeed - e.z) * 0.1);
            }
            velocity = e.multiply(0.99, 0.98, 0.99);
            move(velocity);
        } else {
            BlockPos blockPos = getVelocityAffectingPos();
            float p = player.level().getBlockState(blockPos).getBlock().getFriction();
            float f = onGround ? p * 0.91f : 0.91f;
            Vec3 vec3d6 = applyMovementInput(movementInput, p);
            double q = vec3d6.y;
            if (hasStatusEffect(MobEffects.LEVITATION)) {
                MobEffectInstance levitation = getStatusEffect(MobEffects.LEVITATION);
                if (levitation != null) {
                    q += (0.05 * (levitation.getAmplifier() + 1) - vec3d6.y) * 0.2;
                }
            } else if (player.level().isClientSide() && !player.level().hasChunkAt(blockPos)) {
                q = (pos.y > player.level().getMinY()) ? -0.1 : 0.0;
            } else if (!player.isNoGravity()) {
                q -= d;
            }
            if (player.shouldDiscardFriction()) {
                velocity = new Vec3(vec3d6.x, q, vec3d6.z);
            } else {
                velocity = new Vec3(vec3d6.x * f, q * 0.9800000190734863, vec3d6.z * f);
            }
        }

        if (player.getAbilities().flying && !player.isPassenger()) {
            velocity = new Vec3(velocity.x, beforeTravelVelocityY * 0.6, velocity.z);
            onLanding();
        }
    }

    private Vec3 applyMovementInput(Vec3 movementInput, float slipperiness) {
        updateVelocity(getMovementSpeed(slipperiness), movementInput);
        velocity = applyClimbingSpeed(velocity);
        move(velocity);
        Vec3 result = velocity;
        BlockPos posBlock = posToBlockPos(pos);
        BlockState state = getState(posBlock);
        if ((horizontalCollision || isJumping) &&
                (isClimbing() || (state != null && state.is(Blocks.POWDER_SNOW) &&
                        PowderSnowBlock.canEntityWalkOnPowderSnow(player)))) {
            result = new Vec3(result.x, 0.2, result.z);
        }
        return result;
    }

    private void updateVelocity(float speed, Vec3 movementInput) {
        Vec3 vec = movementInputToVelocity(movementInput, speed, yaw);
        velocity = velocity.add(vec);
    }

    private static Vec3 movementInputToVelocity(Vec3 movementInput, float speed, float yaw) {
        double lengthSquared = movementInput.lengthSqr();
        if (lengthSquared < 1.0E-7) {
            return Vec3.ZERO;
        }

        Vec3 normalized = (lengthSquared > 1.0) ? movementInput.normalize() : movementInput;
        Vec3 scaled = normalized.scale(speed);

        float yawRad = yaw * ((float) Math.PI / 180.0F);
        float sin = Mth.sin(yawRad);
        float cos = Mth.cos(yawRad);

        return new Vec3(
                scaled.x * cos - scaled.z * sin,
                scaled.y,
                scaled.z * cos + scaled.x * sin
        );
    }

    private float getMovementSpeed(float slipperiness) {
        return onGround ? getMovementSpeed() * (0.21600002f / (slipperiness * slipperiness * slipperiness))
                : getAirStrafingSpeed();
    }

    private float getAirStrafingSpeed() {
        float speed = 0.02f;
        if (input.keyPresses.sprint()) {
            return speed + 0.005999999865889549f;
        }
        return speed;
    }

    private float getMovementSpeed() {
        return 0.10000000149011612f;
    }

    private void move(Vec3 movement) {
        Vec3 modifiedMovement = movement;
        modifiedMovement = adjustMovementForSneaking(modifiedMovement);
        Vec3 adjustedMovement = adjustMovementForCollisions(modifiedMovement);
        if (adjustedMovement.lengthSqr() > 1.0E-7) {
            pos = pos.add(adjustedMovement);
            boundingBox = player.getDimensions(player.getPose()).makeBoundingBox(pos);
        }
        boolean xCollision = !Mth.equal(movement.x, adjustedMovement.x);
        boolean zCollision = !Mth.equal(movement.z, adjustedMovement.z);
        horizontalCollision = xCollision || zCollision;
        verticalCollision = (movement.y != adjustedMovement.y);
        onGround = verticalCollision && movement.y < 0.0;
        if (!isTouchingWater()) {
            checkWaterState();
        }
        if (onGround) {
            onLanding();
        } else if (movement.y < 0) {
            fallDistance -= (float) movement.y;
        }
        Vec3 currentVel = velocity;
        if (horizontalCollision || verticalCollision) {
            velocity = new Vec3(xCollision ? 0.0 : currentVel.x,
                    onGround ? 0.0 : currentVel.y,
                    zCollision ? 0.0 : currentVel.z);
        }
    }

    private Vec3 adjustMovementForCollisions(Vec3 movement) {
        AABB box = new AABB(-0.3, 0.0, -0.3, 0.3, 1.8, 0.3).move(pos);
        List<VoxelShape> collisionShapes = Collections.emptyList();
        Vec3 adjusted;
        if (movement.lengthSqr() == 0.0) {
            adjusted = movement;
        } else {
            adjusted = Entity.collideBoundingBox(player, movement, box, player.level(), collisionShapes);
        }
        boolean xCollide = movement.x != adjusted.x;
        boolean yCollide = movement.y != adjusted.y;
        boolean zCollide = movement.z != adjusted.z;
        boolean stepPossible = onGround || (yCollide && movement.y < 0.0);
        if (player.maxUpStep() > 0.0f && stepPossible && (xCollide || zCollide)) {
            Vec3 stepAdjust = Entity.collideBoundingBox(player,
                    new Vec3(movement.x, player.maxUpStep(), movement.z),
                    box, player.level(), collisionShapes);
            Vec3 stepOffset = Entity.collideBoundingBox(player,
                    new Vec3(0.0, player.maxUpStep(), 0.0),
                    box.expandTowards(movement.x, 0.0, movement.z), player.level(), collisionShapes);
            Vec3 combined = Entity.collideBoundingBox(player,
                    new Vec3(movement.x, 0.0, movement.z),
                    box.move(stepOffset), player.level(), collisionShapes).add(stepOffset);
            if (stepOffset.y < player.maxUpStep() && combined.horizontalDistanceSqr() > stepAdjust.horizontalDistanceSqr()) {
                stepAdjust = combined;
            }
            if (stepAdjust.horizontalDistanceSqr() > adjusted.horizontalDistanceSqr()) {
                return stepAdjust.add(Entity.collideBoundingBox(player,
                        new Vec3(0.0, -stepAdjust.y + movement.y, 0.0),
                        box.move(stepAdjust), player.level(), collisionShapes));
            }
        }
        return adjusted;
    }

    private void onLanding() {
        fallDistance = 0.0f;
    }

    public void jump() {
        velocity = velocity.add(0.0, getJumpVelocity() - velocity.y, 0.0);
        if (isSprinting()) {
            float rad = (float) Math.toRadians(yaw);
            velocity = velocity.add(-Mth.sin(rad) * 0.2, 0.0, Mth.cos(rad) * 0.2);
        }
    }

    private Vec3 applyClimbingSpeed(Vec3 motion) {
        if (!isClimbing()) {
            return motion;
        }
        onLanding();
        double clampedX = Mth.clamp(motion.x, -0.15000000596046448, 0.15000000596046448);
        double clampedZ = Mth.clamp(motion.z, -0.15000000596046448, 0.15000000596046448);
        double clampedY = Math.max(motion.y, -0.15000000596046448);
        if (clampedY < 0.0 && !getState(posToBlockPos(pos)).is(Blocks.SCAFFOLDING) && player.isSuppressingSlidingDownLadder()) {
            clampedY = 0.0;
        }
        return new Vec3(clampedX, clampedY, clampedZ);
    }

    public boolean isClimbing() {
        BlockPos posBlock = posToBlockPos(pos);
        BlockState state = getState(posBlock);
        if (state.is(BlockTags.CLIMBABLE)) {
            return true;
        } else return state.getBlock() instanceof TrapDoorBlock && canEnterTrapdoor(posBlock, state);
    }

    private boolean canEnterTrapdoor(BlockPos pos, BlockState state) {
        if (!state.getValue(TrapDoorBlock.OPEN)) {
            return false;
        }
        BlockState below = player.level().getBlockState(pos.below());
        return below.is(Blocks.LADDER) && below.getValue(LadderBlock.FACING).equals(state.getValue(TrapDoorBlock.FACING));
    }

    private Vec3 adjustMovementForSneaking(Vec3 movement) {
        if (movement.y <= 0.0 && method_30263()) {
            double dx = movement.x;
            double dz = movement.z;
            double step = 0.05;
            while (dx != 0.0 && player.level().noCollision(player, boundingBox.move(dx, -STEP_HEIGHT, 0.0))) {
                if (dx < step && dx >= -step) {
                    dx = 0.0;
                    break;
                }
                dx += (dx > 0 ? -step : step);
            }
            while (dz != 0.0 && player.level().noCollision(player, boundingBox.move(0.0, -STEP_HEIGHT, dz))) {
                if (dz < step && dz >= -step) {
                    dz = 0.0;
                    break;
                }
                dz += (dz > 0 ? -step : step);
            }
            while (dx != 0.0 && dz != 0.0 && player.level().noCollision(player, boundingBox.move(dx, -STEP_HEIGHT, dz))) {
                dx = (dx < step && dx >= -step) ? 0.0 : (dx > 0 ? dx - step : dx + step);
                if (dz < step && dz >= -step) {
                    dz = 0.0;
                    break;
                }
                dz += (dz > 0 ? -step : step);
            }
            if (movement.x != dx || movement.z != dz) {
                clipLedged = true;
            }
            if (shouldClipAtLedge()) {
                movement = new Vec3(dx, movement.y, dz);
            }
        }
        return movement;
    }

    protected boolean shouldClipAtLedge() {
        return input.keyPresses.shift() || input.forceSafeWalk;
    }

    private boolean method_30263() {
        return onGround || (fallDistance < STEP_HEIGHT &&
                !player.level().noCollision(player, boundingBox.move(0.0, fallDistance - STEP_HEIGHT, 0.0)));
    }

    private boolean isSprinting() {
        return sprinting;
    }

    private float getJumpVelocity() {
        return 0.42f * getJumpVelocityMultiplier() + getJumpBoostVelocityModifier();
    }

    private float getJumpBoostVelocityModifier() {
        if (hasStatusEffect(MobEffects.JUMP_BOOST)) {
            MobEffectInstance boost = getStatusEffect(MobEffects.JUMP_BOOST);
            return 0.1f * (boost.getAmplifier() + 1);
        }
        return 0f;
    }

    private float getJumpVelocityMultiplier() {
        float multiplier1 = 0f;
        Block block = getState(posToBlockPos(pos)).getBlock();
        if (block != null) {
            multiplier1 = block.getJumpFactor();
        }
        float multiplier2 = 0f;
        Block block2 = getState(getVelocityAffectingPos()).getBlock();
        if (block2 != null) {
            multiplier2 = block2.getJumpFactor();
        }
        return (multiplier1 == 1.0f) ? multiplier2 : multiplier1;
    }

    private boolean doesNotCollide(double offsetX, double offsetY, double offsetZ) {
        return doesNotCollide(boundingBox.move(offsetX, offsetY, offsetZ));
    }

    private boolean doesNotCollide(AABB box) {
        return player.level().noCollision(player, box) && !player.level().containsAnyLiquid(box);
    }

    private void swimUpward(TagKey<Fluid> fluidTag) {
        velocity = velocity.add(0.0, 0.03999999910593033, 0.0);
    }

    private BlockPos getVelocityAffectingPos() {
        return BlockPos.containing(pos.x, boundingBox.minY - 0.5000001, pos.z);
    }

    private double getSwimHeight() {
        return (player.getEyeHeight() < 0.4) ? 0.0 : 0.4;
    }

    private boolean isTouchingWater() {
        return touchingWater;
    }

    public boolean isInLava() {
        return fluidHeight.getDouble(FluidTags.LAVA) > 0.0;
    }

    private void checkWaterState() {
        Entity vehicle = player.getVehicle();
        if (vehicle instanceof AbstractBoat boat) {
            if (!boat.isUnderWater()) {
                touchingWater = false;
                return;
            }
        }
        if (updateMovementInFluid(FluidTags.WATER, 0.014)) {
            onLanding();
            touchingWater = true;
        } else {
            touchingWater = false;
        }
    }

    private void updateSwimming() {
        if (isSwimming) {
            isSwimming = isSprinting() && isTouchingWater() && !player.isPassenger();
        } else {
            isSwimming = isSprinting() && isSubmergedInWater() &&
                    !player.isPassenger() &&
                    player.level().getFluidState(posToBlockPos(pos)).is(FluidTags.WATER);
        }
    }

    private void updateSubmergedInWaterState() {
        submergedInWater = submergedFluidTag.contains(FluidTags.WATER);
        submergedFluidTag.clear();
        double eyeLevel = getEyeY() - 0.1111111119389534;
        Entity vehicle = player.getVehicle();
        if (vehicle instanceof AbstractBoat boat) {
            if (!boat.isUnderWater() &&
                    boat.getBoundingBox().maxY >= eyeLevel &&
                    boat.getBoundingBox().minY <= eyeLevel) {
                return;
            }
        }
        BlockPos posEye = BlockPos.containing(pos.x, eyeLevel, pos.z);
        FluidState fluidState = player.level().getFluidState(posEye);
        double height = posEye.getY() + fluidState.getHeight(player.level(), posEye);
        if (height > eyeLevel) {
            submergedFluidTag.addAll(fluidState.getTags().toList());
        }
    }

    private double getEyeY() {
        return pos.y + player.getEyeHeight();
    }

    public boolean isSubmergedInWater() {
        return submergedInWater && isTouchingWater();
    }

    private double getFluidHeight(TagKey<Fluid> tag) {
        return fluidHeight.getDouble(tag);
    }

    private boolean updateMovementInFluid(TagKey<Fluid> tag, double speed) {
        if (isRegionUnloaded()) {
            return false;
        }
        AABB box = boundingBox.deflate(0.001);
        int i = Mth.floor(box.minX);
        int j = Mth.ceil(box.maxX);
        int k = Mth.floor(box.minY);
        int l = Mth.ceil(box.maxY);
        int m = Mth.floor(box.minZ);
        int n = Mth.ceil(box.maxZ);
        double d = 0.0;
        boolean pushedByFluids = true;
        boolean foundFluid = false;
        Vec3 fluidVelocity = Vec3.ZERO;
        int count = 0;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int p = i; p < j; p++) {
            for (int q = k; q < l; q++) {
                for (int r = m; r < n; r++) {
                    mutable.set(p, q, r);
                    FluidState fluidState = player.level().getFluidState(mutable);
                    if (fluidState.is(tag)) {
                        double e = q + fluidState.getHeight(player.level(), mutable);
                        if (e >= box.minY) {
                            foundFluid = true;
                            d = Math.max(e - box.minY, d);
                            if (pushedByFluids) {
                                Vec3 vel = fluidState.getFlow(player.level(), mutable);
                                if (d < 0.4) {
                                    vel = vel.scale(d);
                                }
                                fluidVelocity = fluidVelocity.add(vel);
                                count++;
                            }
                        }
                    }
                }
            }
        }
        if (fluidVelocity.length() > 0.0) {
            if (count > 0) {
                fluidVelocity = fluidVelocity.scale(1.0 / count);
            }
            fluidVelocity = fluidVelocity.scale(speed);
            if (Math.abs(velocity.x) < 0.003 && Math.abs(velocity.z) < 0.003 &&
                    fluidVelocity.length() < 0.0045) {
                fluidVelocity = fluidVelocity.normalize().scale(0.0045);
            }
            velocity = velocity.add(fluidVelocity);
        }
        fluidHeight.put(tag, d);
        return foundFluid;
    }

    private boolean isRegionUnloaded() {
        AABB box = boundingBox.inflate(1.0);
        int i = Mth.floor(box.minX);
        int j = Mth.ceil(box.maxX);
        int k = Mth.floor(box.minZ);
        int l = Mth.ceil(box.maxZ);
        return !player.level().hasChunksAt(i, k, j, l);
    }

    private Vec3 getRotationVector() {
        return getRotationVector(pitch, yaw);
    }

    private Vec3 getRotationVector(float pitch, float yaw) {
        float f = (float) (pitch * Math.PI / 180.0);
        float g = (float) (-yaw * Math.PI / 180.0);
        float h = Mth.cos(g);
        float i = Mth.sin(g);
        float j = Mth.cos(f);
        float k = Mth.sin(f);
        return new Vec3(i * j, -k, h * j);
    }

    public boolean hasStatusEffect(Holder<MobEffect> effect) {
        MobEffectInstance instance = player.getEffect(effect);
        return instance != null && instance.getDuration() >= simulatedTicks;
    }

    private MobEffectInstance getStatusEffect(Holder<MobEffect> effect) {
        MobEffectInstance instance = player.getEffect(effect);
        if (instance == null || instance.getDuration() < simulatedTicks) {
            return null;
        }
        return instance;
    }

    public double getAttributeValue(Holder<Attribute> attribute) {
        return player.getAttributes().getValue(attribute);
    }

    @Override
    public PlayerSimulation clone() {
        return new PlayerSimulation(
                player,
                input,
                pos,
                velocity,
                boundingBox,
                yaw,
                pitch,
                sprinting,
                fallDistance,
                jumpingCooldown,
                isJumping,
                isFallFlying,
                onGround,
                horizontalCollision,
                verticalCollision,
                touchingWater,
                isSwimming,
                submergedInWater,
                new Object2DoubleArrayMap<>(fluidHeight),
                new HashSet<>(submergedFluidTag)
        );
    }

    public BlockPos posToBlockPos(Vec3 pos) {
        return new BlockPos(Mth.floor(pos.x), Mth.floor(pos.y), Mth.floor(pos.z));
    }

    public BlockState getState(BlockPos pos) {
        return player.level().getBlockState(pos);
    }

    public static class SimulatedPlayerInput extends ClientInput {
        public boolean forceSafeWalk = false;
        public float movementForward;
        public float movementSideways;
        public Input keyPresses;
        public static final double MAX_WALKING_SPEED = 0.121;

        public SimulatedPlayerInput(Input input) {
            this.keyPresses = input;
        }

        public void update() {
            if (keyPresses.forward() != keyPresses.backward()) {
                movementForward = keyPresses.forward() ? 1.0f : -1.0f;
            } else {
                movementForward = 0.0f;
            }
            if (keyPresses.left() == keyPresses.right()) {
                movementSideways = 0.0f;
            } else {
                movementSideways = keyPresses.left() ? 1.0f : -1.0f;
            }
            if (keyPresses.shift()) {
                movementSideways *= 0.3f;
                movementForward *= 0.3f;
            }
        }

        @Override
        public String toString() {
            return "SimulatedPlayerInput(forwards={" + keyPresses.forward() + "}, backwards={" + keyPresses.backward() +
                    "}, left={" + keyPresses.left() + "}, right={" + keyPresses.right() + "}, jumping={" + keyPresses.jump() +
                    "}, sprinting=" + keyPresses.sprint() + ", slowDown=" + keyPresses.shift() + ")";
        }

        public static SimulatedPlayerInput fromClientPlayer(Input input) {
            return new SimulatedPlayerInput(input);
        }

        public static SimulatedPlayerInput guessInput(Player entity) {
            Vec3 velocity = entity.position().subtract(new Vec3(entity.xo, entity.yo, entity.zo));
            double horizontalVelocity = velocity.horizontalDistanceSqr();
            Input input = new Input(false, false, false, false, !entity.onGround(), entity.isShiftKeyDown(), horizontalVelocity >= MAX_WALKING_SPEED * MAX_WALKING_SPEED);
            if (horizontalVelocity > 0.05 * 0.05) {
                double velocityAngle = MoveUtil.getDegreesRelativeToView(velocity, entity.getYRot());
                double wrappedAngle = Mth.wrapDegrees(velocityAngle);
                input = MoveUtil.getDirectionalInputForDegrees(input, wrappedAngle);
            }
            return new SimulatedPlayerInput(input);
        }
    }
}

