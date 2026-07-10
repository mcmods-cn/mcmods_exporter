package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.OnceStage;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LanguageStage extends OnceStage {
    public static final String ID = "languages";
    private static final Pattern LANGUAGE_FILE = Pattern.compile("^lang/([a-z0-9_-]+)\\.json$");

    @Override
    public String id() {
        return ID;
    }

    @Override
    protected void run(ExportContext context) throws Exception {
        ResourceManager resources = context.minecraft().getResourceManager();
        TreeSet<String> discovered = new TreeSet<>();
        resources.listResources("lang", id -> context.matches(id) && LANGUAGE_FILE.matcher(id.getPath()).matches())
            .keySet()
            .forEach(id -> {
                Matcher matcher = LANGUAGE_FILE.matcher(id.getPath());
                if (matcher.matches()) {
                    discovered.add(matcher.group(1));
                }
            });
        context.includeLanguages(discovered);

        JsonArray languageIndex = new JsonArray();
        for (String locale : context.languages()) {
            String path = "lang/" + locale + ".json";
            Map<String, String> localizationTranslations = new LinkedHashMap<>();
            Map<String, String> exportedTranslations = new LinkedHashMap<>();
            Map<ResourceLocation, List<Resource>> stacks = resources.listResourceStacks(
                "lang",
                id -> id.getPath().equals(path)
            );
            // Dependency strings (Minecraft, JEI, library mods) are needed to localize composed
            // Components. Load them first, then target namespaces so exported Mod text wins.
            for (boolean targetPass : List.of(false, true)) {
                for (Map.Entry<ResourceLocation, List<Resource>> entry : stacks.entrySet().stream()
                    .filter(entry -> context.matches(entry.getKey()) == targetPass)
                    .sorted(Map.Entry.comparingByKey())
                    .toList()) {
                    for (Resource resource : entry.getValue()) {
                        Map<String, String> resourceValues = new LinkedHashMap<>();
                        try (InputStream input = resource.open()) {
                            Language.loadFromJson(input, resourceValues::put);
                            localizationTranslations.putAll(resourceValues);
                            if (targetPass) {
                                exportedTranslations.putAll(resourceValues);
                            }
                        } catch (Exception exception) {
                            context.report().error(ID, entry.getKey() + "@" + resource.sourcePackId(), exception);
                        }
                    }
                }
            }
            context.translations().put(locale, localizationTranslations);

            JsonObject output = new JsonObject();
            output.addProperty("schema_version", "mcwiki-language/v1");
            output.addProperty("locale", locale);
            JsonObject values = new JsonObject();
            exportedTranslations.forEach(values::addProperty);
            output.add("translations", values);
            output.addProperty("translation_count", exportedTranslations.size());
            context.writeJson("translations/" + locale + ".json", output);
            context.report().addCount(ID, "locales", 1);
            context.report().addCount(ID, "translations", exportedTranslations.size());
            JsonObject languageEntry = new JsonObject();
            languageEntry.addProperty("locale", locale);
            languageEntry.addProperty("translation_count", exportedTranslations.size());
            languageIndex.add(languageEntry);
        }
        JsonObject languages = new JsonObject();
        languages.addProperty("schema_version", "mcwiki-languages/v1");
        languages.addProperty("count", languageIndex.size());
        languages.add("languages", languageIndex);
        context.writeJson("translations/languages.json", languages);
    }
}
