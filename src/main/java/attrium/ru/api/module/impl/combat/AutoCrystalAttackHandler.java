package attrium.ru.api.module.impl.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

public class AutoCrystalAttackHandler implements ServerboundInteractPacket.Handler {
    private final AutoCrystal module;

    public AutoCrystalAttackHandler(AutoCrystal module) {
        this.module = module;
    }

    @Override
    public void onInteraction(InteractionHand hand) {
    }

    @Override
    public void onInteraction(InteractionHand hand, Vec3 pos) {
    }

    @Override
    public void onAttack() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || module == null || !module.isEnabled()) {
            return;
        }

        Entity entity = null;
        HitResult hit = mc.hitResult;
        if (hit instanceof EntityHitResult entityHit && hit.getType() == HitResult.Type.ENTITY) {
            entity = entityHit.getEntity();
        } else if (mc.crosshairPickEntity != null) {
            entity = mc.crosshairPickEntity;
        }

        if (entity == null || !entity.isAlive()) {
            return;
        }

        if (!(entity instanceof EndCrystal)) {
            if (module.isHitCrystalEnabled()) {
                AutoCrystal.notifyMeleeHitEntity(entity);
            }
            return;
        }

        if (!module.isCrystalOptimizerEnabled()) {
            return;
        }
        EndCrystal crystal = (EndCrystal) entity;

        int weaknessAmp = getActiveEffectAmplifier(mc, "weakness");
        int strengthAmp = getActiveEffectAmplifier(mc, "strength");
        if (strengthAmp < 0) {
            strengthAmp = getActiveEffectAmplifier(mc, "damage_boost");
        }

        boolean weakened = weaknessAmp >= 0 && (strengthAmp < 0 || strengthAmp <= weaknessAmp);
        if (weakened && !isToolOrWeapon(mc.player.getMainHandItem())) {
            return;
        }

        crystal.discard();
        crystal.setRemoved(Entity.RemovalReason.KILLED);
        crystal.onClientRemoval();
    }

    private static int getActiveEffectAmplifier(Minecraft mc, String keyword) {
        if (mc.player == null || keyword == null) {
            return -1;
        }
        for (MobEffectInstance inst : mc.player.getActiveEffects()) {
            if (inst != null && inst.getDescriptionId().toLowerCase(Locale.ROOT).contains(keyword)) {
                return inst.getAmplifier();
            }
        }
        return -1;
    }

    private static boolean isToolOrWeapon(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getItem() instanceof HoeItem) {
            return false;
        }
        return stack.has(DataComponents.TOOL)
                || stack.is(ItemTags.SWORDS)
                || stack.is(ItemTags.AXES)
                || stack.is(ItemTags.PICKAXES)
                || stack.getItem() instanceof AxeItem
                || stack.getItem() instanceof MaceItem
                || stack.getItem() instanceof TridentItem;
    }
}
