package attrium.ru.manager;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import attrium.ru.screens.GlassClickGuiScreen;

/**
 * Clean Tick Pipeline Coordinator (`TickPipelineManager`).
 * Polls keybinds, updates target scanning once per tick, and runs any queued post-motion tasks
 * directly without sending duplicate look packets or spoofing/reverting hotbar slots.
 */
public final class TickPipelineManager {
    private static final TickPipelineManager INSTANCE = new TickPipelineManager();

    public enum TickPhase {
        IDLE,
        PHASE_1_TARGET_ACQUISITION,
        PHASE_2_PRE_MOTION,
        PHASE_3_DISPATCH,
        PHASE_4_POST_MOTION
    }

    private static final int MAX_QUEUED_POST_ACTIONS = 32;

    private static final class PostMotionSlot {
        int spoofSlot = -1;
        Packet<?> directPacket = null;
        Runnable customTask = null;

        void clear() {
            spoofSlot = -1;
            directPacket = null;
            customTask = null;
        }
    }

    private final PostMotionSlot[] postMotionPool = new PostMotionSlot[MAX_QUEUED_POST_ACTIONS];
    private int queuedPostMotionCount = 0;
    // [ATTRIUM-STEP3-LOCKFREE-TICK]
    private volatile TickPhase currentPhase = TickPhase.IDLE;
    private long lastPhase1GameTime = -1L;

    private TickPipelineManager() {
        for (int i = 0; i < MAX_QUEUED_POST_ACTIONS; i++) {
            postMotionPool[i] = new PostMotionSlot();
        }
    }

    public static TickPipelineManager getInstance() {
        return INSTANCE;
    }

    public TickPhase getCurrentPhase() {
        return currentPhase;
    }

    public void beginTickPhase1And2(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null) {
            resetPipeline();
            return;
        }

        long gameTime = mc.level.getGameTime();
        if (gameTime == lastPhase1GameTime) {
            return;
        }
        lastPhase1GameTime = gameTime;

        // [ATTRIUM-STEP4-TICK-PROF]
        long _profTickStart = PerformanceProfiler.beginTick();
        currentPhase = TickPhase.PHASE_1_TARGET_ACQUISITION;
        clearPostMotionQueue();

        try {
            GlassClickGuiScreen.pollGlobalKeybinds();
        } catch (Throwable ignored) {
        }

        ActionManager.getInstance().onTick();
        TargetManager.updateOncePerTick(mc);
        EntityTargetScanner.ensureUpdated(mc, false);

        currentPhase = TickPhase.PHASE_2_PRE_MOTION;
        RotationManager.getInstance().onClientTick(mc);
        PerformanceProfiler.endTick(_profTickStart);
    }

    public boolean enqueuePostMotionPacket(
            int requiredHotbarSlot,
            Packet<?> actionPacket,
            boolean restoreSlotAfter
    ) {
        if (actionPacket == null || queuedPostMotionCount >= MAX_QUEUED_POST_ACTIONS) {
            return false;
        }
        PostMotionSlot slot = postMotionPool[queuedPostMotionCount++];
        slot.spoofSlot = requiredHotbarSlot;
        slot.directPacket = actionPacket;
        slot.customTask = null;
        return true;
    }

    public boolean enqueuePostMotionTask(
            int requiredHotbarSlot,
            boolean restoreSlotAfter,
            Runnable postMotionTask
    ) {
        if (postMotionTask == null || queuedPostMotionCount >= MAX_QUEUED_POST_ACTIONS) {
            return false;
        }
        PostMotionSlot slot = postMotionPool[queuedPostMotionCount++];
        slot.spoofSlot = requiredHotbarSlot;
        slot.directPacket = null;
        slot.customTask = postMotionTask;
        return true;
    }

    public void notifyMovementPacketDispatched() {
    }

    public void completeTickPhase3And4(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null || mc.player.connection == null) {
            resetPipeline();
            return;
        }

        currentPhase = TickPhase.PHASE_4_POST_MOTION;
        for (int i = 0; i < queuedPostMotionCount; i++) {
            PostMotionSlot entry = postMotionPool[i];
            try {
                if (entry.spoofSlot >= 0 && entry.spoofSlot <= 8
                        && mc.player.getInventory().getSelectedSlot() != entry.spoofSlot) {
                    mc.player.getInventory().setSelectedSlot(entry.spoofSlot);
                }
                if (entry.directPacket != null) {
                    mc.player.connection.send(entry.directPacket);
                } else if (entry.customTask != null) {
                    entry.customTask.run();
                }
            } catch (Throwable ignored) {
            } finally {
                entry.clear();
            }
        }
        queuedPostMotionCount = 0;
        currentPhase = TickPhase.IDLE;
    }

    private void clearPostMotionQueue() {
        for (int i = 0; i < queuedPostMotionCount; i++) {
            postMotionPool[i].clear();
        }
        queuedPostMotionCount = 0;
    }

    public void resetPipeline() {
        clearPostMotionQueue();
        currentPhase = TickPhase.IDLE;
        lastPhase1GameTime = -1L;
    }
}
