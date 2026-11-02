package releon.ru.utils.repository.way;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.DrawEvent;
import releon.ru.api.module.impl.visual.esp.EspGeometry;
import releon.ru.api.config.ConfigManager;
import releon.ru.utils.repository.RepositoryStorage;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

public final class WayRepository {
    private static final WayRepository INSTANCE = new WayRepository();
    private final List<Way> wayList = new ArrayList<>();
    private final Minecraft mc = Minecraft.getInstance();
    private final WayRenderer renderer = new WayRenderer();

    private WayRepository() {
    }

    public static WayRepository getInstance() {
        return INSTANCE;
    }

    public WayRenderer renderer() {
        return renderer;
    }

    public void load() {
        wayList.clear();
        JsonArray array = readWayArray();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String name = object.has("name") ? object.get("name").getAsString() : "";
            String server = object.has("server") ? object.get("server").getAsString() : "";
            int x = object.has("x") ? object.get("x").getAsInt() : 0;
            int y = object.has("y") ? object.get("y").getAsInt() : 0;
            int z = object.has("z") ? object.get("z").getAsInt() : 0;
            if (!name.isBlank()) {
                wayList.add(new Way(name, new BlockPos(x, y, z), normalizeServer(server)));
            }
        }
    }

    public void save() {
        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        for (Way way : wayList) {
            JsonObject object = new JsonObject();
            object.addProperty("name", way.name());
            object.addProperty("server", way.server());
            object.addProperty("x", way.pos().getX());
            object.addProperty("y", way.pos().getY());
            object.addProperty("z", way.pos().getZ());
            array.add(object);
        }
        root.add("ways", array);
        RepositoryStorage.write("ways.releon", root);
    }

    private JsonArray readWayArray() {
        JsonObject root = RepositoryStorage.readObject("ways.releon");
        if (root.has("ways") && root.get("ways").isJsonArray()) {
            return root.getAsJsonArray("ways");
        }

        JsonArray legacy = readLegacyWayArray();
        return legacy == null ? new JsonArray() : legacy;
    }

    private JsonArray readLegacyWayArray() {
        for (Path file : legacyWayFiles()) {
            if (file == null || !Files.exists(file)) {
                continue;
            }

            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonElement element = com.google.gson.JsonParser.parseReader(reader);
                if (element != null && element.isJsonArray()) {
                    return element.getAsJsonArray();
                }
                if (element != null && element.isJsonObject()) {
                    JsonObject object = element.getAsJsonObject();
                    if (object.has("ways") && object.get("ways").isJsonArray()) {
                        return object.getAsJsonArray("ways");
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private List<Path> legacyWayFiles() {
        List<Path> files = new ArrayList<>();
        files.add(RepositoryStorage.root().resolve("waypoints.releon"));
        files.add(RepositoryStorage.root().resolve("ways.releon"));
        files.add(ConfigManager.legacyDirectory().resolve("waypoints.releon"));
        Path gameDir = ConfigManager.rootDirectory().getParent();
        if (gameDir != null) {
            files.add(gameDir.resolve("releon").resolve("configs").resolve("defaults").resolve("waypoints.releon"));
        }
        return files;
    }

    public boolean isEmpty() {
        return wayList.isEmpty();
    }

    public void addWay(String name, BlockPos pos, String server) {
        wayList.add(new Way(name, pos, normalizeServer(server)));
    }

    public void addWayAndSave(String name, BlockPos pos, String server) {
        addWay(name, pos, server);
        save();
    }

    public boolean hasWay(String name) {
        return hasWay(name, getCurrentServer());
    }

    public boolean hasWay(String name, String server) {
        return wayList.stream().anyMatch(way -> isSameWay(way, name, server));
    }

    public Optional<Way> getWay(String name) {
        return getWay(name, getCurrentServer());
    }

    public Optional<Way> getWay(String name, String server) {
        return wayList.stream().filter(way -> isSameWay(way, name, server)).findFirst();
    }

    public void deleteWay(String name) {
        deleteWay(name, getCurrentServer());
    }

    public void deleteWay(String name, String server) {
        wayList.removeIf(way -> isSameWay(way, name, server));
    }

    public void deleteWayAndSave(String name) {
        deleteWay(name);
        save();
    }

    public void deleteWayAndSave(String name, String server) {
        deleteWay(name, server);
        save();
    }

    public void clearList() {
        wayList.clear();
    }

    public void clearListAndSave() {
        clearList();
        save();
    }

    public int size() {
        return wayList.size();
    }

    public List<Way> getWayList() {
        return wayList;
    }

    public List<String> getWayNames() {
        return wayList.stream().map(Way::name).collect(Collectors.toList());
    }

    public List<String> getWayNamesForServer(String server) {
        return wayList.stream()
                .filter(way -> isSameServer(way, server))
                .map(Way::name)
                .collect(Collectors.toList());
    }

    public List<Way> getWaysForServer(String server) {
        return wayList.stream()
                .filter(way -> isSameServer(way, server))
                .collect(Collectors.toList());
    }

    public int clearServer(String server) {
        int before = wayList.size();
        wayList.removeIf(way -> isSameServer(way, server));
        return before - wayList.size();
    }

    public int clearServerAndSave(String server) {
        int removed = clearServer(server);
        save();
        return removed;
    }

    public void setWays(List<Way> ways) {
        wayList.clear();
        if (ways != null) {
            wayList.addAll(ways);
        }
    }

    public String getCurrentServer() {
        if (mc.getConnection() == null || mc.getConnection().getServerData() == null) {
            return "";
        }
        return normalizeServer(mc.getConnection().getServerData().ip);
    }

    @SubscribeEvent
    private void onDraw(DrawEvent event) {
        if (mc.player == null || mc.level == null || mc.gameRenderer == null || mc.getWindow() == null) {
            return;
        }

        String server = getCurrentServer();
        if (server.isBlank() || wayList.isEmpty()) {
            return;
        }

        EspGeometry.ProjectionContext context = EspGeometry.createProjectionContext(mc, event.getPartialTicks());
        if (context == null) {
            return;
        }

        Vec3 playerPos = mc.player.getPosition(event.getPartialTicks());
        renderer.beginFrame();
        for (Way way : getWaysForServer(server)) {
            Vec3 worldPos = way.pos().getCenter();
            if (!isInFrontOfCamera(worldPos)) {
                continue;
            }

            EspGeometry.ScreenPoint screen = EspGeometry.projectWorldToScreenSpace(worldPos, context);
            if (screen == null) {
                continue;
            }

            renderer.queue(mc, way, screen, playerPos.distanceTo(worldPos));
        }
        renderer.renderQueued(event.getGraphics());
    }

    private boolean isInFrontOfCamera(Vec3 worldPos) {
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null || !camera.isInitialized()) {
            return false;
        }

        Vec3 cameraPos = camera.position();
        Vec3 toPoint = worldPos.subtract(cameraPos);
        double yaw = Math.toRadians(camera.yRot());
        double pitch = Math.toRadians(camera.xRot());
        double cosPitch = Math.cos(pitch);
        Vec3 look = new Vec3(
                -Math.sin(yaw) * cosPitch,
                -Math.sin(pitch),
                Math.cos(yaw) * cosPitch
        );
        return look.dot(toPoint) > 0.0D;
    }

    private boolean isSameWay(Way way, String name, String server) {
        return way.name().equalsIgnoreCase(name) && isSameServer(way, server);
    }

    private boolean isSameServer(Way way, String server) {
        return normalizeServer(way.server()).equals(normalizeServer(server));
    }

    private static String normalizeServer(String server) {
        return server == null ? "" : server.trim().toLowerCase(Locale.ROOT);
    }
}
