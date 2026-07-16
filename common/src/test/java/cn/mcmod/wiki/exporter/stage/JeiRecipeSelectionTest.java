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
    void vanillaPresentationWinsWhenDuplicateIdsReportDifferentUnderlyingTypes() {
        List<JeiRecipeSelection.Candidate> candidates = List.of(
            candidate(0, "minecraft:crafting", "create:shaft", "minecraft:crafting"),
            candidate(1, "create:automatic_shaped", "create:shaft", "create:automatic_shaped"),
            candidate(2, "create:mechanical_crafting", "create:crushing_wheel", "create:mechanical_crafting")
        );

        JeiRecipeSelection.Result result = JeiRecipeSelection.select(candidates, Set.of("create"));

        assertTrue(result.includedOrdinals().contains(0));
        assertEquals(JeiRecipeSelection.DUPLICATE_RECIPE_ID, result.exclusions().get(1));
        assertTrue(result.includedOrdinals().contains(2));
    }

    @Test
    void ownerNativePresentationWinsWhenThereIsNoVanillaPresentation() {
        List<JeiRecipeSelection.Candidate> candidates = List.of(
            candidate(0, "compat:machine", "example:recipe", "compat:machine"),
            candidate(1, "example:machine", "example:recipe", "example:machine")
        );

        JeiRecipeSelection.Result result = JeiRecipeSelection.select(candidates, Set.of("example"));

        assertEquals(JeiRecipeSelection.DUPLICATE_RECIPE_ID, result.exclusions().get(0));
        assertTrue(result.includedOrdinals().contains(1));
    }

    @Test
    void firstPresentationWinsDeterministicallyWhenNoOwnershipSignalExists() {
        List<JeiRecipeSelection.Candidate> candidates = List.of(
            candidate(7, "compat:first", "example:recipe", null),
            candidate(3, "compat:second", "example:recipe", null)
        );

        JeiRecipeSelection.Result result = JeiRecipeSelection.select(candidates, Set.of("example"));

        assertTrue(result.includedOrdinals().contains(3));
        assertEquals(JeiRecipeSelection.DUPLICATE_RECIPE_ID, result.exclusions().get(7));
    }

    @Test
    void generatedIdsAreNotDeduplicatedAsCanonicalRecipes() {
        List<JeiRecipeSelection.Candidate> candidates = List.of(
            new JeiRecipeSelection.Candidate(0, "example:first", "example:generated", false, null),
            new JeiRecipeSelection.Candidate(1, "example:second", "example:generated", false, null)
        );

        JeiRecipeSelection.Result result = JeiRecipeSelection.select(candidates, Set.of("example"));

        assertTrue(result.includedOrdinals().containsAll(Set.of(0, 1)));
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
