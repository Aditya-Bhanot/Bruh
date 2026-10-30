package releon.ru.utils.render.ui.msdf;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import org.joml.Matrix3x2f;

final class MsdfIconRenderState implements GuiElementRenderState {
    private final Matrix3x2f pose;
    private final TextureSetup textureSetup;
    private final ScreenRectangle scissorArea;
    private final float distanceRange;
    private float[] positions = new float[16 * 8];
    private float[] uvs = new float[16 * 4];
    private int[] colors = new int[16 * 4];
    private int quadCount;
    private float minX = Float.MAX_VALUE;
    private float minY = Float.MAX_VALUE;
    private float maxX = -Float.MAX_VALUE;
    private float maxY = -Float.MAX_VALUE;

    MsdfIconRenderState(Matrix3x2f pose, TextureSetup textureSetup, ScreenRectangle scissorArea, float distanceRange) {
        this.pose = pose;
        this.textureSetup = textureSetup;
        this.scissorArea = scissorArea;
        this.distanceRange = distanceRange;
    }

    void add(float x, float y, float width, float height, MsdfGlyph glyph, int color) {
        add(x, y, width, height, glyph, color, color, color, color, 0.0f, x, y, false, false, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
    }

    void add(
            float x,
            float y,
            float width,
            float height,
            MsdfGlyph glyph,
            int colorTopLeft,
            int colorTopRight,
            int colorBottomRight,
            int colorBottomLeft,
            float rotationDegrees,
            float rotationOriginX,
            float rotationOriginY,
            boolean fadeLeft,
            boolean fadeRight,
            float fadeLeftX,
            float fadeRightX,
            float fadeWidth,
            float fadeLeftStrength,
            float fadeRightStrength
    ) {
        ensureCapacity(quadCount + 1);
        int positionIndex = quadCount * 8;
        int uvIndex = quadCount * 4;
        int colorIndex = quadCount * 4;
        float x1 = x + width;
        float y1 = y + height;
        float tlX = x;
        float tlY = y;
        float blX = x;
        float blY = y1;
        float brX = x1;
        float brY = y1;
        float trX = x1;
        float trY = y;

        if (Math.abs(rotationDegrees) > 0.001f) {
            float radians = (float) Math.toRadians(rotationDegrees);
            float sin = (float) Math.sin(radians);
            float cos = (float) Math.cos(radians);
            tlX = rotateX(x, y, rotationOriginX, rotationOriginY, sin, cos);
            tlY = rotateY(x, y, rotationOriginX, rotationOriginY, sin, cos);
            blX = rotateX(x, y1, rotationOriginX, rotationOriginY, sin, cos);
            blY = rotateY(x, y1, rotationOriginX, rotationOriginY, sin, cos);
            brX = rotateX(x1, y1, rotationOriginX, rotationOriginY, sin, cos);
            brY = rotateY(x1, y1, rotationOriginX, rotationOriginY, sin, cos);
            trX = rotateX(x1, y, rotationOriginX, rotationOriginY, sin, cos);
            trY = rotateY(x1, y, rotationOriginX, rotationOriginY, sin, cos);
        }

        boolean faded = (fadeLeft || fadeRight)
                && fadeWidth > 0.0f
                && fadeRightX > fadeLeftX
                && (fadeLeftStrength > 0.001f || fadeRightStrength > 0.001f);

        positions[positionIndex] = tlX;
        positions[positionIndex + 1] = tlY;
        positions[positionIndex + 2] = blX;
        positions[positionIndex + 3] = blY;
        positions[positionIndex + 4] = brX;
        positions[positionIndex + 5] = brY;
        positions[positionIndex + 6] = trX;
        positions[positionIndex + 7] = trY;

        uvs[uvIndex] = glyph.u0();
        uvs[uvIndex + 1] = glyph.v0();
        uvs[uvIndex + 2] = glyph.u1();
        uvs[uvIndex + 3] = glyph.v1();

        colors[colorIndex] = faded ? fadeColor(colorTopLeft, tlX, fadeLeft, fadeRight, fadeLeftX, fadeRightX, fadeWidth, fadeLeftStrength, fadeRightStrength) : colorTopLeft;
        colors[colorIndex + 1] = faded ? fadeColor(colorBottomLeft, blX, fadeLeft, fadeRight, fadeLeftX, fadeRightX, fadeWidth, fadeLeftStrength, fadeRightStrength) : colorBottomLeft;
        colors[colorIndex + 2] = faded ? fadeColor(colorBottomRight, brX, fadeLeft, fadeRight, fadeLeftX, fadeRightX, fadeWidth, fadeLeftStrength, fadeRightStrength) : colorBottomRight;
        colors[colorIndex + 3] = faded ? fadeColor(colorTopRight, trX, fadeLeft, fadeRight, fadeLeftX, fadeRightX, fadeWidth, fadeLeftStrength, fadeRightStrength) : colorTopRight;
        quadCount++;

        include(tlX, tlY);
        include(blX, blY);
        include(brX, brY);
        include(trX, trY);
    }

    @Override
    public void buildVertices(VertexConsumer consumer) {
        for (int i = 0; i < quadCount; i++) {
            int positionIndex = i * 8;
            int uvIndex = i * 4;
            int colorIndex = i * 4;
            float u0 = uvs[uvIndex];
            float v0 = uvs[uvIndex + 1];
            float u1 = uvs[uvIndex + 2];
            float v1 = uvs[uvIndex + 3];

            vertex(consumer, positions[positionIndex], positions[positionIndex + 1], u0, v0, colors[colorIndex]);
            vertex(consumer, positions[positionIndex + 2], positions[positionIndex + 3], u0, v1, colors[colorIndex + 1]);
            vertex(consumer, positions[positionIndex + 4], positions[positionIndex + 5], u1, v1, colors[colorIndex + 2]);
            vertex(consumer, positions[positionIndex + 6], positions[positionIndex + 7], u1, v0, colors[colorIndex + 3]);
        }
    }

    private void ensureCapacity(int requestedQuads) {
        int currentQuads = colors.length / 4;
        if (requestedQuads <= currentQuads) {
            return;
        }

        int newQuads = currentQuads;
        while (newQuads < requestedQuads) {
            newQuads *= 2;
        }
        float[] newPositions = new float[newQuads * 8];
        float[] newUvs = new float[newQuads * 4];
        int[] newColors = new int[newQuads * 4];
        System.arraycopy(positions, 0, newPositions, 0, quadCount * 8);
        System.arraycopy(uvs, 0, newUvs, 0, quadCount * 4);
        System.arraycopy(colors, 0, newColors, 0, quadCount * 4);
        positions = newPositions;
        uvs = newUvs;
        colors = newColors;
    }

    private float rotateX(float x, float y, float originX, float originY, float sin, float cos) {
        float translatedX = x - originX;
        float translatedY = y - originY;
        return originX + translatedX * cos - translatedY * sin;
    }

    private float rotateY(float x, float y, float originX, float originY, float sin, float cos) {
        float translatedX = x - originX;
        float translatedY = y - originY;
        return originY + translatedX * sin + translatedY * cos;
    }

    private void include(float x, float y) {
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
    }

    private int fadeColor(int color, float x, boolean fadeLeft, boolean fadeRight, float fadeLeftX, float fadeRightX, float fadeWidth, float fadeLeftStrength, float fadeRightStrength) {
        float alpha = 1.0f;
        if (fadeLeft) {
            float edgeAlpha = smoothstep(clamp((x - fadeLeftX) / fadeWidth, 0.0f, 1.0f));
            alpha = Math.min(alpha, 1.0f - clamp(fadeLeftStrength, 0.0f, 1.0f) * (1.0f - edgeAlpha));
        }
        if (fadeRight) {
            float edgeAlpha = smoothstep(clamp((fadeRightX - x) / fadeWidth, 0.0f, 1.0f));
            alpha = Math.min(alpha, 1.0f - clamp(fadeRightStrength, 0.0f, 1.0f) * (1.0f - edgeAlpha));
        }

        int originalAlpha = (color >>> 24) & 0xFF;
        return (Math.round(originalAlpha * alpha) << 24) | (color & 0x00FFFFFF);
    }

    private float smoothstep(float value) {
        return value * value * (3.0f - 2.0f * value);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void vertex(VertexConsumer consumer, float x, float y, float u, float v, int color) {
        consumer.addVertexWith2DPose(pose, x, y)
                .setUv(u, v)
                .setColor(color)
                .setLineWidth(distanceRange);
    }

    @Override
    public RenderPipeline pipeline() {
        return MsdfIconRenderer.MSDF_ICON_PIPELINE;
    }

    @Override
    public TextureSetup textureSetup() {
        return textureSetup;
    }

    @Override
    public ScreenRectangle scissorArea() {
        return scissorArea;
    }

    @Override
    public ScreenRectangle bounds() {
        if (quadCount == 0) {
            return new ScreenRectangle(0, 0, 1, 1);
        }
        int x = (int) Math.floor(minX);
        int y = (int) Math.floor(minY);
        int width = Math.max(1, (int) Math.ceil(maxX - minX));
        int height = Math.max(1, (int) Math.ceil(maxY - minY));
        ScreenRectangle transformedBounds = new ScreenRectangle(x, y, width, height).transformMaxBounds(pose);
        return scissorArea == null ? transformedBounds : scissorArea.intersection(transformedBounds);
    }
}
