package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.OnceStage;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.architectury.platform.Platform;

/** Emits an importer-readable declaration instead of silently omitting data. */
public final class CompatibilityStage extends OnceStage {
    public static final String ID = "compatibility";

    @Override
    public String id() {
        return ID;
    }

    @Override
    protected void run(ExportContext context) throws Exception {
        JsonArray capabilities = new JsonArray();
        capabilities.add(capability("registries", "available", "built_in_registry"));
        capabilities.add(capability("languages", "available", "effective_client_resources"));
        capabilities.add(capability("tags", context.minecraft().level == null ? "degraded" : "available", "runtime_registry_access"));
        capabilities.add(capability("worldgen", context.minecraft().getSingleplayerServer() == null ? "degraded" : "available", "server_data_or_installed_mod_fallback"));
        capabilities.add(capability("advancements", context.minecraft().getConnection() == null && context.minecraft().getSingleplayerServer() == null ? "unavailable" : "available", "client_sync_or_integrated_server"));
        capabilities.add(capability("entity_runtime_properties", context.minecraft().level == null ? "unavailable" : "available", "temporary_default_entity"));
        capabilities.add(capability("biome_entity_spawns", context.minecraft().level == null ? "unavailable" : "available", "runtime_biome_registry"));
        capabilities.add(capability("jei_layouts", jeiRuntimeAvailable() ? "available" : "unavailable", "required_jei_runtime"));

        JsonObject root = new JsonObject();
        root.addProperty("schema_version", "mcmods-capabilities/v1");
        root.addProperty("minecraft_version", Platform.getMinecraftVersion());
        root.addProperty("loader", Platform.isFabric() ? "fabric" : Platform.isForge() ? "forge" : "unknown");
        root.addProperty("profile", context.config().profile().name().toLowerCase(java.util.Locale.ROOT));
        root.addProperty("format_contract", "mcmods-export/v1");
        root.addProperty("binary_compatibility", "exact_minecraft_version_build_required");
        root.addProperty("porting_policy", "optional_or_version_specific_features_must_degrade_without_breaking_core_export");
        root.add("capabilities", capabilities);
        context.writeJson("compatibility/capabilities.json", root);
        context.report().addCount(ID, "capabilities", capabilities.size());
    }

    private static JsonObject capability(String id, String status, String source) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("status", status);
        json.addProperty("source", source);
        return json;
    }

    private static boolean jeiRuntimeAvailable() {
        if (!Platform.isModLoaded("jei")) {
            return false;
        }
        try {
            Class<?> plugin = Class.forName("cn.mcmod.wiki.exporter.compat.jei.McModsJeiPlugin");
            return plugin.getMethod("runtime").invoke(null) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
