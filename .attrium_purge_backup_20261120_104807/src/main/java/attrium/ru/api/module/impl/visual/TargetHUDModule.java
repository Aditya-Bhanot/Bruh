package attrium.ru.api.module.impl.visual;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import attrium.ru.api.module.impl.misc.NameProtectModule;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.ModeSetting;
import attrium.ru.api.settings.impl.NumberSetting;
import attrium.ru.manager.FriendManager;
import attrium.ru.manager.HudManager;
import attrium.ru.manager.TargetManager;
import attrium.ru.manager.TotemPopManager;
import attrium.ru.utils.render.AnimationUtil;

/**
 * Standalone TargetHUD Module (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * Features:
 *  - Modern Glass card (165x54px, 10px radius, 0xD9111318), Compact Pill (138x36), Minimalist (145x34)
 *  - Color Themes: Aqua Cyan, Health Dynamic (Green -> Yellow -> Red), Cyber Gradient
 *  - Health Bar Modes: Ghost Trail (lagging easingHealth), Smooth Lerp, Instant
 *  - Enemy Armor Row (Helmet -> Chestplate -> Leggings -> Boots at 0.75f scale) with
 *    Durability Style: Mini Bar (2px bar shifting #00E5FF -> #FACC15 -> #EF4444), Percentage, or Icon Only
 *  - Live Totem Pop Counter pill (4px radius, 0x33FFAA00, 0.65f Totem icon + Nunito Bold xN badge,
 *    auto-hidden when pops == 0; mock "x2" + preview armor in HUD tab / Layout Editor)
 *  - Zero per-frame ItemStack, collection, or String allocations for FoldCraftLauncher.
 */
public class TargetHUDModule extends HudModule {
    private static volatile TargetHUDModule instance;

