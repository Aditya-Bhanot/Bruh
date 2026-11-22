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
        applyStagedUpdateNow();
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
        lastCheckTimestampMs = System.currentTimeMillis();
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
            changelogLines = fetchCumulativeChangelog(client, localVer, remoteVerClean, parsedChanges);
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
        if (!manual && !autoDownloadEnabled) {
            changelogLines = fetchCumulativeChangelog(client, localVer, remoteVerClean, parsedChanges);
            statusText = "Update v" + remoteVerClean;
            if (notifyOnUpdateEnabled && !remoteVerClean.equals(lastNotifiedVersion)) {
                lastNotifiedVersion = remoteVerClean;
                notifyClient("Update v" + remoteVerClean + " Available", "Open ClickGUI to download & restart.", 1);
            }
            return;
        }

        statusText = "Downloading...";
        Path updateDir = stateDir.resolve("update");
        Files.createDirectories(updateDir);
        Path tmpFile = updateDir.resolve("update.jar.tmp");
        Files.deleteIfExists(tmpFile);

        long expectedBytes = jarAsset.has("size") ? jarAsset.get("size").getAsLong() : -1L;
            int dlCode = downloadAssetResilient(downloadUrl, tmpFile, expectedBytes);
            if (dlCode < 200 || dlCode >= 300) {
                Files.deleteIfExists(tmpFile);
                statusText = dlCode > 0 ? ("HTTP " + dlCode) : "DL Error";
                return;
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
        if (manual || (notifyOnUpdateEnabled && !remoteVerClean.equals(lastNotifiedVersion))) {
            lastNotifiedVersion = remoteVerClean;
            if (manual || (notifyOnUpdateEnabled && !remoteVerClean.equals(lastNotifiedVersion))) {
                lastNotifiedVersion = remoteVerClean;
                notifyClient("Update v" + remoteVerClean + " Ready", summary, 0);
            }
        }
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
        Runtime.getRuntime().addShutdownHook(new Thread(AutoUpdater::applyStagedUpdateNow, "Attrium-AutoUpdater-ShutdownSwap"));
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

    private static final java.util.concurrent.atomic.AtomicBoolean SWAP_APPLIED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static int downloadAssetResilient(String startUrl, Path destFile, long expectedBytes) {
        int lastCode = -1;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                Files.deleteIfExists(destFile);
                String currentUrl = startUrl;
                int code = -1;
                for (int hop = 0; hop < 8; hop++) {
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) URI.create(currentUrl).toURL().openConnection();
                    conn.setInstanceFollowRedirects(false);
                    conn.setConnectTimeout(15_000);
                    conn.setReadTimeout(120_000);
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", USER_AGENT);
                    if (hop == 0) {
                        conn.setRequestProperty("Accept", "application/octet-stream");
                    }
                    code = conn.getResponseCode();
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String loc = conn.getHeaderField("Location");
                        conn.disconnect();
                        if (loc == null || loc.isEmpty()) {
                            return code;
                        }
                        currentUrl = URI.create(currentUrl).resolve(loc).toString();
                        continue;
                    }
                    lastCode = code;
                    if (code < 200 || code >= 300) {
                        conn.disconnect();
                        break;
                    }
                    try (java.io.InputStream in = conn.getInputStream();
                         java.io.OutputStream out = Files.newOutputStream(destFile)) {
                        byte[] buf = new byte[16384];
                        int r;
                        while ((r = in.read(buf)) != -1) {
                            out.write(buf, 0, r);
                        }
                        out.flush();
                    } finally {
                        conn.disconnect();
                    }
                    long actual = Files.exists(destFile) ? Files.size(destFile) : 0L;
                    if (expectedBytes > 0L && actual != expectedBytes) {
                        System.err.println("[Attrium AutoUpdater] Size mismatch on attempt " + attempt
                                + ": got " + actual + ", expected " + expectedBytes);
                        lastCode = -2;
                        break;
                    }
                    return 200;
                }
            } catch (Exception ex) {
                System.err.println("[Attrium AutoUpdater] Download attempt " + attempt + " failed: " + ex);
                lastCode = -1;
            }
        }
        return lastCode;
    }

    public static boolean applyStagedUpdateNow() {
        if (!updateReady || !SWAP_APPLIED.compareAndSet(false, true)) {
            return SWAP_APPLIED.get();
        }
        try {
            Path tmp = stagedTmpPath != null ? stagedTmpPath : getStateDir().resolve("update").resolve("update.jar.tmp");
            if (!Files.isRegularFile(tmp) || !isValidFabricJar(tmp)) {
                SWAP_APPLIED.set(false);
                return false;
            }
            Path targetJar = activeModJarPath != null ? activeModJarPath : resolveActiveModJar();
            if (targetJar == null || !Files.isRegularFile(targetJar)) {
                Path modsDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("mods");
                if (Files.isDirectory(modsDir)) {
                    try (java.util.stream.Stream<Path> stream = Files.list(modsDir)) {
                        targetJar = stream
                                .filter(Files::isRegularFile)
                                .filter(AutoUpdater::isAttriumModJar)
                                .findFirst()
                                .orElse(null);
                    }
                }
            }
            if (targetJar == null || !Files.isRegularFile(targetJar)) {
                System.err.println("[Attrium AutoUpdater] Could not locate active mod JAR to replace.");
                SWAP_APPLIED.set(false);
                return false;
            }
            Path stagingInMods = targetJar.resolveSibling(targetJar.getFileName().toString() + ".updating");
            Files.copy(tmp, stagingInMods, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(stagingInMods, targetJar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicEx) {
                try {
                    Files.move(stagingInMods, targetJar, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception moveEx) {
                    Files.copy(tmp, targetJar, StandardCopyOption.REPLACE_EXISTING);
                    Files.deleteIfExists(stagingInMods);
                }
            }
            Files.deleteIfExists(tmp);
            System.out.println("[Attrium AutoUpdater] Applied staged update to " + targetJar);
            return true;
        } catch (Exception ex) {
            System.err.println("[Attrium AutoUpdater] Failed to apply staged update: " + ex);
            SWAP_APPLIED.set(false);
            return false;
        }
    }


    private static java.util.List<String> fetchCumulativeChangelog(
            java.net.http.HttpClient client,
            String localVer,
            String remoteVer,
            java.util.List<String> fallbackLines) {
        try {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/repos/Aditya-Bhanot/Bruh/releases?per_page=15"))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300 || resp.body() == null) {
                return fallbackLines;
            }
            com.google.gson.JsonArray releases = com.google.gson.JsonParser.parseString(resp.body()).getAsJsonArray();
            java.util.List<String> merged = new java.util.ArrayList<>();
            int matchedVersions = 0;
            for (com.google.gson.JsonElement el : releases) {
                if (!el.isJsonObject()) continue;
                com.google.gson.JsonObject rel = el.getAsJsonObject();
                if (rel.has("draft") && rel.get("draft").getAsBoolean()) continue;
                if (rel.has("prerelease") && rel.get("prerelease").getAsBoolean()) continue;
                String tag = rel.has("tag_name") && !rel.get("tag_name").isJsonNull()
                        ? rel.get("tag_name").getAsString() : "";
                String relVer = normalizeVersion(tag);
                if (relVer.isEmpty()) continue;
                if (compareVersions(relVer, localVer) <= 0) continue;
                if (compareVersions(relVer, remoteVer) > 0) continue;

                matchedVersions++;
                String body = rel.has("body") && !rel.get("body").isJsonNull()
                        ? rel.get("body").getAsString() : "";
                int addedForVer = 0;
                for (String rawLine : body.split("\r?\n")) {
                    String line = rawLine.trim();
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith("```") || line.startsWith("---")) {
                        continue;
                    }
                    line = line.replaceFirst("^[-*\u2022]+\s*", "").trim();
                    if (line.isEmpty()) continue;
                    merged.add("[v" + relVer + "] " + line);
                    addedForVer++;
                }
                if (addedForVer == 0) {
                    merged.add("[v" + relVer + "] Update released");
                }
            }
            if (matchedVersions > 1 && !merged.isEmpty()) {
                return java.util.Collections.unmodifiableList(merged);
            }
            if (matchedVersions == 1 && !merged.isEmpty()) {
                return java.util.Collections.unmodifiableList(merged);
            }
        } catch (Exception ex) {
            System.err.println("[Attrium AutoUpdater] Cumulative changelog fetch skipped: " + ex);
        }
        return fallbackLines;
    }

    private static boolean isAttriumModJar(Path jarPath) {
        if (jarPath == null || !Files.isRegularFile(jarPath)) {
            return false;
        }
        String lower = jarPath.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".jar")) {
            return false;
        }
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jarPath.toFile())) {
            java.util.zip.ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) {
                return false;
            }
            try (java.io.InputStream in = zip.getInputStream(entry);
                 java.io.InputStreamReader reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
                com.google.gson.JsonObject modJson = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                if (modJson != null && modJson.has("id") && !modJson.get("id").isJsonNull()) {
                    return MOD_ID.equalsIgnoreCase(modJson.get("id").getAsString().trim());
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }


    private static volatile boolean autoCheckEnabled = true;
    private static volatile int checkIntervalMinutes = 15;
    private static volatile boolean autoDownloadEnabled = true;
    private static volatile boolean notifyOnUpdateEnabled = true;
    private static volatile long lastCheckTimestampMs = System.currentTimeMillis();
    private static volatile String lastNotifiedVersion = "";

    public static void syncSettings(boolean autoCheck, int intervalMin, boolean autoDownload, boolean notifyOnUpdate) {
        autoCheckEnabled = autoCheck;
        checkIntervalMinutes = Math.max(1, Math.min(120, intervalMin));
        autoDownloadEnabled = autoDownload;
        notifyOnUpdateEnabled = notifyOnUpdate;
    }

    public static void tickPeriodicCheck() {
        if (!autoCheckEnabled || updateReady || isChecking()) {
            return;
        }
        long now = System.currentTimeMillis();
        long intervalMs = checkIntervalMinutes * 60_000L;
        if (now - lastCheckTimestampMs >= intervalMs) {
            lastCheckTimestampMs = now;
            checkForUpdatesAsync(false);
        }
    }

}
