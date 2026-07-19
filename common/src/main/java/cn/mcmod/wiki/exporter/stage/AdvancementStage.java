package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.OnceStage;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementList;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class AdvancementStage extends OnceStage {
    public static final String ID = "advancements";

    @Override
    public String id() {
        return ID;
    }

    @Override
    protected void run(ExportContext context) throws Exception {
        ClientPacketListener connection = context.minecraft().getConnection();
        IntegratedServer server = context.minecraft().getSingleplayerServer();
        if (connection == null && server == null) {
            throw new IllegalStateException("Advancements are not available before joining a world");
        }
        Map<String, Advancement> merged = new LinkedHashMap<>();
        if (server != null) {
            server.getAdvancements().getAllAdvancements().forEach(advancement -> merged.put(advancement.getId().toString(), advancement));
        }
        if (connection != null) {
            AdvancementList list = connection.getAdvancements().getAdvancements();
            list.getAllAdvancements().forEach(advancement -> merged.putIfAbsent(advancement.getId().toString(), advancement));
        }
        List<Advancement> advancements = new ArrayList<>(merged.values());
        advancements.sort(Comparator.comparing(advancement -> advancement.getId().toString()));

        JsonArray data = new JsonArray();
        JsonArray roots = new JsonArray();
        List<Advancement> candidates = new ArrayList<>();
        int filteredRecipeUnlocks = 0;
        int filteredWithoutDisplay = 0;
        for (Advancement advancement : advancements) {
            if (!context.matches(advancement.getId())) {
                continue;
            }
            String exclusionReason = advancementExclusionReason(
                advancement.getId().toString(),
                advancement.getDisplay() != null
            );
            if ("recipe_unlock".equals(exclusionReason)) {
                filteredRecipeUnlocks++;
                continue;
            }
            if ("no_display".equals(exclusionReason)) {
                filteredWithoutDisplay++;
                continue;
            }
            candidates.add(advancement);
        }
        Set<String> candidateIds = new TreeSet<>();
        candidates.forEach(advancement -> candidateIds.add(advancement.getId().toString()));

        List<Advancement> exported = new ArrayList<>();
        for (Advancement advancement : candidates) {
            try {
                String parentId = advancement.getParent() == null
                    ? null
                    : advancement.getParent().getId().toString();
                if (parentId != null && !candidateIds.contains(parentId)) {
                    parentId = null;
                }
                List<String> childIds = new ArrayList<>();
                advancement.getChildren().forEach(child -> {
                    String childId = child.getId().toString();
                    if (candidateIds.contains(childId)) {
                        childIds.add(childId);
                    }
                });
                childIds.sort(String::compareTo);
                data.add(toJson(context, advancement, parentId, childIds));
                exported.add(advancement);
                if (parentId == null) {
                    roots.add(advancement.getId().toString());
                }
            } catch (Throwable throwable) {
                context.report().error(ID, advancement.getId().toString(), throwable);
            }
        }

        JsonObject output = new JsonObject();
        output.addProperty("schema_version", "mcmods-advancements/v2");
        output.addProperty("source", server == null ? "client_sync" : "integrated_server");
        output.addProperty("filter_mode", "displayed_non_recipe_advancements");
        output.addProperty("count", data.size());
        output.addProperty("filtered_recipe_unlock_count", filteredRecipeUnlocks);
        output.addProperty("filtered_no_display_count", filteredWithoutDisplay);
        output.addProperty("filtered_count", filteredRecipeUnlocks + filteredWithoutDisplay);
        output.addProperty("language_count", context.languages().size());
        output.add("roots", roots);
        output.add("advancements", data);
        context.writeJson("advancements/advancements.json", output);

        for (String language : context.languages()) {
            JsonArray localizedEntries = new JsonArray();
            for (Advancement advancement : exported) {
                DisplayInfo display = advancement.getDisplay();
                if (display == null) {
                    continue;
                }
                JsonObject localized = new JsonObject();
                localized.addProperty("id", advancement.getId().toString());
                localized.addProperty("title", context.localize(display.getTitle(), language));
                localized.addProperty("description", context.localize(display.getDescription(), language));
                localizedEntries.add(localized);
            }
            JsonObject localizedOutput = new JsonObject();
            localizedOutput.addProperty("schema_version", "mcmods-advancements-localized/v1");
            localizedOutput.addProperty("locale", language);
            localizedOutput.addProperty("count", localizedEntries.size());
            localizedOutput.add("advancements", localizedEntries);
            context.writeJson("advancements/localized/" + language + ".json", localizedOutput);
        }
        context.report().addCount(ID, "advancements", data.size());
        context.report().addCount(ID, "roots", roots.size());
        context.report().addCount(ID, "filtered_recipe_unlocks", filteredRecipeUnlocks);
        context.report().addCount(ID, "filtered_no_display", filteredWithoutDisplay);
        context.report().addCount(ID, "localized_files", context.languages().size());
    }

    static String advancementExclusionReason(String advancementId, boolean hasDisplay) {
        int separator = advancementId.indexOf(':');
        String path = separator >= 0 ? advancementId.substring(separator + 1) : advancementId;
        if (path.equals("recipes") || path.startsWith("recipes/")) {
            return "recipe_unlock";
        }
        return hasDisplay ? null : "no_display";
    }

    private static JsonObject toJson(
        ExportContext context,
        Advancement advancement,
        String parentId,
        List<String> childIds
    ) {
        JsonObject json = new JsonObject();
        json.addProperty("id", advancement.getId().toString());
        if (parentId != null) {
            json.addProperty("parent", parentId);
        }
        JsonArray children = new JsonArray();
        childIds.forEach(children::add);
        json.add("children", children);

        DisplayInfo display = advancement.getDisplay();
        if (display != null) {
            JsonObject displayJson = new JsonObject();
            addComponent(context, displayJson, "title", display.getTitle());
            addComponent(context, displayJson, "description", display.getDescription());
            displayJson.add("icon", ExportContext.stackJson(display.getIcon()));
            displayJson.addProperty("frame", display.getFrame().name().toLowerCase());
            if (display.getBackground() != null) {
                displayJson.addProperty("background", display.getBackground().toString());
            }
            displayJson.addProperty("x", display.getX());
            displayJson.addProperty("y", display.getY());
            displayJson.addProperty("hidden", display.isHidden());
            displayJson.addProperty("show_toast", display.shouldShowToast());
            displayJson.addProperty("announce_chat", display.shouldAnnounceChat());
            json.add("display", displayJson);
        }

        JsonArray criteria = new JsonArray();
        advancement.getCriteria().keySet().stream().sorted().forEach(criteria::add);
        json.add("criteria", criteria);
        JsonArray requirements = new JsonArray();
        for (String[] group : advancement.getRequirements()) {
            JsonArray groupJson = new JsonArray();
            for (String criterion : group) {
                groupJson.add(criterion);
            }
            requirements.add(groupJson);
        }
        json.add("requirements", requirements);
        json.addProperty("max_criteria_required", advancement.getMaxCriteraRequired());
        addRewards(json, advancement.getRewards());
        return json;
    }

    private static void addRewards(JsonObject json, AdvancementRewards rewards) {
        json.add("rewards", rewards.serializeToJson());
    }

    private static void addComponent(ExportContext context, JsonObject target, String field, Component component) {
        target.add(field + "_names", context.componentNames(component));
        if (component.getContents() instanceof TranslatableContents translatable) {
            target.addProperty(field + "_translation_key", translatable.getKey());
        }
    }
}
