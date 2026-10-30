package releon.ru.api.module.impl.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.NumberSetting;

public class AutoGApple extends Module {
    private final NumberSetting healthThreshold = register(new NumberSetting("Health", "Health threshold.", 16.0, 3.0, 20.0, 0.5));
    private final BooleanSetting smartMode = register(new BooleanSetting("Smart", "Use hotbar apples, otherwise offhand only.", true));
    private final BooleanSetting goldenHearts = register(new BooleanSetting("Golden Hearts", "Count absorption hearts.", true));
    private final BooleanSetting returnSlot = register(new BooleanSetting("Return Slot", "Return previous slot after eating.", true));

    private boolean eating;
    private int previousSlot = -1;

    public AutoGApple() {
        super("Auto GApple", "Automatically eats golden apples.", ModuleCategory.COMBAT);
    }

    @Override
    public void onTick(Minecraft client) {
        if (client.player == null || client.level == null) {
            return;
        }
        handleEating(client);
    }

    @Override
    protected void onEnable() {
        eating = false;
        previousSlot = -1;
    }

    @Override
    protected void onDisable() {
        stopEating(Minecraft.getInstance());
    }

    private void handleEating(Minecraft client) {
        if (canEat(client)) {
            if (smartMode.getValue() && !hasGappleInHand(client)) {
                swapToGappleSlot(client);
            }
            startEating(client);
        } else if (eating && !shouldContinueEating(client)) {
            stopEating(client);
        }
    }

    private boolean canEat(Minecraft client) {
        if (client.player.isDeadOrDying() || client.player.getCooldowns().isOnCooldown(Items.GOLDEN_APPLE.getDefaultInstance())) {
            return false;
        }
        if (smartMode.getValue() ? !hasGapple(client) : !hasGappleInOffhand(client)) {
            return false;
        }
        float health = client.player.getHealth();
        if (goldenHearts.getValue()) {
            health += client.player.getAbsorptionAmount();
        }
        return health <= healthThreshold.getFloat();
    }

    private boolean shouldContinueEating(Minecraft client) {
        return !client.player.isDeadOrDying()
                && client.player.isUsingItem()
                && client.player.getUseItem().getItem() == Items.GOLDEN_APPLE;
    }

    private boolean hasGappleInHand(Minecraft client) {
        return client.player.getMainHandItem().getItem() == Items.GOLDEN_APPLE;
    }

    private boolean hasGappleInOffhand(Minecraft client) {
        return client.player.getOffhandItem().getItem() == Items.GOLDEN_APPLE;
    }

    private boolean hasGapple(Minecraft client) {
        return hasGappleInOffhand(client) || findGappleInHotbar(client) != -1;
    }

    private int findGappleInHotbar(Minecraft client) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = client.player.getInventory().getItem(i);
            if (stack.getItem() == Items.GOLDEN_APPLE) {
                return i;
            }
        }
        return -1;
    }

    private void swapToGappleSlot(Minecraft client) {
        int slot = findGappleInHotbar(client);
        int current = client.player.getInventory().getSelectedSlot();
        if (slot != -1 && current != slot) {
            if (previousSlot == -1 && returnSlot.getValue()) {
                previousSlot = current;
            }
            client.player.getInventory().setSelectedSlot(slot);
        }
    }

    private void startEating(Minecraft client) {
        if (!eating && !client.options.keyUse.isDown()) {
            client.options.keyUse.setDown(true);
            eating = true;
        }
    }

    private void stopEating(Minecraft client) {
        if (!eating) {
            return;
        }
        client.options.keyUse.setDown(false);
        eating = false;
        if (client.player != null && previousSlot != -1 && returnSlot.getValue()) {
            client.player.getInventory().setSelectedSlot(previousSlot);
            previousSlot = -1;
        }
    }
}
