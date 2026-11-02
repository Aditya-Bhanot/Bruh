package releon.ru.api.events.impl;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import releon.ru.api.events.CancellableEvent;

public final class HandOffsetEvent extends CancellableEvent {
    private final PoseStack matrices;
    private final ItemStack stack;
    private final InteractionHand hand;
    private float scale = 1.0F;

    public HandOffsetEvent(PoseStack matrices, ItemStack stack, InteractionHand hand) {
        this.matrices = matrices;
        this.stack = stack;
        this.hand = hand;
    }

    public PoseStack getMatrices() {
        return matrices;
    }

    public ItemStack getStack() {
        return stack;
    }

    public InteractionHand getHand() {
        return hand;
    }

    public float getScale() {
        return scale;
    }

    public void setScale(float scale) {
        this.scale = scale;
    }
}
