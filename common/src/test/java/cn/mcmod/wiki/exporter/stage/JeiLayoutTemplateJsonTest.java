package cn.mcmod.wiki.exporter.stage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JeiLayoutTemplateJsonTest {
    @Test
    void ingredientsDoNotChangeTemplateIdentity() {
        JsonArray first = new JsonArray();
        first.add(slot(0, "input", 10, 20, "minecraft:stone"));
        JsonArray second = new JsonArray();
        second.add(slot(0, "input", 10, 20, "minecraft:dirt"));

        JeiLayoutTemplateJson.Split firstSplit = JeiLayoutTemplateJson.split(first);
        JeiLayoutTemplateJson.Split secondSplit = JeiLayoutTemplateJson.split(second);

        assertEquals(firstSplit.templateId(), secondSplit.templateId());
        assertNotEquals(firstSplit.bindings(), secondSplit.bindings());
        JsonObject templateSlot = firstSplit.templateSlots().get(0).getAsJsonObject();
        assertFalse(templateSlot.has("alternatives"));
        assertEquals("input_0", templateSlot.get("slot_id").getAsString());
    }

    @Test
    void geometryChangesCreateTemplateVariant() {
        JsonArray first = new JsonArray();
        first.add(slot(0, "input", 10, 20, "minecraft:stone"));
        JsonArray second = new JsonArray();
        second.add(slot(0, "input", 11, 20, "minecraft:stone"));

        assertNotEquals(
            JeiLayoutTemplateJson.split(first).templateId(),
            JeiLayoutTemplateJson.split(second).templateId()
        );
    }

    @Test
    void slotIdsFollowRoleAndScreenPositionInsteadOfJeiIterationOrder() {
        JsonArray slots = new JsonArray();
        slots.add(slot(0, "input", 50, 20, "minecraft:dirt"));
        slots.add(slot(1, "input", 10, 20, "minecraft:stone"));
        slots.add(slot(2, "output", 90, 20, "minecraft:diamond"));

        JeiLayoutTemplateJson.Split split = JeiLayoutTemplateJson.split(slots);

        assertEquals("input_0", split.templateSlots().get(0).getAsJsonObject().get("slot_id").getAsString());
        assertEquals(10, split.templateSlots().get(0).getAsJsonObject().getAsJsonObject("rect").get("x").getAsInt());
        assertEquals("input_1", split.templateSlots().get(1).getAsJsonObject().get("slot_id").getAsString());
        assertEquals("output_0", split.templateSlots().get(2).getAsJsonObject().get("slot_id").getAsString());
        assertTrue(split.bindings().get(0).getAsJsonObject().get("clickable").getAsBoolean());
    }

    @Test
    void fixedCraftingCapacityReusesTemplateRegardlessOfUsedSlots() {
        JeiLayoutTemplateJson.Split sticks = JeiLayoutTemplateJson.split(craftingGrid(2));
        JeiLayoutTemplateJson.Split doors = JeiLayoutTemplateJson.split(craftingGrid(6));

        assertEquals(sticks.templateId(), doors.templateId());
        assertEquals(10, sticks.templateSlots().size());
        JsonObject emptyBinding = sticks.bindings().get(2).getAsJsonObject();
        assertFalse(emptyBinding.get("ingredient_present").getAsBoolean());
        assertFalse(emptyBinding.get("clickable").getAsBoolean());
        assertEquals("minecraft:air", emptyBinding.get("placeholder_item").getAsString());
        assertTrue(emptyBinding.getAsJsonArray("alternatives").isEmpty());
    }

    @Test
    void byproductEvidenceDoesNotCreateAnotherGeometryTemplate() {
        JsonObject regularOutput = slot(0, "output", 90, 20, "example:result");
        regularOutput.addProperty("jei_role", "output");
        JsonObject byproductOutput = slot(0, "byproduct", 90, 20, "example:result");
        byproductOutput.addProperty("jei_role", "output");

        JeiLayoutTemplateJson.Split regular = JeiLayoutTemplateJson.split(array(regularOutput));
        JeiLayoutTemplateJson.Split byproduct = JeiLayoutTemplateJson.split(array(byproductOutput));

        assertEquals(regular.templateId(), byproduct.templateId());
        assertEquals("output_0", byproduct.templateSlots().get(0).getAsJsonObject().get("slot_id").getAsString());
        assertEquals("byproduct", byproduct.templateSlots().get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("byproduct", byproduct.bindings().get(0).getAsJsonObject().get("semantic_role").getAsString());
    }

    private static JsonArray craftingGrid(int usedInputs) {
        JsonArray slots = new JsonArray();
        for (int index = 0; index < 9; index++) {
            int x = 10 + (index % 3) * 18;
            int y = 10 + (index / 3) * 18;
            slots.add(slot(index, "input", x, y, index < usedInputs ? "example:ingredient_" + index : null));
        }
        slots.add(slot(9, "output", 90, 28, "example:result"));
        return slots;
    }

    private static JsonObject slot(int index, String role, int x, int y, String itemId) {
        JsonObject slot = new JsonObject();
        slot.addProperty("index", index);
        slot.addProperty("role", role);
        slot.add("rect", rect(x, y, 18, 18));
        slot.add("visual_rect", rect(x, y, 18, 18));
        JsonArray alternatives = new JsonArray();
        if (itemId != null) {
            JsonObject ingredient = new JsonObject();
            ingredient.addProperty("type", "minecraft:item_stack");
            ingredient.addProperty("item", itemId);
            alternatives.add(ingredient);
        }
        slot.add("alternatives", alternatives);
        return slot;
    }

    private static JsonObject rect(int x, int y, int width, int height) {
        JsonObject rect = new JsonObject();
        rect.addProperty("x", x);
        rect.addProperty("y", y);
        rect.addProperty("width", width);
        rect.addProperty("height", height);
        return rect;
    }

    private static JsonArray array(JsonObject value) {
        JsonArray array = new JsonArray();
        array.add(value);
        return array;
    }
}
