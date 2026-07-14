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
    private static final int IMAGE_SCALE = 4;

    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> renderedCategories = new HashSet<>();
    private final Map<String, JsonArray> layoutsByCategory = new HashMap<>();
    private IJeiRuntime runtime;
    private int index;
    private boolean layoutCollectionsWritten;

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
                ResourceLocation registeredId = registeredRecipeId(context, type, category, recipe);
                if (!context.matches(type.getUid()) && (registeredId == null || !context.matches(registeredId))) {
                    continue;
                }
                int recipeIndexInCategory = localIndex++;
                boolean canonicalRecipeId = registeredId != null;
                ResourceLocation recipeId = canonicalRecipeId
                    ? registeredId
                    : generatedRecipeId(type, recipeIndexInCategory);
                String recipeIdSource = canonicalRecipeId
                    ? recipeIdSource(recipe, registeredId)
                    : "generated_index";
                Entry exportEntry = new Entry(
                    type,
                    category,
                    recipe,
                    recipeIndexInCategory,
                    recipeId,
                    recipeIdSource,
                    canonicalRecipeId
                );
                entries.add(exportEntry);
                categoryCounts.merge(type.getUid().toString(), 1, Integer::sum);
                JsonObject recipeJson = new JsonObject();
                recipeJson.addProperty("recipe_type_id", type.getUid().toString());
                addRecipeIdentity(recipeJson, exportEntry);
                RecipeLayoutNormalizer.write(recipeJson, recipe);
                recipeJson.addProperty("layout_collection", relative(context, layoutCollectionPath(context, type.getUid())));
                recipeJson.addProperty("layout_key", layoutKey(exportEntry));
                recipeJson.addProperty("background", relative(context, backgroundPath(context, type.getUid())));
                recipeIndex.add(recipeJson);
            }
            String categoryId = type.getUid().toString();
            if (categoryCounts.containsKey(categoryId)) {
                layoutsByCategory.putIfAbsent(categoryId, new JsonArray());
                JsonObject categoryJson = new JsonObject();
                categoryJson.addProperty("recipe_type_id", categoryId);
                addLocalizedComponent(context, categoryJson, "title", category.getTitle());
                categoryJson.addProperty("width", category.getWidth());
                categoryJson.addProperty("height", category.getHeight());
                categoryJson.addProperty("recipe_count", categoryCounts.get(categoryId));
                categoryJson.addProperty("background", relative(context, backgroundPath(context, type.getUid())));
                categoryJson.addProperty("layout_collection", relative(context, layoutCollectionPath(context, type.getUid())));
                categoryJson.addProperty("background_contains_ingredients", false);
                categoryJson.addProperty("image_scale", IMAGE_SCALE);
                categoryJson.add("canvas", rect(0, 0, category.getWidth() + PADDING * 2, category.getHeight() + PADDING * 2));
                JsonArray catalysts = serializeRecipeCatalysts(context, type);
                categoryJson.addProperty("catalyst_count", catalysts.size());
                categoryJson.add("catalysts", catalysts);
                categories.add(categoryJson);
            }
        }
        JsonObject indexJson = new JsonObject();
        indexJson.addProperty("schema_version", "mcmods-jei-categories/v4");
        indexJson.addProperty("category_count", categories.size());
        indexJson.addProperty("recipe_count", entries.size());
        indexJson.add("categories", categories);
        indexJson.add("recipes", recipeIndex);
        context.writeJson("recipes/jei/categories.json", indexJson);
    }

    @Override
    public StageResult step(ExportContext context) {
        if (runtime == null) {
            return StageResult.DONE;
        }
        if (index >= entries.size()) {
            writeLayoutCollections(context);
            return StageResult.DONE;
        }
        Entry entry = entries.get(index++);
        try {
            renderEntry(context, entry);
            context.report().addCount(ID, "layouts", 1);
        } catch (Throwable throwable) {
            context.report().error(ID, entry.type.getUid() + "#" + entry.index, throwable);
        }
        if (index >= entries.size()) {
            writeLayoutCollections(context);
            return StageResult.DONE;
        }
        return StageResult.MORE_WORK;
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
        JsonArray slots = serializeSlots(context, layout);
        renderCategoryBackground(context, entry, layout, canvasWidth, canvasHeight);

        JsonObject metadata = new JsonObject();
        metadata.addProperty("schema_version", "mcmods-jei-layout/v4");
        metadata.addProperty("recipe_type_id", entry.type.getUid().toString());
        addRecipeIdentity(metadata, entry);
        RecipeLayoutNormalizer.write(metadata, entry.recipe);
        metadata.addProperty("layout_key", layoutKey(entry));
        metadata.addProperty("render_locale", context.minecraft().getLanguageManager().getSelected());
        metadata.addProperty("background", relative(context, backgroundPath(context, entry.type.getUid())));
        metadata.addProperty("background_contains_ingredients", false);
        metadata.addProperty("coordinate_space", "logical_pixels");
        metadata.addProperty("image_scale", IMAGE_SCALE);
        metadata.add("canvas", rect(0, 0, canvasWidth, canvasHeight));
        metadata.add("image_pixels", rect(0, 0, canvasWidth * IMAGE_SCALE, canvasHeight * IMAGE_SCALE));
        metadata.add("content", rect(PADDING, PADDING, contentWidth, contentHeight));
        JsonObject parameters = RecipeParameterExtractor.extract(entry.recipe);
        metadata.addProperty("parameter_count", parameters.size());
        metadata.add("parameters", parameters);
        metadata.add("slots", slots);
        layoutsByCategory.computeIfAbsent(entry.type.getUid().toString(), ignored -> new JsonArray()).add(metadata);
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
            canvasWidth * IMAGE_SCALE,
            canvasHeight * IMAGE_SCALE,
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
            slot.getAllIngredients().forEach(ingredient -> alternatives.add(serializeIngredient(ingredient)));
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
    private JsonObject serializeIngredient(ITypedIngredient<?> typedIngredient) {
        return serializeTypedIngredient((ITypedIngredient) typedIngredient);
    }

    private JsonArray serializeRecipeCatalysts(ExportContext context, RecipeType<?> type) {
        List<JsonObject> catalysts = new ArrayList<>();
        try {
            runtime.getRecipeManager()
                .createRecipeCatalystLookup(type)
                .includeHidden()
                .get()
                .forEach(ingredient -> {
                    try {
                        catalysts.add(serializeIngredient(ingredient));
                    } catch (Throwable throwable) {
                        context.report().error(
                            ID,
                            type.getUid() + ":catalyst",
                            throwable
                        );
                    }
                });
        } catch (Throwable throwable) {
            context.report().error(ID, type.getUid() + ":catalysts", throwable);
        }
        return JeiIngredientJson.stableUnique(catalysts);
    }

    private <T> JsonObject serializeTypedIngredient(ITypedIngredient<T> typedIngredient) {
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

    private static Path layoutCollectionPath(ExportContext context, ResourceLocation categoryId) {
        return ExportPaths.idFile(context.root(), "recipes/jei/layouts", null, categoryId, ".json");
    }

    private static Path backgroundPath(ExportContext context, ResourceLocation categoryId) {
        return ExportPaths.idFile(context.root(), "recipes/jei/backgrounds", null, categoryId, ".png");
    }

    private static String relative(ExportContext context, Path path) {
        return context.root().relativize(path).toString().replace('\\', '/');
    }

    private static String layoutKey(Entry entry) {
        return entry.recipeId + "#" + String.format("%06d", entry.index);
    }

    private void writeLayoutCollections(ExportContext context) {
        if (layoutCollectionsWritten) {
            return;
        }
        layoutCollectionsWritten = true;
        Map<String, Entry> categories = new HashMap<>();
        for (Entry entry : entries) {
            categories.putIfAbsent(entry.type.getUid().toString(), entry);
        }
        for (Map.Entry<String, JsonArray> collection : layoutsByCategory.entrySet()) {
            Entry representative = categories.get(collection.getKey());
            if (representative == null) {
                continue;
            }
            JsonObject root = new JsonObject();
            root.addProperty("schema_version", "mcmods-jei-layout-collection/v1");
            root.addProperty("recipe_type_id", collection.getKey());
            root.addProperty("count", collection.getValue().size());
            root.add("layouts", collection.getValue());
            try {
                context.writeJson(relative(context, layoutCollectionPath(context, representative.type.getUid())), root);
                context.report().addCount(ID, "layout_collection_files", 1);
            } catch (Exception exception) {
                context.report().error(ID, collection.getKey() + ":layout_collection", exception);
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ResourceLocation registeredRecipeId(
        ExportContext context,
        RecipeType type,
        IRecipeCategory category,
        Object recipe
    ) {
        try {
            ResourceLocation id = category.getRegistryName(recipe);
            if (id != null) {
                return id;
            }
        } catch (Throwable throwable) {
            context.report().error(ID, type.getUid() + ":recipe_id", throwable);
        }
        if (recipe instanceof Recipe<?> vanillaRecipe) {
            return vanillaRecipe.getId();
        }
        return null;
    }

    private static String recipeIdSource(Object recipe, ResourceLocation registeredId) {
        if (recipe instanceof Recipe<?> vanillaRecipe && vanillaRecipe.getId().equals(registeredId)) {
            return "minecraft_recipe";
        }
        return "jei_category";
    }

    private static ResourceLocation generatedRecipeId(RecipeType<?> type, int index) {
        ResourceLocation categoryId = type.getUid();
        return new ResourceLocation(
            categoryId.getNamespace(),
            categoryId.getPath() + "/__generated/" + String.format("recipe_%06d", index)
        );
    }

    private static void addRecipeIdentity(JsonObject target, Entry entry) {
        target.addProperty("recipe_id", entry.recipeId.toString());
        target.addProperty("recipe_id_source", entry.recipeIdSource);
        target.addProperty("recipe_id_canonical", entry.canonicalRecipeId);
    }

    @Override
    public String progress() {
        return index + "/" + entries.size();
    }

    @SuppressWarnings("rawtypes")
    private record Entry(
        RecipeType type,
        IRecipeCategory category,
        Object recipe,
        int index,
        ResourceLocation recipeId,
        String recipeIdSource,
        boolean canonicalRecipeId
    ) {
    }
}
