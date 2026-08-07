package cn.mcmod.wiki.exporter.task;

import cn.mcmod.wiki.exporter.McModsExporter;
import cn.mcmod.wiki.exporter.config.ExportConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.architectury.platform.Platform;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

public final class ExportReport {
    private final Instant startedAt = Instant.now();
    private final JsonArray errors = new JsonArray();
    private final Map<String, StageInfo> stages = new LinkedHashMap<>();
    private List<String> selectedModules = List.of();
    private String status = "running";
    private Instant finishedAt;

    public void stageStarted(String id) {
        stages.computeIfAbsent(id, ignored -> new StageInfo()).startedAt = Instant.now();
    }

    public void stageFinished(String id) {
        StageInfo stage = stages.computeIfAbsent(id, ignored -> new StageInfo());
        stage.finishedAt = Instant.now();
        stage.status = "complete";
    }

    public void stageFailed(String id, Throwable throwable) {
        StageInfo stage = stages.computeIfAbsent(id, ignored -> new StageInfo());
        stage.finishedAt = Instant.now();
        stage.status = "failed";
        error(id, "stage", throwable);
    }

    public void addCount(String stageId, String key, long amount) {
        StageInfo stage = stages.computeIfAbsent(stageId, ignored -> new StageInfo());
        stage.counts.merge(key, amount, Long::sum);
    }

    public void modulesSelected(List<String> modules) {
        selectedModules = List.copyOf(modules);
    }

    public void error(String stageId, String subject, Throwable throwable) {
        JsonObject error = new JsonObject();
        error.addProperty("stage", stageId);
        error.addProperty("subject", subject);
        error.addProperty("message", String.valueOf(throwable.getMessage()));
        errors.add(error);
    }

    public void finish(boolean cancelled) {
        status = cancelled ? "cancelled" : "complete";
        finishedAt = Instant.now();
    }

    public JsonObject manifest(ExportConfig config, List<String> exportedLanguages, String archiveName) {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", McModsExporter.SCHEMA_VERSION);
        root.addProperty("exporter_version", McModsExporter.VERSION);
        root.addProperty("status", status);
        root.addProperty("started_at", startedAt.toString());
        if (finishedAt != null) {
            root.addProperty("finished_at", finishedAt.toString());
            root.addProperty("duration_ms", finishedAt.toEpochMilli() - startedAt.toEpochMilli());
        }
        root.addProperty("minecraft_version", Platform.getMinecraftVersion());
        root.addProperty("loader", Platform.isFabric() ? "fabric" : Platform.isNeoForge() ? "neoforge" : "unknown");
        root.addProperty("package_format", archiveName == null ? "directory" : "zip");
        if (archiveName != null) {
            root.addProperty("archive_name", archiveName);
        }

        JsonObject configuration = new JsonObject();
        JsonArray namespaces = new JsonArray();
        config.namespaces().forEach(namespaces::add);
        configuration.add("namespaces", namespaces);
        configuration.addProperty("all_namespaces", config.namespaces().isEmpty());
        JsonArray languages = new JsonArray();
        exportedLanguages.forEach(languages::add);
        configuration.add("languages", languages);
        JsonArray sizes = new JsonArray();
        config.imageSizes().forEach(sizes::add);
        configuration.add("image_sizes", sizes);
        configuration.addProperty("profile", config.profile().name().toLowerCase());
        configuration.addProperty("automatic", config.automatic());
        JsonArray modules = new JsonArray();
        selectedModules.forEach(modules::add);
        configuration.add("modules", modules);
        JsonArray excludedRecipeTypes = new JsonArray();
        config.excludedRecipeTypes().forEach(excludedRecipeTypes::add);
        configuration.add("excluded_recipe_type_ids", excludedRecipeTypes);
        JsonArray templateOnlyRecipeTypes = new JsonArray();
        config.templateOnlyRecipeTypes().forEach(templateOnlyRecipeTypes::add);
        configuration.add("template_only_recipe_type_ids", templateOnlyRecipeTypes);
        root.add("configuration", configuration);

        JsonArray stageArray = new JsonArray();
        stages.forEach((id, info) -> stageArray.add(info.toJson(id)));
        root.add("stages", stageArray);
        root.add("errors", errors);
        root.addProperty("error_count", errors.size());
        return root;
    }

    private static final class StageInfo {
        private Instant startedAt;
        private Instant finishedAt;
        private String status = "running";
        private final Map<String, Long> counts = new LinkedHashMap<>();

        private JsonObject toJson(String id) {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("status", status);
            if (startedAt != null && finishedAt != null) {
                json.addProperty("duration_ms", finishedAt.toEpochMilli() - startedAt.toEpochMilli());
            }
            JsonObject countJson = new JsonObject();
            counts.forEach(countJson::addProperty);
            json.add("counts", countJson);
            return json;
        }
    }
}
