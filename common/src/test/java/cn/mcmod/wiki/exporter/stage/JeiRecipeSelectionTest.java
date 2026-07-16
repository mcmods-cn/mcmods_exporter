package cn.mcmod.wiki.exporter.stage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JeiRecipeSelectionTest {
    @Test
    void targetNamespaceFilteringRemovesForeignRecipesFromOwnedCategories() {
        List<JeiRecipeSelection.Candidate> candidates = List.of(
            candidate(0, "create:automatic_shaped", "minecraft:stick", "minecraft:crafting"),
            candidate(1, "create:mechanical_crafting", "create:crushing_wheel", "create:mechanical_crafting")
        );

        JeiRecipeSelection.Result result = JeiRecipeSelection.select(candidates, Set.of("create"));

        assertEquals(JeiRecipeSelection.FOREIGN_RECIPE_NAMESPACE, result.exclusions().get(0));
        assertTrue(result.includedOrdinals().contains(1));
    }

    @Test
    void nativePresentationWinsOverDuplicateMachineProjection() {
        List<JeiRecipeSelection.Candidate> candidates = List.of(
            candidate(0, "minecraft:crafting", "create:shaft", "minecraft:crafting"),
            candidate(1, "create:automatic_shaped", "create:shaft", "minecraft:crafting"),
            candidate(2, "create:mechanical_crafting", "create:crushing_wheel", "create:mechanical_crafting")
        );

        JeiRecipeSelection.Result result = JeiRecipeSelection.select(candidates, Set.of("create"));

        assertTrue(result.includedOrdinals().contains(0));
        assertEquals(JeiRecipeSelection.DUPLICATE_CATEGORY_PROJECTION, result.exclusions().get(1));
        assertTrue(result.includedOrdinals().contains(2));
    }

    @Test
    void aliasCategoryIsKeptWhenNoNativePresentationExists() {
        List<JeiRecipeSelection.Candidate> candidates = List.of(
            candidate(0, "example:display", "example:recipe", "example:internal_type")
        );

        JeiRecipeSelection.Result result = JeiRecipeSelection.select(candidates, Set.of("example"));

        assertTrue(result.includedOrdinals().contains(0));
        assertTrue(result.exclusions().isEmpty());
    }

    private static JeiRecipeSelection.Candidate candidate(
        int ordinal,
        String presentationType,
        String recipeId,
        String underlyingType
    ) {
        return new JeiRecipeSelection.Candidate(ordinal, presentationType, recipeId, true, underlyingType);
    }
}
