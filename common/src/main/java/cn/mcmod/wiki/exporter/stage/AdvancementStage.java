package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.OnceStage;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
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
        Map<String, AdvancementHolder> merged = new LinkedHashMap<>();
        if (server != null) {
            server.getAdvancements().getAllAdvancements().forEach(holder -> merged.put(holder.id().toString(), holder));
        }
        if (connection != null) {
            connection.getAdvancements().getTree().nodes().forEach(node -> {
                AdvancementHolder holder = node.holder();
                merged.putIfAbsent(holder.id().toString(), holder);
            });
        }
        List<AdvancementHolder> advancements = new ArrayList<>(merged.values());
        advancements.sort(Comparator.comparing(holder -> holder.id().toString()));
        Map<String, List<String>> children = collectChildren(advancements);

        JsonArray data = new JsonArray();
        JsonArray roots = new JsonArray();
        List<AdvancementHolder> exported = new ArrayList<>();
        for (AdvancementHolder holder : advancements) {
            if (!context.matches(holder.id())) {
                continue;
            }
            try {
                data.add(toJson(context, holder, children.getOrDefault(holder.id().toString(), List.of())));
                exported.add(holder);
                if (holder.value().parent().isEmpty()) {
                    roots.add(holder.id().toString());
                }
            } catch (Throwable throwable) {
                context.report().error(ID, holder.id().toString(), throwable);
            }
        }

        JsonObject output = new JsonObject();
        output.addProperty("schema_version", "mcmods-advancements/v1");
        output.addProperty("source", server == null ? "client_sync" : "integrated_server");
        output.addProperty("count", data.size());
        output.addProperty("language_count", context.languages().size());
        output.add("roots", roots);
        output.add("advancements", data);
        context.writeJson("advancements/advancements.json", output);

        for (String language : context.languages()) {
            JsonArray localizedEntries = new JsonArray();
            for (AdvancementHolder holder : exported) {
                DisplayInfo display = holder.value().display().orElse(null);
                if (display == null) {
                    continue;
                }
                JsonObject localized = new JsonObject();
                localized.addProperty("id", holder.id().toString());
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
        context.report().addCount(ID, "localized_files", context.languages().size());
    }

    private static Map<String, List<String>> collectChildren(List<AdvancementHolder> advancements) {
        Map<String, List<String>> children = new LinkedHashMap<>();
        for (AdvancementHolder holder : advancements) {
            holder.value().parent().ifPresent(parent -> children
                .computeIfAbsent(parent.toString(), ignored -> new ArrayList<>())
                .add(holder.id().toString()));
        }
        children.values().forEach(values -> values.sort(String::compareTo));
        return children;
    }

    private static JsonObject toJson(ExportContext context, AdvancementHolder holder, List<String> childIds) {
        Advancement advancement = holder.value();
        JsonObject json = new JsonObject();
        json.addProperty("id", holder.id().toString());
        advancement.parent().ifPresent(parent -> json.addProperty("parent", parent.toString()));
        JsonArray children = new JsonArray();
        childIds.forEach(children::add);
        json.add("children", children);

        DisplayInfo display = advancement.display().orElse(null);
        if (display != null) {
            JsonObject displayJson = new JsonObject();
            addComponent(context, displayJson, "title", display.getTitle());
            addComponent(context, displayJson, "description", display.getDescription());
            displayJson.add("icon", ExportContext.stackJson(display.getIcon()));
            displayJson.addProperty("frame", display.getType().name().toLowerCase());
            display.getBackground().ifPresent(background -> displayJson.addProperty("background", background.toString()));
            displayJson.addProperty("x", display.getX());
            displayJson.addProperty("y", display.getY());
            displayJson.addProperty("hidden", display.isHidden());
            displayJson.addProperty("show_toast", display.shouldShowToast());
            displayJson.addProperty("announce_chat", display.shouldAnnounceChat());
            json.add("display", displayJson);
        }

        JsonArray criteria = new JsonArray();
        advancement.criteria().keySet().stream().sorted().forEach(criteria::add);
        json.add("criteria", criteria);
        JsonArray requirements = new JsonArray();
        for (List<String> group : advancement.requirements().requirements()) {
            JsonArray groupJson = new JsonArray();
            for (String criterion : group) {
                groupJson.add(criterion);
            }
            requirements.add(groupJson);
        }
        json.add("requirements", requirements);
        json.addProperty("max_criteria_required", advancement.requirements().size());
        addRewards(json, advancement.rewards());
        return json;
    }

    private static void addRewards(JsonObject json, AdvancementRewards rewards) {
        JsonObject output = new JsonObject();
        output.addProperty("experience", rewards.experience());
        JsonArray loot = new JsonArray();
        rewards.loot().forEach(key -> loot.add(key.location().toString()));
        output.add("loot", loot);
        JsonArray recipes = new JsonArray();
        rewards.recipes().forEach(recipe -> recipes.add(recipe.toString()));
        output.add("recipes", recipes);
        rewards.function().ifPresent(function -> output.addProperty("function", function.getId().toString()));
        json.add("rewards", output);
    }

    private static void addComponent(ExportContext context, JsonObject target, String field, Component component) {
        target.add(field + "_names", context.componentNames(component));
        if (component.getContents() instanceof TranslatableContents translatable) {
            target.addProperty(field + "_translation_key", translatable.getKey());
        }
    }
}
