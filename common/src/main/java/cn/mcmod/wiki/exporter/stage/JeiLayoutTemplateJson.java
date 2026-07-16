package cn.mcmod.wiki.exporter.stage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Splits JEI slot geometry from recipe-specific ingredient bindings. */
final class JeiLayoutTemplateJson {
    private JeiLayoutTemplateJson() {
    }

    static Split split(JsonArray serializedSlots) {
        List<JsonObject> ordered = new ArrayList<>();
        for (JsonElement element : serializedSlots) {
            ordered.add(element.getAsJsonObject());
        }
        ordered.sort(Comparator.comparing(JeiLayoutTemplateJson::sortKey));

        Map<String, Integer> roleIndexes = new HashMap<>();
        JsonArray templateSlots = new JsonArray();
        JsonArray bindings = new JsonArray();
        for (JsonObject source : ordered) {
            String role = source.get("role").getAsString();
            int roleIndex = roleIndexes.merge(role, 1, Integer::sum) - 1;
            String slotId = role + "_" + roleIndex;

            JsonObject templateSlot = new JsonObject();
            templateSlot.addProperty("slot_id", slotId);
            templateSlot.addProperty("role", role);
            copy(source, templateSlot, "name");
            copy(source, templateSlot, "output_index");
            boolean coordinatesAvailable = source.has("rect");
            templateSlot.addProperty("coordinates_available", coordinatesAvailable);
            copy(source, templateSlot, "rect");
            copy(source, templateSlot, "visual_rect");
            templateSlots.add(templateSlot);

            JsonArray alternatives = source.getAsJsonArray("alternatives");
            boolean ingredientPresent = alternatives != null && !alternatives.isEmpty();
            JsonObject binding = new JsonObject();
            binding.addProperty("slot_id", slotId);
            binding.addProperty("ingredient_present", ingredientPresent);
            binding.addProperty("clickable", ingredientPresent && coordinatesAvailable);
            binding.add("alternatives", alternatives == null ? new JsonArray() : alternatives.deepCopy());
            if (!ingredientPresent) {
                binding.addProperty("placeholder_item", "minecraft:air");
            }
            copy(source, binding, "item_tag_equivalent");
            copy(source, binding, "role_source");
            copy(source, binding, "chance_available");
            copy(source, binding, "chance");
            copy(source, binding, "chance_percent");
            copy(source, binding, "chance_comparator");
            copy(source, binding, "chance_source");
            copy(source, binding, "chance_text");
            copy(source, binding, "chance_texts");
            copy(source, binding, "chance_translation_key");
            copy(source, binding, "chance_render_x");
            copy(source, binding, "chance_render_y");
            bindings.add(binding);
        }

        return new Split(templateId(templateSlots), templateSlots, bindings);
    }

    private static String sortKey(JsonObject slot) {
        String role = string(slot, "role");
        JsonObject rect = object(slot, "rect");
        JsonObject visualRect = object(slot, "visual_rect");
        return role + '|'
            + padded(integer(rect, "y")) + '|'
            + padded(integer(rect, "x")) + '|'
            + padded(integer(rect, "width")) + '|'
            + padded(integer(rect, "height")) + '|'
            + padded(integer(visualRect, "y")) + '|'
            + padded(integer(visualRect, "x")) + '|'
            + string(slot, "name") + '|'
            + padded(integer(slot, "index"));
    }

    private static String templateId(JsonArray templateSlots) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(templateSlots.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder("template_");
            for (int index = 0; index < 8; index++) {
                value.append(String.format("%02x", hash[index]));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void copy(JsonObject source, JsonObject target, String key) {
        if (source.has(key)) {
            target.add(key, source.get(key).deepCopy());
        }
    }

    private static JsonObject object(JsonObject source, String key) {
        return source != null && source.has(key) && source.get(key).isJsonObject()
            ? source.getAsJsonObject(key)
            : null;
    }

    private static String string(JsonObject source, String key) {
        return source != null && source.has(key) ? source.get(key).getAsString() : "";
    }

    private static int integer(JsonObject source, String key) {
        return source != null && source.has(key) ? source.get(key).getAsInt() : Integer.MAX_VALUE;
    }

    private static String padded(int value) {
        return String.format("%011d", value);
    }

    record Split(String templateId, JsonArray templateSlots, JsonArray bindings) {
    }
}
