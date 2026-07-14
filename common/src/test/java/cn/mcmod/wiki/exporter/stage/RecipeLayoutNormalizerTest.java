package cn.mcmod.wiki.exporter.stage;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeLayoutNormalizerTest {
    @Test
    void recognizesVanillaShapedSerializer() {
        RecipeLayoutNormalizer.NormalizedLayout layout = RecipeLayoutNormalizer.classifyIds(
            "minecraft:crafting",
            "minecraft:crafting_shaped"
        );

        assertEquals("shaped", layout.kind());
        assertEquals(true, layout.ordered());
        assertEquals("serializer", layout.source());
    }

    @Test
    void recognizesVanillaShapelessSerializer() {
        RecipeLayoutNormalizer.NormalizedLayout layout = RecipeLayoutNormalizer.classifyIds(
            "minecraft:crafting",
            "minecraft:crafting_shapeless"
        );

        assertEquals("shapeless", layout.kind());
        assertEquals(false, layout.ordered());
    }

    @Test
    void doesNotGuessSpecialCraftingRecipeSemantics() {
        RecipeLayoutNormalizer.NormalizedLayout layout = RecipeLayoutNormalizer.classifyIds(
            "minecraft:crafting",
            "minecraft:crafting_special_repairitem"
        );

        assertEquals("unknown", layout.kind());
        assertNull(layout.ordered());
        assertEquals("recipe_type", layout.source());
    }

    @Test
    void marksMachineRecipesAsNotApplicable() {
        RecipeLayoutNormalizer.NormalizedLayout layout = RecipeLayoutNormalizer.classifyIds(
            "minecraft:smelting",
            "minecraft:smelting"
        );

        assertEquals("not_applicable", layout.kind());
        assertNull(layout.ordered());
    }

    @Test
    void writesUnknownOrderedValueAsExplicitJsonNull() {
        JsonObject json = new JsonObject();

        RecipeLayoutNormalizer.write(json, new Object());

        assertEquals("unknown", json.get("layout_kind").getAsString());
        assertTrue(json.get("ordered").isJsonNull());
        assertEquals("unavailable", json.get("layout_classification_source").getAsString());
    }
}
