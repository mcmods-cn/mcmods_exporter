package cn.mcmod.wiki.exporter.render;

import com.mojang.blaze3d.platform.NativeImage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OffscreenRendererTest {
    @Test
    void detectsFullyTransparentAndVisibleCaptures() {
        try (NativeImage image = new NativeImage(4, 4, true)) {
            image.fillRect(0, 0, 4, 4, 0x00000000);
            assertFalse(OffscreenRenderer.hasVisiblePixels(image));

            image.setPixelRGBA(2, 1, 0xFF336699);
            assertTrue(OffscreenRenderer.hasVisiblePixels(image));
        }
    }
}