    private static final EquipmentSlot[] ARMOR_SLOTS = new EquipmentSlot[]{
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    // Pre-allocated static ItemStacks & percentage Component cache (zero per-frame heap churn)
    private static ItemStack cachedTotemStack = null;
    private static ItemStack[] cachedPreviewArmor = null;
    private static final Component[] PERCENT_COMPS = new Component[101];
    private static boolean percentCompsInit = false;

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

    private final BooleanSetting showArmor = register(new BooleanSetting(
            "Show Armor",
            "Renders target armor pieces (Helmet to Boots) above the health bar.",
            true
    ));

    private final ModeSetting durabilityStyle = register(new ModeSetting(
            "Durability Style",
            "Mini Bar renders a 2px colored bar under each piece; Percentage shows numeric %; Icon Only hides durability.",
            "Mini Bar",
            "Mini Bar", "Percentage", "Icon Only"
    ));

    private final BooleanSetting totemPopCounter = register(new BooleanSetting(
            "Totem Pop Counter",
            "Displays a compact golden totem pop counter badge when the target has popped totems.",
            true
    ));

    private final BooleanSetting showStatusTag = register(new BooleanSetting(
            "Show Status Tag",
            "Displays WIN (cyan) or LOSE (coral) based on combined HP and armor.",
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
        super("TargetHUD", "Displays target health, armor durability, and totem pops",
                "T", 0.560D, 0.620D, 217.0D, true);
        instance = this;
        this.durabilityStyle.visibleWhen(() -> Boolean.TRUE.equals(showArmor.getValue()));
    }

    public static TargetHUDModule getInstance() {
        return instance;
    }

    private static void ensureStaticCaches() {
        if (cachedTotemStack == null) {
            cachedTotemStack = new ItemStack(Items.TOTEM_OF_UNDYING);
        }
        if (cachedPreviewArmor == null) {
            ItemStack helm = new ItemStack(Items.DIAMOND_HELMET);
            helm.setDamageValue(Math.round(helm.getMaxDamage() * 0.08F)); // 92% -> cyan
            ItemStack chest = new ItemStack(Items.DIAMOND_CHESTPLATE);
            chest.setDamageValue(Math.round(chest.getMaxDamage() * 0.28F)); // 72% -> cyan
            ItemStack legs = new ItemStack(Items.DIAMOND_LEGGINGS);
            legs.setDamageValue(Math.round(legs.getMaxDamage() * 0.54F)); // 46% -> yellow
            ItemStack boots = new ItemStack(Items.DIAMOND_BOOTS);
            boots.setDamageValue(Math.round(boots.getMaxDamage() * 0.80F)); // 20% -> red
            cachedPreviewArmor = new ItemStack[]{ helm, chest, legs, boots };
        }
        if (!percentCompsInit) {
            percentCompsInit = true;
            for (int i = 0; i <= 100; i++) {
                PERCENT_COMPS[i] = Component.literal(i + "%").withStyle(HudManager.getRegularStyle());
            }
        }
    }

    @Override
    public void renderHud(Minecraft mc, GuiGraphics guiGraphics, Font font,
                          int screenW, int screenH, float dt, float animClockSeconds,
                          boolean previewMode, boolean editorMode) {
        ensureStaticCaches();
        boolean isPreview = previewMode || editorMode;

        Player candidate = resolveTarget(mc, isPreview);
        long nowMs = System.currentTimeMillis();
        long holdMs = Math.round(Mth.clamp(holdDuration.getValue().floatValue(), 0.5F, 3.0F) * 1000.0F);

        if (candidate != null) {
            trackedTarget = candidate;
            lastSeenMs = nowMs;
        } else if (trackedTarget != null) {
            if (trackedTarget.isDeadOrDying() || !trackedTarget.isAlive() || trackedTarget.isRemoved()) {
                TotemPopManager.clearPop(trackedTarget.getUUID());
                trackedTarget = null;
            } else if ((nowMs - lastSeenMs) > holdMs) {
                trackedTarget = null;
            }
        }

        popAnim = AnimationUtil.lerp(popAnim, trackedTarget != null ? 1.0F : 0.0F, 13.0F, dt);

        String style = cardStyle.getValue();
        boolean compact = "Compact Pill".equalsIgnoreCase(style);
        boolean minimal = "Minimalist".equalsIgnoreCase(style);
        int cardW = compact ? 138 : (minimal ? 145 : 165);
        int cardH = compact ? 36 : (minimal ? 34 : 54);

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

        int displayPops = isPreview ? 2 : TotemPopManager.getPopCount(target.getUUID());
        updateCachedTextComponents(mc, target, hp, displayPops);

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
            if ((textCol >>> 24) > 8) {
                guiGraphics.drawString(font, cachedNameComp, 9, 7, textCol, false);
                int hpW = font.width(cachedHpComp);
                guiGraphics.drawString(font, cachedHpComp, cardW - 9 - hpW, 7, barLeftCol, false);
            }
            drawHealthBar(guiGraphics, 9, 22, cardW - 18, 5, 2, alpha, barMode, barLeftCol, barRightCol);
            guiGraphics.pose().popMatrix();
            return;
        }

        // Player Avatar on left (32x32 for Modern Glass, 24x24 for Compact Pill)
        int avatarSize = compact ? 24 : 32;
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

        if (compact) {
            if ((textCol >>> 24) > 8) {
                guiGraphics.drawString(font, cachedNameComp, infoX, 6, textCol, false);
                int hpW = font.width(cachedHpComp);
                guiGraphics.drawString(font, cachedHpComp, infoRight - hpW, 6, barLeftCol, false);
            }
            if (Boolean.TRUE.equals(totemPopCounter.getValue()) && displayPops > 0 && cachedPopComp != null) {
                drawTotemPopPill(guiGraphics, font, infoRight, 15, alpha);
            }
            drawHealthBar(guiGraphics, infoX, 26, infoRight - infoX, 5, 2, alpha, barMode, barLeftCol, barRightCol);
            guiGraphics.pose().popMatrix();
            return;
        }

        // =====================================================================
        // Modern Glass (165 x 54px)
        // Row 1 (y=7): Target Username (left) + HP Readout & optional WIN/LOSE tag (right)
        // Row 2 (y=20): Armor Row (0.75f icons + Mini Bar/Percentage durability) + Totem Pop Pill (right)
        // Row 3 (y=42): Dual-layer rounded Health Bar
        // =====================================================================
        if ((textCol >>> 24) > 8) {
            guiGraphics.drawString(font, cachedNameComp, infoX, 7, textCol, false);

            int rightCursor = infoRight;
            if (Boolean.TRUE.equals(showStatusTag.getValue())) {
                boolean winning = isPlayerWinning(mc.player, target);
                Component statusComp = winning ? winningComp : losingComp;
                int statusCol = AnimationUtil.withAlpha(winning ? 0xFF00E5FF : 0xFFFF5C77, alpha);
                int sw = font.width(statusComp);
                rightCursor -= sw;
                guiGraphics.drawString(font, statusComp, rightCursor, 7, statusCol, false);
                rightCursor -= 5;
            }
            int hpW = font.width(cachedHpComp);
            if (rightCursor - hpW > infoX + 40) {
                guiGraphics.drawString(font, cachedHpComp, rightCursor - hpW, 7, barLeftCol, false);
            }
        }

        // Row 2: Armor Row (Helmet -> Chestplate -> Leggings -> Boots) + Totem Pop Badge
        boolean armorEnabled = Boolean.TRUE.equals(showArmor.getValue());
        if (armorEnabled) {
            renderArmorRow(guiGraphics, font, target, infoX, 20, alpha, isPreview);
        } else if ((mutedCol >>> 24) > 8) {
            guiGraphics.drawString(font, cachedDistComp, infoX, 23, mutedCol, false);
        }

        if (Boolean.TRUE.equals(totemPopCounter.getValue()) && displayPops > 0 && cachedPopComp != null) {
            drawTotemPopPill(guiGraphics, font, infoRight, 20, alpha);
        } else if (armorEnabled && (mutedCol >>> 24) > 8) {
            int distW = font.width(cachedDistComp);
            guiGraphics.drawString(font, cachedDistComp, infoRight - distW, 23, mutedCol, false);
        }

        // Row 3: Dual-Layer Rounded Health Bar
        drawHealthBar(guiGraphics, infoX, 42, infoRight - infoX, 6, 3, alpha, barMode, barLeftCol, barRightCol);

        guiGraphics.pose().popMatrix();
    }

    private void renderArmorRow(GuiGraphics guiGraphics, Font font, Player target,
                                int startX, int startY, float alpha, boolean isPreview) {
        String durMode = durabilityStyle.getValue();
        boolean showMiniBar = "Mini Bar".equalsIgnoreCase(durMode);
        boolean showPct = "Percentage".equalsIgnoreCase(durMode);

        // In preview mode, if the local player isn't wearing any armor, show cached preview diamond armor
        boolean usePreviewFallback = false;
        if (isPreview) {
            boolean anyWorn = false;
            for (int i = 0; i < ARMOR_SLOTS.length; i++) {
                ItemStack st = target.getItemBySlot(ARMOR_SLOTS[i]);
                if (st != null && !st.isEmpty()) {
                    anyWorn = true;
                    break;
                }
            }
            usePreviewFallback = !anyWorn;
        }

        int cursorX = startX;
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            ItemStack stack = usePreviewFallback ? cachedPreviewArmor[i] : target.getItemBySlot(ARMOR_SLOTS[i]);
            if (stack == null || stack.isEmpty()) {
                continue;
            }

            // Render item icon at 0.75f scale (12x12px)
            guiGraphics.pose().pushMatrix();
            guiGraphics.pose().translate(cursorX, startY);
            guiGraphics.pose().scale(0.75F, 0.75F);
            guiGraphics.renderItem(stack, 0, 0);
            guiGraphics.pose().popMatrix();

            // Render durability indicator directly beneath the 12x12 icon
            if (stack.isDamageableItem() && stack.getMaxDamage() > 0) {
                int maxD = stack.getMaxDamage();
                int remD = Mth.clamp(maxD - stack.getDamageValue(), 0, maxD);
                float ratio = (float) remD / (float) maxD;
                int durCol = AnimationUtil.withAlpha(resolveDurabilityColor(ratio), alpha);

                if (showMiniBar) {
                    int barX = cursorX;
                    int barY = startY + 13;
                    int barW = 12;
                    HudManager.drawRoundedRect(guiGraphics, barX, barY, barW, 2, 1,
                            AnimationUtil.withAlpha(0x66000000, alpha));
                    int fillW = Math.max(2, Math.round(barW * ratio));
                    HudManager.drawRoundedRect(guiGraphics, barX, barY, fillW, 2, 1, durCol);
                } else if (showPct && (durCol >>> 24) > 8) {
                    int pct = Mth.clamp(Math.round(ratio * 100.0F), 0, 100);
                    Component pctComp = PERCENT_COMPS[pct];
                    guiGraphics.pose().pushMatrix();
                    guiGraphics.pose().translate(cursorX, startY + 12.5F);
                    guiGraphics.pose().scale(0.58F, 0.58F);
                    guiGraphics.drawString(font, pctComp, 0, 0, durCol, false);
                    guiGraphics.pose().popMatrix();
                }
            }

            cursorX += 14;
        }
    }

