package releon.ru.api.module.impl.visual.esp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import releon.ru.utils.render.Render3D;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.item.RenderItem;
import releon.ru.utils.render.item.RenderItemOptions;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.util.List;

public final class EspVisualRenderer {
    private EspVisualRenderer() {
    }

    public record ItemTagRow(ItemStack stack, String name, int count) {
    }

    public static void draw2DBox(EspGeometry.ScreenBox box, int color, float alpha) {
        float x = roundHalf((float) box.minX());
        float y = roundHalf((float) box.minY());
        float width = Math.max(2.0F, roundHalf((float) box.width()));
        float height = Math.max(2.0F, roundHalf((float) box.height()));
        float length = Math.max(5.0F, Math.min(Math.min(width * 0.34F, height * 0.22F), 18.0F));

        drawCornerSegments(x, y, width, height, length, 3.1F, ColorUtil.rgba(0, 0, 0, Math.round(155.0F * alpha)));
        drawCornerSegments(x, y, width, height, length, 1.35F, ColorUtil.multAlpha(color, alpha));
    }

    public static void draw3DCornerBox(AABB box, int color, float width) {
        double lenX = Math.max(0.08D, Math.min(box.getXsize() * 0.38D, 0.34D));
        double lenY = Math.max(0.18D, Math.min(box.getYsize() * 0.22D, 0.48D));
        double lenZ = Math.max(0.08D, Math.min(box.getZsize() * 0.38D, 0.34D));
        int renderColor = ColorUtil.multAlpha(color, 0.92F);

        for (int xi = 0; xi < 2; xi++) {
            for (int yi = 0; yi < 2; yi++) {
                for (int zi = 0; zi < 2; zi++) {
                    double x = xi == 0 ? box.minX : box.maxX;
                    double y = yi == 0 ? box.minY : box.maxY;
                    double z = zi == 0 ? box.minZ : box.maxZ;
                    double dx = xi == 0 ? lenX : -lenX;
                    double dy = yi == 0 ? lenY : -lenY;
                    double dz = zi == 0 ? lenZ : -lenZ;
                    Vec3 corner = new Vec3(x, y, z);

                    Render3D.drawLineOverlay(corner, new Vec3(x + dx, y, z), renderColor, width);
                    Render3D.drawLineOverlay(corner, new Vec3(x, y + dy, z), renderColor, width);
                    Render3D.drawLineOverlay(corner, new Vec3(x, y, z + dz), renderColor, width);
                }
            }
        }
    }

    public static float resolve3DLineWidth(Minecraft mc, AABB box) {
        double distance = mc.gameRenderer.getMainCamera().position().distanceTo(box.getCenter());
        return (float) Mth.clamp(2.4D - distance * 0.012D, 1.05D, 2.4D);
    }

