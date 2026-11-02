package releon.ru.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import releon.ru.api.events.impl.InputEvent;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.api.module.impl.combat.aura.AngleConnection;
import releon.ru.api.module.impl.combat.aura.AngleConstructor;
import releon.ru.api.module.impl.movement.InventoryMove;
import releon.ru.manager.Manager;
import releon.ru.utils.inventory.InventoryFlowManager;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
    @ModifyExpressionValue(method = "tick", at = @At(value = "NEW", target = "(ZZZZZZZ)Lnet/minecraft/world/entity/player/Input;"))
    private Input releon$tickHook(Input original) {
        InputEvent event = Manager.postEvent(new InputEvent(original));
        InventoryFlowManager.input(event);
        return releon$transformInput(event.getInput());
    }

    @Unique
    private Input releon$transformInput(Input input) {
        Minecraft client = Minecraft.getInstance();
        AngleConnection rotationController = AngleConnection.INSTANCE;
        Angle angle = rotationController.getCurrentAngle();
        AngleConstructor plan = rotationController.getCurrentRotationPlan();

        if (client.player == null
                || releon$shouldPreserveInventoryMovement()
                || angle == null
                || plan == null
                || !plan.isMoveCorrection()
                || !plan.isFreeCorrection()) {
            return input;
        }

        float deltaYaw = client.player.getYRot() - angle.getYaw();
        float z = releon$getMovementMultiplier(input.forward(), input.backward());
        float x = releon$getMovementMultiplier(input.left(), input.right());
        float radians = deltaYaw * Mth.DEG_TO_RAD;
        float cos = Mth.cos(radians);
        float sin = Mth.sin(radians);
        float newX = x * cos - z * sin;
        float newZ = z * cos + x * sin;
        int movementSideways = Math.round(newX);
        int movementForward = Math.round(newZ);

        return new Input(
                movementForward > 0,
                movementForward < 0,
                movementSideways > 0,
                movementSideways < 0,
                input.jump(),
                input.shift(),
                input.sprint());
    }

    @Unique
    private boolean releon$shouldPreserveInventoryMovement() {
        InventoryMove inventoryMove = InventoryMove.getInstance();
        return inventoryMove != null && inventoryMove.shouldPreserveInventoryMovementInput();
    }

    @Unique
    private float releon$getMovementMultiplier(boolean positive, boolean negative) {
        if (positive == negative) {
            return 0.0F;
        }
        return positive ? 1.0F : -1.0F;
    }
}
