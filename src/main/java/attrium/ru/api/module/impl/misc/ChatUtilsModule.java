package attrium.ru.api.module.impl.misc;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.ModuleCategory;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.NumberSetting;
import attrium.ru.manager.ClientThemeManager;
import attrium.ru.manager.FriendManager;
import attrium.ru.manager.HudManager;

import java.lang.reflect.Method;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ChatUtils Module (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * Smart chat filtering (Hide Chat with Nearby Players, Mentions + Alert, Friends, and DMs),
 * [x2] Aqua-Cyan anti-spam message stacking with a 15-slot ring buffer, clean transparent chat
 * background, zero-allocation [HH:mm] timestamps, 500-line extended history with no-clear on
 * world/lobby switch, Click-to-Copy on ChatScreen, and delayed AutoGG with 10s cooldown.
 */
public class ChatUtilsModule extends Module {
    private static volatile ChatUtilsModule instance;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final Style TIMESTAMP_STYLE = Style.EMPTY.withColor(0x9CA3AF);

    // 15-entry anti-spam ring buffer (zero per-message heap churn)
    private static final int SPAM_BUFFER_SIZE = 15;
    private static final class SpamEntry {
        String normalizedPlain;
        int count;
        Component lastRenderedComponent;
    }
    private static final SpamEntry[] SPAM_RING = new SpamEntry[SPAM_BUFFER_SIZE];
    private static int spamRingCursor = 0;
    static {
        for (int i = 0; i < SPAM_BUFFER_SIZE; i++) {
            SPAM_RING[i] = new SpamEntry();
        }
    }

    // Cached lowercase local username / alias
    private static String cachedRawIgn = "";
    private static String cachedLowerIgn = "";
    private static String cachedRawAlias = "";
    private static String cachedLowerAlias = "";

    // AutoGG delayed dispatch & 10s cooldown state
    private static long pendingAutoGgAtMs = 0L;
    private static long lastAutoGgSentMs = 0L;
    private static final long AUTOGG_COOLDOWN_MS = 10_000L;

    // Click-to-Copy edge state & reusable StringBuilder
    private static boolean wasChatLeftMouseDown = false;
    private static final StringBuilder LINE_EXTRACTOR_SB = new StringBuilder(128);

    // Cached ChatComponent refresh method (1.21.11 reflection-safe)
    private static boolean refreshMethodInit = false;
    private static Method cachedRefreshTrimmedMethod = null;

    // =========================================================================
    // Settings (Chat Visibility & Filter Group + Anti-Spam & Visual Polish)
    // =========================================================================
    private final BooleanSetting hideChat = register(new BooleanSetting(
            "Hide Chat",
            "Suppresses global chat clutter except active filter exceptions.",
            false
    ));
    private final BooleanSetting nearbyPlayers = register(new BooleanSetting(
            "Nearby Players",
            "Allows chat messages from loaded players within Nearby Range.",
            true
    ));
    private final NumberSetting nearbyRange = register(new NumberSetting(
            "Nearby Range",
            "Maximum distance in blocks to allow nearby player chat.",
            48.0D, 8.0D, 128.0D, 4.0D
    ));
    private final BooleanSetting mentions = register(new BooleanSetting(
            "Mentions",
            "Allows messages containing your username or active NameProtect alias.",
            true
    ));
    private final BooleanSetting mentionAlert = register(new BooleanSetting(
            "Mention Alert",
            "Plays a subtle chime and shows a notification toast when mentioned.",
            true
    ));
    private final BooleanSetting friends = register(new BooleanSetting(
            "Friends",
            "Always allows chat messages from players in your FriendManager list.",
            true
    ));
    private final BooleanSetting directMessages = register(new BooleanSetting(
            "Direct Messages",
            "Allows private whispers matching -> me, whispers to you, or [MSG].",
            true
    ));

    private final BooleanSetting antiSpamStack = register(new BooleanSetting(
            "Anti-Spam Stack",
            "Collapses duplicate recent messages with an Aqua-Cyan [x2] counter.",
            true
    ));
    private final BooleanSetting cleanBackground = register(new BooleanSetting(
            "Clean Background",
            "Removes vanilla dark rectangular chat background boxes.",
            true
    ));
    private final BooleanSetting timestamps = register(new BooleanSetting(
            "Timestamps",
            "Prepends a compact muted [HH:mm] timestamp to incoming chat.",
            false
    ));
    private final BooleanSetting extendedHistory = register(new BooleanSetting(
            "Extended History",
            "Expands chat scrollback to 500 lines and prevents lobby clears.",
            true
    ));
    private final BooleanSetting clickToCopy = register(new BooleanSetting(
            "Click to Copy",
            "Clicking a chat line while ChatScreen is open copies its plain text.",
            true
    ));
    private final BooleanSetting autoGg = register(new BooleanSetting(
            "AutoGG",
            "Sends gg after a 1.2s-2.0s delay when duel or match victory text appears.",
            false
    ));

    public ChatUtilsModule() {
        super("ChatUtils", "Filters chat clutter, stacks duplicate messages, and upgrades chat UX", resolveMiscCategory());
        instance = this;
        this.nearbyRange.visibleWhen(() -> Boolean.TRUE.equals(this.nearbyPlayers.getValue()));
        this.mentionAlert.visibleWhen(() -> Boolean.TRUE.equals(this.mentions.getValue()));
    }

    private static ModuleCategory resolveMiscCategory() {
        for (ModuleCategory cat : ModuleCategory.values()) {
            String n = cat.name().toUpperCase(Locale.ROOT);
            if (n.equals("MISC") || n.equals("UTILITY") || n.equals("PLAYER")) {
                return cat;
            }
        }
        return ModuleCategory.VISUAL;
    }

    public static ChatUtilsModule getInstance() {
        return instance;
    }

    public static boolean isActive() {
        ChatUtilsModule mod = instance;
        return mod != null && mod.isEnabled();
    }

    public static boolean isCleanBackgroundEnabled() {
        ChatUtilsModule mod = instance;
        return mod != null && mod.isEnabled() && Boolean.TRUE.equals(mod.cleanBackground.getValue());
    }

    public static boolean isExtendedHistoryEnabled() {
        ChatUtilsModule mod = instance;
        return mod != null && mod.isEnabled() && Boolean.TRUE.equals(mod.extendedHistory.getValue());
    }

    // =========================================================================
    // 1. Smart Filter Evaluation & AutoGG Detection (Called at ChatComponent#addMessage HEAD)
    // =========================================================================
    public static boolean shouldAllowIncomingMessage(Component message) {
        ChatUtilsModule mod = instance;
        if (mod == null || !mod.isEnabled() || message == null) {
            return true;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) {
            return true;
        }

        String plain = stripFormattingCodes(message.getString());
        if (plain.isEmpty()) {
            return !Boolean.TRUE.equals(mod.hideChat.getValue());
        }

        String lowerFull = plain.toLowerCase(Locale.ROOT);

        // AutoGG check (runs regardless of Hide Chat)
        if (Boolean.TRUE.equals(mod.autoGg.getValue())) {
            checkAndQueueAutoGg(lowerFull);
        }

        // Split header and body at first standard chat delimiter (:, », >, or "] ")
        int splitIdx = findHeaderBodySplit(plain);
        String header = (splitIdx > 0) ? plain.substring(0, splitIdx) : plain;
        String body = (splitIdx > 0 && splitIdx < plain.length()) ? plain.substring(splitIdx) : plain;
        String lowerHeader = header.toLowerCase(Locale.ROOT);
        String lowerBody = body.toLowerCase(Locale.ROOT);

        updateCachedLocalNames(mc);

        // Mentions check (always notify if Mention Alert is enabled and message isn't from ourselves)
        boolean isMention = false;
        if (Boolean.TRUE.equals(mod.mentions.getValue())) {
            boolean fromSelf = !cachedLowerIgn.isEmpty() && containsWholeToken(lowerHeader, cachedLowerIgn);
            if (!fromSelf) {
                if (!cachedLowerIgn.isEmpty() && lowerBody.contains(cachedLowerIgn)) {
                    isMention = true;
                } else if (!cachedLowerAlias.isEmpty() && lowerBody.contains(cachedLowerAlias)) {
                    isMention = true;
                }
            }
            if (isMention && Boolean.TRUE.equals(mod.mentionAlert.getValue())) {
                triggerMentionAlert(mc, plain);
            }
        }

        if (!Boolean.TRUE.equals(mod.hideChat.getValue())) {
            return true;
        }

        // Hide Chat == true -> evaluate selective allowlist
        if (isMention) {
            return true;
        }

        // Always allow our own sent messages
        if (!cachedLowerIgn.isEmpty() && containsWholeToken(lowerHeader, cachedLowerIgn)) {
            return true;
        }

        // Direct Messages check
        if (Boolean.TRUE.equals(mod.directMessages.getValue())) {
            if (lowerHeader.contains("-> me")
                    || lowerHeader.contains("whispers to you")
                    || lowerHeader.contains("[msg]")
                    || lowerFull.contains("-> me")
                    || lowerFull.contains("whispers to you")) {
                return true;
            }
        }

        // Friends & Nearby Players check over loaded players (zero distanceTo sqrt)
        boolean allowFriends = Boolean.TRUE.equals(mod.friends.getValue());
        boolean allowNearby = Boolean.TRUE.equals(mod.nearbyPlayers.getValue());
        if ((allowFriends || allowNearby) && mc.level != null) {
            double range = mod.nearbyRange.getValue();
            double rangeSq = range * range;
            List<AbstractClientPlayer> players = mc.level.players();
            for (int i = 0; i < players.size(); i++) {
                AbstractClientPlayer p = players.get(i);
                if (p == null) continue;
                String pName = p.getName().getString();
                if (pName == null || pName.isEmpty()) continue;
                String pLower = pName.toLowerCase(Locale.ROOT);
                if (!containsWholeToken(lowerHeader, pLower)) {
                    continue;
                }
                if (allowFriends && FriendManager.isFriend(p)) {
                    return true;
                }
                if (allowNearby && mc.player.distanceToSqr(p) <= rangeSq) {
                    return true;
                }
            }
        }

        return false;
    }

    // =========================================================================
    // 2. [x2] Anti-Spam Stacking & [HH:mm] Timestamps
    // =========================================================================
    public static Component transformIncomingMessage(
            Component message,
            List<GuiMessage> allMessages,
            List<GuiMessage.Line> trimmedMessages,
            ChatComponent chatComponent
    ) {
        ChatUtilsModule mod = instance;
        if (mod == null || !mod.isEnabled() || message == null) {
            return message;
        }

        Component result = message;

        if (Boolean.TRUE.equals(mod.antiSpamStack.getValue())) {
            String plain = stripFormattingCodes(message.getString());
            String normalized = stripTrailingCountSuffix(stripLeadingTimestamp(plain)).trim();
            if (!normalized.isEmpty()) {
                SpamEntry matched = null;
                for (int i = 0; i < SPAM_BUFFER_SIZE; i++) {
                    SpamEntry entry = SPAM_RING[i];
                    if (entry.normalizedPlain != null && entry.normalizedPlain.equals(normalized)) {
                        matched = entry;
                        break;
                    }
                }
                if (matched != null) {
                    matched.count++;
                    removePreviousMatchingMessage(allMessages, matched.lastRenderedComponent, chatComponent);
                    int accentRgb = ClientThemeManager.getAccentRgb();
                    MutableComponent stacked = message.copy();
                    stacked.append(Component.literal(" [x" + matched.count + "]")
                            .withStyle(Style.EMPTY.withColor(accentRgb)));
                    result = stacked;
                    if (Boolean.TRUE.equals(mod.timestamps.getValue())) {
                        result = prependTimestamp(result);
                    }
                    matched.lastRenderedComponent = result;
                    return result;
                } else {
                    if (Boolean.TRUE.equals(mod.timestamps.getValue())) {
                        result = prependTimestamp(result);
                    }
                    SpamEntry slot = SPAM_RING[spamRingCursor];
                    spamRingCursor = (spamRingCursor + 1) % SPAM_BUFFER_SIZE;
                    slot.normalizedPlain = normalized;
                    slot.count = 1;
                    slot.lastRenderedComponent = result;
                    return result;
                }
            }
        }

        if (Boolean.TRUE.equals(mod.timestamps.getValue())) {
            result = prependTimestamp(result);
        }
        return result;
    }

    private static Component prependTimestamp(Component original) {
        String timeStr = TIME_FORMATTER.format(LocalTime.now());
        MutableComponent prefix = Component.literal("[" + timeStr + "] ").withStyle(TIMESTAMP_STYLE);
        prefix.append(original);
        return prefix;
    }

    private static void removePreviousMatchingMessage(
            List<GuiMessage> allMessages,
            Component prevComponent,
            ChatComponent chatComponent
    ) {
        if (allMessages == null || prevComponent == null || allMessages.isEmpty()) {
            return;
        }
        boolean removed = false;
        for (int i = 0; i < allMessages.size() && i < 32; i++) {
            GuiMessage gm = allMessages.get(i);
            if (gm != null && gm.content() == prevComponent) {
                allMessages.remove(i);
                removed = true;
                break;
            }
        }
        if (!removed) {
            String prevStr = prevComponent.getString();
            for (int i = 0; i < allMessages.size() && i < 24; i++) {
                GuiMessage gm = allMessages.get(i);
                if (gm != null && gm.content() != null && prevStr.equals(gm.content().getString())) {
                    allMessages.remove(i);
                    removed = true;
                    break;
                }
            }
        }
        if (removed && chatComponent != null) {
            invokeRefreshTrimmedMessages(chatComponent);
        }
    }

    private static void invokeRefreshTrimmedMessages(ChatComponent chatComponent) {
        if (!refreshMethodInit) {
            refreshMethodInit = true;
            for (String candidate : new String[]{"refreshTrimmedMessages", "rescaleChat"}) {
                try {
                    Method m = ChatComponent.class.getDeclaredMethod(candidate);
                    m.setAccessible(true);
                    cachedRefreshTrimmedMethod = m;
                    break;
                } catch (Throwable ignored) {
                }
            }
        }
        if (cachedRefreshTrimmedMethod != null) {
            try {
                cachedRefreshTrimmedMethod.invoke(chatComponent);
            } catch (Throwable ignored) {
            }
        }
    }

    // =========================================================================
    // 3. Click-to-Copy while ChatScreen is Open (Zero Mixin Signature Risk)
    // =========================================================================
    public static void tickClickToCopy(List<GuiMessage.Line> trimmedMessages, int chatScrollbarPos) {
        ChatUtilsModule mod = instance;
        Minecraft mc = Minecraft.getInstance();
        if (mod == null || !mod.isEnabled() || !Boolean.TRUE.equals(mod.clickToCopy.getValue())
                || mc == null || !(mc.screen instanceof ChatScreen) || mc.getWindow() == null
                || trimmedMessages == null || trimmedMessages.isEmpty()) {
            wasChatLeftMouseDown = false;
            return;
        }

        long win = mc.getWindow().handle();
        if (win == 0L) {
            return;
        }
        boolean down = GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean clicked = down && !wasChatLeftMouseDown;
        wasChatLeftMouseDown = down;
        if (!clicked) {
            return;
        }

        double[] mx = new double[1];
        double[] my = new double[1];
        GLFW.glfwGetCursorPos(win, mx, my);
        int fbW = Math.max(1, mc.getWindow().getScreenWidth());
        int fbH = Math.max(1, mc.getWindow().getScreenHeight());
        int guiW = Math.max(1, mc.getWindow().getGuiScaledWidth());
        int guiH = Math.max(1, mc.getWindow().getGuiScaledHeight());
        double mouseX = mx[0] * guiW / fbW;
        double mouseY = my[0] * guiH / fbH;

        double chatScale = Mth.clamp(mc.options.chatScale().get(), 0.01D, 1.0D);
        double chatX = (mouseX - 2.0D) / chatScale;
        int chatWidth = ChatComponent.getWidth(mc.options.chatWidth().get());
        if (chatX < -4.0D || chatX > chatWidth + 4.0D) {
            return;
        }

        double lineSpacing = mc.options.chatLineSpacing().get();
        double lineStep = 9.0D * (lineSpacing + 1.0D);
        double bottomY = guiH - 40.0D;
        double relFromBottom = (bottomY - mouseY) / chatScale;
        if (relFromBottom < 0.0D) {
            return;
        }
        int lineIdx = Mth.floor(relFromBottom / lineStep) + chatScrollbarPos;
        if (lineIdx < 0 || lineIdx >= trimmedMessages.size()) {
            return;
        }

        GuiMessage.Line clickedLine = trimmedMessages.get(lineIdx);
        if (clickedLine == null || clickedLine.content() == null) {
            return;
        }
        String plain = extractPlainText(clickedLine.content()).trim();
        if (plain.isEmpty()) {
            return;
        }
        mc.keyboardHandler.setClipboard(plain);
        HudManager.pushNotification("ChatUtils", "Copied message", HudManager.TYPE_ENABLED);
    }

    private static String extractPlainText(FormattedCharSequence seq) {
        LINE_EXTRACTOR_SB.setLength(0);
        seq.accept((idx, style, codePoint) -> {
            LINE_EXTRACTOR_SB.appendCodePoint(codePoint);
            return true;
        });
        return stripFormattingCodes(LINE_EXTRACTOR_SB.toString());
    }

    // =========================================================================
    // 4. AutoGG Delayed Tick Loop
    // =========================================================================
    public static void onClientTick(Minecraft mc) {
        ChatUtilsModule mod = instance;
        if (mod == null || !mod.isEnabled() || !Boolean.TRUE.equals(mod.autoGg.getValue())) {
            pendingAutoGgAtMs = 0L;
            return;
        }
        if (pendingAutoGgAtMs <= 0L || mc == null || mc.player == null || mc.player.connection == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= pendingAutoGgAtMs) {
            pendingAutoGgAtMs = 0L;
            lastAutoGgSentMs = now;
            try {
                mc.player.connection.sendChat("gg");
            } catch (Throwable ignored) {
            }
        }
    }

    private static void checkAndQueueAutoGg(String lowerMsg) {
        long now = System.currentTimeMillis();
        if (pendingAutoGgAtMs > 0L || (now - lastAutoGgSentMs) < AUTOGG_COOLDOWN_MS) {
            return;
        }
        if (lowerMsg.contains("winner:")
                || lowerMsg.contains("won the duel")
                || lowerMsg.contains("victory!")
                || lowerMsg.contains("1st place")) {
            long delayMs = ThreadLocalRandom.current().nextLong(1200L, 2001L);
            pendingAutoGgAtMs = now + delayMs;
        }
    }

    // =========================================================================
    // 5. Mention Alert & Zero-Regex String Utilities
    // =========================================================================
    private static void triggerMentionAlert(Minecraft mc, String plain) {
        try {
            if (mc.player != null) {
                mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.6F, 1.15F);
            }
        } catch (Throwable ignored) {
        }
        String preview = plain.length() > 36 ? plain.substring(0, 36) + "..." : plain;
        HudManager.pushNotification("Mentioned", preview, HudManager.TYPE_ALERT);
    }

    private static void updateCachedLocalNames(Minecraft mc) {
        String ign = (mc.player != null && mc.player.getName() != null) ? mc.player.getName().getString() : "";
        if (!ign.equals(cachedRawIgn)) {
            cachedRawIgn = ign;
            cachedLowerIgn = ign.toLowerCase(Locale.ROOT);
        }
        String alias = NameProtectModule.isActive() ? NameProtectModule.getActiveAlias() : "";
        if (alias == null) alias = "";
        if (!alias.equals(cachedRawAlias)) {
            cachedRawAlias = alias;
            cachedLowerAlias = alias.toLowerCase(Locale.ROOT);
        }
    }

    public static String stripFormattingCodes(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        int idx = input.indexOf('\u00A7');
        if (idx < 0) {
            return input;
        }
        StringBuilder sb = new StringBuilder(input.length());
        int len = input.length();
        for (int i = 0; i < len; i++) {
            char c = input.charAt(i);
            if (c == '\u00A7' && i + 1 < len) {
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static int findHeaderBodySplit(String plain) {
        int len = plain.length();
        int best = -1;
        for (int i = 0; i < len; i++) {
            char c = plain.charAt(i);
            if (c == ':' || c == '\u00BB' || c == '>') {
                best = i + 1;
                break;
            }
            if (c == ']' && i + 1 < len && plain.charAt(i + 1) == ' ') {
                // Keep scanning if a ':' or '»' appears soon after rank tag (e.g. "[VIP] Steve: hi")
                int colon = plain.indexOf(':', i + 2);
                int arrow = plain.indexOf('\u00BB', i + 2);
                int gt = plain.indexOf('>', i + 2);
                int nextDelim = Integer.MAX_VALUE;
                if (colon > 0 && colon < i + 28) nextDelim = Math.min(nextDelim, colon + 1);
                if (arrow > 0 && arrow < i + 28) nextDelim = Math.min(nextDelim, arrow + 1);
                if (gt > 0 && gt < i + 28) nextDelim = Math.min(nextDelim, gt + 1);
                best = (nextDelim != Integer.MAX_VALUE) ? nextDelim : (i + 2);
                break;
            }
        }
        return best;
    }

    private static boolean containsWholeToken(String haystackLower, String tokenLower) {
        int tLen = tokenLower.length();
        int hLen = haystackLower.length();
        if (tLen == 0 || tLen > hLen) {
            return false;
        }
        int pos = 0;
        while ((pos = haystackLower.indexOf(tokenLower, pos)) >= 0) {
            boolean leftOk = (pos == 0) || !isWordChar(haystackLower.charAt(pos - 1));
            int end = pos + tLen;
            boolean rightOk = (end == hLen) || !isWordChar(haystackLower.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            pos = end;
        }
        return false;
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static String stripLeadingTimestamp(String s) {
        // Strips "[HH:mm] " if present
        if (s.length() >= 8 && s.charAt(0) == '[' && s.charAt(3) == ':' && s.charAt(6) == ']' && s.charAt(7) == ' ') {
            return s.substring(8);
        }
        return s;
    }

    private static String stripTrailingCountSuffix(String s) {
        // Strips trailing " [xN]" without regex
        int len = s.length();
        if (len < 6 || s.charAt(len - 1) != ']') {
            return s;
        }
        int open = s.lastIndexOf(" [x");
        if (open < 0) {
            return s;
        }
        for (int i = open + 3; i < len - 1; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return s;
            }
        }
        return s.substring(0, open);
    }
}
