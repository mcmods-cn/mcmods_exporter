package cn.mcmod.wiki.exporter.render;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ObjVertexConsumerTest {
    @Test
    void exportsExplicitQuadUvAndDirection() {
        ObjVertexConsumer consumer = new ObjVertexConsumer();
        vertex(consumer, 0, 1, 0, 0, 0);
        vertex(consumer, 1, 1, 0, 1, 0);
        vertex(consumer, 1, 1, 1, 1, 1);
        vertex(consumer, 0, 1, 1, 0, 1);

        JsonObject mesh = consumer.meshJson("block_units", "source_texture_normalized");
        JsonObject face = mesh.getAsJsonArray("faces").get(0).getAsJsonObject();

        assertEquals(1, mesh.get("quad_count").getAsInt());
        assertEquals("up", face.get("direction").getAsString());
        assertEquals(4, face.getAsJsonArray("vertices").size());
        assertEquals(0.0D, face.getAsJsonObject("uv_bounds").get("u_min").getAsDouble());
        assertEquals(1.0D, face.getAsJsonObject("uv_bounds").get("u_max").getAsDouble());
    }

    private static void vertex(
        ObjVertexConsumer consumer,
        double x,
        double y,
        double z,
        float u,
        float v
    ) {
        consumer.vertex(x, y, z)
            .uv(u, v)
            .normal(0.0F, 1.0F, 0.0F)
            .endVertex();
    }
}
