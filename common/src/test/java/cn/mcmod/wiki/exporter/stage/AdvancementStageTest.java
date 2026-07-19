package cn.mcmod.wiki.exporter.stage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AdvancementStageTest {
    @Test
    void filtersRecipeBookUnlockAdvancementsFromEveryNamespace() {
        assertEquals(
            "recipe_unlock",
            AdvancementStage.advancementExclusionReason(
                "minecraft:recipes/building_blocks/oak_planks",
                false
            )
        );
        assertEquals(
            "recipe_unlock",
            AdvancementStage.advancementExclusionReason(
                "examplemod:recipes/machines/crusher",
                true
            )
        );
    }

    @Test
    void filtersInternalAdvancementsWithoutDisplayButKeepsHiddenDisplayedOnes() {
        assertEquals(
            "no_display",
            AdvancementStage.advancementExclusionReason("examplemod:internal/unlock_machine", false)
        );
        assertNull(
            AdvancementStage.advancementExclusionReason("examplemod:progression/secret_machine", true)
        );
    }
}
