package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.OnceStage;
import cn.mcmod.wiki.exporter.util.ExportPaths;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Copies the transitive client-resource closure for registered content instead
 * of copying every texture in a namespace. Standard JSON models, composite
 * children, OBJ/MTL references and default entity textures are roots/edges.
 */
public final class ResourceStage extends OnceStage {
    public static final String ID = "resources";

    @Override
    public String id() {
        return ID;
    }

    @Override
    protected void run(ExportContext context) throws Exception {
        ResourceManager manager = context.minecraft().getResourceManager();
        Deque<PendingResource> pending = new ArrayDeque<>();
        Set<String> queued = new TreeSet<>();
        Map<String, ExportedResource> exported = new TreeMap<>();

        BuiltInRegistries.BLOCK.entrySet().stream()
            .filter(entry -> context.matches(entry.getKey().location()))
            .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
            .forEach(entry -> {
                ResourceLocation id = entry.getKey().location();
                enqueue(pending, queued, asset(id, "blockstates/", ".json"), "registered_block");
                enqueue(pending, queued, asset(id, "models/block/", ".json"), "registered_block_fallback_model");
            });
        BuiltInRegistries.ITEM.entrySet().stream()
            .filter(entry -> context.matches(entry.getKey().location()))
            .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
            .forEach(entry -> enqueue(
                pending,
                queued,
                asset(entry.getKey().location(), "models/item/", ".json"),
                "registered_item"
            ));
        enqueueDefaultEntityTextures(context, pending, queued);

        while (!pending.isEmpty()) {
            PendingResource next = pending.removeFirst();
            Optional<Resource> found = manager.getResource(next.id);
            if (found.isEmpty()) {
                context.report().addCount(ID, "missing_optional_dependencies", 1);
                continue;
            }
            Path target = ExportPaths.resourceFile(context.root(), "assets", next.id);
            try {
                Files.createDirectories(target.getParent());
                try (InputStream input = found.get().open()) {
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }
                long size = Files.size(target);
                String kind = kind(next.id.getPath());
                exported.put(next.id.toString(), new ExportedResource(next.id, target, kind, next.reason, size));
                discoverDependencies(context, next.id, target, pending, queued);
            } catch (Throwable throwable) {
                context.report().error(ID, next.id.toString(), throwable);
            }
        }

        com.google.gson.JsonArray files = new com.google.gson.JsonArray();
        Map<String, Integer> counts = new TreeMap<>();
        int dependencyCount = 0;
        for (ExportedResource resource : exported.values()) {
            JsonObject json = new JsonObject();
            json.addProperty("kind", resource.kind);
            json.addProperty("id", resource.id.toString());
            json.addProperty("path", context.root().relativize(resource.path).toString().replace('\\', '/'));
            json.addProperty("size_bytes", resource.size);
            json.addProperty("selected_namespace", context.matches(resource.id));
            json.addProperty("inclusion_reason", resource.reason);
            if (!context.matches(resource.id)) {
                dependencyCount++;
            }
            counts.merge(resource.kind, 1, Integer::sum);
            files.add(json);
        }
        JsonObject output = new JsonObject();
        output.addProperty("schema_version", "mcmods-resources/v2");
        output.addProperty("selection", "registered_content_dependency_closure");
        output.addProperty("asset_count", files.size());
        output.addProperty("dependency_count", dependencyCount);
        JsonObject countsJson = new JsonObject();
        counts.forEach(countsJson::addProperty);
        output.add("counts_by_kind", countsJson);
        output.add("files", files);
        context.writeJson("resources/index.json", output);
        context.report().addCount(ID, "assets", files.size());
        context.report().addCount(ID, "external_dependencies", dependencyCount);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void enqueueDefaultEntityTextures(
        ExportContext context,
        Deque<PendingResource> pending,
        Set<String> queued
    ) {
        if (context.minecraft().level == null) {
            return;
        }
        BuiltInRegistries.ENTITY_TYPE.entrySet().stream()
            .filter(entry -> context.matches(entry.getKey().location()))
            .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
            .forEach(entry -> {
                Entity entity = null;
                try {
                    entity = ((EntityType<?>) entry.getValue()).create(context.minecraft().level);
                    if (entity == null) {
                        return;
                    }
                    net.minecraft.client.renderer.entity.EntityRenderer renderer =
                        context.minecraft().getEntityRenderDispatcher().getRenderer(entity);
                    ResourceLocation texture = renderer.getTextureLocation(entity);
                    if (texture != null) {
                        enqueue(pending, queued, texture, "default_entity_texture");
                    }
                } catch (Throwable throwable) {
                    context.report().error(ID, entry.getKey().location() + ":entity_texture", throwable);
                } finally {
                    if (entity != null) {
                        entity.discard();
                    }
                }
            });
    }

    private static void discoverDependencies(
        ExportContext context,
        ResourceLocation source,
        Path copied,
        Deque<PendingResource> pending,
        Set<String> queued
    ) {
        String path = source.getPath().toLowerCase(Locale.ROOT);
        try {
            if (path.endsWith(".json")) {
                try (Reader reader = Files.newBufferedReader(copied, StandardCharsets.UTF_8)) {
                    JsonElement json = JsonParser.parseReader(reader);
                    scanJson(json, null, false, source, pending, queued);
                }
            } else if (path.endsWith(".obj")) {
                for (String line : Files.readAllLines(copied, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("mtllib ")) {
                        enqueueRelativeFile(pending, queued, source, trimmed.substring(7).trim(), "obj_material");
                    }
                }
            } else if (path.endsWith(".mtl")) {
                for (String line : Files.readAllLines(copied, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.toLowerCase(Locale.ROOT).startsWith("map_kd ")) {
                        enqueueRelativeFile(pending, queued, source, trimmed.substring(7).trim(), "material_texture");
                    }
                }
            }
        } catch (Throwable throwable) {
            context.report().error(ID, source + ":dependency_scan", throwable);
        }
        if (path.endsWith(".png")) {
            ResourceLocation metadata = ResourceLocation.fromNamespaceAndPath(source.getNamespace(), source.getPath() + ".mcmeta");
            if (context.minecraft().getResourceManager().getResource(metadata).isPresent()) {
                enqueue(pending, queued, metadata, "texture_metadata");
            }
        }
    }

    private static void scanJson(
        JsonElement element,
        String key,
        boolean inTextures,
        ResourceLocation source,
        Deque<PendingResource> pending,
        Set<String> queued
    ) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> scanJson(child, key, inTextures, source, pending, queued));
            return;
        }
        if (element.isJsonObject()) {
            element.getAsJsonObject().entrySet().forEach(entry -> scanJson(
                entry.getValue(),
                entry.getKey(),
                inTextures || entry.getKey().equals("textures"),
                source,
                pending,
                queued
            ));
            return;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return;
        }
        String value = element.getAsString().trim();
        if (value.isBlank() || value.startsWith("#")) {
            return;
        }
        String normalizedKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
        if (normalizedKey.equals("parent")) {
            ResourceLocation id = parseLocation(value, source.getNamespace());
            if (id != null && !id.getPath().equals("builtin/generated") && !id.getPath().equals("builtin/entity")) {
                enqueue(pending, queued, modelFile(id), "parent_model");
            }
        } else if (normalizedKey.equals("model")) {
            ResourceLocation id = parseLocation(value, source.getNamespace());
            if (id != null) {
                enqueue(pending, queued, modelOrCustomFile(id), "referenced_model");
            }
        } else if (inTextures || normalizedKey.equals("texture")) {
            ResourceLocation id = parseLocation(value, source.getNamespace());
            if (id != null) {
                enqueue(pending, queued, textureFile(id), "model_texture");
            }
        }
        if (value.endsWith(".obj") || value.endsWith(".mtl") || value.endsWith(".png")) {
            ResourceLocation id = parseLocation(value, source.getNamespace());
            if (id != null) {
                enqueue(pending, queued, normalizeExplicitFile(id), "custom_loader_resource");
            }
        }
    }

    private static void enqueueRelativeFile(
        Deque<PendingResource> pending,
        Set<String> queued,
        ResourceLocation source,
        String value,
        String reason
    ) {
        ResourceLocation explicit = parseLocation(value.replace('\\', '/'), source.getNamespace());
        if (explicit == null) {
            return;
        }
        if (!value.contains(":")) {
            String parent = source.getPath().contains("/")
                ? source.getPath().substring(0, source.getPath().lastIndexOf('/') + 1)
                : "";
            explicit = ResourceLocation.fromNamespaceAndPath(source.getNamespace(), parent + explicit.getPath());
        }
        enqueue(pending, queued, normalizeExplicitFile(explicit), reason);
    }

    private static ResourceLocation asset(ResourceLocation id, String prefix, String suffix) {
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), prefix + id.getPath() + suffix);
    }

    private static ResourceLocation modelFile(ResourceLocation id) {
        String path = id.getPath();
        if (!path.startsWith("models/")) {
            path = "models/" + path;
        }
        if (!path.endsWith(".json")) {
            path += ".json";
        }
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), path);
    }

    private static ResourceLocation modelOrCustomFile(ResourceLocation id) {
        String lower = id.getPath().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".obj") || lower.endsWith(".mtl") || lower.endsWith(".json")) {
            return normalizeExplicitFile(id.getPath().startsWith("models/") ? id : ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "models/" + id.getPath()));
        }
        return modelFile(id);
    }

    private static ResourceLocation textureFile(ResourceLocation id) {
        String path = id.getPath();
        if (!path.startsWith("textures/")) {
            path = "textures/" + path;
        }
        if (!path.endsWith(".png")) {
            path += ".png";
        }
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), path);
    }

    private static ResourceLocation normalizeExplicitFile(ResourceLocation id) {
        String path = id.getPath();
        if (path.endsWith(".png") && !path.startsWith("textures/")) {
            path = "textures/" + path;
        }
        return ResourceLocation.fromNamespaceAndPath(id.getNamespace(), path);
    }

    private static ResourceLocation parseLocation(String value, String fallbackNamespace) {
        try {
            return value.contains(":") ? ResourceLocation.parse(value) : ResourceLocation.fromNamespaceAndPath(fallbackNamespace, value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void enqueue(
        Deque<PendingResource> pending,
        Set<String> queued,
        ResourceLocation id,
        String reason
    ) {
        if (id != null && queued.add(id.toString())) {
            pending.addLast(new PendingResource(id, reason));
        }
    }

    private static String kind(String path) {
        if (path.startsWith("blockstates/")) return "blockstate";
        if (path.startsWith("models/") && path.endsWith(".json")) return "model";
        if (path.endsWith(".obj")) return "obj";
        if (path.endsWith(".mtl")) return "material";
        if (path.endsWith(".png")) return "texture";
        if (path.endsWith(".mcmeta")) return "texture_metadata";
        return "resource";
    }

    private record PendingResource(ResourceLocation id, String reason) {
    }

    private record ExportedResource(ResourceLocation id, Path path, String kind, String reason, long size) {
    }
}
