package releon.ru.screens.clickgui.impl;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.screens.clickgui.ClickGui;
import releon.ru.screens.clickgui.impl.panel.ClickGuiPanel;
import releon.ru.screens.clickgui.impl.panel.ClickGuiPanelSlot;
import releon.ru.screens.clickgui.impl.search.ClickGuiSearchBar;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.render.ui.Render2DCoordinateSpace;

import java.util.ArrayList;
import java.util.List;

public final class ClickGuiController {
    private static final SmoothAnimation OPEN_ANIMATION = new SmoothAnimation();
    private static final float OPEN_OFFSET = 18.0f;
    private static final float PANEL_WIDTH = 130.0f;
    private static final float PANEL_HEIGHT = 280.0f;
    private static final float MARGIN = 22.0f;
    private static final float GAP = 7.0f;
    private static final ClickGuiSearchBar SEARCH_BAR = new ClickGuiSearchBar();
    private static final List<ClickGuiPanel> PANELS = new ArrayList<>(List.of(
            new ClickGuiPanel(ModuleCategory.COMBAT),
            new ClickGuiPanel(ModuleCategory.MOVEMENT),
            new ClickGuiPanel(ModuleCategory.VISUAL),
            new ClickGuiPanel(ModuleCategory.PLAYER),
            new ClickGuiPanel(ModuleCategory.MISC)
    ));
    private static final List<ClickGuiPanelSlot> PANEL_SLOTS = new ArrayList<>(5);
    private static final List<ClickGuiPanelSlot> PANEL_SLOT_POOL = new ArrayList<>(5);

    private static boolean closing;
    private static boolean overlayActive;
    private static boolean textWarmed;
    private static boolean pendingOpenAnimation;
    private static boolean sprintStateCaptured;
    private static boolean sprintKeyWasDown;
    private static boolean playerWasSprinting;
    private static double mouseX;
    private static double mouseY;

    private ClickGuiController() {
    }

    public static void init() {
        closing = false;
        overlayActive = true;
        pendingOpenAnimation = true;
        OPEN_ANIMATION.set(0.0);
        captureSprintState();
        stopGuiMovement(Minecraft.getInstance());
    }

    public static void renderPanels(GuiGraphics graphics, int screenWidth, int screenHeight) {
        updateMousePosition();
        float coordinateScale = coordinateScale();
        int designScreenWidth = Math.max(1, Math.round(screenWidth / coordinateScale));
        int designScreenHeight = Math.max(1, Math.round(screenHeight / coordinateScale));

        if (pendingOpenAnimation) {
            pendingOpenAnimation = false;
            OPEN_ANIMATION.set(0.0);
            OPEN_ANIMATION.run(1.0, 0.28, Easings.CUBIC_OUT);
        }
        OPEN_ANIMATION.update();
        float openProgress = clamp(OPEN_ANIMATION.get(), 0.0f, 1.0f);

        if (closing && !OPEN_ANIMATION.isAlive() && openProgress <= 0.01f) {
            closing = false;
            overlayActive = false;
            return;
        }

        if (openProgress <= 0.01f && !closing) {
            return;
        }

        float panelWidth = PANEL_WIDTH;
        float scale = 1.08f - 0.08f * openProgress;
        float scaledPanelWidth = panelWidth * scale;
        float scaledPanelHeight = PANEL_HEIGHT * scale;
        float guiWidth = scaledPanelWidth * PANELS.size() + GAP * (PANELS.size() - 1);
        float startX = (designScreenWidth - guiWidth) / 2.0f;
        float centeredY = (designScreenHeight - scaledPanelHeight) / 2.0f;
        float baseY = Math.max(MARGIN, centeredY) - (1.0f - openProgress) * OPEN_OFFSET;
        List<ClickGuiPanelSlot> slots = panelSlots(designScreenHeight, startX, baseY, panelWidth, scaledPanelWidth, scale, openProgress);

        Render2D.beginFrame(graphics);
        renderCornerDimming(designScreenWidth, designScreenHeight, openProgress);
        graphics.nextStratum();
        for (ClickGuiPanelSlot slot : slots) {
            slot.panel().renderShell(graphics, slot.x(), slot.y(), slot.width(), slot.height(), slot.scale(), slot.alpha());
            graphics.nextStratum();
            slot.panel().renderContent(graphics, slot.x(), slot.y(), slot.width(), slot.height(), slot.scale(), slot.alpha());
            graphics.nextStratum();
        }
        for (ClickGuiPanelSlot slot : slots) {
            slot.panel().renderOverlays(graphics);
            graphics.nextStratum();
        }
        SEARCH_BAR.render(graphics, designScreenWidth, openProgress, 0.0f, 0.0f);
        Render2D.flush();
    }

