package attrium.ru.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import attrium.ru.manager.TickPipelineManager;

/**
 * Step 3: Hooks Minecraft#tick at HEAD (Phase 1 Target Acquisition & Phase 2 Pre-Motion)
 * and TAIL (Phase 3 Spoofed Movement Packet Dispatch & Phase 4 Post-Motion Action Dispatch).
 */
@Mixin(Minecraft.class)
public class TickPipelineMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void attrium$onTickHeadPhase1And2(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        if (mc.player == null || mc.level == null) {
            return;
        }
        TickPipelineManager.getInstance().beginTickPhase1And2(mc);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void attrium$onTickTailPhase3And4(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        if (mc.player == null || mc.level == null) {
            return;
        }
        TickPipelineManager.getInstance().completeTickPhase3And4(mc);
    }
}
