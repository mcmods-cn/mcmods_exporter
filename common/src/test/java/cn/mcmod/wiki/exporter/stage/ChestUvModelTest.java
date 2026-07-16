package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.render.ObjVertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChestUvModelTest {
    @Test
    void singleAndDoubleChestLayersExposeCompleteQuadUvs() {
        ObjVertexConsumer single = capture(ChestRenderer.createSingleBodyLayer().bakeRoot());
        ObjVertexConsumer left = capture(ChestRenderer.createDoubleBodyLeftLayer().bakeRoot());
        ObjVertexConsumer right = capture(ChestRenderer.createDoubleBodyRightLayer().bakeRoot());

        assertEquals(18, single.faceCount());
        assertEquals(18, left.faceCount());
        assertEquals(18, right.faceCount());
        assertTrue(single.meshJson("block_units", "source_texture_normalized")
            .getAsJsonArray("faces")
            .get(0)
            .getAsJsonObject()
            .has("uv_bounds"));
    }

    private static ObjVertexConsumer capture(net.minecraft.client.model.geom.ModelPart model) {
        ObjVertexConsumer consumer = new ObjVertexConsumer();
        model.render(new PoseStack(), consumer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        return consumer;
    }
}
