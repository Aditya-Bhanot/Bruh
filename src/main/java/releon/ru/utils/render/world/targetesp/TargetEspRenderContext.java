package releon.ru.utils.render.world.targetesp;

import net.minecraft.world.entity.LivingEntity;

public record TargetEspRenderContext(
        LivingEntity target,
        float alpha,
        float partialTicks,
        long frameTimeMs,
        int primaryColor,
        int secondaryColor,
        float hurtProgress,
        float chainImpactProgress
) {
}
