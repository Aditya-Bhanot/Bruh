package attrium.ru.manager;

import attrium.ru.api.module.impl.visual.NotificationsModule;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;

/**
 * Version-aware Asynchronous GitHub Releases Auto-Updater for Attrium (Fabric 1.21.11).
 * Tracks installed/saved version, compares semantic/numeric versions against GitHub Releases,
 * parses release changelogs ("What's Changed"), and swaps staged JARs safely on JVM shutdown.
 */
public final class AutoUpdater {

    private static final String MOD_ID = "attrium";
    private static final String RELEASES_LATEST_URL = "https://api.github.com/repos/Aditya-Bhanot/Bruh/releases/latest";
    private static final String USER_AGENT = "Attrium-Client-Updater";
    private static final Duration API_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(120);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final AtomicBoolean CHECK_IN_PROGRESS = new AtomicBoolean(false);
    private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean(false);

    public static volatile boolean updateReady = false;
    private static volatile String statusText = "Up to date";
    private static volatile String currentVersion = "1.0.0";
    private static volatile String latestVersion = "1.0.0";
    private static volatile List<String> changelogLines = Collections.emptyList();
    private static volatile Path stagedTmpPath = null;
    private static volatile Path activeModJarPath = null;

    private AutoUpdater() {
    }

    public static String getStatusText() {
        return statusText;
    }

    public static String getCurrentVersion() {
        return currentVersion;
    }

    public static String getLatestVersion() {
        return latestVersion;
    }

    public static List<String> getChangelogLines() {
        return changelogLines;
    }

    public static boolean isChecking() {
        return CHECK_IN_PROGRESS.get();
    }

    public static String getButtonLabel() {
        return updateReady ? "Restart to Update" : "Check Updates";
    }

    public static int getStatusColor() {
        return updateReady ? 0xFF00E5FF : 0xFFB8C0D0;
    }

    public static void initOnStartup() {
        activeModJarPath = resolveActiveModJar();
        currentVersion = resolveInstalledModVersion();
        latestVersion = currentVersion;
        registerShutdownHookOnce();
        checkForUpdatesAsync(false);
    }

    public static void onUpdateButtonClicked() {
        if (updateReady) {
            restartToApply();
            return;
        }
        checkForUpdatesAsync(true);
    }

