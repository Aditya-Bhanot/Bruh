package releon.ru.utils.inventory;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.AbstractCommandBlockEditScreen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.SignEditScreen;
import net.minecraft.client.gui.screens.inventory.StructureBlockEditScreen;
import org.lwjgl.glfw.GLFW;
import releon.ru.utils.inventory.script.ScriptAction;
import releon.ru.IMinecraft;
import releon.ru.api.events.impl.InputEvent;
import releon.ru.api.module.impl.movement.InventoryMove;
import releon.ru.utils.inventory.script.Script;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class InventoryFlowManager implements IMinecraft {
    public static final Script script = new Script();
    public static final Script postScript = new Script();

    private static final Queue<Runnable> TASK_QUEUE = new ConcurrentLinkedQueue<>();
    private static final int CLOSE_SPRINT_DELAY_TICKS = 0;
    private static final int CLOSE_PACKET_DELAY_TICKS = 0;
    private static final int CLOSE_RESTORE_TICKS = 0;

    private static boolean canMove = true;

    private InventoryFlowManager() {
    }

    public static void tick() {
        script.update();
        postScript.update();
        InventoryTask.updateSwapAndUseScript();

        if (script.isFinished() && postScript.isFinished() && !TASK_QUEUE.isEmpty()) {
            Runnable nextTask = TASK_QUEUE.poll();
            if (nextTask != null) {
                addTask(nextTask);
            }
        }
    }

    public static void update() {
        tick();
    }

    public static void postMotion() {
        postScript.update();
    }

    public static void input(InputEvent event) {
        if (event == null) {
            return;
        }

        InventoryMove inventoryMove = InventoryMove.getInstance();
        if (inventoryMove != null && inventoryMove.isEnabled() && inventoryMove.shouldSuppressSprintInput()) {
            event.setSprinting(false);
        }

        if (canMove) {
            return;
        }

        event.setDirectionalLow(false, false, false, false);
        event.setJumping(false);
        event.setSprinting(false);
    }

    public static void addTask(Runnable task) {
        if (task == null) {
            return;
        }

        if (!script.isFinished() || !postScript.isFinished()) {
            TASK_QUEUE.offer(task);
            return;
        }

        switch (getSelectedMode()) {
            case "FunTime" -> script.cleanup()

                    .addStep(0, new ScriptActionAdapter() {
                        @Override
                        public void perform() {
                            disableMoveKeys();
                        }
                    })
                    .addStep(1, new ScriptRunnableAction(task))
                    .addStep(0, new ScriptActionAdapter() {
                        @Override
                        public void perform() {
                            enableMoveKeys();
                        }
                    });
            case "SpookyTime" -> script.cleanup()
                    .addStep(0, new ScriptActionAdapter() {
                        @Override
                        public void perform() {
                            disableMoveKeys();
                        }
                    })
                    .addStep(0, new ScriptRunnableAction(task))
                    .addStep(0, new ScriptActionAdapter() {
                        @Override
                        public void perform() {
                            enableMoveKeys();
                        }
                    });
            case "HolyWorld" -> script.cleanup()
                    .addStep(0, new ScriptActionAdapter() {
                        @Override
                        public void perform() {
                            disableMoveKeys();
                        }
                    })
                    .addStep(0, new ScriptRunnableAction(task))
                    .addStep(0, new ScriptActionAdapter() {
                        @Override
                        public void perform() {
                            enableMoveKeys();
                        }
                    });
            case "CopyTime" -> script.cleanup()
                    .addStep(0, new ScriptActionAdapter() {
                        @Override
                        public void perform() {
                            disableMoveKeys();
                        }
                    })
                    .addStep(0, new ScriptRunnableAction(task))
                    .addStep(0, new ScriptActionAdapter() {
                        @Override
                        public void perform() {
                            enableMoveKeys();
                        }
                    });
            case "ReallyWorld", "Normal" -> task.run();
            default -> task.run();
        }
    }

    public static void disableMoveKeys() {
        canMove = false;
        postScript.cleanup();
        unPressMoveKeys();
    }

    public static void enableMoveKeys() {
        Script restoreScript = postScript.cleanup()
                .addTickStep(0, new ScriptActionAdapter() {
                    @Override
                    public void perform() {
                        unPressMoveKeys();
                    }
                });

        if (TASK_QUEUE.isEmpty()) {
            restoreScript
                    .addTickStep(CLOSE_SPRINT_DELAY_TICKS, new ScriptActionAdapter() {
                        @Override
                        public void perform() {
                            stopPlayerSprintBeforeClose();
                        }
                    })
                    .addTickStep(CLOSE_PACKET_DELAY_TICKS, new ScriptActionAdapter() {
                        @Override
                        public void perform() {
                            InventoryTask.closeScreen(true);
                        }
                    })
                    .addTickStep(CLOSE_PACKET_DELAY_TICKS + CLOSE_RESTORE_TICKS, new ScriptActionAdapter() {
                        @Override
                        public void perform() {
                            finishEnableMoveKeys();
                        }
                    });
            return;
        }

        restoreScript.addTickStep(CLOSE_RESTORE_TICKS, new ScriptActionAdapter() {
            @Override
            public void perform() {
                finishEnableMoveKeys();
            }
        });
    }

    public static void unPressMoveKeys() {
        if (mc.options == null) {
            return;
        }

        for (KeyMapping key : getMoveKeys()) {
            key.setDown(false);
        }

        stopSprint();
    }

    public static void updateMoveKeys() {
        if (mc.options == null || mc.getWindow() == null) {
            return;
        }

        for (KeyMapping key : getMoveKeys()) {
            key.setDown(isCurrentlyPressed(key));
        }
    }

    public static boolean shouldSkipExecution() {
        if (mc.screen == null) {
            return false;
        }

        if (mc.screen instanceof ChatScreen
                || mc.screen instanceof SignEditScreen
                || mc.screen instanceof AnvilScreen
                || mc.screen instanceof AbstractCommandBlockEditScreen
                || mc.screen instanceof StructureBlockEditScreen) {
            return false;
        }

        return !("HolyWorld".equals(getSelectedMode()) && mc.screen instanceof ContainerScreen);
    }

    public static void reset() {
        script.cleanup();
        postScript.cleanup();
        TASK_QUEUE.clear();
        canMove = true;
        updateMoveKeys();
        updateSprintKey();
    }

    public static boolean isMovementAllowed() {
        return canMove;
    }

    public static boolean isIdle() {
        return script.isFinished() && postScript.isFinished() && TASK_QUEUE.isEmpty();
    }

    private static void finishEnableMoveKeys() {
        canMove = true;
        updateMoveKeys();
        updateSprintKey();
    }

    private static void updateSprintKey() {
        if (mc.options == null || mc.getWindow() == null) {
            return;
        }

        mc.options.keySprint.setDown(isCurrentlyPressed(mc.options.keySprint));
    }

    private static void stopSprint() {
        if (mc.options != null) {
            mc.options.keySprint.setDown(false);
        }
    }

    private static void stopPlayerSprintBeforeClose() {
        stopSprint();

        if (mc.player != null) {
            mc.player.setSprinting(false);
        }
    }

    private static String getSelectedMode() {
        InventoryMove inventoryMove = InventoryMove.getInstance();
        if (inventoryMove == null || !inventoryMove.isEnabled()) {
            return "Normal";
        }
        return normalizeModeName(inventoryMove.getSelectedMode());
    }

    private static String normalizeModeName(String mode) {
        if (mode == null) {
            return "Normal";
        }

        return switch (mode) {
            case "ФанТайм", "Фантайм" -> "FunTime";
            case "СпукиТайм" -> "SpookyTime";
            case "ХолиВорлд" -> "HolyWorld";
            case "РиллиВорлд" -> "ReallyWorld";
            case "КопиТайм" -> "CopyTime";
            default -> mode;
        };
    }

    private static List<KeyMapping> getMoveKeys() {
        return List.of(
                mc.options.keyUp,
                mc.options.keyDown,
                mc.options.keyLeft,
                mc.options.keyRight,
                mc.options.keyJump,
                mc.options.keySprint
        );
    }

    private static boolean isCurrentlyPressed(KeyMapping key) {
        if (mc.getWindow() == null) {
            return false;
        }

        InputConstants.Key inputKey = InputConstants.getKey(key.saveString());
        var window = mc.getWindow();
        long handle = window.handle();

        return switch (inputKey.getType()) {
            case KEYSYM -> InputConstants.isKeyDown(window, inputKey.getValue());
            case MOUSE -> GLFW.glfwGetMouseButton(handle, inputKey.getValue()) == GLFW.GLFW_PRESS;
            default -> false;
        };
    }

    private abstract static class ScriptActionAdapter implements ScriptAction {
    }

    private static final class ScriptRunnableAction extends ScriptActionAdapter {
        private final Runnable runnable;

        private ScriptRunnableAction(Runnable runnable) {
            this.runnable = runnable;
        }

        @Override
        public void perform() {
            if (runnable != null) {
                runnable.run();
            }
        }
    }
}
