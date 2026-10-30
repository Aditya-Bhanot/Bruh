package releon.ru.api.module.impl.visual.particles;

import net.minecraft.resources.Identifier;
import releon.ru.utils.render.color.ColorUtil;

final class ParticleConstants {
    static final String ROOT = "textures/features/particles/";
    static final int CLIENT_ACCENT = ColorUtil.rgba(127, 242, 255, 255);
    static final int MAX_PARTICLES = 1800;
    static final float LOCK_XZ_TRIGGER_YAW = -1337.1488F;

    static final String EVENT_WORLD = "World";
    static final String EVENT_ATTACKS = "Attacks";

    static final String TEXTURE_CROSS = "Cross";
    static final String TEXTURE_CROWN = "Crown";
    static final String TEXTURE_DOLLAR = "Dollar";
    static final String TEXTURE_HEART = "Heart";
    static final String TEXTURE_LIGHTNING = "Lightning";
    static final String TEXTURE_LINE = "Line";
    static final String TEXTURE_POINT = "Point";
    static final String TEXTURE_RHOMBUS = "Rhombus";
    static final String TEXTURE_SNOWFLAKE = "Snowflake";
    static final String TEXTURE_STAR = "Star";
    static final String TEXTURE_TRIANGLE = "Triangle";

    static final String RENDER_TEXTURES = "Textures";
    static final String RENDER_CUBES = "Cubes";
    static final String RENDER_TEXTURES_AND_CUBES = "Textures and Cubes";
    static final String RENDER_RANDOM = "Random";

    static final String RULE_NO_XZ = "No XZ Push";
    static final String RULE_RANDOM = "Random";
    static final String RULE_TO_SOURCE = "To Source";
    static final String RULE_FROM_SOURCE = "From Source";
    static final String RULE_CAMERA_LEFT = "Camera Left";
    static final String RULE_CAMERA_RIGHT = "Camera Right";
    static final String RULE_CAMERA_FORWARD = "Camera Forward";
    static final String RULE_CAMERA_BACK = "Camera Back";
    static final String RULE_NONE = "None";
    static final String RULE_TWIST_LEFT = "Twist Left";
    static final String RULE_TWIST_RIGHT = "Twist Right";
    static final String RULE_TWIST_RANDOM = "Twist Random";
    static final String RULE_ALTERNATE_SOURCE = "Alternate by Source";

    static final String COLLIDE_IGNORE = "Ignore";
    static final String COLLIDE_RICOCHET = "Ricochet";
    static final String COLLIDE_STRONG_RICOCHET = "Strong Ricochet";
    static final String COLLIDE_VERY_STRONG_RICOCHET = "Very Strong Ricochet";
    static final String COLLIDE_WEAK_RICOCHET = "Weak Ricochet";
    static final String COLLIDE_VERY_WEAK_RICOCHET = "Very Weak Ricochet";
    static final String COLLIDE_STUCK = "Stuck";
    static final String COLLIDE_FATAL = "Fatal";
    static final String COLLIDE_SLIDE = "Slide";
    static final String COLLIDE_SLIDE_SLOW = "Slide Slow";

    static final String[] EVENT_MODES = new String[]{EVENT_WORLD, EVENT_ATTACKS};
    static final String[] TEXTURE_MODES = new String[]{
            TEXTURE_CROSS, TEXTURE_CROWN, TEXTURE_DOLLAR, TEXTURE_HEART, TEXTURE_LIGHTNING, TEXTURE_LINE,
            TEXTURE_POINT, TEXTURE_RHOMBUS, TEXTURE_SNOWFLAKE, TEXTURE_STAR, TEXTURE_TRIANGLE
    };
    static final String[] RENDER_MODES = new String[]{RENDER_TEXTURES, RENDER_CUBES, RENDER_TEXTURES_AND_CUBES, RENDER_RANDOM};
    static final String[] SPAWN_RULES = new String[]{
            RULE_NO_XZ, RULE_RANDOM, RULE_TO_SOURCE, RULE_FROM_SOURCE,
            RULE_CAMERA_LEFT, RULE_CAMERA_RIGHT, RULE_CAMERA_FORWARD, RULE_CAMERA_BACK
    };
    static final String[] FORCE_RULES = new String[]{
            RULE_NONE, RULE_CAMERA_LEFT, RULE_CAMERA_RIGHT, RULE_CAMERA_FORWARD, RULE_CAMERA_BACK,
            RULE_TWIST_LEFT, RULE_TWIST_RIGHT, RULE_TWIST_RANDOM, RULE_TO_SOURCE, RULE_FROM_SOURCE, RULE_ALTERNATE_SOURCE
    };
    static final String[] COLLISION_MODES = new String[]{
            COLLIDE_IGNORE, COLLIDE_RICOCHET, COLLIDE_STRONG_RICOCHET, COLLIDE_VERY_STRONG_RICOCHET,
            COLLIDE_WEAK_RICOCHET, COLLIDE_VERY_WEAK_RICOCHET, COLLIDE_STUCK, COLLIDE_FATAL,
            COLLIDE_SLIDE, COLLIDE_SLIDE_SLOW
    };

    static final ParticleTexture[] TEXTURES = new ParticleTexture[]{
            particle(TEXTURE_CROSS, "cross.png"),
            particle(TEXTURE_CROWN, "crown.png"),
            particle(TEXTURE_DOLLAR, "dollar.png"),
            particle(TEXTURE_HEART, "heart.png"),
            particle(TEXTURE_LIGHTNING, "lighting.png"),
            particle(TEXTURE_LINE, "line.png"),
            particle(TEXTURE_POINT, "point.png"),
            particle(TEXTURE_RHOMBUS, "rhombus.png"),
            particle(TEXTURE_SNOWFLAKE, "snowflake.png"),
            particle(TEXTURE_STAR, "star.png"),
            particle(TEXTURE_TRIANGLE, "triangle.png")
    };

    private ParticleConstants() {
    }

    private static ParticleTexture particle(String mode, String path) {
        return new ParticleTexture(mode, Identifier.fromNamespaceAndPath("releon", ROOT + path));
    }
}