    public static void warmupText() {
        if (textWarmed) {
            return;
        }

        textWarmed = true;
        for (ClickGuiPanel panel : PANELS) {
            panel.warmupText();
        }
    }

    public static boolean keyPressed(KeyEvent event) {
        if (SEARCH_BAR.keyPressed(event, ClickGuiController::selectSearchResult)) {
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_RIGHT_SHIFT) {
            startClosing();
            return true;
        }
        return !closing && handlePanelKeyPressed(event);
    }

    public static boolean charTyped(CharacterEvent event) {
        if (SEARCH_BAR.charTyped(event)) {
            return true;
        }
        return !closing && handlePanelCharTyped(event);
    }

    public static boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        event = toDesignEvent(event);
        mouseX = event.x();
        mouseY = event.y();
        if (!closing && SEARCH_BAR.mouseClicked(event, doubled, ClickGuiController::selectSearchResult)) {
            return true;
        }
        if (!closing && handlePanelMouseClicked(event, doubled)) {
            return true;
        }
        return true;
    }

    public static boolean mouseReleased(MouseButtonEvent event) {
        event = toDesignEvent(event);
        mouseX = event.x();
        mouseY = event.y();
        if (handlePanelMouseReleased(event)) {
            return true;
        }
        return true;
    }

    public static boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        event = toDesignEvent(event);
        dragX /= coordinateScale();
        dragY /= coordinateScale();
        mouseX = event.x();
        mouseY = event.y();
        if (handlePanelMouseDragged(event, dragX, dragY)) {
            return true;
        }
        return true;
    }

    public static boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (closing) {
            return true;
        }
        mouseX /= coordinateScale();
        mouseY /= coordinateScale();
        ClickGuiController.mouseX = mouseX;
        ClickGuiController.mouseY = mouseY;
        return handlePanelMouseScrolled(mouseX, mouseY, scrollY);
    }

    public static double mouseX() {
        return mouseX;
    }

    public static double mouseY() {
        return mouseY;
    }

    public static void startClosing() {
        if (closing) {
            return;
        }
        closing = true;
        overlayActive = true;
        OPEN_ANIMATION.run(0.0, 0.22, Easings.CUBIC_OUT);

        Minecraft client = Minecraft.getInstance();
        if (client.screen instanceof ClickGui) {
            client.setScreen(null);
        }
        restoreSprintState();
    }

    public static void resetOverlayState() {
        restoreSprintState();
        closing = false;
        overlayActive = false;
        pendingOpenAnimation = false;
        OPEN_ANIMATION.set(0.0);
    }

    public static boolean shouldRenderOverlay() {
        Minecraft client = Minecraft.getInstance();
        if (client.screen != null && !(client.screen instanceof ClickGui)) {
            resetOverlayState();
            return false;
        }
        return overlayActive || client.screen instanceof ClickGui;
    }

    public static void tickMovementKeys() {
        Minecraft client = Minecraft.getInstance();
        if (!(client.screen instanceof ClickGui)) {
            restoreSprintState();
            return;
        }

        if (client.player == null || client.getWindow() == null) {
            return;
        }

        long handle = client.getWindow().handle();
        if (SEARCH_BAR.isFocused()) {
            stopGuiMovement(client);
            return;
        }

        boolean forward = updateKey(client.options.keyUp, handle);
        boolean left = updateKey(client.options.keyLeft, handle);
        boolean back = updateKey(client.options.keyDown, handle);
        boolean right = updateKey(client.options.keyRight, handle);
        updateKey(client.options.keyJump, handle);
        updateKey(client.options.keyShift, handle);
        setGuiSprint(client, forward || left || back || right);
    }

    private static List<ClickGuiPanelSlot> panelSlots(int screenHeight, float startX, float baseY, float panelWidth, float scaledPanelWidth, float scale, float openProgress) {
        List<ClickGuiPanelSlot> slots = PANEL_SLOTS;
        slots.clear();
        int slotIndex = 0;
        for (int i = 0; i < PANELS.size(); i++) {
            ClickGuiPanel panel = PANELS.get(i);
            panel.updateAnimations();
            float targetX = startX + i * (scaledPanelWidth + GAP);
            panel.snapX(targetX);
            float panelX = targetX;
            slots.add(slot(slotIndex++).set(panel, panelX, baseY, panelWidth, PANEL_HEIGHT, scale, openProgress));
        }
        return slots;
    }

    private static ClickGuiPanelSlot slot(int index) {
        while (PANEL_SLOT_POOL.size() <= index) {
            PANEL_SLOT_POOL.add(new ClickGuiPanelSlot());
        }
        return PANEL_SLOT_POOL.get(index);
    }

    private static boolean updateKey(KeyMapping keyMapping, long windowHandle) {
        InputConstants.Key key = InputConstants.getKey(keyMapping.saveString());
        if (key.getType() != InputConstants.Type.KEYSYM) {
            return false;
        }

        boolean down = GLFW.glfwGetKey(windowHandle, key.getValue()) == GLFW.GLFW_PRESS;
        KeyMapping.set(key, down);
        return down;
    }

    private static void captureSprintState() {
        if (sprintStateCaptured) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        sprintStateCaptured = true;
        sprintKeyWasDown = client.options != null && client.options.keySprint.isDown();
        playerWasSprinting = client.player != null && client.player.isSprinting();
    }

    private static void stopGuiMovement(Minecraft client) {
        releaseMovementKeys(client);
        setGuiSprint(client, false);
    }

    private static void setGuiSprint(Minecraft client, boolean sprint) {
        if (client.options != null) {
            client.options.keySprint.setDown(sprint);
        }

        if (client.player != null) {
            client.player.setSprinting(sprint);
        }
    }

    private static void restoreSprintState() {
        Minecraft client = Minecraft.getInstance();
        if (!sprintStateCaptured) {
            return;
        }

        if (client.options != null) {
            client.options.keySprint.setDown(false);
        }

        if (client.player != null) {
            client.player.setSprinting(false);
        }

        sprintStateCaptured = false;
    }

    private static void releaseMovementKeys(Minecraft client) {
        releaseKey(client.options.keyUp);
        releaseKey(client.options.keyLeft);
        releaseKey(client.options.keyDown);
        releaseKey(client.options.keyRight);
        releaseKey(client.options.keyJump);
        releaseKey(client.options.keyShift);
        releaseKey(client.options.keySprint);
    }

    private static void releaseKey(KeyMapping keyMapping) {
        InputConstants.Key key = InputConstants.getKey(keyMapping.saveString());
        if (key.getType() == InputConstants.Type.KEYSYM) {
            KeyMapping.set(key, false);
        }
    }

    private static boolean handlePanelMouseClicked(MouseButtonEvent event, boolean doubled) {
        for (int i = PANELS.size() - 1; i >= 0; i--) {
            if (PANELS.get(i).mouseClickedPopup(event, doubled)) {
                return true;
            }
        }
        for (int i = PANELS.size() - 1; i >= 0; i--) {
            if (PANELS.get(i).mouseClickedControls(event, doubled)) {
                return true;
            }
        }
        return false;
    }

    private static boolean handlePanelMouseReleased(MouseButtonEvent event) {
        for (int i = PANELS.size() - 1; i >= 0; i--) {
            if (PANELS.get(i).mouseReleased(event)) {
                return true;
            }
        }
        return false;
    }

    private static boolean handlePanelMouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        for (int i = PANELS.size() - 1; i >= 0; i--) {
            if (PANELS.get(i).mouseDragged(event, dragX, dragY)) {
                return true;
            }
        }
        return false;
    }

    private static boolean handlePanelMouseScrolled(double mouseX, double mouseY, double scrollY) {
        for (int i = PANELS.size() - 1; i >= 0; i--) {
            if (PANELS.get(i).mouseScrolled(mouseX, mouseY, scrollY)) {
                return true;
            }
        }
        return true;
    }

    private static boolean handlePanelKeyPressed(KeyEvent event) {
        for (int i = PANELS.size() - 1; i >= 0; i--) {
            if (PANELS.get(i).keyPressed(event)) {
                return true;
            }
        }
        return false;
    }

    private static boolean handlePanelCharTyped(CharacterEvent event) {
        for (int i = PANELS.size() - 1; i >= 0; i--) {
            if (PANELS.get(i).charTyped(event)) {
                return true;
            }
        }
        return false;
    }

    private static void selectSearchResult(ClickGuiSearchBar.Result result) {
        if (result == null) {
            return;
        }

        Module module = result.module();
        for (ClickGuiPanel panel : PANELS) {
            if (panel.category() == module.getCategory()) {
                panel.reveal(module);
                return;
            }
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static MouseButtonEvent toDesignEvent(MouseButtonEvent event) {
        float scale = coordinateScale();
        if (Math.abs(scale - 1.0f) <= 0.0001f) {
            return event;
        }
        MouseButtonInfo info = new MouseButtonInfo(event.button(), event.modifiers());
        return new MouseButtonEvent(event.x() / scale, event.y() / scale, info);
    }

    private static float coordinateScale() {
        return Render2DCoordinateSpace.guiIndependentScale();
    }

    private static void updateMousePosition() {
        Minecraft client = Minecraft.getInstance();
        if (client.mouseHandler == null || client.getWindow() == null) {
            return;
        }

        float scale = coordinateScale();
        mouseX = client.mouseHandler.getScaledXPos(client.getWindow()) / scale;
        mouseY = client.mouseHandler.getScaledYPos(client.getWindow()) / scale;
    }

    private static void renderCornerDimming(int screenWidth, int screenHeight, float alpha) {
        float overscan = 5.0f;
        float topHeight = 92.0f;
        float leftWidth = 122.0f;
        float fadeAlpha = alpha * alpha * (3.0f - 2.0f * alpha);

        Render2D.rect(
                -overscan,
                -overscan,
                screenWidth + overscan * 2.0f,
                topHeight + overscan,
                0.0f,
                color(0, 0, 0, 112, fadeAlpha),
                color(0, 0, 0, 112, fadeAlpha),
                color(0, 0, 0, 0, fadeAlpha),
                color(0, 0, 0, 0, fadeAlpha)
        );
        Render2D.rect(
                -overscan,
                -overscan,
                leftWidth + overscan,
                screenHeight + overscan * 2.0f,
                0.0f,
                color(0, 0, 0, 96, fadeAlpha),
                color(0, 0, 0, 0, fadeAlpha),
                color(0, 0, 0, 0, fadeAlpha),
                color(0, 0, 0, 96, fadeAlpha)
        );
    }

    private static int color(int red, int green, int blue, int alpha, float alphaMultiplier) {
        return ColorUtil.rgba(red, green, blue, Math.round(alpha * alphaMultiplier));
    }
}
