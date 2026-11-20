package attrium.ru.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import attrium.ru.api.events.impl.FireworkEvent;
import attrium.ru.api.module.ModuleManager;
import attrium.ru.api.module.impl.combat.aura.AngleConnection;
import attrium.ru.api.module.impl.movement.SuperFireWork;
import attrium.ru.manager.Manager;

@Mixin(FireworkRocketEntity.class)
public abstract class FireworkRocketEntityMixin extends Entity {
    @Shadow
    @Nullable
    private LivingEntity attachedToEntity;

    private FireworkRocketEntityMixin(Level level) {
        super(EntityType.FIREWORK_ROCKET, level);
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getLookAngle()Lnet/minecraft/world/phys/Vec3;"), require = 0)
    private Vec3 attrium$getRotationVectorHook(LivingEntity instance, Operation<Vec3> original) {
        if (attrium$shouldModifyFireworkBoost() && attachedToEntity == Minecraft.getInstance().player && attachedToEntity.isFallFlying()) {
            return AngleConnection.INSTANCE.getMoveRotation().toVector();
        }
        return original.call(instance);
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"), require = 0)
    private void attrium$setVelocityHook(LivingEntity instance, Vec3 velocity, Operation<Void> original) {
        if (attrium$shouldModifyFireworkBoost() && attachedToEntity == Minecraft.getInstance().player && attachedToEntity.isFallFlying()) {
            FireworkEvent event = Manager.postEvent(new FireworkEvent(velocity));
            original.call(instance, event.getVector());
            return;
        }
        original.call(instance, velocity);
    }

    @Unique
    private boolean attrium$shouldModifyFireworkBoost() {
        ModuleManager modules = Manager.getModules();
        return modules != null && modules.isEnabled(SuperFireWork.class);
    }
}
