package releon.ru.api.module.impl.combat.aura.attack;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import releon.ru.api.module.impl.combat.AuraModule;

public class Pressing {
    private final int[] funTimeTicks = new int[]{10, 11, 10, 12};
    private final int[] spookyTicks = new int[]{10, 11, 12, 13, 12};
    private final int[] defaultTicks = new int[]{10, 11};
    private long lastClickTime = System.currentTimeMillis();

    public boolean isCooldownComplete(boolean dynamicCooldown, float ticks) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) {
            return false;
        }
        boolean mace = isHoldingMace(client);
        float cooldownTicks = ticks;
        float itemCooldownTicks = Math.max(1.0F, client.player.getCurrentItemAttackStrengthDelay());
        if (dynamicCooldown) {
            float tps = Math.max(1.0F, AuraModule.activeTps());
            float tpsFactor = 20.0F / tps;
            cooldownTicks *= tpsFactor;
            itemCooldownTicks *= tpsFactor;
        }

        long delay = Math.max(0L, Math.round(Math.max(0.0F, itemCooldownTicks * 0.82F - cooldownTicks) * 50.0F));
        boolean cooldownReady = mace || client.player.getAttackStrengthScale(cooldownTicks) > 0.82F;
        return cooldownReady && lastClickPassed() >= delay && hasModeTicksElapsedSinceLastClick();
    }

    public boolean hasTicksElapsedSinceLastClick(int ticks) {
        float tps = Math.max(1.0F, AuraModule.activeTps());
        return lastClickPassed() >= (ticks * 50L * (20F / tps));
    }

    public long lastClickPassed() {
        return System.currentTimeMillis() - lastClickTime;
    }

    public void recalculate() {
        lastClickTime = System.currentTimeMillis();
    }

    private boolean hasModeTicksElapsedSinceLastClick() {
        AuraModule aura = AuraModule.getInstance();
        int count = aura == null ? 0 : aura.getAttackHandlerRaw().getCount();
        if (aura != null && aura.isEnabled()) {
            if ("FunTime".equals(aura.getMode().getValue())) {
                return hasTicksElapsedSinceLastClick(Math.max(1, funTimeTicks[count % funTimeTicks.length] - 1));
            }
            if ("SpookyTime".equals(aura.getMode().getValue())) {
                return hasTicksElapsedSinceLastClick(Math.max(1, spookyTicks[count % spookyTicks.length] - 1));
            }
        }
        return hasTicksElapsedSinceLastClick(Math.max(1, defaultTicks[count % defaultTicks.length] - 1));
    }

    private boolean isHoldingMace(Minecraft client) {
        ItemStack mainHand = client.player.getMainHandItem();
        return mainHand.getItem().getDescriptionId().toLowerCase().contains("mace");
    }
}
