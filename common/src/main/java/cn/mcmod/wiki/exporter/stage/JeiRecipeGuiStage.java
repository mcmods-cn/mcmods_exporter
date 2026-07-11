package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.compat.jei.McModsJeiPlugin;
import cn.mcmod.wiki.exporter.render.OffscreenRenderer;
import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.ExportStage;
import cn.mcmod.wiki.exporter.task.StageResult;
import cn.mcmod.wiki.exporter.util.ExportPaths;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Exports one ingredient-free GUI background per JEI RecipeType together with
 * one machine-readable ingredient layout per recipe. The website composes the
 * final view from the category background and separately exported item images.
 */
public final class JeiRecipeGuiStage implements ExportStage {
    public static final String ID = "jei_recipe_gui";
    private static final int PADDING = 4;

    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> renderedCategories = new HashSet<>();
    private IJeiRuntime runtime;
    private int index;

    @Override
    public String id() {
        return ID;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked", "removal"})
    public void start(ExportContext context) throws Exception {
        runtime = McModsJeiPlugin.runtime();
        if (runtime == null) {
            context.report().addCount(ID, "runtime_unavailable", 1);
            return;
        }
        List<RecipeType<?>> types = runtime.getJeiHelpers().getAllRecipeTypes()
            .sorted(Comparator.comparing(type -> type.getUid().toString()))
            .toList();
        JsonArray categories = new JsonArray();
        JsonArray recipeIndex = new JsonArray();
        Map<String, Integer> categoryCounts = new HashMap<>();
        for (RecipeType type : types) {
            IRecipeCategory category = runtime.getRecipeManager().getRecipeCategory(type);
            List<?> recipes = runtime.getRecipeManager().createRecipeLookup(type).includeHidden().get().toList();
            int localIndex = 0;
            for (Object recipe : recipes) {
                ResourceLocation vanillaId = recipe instanceof Recipe<?> vanillaRecipe ? vanillaRecipe.getId() : null;
                if (!context.matches(type.getUid()) && (vanillaId == null || !context.matches(vanillaId))) {
                    continue;
                }
                Entry exportEntry = new Entry(type, category, recipe, localIndex++);
                entries.add(exportEntry);
                categoryCounts.merge(type.getUid().toString(), 1, Integer::sum);
                ResourceLocation outputId = outputId(exportEntry);
                JsonObject recipeJson = new JsonObject();
                recipeJson.addProperty("recipe_type_id", type.getUid().toString());
                if (vanillaId != null) {
                    recipeJson.addProperty("recipe_id", vanillaId.toString());
                }
                recipeJson.addProperty("layout", relative(context, layoutPath(context, outputId)));
                recipeJson.addProperty("background", relative(context, backgroundPath(context, type.getUid())));
                recipeIndex.add(recipeJson);
            }
            String categoryId = type.getUid().toString();
            if (categoryCounts.containsKey(categoryId)) {
                JsonObject categoryJson = new JsonObject();
                categoryJson.addProperty("recipe_type_id", categoryId);
                addLocalizedComponent(context, categoryJson, "title", category.getTitle());
                categoryJson.addProperty("width", category.getWidth());
                categoryJson.addProperty("height", category.getHeight());
                categoryJson.addProperty("recipe_count", categoryCounts.get(categoryId));
                categoryJson.addProperty("background", relative(context, backgroundPath(context, type.getUid())));
                categoryJson.addProperty("background_contains_ingredients", false);
                categoryJson.addProperty("image_scale", 2);
                categoryJson.add("canvas", rect(0, 0, category.getWidth() + PADDING * 2, category.getHeight() + PADDING * 2));
                categories.add(categoryJson);
            }
        }
        JsonObject indexJson = new JsonObject();
        indexJson.addProperty("schema_version", "mcmods-jei-categories/v3");
        indexJson.addProperty("category_count", categories.size());
        indexJson.addProperty("recipe_count", entries.size());
        indexJson.add("categories", categories);
        indexJson.add("recipes", recipeIndex);
        context.writeJson("recipes/jei/categories.json", indexJson);
    }

    @Override
    public StageResult step(ExportContext context) {
        if (runtime == null || index >= entries.size()) {
            return StageResult.DONE;
        }
        Entry entry = entries.get(index++);
        try {
            renderEntry(context, entry);
            context.report().addCount(ID, "layouts", 1);
        } catch (Throwable throwable) {
            context.report().error(ID, entry.type.getUid() + "#" + entry.index, throwable);
        }
        return index >= entries.size() ? StageResult.DONE : StageResult.MORE_WORK;
    }

    @SuppressWarnings({"rawtypes", "unchecked", "removal"})
    private void renderEntry(ExportContext context, Entry entry) throws Exception {
        IRecipeManager recipeManager = runtime.getRecipeManager();
        Optional layoutOptional = recipeManager.createRecipeLayoutDrawable(
            entry.category,
            entry.recipe,
            runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup()
        );
        if (layoutOptional.isEmpty()) {
            throw new IllegalStateException("JEI refused to create a recipe layout");
        }
        IRecipeLayoutDrawable layout = (IRecipeLayoutDrawable) layoutOptional.get();

        int contentWidth = entry.category.getWidth();
        int contentHeight = entry.category.getHeight();
        int canvasWidth = contentWidth + PADDING * 2;
        int canvasHeight = contentHeight + PADDING * 2;
        layout.setPosition(PADDING, PADDING);
        ResourceLocation recipeId = outputId(entry);

        JsonArray slots = serializeSlots(context, layout);
        renderCategoryBackground(context, entry, layout, canvasWidth, canvasHeight);

        JsonObject metadata = new JsonObject();
        metadata.addProperty("schema_version", "mcmods-jei-layout/v3");
        metadata.addProperty("recipe_type_id", entry.type.getUid().toString());
        if (entry.recipe instanceof Recipe<?> recipe) {
            metadata.addProperty("recipe_id", recipe.getId().toString());
        }
        metadata.addProperty("render_locale", context.minecraft().getLanguageManager().getSelected());
        metadata.addProperty("background", relative(context, backgroundPath(context, entry.type.getUid())));
        metadata.addProperty("background_contains_ingredients", false);
        metadata.addProperty("coordinate_space", "logical_pixels");
        metadata.addProperty("image_scale", 2);
        metadata.add("canvas", rect(0, 0, canvasWidth, canvasHeight));
        metadata.add("image_pixels", rect(0, 0, canvasWidth * 2, canvasHeight * 2));
        metadata.add("content", rect(PADDING, PADDING, contentWidth, contentHeight));
        metadata.add("slots", slots);
        context.writeJson(relative(context, layoutPath(context, recipeId)), metadata);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void renderCategoryBackground(
        ExportContext context,
        Entry entry,
        IRecipeLayoutDrawable layout,
        int canvasWidth,
        int canvasHeight
    ) throws Exception {
        String categoryId = entry.type.getUid().toString();
        if (renderedCategories.contains(categoryId)) {
            return;
        }
        for (IRecipeSlotView slot : layout.getRecipeSlotsView().getSlotViews()) {
            if (slot instanceof IRecipeSlotDrawable drawable) {
                // An empty display override keeps the slot/background but makes the
                // ingredient renderer return Optional.empty().
                drawable.createDisplayOverrides();
            }
        }
        OffscreenRenderer.renderGui(
            canvasWidth * 2,
            canvasHeight * 2,
            canvasWidth,
            canvasHeight,
            backgroundPath(context, entry.type.getUid()),
            graphics -> layout.drawRecipe(graphics, -1000, -1000)
        );
        renderedCategories.add(categoryId);
        context.report().addCount(ID, "recipe_type_backgrounds", 1);
    }

    @SuppressWarnings("removal")
    private JsonArray serializeSlots(ExportContext context, IRecipeLayoutDrawable<?> layout) {
        JsonArray slots = new JsonArray();
        int slotIndex = 0;
        for (IRecipeSlotView slot : layout.getRecipeSlotsView().getSlotViews()) {
            JsonObject json = new JsonObject();
            json.addProperty("index", slotIndex++);
            json.addProperty("role", slot.getRole().name().toLowerCase());
            slot.getSlotName().ifPresent(name -> json.addProperty("name", name));

            JsonArray alternatives = new JsonArray();
            slot.getAllIngredients().forEach(ingredient -> alternatives.add(serializeIngredient(context, ingredient)));
            json.addProperty("clickable", alternatives.size() > 0);
            json.add("alternatives", alternatives);

            List<ItemStack> itemStacks = slot.getItemStacks().toList();
            if (!itemStacks.isEmpty()) {
                runtime.getIngredientManager().getIngredientHelper(VanillaTypes.ITEM_STACK)
                    .getTagKeyEquivalent(itemStacks)
                    .ifPresent(tag -> json.addProperty("item_tag_equivalent", tag.location().toString()));
            }
            if (slot instanceof IRecipeSlotDrawable drawable) {
                Rect2i clickable = drawable.getRect();
                Rect2i visual = drawable.getAreaIncludingBackground();
                json.add("rect", canvasRect(clickable));
                json.add("visual_rect", canvasRect(visual));
            } else {
                json.addProperty("coordinates_available", false);
            }
            slots.add(json);
        }
        return slots;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private JsonObject serializeIngredient(ExportContext context, ITypedIngredient<?> typedIngredient) {
        return serializeTypedIngredient(context, (ITypedIngredient) typedIngredient);
    }

    private <T> JsonObject serializeTypedIngredient(ExportContext context, ITypedIngredient<T> typedIngredient) {
        JsonObject json = new JsonObject();
        T ingredient = typedIngredient.getIngredient();
        IIngredientHelper<T> helper = runtime.getIngredientManager().getIngredientHelper(typedIngredient.getType());
        json.addProperty("type", typedIngredient.getType().getUid());
        try {
            json.addProperty("resource_location", helper.getResourceLocation(ingredient).toString());
        } catch (Throwable throwable) {
            json.addProperty("resource_location_unavailable", true);
        }
        try {
            json.addProperty("unique_id", helper.getUniqueId(ingredient, UidContext.Recipe));
        } catch (Throwable throwable) {
            json.addProperty("unique_id_unavailable", true);
        }
        long amount = helper.getAmount(ingredient);
        if (amount >= 0) {
            json.addProperty("amount", amount);
        }

        typedIngredient.getItemStack().ifPresent(stack -> {
            JsonObject stackJson = ExportContext.stackJson(stack);
            stackJson.entrySet().forEach(entry -> json.add(entry.getKey(), entry.getValue()));
            json.addProperty("translation_key", stack.getDescriptionId());
            json.add("names", context.componentNames(stack.getHoverName()));
        });
        return json;
    }

    private static void addLocalizedComponent(ExportContext context, JsonObject target, String field, Component component) {
        target.addProperty(field + "_component", Component.Serializer.toJson(component));
        target.add(field + "_names", context.componentNames(component));
        if (component.getContents() instanceof TranslatableContents translatable) {
            target.addProperty(field + "_translation_key", translatable.getKey());
        }
    }

    private static JsonObject canvasRect(Rect2i source) {
        return rect(PADDING + source.getX(), PADDING + source.getY(), source.getWidth(), source.getHeight());
    }

    private static JsonObject rect(int x, int y, int width, int height) {
        JsonObject rect = new JsonObject();
        rect.addProperty("x", x);
        rect.addProperty("y", y);
        rect.addProperty("width", width);
        rect.addProperty("height", height);
        return rect;
    }

    private static Path layoutPath(ExportContext context, ResourceLocation id) {
        return ExportPaths.idFile(context.root(), "recipes/jei/layouts", null, id, ".json");
    }

    private static Path backgroundPath(ExportContext context, ResourceLocation categoryId) {
        return ExportPaths.idFile(context.root(), "recipes/jei/backgrounds", null, categoryId, ".png");
    }

    private static String relative(ExportContext context, Path path) {
        return context.root().relativize(path).toString().replace('\\', '/');
    }

    private static ResourceLocation outputId(Entry entry) {
        ResourceLocation categoryId = entry.type.getUid();
        String name;
        if (entry.recipe instanceof Recipe<?> vanillaRecipe) {
            ResourceLocation recipeId = vanillaRecipe.getId();
            name = recipeId.getNamespace() + "__" + recipeId.getPath();
        } else {
            name = String.format("recipe_%06d", entry.index);
        }
        return new ResourceLocation(categoryId.getNamespace(), categoryId.getPath() + "/" + name);
    }

    @Override
    public String progress() {
        return index + "/" + entries.size();
    }

    @SuppressWarnings("rawtypes")
    private record Entry(RecipeType type, IRecipeCategory category, Object recipe, int index) {
    }
}
