package releon.ru.api.module.impl.movement;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.level.block.Blocks;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.events.impl.UsingItemEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.utils.inventory.interaction.PlayerInteractionHelper;
import releon.ru.utils.inventory.script.Script;

public final class NoSlow extends Module {
    private final ModeSetting itemMode = register(new ModeSetting("Item Mode", "Item slowdown bypass mode.", "ReallyWorld", "Old Grim", "ReallyWorld", "FunTime", "SpookyTime", "FunTimeCrossBow"));
    private final Script script = new Script();
    private int ticks;
    private int cycleCounter;
    private boolean forcedSprintDuringEat;
    private boolean sprintWasPressedBeforeEat;

    public NoSlow() {
        super("No Slow", "Prevents item-use movement slowdown.", ModuleCategory.MOVEMENT);
    }

    @Override
    protected void onDisable() {
        resetEatSprintBoostIfNeeded();
        ticks = 0;
        cycleCounter = 0;
        script.cleanup();
    }

    @SubscribeEvent
    private void onUpdate(TickEvent event) {
        Minecraft client = event.getClient();
        if (client.player == null) {
            return;
        }

        if (itemMode.is("ReallyWorld") || itemMode.is("SpookyTime")) {
            if (!client.player.isAutoSpinAttack()) {
                if (client.player.isUsingItem()) {
                    ticks++;
                } else {
                    ticks = 0;
                    cycleCounter = 0;
                }
            }
            updateEatSprintBoost(client);
        } else {
            if (client.player.getUsedItemHand() == InteractionHand.MAIN_HAND || client.player.getUsedItemHand() == InteractionHand.OFF_HAND) {
                ticks++;
            } else {
                ticks = 0;
            }
            resetEatSprintBoostIfNeeded();
        }
    }

    @SubscribeEvent
    private void onUsingItem(UsingItemEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        if (event.getType() == UsingItemEvent.POST) {
            while (!script.isFinished()) {
                script.update();
            }
            return;
        }

        InteractionHand first = client.player.getUsedItemHand();
        InteractionHand second = first == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        handleItemUse(client, event, first, second);
    }

    private void handleItemUse(Minecraft client, UsingItemEvent event, InteractionHand first, InteractionHand second) {
        if (itemMode.is("Old Grim")) {
            if (client.player.getOffhandItem().getUseAnimation() == ItemUseAnimation.NONE
                    || client.player.getMainHandItem().getUseAnimation() == ItemUseAnimation.NONE) {
                PlayerInteractionHelper.interactItem(first);
                PlayerInteractionHelper.interactItem(second);
                event.setCancelled(true);
            }
            return;
        }

        if (itemMode.is("ReallyWorld")) {
            int[] thresholds = client.player.isJumping() ? new int[]{5, 5, 4} : new int[]{5, 5, 4};
            int threshold = thresholds[cycleCounter % thresholds.length];
            if (ticks >= threshold) {
                event.setCancelled(true);
                ticks = 0;
                cycleCounter++;
            }
            return;
        }

        if (itemMode.is("SpookyTime")) {
            int[] thresholds = new int[]{2, 2, 3};
            int threshold = thresholds[cycleCounter % 2];
            if (ticks >= threshold) {
                event.setCancelled(true);
                ticks = 0;
                cycleCounter++;
            }
            return;
        }
        if (itemMode.is("FunTimeCrossBow")) {
            boolean mainHandCrossbow = client.player.getMainHandItem().getItem() instanceof CrossbowItem;
            boolean offHandCrossbow = client.player.getOffhandItem().getItem() instanceof CrossbowItem;
            if (mainHandCrossbow || offHandCrossbow) {
                int[] thresholds = new int[]{1, 1, 1};
                int threshold = thresholds[cycleCounter % 1];
                if (ticks >= threshold) {
                    event.setCancelled(true);
                    ticks = 0;
                    cycleCounter++;
                }
                return;
            }
        }
        if (itemMode.is("FunTime") && ticks > 0 && client.player.getTicksUsingItem() > 1) {
            boolean mainHandCrossbow = client.player.getMainHandItem().getItem() instanceof CrossbowItem;
            boolean offHandCrossbow = client.player.getOffhandItem().getItem() instanceof CrossbowItem;
            if (mainHandCrossbow || offHandCrossbow) {
                event.setCancelled(true);
                ticks = 0;
            } else if (client.player.onGround() && isOnSnowOrCarpet(client)) {
                client.player.connection.send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                        client.player.blockPosition().above(),
                        Direction.DOWN
                ));
                event.setCancelled(true);
                ticks = 0;
            }
        }
    }

    private boolean isOnSnowOrCarpet(Minecraft client) {
        if (client.player == null || client.level == null) {
            return false;
        }
        BlockPos playerPos = client.player.blockPosition();
        var block = client.level.getBlockState(playerPos).getBlock();
        return block == Blocks.SNOW
                || block == Blocks.WHITE_CARPET
                || block == Blocks.ORANGE_CARPET
                || block == Blocks.MAGENTA_CARPET
                || block == Blocks.LIGHT_BLUE_CARPET
                || block == Blocks.YELLOW_CARPET
                || block == Blocks.LIME_CARPET
                || block == Blocks.PINK_CARPET
                || block == Blocks.GRAY_CARPET
                || block == Blocks.LIGHT_GRAY_CARPET
                || block == Blocks.CYAN_CARPET
                || block == Blocks.PURPLE_CARPET
                || block == Blocks.BLUE_CARPET
                || block == Blocks.BROWN_CARPET
                || block == Blocks.GREEN_CARPET
                || block == Blocks.RED_CARPET
                || block == Blocks.BLACK_CARPET;
    }

    private void updateEatSprintBoost(Minecraft client) {
        if (client.player == null || client.options == null) {
            return;
        }
        boolean eating = client.player.isUsingItem() && client.player.getUseItem().getUseAnimation() == ItemUseAnimation.EAT;
        if (eating) {
            if (!forcedSprintDuringEat) {
                sprintWasPressedBeforeEat = client.options.keySprint.isDown();
                forcedSprintDuringEat = true;
            }
            client.options.keySprint.setDown(true);
            client.player.setSprinting(true);
            return;
        }
        resetEatSprintBoostIfNeeded();
    }

    private void resetEatSprintBoostIfNeeded() {
        Minecraft client = Minecraft.getInstance();
        if (!forcedSprintDuringEat || client.options == null) {
            return;
        }
        if (!sprintWasPressedBeforeEat) {
            client.options.keySprint.setDown(false);
        }
        forcedSprintDuringEat = false;
        sprintWasPressedBeforeEat = false;
    }
}
