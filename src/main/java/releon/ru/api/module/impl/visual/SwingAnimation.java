package releon.ru.api.module.impl.visual;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.HandAnimationEvent;
import releon.ru.api.events.impl.SwingDurationEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.AuraModule;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.NumberSetting;

public class SwingAnimation extends Module {
    private final ModeSetting swingType = register(new ModeSetting("Type", "Swing animation type.", "Chop",
            "Chop", "Swipe", "Down", "Smooth", "Smooth 2", "Power", "Feast", "Twist", "Stab", "Helix", "Default"));
    private final NumberSetting hitStrength = register(new NumberSetting("Strength", "Swing animation strength.", 1.0, 0.5, 3.0, 0.05));
    private final NumberSetting swingSpeed = register(new NumberSetting("Duration", "Swing animation duration multiplier.", 1.0, 0.5, 4.0, 0.05));
    private final BooleanSetting onlySwing = register(new BooleanSetting("Only Swing", "Animate only while the player swings.", false));
    private final BooleanSetting onlyAura = register(new BooleanSetting("Only Aura", "Animate only while Aura has a target.", false));

    public SwingAnimation() {
        super("Swing Animation", "Custom first-person swing animations.", ModuleCategory.VISUAL);
    }

    @SubscribeEvent
    private void onSwingDuration(SwingDurationEvent event) {
        if (isBlockedForSwingAnimation(InteractionHand.MAIN_HAND) || isBlockedForSwingAnimation(InteractionHand.OFF_HAND)) {
            return;
        }
        if (passesAuraFilter()) {
            event.setAnimation(swingSpeed.getFloat());
            event.cancel();
        }
    }

