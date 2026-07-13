package cn.mcmod.wiki.exporter.stage;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RecipeParameterExtractorTest {
    @Test
    void onlyExportsAllowlistedScalarGetters() {
        JsonObject json = RecipeParameterExtractor.extract(new ExampleRecipe());
        assertEquals(200, json.get("energy_required").getAsInt());
        assertEquals(40, json.get("processing_time").getAsInt());
        assertFalse(json.has("secret"));
    }

    public static final class ExampleRecipe {
        public int getEnergyRequired() { return 200; }
        public int getProcessingTime() { return 40; }
        public String getSecret() { return "do-not-export"; }
    }
}
