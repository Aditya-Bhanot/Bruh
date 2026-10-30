package releon.ru.utils.inventory.movement;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

public class MovementController {
    private static final Minecraft mc = Minecraft.getInstance();

    private boolean forward;
    private boolean back;
    private boolean left;
    private boolean right;
    private boolean jump;
    private boolean sprint;
    private boolean saved = false;
    private boolean blocked = false;

    public void saveState() {
        if (mc.player == null) return;
        forward = isKeyPressed(mc.options.keyUp);
        back = isKeyPressed(mc.options.keyDown);
        left = isKeyPressed(mc.options.keyLeft);
        right = isKeyPressed(mc.options.keyRight);
        jump = isKeyPressed(mc.options.keyJump);
        sprint = mc.player.isSprinting();
        saved = true;
    }

    public void block() {
        if (mc.player == null) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keySprint.setDown(false);
        blocked = true;
    }

    public void stopSprint() {
        if (mc.player != null) {
            mc.player.setSprinting(false);
            mc.options.keySprint.setDown(false);
        }
    }

    public void restore() {
        if (!saved) return;
        mc.options.keyUp.setDown(forward && isCurrentlyPressed(mc.options.keyUp));
        mc.options.keyDown.setDown(back && isCurrentlyPressed(mc.options.keyDown));
        mc.options.keyLeft.setDown(left && isCurrentlyPressed(mc.options.keyLeft));
        mc.options.keyRight.setDown(right && isCurrentlyPressed(mc.options.keyRight));
        mc.options.keyJump.setDown(jump && isCurrentlyPressed(mc.options.keyJump));
        blocked = false;
        saved = false;
    }

    public void restoreFromCurrent() {
        mc.options.keyUp.setDown(isCurrentlyPressed(mc.options.keyUp));
        mc.options.keyDown.setDown(isCurrentlyPressed(mc.options.keyDown));
        mc.options.keyLeft.setDown(isCurrentlyPressed(mc.options.keyLeft));
        mc.options.keyRight.setDown(isCurrentlyPressed(mc.options.keyRight));
        mc.options.keyJump.setDown(isCurrentlyPressed(mc.options.keyJump));
        mc.options.keySprint.setDown(isCurrentlyPressed(mc.options.keySprint));
        blocked = false;
    }

    public boolean isPlayerStopped(double threshold) {
        if (mc.player == null) return true;
        double vx = Math.abs(mc.player.getDeltaMovement().x);
        double vz = Math.abs(mc.player.getDeltaMovement().z);
        return vx < threshold && vz < threshold;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public void reset() {
        saved = false;
        blocked = false;
    }

    private boolean isKeyPressed(KeyMapping key) {
        return key.isDown();
    }

    private boolean isCurrentlyPressed(KeyMapping key) {
        return InputConstants.isKeyDown(
                mc.getWindow(),
                InputConstants.getKey(key.saveString()).getValue()
        );
    }
}
