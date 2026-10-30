package releon.ru.api.module.impl.combat.macetarget.attack;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import releon.ru.utils.inventory.lookup.InventoryUtils;

public class AttackHandler {
    private static final Minecraft MC = Minecraft.getInstance();
    private boolean pendingAttack;
    private boolean shouldDisableAfterAttack;

    public boolean isPendingAttack() {
        return pendingAttack;
    }

    public void setPendingAttack(boolean pendingAttack) {
        this.pendingAttack = pendingAttack;
    }

    public boolean isShouldDisableAfterAttack() {
        return shouldDisableAfterAttack;
    }

    public void setShouldDisableAfterAttack(boolean shouldDisableAfterAttack) {
        this.shouldDisableAfterAttack = shouldDisableAfterAttack;
    }

    public void performAttack(LivingEntity target) {
        if (MC.player == null || MC.gameMode == null || target == null) {
            return;
        }
        int maceSlot = InventoryUtils.findHotbarItem(Items.MACE);
        int prevSlot = MC.player.getInventory().getSelectedSlot();
        if (maceSlot != -1 && maceSlot != prevSlot && MC.getConnection() != null) {
            MC.getConnection().send(new ServerboundSetCarriedItemPacket(maceSlot));
        }
        MC.gameMode.attack(MC.player, target);
        MC.player.swing(InteractionHand.MAIN_HAND);
        if (maceSlot != -1 && maceSlot != prevSlot && MC.getConnection() != null) {
            MC.getConnection().send(new ServerboundSetCarriedItemPacket(prevSlot));
        }
    }

    public void reset() {
        pendingAttack = false;
        shouldDisableAfterAttack = false;
    }
}
