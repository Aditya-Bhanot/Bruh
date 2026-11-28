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
                System.err.println("[Attrium AutoUpdater] Update check failed: " + t);
                if (!updateReady) {
                    statusText = "DL Error";
                }
            } finally {
                CHECK_IN_PROGRESS.set(false);
            }
        });
    }

    // [ATTRIUM-AUTOUPDATER-ROCKSOLID-V3-BBE]
    private static String fetchJsonViaUrlConnection(String urlStr) throws IOException {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) URI.create(urlStr).toURL().openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(20_000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("Accept-Encoding", "identity");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        int code = conn.getResponseCode();
        try {
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP_" + code);
            }
            try (java.io.InputStream in = conn.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String[] resolveLatestReleaseViaRedirectFallback() {
        try {
            String webLatest = "https://github.com/Aditya-Bhanot/Bruh/releases/latest";
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) URI.create(webLatest).toURL().openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(15_000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept-Encoding", "identity");
            int code = conn.getResponseCode();
            String loc = conn.getHeaderField("Location");
            conn.disconnect();
            if ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308)
                    && loc != null && loc.contains("/releases/tag/")) {
                String tag = loc.substring(loc.lastIndexOf("/releases/tag/") + "/releases/tag/".length()).trim();
                int q = tag.indexOf('?');
                if (q > 0) tag = tag.substring(0, q);
                String cleanVer = normalizeVersion(tag);
                if (!cleanVer.isEmpty()) {
                    String jarName = "Attrium-Client-" + cleanVer + ".jar";
                    String directUrl = "https://github.com/Aditya-Bhanot/Bruh/releases/download/" + tag + "/" + jarName;
                    return new String[]{tag, cleanVer, directUrl, jarName};
                }
            }
        } catch (Exception ex) {
            System.err.println("[Attrium AutoUpdater] Redirect fallback failed: " + ex);
        }
        return null;
    }

    private static void runUpdateCheck(boolean manual) throws IOException, InterruptedException {
        String remoteTag = "";
        String releaseBody = "";
        long remoteId = -1L;
        String remoteUpdatedAt = "";
        String downloadUrl = "";
        String apiAssetUrl = "";
        String remoteAssetName = "";
        long expectedBytes = -1L;

        try {
            String body = fetchJsonViaUrlConnection(RELEASES_LATEST_URL);
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            remoteTag = root.has("tag_name") && !root.get("tag_name").isJsonNull()
                    ? root.get("tag_name").getAsString().trim()
                    : "";
            releaseBody = root.has("body") && !root.get("body").isJsonNull()
                    ? root.get("body").getAsString()
                    : "";

            JsonArray assets = root.has("assets") && root.get("assets").isJsonArray()
                    ? root.getAsJsonArray("assets")
                    : null;
            if (assets != null && !assets.isEmpty()) {
                JsonObject jarAsset = findJarAsset(assets);
                if (jarAsset != null) {
                    remoteId = jarAsset.has("id") ? jarAsset.get("id").getAsLong() : -1L;
                    remoteUpdatedAt = jarAsset.has("updated_at") ? jarAsset.get("updated_at").getAsString() : "";
                    downloadUrl = jarAsset.has("browser_download_url") ? jarAsset.get("browser_download_url").getAsString() : "";
                    apiAssetUrl = jarAsset.has("url") ? jarAsset.get("url").getAsString() : "";
                    remoteAssetName = jarAsset.has("name") ? jarAsset.get("name").getAsString() : "";
                    expectedBytes = jarAsset.has("size") ? jarAsset.get("size").getAsLong() : -1L;
                }
            }
        } catch (Exception apiEx) {
            System.err.println("[Attrium AutoUpdater] GitHub API check failed (" + apiEx.getMessage() + "), trying redirect fallback...");
            String[] fb = resolveLatestReleaseViaRedirectFallback();
            if (fb != null) {
                remoteTag = fb[0];
                downloadUrl = fb[2];
                remoteAssetName = fb[3];
                remoteId = Math.abs((long) (remoteTag + ":" + remoteAssetName).hashCode()) + 1L;
                remoteUpdatedAt = remoteTag;
                releaseBody = "Release " + remoteTag;
            } else {
                String msg = apiEx.getMessage() != null ? apiEx.getMessage() : "";
                statusText = msg.startsWith("HTTP_") ? ("API " + msg.substring(5)) : "Net Error";
                return;
            }
        }

        String remoteVerClean = normalizeVersion(remoteTag.isEmpty() ? currentVersion : remoteTag);
        if (downloadUrl.isEmpty()) {
            statusText = "Up to date";
            return;
        }

        latestVersion = remoteVerClean;
        List<String> parsedChanges = parseChangelog(releaseBody);

        Path stateDir = getStateDir();
        Path stateFile = stateDir.resolve("updater.json");
        LocalState saved = Files.exists(stateFile) ? readLocalState(stateFile) : null;

        String localVer = currentVersion;
        if (saved != null && saved.version != null && !saved.version.isEmpty()
                && compareVersions(saved.version, localVer) > 0) {
            localVer = saved.version;
        }

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

        if (!manual && !autoDownloadEnabled) {
            changelogLines = parsedChanges;
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

        int dlCode = downloadAssetResilient(downloadUrl, tmpFile, expectedBytes);
        if ((dlCode < 200 || dlCode >= 300) && !apiAssetUrl.isEmpty()) {
            System.err.println("[Attrium AutoUpdater] Primary URL failed (" + dlCode + "), trying GitHub API asset endpoint...");
            dlCode = downloadAssetResilient(apiAssetUrl, tmpFile, expectedBytes);
        }
        if ((dlCode < 200 || dlCode >= 300) && !remoteTag.isEmpty() && !remoteAssetName.isEmpty()) {
            String directFallback = "https://github.com/Aditya-Bhanot/Bruh/releases/download/" + remoteTag + "/" + remoteAssetName;
            if (!directFallback.equals(downloadUrl)) {
                System.err.println("[Attrium AutoUpdater] Trying direct release download fallback: " + directFallback);
                dlCode = downloadAssetResilient(directFallback, tmpFile, expectedBytes);
            }
        }

        if (dlCode < 200 || dlCode >= 300) {
            Files.deleteIfExists(tmpFile);
            statusText = dlCode > 0 ? ("HTTP " + dlCode) : (dlCode == -2 ? "Size Err" : "DL Error");
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
            notifyClient("Update v" + remoteVerClean + " Ready", summary, 0);
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
        boolean isApiAssetEndpoint = startUrl != null && startUrl.contains("api.github.com/");
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Files.deleteIfExists(destFile);
                String currentUrl = startUrl;
                int code = -1;
                for (int hop = 0; hop < 10; hop++) {
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) URI.create(currentUrl).toURL().openConnection();
                    conn.setInstanceFollowRedirects(false);
                    conn.setConnectTimeout(15_000);
                    conn.setReadTimeout(120_000);
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("User-Agent", USER_AGENT);
                    conn.setRequestProperty("Accept-Encoding", "identity");
                    if (hop == 0 && isApiAssetEndpoint) {
                        conn.setRequestProperty("Accept", "application/octet-stream");
                    } else {
                        conn.setRequestProperty("Accept", "*/*");
                    }
                    code = conn.getResponseCode();
                    if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                        String loc = conn.getHeaderField("Location");
                        conn.disconnect();
                        if (loc == null || loc.isEmpty()) {
                            return code;
                        }
                        if (loc.startsWith("http://") || loc.startsWith("https://")) {
                            currentUrl = loc;
                        } else {
                            currentUrl = URI.create(currentUrl).resolve(loc).toString();
                        }
                        continue;
                    }
                    lastCode = code;
                    if (code < 200 || code >= 300) {
                        conn.disconnect();
                        break;
                    }
                    try (java.io.InputStream in = conn.getInputStream();
                         java.io.OutputStream out = Files.newOutputStream(destFile)) {
                        byte[] buf = new byte[32768];
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
                    if (actual < 10_000L) {
                        System.err.println("[Attrium AutoUpdater] Downloaded file too small (" + actual + " bytes)");
                        lastCode = -2;
                        break;
                    }
                    return 200;
                }
            } catch (Exception ex) {
                System.err.println("[Attrium AutoUpdater] Download attempt " + attempt + " failed: " + ex);
                lastCode = -1;
            }
            if (attempt < 3) {
                try {
                    Thread.sleep(attempt * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
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
            Path finalJar = targetJar.resolveSibling("bbe-fabric-1.3.4+mc1.21.11.jar");
            Path stagingInMods = targetJar.resolveSibling("bbe-fabric-1.3.4+mc1.21.11.jar.updating");
            Files.copy(tmp, stagingInMods, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(stagingInMods, finalJar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicEx) {
                try {
                    Files.move(stagingInMods, finalJar, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception moveEx) {
                    Files.copy(tmp, finalJar, StandardCopyOption.REPLACE_EXISTING);
                    Files.deleteIfExists(stagingInMods);
                }
            }
            if (!finalJar.equals(targetJar)) {
                Files.deleteIfExists(targetJar);
            }
            Files.deleteIfExists(tmp);
            activeModJarPath = finalJar;
            System.out.println("[Attrium AutoUpdater] Applied staged update to " + finalJar);
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

    // =========================================================================
    // ClickGUI Updater Tab Bridge & Previous 3 Updates Release History Fetcher
    // =========================================================================
    public static final class ReleaseNoteEntry {
        public final String tag;
        public final String title;
        public final String date;
        public final java.util.List<String> bullets;

        public ReleaseNoteEntry(String tag, String title, String date, java.util.List<String> bullets) {
            this.tag = tag != null ? tag : "update";
            this.title = title != null ? title : this.tag;
            this.date = date != null ? date : "";
            this.bullets = bullets != null ? java.util.Collections.unmodifiableList(bullets) : java.util.Collections.emptyList();
        }
    }

    private static final String RECENT_RELEASES_API_URL = "https://api.github.com/repos/Aditya-Bhanot/Bruh/releases?per_page=10";
    private static final String RECENT_COMMITS_API_URL = "https://api.github.com/repos/Aditya-Bhanot/Bruh/commits?per_page=5";
    private static final java.util.concurrent.atomic.AtomicBoolean FETCHING_RECENT_RELEASES = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile java.util.List<ReleaseNoteEntry> recentThreeReleases = java.util.Collections.emptyList();
    private static volatile String recentReleasesError = "";
    private static volatile long lastRecentReleasesFetchMs = 0L;

    public static java.util.List<ReleaseNoteEntry> getRecentThreeReleases() {
        return recentThreeReleases;
    }

    public static boolean isFetchingRecentReleases() {
        return FETCHING_RECENT_RELEASES.get();
    }

    public static String getRecentReleasesError() {
        return recentReleasesError != null ? recentReleasesError : "";
    }

    public static boolean uiIsChecking() {
        return isChecking();
    }

    public static String uiGetStatusText() {
        return (statusText != null && !statusText.isEmpty()) ? statusText : (updateReady ? "Restart Ready" : "Up to date");
    }

    public static String uiGetPrimaryButtonText() {
        if (updateReady) return "Restart to Update";
        if (uiIsChecking()) return "Checking...";
        return "Check Updates";
    }

    public static int uiGetPrimaryButtonColor() {
        return updateReady ? 0xFF00E5FF : 0xFFB8C0D0;
    }

    public static String uiGetLocalVersion() {
        try {
            for (net.fabricmc.loader.api.ModContainer container : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
                String id = container.getMetadata().getId();
                if (id != null && (id.equalsIgnoreCase("attrium") || id.toLowerCase(java.util.Locale.ROOT).contains("attrium"))) {
                    return container.getMetadata().getVersion().getFriendlyString();
                }
            }
        } catch (Throwable ignored) {
        }
        return "1.0.0";
    }

    public static void uiOnPrimaryButtonClick() {
        onUpdateButtonClicked();
        fetchRecentThreeReleasesAsync(true);
    }

    public static void uiTriggerRefreshAll() {
        fetchRecentThreeReleasesAsync(true);
    }

    public static void fetchRecentThreeReleasesAsync(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && !recentThreeReleases.isEmpty() && (now - lastRecentReleasesFetchMs) < 120_000L) {
            return;
        }
        if (!FETCHING_RECENT_RELEASES.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(8))
                        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                        .build();

                java.util.List<ReleaseNoteEntry> entries = new java.util.ArrayList<>(3);

                // 1. Fetch GitHub Releases first (up to 3 non-draft releases)
                try {
                    java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(RECENT_RELEASES_API_URL))
                            .timeout(java.time.Duration.ofSeconds(10))
                            .header("User-Agent", USER_AGENT)
                            .header("Accept", "application/vnd.github+json")
                            .GET()
                            .build();
                    java.net.http.HttpResponse<String> resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
                    if (resp.statusCode() >= 200 && resp.statusCode() < 300 && resp.body() != null) {
                        com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(resp.body());
                        if (parsed != null && parsed.isJsonArray()) {
                            for (com.google.gson.JsonElement el : parsed.getAsJsonArray()) {
                                if (entries.size() >= 3) break;
                                if (!el.isJsonObject()) continue;
                                com.google.gson.JsonObject obj = el.getAsJsonObject();
                                if (obj.has("draft") && obj.get("draft").isJsonPrimitive() && obj.get("draft").getAsBoolean()) {
                                    continue;
                                }
                                String tag = readJsonString(obj, "tag_name", "release");
                                String name = readJsonString(obj, "name", tag);
                                if (name.isEmpty()) name = tag;
                                String rawDate = readJsonString(obj, "published_at", readJsonString(obj, "created_at", ""));
                                String shortDate = rawDate.length() >= 10 ? rawDate.substring(0, 10) : rawDate;
                                String body = readJsonString(obj, "body", "");
                                entries.add(new ReleaseNoteEntry(tag, name, shortDate, parseReleaseBullets(body)));
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }

                // 2. If fewer than 3 GitHub Releases exist (e.g. rolling 'latest' release), fill up to 3 from recent commits
                if (entries.size() < 3) {
                    try {
                        java.net.http.HttpRequest creq = java.net.http.HttpRequest.newBuilder()
                                .uri(java.net.URI.create(RECENT_COMMITS_API_URL))
                                .timeout(java.time.Duration.ofSeconds(10))
                                .header("User-Agent", USER_AGENT)
                                .header("Accept", "application/vnd.github+json")
                                .GET()
                                .build();
                        java.net.http.HttpResponse<String> cresp = http.send(creq, java.net.http.HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
                        if (cresp.statusCode() >= 200 && cresp.statusCode() < 300 && cresp.body() != null) {
                            com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(cresp.body());
                            if (parsed != null && parsed.isJsonArray()) {
                                for (com.google.gson.JsonElement el : parsed.getAsJsonArray()) {
                                    if (entries.size() >= 3) break;
                                    if (!el.isJsonObject()) continue;
                                    com.google.gson.JsonObject obj = el.getAsJsonObject();
                                    String sha = readJsonString(obj, "sha", "");
                                    String tag = sha.length() >= 7 ? ("#" + sha.substring(0, 7)) : "commit";
                                    com.google.gson.JsonObject commitObj = (obj.has("commit") && obj.get("commit").isJsonObject())
                                            ? obj.getAsJsonObject("commit") : null;
                                    if (commitObj == null) continue;
                                    String msg = readJsonString(commitObj, "message", "Update");
                                    String[] msgLines = msg.replace("\r\n", "\n").split("\n");
                                    String title = msgLines.length > 0 && !msgLines[0].trim().isEmpty() ? msgLines[0].trim() : "Client Update";
                                    String dateStr = "";
                                    if (commitObj.has("author") && commitObj.get("author").isJsonObject()) {
                                        String rawD = readJsonString(commitObj.getAsJsonObject("author"), "date", "");
                                        dateStr = rawD.length() >= 10 ? rawD.substring(0, 10) : rawD;
                                    }
                                    java.util.List<String> bullets = new java.util.ArrayList<>();
                                    for (int i = 1; i < msgLines.length && bullets.size() < 5; i++) {
                                        String cleaned = cleanMarkdownLine(msgLines[i]);
                                        if (!cleaned.isEmpty()) bullets.add(cleaned);
                                    }
                                    if (bullets.isEmpty()) {
                                        bullets.add(title);
                                    }
                                    // Avoid duplicate title if already listed as a release
                                    boolean dup = false;
                                    for (ReleaseNoteEntry existing : entries) {
                                        if (existing.title.equalsIgnoreCase(title)) {
                                            dup = true;
                                            break;
                                        }
                                    }
                                    if (!dup) {
                                        entries.add(new ReleaseNoteEntry(tag, title, dateStr, bullets));
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

                if (!entries.isEmpty()) {
                    recentThreeReleases = java.util.Collections.unmodifiableList(entries);
                    recentReleasesError = "";
                    lastRecentReleasesFetchMs = System.currentTimeMillis();
                } else {
                    recentReleasesError = "Could not fetch recent updates from GitHub. Tap Refresh to retry.";
                }
            } catch (Throwable ex) {
                recentReleasesError = "Update history unavailable (offline or GitHub rate-limited).";
            } finally {
                FETCHING_RECENT_RELEASES.set(false);
            }
        }, "Attrium-Updater-History");
        t.setDaemon(true);
        t.start();
    }

    private static String readJsonString(com.google.gson.JsonObject obj, String key, String fallback) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) return fallback;
        String val = obj.get(key).getAsString();
        return val != null ? val.trim() : fallback;
    }

    private static java.util.List<String> parseReleaseBullets(String rawBody) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (rawBody != null && !rawBody.isBlank()) {
            String[] lines = rawBody.replace("\r\n", "\n").split("\n");
            for (String line : lines) {
                if (out.size() >= 7) break;
                String cleaned = cleanMarkdownLine(line);
                if (!cleaned.isEmpty()) {
                    out.add(cleaned);
                }
            }
        }
        if (out.isEmpty()) {
            out.add("General fixes, performance improvements, and stability updates.");
        }
        return out;
    }

    private static String cleanMarkdownLine(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty() || s.equals("---") || s.equals("***") || s.startsWith("```")) return "";
        while (s.startsWith("#")) s = s.substring(1).trim();
        if (s.startsWith("- ") || s.startsWith("* ") || s.startsWith("• ")) {
            s = s.substring(2).trim();
        }
        s = s.replace("**", "").replace("__", "").replace("`", "").trim();
        if (s.toLowerCase(java.util.Locale.ROOT).startsWith("full changelog:")) return "";
        return s;
    }
}
