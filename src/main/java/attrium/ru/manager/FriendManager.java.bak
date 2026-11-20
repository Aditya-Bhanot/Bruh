package attrium.ru.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import attrium.ru.api.module.Module;
import attrium.ru.utils.repository.friend.FriendUtils;
import attrium.ru.utils.sound.SoundUtil;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global FriendManager, UUID Anti-Bot Whitelist & Zero-Allocation Visual Aliasing Engine
 * (Minecraft 1.21.11 Official Mojang Mappings).
 *
 * 1. Thread-Safe UUID Data Structure:
 *    - Stores friends in `ConcurrentHashMap<UUID, String>` mapping each player's authentic `UUID`
 *      to their custom alias (or authentic username), ensuring lock-free reads during render & tick loops.
 * 2. Strict UUID Verification (Anti-Bot & /nick Proof):
 *    - `isFriend(Entity)` inspects `player.getGameProfile().getId()` / `entity.getUUID()` directly
 *      against the `ConcurrentHashMap<UUID, String>`, ignoring spoofed custom display names.
 * 3. Look-At Player Keybind ("Friend Bind"):
 *    - Supports binding any keyboard key or mouse button in the ClickGUI "Friends" tab to instantly
 *      add/remove the player currently under the user's crosshair.
 * 4. Conditional Visual Aliasing with Bounded Cache:
 *    - Replaces friend usernames in Chat and Nametags ONLY when the `NameProtect` / `Hide Nametag`
 *      module is enabled, caching replaced strings in a bounded map so zero per-frame allocations occur.
 */
public final class FriendManager {
    private static final FriendManager INSTANCE = new FriendManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Primary thread-safe UUID -> Alias/DisplayName map required by specification
    private static final ConcurrentHashMap<UUID, String> FRIENDS = new ConcurrentHashMap<>();

    // Metadata map storing authentic username alongside UUID and custom alias for UI & aliasing
    private static final ConcurrentHashMap<UUID, FriendRecord> FRIEND_RECORDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, FriendRecord> FRIENDS_BY_LOWER_NAME = new ConcurrentHashMap<>();

    // Bounded string aliasing cache (cleared only when friend list mutates — zero per-frame GC churn)
    private static final ConcurrentHashMap<String, String> ALIASED_TEXT_CACHE = new ConcurrentHashMap<>();
    private static final int MAX_ALIAS_CACHE_SIZE = 512;

    private static volatile List<FriendRecord> cachedSortedEntries = Collections.emptyList();
    private static volatile boolean entriesDirty = true;
    private static volatile boolean initialized = false;

    // Keybind for clicking while looking at a player in-game to add/toggle them as a friend
    private static volatile int friendBindKey = -1;
    private static boolean wasFriendBindDownLastTick = false;
    private static long lastFriendBindActionMs = 0L;

    public static final class FriendRecord {
        public final UUID uuid;
        public final String username;
        public final String alias;

        public FriendRecord(UUID uuid, String username, String alias) {
            this.uuid = uuid;
            this.username = (username != null && !username.isBlank()) ? username.trim() : "Friend";
            this.alias = (alias != null && !alias.isBlank()) ? alias.trim() : this.username;
        }

        public String getDisplayLabel() {
            if (alias != null && !alias.equalsIgnoreCase(username)) {
                return username + " (" + alias + ")";
            }
            return username;
        }
    }

    private FriendManager() {
    }

    public static FriendManager getInstance() {
        ensureLoaded();
        return INSTANCE;
    }

    public static ConcurrentHashMap<UUID, String> getFriendsMap() {
        ensureLoaded();
        return FRIENDS;
    }

    public static synchronized void ensureLoaded() {
        if (initialized) {
            return;
        }
        initialized = true;
        loadFromFile();
    }

    // =========================================================================
    // 1. UUID-Based Friend Verification (Anti-Bot & /nick Proof)
    // =========================================================================

