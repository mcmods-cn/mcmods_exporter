package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.OnceStage;
import cn.mcmod.wiki.exporter.util.ExportPaths;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import dev.architectury.platform.Mod;
import dev.architectury.platform.Platform;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.storage.loot.LootDataType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;

import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Exports the effective server-data resources needed to describe modded worlds.
 * Raw JSON/NBT is retained, while website-facing indexes contain stable logical IDs
 * and already parsed definitions so an importer does not need to understand data-pack paths.
 */
public final class WorldgenDataStage extends OnceStage {
    public static final String ID = "worldgen_data";
    private static final List<String> DATA_PREFIXES = List.of(
        "dimension",
        "dimension_type",
        "worldgen",
        "loot_tables",
        "predicates",
        "item_modifiers",
        "forge/biome_modifier",
        "forge/structure_modifier",
        "neoforge/biome_modifier",
        "neoforge/structure_modifier"
    );
    private static final Set<String> INTERNAL_STRUCTURE_WORLDGEN_KINDS = Set.of(
        "worldgen/template_pool",
        "worldgen/processor_list"
    );
    private static final Set<String> NESTED_STRUCTURE_REGISTRIES = Set.of(
        "minecraft:worldgen/structure_set",
        "minecraft:worldgen/template_pool",
        "minecraft:worldgen/processor_list"
    );
    private static final Map<String, String> EXTENSION_WORLDGEN_REGISTRIES = Map.of(
        "forge:biome_modifier", "worldgen_extension/forge/biome_modifier",
        "forge:structure_modifier", "worldgen_extension/forge/structure_modifier",
        "neoforge:biome_modifier", "worldgen_extension/neoforge/biome_modifier",
        "neoforge:structure_modifier", "worldgen_extension/neoforge/structure_modifier"
    );

    @Override
    public String id() {
        return ID;
    }

    @Override
    protected void run(ExportContext context) throws Exception {
        Map<String, Set<ResourceLocation>> registries = collectRuntimeRegistries(context);
        Map<String, Map<String, JsonElement>> runtimeDefinitions = collectRuntimeDefinitions(context);
        Map<String, DataFile> files = new TreeMap<>();
        String resourceSource;

        int omittedStructureTemplates;
        if (context.minecraft().getSingleplayerServer() != null) {
            omittedStructureTemplates = copyEffectiveResources(
                context,
                context.minecraft().getSingleplayerServer().getResourceManager(),
                files
            );
            resourceSource = "integrated_server_resource_stack";
        } else {
            omittedStructureTemplates = copyInstalledModResources(context, files);
            resourceSource = "installed_mod_fallback";
        }

        writeRegistryIndex(context, registries);
        writeDataFileIndex(context, files, resourceSource);
        writeDimensions(context, registries, runtimeDefinitions, files);
        writeBiomes(context, registries, runtimeDefinitions, files);
        writeEntitySpawns(context);
        writeNaturalGeneration(context, registries, runtimeDefinitions, files);
        writeStructures(context, registries, runtimeDefinitions, files, omittedStructureTemplates);
        writeLootTables(context, files);

        context.report().addCount(ID, "runtime_registries", registries.size());
        context.report().addCount(
            ID,
            "runtime_definitions",
            runtimeDefinitions.values().stream().mapToInt(Map::size).sum()
        );
        context.report().addCount(ID, "data_files", files.size());
        context.report().addCount(ID, "omitted_structure_templates", omittedStructureTemplates);
        context.report().addCount(ID, "loot_tables", countKind(files, "loot_table"));
    }

    private static Map<String, Set<ResourceLocation>> collectRuntimeRegistries(ExportContext context) {
        Map<String, Set<ResourceLocation>> result = new TreeMap<>();
        if (context.minecraft().level == null) {
            return result;
        }
        context.minecraft().level.registryAccess().registries().forEach(entry -> {
            String registryId = entry.key().location().toString();
            if (!isWorldRegistry(registryId)) {
                return;
            }
            Registry<?> registry = entry.value();
            TreeSet<ResourceLocation> values = new TreeSet<>(Comparator.comparing(ResourceLocation::toString));
            registry.keySet().stream().filter(context::matches).forEach(values::add);
            if (!values.isEmpty()) {
                result.put(registryId, values);
            }
        });
        return result;
    }

    private static boolean isWorldRegistry(String registryId) {
        if (registryId.equals("minecraft:worldgen/template_pool")
            || registryId.equals("minecraft:worldgen/processor_list")) {
            return false;
        }
        return registryId.equals("minecraft:dimension")
            || registryId.equals("minecraft:dimension_type")
            || registryId.startsWith("minecraft:worldgen/")
            || EXTENSION_WORLDGEN_REGISTRIES.containsKey(registryId);
    }

    private static Map<String, Map<String, JsonElement>> collectRuntimeDefinitions(ExportContext context) {
        Map<String, Map<String, JsonElement>> result = new TreeMap<>();
        if (context.minecraft().getSingleplayerServer() == null) {
            return result;
        }
        RegistryAccess access = context.minecraft().getSingleplayerServer().registryAccess();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, access);
        Stream.concat(
            RegistryDataLoader.WORLDGEN_REGISTRIES.stream(),
            RegistryDataLoader.DIMENSION_REGISTRIES.stream()
        ).filter(data -> isWorldRegistry(data.key().location().toString()))
            .forEach(data -> encodeUnknownRegistry(context, access, ops, data, result));
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void encodeUnknownRegistry(
        ExportContext context,
        RegistryAccess access,
        RegistryOps<JsonElement> ops,
        RegistryDataLoader.RegistryData<?> data,
        Map<String, Map<String, JsonElement>> result
    ) {
        encodeRegistry(context, access, ops, (RegistryDataLoader.RegistryData) data, result);
    }