    public static void drawTagFrame(Player player, List<TagPart> parts, ItemStack iconStack, boolean roundIcon,
                                    EspGeometry.ScreenBox box, EspGeometry.ScreenAnchor anchor, float alpha,
                                    GuiGraphics graphics) {
        boolean playerTag = player != null;
        float textSize = 6.0F;
        float textWidth = 0.0F;
        for (TagPart part : parts) {
            textWidth += Render2D.textWidth(part.font(), part.text(), textSize);
        }

        boolean hasIcon = !iconStack.isEmpty();
        boolean hasHead = playerTag;
        float headSize = hasHead ? 8.0F : 0.0F;
        float headGap = hasHead && !parts.isEmpty() ? 3.2F : 0.0F;
        float iconSize = hasIcon ? 9.0F : 0.0F;
        float iconBgSize = hasIcon ? iconSize + 3.0F : 0.0F;
        float iconGap = hasIcon && !parts.isEmpty() ? 2.8F : 0.0F;
        float paddingX = 3.2F;
        float paddingY = 1.8F;
        float height = Math.max(textSize + paddingY * 2.0F + 1.0F, Math.max(hasIcon ? iconBgSize + 2.0F : 0.0F, hasHead ? headSize + 2.0F : 0.0F));
        float totalWidth = textWidth + headSize + headGap + iconBgSize + iconGap;
        double anchorX = anchor != null ? anchor.x() : box.centerX();
        double anchorY = anchor != null ? anchor.y() : box.minY();
        float x = (float) (anchorX - totalWidth * 0.5F);
        float y = (float) anchorY - height - 1.0F + 0.0F;

        float backgroundX = x - (hasIcon && parts.isEmpty() ? 0.0F : paddingX);
        float backgroundWidth = totalWidth + (hasIcon && parts.isEmpty() ? 0.0F : paddingX * 2.0F);
        Render2D.rect(backgroundX, y - 1, backgroundWidth, height + 2, 3,
                ColorUtil.rgba(0, 0, 0, Math.round(185.0F * alpha)));


        float drawX = x;
        if (hasHead) {
            drawPlayerHead(graphics, player, drawX, y + (height - headSize) * 0.5F + 0.0F, headSize, alpha);
            drawX += headSize + headGap;
        }

        if (hasIcon) {
            float iconBgX = drawX;
            float iconBgY = y + (height - iconBgSize) * 0.5F + 0.0F;
            if (roundIcon) {
                Render2D.rect(iconBgX, iconBgY, iconBgSize, iconBgSize, iconBgSize * 0.5F, ColorUtil.rgba(22, 22, 22, Math.round(170.0F * alpha)));
            }
            RenderItem.item(iconStack, iconBgX + 1.5F, iconBgY + 1.5F, iconSize, RenderItemOptions.noDecorations(alpha));
            drawX += iconBgSize + iconGap;
        }

        float textY = y + (height - textSize) * 0.5F - 0.75F;
        for (TagPart part : parts) {
            Render2D.text(part.font(), part.text(), drawX, textY + 0.0F + part.yOffset(), textSize, ColorUtil.multAlpha(part.color(), alpha));
            drawX += Render2D.textWidth(part.font(), part.text(), textSize);
        }
    }

    public static void drawCompactItemTag(List<ItemTagRow> rows, double anchorX, double anchorY, float alpha, GuiGraphics graphics) {
        if (rows == null || rows.isEmpty()) {
            return;
        }

        float textSize = 6.2F;
        float rowHeight = 11.0F;
        float iconSize = 8.5F;
        float iconGap = 3.0F;
        float paddingX = 4.2F;
        float paddingY = 3.0F;
        float countGap = 4.0F;
        float nameWidth = 0.0F;
        float countWidth = 0.0F;
        for (ItemTagRow row : rows) {
            nameWidth = Math.max(nameWidth, Render2D.textWidth(FontType.SEMIBOLD, row.name(), textSize));
            countWidth = Math.max(countWidth, Render2D.textWidth(FontType.SEMIBOLD, compactCount(row.count()), textSize));
        }

        float width = paddingX * 2.0F + iconSize + iconGap + nameWidth + countGap + countWidth;
        float height = paddingY * 2.0F + rows.size() * rowHeight;
        float x = (float) anchorX - width * 0.5F;
        float y = (float) anchorY - height - 2.0F + 0.0F;

        Render2D.rect(x, y, width, height, 3.0F, ColorUtil.rgba(0, 0, 0, Math.round(185.0F * alpha)));

        RenderItemOptions itemOptions = RenderItemOptions.noDecorations(alpha);
        float rowY = y + paddingY;
        for (ItemTagRow row : rows) {
            float iconY = rowY + (rowHeight - iconSize) * 0.5F;
            RenderItem.item(row.stack(), x + paddingX, iconY, iconSize, itemOptions);

            float textY = rowY + (rowHeight - textSize) * 0.5F - 0.65F;
            float textX = x + paddingX + iconSize + iconGap;
            Render2D.text(FontType.SEMIBOLD, row.name(), textX, textY, textSize, ColorUtil.rgba(245, 245, 245, Math.round(255.0F * alpha)));

            String count = compactCount(row.count());
            float rowCountWidth = Render2D.textWidth(FontType.SEMIBOLD, count, textSize);
            Render2D.text(FontType.SEMIBOLD, count, x + width - paddingX - rowCountWidth, textY, textSize,
                    ColorUtil.rgba(165, 165, 165, Math.round(245.0F * alpha)));
            rowY += rowHeight;
        }
    }

