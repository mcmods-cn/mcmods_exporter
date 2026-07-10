package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.OnceStage;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementList;
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
        List<Advancement> exported = new ArrayList<>();
        for (Advancement advancement : advancements) {
            if (!context.matches(advancement.getId())) {
                continue;
            }
            try {
                data.add(toJson(context, advancement));
                exported.add(advancement);
                if (advancement.getParent() == null) {
                    roots.add(advancement.getId().toString());
                }
            } catch (Throwable throwable) {
                context.report().error(ID, advancement.getId().toString(), throwable);
            }
        }

        JsonObject output = new JsonObject();
        output.addProperty("schema_version", "mcwiki-advancements/v1");
        output.addProperty("source", server == null ? "client_sync" : "integrated_server");
        output.addProperty("count", data.size());
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
            localizedOutput.addProperty("schema_version", "mcwiki-advancements-localized/v1");
            localizedOutput.addProperty("locale", language);
            localizedOutput.addProperty("count", localizedEntries.size());
            localizedOutput.add("advancements", localizedEntries);
            context.writeJson("advancements/localized/" + language + ".json", localizedOutput);
        }
        context.report().addCount(ID, "advancements", data.size());
        context.report().addCount(ID, "roots", roots.size());
        context.report().addCount(ID, "localized_files", context.languages().size());
    }

    private static JsonObject toJson(ExportContext context, Advancement advancement) {
        JsonObject json = new JsonObject();
        json.addProperty("id", advancement.getId().toString());
        if (advancement.getParent() != null) {
            json.addProperty("parent", advancement.getParent().getId().toString());
        }
        JsonArray children = new JsonArray();
        advancement.getChildren().forEach(child -> children.add(child.getId().toString()));
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
        return json;
    }

    private static void addComponent(ExportContext context, JsonObject target, String field, Component component) {
        target.add(field + "_names", context.componentNames(component));
        if (component.getContents() instanceof TranslatableContents translatable) {
            target.addProperty(field + "_translation_key", translatable.getKey());
        }
    }
}
