package cn.mcmod.wiki.exporter.stage;

import com.google.gson.JsonObject;
import dev.architectury.platform.Mod;
import dev.architectury.platform.Platform;
import net.minecraft.resources.ResourceLocation;

/** Adds stable recipe source fields using the recipe namespace and loaded mod metadata. */
final class RecipeSourceMetadata {
    private RecipeSourceMetadata() {
    }

    static void add(JsonObject target, ResourceLocation sourceId, String sourceKind) {
        String modId = sourceId.getNamespace();
        target.addProperty("source_mod_id", modId);
        target.addProperty("source_mod_version", version(modId));
        target.addProperty("source_mod_id_source", sourceKind);
    }

    private static String version(String modId) {
        if ("minecraft".equals(modId)) {
            return Platform.getMinecraftVersion();
        }
        return Platform.getOptionalMod(modId)
            .map(Mod::getVersion)
            .orElse("unknown");
    }
}
