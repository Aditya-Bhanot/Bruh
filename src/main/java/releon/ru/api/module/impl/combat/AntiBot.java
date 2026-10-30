package releon.ru.api.module.impl.combat;

import com.adl.nativeprotect.Native;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.ModeSetting;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class AntiBot extends Module {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final Set<UUID> BOT_SET = new HashSet<>();
    private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static AntiBot instance;

    private final Set<UUID> suspectSet = new HashSet<>();
    private final ModeSetting mode = register(new ModeSetting("Mode", "Bot detection mode.", "ReallyWorld", "Matrix", "ReallyWorld"));

    public AntiBot() {
        super("Anti Bot", "Filters fake server-side entities.", ModuleCategory.COMBAT);
        instance = this;
    }

    @Override
    public void onTick(Minecraft client) {
        if (client.player == null || client.level == null) {
            reset();
            return;
        }
        if (mode.is("Matrix")) {
            matrixMode();
        } else {
            reallyWorldMode();
        }
    }

    @SubscribeEvent
    private void onPacket(PacketEvent event) {
        if (!event.isReceive()) {
            return;
        }
        switch (event.getPacket()) {
            case ClientboundPlayerInfoUpdatePacket packet -> packet.newEntries().forEach(this::checkPlayerAfterSpawn);
            case ClientboundPlayerInfoRemovePacket packet -> packet.profileIds().forEach(uuid -> {
                suspectSet.remove(uuid);
                BOT_SET.remove(uuid);
            });
            default -> {
            }
        }
    }

    private void checkPlayerAfterSpawn(ClientboundPlayerInfoUpdatePacket.Entry entry) {
        GameProfile profile = entry.profile();
        if (profile == null || entry.latency() < 2 || profile.properties() != null && !profile.properties().isEmpty()) {
            return;
        }
        if (isDuplicateProfile(profile)) {
            BOT_SET.add(profile.id());
        } else {
            suspectSet.add(profile.id());
        }
    }

    @Native
    private void matrixMode() {
        if (MC.level == null) {
            return;
        }
        for (UUID uuid : Set.copyOf(suspectSet)) {
            Player player = MC.level.getPlayerByUUID(uuid);
            if (player != null && (isFullyEquipped(player) || hasForeignUuid(player) || isNameBot(player))) {
                BOT_SET.add(uuid);
            }
            suspectSet.remove(uuid);
        }
        if (MC.player != null && MC.player.tickCount % 100 == 0) {
            BOT_SET.removeIf(uuid -> MC.level.getPlayerByUUID(uuid) == null);
        }
    }

    @Native
    private void reallyWorldMode() {
        if (MC.level == null) {
            return;
        }
        for (Player player : MC.level.players()) {
            if (player != MC.player && hasForeignUuid(player)) {
                BOT_SET.add(player.getUUID());
            }
        }
    }

    private boolean isDuplicateProfile(GameProfile profile) {
        return MC.getConnection() != null && MC.getConnection().getOnlinePlayers().stream()
                .filter(player -> player.getProfile().name().equals(profile.name()) && !player.getProfile().id().equals(profile.id()))
                .count() == 1;
    }

    private boolean isFullyEquipped(Player player) {
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = player.getItemBySlot(slot);
            Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
            if (stack.isEmpty() || equippable == null || stack.isEnchanted()) {
                return false;
            }
        }
        return true;
    }

    public boolean isBot(Player player) {
        return player != null && (BOT_SET.contains(player.getUUID()) || isNameBot(player) || isBotU(player) || hasForeignUuid(player));
    }

    public static boolean shouldIgnore(Player player) {
        return instance != null && instance.isEnabled() && instance.isBot(player);
    }

    public static boolean isKnownBot(UUID uuid) {
        return BOT_SET.contains(uuid);
    }

    public static boolean hasForeignUuid(Player player) {
        if (player == null) {
            return false;
        }
        String name = player.getName().getString();
        if (name.contains("NPC") || name.startsWith("[ZNPC]")) {
            return false;
        }
        return !player.getUUID().equals(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)));
    }

    private boolean isNameBot(Player player) {
        String name = player.getName().getString();
        return name.startsWith("CIT-") && !name.contains("NPC") && !name.startsWith("[ZNPC]");
    }

    private boolean isBotU(Entity entity) {
        String name = entity.getName().getString();
        return entity.isInvisible()
                && !name.contains("NPC")
                && !name.startsWith("[ZNPC]")
                && !entity.getUUID().equals(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)));
    }

    private void reset() {
        suspectSet.clear();
        BOT_SET.clear();
    }

    @Override
    protected void onDisable() {
        reset();
    }
}