    private void drawTotemPopPill(GuiGraphics guiGraphics, Font font, int rightEdge, int y, float alpha) {
        if (cachedPopComp == null || cachedTotemStack == null) {
            return;
        }
        int textW = font.width(cachedPopComp);
        int iconW = 11;
        int pillW = iconW + textW + 7;
        int pillH = 14;
        int pillX = rightEdge - pillW;

        int pillBg = AnimationUtil.withAlpha(0x33FFAA00, alpha);
        HudManager.drawRoundedRect(guiGraphics, pillX, y, pillW, pillH, 4, pillBg);

        // Mini Totem icon at 0.65f scale (~10x10px)
        guiGraphics.pose().pushMatrix();
        guiGraphics.pose().translate(pillX + 2.0F, y + 2.0F);
        guiGraphics.pose().scale(0.65F, 0.65F);
        guiGraphics.renderItem(cachedTotemStack, 0, 0);
        guiGraphics.pose().popMatrix();

        int badgeTextCol = AnimationUtil.withAlpha(0xFFFFD54F, alpha);
        if ((badgeTextCol >>> 24) > 8) {
            guiGraphics.drawString(font, cachedPopComp, pillX + iconW + 2, y + 3, badgeTextCol, false);
        }
    }

    private static int resolveDurabilityColor(float ratio) {
        if (ratio >= 0.50F) {
            return AnimationUtil.lerpColor(0xFFFACC15, 0xFF00E5FF, (ratio - 0.50F) * 2.0F);
        }
        return AnimationUtil.lerpColor(0xFFEF4444, 0xFFFACC15, Mth.clamp(ratio * 2.0F, 0.0F, 1.0F));
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

    private void updateCachedTextComponents(Minecraft mc, Player target, float hp, int displayPops) {
        if (winningComp == null) {
            winningComp = Component.literal("WIN").withStyle(HudManager.getBoldStyle());
            losingComp = Component.literal("LOSE").withStyle(HudManager.getBoldStyle());
        }

        String rawName = target.getName().getString();
        String safeName = NameProtectModule.isActive() ? NameProtectModule.protect(rawName) : rawName;
        if (!safeName.equals(cachedName) || cachedNameComp == null) {
            cachedName = safeName;
            String clipped = safeName.length() > 11 ? safeName.substring(0, 11) : safeName;
            cachedNameComp = Component.literal(clipped).withStyle(HudManager.getBoldStyle());
        }

        int hpTenths = Math.round(hp * 10.0F);
        if (hpTenths != cachedHpTenths || cachedHpComp == null) {
            cachedHpTenths = hpTenths;
            cachedHpComp = Component.literal((hpTenths / 10) + "." + (hpTenths % 10))
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

        if (displayPops != cachedPopCount) {
            cachedPopCount = displayPops;
            cachedPopComp = displayPops > 0
                    ? Component.literal("\u00D7" + displayPops).withStyle(HudManager.getBoldStyle())
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
