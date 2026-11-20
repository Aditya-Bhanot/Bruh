package attrium.ru.api.module.impl.misc;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import attrium.ru.api.module.Module;
import attrium.ru.api.module.ModuleCategory;
import attrium.ru.api.settings.impl.BooleanSetting;
import attrium.ru.api.settings.impl.StringSetting;
import attrium.ru.manager.FriendManager;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * NameProtect / Streamer Mode Module (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * Masks the local player's username and integrated FriendManager allies across chat,
 * overhead nametags, tab list, scoreboard sidebar, and TargetHUD, plus optional
 * scoreboard IP/domain scrambling and default Steve/Alex skin override.
 *
 * Zero-Allocation Pipeline for FoldCraftLauncher:
 *  - Bounded 256-entry LinkedHashMap LRU cache for both regular and scoreboard strings.
 *  - Zero Regex in hot paths; fast case-insensitive scan returns original Component/String
 *    untouched when no match is present.
 *  - Preserves original Component Styles (colors, bold, hover/click events) via Component#visit.
 */
public class NameProtectModule extends Module {
    private static volatile NameProtectModule instance;

    private static final int MAX_LRU_ENTRIES = 256;
    private static final Object CACHE_LOCK = new Object();

    private static final LinkedHashMap<String, String> TEXT_LRU =
            new LinkedHashMap<String, String>(MAX_LRU_ENTRIES + 16, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_LRU_ENTRIES;
                }
            };

    private static final LinkedHashMap<String, String> SCOREBOARD_LRU =
            new LinkedHashMap<String, String>(MAX_LRU_ENTRIES + 16, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_LRU_ENTRIES;
                }
            };

    private static final String[] DOMAIN_TLDS = new String[]{
            ".com", ".net", ".org", ".gg", ".xyz", ".io", ".me", ".fun",
            ".club", ".top", ".us", ".eu", ".in", ".co", ".tv", ".mc", ".play"
    };

    private static final UUID STEVE_DEFAULT_UUID = new UUID(0L, 0L);
    private static Object cachedDefaultSkin = null;
    private static boolean defaultSkinResolved = false;

    private final StringSetting customAlias = register(new StringSetting(
            "Custom Alias",
            "Replacement username shown instead of your real IGN across chat, tab, nametags, and HUD.",
            "AttriumUser",
            24
    ).onChange(NameProtectModule::invalidateCache));

    private final BooleanSetting protectFriends = register(new BooleanSetting(
            "Protect Friends",
            "Automatically masks all saved FriendManager usernames with their alias (or Friend).",
            true
    ));

    private final BooleanSetting scrambleScoreboard = register(new BooleanSetting(
            "Scramble Scoreboard",
            "Masks server IPs and domain names in the sidebar scoreboard while streaming.",
            true
    ));

    private final BooleanSetting hideSkin = register(new BooleanSetting(
            "Hide Skin",
            "Replaces your local player skin/cape with the default Steve texture to prevent stream sniping.",
            false
    ));

    // Track last seen toggle states to auto-invalidate cache if changed via ClickGUI/config
    private static volatile boolean lastProtectFriends = true;
    private static volatile boolean lastScrambleScoreboard = true;
    private static volatile String lastCustomAlias = "AttriumUser";

    public NameProtectModule() {
        super("NameProtect", "Masks your username and friends across chat and HUD",
                resolveMiscCategory());
        instance = this;
    }

    private static ModuleCategory resolveMiscCategory() {
        for (ModuleCategory cat : ModuleCategory.values()) {
            String n = cat.name().toUpperCase(Locale.ROOT);
            if (n.equals("MISC") || n.contains("MISC") || n.equals("OTHER") || n.contains("UTIL")) {
                return cat;
            }
        }
        for (ModuleCategory cat : ModuleCategory.values()) {
            String n = cat.name().toUpperCase(Locale.ROOT);
            if (n.equals("PLAYER") || n.equals("CLIENT")) {
                return cat;
            }
        }
        ModuleCategory[] vals = ModuleCategory.values();
        return vals.length > 0 ? vals[vals.length - 1] : ModuleCategory.VISUAL;
    }

    public static NameProtectModule getInstance() {
        return instance;
    }

    public static boolean isActive() {
        NameProtectModule m = instance;
        return m != null && m.isEnabled();
    }

    public static boolean isHideSkinActive() {
        NameProtectModule m = instance;
        return m != null && m.isEnabled() && Boolean.TRUE.equals(m.hideSkin.getValue());
    }

    public static boolean isProtectFriendsActive() {
        NameProtectModule m = instance;
        return m != null && m.isEnabled() && Boolean.TRUE.equals(m.protectFriends.getValue());
    }

    public static boolean isScrambleScoreboardActive() {
        NameProtectModule m = instance;
        return m != null && m.isEnabled() && Boolean.TRUE.equals(m.scrambleScoreboard.getValue());
    }

    public static String getCustomAliasValue() {
        NameProtectModule m = instance;
        if (m == null) {
            return "AttriumUser";
        }
        String val = m.customAlias.getValue();
        return (val != null && !val.trim().isEmpty()) ? val.trim() : "AttriumUser";
    }

    /**
     * Clears both bounded LRU caches (called whenever Custom Alias or FriendManager changes).
     */
    public static void invalidateCache() {
        synchronized (CACHE_LOCK) {
            TEXT_LRU.clear();
            SCOREBOARD_LRU.clear();
        }
    }

    private static void syncCacheInvalidationState() {
        NameProtectModule m = instance;
        if (m == null) return;
        String alias = getCustomAliasValue();
        boolean pf = Boolean.TRUE.equals(m.protectFriends.getValue());
        boolean sc = Boolean.TRUE.equals(m.scrambleScoreboard.getValue());
        if (pf != lastProtectFriends || sc != lastScrambleScoreboard || !alias.equals(lastCustomAlias)) {
            lastProtectFriends = pf;
            lastScrambleScoreboard = sc;
            lastCustomAlias = alias;
            invalidateCache();
        }
    }

    /**
     * Resolves the local player's authentic username without allocating.
     */
    public static String getLocalUsername() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return "";
        }
        try {
            if (mc.getUser() != null) {
                String u = mc.getUser().getName();
                if (u != null && !u.isEmpty()) {
                    return u;
                }
            }
        } catch (Throwable ignored) {
        }
        if (mc.player != null) {
            return mc.player.getName().getString();
        }
        return "";
    }

    /**
     * Centralized zero-allocation string replacement pipeline (backed by 256-entry LRU cache).
     */
    public static String protect(String input) {
        return protectInternal(input, false);
    }

    /**
     * Scoreboard variant of protect(): also masks server IPs and domains when Scramble Scoreboard is on.
     */
    public static String protectScoreboard(String input) {
        return protectInternal(input, true);
    }

    private static String protectInternal(String input, boolean scoreboardMode) {
        if (input == null || input.isEmpty() || !isActive()) {
            return input;
        }
        syncCacheInvalidationState();

        LinkedHashMap<String, String> cache = scoreboardMode ? SCOREBOARD_LRU : TEXT_LRU;
        synchronized (CACHE_LOCK) {
            String cached = cache.get(input);
            if (cached != null) {
                return cached;
            }
        }

        String result = input;
        String localName = getLocalUsername();
        String alias = getCustomAliasValue();

        if (!localName.isEmpty() && !localName.equals(alias) && containsIgnoreCase(result, localName)) {
            result = replaceIgnoreCaseFast(result, localName, alias);
        }

        if (isProtectFriendsActive()) {
            List<FriendManager.FriendRecord> friends = FriendManager.getSortedFriends();
            for (int i = 0; i < friends.size(); i++) {
                FriendManager.FriendRecord rec = friends.get(i);
                if (rec == null || rec.username == null || rec.username.isEmpty()) {
                    continue;
                }
                if (containsIgnoreCase(result, rec.username)) {
                    String replacement = (rec.alias != null && !rec.alias.isEmpty()
                            && !rec.alias.equalsIgnoreCase(rec.username))
                            ? rec.alias
                            : "Friend";
                    result = replaceIgnoreCaseFast(result, rec.username, replacement);
                }
            }
        }

        if (scoreboardMode && isScrambleScoreboardActive()) {
            result = maskServerAddressFast(result);
        }

        synchronized (CACHE_LOCK) {
            cache.put(input, result);
        }
        return result;
    }

    /**
     * Protects a Minecraft Component while preserving all original Style colors, formatting,
     * hover events, and click events via Component#visit. Returns the original Component
     * untouched (zero allocations) if no protected token appears in its text.
     */
    public static Component protectComponent(Component component) {
        return protectComponentInternal(component, false);
    }

    /**
     * Scoreboard variant of protectComponent().
     */
    public static Component protectScoreboardComponent(Component component) {
        return protectComponentInternal(component, true);
    }

    private static Component protectComponentInternal(Component component, boolean scoreboardMode) {
        if (component == null || !isActive()) {
            return component;
        }
        String full = component.getString();
        if (full == null || full.isEmpty()) {
            return component;
        }
        String protectedFull = protectInternal(full, scoreboardMode);
        if (protectedFull.equals(full)) {
            return component;
        }

        MutableComponent rebuilt = Component.empty();
        component.visit((Style style, String part) -> {
            if (part != null && !part.isEmpty()) {
                String replaced = protectInternal(part, scoreboardMode);
                rebuilt.append(Component.literal(replaced).withStyle(style));
            }
            return Optional.empty();
        }, Style.EMPTY);

        // Fallback if a username spanned across multiple sibling formatting runs
        String rebuiltStr = rebuilt.getString();
        if (rebuiltStr.equals(full)) {
            return Component.literal(protectedFull).withStyle(component.getStyle());
        }
        return rebuilt;
    }

    /**
     * Resolves and caches Minecraft's default Steve PlayerSkin object via reflection
     * so NameProtectSkinMixin compiles cleanly across 1.21.9-1.21.11 PlayerSkin package layouts.
     */
    public static Object getDefaultSteveSkin() {
        if (defaultSkinResolved) {
            return cachedDefaultSkin;
        }
        defaultSkinResolved = true;
        try {
            Class<?> cls = Class.forName("net.minecraft.client.resources.DefaultPlayerSkin");
            try {
                Method m = cls.getMethod("get", UUID.class);
                cachedDefaultSkin = m.invoke(null, STEVE_DEFAULT_UUID);
                if (cachedDefaultSkin != null) {
                    return cachedDefaultSkin;
                }
            } catch (Throwable ignored) {
            }
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType().getSimpleName().contains("Skin")) {
                    m.setAccessible(true);
                    cachedDefaultSkin = m.invoke(null);
                    if (cachedDefaultSkin != null) {
                        return cachedDefaultSkin;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // =========================================================================
    // Fast Zero-Regex String Utilities
    // =========================================================================

    private static boolean containsIgnoreCase(String haystack, String needle) {
        int nLen = needle.length();
        int hLen = haystack.length();
        if (nLen == 0 || nLen > hLen) {
            return false;
        }
        char firstLo = Character.toLowerCase(needle.charAt(0));
        char firstUp = Character.toUpperCase(needle.charAt(0));
        int max = hLen - nLen;
        for (int i = 0; i <= max; i++) {
            char c = haystack.charAt(i);
            if ((c == firstLo || c == firstUp) && haystack.regionMatches(true, i, needle, 0, nLen)) {
                return true;
            }
        }
        return false;
    }

    private static String replaceIgnoreCaseFast(String text, String target, String replacement) {
        int tLen = target.length();
        int len = text.length();
        if (tLen == 0 || tLen > len) {
            return text;
        }
        StringBuilder sb = null;
        int idx = 0;
        int last = 0;
        char firstLo = Character.toLowerCase(target.charAt(0));
        char firstUp = Character.toUpperCase(target.charAt(0));
        while (idx <= len - tLen) {
            char c = text.charAt(idx);
            if ((c == firstLo || c == firstUp) && text.regionMatches(true, idx, target, 0, tLen)) {
                if (sb == null) {
                    sb = new StringBuilder(len + Math.max(0, replacement.length() - tLen));
                }
                sb.append(text, last, idx).append(replacement);
                idx += tLen;
                last = idx;
            } else {
                idx++;
            }
        }
        if (sb == null) {
            return text;
        }
        sb.append(text, last, len);
        return sb.toString();
    }

    /**
     * Masks server domains (e.g. play.example.net) and IPv4 addresses without Regex.
     */
    private static String maskServerAddressFast(String text) {
        if (text == null || text.length() < 4 || text.indexOf('.') < 0) {
            return text;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < DOMAIN_TLDS.length; i++) {
            String tld = DOMAIN_TLDS[i];
            int pos = lower.indexOf(tld);
            if (pos > 0) {
                int end = pos + tld.length();
                boolean boundaryOk = (end == lower.length())
                        || !Character.isLetterOrDigit(lower.charAt(end));
                if (boundaryOk && Character.isLetterOrDigit(lower.charAt(pos - 1))) {
                    return "play.attrium.gg";
                }
            }
        }
        // Check simple IPv4 pattern (at least 3 dots surrounded by digits)
        int dotCount = 0;
        for (int i = 1; i < text.length() - 1; i++) {
            if (text.charAt(i) == '.'
                    && Character.isDigit(text.charAt(i - 1))
                    && Character.isDigit(text.charAt(i + 1))) {
                dotCount++;
            }
        }
        if (dotCount >= 3) {
            return "play.attrium.gg";
        }
        return text;
    }
}
