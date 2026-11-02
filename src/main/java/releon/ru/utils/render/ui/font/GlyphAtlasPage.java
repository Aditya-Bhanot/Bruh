package releon.ru.utils.render.ui.font;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.texture.DynamicTexture;

import java.awt.image.BufferedImage;

final class GlyphAtlasPage implements AutoCloseable {
    private static int nextId;

    private final int size;
    private final NativeImage image;
    private final DynamicTexture texture;
    private final TextureSetup textureSetup;
    private int cursorX = 1;
    private int cursorY = 1;
    private int rowHeight;
    private boolean dirty;

    GlyphAtlasPage(int size) {
        this.size = size;
        this.image = new NativeImage(size, size, true);
        this.texture = new DynamicTexture(() -> "RELEON_font_atlas_" + nextId++, image);
        this.textureSetup = TextureSetup.singleTexture(
                texture.getTextureView(),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
        );
    }

    TextureSetup textureSetup() {
        return textureSetup;
    }

    Allocation allocate(int width, int height) {
        int paddedWidth = width + FontQuality.ATLAS_GAP;
        int paddedHeight = height + FontQuality.ATLAS_GAP;
        if (paddedWidth > size || paddedHeight > size) {
            return null;
        }

        if (cursorX + paddedWidth > size) {
            cursorX = 1;
            cursorY += rowHeight;
            rowHeight = 0;
        }

        if (cursorY + paddedHeight > size) {
            return null;
        }

        int x = cursorX;
        int y = cursorY;
        cursorX += paddedWidth;
        rowHeight = Math.max(rowHeight, paddedHeight);

        return new Allocation(
                this,
                x,
                y,
                x / (float) size,
                y / (float) size,
                (x + width) / (float) size,
                (y + height) / (float) size
        );
    }

    void copy(BufferedImage source, int atlasX, int atlasY) {
        int width = source.getWidth();
        int height = source.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setPixelABGR(atlasX + x, atlasY + y, argbToAbgr(source.getRGB(x, y)));
            }
        }
        dirty = true;
    }

    void uploadIfDirty() {
        if (!dirty) {
            return;
        }
        texture.upload();
        dirty = false;
    }

    private static int argbToAbgr(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        return (alpha << 24) | (blue << 16) | (green << 8) | red;
    }

    @Override
    public void close() {
        texture.close();
    }

    record Allocation(GlyphAtlasPage page, int x, int y, float u0, float v0, float u1, float v1) {
    }
}
