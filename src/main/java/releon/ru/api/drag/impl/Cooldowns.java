package releon.ru.api.drag.impl;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.item.RenderItem;
import releon.ru.utils.render.item.RenderItemOptions;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Cooldowns extends HudPanel {
    private static final float ROW_STEP = 11.0F;
    private static final float PANEL_ANIM = 0.24F;
    private static final float ROW_ANIM = 0.22F;
    private static final ItemStack[] PREVIEW_STACKS = {
            Items.SUGAR.getDefaultInstance(),
            Items.MACE.getDefaultInstance(),
            Items.GOLDEN_APPLE.getDefaultInstance()
    };
    private static final Comparator<RowState> ROW_STATE_COMPARATOR = Comparator.comparingDouble(RowState::offset);

    private final Map<CooldownKey, CooldownInfo> infoByItem = new LinkedHashMap<>();
    private final List<RowEntry> rowEntries = new ArrayList<>();
    private final List<CooldownInfo> activeCooldowns = new ArrayList<>();
    private final List<RowState> rowStates = new ArrayList<>();
    private final SmoothAnimation panelAnimation = new SmoothAnimation();
    private final SmoothAnimation iconAlphaAnimation = new SmoothAnimation();
    private boolean iconAlphaForward = true;
    public Cooldowns() {
        super("cooldowns", "Cooldowns", 10.0F, 40.0F, 96.0F, 23.0F);
    }

    @Override
    public void render() {
        CooldownsState state = logics();
        if (state == null) {
            return;
        }
        renderCooldowns(state);
    }

    private CooldownsState logics() {
        List<CooldownInfo> active = collectActive();
        boolean preview = active.isEmpty() && editPreview();
        boolean targetVisible = !active.isEmpty() || preview;

        drag.hitExpansion(0.0F, 17.0F, 0.0F, 0.0F);
        panelAnimation.update();
        panelAnimation.run(targetVisible ? 1.0 : 0.0, PANEL_ANIM, targetVisible ? Easings.EXPO_OUT : Easings.EXPO_IN, true);

        iconAlphaAnimation.update();
        if (!iconAlphaAnimation.isAlive()) {
            iconAlphaAnimation.run(iconAlphaForward ? 1.0 : 0.0, 1.55, Easings.EXPO_IN_OUT);
            iconAlphaForward = !iconAlphaForward;
        }

        for (RowEntry entry : rowEntries) {
            entry.active = false;
            entry.alpha.update();
            entry.y.update();
        }

        int targetRows = 0;
        if (preview) {
            RowEntry entry = row("__preview", PREVIEW_STACKS[0], "Sugar", "**:**", 6.0F, 0.0F);
            entry.active = true;
            entry.alpha.run(1.0, ROW_ANIM, Easings.EXPO_OUT, true);
            entry.y.run(0.0F, ROW_ANIM, Easings.EXPO_OUT, true);
            targetRows = 1;
        } else {
            for (CooldownInfo info : active) {
                float targetY = targetRows * ROW_STEP;
                RowEntry entry = row(info.key.rowKey(), info.stack, info.displayName, info.remainingText(), 6.0F, targetY);
                entry.active = true;
                entry.alpha.run(1.0, ROW_ANIM, Easings.EXPO_OUT, true);
                entry.y.run(targetY, ROW_ANIM, Easings.EXPO_OUT, true);
                targetRows++;
            }
        }

        for (RowEntry entry : rowEntries) {
            if (!entry.active) {
                entry.alpha.run(0.0, ROW_ANIM, Easings.EXPO_IN, true);
            }
        }
        rowEntries.removeIf(entry -> !entry.active && entry.alpha.get() <= 0.01F && !entry.alpha.isAlive());

        float panelAlpha = panelAnimation.get();
        boolean visible = targetVisible || panelAlpha > 0.01F || !rowEntries.isEmpty();
        contentVisible(visible);
        if (!visible) {
            return null;
        }

        float width = 96.0F;
        for (RowEntry entry : rowEntries) {
            if (entry.alpha.get() > 0.01F || entry.active) {
                width = Math.max(width, Render2D.textWidth(TEXT_FONT, entry.name, 6.0F) + 65.0F);
            }
        }
        size(width, Math.max(1, targetRows) * 10.0F + 10.0F);

        rowStates.clear();
        for (RowEntry entry : rowEntries) {
            float alpha = entry.alpha.get();
            if (alpha > 0.01F || entry.active) {
                rowStates.add(new RowState(entry.stack, entry.name, entry.time, entry.timeSize, entry.y.get(), alpha));
            }
        }
        if (rowStates.size() > 1) {
            rowStates.sort(ROW_STATE_COMPARATOR);
        }

        return new CooldownsState(rowStates, iconAlphaAnimation.get(), panelAlpha, drag.x(), drag.y(), drag.width(), drag.height());
    }

    private void renderCooldowns(CooldownsState state) {
        float headerY = state.y - 17.0F;
        float headerHeight = 17.0F;
        float rowY = state.y + 5.0F;
        String headerText = "Cooldowns";
        float headerTextSize = 6.0F;
        float headerTextX = state.x + 10.0F;
        float headerTextY = headerY + (headerHeight - headerTextSize) * 0.5F - 0.5F;
        float headerTextWidth = Render2D.textWidth(TEXT_FONT, headerText, headerTextSize);
        float headerTextDotX = headerTextX + headerTextWidth + 3.0F;
        float iconSize = 10.0F;
        float iconWidth = Render2D.textWidth(FontType.MAINMENUSCREEN, "l", iconSize);
        float iconX = state.x + state.width - iconWidth - 8.0F;
        float iconY = headerY + (headerHeight - iconSize) * 0.5F + 1.0F;
        float glowSize = 22.0F;
        float glowX = iconX + iconWidth * 0.5F - glowSize * 0.5F;
        float glowY = headerY + (headerHeight - glowSize) * 0.5F;
        float sideRectY = headerY + (headerHeight - 9.0F) * 0.5F + 0.5F;
        float sideDotY = headerY + (headerHeight - 4.0F) * 0.5F + 0.5F;
        int glowAlpha = Math.round(128.0F * state.alpha);
        int glow = ColorUtil.rgba(247, 133, 255, glowAlpha);
        int purple = ColorUtil.rgba(247, 133, 255, Math.round(255.0F * state.alpha));
        int headerTextColor = ColorUtil.rgba(255, 255, 255, Math.round(255.0F * state.alpha));
        int rowRectColor = ColorUtil.rgba(247, 133, 255, Math.round(255.0F * state.alpha));

        HudRenderCompat.background(Render2D.blurBuilder()
                .rectangle(state.x, headerY, state.width, headerHeight)
                .radius(4.0F, 4.0F, 2.0F, 2.0F)
                .blurRadius(15.0F)
                .smoothness(1.2F)
                .color(ColorUtil.rgba(0, 0, 0, Math.round(255.0F * state.alpha)))
                .build());

        HudRenderCompat.background(Render2D.blurBuilder()
                .rectangle(state.x, state.y, state.width, state.height)
                .radius(2.0F, 2.0F, 4.0F, 4.0F)
                .blurRadius(15.0F)
                .smoothness(1.2F)
                .color(ColorUtil.rgba(0, 0, 0, Math.round(255.0F * state.alpha)))
                .build());

        if (glowAlpha > 0) {
            HudRenderCompat.glow("releon:textures/particles/ghost-glow.png", glowX, glowY - 7.5F, glowSize , glowSize + 15, 0.0F, glow);
        }
        Render2D.rect(state.x, sideRectY, 2.5F, 9.0F, 0.0F, 2.0F, 2.0F, 0.0F, rowRectColor);
        Render2D.rect(state.x + 3.5F, sideDotY, 4.0F, 4.0F, 4.0F, rowRectColor);
        Render2D.rect(headerTextDotX, sideDotY, 4.0F, 4.0F, 4.0F, rowRectColor);
        Render2D.text(TEXT_FONT, headerText, headerTextX, headerTextY, headerTextSize, headerTextColor);
        Render2D.text(FontType.MAINMENUSCREEN, "l", iconX, iconY, iconSize, purple);

        for (RowState row : state.rows) {
            float rowAlpha = state.alpha * row.alpha;
            float currentY = rowY + row.offset;
            float itemX = state.x + 5.0F;
            float itemY = currentY + 1F;
            float itemSize = 8.0F;
            float dotX = itemX + itemSize + 1.5F;
            float dotY = currentY + 3.5F;
            float nameX = dotX + 4.5F;
            float timeX = state.x + state.width - 27.0F;
            String visibleName = trimToWidth(row.name, TEXT_FONT, 6.0F, timeX - nameX - 5.0F);

            RenderItem.item(row.stack, itemX, itemY, itemSize, RenderItemOptions.noDecorations(rowAlpha));
            Render2D.rect(dotX, dotY, 3.0F, 3.0F, 3.0F, ColorUtil.rgba(128, 128, 128, Math.round(245.0F * rowAlpha)));
            Render2D.text(TEXT_FONT, visibleName, nameX, currentY + 1.5F, 6.0F, ColorUtil.multAlpha(TEXT_COLOR, rowAlpha));
            renderTimer(row.time, timeX, currentY + 1F, row.timeSize, rowAlpha);
        }
    }

    private RowEntry row(String key, ItemStack stack, String name, String time, float timeSize, float targetY) {
        for (RowEntry entry : rowEntries) {
            if (entry.key.equals(key)) {
                entry.stack = stack == null ? ItemStack.EMPTY : stack.copy();
                entry.name = name;
                entry.time = time;
                entry.timeSize = timeSize;
                return entry;
            }
        }
        RowEntry entry = new RowEntry(key, stack, name, time, timeSize);
        entry.alpha.set(0.0);
        entry.y.set(targetY + 4.0F);
        rowEntries.add(entry);
        return entry;
    }

    private List<CooldownInfo> collectActive() {
        activeCooldowns.clear();
        if (mc.player == null) {
            infoByItem.clear();
            return activeCooldowns;
        }

        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty() || !mc.player.getCooldowns().isOnCooldown(stack)) {
                continue;
            }

            CooldownKey key = CooldownKey.of(stack);
            float progress = mc.player.getCooldowns().getCooldownPercent(stack, 0.0F);
            CooldownInfo info = infoByItem.computeIfAbsent(key, ignored -> new CooldownInfo());
            info.key = key;
            info.stack = stack.copy();
            info.displayName = displayName(stack);
            info.update(progress, mc.player.tickCount);
            activeCooldowns.add(info);
        }
        infoByItem.entrySet().removeIf(entry -> !containsActiveKey(entry.getKey()));
        return activeCooldowns;
    }

    private boolean containsActiveKey(CooldownKey key) {
        for (CooldownInfo info : activeCooldowns) {
            if (info.key.equals(key)) {
                return true;
            }
        }
        return false;
    }

    private String displayName(ItemStack stack) {
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return switch (id) {
            case "minecraft:ender_eye" -> "Disorientation";
            case "minecraft:sugar" -> "Sugar";
            case "minecraft:netherite_scrap" -> "Trap";
            case "minecraft:dried_kelp" -> "Plast";
            case "minecraft:trident" -> "Trident";
            case "minecraft:mace" -> "Mace";
            case "minecraft:wind_charge" -> "Wind Charge";
            case "minecraft:enchanted_golden_apple" -> "Ench. Gap";
            case "minecraft:golden_apple" -> "Golden Apple";
            default -> stack.getHoverName().getString();
        };
    }

    private static final class RowEntry {
        private final String key;
        private final SmoothAnimation alpha = new SmoothAnimation();
        private final SmoothAnimation y = new SmoothAnimation();
        private ItemStack stack;
        private String name;
        private String time;
        private float timeSize;
        private boolean active;

        private RowEntry(String key, ItemStack stack, String name, String time, float timeSize) {
            this.key = key;
            this.stack = stack == null ? ItemStack.EMPTY : stack.copy();
            this.name = name;
            this.time = time;
            this.timeSize = timeSize;
        }
    }

    private final class CooldownInfo {
        private CooldownKey key;
        private ItemStack stack = ItemStack.EMPTY;
        private String displayName = "";
        private float progress;
        private float lastProgress = -1.0F;
        private long lastTick = -1L;
        private int totalTicks = -1;
        private int remainingTicks;

        private void update(float nextProgress, long tick) {
            progress = clamp(nextProgress, 0.0F, 1.0F);
            if (lastTick >= 0L && tick > lastTick && lastProgress > progress) {
                float diff = lastProgress - progress;
                if (diff > 0.00001F) {
                    int estimate = Math.round((tick - lastTick) / diff);
                    if (estimate > 0 && estimate < 12000) {
                        totalTicks = totalTicks <= 0 ? estimate : Math.round(totalTicks * 0.75F + estimate * 0.25F);
                    }
                }
            }
            lastProgress = progress;
            lastTick = tick;
            remainingTicks = totalTicks <= 0 ? Math.max(1, Math.round(progress * 20.0F)) : Math.max(1, Math.round(progress * totalTicks));
        }

        private String remainingText() {
            return formatCooldownDurationTicks(remainingTicks);
        }
    }

    private void renderTimer(String text, float x, float y, float size, float alpha) {
        float cellWidth = 3.25F;
        int color = ColorUtil.rgba(255, 255, 255, Math.round(245.0F * alpha));

        for (int i = 0; i < text.length(); i++) {
            String character = charText(text.charAt(i));
            float characterWidth = Render2D.textWidth(TEXT_FONT, character, size);
            float characterX = x + i * cellWidth + (cellWidth - characterWidth) * 0.5F + 3.0F;

            Render2D.text(TEXT_FONT, character, characterX, y, size, color);
        }
    }

    private String formatCooldownDurationTicks(int ticks) {
        if (ticks < 0) {
            return "**:**";
        }

        int totalSeconds = Math.max(0, ticks / 20);
        int minutes = Math.min(99, totalSeconds / 60);
        int seconds = totalSeconds % 60;
        return twoDigits(minutes) + ":" + twoDigits(seconds);
    }

    private record RowState(ItemStack stack, String name, String time, float timeSize, float offset, float alpha) {
    }

    private record CooldownsState(List<RowState> rows, float iconAlpha, float alpha, float x, float y, float width, float height) {
    }

    private record CooldownKey(Item item, DataComponentMap components, String name) {
        private static CooldownKey of(ItemStack stack) {
            return new CooldownKey(stack.getItem(), stack.immutableComponents(), stack.getHoverName().getString());
        }

        private String rowKey() {
            return BuiltInRegistries.ITEM.getKey(item) + "|" + name + "|" + components.hashCode();
        }
    }
}
