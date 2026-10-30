package releon.ru.api.drag.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.Minecraft;
import releon.ru.api.config.ConfigManager;
import releon.ru.utils.render.LoadingVisualGuard;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.font.FontType;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ElementManager {
    private static final ElementManager INSTANCE = new ElementManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String FILE_NAME = "drags" + ConfigManager.CONFIG_EXTENSION;
    private static final float HIT_PADDING = 3.0f;

    private final Map<String, ElementComponent> components = new LinkedHashMap<>();
    private final Map<String, SavedElement> pendingStates = new LinkedHashMap<>();
    private final List<ElementComponent> sortedScratch = new ArrayList<>();
    private ElementComponent active;
    private ElementScreen screen = new ElementScreen(1.0f, 1.0f, 1.0f);
    private int nextOrder;
    private boolean loaded;

    private ElementManager() {
    }

    public static ElementManager getInstance() {
        return INSTANCE;
    }

    public ElementComponent register(String id, String title, float defaultX, float defaultY) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Element id cannot be empty");
        }

        String key = normalize(id);
        ElementComponent existing = components.get(key);
        if (existing != null) {
            return existing;
        }

        ElementComponent component = new ElementComponent(key, title == null || title.isBlank() ? key : title, defaultX, defaultY, nextOrder++);
        SavedElement saved = pendingStates.get(key);
        if (saved != null) {
            component.applyState(saved.x, saved.y, saved.width, saved.height, saved.visible, saved.order);
            nextOrder = Math.max(nextOrder, saved.order + 1);
        }
        components.put(key, component);
        return component;
    }

    public List<ElementComponent> components() {
        return List.copyOf(components.values());
    }

    public void frame(ElementScreen screen) {
        if (screen != null && screen.valid()) {
            this.screen = screen;
        }
        for (ElementComponent component : components.values()) {
            component.clamp(this.screen);
        }
    }

    public boolean handleMouseClicked(MouseButtonEvent event) {
        if (event == null || event.button() != 0 || !canEdit()) {
            return false;
        }

        frame(ElementScreen.current());
        ElementComponent hovered = topmostAt((float) event.x(), (float) event.y());
        if (hovered == null) {
            return false;
        }

        active = hovered;
        active.order(nextOrder++);
        active.beginMove((float) event.x(), (float) event.y());
        return true;
    }

    public boolean handleMouseDragged(MouseButtonEvent event) {
        if (event == null || active == null || !canEdit()) {
            return false;
        }

        active.moveTo((float) event.x(), (float) event.y(), screen);
        return true;
    }

    public void updateActiveElementFromMouse() {
        if (active == null || !canEdit()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client == null || client.mouseHandler == null || client.getWindow() == null) {
            return;
        }

        ElementScreen currentScreen = ElementScreen.current();
        frame(currentScreen);
        float scale = Math.max(0.0001f, currentScreen.coordinateScale());
        float mouseX = (float) client.mouseHandler.getScaledXPos(client.getWindow()) / scale;
        float mouseY = (float) client.mouseHandler.getScaledYPos(client.getWindow()) / scale;
        active.moveTo(mouseX, mouseY, screen);
    }

    public boolean handleMouseReleased(MouseButtonEvent event) {
        if (active == null) {
            return false;
        }

        boolean editing = canEdit();
        active.endMove();
        active.commitClamp(screen);
        active = null;
        save();
        return editing;
    }

    public void cancelActiveElement() {
        if (active == null) {
            return;
        }

        active.endMove();
        active.commitClamp(screen);
        active = null;
        save();
    }

    public boolean canEditCurrentScreen() {
        return canEdit();
    }

    public void renderEditorOverlay(GuiGraphics graphics, ElementScreen screen) {
        if (!canEdit()) {
            return;
        }

        frame(screen);
        for (ElementComponent component : sortedComponents()) {
            if (!component.visible()) {
                continue;
            }
            renderComponentOverlay(component);
        }
    }

    public void load() {
        loaded = true;
        Path file = configFile();
        if (!Files.exists(file)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root == null || !root.has("components") || !root.get("components").isJsonObject()) {
                return;
            }

            JsonObject componentsObject = root.getAsJsonObject("components");
            for (String id : componentsObject.keySet()) {
                SavedElement saved = GSON.fromJson(componentsObject.get(id), SavedElement.class);
                if (saved == null || !saved.valid()) {
                    continue;
                }

                String key = normalize(id);
                pendingStates.put(key, saved);
                ElementComponent component = components.get(key);
                if (component != null) {
                    component.applyState(saved.x, saved.y, saved.width, saved.height, saved.visible, saved.order);
                    nextOrder = Math.max(nextOrder, saved.order + 1);
                }
            }
        } catch (Exception exception) {
            System.err.println("Failed to load element config: " + file + " (" + exception.getMessage() + ")");
        }
    }

    public void save() {
        if (!loaded) {
            return;
        }

        Path file = configFile();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                JsonObject root = new JsonObject();
                root.addProperty("version", 1);
                JsonObject componentsObject = new JsonObject();
                for (ElementComponent component : components.values()) {
                    componentsObject.add(component.id(), GSON.toJsonTree(SavedElement.from(component)));
                }
                root.add("components", componentsObject);
                GSON.toJson(root, writer);
            }
        } catch (IOException exception) {
            System.err.println("Failed to save element config: " + file + " (" + exception.getMessage() + ")");
        }
    }

    private ElementComponent topmostAt(float mouseX, float mouseY) {
        ElementComponent topmost = null;
        int topmostOrder = Integer.MIN_VALUE;
        for (ElementComponent component : components.values()) {
            int order = component.order();
            if (order > topmostOrder && component.hit(mouseX, mouseY, HIT_PADDING)) {
                topmost = component;
                topmostOrder = order;
            }
        }
        return topmost;
    }

    private List<ElementComponent> sortedComponents() {
        sortedScratch.clear();
        sortedScratch.addAll(components.values());
        sortedScratch.sort(Comparator.comparingInt(ElementComponent::order));
        return sortedScratch;
    }

    private boolean canEdit() {
        Minecraft client = Minecraft.getInstance();
        return client != null
                && client.player != null
                && client.level != null
                && client.screen instanceof ChatScreen
                && !LoadingVisualGuard.shouldSuppressHud(client);
    }

    private void renderComponentOverlay(ElementComponent component) {
    }

    private Path configFile() {
        return ConfigManager.systemDirectory().resolve(FILE_NAME);
    }

    private static String normalize(String id) {
        return id.trim().toLowerCase().replace(' ', '_');
    }

    private static final class SavedElement {
        private float x;
        private float y;
        private float width;
        private float height;
        private boolean visible = true;
        private int order;

        private static SavedElement from(ElementComponent component) {
            SavedElement saved = new SavedElement();
            saved.x = component.targetX();
            saved.y = component.targetY();
            saved.width = component.width();
            saved.height = component.height();
            saved.visible = component.visible();
            saved.order = component.order();
            return saved;
        }

        private boolean valid() {
            return Float.isFinite(x)
                    && Float.isFinite(y)
                    && Float.isFinite(width)
                    && Float.isFinite(height)
                    && width > 0.0f
                    && height > 0.0f;
        }
    }
}