    /**
     * Primary Stage-1 TargetManager & ESP check.
     * Operates strictly on the entity's authentic `GameProfile` UUID (`entity.getUUID()`),
     * preventing fake server-spawned NPC bots or `/nick` players from bypassing the filter.
     */
    /**
     * Verifies that a player exists in the active tab list (`mc.getConnection().getOnlinePlayers()`)
     * with a matching GameProfile UUID so fake server-spawned bots are rejected while supporting
     * both online-mode (v4) and offline-mode (v3) servers.
     */
    public static boolean isVerifiedInTabList(Minecraft mc, UUID entityUuid, String profileName) {
        if (mc == null || mc.getConnection() == null) {
            return true;
        }
        if (entityUuid == null && (profileName == null || profileName.isBlank())) {
            return false;
        }
        try {
            Collection<PlayerInfo> online = mc.getConnection().getOnlinePlayers();
            if (online == null || online.isEmpty()) {
                return true;
            }
            for (PlayerInfo info : online) {
                if (info == null || info.getProfile() == null) {
                    continue;
                }
                GameProfile tabProf = info.getProfile();
                if (entityUuid != null && entityUuid.equals(tabProf.id())) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static boolean isFriend(Entity entity) {
        if (entity == null) {
            return false;
        }
        ensureLoaded();
        if (FRIENDS.isEmpty() && FRIENDS_BY_LOWER_NAME.isEmpty()) {
            return false;
        }
        if (entity instanceof Player player) {
            GameProfile profile = player.getGameProfile();
            UUID uuid = (profile != null && profile.id() != null) ? profile.id() : player.getUUID();
            String rawName = (profile != null && profile.name() != null) ? profile.name().trim() : null;

            // Anti-bot tab-list verification: ensure the entity matches a real tab-list GameProfile
            if (!isVerifiedInTabList(Minecraft.getInstance(), uuid, rawName)) {
                return false;
            }

            // 1. Direct Dual-UUID match (matches online v4 UUID or offline v3 UUID)
            if (uuid != null && FRIENDS.containsKey(uuid)) {
                return true;
            }

            // 2. Match by raw GameProfile.name() (persists even under /nick) or computed offline v3 UUID
            if (rawName != null && !rawName.isEmpty()) {
                String lower = rawName.toLowerCase(Locale.ROOT);
                FriendRecord byName = FRIENDS_BY_LOWER_NAME.get(lower);
                if (byName != null) {
                    if (uuid != null) {
                        FRIENDS.putIfAbsent(uuid, byName.alias);
                    }
                    return true;
                }
                if (reconcileOnlineProfileIfMatched(uuid, rawName)) {
                    return true;
                }
            }
            return false;
        }
        UUID entityUuid = entity.getUUID();
        return entityUuid != null && FRIENDS.containsKey(entityUuid);
    }

    public static boolean isFriend(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        ensureLoaded();
        return FRIENDS.containsKey(uuid);
    }

    /**
     * Secondary lookup by raw username for legacy compatibility and offline checks.
     */
    public static boolean isFriend(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        ensureLoaded();
        if (FRIENDS.isEmpty()) {
            return false;
        }
        String clean = username.trim();
        UUID offlineUuid = computeOfflineUuid(clean);
        if (FRIENDS.containsKey(offlineUuid)) {
            return true;
        }
        for (FriendRecord rec : FRIEND_RECORDS.values()) {
            if (rec != null && (rec.username.equalsIgnoreCase(clean) || rec.alias.equalsIgnoreCase(clean))) {
                return true;
            }
        }
        return false;
    }

    private static boolean reconcileOnlineProfileIfMatched(UUID liveUuid, String profileName) {
        if (liveUuid == null || profileName == null || profileName.isEmpty()) {
            return false;
        }
        String clean = profileName.trim();
        FriendRecord matched = FRIENDS_BY_LOWER_NAME.get(clean.toLowerCase(Locale.ROOT));
        if (matched == null) {
            matched = FRIEND_RECORDS.get(computeOfflineUuid(clean));
        }
        if (matched == null) {
            matched = FRIEND_RECORDS.get(computeLowercaseOfflineUuid(clean));
        }
        if (matched != null) {
            FRIENDS.put(liveUuid, matched.alias);
            FRIENDS.put(computeOfflineUuid(clean), matched.alias);
            FRIENDS.put(computeLowercaseOfflineUuid(clean), matched.alias);
            if (!liveUuid.equals(matched.uuid)) {
                FRIEND_RECORDS.remove(matched.uuid);
                FriendRecord upgraded = new FriendRecord(liveUuid, clean, matched.alias);
                FRIEND_RECORDS.put(liveUuid, upgraded);
                FRIENDS_BY_LOWER_NAME.put(clean.toLowerCase(Locale.ROOT), upgraded);
                entriesDirty = true;
                ALIASED_TEXT_CACHE.clear();
        attrium.ru.api.module.impl.misc.NameProtectModule.invalidateCache();
                saveToFile();
            }
            return true;
        }
        return false;
    }

    /**
     * Reconciles online lobby players once per tick so any friend added by name while offline
     * is immediately bound to their authentic online `GameProfile` UUID.
     */
    public static void reconcileOnlinePlayerProfile(Player player) {
        if (player == null) {
            return;
        }
        ensureLoaded();
        if (FRIENDS.isEmpty()) {
            return;
        }
        GameProfile profile = player.getGameProfile();
        if (profile != null && profile.id() != null && profile.name() != null) {
            if (!FRIENDS.containsKey(profile.id())) {
                reconcileOnlineProfileIfMatched(profile.id(), profile.name());
            }
        }
    }

    // =========================================================================
    // 2. Adding, Removing & Resolving Friends (UUID + JSON Persistence)
    // =========================================================================

    /**
     * Adds a friend from an in-game `Player` entity using their authentic `GameProfile` UUID.
     */
    public static boolean addFriend(Player player) {
        if (player == null) {
            return false;
        }
        GameProfile profile = player.getGameProfile();
        UUID uuid = (profile != null && profile.id() != null) ? profile.id() : player.getUUID();
        String name = (profile != null && profile.name() != null && !profile.name().isBlank())
                ? profile.name()
                : player.getName().getString();
        return addFriend(uuid, name, name);
    }

    /**
     * Resolves a typed `input` (supports `"Username"` or `"Username:CustomAlias"`) to a `UUID`
     * by checking `mc.level.players()` and `mc.getConnection().getOnlinePlayers()` first,
     * falling back to a deterministic offline UUID, and saving to JSON.
     */
    public static boolean addFriendByNameOrAliasInput(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return false;
        }
        String trimmed = rawInput.trim();
        String username = trimmed;
        String alias = trimmed;

        int sep = trimmed.indexOf(':');
        if (sep > 0 && sep < trimmed.length() - 1) {
            username = trimmed.substring(0, sep).trim();
            alias = trimmed.substring(sep + 1).trim();
        }
        if (username.isEmpty()) {
            return false;
        }
        if (alias.isEmpty()) {
            alias = username;
        }

        UUID resolvedUuid = resolvePlayerUuidFromLobbyOrTabList(username);
        if (resolvedUuid == null) {
            resolvedUuid = computeOfflineUuid(username);
        }

        return addFriend(resolvedUuid, username, alias);
    }

    public static synchronized boolean addFriend(UUID uuid, String username, String alias) {
        if (uuid == null || username == null || username.isBlank()) {
            return false;
        }
        ensureLoaded();
        String cleanUser = username.trim();
        String cleanAlias = (alias != null && !alias.isBlank()) ? alias.trim() : cleanUser;

        // Remove any prior offline record for the same username
        for (Map.Entry<UUID, FriendRecord> entry : new ArrayList<>(FRIEND_RECORDS.entrySet())) {
            FriendRecord existing = entry.getValue();
            if (existing != null && existing.username.equalsIgnoreCase(cleanUser) && !entry.getKey().equals(uuid)) {
                FRIEND_RECORDS.remove(entry.getKey());
                FRIENDS.remove(entry.getKey());
            }
        }

        FriendRecord record = new FriendRecord(uuid, cleanUser, cleanAlias);
        FRIENDS.put(uuid, cleanAlias);
        FRIENDS.put(computeOfflineUuid(cleanUser), cleanAlias);
        FRIENDS.put(computeLowercaseOfflineUuid(cleanUser), cleanAlias);
        FRIEND_RECORDS.put(uuid, record);
        FRIENDS_BY_LOWER_NAME.put(cleanUser.toLowerCase(Locale.ROOT), record);
        entriesDirty = true;
        ALIASED_TEXT_CACHE.clear();
        attrium.ru.api.module.impl.misc.NameProtectModule.invalidateCache();

        syncLegacyFriendUtils(cleanUser, true);
        HudManager.pushNotification(cleanUser, "Added Friend", HudManager.TYPE_ENABLED);
        saveToFile();
        return true;
    }

    public static synchronized boolean removeFriend(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        ensureLoaded();
        FriendRecord removed = FRIEND_RECORDS.remove(uuid);
        if (removed != null && removed.username != null) {
            FRIENDS.remove(computeOfflineUuid(removed.username));
            FRIENDS.remove(computeLowercaseOfflineUuid(removed.username));
            FRIENDS_BY_LOWER_NAME.remove(removed.username.toLowerCase(Locale.ROOT));
        }
        String removedAlias = FRIENDS.remove(uuid);
        if (removed != null || removedAlias != null) {
            entriesDirty = true;
            ALIASED_TEXT_CACHE.clear();
        attrium.ru.api.module.impl.misc.NameProtectModule.invalidateCache();
            if (removed != null) {
                syncLegacyFriendUtils(removed.username, false);
                HudManager.pushNotification(removed.username, "Removed Friend", HudManager.TYPE_DISABLED);
            }
            saveToFile();
            return true;
        }
        return false;
    }

    public static synchronized boolean removeFriendByName(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        ensureLoaded();
        String clean = username.trim();
        boolean removedAny = false;
        for (Map.Entry<UUID, FriendRecord> entry : new ArrayList<>(FRIEND_RECORDS.entrySet())) {
            FriendRecord rec = entry.getValue();
            if (rec != null && (rec.username.equalsIgnoreCase(clean) || rec.alias.equalsIgnoreCase(clean))) {
                FRIEND_RECORDS.remove(entry.getKey());
                FRIENDS.remove(entry.getKey());
                removedAny = true;
            }
        }
        if (removedAny) {
            entriesDirty = true;
            ALIASED_TEXT_CACHE.clear();
        attrium.ru.api.module.impl.misc.NameProtectModule.invalidateCache();
            syncLegacyFriendUtils(clean, false);
            saveToFile();
        }
        return removedAny;
    }

    public static List<FriendRecord> getSortedFriends() {
        ensureLoaded();
        if (entriesDirty) {
            List<FriendRecord> list = new ArrayList<>(FRIEND_RECORDS.values());
            list.sort(Comparator.comparing(r -> r.username.toLowerCase(Locale.ROOT)));
            cachedSortedEntries = Collections.unmodifiableList(list);
            entriesDirty = false;
        }
        return cachedSortedEntries;
    }

    private static UUID resolvePlayerUuidFromLobbyOrTabList(String username) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return null;
        }
        if (mc.level != null) {
            for (Player player : mc.level.players()) {
                if (player == null) {
                    continue;
                }
                GameProfile gp = player.getGameProfile();
                if (gp != null && gp.name() != null && gp.name().equalsIgnoreCase(username)) {
                    return gp.id() != null ? gp.id() : player.getUUID();
                }
                if (player.getName().getString().equalsIgnoreCase(username)) {
                    return player.getUUID();
                }
            }
        }
        ClientPacketListener conn = mc.getConnection();
        if (conn != null) {
            for (PlayerInfo info : conn.getOnlinePlayers()) {
                if (info == null || info.getProfile() == null) {
                    continue;
                }
                GameProfile gp = info.getProfile();
                if (gp.name() != null && gp.name().equalsIgnoreCase(username) && gp.id() != null) {
                    return gp.id();
                }
            }
        }
        return null;
    }

