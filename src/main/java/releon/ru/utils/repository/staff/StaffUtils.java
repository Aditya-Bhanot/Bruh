package releon.ru.utils.repository.staff;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import releon.ru.utils.repository.RepositoryStorage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class StaffUtils {
    private static final List<Staff> STAFF = new ArrayList<>();

    private StaffUtils() {
    }

    public static void load() {
        setStaff(RepositoryStorage.loadStringList("staff.releon", "staff"));
    }

    public static void save() {
        RepositoryStorage.saveStringList("staff.releon", "staff", getStaffNames());
    }

    public static void addStaff(String name) {
        String normalized = normalizeName(name);
        if (!normalized.isEmpty() && !isStaff(normalized)) {
            STAFF.add(new Staff(normalized));
        }
    }

    public static void addStaffAndSave(String name) {
        addStaff(name);
        save();
    }

    public static void removeStaff(String name) {
        String normalized = normalizeName(name);
        STAFF.removeIf(staff -> staff.getName().equalsIgnoreCase(normalized));
    }

    public static void removeStaffAndSave(String name) {
        removeStaff(name);
        save();
    }

    public static boolean isStaff(Entity entity) {
        return entity instanceof Player player && isStaff(player.getName().getString());
    }

    public static boolean isStaff(String name) {
        String normalized = normalizeName(name);
        return !normalized.isEmpty() && STAFF.stream().anyMatch(staff -> staff.getName().equalsIgnoreCase(normalized));
    }

    public static void clear() {
        STAFF.clear();
    }

    public static void clearAndSave() {
        clear();
        save();
    }

    public static List<Staff> getStaffList() {
        return STAFF;
    }

    public static List<String> getStaffNames() {
        return STAFF.stream().map(Staff::getName).collect(Collectors.toList());
    }

    public static int size() {
        return STAFF.size();
    }

    public static void setStaff(List<String> names) {
        STAFF.clear();
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
            STAFF.add(new Staff(name));
        }
    }

    public static String normalizeName(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("\\p{Cf}", "").trim();
        return cleaned.replaceAll("\\s+", "");
    }
}