    public static void drawHealthBar(EspGeometry.ScreenBox box, LivingEntity entity, float alpha, EspHealthTracker healthTracker) {
        float health = healthTracker.resolveDisplayHealth(entity);
        float maxHealth = Math.max(1.0F, entity.getMaxHealth() + entity.getAbsorptionAmount());
        maxHealth = Math.max(maxHealth, health);
        float progress = Math.clamp(health / maxHealth, 0.0F, 1.0F);
        float x = (float) box.minX() - 4.6F;
        float y = (float) box.minY();
        float height = (float) box.height();
        float fillHeight = Math.max(1.0F, height * progress);
        int healthColor = ColorUtil.lerpColor(ColorUtil.rgba(255, 70, 70, 240), ColorUtil.rgba(80, 255, 120, 240), progress);

        Render2D.rect(x, y, 2.1F, height, 1.0F, ColorUtil.rgba(0, 0, 0, Math.round(135.0F * alpha)));
        Render2D.rect(x, y + height - fillHeight, 2.1F, fillHeight, 1.0F, ColorUtil.multAlpha(healthColor, alpha));
    }

    public static int healthTextColor(LivingEntity entity, EspHealthTracker healthTracker) {
        float health = healthTracker.resolveDisplayHealth(entity);
        float maxHealth = Math.max(1.0F, entity.getMaxHealth() + entity.getAbsorptionAmount());
        maxHealth = Math.max(maxHealth, health);
        return ColorUtil.lerpColor(ColorUtil.rgba(255, 70, 70, 255), ColorUtil.rgba(70, 255, 115, 255), Math.clamp(health / maxHealth, 0.0F, 1.0F));
    }

    public static void drawEquipment(Player player, EspGeometry.ScreenBox box, EspGeometry.ScreenAnchor anchor, float alpha) {
        ItemStack offhand = player.getOffhandItem();
        ItemStack helmet = player.getItemBySlot(EquipmentSlot.HEAD);
        ItemStack chestplate = player.getItemBySlot(EquipmentSlot.CHEST);
        ItemStack leggings = player.getItemBySlot(EquipmentSlot.LEGS);
        ItemStack boots = player.getItemBySlot(EquipmentSlot.FEET);
        ItemStack mainHand = player.getMainHandItem();
        int itemCount = equipmentCount(offhand, helmet, chestplate, leggings, boots, mainHand);
        if (itemCount == 0) {
            return;
        }

        float totalWidth = itemCount * 10.0F + (itemCount - 1) * 1.4F;
        double anchorX = anchor != null ? anchor.x() : box.centerX();
        double anchorY = anchor != null ? anchor.y() : box.minY();
        float x = (float) (anchorX - totalWidth * 0.5F);
        float y = (float) anchorY - 27.0F;
        RenderItemOptions options = RenderItemOptions.decorated(alpha);

        x = drawEquipmentItem(offhand, x, y, options);
        x = drawEquipmentItem(helmet, x, y, options);
        x = drawEquipmentItem(chestplate, x, y, options);
        x = drawEquipmentItem(leggings, x, y, options);
        x = drawEquipmentItem(boots, x, y, options);
        drawEquipmentItem(mainHand, x, y, options);
    }