    public static void restartToApply() {
        if (!updateReady) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.stop();
        }
    }

    public static void checkForUpdatesAsync(boolean manual) {
        if (!CHECK_IN_PROGRESS.compareAndSet(false, true)) {
            return;
        }
        if (activeModJarPath == null) {
            activeModJarPath = resolveActiveModJar();
        }
        currentVersion = resolveInstalledModVersion();
        registerShutdownHookOnce();

        statusText = "Checking...";
        CompletableFuture.runAsync(() -> {
            try {
                runUpdateCheck(manual);
            } catch (Throwable t) {
                if (!updateReady) {
                    statusText = "DL Error";
                }
            } finally {
                CHECK_IN_PROGRESS.set(false);
            }
        });
    }

    private static void runUpdateCheck(boolean manual) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(API_TIMEOUT)
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(RELEASES_LATEST_URL))
                .timeout(API_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            statusText = "API " + response.statusCode();
            return;
        }

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        String remoteTag = root.has("tag_name") && !root.get("tag_name").isJsonNull()
                ? root.get("tag_name").getAsString().trim()
                : "";
        String releaseBody = root.has("body") && !root.get("body").isJsonNull()
                ? root.get("body").getAsString()
                : "";

        JsonArray assets = root.has("assets") && root.get("assets").isJsonArray()
                ? root.getAsJsonArray("assets")
                : null;
        if (assets == null || assets.isEmpty()) {
            statusText = "Up to date";
            return;
        }

        JsonObject jarAsset = findJarAsset(assets);
        if (jarAsset == null) {
            statusText = "Up to date";
            return;
        }

        long remoteId = jarAsset.has("id") ? jarAsset.get("id").getAsLong() : -1L;
        String remoteUpdatedAt = jarAsset.has("updated_at") ? jarAsset.get("updated_at").getAsString() : "";
        String downloadUrl = jarAsset.has("browser_download_url") ? jarAsset.get("browser_download_url").getAsString() : "";
        String remoteVerClean = normalizeVersion(remoteTag.isEmpty() ? currentVersion : remoteTag);

        if (remoteId <= 0L || downloadUrl.isEmpty()) {
            statusText = "Up to date";
            return;
        }

        latestVersion = remoteVerClean;
        List<String> parsedChanges = parseChangelog(releaseBody);

        Path stateDir = getStateDir();
        Path stateFile = stateDir.resolve("updater.json");
        LocalState saved = Files.exists(stateFile) ? readLocalState(stateFile) : null;

        // Baseline version is whichever is newer between installed fabric.mod.json version and saved updater.json version
        String localVer = currentVersion;
        if (saved != null && saved.version != null && !saved.version.isEmpty()
                && compareVersions(saved.version, localVer) > 0) {
            localVer = saved.version;
        }

        // First-run initialization if version matches
        if (saved == null && compareVersions(remoteVerClean, localVer) <= 0) {
            saveLocalState(stateFile, remoteVerClean, remoteId, remoteUpdatedAt);
            changelogLines = parsedChanges;
            statusText = "Up to date";
            if (manual) {
                notifyClient("Attrium Updater", "Up to date (v" + localVer + ")", 0);
            }
            return;
        }

        boolean versionNewer = compareVersions(remoteVerClean, localVer) > 0;
        boolean rollingAssetNewer = (saved != null)
                && compareVersions(remoteVerClean, localVer) == 0
                && (saved.id != remoteId || (!remoteUpdatedAt.isEmpty() && !remoteUpdatedAt.equals(saved.updatedAt)));

        if (!versionNewer && !rollingAssetNewer) {
            changelogLines = parsedChanges;
            statusText = updateReady ? "Restart Ready" : "Up to date";
            if (manual && !updateReady) {
                notifyClient("Attrium Updater", "Up to date (v" + localVer + ")", 0);
            }
            return;
        }

        // Stream download to staging file
        statusText = "Downloading...";
        Path updateDir = stateDir.resolve("update");
        Files.createDirectories(updateDir);
        Path tmpFile = updateDir.resolve("update.jar.tmp");
        Files.deleteIfExists(tmpFile);

        HttpRequest dlRequest = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .timeout(DOWNLOAD_TIMEOUT)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        HttpResponse<InputStream> dlResponse = client.send(dlRequest, HttpResponse.BodyHandlers.ofInputStream());
        if (dlResponse.statusCode() < 200 || dlResponse.statusCode() >= 300) {
            try (InputStream ignored = dlResponse.body()) {
            }
            statusText = "HTTP " + dlResponse.statusCode();
            return;
        }

        try (InputStream in = dlResponse.body()) {
            Files.copy(in, tmpFile, StandardCopyOption.REPLACE_EXISTING);
        }

        if (!Files.exists(tmpFile) || Files.size(tmpFile) < 10_000L || !isValidFabricJar(tmpFile)) {
            Files.deleteIfExists(tmpFile);
            statusText = "Bad JAR";
            return;
        }

        saveLocalState(stateFile, remoteVerClean, remoteId, remoteUpdatedAt);
        changelogLines = parsedChanges;
        stagedTmpPath = tmpFile;
        updateReady = true;
        statusText = "Restart Ready";
        String summary = parsedChanges.isEmpty() ? "Restart client to apply v" + remoteVerClean : parsedChanges.get(0);
        notifyClient("Update v" + remoteVerClean + " Ready", summary, 0);
    }

    private static List<String> parseChangelog(String body) {
        if (body == null || body.isBlank()) {
            return Collections.singletonList("Bug fixes and performance improvements");
        }
        List<String> out = new ArrayList<>();
        for (String raw : body.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("**Full Changelog**")) {
                continue;
            }
            if (line.startsWith("- ") || line.startsWith("* ")) {
                line = line.substring(2).trim();
            }
            if (line.length() > 74) {
                line = line.substring(0, 71) + "...";
            }
            if (!line.isEmpty()) {
                out.add(line);
            }
            if (out.size() >= 4) {
                break;
            }
        }
        return out.isEmpty() ? Collections.singletonList("Bug fixes and performance improvements") : Collections.unmodifiableList(out);
    }

    private static String resolveInstalledModVersion() {
        try {
            return FabricLoader.getInstance()
                    .getModContainer(MOD_ID)
                    .map(c -> normalizeVersion(c.getMetadata().getVersion().getFriendlyString()))
                    .orElse("1.0.0");
        } catch (Throwable ignored) {
            return "1.0.0";
        }
    }

    private static String normalizeVersion(String raw) {
        if (raw == null || raw.isBlank()) {
            return "0.0.0";
        }
        String s = raw.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        int plusIdx = s.indexOf('+');
        if (plusIdx > 0) {
            s = s.substring(0, plusIdx);
        }
        return s;
    }

    /**
     * Compares numeric dot-separated version strings (e.g., "1.0.12" vs "1.0.9").
     */
    public static int compareVersions(String a, String b) {
        String[] pa = normalizeVersion(a).split("[^0-9]+");
        String[] pb = normalizeVersion(b).split("[^0-9]+");
        int maxLen = Math.max(pa.length, pb.length);
        for (int i = 0; i < maxLen; i++) {
            int va = (i < pa.length && !pa[i].isEmpty()) ? safeParseInt(pa[i]) : 0;
            int vb = (i < pb.length && !pb[i].isEmpty()) ? safeParseInt(pb[i]) : 0;
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        return 0;
    }

    private static int safeParseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static JsonObject findJarAsset(JsonArray assets) {
        for (JsonElement elem : assets) {
            if (!elem.isJsonObject()) {
                continue;
            }
            JsonObject obj = elem.getAsJsonObject();
            String name = obj.has("name") ? obj.get("name").getAsString() : "";
            if (name.endsWith(".jar") && !name.endsWith("-sources.jar")) {
                return obj;
            }
        }
        return null;
    }

    private static boolean isValidFabricJar(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            return zip.size() > 0 && zip.getEntry("fabric.mod.json") != null;
        } catch (IOException e) {
            return false;
        }
    }

    private static Path resolveActiveModJar() {
        try {
            Path origin = FabricLoader.getInstance()
                    .getModContainer(MOD_ID)
                    .map(c -> c.getOrigin().getPaths().get(0))
                    .orElse(null);
            if (origin != null && Files.isRegularFile(origin) && origin.toString().endsWith(".jar")) {
                return origin;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Path getStateDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("attrium_updater");
    }

    private static void registerShutdownHookOnce() {
        if (!SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!updateReady) {
                return;
            }
            Path tmp = stagedTmpPath != null
                    ? stagedTmpPath
                    : getStateDir().resolve("update").resolve("update.jar.tmp");
            Path targetJar = activeModJarPath != null ? activeModJarPath : resolveActiveModJar();
            if (tmp == null || targetJar == null || !Files.isRegularFile(tmp) || !Files.isRegularFile(targetJar)) {
                return;
            }
            try {
                Files.move(tmp, targetJar, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveErr) {
                try {
                    Files.copy(tmp, targetJar, StandardCopyOption.REPLACE_EXISTING);
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                }
            }
        }, "Attrium-AutoUpdater-ShutdownSwap"));
    }

    private static void notifyClient(String title, String message, int type) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> {
                try {
                    NotificationsModule.pushCustom(title, message, type);
                } catch (Throwable ignored) {
                }
            });
        }
    }

    private static LocalState readLocalState(Path file) {
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
            String ver = obj.has("version") ? obj.get("version").getAsString() : "";
            long id = obj.has("id") ? obj.get("id").getAsLong() : -1L;
            String updatedAt = obj.has("updated_at") ? obj.get("updated_at").getAsString() : "";
            return new LocalState(ver, id, updatedAt);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void saveLocalState(Path file, String version, long id, String updatedAt) throws IOException {
        Files.createDirectories(file.getParent());
        JsonObject obj = new JsonObject();
        obj.addProperty("version", version != null ? version : "1.0.0");
        obj.addProperty("id", id);
        obj.addProperty("updated_at", updatedAt != null ? updatedAt : "");
        Files.writeString(file, GSON.toJson(obj), StandardCharsets.UTF_8);
    }

    private record LocalState(String version, long id, String updatedAt) {
    }
}
