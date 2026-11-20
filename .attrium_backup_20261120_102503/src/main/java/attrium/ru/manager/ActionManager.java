package attrium.ru.manager;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import attrium.ru.api.module.impl.combat.MaceTarget;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;

/**
 * Global ActionManager & Combat Priority Mutex Coordinator.
 *
 * Preserves the 2-tick hardware-simulated inventory swap while delegating all
 * slot lookups to the centralized 10-tick {@link GlobalInventoryCache}.
 */
public class ActionManager {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final ActionManager INSTANCE = new ActionManager();

    public enum Priority {
        AUTO_ELYTRA_UNEQUIP(1),
        ELYTRA_UNEQUIP(1),
        COMBAT(2),
        AUTO_MACE_STRIKE(2),
        AUTO_LUNGE_EXECUTE(3),
        MOVEMENT(4),
        MOVEMENT_SWAP(4),
        AUTO_ELYTRA_EQUIP(4),
        UTILITY(5);

        private final int rank;

        Priority(int rank) {
            this.rank = rank;
        }

        public int getRank() {
            return rank;
        }

        public boolean outranks(Priority other) {
            return other == null || this.rank < other.rank;
        }

        public boolean outranksOrEquals(Priority other) {
            return other == null || this.rank <= other.rank;
        }
    }

    private static final class QueuedAction {
        final Priority priority;
        final long sequenceNumber;
        final Runnable task;
        final BooleanSupplier validator;

        QueuedAction(Priority priority, long sequenceNumber, Runnable task, BooleanSupplier validator) {
            this.priority = priority;
            this.sequenceNumber = sequenceNumber;
            this.task = task;
            this.validator = validator;
        }
    }

    private final PriorityQueue<QueuedAction> actionQueue = new PriorityQueue<>(
            Comparator.comparingInt((QueuedAction a) -> a.priority.getRank())
                    .thenComparingLong(a -> a.sequenceNumber)
    );

    private long sequenceCounter = 0L;
    private long lastProcessedTick = -1L;
    private int actionsExecutedThisTick = 0;
    private int maxActionsPerTick = 1;
    private Priority activeTickPriority = null;
    private long activeLockTimestampMs = 0L;
    private static final long PRIORITY_LOCK_WINDOW_MS = 110L;

    public static ActionManager getInstance() {
        return INSTANCE;
    }

    public static void onPreTickRender(Minecraft client) {
        INSTANCE.onTick();
    }

    public static void reset() {
        INSTANCE.actionQueue.clear();
        INSTANCE.activeTickPriority = null;
        INSTANCE.activeLockTimestampMs = 0L;
    }

    public static boolean isBusy() {
        return INSTANCE.hasPendingActions();
    }

    public static boolean executeLegitArmorSwapFromSlot(
            int slot,
            Boolean autoClose,
            long delayMs,
            Priority priority
    ) {
        if (mc == null || mc.player == null || mc.gameMode == null || slot < 0) {
            return false;
        }
        int containerSlot = slot < 9 ? slot + 36 : slot;
        INSTANCE.swapChestplateWithSlot(containerSlot);
        return true;
    }

    public static boolean executeLegitArmorSwapFromSlot(int slot) {
        return executeLegitArmorSwapFromSlot(slot, Boolean.TRUE, 0L, Priority.MOVEMENT_SWAP);
    }

    public void setMaxActionsPerTick(int maxActionsPerTick) {
        this.maxActionsPerTick = Math.max(1, maxActionsPerTick);
    }

    public int getMaxActionsPerTick() {
        return maxActionsPerTick;
    }

    public synchronized boolean canAuthorizeAction(Priority candidatePriority) {
        if (candidatePriority == null) {
            return false;
        }
        if (candidatePriority.getRank() > Priority.AUTO_MACE_STRIKE.getRank()) {
            if (MaceTarget.isAutoMaceEnabled() && MaceTarget.canExecuteStrike()) {
                return false;
            }
        }
        long now = System.currentTimeMillis();
        if (activeTickPriority != null && (now - activeLockTimestampMs) <= PRIORITY_LOCK_WINDOW_MS) {
            if (activeTickPriority.outranks(candidatePriority)) {
                return false;
            }
        }
        QueuedAction head = actionQueue.peek();
        if (head != null && head.priority.outranks(candidatePriority)) {
            return false;
        }
        return true;
    }

