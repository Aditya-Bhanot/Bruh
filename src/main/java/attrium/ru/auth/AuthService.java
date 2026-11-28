package attrium.ru.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import attrium.ru.manager.ClientThemeManager;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.zip.CRC32;

/**
 * Attrium Async Auth & Tamper-Resistant 24-Hour Session Service (Step 2).
 *
 * - Fetches salted PBKDF2-HMAC-SHA256 list asynchronously over HTTPS (never blocks render thread).
 * - Anchors "now" to GitHub's trusted HTTP Date response header to defeat device clock rollbacks.
 * - Verifies Device Fingerprints (d1, 10,000 iters) for zero-prompt auto-login and
 *   Username:Password credentials (u2, 50,000 iters) in constant time via MessageDigest.isEqual.
 * - Stores an HMAC-SHA256 signed 24-hour session token inside config/acc.json ("cacheSeed").
 * - Uses a signed runtime proof token rather than a single boolean flag.
 */
public final class AuthService {
    // Paste your raw GitHub Gist or Repo raw URL here (e.g. https://gist.githubusercontent.com/USER/ID/raw)
    private static final int[] AUTH_URL_XOR = new int[]{50, 46, 46, 42, 41, 96, 117, 117, 61, 51, 41, 46, 116, 61, 51, 46, 50, 47, 56, 47, 41, 63, 40, 57, 53, 52, 46, 63, 52, 46, 116, 57, 53, 55, 117, 27, 62, 51, 46, 35, 59, 119, 24, 50, 59, 52, 53, 46, 117, 62, 60, 108, 57, 59, 107, 57, 98, 56, 107, 59, 105, 110, 59, 99, 111, 106, 105, 108, 105, 108, 63, 111, 57, 60, 107, 110, 111, 57, 57, 107, 63, 117, 40, 59, 45, 117, 61, 51, 41, 46, 60, 51, 54, 63, 107, 116, 46, 34, 46};
    private static String resolveAuthUrl() {
        byte[] out = new byte[AUTH_URL_XOR.length];
        for (int i = 0; i < AUTH_URL_XOR.length; i++) {
            out[i] = (byte) (AUTH_URL_XOR[i] ^ 0x5A);
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    public static final long SESSION_TTL_MS = 86_400_000L; // Hard 24-hour ceiling
    public static final int MAX_LOGIN_ATTEMPTS = 3;
    private static final long ATTEMPT_COOLDOWN_MS = 1_500L;

    private static final int[] H_BYTES = new int[]{10, 31, 31, 25, 52, 3, 6, 10, 8, 52, 24, 90, 52, 92, 8, 88, 14, 82, 10, 89, 13, 95, 9, 83, 15, 90, 91, 14, 93};
    private static byte[] resolveHmacKey() {
        byte[] o = new byte[H_BYTES.length];
        for (int i = 0; i < H_BYTES.length; i++) o[i] = (byte) (H_BYTES[i] ^ 0x6B);
        return o;
    }
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();
    private static final Base64.Encoder B64_ENC = Base64.getUrlEncoder().withoutPadding();

    private static final class ParsedEntry {
        final String tag; // "d1" or "u2"
        final byte[] salt;
        final byte[] hash;

        ParsedEntry(String tag, byte[] salt, byte[] hash) {
            this.tag = tag;
            this.salt = salt;
            this.hash = hash;
        }
    }

    public enum Status {
        PENDING,
        FETCHING,
        AUTHORIZED,
        LOGIN_REQUIRED,
        LOCKED_OUT,
        NETWORK_ERROR
    }

    private static volatile Status status = Status.PENDING;
    private static volatile String statusMessage = "Initializing...";
    private static volatile long trustedServerNowMs = 0L;
    private static volatile long localMonoAtFetchMs = 0L;
    private static volatile long activeSessionIssuedMs = 0L;
    private static volatile long activeSessionExpiryMs = 0L;
    private static volatile byte[] runtimeProof = null;

    private static final List<ParsedEntry> CACHED_ENTRIES = new ArrayList<>();
    private static volatile int failedAttempts = 0;
    private static volatile long lastAttemptMs = 0L;
    private static volatile boolean bootstrapStarted = false;

    private AuthService() {
    }

    public static Status getStatus() {
        return status;
    }

    public static String getStatusMessage() {
        return statusMessage;
    }

    public static int getRemainingAttempts() {
        return Math.max(0, MAX_LOGIN_ATTEMPTS - failedAttempts);
    }

    /**
     * Validates the signed runtime proof token + 24h ceiling against trusted server time.
     * Checked across TickPipelineManager, HudManager, and GlassClickGuiScreen (fail-closed).
     */
    public static boolean isAuthorized() {
        byte[] proof = runtimeProof;
        if (proof == null || proof.length != 32 || status != Status.AUTHORIZED) {
            return false;
        }
        long now = getTrustedNowMs();
        if (now <= 0L || activeSessionExpiryMs <= 0L) {
            return false;
        }
        if (now > activeSessionExpiryMs
                || (activeSessionExpiryMs - activeSessionIssuedMs) > SESSION_TTL_MS
                || now < activeSessionIssuedMs - 60_000L) {
            revokeRuntimeAuth("Session expired");
            return false;
        }
        String fp = DeviceFingerprint.getFingerprint();
        byte[] expected = computeSessionHmac(fp, activeSessionIssuedMs, activeSessionExpiryMs);
        return expected != null && MessageDigest.isEqual(proof, expected);
    }

    public static long getTrustedNowMs() {
        if (trustedServerNowMs <= 0L) {
            return 0L;
        }
        long elapsed = Math.max(0L, (System.nanoTime() / 1_000_000L) - localMonoAtFetchMs);
        return trustedServerNowMs + elapsed;
    }

    /**
     * Starts async startup verification (safe to call once from ClientModInitializer or first tick).
     */
    public static void retryBootstrapAsync() {
        bootstrapStarted = false;
        bootstrapAsync();
    }

    public static void bootstrapAsync() {
        if (bootstrapStarted) {
            return;
        }
        bootstrapStarted = true;
        status = Status.FETCHING;
        statusMessage = "Connecting...";

        CompletableFuture.runAsync(() -> {
            String url = resolveAuthUrl();
            if (url.isEmpty()) {
                status = Status.NETWORK_ERROR;
                statusMessage = "Auth URL not configured yet.";
                return;
            }
            if (!fetchAuthListSync()) {
                status = Status.NETWORK_ERROR;
                statusMessage = "Unable to reach auth server.";
                return;
            }

            String fp = DeviceFingerprint.getFingerprint();

            // 1. Check if this Device Fingerprint is pre-approved (auto-login without password)
            if (verifyAgainstEntries("d1", fp, 10_000)) {
                long now = getTrustedNowMs();
                grantVerifiedSession(fp, now, now + SESSION_TTL_MS, true);
                return;
            }

            // 2. Check if a valid, untampered 24-hour signed session exists in config/acc.json
            if (tryRestoreSignedSession(fp)) {
                return;
            }

            // 3. Otherwise require modal login
            status = Status.LOGIN_REQUIRED;
            statusMessage = "Sign in to continue";
        });
    }

    /**
     * Async credential verification called from the Modal Login Screen.
     */
    public static CompletableFuture<Boolean> submitLoginAsync(String username, String password) {
        return CompletableFuture.supplyAsync(() -> {
            if (status == Status.LOCKED_OUT) {
                return false;
            }
            long monoNow = System.currentTimeMillis();
            if (monoNow - lastAttemptMs < ATTEMPT_COOLDOWN_MS) {
                statusMessage = "Please wait a moment...";
                return false;
            }
            lastAttemptMs = monoNow;

            String cleanUser = username != null ? username.trim().toLowerCase(Locale.ROOT) : "";
            String cleanPass = password != null ? password : "";
            if (cleanUser.isEmpty() || cleanPass.isEmpty()) {
                statusMessage = "Enter username and password.";
                return false;
            }

            if (CACHED_ENTRIES.isEmpty() && !fetchAuthListSync()) {
                statusMessage = "Network error. Check connection.";
                return false;
            }

            String cred = cleanUser + ":" + cleanPass;
            if (verifyAgainstEntries("u2", cred, 50_000)) {
                long now = getTrustedNowMs();
                String fp = DeviceFingerprint.getFingerprint();
                grantVerifiedSession(fp, now, now + SESSION_TTL_MS, true);
                return true;
            }

            failedAttempts++;
            if (failedAttempts >= MAX_LOGIN_ATTEMPTS) {
                clearStoredSessionSeed();
                revokeRuntimeAuth("Too many failed attempts.");
                status = Status.LOCKED_OUT;
                return false;
            }
            statusMessage = "Invalid credentials (" + getRemainingAttempts() + " left)";
            return false;
        });
    }

    // =========================================================================
    // HTTPS Fetch + HTTP Date Header Parsing + Entry Validation
    // =========================================================================
    private static boolean fetchAuthListSync() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(8))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(resolveAuthUrl()))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Cache-Control", "no-cache")
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return false;
            }

            // Parse trusted HTTP Date header from GitHub's servers
            String dateHeader = resp.headers().firstValue("date").orElse(null);
            if (dateHeader == null || dateHeader.isBlank()) {
                return false;
            }
            ZonedDateTime serverTime = ZonedDateTime.parse(dateHeader.trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
            trustedServerNowMs = serverTime.toInstant().toEpochMilli();
            localMonoAtFetchMs = System.nanoTime() / 1_000_000L;

            List<ParsedEntry> parsed = new ArrayList<>();
            String body = resp.body();
            if (body != null) {
                String[] lines = body.split("\\R");
                for (String rawLine : lines) {
                    ParsedEntry entry = tryParseEntryLine(rawLine);
                    if (entry != null) {
                        parsed.add(entry);
                    }
                }
            }
            synchronized (CACHED_ENTRIES) {
                CACHED_ENTRIES.clear();
                CACHED_ENTRIES.addAll(parsed);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static ParsedEntry tryParseEntryLine(String rawLine) {
        if (rawLine == null) return null;
        String line = rawLine.trim();
        if (line.length() < 40) return null;
        int lastDot = line.lastIndexOf('.');
        if (lastDot <= 4 || lastDot + 5 != line.length()) return null;

        // Format: <2-hex-noise><tag(d1|u2)><salt_b64url>.<hash_b64url>.<4-hex-crc>
        String payloadWithTag = line.substring(2, lastDot);
        String crcHex = line.substring(lastDot + 1).toLowerCase(Locale.ROOT);
        CRC32 crc32 = new CRC32();
        crc32.update(payloadWithTag.getBytes(StandardCharsets.US_ASCII));
        String expectedCrc = String.format(Locale.ROOT, "%04x", crc32.getValue() & 0xFFFFL);
        if (!expectedCrc.equals(crcHex)) {
            return null;
        }

        if (!payloadWithTag.startsWith("d1") && !payloadWithTag.startsWith("u2")) {
            return null;
        }
        String tag = payloadWithTag.substring(0, 2);
        String rest = payloadWithTag.substring(2);
        int dot = rest.indexOf('.');
        if (dot <= 0 || dot >= rest.length() - 1) return null;

        try {
            byte[] salt = B64_DEC.decode(rest.substring(0, dot));
            byte[] hash = B64_DEC.decode(rest.substring(dot + 1));
            if (salt.length != 16 || hash.length != 32) return null;
            return new ParsedEntry(tag, salt, hash);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean verifyAgainstEntries(String tag, String candidate, int iterations) {
        char[] chars = candidate.toCharArray();
        List<ParsedEntry> snapshot;
        synchronized (CACHED_ENTRIES) {
            snapshot = new ArrayList<>(CACHED_ENTRIES);
        }
        try {
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            for (int i = 0; i < snapshot.size(); i++) {
                ParsedEntry e = snapshot.get(i);
                if (!tag.equals(e.tag)) continue;
                PBEKeySpec spec = new PBEKeySpec(chars, e.salt, iterations, 256);
                try {
                    byte[] derived = skf.generateSecret(spec).getEncoded();
                    if (MessageDigest.isEqual(derived, e.hash)) {
                        return true;
                    }
                } finally {
                    spec.clearPassword();
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // =========================================================================
    // Signed 24-Hour Session Storage ("cacheSeed" in config/acc.json)
    // =========================================================================
    private static void grantVerifiedSession(String fp, long issuedAt, long expiry, boolean persist) {
        long clampedExpiry = Math.min(expiry, issuedAt + SESSION_TTL_MS);
        byte[] sig = computeSessionHmac(fp, issuedAt, clampedExpiry);
        if (sig == null) return;
        activeSessionIssuedMs = issuedAt;
        activeSessionExpiryMs = clampedExpiry;
        runtimeProof = sig;
        status = Status.AUTHORIZED;
        statusMessage = "Verified";
        if (persist) {
            saveSignedSessionSeed(fp, issuedAt, clampedExpiry, sig);
        }
    }

    private static void revokeRuntimeAuth(String msg) {
        runtimeProof = null;
        activeSessionIssuedMs = 0L;
        activeSessionExpiryMs = 0L;
        status = Status.LOGIN_REQUIRED;
        statusMessage = msg;
    }

    private static boolean tryRestoreSignedSession(String fp) {
        try {
            Path cfg = ClientThemeManager.getUnifiedConfigPath();
            if (!Files.exists(cfg)) return false;
            JsonElement parsed = JsonParser.parseString(Files.readString(cfg, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) return false;
            JsonObject root = parsed.getAsJsonObject();
            if (!root.has("cacheSeed") || !root.get("cacheSeed").isJsonPrimitive()) return false;

            String token = root.get("cacheSeed").getAsString();
            String decoded = new String(B64_DEC.decode(token), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|");
            if (parts.length != 4) return false;

            String storedFp = parts[0];
            long issuedAt = Long.parseLong(parts[1]);
            long expiry = Long.parseLong(parts[2]);
            byte[] storedSig = B64_DEC.decode(parts[3]);

            if (!fp.equals(storedFp)) return false;
            long now = getTrustedNowMs();
            if (now <= 0L || now > expiry || (expiry - issuedAt) > SESSION_TTL_MS || now < issuedAt - 60_000L) {
                return false;
            }

            byte[] expectedSig = computeSessionHmac(fp, issuedAt, expiry);
            if (expectedSig == null || !MessageDigest.isEqual(storedSig, expectedSig)) {
                return false;
            }
            grantVerifiedSession(fp, issuedAt, expiry, false);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void saveSignedSessionSeed(String fp, long issuedAt, long expiry, byte[] sig) {
        try {
            Path cfg = ClientThemeManager.getUnifiedConfigPath();
            Path parent = cfg.getParent();
            if (parent != null) Files.createDirectories(parent);

            JsonObject root = new JsonObject();
            if (Files.exists(cfg)) {
                try {
                    JsonElement el = JsonParser.parseString(Files.readString(cfg, StandardCharsets.UTF_8));
                    if (el != null && el.isJsonObject()) root = el.getAsJsonObject();
                } catch (Throwable ignored) {
                }
            }
            String raw = fp + "|" + issuedAt + "|" + expiry + "|" + B64_ENC.encodeToString(sig);
            root.addProperty("cacheSeed", B64_ENC.encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
            Files.writeString(cfg, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
        }
    }

    private static void clearStoredSessionSeed() {
        try {
            Path cfg = ClientThemeManager.getUnifiedConfigPath();
            if (!Files.exists(cfg)) return;
            JsonElement el = JsonParser.parseString(Files.readString(cfg, StandardCharsets.UTF_8));
            if (el != null && el.isJsonObject()) {
                JsonObject root = el.getAsJsonObject();
                if (root.remove("cacheSeed") != null) {
                    Files.writeString(cfg, GSON.toJson(root), StandardCharsets.UTF_8);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static byte[] computeSessionHmac(String fp, long issuedAt, long expiry) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(resolveHmacKey(), "HmacSHA256"));
            String msg = fp + "|" + issuedAt + "|" + expiry;
            return mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return null;
        }
    }
}
