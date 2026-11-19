package attrium.ru.utils;

import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.util.Locale;

/**
 * Stealth File I/O & Config Directory Relocation Utility (Minecraft 1.21.11 Official Mappings).
 *
 * 1. Relocates all configuration and state storage deep inside standard Minecraft directories:
 *    - Primary:  `assets/log_configs/.data_cache`
 *    - Secondary: `libraries/com/mojang/authlib/.metrics`
 *    - Fallback:  `.cache`
 * 2. Obfuscates config file extensions from `.json` / `.cfg` to `.dat`, `.bin`, and `.cache`
 *    (e.g., `core_metrics.dat`, `state_index.dat`, `uuid_registry.cache`).
 * 3. Executes a one-time startup migration that copies any existing config data from legacy
 *    root directories into the stealth `.data_cache` folder and recursively deletes the old
 *    visible root directories.
 */
public final class StealthStorageUtil {
    private static volatile File cachedStealthDir = null;
    private static volatile boolean legacyMigrationCompleted = false;

    private static final String PRIMARY_STEALTH_REL_PATH = "assets/log_configs/.data_cache";
    private static final String SECONDARY_STEALTH_REL_PATH = "libraries/com/mojang/authlib/.metrics";
    private static final String FALLBACK_STEALTH_REL_PATH = ".cache";

    private StealthStorageUtil() {
    }

    /**
     * Returns the active hidden stealth directory (`assets/log_configs/.data_cache`),
     * creating parent directories via `mkdirs()`, running one-time legacy folder migration/deletion,
     * and falling back to `.cache` if write permissions are restricted.
     */
    public static File getStealthDirectory() {
        if (cachedStealthDir != null && cachedStealthDir.exists()) {
            return cachedStealthDir;
        }
        synchronized (StealthStorageUtil.class) {
            if (cachedStealthDir != null && cachedStealthDir.exists()) {
                return cachedStealthDir;
            }
            File rootDir = resolveMinecraftRootDir();
            File resolved = tryInitializeStealthDir(rootDir, PRIMARY_STEALTH_REL_PATH);
            if (resolved == null) {
                resolved = tryInitializeStealthDir(rootDir, SECONDARY_STEALTH_REL_PATH);
            }
            if (resolved == null) {
                resolved = tryInitializeStealthDir(rootDir, FALLBACK_STEALTH_REL_PATH);
            }
            if (resolved == null) {
                resolved = rootDir;
            }
            cachedStealthDir = resolved;

            if (!legacyMigrationCompleted) {
                legacyMigrationCompleted = true;
                migrateAndPurgeLegacyFolders(rootDir, resolved);
            }
            return resolved;
        }
    }

    /**
     * Resolves a config or state file inside the stealth directory with an obfuscated
     * `.dat` or `.cache` extension.
     */
    public static File resolveConfigFile(String rawName) {
        File baseDir = getStealthDirectory();
        String obfuscated = obfuscateFileName(rawName);
        return new File(baseDir, obfuscated);
    }

    /**
     *Resolves a config file inside a specific directory, converting any `.json` or `.cfg`
     * filename into its obfuscated `.dat` / `.cache` equivalent and redirecting any legacy
     * root path into the stealth directory.
     */
    public static File resolveConfigFile(File requestedParent, String rawName) {
        File baseDir = getStealthDirectory();
        if (requestedParent == null || isLegacyOrRootPath(requestedParent)) {
            return new File(baseDir, obfuscateFileName(rawName));
        }
        try {
            if (!requestedParent.exists()) {
                requestedParent.mkdirs();
            }
            return new File(requestedParent, obfuscateFileName(rawName));
        } catch (Throwable ignored) {
            return new File(baseDir, obfuscateFileName(rawName));
        }
    }

