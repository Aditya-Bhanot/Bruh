package releon.ru.api.module.impl.player;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import releon.ru.api.events.annotation.SubscribeEvent;
import releon.ru.api.events.impl.CameraPositionEvent;
import releon.ru.api.events.impl.ChunkOcclusionEvent;
import releon.ru.api.events.impl.InputEvent;
import releon.ru.api.events.impl.PacketEvent;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.BooleanSetting;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.utils.move.MoveUtil;

import java.util.UUID;

public final class FreeCam extends Module {
    private static final int FAKE_PLAYER_ENTITY_ID = -81374659;
    private static FreeCam instance;

    private final NumberSetting speedSetting = register(new NumberSetting("Speed", "Free camera speed.", 2.0, 0.5, 5.0, 0.1));
    private final BooleanSetting freezeSetting = register(new BooleanSetting("Freeze", "Freeze player on place.", false));

    public Vec3 pos;
    public Vec3 prevPos;
    private RemotePlayer fakePlayerEntity;

    public FreeCam() {
        super("Free Cam", "Detached free camera.", ModuleCategory.PLAYER);
        instance = this;
    }

    public static FreeCam getInstance() {
        return instance;
    }

    @Override
    protected void onEnable() {
        Minecraft client = Minecraft.getInstance();
        Vec3 cameraPos = client.gameRenderer != null && client.gameRenderer.getMainCamera() != null
                ? client.gameRenderer.getMainCamera().position()
                : client.player != null ? client.player.getEyePosition() : Vec3.ZERO;
        prevPos = pos = isFinite(cameraPos) ? cameraPos : Vec3.ZERO;
        spawnFakePlayerEntity(client);
    }

    @Override
    protected void onDisable() {
        resetRuntimeState();
        removeFakePlayerEntity(Minecraft.getInstance());
    }

    @SubscribeEvent
    private void onPacket(PacketEvent event) {
        if (event.getPacket() instanceof ServerboundMovePlayerPacket && freezeSetting.getValue()) {
            event.setCancelled(true);
        } else if (event.getPacket() instanceof ClientboundRespawnPacket || event.getPacket() instanceof ClientboundLoginPacket) {
            setEnabled(false);
        }
    }

    @SubscribeEvent
    private void onInput(InputEvent event) {
        if (pos == null) {
            return;
        }
        float speed = speedSetting.getFloat();
        double[] motion = MoveUtil.calculateDirection(event.forward(), event.sideways(), speed);
        prevPos = pos;
        pos = pos.add(motion[0], event.getInput().jump() ? speed : event.getInput().shift() ? -speed : 0.0D, motion[1]);
        event.inputNone();
    }

    @SubscribeEvent
    private void onCameraPosition(CameraPositionEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (!isFinite(prevPos) || !isFinite(pos)) {
            Vec3 fallback = isFinite(event.getPos()) ? event.getPos() : client.player != null ? client.player.getEyePosition() : Vec3.ZERO;
            prevPos = fallback;
            pos = fallback;
        }
        if (isFinite(pos)) {
            float tickDelta = client.getDeltaTracker() != null
                    ? Mth.clamp(client.getDeltaTracker().getGameTimeDeltaPartialTick(false), 0.0F, 1.0F)
                    : 1.0F;
            event.setPos(prevPos.lerp(pos, tickDelta));
        }
        if (client.options != null) {
            client.options.setCameraType(CameraType.FIRST_PERSON);
        }
    }

    @SubscribeEvent
    private void onChunkOcclusion(ChunkOcclusionEvent event) {
        event.setCancelled(true);
    }

    public boolean hasDetachedCameraState() {
        return isFinite(pos) || isFinite(prevPos) || fakePlayerEntity != null;
    }

    public void resetRuntimeState() {
        pos = null;
        prevPos = null;
    }

    private void spawnFakePlayerEntity(Minecraft client) {
        if (client.player == null || client.level == null) {
            return;
        }
        removeFakePlayerEntity(client);
        GameProfile profile = new GameProfile(UUID.randomUUID(), resolveProfileName(client.player.getGameProfile()));
        RemotePlayer fake = new RemotePlayer(client.level, profile);
        fake.setId(FAKE_PLAYER_ENTITY_ID);
        fake.copyPosition(client.player);
        fake.setYRot(client.player.getYRot());
        fake.setXRot(client.player.getXRot());
        fake.setYHeadRot(client.player.getYHeadRot());
        fake.setSprinting(client.player.isSprinting());
        fake.setShiftKeyDown(client.player.isShiftKeyDown());
        fake.setPose(client.player.getPose());
        fake.setHealth(client.player.getHealth());
        fake.setAbsorptionAmount(client.player.getAbsorptionAmount());
        for (int i = 0; i < client.player.getInventory().getContainerSize(); i++) {
            fake.getInventory().setItem(i, client.player.getInventory().getItem(i).copy());
        }
        fake.getInventory().setSelectedSlot(client.player.getInventory().getSelectedSlot());
        client.level.addEntity(fake);
        fakePlayerEntity = fake;
    }

    private void removeFakePlayerEntity(Minecraft client) {
        if (client.level != null) {
            client.level.removeEntity(FAKE_PLAYER_ENTITY_ID, Entity.RemovalReason.DISCARDED);
            if (fakePlayerEntity != null && fakePlayerEntity.getId() != FAKE_PLAYER_ENTITY_ID) {
                client.level.removeEntity(fakePlayerEntity.getId(), Entity.RemovalReason.DISCARDED);
            }
        }
        if (fakePlayerEntity != null) {
            fakePlayerEntity.discard();
        }
        fakePlayerEntity = null;
    }

    private boolean isFinite(Vec3 vec) {
        return vec != null && Double.isFinite(vec.x) && Double.isFinite(vec.y) && Double.isFinite(vec.z);
    }

    private String resolveProfileName(GameProfile profile) {
        Object value = invokeProfileMethod(profile, "name");
        if (!(value instanceof String)) {
            value = invokeProfileMethod(profile, "getName");
        }
        return value instanceof String name ? name : "FreeCam";
    }

    private Object invokeProfileMethod(GameProfile profile, String methodName) {
        try {
            return GameProfile.class.getMethod(methodName).invoke(profile);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
