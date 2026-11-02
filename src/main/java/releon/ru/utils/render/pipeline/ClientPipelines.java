package releon.ru.utils.render.pipeline;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import releon.ru.utils.render.RenderCompatibility;
import releon.ru.utils.render.RenderLayerFactory;

import java.util.function.Function;
import net.minecraft.util.Util;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import static net.minecraft.client.renderer.RenderPipelines.MATRICES_PROJECTION_SNIPPET;

public final class ClientPipelines {

    private ClientPipelines() {
    }

    private static BlendFunction worldBlend() {
        return RenderCompatibility.useSafeWorldEffects() ? BlendFunction.TRANSLUCENT : BlendFunction.LIGHTNING;
    }

    public static final RenderPipeline ROMB_ESP_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(MATRICES_PROJECTION_SNIPPET)
                    .withLocation("pipeline/wtex")
                    .withVertexShader("core/position_tex_color")
                    .withFragmentShader("core/position_tex_color")
                    .withSampler("Sampler0")
                    .withBlend(worldBlend())
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withCull(false)
                    .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
                    .build()
    );

    public static final Function<Identifier, RenderType> ROMB_ESP =
            Util.memoize(texture -> RenderLayerFactory.create("wtex", 1536, ROMB_ESP_PIPELINE, texture));

    public static final RenderPipeline CLICKGUI_CLOSE_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(MATRICES_PROJECTION_SNIPPET)
                    .withLocation("pipeline/clickgui_close")
                    .withVertexShader("core/position_tex_color")
                    .withFragmentShader("core/position_tex_color")
                    .withSampler("Sampler0")
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withCull(false)
                    .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
                    .build()
    );

    public static final Function<Identifier, RenderType> CLICKGUI_CLOSE =
            Util.memoize(texture -> RenderLayerFactory.create("clickgui_close", 1536, CLICKGUI_CLOSE_PIPELINE, texture));

    public static final RenderPipeline GHOSTS_ESP_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(MATRICES_PROJECTION_SNIPPET)
                    .withLocation("pipeline/wtex")
                    .withVertexShader("core/position_tex_color")
                    .withFragmentShader("core/position_tex_color")
                    .withSampler("Sampler0")
                    .withBlend(worldBlend())
                    .withDepthTestFunction(DepthTestFunction.LESS_DEPTH_TEST)
                    .withDepthWrite(false)
                    .withCull(false)
                    .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
                    .build()
    );

    public static final Function<Identifier, RenderType> GHOSTS_ESP =
            Util.memoize(texture -> RenderLayerFactory.create("wtex", 1536, GHOSTS_ESP_PIPELINE, texture));

    public static final RenderPipeline CHAIN_ESP_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(MATRICES_PROJECTION_SNIPPET)
                    .withLocation("pipeline/wtex")
                    .withVertexShader("core/position_tex_color")
                    .withFragmentShader("core/position_tex_color")
                    .withSampler("Sampler0")
                    .withBlend(worldBlend())
                    .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                    .withDepthWrite(false)
                    .withCull(false)
                    .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
                    .build()
    );

    public static final Function<Identifier, RenderType> CHAIN_ESP =
            Util.memoize(texture -> RenderLayerFactory.create("wtex", 1536, CHAIN_ESP_PIPELINE, texture));

    public static final RenderPipeline CRYSTAL_FILLED_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(MATRICES_PROJECTION_SNIPPET)
                    .withLocation("pipeline/crystal_filled")
                    .withVertexShader("core/position_color")
                    .withFragmentShader("core/position_color")
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                    .withDepthWrite(false)
                    .withCull(false)
                    .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
                    .build()
    );

    public static final RenderType CRYSTAL_FILLED = RenderLayerFactory.create("crystal_filled", 8192, CRYSTAL_FILLED_PIPELINE);

    public static final RenderPipeline CRYSTAL_GLOW_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(MATRICES_PROJECTION_SNIPPET)
                    .withLocation("pipeline/crystal_glow")
                    .withVertexShader("core/position_color")
                    .withFragmentShader("core/position_color")
                    .withBlend(worldBlend())
                    .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                    .withDepthWrite(false)
                    .withCull(false)
                    .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
                    .build()
    );

    public static final RenderType CRYSTAL_GLOW = RenderLayerFactory.create("crystal_glow", 4096, CRYSTAL_GLOW_PIPELINE);

    public static final RenderPipeline BLOOM_ESP_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(MATRICES_PROJECTION_SNIPPET)
                    .withLocation("pipeline/bloom_esp")
                    .withVertexShader("core/position_tex_color")
                    .withFragmentShader("core/position_tex_color")
                    .withSampler("Sampler0")
                    .withBlend(worldBlend())
                    .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                    .withDepthWrite(false)
                    .withCull(false)
                    .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
                    .build()
    );

    public static final Function<Identifier, RenderType> BLOOM_ESP =
            Util.memoize(texture -> RenderLayerFactory.create("bloom_esp", 2048, BLOOM_ESP_PIPELINE, texture));

    public static final RenderPipeline CHINA_HAT_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(MATRICES_PROJECTION_SNIPPET)
                    .withLocation("pipeline/china_hat")
                    .withVertexShader("core/position_color")
                    .withFragmentShader("core/position_color")
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                    .withDepthWrite(true)
                    .withCull(false)
                    .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLE_FAN)
                    .build()
    );

    public static final RenderType CHINA_HAT = RenderLayerFactory.create("china_hat", 8192, CHINA_HAT_PIPELINE);

    public static final RenderPipeline CHINA_HAT_OUTLINE_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(MATRICES_PROJECTION_SNIPPET)
                    .withLocation("pipeline/china_hat_outline")
                    .withVertexShader("core/position_color")
                    .withFragmentShader("core/position_color")
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                    .withDepthWrite(true)
                    .withCull(false)
                    .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.DEBUG_LINE_STRIP)
                    .build()
    );

    public static final RenderType CHINA_HAT_OUTLINE = RenderLayerFactory.create("china_hat_outline", 4096, CHINA_HAT_OUTLINE_PIPELINE);

    public static final RenderPipeline WORLD_PARTICLES_COLOR_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("releon", "world_particles_color"))
                    .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
                    .withCull(false)
                    .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                    .withDepthWrite(false)
                    .withBlend(worldBlend())
                    .build()
    );

    public static final RenderType WORLD_PARTICLES_QUADS = RenderLayerFactory.create("world_particles_cube", 2048, WORLD_PARTICLES_COLOR_PIPELINE);

    public static final RenderPipeline WORLD_PARTICLES_LINES_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("releon", "world_particles_lines"))
                    .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.DEBUG_LINES)
                    .withCull(false)
                    .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                    .withDepthWrite(false)
                    .withBlend(worldBlend())
                    .build()
    );

    public static final RenderType WORLD_PARTICLES_LINES = RenderLayerFactory.create("world_particles_lines", 2048, WORLD_PARTICLES_LINES_PIPELINE);

    public static final RenderPipeline WORLD_PARTICLES_GLOW_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("releon", "world_particles_glow"))
                    .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
                    .withCull(false)
                    .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                    .withDepthWrite(false)
                    .withBlend(worldBlend())
                    .withSampler("Sampler0")
                    .build()
    );

    public static final Function<Identifier, RenderType> WORLD_PARTICLES_GLOW =
            Util.memoize(texture -> RenderLayerFactory.create("world_particles_glow", 2048, WORLD_PARTICLES_GLOW_PIPELINE, texture));

    public static final RenderPipeline GUI_ARROW_BLEND_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(MATRICES_PROJECTION_SNIPPET)
                    .withLocation("pipeline/gui_arrow_blend")
                    .withVertexShader("core/position_tex_color")
                    .withFragmentShader("core/position_tex_color")
                    .withSampler("Sampler0")
                    .withBlend(BlendFunction.LIGHTNING)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withCull(false)
                    .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.QUADS)
                    .build()
    );

    public static final Function<Identifier, RenderType> GUI_ARROW_BLEND =
            Util.memoize(texture -> RenderLayerFactory.create("gui_arrow_blend", 256, GUI_ARROW_BLEND_PIPELINE, texture));

    public static final RenderPipeline TRAILS_ALPHA_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("releon", "trails_alpha"))
                    .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
                    .withCull(false)
                    .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                    .withDepthWrite(false)
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .build()
    );

    public static final RenderType TRAILS_ALPHA = RenderLayerFactory.create("trails_alpha", 16384, TRAILS_ALPHA_PIPELINE);
}