    public synchronized boolean acquirePriorityLock(Priority priority) {
        if (!canAuthorizeAction(priority)) {
            return false;
        }
        if (activeTickPriority == null || priority.outranksOrEquals(activeTickPriority)) {
            activeTickPriority = priority;
            activeLockTimestampMs = System.currentTimeMillis();
        }
        return true;
    }

    public synchronized void releasePriorityLock(Priority priority) {
        if (activeTickPriority == priority) {
            activeTickPriority = null;
            activeLockTimestampMs = 0L;
        }
    }

    public synchronized Priority getActivePriority() {
        long now = System.currentTimeMillis();
        if (activeTickPriority != null && (now - activeLockTimestampMs) > PRIORITY_LOCK_WINDOW_MS) {
            activeTickPriority = null;
        }
        return activeTickPriority;
    }

    public synchronized void schedule(Priority priority, Runnable task) {
        schedule(priority, task, () -> true);
    }

    public synchronized void schedule(Priority priority, Runnable task, BooleanSupplier validator) {
        if (priority == null || task == null) {
            return;
        }
        if (priority.getRank() <= Priority.AUTO_MACE_STRIKE.getRank()) {
            actionQueue.removeIf(queued -> queued.priority.getRank() > priority.getRank());
        }
        if (activeTickPriority == null || priority.outranksOrEquals(activeTickPriority)) {
            activeTickPriority = priority;
            activeLockTimestampMs = System.currentTimeMillis();
        }
        actionQueue.offer(new QueuedAction(
                priority,
                ++sequenceCounter,
                task,
                validator != null ? validator : () -> true
        ));
    }

    public synchronized void clear() {
        actionQueue.clear();
        activeTickPriority = null;
        activeLockTimestampMs = 0L;
    }

    public synchronized void clearPriority(Priority priority) {
        if (priority == null) {
            return;
        }
        actionQueue.removeIf(queued -> queued.priority == priority);
        if (activeTickPriority == priority) {
            activeTickPriority = null;
            activeLockTimestampMs = 0L;
        }
    }

    public synchronized boolean hasPendingActions() {
        return !actionQueue.isEmpty();
    }

    public synchronized void onTick() {

        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            actionQueue.clear();
            activeTickPriority = null;
            return;
        }

        long currentTick = mc.level.getGameTime();
        if (currentTick != lastProcessedTick) {
            lastProcessedTick = currentTick;
            actionsExecutedThisTick = 0;
        }

        if (MaceTarget.isAutoMaceEnabled() && MaceTarget.canExecuteStrike()) {
            actionQueue.removeIf(queued -> queued.priority == Priority.AUTO_LUNGE_EXECUTE);
            if (activeTickPriority == Priority.AUTO_LUNGE_EXECUTE) {
                activeTickPriority = Priority.AUTO_MACE_STRIKE;
                activeLockTimestampMs = System.currentTimeMillis();
            }
        }

        List<QueuedAction> skipped = new ArrayList<>();
        while (actionsExecutedThisTick < maxActionsPerTick && !actionQueue.isEmpty()) {
            QueuedAction next = actionQueue.poll();
            if (next == null) {
                break;
            }
            if (!next.validator.getAsBoolean()) {
                continue;
            }
            try {
                activeTickPriority = next.priority;
                activeLockTimestampMs = System.currentTimeMillis();
                next.task.run();
                actionsExecutedThisTick++;
            } catch (Exception ignored) {
            }
        }

        actionQueue.addAll(skipped);
    }

    public boolean isWearingElytra() {
        if (mc.player == null) {
            return false;
        }
        return mc.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
    }

    public int findItemSlotInHotbar(Item item) {
        if (mc.player == null || mc.player.getInventory() == null || item == null) {
            return -1;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && (stack.is(item) || stack.getItem() == item)) {
                return i;
            }
        }
        return -1;
    }

    public int findItemSlotInInventory(Item item) {
        if (mc.player == null || mc.player.getInventory() == null || item == null) {
            return -1;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && (stack.is(item) || stack.getItem() == item)) {
                return i + 36;
            }
        }
        return -1;
    }

    public void swapChestplateWithSlot(int containerSlot) {
        if (mc.player == null || mc.gameMode == null || containerSlot < 0) {
            return;
        }
        int containerId = mc.player.inventoryMenu.containerId;
        // Strictly hotbar-only (container slots 36..44 = hotbar 0..8); never touch main inventory (9..35)
        if (containerSlot >= 36 && containerSlot <= 44) {
            int hotbarIndex = containerSlot - 36;
            mc.gameMode.handleInventoryMouseClick(containerId, 6, hotbarIndex, ClickType.SWAP, mc.player);
        }
    }
}
