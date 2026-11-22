package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.ExportStage;
import cn.mcmod.wiki.exporter.task.StageResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LanguageStage implements ExportStage {
    public static final String ID = "languages";
    private static final Pattern LANGUAGE_FILE = Pattern.compile("^lang/([a-z0-9_-]+)\\.json$");

    private final Map<String, List<LanguageSource>> sources = new HashMap<>();
    private final JsonArray languageIndex = new JsonArray();
    private int nextLocale;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void start(ExportContext context) {
        prepare(context, context.minecraft().getResourceManager());
    }

    void prepare(ExportContext context, ResourceManager resources) {
        TreeSet<String> discovered = new TreeSet<>();
        resources.listResources("lang", id -> context.matches(id) && LANGUAGE_FILE.matcher(id.getPath()).matches())
            .keySet().forEach(id -> {
                Matcher matcher = LANGUAGE_FILE.matcher(id.getPath());
                if (matcher.matches()) {
                    discovered.add(matcher.group(1));
                }
            });
        context.includeLanguages(discovered);

        Map<String, String> localesByPath = new HashMap<>();
        context.languages().forEach(locale -> localesByPath.put("lang/" + locale + ".json", locale));
        resources.listResourceStacks("lang", id -> localesByPath.containsKey(id.getPath())).forEach((id, stack) -> {
            String locale = localesByPath.get(id.getPath());
            sources.computeIfAbsent(locale, ignored -> new ArrayList<>())
                .add(new LanguageSource(id, context.matches(id), stack));
        });
        // Dependencies first, then the selected namespaces. Each resource stack
        // already follows the resource manager's low-to-high pack priority.
        Comparator<LanguageSource> order = Comparator.comparing(LanguageSource::target).thenComparing(LanguageSource::id);
        sources.values().forEach(group -> group.sort(order));
    }

    @Override
    public StageResult step(ExportContext context) throws IOException {
        if (nextLocale < context.languages().size()) {
            String locale = context.languages().get(nextLocale++);
            List<LanguageSource> group = sources.getOrDefault(locale, List.of());
            exportLocale(context, locale, group);
            sources.remove(locale);
            return StageResult.MORE_WORK;
        }
        JsonObject languages = new JsonObject();
        languages.addProperty("schema_version", "mcmods-languages/v1");
        languages.addProperty("count", languageIndex.size());
        languages.add("languages", languageIndex);
        context.writeJson("translations/languages.json", languages);
        return StageResult.DONE;
    }

    private void exportLocale(ExportContext context, String locale, List<LanguageSource> group) throws IOException {
        Map<String, String> localizationTranslations = new LinkedHashMap<>();
        Map<String, String> exportedTranslations = new LinkedHashMap<>();
        for (LanguageSource source : group) {
            for (Resource resource : source.stack()) {
                Map<String, String> resourceValues = new LinkedHashMap<>();
                try (InputStream input = resource.open()) {
                    Language.loadFromJson(input, resourceValues::put);
                    localizationTranslations.putAll(resourceValues);
                    if (source.target()) {
                        exportedTranslations.putAll(resourceValues);
                    }
                } catch (Exception exception) {
                    context.report().error(ID, source.id(), exception);
                }
            }
        }
        context.translations().put(locale, localizationTranslations);

        JsonObject output = new JsonObject();
        output.addProperty("schema_version", "mcmods-language/v1");
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

    private record LanguageSource(ResourceLocation id, boolean target, List<Resource> stack) { }
}
