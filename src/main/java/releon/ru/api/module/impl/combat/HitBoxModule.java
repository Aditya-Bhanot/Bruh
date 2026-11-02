package releon.ru.api.module.impl.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import releon.ru.api.module.Module;
import releon.ru.api.module.ModuleCategory;
import releon.ru.api.settings.impl.NumberSetting;
import releon.ru.manager.Manager;
import releon.ru.utils.repository.friend.FriendUtils;

public class HitBoxModule extends Module {
    private final NumberSetting xzExpand = register(new NumberSetting("Expand XZ", "Expand target hitbox on X/Z.", 0.2, 0.0, 3.0, 0.05));
    private final NumberSetting yExpand = register(new NumberSetting("Expand Y", "Expand target hitbox on Y.", 0.0, 0.0, 3.0, 0.05));

    public HitBoxModule() {
        super("Hit Box", "Expands target hitboxes.", ModuleCategory.COMBAT);
    }

    public static AABB apply(LivingEntity entity, AABB box) {
        Module module = Manager.getModules() == null ? null : Manager.getModules().getHitBox().orElse(null);
        Minecraft client = Minecraft.getInstance();
        if (!(module instanceof HitBoxModule hitBox) || !module.isEnabled() || entity == client.player || FriendUtils.isFriend(entity)) {
            return box;
        }
        double xz = hitBox.xzExpand.getValue() * 0.5D;
        double y = hitBox.yExpand.getValue() * 0.5D;
        return new AABB(box.minX - xz, box.minY - y, box.minZ - xz, box.maxX + xz, box.maxY + y, box.maxZ + xz);
    }
}