    private static <T> void encodeRegistry(
        ExportContext context,
        RegistryAccess access,
        RegistryOps<JsonElement> ops,
        RegistryDataLoader.RegistryData<T> data,
        Map<String, Map<String, JsonElement>> result
    ) {
        String registryId = data.key().location().toString();
        Registry<T> registry = access.registry(data.key()).orElse(null);
        if (registry == null) {
            return;
        }
        Map<String, JsonElement> definitions = new TreeMap<>();
        registry.keySet().stream()
            .filter(context::matches)
            .sorted(Comparator.comparing(ResourceLocation::toString))
            .forEach(id -> {
                T value = registry.get(id);
                if (value == null) {
                    return;
                }
                String[] error = new String[1];
                data.elementCodec().encodeStart(ops, value)
                    .resultOrPartial(message -> error[0] = message)
                    .ifPresent(json -> definitions.put(id.toString(), json));
                if (error[0] != null && !definitions.containsKey(id.toString())) {
                    context.report().error(
                        ID,
                        "runtime_definition:" + registryId + "/" + id,
                        new IllegalStateException(error[0])
                    );
                }
            });
        if (!definitions.isEmpty()) {
            result.put(registryId, definitions);
        }
    }

    private static int copyEffectiveResources(
        ExportContext context,
        ResourceManager manager,
        Map<String, DataFile> files
    ) {
        for (String prefix : DATA_PREFIXES) {
            Map<ResourceLocation, Resource> resources = manager.listResources(
                prefix,
                id -> shouldCopyDataResource(id.getPath())
            );
            resources.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString)))
                .filter(entry -> context.matches(entry.getKey()) || resourceOwnedByTarget(context, entry.getValue()))
                .forEach(entry -> copyResource(context, entry.getKey(), entry.getValue(), files));
        }
        copyReferencedLootTables(context, manager, files);
        return (int) manager.listResources("structures", id -> isStructureTemplatePath(id.getPath()))
            .entrySet().stream()
            .filter(entry -> context.matches(entry.getKey()) || resourceOwnedByTarget(context, entry.getValue()))
            .count();
    }

    private static void copyResource(
        ExportContext context,
        ResourceLocation resourceId,
        Resource resource,
        Map<String, DataFile> files
    ) {
        if (files.containsKey(resourceId.toString())) {
            return;
        }
        try (InputStream input = resource.open()) {
            Path target = ExportPaths.resourceFile(context.root(), "data", resourceId);
            Files.createDirectories(target.getParent());
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            DataFile file = describeFile(resourceId, target);
            if (file != null) {
                files.put(resourceId.toString(), file);
            }
        } catch (Exception exception) {
            context.report().error(ID, "data:" + resourceId, exception);
        }
    }

    private static int copyInstalledModResources(ExportContext context, Map<String, DataFile> files) {
        Set<String> omittedStructureTemplates = new TreeSet<>();
        List<Mod> mods = new ArrayList<>(Platform.getMods());
        mods.sort(Comparator.comparing(Mod::getModId));
        for (Mod mod : mods) {
            boolean selectedOwner = context.config().matchesNamespace(mod.getModId());
            for (Path root : mod.getFilePaths()) {
                Path dataRoot = root.resolve("data");
                if (!Files.isDirectory(dataRoot)) {
                    continue;
                }
                try (Stream<Path> paths = Files.walk(dataRoot)) {
                    paths.filter(Files::isRegularFile).sorted().forEach(source -> {
                        try {
                            Path relative = dataRoot.relativize(source);
                            if (relative.getNameCount() < 2) {
                                return;
                            }
                            String namespace = relative.getName(0).toString();
                            String resourcePath = relative.subpath(1, relative.getNameCount()).toString().replace('\\', '/');
                            ResourceLocation resourceId = ResourceLocation.fromNamespaceAndPath(namespace, resourcePath);
                            if (isStructureTemplatePath(resourcePath)) {
                                if (selectedOwner || context.matches(resourceId)) {
                                    omittedStructureTemplates.add(resourceId.toString());
                                }
                                return;
                            }
                            if ((!selectedOwner && !context.matches(resourceId))
                                || !shouldCopyDataResource(resourcePath)
                                || files.containsKey(resourceId.toString())) {
                                return;
                            }
                            Path target = ExportPaths.resourceFile(context.root(), "data", resourceId);
                            Files.createDirectories(target.getParent());
                            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                            DataFile file = describeFile(resourceId, target);
                            if (file != null) {
                                files.put(resourceId.toString(), file);
                            }
                        } catch (Exception exception) {
                            context.report().error(ID, "installed_mod_data:" + source, exception);
                        }
                    });
                } catch (Exception exception) {
                    context.report().error(ID, "mod_data_root:" + mod.getModId(), exception);
                }
            }
        }
        return omittedStructureTemplates.size();
    }

    private static boolean resourceOwnedByTarget(ExportContext context, Resource resource) {
        if (context.config().namespaces().isEmpty()) {
            return false;
        }
        String packId = resource.sourcePackId().toLowerCase(java.util.Locale.ROOT);
        for (String namespace : context.config().namespaces()) {
            int offset = -1;
            while ((offset = packId.indexOf(namespace, offset + 1)) >= 0) {
                int end = offset + namespace.length();
                boolean leftBoundary = offset == 0 || !isNamespaceCharacter(packId.charAt(offset - 1));
                boolean rightBoundary = end == packId.length() || !isNamespaceCharacter(packId.charAt(end));
                if (leftBoundary && rightBoundary) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isNamespaceCharacter(char value) {
        return value >= 'a' && value <= 'z'
            || value >= '0' && value <= '9'
            || value == '_'
            || value == '-'
            || value == '.';
    }

    private static void copyReferencedLootTables(
        ExportContext context,
        ResourceManager manager,
        Map<String, DataFile> files
    ) {
        Set<ResourceLocation> attempted = new TreeSet<>(Comparator.comparing(ResourceLocation::toString));
        boolean copied;
        do {
            copied = false;
            for (ResourceLocation reference : collectLootTableReferences(context, files)) {
                if (!attempted.add(reference) || hasLootTable(files, reference)) {
                    continue;
                }
                ResourceLocation resourceId = ResourceLocation.fromNamespaceAndPath(
                    reference.getNamespace(),
                    "loot_tables/" + reference.getPath() + ".json"
                );
                var resource = manager.getResource(resourceId);
                if (resource.isPresent()) {
                    copyResource(context, resourceId, resource.get(), files);
                    copied = true;
                }
            }
        } while (copied);
    }

    private static boolean hasLootTable(Map<String, DataFile> files, ResourceLocation objectId) {
        return files.values().stream().anyMatch(file ->
            file.kind().equals("loot_table") && file.objectId().equals(objectId)
        );
    }

    private static Set<ResourceLocation> collectLootTableReferences(
        ExportContext context,
        Map<String, DataFile> files
    ) {
        Set<ResourceLocation> references = new TreeSet<>(Comparator.comparing(ResourceLocation::toString));
        for (DataFile file : files.values()) {
            try {
                if (file.kind().equals("loot_table")) {
                    JsonElement definition = readJson(context, file);
                    if (definition != null) {
                        collectReferencedLootTables(definition, references);
                    }
                }
            } catch (Exception exception) {
                context.report().error(ID, "loot_references:" + file.resourceId(), exception);
            }
        }
        return references;
    }

    private static DataFile describeFile(ResourceLocation resourceId, Path target) throws Exception {
        Classification classification = classify(resourceId.getPath());
        if (classification == null) {
            return null;
        }
        ResourceLocation objectId = ResourceLocation.fromNamespaceAndPath(resourceId.getNamespace(), classification.objectPath());
        return new DataFile(classification.kind(), resourceId, objectId, target, Files.size(target));
    }

    private static Classification classify(String path) {
        if (path.startsWith("dimension_type/") && path.endsWith(".json")) {
            return classification("dimension_type", path, "dimension_type/", ".json");
        }
        if (path.startsWith("dimension/") && path.endsWith(".json")) {
            return classification("dimension", path, "dimension/", ".json");
        }
        if (path.startsWith("loot_tables/") && path.endsWith(".json")) {
            return classification("loot_table", path, "loot_tables/", ".json");
        }
        if (path.startsWith("predicates/") && path.endsWith(".json")) {
            return classification("loot_predicate", path, "predicates/", ".json");
        }
        if (path.startsWith("item_modifiers/") && path.endsWith(".json")) {
            return classification("loot_item_modifier", path, "item_modifiers/", ".json");
        }
        for (Map.Entry<String, String> extension : EXTENSION_WORLDGEN_REGISTRIES.entrySet()) {
            String prefix = extension.getKey().replace(':', '/') + "/";
            if (path.startsWith(prefix) && path.endsWith(".json")) {
                return classification(extension.getValue(), path, prefix, ".json");
            }
        }
        if (path.startsWith("worldgen/") && path.endsWith(".json")) {
            String remainder = path.substring("worldgen/".length(), path.length() - ".json".length());
            int slash = remainder.indexOf('/');
            if (slash > 0 && slash < remainder.length() - 1) {
                return new Classification("worldgen/" + remainder.substring(0, slash), remainder.substring(slash + 1));
            }
        }
        return null;
    }

    private static boolean shouldCopyDataResource(String path) {
        Classification classification = classify(path);
        return classification != null && !INTERNAL_STRUCTURE_WORLDGEN_KINDS.contains(classification.kind());
    }

    private static boolean isStructureTemplatePath(String path) {
        return path.startsWith("structures/") && path.endsWith(".nbt");
    }

    private static Classification classification(String kind, String path, String prefix, String extension) {
        String objectPath = path.substring(prefix.length(), path.length() - extension.length());
        return objectPath.isBlank() ? null : new Classification(kind, objectPath);
    }

    private static void writeRegistryIndex(
        ExportContext context,
        Map<String, Set<ResourceLocation>> registries
    ) throws Exception {
        JsonArray registryData = new JsonArray();
        int entryCount = 0;
        for (Map.Entry<String, Set<ResourceLocation>> entry : registries.entrySet()) {
            if (NESTED_STRUCTURE_REGISTRIES.contains(entry.getKey())) {
                continue;
            }
            JsonObject registry = new JsonObject();
            registry.addProperty("registry", entry.getKey());
            JsonArray values = new JsonArray();
            entry.getValue().forEach(value -> values.add(value.toString()));
            registry.addProperty("count", values.size());
            registry.add("entries", values);
            registryData.add(registry);
            entryCount += values.size();
        }
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", "mcmods-worldgen-registries/v1");
        root.addProperty("registry_count", registryData.size());
        root.addProperty("entry_count", entryCount);
        root.add("registries", registryData);
        context.writeJson("worldgen/registries.json", root);
    }

    private static void writeDataFileIndex(
        ExportContext context,
        Map<String, DataFile> files,
        String resourceSource
    ) throws Exception {
        JsonArray data = new JsonArray();
        for (DataFile file : files.values()) {
            JsonObject item = new JsonObject();
            item.addProperty("kind", file.kind());
            item.addProperty("resource_id", file.resourceId().toString());
            item.addProperty("object_id", file.objectId().toString());
            item.addProperty("path", exportPath(context, file.target()));
            item.addProperty("size_bytes", file.size());
            data.add(item);
        }
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", "mcmods-worldgen-files/v1");
        root.addProperty("resource_source", resourceSource);
        root.addProperty("remote_server_data_complete", !resourceSource.equals("installed_mod_fallback"));
        root.addProperty("count", data.size());
        root.add("files", data);
        context.writeJson("worldgen/data_files.json", root);
    }

    private static void writeDimensions(
        ExportContext context,
        Map<String, Set<ResourceLocation>> registries,
        Map<String, Map<String, JsonElement>> runtimeDefinitions,
        Map<String, DataFile> files
    ) throws Exception {
        JsonArray dimensions = objectEntries(
            context,
            indexedObjects(
                registries.get("minecraft:dimension"),
                runtimeDefinitions.get("minecraft:dimension"),
                files,
                "dimension"
            ),
            "dimension"
        );
        JsonArray dimensionTypes = objectEntries(
            context,
            indexedObjects(
                registries.get("minecraft:dimension_type"),
                runtimeDefinitions.get("minecraft:dimension_type"),
                files,
                "dimension_type"
            ),
            null
        );
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", "mcmods-dimensions/v1");
        root.addProperty("dimension_count", dimensions.size());
        root.addProperty("dimension_type_count", dimensionTypes.size());
        root.add("dimensions", dimensions);
        root.add("dimension_types", dimensionTypes);
        context.writeJson("worldgen/dimensions.json", root);
        context.report().addCount(ID, "dimensions", dimensions.size());
        context.report().addCount(ID, "dimension_types", dimensionTypes.size());
    }

    private static void writeBiomes(
        ExportContext context,
        Map<String, Set<ResourceLocation>> registries,
        Map<String, Map<String, JsonElement>> runtimeDefinitions,
        Map<String, DataFile> files
    ) throws Exception {
        JsonArray biomes = objectEntries(
            context,
            indexedObjects(
                registries.get("minecraft:worldgen/biome"),
                runtimeDefinitions.get("minecraft:worldgen/biome"),
                files,
                "worldgen/biome"
            ),
            "biome"
        );
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", "mcmods-biomes/v1");
        root.addProperty("count", biomes.size());
        root.add("biomes", biomes);
        context.writeJson("worldgen/biomes.json", root);
        context.report().addCount(ID, "biomes", biomes.size());
    }

    private static void writeEntitySpawns(ExportContext context) throws Exception {
        JsonArray biomes = new JsonArray();
        int spawnCount = 0;
        if (context.minecraft().level != null) {
            Registry<Biome> registry = context.minecraft().level.registryAccess().registryOrThrow(Registries.BIOME);
            for (Map.Entry<net.minecraft.resources.ResourceKey<Biome>, Biome> registryEntry : registry.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
                .toList()) {
                ResourceLocation biomeId = registryEntry.getKey().location();
                Biome biome = registryEntry.getValue();
                boolean selectedBiome = context.matches(biomeId);
                JsonArray spawns = new JsonArray();
                for (MobCategory category : MobCategory.values()) {
                    for (MobSpawnSettings.SpawnerData spawn : biome.getMobSettings().getMobs(category).unwrap()) {
                        ResourceLocation entityId = EntityType.getKey(spawn.type);
                        if (entityId == null) {
                            continue;
                        }
                        if (!selectedBiome && !context.matches(entityId)) {
                            continue;
                        }
                        JsonObject spawnJson = new JsonObject();
                        spawnJson.addProperty("entity", entityId.toString());
                        spawnJson.addProperty("category", category.getName());
                        spawnJson.addProperty("weight", spawn.getWeight().asInt());
                        spawnJson.addProperty("min_count", spawn.minCount);
                        spawnJson.addProperty("max_count", spawn.maxCount);
                        spawns.add(spawnJson);
                    }
                }
                if (!selectedBiome && spawns.isEmpty()) {
                    continue;
                }
                JsonObject biomeJson = baseId(biomeId);
                biomeJson.addProperty("selected_namespace", selectedBiome);
                biomeJson.addProperty("inclusion_reason", selectedBiome ? "target_biome" : "contains_target_entity_spawn");
                biomeJson.addProperty("spawn_count", spawns.size());
                biomeJson.add("spawns", spawns);
                biomes.add(biomeJson);
                spawnCount += spawns.size();
            }
        }
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", "mcmods-entity-spawns/v1");
        root.addProperty("source", context.minecraft().level == null ? "unavailable" : "runtime_biome_registry");
        root.addProperty("biome_count", biomes.size());
        root.addProperty("spawn_count", spawnCount);
        root.add("biomes", biomes);
        context.writeJson("worldgen/entity_spawns.json", root);
        context.report().addCount(ID, "entity_spawn_biomes", biomes.size());
        context.report().addCount(ID, "entity_spawns", spawnCount);
    }

    private static void writeNaturalGeneration(
        ExportContext context,
        Map<String, Set<ResourceLocation>> registries,
        Map<String, Map<String, JsonElement>> runtimeDefinitions,
        Map<String, DataFile> files
    ) throws Exception {
        Map<String, String> registryKinds = new TreeMap<>();
        registries.keySet().forEach(registryId -> {
            if (registryId.startsWith("minecraft:worldgen/")) {
                String category = registryId.substring("minecraft:worldgen/".length());
                if (!category.equals("biome")
                    && !category.equals("structure")
                    && !isNestedStructureCategory(category)) {
                    registryKinds.put(registryId, "worldgen/" + category);
                }
            } else if (EXTENSION_WORLDGEN_REGISTRIES.containsKey(registryId)) {
                registryKinds.put(registryId, EXTENSION_WORLDGEN_REGISTRIES.get(registryId));
            }
        });
        files.values().stream().map(DataFile::kind).forEach(kind -> {
            if (kind.startsWith("worldgen/")
                && !kind.equals("worldgen/biome")
                && !kind.equals("worldgen/structure")
                && !isNestedStructureCategory(kind.substring("worldgen/".length()))) {
                registryKinds.putIfAbsent("minecraft:" + kind, kind);
            } else if (kind.startsWith("worldgen_extension/")) {
                String registryPath = kind.substring("worldgen_extension/".length());
                int slash = registryPath.indexOf('/');
                if (slash > 0) {
                    registryKinds.putIfAbsent(
                        registryPath.substring(0, slash) + ":" + registryPath.substring(slash + 1),
                        kind
                    );
                }
            }
        });

        JsonArray categoryData = new JsonArray();
        int entryCount = 0;
        for (Map.Entry<String, String> registryKind : registryKinds.entrySet()) {
            String registryId = registryKind.getKey();
            String kind = registryKind.getValue();
            JsonArray entries = objectEntries(
                context,
                indexedObjects(registries.get(registryId), runtimeDefinitions.get(registryId), files, kind),
                null
            );
            JsonObject categoryJson = new JsonObject();
            categoryJson.addProperty("category", kind.replace("worldgen_extension/", ""));
            categoryJson.addProperty("registry", registryId);
            categoryJson.addProperty("count", entries.size());
            categoryJson.add("entries", entries);
            categoryData.add(categoryJson);
            entryCount += entries.size();
        }
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", "mcmods-natural-generation/v1");
        root.addProperty("category_count", categoryData.size());
        root.addProperty("entry_count", entryCount);
        root.add("categories", categoryData);
        context.writeJson("worldgen/natural_generation.json", root);
        context.report().addCount(ID, "natural_generation_categories", categoryData.size());
        context.report().addCount(ID, "natural_generation_entries", entryCount);
    }

    private static boolean isNestedStructureCategory(String category) {
        return category.equals("structure_set")
            || category.equals("template_pool")
            || category.equals("processor_list");
    }

    private static void writeStructures(
        ExportContext context,
        Map<String, Set<ResourceLocation>> registries,
        Map<String, Map<String, JsonElement>> runtimeDefinitions,
        Map<String, DataFile> files,
        int omittedStructureTemplates
    ) throws Exception {
        Map<String, IndexedObject> structures = indexedObjects(
            registries.get("minecraft:worldgen/structure"),
            runtimeDefinitions.get("minecraft:worldgen/structure"),
            files,
            "worldgen/structure"
        );
        Map<String, JsonArray> structureSetReferences = structureSetReferences(
            context,
            registries,
            runtimeDefinitions,
            files
        );

        JsonArray data = new JsonArray();
        int definitionCount = 0;
        int structureSetReferenceCount = 0;
        for (IndexedObject entry : structures.values()) {
            JsonObject json = baseId(entry.id);
            json.addProperty("structure_id", entry.id.toString());
            json.addProperty("catalog_kind", "top_level_structure");
            json.addProperty("runtime_registered", entry.runtime);
            json.addProperty("internal_templates_exported", false);
            JsonElement definition = effectiveDefinition(context, entry);
            if (entry.file != null) {
                json.addProperty("definition_path", exportPath(context, entry.file.target()));
            }
            if (definition != null) {
                json.addProperty(
                    "definition_source",
                    entry.runtimeDefinition != null ? "runtime_registry" : "effective_data_resource"
                );
                json.add("definition", definition);
                JsonObject summary = summarizeStructureDefinition(definition);
                summary.entrySet().forEach(field -> json.add(field.getKey(), field.getValue()));
                definitionCount++;
            }
            JsonArray references = structureSetReferences.getOrDefault(entry.id.toString(), new JsonArray());
            JsonArray structureSetIds = new JsonArray();
            Set<String> distinctStructureSetIds = new TreeSet<>();
            for (JsonElement reference : references) {
                distinctStructureSetIds.add(
                    reference.getAsJsonObject().get("structure_set_id").getAsString()
                );
            }
            distinctStructureSetIds.forEach(structureSetIds::add);
            json.add("structure_set_ids", structureSetIds);
            json.add("structure_set_references", references);
            structureSetReferenceCount += references.size();
            data.add(json);
        }
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", "mcmods-structures/v2");
        root.addProperty("export_mode", "catalog_only");
        root.addProperty("count", data.size());
        root.addProperty("definition_count", definitionCount);
        root.addProperty("structure_set_reference_count", structureSetReferenceCount);
        root.addProperty("internal_templates_exported", false);
        root.addProperty("omitted_internal_template_count", omittedStructureTemplates);
        root.add("structures", data);
        context.writeJson("worldgen/structures.json", root);
        context.report().addCount(ID, "structure_definitions", definitionCount);
        context.report().addCount(ID, "structure_set_references", structureSetReferenceCount);
    }

    private static Map<String, JsonArray> structureSetReferences(
        ExportContext context,
        Map<String, Set<ResourceLocation>> registries,
        Map<String, Map<String, JsonElement>> runtimeDefinitions,
        Map<String, DataFile> files
    ) {
        Map<String, JsonArray> references = new TreeMap<>();
        Map<String, IndexedObject> structureSets = indexedObjects(
            registries.get("minecraft:worldgen/structure_set"),
            runtimeDefinitions.get("minecraft:worldgen/structure_set"),
            files,
            "worldgen/structure_set"
        );
        for (IndexedObject structureSet : structureSets.values()) {
            JsonElement definition = effectiveDefinition(context, structureSet);
            if (definition != null) {
                addStructureSetReferences(references, structureSet.id, definition);
            }
        }
        return references;
    }

    static void addStructureSetReferences(
        Map<String, JsonArray> target,
        ResourceLocation structureSetId,
        JsonElement definition
    ) {
        if (!definition.isJsonObject()) {
            return;
        }
        JsonObject object = definition.getAsJsonObject();
        if (!object.has("structures") || !object.get("structures").isJsonArray()) {
            return;
        }
        JsonElement placement = object.get("placement");
        for (JsonElement element : object.getAsJsonArray("structures")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject weighted = element.getAsJsonObject();
            String structureId = resourceIdString(weighted.get("structure"));
            if (structureId == null) {
                continue;
            }
            JsonObject reference = new JsonObject();
            reference.addProperty("structure_set_id", structureSetId.toString());
            if (weighted.has("weight") && weighted.get("weight").isJsonPrimitive()) {
                reference.add("weight", weighted.get("weight"));
            }
            if (placement != null && placement.isJsonObject()) {
                reference.add("placement", placement);
                JsonObject placementObject = placement.getAsJsonObject();
                if (placementObject.has("type") && placementObject.get("type").isJsonPrimitive()) {
                    reference.add("placement_type", placementObject.get("type"));
                }
            }
            target.computeIfAbsent(structureId, ignored -> new JsonArray()).add(reference);
        }
    }

    private static String resourceIdString(JsonElement element) {
        if (element == null) {
            return null;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return element.getAsString();
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("id") && object.get("id").isJsonPrimitive()) {
                return object.get("id").getAsString();
            }
        }
        return null;
    }

    static JsonObject summarizeStructureDefinition(JsonElement definition) {
        JsonObject summary = new JsonObject();
        if (!definition.isJsonObject()) {
            return summary;
        }
        JsonObject object = definition.getAsJsonObject();
        copyField(object, summary, "type", "structure_type");
        copyField(object, summary, "biomes", "biomes");
        copyField(object, summary, "step", "generation_step");
        copyField(object, summary, "terrain_adaptation", "terrain_adaptation");
        copyField(object, summary, "spawn_overrides", "spawn_overrides");
        copyField(object, summary, "start_pool", "start_pool");
        copyField(object, summary, "start_jigsaw_name", "start_jigsaw_name");
        copyField(object, summary, "size", "jigsaw_size");
        copyField(object, summary, "start_height", "start_height");
        copyField(object, summary, "max_distance_from_center", "max_distance_from_center");
        copyField(object, summary, "use_expansion_hack", "use_expansion_hack");
        copyField(object, summary, "project_start_to_heightmap", "project_start_to_heightmap");
        copyField(object, summary, "liquid_settings", "liquid_settings");
        return summary;
    }

    private static void copyField(JsonObject source, JsonObject target, String sourceName, String targetName) {
        if (source.has(sourceName)) {
            target.add(targetName, source.get(sourceName));
        }
    }

    private static JsonElement effectiveDefinition(ExportContext context, IndexedObject entry) {
        if (entry.runtimeDefinition != null) {
            return entry.runtimeDefinition;
        }
        return entry.file == null ? null : readJson(context, entry.file);
    }

    private static void writeLootTables(ExportContext context, Map<String, DataFile> files) throws Exception {
        Set<ResourceLocation> runtimeTables = null;
        Set<ResourceLocation> runtimePredicates = null;
        Set<ResourceLocation> runtimeItemModifiers = null;
        if (context.minecraft().getSingleplayerServer() != null) {
            Set<ResourceLocation> includedTables = new TreeSet<>(Comparator.comparing(ResourceLocation::toString));
            files.values().stream()
                .filter(file -> file.kind().equals("loot_table"))
                .map(DataFile::objectId)
                .forEach(includedTables::add);
            runtimeTables = filteredLootKeys(context, LootDataType.TABLE, includedTables);
            runtimePredicates = filteredLootKeys(context, LootDataType.PREDICATE, Set.of());
            runtimeItemModifiers = filteredLootKeys(context, LootDataType.MODIFIER, Set.of());
        }

        JsonArray data = new JsonArray();
        Map<String, Integer> categoryCounts = new TreeMap<>();
        int dependencyCount = 0;
        Map<String, IndexedObject> tables = indexedObjects(runtimeTables, null, files, "loot_table");
        Map<String, JsonObject> tableDefinitions = new TreeMap<>();
        for (IndexedObject entry : tables.values()) {
            if (entry.file == null) {
                continue;
            }
            JsonElement definition = readJson(context, entry.file);
            if (definition != null && definition.isJsonObject()) {
                tableDefinitions.put(entry.id.toString(), definition.getAsJsonObject());
            }
        }
        for (IndexedObject entry : tables.values()) {
            JsonObject json = baseId(entry.id);
            String category = lootTableCategory(entry.id);
            categoryCounts.merge(category, 1, Integer::sum);
            boolean selectedNamespace = context.matches(entry.id);
            if (!selectedNamespace) {
                dependencyCount++;
            }
            json.addProperty("category", category);
            json.addProperty("selected_namespace", selectedNamespace);
            json.addProperty("inclusion_reason", selectedNamespace ? "target_namespace" : "external_namespace");
            json.addProperty("runtime_loaded", entry.runtime);
            if (entry.file != null) {
                JsonElement definition = tableDefinitions.get(entry.id.toString());
                json.addProperty("data_path", exportPath(context, entry.file.target()));
                if (definition != null) {
                    json.add("definition", definition);
                    if (definition.isJsonObject()) {
                        addLootSummary(json, definition.getAsJsonObject(), tableDefinitions);
                    }
                }
            }
            data.add(json);
        }
        JsonArray predicates = objectEntries(
            context,
            indexedObjects(runtimePredicates, null, files, "loot_predicate"),
            null
        );
        JsonArray itemModifiers = objectEntries(
            context,
            indexedObjects(runtimeItemModifiers, null, files, "loot_item_modifier"),
            null
        );
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", "mcmods-loot-tables/v1");
        root.addProperty("count", data.size());
        root.addProperty("dependency_count", dependencyCount);
        root.addProperty("predicate_count", predicates.size());
        root.addProperty("item_modifier_count", itemModifiers.size());
        root.add("loot_tables", data);
        JsonObject counts = new JsonObject();
        categoryCounts.forEach(counts::addProperty);
        root.add("counts_by_category", counts);
        root.add("predicates", predicates);
        root.add("item_modifiers", itemModifiers);
        context.writeJson("worldgen/loot_tables.json", root);
        context.report().addCount(ID, "loot_predicates", predicates.size());
        context.report().addCount(ID, "loot_item_modifiers", itemModifiers.size());
    }

    private static Set<ResourceLocation> filteredLootKeys(
        ExportContext context,
        LootDataType<?> type,
        Set<ResourceLocation> additionallyIncluded
    ) {
        TreeSet<ResourceLocation> result = new TreeSet<>(Comparator.comparing(ResourceLocation::toString));
        context.minecraft().getSingleplayerServer().reloadableRegistries().getKeys(type.registryKey()).stream()
            .filter(id -> context.matches(id) || additionallyIncluded.contains(id))
            .forEach(result::add);
        return result;
    }

    static String lootTableCategory(ResourceLocation id) {
        String path = id.getPath();
        if (path.equals("gameplay/fishing") || path.startsWith("gameplay/fishing/")) {
            return "fishing";
        }
        String prefix = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
        return switch (prefix) {
            case "blocks" -> "block";
            case "chests" -> "chest";
            case "entities" -> "entity";
            default -> prefix.isBlank() ? "custom" : prefix;
        };
    }

    private static Map<String, IndexedObject> indexedObjects(
        Set<ResourceLocation> registered,
        Map<String, JsonElement> runtimeDefinitions,
        Map<String, DataFile> files,
        String kind
    ) {
        Map<String, IndexedObject> result = new TreeMap<>();
        if (registered != null) {
            registered.forEach(id -> result.computeIfAbsent(id.toString(), ignored -> new IndexedObject(id)).runtime = true);
        }
        if (runtimeDefinitions != null) {
            runtimeDefinitions.forEach((id, definition) -> {
                ResourceLocation resourceId = ResourceLocation.parse(id);
                IndexedObject entry = result.computeIfAbsent(id, ignored -> new IndexedObject(resourceId));
                entry.runtime = true;
                entry.runtimeDefinition = definition;
            });
        }
        for (DataFile file : files.values()) {
            if (file.kind().equals(kind)) {
                IndexedObject entry = result.computeIfAbsent(
                    file.objectId().toString(),
                    ignored -> new IndexedObject(file.objectId())
                );
                entry.file = file;
            }
        }
        return result;
    }

    private static JsonArray objectEntries(
        ExportContext context,
        Map<String, IndexedObject> entries,
        String translationPrefix
    ) {
        JsonArray data = new JsonArray();
        for (IndexedObject entry : entries.values()) {
            JsonObject json = baseId(entry.id);
            json.addProperty("runtime_registered", entry.runtime);
            if (entry.runtimeDefinition != null) {
                json.add("runtime_definition", entry.runtimeDefinition);
            }
            if (translationPrefix != null) {
                String key = translationPrefix + "." + entry.id.getNamespace() + "." + entry.id.getPath().replace('/', '.');
                json.addProperty("translation_key", key);
                json.add("names", context.names(key));
            }
            if (entry.file != null) {
                addJsonDefinition(context, json, "definition", entry.file);
            }
            data.add(json);
        }
        return data;
    }

    private static void addJsonDefinition(
        ExportContext context,
        JsonObject target,
        String field,
        DataFile file
    ) {
        target.addProperty(field + "_path", exportPath(context, file.target()));
        JsonElement definition = readJson(context, file);
        if (definition != null) {
            target.add(field, definition);
        }
    }

    private static JsonElement readJson(ExportContext context, DataFile file) {
        try (Reader reader = Files.newBufferedReader(file.target(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        } catch (Exception exception) {
            context.report().error(ID, "json:" + file.resourceId(), exception);
            return null;
        }
    }

    private static void addLootSummary(
        JsonObject target,
        JsonObject definition,
        Map<String, JsonObject> allDefinitions
    ) {
        if (definition.has("type") && definition.get("type").isJsonPrimitive()) {
            target.addProperty("type", definition.get("type").getAsString());
        }
        if (definition.has("random_sequence") && definition.get("random_sequence").isJsonPrimitive()) {
            target.addProperty("random_sequence", definition.get("random_sequence").getAsString());
        }
        JsonArray pools = definition.has("pools") && definition.get("pools").isJsonArray()
            ? definition.getAsJsonArray("pools")
            : new JsonArray();
        int topLevelEntries = 0;
        TreeSet<String> referencedTables = new TreeSet<>();
        for (JsonElement poolElement : pools) {
            if (!poolElement.isJsonObject()) {
                continue;
            }
            JsonObject pool = poolElement.getAsJsonObject();
            if (pool.has("entries") && pool.get("entries").isJsonArray()) {
                JsonArray entries = pool.getAsJsonArray("entries");
                topLevelEntries += entries.size();
                collectReferencedLootTables(entries, referencedTables);
            }
        }
        target.addProperty("pool_count", pools.size());
        target.addProperty("top_level_entry_count", topLevelEntries);
        JsonArray references = new JsonArray();
        referencedTables.forEach(references::add);
        target.add("referenced_loot_tables", references);

        LootCandidates candidates = new LootCandidates();
        collectLootCandidates(definition, allDefinitions, candidates, new TreeSet<>());
        JsonArray itemIds = new JsonArray();
        candidates.items.forEach(itemIds::add);
        JsonArray itemTags = new JsonArray();
        candidates.tags.forEach(itemTags::add);
        target.addProperty("possible_item_count", itemIds.size());
        target.add("possible_item_ids", itemIds);
        target.add("possible_item_tags", itemTags);
        target.addProperty("possible_items_complete", false);
        target.addProperty("possible_items_analysis", "static_candidates_including_nested_tables");
    }

    private static void collectLootCandidates(
        JsonElement element,
        Map<String, JsonObject> definitions,
        LootCandidates target,
        Set<String> visiting
    ) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectLootCandidates(child, definitions, target, visiting);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        String type = object.has("type") && object.get("type").isJsonPrimitive()
            ? object.get("type").getAsString()
            : "";
        String name = object.has("name") && object.get("name").isJsonPrimitive()
            ? object.get("name").getAsString()
            : "";
        if ((type.equals("minecraft:item") || type.endsWith(":item")) && !name.isBlank()) {
            target.items.add(name);
        } else if ((type.equals("minecraft:tag") || type.endsWith(":tag")) && !name.isBlank()) {
            target.tags.add(name);
        } else if ((type.equals("minecraft:loot_table") || type.endsWith(":loot_table")) && !name.isBlank()) {
            JsonObject nested = definitions.get(name);
            if (nested != null && visiting.add(name)) {
                collectLootCandidates(nested, definitions, target, visiting);
                visiting.remove(name);
            }
        }
        object.entrySet().forEach(entry -> collectLootCandidates(entry.getValue(), definitions, target, visiting));
    }

    private static void collectReferencedLootTables(JsonArray entries, Set<String> target) {
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String type = entry.has("type") && entry.get("type").isJsonPrimitive()
                ? entry.get("type").getAsString()
                : "";
            if ((type.equals("minecraft:loot_table") || type.endsWith(":loot_table"))
                && entry.has("name")
                && entry.get("name").isJsonPrimitive()) {
                target.add(entry.get("name").getAsString());
            }
            if (entry.has("children") && entry.get("children").isJsonArray()) {
                collectReferencedLootTables(entry.getAsJsonArray("children"), target);
            }
        }
    }

    private static void collectReferencedLootTables(JsonElement element, Set<ResourceLocation> target) {
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectReferencedLootTables(child, target);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        String type = object.has("type") && object.get("type").isJsonPrimitive()
            ? object.get("type").getAsString()
            : "";
        if ((type.equals("minecraft:loot_table") || type.endsWith(":loot_table"))
            && object.has("name")
            && object.get("name").isJsonPrimitive()) {
            try {
                target.add(ResourceLocation.parse(object.get("name").getAsString()));
            } catch (RuntimeException ignored) {
            }
        }
        object.entrySet().forEach(entry -> collectReferencedLootTables(entry.getValue(), target));
    }

    private static JsonObject baseId(ResourceLocation id) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id.toString());
        json.addProperty("namespace", id.getNamespace());
        json.addProperty("path", id.getPath());
        return json;
    }

    private static String exportPath(ExportContext context, Path path) {
        return context.root().relativize(path).toString().replace('\\', '/');
    }

    private static int countKind(Map<String, DataFile> files, String kind) {
        return (int) files.values().stream().filter(file -> file.kind().equals(kind)).count();
    }

    private record Classification(String kind, String objectPath) {
    }

    private record DataFile(
        String kind,
        ResourceLocation resourceId,
        ResourceLocation objectId,
        Path target,
        long size
    ) {
    }

    private static final class IndexedObject {
        private final ResourceLocation id;
        private boolean runtime;
        private JsonElement runtimeDefinition;
        private DataFile file;

        private IndexedObject(ResourceLocation id) {
            this.id = id;
        }
    }

    private static final class LootCandidates {
        private final Set<String> items = new TreeSet<>();
        private final Set<String> tags = new TreeSet<>();
    }
}
