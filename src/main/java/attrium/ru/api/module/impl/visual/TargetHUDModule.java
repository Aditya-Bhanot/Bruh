package attrium.ru.api.module.impl.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.ModeSetting;
import attrium.ru.api.settings.impl.NumberSetting;
import attrium.ru.manager.FriendManager;
import attrium.ru.manager.HudManager;
import attrium.ru.manager.TargetManager;
import attrium.ru.utils.render.AnimationUtil;

/**
 * Standalone TargetHUD Module (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * Features:
 *  - Card Styles: Modern Glass (160x48), Compact Pill (135x34), Minimalist
 *  - Color Themes: Aqua Cyan, Health Dynamic (Green -> Yellow -> Red), Cyber Gradient
 *  - Health Bar Modes: Ghost Trail (lagging easingHealth), Smooth Lerp, Instant
 *  - Show Armor & Pops (compact armor icons + live totem pop badge), Show Status Tag (WINNING / LOSING)
 *  - Hold Duration (0.5 - 3.0s) + live mc.player preview in HUD tab & Layout Editor
 */
public class TargetHUDModule extends HudModule {
    private static volatile TargetHUDModule instance;

    private static final EquipmentSlot[] ARMOR_SLOTS = new EquipmentSlot[]{
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private final ModeSetting cardStyle = register(new ModeSetting(
            "Card Style",
            "TargetHUD layout style: Modern Glass, Compact Pill, or Minimalist.",
            "Modern Glass",
            "Modern Glass", "Compact Pill", "Minimalist"
    ));

    private final ModeSetting colorTheme = register(new ModeSetting(
            "Color Theme",
            "Health bar and accent color scheme.",
            "Aqua Cyan",
            "Aqua Cyan", "Health Dynamic", "Cyber Gradient"
    ));

    private final ModeSetting healthBarMode = register(new ModeSetting(
            "Health Bar Mode",
            "Ghost Trail shows lagging burst damage; Smooth Lerp or Instant update directly.",
            "Ghost Trail",
            "Ghost Trail", "Smooth Lerp", "Instant"
    ));

    private final BooleanSetting showArmorAndPops = register(new BooleanSetting(
            "Show Armor & Pops",
            "Displays compact armor icons and live totem pop count next to the target.",
            true
    ));

    private final BooleanSetting showStatusTag = register(new BooleanSetting(
            "Show Status Tag",
            "Displays WINNING (cyan) or LOSING (coral) based on combined HP and armor.",
            true
    ));

    private final NumberSetting holdDuration = register(new NumberSetting(
            "Hold Duration",
            "Seconds TargetHUD stays visible after combat ends (0.5 - 3.0s).",
            1.5D, 0.5D, 3.0D, 0.25D
    ));

    // Zero-allocation persistent animation & string cache state
    private Player trackedTarget = null;
    private long lastSeenMs = 0L;
    private int lastTargetId = -1;
    private float popAnim = 0.0F;
    private float animatedHealth = 1.0F;
    private float easingHealth = 1.0F;

    private String cachedName = "";
    private Component cachedNameComp = null;
    private int cachedHpTenths = -1;
    private Component cachedHpComp = null;
    private int cachedDistTenths = -1;
    private Component cachedDistComp = null;
    private int cachedPopCount = -1;
    private Component cachedPopComp = null;
    private Component winningComp = null;
    private Component losingComp = null;

    public TargetHUDModule() {
        super("TargetHUD", "Animated combat status card with avatar, ghost HP bar, armor & pops.",
                "T", 0.560D, 0.620D, 217.0D, true);
        instance = this;
    }

    public static TargetHUDModule getInstance() {
        return instance;
    }

    @Override
    public void renderHud(Minecraft mc, GuiGraphics guiGraphics, Font font,
                          int screenW, int screenH, float dt, float animClockSeconds,
                          boolean previewMode, boolean editorMode) {
        Player candidate = resolveTarget(mc, previewMode || editorMode);
        long nowMs = System.currentTimeMillis();
        long holdMs = Math.round(Mth.clamp(holdDuration.getValue().floatValue(), 0.5F, 3.0F) * 1000.0F);

        if (candidate != null) {
            trackedTarget = candidate;
            lastSeenMs = nowMs;
        } else if (trackedTarget != null) {
            if (!trackedTarget.isAlive() || trackedTarget.isRemoved() || (nowMs - lastSeenMs) > holdMs) {
                trackedTarget = null;
            }
        }

        popAnim = AnimationUtil.lerp(popAnim, trackedTarget != null ? 1.0F : 0.0F, 13.0F, dt);

        String style = cardStyle.getValue();
        boolean compact = "Compact Pill".equalsIgnoreCase(style);
        boolean minimal = "Minimalist".equalsIgnoreCase(style);
        int cardW = compact ? 135 : (minimal ? 142 : 160);
        int cardH = compact ? 34 : (minimal ? 32 : 48);

        float sc = getHudScale();
        int boxW = Math.round(cardW * sc);
        int boxH = Math.round(cardH * sc);
        int baseX = Mth.clamp(Math.round(getXRatio() * screenW), 2, Math.max(2, screenW - boxW - 2));
        int baseY = Mth.clamp(Math.round(getYRatio() * screenH), 2, Math.max(2, screenH - boxH - 2));

        this.lastBoxX = baseX;
        this.lastBoxY = baseY;
        this.lastBoxW = boxW;
        this.lastBoxH = boxH;

        if (popAnim <= 0.01F || trackedTarget == null) {
            return;
        }

        Player target = trackedTarget;
        float maxHp = Math.max(1.0F, target.getMaxHealth());
        float hp = Mth.clamp(target.getHealth() + target.getAbsorptionAmount(), 0.0F, maxHp + 20.0F);
        float hpRatio = Mth.clamp(hp / maxHp, 0.0F, 1.0F);

        if (target.getId() != lastTargetId) {
            lastTargetId = target.getId();
            animatedHealth = hpRatio;
            easingHealth = hpRatio;
            cachedName = "";
            cachedHpTenths = -1;
            cachedDistTenths = -1;
            cachedPopCount = -1;
        }

        String barMode = healthBarMode.getValue();
        if ("Instant".equalsIgnoreCase(barMode)) {
            animatedHealth = hpRatio;
            easingHealth = hpRatio;
        } else if ("Smooth Lerp".equalsIgnoreCase(barMode)) {
            animatedHealth = AnimationUtil.lerp(animatedHealth, hpRatio, 12.0F, dt);
            easingHealth = animatedHealth;
        } else {
            // Ghost Trail
            animatedHealth = AnimationUtil.lerp(animatedHealth, hpRatio, 14.0F, dt);
            easingHealth = AnimationUtil.lerp(easingHealth, hpRatio, 3.6F, dt);
            if (easingHealth < animatedHealth) easingHealth = animatedHealth;
        }

        updateCachedTextComponents(mc, target, hp);

        float alpha = Mth.clamp(popAnim, 0.0F, 1.0F);
        float popScale = sc * (0.85F + 0.15F * AnimationUtil.easeOutCubic(alpha));
        int cardBg = (Mth.clamp(Math.round(getBackgroundAlphaByte() * alpha), 0, 255) << 24) | 0x111318;

        int barLeftCol = AnimationUtil.withAlpha(resolveBarStartColor(hpRatio, animClockSeconds), alpha);
        int barRightCol = AnimationUtil.withAlpha(resolveBarEndColor(hpRatio, animClockSeconds), alpha);

        float cx = baseX + boxW * 0.5F;
        float cy = baseY + boxH * 0.5F;

        guiGraphics.pose().pushMatrix();
        guiGraphics.pose().translate(cx, cy);
        guiGraphics.pose().scale(popScale, popScale);
        guiGraphics.pose().translate(-cardW * 0.5F, -cardH * 0.5F);

        HudManager.drawRoundedRect(guiGraphics, 0, 0, cardW, cardH, compact ? 9 : 10, cardBg);

        int textCol = AnimationUtil.withAlpha(0xFFFFFFFF, alpha);
        int mutedCol = AnimationUtil.withAlpha(0xFF9CA3AF, alpha);

        if (minimal) {
            // Minimalist: name + HP/dist + sleek 5px bar
            if ((textCol >>> 24) > 8) {
                guiGraphics.drawString(font, cachedNameComp, 9, 7, textCol, false);
                int hpW = font.width(cachedHpComp);
                guiGraphics.drawString(font, cachedHpComp, cardW - 9 - hpW, 7, barLeftCol, false);
            }
            drawHealthBar(guiGraphics, 9, 21, cardW - 18, 5, 2, alpha, barMode, barLeftCol, barRightCol);
            guiGraphics.pose().popMatrix();
            return;
        }

        // Player Avatar on left (28x28 for Modern Glass, 22x22 for Compact Pill)
        int avatarSize = compact ? 22 : 28;
        int avatarX = compact ? 6 : 9;
        int avatarY = (cardH - avatarSize) / 2;

        HudManager.drawRoundedRect(guiGraphics, avatarX - 1, avatarY - 1, avatarSize + 2, avatarSize + 2, 6,
                AnimationUtil.withAlpha(0x33FFFFFF, alpha));
        HudManager.drawPlayerAvatar(guiGraphics, mc, target, avatarX, avatarY, avatarSize);
        if (target.hurtTime > 0) {
            float hurtAlpha = Mth.clamp((target.hurtTime / 10.0F) * 0.55F * alpha, 0.0F, 0.6F);
            HudManager.drawRoundedRect(guiGraphics, avatarX, avatarY, avatarSize, avatarSize, 4,
                    AnimationUtil.withAlpha(0xFFFF2E4C, hurtAlpha));
        }

        int infoX = avatarX + avatarSize + 8;
        int infoRight = cardW - 8;

        if ((textCol >>> 24) > 8) {
            guiGraphics.drawString(font, cachedNameComp, infoX, compact ? 6 : 7, textCol, false);

            // Status Tag (WINNING / LOSING) on top-right
            if (Boolean.TRUE.equals(showStatusTag.getValue()) && !compact) {
                boolean winning = isPlayerWinning(mc.player, target);
                Component statusComp = winning ? winningComp : losingComp;
                int statusCol = AnimationUtil.withAlpha(winning ? 0xFF00E5FF : 0xFFFF5C77, alpha);
                int sw = font.width(statusComp);
                guiGraphics.drawString(font, statusComp, infoRight - sw, 7, statusCol, false);
            }

            // Second row: HP + Distance (and Pops badge if enabled)
            int row2Y = compact ? 15 : 18;
            guiGraphics.drawString(font, cachedHpComp, infoX, row2Y, barLeftCol, false);
            int hpW = font.width(cachedHpComp);

            if (!compact && Boolean.TRUE.equals(showArmorAndPops.getValue()) && cachedPopComp != null) {
                int popW = font.width(cachedPopComp);
                if (infoX + hpW + 6 + popW <= infoRight) {
                    guiGraphics.drawString(font, cachedPopComp, infoRight - popW, row2Y,
                            AnimationUtil.withAlpha(0xFFFFC94A, alpha), false);
                }
            } else {
                int distW = font.width(cachedDistComp);
                if (infoX + hpW + 5 + distW <= infoRight) {
                    guiGraphics.drawString(font, cachedDistComp, infoRight - distW, row2Y, mutedCol, false);
                }
            }
        }

        // Compact Armor Icons in Modern Glass mode (above/right of bar or compact row)
        if (!compact && Boolean.TRUE.equals(showArmorAndPops.getValue())) {
            int armorX = infoX;
            int armorY = 27;
            for (int i = 0; i < ARMOR_SLOTS.length; i++) {
                ItemStack stack = target.getItemBySlot(ARMOR_SLOTS[i]);
                if (stack != null && !stack.isEmpty()) {
                    guiGraphics.pose().pushMatrix();
                    guiGraphics.pose().translate(armorX, armorY);
                    guiGraphics.pose().scale(0.56F, 0.56F);
                    guiGraphics.renderItem(stack, 0, 0);
                    guiGraphics.pose().popMatrix();
                    armorX += 10;
                }
            }
            if ((mutedCol >>> 24) > 8) {
                int distW = font.width(cachedDistComp);
                guiGraphics.drawString(font, cachedDistComp, infoRight - distW, 28, mutedCol, false);
            }
        }

        // Dual-Layer Rounded Health Bar
        int barY = compact ? 25 : 38;
        int barH = compact ? 5 : 6;
        drawHealthBar(guiGraphics, infoX, barY, infoRight - infoX, barH, 3, alpha, barMode, barLeftCol, barRightCol);

        guiGraphics.pose().popMatrix();
    }

    private void drawHealthBar(GuiGraphics guiGraphics, int x, int y, int w, int h, int radius,
                               float alpha, String barMode, int leftCol, int rightCol) {
        HudManager.drawRoundedRect(guiGraphics, x, y, w, h, radius, AnimationUtil.withAlpha(0x55000000, alpha));

        if ("Ghost Trail".equalsIgnoreCase(barMode)) {
            int ghostW = Math.round(w * easingHealth);
            if (ghostW > 2) {
                HudManager.drawRoundedRect(guiGraphics, x, y, ghostW, h, radius,
                        AnimationUtil.withAlpha(0x77A5F3FC, alpha));
            }
        }

        int fillW = Math.round(w * animatedHealth);
        if (fillW > 2) {
            HudManager.drawHorizontalGradientBar(guiGraphics, x, y, fillW, h, radius, leftCol, rightCol);
        }
    }

    private void updateCachedTextComponents(Minecraft mc, Player target, float hp) {
        if (winningComp == null) {
            winningComp = Component.literal("WIN").withStyle(HudManager.getBoldStyle());
            losingComp = Component.literal("LOSE").withStyle(HudManager.getBoldStyle());
        }

        String rawName = target.getName().getString();
        if (!rawName.equals(cachedName) || cachedNameComp == null) {
            cachedName = rawName;
            String clipped = rawName.length() > 11 ? rawName.substring(0, 11) : rawName;
            cachedNameComp = Component.literal(clipped).withStyle(HudManager.getBoldStyle());
        }

        int hpTenths = Math.round(hp * 10.0F);
        if (hpTenths != cachedHpTenths || cachedHpComp == null) {
            cachedHpTenths = hpTenths;
            cachedHpComp = Component.literal((hpTenths / 10) + "." + (hpTenths % 10) + " HP")
                    .withStyle(HudManager.getRegularStyle());
        }

        double dx = target.getX() - mc.player.getX();
        double dy = target.getY() - mc.player.getY();
        double dz = target.getZ() - mc.player.getZ();
        int distTenths = Math.round((float) Math.sqrt(dx * dx + dy * dy + dz * dz) * 10.0F);
        if (distTenths != cachedDistTenths || cachedDistComp == null) {
            cachedDistTenths = distTenths;
            cachedDistComp = Component.literal((distTenths / 10) + "." + (distTenths % 10) + "m")
                    .withStyle(HudManager.getRegularStyle());
        }

        int pops = HudManager.getTotemPopCount(target.getUUID());
        if (pops != cachedPopCount) {
            cachedPopCount = pops;
            cachedPopComp = pops > 0
                    ? Component.literal("Pops: " + pops).withStyle(HudManager.getRegularStyle())
                    : null;
        }
    }

    private int resolveBarStartColor(float hpRatio, float clockSec) {
        String theme = colorTheme.getValue();
        if ("Health Dynamic".equalsIgnoreCase(theme)) {
            if (hpRatio >= 0.5F) {
                return AnimationUtil.lerpColor(0xFFFACC15, 0xFF34D399, (hpRatio - 0.5F) * 2.0F);
            }
            return AnimationUtil.lerpColor(0xFFFF4D6D, 0xFFFACC15, hpRatio * 2.0F);
        }
        if ("Cyber Gradient".equalsIgnoreCase(theme)) {
            return AnimationUtil.resolveAccentColor(3, clockSec, 0.0F);
        }
        return AnimationUtil.ACCENT_AQUA_CYAN;
    }

    private int resolveBarEndColor(float hpRatio, float clockSec) {
        String theme = colorTheme.getValue();
        if ("Health Dynamic".equalsIgnoreCase(theme)) {
            return resolveBarStartColor(Mth.clamp(hpRatio - 0.12F, 0.0F, 1.0F), clockSec);
        }
        if ("Cyber Gradient".equalsIgnoreCase(theme)) {
            return AnimationUtil.resolveAccentSecondary(3, clockSec, 0.0F);
        }
        return AnimationUtil.ACCENT_CYBER_BLUE;
    }

    private static boolean isPlayerWinning(Player self, Player target) {
        if (self == null || target == null || self == target) return true;
        float selfScore = self.getHealth() + self.getAbsorptionAmount() + self.getArmorValue() * 0.5F;
        float targetScore = target.getHealth() + target.getAbsorptionAmount() + target.getArmorValue() * 0.5F;
        return selfScore >= targetScore;
    }

    private static Player resolveTarget(Minecraft mc, boolean previewMode) {
        if (previewMode) {
            return mc.player;
        }
        Player[] enemies = TargetManager.getEnemyPlayersArray();
        int count = TargetManager.getEnemyPlayerCount();
        Player best = null;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            Player p = enemies[i];
            if (p == null || p == mc.player || !p.isAlive() || p.isRemoved()) continue;
            if (FriendManager.isFriend(p)) continue;
            double distSq = mc.player.distanceToSqr(p);
            if (distSq > 64.0D) continue;
            double score = distSq - (p.hurtTime > 0 ? 18.0D : 0.0D);
            if (score < bestScore) {
                bestScore = score;
                best = p;
            }
        }
        if (best != null && (best.hurtTime > 0 || mc.crosshairPickEntity == best || mc.player.swinging)) {
            return best;
        }
        return null;
    }
}
