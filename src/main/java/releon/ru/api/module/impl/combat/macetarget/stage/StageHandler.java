package releon.ru.api.module.impl.combat.macetarget.stage;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import releon.ru.api.module.impl.combat.macetarget.armor.ArmorSwapHandler;
import releon.ru.api.module.impl.combat.macetarget.armor.FireworkHandler;
import releon.ru.api.module.impl.combat.macetarget.attack.AttackHandler;
import releon.ru.api.module.impl.combat.macetarget.state.MaceState.Stage;
import releon.ru.api.module.impl.combat.aura.util.StopWatch;
import releon.ru.utils.inventory.lookup.InventoryUtils;

public class StageHandler {
    private static final Minecraft MC = Minecraft.getInstance();
    private final ArmorSwapHandler armorSwapHandler;
    private final FireworkHandler fireworkHandler;
    private final AttackHandler attackHandler;
    private final StopWatch fireworkTimer;
    private Stage stage = Stage.PREPARE;
    private boolean silentMode = true;
    private boolean reallyWorldMode;
    private float height = 30.0F;

    public StageHandler(ArmorSwapHandler armorSwapHandler, FireworkHandler fireworkHandler, AttackHandler attackHandler, StopWatch fireworkTimer) {
        this.armorSwapHandler = armorSwapHandler;
        this.fireworkHandler = fireworkHandler;
        this.attackHandler = attackHandler;
        this.fireworkTimer = fireworkTimer;
    }

    public Stage getStage() {
        return stage;
    }

    public void setSilentMode(boolean silentMode) {
        this.silentMode = silentMode;
    }

    public void setReallyWorldMode(boolean reallyWorldMode) {
        this.reallyWorldMode = reallyWorldMode;
    }

    public void setHeight(float height) {
        this.height = height;
    }

    public void handlePrepare(boolean hasElytra) {
        if (!hasElytra) {
            int slot = InventoryUtils.findElytraSlot();
            if (slot != -1) {
                armorSwapHandler.startSwap(slot, silentMode);
            }
            return;
        }
        stage = Stage.FLYING_UP;
        fireworkTimer.reset();
    }

    public void handleFlyingUp(LivingEntity target, boolean hasElytra) {
        if (MC.player == null) {
            return;
        }
        if (!hasElytra) {
            stage = Stage.PREPARE;
            return;
        }
        if (MC.player.isFallFlying() && fireworkTimer.finished(300)) {
            fireworkHandler.useFirework(silentMode);
            fireworkTimer.reset();
        }
        if (MC.player.getY() - target.getY() >= height) {
            stage = Stage.TARGETTING;
        }
    }

    public void handleTargetting(LivingEntity target) {
        if (MC.player == null) {
            return;
        }
        if (InventoryUtils.hasElytra() && MC.player.distanceTo(target) < 12.0F && !armorSwapHandler.isActive()) {
            int slot = InventoryUtils.findChestArmorSlot();
            if (slot != -1) {
                armorSwapHandler.startSwap(slot, silentMode);
            }
        }
        if (MC.player.distanceTo(target) < 16.0F) {
            stage = Stage.ATTACKING;
        }
    }

    public void handleAttacking(LivingEntity target, boolean hasElytra) {
        if (MC.player == null) {
            return;
        }
        if (hasElytra && !armorSwapHandler.isActive()) {
            int slot = InventoryUtils.findChestArmorSlot();
            if (slot != -1) {
                armorSwapHandler.startSwap(slot, silentMode);
            }
            return;
        }
        if (!hasElytra && !armorSwapHandler.isActive() && MC.player.distanceTo(target) < 5.0F) {
            attackHandler.setPendingAttack(true);
            if (reallyWorldMode) {
                attackHandler.setShouldDisableAfterAttack(true);
            } else {
                stage = Stage.FLYING_UP;
                fireworkTimer.reset();
            }
        }
    }

    public void reset() {
        stage = Stage.PREPARE;
    }
}
