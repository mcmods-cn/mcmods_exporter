package cn.mcmod.wiki.exporter.stage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Selects target-owned JEI recipes and removes duplicate cross-category projections. */
final class JeiRecipeSelection {
    static final String FOREIGN_RECIPE_NAMESPACE = "foreign_recipe_namespace";
    static final String DUPLICATE_CATEGORY_PROJECTION = "duplicate_category_projection";

    private JeiRecipeSelection() {
    }

    static Result select(List<Candidate> candidates, Set<String> selectedNamespaces) {
        Map<Integer, String> exclusions = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            if (selectedNamespaces.isEmpty()) {
                continue;
            }
            String sourceNamespace = candidate.canonicalRecipeId()
                ? namespace(candidate.recipeId())
                : namespace(candidate.presentationRecipeTypeId());
            if (!selectedNamespaces.contains(sourceNamespace)) {
                exclusions.put(candidate.ordinal(), FOREIGN_RECIPE_NAMESPACE);
            }
        }

        Map<ProjectionKey, List<Candidate>> groups = new HashMap<>();
        for (Candidate candidate : candidates) {
            if (exclusions.containsKey(candidate.ordinal())
                || !candidate.canonicalRecipeId()
                || candidate.underlyingRecipeTypeId() == null) {
                continue;
            }
            groups.computeIfAbsent(
                new ProjectionKey(candidate.recipeId(), candidate.underlyingRecipeTypeId()),
                ignored -> new ArrayList<>()
            ).add(candidate);
        }
        for (List<Candidate> group : groups.values()) {
            boolean hasNativePresentation = group.stream().anyMatch(candidate ->
                Objects.equals(candidate.presentationRecipeTypeId(), candidate.underlyingRecipeTypeId())
            );
            if (!hasNativePresentation) {
                continue;
            }
            for (Candidate candidate : group) {
                if (!Objects.equals(candidate.presentationRecipeTypeId(), candidate.underlyingRecipeTypeId())) {
                    exclusions.put(candidate.ordinal(), DUPLICATE_CATEGORY_PROJECTION);
                }
            }
        }

        Set<Integer> included = new HashSet<>();
        for (Candidate candidate : candidates) {
            if (!exclusions.containsKey(candidate.ordinal())) {
                included.add(candidate.ordinal());
            }
        }
        return new Result(Set.copyOf(included), Map.copyOf(exclusions));
    }

    private static String namespace(String id) {
        int separator = id.indexOf(':');
        return separator > 0 ? id.substring(0, separator) : id;
    }

    record Candidate(
        int ordinal,
        String presentationRecipeTypeId,
        String recipeId,
        boolean canonicalRecipeId,
        String underlyingRecipeTypeId
    ) {
    }

    record Result(Set<Integer> includedOrdinals, Map<Integer, String> exclusions) {
    }

    private record ProjectionKey(String recipeId, String underlyingRecipeTypeId) {
    }
}
