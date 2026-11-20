package attrium.ru.manager;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.TridentItem;

/**
 * Centralized Global Inventory Cache (Packet-Driven + 10-Tick Interval Fallback)
 * with strict World-Join NullPointerException guards and container slot verification.
 */
public final class GlobalInventoryCache {
    private static final GlobalInventoryCache INSTANCE = new GlobalInventoryCache();

    private static int ticks = 0;
    private static long lastTickGameTime = -1L;
    private static volatile boolean packetDirty = true;

    public static int cachedMaceSlot = -1;
    public static int cachedCrystalSlot = -1;
    public static int cachedObsidianSlot = -1;
    public static int cachedWindChargeSlot = -1;
    public static int cachedElytraSlot = -1;
    public static int cachedChestplateSlot = -1;
    public static int cachedFireworkSlot = -1;
    public static int cachedSpearSlot = -1;
    public static int cachedWeaponSlot = -1;
    public static int cachedBackupWeaponSlot = -1;

    private GlobalInventoryCache() {
    }

    public static GlobalInventoryCache getInstance() {
        return INSTANCE;
    }

    public static void markDirtyFromPacket() {
        packetDirty = true;
    }

    public static void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null || mc.player.getInventory() == null) {
            reset();
            return;
        }

        long gameTime = mc.level.getGameTime();
        if (gameTime == lastTickGameTime && !packetDirty) {
            return;
        }
        lastTickGameTime = gameTime;

        ticks++;

        packetDirty = false;
        forceScanNow(mc);
    }

    public static void forceScanNow(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null || mc.player.getInventory() == null) {
            reset();
            return;
        }

        int mace = -1;
        int bestMaceScore = -1;
        int crystal = -1;
        int obsidian = -1;
        int windCharge = -1;
        int firework = -1;
        int spear = -1;
        int bestSpearScore = -1;
        int weapon = -1;
        int backupWeapon = -1;
        int elytraContainer = -1;
        int chestplateContainer = -1;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            Item item = stack.getItem();

            if (crystal == -1 && item == Items.END_CRYSTAL) {
                crystal = i;
            } else if (obsidian == -1 && item == Items.OBSIDIAN) {
                obsidian = i;
            } else if (windCharge == -1 && item == Items.WIND_CHARGE) {
                windCharge = i;
            } else if (firework == -1 && item == Items.FIREWORK_ROCKET) {
                firework = i;
            }

            if (item instanceof MaceItem || item == Items.MACE) {
                int score = mc.player.getCooldowns().isOnCooldown(stack) ? 10 : 60;
                if (score > bestMaceScore) {
                    bestMaceScore = score;
                    mace = i;
                }
            }

            if (backupWeapon == -1 && isBackupSwordOrAxe(stack, item)) {
                backupWeapon = i;
            }

            if (weapon == -1 && (item instanceof MaceItem || item == Items.MACE || isBackupSwordOrAxe(stack, item))) {
                weapon = i;
            }

            int lungeRank = scoreSpearLungeCandidate(stack, item);
            if (lungeRank > bestSpearScore) {
                bestSpearScore = lungeRank;
                spear = i;
            }
        }

        if (weapon == -1) {
            weapon = mace != -1 ? mace : backupWeapon;
        }

        for (int idx = 0; idx < 36; idx++) {
            ItemStack stack = mc.player.getInventory().getItem(idx);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            int containerSlot = idx < 9 ? (idx + 36) : idx;
            if (elytraContainer == -1 && stack.is(Items.ELYTRA)) {
                elytraContainer = containerSlot;
            } else if (chestplateContainer == -1 && isChestplateItem(stack)) {
                chestplateContainer = containerSlot;
            }
            if (elytraContainer != -1 && chestplateContainer != -1) {
                break;
            }
        }

        cachedMaceSlot = mace;
        cachedCrystalSlot = crystal;
        cachedObsidianSlot = obsidian;
        cachedWindChargeSlot = windCharge;
        cachedFireworkSlot = firework;
        cachedSpearSlot = spear;
        cachedWeaponSlot = weapon;
        cachedBackupWeaponSlot = backupWeapon;
        cachedElytraSlot = elytraContainer;
        cachedChestplateSlot = chestplateContainer;
    }

    public static void reset() {
        cachedMaceSlot = -1;
        cachedCrystalSlot = -1;
        cachedObsidianSlot = -1;
        cachedWindChargeSlot = -1;
        cachedElytraSlot = -1;
        cachedChestplateSlot = -1;
        cachedFireworkSlot = -1;
        cachedSpearSlot = -1;
        cachedWeaponSlot = -1;
        cachedBackupWeaponSlot = -1;
        packetDirty = true;
    }

    public static int verifyHotbarSlot(Minecraft mc, int cachedSlot, Item expectedItem) {
        if (mc == null || mc.player == null || mc.level == null || mc.player.getInventory() == null) {
            return -1;
        }
        if (cachedSlot >= 0 && cachedSlot < 9) {
            ItemStack s = mc.player.getInventory().getItem(cachedSlot);
            if (s != null && !s.isEmpty() && s.is(expectedItem)) {
                return cachedSlot;
            }
        }
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s != null && !s.isEmpty() && s.is(expectedItem)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Verifies or scans for the container slot (36..44 for hotbar 0..8, or 9..35 for main inventory)
     * containing either an Elytra (`wantElytra == true`) or a Chestplate (`wantElytra == false`).
     */
    public static int verifyContainerSlot(Minecraft mc, int cachedContainerSlot, boolean wantElytra) {
        if (mc == null || mc.player == null || mc.level == null || mc.player.getInventory() == null) {
            return -1;
        }
        if (cachedContainerSlot >= 9 && cachedContainerSlot <= 44) {
            int invIdx = cachedContainerSlot >= 36 ? (cachedContainerSlot - 36) : cachedContainerSlot;
            if (invIdx >= 0 && invIdx < 36) {
                ItemStack s = mc.player.getInventory().getItem(invIdx);
                if (s != null && !s.isEmpty()) {
                    if (wantElytra && s.is(Items.ELYTRA)) {
                        return cachedContainerSlot;
                    }
                    if (!wantElytra && isChestplateItem(s)) {
                        return cachedContainerSlot;
                    }
                }
            }
        }
        for (int idx = 0; idx < 36; idx++) {
            ItemStack stack = mc.player.getInventory().getItem(idx);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            int containerSlot = idx < 9 ? (idx + 36) : idx;
            if (wantElytra && stack.is(Items.ELYTRA)) {
                cachedElytraSlot = containerSlot;
                return containerSlot;
            }
            if (!wantElytra && isChestplateItem(stack)) {
                cachedChestplateSlot = containerSlot;
                return containerSlot;
            }
        }
        return -1;
    }

    public static int getMaceSlot(Minecraft mc) {
        return verifyHotbarSlot(mc, cachedMaceSlot, Items.MACE);
    }

    public static int getCrystalSlot(Minecraft mc) {
        return verifyHotbarSlot(mc, cachedCrystalSlot, Items.END_CRYSTAL);
    }

    public static int getObsidianSlot(Minecraft mc) {
        return verifyHotbarSlot(mc, cachedObsidianSlot, Items.OBSIDIAN);
    }

    public static int getWindChargeSlot(Minecraft mc) {
        return verifyHotbarSlot(mc, cachedWindChargeSlot, Items.WIND_CHARGE);
    }

    public static int getFireworkSlot(Minecraft mc) {
        return verifyHotbarSlot(mc, cachedFireworkSlot, Items.FIREWORK_ROCKET);
    }

    public static int getSpearSlot(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null || mc.player.getInventory() == null) {
            return -1;
        }
        if (cachedSpearSlot >= 0 && cachedSpearSlot < 9) {
            ItemStack s = mc.player.getInventory().getItem(cachedSpearSlot);
            if (s != null && !s.isEmpty() && scoreSpearLungeCandidate(s, s.getItem()) > 0) {
                return cachedSpearSlot;
            }
        }
        forceScanNow(mc);
        return cachedSpearSlot;
    }

    public static int getElytraContainerSlot(Minecraft mc) {
        return verifyContainerSlot(mc, cachedElytraSlot, true);
    }

    public static int getChestplateContainerSlot(Minecraft mc) {
        return verifyContainerSlot(mc, cachedChestplateSlot, false);
    }

    public static int getBackupWeaponSlot(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null || mc.player.getInventory() == null) {
            return -1;
        }
        if (cachedBackupWeaponSlot >= 0 && cachedBackupWeaponSlot < 9) {
            ItemStack s = mc.player.getInventory().getItem(cachedBackupWeaponSlot);
            if (s != null && !s.isEmpty() && isBackupSwordOrAxe(s, s.getItem())) {
                return cachedBackupWeaponSlot;
            }
        }
        forceScanNow(mc);
        return cachedBackupWeaponSlot;
    }

    private static boolean isBackupSwordOrAxe(ItemStack stack, Item item) {
        return stack.is(ItemTags.SWORDS)
                || stack.is(ItemTags.AXES)
                || item instanceof AxeItem
                || item == Items.NETHERITE_SWORD
                || item == Items.DIAMOND_SWORD
                || item == Items.NETHERITE_AXE
                || item == Items.DIAMOND_AXE;
    }

    private static boolean isChestplateItem(ItemStack stack) {
        return stack.is(Items.NETHERITE_CHESTPLATE)
                || stack.is(Items.DIAMOND_CHESTPLATE)
                || stack.is(Items.IRON_CHESTPLATE)
                || stack.is(Items.GOLDEN_CHESTPLATE)
                || stack.is(Items.CHAINMAIL_CHESTPLATE)
                || stack.is(Items.LEATHER_CHESTPLATE)
                || stack.is(ItemTags.CHEST_ARMOR);
    }

    private static int scoreSpearLungeCandidate(ItemStack stack, Item item) {
        String idPath = BuiltInRegistries.ITEM.getKey(item).getPath();
        String hoverLower = stack.getHoverName().getString().toLowerCase();
        boolean hasCustomData = stack.has(DataComponents.CUSTOM_DATA) || stack.has(DataComponents.CUSTOM_MODEL_DATA);

        if (idPath.contains("spear") || hoverLower.contains("spear") || hoverLower.contains("lunge") || hoverLower.contains("lance")) {
            return 100;
        }
        if (item instanceof TridentItem || item == Items.TRIDENT) {
            return 90;
        }
        if ((item instanceof HoeItem || stack.is(ItemTags.HOES)) && hasCustomData) {
            return 75;
        }
        if (item == Items.BREEZE_ROD || item == Items.STICK || item == Items.BLAZE_ROD) {
            return hasCustomData ? 65 : 25;
        }
        if (item instanceof HoeItem || stack.is(ItemTags.HOES)) {
            return 40;
        }
        return -1;
    }
}
