package releon.ru.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import releon.ru.api.events.impl.AttackEvent;
import releon.ru.api.events.impl.BlockBreakingEvent;
import releon.ru.api.events.impl.ClickSlotEvent;
import releon.ru.api.events.impl.InteractEntityEvent;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.module.ModuleManager;
import releon.ru.manager.Manager;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    @Inject(method = "handleInventoryMouseClick", at = @At("HEAD"), cancellable = true)
    private void releon$clickSlotHook(int syncId, int slotId, int button, ClickType actionType, Player player, CallbackInfo info) {
        ClickSlotEvent event = Manager.postEvent(new ClickSlotEvent(syncId, slotId, button, actionType));
        if (event.isCancelled()) {
            info.cancel();
        }
    }

    @Inject(method = "startDestroyBlock", at = @At("HEAD"))
    private void releon$startDestroyBlock(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        Manager.postEvent(new BlockBreakingEvent(pos, direction));
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"))
    private void releon$continueDestroyBlock(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        Manager.postEvent(new BlockBreakingEvent(pos, direction));
    }

    @Inject(method = "attack", at = @At("HEAD"), cancellable = true)
    private void releon$onAttack(Player player, Entity target, CallbackInfo ci) {
        AttackEvent attackEvent = Manager.postEvent(new AttackEvent(target));
        InteractEntityEvent interactEvent = Manager.postEvent(new InteractEntityEvent(target));
        if (attackEvent.isCancelled() || interactEvent.isCancelled()) {
            ci.cancel();
        }
    }

    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
    private void releon$cancelShieldUse(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        AuraModule aura = AuraModule.getInstance();
        if (aura != null && aura.shouldCancelInteractItem(hand)) {
            cir.setReturnValue(InteractionResult.PASS);
        }
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void releon$cancelUseItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hitResult, CallbackInfoReturnable<InteractionResult> cir) {
        AuraModule aura = AuraModule.getInstance();
        ModuleManager modules = Manager.getModules();
        boolean noInteract = modules != null && modules.isNoInteractEnabled();
        if (noInteract || aura != null && aura.shouldCancelInteractBlock()) {
            cir.setReturnValue(InteractionResult.PASS);
        }
    }

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void releon$cancelInteract(Player player, Entity target, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        AuraModule aura = AuraModule.getInstance();
        InteractEntityEvent event = Manager.postEvent(new InteractEntityEvent(target));
        ModuleManager modules = Manager.getModules();
        boolean noInteract = modules != null && modules.isNoInteractEnabled();
        if (event.isCancelled() || noInteract || aura != null && aura.shouldCancelEntityInteraction()) {
            cir.setReturnValue(InteractionResult.PASS);
        }
    }
}
