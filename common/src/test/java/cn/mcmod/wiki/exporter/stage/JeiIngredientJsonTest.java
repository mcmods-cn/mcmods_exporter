package cn.mcmod.wiki.exporter.stage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JeiIngredientJsonTest {
    @Test
    void sortsAndDeduplicatesRecipeCatalystsByJeiIdentity() {
        JsonObject duplicate = ingredient("minecraft:item_stack", "example:machine", "example:machine");
        JsonObject other = ingredient("minecraft:item_stack", "example:advanced_machine", "example:advanced_machine");
        JsonObject duplicateWithDifferentName = ingredient("minecraft:item_stack", "example:machine", "example:machine");
        duplicateWithDifferentName.addProperty("translation_key", "block.example.machine");

        JsonArray result = JeiIngredientJson.stableUnique(List.of(
            duplicate,
            other,
            duplicateWithDifferentName
        ));

        assertEquals(2, result.size());
        assertEquals("example:advanced_machine", result.get(0).getAsJsonObject().get("unique_id").getAsString());
        assertEquals("example:machine", result.get(1).getAsJsonObject().get("unique_id").getAsString());
    }

    private static JsonObject ingredient(String type, String uniqueId, String item) {
        JsonObject json = new JsonObject();
        json.addProperty("type", type);
        json.addProperty("unique_id", uniqueId);
        json.addProperty("item", item);
        return json;
    }
}
