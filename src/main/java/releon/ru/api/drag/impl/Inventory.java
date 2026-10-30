package releon.ru.api.drag.impl;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.item.RenderItem;
import releon.ru.utils.render.item.RenderItemOptions;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

public final class Inventory extends HudPanel {
    private static final ItemStack[] PREVIEW = {
            Items.ENDER_PEARL.getDefaultInstance(),
            Items.GOLDEN_APPLE.getDefaultInstance(),
            Items.TOTEM_OF_UNDYING.getDefaultInstance(),
            Items.SUGAR.getDefaultInstance()
    };
    private final ItemStack[] inventoryStacks = new ItemStack[27];

    public Inventory() {
        super("inventory", "Inventory", 10.0F, 260.0F, 126.0F, 62.0F);
        for (int i = 0; i < inventoryStacks.length; i++) {
            inventoryStacks[i] = ItemStack.EMPTY;
        }
    }

    @Override
    public void render() {
        InventoryState state = logics();
        if (state == null) {
            return;
        }
        renderInventory(state);
    }

    private InventoryState logics() {
        boolean hasItems = inventoryItems();
        boolean preview = !hasItems && editPreview();
        boolean visible = hasItems || preview;
        float alpha = contentAlpha(visible);
        if (alpha <= 0.0F) {
            return null;
        }

        size(126.0F, 45.0F);

        return new InventoryState(preview ? PREVIEW : inventoryStacks, preview ? PREVIEW.length : inventoryStacks.length, alpha, drag.x(), drag.y(), drag.width(), drag.height());
    }

    private void renderInventory(InventoryState state) {
        float iconSize = 18.0F;
        float iconWidth = Render2D.textWidth(FontType.MAINMENUSCREEN, "B", iconSize);
        int index = 0;

        HudRenderCompat.background(state.x, state.y, state.width, state.height, 4.0F, 15.0F, 1.2F, ColorUtil.rgba(0, 0, 0, Math.round(255.0F * state.alpha)));
        Render2D.text(FontType.MAINMENUSCREEN, "B", state.x + (state.width - iconWidth) * 0.5F, state.y + (state.height - iconSize) * 0.5F, iconSize, ColorUtil.rgba(255, 255, 255, Math.round(36.0F * state.alpha)));

        for (int i = 0; i < state.count; i++) {
            ItemStack stack = state.items[i];
            int column = index % 9;
            int row = index / 9;
            RenderItem.item(stack, state.x + 7.0F + column * 13.0F, state.y + 6.0F + row * 12.5F, 8.0F, RenderItemOptions.countNoDurability(state.alpha));
            index++;
        }
    }

    private boolean inventoryItems() {
        if (mc.player == null) {
            clearInventoryCache();
            return false;
        }
        boolean hasItems = false;
        for (int slot = 9; slot < 36; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            ItemStack safeStack = stack == null ? ItemStack.EMPTY : stack;
            inventoryStacks[slot - 9] = safeStack;
            if (!safeStack.isEmpty()) {
                hasItems = true;
            }
        }
        return hasItems;
    }

    private void clearInventoryCache() {
        for (int i = 0; i < inventoryStacks.length; i++) {
            inventoryStacks[i] = ItemStack.EMPTY;
        }
    }

    private record InventoryState(ItemStack[] items, int count, float alpha, float x, float y, float width, float height) {
    }
}
