package cn.mcmod.wiki.exporter.task;

import cn.mcmod.wiki.exporter.config.ExportConfig;
import cn.mcmod.wiki.exporter.util.ExportPaths;
import cn.mcmod.wiki.exporter.util.JsonFiles;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class ExportContext {
    private final Minecraft minecraft;
    private final ExportConfig config;
    private final Path root;
    private final ExportReport report;
    private final Map<String, Map<String, String>> translations = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> resolvedTranslations = new LinkedHashMap<>();
    private final List<String> languages;

    public ExportContext(Minecraft minecraft, ExportConfig config, Path root, ExportReport report) {
        this.minecraft = minecraft;
        this.config = config;
        this.root = root;
        this.report = report;
        this.languages = new ArrayList<>(config.languages());
    }

    public Minecraft minecraft() {
        return minecraft;
    }

    public ExportConfig config() {
        return config;
    }

    public Path root() {
        return root;
    }

    public ExportReport report() {
        return report;
    }

    public Map<String, Map<String, String>> translations() {
        return translations;
    }

    public List<String> languages() {
        return Collections.unmodifiableList(languages);
    }

    public void includeLanguages(Collection<String> discoveredLanguages) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(languages);
        merged.addAll(discoveredLanguages);
        languages.clear();
        languages.addAll(merged.stream().sorted().toList());
        resolvedTranslations.clear();
    }

    public boolean matches(ResourceLocation id) {
        return config.matchesNamespace(id.getNamespace());
    }

    public JsonObject names(String translationKey) {
        JsonObject names = new JsonObject();
        for (String language : languages) {
            String value = translationsFor(language).get(translationKey);
            if (value != null) {
                names.addProperty(language, value);
            }
        }
        return names;
    }

    public JsonObject componentNames(Component component) {
        JsonObject names = new JsonObject();
        for (String language : languages) {
            names.addProperty(language, cn.mcmod.wiki.exporter.util.ComponentLocalizer.localize(
                component,
                translationsFor(language)
            ));
        }
        return names;
    }

    public String localize(Component component, String language) {
        return cn.mcmod.wiki.exporter.util.ComponentLocalizer.localize(
            component,
            translationsFor(language)
        );
    }

    private Map<String, String> translationsFor(String language) {
        return resolvedTranslations.computeIfAbsent(language, this::buildTranslationsFor);
    }

    private Map<String, String> buildTranslationsFor(String language) {
        Map<String, String> selected = translations.getOrDefault(language, Map.of());
        if ("en_us".equals(language)) {
            return selected;
        }
        Map<String, String> english = translations.getOrDefault("en_us", Map.of());
        if (english.isEmpty()) {
            return selected;
        }
        LinkedHashMap<String, String> withFallback = new LinkedHashMap<>(english);
        withFallback.putAll(selected);
        return Collections.unmodifiableMap(withFallback);
    }

    public void writeJson(String relativePath, JsonElement json) throws IOException {
        JsonFiles.write(ExportPaths.safeResolve(root, relativePath), json);
    }

    public static JsonObject stackJson(ItemStack stack) {
        JsonObject json = new JsonObject();
        if (stack == null || stack.isEmpty()) {
            json.addProperty("empty", true);
            return json;
        }
        json.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        json.addProperty("count", stack.getCount());
        if (stack.hasTag()) {
            json.addProperty("nbt_snbt", stack.getTag().toString());
        }
        return json;
    }
}
