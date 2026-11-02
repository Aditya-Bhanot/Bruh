package releon.ru.api.drag.impl;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.item.RenderItem;
import releon.ru.utils.render.item.RenderItemOptions;
import releon.ru.utils.render.ui.Render2D;

public final class Armor extends HudPanel {

    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
    };
    private static final ItemStack[] PREVIEW = {
            Items.NETHERITE_HELMET.getDefaultInstance(),
            Items.NETHERITE_CHESTPLATE.getDefaultInstance(),
            Items.NETHERITE_LEGGINGS.getDefaultInstance(),
            Items.NETHERITE_BOOTS.getDefaultInstance()
    };
    private static final float ANIM_S = 0.35F;

    private final SmoothAnimation[] slotAnim = {
            new SmoothAnimation(), new SmoothAnimation(),
            new SmoothAnimation(), new SmoothAnimation()
    };
    private final boolean[] wasEmpty = { true, true, true, true };

    private final SmoothAnimation panelAnim = new SmoothAnimation();
    private boolean wasPreview = false;

    public Armor() {
        super("armor", "Armor", 210.0F, 10.0F, 75.0F, 20.0F);
    }

    @Override
    public void render() {
        ArmorState state = logics();
        if (state == null) {
            return;
        }
        renderArmor(state);
    }

    private ArmorState logics() {
        for (int i = 0; i < 4; i++) {
            slotAnim[i].update();
            boolean empty = mc.player == null || mc.player.getItemBySlot(SLOTS[i]).isEmpty();
            if (empty != wasEmpty[i]) {
                slotAnim[i].run(empty ? 0.0 : 1.0, ANIM_S, Easings.EXPO_OUT);
                wasEmpty[i] = empty;
            }
        }

        boolean anySlotVisible = false;
        for (SmoothAnimation a : slotAnim) {
            if (a.get() > 0.01F) {
                anySlotVisible = true;
                break;
            }
        }

        boolean preview = !anySlotVisible && editPreview();

        panelAnim.update();
        if (preview && !wasPreview) {
            panelAnim.run(1.0, ANIM_S, Easings.EXPO_OUT);
        } else if (!preview && wasPreview) {
            panelAnim.run(0.0, ANIM_S, Easings.EXPO_IN);
        }
        wasPreview = preview;

        boolean targetVisible = anySlotVisible || preview || panelAnim.get() > 0.01F;
        float alpha = contentAlpha(targetVisible);
        if (alpha <= 0.0F) {
            return null;
        }

        int visibleCount = preview ? PREVIEW.length : 0;
        if (!preview) {
            for (SmoothAnimation a : slotAnim) {
                if (a.get() > 0.01F) {
                    visibleCount++;
                }
            }
        }
        size(Math.max(24.0F, visibleCount * 18.0F + 7.0F), 20.0F);

        return new ArmorState(preview, alpha, drag.x(), drag.y());
    }

    private void renderArmor(ArmorState state) {
        if (state.preview) {
            float pa = clamp(panelAnim.get() * state.alpha, 0.0F, 1.0F);
            float itemX = state.x + 4.0F;

            for (ItemStack stack : PREVIEW) {
                RenderItem.item(stack, itemX + 1.7F, state.y + 3.0F, 12.0F, RenderItemOptions.noDecorations(pa));
                itemX += 18.0F;
            }
            return;
        }

        if (mc.player == null) {
            return;
        }

        float itemX = state.x + 4.0F;
        for (int i = 0; i < 4; i++) {
            float a = clamp(slotAnim[i].get() * state.alpha, 0.0F, 1.0F);
            ItemStack stack = mc.player.getItemBySlot(SLOTS[i]);

            if (a < 0.01F) {
                continue;
            }

            RenderItem.item(stack, itemX + 1.7F, state.y + 3.0F, 12.0F, RenderItemOptions.noDecorations(a));

            if (stack.isDamageableItem() && stack.getMaxDamage() > 0) {
                int percent = Math.round((1.0F - stack.getDamageValue() / (float) stack.getMaxDamage()) * 100.0F);
                String text = Integer.toString(Math.max(0, percent));
                float tw = Render2D.textWidth(TEXT_FONT, text, 4.5F);
                int color = ColorUtil.rgba(255, 255, 255, Math.round(255.0F * a));

                Render2D.text(TEXT_FONT, text, itemX + 6F - tw * 0.5F, state.y + 13F, 6F, color);
            }

            itemX += 18.0F;
        }
    }

    private record ArmorState(boolean preview, float alpha, float x, float y) {
    }
}
