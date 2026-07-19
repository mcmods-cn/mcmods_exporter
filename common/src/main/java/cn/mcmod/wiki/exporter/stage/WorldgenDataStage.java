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
    private static final List<String> GENERATION_STEPS = List.of(
        "raw_generation",
        "lakes",
        "local_modifications",
        "underground_structures",
        "surface_structures",
        "strongholds",
        "underground_ores",
        "underground_decoration",
        "fluid_springs",
        "vegetal_decoration",
        "top_layer_modification"
    );
    private static final Set<String> NATURAL_GENERATION_RESOLUTION_REGISTRIES = Set.of(
        "minecraft:worldgen/biome",
        "minecraft:worldgen/configured_feature",
        "minecraft:worldgen/placed_feature"
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
        Map<String, Map<String, JsonElement>> naturalGenerationRuntimeDefinitions =
            collectNaturalGenerationRuntimeDefinitions(context);
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
        writeNaturalGeneration(
            context,
            registries,
            runtimeDefinitions,
            naturalGenerationRuntimeDefinitions,
            files
        );
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

    private static Map<String, Map<String, JsonElement>> collectNaturalGenerationRuntimeDefinitions(
        ExportContext context
    ) {
        if (context.minecraft().getSingleplayerServer() == null) {
            return Map.of();
        }
        RegistryAccess access = context.minecraft().getSingleplayerServer().registryAccess();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, access);
        Map<String, Map<String, JsonElement>> result = new TreeMap<>();
        for (RegistryDataLoader.RegistryData<?> data : RegistryDataLoader.WORLDGEN_REGISTRIES) {
            String registryId = data.key().location().toString();
            if (NATURAL_GENERATION_RESOLUTION_REGISTRIES.contains(registryId)) {
                Map<String, JsonElement> definitions = encodeAllRegistryEntriesUnknown(
                    context,
                    access,
                    ops,
                    data
                );
                if (!definitions.isEmpty()) {
                    result.put(registryId, definitions);
                }
            }
        }
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Map<String, JsonElement> encodeAllRegistryEntriesUnknown(
        ExportContext context,
        RegistryAccess access,
        RegistryOps<JsonElement> ops,
        RegistryDataLoader.RegistryData<?> data
    ) {
        return encodeAllRegistryEntries(context, access, ops, (RegistryDataLoader.RegistryData) data);
    }

    private static <T> Map<String, JsonElement> encodeAllRegistryEntries(
        ExportContext context,
        RegistryAccess access,
        RegistryOps<JsonElement> ops,
        RegistryDataLoader.RegistryData<T> data
    ) {
        Registry<T> registry = access.registry(data.key()).orElse(null);
        if (registry == null) {
            return Map.of();
        }
        Map<String, JsonElement> definitions = new TreeMap<>();
        registry.keySet().stream()
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
                        "runtime_biome_definition:" + id,
                        new IllegalStateException(error[0])
                    );
                }
            });
        return definitions;
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
            DataFile file = describeFile(
                resourceId,
                target,
                sourceModId(context, resourceId, resource.sourcePackId())
            );
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
                            DataFile file = describeFile(
                                resourceId,
                                target,
                                selectedOwner ? mod.getModId() : resourceId.getNamespace()
                            );
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

    private static String sourceModId(
        ExportContext context,
        ResourceLocation resourceId,
        String sourcePackId
    ) {
        String normalizedPackId = sourcePackId.toLowerCase(java.util.Locale.ROOT);
        for (String namespace : context.config().namespaces()) {
            int offset = -1;
            while ((offset = normalizedPackId.indexOf(namespace, offset + 1)) >= 0) {
                int end = offset + namespace.length();
                boolean leftBoundary = offset == 0 || !isNamespaceCharacter(normalizedPackId.charAt(offset - 1));
                boolean rightBoundary = end == normalizedPackId.length()
                    || !isNamespaceCharacter(normalizedPackId.charAt(end));
                if (leftBoundary && rightBoundary) {
                    return namespace;
                }
            }
        }
        return resourceId.getNamespace();
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

    private static DataFile describeFile(
        ResourceLocation resourceId,
        Path target,
        String sourceModId
    ) throws Exception {
        Classification classification = classify(resourceId.getPath());
        if (classification == null) {
            return null;
        }
        ResourceLocation objectId = ResourceLocation.fromNamespaceAndPath(resourceId.getNamespace(), classification.objectPath());
        return new DataFile(
            classification.kind(),
            resourceId,
            objectId,
            target,
            Files.size(target),
            sourceModId
        );
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
            item.addProperty("source_mod_id", file.sourceModId());
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
        Map<String, Map<String, JsonElement>> naturalGenerationRuntimeDefinitions,
        Map<String, DataFile> files
    ) throws Exception {
        Map<String, IndexedObject> placedFeatures = indexedObjects(
            registries.get("minecraft:worldgen/placed_feature"),
            runtimeDefinitions.get("minecraft:worldgen/placed_feature"),
            files,
            "worldgen/placed_feature"
        );
        Map<String, IndexedObject> configuredFeatures = indexedObjects(
            registries.get("minecraft:worldgen/configured_feature"),
            runtimeDefinitions.get("minecraft:worldgen/configured_feature"),
            files,
            "worldgen/configured_feature"
        );
        Map<String, IndexedObject> resolvablePlacedFeatures = indexedObjects(
            null,
            naturalGenerationRuntimeDefinitions.get("minecraft:worldgen/placed_feature"),
            files,
            "worldgen/placed_feature"
        );
        Map<String, IndexedObject> resolvableConfiguredFeatures = indexedObjects(
            null,
            naturalGenerationRuntimeDefinitions.get("minecraft:worldgen/configured_feature"),
            files,
            "worldgen/configured_feature"
        );
        Map<String, IndexedObject> configuredCarvers = indexedObjects(
            registries.get("minecraft:worldgen/configured_carver"),
            runtimeDefinitions.get("minecraft:worldgen/configured_carver"),
            files,
            "worldgen/configured_carver"
        );

        Map<String, JsonElement> biomeDefinitions = new TreeMap<>();
        Map<String, JsonElement> allRuntimeBiomes = naturalGenerationRuntimeDefinitions.get(
            "minecraft:worldgen/biome"
        );
        if (allRuntimeBiomes != null) {
            biomeDefinitions.putAll(allRuntimeBiomes);
        }
        Map<String, IndexedObject> selectedBiomes = indexedObjects(
            registries.get("minecraft:worldgen/biome"),
            runtimeDefinitions.get("minecraft:worldgen/biome"),
            files,
            "worldgen/biome"
        );
        for (IndexedObject biome : selectedBiomes.values()) {
            JsonElement definition = effectiveDefinition(context, biome);
            if (definition != null) {
                biomeDefinitions.put(biome.id.toString(), definition);
            }
        }

        Map<String, GenerationUse> featureUses = new TreeMap<>();
        Map<String, GenerationUse> carverUses = new TreeMap<>();
        addBiomeGenerationUses(biomeDefinitions, featureUses, carverUses);
        int consumedBiomeModifierCount = addBiomeModifierUses(
            context,
            registries,
            runtimeDefinitions,
            files,
            featureUses
        );

        JsonArray entries = new JsonArray();
        Map<String, Integer> categoryCounts = new TreeMap<>();
        Set<String> referencedConfiguredFeatures = new TreeSet<>();
        int completeCount = 0;
        int partialCount = 0;

        for (IndexedObject placedFeature : placedFeatures.values()) {
            JsonElement placedDefinition = effectiveDefinition(context, placedFeature);
            FeatureSummary summary = resolvePlacedFeature(
                placedDefinition,
                resolvablePlacedFeatures,
                resolvableConfiguredFeatures,
                new TreeSet<>()
            );
            referencedConfiguredFeatures.addAll(summary.configuredFeatureIds);
            String category = featureCategory(summary.featureType);
            categoryCounts.merge(category, 1, Integer::sum);

            JsonObject json = baseId(placedFeature.id);
            json.addProperty("generation_id", placedFeature.id.toString());
            json.addProperty("entry_kind", "placed_feature");
            addSourceMod(json, placedFeature);
            json.addProperty("category", category);
            if (summary.featureType != null) {
                json.addProperty("feature_type", summary.featureType);
            }
            json.addProperty("runtime_registered", placedFeature.runtime);
            addDefinitionPaths(
                context,
                json,
                placedFeature,
                summary.configuredFeatureIds,
                resolvableConfiguredFeatures
            );
            addFeatureSummary(context, json, summary);
            addGenerationUse(json, featureUses.get(placedFeature.id.toString()));

            String normalizationStatus = placedDefinition != null && !summary.unresolved
                ? "complete"
                : "partial";
            json.addProperty("normalization_status", normalizationStatus);
            if (normalizationStatus.equals("complete")) {
                completeCount++;
            } else {
                partialCount++;
            }
            entries.add(json);
        }

        for (IndexedObject carver : configuredCarvers.values()) {
            JsonElement definition = effectiveDefinition(context, carver);
            JsonObject json = baseId(carver.id);
            json.addProperty("generation_id", carver.id.toString());
            json.addProperty("entry_kind", "configured_carver");
            addSourceMod(json, carver);
            json.addProperty("category", "carver");
            json.addProperty("runtime_registered", carver.runtime);
            if (carver.file != null) {
                json.addProperty("definition_path", exportPath(context, carver.file.target()));
            }
            summarizeCarverDefinition(json, definition);
            addGenerationUse(json, carverUses.get(carver.id.toString()));
            String normalizationStatus = definition == null ? "partial" : "complete";
            json.addProperty("normalization_status", normalizationStatus);
            if (normalizationStatus.equals("complete")) {
                completeCount++;
            } else {
                partialCount++;
            }
            categoryCounts.merge("carver", 1, Integer::sum);
            entries.add(json);
        }

        int unreferencedConfiguredFeatureCount = (int) configuredFeatures.keySet().stream()
            .filter(id -> !referencedConfiguredFeatures.contains(id))
            .count();
        JsonArray technicalCategories = technicalNaturalGenerationCategories(registries, files);
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", "mcmods-natural-generation/v2");
        root.addProperty("export_mode", "normalized_catalog");
        root.addProperty("entry_count", entries.size());
        root.addProperty("placed_feature_count", placedFeatures.size());
        root.addProperty("configured_carver_count", configuredCarvers.size());
        root.addProperty("complete_count", completeCount);
        root.addProperty("partial_count", partialCount);
        root.addProperty("consumed_biome_modifier_count", consumedBiomeModifierCount);
        root.addProperty("unreferenced_configured_feature_count", unreferencedConfiguredFeatureCount);
        root.addProperty("technical_registry_category_count", technicalCategories.size());
        root.add("technical_registry_categories_omitted_from_catalog", technicalCategories);
        JsonObject counts = new JsonObject();
        categoryCounts.forEach(counts::addProperty);
        root.add("counts_by_category", counts);
        root.add("entries", entries);
        context.writeJson("worldgen/natural_generation.json", root);
        context.report().addCount(ID, "natural_generation_entries", entries.size());
        context.report().addCount(ID, "natural_generation_complete", completeCount);
        context.report().addCount(ID, "natural_generation_partial", partialCount);
        context.report().addCount(ID, "unreferenced_configured_features", unreferencedConfiguredFeatureCount);
        context.report().addCount(ID, "consumed_biome_modifiers", consumedBiomeModifierCount);
    }

    static JsonObject summarizePlacedFeature(JsonElement placedDefinition, JsonElement configuredDefinition) {
        FeatureSummary summary = new FeatureSummary();
        resolveConfiguredFeatureElement(
            configuredDefinition,
            Map.of(),
            Map.of(),
            new TreeSet<>(),
            summary
        );
        if (placedDefinition != null && placedDefinition.isJsonObject()) {
            JsonObject placed = placedDefinition.getAsJsonObject();
            String configuredId = resourceIdString(placed.get("feature"));
            if (configuredId != null) {
                summary.configuredFeatureIds.add(configuredId);
            }
            summary.placement = normalizePlacement(placed.get("placement"));
        }
        JsonObject result = new JsonObject();
        if (summary.featureType != null) {
            result.addProperty("feature_type", summary.featureType);
        }
        result.addProperty("category", featureCategory(summary.featureType));
        addFeatureSummary(null, result, summary);
        return result;
    }

    private static FeatureSummary resolvePlacedFeature(
        JsonElement placedDefinition,
        Map<String, IndexedObject> placedFeatures,
        Map<String, IndexedObject> configuredFeatures,
        Set<String> visiting
    ) {
        FeatureSummary summary = new FeatureSummary();
        if (placedDefinition == null || !placedDefinition.isJsonObject()) {
            summary.unresolved = true;
            return summary;
        }
        JsonObject placed = placedDefinition.getAsJsonObject();
        summary.placement = normalizePlacement(placed.get("placement"));
        JsonElement feature = placed.get("feature");
        String configuredId = resourceIdString(feature);
        if (configuredId != null) {
            summary.configuredFeatureIds.add(configuredId);
            resolveConfiguredFeatureId(
                configuredId,
                placedFeatures,
                configuredFeatures,
                visiting,
                summary
            );
        } else {
            resolveConfiguredFeatureElement(feature, placedFeatures, configuredFeatures, visiting, summary);
        }
        return summary;
    }

    private static void resolveConfiguredFeatureId(
        String id,
        Map<String, IndexedObject> placedFeatures,
        Map<String, IndexedObject> configuredFeatures,
        Set<String> visiting,
        FeatureSummary target
    ) {
        String visitKey = "configured:" + id;
        if (!visiting.add(visitKey)) {
            return;
        }
        IndexedObject configured = configuredFeatures.get(id);
        if (configured == null) {
            target.unresolved = true;
            visiting.remove(visitKey);
            return;
        }
        resolveConfiguredFeatureElement(
            effectiveDefinition(configured),
            placedFeatures,
            configuredFeatures,
            visiting,
            target
        );
        visiting.remove(visitKey);
    }

    private static JsonElement effectiveDefinition(IndexedObject entry) {
        if (entry.runtimeDefinition != null) {
            return entry.runtimeDefinition;
        }
        if (entry.file == null) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(entry.file.target(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void resolveConfiguredFeatureElement(
        JsonElement definition,
        Map<String, IndexedObject> placedFeatures,
        Map<String, IndexedObject> configuredFeatures,
        Set<String> visiting,
        FeatureSummary target
    ) {
        if (definition == null || !definition.isJsonObject()) {
            target.unresolved = true;
            return;
        }
        JsonObject object = definition.getAsJsonObject();
        String type = stringValue(object.get("type"));
        if (target.featureType == null && type != null) {
            target.featureType = type;
        }
        JsonElement configElement = object.has("config") ? object.get("config") : object;
        if (!configElement.isJsonObject()) {
            return;
        }
        JsonObject config = configElement.getAsJsonObject();
        collectConfiguredFeatureBlocks(config, target);
        if (target.size == null && config.has("size")) {
            target.size = config.get("size");
        }
        if (target.discardChance == null && config.has("discard_chance_on_air_exposure")) {
            target.discardChance = config.get("discard_chance_on_air_exposure");
        }
        resolveNestedFeatureReferences(
            config,
            placedFeatures,
            configuredFeatures,
            visiting,
            target
        );
    }

    private static void resolveNestedFeatureReferences(
        JsonElement element,
        Map<String, IndexedObject> placedFeatures,
        Map<String, IndexedObject> configuredFeatures,
        Set<String> visiting,
        FeatureSummary target
    ) {
        if (element == null || element.isJsonNull() || element.isJsonPrimitive()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                resolveNestedFeatureReferences(child, placedFeatures, configuredFeatures, visiting, target);
            }
            return;
        }
        JsonObject object = element.getAsJsonObject();
        for (Map.Entry<String, JsonElement> field : object.entrySet()) {
            if (isFeatureReferenceField(field.getKey())) {
                resolveFeatureReference(
                    field.getValue(),
                    placedFeatures,
                    configuredFeatures,
                    visiting,
                    target
                );
            } else {
                resolveNestedFeatureReferences(
                    field.getValue(),
                    placedFeatures,
                    configuredFeatures,
                    visiting,
                    target
                );
            }
        }
    }

    private static boolean isFeatureReferenceField(String name) {
        return name.equals("feature") || name.equals("features") || name.endsWith("_feature");
    }

    private static void resolveFeatureReference(
        JsonElement reference,
        Map<String, IndexedObject> placedFeatures,
        Map<String, IndexedObject> configuredFeatures,
        Set<String> visiting,
        FeatureSummary target
    ) {
        if (reference == null || reference.isJsonNull()) {
            return;
        }
        if (reference.isJsonArray()) {
            for (JsonElement child : reference.getAsJsonArray()) {
                resolveFeatureReference(child, placedFeatures, configuredFeatures, visiting, target);
            }
            return;
        }
        String referenceId = resourceIdString(reference);
        if (referenceId != null) {
            target.referencedFeatureIds.add(referenceId);
            IndexedObject placed = placedFeatures.get(referenceId);
            if (placed != null) {
                String visitKey = "placed:" + referenceId;
                if (visiting.add(visitKey)) {
                    target.merge(resolvePlacedFeature(
                        effectiveDefinition(placed),
                        placedFeatures,
                        configuredFeatures,
                        visiting
                    ));
                    visiting.remove(visitKey);
                }
                return;
            }
            if (configuredFeatures.containsKey(referenceId)) {
                target.configuredFeatureIds.add(referenceId);
                resolveConfiguredFeatureId(
                    referenceId,
                    placedFeatures,
                    configuredFeatures,
                    visiting,
                    target
                );
            } else if (!referenceId.startsWith("#")) {
                target.unresolved = true;
            }
            return;
        }
        if (!reference.isJsonObject()) {
            return;
        }
        JsonObject object = reference.getAsJsonObject();
        if (object.has("feature") && object.has("placement")) {
            target.merge(resolvePlacedFeature(reference, placedFeatures, configuredFeatures, visiting));
        } else if (object.has("type") && object.has("config")) {
            resolveConfiguredFeatureElement(
                reference,
                placedFeatures,
                configuredFeatures,
                visiting,
                target
            );
        } else {
            resolveNestedFeatureReferences(
                reference,
                placedFeatures,
                configuredFeatures,
                visiting,
                target
            );
        }
    }

    private static void collectConfiguredFeatureBlocks(JsonObject config, FeatureSummary target) {
        if (config.has("targets") && config.get("targets").isJsonArray()) {
            for (JsonElement targetElement : config.getAsJsonArray("targets")) {
                if (!targetElement.isJsonObject()) {
                    continue;
                }
                JsonObject targetObject = targetElement.getAsJsonObject();
                collectBlockStateIds(targetObject.get("state"), target.outputBlockIds);
                collectTargetSelectors(targetObject.get("target"), target.targetSelectors);
            }
        }
        for (String field : List.of(
            "state",
            "to_place",
            "trunk_provider",
            "foliage_provider",
            "dirt_provider",
            "fluid",
            "block_provider"
        )) {
            if (config.has(field)) {
                collectBlockStateIds(config.get(field), target.outputBlockIds);
            }
        }
        if (config.has("replaceable")) {
            collectTargetSelectors(config.get("replaceable"), target.targetSelectors);
        }
    }

    private static void collectBlockStateIds(JsonElement element, Set<String> target) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectBlockStateIds(child, target);
            }
            return;
        }
        if (element.isJsonPrimitive()) {
            String id = stringValue(element);
            if (isResourceId(id)) {
                target.add(id);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject object = element.getAsJsonObject();
        for (String key : List.of("Name", "name")) {
            String id = stringValue(object.get(key));
            if (isResourceId(id)) {
                target.add(id);
            }
        }
        for (String key : List.of("state", "states", "entries", "fallback", "provider")) {
            if (object.has(key)) {
                collectBlockStateIds(object.get(key), target);
            }
        }
        if (object.has("data")) {
            collectBlockStateIds(object.get("data"), target);
        }
    }

    private static void collectTargetSelectors(JsonElement element, Set<String> target) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonPrimitive()) {
            String value = stringValue(element);
            if (isResourceId(value) || value != null && value.startsWith("#")) {
                target.add(value);
            }
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectTargetSelectors(child, target);
            }
            return;
        }
        JsonObject object = element.getAsJsonObject();
        String tag = stringValue(object.get("tag"));
        if (isResourceId(tag)) {
            target.add("#" + tag);
        }
        for (String key : List.of("block", "blocks", "target", "targets")) {
            if (object.has(key)) {
                collectTargetSelectors(object.get(key), target);
            }
        }
    }

    private static JsonObject normalizePlacement(JsonElement placementElement) {
        JsonObject placement = new JsonObject();
        JsonArray modifiers = new JsonArray();
        JsonArray modifierTypes = new JsonArray();
        TreeSet<String> types = new TreeSet<>();
        boolean biomeFilter = false;
        if (placementElement != null && placementElement.isJsonArray()) {
            for (JsonElement modifierElement : placementElement.getAsJsonArray()) {
                if (!modifierElement.isJsonObject()) {
                    continue;
                }
                JsonObject modifier = modifierElement.getAsJsonObject();
                modifiers.add(modifier.deepCopy());
                String type = stringValue(modifier.get("type"));
                if (type == null) {
                    continue;
                }
                types.add(type);
                String path = idPath(type);
                if (path.equals("count") || path.equals("count_on_every_layer")) {
                    if (modifier.has("count")) {
                        placement.add("count", modifier.get("count").deepCopy());
                    }
                } else if (path.equals("rarity_filter") && modifier.has("chance")) {
                    placement.add("rarity", modifier.get("chance").deepCopy());
                } else if (path.equals("height_range") && modifier.has("height")) {
                    JsonElement height = modifier.get("height");
                    placement.add("height", height.deepCopy());
                    addHeightSummary(placement, height);
                } else if (path.equals("biome")) {
                    biomeFilter = true;
                }
            }
        }
        types.forEach(modifierTypes::add);
        placement.addProperty("biome_filter", biomeFilter);
        placement.add("modifier_types", modifierTypes);
        placement.add("modifiers", modifiers);
        return placement;
    }

    private static void addHeightSummary(JsonObject target, JsonElement heightElement) {
        if (!heightElement.isJsonObject()) {
            return;
        }
        JsonObject height = heightElement.getAsJsonObject();
        String type = stringValue(height.get("type"));
        if (type != null) {
            target.addProperty("distribution", idPath(type));
        }
        Integer min = absoluteAnchor(height.get("min_inclusive"));
        Integer max = absoluteAnchor(height.get("max_inclusive"));
        if (min != null) {
            target.addProperty("min_y", min);
        }
        if (max != null) {
            target.addProperty("max_y", max);
        }
    }

    private static Integer absoluteAnchor(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return element.getAsInt();
        }
        if (element.isJsonObject()) {
            JsonElement absolute = element.getAsJsonObject().get("absolute");
            if (absolute != null && absolute.isJsonPrimitive() && absolute.getAsJsonPrimitive().isNumber()) {
                return absolute.getAsInt();
            }
        }
        return null;
    }

    private static void addBiomeGenerationUses(
        Map<String, JsonElement> biomeDefinitions,
        Map<String, GenerationUse> featureUses,
        Map<String, GenerationUse> carverUses
    ) {
        for (Map.Entry<String, JsonElement> biomeEntry : biomeDefinitions.entrySet()) {
            if (!biomeEntry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject biome = biomeEntry.getValue().getAsJsonObject();
            JsonObject generation = biome.has("generation_settings")
                && biome.get("generation_settings").isJsonObject()
                ? biome.getAsJsonObject("generation_settings")
                : biome;
            JsonElement features = generation.get("features");
            if (features != null && features.isJsonArray()) {
                JsonArray stages = features.getAsJsonArray();
                boolean staged = stages.size() > 0 && stages.get(0).isJsonArray();
                if (staged) {
                    for (int index = 0; index < stages.size(); index++) {
                        TreeSet<String> ids = new TreeSet<>();
                        collectHolderReferences(stages.get(index), ids);
                        String step = index < GENERATION_STEPS.size() ? GENERATION_STEPS.get(index) : null;
                        addResolvedBiomeUses(featureUses, ids, biomeEntry.getKey(), step);
                    }
                } else {
                    TreeSet<String> ids = new TreeSet<>();
                    collectHolderReferences(features, ids);
                    addResolvedBiomeUses(featureUses, ids, biomeEntry.getKey(), null);
                }
            }
            JsonElement carvers = generation.get("carvers");
            if (carvers != null && carvers.isJsonObject()) {
                for (Map.Entry<String, JsonElement> step : carvers.getAsJsonObject().entrySet()) {
                    TreeSet<String> ids = new TreeSet<>();
                    collectHolderReferences(step.getValue(), ids);
                    addResolvedBiomeUses(carverUses, ids, biomeEntry.getKey(), step.getKey());
                }
            }
        }
    }

    private static void addResolvedBiomeUses(
        Map<String, GenerationUse> uses,
        Set<String> ids,
        String biomeId,
        String step
    ) {
        for (String id : ids) {
            if (id.startsWith("#")) {
                continue;
            }
            GenerationUse use = uses.computeIfAbsent(id, ignored -> new GenerationUse());
            use.resolvedBiomeIds.add(biomeId);
            if (step != null) {
                use.generationSteps.add(step);
            }
        }
    }

    private static int addBiomeModifierUses(
        ExportContext context,
        Map<String, Set<ResourceLocation>> registries,
        Map<String, Map<String, JsonElement>> runtimeDefinitions,
        Map<String, DataFile> files,
        Map<String, GenerationUse> featureUses
    ) {
        int count = 0;
        for (Map.Entry<String, String> extension : EXTENSION_WORLDGEN_REGISTRIES.entrySet()) {
            if (!extension.getKey().endsWith(":biome_modifier")) {
                continue;
            }
            Map<String, IndexedObject> modifiers = indexedObjects(
                registries.get(extension.getKey()),
                runtimeDefinitions.get(extension.getKey()),
                files,
                extension.getValue()
            );
            for (IndexedObject modifier : modifiers.values()) {
                JsonElement definition = effectiveDefinition(context, modifier);
                if (definition == null || !definition.isJsonObject()) {
                    continue;
                }
                JsonObject object = definition.getAsJsonObject();
                String type = stringValue(object.get("type"));
                if (type != null && idPath(type).startsWith("remove_")) {
                    continue;
                }
                TreeSet<String> features = new TreeSet<>();
                collectHolderReferences(object.get("features"), features);
                if (features.isEmpty()) {
                    continue;
                }
                TreeSet<String> selectors = new TreeSet<>();
                collectHolderReferences(object.get("biomes"), selectors);
                String step = stringValue(object.get("step"));
                for (String feature : features) {
                    if (feature.startsWith("#")) {
                        continue;
                    }
                    GenerationUse use = featureUses.computeIfAbsent(feature, ignored -> new GenerationUse());
                    use.biomeSelectors.addAll(selectors);
                    use.biomeModifierIds.add(modifier.id.toString());
                    if (step != null) {
                        use.generationSteps.add(step);
                    }
                }
                count++;
            }
        }
        return count;
    }

    private static void collectHolderReferences(JsonElement element, Set<String> target) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonPrimitive()) {
            String value = stringValue(element);
            if (isResourceId(value) || value != null && value.startsWith("#")) {
                target.add(value);
            }
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectHolderReferences(child, target);
            }
            return;
        }
        JsonObject object = element.getAsJsonObject();
        String id = stringValue(object.get("id"));
        if (isResourceId(id)) {
            target.add(id);
        }
    }

    private static void addFeatureSummary(
        ExportContext context,
        JsonObject json,
        FeatureSummary summary
    ) {
        JsonArray configuredIds = new JsonArray();
        summary.configuredFeatureIds.forEach(configuredIds::add);
        json.add("configured_feature_ids", configuredIds);
        if (configuredIds.size() == 1) {
            json.addProperty("configured_feature_id", configuredIds.get(0).getAsString());
        }
        JsonArray outputs = new JsonArray();
        for (String blockId : summary.outputBlockIds) {
            JsonObject output = new JsonObject();
            output.addProperty("block_id", blockId);
            outputs.add(output);
        }
        json.add("outputs", outputs);
        JsonArray targets = new JsonArray();
        summary.targetSelectors.forEach(targets::add);
        json.add("targets", targets);
        JsonArray references = new JsonArray();
        summary.referencedFeatureIds.forEach(references::add);
        json.add("referenced_feature_ids", references);
        if (summary.size != null) {
            json.add("size", summary.size.deepCopy());
        }
        if (summary.discardChance != null) {
            json.add("discard_chance_on_air_exposure", summary.discardChance.deepCopy());
        }
        json.add("placement", summary.placement == null ? new JsonObject() : summary.placement.deepCopy());
        if (context != null && !summary.outputBlockIds.isEmpty()) {
            String outputId = summary.outputBlockIds.iterator().next();
            int separator = outputId.indexOf(':');
            if (separator > 0 && separator < outputId.length() - 1) {
                String translationKey = "block."
                    + outputId.substring(0, separator)
                    + "."
                    + outputId.substring(separator + 1).replace('/', '.');
                JsonObject names = context.names(translationKey);
                if (names.size() > 0) {
                    json.addProperty("translation_key", translationKey);
                    json.addProperty("name_source_block_id", outputId);
                    json.add("names", names);
                }
            }
        }
    }

    private static void addDefinitionPaths(
        ExportContext context,
        JsonObject json,
        IndexedObject placedFeature,
        Set<String> configuredFeatureIds,
        Map<String, IndexedObject> configuredFeatures
    ) {
        if (placedFeature.file != null) {
            json.addProperty("placed_feature_definition_path", exportPath(context, placedFeature.file.target()));
        }
        JsonArray paths = new JsonArray();
        for (String configuredId : configuredFeatureIds) {
            IndexedObject configured = configuredFeatures.get(configuredId);
            if (configured != null && configured.file != null) {
                JsonObject path = new JsonObject();
                path.addProperty("configured_feature_id", configuredId);
                path.addProperty("path", exportPath(context, configured.file.target()));
                paths.add(path);
            }
        }
        json.add("configured_feature_definition_paths", paths);
    }

    private static void addGenerationUse(JsonObject json, GenerationUse use) {
        if (use == null) {
            use = new GenerationUse();
        }
        json.add("generation_steps", stringArray(use.generationSteps));
        if (use.generationSteps.size() == 1) {
            json.addProperty("generation_step", use.generationSteps.iterator().next());
        }
        json.add("biome_selectors", stringArray(use.biomeSelectors));
        json.add("resolved_biome_ids", stringArray(use.resolvedBiomeIds));
        json.add("biome_modifier_ids", stringArray(use.biomeModifierIds));
        JsonArray dimensions = inferredDimensionIds(use);
        json.add("dimension_ids", dimensions);
        json.addProperty(
            "dimension_resolution",
            dimensions.size() > 0 ? "biome_selector_inference" : "not_directly_resolvable"
        );
    }

    private static JsonArray inferredDimensionIds(GenerationUse use) {
        TreeSet<String> dimensions = new TreeSet<>();
        for (String selector : use.biomeSelectors) {
            String normalized = selector.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("is_overworld")) {
                dimensions.add("minecraft:overworld");
            }
            if (normalized.contains("is_nether")) {
                dimensions.add("minecraft:the_nether");
            }
            if (normalized.contains("is_end")) {
                dimensions.add("minecraft:the_end");
            }
        }
        return stringArray(dimensions);
    }

    private static void summarizeCarverDefinition(JsonObject target, JsonElement definition) {
        if (definition == null || !definition.isJsonObject()) {
            return;
        }
        JsonObject object = definition.getAsJsonObject();
        String type = stringValue(object.get("type"));
        if (type != null) {
            target.addProperty("carver_type", type);
        }
        JsonObject config = object.has("config") && object.get("config").isJsonObject()
            ? object.getAsJsonObject("config")
            : object;
        for (String field : List.of(
            "probability",
            "y",
            "yScale",
            "y_scale",
            "lava_level",
            "aquifers_enabled",
            "replaceable",
            "liquids",
            "debug_settings"
        )) {
            if (config.has(field)) {
                target.add(field, config.get(field).deepCopy());
            }
        }
    }

    private static JsonArray technicalNaturalGenerationCategories(
        Map<String, Set<ResourceLocation>> registries,
        Map<String, DataFile> files
    ) {
        TreeSet<String> categories = new TreeSet<>();
        registries.keySet().stream()
            .filter(id -> id.startsWith("minecraft:worldgen/"))
            .map(id -> id.substring("minecraft:worldgen/".length()))
            .filter(WorldgenDataStage::isTechnicalNaturalGenerationCategory)
            .forEach(categories::add);
        files.values().stream()
            .map(DataFile::kind)
            .filter(kind -> kind.startsWith("worldgen/"))
            .map(kind -> kind.substring("worldgen/".length()))
            .filter(WorldgenDataStage::isTechnicalNaturalGenerationCategory)
            .forEach(categories::add);
        return stringArray(categories);
    }

    private static boolean isTechnicalNaturalGenerationCategory(String category) {
        return !category.equals("biome")
            && !category.equals("structure")
            && !category.equals("configured_feature")
            && !category.equals("placed_feature")
            && !category.equals("configured_carver")
            && !isNestedStructureCategory(category);
    }

    private static void addSourceMod(JsonObject json, IndexedObject entry) {
        String sourceModId = entry.file != null ? entry.file.sourceModId() : entry.id.getNamespace();
        json.addProperty("source_mod_id", sourceModId);
        Platform.getMods().stream()
            .filter(mod -> mod.getModId().equals(sourceModId))
            .findFirst()
            .ifPresent(mod -> json.addProperty("source_mod_version", mod.getVersion()));
    }

    private static JsonArray stringArray(Iterable<String> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }

    private static String featureCategory(String featureType) {
        String path = idPath(featureType);
        if (path.contains("ore")) {
            return "ore";
        }
        if (path.contains("tree") || path.contains("fungus")) {
            return "tree";
        }
        if (path.contains("vegetation")
            || path.contains("flower")
            || path.contains("patch")
            || path.contains("bamboo")
            || path.contains("kelp")
            || path.contains("seagrass")
            || path.contains("cactus")
            || path.contains("chorus")) {
            return "vegetation";
        }
        if (path.contains("lake")) {
            return "lake";
        }
        if (path.contains("spring")) {
            return "spring";
        }
        if (path.contains("geode")) {
            return "geode";
        }
        if (path.contains("disk")) {
            return "disk";
        }
        if (path.contains("dripstone")
            || path.contains("fossil")
            || path.contains("monster_room")
            || path.contains("basalt")
            || path.contains("glowstone")
            || path.contains("delta")) {
            return "underground";
        }
        if (path.contains("surface") || path.contains("ice") || path.contains("snow")) {
            return "surface";
        }
        return "other";
    }

    private static String idPath(String id) {
        if (id == null) {
            return "";
        }
        int separator = id.indexOf(':');
        return separator >= 0 ? id.substring(separator + 1) : id;
    }

    private static String stringValue(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
            ? element.getAsString()
            : null;
    }

    private static boolean isResourceId(String value) {
        if (value == null || value.startsWith("#")) {
            return false;
        }
        int separator = value.indexOf(':');
        return separator > 0 && separator < value.length() - 1;
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
        long size,
        String sourceModId
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

    private static final class FeatureSummary {
        private String featureType;
        private JsonElement size;
        private JsonElement discardChance;
        private JsonObject placement = new JsonObject();
        private boolean unresolved;
        private final Set<String> outputBlockIds = new TreeSet<>();
        private final Set<String> targetSelectors = new TreeSet<>();
        private final Set<String> configuredFeatureIds = new TreeSet<>();
        private final Set<String> referencedFeatureIds = new TreeSet<>();

        private void merge(FeatureSummary other) {
            if (featureType == null) {
                featureType = other.featureType;
            }
            if (size == null) {
                size = other.size;
            }
            if (discardChance == null) {
                discardChance = other.discardChance;
            }
            outputBlockIds.addAll(other.outputBlockIds);
            targetSelectors.addAll(other.targetSelectors);
            configuredFeatureIds.addAll(other.configuredFeatureIds);
            referencedFeatureIds.addAll(other.referencedFeatureIds);
            unresolved |= other.unresolved;
        }
    }

    private static final class GenerationUse {
        private final Set<String> generationSteps = new TreeSet<>();
        private final Set<String> biomeSelectors = new TreeSet<>();
        private final Set<String> resolvedBiomeIds = new TreeSet<>();
        private final Set<String> biomeModifierIds = new TreeSet<>();
    }

    private static final class LootCandidates {
        private final Set<String> items = new TreeSet<>();
        private final Set<String> tags = new TreeSet<>();
    }
}
