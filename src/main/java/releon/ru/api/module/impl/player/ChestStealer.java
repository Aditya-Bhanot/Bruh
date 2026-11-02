package releon.ru.api.module.impl.player;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.InputEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.module.impl.combat.aura.util.StopWatch;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.MultiModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.inventory.InventoryTask;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class ChestStealer extends Module {
    private static final int STOP_BEFORE_LOOT_TICKS = 2;
    private static final String[] LOOT_ITEMS = {
            "Totem", "Netherite Helmet", "Netherite Chestplate", "Netherite Leggings", "Netherite Boots",
            "Netherite Sword", "Netherite Pickaxe", "Enchanted Golden Apple", "Player Head", "Shulker Box",
            "Netherite Ingot", "Dragon Head", "Elytra", "Snowball", "Splash Potion", "Tripwire Hook",
            "Netherite Scrap", "Beacon", "Villager Spawn Egg"
    };

    private final StopWatch lootWatch = new StopWatch();
    private final Map<Item, String> itemNames = new HashMap<>();

    private final ModeSetting mode = register(new ModeSetting("Mode", "Chest looting mode.", "Default", "Default", "FT Warden"));
    private final NumberSetting delay = register(new NumberSetting("Delay", "Delay between slots.", 100.0, 0.0, 1000.0, 10.0));
    private final BooleanSetting whitelistEnabled = register(new BooleanSetting("Whitelist", "Loot only selected items.", false));
    private final MultiModeSetting defaultWhitelist = register(new MultiModeSetting("Default Loot", "Selected Default loot.", LOOT_ITEMS));
    private final MultiModeSetting ftWardenWhitelist = register(new MultiModeSetting("FT Warden Loot", "Selected FT Warden loot.", LOOT_ITEMS));

    private boolean chestSessionActive;
    private int stopTicksRemaining;

    public ChestStealer() {
        super("Chest Stealer", "Automatically loots chests.", ModuleCategory.PLAYER);
        delay.visibleWhen(() -> mode.is("Default"));
        defaultWhitelist.visibleWhen(() -> whitelistEnabled.getValue() && mode.is("Default"));
        ftWardenWhitelist.visibleWhen(() -> whitelistEnabled.getValue() && mode.is("FT Warden"));
        fillItemNames();
    }

    @Override
    protected void onEnable() {
        resetState();
    }

    @Override
    protected void onDisable() {
        resetState();
    }

    @SubscribeEvent
    private void onTick(TickEvent.Post event) {
        Minecraft client = event.getClient();
        if (client.player == null || client.level == null || client.gameMode == null) {
            resetState();
            return;
        }
        if (!(client.player.containerMenu instanceof ChestMenu menu)) {
            resetState();
            return;
        }

        if (mode.is("FT Warden")) {
            if (!chestSessionActive) {
                chestSessionActive = true;
                stopTicksRemaining = STOP_BEFORE_LOOT_TICKS;
                lootWatch.reset();
            }
            client.player.setSprinting(false);
            if (stopTicksRemaining > 0) {
                stopTicksRemaining--;
                return;
            }
            stealAllFromChest(menu);
            return;
        }

        chestSessionActive = false;
        stopTicksRemaining = 0;
        if (delay.getValue() <= 0.0D) {
            stealAllFromChest(menu);
        } else if (lootWatch.every(delay.getValue())) {
            quickMoveNextSlot(menu);
        }
    }

    @SubscribeEvent
    private void onInput(InputEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (mode.is("FT Warden") && chestSessionActive && client.player != null && client.player.containerMenu instanceof ChestMenu) {
            event.inputNone();
            client.player.setSprinting(false);
        }
    }

    private void quickMoveNextSlot(ChestMenu menu) {
        int chestSlots = menu.getRowCount() * 9;
        for (int slotIndex = 0; slotIndex < chestSlots; slotIndex++) {
            Slot slot = menu.slots.get(slotIndex);
            if (slot.hasItem() && shouldLoot(slot.getItem())) {
                InventoryTask.clickSlot(slotIndex, 0, ClickType.QUICK_MOVE, true);
                break;
            }
        }
        closeIfChestEmpty(menu);
    }

    private void stealAllFromChest(ChestMenu menu) {
        int chestSlots = menu.getRowCount() * 9;
        for (int slotIndex = 0; slotIndex < chestSlots; slotIndex++) {
            Slot slot = menu.slots.get(slotIndex);
            if (slot.hasItem() && shouldLoot(slot.getItem())) {
                InventoryTask.clickSlot(slotIndex, 0, ClickType.QUICK_MOVE, true);
            }
        }
        closeIfChestEmpty(menu);
    }

    private void closeIfChestEmpty(ChestMenu menu) {
        int chestSlots = menu.getRowCount() * 9;
        for (int slotIndex = 0; slotIndex < chestSlots; slotIndex++) {
            Slot slot = menu.slots.get(slotIndex);
            if (slot.hasItem() && shouldLoot(slot.getItem())) {
                return;
            }
        }
        InventoryTask.closeScreen(false);
    }

    private boolean shouldLoot(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (!whitelistEnabled.getValue()) {
            return true;
        }
        Set<String> selected = getActiveWhitelist().getValue();
        if (selected == null || selected.isEmpty()) {
            return true;
        }
        String itemName = resolveWhitelistName(stack);
        return itemName != null && selected.contains(itemName);
    }

    private MultiModeSetting getActiveWhitelist() {
        return mode.is("FT Warden") ? ftWardenWhitelist : defaultWhitelist;
    }

    private String resolveWhitelistName(ItemStack stack) {
        Item item = stack.getItem();
        String mapped = itemNames.get(item);
        if (mapped != null) {
            return mapped;
        }
        if (item instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock) {
            return "Shulker Box";
        }
        return null;
    }

    private void fillItemNames() {
        itemNames.put(Items.TOTEM_OF_UNDYING, "Totem");
        itemNames.put(Items.NETHERITE_HELMET, "Netherite Helmet");
        itemNames.put(Items.NETHERITE_CHESTPLATE, "Netherite Chestplate");
        itemNames.put(Items.NETHERITE_LEGGINGS, "Netherite Leggings");
        itemNames.put(Items.NETHERITE_BOOTS, "Netherite Boots");
        itemNames.put(Items.NETHERITE_SWORD, "Netherite Sword");
        itemNames.put(Items.NETHERITE_PICKAXE, "Netherite Pickaxe");
        itemNames.put(Items.ENCHANTED_GOLDEN_APPLE, "Enchanted Golden Apple");
        itemNames.put(Items.PLAYER_HEAD, "Player Head");
        itemNames.put(Items.NETHERITE_INGOT, "Netherite Ingot");
        itemNames.put(Items.DRAGON_HEAD, "Dragon Head");
        itemNames.put(Items.ELYTRA, "Elytra");
        itemNames.put(Items.SNOWBALL, "Snowball");
        itemNames.put(Items.SPLASH_POTION, "Splash Potion");
        itemNames.put(Items.TRIPWIRE_HOOK, "Tripwire Hook");
        itemNames.put(Items.NETHERITE_SCRAP, "Netherite Scrap");
        itemNames.put(Items.BEACON, "Beacon");
        itemNames.put(Items.VILLAGER_SPAWN_EGG, "Villager Spawn Egg");
    }

    private void resetState() {
        lootWatch.reset();
        chestSessionActive = false;
        stopTicksRemaining = 0;
    }
}
