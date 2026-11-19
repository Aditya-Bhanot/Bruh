package releon.ru.manager;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.module.impl.combat.MaceTarget;
import releon.ru.api.module.impl.combat.aura.AngleConnection;

import java.util.List;

/**
 * GrimAC-Safe Legit Hotbar ActionManager (Mojang 1.21.11 Official Mappings):
 * - Uses ONLY legit hotbar slot selection (setSelectedSlot) + native Vanilla keybind click simulation
 *   (KeyMapping.click(mc.options.keyUse.getDefaultKey())) instead of raw gameMode.useItem packet calls.
 * - Because Vanilla's own Minecraft.handleKeybinds() consumes the keyUse click at the exact start of
 *   the client tick (before LocalPlayer.tick() and LocalPlayer.sendPosition()) with identical (yRot, xRot),
 *   GrimAC "BadPacketsJ", "Post", "PacketOrder", and "MultiActions" flags cannot trigger even at 200ms+ ping.
 */
public final class ActionManager {
    private static final ActionManager INSTANCE = new ActionManager();
    private static final Minecraft mc = Minecraft.getInstance();

    public enum Priority {
        IDLE(0),
        COMBAT_SWING(1),
        MOVEMENT_SWAP(2),
        ELYTRA_UNEQUIP(3),
        SURVIVAL_TOTEM(4);

        public final int level;

        Priority(int level) {
            this.level = level;
        }
    }

    public enum ActionPriority {
        CRITICAL(0),
        AUTO_ELYTRA_UNEQUIP(1),
        AUTO_MACE_STRIKE(2),
        COMBAT(2),
        AUTO_ELYTRA_EQUIP(3),
        MOVEMENT(3),
        UTILITY(4);

        private final int rank;

        ActionPriority(int rank) {
            this.rank = rank;
        }

        public int getRank() {
            return rank;
        }

        public boolean outranks(ActionPriority other) {
            return other == null || this.rank < other.rank;
        }

        public boolean outranksOrEquals(ActionPriority other) {
            return other == null || this.rank <= other.rank;
        }
    }

    private ActionManager() {
    }

    public static ActionManager getInstance() {
        return INSTANCE;
    }

    public static boolean isBusy() {
        return MaceTarget.ActionManager.getInstance().isSwapInProgress();
    }

    public static boolean canDispatchInteraction(Priority requesterPriority) {
        return !isBusy() || requesterPriority.level >= Priority.ELYTRA_UNEQUIP.level;
    }

    /**
     * Queues a fast 2-tick legit hotbar swap using setSelectedSlot + KeyMapping.click(keyUse).
     */
    public static synchronized boolean executeLegitArmorSwapFromSlot(
            int hotbarSlot,
            boolean restoreSlot,
            long delayMs,
            Priority priority
    ) {
        if (mc == null || mc.player == null || mc.gameMode == null || mc.screen != null) {
            return false;
        }
        if (mc.player.isUsingItem()) {
            return false;
        }
        if (hotbarSlot < 0 || hotbarSlot > 8) {
            return MaceTarget.ActionManager.executeLegitArmorSwapFromSlot(hotbarSlot);
        }

        MaceTarget.ActionManager.ActionPriority mappedPriority = switch (priority) {
            case SURVIVAL_TOTEM -> MaceTarget.ActionManager.ActionPriority.CRITICAL;
            case ELYTRA_UNEQUIP -> MaceTarget.ActionManager.ActionPriority.AUTO_ELYTRA_UNEQUIP;
            case COMBAT_SWING -> MaceTarget.ActionManager.ActionPriority.AUTO_MACE_STRIKE;
            default -> MaceTarget.ActionManager.ActionPriority.AUTO_ELYTRA_EQUIP;
        };

        int currentSlot = mc.player.getInventory().getSelectedSlot();
        int slotToRestore = restoreSlot ? currentSlot : -1;

        return MaceTarget.ActionManager.getInstance().submitFastTwoTickArmorSwap(
                mc,
                mappedPriority,
                "LegitHotbarSwap",
                hotbarSlot,
                slotToRestore,
                true
        );
    }

    public static synchronized void onTick() {
        if (mc != null) {
            MaceTarget.ActionManager.getInstance().ensureTickSynchronized(mc);
        }
    }

    public static synchronized void onPreTickRender(Minecraft client) {
        if (client != null) {
            MaceTarget.ActionManager.getInstance().onPreTickRender(client);
        }
    }

    public static synchronized void reset() {
        MaceTarget.ActionManager.getInstance().resetAll();
    }

    // Instance methods delegating to MaceTarget.ActionManager for full compatibility across all modules
    public void ensureTickSynchronized(Minecraft client) {
        MaceTarget.ActionManager.getInstance().ensureTickSynchronized(client);
    }

    public boolean acquireLock(ActionPriority priority, String ownerName) {
        return MaceTarget.ActionManager.getInstance().acquireLock(mapPriority(priority), ownerName);
    }

    public void releaseLock(ActionPriority priority, String ownerName) {
        MaceTarget.ActionManager.getInstance().releaseLock(mapPriority(priority), ownerName);
    }

    public void forceReleaseAllLocks() {
        MaceTarget.ActionManager.getInstance().forceReleaseAllLocks();
    }

