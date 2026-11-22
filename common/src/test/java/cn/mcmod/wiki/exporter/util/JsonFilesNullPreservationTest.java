package cn.mcmod.wiki.exporter.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JsonFilesNullPreservationTest {
    @Test
    void failedReplacementLeavesExistingContentsAndNoTemporaryJson(@TempDir Path directory) throws IOException {
        Path occupied = Files.createDirectory(directory.resolve("index.json"));
        Path original = Files.writeString(occupied.resolve("contents"), "previous data");

        assertThrows(IOException.class, () -> JsonFiles.write(occupied, new JsonObject()));

        assertEquals("previous data", Files.readString(original));
        try (var entries = Files.list(directory)) {
            assertEquals(1, entries.count());
        }
    }

    @Test
    void preservesExplicitNestedNullWithoutInventingAbsentFieldsOrChangingBusinessValues(@TempDir Path directory) throws Exception {
        JsonObject recipe = new JsonObject();
        recipe.add("ordered", JsonNull.INSTANCE);
        recipe.addProperty("ingredient_present", false);
        JsonObject ingredient = new JsonObject();
        ingredient.addProperty("item", "minecraft:stone");
        ingredient.addProperty("count", 3);
        ingredient.addProperty("chance", 0.25D);
        ingredient.addProperty("nbt_snbt", "{AuditNBT:1}");
        // Synthetic nullable control, not a business-extension storage policy.
        ingredient.add("nested_nullable_control", JsonNull.INSTANCE);
        JsonArray alternatives = new JsonArray();
        alternatives.add(ingredient);
        alternatives.add(JsonNull.INSTANCE);
        recipe.add("alternatives", alternatives);
        JsonObject before = recipe.deepCopy();

        Path file = directory.resolve("nested/recipe.json");
        JsonFiles.write(file, recipe);
        JsonObject persisted = JsonParser.parseString(Files.readString(file)).getAsJsonObject();

        assertEquals(before, recipe, "writing must not mutate the supplied tree");
        assertEquals(before, persisted, "the actual file must preserve all supplied JsonElement values");
        assertTrue(persisted.has("ordered") && persisted.get("ordered").isJsonNull());
        assertFalse(persisted.has("not_supplied"), "absence is not an explicit null");
        assertFalse(persisted.get("ingredient_present").getAsBoolean());
        JsonObject actualIngredient = persisted.getAsJsonArray("alternatives").get(0).getAsJsonObject();
        assertTrue(actualIngredient.has("nested_nullable_control") && actualIngredient.get("nested_nullable_control").isJsonNull());
        assertFalse(actualIngredient.has("not_supplied"));
        assertEquals(3, actualIngredient.get("count").getAsInt());
        assertEquals(0.25D, actualIngredient.get("chance").getAsDouble());
        assertEquals("{AuditNBT:1}", actualIngredient.get("nbt_snbt").getAsString());
        assertTrue(persisted.getAsJsonArray("alternatives").get(1).isJsonNull());
    }
}