    public static void drawPotions(LivingEntity entity, EspGeometry.ScreenBox box, float alpha) {
        if (entity.getActiveEffects().isEmpty()) {
            return;
        }

        float x = (float) box.maxX() + 4.0F;
        float y = (float) box.minY() + 1.0F;
        int shown = 0;
        for (MobEffectInstance effect : entity.getActiveEffects()) {
            if (shown >= 5) {
                break;
            }

            Render2D.effectIcon(effect, x, y - 0.4F, 7.0F, ColorUtil.rgba(255, 255, 255, Math.round(235.0F * alpha)));
            Render2D.text(FontType.SEMIBOLD, effectText(effect), x + 8.2F, y + 0.1F, 5.8F, ColorUtil.rgba(235, 235, 235, Math.round(230.0F * alpha)));
            y += 8.5F;
            shown++;
        }
    }

    private static void drawPlayerHead(GuiGraphics graphics, Player player, float x, float y, float size, float alpha) {
        if (graphics == null || !(player instanceof AbstractClientPlayer clientPlayer) || size <= 0.0F || alpha <= 0.0F) {
            return;
        }

        Identifier skinTexture = clientPlayer.getSkin().body().texturePath();
        String texture = skinTexture.toString();
        int color = ColorUtil.rgba(255, 255, 255, Math.round(255.0F * Math.clamp(alpha, 0.0F, 1.0F)));

        Render2D.imageUvNearest(texture, x, y, size, size, 2.0F, 1.0F, 8.0F / 64.0F, 8.0F / 64.0F, 16.0F / 64.0F, 16.0F / 64.0F, color);
        Render2D.imageUvNearest(texture, x, y, size, size, 2.0F, 1.0F, 40.0F / 64.0F, 8.0F / 64.0F, 48.0F / 64.0F, 16.0F / 64.0F, color);
    }

    private static String compactCount(int count) {
        return count > 1 ? "x" + count : "x1";
    }

    private static String effectText(MobEffectInstance effect) {
        String name = effect.getEffect().value().getDisplayName().getString();
        int level = effect.getAmplifier() + 1;
        return name + (level > 1 ? " " + level : "") + " " + formatDuration(effect.getDuration());
    }

    private static String formatDuration(int ticks) {
        if (ticks < 0 || ticks >= 20 * 60 * 60) {
            return "**:**";
        }
        int seconds = Math.max(0, ticks / 20);
        return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
    }

    private static int equipmentCount(ItemStack offhand, ItemStack helmet, ItemStack chestplate, ItemStack leggings, ItemStack boots, ItemStack mainHand) {
        int count = 0;
        if (!offhand.isEmpty()) count++;
        if (!helmet.isEmpty()) count++;
        if (!chestplate.isEmpty()) count++;
        if (!leggings.isEmpty()) count++;
        if (!boots.isEmpty()) count++;
        if (!mainHand.isEmpty()) count++;
        return count;
    }

    private static float drawEquipmentItem(ItemStack stack, float x, float y, RenderItemOptions options) {
        if (!stack.isEmpty()) {
            RenderItem.item(stack, x, y, 8.0F, options);
            return x + 11.4F;
        }
        return x;
    }

    private static void drawCornerSegments(float x, float y, float width, float height, float length, float thickness, int color) {
        float right = x + width;
        float bottom = y + height;
        float half = thickness * 0.5F;

        Render2D.rect(x - half, y - half, length, thickness, 0.0F, color);
        Render2D.rect(x - half, y - half, thickness, length, 0.0F, color);
        Render2D.rect(right - length + half, y - half, length, thickness, 0.0F, color);
        Render2D.rect(right - half, y - half, thickness, length, 0.0F, color);

        Render2D.rect(x - half, bottom - half, length, thickness, 0.0F, color);
        Render2D.rect(x - half, bottom - length + half, thickness, length, 0.0F, color);
        Render2D.rect(right - length + half, bottom - half, length, thickness, 0.0F, color);
        Render2D.rect(right - half, bottom - length + half, thickness, length, 0.0F, color);
    }

    private static float roundHalf(float value) {
        return Math.round(value * 2.0F) / 2.0F;
    }
}
