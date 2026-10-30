package releon.ru.api.module.impl.visual.particles;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

final class ParticleWorld {
    private ParticleWorld() {
    }

    static Vec3 entityVecForParticle(Minecraft client, Entity entityFor, float yMultiplier) {
        Entity entity = entityFor == null ? client.player : entityFor;
        if (entity == null) {
            return Vec3.ZERO;
        }
        float eyeHeight = client.player == null ? entity.getEyeHeight() : client.player.getEyeHeight();
        return entity.getEyePosition().add(0.0D, -eyeHeight * yMultiplier, 0.0D);
    }

    static Vec3 randomSpawnPosition(Minecraft client, Random random, ParticleSettings settings, Vec3 at, Entity entityFor) {
        if (client.level == null) {
            return null;
        }
        boolean worldPoint = entityFor == null || entityFor == client.player;
        float maxDistance = worldPoint ? settings.maxSpawnDistanceInWorld.getFloat() : settings.maxSpawnDistanceOnAttack.getFloat();
        for (int attempt = 0; attempt < 10; attempt++) {
            Vec3 vec = ParticleMath.randomVec(random, at, maxDistance / 5.0F, maxDistance);
            if (!isSolidCollisionBlock(client, vec.x, vec.y, vec.z)) {
                return vec;
            }
        }
        return null;
    }

    static boolean isSolidCollisionBlock(Minecraft client, double x, double y, double z) {
        if (client.level == null) {
            return false;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        BlockState state = client.level.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(client.level, pos).isEmpty();
    }
}