    @SubscribeEvent
    private void onHandAnimation(HandAnimationEvent event) {
        if (mc.player == null || event.getHand() != InteractionHand.MAIN_HAND || isBlockedForSwingAnimation(event.getHand()) || !passesAuraFilter()) {
            return;
        }
        PoseStack matrices = event.getMatrices();
        float swingProgress = event.getSwingProgress();
        int armSide = mc.player.getMainArm() == HumanoidArm.RIGHT ? 1 : -1;
        float sin1 = Mth.sin(swingProgress * swingProgress * Mth.PI);
        float sin2 = Mth.sin(Mth.sqrt(swingProgress) * Mth.PI);
        float sinSmooth = (float) (Math.sin(swingProgress * Math.PI) * 0.5F);
        float strength = hitStrength.getFloat();

        if (onlySwing.getValue() && mc.player.swingTime == 0) {
            matrices.translate(armSide * 0.56F, -0.52F, -0.72F);
        } else if (swingType.is("Chop")) {
            matrices.translate(0.56F * armSide, -0.44F, -0.72F);
            matrices.translate(0.0F, 0.33F * -0.6F, 0.0F);
            matrices.mulPose(Axis.YP.rotationDegrees(45.0F * armSide));
            matrices.mulPose(Axis.ZP.rotationDegrees(sin2 * -20.0F * armSide * strength));
            matrices.mulPose(Axis.XP.rotationDegrees(sin2 * -80.0F * strength));
            matrices.translate(0.4F, 0.2F, 0.2F);
            matrices.translate(-0.5F, 0.08F, 0.0F);
            matrices.mulPose(Axis.YP.rotationDegrees(20.0F));
            matrices.mulPose(Axis.XP.rotationDegrees(-80.0F));
            matrices.mulPose(Axis.YP.rotationDegrees(20.0F));
        } else if (swingType.is("Twist")) {
            matrices.translate(armSide * 0.56F, -0.36F, -0.72F);
            matrices.mulPose(Axis.YP.rotationDegrees(80.0F * armSide));
            matrices.mulPose(Axis.XP.rotationDegrees(sin2 * -90.0F * strength));
            matrices.mulPose(Axis.ZP.rotationDegrees((sin1 - sin2) * 60.0F * armSide * strength));
            matrices.mulPose(Axis.XP.rotationDegrees(-30.0F));
            matrices.translate(0.0F, -0.1F, 0.05F);
        } else if (swingType.is("Swipe")) {
            matrices.translate(0.56F * armSide, -0.32F, -0.72F);
            matrices.mulPose(Axis.YP.rotationDegrees(70.0F * armSide));
            matrices.mulPose(Axis.ZP.rotationDegrees(-20.0F * armSide));
            matrices.mulPose(Axis.YP.rotationDegrees((sin2 * sin1) * -5.0F * strength));
            matrices.mulPose(Axis.XP.rotationDegrees((sin2 * sin1) * -120.0F * strength));
            matrices.mulPose(Axis.XP.rotationDegrees(-70.0F));
        } else if (swingType.is("Default")) {
            matrices.translate(armSide * 0.56F, -0.52F - sin2 * 0.5F * strength, -0.72F);
            matrices.mulPose(Axis.YP.rotationDegrees(45.0F * armSide));
            matrices.mulPose(Axis.YP.rotationDegrees(-45.0F * armSide));
        } else if (swingType.is("Down")) {
            matrices.translate(armSide * 0.56F, -0.32F, -0.72F);
            matrices.mulPose(Axis.YP.rotationDegrees(76.0F * armSide));
            matrices.mulPose(Axis.YP.rotationDegrees(sin2 * -5.0F * strength));
            matrices.mulPose(Axis.XN.rotationDegrees(sin2 * -100.0F * strength));
            matrices.mulPose(Axis.XP.rotationDegrees(sin2 * -155.0F * strength));
            matrices.mulPose(Axis.XP.rotationDegrees(-100.0F));
        } else if (swingType.is("Smooth")) {
            matrices.translate(armSide * 0.56F, -0.42F, -0.72F);
            matrices.mulPose(Axis.YP.rotationDegrees(armSide * (45.0F + sin1 * -20.0F * strength)));
            matrices.mulPose(Axis.ZP.rotationDegrees(armSide * sin2 * -20.0F * strength));
            matrices.mulPose(Axis.XP.rotationDegrees(sin2 * -80.0F * strength));
            matrices.mulPose(Axis.YP.rotationDegrees(armSide * -45.0F));
            matrices.translate(0.0F, -0.1F, 0.0F);
        } else if (swingType.is("Smooth 2")) {
            matrices.translate(armSide * 0.56F, -0.42F, -0.72F);
            matrices.mulPose(Axis.XP.rotationDegrees(sin2 * -80.0F * strength));
            matrices.translate(0.0F, -0.1F, 0.0F);
        } else if (swingType.is("Power")) {
            matrices.translate(armSide * 0.56F, -0.32F, -0.72F);
            matrices.translate((-sinSmooth * sinSmooth * sin1) * armSide * strength, 0.0F, 0.0F);
            matrices.mulPose(Axis.YP.rotationDegrees(61.0F * armSide));
            matrices.mulPose(Axis.ZP.rotationDegrees(sin2 * strength));
            matrices.mulPose(Axis.YP.rotationDegrees((sin2 * sin1) * -5.0F * strength));
            matrices.mulPose(Axis.XP.rotationDegrees((sin2 * sin1) * -30.0F * strength));
            matrices.mulPose(Axis.XP.rotationDegrees(-60.0F));
            matrices.mulPose(Axis.XP.rotationDegrees(sinSmooth * -60.0F * strength));
        } else if (swingType.is("Feast")) {
            matrices.translate(armSide * 0.56F, -0.32F, -0.72F);
            matrices.mulPose(Axis.YP.rotationDegrees(30.0F * armSide));
            matrices.mulPose(Axis.YP.rotationDegrees(sin2 * 75.0F * armSide * strength));
            matrices.mulPose(Axis.XP.rotationDegrees(sin2 * -45.0F * strength));
            matrices.mulPose(Axis.YP.rotationDegrees(30.0F * armSide));
            matrices.mulPose(Axis.XP.rotationDegrees(-80.0F));
            matrices.mulPose(Axis.YP.rotationDegrees(35.0F * armSide));
        } else if (swingType.is("Stab")) {
            matrices.translate(armSide * 0.66F, -0.32F, -0.92F);
            matrices.mulPose(Axis.YP.rotationDegrees(25 * armSide));
            matrices.mulPose(Axis.YP.rotationDegrees(sin2 * 90 * armSide * strength));
            matrices.mulPose(Axis.XP.rotationDegrees(sin2 * -15 * strength));
            matrices.mulPose(Axis.YP.rotationDegrees(50 * armSide));
            matrices.mulPose(Axis.XP.rotationDegrees(-90));
            matrices.mulPose(Axis.YP.rotationDegrees(70 * armSide));
            matrices.mulPose(Axis.ZP.rotationDegrees(2 * armSide));

        } else if (swingType.is("Helix")) {

            matrices.translate(armSide * 0.66F, -0.32F, -0.92F);

            matrices.mulPose(Axis.YP.rotationDegrees(35 * armSide));
            matrices.mulPose(Axis.YP.rotationDegrees(sin2 * 15 * armSide * strength));
            matrices.mulPose(Axis.XP.rotationDegrees(sin2 * -55 * strength));
            matrices.mulPose(Axis.YP.rotationDegrees(50 * armSide));
            matrices.mulPose(Axis.XP.rotationDegrees(-90));
            matrices.mulPose(Axis.YP.rotationDegrees(70 * armSide));
            matrices.mulPose(Axis.ZP.rotationDegrees(2 * armSide));
        }

        event.cancel();
    }
    private boolean passesAuraFilter() {
        AuraModule aura = AuraModule.getInstance();
        return !onlyAura.getValue() || aura != null && aura.isEnabled() && AuraModule.target != null;
    }

    private boolean isBlockedForSwingAnimation(InteractionHand hand) {
        return mc.player != null && isExcludedSwingItem(mc.player.getItemInHand(hand));
    }

    private boolean isExcludedSwingItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof CrossbowItem || stack.getItem() instanceof TridentItem) {
            return true;
        }
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return path.contains("spear");
    }
}
