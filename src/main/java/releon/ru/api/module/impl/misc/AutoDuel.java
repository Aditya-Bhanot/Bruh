package releon.ru.api.module.impl.misc;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.aura.util.StopWatch;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.api.settings.impl.StringSetting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class AutoDuel extends Module {
    private static final Pattern NICK_PATTERN = Pattern.compile("^\\w{3,16}$");

    private final ModeSetting mode = register(new ModeSetting("Mode", "Duel kit mode.", "Spheres",
            "Spheres", "Shield", "Spikes 3", "Netherite", "Cheater Paradise", "Bow", "Classic", "Totems", "NoDebuff"));
    private final NumberSetting slowTime = register(new NumberSetting("Send Speed", "Delay between duel requests.", 500.0, 300.0, 1000.0, 10.0));
    private final BooleanSetting moneyMode = register(new BooleanSetting("Play For Money", "Adds money amount to duel request.", false));
    private final StringSetting money = register(new StringSetting("Money", "Money amount for duel request.", "10000", 16));

    private final List<String> sent = new ArrayList<>();
    private final StopWatch counter = new StopWatch();
    private final StopWatch clearCounter = new StopWatch();
    private final StopWatch choiceCounter = new StopWatch();
    private final StopWatch confirmCounter = new StopWatch();

    private double lastPosX;
    private double lastPosY;
    private double lastPosZ;

    public AutoDuel() {
        super("Auto Duel", "Automates duel requests.", ModuleCategory.MISC);
        money.visibleWhen(moneyMode::getValue);
    }

    @Override
    protected void onEnable() {
        counter.reset();
        clearCounter.reset();
        choiceCounter.reset();
        confirmCounter.reset();
        sent.clear();
        if (mc.player != null) {
            lastPosX = mc.player.getX();
            lastPosY = mc.player.getY();
            lastPosZ = mc.player.getZ();
        }
    }

    @SubscribeEvent
    private void onTick(TickEvent event) {
        if (mc.player == null || mc.level == null || mc.player.connection == null || mc.gameMode == null) {
            return;
        }
        handleDuelLogic();
        handleScreenInteraction();
    }

    @SubscribeEvent
    private void onPacket(PacketEvent event) {
        if (!event.isReceive() || !(event.getPacket() instanceof ClientboundSystemChatPacket chat)) {
            return;
        }
        String text = chat.content().getString().toLowerCase(Locale.ROOT);
        if ((text.contains("начало") && text.contains("через") && text.contains("секунд"))
                || (text.contains("дуэли") && text.contains("запрещено") && text.contains("команд"))) {
            setEnabled(false);
        }
    }

    private void handleDuelLogic() {
        List<String> players = getOnlinePlayers();
        double distance = Math.sqrt(Math.pow(lastPosX - mc.player.getX(), 2.0D)
                + Math.pow(lastPosY - mc.player.getY(), 2.0D)
                + Math.pow(lastPosZ - mc.player.getZ(), 2.0D));

        if (distance > 500.0D) {
            setEnabled(false);
            return;
        }

        lastPosX = mc.player.getX();
        lastPosY = mc.player.getY();
        lastPosZ = mc.player.getZ();

        if (clearCounter.finished(800L * Math.max(1, players.size()))) {
            sent.clear();
            clearCounter.reset();
        }

        String ownName = mc.player.getGameProfile().name();
        for (String player : players) {
            if (sent.contains(player) || player.equals(ownName) || !counter.finished(slowTime.getValue())) {
                continue;
            }
            sendDuelRequest(player);
            sent.add(player);
            counter.reset();
        }
    }

    private void sendDuelRequest(String player) {
        if (moneyMode.getValue()) {
            mc.player.connection.sendCommand("duel " + player + " " + money.getValue());
        } else {
            mc.player.connection.sendCommand("duel " + player);
        }
    }

    private void handleScreenInteraction() {
        if (mc.screen == null || !(mc.player.containerMenu instanceof AbstractContainerMenu menu)) {
            return;
        }

        String title = mc.screen.getTitle().getString().toLowerCase(Locale.ROOT);
        if (title.contains("выбор набора") && choiceCounter.finished(150L)) {
            int slotId = getKitSlot();
            if (slotId >= 0) {
                mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, slotId, 0, ClickType.QUICK_MOVE, mc.player);
            }
            choiceCounter.reset();
            return;
        }

        if (title.contains("настройка поединка") && confirmCounter.finished(150L)) {
            mc.gameMode.handleInventoryMouseClick(menu.containerId, 0, 0, ClickType.QUICK_MOVE, mc.player);
            confirmCounter.reset();
        }
    }

    private int getKitSlot() {
        return switch (mode.getValue()) {
            case "Shield" -> 0;
            case "Spikes 3" -> 1;
            case "Bow" -> 2;
            case "Totems" -> 3;
            case "NoDebuff" -> 4;
            case "Spheres" -> 5;
            case "Classic" -> 6;
            case "Cheater Paradise" -> 7;
            case "Netherite" -> 8;
            default -> -1;
        };
    }

    private List<String> getOnlinePlayers() {
        List<String> players = new ArrayList<>();
        if (mc.player == null || mc.player.connection == null) {
            return players;
        }
        for (PlayerInfo info : mc.player.connection.getOnlinePlayers()) {
            GameProfile profile = info.getProfile();
            String name = profile.name();
            if (NICK_PATTERN.matcher(name).matches()) {
                players.add(name);
            }
        }
        return players;
    }
}
