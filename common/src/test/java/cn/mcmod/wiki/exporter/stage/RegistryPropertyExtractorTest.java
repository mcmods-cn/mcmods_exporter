package cn.mcmod.wiki.exporter.stage;

import com.google.gson.JsonObject;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RegistryPropertyExtractorTest {
    @Test
    void normalizesVanillaMiningTags() {
        JsonObject mining = RegistryPropertyExtractor.normalizeBlockMining(List.of(
            "minecraft:needs_iron_tool",
            "minecraft:mineable/pickaxe"
        ), true);

        assertEquals(true, mining.get("requires_correct_tool").getAsBoolean());
        assertEquals("pickaxe", mining.getAsJsonArray("preferred_tools").get(0).getAsString());
        assertEquals("iron", mining.get("required_tier").getAsString());
        assertEquals(2, mining.get("required_mining_level").getAsInt());
    }

    @Test
    void preservesUnknownModdedTierWithoutInventingNumericLevel() {
        JsonObject mining = RegistryPropertyExtractor.normalizeBlockMining(List.of(
            "example:mineable/hammers",
            "example:needs_osmium_tool"
        ), true);

        assertEquals("hammer", mining.getAsJsonArray("preferred_tools").get(0).getAsString());
        assertEquals("osmium", mining.get("required_tier").getAsString());
        assertFalse(mining.has("required_mining_level"));
    }

    @Test
    void selectsHighestKnownRequiredTier() {
        JsonObject mining = RegistryPropertyExtractor.normalizeBlockMining(List.of(
            "minecraft:needs_stone_tool",
            "minecraft:needs_diamond_tool"
        ), true);

        assertEquals("diamond", mining.get("required_tier").getAsString());
        assertEquals(3, mining.get("required_mining_level").getAsInt());
    }

    @Test
    void appliesAttributeOperationsInMinecraftOrder() {
        List<AttributeModifier> modifiers = List.of(
            new AttributeModifier("addition", 2.0D, AttributeModifier.Operation.ADDITION),
            new AttributeModifier("base", 0.5D, AttributeModifier.Operation.MULTIPLY_BASE),
            new AttributeModifier("total", 0.25D, AttributeModifier.Operation.MULTIPLY_TOTAL)
        );

        var attribute = new RangedAttribute("fixture.combat", 5.0D, 0.0D, 200.0D);
        var game = new AttributeInstance(attribute, ignored -> {});
        modifiers.forEach(game::addTransientModifier);

        assertEquals(13.125D, game.getValue());
        assertEquals(game.getValue(), RegistryPropertyExtractor.equippedValue(attribute, 5.0D, modifiers));
    }

    @Test
    void equippedDisplayUsesTheGameAttributeRangeAndSpecifiedPlayerBase() {
        var attribute = new RangedAttribute("fixture.combat", 2.0D, 0.0D, 16.0D);
        var addition = new AttributeModifier("addition", 14.0D, AttributeModifier.Operation.ADDITION);
        assertEquals(16.0D, RegistryPropertyExtractor.equippedValue(attribute, 4.0D, List.of(addition)));
        assertEquals(4.0D, RegistryPropertyExtractor.equippedValue(attribute, 4.0D, List.of()));
    }
}
