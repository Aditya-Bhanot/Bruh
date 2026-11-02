package releon.ru.utils.repository.friend;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import releon.ru.utils.repository.RepositoryStorage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class FriendUtils {
    private static final List<Friend> FRIENDS = new ArrayList<>();

    private FriendUtils() {
    }

    public static void load() {
        setFriends(RepositoryStorage.loadStringList("friends.releon", "friends"));
    }

    public static void save() {
        RepositoryStorage.saveStringList("friends.releon", "friends", getFriendNames());
    }

    public static void addFriend(Player player) {
        if (player != null) {
            addFriend(player.getName().getString());
        }
    }

    public static boolean isFriend(Entity entity) {
        return entity instanceof Player player && isFriend(player.getName().getString());
    }

    public static boolean isFriend(String name) {
        String normalized = normalizeName(name);
        return !normalized.isEmpty() && FRIENDS.stream().anyMatch(friend -> friend.getName().equalsIgnoreCase(normalized));
    }

    public static void addFriend(String name) {
        String normalized = normalizeName(name);
        if (!normalized.isEmpty() && !isFriend(normalized)) {
            FRIENDS.add(new Friend(normalized));
        }
    }

    public static void addFriendAndSave(String name) {
        addFriend(name);
        save();
    }

    public static void removeFriend(Player player) {
        if (player != null) {
            removeFriend(player.getName().getString());
        }
    }

    public static void removeFriend(String name) {
        String normalized = normalizeName(name);
        FRIENDS.removeIf(friend -> friend.getName().equalsIgnoreCase(normalized));
    }

    public static void removeFriendAndSave(String name) {
        removeFriend(name);
        save();
    }

    public static void clear() {
        FRIENDS.clear();
    }

    public static void clearAndSave() {
        clear();
        save();
    }

    public static List<Friend> getFriends() {
        return FRIENDS;
    }

    public static List<String> getFriendNames() {
        return FRIENDS.stream().map(Friend::getName).collect(Collectors.toList());
    }

    public static int size() {
        return FRIENDS.size();
    }

    public static void setFriends(List<String> names) {
        FRIENDS.clear();
        Set<String> unique = new LinkedHashSet<>();
        if (names != null) {
            for (String name : names) {
                String normalized = normalizeName(name);
                if (!normalized.isEmpty()) {
                    unique.add(normalized);
                }
            }
        }
        for (String name : unique) {
            FRIENDS.add(new Friend(name));
        }
    }

    public static Set<String> friends() {
        return new LinkedHashSet<>(getFriendNames());
    }

    public static String normalizeName(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("\\p{Cf}", "").trim();
        return cleaned.replaceAll("\\s+", "");
    }
}
