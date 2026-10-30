package releon.ru.api.module.impl.misc;

import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.events.impl.TickEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.ModeSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.chat.ChatMessage;
import releon.ru.utils.inventory.lookup.InventoryUtils;
import releon.ru.utils.network.Network;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JoinerHelper extends Module {
    private static final Pattern PAGE_PATTERN = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");
    private static JoinerHelper instance;

    private final ModeSetting serverSelection = register(new ModeSetting("Server", "Target server.", "ReallyWorld", "ReallyWorld", "SpookyTime Duels"));
    private final NumberSetting griefSelection = register(new NumberSetting("Grief Number", "ReallyWorld grief number.", 1.0, 1.0, 70.0, 1.0));
    private final NumberSetting clickDelay = register(new NumberSetting("Delay", "Delay between actions in seconds.", 0.35, 0.1, 2.0, 0.05));

    private long lastActionTime;
    private long lastScreenChangeTime;
    private long lastJoinAttemptTime;
    private boolean retryQueued;
    private boolean successNotified;
    private String lastScreenSignature = "";

    public JoinerHelper() {
        super("Joiner Helper", "Automatically joins the selected server.", ModuleCategory.MISC);
        instance = this;
        griefSelection.visibleWhen(() -> serverSelection.is("ReallyWorld"));
    }

    public static JoinerHelper getInstance() {
        return instance;
    }

    @Override
    protected void onEnable() {
        resetState(true);
    }

    @Override
    protected void onDisable() {
        resetState(false);
    }

    @SubscribeEvent
    private void onTick(TickEvent event) {
        if (mc.player == null || mc.gameMode == null || mc.getConnection() == null) {
            return;
        }

        if (GLFW.glfwGetKey(mc.getWindow().handle(), GLFW.GLFW_KEY_INSERT) == GLFW.GLFW_PRESS) {
            setEnabled(false);
            return;
        }

        if (!isCurrentServerSupported()) {
            return;
        }

        updateScreenSignature();

        if (isAlreadyOnTarget()) {
            retryQueued = false;
            lastJoinAttemptTime = 0L;
            if (!successNotified) {
                successNotified = true;
                if (serverSelection.is("ReallyWorld")) {
                    ChatMessage.brandmessage("Joined Grief #" + targetGrief());
                } else {
                    ChatMessage.brandmessage("Joined SpookyTime Duels");
                }
            }
            if (serverSelection.is("SpookyTime Duels")) {
                setEnabled(false);
            }
            return;
        }

        if (mc.screen == null) {
            if (!retryQueued) {
                return;
            }
            if (lastJoinAttemptTime > 0L && System.currentTimeMillis() - lastJoinAttemptTime < 5000L) {
                return;
            }
            if (canAct(baseDelayMs() + 120L)) {
                openSelector();
            }
            return;
        }

        if (!(mc.screen instanceof ContainerScreen container) || !screenSettled()) {
            return;
        }

        if (serverSelection.is("ReallyWorld")) {
            handleReallyWorld(container);
        } else if (serverSelection.is("SpookyTime Duels")) {
            handleSpookyTime(container);
        }
    }

    @SubscribeEvent
    private void onPacket(PacketEvent event) {
        if (!event.isReceive() || !(event.getPacket() instanceof ClientboundDisconnectPacket packet)) {
            return;
        }

        String message = clean(packet.reason() == null ? "" : packet.reason().getString());
        if (!containsAny(message,
                "к сожалению сервер переполнен",
                "сервер переполнен",
                "сервер заполнен",
                "подождите 20 секунд",
                "подождите несколько секунд перед повторным подключением",
                "вы уже подключены на этот сервер",
                "вы были кикнуты",
                "большой поток игроков")) {
            return;
        }

        retryQueued = true;
        successNotified = false;
        lastJoinAttemptTime = 0L;
        lastActionTime = 0L;
        lastScreenChangeTime = System.currentTimeMillis();
    }

    private void handleReallyWorld(ContainerScreen container) {
        String title = clean(container.getTitle().getString());
        AbstractContainerMenu menu = container.getMenu();
        int targetGrief = targetGrief();

        if (title.contains("выбор мира грифа")) {
            int currentPage = parseCurrentPage(title);
            int targetPage = getTargetPage(targetGrief);

            if (currentPage != -1 && currentPage != targetPage) {
                if (currentPage < targetPage) {
                    int nextPageSlot = findSlotByName(menu, "следующая страница");
                    if (nextPageSlot != -1 && canAct(pageDelayMs())) {
                        clickContainerSlot(menu.containerId, nextPageSlot);
                    }
                } else {
                    int previousPageSlot = findSlotByName(menu, "предыдущая страница");
                    if (previousPageSlot != -1 && canAct(pageDelayMs())) {
                        clickContainerSlot(menu.containerId, previousPageSlot);
                    }
                }
                return;
            }

            int griefSlot = findSlotByName(menu, "гриф #" + targetGrief);
            if (griefSlot != -1 && canAct(targetDelayMs())) {
                clickContainerSlot(menu.containerId, griefSlot);
                lastJoinAttemptTime = System.currentTimeMillis();
            }
            return;
        }

        int survivalSlot = findSlotByName(menu, "гриферское выживание");
        if (survivalSlot != -1 && canAct(menuDelayMs())) {
            clickContainerSlot(menu.containerId, survivalSlot);
        }
    }

    private void handleSpookyTime(ContainerScreen container) {
        String title = clean(container.getTitle().getString());
        AbstractContainerMenu menu = container.getMenu();

        if (title.contains("выберите режим")) {
            int duelsModeSlot = findSlotByName(menu, "дуэли 1.16.5");
            if (duelsModeSlot == -1) {
                duelsModeSlot = findSlotByItem(menu, Items.RESPAWN_ANCHOR);
            }
            if (duelsModeSlot == -1) {
                duelsModeSlot = findNonEmptySlot(menu, 14);
            }
            if (duelsModeSlot == -1) {
                duelsModeSlot = findSlotByName(menu, "дуэли");
            }
            if (duelsModeSlot != -1 && canAct(menuDelayMs())) {
                clickContainerSlot(menu.containerId, duelsModeSlot);
            }
            return;
        }

        int duelServerSlot = findSlotByName(menu, "1duels");
        if (duelServerSlot == -1) {
            duelServerSlot = findSlotByName(menu, "1 дуэл");
        }
        if (duelServerSlot == -1) {
            duelServerSlot = findSlotByItem(menu, Items.STICKY_PISTON);
        }
        if (duelServerSlot != -1 && canAct(targetDelayMs())) {
            clickContainerSlot(menu.containerId, duelServerSlot);
            lastJoinAttemptTime = System.currentTimeMillis();
            return;
        }

        int duelsSlot = findSlotByName(menu, "дуэли");
        if (duelsSlot != -1 && canAct(menuDelayMs())) {
            clickContainerSlot(menu.containerId, duelsSlot);
        }
    }

    private boolean isAlreadyOnTarget() {
        return serverSelection.is("SpookyTime Duels") ? isOnSpookyDuels() : isOnSelectedReallyWorldGrief();
    }

    private boolean isOnSelectedReallyWorldGrief() {
        if (mc.level == null) {
            return false;
        }

        String targetName = "гриф #" + targetGrief();
        Scoreboard scoreboard = mc.level.getScoreboard();
        Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (objective == null) {
            return false;
        }

        if (clean(objective.getDisplayName().getString()).contains(targetName)) {
            return true;
        }

        for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
            String row = clean(PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(entry.owner()), entry.ownerName()).getString());
            if (row.contains(targetName)) {
                return true;
            }
        }
        return false;
    }

    private boolean isOnSpookyDuels() {
        if (mc.level == null) {
            return false;
        }
        if (hasSpookySidebarWithDuelsMarker()) {
            return true;
        }
        if (lastJoinAttemptTime > 0L) {
            return hasSpookySidebarLoaded() || hasSpookyJoinHologram();
        }
        return false;
    }

    private boolean hasSpookySidebarWithDuelsMarker() {
        Scoreboard scoreboard = mc.level.getScoreboard();
        Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (objective == null) {
            return false;
        }

        String header = clean(objective.getDisplayName().getString());
        if (!header.contains("spookytime.net")) {
            return false;
        }

        for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
            String row = clean(PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(entry.owner()), entry.ownerName()).getString());
            if (row.contains("дуэл") || row.contains("duels") || row.contains("1duels")) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSpookySidebarLoaded() {
        Scoreboard scoreboard = mc.level.getScoreboard();
        Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (objective == null) {
            return false;
        }

        String header = clean(objective.getDisplayName().getString());
        int nonEmptyRows = 0;
        if (!header.isEmpty()) {
            nonEmptyRows++;
            if (header.contains("spooky")) {
                return true;
            }
        }

        for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
            String row = clean(PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(entry.owner()), entry.ownerName()).getString());
            if (row.isEmpty()) {
                continue;
            }
            nonEmptyRows++;
            if (row.contains("spooky") || row.contains("донат") || row.contains("donate") || row.contains("онлайн") || row.contains("online")) {
                return true;
            }
        }
        return nonEmptyRows >= 3;
    }

    private boolean hasSpookyJoinHologram() {
        if (mc.player == null || mc.level == null) {
            return false;
        }
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!entity.hasCustomName() || mc.player.distanceToSqr(entity) > 64.0D * 64.0D) {
                continue;
            }
            String name = clean(entity.getDisplayName().getString());
            if (name.contains("донат сервера") || (name.contains("донат") && name.contains("сервер")) || name.contains("donate server")) {
                return true;
            }
        }
        return false;
    }

    private void openSelector() {
        int selectorSlot = findSelectorSlot();
        if (selectorSlot == -1) {
            return;
        }

        if (mc.player.getInventory().getSelectedSlot() != selectorSlot) {
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(selectorSlot));
            mc.player.getInventory().setSelectedSlot(selectorSlot);
        }

        InventoryUtils.use(InteractionHand.MAIN_HAND);
        lastActionTime = System.currentTimeMillis();
        lastScreenChangeTime = lastActionTime;
        successNotified = false;
    }

    private boolean isCurrentServerSupported() {
        if (serverSelection.is("ReallyWorld")) {
            return Network.isReallyWorld();
        }
        if (!serverSelection.is("SpookyTime Duels")) {
            return false;
        }
        if (Network.isSpookyTime()) {
            return true;
        }

        String address = mc.getConnection().getServerData() == null || mc.getConnection().getServerData().ip == null
                ? ""
                : clean(mc.getConnection().getServerData().ip);
        return address.contains("spooky") || clean(mc.getConnection().serverBrand()).contains("spooky");
    }

    private int findSelectorSlot() {
        int preferredCompassSlot = findPreferredCompassSlot(0, 4);
        if (preferredCompassSlot != -1) {
            return preferredCompassSlot;
        }

        int compassSlot = InventoryUtils.findItemInHotbar(Items.COMPASS);
        if (compassSlot != -1) {
            return compassSlot;
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            String name = clean(stack.getHoverName().getString());
            if (name.contains("компас") || name.contains("меню")) {
                return i;
            }
        }
        return -1;
    }

    private int findPreferredCompassSlot(int... hotbarSlots) {
        for (int hotbarSlot : hotbarSlots) {
            if (hotbarSlot < 0 || hotbarSlot > 8) {
                continue;
            }

            ItemStack stack = mc.player.getInventory().getItem(hotbarSlot);
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.getItem() == Items.COMPASS) {
                return hotbarSlot;
            }
            String name = clean(stack.getHoverName().getString());
            if (name.contains("компас") || name.contains("меню")) {
                return hotbarSlot;
            }
        }
        return -1;
    }

    private int findSlotByName(AbstractContainerMenu menu, String target) {
        String cleanTarget = clean(target);
        for (Slot slot : menu.slots) {
            if (slot == null || slot.getItem().isEmpty()) {
                continue;
            }
            String name = clean(slot.getItem().getHoverName().getString());
            if (name.contains(cleanTarget)) {
                return slot.index;
            }
        }
        return -1;
    }

    private int findSlotByItem(AbstractContainerMenu menu, Item item) {
        for (Slot slot : menu.slots) {
            if (slot != null && !slot.getItem().isEmpty() && slot.getItem().getItem() == item) {
                return slot.index;
            }
        }
        return -1;
    }

    private int findNonEmptySlot(AbstractContainerMenu menu, int slotId) {
        for (Slot slot : menu.slots) {
            if (slot != null && slot.index == slotId && !slot.getItem().isEmpty()) {
                return slot.index;
            }
        }
        return -1;
    }

    private void clickContainerSlot(int syncId, int slotId) {
        mc.gameMode.handleInventoryMouseClick(syncId, slotId, 0, ClickType.PICKUP, mc.player);
        lastActionTime = System.currentTimeMillis();
    }

    private int getTargetPage(int griefNumber) {
        return ((Math.max(1, griefNumber) - 1) / 32) + 1;
    }

    private int parseCurrentPage(String title) {
        Matcher matcher = PAGE_PATTERN.matcher(title);
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private void updateScreenSignature() {
        String currentSignature;
        if (mc.screen instanceof ContainerScreen container) {
            currentSignature = container.getClass().getName() + "|" + clean(container.getTitle().getString());
        } else if (mc.screen != null) {
            currentSignature = mc.screen.getClass().getName();
        } else {
            currentSignature = "";
        }

        if (!currentSignature.equals(lastScreenSignature)) {
            lastScreenSignature = currentSignature;
            lastScreenChangeTime = System.currentTimeMillis();
        }
    }

    private boolean canAct(long delayMs) {
        return System.currentTimeMillis() - lastActionTime >= delayMs;
    }

    private boolean screenSettled() {
        return System.currentTimeMillis() - lastScreenChangeTime >= 180L;
    }

    private long baseDelayMs() {
        return (long) Math.max(100L, clickDelay.getValue() * 1000.0D);
    }

    private long menuDelayMs() {
        return baseDelayMs() + 60L;
    }

    private long pageDelayMs() {
        return baseDelayMs() + 110L;
    }

    private long targetDelayMs() {
        return baseDelayMs() + 90L;
    }

    private void resetState(boolean queueRetry) {
        lastActionTime = 0L;
        lastScreenChangeTime = 0L;
        lastJoinAttemptTime = 0L;
        retryQueued = queueRetry;
        successNotified = false;
        lastScreenSignature = "";
    }

    private int targetGrief() {
        return griefSelection.getValue().intValue();
    }

    private static String clean(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        StringBuilder out = new StringBuilder(value.length());
        boolean skipFormatting = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (skipFormatting) {
                skipFormatting = false;
                continue;
            }
            if (c == '\u00A7') {
                skipFormatting = true;
                continue;
            }
            if (c == 194) {
                continue;
            }
            out.append(c == '\u00A0' ? ' ' : c);
        }
        return out.toString().toLowerCase(Locale.ROOT).trim();
    }

    private static boolean containsAny(String value, String... patterns) {
        for (String pattern : patterns) {
            if (value.contains(pattern)) {
                return true;
            }
        }
        return false;
    }
}
