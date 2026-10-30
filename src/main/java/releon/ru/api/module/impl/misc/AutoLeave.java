package releon.ru.api.module.impl.misc;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.MultiModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.network.Network;
import releon.ru.utils.repository.friend.FriendUtils;

public final class AutoLeave extends Module {
    private final ModeSetting leaveType = register(new ModeSetting("Leave Type", "Leave action.", "Disconnect", "Hub", "Disconnect"));
    private final MultiModeSetting triggers = register(new MultiModeSetting("Triggers", "Auto leave triggers.",
            new String[]{"Players", "Staff", "HP"}, "Players", "Staff"));
    private final NumberSetting distance = register(new NumberSetting("Max Distance", "Player trigger distance.", 10.0, 5.0, 40.0, 1.0));
    private final NumberSetting hp = register(new NumberSetting("Minimum HP", "Health trigger threshold.", 8.0, 1.0, 40.0, 1.0));

    public AutoLeave() {
        super("Auto Leave", "Leaves the server when selected danger triggers fire.", ModuleCategory.MISC);
        distance.visibleWhen(() -> triggers.isSelected("Players"));
        hp.visibleWhen(() -> triggers.isSelected("HP"));
    }

    @SubscribeEvent
    private void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null || mc.getConnection() == null) {
            return;
        }

        if (triggers.isSelected("HP")) {
            float health = Network.getResolvedHealth(mc.player, true);
            if (health > 0.0F && health <= hp.getFloat()) {
                leave(Component.literal(" - HP dropped to " + Network.formatHealthValue(health)));
                return;
            }
        }

        if (Network.isPvp()) {
            return;
        }

        if (triggers.isSelected("Players")) {
            for (Player player : mc.level.players()) {
                if (player == mc.player || FriendUtils.isFriend(player) || mc.player.distanceTo(player) >= distance.getFloat()) {
                    continue;
                }
                leave(player.getName().copy().append(" - appeared nearby " + Math.round(mc.player.distanceTo(player)) + "m"));
                return;
            }
        }
    }

    private void leave(Component text) {
        if (mc.getConnection() == null) {
            return;
        }
        if (leaveType.is("Hub")) {
            mc.getConnection().sendCommand("hub");
        } else {
            mc.getConnection().getConnection().disconnect(Component.nullToEmpty("[Auto Leave]\n").copy().append(text));
        }
        setEnabled(false);
    }
}