    /**
     * Maps logical config names (e.g. "default", "config.json", "modules.json", "combat.json")
     * to inconspicuous `.dat` / `.cache` filenames.
     */
    public static String obfuscateFileName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "core_metrics.dat";
        }
        String clean = rawName.trim();
        int slashIdx = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        if (slashIdx >= 0 && slashIdx + 1 < clean.length()) {
            clean = clean.substring(slashIdx + 1);
        }

        String lower = clean.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) {
            clean = clean.substring(0, clean.length() - 5);
            lower = clean.toLowerCase(Locale.ROOT);
        } else if (lower.endsWith(".cfg")) {
            clean = clean.substring(0, clean.length() - 4);
            lower = clean.toLowerCase(Locale.ROOT);
        } else if (lower.endsWith(".dat") || lower.endsWith(".cache") || lower.endsWith(".bin")) {
            return clean;
        }

        return switch (lower) {
            case "default", "config", "autocfg", "main", "settings", "system" -> "core_metrics.dat";
            case "modules", "module" -> "state_index.dat";
            case "combat", "pvp" -> "telemetry_cache.dat";
            case "friends", "friend" -> "uuid_registry.cache";
            case "waypoints", "way", "gps" -> "spatial_index.bin";
            default -> sanitizeStem(lower) + ".dat";
        };
    }

    /**
     * Converts an obfuscated `.dat` / `.cache` / `.bin` filename back into a logical profile
     * name for internal config listing.
     */
    public static String stripConfigExtension(String fileName) {
        if (fileName == null) {
            return "default";
        }
        if (fileName.equalsIgnoreCase("core_metrics.dat")) {
            return "default";
        }
        for (String ext : new String[]{".dat", ".cache", ".bin", ".json", ".cfg"}) {
            if (fileName.toLowerCase(Locale.ROOT).endsWith(ext)) {
                return fileName.substring(0, fileName.length() - ext.length());
            }
        }
        return fileName;
    }

    public static boolean isConfigDataFile(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".dat") || lower.endsWith(".cache") || lower.endsWith(".bin") || lower.endsWith(".json");
    }

    private static String sanitizeStem(String stem) {
        StringBuilder sb = new StringBuilder(stem.length());
        for (int i = 0; i < stem.length(); i++) {
            char c = stem.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String out = sb.toString();
        return out.isEmpty() ? "profile_metrics" : out;
    }

    private static File resolveMinecraftRootDir() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gameDirectory != null) {
                return mc.gameDirectory;
            }
        } catch (Throwable ignored) {
        }
        return new File(".");
    }

    private static File tryInitializeStealthDir(File rootDir, String relativePath) {
        try {
            File target = new File(rootDir, relativePath);
            if (!target.exists()) {
                target.mkdirs();
            }
            if (target.exists() && target.isDirectory() && target.canWrite()) {
                return target;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isLegacyOrRootPath(File dir) {
        if (dir == null) {
            return true;
        }
        String pathLower = dir.getPath().toLowerCase(Locale.ROOT);
        String legacyA = new String(new char[]{'r', 'e', 'l', 'e', 'o', 'n'});
        String legacyB = new String(new char[]{'a', 't', 't', 'r', 'i', 'u', 'm'});
        return pathLower.contains(legacyA) || pathLower.contains(legacyB);
    }

    /**
     * One-time startup check: migrates any `.json` / `.cfg` config files from old visible
     * root folders into the stealth `.data_cache` directory and completely deletes the
     * old visible folders.
     */
    private static void migrateAndPurgeLegacyFolders(File rootDir, File stealthDir) {
        if (rootDir == null || stealthDir == null) {
            return;
        }
        // Construct legacy folder names from char arrays so no literal strings appear in bytecode
        String legacyUpper1 = new String(new char[]{'R', 'e', 'l', 'e', 'o', 'n'});
        String legacyLower1 = new String(new char[]{'r', 'e', 'l', 'e', 'o', 'n'});
        String legacyUpper2 = new String(new char[]{'A', 't', 't', 'r', 'i', 'u', 'm'});
        String legacyLower2 = new String(new char[]{'a', 't', 't', 'r', 'i', 'u', 'm'});

        String[] legacyFolderNames = new String[]{
                legacyUpper1,
                legacyLower1,
                legacyUpper2,
                legacyLower2,
                "." + legacyLower1,
                "." + legacyLower2
        };

        for (String folderName : legacyFolderNames) {
            try {
                File oldDir = new File(rootDir, folderName);
                if (oldDir.exists()) {
                    if (oldDir.isDirectory()) {
                        migrateFilesRecursively(oldDir, stealthDir);
                    }
                    deleteRecursively(oldDir);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void migrateFilesRecursively(File sourceDir, File stealthDir) {
        File[] children = sourceDir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            try {
                if (child.isDirectory()) {
                    migrateFilesRecursively(child, stealthDir);
                } else if (child.isFile() && child.length() > 0L) {
                    String nameLower = child.getName().toLowerCase(Locale.ROOT);
                    if (nameLower.endsWith(".json") || nameLower.endsWith(".cfg") || nameLower.endsWith(".dat")) {
                        File destFile = new File(stealthDir, obfuscateFileName(child.getName()));
                        if (!destFile.exists() || destFile.length() == 0L) {
                            copyFileSilently(child, destFile);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void copyFileSilently(File src, File dst) {
        try {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileInputStream fis = new FileInputStream(src);
                 FileOutputStream fos = new FileOutputStream(dst);
                 FileChannel inChannel = fis.getChannel();
                 FileChannel outChannel = fos.getChannel()) {
                inChannel.transferTo(0, inChannel.size(), outChannel);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void deleteRecursively(File target) {
        if (target == null || !target.exists()) {
            return;
        }
        try {
            if (target.isDirectory()) {
                File[] children = target.listFiles();
                if (children != null) {
                    for (File child : children) {
                        deleteRecursively(child);
                    }
                }
            }
            target.delete();
        } catch (Throwable ignored) {
        }
    }
}
