package releon.ru.api.module.impl.misc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.aura.util.StopWatch;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.api.settings.impl.StringSetting;
import releon.ru.utils.network.Network;

import java.util.Locale;

public final class AutoLes extends Module {
    private static final int SEARCH_RANGE = 6;
    private static final double SEARCH_RANGE_SQUARED = SEARCH_RANGE * SEARCH_RANGE;
    private static final int STOP_PACKETS_PER_TICK = 3;
    private static final double MILLIS_PER_MINUTE = 60000.0D;

    private final StopWatch sellWatch = new StopWatch();
    private final StopWatch payWatch = new StopWatch();
    private final StopWatch restartWatch = new StopWatch();

    private final BooleanSetting autoPay = register(new BooleanSetting("Auto Pay", "Send money.", false));
    private final StringSetting payName = register(new StringSetting("Name", "Pay target nickname.", "nick", 32));
    private final NumberSetting payAmount = register(new NumberSetting("Amount", "Pay amount.", 1000.0D, 500.0D, 25000.0D, 100.0D));
    private final NumberSetting delay = register(new NumberSetting("Delay", "Delay in seconds.", 20.0D, 1.0D, 60.0D, 1.0D));
    private final BooleanSetting restart = register(new BooleanSetting("Restart", "Restart module by timer.", false));
    private final NumberSetting restartDelay = register(new NumberSetting("Restart Time", "Restart delay in minutes.", 5.0D, 1.0D, 30.0D, 1.0D));

    private BlockPos currentTarget;
    private BlockPos breakingTarget;

    public AutoLes() {
        super("Auto Les", "Automates routines on /warp les.", ModuleCategory.MISC);
        payName.visibleWhen(autoPay::getValue);
        payAmount.visibleWhen(autoPay::getValue);
        delay.visibleWhen(autoPay::getValue);
        restartDelay.visibleWhen(restart::getValue);
    }

    @Override
    public void onTick(Minecraft client) {
        if (!Network.isReallyWorld()) {
            setEnabled(false);
            return;
        }

        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        ClientPacketListener connection = client.getConnection();
        if (player == null || level == null || connection == null) {
            resetBreakState();
            return;
        }

        runRestartTimer();
        if (!isEnabled()) {
            return;
        }
        runSell(player);
        runPay(player);
        runNuker(player, level, connection);
    }

    @Override
    protected void onEnable() {
        Minecraft client = Minecraft.getInstance();
        if (!Network.isReallyWorld() || client.player == null || client.level == null || client.getConnection() == null) {
            setEnabled(false);
            return;
        }
        resetTimers();
        resetBreakState();
    }

    @Override
    protected void onDisable() {
        resetTimers();
        resetBreakState();
    }

    private void runRestartTimer() {
        if (!restart.getValue()) {
            restartWatch.reset();
            return;
        }
        if (restartWatch.finished(restartDelay.getValue() * MILLIS_PER_MINUTE)) {
            restartModule();
        }
    }

    private void runSell(LocalPlayer player) {
        if (sellWatch.finished(70500.0D)) {
            player.connection.sendCommand("sellwood");
            sellWatch.reset();
        }
    }

    private void runPay(LocalPlayer player) {
        if (!autoPay.getValue()) {
            return;
        }

        String targetName = payName.getValue() == null ? "" : payName.getValue().trim();
        if (targetName.isEmpty()) {
            return;
        }
        if (payWatch.finished(delay.getValue() * 500.0D + 200.0D)) {
            player.connection.sendCommand("pay " + targetName + " " + payAmount.getValue().intValue());
            payWatch.reset();
        }
    }

    private void runNuker(LocalPlayer player, ClientLevel level, ClientPacketListener connection) {
        BlockPos target = resolveTarget(player.blockPosition(), level);
        if (target == null) {
            currentTarget = null;
            clearBreakingState();
            return;
        }

        if (!target.equals(currentTarget)) {
            clearBreakingState();
            currentTarget = target;
        }

        BlockPos activeTarget = currentTarget;
        if (activeTarget == null || !isValidWoodTarget(activeTarget, level)) {
            currentTarget = null;
            clearBreakingState();
            return;
        }

        if (!activeTarget.equals(breakingTarget)) {
            sendBreakPacket(connection, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, activeTarget);
            breakingTarget = activeTarget;
        }

        for (int i = 0; i < STOP_PACKETS_PER_TICK; i++) {
            if (level.getBlockState(activeTarget).isAir()) {
                currentTarget = null;
                clearBreakingState(false);
                return;
            }
            sendBreakPacket(connection, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, activeTarget);
        }

        if (level.getBlockState(activeTarget).isAir()) {
            currentTarget = null;
            clearBreakingState(false);
            return;
        }

        player.swing(InteractionHand.MAIN_HAND);
    }

    private BlockPos resolveTarget(BlockPos playerPos, ClientLevel level) {
        if (currentTarget != null && isValidWoodTarget(currentTarget, level)) {
            return currentTarget;
        }
        return findNearestWood(playerPos, level);
    }

    private BlockPos findNearestWood(BlockPos playerPos, ClientLevel level) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return null;
        }

        BlockPos closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (int x = -SEARCH_RANGE; x <= SEARCH_RANGE; x++) {
            for (int y = -SEARCH_RANGE; y <= SEARCH_RANGE; y++) {
                for (int z = -SEARCH_RANGE; z <= SEARCH_RANGE; z++) {
                    BlockPos position = playerPos.offset(x, y, z);
                    if (!isValidWoodTarget(position, level)) {
                        continue;
                    }

                    double distance = player.distanceToSqr(Vec3.atCenterOf(position));
                    if (distance < closestDistance) {
                        closest = position;
                        closestDistance = distance;
                    }
                }
            }
        }
        return closest;
    }

    private boolean isValidWoodTarget(BlockPos position, ClientLevel level) {
        if (!isWithinRange(position)) {
            return false;
        }

        BlockState state = level.getBlockState(position);
        return isWoodState(state) && canBreak(state, level, position);
    }

    private boolean isWoodState(BlockState state) {
        String name = state.getBlock().getDescriptionId().toLowerCase(Locale.ROOT);
        return name.contains("log") || name.contains("wood") || name.contains("stem") || state.is(BlockTags.LOGS);
    }

    private boolean canBreak(BlockState state, ClientLevel level, BlockPos position) {
        return !state.isAir() && state.getDestroySpeed(level, position) >= 0.0F;
    }

    private void sendBreakPacket(ClientPacketListener connection, ServerboundPlayerActionPacket.Action action, BlockPos target) {
        connection.send(new ServerboundPlayerActionPacket(action, target, Direction.UP));
    }

    private boolean isWithinRange(BlockPos position) {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.distanceToSqr(Vec3.atCenterOf(position)) <= SEARCH_RANGE_SQUARED;
    }

    private void clearBreakingState() {
        clearBreakingState(true);
    }

    private void clearBreakingState(boolean abort) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (abort && breakingTarget != null && connection != null) {
            sendBreakPacket(connection, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, breakingTarget);
        }
        breakingTarget = null;
    }

    private void resetBreakState() {
        currentTarget = null;
        clearBreakingState();
    }

    private void resetTimers() {
        sellWatch.reset();
        payWatch.reset();
        restartWatch.reset();
    }

    private void restartModule() {
        setEnabled(false);
        setEnabled(true);
    }
}
