package releon.ru.api.events.impl;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.InteractionHand;
import releon.ru.api.events.CancellableEvent;

public final class HandAnimationEvent extends CancellableEvent {
    private final PoseStack matrices;
    private final InteractionHand hand;
    private final float swingProgress;

    public HandAnimationEvent(PoseStack matrices, InteractionHand hand, float swingProgress) {
        this.matrices = matrices;
        this.hand = hand;
        this.swingProgress = swingProgress;
    }

    public PoseStack getMatrices() {
        return matrices;
    }

    public InteractionHand getHand() {
        return hand;
    }

    public float getSwingProgress() {
        return swingProgress;
    }
}