    public boolean isBlockedByHigherPriority(ActionPriority requesterPriority) {
        return MaceTarget.ActionManager.getInstance().isBlockedByHigherPriority(mapPriority(requesterPriority));
    }

    public ActionPriority getActiveLockPriority() {
        MaceTarget.ActionManager.ActionPriority active = MaceTarget.ActionManager.getInstance().getActiveLockPriority();
        if (active == null) {
            return null;
        }
        return switch (active) {
            case CRITICAL -> ActionPriority.CRITICAL;
            case AUTO_ELYTRA_UNEQUIP -> ActionPriority.AUTO_ELYTRA_UNEQUIP;
            case AUTO_MACE_STRIKE -> ActionPriority.AUTO_MACE_STRIKE;
            case AUTO_ELYTRA_EQUIP -> ActionPriority.AUTO_ELYTRA_EQUIP;
            case UTILITY -> ActionPriority.UTILITY;
            default -> ActionPriority.COMBAT;
        };
    }

    public boolean isSwapInProgress() {
        return MaceTarget.ActionManager.getInstance().isSwapInProgress();
    }

    public boolean isLocked() {
        return MaceTarget.ActionManager.getInstance().isLocked();
    }

    public boolean canDispatchInteraction(Minecraft client, ActionPriority priority) {
        return MaceTarget.ActionManager.getInstance().canDispatchInteraction(client, mapPriority(priority));
    }

    public void recordInteractionDispatched(Minecraft client) {
        MaceTarget.ActionManager.getInstance().recordInteractionDispatched(client);
    }

    public boolean submitFastTwoTickUnequip(
            Minecraft client,
            int chestplateHotbarSlot,
            int restoreSlotAfterUnequip,
            boolean clickSim
    ) {
        return MaceTarget.ActionManager.getInstance().submitFastTwoTickUnequip(
                client,
                chestplateHotbarSlot,
                restoreSlotAfterUnequip,
                clickSim
        );
    }

    public boolean submitFastTwoTickArmorSwap(
            Minecraft client,
            ActionPriority priority,
            String ownerName,
            int armorHotbarSlot,
            int restoreSlotAfterEquip,
            boolean clickSim
    ) {
        return MaceTarget.ActionManager.getInstance().submitFastTwoTickArmorSwap(
                client,
                mapPriority(priority),
                ownerName,
                armorHotbarSlot,
                restoreSlotAfterEquip,
                clickSim
        );
    }

    public boolean submitConsolidatedSwap(
            Minecraft client,
            ActionPriority priority,
            String ownerName,
            int targetSlot,
            int restoreSlot,
            boolean rightClickUseOnEquip,
            boolean clickSim,
            Runnable onSlotReady
    ) {
        return MaceTarget.ActionManager.getInstance().submitConsolidatedSwap(
                client,
                mapPriority(priority),
                ownerName,
                targetSlot,
                restoreSlot,
                rightClickUseOnEquip,
                clickSim,
                onSlotReady
        );
    }

    public List<LivingEntity> getValidTargets(Minecraft client) {
        return MaceTarget.ActionManager.getInstance().getValidTargets(client);
    }

    public LivingEntity getNearestVisibleTarget(Minecraft client, double maxRange, boolean ignoreFriends) {
        return MaceTarget.ActionManager.getInstance().getNearestVisibleTarget(client, maxRange, ignoreFriends);
    }

    public static boolean isWhitelistedFriend(Minecraft client, Entity entity) {
        return MaceTarget.ActionManager.isWhitelistedFriend(client, entity);
    }

    public static boolean hasLineOfSight(Minecraft client, LivingEntity target) {
        return MaceTarget.ActionManager.hasLineOfSight(client, target);
    }

    public static boolean verifyStrict3mAttackRaycast(Minecraft client, LivingEntity target, double requestedReach) {
        return MaceTarget.ActionManager.verifyStrict3mAttackRaycast(client, target, requestedReach);
    }

    public static boolean verifyStrict3mAttackRaycast(
            Minecraft client,
            LivingEntity target,
            double requestedReach,
            Vec3 customLookVec
    ) {
        return MaceTarget.ActionManager.verifyStrict3mAttackRaycast(client, target, requestedReach, customLookVec);
    }

    public void resetAll() {
        MaceTarget.ActionManager.getInstance().resetAll();
    }

    private static MaceTarget.ActionManager.ActionPriority mapPriority(ActionPriority p) {
        if (p == null) {
            return MaceTarget.ActionManager.ActionPriority.UTILITY;
        }
        return switch (p) {
            case CRITICAL -> MaceTarget.ActionManager.ActionPriority.CRITICAL;
            case AUTO_ELYTRA_UNEQUIP -> MaceTarget.ActionManager.ActionPriority.AUTO_ELYTRA_UNEQUIP;
            case AUTO_MACE_STRIKE, COMBAT -> MaceTarget.ActionManager.ActionPriority.AUTO_MACE_STRIKE;
            case AUTO_ELYTRA_EQUIP, MOVEMENT -> MaceTarget.ActionManager.ActionPriority.AUTO_ELYTRA_EQUIP;
            case UTILITY -> MaceTarget.ActionManager.ActionPriority.UTILITY;
        };
    }
}