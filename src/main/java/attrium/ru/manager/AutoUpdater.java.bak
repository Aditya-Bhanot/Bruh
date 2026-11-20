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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;

/**
 * Asynchronous GitHub Releases Auto-Updater for Attrium (Fabric 1.21.11 / FoldCraftLauncher-safe).
 *
 * - Queries https://api.github.com/repos/Aditya-Bhanot/Bruh/releases/latest asynchronously.
 * - Tracks rolling latest release asset.id and asset.updated_at in .minecraft/attrium/updater.json.
 * - Downloads to .minecraft/attrium/update/update.jar.tmp, validates ZIP integrity + file size,
 *   and swaps over the active mod JAR inside a single JVM shutdown hook to avoid ZipException crashes.
 */
public final class AutoUpdater {

    private static final String MOD_ID = "attrium";
    private static final String RELEASES_LATEST_URL = "https://api.github.com/repos/Aditya-Bhanot/Bruh/releases/latest";
    private static final String USER_AGENT = "Attrium-Client-Updater";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final AtomicBoolean CHECK_IN_PROGRESS = new AtomicBoolean(false);
    private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean(false);

    public static volatile boolean updateReady = false;
    private static volatile String statusText = "Up to date";
    private static volatile Path stagedTmpPath = null;
    private static volatile Path activeModJarPath = null;

    private AutoUpdater() {
    }

    public static String getStatusText() {
        return statusText;
    }

    public static String getButtonLabel() {
        return updateReady ? "Restart to Update" : "Check Updates";
    }

    public static int getStatusColor() {
        return updateReady ? 0xFF00E5FF : 0xFFB8C0D0;
    }

    /**
     * Call once during Engine.onInitializeClient(). Runs fully asynchronously.
     */
    public static void initOnStartup() {
        activeModJarPath = resolveActiveModJar();
        registerShutdownHookOnce();
        checkForUpdatesAsync(false);
    }

    /**
     * ClickGUI Client tab pill button handler.
     */
    public static void onUpdateButtonClicked() {
        if (updateReady) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.stop();
            }
            return;
        }
        checkForUpdatesAsync(true);
    }

    /**
     * Runs an async GitHub release check and background download if a newer rolling asset exists.
     */
    public static void checkForUpdatesAsync(boolean manual) {
        if (!CHECK_IN_PROGRESS.compareAndSet(false, true)) {
            return;
        }
        if (activeModJarPath == null) {
            activeModJarPath = resolveActiveModJar();
        }
        registerShutdownHookOnce();

        statusText = "Checking...";
        CompletableFuture.runAsync(() -> {
            try {
                runUpdateCheck(manual);
            } catch (Throwable ignored) {
                if (!updateReady) {
                    statusText = "Check Failed";
                }
            } finally {
                CHECK_IN_PROGRESS.set(false);
            }
        });
    }

    private static void runUpdateCheck(boolean manual) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(RELEASES_LATEST_URL))
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            statusText = "Check Failed";
            return;
        }

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
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
        long expectedSize = jarAsset.has("size") ? jarAsset.get("size").getAsLong() : -1L;
        String downloadUrl = jarAsset.has("browser_download_url") ? jarAsset.get("browser_download_url").getAsString() : "";
        if (remoteId <= 0L || downloadUrl.isEmpty()) {
            statusText = "Up to date";
            return;
        }

        Path attriumDir = getAttriumDir();
        Path stateFile = attriumDir.resolve("updater.json");

        // First-run initialization: record current release without unnecessarily re-downloading
        if (!Files.exists(stateFile)) {
            saveLocalState(stateFile, remoteId, remoteUpdatedAt);
            statusText = "Up to date";
            if (manual) {
                notifyClient("Attrium Updater", "Client is up to date", 0);
            }
            return;
        }

        LocalState saved = readLocalState(stateFile);
        boolean isNewer = (saved == null)
                || (saved.id != remoteId)
                || (!remoteUpdatedAt.isEmpty() && !remoteUpdatedAt.equals(saved.updatedAt));

        if (!isNewer) {
            statusText = updateReady ? "Restart Ready" : "Up to date";
            if (manual && !updateReady) {
                notifyClient("Attrium Updater", "Client is up to date", 0);
            }
            return;
        }

        // Download new JAR to staging file
        statusText = "Downloading...";
        Path updateDir = attriumDir.resolve("update");
        Files.createDirectories(updateDir);
        Path tmpFile = updateDir.resolve("update.jar.tmp");
        Files.deleteIfExists(tmpFile);

        HttpRequest dlRequest = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        HttpResponse<Path> dlResponse = client.send(dlRequest, HttpResponse.BodyHandlers.ofFile(tmpFile));
        if (dlResponse.statusCode() < 200 || dlResponse.statusCode() >= 300 || !Files.exists(tmpFile)) {
            Files.deleteIfExists(tmpFile);
            statusText = "Check Failed";
            return;
        }

        // Verify expected size and valid ZIP/JAR structure before marking ready
        long actualSize = Files.size(tmpFile);
        if ((expectedSize > 0L && actualSize != expectedSize) || !isValidZipArchive(tmpFile)) {
            Files.deleteIfExists(tmpFile);
            statusText = "Check Failed";
            return;
        }

        saveLocalState(stateFile, remoteId, remoteUpdatedAt);
        stagedTmpPath = tmpFile;
        updateReady = true;
        statusText = "Restart Ready";
        notifyClient("Update Ready", "Restart client to apply new build", 0);
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

    private static boolean isValidZipArchive(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            return zip.size() > 0;
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

    private static Path getAttriumDir() {
        return FabricLoader.getInstance().getGameDir().resolve("attrium");
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
                    : getAttriumDir().resolve("update").resolve("update.jar.tmp");
            Path targetJar = activeModJarPath != null ? activeModJarPath : resolveActiveModJar();
            if (tmp == null || targetJar == null || !Files.isRegularFile(tmp) || !Files.isRegularFile(targetJar)) {
                return;
            }
            try {
                Files.move(tmp, targetJar, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveErr) {
                // Fallback for Android/FUSE cross-mount moves
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
            long id = obj.has("id") ? obj.get("id").getAsLong() : -1L;
            String updatedAt = obj.has("updated_at") ? obj.get("updated_at").getAsString() : "";
            return new LocalState(id, updatedAt);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void saveLocalState(Path file, long id, String updatedAt) throws IOException {
        Files.createDirectories(file.getParent());
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id);
        obj.addProperty("updated_at", updatedAt != null ? updatedAt : "");
        Files.writeString(file, GSON.toJson(obj), StandardCharsets.UTF_8);
    }

    private record LocalState(long id, String updatedAt) {
    }
}