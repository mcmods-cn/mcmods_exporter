package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.util.ProbabilityText;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Enriches JEI's generic OUTPUT slots with conservative byproduct and probability semantics. */
final class JeiSlotSemantics {
    private static final List<String> CHANCE_HINTS = List.of(
        "chance", "probability", "probabil", "概率", "几率", "機率", "確率", "шанс"
    );
    private static final List<String> BYPRODUCT_HINTS = List.of(
        "secondary", "byproduct", "by-product", "bonus", "extra_output", "extra output",
        "副产品", "副產物", "副产物", "副产出", "副輸出", "副输出", "次要产物", "次要產物"
    );
    private static final List<String> PRIMARY_HINTS = List.of(
        "primary", "main_output", "main output", "result", "主产品", "主產物", "主要产物", "主要產物"
    );

    private JeiSlotSemantics() {
    }

    static void enrich(JsonArray slots, List<ProbabilityText.Draw> drawnProbabilities) {
        List<JsonObject> outputs = new ArrayList<>();
        for (JsonElement element : slots) {
            JsonObject slot = element.getAsJsonObject();
            if ("output".equals(string(slot, "role"))) {
                slot.addProperty("output_index", outputs.size());
                applyTooltipProbability(slot);
                outputs.add(slot);
            }
        }
        if (outputs.isEmpty()) {
            return;
        }

        int primaryIndex = primaryOutputIndex(outputs);
        applyDrawnProbabilities(outputs, drawnProbabilities);
        for (int index = 0; index < outputs.size(); index++) {
            JsonObject output = outputs.get(index);
            if (!output.has("chance_available")) {
                output.addProperty("chance_available", false);
            }
            String evidence = byproductEvidence(output, index, primaryIndex);
            if (evidence != null) {
                output.addProperty("role", "byproduct");
                output.addProperty("role_source", evidence);
            } else {
                output.addProperty("role_source", "jei");
            }
        }
    }

    private static void applyTooltipProbability(JsonObject slot) {
        JsonArray tooltip = slot.has("_jei_tooltip") && slot.get("_jei_tooltip").isJsonArray()
            ? slot.getAsJsonArray("_jei_tooltip")
            : new JsonArray();
        List<TooltipChance> hinted = new ArrayList<>();
        List<TooltipChance> bare = new ArrayList<>();
        for (JsonElement element : tooltip) {
            JsonObject line = element.getAsJsonObject();
            String text = string(line, "text");
            Optional<ProbabilityText.Match> parsed = ProbabilityText.parse(text);
            if (parsed.isEmpty()) {
                continue;
            }
            String translationKey = string(line, "translation_key");
            TooltipChance candidate = new TooltipChance(parsed.get(), line);
            if (containsHint(text, CHANCE_HINTS) || containsHint(translationKey, CHANCE_HINTS)) {
                hinted.add(candidate);
            } else if (isBareProbability(parsed.get())) {
                bare.add(candidate);
            }
        }
        TooltipChance selected = uniqueChance(hinted).orElseGet(() -> uniqueChance(bare).orElse(null));
        if (selected == null) {
            return;
        }
        applyChance(slot, selected.match(), "jei_slot_tooltip");
        if (selected.line().has("texts")) {
            slot.add("chance_texts", selected.line().get("texts").deepCopy());
        }
        if (selected.line().has("translation_key")) {
            slot.add("chance_translation_key", selected.line().get("translation_key").deepCopy());
        }
    }

