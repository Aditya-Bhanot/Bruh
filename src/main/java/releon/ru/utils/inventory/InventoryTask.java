package releon.ru.utils.inventory;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import releon.ru.IMinecraft;
import releon.ru.api.module.impl.combat.aura.Angle;
import releon.ru.utils.inventory.lookup.InventoryUtils;
import releon.ru.utils.inventory.script.Script;
import releon.ru.utils.inventory.interaction.PlayerInteractionHelper;
import releon.ru.utils.chat.ChatMessage;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public final class InventoryTask implements IMinecraft {
    private static final Script SWAP_AND_USE_SCRIPT = new Script();
    private static final Script HOTBAR_USE_SCRIPT = new Script();

    private InventoryTask() {
    }

    public static void moveItem(Slot from, int to) {
        if (from != null) {
            moveItem(getMenuSlotId(from), to, false, false);
        }
    }

    public static void moveItem(Slot from, int to, boolean task) {
        if (from != null) {
            moveItem(getMenuSlotId(from), to, task, false);
        }
    }

    public static void moveItem(Slot from, int to, boolean task, boolean updateInventory) {
        if (from != null) {
            moveItem(getMenuSlotId(from), to, task, updateInventory);
        }
    }

    public static void moveItem(int from, int to, boolean task, boolean updateInventory) {
        if (from == to || from == -1) {
            return;
        }

        int count = Math.toIntExact(slots().count()) - 10;
        if (from >= count && count == 36) {
            Runnable action = () -> clickSlot(to, from - count, ClickType.SWAP, false);
            if (task) {
                InventoryFlowManager.addTask(action);
            } else {
                action.run();
            }
            return;
        }

        if (task) {
            InventoryFlowManager.addTask(() -> moveItem(from, to, updateInventory));
        } else {
            moveItem(from, to, updateInventory);
        }
    }

    public static void moveItem(int from, int to, boolean updateInventory) {
        clickSlot(from, 0, ClickType.SWAP, false);
        clickSlot(to, 0, ClickType.SWAP, false);
        clickSlot(from, 0, ClickType.SWAP, false);
        if (updateInventory) {
            updateSlots();
        }
    }

    public static void swapHand(Slot slot, InteractionHand hand, boolean task) {
        swapHand(slot, hand, task, false);
    }

    public static void swapHand(Slot slot, InteractionHand hand, boolean task, boolean updateInventory) {
        int slotId = getMenuSlotId(slot);
        if (slot == null || slotId == -1 || mc.player == null) {
            return;
        }

        int button = hand == InteractionHand.MAIN_HAND ? mc.player.getInventory().getSelectedSlot() : 40;
        Runnable action = () -> swap(slotId, button, updateInventory);
        if (task) {
            InventoryFlowManager.addTask(action);
        } else {
            action.run();
        }
    }

    public static void swap(int slotId, int button, boolean updateInventory) {
        clickSlot(slotId, button, ClickType.SWAP, false);

        if (updateInventory && "FunTime".equals(getSelectedMode())) {
            updateSlots();
        }
    }

    public static void swapAndUse(Slot slot, String text, boolean task) {
        if (slot == null) {
            ChatMessage.brandmessage(text + " - \u043d\u0435 \u043d\u0430\u0439\u0434\u0435\u043d!");
            return;
        }

        Runnable action = () -> swapAndUse(slot);
        if (task) {
            InventoryFlowManager.addTask(action);
        } else {
            action.run();
        }
    }

    public static void swapAndUse(Item item, boolean task) {
        swapAndUse(item, task, null);
    }

    public static void swapAndUse(Item item, boolean task, Angle angle) {
        Slot slot = getSlot(item);
        if (slot == null) {
            ChatMessage.brandmessage(item.getName(item.getDefaultInstance()).getString() + " - \u043d\u0435 \u043d\u0430\u0439\u0434\u0435\u043d!");
            return;
        }

        Runnable action = () -> swapAndUse(slot, angle);
        if (task) {
            InventoryFlowManager.addTask(action);
        } else {
            action.run();
        }
    }

    public static void updateSwapAndUseScript() {
        SWAP_AND_USE_SCRIPT.update();
        HOTBAR_USE_SCRIPT.update();
    }

    public static boolean isSwapAndUseIdle() {
        return SWAP_AND_USE_SCRIPT.isFinished() && HOTBAR_USE_SCRIPT.isFinished();
    }

    public static void swapAndUse(Slot slot) {
        swapAndUse(slot, null);
    }

    public static void swapAndUse(Slot slot, Angle angle) {
        int delay1;
        int delay2;

        switch (getSelectedMode()) {
            case "HolyWorld" -> {
                delay1 = 2;
                delay2 = 1;
            }
            case "FunTime" -> {
                delay1 = 0;
                delay2 = 1;
            }
            default -> {
                delay1 = 0;
                delay2 = 0;
            }
        }

        SWAP_AND_USE_SCRIPT.cleanup()
                .addTickStep(0, () -> swapHand(slot, InteractionHand.MAIN_HAND, false))
                .addTickStep(delay1, () -> PlayerInteractionHelper.interactItem(InteractionHand.MAIN_HAND, angle))
                .addTickStep(delay2, () -> swapHand(slot, InteractionHand.MAIN_HAND, false, true));
    }

    public static void useHotbarItem(int hotbarSlot, int previousSlot, boolean instant) {
        if (mc.player == null) {
            return;
        }

        int useDelay = switch (getSelectedMode()) {
            case "HolyWorld" -> instant ? 1 : 3;
            case "FunTime", "SpookyTime", "CopyTime" -> instant ? 1 : 2;
            default -> instant ? 0 : 1;
        };
        int restoreDelay = instant ? 1 : 2;

        useHotbarItem(hotbarSlot, previousSlot, useDelay, restoreDelay);
    }

    public static void useHotbarItem(int hotbarSlot, int previousSlot, int useDelay, int restoreDelay) {
        if (mc.player == null || mc.gameMode == null || hotbarSlot < 0 || hotbarSlot > 8) {
            return;
        }

        int safeUseDelay = Math.max(0, useDelay);
        int safeRestoreDelay = Math.max(1, restoreDelay);

        HOTBAR_USE_SCRIPT.cleanup()
                .addTickStep(0, () -> switchTo(hotbarSlot))
                .addTickStep(safeUseDelay, () -> {
                    if (mc.player == null) {
                        return;
                    }

                    PlayerInteractionHelper.interactItem(InteractionHand.MAIN_HAND);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                })
                .addTickStep(safeUseDelay + safeRestoreDelay, () -> {
                    if (previousSlot != hotbarSlot) {
                        switchTo(previousSlot);
                    }
                });

    }

    public static void updateSlots() {
        if (mc.player == null || mc.gameMode == null) {
            return;
        }

        mc.gameMode.handleInventoryMouseClick(
                mc.player.containerMenu.containerId,
                0,
                0,
                ClickType.PICKUP_ALL,
                mc.player
        );
    }

    public static void closeScreen(boolean packet) {
        if (mc.player == null) {
            return;
        }

        if (packet) {
            if (mc.getConnection() != null) {
                mc.getConnection().send(new ServerboundContainerClosePacket(mc.player.containerMenu.containerId));
            }
        } else {
            mc.player.closeContainer();
        }
    }

    public static void clickSlot(int id, int button, ClickType type) {
        if (id == -1 || mc.gameMode == null || mc.player == null) {
            return;
        }

        mc.gameMode.handleInventoryMouseClick(mc.player.containerMenu.containerId, id, button, type, mc.player);
    }

    public static void switchTo(int slot) {
        if (mc.player == null || slot < 0 || slot > 8) {
            return;
        }

        InventoryUtils.syncSelectedHotbarSlot(slot);
    }

    public static void swapSlot(Slot targetSlot) {
        swapSlot(targetSlot, false);
    }

    public static void swapSlot(Slot targetSlot, boolean task) {
        int slotId = getMenuSlotId(targetSlot);
        if (targetSlot == null || slotId == -1) {
            return;
        }

        int hotbarSlot = slotId >= 36 && slotId <= 44 ? slotId - 36 : targetSlot.index;
        Runnable action = () -> switchTo(hotbarSlot);
        if (task) {
            InventoryFlowManager.addTask(action);
        } else {
            action.run();
        }
    }

    public static void swapSlot(Item item) {
        swapSlot(getSlot(item));
    }

    public static void swapSlot(Item item, boolean task) {
        swapSlot(getSlot(item), task);
    }

    public static void clickSlot(Slot slot, int button, ClickType ClickType, boolean silent) {
        if (slot != null) {
            clickSlot(getMenuSlotId(slot), button, ClickType, silent);
        }
    }

    public static void clickSlot(int slotId, int buttonId, ClickType ClickType, boolean silent) {
        clickSlot(mc.player.containerMenu.containerId, slotId, buttonId, ClickType, silent, false);
    }

    public static void clickSlot(int slotId, int buttonId, ClickType ClickType, boolean silent, boolean task) {
        clickSlot(mc.player.containerMenu.containerId, slotId, buttonId, ClickType, silent, task);
    }

    public static void clickSlot(int windowId, int slotId, int buttonId, ClickType ClickType, boolean silent) {
        clickSlot(windowId, slotId, buttonId, ClickType, silent, false);
    }

    public static void clickSlot(int windowId, int slotId, int buttonId, ClickType ClickType, boolean silent, boolean task) {
        Runnable action = () -> {
            if (mc.gameMode == null || mc.player == null) {
                return;
            }

            int currentWindowId = mc.player.containerMenu.containerId;
            if (windowId != currentWindowId) {
                return;
            }

            mc.gameMode.handleInventoryMouseClick(currentWindowId, slotId, buttonId, ClickType, mc.player);
            if (silent) {
                mc.player.containerMenu.clicked(slotId, buttonId, ClickType, mc.player);
            }
        };

        if (task) {
            InventoryFlowManager.addTask(action);
        } else {
            action.run();
        }
    }

    public static Slot getSlot(Item item) {
        return getSlot(item, slot -> true);
    }

    public static Slot getSlot(Item item, Predicate<Slot> filter) {
        return getSlot(item, Comparator.comparingInt(slot -> 0), filter);
    }

    public static Slot getSlot(Predicate<Slot> filter) {
        return slots().filter(filter).findFirst().orElse(null);
    }

    public static Slot getSlot(Predicate<Slot> filter, Comparator<Slot> comparator) {
        return slots().filter(filter).max(comparator).orElse(null);
    }

    public static Slot getSlot(Item item, Comparator<Slot> comparator, Predicate<Slot> filter) {
        return slots().filter(slot -> slot.getItem().getItem().equals(item)).filter(filter).max(comparator).orElse(null);
    }

    public static Slot getFoodMaxSaturationSlot() {
        return slots().filter(slot -> {FoodProperties food = slot.getItem().get(DataComponents.FOOD);return food != null && !food.canAlwaysEat();}).max(Comparator.comparingDouble(slot -> Objects.requireNonNull(slot.getItem().get(DataComponents.FOOD)).saturation())).orElse(null);
    }

    public static Slot getSlot(List<Item> items) {
        return slots().filter(slot -> items.contains(slot.getItem().getItem())).findFirst().orElse(null);
    }

    public static Slot getPotion(Holder<MobEffect> effect) {
        return slots().filter(slot -> {
            PotionContents component = slot.getItem().get(DataComponents.POTION_CONTENTS);
            if (component == null) {
                return false;
            }

            return StreamSupport.stream(component.getAllEffects().spliterator(), false).anyMatch(instance -> instance.getEffect().equals(effect));
        }).findFirst().orElse(null);
    }

    public static Slot getPotionFromCategory(MobEffectCategory category) {
        return slots().filter(slot -> {
            ItemStack stack = slot.getItem();
            PotionContents component = stack.get(DataComponents.POTION_CONTENTS);
            if (!stack.getItem().equals(Items.SPLASH_POTION) || component == null) {
                return false;
            }

            MobEffectCategory inverse = category == MobEffectCategory.BENEFICIAL ? MobEffectCategory.HARMFUL : MobEffectCategory.BENEFICIAL;

            long matching = StreamSupport.stream(component.getAllEffects().spliterator(), false).filter(effect -> effect.getEffect().value().getCategory() == category).count();
            long opposite = StreamSupport.stream(component.getAllEffects().spliterator(), false).filter(effect -> effect.getEffect().value().getCategory() == inverse).count();
            return matching >= opposite;
        }).findFirst().orElse(null);
    }

    public static int getInventoryCount(Item item) {
        if (mc.player == null) {
            return 0;
        }

        return IntStream.range(0, mc.player.getInventory().getContainerSize()).filter(i -> mc.player.getInventory().getItem(i).getItem().equals(item)).map(i -> mc.player.getInventory().getItem(i).getCount()).sum();
    }

    public static int findFreeHotbarSlot() {
        if (mc.player == null) {
            return -1;
        }

        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) {
                return i;
            }
        }

        return 8;
    }

    public static int findHotbarSlot(Item item) {
        if (mc.player == null) {
            return -1;
        }

        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) {
                return i;
            }
        }

        return -1;
    }

    public static int findInventorySlot(Item item) {
        if (mc.player == null) {
            return -1;
        }

        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).is(item)) {
                return i;
            }
        }

        return -1;
    }

    public static void swapSlots(int fromSlot, int toSlot) {
        if (mc.gameMode == null || mc.player == null) {
            return;
        }

        mc.gameMode.handleInventoryMouseClick(
                mc.player.containerMenu.containerId,
                fromSlot,
                toSlot,
                ClickType.SWAP,
                mc.player
        );
    }

    public static void swapSlots(int fromSlot, int toSlot, boolean task) {
        if (mc.gameMode == null || mc.player == null) {
            return;
        }

        Runnable action = () -> mc.gameMode.handleInventoryMouseClick(
                mc.player.containerMenu.containerId,
                fromSlot,
                toSlot,
                ClickType.SWAP,
                mc.player
        );

        if (task) {
            InventoryFlowManager.addTask(action);
        } else {
            action.run();
        }
    }

    public static Slot slotByIndex(int index) {
        if (mc.player == null) {
            return null;
        }

        if (index < 0 || index >= mc.player.containerMenu.slots.size()) {
            return null;
        }
        return mc.player.containerMenu.getSlot(index);
    }

    public static void useItemFromSlotMOMENTALNO(int slot) {
        if (mc.player == null || mc.gameMode == null) {
            return;
        }

        ItemStack stack = mc.player.getInventory().getItem(slot);
        if (stack.isEmpty() || mc.player.getCooldowns().isOnCooldown(stack)) {
            return;
        }

        int previousSlot = mc.player.getInventory().getSelectedSlot();
        InventoryUtils.syncSelectedHotbarSlot(slot);
        PlayerInteractionHelper.interactItem(InteractionHand.MAIN_HAND);
        mc.player.swing(InteractionHand.MAIN_HAND);
        InventoryUtils.syncSelectedHotbarSlot(previousSlot);
    }

    public static int getHotbarItems(List<Item> items) {
        if (mc.player == null) {
            return -1;
        }

        return IntStream.range(0, 9)
                .filter(i -> items.contains(mc.player.getInventory().getItem(i).getItem()))
                .findFirst()
                .orElse(-1);
    }

    public static int getHotbarSlotId(IntPredicate filter) {
        return IntStream.range(0, 9).filter(filter).findFirst().orElse(-1);
    }

    public static int getCount(Predicate<Slot> filter) {
        return slots().filter(filter).mapToInt(slot -> slot.getItem().getCount()).sum();
    }

    public static Slot mainHandSlot() {
        if (mc.player == null) {
            return null;
        }

        List<Slot> slots = slots().toList();
        int count = slots.size();
        int offset = count == 46 ? 10 : 9;
        int index = count - offset + mc.player.getInventory().getSelectedSlot();
        if (index < 0 || index >= slots.size()) {
            return null;
        }
        return slots.get(index);
    }

    public static boolean isServerScreen() {
        return slots().toList().size() != 46;
    }

    public static Stream<Slot> slots() {
        if (mc.player == null) {
            return Stream.empty();
        }
        return mc.player.containerMenu.slots.stream();
    }

    public static void selectCompass() {
        Slot slot = getSlot(Items.COMPASS);
        if (slot == null || mc.player == null) {
            return;
        }

        int slotId = getMenuSlotId(slot);
        if (slotId >= 36 && slotId <= 44) {
            InventoryUtils.syncSelectedHotbarSlot(slotId - 36);
        } else {
            InventoryUtils.syncSelectedHotbarSlot(0);
            swapHand(slot, InteractionHand.MAIN_HAND, false, true);
        }
    }

    public static String getCleanName(Component text) {
        if (text == null) {
            return "";
        }

        String name = text.getString();
        if (name == null) {
            return "";
        }

        return name.replaceAll("(?i)\u00A7[0-9A-FK-OR]", "").toLowerCase();
    }

    public static String getMoveMode() {
        return getSelectedMode();
    }

    public static boolean isMoveMode(String mode) {
        return mode != null && mode.equals(getSelectedMode());
    }

    public static int getMenuSlotId(Slot slot) {
        if (slot == null || mc.player == null || mc.player.containerMenu == null) {
            return -1;
        }

        List<Slot> slots = mc.player.containerMenu.slots;
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i) == slot) {
                return i;
            }
        }

        return slot.index;
    }

    private static String getSelectedMode() {
        try {
            return "???????";
        } catch (Throwable ignored) {
            return "\u041e\u0431\u044b\u0447\u043d\u044b\u0439";
        }
    }

    private static String normalizeModeName(String mode) {
        if (mode == null) {
            return "\u041e\u0431\u044b\u0447\u043d\u044b\u0439";
        }

        return switch (mode) {
            case "\u0424\u0430\u043d\u0422\u0430\u0439\u043c", "\u0424\u0430\u043d\u0442\u0430\u0439\u043c" -> "FunTime";
            case "\u0421\u043f\u0443\u043a\u0438\u0422\u0430\u0439\u043c" -> "SpookyTime";
            case "\u0425\u043e\u043b\u0438\u0412\u043e\u0440\u043b\u0434" -> "HolyWorld";
            case "\u0420\u0438\u043b\u043b\u0438\u0412\u043e\u0440\u043b\u0434" -> "ReallyWorld";
            case "\u041a\u043e\u043f\u0438\u0422\u0430\u0439\u043c" -> "CopyTime";
            default -> mode;
        };
    }
}
