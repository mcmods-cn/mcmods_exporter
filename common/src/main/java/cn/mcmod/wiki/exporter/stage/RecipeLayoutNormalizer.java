package cn.mcmod.wiki.exporter.stage;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/** Normalizes Minecraft recipe implementations into stable website-facing layout semantics. */
final class RecipeLayoutNormalizer {
    private static final String CRAFTING_TYPE = "minecraft:crafting";
    private static final String SHAPED_SERIALIZER = "minecraft:crafting_shaped";
    private static final String SHAPELESS_SERIALIZER = "minecraft:crafting_shapeless";

    private RecipeLayoutNormalizer() {
    }

    static void write(JsonObject target, Object recipeLike) {
        Object value = recipeLike instanceof RecipeHolder<?> holder ? holder.value() : recipeLike;
        if (!(value instanceof Recipe<?> recipe)) {
            write(target, new NormalizedLayout("unknown", null, "unavailable"));
            return;
        }

        ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        ResourceLocation serializer = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
        write(target, classify(
            recipe,
            type == null ? null : type.toString(),
            serializer == null ? null : serializer.toString()
        ));
    }

    static NormalizedLayout classify(Recipe<?> recipe, String recipeTypeId, String serializerId) {
        if (recipe instanceof ShapedRecipe) {
            return new NormalizedLayout("shaped", true, "recipe_class");
        }
        if (recipe instanceof ShapelessRecipe) {
            return new NormalizedLayout("shapeless", false, "recipe_class");
        }
        return classifyIds(recipeTypeId, serializerId);
    }

    static NormalizedLayout classifyIds(String recipeTypeId, String serializerId) {
        if (SHAPED_SERIALIZER.equals(serializerId)) {
            return new NormalizedLayout("shaped", true, "serializer");
        }
        if (SHAPELESS_SERIALIZER.equals(serializerId)) {
            return new NormalizedLayout("shapeless", false, "serializer");
        }
        if (CRAFTING_TYPE.equals(recipeTypeId)) {
            return new NormalizedLayout("unknown", null, "recipe_type");
        }
        if (recipeTypeId != null) {
            return new NormalizedLayout("not_applicable", null, "recipe_type");
        }
        return new NormalizedLayout("unknown", null, "unavailable");
    }

    private static void write(JsonObject target, NormalizedLayout layout) {
        target.addProperty("layout_kind", layout.kind());
        if (layout.ordered() == null) {
            target.add("ordered", JsonNull.INSTANCE);
        } else {
            target.addProperty("ordered", layout.ordered());
        }
        target.addProperty("layout_classification_source", layout.source());
    }

    record NormalizedLayout(String kind, Boolean ordered, String source) {
    }
}
