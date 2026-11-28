package attrium.ru.auth;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

/**
 * Pure-Java Device Fingerprinting (FoldCraftLauncher + Cracked Launcher + Desktop Safe).
 *
 * Uses zero native DLLs, zero Windows registry/wmic calls, and zero Minecraft account UUIDs.
 * Computes a stable 64-char SHA-256 hex fingerprint cached once per JVM session.
 */
public final class DeviceFingerprint {
    private static final int[] P_BYTES = new int[]{92, 73, 73, 79, 98, 75, 12, 98, 4, 91, 9, 94, 15, 92, 5, 88, 10, 95, 12, 89, 98, 91, 77};
    private static String p() {
        byte[] o = new byte[P_BYTES.length];
        for (int i = 0; i < P_BYTES.length; i++) o[i] = (byte) (P_BYTES[i] ^ 0x3D);
        return new String(o, StandardCharsets.UTF_8);
    }
    private static volatile String cachedFingerprint = null;

    private DeviceFingerprint() {
    }

    public static String getFingerprint() {
        String existing = cachedFingerprint;
        if (existing != null) {
            return existing;
        }
        synchronized (DeviceFingerprint.class) {
            if (cachedFingerprint == null) {
                cachedFingerprint = computeFingerprint();
            }
            return cachedFingerprint;
        }
    }

    private static String computeFingerprint() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("cores=").append(Runtime.getRuntime().availableProcessors()).append('|');
        sb.append("arch=").append(safeProp("os.arch")).append('|');
        sb.append("os=").append(safeProp("os.name")).append('|');
        sb.append("user=").append(safeProp("user.name")).append('|');
        sb.append("hw=").append(readHardwareSignature()).append('|');
        sb.append(p());

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >>> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Throwable t) {
            return "0000000000000000000000000000000000000000000000000000000000000000";
        }
    }

    private static String readHardwareSignature() {
        // Android / FoldCraftLauncher / Linux: read stable CPU fields from /proc/cpuinfo
        try {
            Path cpuInfo = Path.of("/proc/cpuinfo");
            if (Files.isReadable(cpuInfo)) {
                List<String> lines = Files.readAllLines(cpuInfo, StandardCharsets.UTF_8);
                String hardware = "";
                String implementer = "";
                String part = "";
                String modelName = "";
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    int colon = line.indexOf(':');
                    if (colon <= 0) continue;
                    String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    String val = line.substring(colon + 1).trim();
                    if (hardware.isEmpty() && key.equals("hardware")) {
                        hardware = val;
                    } else if (implementer.isEmpty() && key.equals("cpu implementer")) {
                        implementer = val;
                    } else if (part.isEmpty() && key.equals("cpu part")) {
                        part = val;
                    } else if (modelName.isEmpty() && key.equals("model name")) {
                        modelName = val;
                    }
                }
                String combined = hardware + "/" + implementer + "/" + part + "/" + modelName;
                if (!combined.equals("///")) {
                    return combined.toLowerCase(Locale.ROOT);
                }
            }
        } catch (Throwable ignored) {
        }

        // Windows / Desktop fallback
        String procId = System.getenv("PROCESSOR_IDENTIFIER");
        String procRev = System.getenv("PROCESSOR_REVISION");
        return ((procId != null ? procId : "") + "/" + (procRev != null ? procRev : "")).toLowerCase(Locale.ROOT);
    }

    private static String safeProp(String key) {
        try {
            String v = System.getProperty(key);
            return v != null ? v.trim().toLowerCase(Locale.ROOT) : "";
        } catch (Throwable t) {
            return "";
        }
    }
}