    /**
     * Resolves an online player's `PlayerInfo` if they are in the current server tab list,
     * allowing the ClickGUI "Friends" tab to render their 2D head avatar without importing ResourceLocation.
     */
    public static PlayerInfo resolveOnlineFriendPlayerInfo(UUID uuid, String username) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getConnection() == null) {
            return null;
        }
        try {
            for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                if (info == null || info.getProfile() == null) {
                    continue;
                }
                GameProfile gp = info.getProfile();
                if ((uuid != null && uuid.equals(gp.id()))
                        || (username != null && username.equalsIgnoreCase(gp.name()))) {
                    return info;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Computes vanilla Minecraft's deterministic Version 3 offline UUID (`"OfflinePlayer:" + name`).
     */
    public static UUID computeOfflineUuid(String username) {
        String clean = (username != null && !username.isBlank()) ? username.trim() : "friend";
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + clean).getBytes(StandardCharsets.UTF_8));
    }

    public static UUID computeLowercaseOfflineUuid(String username) {
        String lower = (username != null && !username.isBlank()) ? username.trim().toLowerCase(Locale.ROOT) : "friend";
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + lower).getBytes(StandardCharsets.UTF_8));
    }

    private static void syncLegacyFriendUtils(String username, boolean add) {
        try {
            for (Method m : FriendUtils.class.getDeclaredMethods()) {
                if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class) {
                    String n = m.getName().toLowerCase(Locale.ROOT);
                    if (add && n.contains("add")) {
                        m.setAccessible(true);
                        m.invoke(null, username);
                        break;
                    } else if (!add && (n.contains("remove") || n.contains("del"))) {
                        m.setAccessible(true);
                        m.invoke(null, username);
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // =========================================================================
    // 3. Friend Bind (Click Keybind While Looking at a Player to Add/Remove Friend)
    // =========================================================================

    public static int getFriendBindKey() {
        ensureLoaded();
        return friendBindKey;
    }

    public static void setFriendBindKey(int key) {
        ensureLoaded();
        int normalized = (key == 0 || key == GLFW.GLFW_KEY_UNKNOWN) ? -1 : key;
        friendBindKey = normalized;
        saveToFile();
    }

    /**
     * Polled every client tick when `mc.screen == null`.
     * When the user presses their configured Friend Bind while looking at a player,
     * toggles that player in `FriendManager` by their authentic `GameProfile` UUID.
     */
    public static void pollFriendKeybindInGame(Minecraft mc, boolean isKeyDown) {
        if (mc == null || mc.player == null || mc.level == null || mc.screen != null) {
            wasFriendBindDownLastTick = false;
            return;
        }
        boolean risingEdge = isKeyDown && !wasFriendBindDownLastTick;
        wasFriendBindDownLastTick = isKeyDown;
        if (!risingEdge) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastFriendBindActionMs < 150L) {
            return;
        }
        lastFriendBindActionMs = now;

        Player targetPlayer = findPlayerInCrosshair(mc, 64.0D);
        if (targetPlayer == null || targetPlayer == mc.player) {
            return;
        }

        GameProfile gp = targetPlayer.getGameProfile();
        UUID uuid = (gp != null && gp.id() != null) ? gp.id() : targetPlayer.getUUID();
        String name = (gp != null && gp.name() != null && !gp.name().isBlank())
                ? gp.name()
                : targetPlayer.getName().getString();

        if (isFriend(uuid)) {
            removeFriend(uuid);
            SoundUtil.playTogglePop(false);
            notifyClientChat(mc, "\u00A77[\u00A7bAttrium\u00A77] \u00A7cRemoved \u00A7f" + name + " \u00A7cfrom friends.");
        } else {
            addFriend(uuid, name, name);
            SoundUtil.playTogglePop(true);
            notifyClientChat(mc, "\u00A77[\u00A7bAttrium\u00A77] \u00A7aAdded \u00A7b" + name + " \u00A7aas a friend.");
        }
    }

    public static Player findPlayerInCrosshair(Minecraft mc, double maxDistance) {
        if (mc == null || mc.player == null || mc.level == null) {
            return null;
        }
        if (mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() instanceof Player hitPlayer) {
            return hitPlayer;
        }

        Entity cameraEntity = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
        Vec3 eyePos = cameraEntity.getEyePosition(1.0F);
        Vec3 lookVec = cameraEntity.getViewVector(1.0F);
        Vec3 endPos = eyePos.add(lookVec.scale(maxDistance));
        AABB searchBox = cameraEntity.getBoundingBox().expandTowards(lookVec.scale(maxDistance)).inflate(1.0D);

        EntityHitResult rayHit = ProjectileUtil.getEntityHitResult(
                cameraEntity,
                eyePos,
                endPos,
                searchBox,
                entity -> entity instanceof Player p && p != mc.player && p.isAlive() && !p.isSpectator(),
                maxDistance * maxDistance
        );
        if (rayHit != null && rayHit.getType() != HitResult.Type.MISS && rayHit.getEntity() instanceof Player p) {
            return p;
        }
        return null;
    }

    private static void notifyClientChat(Minecraft mc, String message) {
        try {
            if (mc != null && mc.gui != null && mc.gui.getChat() != null) {
                mc.gui.getChat().addMessage(Component.literal(message));
            }
        } catch (Throwable ignored) {
        }
    }

    // =========================================================================
    // 4. Conditional Visual Aliasing (Chat & Nametag — Active ONLY When NameProtect Enabled)
    // =========================================================================

    /**
     * Checks whether the `NameProtect` / `Hide Nametag` module is currently enabled.
     */
    private static Method cachedActiveModulesMethod = null;
    private static boolean activeModulesMethodScanned = false;

    public static boolean isNameProtectOrHideNametagEnabled() {
        try {
            if (!activeModulesMethodScanned) {
                activeModulesMethodScanned = true;
                for (Method m : Module.class.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 && java.util.Collection.class.isAssignableFrom(m.getReturnType())) {
                        String mn = m.getName().toLowerCase(Locale.ROOT);
                        if (mn.contains("active") || mn.contains("module")) {
                            m.setAccessible(true);
                            cachedActiveModulesMethod = m;
                            break;
                        }
                    }
                }
            }
            if (cachedActiveModulesMethod != null) {
                Object res = cachedActiveModulesMethod.invoke(null);
                if (res instanceof java.util.Collection<?> col) {
                    for (Object obj : col) {
                        if (obj instanceof Module m && m.isEnabled()) {
                            String n = m.getName() != null ? m.getName().toLowerCase(Locale.ROOT) : "";
                            String cls = m.getClass().getSimpleName().toLowerCase(Locale.ROOT);
                            if (n.contains("nameprotect") || n.contains("name protect")
                                    || n.contains("hidenametag") || n.contains("hide nametag")
                                    || n.contains("nametag") || n.contains("streamer")
                                    || cls.contains("nameprotect") || cls.contains("hidenametag")) {
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Replaces any friend's authentic username in `rawText` with their assigned alias (or `"Friend"`
     * if their alias equals their username) ONLY when `NameProtect` / `Hide Nametag` is enabled.
     * Uses a bounded `ConcurrentHashMap` cache so zero per-frame `String` allocations occur.
     */
    public static String applyConditionalFriendAlias(String rawText) {
        if (rawText == null || rawText.isEmpty() || FRIENDS.isEmpty() || !isNameProtectOrHideNametagEnabled()) {
            return rawText;
        }
        String cached = ALIASED_TEXT_CACHE.get(rawText);
        if (cached != null) {
            return cached;
        }

        String result = rawText;
        for (FriendRecord rec : FRIEND_RECORDS.values()) {
            if (rec == null || rec.username.isEmpty()) {
                continue;
            }
            if (result.contains(rec.username)) {
                String replacement = (!rec.alias.isEmpty() && !rec.alias.equalsIgnoreCase(rec.username))
                        ? rec.alias
                        : "Friend";
                result = result.replace(rec.username, replacement);
            }
        }

        if (ALIASED_TEXT_CACHE.size() >= MAX_ALIAS_CACHE_SIZE) {
            ALIASED_TEXT_CACHE.clear();
        attrium.ru.api.module.impl.misc.NameProtectModule.invalidateCache();
        }
        ALIASED_TEXT_CACHE.put(rawText, result);
        return result;
    }

    public static Component applyConditionalFriendAlias(Component component) {
        if (component == null || FRIENDS.isEmpty() || !isNameProtectOrHideNametagEnabled()) {
            return component;
        }
        String raw = component.getString();
        String aliased = applyConditionalFriendAlias(raw);
        if (aliased.equals(raw)) {
            return component;
        }
        MutableComponent out = Component.literal(aliased);
        out.setStyle(component.getStyle());
        return out;
    }

    // =========================================================================
    // 5. JSON Persistence (`friends.json` / `friends.json`)
    // =========================================================================

    private static File resolveFriendsStorageFile() {
        Minecraft mc = Minecraft.getInstance();
        File base = (mc != null && mc.gameDirectory != null) ? mc.gameDirectory : new File(".");
        return new File(base, "friends.json");
    }

    public static synchronized void saveToFile() {
        try {
            File file = resolveFriendsStorageFile();
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            JsonObject root = new JsonObject();
            root.addProperty("friendBindKey", friendBindKey);

            JsonArray arr = new JsonArray();
            for (FriendRecord rec : FRIEND_RECORDS.values()) {
                if (rec == null || rec.uuid == null) {
                    continue;
                }
                JsonObject obj = new JsonObject();
                obj.addProperty("uuid", rec.uuid.toString());
                obj.addProperty("username", rec.username);
                obj.addProperty("alias", rec.alias);
                arr.add(obj);
            }
            root.add("friends", arr);

            Files.writeString(file.toPath(), GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void loadFromFile() {
        try {
            File file = resolveFriendsStorageFile();
            if (!file.exists() || file.length() == 0L) {
                return;
            }
            String raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            if (raw == null || raw.isBlank()) {
                return;
            }

            JsonElement parsed = JsonParser.parseString(raw);
            if (parsed.isJsonObject()) {
                JsonObject root = parsed.getAsJsonObject();
                if (root.has("friendBindKey")) {
                    friendBindKey = root.get("friendBindKey").getAsInt();
                }
                if (root.has("friends") && root.get("friends").isJsonArray()) {
                    loadFriendsArray(root.getAsJsonArray("friends"));
                }
            } else if (parsed.isJsonArray()) {
                loadFriendsArray(parsed.getAsJsonArray());
            }
            entriesDirty = true;
            ALIASED_TEXT_CACHE.clear();
        attrium.ru.api.module.impl.misc.NameProtectModule.invalidateCache();
        } catch (Throwable ignored) {
        }
    }

    private static void loadFriendsArray(JsonArray arr) {
        for (JsonElement el : arr) {
            try {
                if (el.isJsonPrimitive()) {
                    String name = el.getAsString();
                    if (name != null && !name.isBlank()) {
                        UUID uuid = computeOfflineUuid(name);
                        FriendRecord rec = new FriendRecord(uuid, name, name);
                        FRIENDS.put(uuid, rec.alias);
                        FRIEND_RECORDS.put(uuid, rec);
                            FRIENDS.put(computeOfflineUuid(rec.username), rec.alias);
                            FRIENDS.put(computeLowercaseOfflineUuid(rec.username), rec.alias);
                            FRIENDS_BY_LOWER_NAME.put(rec.username.toLowerCase(Locale.ROOT), rec);
                    }
                } else if (el.isJsonObject()) {
                    JsonObject obj = el.getAsJsonObject();
                    String name = obj.has("username") ? obj.get("username").getAsString()
                            : (obj.has("name") ? obj.get("name").getAsString() : "");
                    String alias = obj.has("alias") ? obj.get("alias").getAsString() : name;
                    UUID uuid = obj.has("uuid") ? UUID.fromString(obj.get("uuid").getAsString()) : computeOfflineUuid(name);
                    if (name != null && !name.isBlank() && uuid != null) {
                        FriendRecord rec = new FriendRecord(uuid, name, alias);
                        FRIENDS.put(uuid, rec.alias);
                        FRIEND_RECORDS.put(uuid, rec);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
