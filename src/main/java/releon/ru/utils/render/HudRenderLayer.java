package releon.ru.utils.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import releon.ru.api.events.impl.DrawEvent;
import releon.ru.api.module.impl.visual.Hud;
import releon.ru.manager.Manager;
import releon.ru.utils.render.ui.Render2D;

public final class HudRenderLayer {
    private HudRenderLayer() {
    }

    public static boolean shouldRender(Minecraft client) {
        return client != null
                && client.player != null
                && client.level != null
                && client.getWindow() != null
                && !LoadingVisualGuard.shouldSuppressHud(client);
    }

    public static void renderGame(GuiGraphics graphics, float partialTick) {
        renderGame(Minecraft.getInstance(), graphics, partialTick);
    }

    public static void renderGame(Minecraft client, GuiGraphics graphics, float partialTick) {
        renderComposite(client, graphics, partialTick, DrawEvent.Layer.GAME);
    }

    public static void renderGameDrawEvents(GuiGraphics graphics, float partialTick) {
        renderDrawEvent(graphics, partialTick, DrawEvent.Layer.GAME);
    }

    public static void renderGameHud(Minecraft client, GuiGraphics graphics, float partialTick) {
        renderHudOnly(client, graphics, partialTick, DrawEvent.Layer.GAME);
    }

    public static void renderScreenBackground(Minecraft client, GuiGraphics graphics, float partialTick) {
        if (!shouldRender(client) || client.screen == null) {
            return;
        }

        renderHudOnly(client, graphics, partialTick, DrawEvent.Layer.SCREEN_BACKGROUND);
    }

    public static void renderChatOverlay(Minecraft client, GuiGraphics graphics, float partialTick) {
        renderHudOnly(client, graphics, partialTick, DrawEvent.Layer.CHAT_OVERLAY);
    }

    private static void renderComposite(Minecraft client, GuiGraphics graphics, float partialTick, DrawEvent.Layer hudLayer) {
        if (!shouldRender(client)) {
            return;
        }

        renderDrawEvent(graphics, partialTick, DrawEvent.Layer.GAME);
        renderHudOnly(client, graphics, partialTick, hudLayer);
    }

    private static void renderHudOnly(Minecraft client, GuiGraphics graphics, float partialTick, DrawEvent.Layer layer) {
        if (!shouldRender(client)) {
            return;
        }

        Hud hud = Hud.getInstance();
        if (hud == null || !hud.isEnabled()) {
            return;
        }

        Render2D.beginFrame(graphics);
        hud.renderHudLayer(new DrawEvent(graphics, partialTick, layer));
        Render2D.flush();
        graphics.nextStratum();
    }

    private static void renderDrawEvent(GuiGraphics graphics, float partialTick, DrawEvent.Layer layer) {
        Render2D.beginFrame(graphics);
        Manager.postEvent(new DrawEvent(graphics, partialTick, layer));
        Render2D.flush();
        graphics.nextStratum();
    }
}
