package cn.mcmod.wiki.exporter.stage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldgenDataStageTest {
    @Test
    void normalizesTopLevelStructureDefinitionForWebsiteImport() {
        JsonObject definition = JsonParser.parseString("""
            {
              "type": "minecraft:jigsaw",
              "biomes": "#minecraft:has_structure/village_desert",
              "step": "surface_structures",
              "terrain_adaptation": "beard_thin",
              "start_pool": "minecraft:village/desert/town_centers",
              "size": 6,
              "start_height": {"absolute": 0},
              "max_distance_from_center": 80
            }
            """).getAsJsonObject();

        JsonObject summary = WorldgenDataStage.summarizeStructureDefinition(definition);

        assertEquals("minecraft:jigsaw", summary.get("structure_type").getAsString());
        assertEquals("#minecraft:has_structure/village_desert", summary.get("biomes").getAsString());
        assertEquals("surface_structures", summary.get("generation_step").getAsString());
        assertEquals("beard_thin", summary.get("terrain_adaptation").getAsString());
        assertEquals("minecraft:village/desert/town_centers", summary.get("start_pool").getAsString());
        assertEquals(6, summary.get("jigsaw_size").getAsInt());
        assertEquals(80, summary.get("max_distance_from_center").getAsInt());
    }

    @Test
    void nestsStructureSetPlacementUnderReferencedTopLevelStructures() {
        JsonObject definition = JsonParser.parseString("""
            {
              "structures": [
                {"structure": "minecraft:village_desert", "weight": 1},
                {"structure": "minecraft:village_plains", "weight": 2}
              ],
              "placement": {
                "type": "minecraft:random_spread",
                "spacing": 34,
                "separation": 8
              }
            }
            """).getAsJsonObject();
        Map<String, JsonArray> references = new TreeMap<>();

        WorldgenDataStage.addStructureSetReferences(
            references,
            ResourceLocation.fromNamespaceAndPath("minecraft", "villages"),
            definition
        );

        JsonObject desert = references.get("minecraft:village_desert").get(0).getAsJsonObject();
        assertEquals("minecraft:villages", desert.get("structure_set_id").getAsString());
        assertEquals(1, desert.get("weight").getAsInt());
        assertEquals("minecraft:random_spread", desert.get("placement_type").getAsString());
        assertEquals(34, desert.getAsJsonObject("placement").get("spacing").getAsInt());
    }

    @Test
    void classifiesBlockChestAndEntityLootTables() {
        assertEquals("block", WorldgenDataStage.lootTableCategory(ResourceLocation.fromNamespaceAndPath("example", "blocks/machine")));
        assertEquals("chest", WorldgenDataStage.lootTableCategory(ResourceLocation.fromNamespaceAndPath("example", "chests/lab")));
        assertEquals("entity", WorldgenDataStage.lootTableCategory(ResourceLocation.fromNamespaceAndPath("example", "entities/robot")));
        assertEquals("fishing", WorldgenDataStage.lootTableCategory(ResourceLocation.fromNamespaceAndPath("minecraft", "gameplay/fishing")));
        assertEquals("fishing", WorldgenDataStage.lootTableCategory(ResourceLocation.fromNamespaceAndPath("minecraft", "gameplay/fishing/treasure")));
        assertEquals("gameplay", WorldgenDataStage.lootTableCategory(ResourceLocation.fromNamespaceAndPath("example", "gameplay/reward")));
    }
}
