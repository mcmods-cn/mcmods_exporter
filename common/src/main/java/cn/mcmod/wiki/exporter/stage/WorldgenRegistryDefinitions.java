package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.task.ExportContext;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

// Frozen registry data belongs to the current world. Each step visits at most
// one key and shares its encoded JSON between the catalog and dependency lookup.
final class WorldgenRegistryDefinitions {
    final Map<String, Map<String, JsonElement>> selected = new TreeMap<>();
    final Map<String, Map<String, JsonElement>> dependencies = new TreeMap<>();

    private final RegistryAccess access;
    private final RegistryOps<JsonElement> ops;
    private final Iterator<RegistryDataLoader.RegistryData<?>> types;
    private final Set<String> dependencyTypes;
    private Iterator<ResourceLocation> keys = Collections.emptyIterator();
    private Consumer<ResourceLocation> encode;

    WorldgenRegistryDefinitions(RegistryAccess access, Iterator<RegistryDataLoader.RegistryData<?>> types, Set<String> dependencyTypes) {
        this.access = access;
        this.ops = RegistryOps.create(JsonOps.INSTANCE, access);
        this.types = types;
        this.dependencyTypes = dependencyTypes;
    }

    boolean finishedAfterStep(ExportContext context) {
        if (keys.hasNext()) {
            encode.accept(keys.next());
            return false;
        }
        if (!types.hasNext()) {
            return true;
        }
        select(context, types.next());
        return false;
    }

    private <T> void select(ExportContext context, RegistryDataLoader.RegistryData<T> type) {
        Registry<T> registry = access.registry(type.key()).orElse(null);
        if (registry == null) {
            return;
        }
        String registryId = type.key().location().toString();
        boolean resolveDependencies = dependencyTypes.contains(registryId);
        keys = registry.keySet().iterator();
        encode = id -> {
            boolean inScope = context.matches(id);
            if (!inScope && !resolveDependencies) {
                return;
            }
            T value = registry.get(id);
            if (value == null) {
                return;
            }
            String subject = "runtime_definition:" + registryId + "/" + id;
            try {
                var result = type.elementCodec().encodeStart(ops, value);
                // DataResult may contain usable partial JSON. Keep the producer's
                // existing resultOrPartial behavior and record only missing values.
                var json = result.resultOrPartial(ignored -> {});
                if (json.isEmpty()) {
                    context.report().error(WorldgenDataStage.ID, subject, new IllegalStateException("Missing registry definition"));
                    return;
                }
                if (inScope) {
                    selected.computeIfAbsent(registryId, ignored -> new TreeMap<>()).put(id.toString(), json.get());
                }
                if (resolveDependencies) {
                    dependencies.computeIfAbsent(registryId, ignored -> new TreeMap<>()).put(id.toString(), json.get());
                }
            } catch (RuntimeException | LinkageError failure) {
                context.report().error(WorldgenDataStage.ID, subject, failure);
            }
        };
    }
}
