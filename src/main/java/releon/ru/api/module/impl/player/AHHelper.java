package releon.ru.api.module.impl.player;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.HandledScreenEvent;
import releon.ru.api.events.impl.KeyEvent;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.bind.InputType;
import releon.ru.api.settings.bind.KeyBind;
import releon.ru.api.settings.impl.BindSetting;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.ColorSetting;

import java.awt.Color;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AHHelper extends Module {
    private static final long PULSE_MS = 900L;
    private static final float PULSE_MIN = 0.35F;
    private static final float PULSE_MAX = 1.0F;
    private static final long RESCAN_DEBOUNCE_MS = 90L;
    private static final long RESCAN_INTERVAL_MS = 650L;
    private static final long STORAGE_CLICK_DELAY_MS = 60_100L;
    private static final int CONTAINER_Y_LIMIT = 168;

    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d{1,3}(?:[,.]\\d{3})+|\\d+[,.]\\d+|\\d+)");
    private static final Pattern SECTION_COLOR_PATTERN = Pattern.compile("\\u00A7[0-9A-FK-ORa-fk-or]");
    private static final Pattern AMP_COLOR_PATTERN = Pattern.compile("&[0-9A-FK-ORa-fk-or]");
    private static final Pattern BRACKET_CONTENT_PATTERN = Pattern.compile("\\([^)]*\\)|\\[[^\\]]*\\]|\\{[^}]*\\}");
    private static final Pattern AUCTION_QUERY_CLEANUP = Pattern.compile("[^\\p{L}\\p{N}\\s]+");

    private final BindSetting findFromHandBind = register(new BindSetting("Search Item", "Runs /ah search for the item in hand.", KeyBind.NONE));
    private final BooleanSetting itemReListingSetting = register(new BooleanSetting("Storage Relist", "Quick-moves storage slot 52 once per minute.", false));
    private final ColorSetting cheapestColor = register(new ColorSetting("Cheapest Color", "Highlight color for the lowest total price.", new Color(255, 184, 35, 210)));
    private final ColorSetting economicColor = register(new ColorSetting("Per Item Color", "Highlight color for the best price per item.", new Color(255, 64, 64, 210)));

    private Slot cheapestSlot;
    private Slot bestPerItemSlot;
    private int screenSyncId = -1;
    private long lastScanAt;
    private long lastStorageClick = -1L;
    private boolean dirty;
    private Field cachedLeftField;
    private Field cachedTopField;

    public AHHelper() {
        super("AH Helper", "Highlights cheap lots and searches held item.", ModuleCategory.PLAYER);
    }

    @Override
    public void onTick(Minecraft client) {
        if (client.player == null || client.gameMode == null || !(client.screen instanceof ContainerScreen screen) || !isAuctionScreen(screen)) {
            resetState();
            return;
        }

        if (itemReListingSetting.getValue()) {
            handleStorageRelisting(screen);
        }

        long now = System.currentTimeMillis();
        int syncId = screen.getMenu().containerId;
        if (syncId != screenSyncId) {
            screenSyncId = syncId;
            dirty = true;
            cheapestSlot = null;
            bestPerItemSlot = null;
        }

        if ((dirty && now - lastScanAt >= RESCAN_DEBOUNCE_MS) || now - lastScanAt >= RESCAN_INTERVAL_MS) {
            scanAuctionSlots(screen);
        }
    }

    @SubscribeEvent
    private void onPacket(PacketEvent event) {
        if (event.isReceive()
                && event.getPacket() instanceof ClientboundContainerSetSlotPacket
                && mc.screen instanceof ContainerScreen screen
                && isAuctionScreen(screen)) {
            dirty = true;
        }
    }

    @SubscribeEvent
    private void onKey(KeyEvent event) {
        if (event.action() != GLFW.GLFW_PRESS || !isBindTriggered(findFromHandBind.getValue(), event)) {
            return;
        }
        triggerSearchCommandFromHand();
    }

    @SubscribeEvent
    private void onHandledScreen(HandledScreenEvent event) {
        if (!(mc.screen instanceof ContainerScreen screen) || !isAuctionScreen(screen) || event.getGraphics() == null) {
            return;
        }

        int[] origin = resolveScreenOrigin(screen, event);
        int cheapest = pulsingColor(cheapestColor.getValue());
        int economic = pulsingColor(economicColor.getValue());

        if (cheapestSlot != null && cheapestSlot.hasItem()) {
            drawSlotHighlight(event.getGraphics(), origin[0], origin[1], cheapestSlot, cheapest);
        }

        if (bestPerItemSlot != null && bestPerItemSlot.hasItem()) {
            drawSlotHighlight(event.getGraphics(), origin[0], origin[1], bestPerItemSlot, economic);
        }
    }

    private void handleStorageRelisting(ContainerScreen screen) {
        if (mc.gameMode == null || mc.player == null) {
            return;
        }

        String title = screen.getTitle() == null ? "" : stripFormatting(screen.getTitle().getString()).toLowerCase(Locale.ROOT);
        if (!title.contains("хранилище") && !title.contains("storage")) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastStorageClick < STORAGE_CLICK_DELAY_MS) {
            return;
        }

        List<Slot> slots = screen.getMenu().slots;
        if (slots.size() <= 52) {
            return;
        }

        Slot storageSlot = slots.get(52);
        if (storageSlot == null || !storageSlot.hasItem()) {
            return;
        }

        mc.gameMode.handleInventoryMouseClick(screen.getMenu().containerId, 52, 0, ClickType.QUICK_MOVE, mc.player);
        lastStorageClick = now;
    }

    private void scanAuctionSlots(ContainerScreen screen) {
        if (mc.player == null) {
            resetState();
            return;
        }

        List<Slot> slots = screen.getMenu().slots;
        int[] totalPrice = new int[slots.size()];
        int[] stackCount = new int[slots.size()];

        Slot minTotalSlot = null;
        int minTotal = Integer.MAX_VALUE;

        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || slot.container == mc.player.getInventory() || slot.y >= CONTAINER_Y_LIMIT) {
                totalPrice[i] = -1;
                stackCount[i] = 0;
                continue;
            }

            int price = extractPrice(stack);
            int count = Math.max(1, stack.getCount());
            totalPrice[i] = price;
            stackCount[i] = count;

            if (price >= 0 && price < minTotal) {
                minTotal = price;
                minTotalSlot = slot;
            }
        }

        Slot bestPerItem = null;
        double bestRatio = Double.POSITIVE_INFINITY;
        int tiePrice = Integer.MAX_VALUE;
        for (int i = 0; i < slots.size(); i++) {
            int price = totalPrice[i];
            if (price < 0) {
                continue;
            }

            Slot slot = slots.get(i);
            if (slot == minTotalSlot) {
                continue;
            }

            int count = Math.max(1, stackCount[i]);
            double ratio = (double) price / (double) count;
            if (ratio < bestRatio - 1.0E-9 || (Math.abs(ratio - bestRatio) <= 1.0E-9 && price < tiePrice)) {
                bestRatio = ratio;
                tiePrice = price;
                bestPerItem = slot;
            }
        }

        cheapestSlot = minTotalSlot;
        bestPerItemSlot = bestPerItem;
        dirty = false;
        lastScanAt = System.currentTimeMillis();
    }

    private int extractPrice(ItemStack stack) {
        try {
            int count = Math.max(1, stack.getCount());
            Item.TooltipContext context = mc.level == null ? Item.TooltipContext.EMPTY : Item.TooltipContext.of(mc.level);
            List<Component> tooltip = stack.getTooltipLines(context, mc.player, TooltipFlag.NORMAL);
            StringBuilder combined = new StringBuilder();
            if (tooltip != null && !tooltip.isEmpty()) {
                for (Component line : tooltip) {
                    if (line != null) {
                        combined.append(stripFormatting(line.getString())).append(' ');
                    }
                }
            } else {
                combined.append(stripFormatting(stack.getHoverName().getString()));
            }
            return parsePriceFromText(combined.toString(), count);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private int parsePriceFromText(String text, int stackCount) {
        if (text == null || text.isEmpty()) {
            return -1;
        }

        String lower = text.toLowerCase(Locale.ROOT);
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        int bestWeight = -1;
        long bestPrice = -1L;
        long fallbackPrice = -1L;

        while (matcher.find()) {
            int startIdx = matcher.start(1);
            int endIdx = matcher.end(1);
            int suffixIndex = skipSuffixSeparators(lower, endIdx);
            long multiplier = suffixMultiplier(lower, suffixIndex);
            long value = parseFlexibleNumber(matcher.group(1), multiplier);
            if (value <= 0L) {
                continue;
            }

            int contextStart = Math.max(0, startIdx - 28);
            int contextEnd = Math.min(lower.length(), Math.max(endIdx, suffixIndex + 2) + 28);
            String context = lower.substring(contextStart, contextEnd);

            boolean priceContext = looksLikePriceContext(context);
            boolean totalHint = context.contains("total") || context.contains("итог") || context.contains("всего") || context.contains("сумм");
            boolean perItem = context.contains("за шт") || context.contains("за 1") || context.contains("per item") || context.contains("each");
            int weight = priceContext ? 3 : 1;
            if (totalHint) {
                weight += 3;
            }
            if (perItem) {
                weight += 1;
            }

            long normalized = perItem ? value * Math.max(1, stackCount) : value;
            if (normalized <= 0L) {
                continue;
            }

            if (normalized > fallbackPrice) {
                fallbackPrice = normalized;
            }
            if (weight > bestWeight || (weight == bestWeight && normalized > bestPrice)) {
                bestWeight = weight;
                bestPrice = normalized;
            }
        }

        long resolved = bestPrice > 0L ? bestPrice : fallbackPrice;
        if (resolved <= 0L) {
            return -1;
        }
        return resolved > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) resolved;
    }

    private boolean looksLikePriceContext(String context) {
        return context.contains("price")
                || context.contains("total")
                || context.contains("buy")
                || context.contains("sell")
                || context.contains("coin")
                || context.contains("auction")
                || context.contains("ah")
                || context.contains("цена")
                || context.contains("стоим")
                || context.contains("куп")
                || context.contains("прод")
                || context.contains("монет")
                || context.contains("аукц")
                || context.contains("лот")
                || context.contains("руб")
                || context.contains("$")
                || context.contains("₽");
    }

    private long suffixMultiplier(String text, int indexAfterNumber) {
        if (indexAfterNumber >= text.length()) {
            return 1L;
        }
        char c0 = Character.toLowerCase(text.charAt(indexAfterNumber));
        char c1 = indexAfterNumber + 1 < text.length() ? Character.toLowerCase(text.charAt(indexAfterNumber + 1)) : 0;
        if (c0 == 'k' || c0 == 'к') {
            return (c1 == 'k' || c1 == 'к') ? 1_000_000L : 1_000L;
        }
        if (c0 == 'm' || c0 == 'м') {
            return 1_000_000L;
        }
        return 1L;
    }

    private int skipSuffixSeparators(String text, int index) {
        int i = index;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == ':' || c == '=' || c == '-') {
                i++;
                continue;
            }
            break;
        }
        return i;
    }

    private long parseFlexibleNumber(String source, long multiplier) {
        if (source == null || source.isBlank()) {
            return -1L;
        }

        String compact = source.trim().replace(" ", "").replace("_", "");
        if (compact.matches("\\d{1,3}([,.]\\d{3})+")) {
            long base = parseNumber(compact.replace(",", "").replace(".", ""));
            return multiplySafe(base, multiplier);
        }

        if (compact.indexOf('.') >= 0 || compact.indexOf(',') >= 0) {
            try {
                double parsed = Double.parseDouble(compact.replace(',', '.'));
                if (!(parsed > 0.0D)) {
                    return -1L;
                }
                double scaled = parsed * (double) multiplier;
                return scaled >= (double) Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(scaled);
            } catch (NumberFormatException ignored) {
                return -1L;
            }
        }

        return multiplySafe(parseNumber(compact), multiplier);
    }

    private long parseNumber(String source) {
        long value = 0L;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c >= '0' && c <= '9') {
                value = value * 10L + (c - '0');
            }
        }
        return value;
    }

    private long multiplySafe(long base, long multiplier) {
        if (base <= 0L) {
            return -1L;
        }
        if (multiplier > 1L && base >= Long.MAX_VALUE / multiplier) {
            return Long.MAX_VALUE;
        }
        return base * multiplier;
    }

    private int pulsingColor(Color base) {
        long now = System.currentTimeMillis();
        float phase = (float) (now % PULSE_MS) / (float) PULSE_MS;
        float wave = 0.5F - 0.5F * Mth.cos(phase * (float) (Math.PI * 2.0D));
        float alphaMul = Mth.clamp(PULSE_MIN + (PULSE_MAX - PULSE_MIN) * wave, 0.0F, 1.0F);
        int alpha = Mth.clamp((int) (Math.max(120, base.getAlpha()) * alphaMul), 40, 220);
        return alpha << 24 | base.getRed() << 16 | base.getGreen() << 8 | base.getBlue();
    }

    private void drawSlotHighlight(GuiGraphics graphics, int originX, int originY, Slot slot, int color) {
        int x = originX + slot.x;
        int y = originY + slot.y;
        graphics.fill(x, y, x + 16, y + 16, color);

        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        int borderColor = (230 << 24) | (r << 16) | (g << 8) | b;
        graphics.fill(x, y, x + 16, y + 1, borderColor);
        graphics.fill(x, y + 15, x + 16, y + 16, borderColor);
        graphics.fill(x, y, x + 1, y + 16, borderColor);
        graphics.fill(x + 15, y, x + 16, y + 16, borderColor);
    }

    private boolean isAuctionScreen(ContainerScreen screen) {
        if (mc.player == null || screen == null) {
            return false;
        }

        String title = stripFormatting(screen.getTitle().getString()).toLowerCase(Locale.ROOT);
        if (title.contains("auction") || title.contains("аукц") || title.contains("ah")) {
            return true;
        }

        int checked = 0;
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container == mc.player.getInventory() || !slot.hasItem()) {
                continue;
            }
            if (checked++ >= 8) {
                break;
            }
            try {
                Item.TooltipContext context = mc.level == null ? Item.TooltipContext.EMPTY : Item.TooltipContext.of(mc.level);
                List<Component> tooltip = slot.getItem().getTooltipLines(context, mc.player, TooltipFlag.NORMAL);
                boolean hasPrice = false;
                boolean hasSeller = false;
                for (Component line : tooltip) {
                    String text = stripFormatting(line.getString()).toLowerCase(Locale.ROOT);
                    if (looksLikePriceContext(text)) {
                        hasPrice = true;
                    }
                    if (text.contains("seller") || text.contains("продавец")) {
                        hasSeller = true;
                    }
                }
                if (hasPrice && hasSeller) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private int[] resolveScreenOrigin(AbstractContainerScreen<?> screen, HandledScreenEvent event) {
        int fallbackX = (screen.width - event.getImageWidth()) / 2;
        int fallbackY = (screen.height - event.getImageHeight()) / 2;
        try {
            if (cachedLeftField == null || cachedTopField == null) {
                cachedLeftField = AbstractContainerScreen.class.getDeclaredField("leftPos");
                cachedTopField = AbstractContainerScreen.class.getDeclaredField("topPos");
                cachedLeftField.setAccessible(true);
                cachedTopField.setAccessible(true);
            }
            return new int[]{cachedLeftField.getInt(screen), cachedTopField.getInt(screen)};
        } catch (Throwable ignored) {
            return new int[]{fallbackX, fallbackY};
        }
    }

    private String stripFormatting(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String stripped = ChatFormatting.stripFormatting(text);
        return stripped == null ? text : stripped;
    }

    private void triggerSearchCommandFromHand() {
        if (mc.player == null || mc.player.connection == null) {
            return;
        }

        ItemStack hand = mc.player.getMainHandItem();
        if (hand.isEmpty()) {
            mc.player.displayClientMessage(Component.literal("AH Helper: no item in hand"), true);
            return;
        }

        String query = sanitizeAuctionQuery(hand.getHoverName().getString());
        if (query.isBlank()) {
            query = stripFormatting(hand.getHoverName().getString());
        }
        if (query == null || query.isBlank()) {
            mc.player.displayClientMessage(Component.literal("AH Helper: empty item name"), true);
            return;
        }

        mc.player.connection.sendCommand("ah search " + query);
        mc.player.displayClientMessage(Component.literal("AH Helper: /ah search " + query), true);
        dirty = true;
    }

    private String sanitizeAuctionQuery(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "";
        }
        String normalized = stripFormatting(rawName);
        normalized = SECTION_COLOR_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = AMP_COLOR_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = BRACKET_CONTENT_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = AUCTION_QUERY_CLEANUP.matcher(normalized).replaceAll(" ");
        normalized = normalized.replace('\u00A0', ' ');
        return normalized.trim().replaceAll("\\s{2,}", " ");
    }

    private boolean isBindTriggered(KeyBind bind, KeyEvent event) {
        if (bind == null || !bind.isBound()) {
            return false;
        }
        if (bind.getType() == InputType.KEYBOARD) {
            return event.type() == InputConstants.Type.KEYSYM && event.key() == bind.getCode();
        }
        if (bind.getType() == InputType.MOUSE) {
            return event.type() == InputConstants.Type.MOUSE && event.key() == bind.getCode();
        }
        return false;
    }

    private void resetState() {
        cheapestSlot = null;
        bestPerItemSlot = null;
        screenSyncId = -1;
        lastScanAt = 0L;
        dirty = false;
        cachedLeftField = null;
        cachedTopField = null;
    }
}
