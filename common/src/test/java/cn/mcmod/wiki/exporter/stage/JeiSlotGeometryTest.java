package cn.mcmod.wiki.exporter.stage;

import com.google.gson.JsonObject;
import java.lang.reflect.Proxy;
import java.util.List;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import net.minecraft.client.renderer.Rect2i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class JeiSlotGeometryTest {
    @Test
    void craftingOutputUsesTheIngredientOriginWithCanvasPadding() {
        // The second rectangle was observed on the 1.20.1 JEI crafting output.
        assertBounds(new Rect2i(95, 19, 16, 16), new Rect2i(-5, -5, 116, 40), List.of(99, 23, 16, 16));
    }

    @Test
    void preservesTheCustomFluidRendererDimensionsWithAnOffsetTankFrame() {
        assertBounds(new Rect2i(90, 0, 16, 58), new Rect2i(-1, -1, 107, 60), List.of(94, 4, 16, 58));
    }

    private static void assertBounds(Rect2i ingredient, Rect2i decorated, List<Integer> expected) {
        IRecipeSlotDrawable drawable = (IRecipeSlotDrawable) Proxy.newProxyInstance(
            IRecipeSlotDrawable.class.getClassLoader(),
            new Class<?>[]{IRecipeSlotDrawable.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getRect" -> ingredient;
                case "getAreaIncludingBackground" -> decorated;
                default -> throw new AssertionError(method);
            }
        );
        JsonObject slot = new JsonObject();
        JeiRecipeGuiStage.writeSlotGeometry(slot, drawable);
        for (String key : List.of("rect", "visual_rect")) {
            JsonObject bounds = slot.getAsJsonObject(key);
            assertEquals(expected, List.of("x", "y", "width", "height").stream()
                .map(field -> bounds.get(field).getAsInt()).toList(), key);
        }
    }
}
