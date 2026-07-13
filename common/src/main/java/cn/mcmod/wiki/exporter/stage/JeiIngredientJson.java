package cn.mcmod.wiki.exporter.stage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Stable JSON identity handling shared by JEI export paths and dependency-free tests. */
final class JeiIngredientJson {
    private JeiIngredientJson() {
    }

    static JsonArray stableUnique(List<JsonObject> ingredients) {
        Map<String, JsonObject> unique = new TreeMap<>();
        for (JsonObject ingredient : ingredients) {
            unique.putIfAbsent(identity(ingredient), ingredient);
        }
        JsonArray result = new JsonArray();
        unique.values().forEach(result::add);
        return result;
    }

    private static String identity(JsonObject ingredient) {
        String type = stringField(ingredient, "type");
        String value = stringField(ingredient, "unique_id");
        if (value.isEmpty()) value = stringField(ingredient, "resource_location");
        if (value.isEmpty()) value = stringField(ingredient, "item");
        if (value.isEmpty()) value = ingredient.toString();
        return type + "\u0000" + value;
    }

    private static String stringField(JsonObject object, String field) {
        return object.has(field) && !object.get(field).isJsonNull()
            ? object.get(field).getAsString()
            : "";
    }
}
