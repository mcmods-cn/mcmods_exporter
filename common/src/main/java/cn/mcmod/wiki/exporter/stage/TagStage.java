package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.OnceStage;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TagStage extends OnceStage {
    public static final String ID = "tags";

    @Override
    public String id() {
        return ID;
    }

    @Override
    protected void run(ExportContext context) throws Exception {
        Map<String, Registry<?>> registries = new LinkedHashMap<>();
        BuiltInRegistries.REGISTRY.forEach(registry -> registries.put(registry.key().location().toString(), registry));
        if (context.minecraft().level != null) {
            context.minecraft().level.registryAccess().registries()
                .forEach(entry -> registries.put(entry.key().location().toString(), entry.value()));
        }

        JsonArray registryData = new JsonArray();
        registries.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            JsonObject registryJson = exportUnknownRegistry(context, entry.getKey(), entry.getValue());
            if (registryJson.getAsJsonArray("tags").size() > 0) {
                registryData.add(registryJson);
            }
        });
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", "mcwiki-tags/v1");
        root.addProperty("registry_count", registryData.size());
        root.add("registries", registryData);
        context.writeJson("tags/tags.json", root);
        context.report().addCount(ID, "registries", registryData.size());
    }

    private static <T> JsonObject exportTypedRegistry(ExportContext context, String registryId, Registry<T> registry) {
        JsonObject output = new JsonObject();
        output.addProperty("registry", registryId);
        JsonArray tags = new JsonArray();
        registry.getTags().sorted(Comparator.comparing(pair -> pair.getFirst().location().toString())).forEach(pair -> {
            TagKey<T> key = pair.getFirst();
            HolderSet.Named<T> holders = pair.getSecond();
            JsonObject tag = new JsonObject();
            tag.addProperty("id", key.location().toString());
            JsonArray values = new JsonArray();
            for (Holder<T> holder : holders) {
                ResourceLocation id = holder.unwrapKey().map(resourceKey -> resourceKey.location()).orElseGet(() -> registry.getKey(holder.value()));
                if (id != null) {
                    values.add(id.toString());
                }
            }
            tag.add("values", values);
            tag.addProperty("value_count", values.size());
            boolean relevant = context.matches(key.location());
            if (!relevant) {
                for (int index = 0; index < values.size(); index++) {
                    if (context.matches(new ResourceLocation(values.get(index).getAsString()))) {
                        relevant = true;
                        break;
                    }
                }
            }
            if (relevant) {
                tags.add(tag);
            }
        });
        output.add("tags", tags);
        output.addProperty("tag_count", tags.size());
        return output;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static JsonObject exportUnknownRegistry(ExportContext context, String registryId, Registry<?> registry) {
        return exportTypedRegistry(context, registryId, (Registry) registry);
    }
}
