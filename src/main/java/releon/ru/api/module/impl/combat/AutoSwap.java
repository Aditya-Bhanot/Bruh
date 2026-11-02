package releon.ru.api.module.impl.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.ClickSlotEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.autoswaputil.AutoSwapWheelScreen;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.api.settings.impl.BindSetting;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.MultiModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.api.settings.impl.StringSetting;
import releon.ru.utils.inventory.InventoryTask;
import releon.ru.utils.inventory.interaction.PlayerInteractionHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AutoSwap extends Module {
    private static AutoSwap instance;

    private final ModeSetting mode = register(new ModeSetting("Mode", "Swap mode.", "Double", "Double", "Triple"));
    private final ModeSetting firstItem = register(new ModeSetting("First Item", "First offhand item.", "Totem", "Totem", "Head", "GApple", "Shield"));
    private final ModeSetting secondItem = register(new ModeSetting("Second Item", "Second offhand item.", "Totem", "Totem", "Head", "GApple", "Shield"));
    private final BindSetting swapBind = register(new BindSetting("Swap Bind", "Uses AutoSwap without toggling the module.", KeyBind.NONE));
    private final BooleanSetting autoDamage = register(new BooleanSetting("Auto Damage", "Swaps to max-damage item by conditions.", false));
    private final MultiModeSetting autoDamageConditions = register(new MultiModeSetting("Conditions", "Auto damage conditions.", new String[]{"Target Low HP", "Target No Sword"}, "Target Low HP"));
    private final NumberSetting lowHpThreshold = register(new NumberSetting("HP Threshold", "Low target HP threshold.", 10.0, 1.0, 20.0, 1.0));

    private final StringSetting tripleSlotOneId = hiddenText("TripleSlotOneId");
    private final StringSetting tripleSlotOneName = hiddenText("TripleSlotOneName");
    private final StringSetting tripleSlotTwoId = hiddenText("TripleSlotTwoId");
    private final StringSetting tripleSlotTwoName = hiddenText("TripleSlotTwoName");
    private final StringSetting tripleSlotThreeId = hiddenText("TripleSlotThreeId");
    private final StringSetting tripleSlotThreeName = hiddenText("TripleSlotThreeName");

    private final WheelSlotItem[] wheelSlots = new WheelSlotItem[3];
    private Integer selectingSlotIndex;
    private ItemStack savedOffhandItem = ItemStack.EMPTY;
    private boolean autoDamageActive;
    private boolean swapBindWasDown;
    private Component lastSwapDisplayName;
    private long lastSwapTimeMs;

    public AutoSwap() {
        super("AutoSwap", "Quickly swaps combat offhand items.", ModuleCategory.COMBAT);
        instance = this;
        autoDamageConditions.visibleWhen(autoDamage::getValue);
        lowHpThreshold.visibleWhen(() -> autoDamage.getValue() && autoDamageConditions.isSelected("Target Low HP"));
        syncWheelSlotsFromSettings();
    }

    public static AutoSwap getInstance() {
        return instance;
    }

    @Override
    public void onTick(Minecraft client) {
        syncWheelSlotsFromSettings();
        if (client.player == null || client.level == null || client.getWindow() == null) {
            return;
        }
        boolean bindDown = swapBind.getValue().isDown(client.getWindow().handle());
        if (client.screen != null) {
            swapBindWasDown = bindDown;
            return;
        }
        if (bindDown && !swapBindWasDown) {
            useSwap();
        }
        swapBindWasDown = bindDown;
        if (autoDamage.getValue()) {
            handleAutoDamage();
        }
    }

    @SubscribeEvent
    private void onClickSlot(ClickSlotEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (selectingSlotIndex == null || !(mc.screen instanceof InventoryScreen) || mc.player == null) {
            return;
        }

        Slot clickedSlot = event.getSlotId() >= 0 && event.getSlotId() < mc.player.containerMenu.slots.size()
                ? mc.player.containerMenu.getSlot(event.getSlotId())
                : null;
        if (clickedSlot == null || !clickedSlot.hasItem() || clickedSlot.container != mc.player.getInventory()) {
            return;
        }

        ItemStack stack = clickedSlot.getItem();
        if (stack.isEmpty()) {
            return;
        }

        setWheelSlotItem(selectingSlotIndex, stack.getItem(), stack.getHoverName().getString());
        selectingSlotIndex = null;
        event.cancel();
        mc.setScreen(null);
    }

    @Override
    protected void onDisable() {
        selectingSlotIndex = null;
        autoDamageActive = false;
        savedOffhandItem = ItemStack.EMPTY;
        lastSwapDisplayName = null;
        lastSwapTimeMs = 0L;
        swapBindWasDown = false;
    }

    private void useSwap() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) {
            return;
        }
        if (mode.isSelected("Triple")) {
            if (hasConfiguredWheelSlot() && !hasAvailableWheelStack()) {
                return;
            }
            mc.setScreen(new AutoSwapWheelScreen(this));
            return;
        }

        Slot first = findConfiguredSlot(getItemByType(firstItem.getValue()));
        Slot second = findConfiguredSlot(getItemByType(secondItem.getValue()));
        Slot validSlot = first != null && mc.player.getOffhandItem().getItem() != first.getItem().getItem() ? first : second;
        if (validSlot != null) {
            swapToSlot(validSlot);
        }
    }

    private void handleAutoDamage() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) {
            return;
        }
        LivingEntity target = AuraModule.target;
        boolean shouldSwapToDamage = false;
        if (target != null && target.isAlive()) {
            boolean lowHpCondition = autoDamageConditions.isSelected("Target Low HP")
                    && target.getHealth() <= lowHpThreshold.getFloat()
                    && target.getHealth() < mc.player.getHealth();
            boolean noSwordCondition = autoDamageConditions.isSelected("Target No Sword")
                    && target instanceof net.minecraft.world.entity.player.Player player
                    && getAttackDamage(player.getMainHandItem()) <= 0.0D;
            shouldSwapToDamage = autoDamageConditions.getValue().isEmpty() || lowHpCondition || noSwordCondition;
        }

        if (shouldSwapToDamage && !autoDamageActive) {
            savedOffhandItem = mc.player.getOffhandItem().copy();
            ItemStack bestDamageItem = findBestDamageItem();
            if (!bestDamageItem.isEmpty()) {
                swapToItemStack(bestDamageItem);
                autoDamageActive = true;
            }
            return;
        }

        if (!shouldSwapToDamage && autoDamageActive) {
            if (!savedOffhandItem.isEmpty()) {
                swapToItemStack(savedOffhandItem);
            }
            autoDamageActive = false;
            savedOffhandItem = ItemStack.EMPTY;
        }
    }

    private ItemStack findBestDamageItem() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return ItemStack.EMPTY;
        }
        List<ItemStack> damageItems = new ArrayList<>();
        Inventory inventory = mc.player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && (stack.getItem() == Items.PLAYER_HEAD || stack.getItem() == Items.TOTEM_OF_UNDYING) && getAttackDamage(stack) > 0.0D) {
                damageItems.add(stack);
            }
        }
        return damageItems.stream().max(Comparator.comparingDouble(this::getAttackDamage)).orElse(ItemStack.EMPTY);
    }

    private double getAttackDamage(ItemStack stack) {
        ItemAttributeModifiers modifiers = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (modifiers == null) {
            return 0.0D;
        }
        double totalDamage = 0.0D;
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            if (entry.attribute().value() == Attributes.ATTACK_DAMAGE.value()) {
                totalDamage += entry.modifier().amount();
            }
        }
        return totalDamage;
    }

    private void swapToItemStack(ItemStack targetStack) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) {
            return;
        }

        if (targetStack == null || targetStack.isEmpty()) {
            return;
        }
        Slot foundSlot = findSlotByStack(targetStack);
        if (foundSlot != null) {
            swapToSlot(foundSlot);
        }
    }

    private void swapToSlot(Slot slot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null || slot == null || !slot.hasItem() || !isValidInventorySlot(slot)) {
            return;
        }

        ItemStack swappedTo = slot.getItem().copy();
        mc.player.setSprinting(false);
        InventoryTask.swapHand(slot, InteractionHand.OFF_HAND, true, true);
        triggerSwapToast(swappedTo);
    }

    private Slot findSlotByStack(ItemStack targetStack) {
        return InventoryTask.slots()
                .filter(this::isValidInventorySlot)
                .filter(slot -> slot.getItem().getItem() == targetStack.getItem())
                .filter(slot -> slot.getItem().getHoverName().getString().equals(targetStack.getHoverName().getString()))
                .findFirst()
                .or(() -> InventoryTask.slots().filter(this::isValidInventorySlot).filter(slot -> slot.getItem().getItem() == targetStack.getItem()).findFirst())
                .orElse(null);
    }

    private Slot findConfiguredSlot(Item item) {
        return InventoryTask.getSlot(item, Comparator.comparing(slot -> slot.getItem().hasFoil()), this::isValidInventorySlot);
    }

    private boolean isValidInventorySlot(Slot slot) {
        int slotId = InventoryTask.getMenuSlotId(slot);
        return slotId != 45 && slotId != 46;
    }

    private void triggerSwapToast(ItemStack swappedTo) {
        if (swappedTo == null || swappedTo.isEmpty()) {
            return;
        }
        lastSwapTimeMs = System.currentTimeMillis();
        lastSwapDisplayName = swappedTo.getHoverName();
    }

    public Item getItemByType(String itemType) {
        return switch (itemType) {
            case "Totem" -> Items.TOTEM_OF_UNDYING;
            case "Head" -> Items.PLAYER_HEAD;
            case "GApple" -> Items.GOLDEN_APPLE;
            case "Shield" -> Items.SHIELD;
            default -> Items.AIR;
        };
    }

    public void setWheelSlotItem(int index, Item item, String itemName) {
        if (index < 0 || index >= wheelSlots.length) {
            return;
        }
        if (item == null || item == Items.AIR) {
            wheelSlots[index] = null;
            updateWheelSlotSettings(index, "", "");
            return;
        }
        String normalizedName = itemName == null ? "" : itemName;
        wheelSlots[index] = new WheelSlotItem(item, normalizedName);
        updateWheelSlotSettings(index, BuiltInRegistries.ITEM.getKey(item).toString(), normalizedName);
    }

    public void startSelectingItem(int wheelSlotIndex) {
        Minecraft mc = Minecraft.getInstance();
        selectingSlotIndex = wheelSlotIndex;
        if (mc.player != null) {
            closeCurrentContainerBeforeInventory(mc);
            mc.setScreen(new InventoryScreen(mc.player));
        }
    }

    private void closeCurrentContainerBeforeInventory(Minecraft mc) {
        if (mc.player == null || mc.player.containerMenu == mc.player.inventoryMenu) {
            return;
        }
        if (mc.getConnection() != null) {
            PlayerInteractionHelper.sendPacketWithOutEvent(new ServerboundContainerClosePacket(mc.player.containerMenu.containerId));
        }
        mc.player.containerMenu = mc.player.inventoryMenu;
    }

    public ItemStack getWheelSlotStack(int index) {
        syncWheelSlotsFromSettings();
        if (index < 0 || index >= wheelSlots.length) {
            return ItemStack.EMPTY;
        }
        WheelSlotItem slotItem = wheelSlots[index];
        if (slotItem == null || slotItem.item() == null || slotItem.item() == Items.AIR) {
            return ItemStack.EMPTY;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return ItemStack.EMPTY;
        }
        Inventory inventory = mc.player.getInventory();
        ItemStack sameItemFallback = ItemStack.EMPTY;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || stack.getItem() != slotItem.item()) {
                continue;
            }
            if (stack.getHoverName().getString().equals(slotItem.itemName())) {
                return stack;
            }
            if (sameItemFallback.isEmpty()) {
                sameItemFallback = stack;
            }
        }
        return sameItemFallback;
    }

    public void startSwapToItemStack(ItemStack stack) {
        swapToItemStack(stack);
    }

    private boolean hasConfiguredWheelSlot() {
        syncWheelSlotsFromSettings();
        for (WheelSlotItem slotItem : wheelSlots) {
            if (slotItem != null && slotItem.item() != null && slotItem.item() != Items.AIR) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAvailableWheelStack() {
        for (int i = 0; i < wheelSlots.length; i++) {
            if (!getWheelSlotStack(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void syncWheelSlotsFromSettings() {
        syncWheelSlot(0, tripleSlotOneId, tripleSlotOneName);
        syncWheelSlot(1, tripleSlotTwoId, tripleSlotTwoName);
        syncWheelSlot(2, tripleSlotThreeId, tripleSlotThreeName);
    }

    private void syncWheelSlot(int index, StringSetting idSetting, StringSetting nameSetting) {
        String itemId = normalizeSettingText(idSetting);
        String itemName = normalizeSettingText(nameSetting);
        if (itemId.isEmpty()) {
            wheelSlots[index] = null;
            return;
        }
        Identifier identifier = Identifier.tryParse(itemId);
        if (identifier == null || !BuiltInRegistries.ITEM.containsKey(identifier)) {
            wheelSlots[index] = null;
            return;
        }
        Item item = BuiltInRegistries.ITEM.getValue(identifier);
        WheelSlotItem current = wheelSlots[index];
        if (current == null || current.item() != item || !current.itemName().equals(itemName)) {
            wheelSlots[index] = new WheelSlotItem(item, itemName);
        }
    }

    private void updateWheelSlotSettings(int index, String itemId, String itemName) {
        wheelSlotIdSetting(index).setValue(itemId);
        wheelSlotNameSetting(index).setValue(itemName);
    }

    private StringSetting wheelSlotIdSetting(int index) {
        return switch (index) {
            case 0 -> tripleSlotOneId;
            case 1 -> tripleSlotTwoId;
            case 2 -> tripleSlotThreeId;
            default -> throw new IllegalArgumentException("Invalid wheel slot index: " + index);
        };
    }

    private StringSetting wheelSlotNameSetting(int index) {
        return switch (index) {
            case 0 -> tripleSlotOneName;
            case 1 -> tripleSlotTwoName;
            case 2 -> tripleSlotThreeName;
            default -> throw new IllegalArgumentException("Invalid wheel slot index: " + index);
        };
    }

    private String normalizeSettingText(StringSetting setting) {
        return setting.getValue() == null ? "" : setting.getValue().trim();
    }

    private StringSetting hiddenText(String name) {
        StringSetting setting = new StringSetting(name, "", "", 256);
        setting.visibleWhen(() -> false);
        return register(setting);
    }

    private record WheelSlotItem(Item item, String itemName) {
    }
}