    private static void applyDrawnProbabilities(
        List<JsonObject> outputs,
        List<ProbabilityText.Draw> drawnProbabilities
    ) {
        for (ProbabilityText.Draw draw : drawnProbabilities) {
            List<Integer> candidates = new ArrayList<>();
            for (int index = 0; index < outputs.size(); index++) {
                if (!outputs.get(index).has("chance")) {
                    candidates.add(index);
                }
            }
            if (candidates.isEmpty()) {
                return;
            }
            int selected = candidates.stream()
                .min(Comparator.comparingDouble(index -> distance(outputs.get(index), draw.x(), draw.y())))
                .orElse(0);
            if (distance(outputs.get(selected), draw.x(), draw.y()) <= 64.0D) {
                JsonObject slot = outputs.get(selected);
                applyChance(slot, draw.match(), "jei_category_drawn_text");
                slot.addProperty("chance_render_x", draw.x());
                slot.addProperty("chance_render_y", draw.y());
            }
        }
    }

    private static int primaryOutputIndex(List<JsonObject> outputs) {
        for (int index = 0; index < outputs.size(); index++) {
            if (containsHint(string(outputs.get(index), "name"), PRIMARY_HINTS)) {
                return index;
            }
        }
        return 0;
    }

    private static String byproductEvidence(JsonObject output, int index, int primaryIndex) {
        String name = string(output, "name");
        if (containsHint(name, BYPRODUCT_HINTS)) {
            return "jei_slot_name";
        }
        if (tooltipContainsHint(output, BYPRODUCT_HINTS)) {
            return "jei_slot_tooltip";
        }
        if (index != primaryIndex
            && output.has("chance_available")
            && output.get("chance_available").getAsBoolean()
            && output.get("chance").getAsDouble() < 1.0D) {
            return "jei_probability_non_primary_output";
        }
        return null;
    }

    private static boolean tooltipContainsHint(JsonObject output, List<String> hints) {
        if (!output.has("_jei_tooltip") || !output.get("_jei_tooltip").isJsonArray()) {
            return false;
        }
        for (JsonElement element : output.getAsJsonArray("_jei_tooltip")) {
            JsonObject line = element.getAsJsonObject();
            if (containsHint(string(line, "text"), hints)
                || containsHint(string(line, "translation_key"), hints)) {
                return true;
            }
        }
        return false;
    }

    private static void applyChance(JsonObject target, ProbabilityText.Match chance, String source) {
        target.addProperty("chance_available", true);
        target.addProperty("chance", chance.probability());
        target.addProperty("chance_percent", chance.percent());
        target.addProperty("chance_comparator", chance.comparator());
        target.addProperty("chance_source", source);
        target.addProperty("chance_text", chance.text());
    }

    private static Optional<TooltipChance> uniqueChance(List<TooltipChance> values) {
        Map<String, TooltipChance> unique = new LinkedHashMap<>();
        for (TooltipChance value : values) {
            String key = value.match().comparator() + ':' + value.match().percent();
            unique.putIfAbsent(key, value);
        }
        return unique.size() == 1 ? Optional.of(unique.values().iterator().next()) : Optional.empty();
    }

    private static boolean isBareProbability(ProbabilityText.Match match) {
        String remainder = (match.text().substring(0, match.start()) + match.text().substring(match.end()))
            .replaceAll("[\\s:：()（）\\[\\]<>≤≥~≈-]", "");
        return remainder.isEmpty();
    }

    private static boolean containsHint(String value, List<String> hints) {
        String normalized = value.toLowerCase(Locale.ROOT).replace('-', '_');
        return hints.stream().anyMatch(normalized::contains);
    }

    private static double distance(JsonObject slot, int x, int y) {
        JsonObject rect = slot.has("visual_rect") ? slot.getAsJsonObject("visual_rect")
            : slot.has("rect") ? slot.getAsJsonObject("rect") : null;
        if (rect == null) {
            return Double.MAX_VALUE;
        }
        double centerX = rect.get("x").getAsDouble() + rect.get("width").getAsDouble() / 2.0D;
        double centerY = rect.get("y").getAsDouble() + rect.get("height").getAsDouble() / 2.0D;
        return Math.hypot(centerX - x, centerY - y);
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private record TooltipChance(ProbabilityText.Match match, JsonObject line) {
    }
}
