package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.OnceStage;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RecipeDataStage extends OnceStage {
    public static final String ID = "recipes";

    @Override
    public String id() {
        return ID;
    }

    @Override
    protected void run(ExportContext context) throws Exception {
        List<Recipe<?>> recipes = new ArrayList<>(context.minecraft().level.getRecipeManager().getRecipes());
        recipes.sort(Comparator.comparing(recipe -> recipe.getId().toString()));
        JsonArray data = new JsonArray();
        for (Recipe<?> recipe : recipes) {
            if (!context.matches(recipe.getId())) {
                continue;
            }
            try {
                data.add(recipeJson(context, recipe));
            } catch (Throwable throwable) {
                context.report().error(ID, recipe.getId().toString(), throwable);
            }
        }
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", "mcmods-recipes/v1");
        root.addProperty("count", data.size());
        root.add("recipes", data);
        context.writeJson("recipes/recipes.json", root);
        context.report().addCount(ID, "recipes", data.size());
    }

    private static JsonObject recipeJson(ExportContext context, Recipe<?> recipe) {
        JsonObject json = new JsonObject();
        json.addProperty("id", recipe.getId().toString());
        ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        ResourceLocation serializer = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
        json.addProperty("type", type == null ? recipe.getType().toString() : type.toString());
        if (serializer != null) {
            json.addProperty("serializer", serializer.toString());
        }
        json.addProperty("group", recipe.getGroup());
        json.addProperty("special", recipe.isSpecial());
        json.addProperty("incomplete", recipe.isIncomplete());

        JsonArray ingredients = new JsonArray();
        for (Ingredient ingredient : recipe.getIngredients()) {
            JsonObject ingredientJson = new JsonObject();
            JsonArray alternatives = new JsonArray();
            for (ItemStack stack : ingredient.getItems()) {
                alternatives.add(ExportContext.stackJson(stack));
            }
            ingredientJson.add("alternatives", alternatives);
            ingredientJson.addProperty("alternative_count", alternatives.size());
            ingredients.add(ingredientJson);
        }
        json.add("ingredients", ingredients);
        json.add("result", ExportContext.stackJson(recipe.getResultItem(context.minecraft().level.registryAccess())));

        if (recipe instanceof ShapedRecipe shaped) {
            json.addProperty("width", shaped.getWidth());
            json.addProperty("height", shaped.getHeight());
        }
        if (recipe instanceof AbstractCookingRecipe cooking) {
            json.addProperty("cooking_time_ticks", cooking.getCookingTime());
            json.addProperty("experience", cooking.getExperience());
        }
        JsonObject parameters = RecipeParameterExtractor.extract(recipe);
        json.addProperty("parameter_count", parameters.size());
        json.add("parameters", parameters);
        return json;
    }
}
