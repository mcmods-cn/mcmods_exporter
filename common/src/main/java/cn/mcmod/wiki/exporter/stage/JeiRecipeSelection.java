package cn.mcmod.wiki.exporter.stage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Selects target-owned JEI recipes and keeps one preferred presentation per canonical recipe ID. */
final class JeiRecipeSelection {
    static final String FOREIGN_RECIPE_NAMESPACE = "foreign_recipe_namespace";
    static final String DUPLICATE_RECIPE_ID = "duplicate_recipe_id";

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

        Map<String, List<Candidate>> groups = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            if (exclusions.containsKey(candidate.ordinal())
                || !candidate.canonicalRecipeId()) {
                continue;
            }
            groups.computeIfAbsent(candidate.recipeId(), ignored -> new ArrayList<>()).add(candidate);
        }
        for (List<Candidate> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            Candidate preferred = group.stream()
                .min(Comparator
                    .comparingInt(JeiRecipeSelection::presentationPriority)
                    .thenComparingInt(Candidate::ordinal))
                .orElseThrow();
            for (Candidate candidate : group) {
                if (candidate.ordinal() != preferred.ordinal()) {
                    exclusions.put(candidate.ordinal(), DUPLICATE_RECIPE_ID);
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

    private static int presentationPriority(Candidate candidate) {
        String presentationNamespace = namespace(candidate.presentationRecipeTypeId());
        boolean vanillaPresentation = "minecraft".equals(presentationNamespace);
        boolean ownerPresentation = namespace(candidate.recipeId()).equals(presentationNamespace);
        boolean nativePresentation = Objects.equals(
            candidate.presentationRecipeTypeId(),
            candidate.underlyingRecipeTypeId()
        );
        if (vanillaPresentation) {
            return 0;
        }
        if (ownerPresentation && nativePresentation) {
            return 1;
        }
        if (nativePresentation) {
            return 2;
        }
        if (ownerPresentation) {
            return 3;
        }
        return 4;
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
}
