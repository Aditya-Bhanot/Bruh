package releon.ru.api.module.impl.visual;

import net.minecraft.client.CameraType;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.DrawEvent;
import releon.ru.api.events.types.EventPriority;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.ColorSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.render.animation.Easings;
import releon.ru.utils.render.animation.SmoothAnimation;
import releon.ru.utils.render.color.ColorUtil;
import releon.ru.utils.render.ui.Render2D;
import releon.ru.utils.repository.friend.FriendUtils;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

public class Arrows extends Module {
    private static final String ARROW_TEXTURE = "releon:textures/particles/ghost-triangle.png";

    private final NumberSetting arrowsDistance = register(new NumberSetting("Distance", "Distance from crosshair.", 20.0, 1.0, 20.0, 0.5));
    private final NumberSetting arrowSize = register(new NumberSetting("Size", "Arrow texture size.", 15, 10.0, 25.0, 0.5));
    private final ColorSetting arrowColor = register(new ColorSetting("Color", "Arrow color.", new Color(255, 255, 255, 255)));
    private final ColorSetting friendColor = register(new ColorSetting("Friend Color", "Friend arrow color.", new Color(0, 255, 0, 255)));
    private final SmoothAnimation distanceAnimation = new SmoothAnimation();
    private final SmoothAnimation yawAnimation = new SmoothAnimation();
    private final SmoothAnimation pitchAnimation = new SmoothAnimation();
    private final SmoothAnimation cameraYawAnimation = new SmoothAnimation();
    private final List<Arrow> arrows = new ArrayList<>();

    public Arrows() {
        super("Arrows", "Draws arrows for players outside the screen center.", ModuleCategory.VISUAL);
    }

    @Override
    protected void onDisable() {
        arrows.clear();
    }

    @SubscribeEvent(priority = EventPriority.MONITOR)
    private void onDraw(DrawEvent event) {
        if (mc.player == null || mc.level == null || mc.options.getCameraType() != CameraType.FIRST_PERSON) {
            return;
        }

        distanceAnimation.update();
        yawAnimation.update();
        pitchAnimation.update();
        cameraYawAnimation.update();

        float size = 45.0f + arrowsDistance.getFloat();
        if (mc.screen instanceof InventoryScreen) {
            size += 80.0f;
        }
        if (mc.player.isShiftKeyDown()) {
            size -= 20.0f;
        }
        if (isMoving()) {
            size += 10.0f;
        }

        float strafeInput = mc.player.input.getMoveVector().x;
        float forwardInput = mc.player.input.getMoveVector().y;
        yawAnimation.run(strafeInput * 5.0f, 0.75, Easings.EXPO_OUT, true);
        pitchAnimation.run(forwardInput * 5.0f, 0.75, Easings.EXPO_OUT, true);
        cameraYawAnimation.run(mc.gameRenderer.getMainCamera().yRot(), 0.75, Easings.EXPO_OUT, true);
        distanceAnimation.run(size, 1.0, Easings.EXPO_OUT, true);

        for (Arrow arrow : arrows) {
            arrow.active = false;
        }

        for (AbstractClientPlayer player : mc.level.players()) {
            if (!isValidPlayer(player)) {
                continue;
            }
            Arrow arrow = findArrow(player);
            if (arrow == null) {
                arrow = new Arrow(player);
                arrows.add(arrow);
            }
            arrow.active = true;
            arrow.fade.run(1.0, 0.20, Easings.CUBIC_OUT, true);
        }

        for (int i = arrows.size() - 1; i >= 0; i--) {
            Arrow arrow = arrows.get(i);
            if (!arrow.active) {
                arrow.fade.run(0.0, 0.20, Easings.CUBIC_OUT, true);
                if (arrow.fade.get() <= 0.01f && !arrow.fade.isAlive()) {
                    arrows.remove(i);
                }
            }
        }

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        float centerX = screenWidth / 2.0f;
        float centerY = screenHeight / 2.0f;
        double cameraYaw = cameraYawAnimation.getValue();
        double cos = Mth.cos((float) (cameraYaw * (Math.PI * 2.0 / 360.0)));
        double sin = Mth.sin((float) (cameraYaw * (Math.PI * 2.0 / 360.0)));

        for (Arrow arrow : arrows) {
            arrow.fade.update();
            float alpha = arrow.fade.get();
            if (alpha <= 0.01f) {
                continue;
            }

            Player player = arrow.player;
            double playerX = Mth.lerp(event.getPartialTicks(), player.xOld, player.getX()) - mc.gameRenderer.getMainCamera().position().x;
            double playerZ = Mth.lerp(event.getPartialTicks(), player.zOld, player.getZ()) - mc.gameRenderer.getMainCamera().position().z;
            double rotY = -(playerZ * cos - playerX * sin);
            double rotX = -(playerX * cos + playerZ * sin);
            float angle = (float) (Math.atan2(rotY, rotX) * 180.0 / Math.PI);
            float x = (float) (distanceAnimation.getValue() * alpha * Mth.cos((float) Math.toRadians(angle)) + centerX + yawAnimation.getValue());
            float y = (float) (distanceAnimation.getValue() * alpha * Mth.sin((float) Math.toRadians(angle)) + centerY + pitchAnimation.getValue());
            int baseColor = FriendUtils.isFriend(player) ? friendColor.getValue().getRGB() : arrowColor.getValue().getRGB();
            int color = ColorUtil.multAlpha(baseColor, alpha);
            drawArrow(x, y, angle, color);
        }
    }

    private void drawArrow(float x, float y, float angle, int color) {
        float size = arrowSize.getFloat();
        float halfSize = size * 0.5f;
        Render2D.image(ARROW_TEXTURE, x - halfSize, y - halfSize, size, 0.0f, angle + 90.0f, x, y, color);
    }

    private Arrow findArrow(Player player) {
        for (Arrow arrow : arrows) {
            if (arrow.player == player) {
                return arrow;
            }
        }
        return null;
    }

    private boolean isValidPlayer(Player player) {
        return player != mc.player && !player.isRemoved();
    }

    private boolean isMoving() {
        return mc.player.input.getMoveVector().y != 0.0f || mc.player.input.getMoveVector().x != 0.0f;
    }

    private static final class Arrow {
        private final Player player;
        private final SmoothAnimation fade = new SmoothAnimation();
        private boolean active;

        private Arrow(Player player) {
            this.player = player;
            this.fade.set(0.0);
        }
    }
}
