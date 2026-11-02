package releon.ru.api.module.impl.visual.esp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import releon.ru.api.settings.impl.MultiModeSetting;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.font.FontType;
import releon.ru.utils.repository.friend.FriendUtils;

import java.util.ArrayList;
import java.util.List;

public final class EspTagRenderer {
    private final PlayerTagResolver playerTags;
    private final EspHealthTracker healthTracker;
    private final List<TagPart> parts = new ArrayList<>(12);

    public EspTagRenderer(PlayerTagResolver playerTags, EspHealthTracker healthTracker) {
        this.playerTags = playerTags;
        this.healthTracker = healthTracker;
    }

    public void drawTag(Entity entity, EspGeometry.ScreenBox box, EspGeometry.ScreenAnchor anchor, boolean includeHealth,
                 float alpha, MultiModeSetting tagElements, Minecraft mc, GuiGraphics graphics) {
        parts.clear();
        ItemStack iconStack = ItemStack.EMPTY;
        boolean roundIcon = false;

        if (entity instanceof ItemEntity item) {
            ItemStack stack = item.getItem();
            iconStack = stack;
            TagParts.addTagPart(parts, FontType.SEMIBOLD, TagParts.clean(stack.getHoverName().getString()), EspColors.TAG_TEXT, 0.0F);
            if (stack.getCount() > 1) {
                TagParts.addTagPart(parts, FontType.SEMIBOLD, " x" + stack.getCount(), EspColors.TAG_MUTED, 0.0F);
            }
        } else if (entity instanceof Player player) {
            PlayerTag tag = playerTags.resolve(player, mc);
            if (tagElements.isSelected("Friend Prefix") && FriendUtils.isFriend(player)) {
                TagParts.addTagPart(parts, FontType.SEMIBOLD, "(F)", ColorUtil.rgba(42, 255, 118, 255), -0.25F);
            }
            TagParts.addTagParts(parts, TagParts.withYOffset(tag.voice(), 0.0F));
            if (tagElements.isSelected("Donation")) {
                TagParts.addTagParts(parts, TagParts.withYOffset(TagParts.compactToSingleTextPart(tag.donation(), FontType.BOLD, EspColors.TAG_MUTED), -0.5F));
            }
            if (tagElements.isSelected("Prefix")) {
                TagParts.addTagParts(parts, TagParts.withYOffset(TagParts.compactToSingleTextPart(tag.prefix(), FontType.SEMIBOLD, EspColors.TAG_MUTED), 0.0F));
            }
            TagParts.addTagParts(parts, TagParts.withYOffset(tag.name(), 0.0F));
            if (tagElements.isSelected("Suffix")) {
                TagParts.addTagParts(parts, TagParts.withYOffset(tag.suffix(), 0.0F));
            }
            if (includeHealth && tagElements.isSelected("Health")) {
                TagParts.addTagPart(parts, FontType.BOLD,
                        healthTracker.formatHealth(healthTracker.resolveDisplayHealth(player)),
                        EspVisualRenderer.healthTextColor(player, healthTracker),
                        0.0F);
            }
        } else {
            TagParts.addTagPart(parts, FontType.SEMIBOLD, labelFor(entity, false), EspColors.TAG_TEXT, 0.0F);
        }

        if (includeHealth && entity instanceof LivingEntity living && !(entity instanceof Player)) {
            TagParts.addTagPart(parts, FontType.SEMIBOLD,
                    " [" + healthTracker.formatHealth(healthTracker.resolveDisplayHealth(living)) + "HP]",
                    EspColors.TAG_HEALTH,
                    0.0F);
        }
        if (parts.isEmpty() && iconStack.isEmpty()) {
            return;
        }

        Player headPlayer = entity instanceof Player player && tagElements.isSelected("Head") ? player : null;
        EspVisualRenderer.drawTagFrame(headPlayer, parts, iconStack, roundIcon, box, anchor, alpha, graphics);
    }

    public void drawHealthBar(EspGeometry.ScreenBox box, LivingEntity entity, float alpha) {
        EspVisualRenderer.drawHealthBar(box, entity, alpha, healthTracker);
    }

    public void drawEquipment(Player player, EspGeometry.ScreenBox box, EspGeometry.ScreenAnchor anchor, float alpha) {
        EspVisualRenderer.drawEquipment(player, box, anchor, alpha);
    }

    public void drawPotions(LivingEntity entity, EspGeometry.ScreenBox box, float alpha) {
        EspVisualRenderer.drawPotions(entity, box, alpha);
    }

    private String labelFor(Entity entity, boolean includeHealth) {
        if (entity instanceof ItemEntity item) {
            ItemStack stack = item.getItem();
            String name = TagParts.clean(stack.getHoverName().getString());
            return stack.getCount() > 1 ? name + " x" + stack.getCount() : name;
        }
        String name = TagParts.clean(entity.getName().getString());
        if (entity instanceof Player player && FriendUtils.isFriend(player)) {
            name = "Friend " + name;
        }
        if (includeHealth && entity instanceof LivingEntity living) {
            name += " [" + healthTracker.formatHealth(healthTracker.resolveDisplayHealth(living)) + "HP]";
        }
        return name;
    }

}
