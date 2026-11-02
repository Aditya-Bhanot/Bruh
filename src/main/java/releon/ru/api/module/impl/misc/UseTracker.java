package releon.ru.api.module.impl.misc;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.NumberSetting;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class UseTracker extends Module {
    private final BooleanSetting trackTotem = register(new BooleanSetting("Снос тотема", "Трекать снос тотема у игроков.", true));
    private final BooleanSetting trackPotions = register(new BooleanSetting("Полученные зелья", "Трекать попадания брошенных зелий.", true));
    private final BooleanSetting trackConsume = register(new BooleanSetting("Съеденный предмет", "Трекать еду и выпитые предметы игроков.", true));
    private final NumberSetting radius = register(new NumberSetting("Радиус зелий", "Радиус отслеживания брошенных зелий.", 100.0, 10.0, 100.0, 1.0));

    private final Map<Integer, PotionData> trackedPotions = new HashMap<>();
    private final Map<UUID, ItemStack> activeUseItem = new HashMap<>();
    private final Map<UUID, Integer> useStartTick = new HashMap<>();

    public UseTracker() {
        super("UseTracker", "Tracks player totems, potions and consumed items.", ModuleCategory.MISC);
        radius.setVisible(false);
    }

    @Override
    public void onTick(Minecraft client) {
        if (client.player == null || client.level == null) {
            return;
        }

        if (trackConsume.getValue()) {
            trackConsumedItems(client);
        }

        if (trackPotions.getValue()) {
            trackThrownPotions(client);
        }
    }

    @SubscribeEvent
    private void onPacket(PacketEvent event) {
        if (!event.isReceive() || mc.level == null || mc.player == null) {
            return;
        }

        if (!trackTotem.getValue()
                || !(event.getPacket() instanceof ClientboundEntityEventPacket packet)
                || packet.getEventId() != 35) {
            return;
        }

        Entity entity = packet.getEntity(mc.level);
        if (!(entity instanceof Player player) || player.getUUID().equals(mc.player.getUUID())) {
            return;
        }

        ItemStack totemStack = player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)
                ? player.getOffhandItem()
                : player.getMainHandItem().is(Items.TOTEM_OF_UNDYING)
                ? player.getMainHandItem()
                : player.getOffhandItem();

        Component totemName = totemStack.isEmpty()
                ? Component.literal("Тотем бессмертия")
                : totemStack.getHoverName();

        boolean enchanted = player.getOffhandItem().isEnchanted() || player.getMainHandItem().isEnchanted();
        MutableComponent enchantPart = Component.literal(enchanted ? "зачарованный" : "незачарованный")
                .withStyle(enchanted ? ChatFormatting.GREEN : ChatFormatting.RED);

        sendPrefixed(Component.empty()
                .append(Component.literal(player.getName().getString()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" потерял ").withStyle(ChatFormatting.GRAY))
                .append(totemName)
                .append(Component.literal(" (").withStyle(ChatFormatting.GRAY))
                .append(enchantPart)
                .append(Component.literal(")").withStyle(ChatFormatting.GRAY)));
    }

    @Override
    protected void onDisable() {
        trackedPotions.clear();
        activeUseItem.clear();
        useStartTick.clear();
    }

    private void trackConsumedItems(Minecraft client) {
        for (Player player : client.level.players()) {
            if (player.getUUID().equals(client.player.getUUID())) {
                continue;
            }

            UUID id = player.getUUID();
            if (player.isUsingItem()) {
                activeUseItem.computeIfAbsent(id, ignored -> player.getUseItem().copy());
                useStartTick.putIfAbsent(id, player.tickCount);
                continue;
            }

            ItemStack used = activeUseItem.remove(id);
            Integer startTick = useStartTick.remove(id);
            if (used == null || used.isEmpty() || startTick == null || player.tickCount - startTick < 31) {
                continue;
            }

            ItemUseAnimation action = used.getUseAnimation();
            String verb = switch (action) {
                case DRINK -> "выпил";
                case EAT -> "съел";
                default -> null;
            };
            if (verb == null) {
                continue;
            }

            String itemName = stripFormatting(used.getHoverName().getString());
            chat(ChatFormatting.WHITE + player.getName().getString()
                    + ChatFormatting.GRAY + " " + verb + " "
                    + ChatFormatting.WHITE + itemName);
        }
    }

    private void trackThrownPotions(Minecraft client) {
        Set<Integer> current = new HashSet<>();
        float maxDistance = radius.getFloat();

        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof AbstractThrownPotion potion)) {
                continue;
            }
            if (client.player.distanceTo(potion) > maxDistance) {
                continue;
            }

            int entityId = potion.getId();
            current.add(entityId);
            PotionData data = trackedPotions.get(entityId);
            if (data == null) {
                trackedPotions.put(entityId, new PotionData(potion.getItem().copy(), potion.getX(), potion.getY(), potion.getZ()));
            } else {
                data.lastX = potion.getX();
                data.lastY = potion.getY();
                data.lastZ = potion.getZ();
            }
        }

        Set<Integer> removed = new HashSet<>(trackedPotions.keySet());
        removed.removeAll(current);

        for (int entityId : removed) {
            PotionData data = trackedPotions.remove(entityId);
            if (data != null) {
                notifyPotionHits(client, data);
            }
        }
    }

    private void notifyPotionHits(Minecraft client, PotionData data) {
        AABB hitBox = new AABB(
                data.lastX - 4.0, data.lastY - 2.0, data.lastZ - 4.0,
                data.lastX + 4.0, data.lastY + 2.0, data.lastZ + 4.0
        );

        for (LivingEntity hit : client.level.getEntitiesOfClass(LivingEntity.class, hitBox, ignored -> true)) {
            if (!(hit instanceof Player player)) {
                continue;
            }

            double dx = player.getX() - data.lastX;
            double dz = player.getZ() - data.lastZ;
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance > 4.0) {
                continue;
            }

            double hitChance = Math.max(0.0, 1.0 - distance / 4.0) * 100.0;
            ChatFormatting hitColor = hitChance >= 65.0
                    ? ChatFormatting.GREEN
                    : hitChance >= 35.0 ? ChatFormatting.YELLOW : ChatFormatting.RED;

            sendPrefixed(Component.empty()
                    .append(Component.literal(player.getName().getString()).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" получил ").withStyle(ChatFormatting.GRAY))
                    .append(data.stack.getHoverName())
                    .append(Component.literal(" (").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(String.format("%.0f%%", hitChance)).withStyle(hitColor))
                    .append(Component.literal(")").withStyle(ChatFormatting.GRAY)));
        }
    }

    private void chat(String message) {
        if (mc.player == null) {
            return;
        }
        sendPrefixed(parseLegacy(message));
    }

    private void sendPrefixed(Component message) {
        if (mc.player == null) {
            return;
        }

        MutableComponent text = Component.empty()
                .append(Component.literal("Releon").withStyle(style -> style.withBold(true).withColor(0x7FF2FF)))
                .append(Component.literal(" » ").withStyle(ChatFormatting.DARK_GRAY))
                .append(message);
        mc.player.displayClientMessage(text, false);
    }

    private static String stripFormatting(String text) {
        String stripped = ChatFormatting.stripFormatting(text);
        return stripped == null ? text : stripped;
    }

    private static MutableComponent parseLegacy(String message) {
        MutableComponent result = Component.empty();
        ChatFormatting currentColor = null;
        boolean bold = false;
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (c == ChatFormatting.PREFIX_CODE && i + 1 < message.length()) {
                appendLegacyPart(result, current, currentColor, bold);
                current.setLength(0);

                ChatFormatting formatting = ChatFormatting.getByCode(message.charAt(++i));
                if (formatting == null) {
                    continue;
                }
                if (formatting == ChatFormatting.BOLD) {
                    bold = true;
                } else if (formatting == ChatFormatting.RESET) {
                    currentColor = null;
                    bold = false;
                } else if (formatting.isColor()) {
                    currentColor = formatting;
                }
            } else {
                current.append(c);
            }
        }

        appendLegacyPart(result, current, currentColor, bold);
        return result;
    }

    private static void appendLegacyPart(MutableComponent result, StringBuilder current, ChatFormatting color, boolean bold) {
        if (current.isEmpty()) {
            return;
        }

        MutableComponent part = Component.literal(current.toString());
        if (color != null) {
            part.withStyle(color);
        }
        if (bold) {
            part.withStyle(ChatFormatting.BOLD);
        }
        result.append(part);
    }

    private static final class PotionData {
        private final ItemStack stack;
        private double lastX;
        private double lastY;
        private double lastZ;

        private PotionData(ItemStack stack, double x, double y, double z) {
            this.stack = stack;
            this.lastX = x;
            this.lastY = y;
            this.lastZ = z;
        }
    }
}
