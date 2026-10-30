package releon.ru.utils.render.ui.image;

import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.resources.Identifier;

record ImageTexture(
        Identifier id,
        TextureSetup setup,
        boolean nearestFilter,
        int width,
        int height
) {
    float drawWidth(float size) {
        if (width <= 0 || height <= 0 || width >= height) {
            return size;
        }
        return size * (width / (float) height);
    }

    float drawHeight(float size) {
        if (width <= 0 || height <= 0 || height >= width) {
            return size;
        }
        return size * (height / (float) width);
    }
}
