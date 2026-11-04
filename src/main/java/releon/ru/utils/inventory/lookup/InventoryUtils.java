package releon.ru.utils.inventory.lookup;


import releon.ru.mixin.accessor.ClientWorldAccessor;
import releon.ru.mixin.accessor.MultiPlayerGameModeAccessor;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;
import releon.ru.utils.inventory.InventoryTask;

public final class InventoryUtils {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };
    private static int savedSlot = -1;
    private static int silentSlot = -1;

    private InventoryUtils() {}

    public static int findItemInHotbar(Item item) {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    public static int findItemInInventory(Item item) {
        if (mc.player == null) return -1;
        for (int i = 9; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    public static int findItemAnywhere(Item item) {
        int hotbar = findItemInHotbar(item);
        if (hotbar != -1) return hotbar;
        return findItemInInventory(item);
    }

    public static InventoryResult find(Item item) {
        return find(stack -> stack.getItem() == item);
    }

    public static InventoryResult find(Item... items) {
        return find(Arrays.asList(items));
    }

    public static InventoryResult find(List<Item> items) {
        return find(stack -> items.contains(stack.getItem()));
    }

    public static boolean hasElytra() {
        if (mc.player == null) return false;
        return mc.player.getItemBySlot(EquipmentSlot.CHEST).get(DataComponents.GLIDER) != null;
    }

    public static boolean isElytraEquipped() {
        if (mc.player == null) return false;
        return mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA;
    }

    public static boolean isElytraUsable() {
        if (mc.player == null) return false;
        ItemStack stack = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (stack.getItem() != Items.ELYTRA) return false;
        if (stack.getMaxDamage() <= 0) return true;
        return stack.getDamageValue() < stack.getMaxDamage() - 1;
    }

    public static int findHotbarItem(Item item) {
        if (mc.player == null) return -1;

        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    public static int getHotbarSlotId(IntPredicate filter) {
        if (mc.player == null || filter == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (filter.test(i)) {
                return i;
            }
        }
        return -1;
    }

    public static int findElytraSlot() {
        if (mc.player == null) return -1;

        for (int i = 0; i < 46; i++) {
            if (mc.player.getInventory().getItem(i).getItem() == Items.ELYTRA) {
                return i;
            }
        }
        return -1;
    }

    public static int findChestArmorSlot() {
        if (mc.player == null) return -1;

        for (int i = 0; i < 46; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            Equippable component = stack.get(DataComponents.EQUIPPABLE);
            if (component != null && component.slot() == EquipmentSlot.CHEST && stack.getItem() != Items.ELYTRA) {
                return i;
            }
        }
        return -1;
    }

    public static int findChestArmorSlot(Item targetItem) {
        if (mc.player == null) return -1;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            Equippable component = stack.get(DataComponents.EQUIPPABLE);
            if (component == null || component.slot() != EquipmentSlot.CHEST) {
                continue;
            }
            if (targetItem == null) {
                if (stack.getItem() != Items.ELYTRA) {
                    return i;
                }
            } else if (stack.getItem() == targetItem) {
                return i;
            }
        }

        return -1;
    }

    public static InventoryResult find(ItemSearcher searcher) {
        if (mc.player == null) return InventoryResult.notFound();

        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = mc.player.getItemBySlot(slot);
            if (isValid(stack) && searcher.matches(stack)) {
                return new InventoryResult(-2, true, stack);
            }
        }

        for (int i = 35; i >= 0; i--) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (isValid(stack) && searcher.matches(stack)) {
                return InventoryResult.of(i, stack);
            }
        }
        return InventoryResult.notFound();
    }

    public static InventoryResult findHotbar(Item item) {
        return findHotbar(stack -> stack.getItem() == item);
    }

    public static InventoryResult findHotbar(ItemSearcher searcher) {
        if (mc.player == null) return InventoryResult.notFound();

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (isValid(stack) && searcher.matches(stack)) {
                return InventoryResult.of(i, stack);
            }
        }
        return InventoryResult.notFound();
    }

    public static Slot findSlot(Item item) {
        return findSlot(s -> s.getItem().getItem() == item, null);
    }

    public static Slot findSlot(Predicate<Slot> filter) {
        return findSlot(filter, null);
    }

    public static Slot findSlot(Predicate<Slot> filter, Comparator<Slot> comparator) {
        if (mc.player == null) return null;
        var stream = mc.player.containerMenu.slots.stream().filter(filter);
        return comparator != null ? stream.max(comparator).orElse(null) : stream.findFirst().orElse(null);
    }

    public static Slot findSlot(Item item, Predicate<Slot> extraFilter, Comparator<Slot> comparator) {
        Predicate<Slot> combined = s -> s.getItem().getItem() == item && extraFilter.test(s);
        return findSlot(combined, comparator);
    }

    public static Slot findSlotInHotbar(Item item) {
        if (mc.player == null) return null;
        for (int i = 36; i <= 44; i++) {
            Slot slot = mc.player.inventoryMenu.getSlot(i);
            if (slot != null && !slot.getItem().isEmpty() && slot.getItem().getItem() == item) {
                return slot;
            }
        }
        return null;
    }

    public static Slot findSlotInInventory(Item item) {
        if (mc.player == null) return null;
        for (int i = 9; i <= 35; i++) {
            Slot slot = mc.player.inventoryMenu.getSlot(i);
            if (slot != null && !slot.getItem().isEmpty() && slot.getItem().getItem() == item) {
                return slot;
            }
        }
        return null;
    }

    public static Slot findSlotAnywhere(Item item) {
        Slot hotbar = findSlotInHotbar(item);
        if (hotbar != null) return hotbar;
        return findSlotInInventory(item);
    }

    public static Slot findRegularTotemSlot() {
        if (mc.player == null) return null;

        for (int i = 36; i <= 44; i++) {
            Slot slot = mc.player.inventoryMenu.getSlot(i);
            if (slot != null && !slot.getItem().isEmpty()
                    && slot.getItem().getItem() == Items.TOTEM_OF_UNDYING
                    && !slot.getItem().isEnchanted()) {
                return slot;
            }
        }

        for (int i = 9; i <= 35; i++) {
            Slot slot = mc.player.inventoryMenu.getSlot(i);
            if (slot != null && !slot.getItem().isEmpty()
                    && slot.getItem().getItem() == Items.TOTEM_OF_UNDYING
                    && !slot.getItem().isEnchanted()) {
                return slot;
            }
        }

        return null;
    }

    public static Slot findEnchantedTotemSlot() {
        if (mc.player == null) return null;

        for (int i = 36; i <= 44; i++) {
            Slot slot = mc.player.inventoryMenu.getSlot(i);
            if (slot != null && !slot.getItem().isEmpty()
                    && slot.getItem().getItem() == Items.TOTEM_OF_UNDYING
                    && slot.getItem().isEnchanted()) {
                return slot;
            }
        }

        for (int i = 9; i <= 35; i++) {
            Slot slot = mc.player.inventoryMenu.getSlot(i);
            if (slot != null && !slot.getItem().isEmpty()
                    && slot.getItem().getItem() == Items.TOTEM_OF_UNDYING
                    && slot.getItem().isEnchanted()) {
                return slot;
            }
        }

        return null;
    }

    public static Slot findTotemSlot(boolean preferNonEnchanted) {
        if (mc.player == null) return null;

        Slot regularTotem = null;
        Slot enchantedTotem = null;

        for (int i = 36; i <= 44; i++) {
            Slot slot = mc.player.inventoryMenu.getSlot(i);
            if (slot != null && !slot.getItem().isEmpty() && slot.getItem().getItem() == Items.TOTEM_OF_UNDYING) {
                if (!slot.getItem().isEnchanted()) {
                    if (regularTotem == null) regularTotem = slot;
                } else {
                    if (enchantedTotem == null) enchantedTotem = slot;
                }
            }
        }

        for (int i = 9; i <= 35; i++) {
            Slot slot = mc.player.inventoryMenu.getSlot(i);
            if (slot != null && !slot.getItem().isEmpty() && slot.getItem().getItem() == Items.TOTEM_OF_UNDYING) {
                if (!slot.getItem().isEnchanted()) {
                    if (regularTotem == null) regularTotem = slot;
                } else {
                    if (enchantedTotem == null) enchantedTotem = slot;
                }
            }
        }

        if (preferNonEnchanted) {
            return regularTotem != null ? regularTotem : enchantedTotem;
        } else {
            return regularTotem != null ? regularTotem : enchantedTotem;
        }
    }

    public static boolean hasEnchantedTotemInOffhand() {
        if (mc.player == null) return false;
        ItemStack offhand = mc.player.getOffhandItem();
        return offhand.getItem() == Items.TOTEM_OF_UNDYING && offhand.isEnchanted();
    }

    public static boolean hasRegularTotemInOffhand() {
        if (mc.player == null) return false;
        ItemStack offhand = mc.player.getOffhandItem();
        return offhand.getItem() == Items.TOTEM_OF_UNDYING && !offhand.isEnchanted();
    }

    public static void swap(int from, int to) {
        if (from == 6 || to == 6) {
            int _legitSlot = (to == 6) ? from : to;
            if (releon.ru.api.module.impl.combat.MaceTarget.ActionManager.executeLegitArmorSwapFromSlot(_legitSlot)) {
                return;
            }
        }
        click(from, 0, ClickType.PICKUP);
        click(to, 0, ClickType.PICKUP);
        click(from, 0, ClickType.PICKUP);
    }

    public static void swapHotbar(int slot, int hotbarSlot) {
        if (mc.player != null && mc.options != null) {
            boolean fromInventory = slot < 36 || slot > 44;
            if (fromInventory) {
                mc.player.setSprinting(false);
                mc.options.keySprint.setDown(false);
            }
        }
        click(slot, hotbarSlot, ClickType.SWAP);
    }

    public static void swapToOffhand(int slot) {
        click(slot, 40, ClickType.SWAP);
    }

    public static void swapToOffhand(Slot slot) {
        if (slot != null) {
            click(InventoryTask.getMenuSlotId(slot), 40, ClickType.SWAP);
        }
    }

    public static void swapOffhandWithSlot(int slotId) {
        if (mc.player == null || mc.gameMode == null) return;
        int syncId = mc.player.containerMenu.containerId;
        mc.gameMode.handleInventoryMouseClick(syncId, slotId, 40, ClickType.SWAP, mc.player);
    }

    public static void moveToSlot(int from, int to) {
        swap(from, to);
    }

    public static void click(int slot, int button, ClickType type) {
        if (mc.player == null || mc.gameMode == null || slot == -1) return;
        mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, slot, button, type, mc.player);
    }

    public static void selectSlot(int slot) {
        if (releon.ru.api.module.impl.combat.MaceTarget.ActionManager.onInventoryUtilsSelectSlot(slot)) {
            return;
        }
        if (mc.player == null || slot < 0 || slot > 8) return;
        if (mc.player.getInventory().getSelectedSlot() != slot) {
            mc.player.getInventory().setSelectedSlot(slot);
        }
    }

    public static boolean syncSelectedHotbarSlot(int slot) {
        if (mc.player == null || mc.gameMode == null || slot < 0 || slot > 8) return false;
        if (mc.player.getInventory().getSelectedSlot() == slot) {
            return false;
        }
        mc.player.getInventory().setSelectedSlot(slot);
        ((MultiPlayerGameModeAccessor) mc.gameMode).releon$ensureHasSentCarriedItem();
        resetSilentSlotTracking();
        return true;
    }

    public static void selectSlotSilent(int slot) {
        sendHeldItemChange(slot);
    }

    public static boolean sendHeldItemChange(int slot) {
        if (mc.player == null || mc.getConnection() == null || slot < 0 || slot > 8) return false;
        if (silentSlot == -1) {
            silentSlot = mc.player.getInventory().getSelectedSlot();
        }
        if (silentSlot == slot) {
            return false;
        }
        mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        silentSlot = slot;
        return true;
    }

    public static void resetSilentSlotTracking() {
        if (mc.player == null) {
            silentSlot = -1;
            return;
        }
        silentSlot = mc.player.getInventory().getSelectedSlot();
    }

    public static void saveSlot() {
        if (mc.player != null) {
            savedSlot = mc.player.getInventory().getSelectedSlot();
        }
    }

    public static void restoreSlot() {
        if (savedSlot != -1) {
            selectSlot(savedSlot);
            savedSlot = -1;
        }
    }

    public static void restoreSlotSilent() {
        if (savedSlot != -1) {
            selectSlotSilent(savedSlot);
            savedSlot = -1;
        }
    }

    public static void silentUseHotbarItem(int hotbarSlot) {
        if (mc.player == null || mc.getConnection() == null) return;

        int currentSlot = mc.player.getInventory().getSelectedSlot();

        if (hotbarSlot != currentSlot) {
            sendHeldItemChange(hotbarSlot);
        }

        sendUsePacket(InteractionHand.MAIN_HAND);

        if (hotbarSlot != currentSlot) {
            sendHeldItemChange(currentSlot);
        }
    }

    public static void silentSwapUseAndReturn(int inventorySlot) {
        if (mc.player == null || mc.getConnection() == null) return;

        int currentHotbarSlot = mc.player.getInventory().getSelectedSlot();

        click(inventorySlot, currentHotbarSlot, ClickType.SWAP);

        sendUsePacket(InteractionHand.MAIN_HAND);

        click(inventorySlot, currentHotbarSlot, ClickType.SWAP);
    }

    public static void silentUseItem(Item item) {
        if (mc.player == null) return;

        int hotbarSlot = findItemInHotbar(item);
        if (hotbarSlot != -1) {
            silentUseHotbarItem(hotbarSlot);
            return;
        }

        int invSlot = findItemInInventory(item);
        if (invSlot != -1) {
            int wrappedSlot = wrapSlot(invSlot);
            silentSwapUseAndReturn(wrappedSlot);
            closeScreen();
        }
    }

    public static void sendUsePacket(InteractionHand hand) {
        if (mc.player == null) {
            return;
        }
        sendUsePacket(hand, mc.player.getYRot(), mc.player.getXRot());
    }

    public static void sendUsePacket(InteractionHand hand, float yaw, float pitch) {
        if (mc.player == null || mc.getConnection() == null || mc.level == null) return;
        int sequence = 0;
        BlockStatePredictionHandler pendingUpdateManager = null;

        try {
            ClientWorldAccessor worldAccessor = (ClientWorldAccessor) mc.level;
            pendingUpdateManager = worldAccessor.getPendingUpdateManager().startPredicting();
            sequence = pendingUpdateManager.currentSequence();
        } catch (Exception ignored) {
        } finally {
            try {
                mc.getConnection().send(new ServerboundUseItemPacket(
                        hand,
                        sequence,
                        yaw,
                        pitch
                ));
            } finally {
                if (pendingUpdateManager != null) {
                    try {
                        pendingUpdateManager.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    public static void use(InteractionHand hand) {
        if (mc.player == null || mc.gameMode == null) return;
        sendUsePacket(hand);
    }

    public static void closeScreen() {
        if (releon.ru.api.module.impl.combat.MaceTarget.ActionManager.shouldSuppressCloseScreen()) {
            return;
        }
        if (mc.player == null || mc.getConnection() == null) return;
        mc.getConnection().send(new ServerboundContainerClosePacket(mc.player.containerMenu.containerId));
    }

    public static boolean isScreenOpen() {
        return mc.screen != null && !(mc.screen instanceof ChatScreen);
    }

    public static int wrapSlot(int slot) {
        return slot < 9 ? slot + 36 : slot;
    }

    public static int currentSlot() {
        return mc.player != null ? mc.player.getInventory().getSelectedSlot() : 0;
    }

    public static ItemStack offhandStack() {
        return mc.player != null ? mc.player.getOffhandItem() : ItemStack.EMPTY;
    }

    public static ItemStack mainhandStack() {
        return mc.player != null ? mc.player.getMainHandItem() : ItemStack.EMPTY;
    }

    public static boolean hasTotemInOffhand() {
        return mc.player != null && mc.player.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING;
    }

    public static Item getOffhandItem() {
        return mc.player != null ? mc.player.getOffhandItem().getItem() : Items.AIR;
    }

    private static boolean isValid(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getMaxDamage() <= 0) {
            return true;
        }
        return stack.getDamageValue() < stack.getMaxDamage() - 10;
    }
}
