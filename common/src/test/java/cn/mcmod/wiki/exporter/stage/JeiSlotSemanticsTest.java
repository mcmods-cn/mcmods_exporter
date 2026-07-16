package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.util.ProbabilityText;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JeiSlotSemanticsTest {
    @Test
    void tooltipChanceMarksOnlyNonPrimaryOutputAsByproduct() {
        JsonArray slots = new JsonArray();
        slots.add(output(0, 10, 10, null));
        slots.add(output(1, 30, 10, "Chance: 25%"));

        JeiSlotSemantics.enrich(slots, List.of());

        JsonObject primary = slots.get(0).getAsJsonObject();
        JsonObject byproduct = slots.get(1).getAsJsonObject();
        assertEquals("output", primary.get("role").getAsString());
        assertFalse(primary.get("chance_available").getAsBoolean());
        assertEquals("byproduct", byproduct.get("role").getAsString());
        assertEquals(0.25D, byproduct.get("chance").getAsDouble());
        assertEquals("jei_slot_tooltip", byproduct.get("chance_source").getAsString());
    }

    @Test
    void twoGuaranteedOutputsRemainNormalOutputsWithoutEvidence() {
        JsonArray slots = new JsonArray();
        slots.add(output(0, 10, 10, null));
        slots.add(output(1, 30, 10, null));

        JeiSlotSemantics.enrich(slots, List.of());

        assertEquals("output", slots.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("output", slots.get(1).getAsJsonObject().get("role").getAsString());
    }

    @Test
    void drawnProbabilityIsAssignedToSecondaryOutput() {
        JsonArray slots = new JsonArray();
        slots.add(output(0, 80, 20, null));
        slots.add(output(1, 105, 20, null));
        ProbabilityText.Match match = ProbabilityText.parse("10%").orElseThrow();

        JeiSlotSemantics.enrich(slots, List.of(new ProbabilityText.Draw(match, 92, 38)));

        assertEquals("byproduct", slots.get(1).getAsJsonObject().get("role").getAsString());
        assertEquals(0.1D, slots.get(1).getAsJsonObject().get("chance").getAsDouble());
        assertFalse(slots.get(0).getAsJsonObject().get("chance_available").getAsBoolean());
    }

    @Test
    void attributePercentIsNotMistakenForChance() {
        JsonArray slots = new JsonArray();
        slots.add(output(0, 10, 10, "+10% Attack Speed"));

        JeiSlotSemantics.enrich(slots, List.of());

        assertFalse(slots.get(0).getAsJsonObject().get("chance_available").getAsBoolean());
    }

    private static JsonObject output(int index, int x, int y, String tooltipText) {
        JsonObject slot = new JsonObject();
        slot.addProperty("index", index);
        slot.addProperty("role", "output");
        slot.add("rect", rect(x, y));
        slot.add("visual_rect", rect(x, y));
        slot.add("alternatives", new JsonArray());
        if (tooltipText != null) {
            JsonArray tooltip = new JsonArray();
            JsonObject line = new JsonObject();
            line.addProperty("text", tooltipText);
            tooltip.add(line);
            slot.add("_jei_tooltip", tooltip);
        }
        return slot;
    }

    private static JsonObject rect(int x, int y) {
        JsonObject rect = new JsonObject();
        rect.addProperty("x", x);
        rect.addProperty("y", y);
        rect.addProperty("width", 18);
        rect.addProperty("height", 18);
        return rect;
    }
}
