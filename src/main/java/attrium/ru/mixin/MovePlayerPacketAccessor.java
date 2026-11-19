package attrium.ru.mixin;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Step 5: Zero-Allocation Mixin Accessor for `ServerboundMovePlayerPacket`
 * (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * Allows `RotationManager` and `PacketQueueManager` to mutate `yRot`, `xRot`, `onGround`,
 * and `hasRot` in-place at native bytecode speed without cloning packet objects or boxing primitives.
 */
@Mixin(ServerboundMovePlayerPacket.class)
public interface MovePlayerPacketAccessor {

    @Accessor("yRot")
    float attrium$getYRot();

    @Mutable
    @Accessor("yRot")
    void attrium$setYRot(float yRot);

    @Accessor("xRot")
    float attrium$getXRot();

    @Mutable
    @Accessor("xRot")
    void attrium$setXRot(float xRot);

    @Accessor("onGround")
    boolean attrium$isOnGround();

    @Mutable
    @Accessor("onGround")
    void attrium$setOnGround(boolean onGround);

    @Accessor("hasRot")
    boolean attrium$hasRot();

    @Mutable
    @Accessor("hasRot")
    void attrium$setHasRot(boolean hasRot);
}
