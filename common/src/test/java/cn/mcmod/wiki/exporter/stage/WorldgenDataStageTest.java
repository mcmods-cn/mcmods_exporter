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
    void normalizesPlacedAndConfiguredOreIntoOneCatalogEntry() {
        JsonObject placed = JsonParser.parseString("""
            {
              "feature": "example:tin_ore",
              "placement": [
                {"type": "minecraft:count", "count": 12},
                {
                  "type": "minecraft:height_range",
                  "height": {
                    "type": "minecraft:uniform",
                    "min_inclusive": {"absolute": -32},
                    "max_inclusive": {"absolute": 64}
                  }
                },
                {"type": "minecraft:biome"}
              ]
            }
            """).getAsJsonObject();
        JsonObject configured = JsonParser.parseString("""
            {
              "type": "minecraft:ore",
              "config": {
                "size": 9,
                "discard_chance_on_air_exposure": 0.25,
                "targets": [
                  {
                    "target": {
                      "predicate_type": "minecraft:tag_match",
                      "tag": "minecraft:stone_ore_replaceables"
                    },
                    "state": {"Name": "example:tin_ore"}
                  },
                  {
                    "target": {
                      "predicate_type": "minecraft:tag_match",
                      "tag": "minecraft:deepslate_ore_replaceables"
                    },
                    "state": {"Name": "example:deepslate_tin_ore"}
                  }
                ]
              }
            }
            """).getAsJsonObject();

        JsonObject summary = WorldgenDataStage.summarizePlacedFeature(placed, configured);

        assertEquals("minecraft:ore", summary.get("feature_type").getAsString());
        assertEquals("ore", summary.get("category").getAsString());
        assertEquals("example:tin_ore", summary.get("configured_feature_id").getAsString());
        assertEquals(2, summary.getAsJsonArray("outputs").size());
        assertEquals("example:deepslate_tin_ore", summary.getAsJsonArray("outputs")
            .get(0).getAsJsonObject().get("block_id").getAsString());
        assertEquals("#minecraft:stone_ore_replaceables", summary.getAsJsonArray("targets")
            .get(1).getAsString());
        assertEquals(9, summary.get("size").getAsInt());
        assertEquals(0.25, summary.get("discard_chance_on_air_exposure").getAsDouble());

        JsonObject placement = summary.getAsJsonObject("placement");
        assertEquals(12, placement.get("count").getAsInt());
        assertEquals("uniform", placement.get("distribution").getAsString());
        assertEquals(-32, placement.get("min_y").getAsInt());
        assertEquals(64, placement.get("max_y").getAsInt());
        assertEquals(true, placement.get("biome_filter").getAsBoolean());
    }

    @Test
    void normalizesLootPoolsAndNestedEntriesForWebsiteRendering() {
        JsonObject definition = JsonParser.parseString("""
            {
              "type": "minecraft:chest",
              "pools": [
                {
                  "rolls": 2,
                  "bonus_rolls": 0,
                  "conditions": [
                    {"condition": "minecraft:random_chance", "chance": 0.75}
                  ],
                  "entries": [
                    {
                      "type": "minecraft:item",
                      "name": "minecraft:diamond",
                      "weight": 2,
                      "functions": [
                        {"function": "minecraft:set_count", "count": 3}
                      ]
                    },
                    {
                      "type": "minecraft:alternatives",
                      "children": [
                        {"type": "minecraft:tag", "name": "minecraft:planks"},
                        {"type": "minecraft:loot_table", "value": "example:nested"}
                      ]
                    }
                  ]
                }
              ]
            }
            """).getAsJsonObject();
        JsonObject nested = JsonParser.parseString("""
            {
              "pools": [
                {
                  "rolls": 1,
                  "entries": [
                    {"type": "minecraft:item", "name": "minecraft:gold_ingot"}
                  ]
                }
              ]
            }
            """).getAsJsonObject();

        JsonArray pools = WorldgenDataStage.normalizeLootPools(
            definition,
            Map.of("example:nested", nested)
        );

        assertEquals(1, pools.size());
        JsonObject pool = pools.get(0).getAsJsonObject();
        assertEquals(0, pool.get("pool_index").getAsInt());
        assertEquals(2, pool.get("rolls").getAsInt());
        assertEquals("minecraft:random_chance", pool.getAsJsonArray("condition_types").get(0).getAsString());
        assertEquals(2, pool.get("entry_count").getAsInt());
        assertEquals("minecraft:diamond", pool.getAsJsonArray("entries").get(0)
            .getAsJsonObject().get("item_id").getAsString());
        JsonObject alternatives = pool.getAsJsonArray("entries").get(1).getAsJsonObject();
        assertEquals("alternatives", alternatives.get("entry_kind").getAsString());
        assertEquals(2, alternatives.get("child_count").getAsInt());
        assertEquals("example:nested", alternatives.getAsJsonArray("children").get(1)
            .getAsJsonObject().get("loot_table_id").getAsString());
        assertEquals("minecraft:diamond", pool.getAsJsonArray("possible_item_ids").get(0).getAsString());
        assertEquals("minecraft:gold_ingot", pool.getAsJsonArray("possible_item_ids").get(1).getAsString());
        assertEquals("minecraft:planks", pool.getAsJsonArray("possible_item_tags").get(0).getAsString());
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
