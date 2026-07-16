package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.compat.jei.McModsJeiPlugin;
import cn.mcmod.wiki.exporter.render.OffscreenRenderer;
import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.ExportStage;
import cn.mcmod.wiki.exporter.task.StageResult;
import cn.mcmod.wiki.exporter.util.ExportPaths;
import cn.mcmod.wiki.exporter.util.ProbabilityText;
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
import net.minecraft.core.registries.BuiltInRegistries;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Exports one ingredient-free GUI background per layout template together with
 * one deduplicated slot-template collection per JEI RecipeType. Recipe files
 * only bind ingredients to stable slot IDs; the website resolves coordinates
 * through the referenced template and draws separately exported icons.
 */
public final class JeiRecipeGuiStage implements ExportStage {
    public static final String ID = "jei_recipe_gui";
    private static final int PADDING = 4;
    private static final int IMAGE_SCALE = 4;

    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> renderedTemplates = new HashSet<>();
    private final Map<String, LinkedHashMap<String, JsonObject>> templatesByCategory = new HashMap<>();
    private final Map<String, JsonArray> recipesByCategory = new HashMap<>();
    private final Map<String, JsonObject> categoryMetadataById = new HashMap<>();
    private final Map<String, JsonObject> recipeIndexByKey = new HashMap<>();
    private JsonArray categoryIndex = new JsonArray();
    private JsonArray recipeIndex = new JsonArray();
    private JsonObject filterSummary = new JsonObject();
    private IJeiRuntime runtime;
    private int index;
    private boolean outputsWritten;

    @Override
    public String id() {
        return ID;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked", "removal"})
    public void start(ExportContext context) throws Exception {
        entries.clear();
        renderedTemplates.clear();
        templatesByCategory.clear();
        recipesByCategory.clear();
        categoryMetadataById.clear();
        recipeIndexByKey.clear();
        categoryIndex = new JsonArray();
        recipeIndex = new JsonArray();
        filterSummary = new JsonObject();
        index = 0;
        outputsWritten = false;
        runtime = McModsJeiPlugin.runtime();
        if (runtime == null) {
            context.report().addCount(ID, "runtime_unavailable", 1);
            return;
        }
        List<RecipeType<?>> types = runtime.getJeiHelpers().getAllRecipeTypes()
            .sorted(Comparator.comparing(type -> type.getUid().toString()))
            .toList();
        List<Entry> discovered = new ArrayList<>();
        int ordinal = 0;
        for (RecipeType type : types) {
            IRecipeCategory category = runtime.getRecipeManager().getRecipeCategory(type);
            List<?> recipes = runtime.getRecipeManager().createRecipeLookup(type).includeHidden().get().toList();
            int localIndex = 0;
            for (Object recipe : recipes) {
                ResourceLocation registeredId = registeredRecipeId(context, type, category, recipe);
                int recipeIndexInCategory = localIndex++;
                boolean canonicalRecipeId = registeredId != null;
                ResourceLocation recipeId = canonicalRecipeId
                    ? registeredId
                    : generatedRecipeId(type, recipeIndexInCategory);
                String recipeIdSource = canonicalRecipeId
                    ? recipeIdSource(recipe, registeredId)
                    : "generated_index";
                Entry exportEntry = new Entry(
                    ordinal++,
                    type,
                    category,
                    recipe,
                    recipeIndexInCategory,
                    recipeId,
                    recipeIdSource,
                    canonicalRecipeId,
                    underlyingRecipeTypeId(recipe)
                );
                discovered.add(exportEntry);
            }
        }

        List<JeiRecipeSelection.Candidate> candidates = discovered.stream()
            .map(entry -> new JeiRecipeSelection.Candidate(
                entry.ordinal,
                entry.type.getUid().toString(),
                entry.recipeId.toString(),
                entry.canonicalRecipeId,
                entry.underlyingRecipeTypeId
            ))
            .toList();
        JeiRecipeSelection.Result selection = JeiRecipeSelection.select(candidates, context.config().namespaces());
        Map<String, Integer> excludedByReason = new HashMap<>();
        Map<String, Map<String, Integer>> excludedByCategory = new HashMap<>();
        Map<String, Integer> categoryCounts = new HashMap<>();
        Map<String, Entry> categoryRepresentatives = new HashMap<>();
        for (Entry exportEntry : discovered) {
            String exclusion = selection.exclusions().get(exportEntry.ordinal);
            if (exclusion != null) {
                excludedByReason.merge(exclusion, 1, Integer::sum);
                excludedByCategory.computeIfAbsent(
                    exportEntry.type.getUid().toString(),
                    ignored -> new HashMap<>()
                ).merge(exclusion, 1, Integer::sum);
                context.report().addCount(ID, "excluded_" + exclusion, 1);
                continue;
            }
            entries.add(exportEntry);
            String categoryId = exportEntry.type.getUid().toString();
            categoryCounts.merge(categoryId, 1, Integer::sum);
            categoryRepresentatives.putIfAbsent(categoryId, exportEntry);

            JsonObject recipeJson = new JsonObject();
            recipeJson.addProperty("recipe_type_id", categoryId);
            addRecipeIdentity(recipeJson, exportEntry);
            RecipeLayoutNormalizer.write(recipeJson, exportEntry.recipe);
            recipeJson.addProperty("recipe_key", recipeKey(exportEntry));
            recipeJson.addProperty(
                "template_collection",
                relative(context, templateCollectionPath(context, exportEntry.type.getUid()))
            );
            recipeJson.addProperty(
                "recipe_collection",
                relative(context, recipeCollectionPath(context, exportEntry.type.getUid()))
            );
            recipeJson.addProperty("layout_available", false);
            recipeJson.addProperty("background_available", false);
            recipeIndex.add(recipeJson);
            recipeIndexByKey.put(recipeIndexKey(exportEntry), recipeJson);
        }

        List<String> categoryIds = new ArrayList<>(categoryRepresentatives.keySet());
        categoryIds.sort(String::compareTo);
        for (String categoryId : categoryIds) {
            Entry representative = categoryRepresentatives.get(categoryId);
            RecipeType type = representative.type;
            IRecipeCategory category = representative.category;
            templatesByCategory.putIfAbsent(categoryId, new LinkedHashMap<>());
            recipesByCategory.putIfAbsent(categoryId, new JsonArray());
            JsonObject categoryJson = new JsonObject();
            categoryJson.addProperty("recipe_type_id", categoryId);
            addLocalizedComponent(context, categoryJson, "title", category.getTitle());
            categoryJson.addProperty("width", category.getWidth());
            categoryJson.addProperty("height", category.getHeight());
            categoryJson.addProperty("recipe_count", categoryCounts.get(categoryId));
            categoryJson.addProperty("template_collection", relative(context, templateCollectionPath(context, type.getUid())));
            categoryJson.addProperty("recipe_collection", relative(context, recipeCollectionPath(context, type.getUid())));
            categoryJson.addProperty("image_scale", IMAGE_SCALE);
            categoryJson.add("canvas", rect(0, 0, category.getWidth() + PADDING * 2, category.getHeight() + PADDING * 2));
            JsonArray catalysts = serializeRecipeCatalysts(context, type);
            categoryJson.addProperty("catalyst_count", catalysts.size());
            categoryJson.add("catalysts", catalysts);
            categoryIndex.add(categoryJson);
            categoryMetadataById.put(categoryId, categoryJson);
        }
        filterSummary = filterSummary(discovered.size(), entries.size(), excludedByReason, excludedByCategory);
    }

    @Override
    public StageResult step(ExportContext context) {
        if (runtime == null) {
            return StageResult.DONE;
        }
        if (index >= entries.size()) {
            writeOutputs(context);
            return StageResult.DONE;
        }
        Entry entry = entries.get(index++);
        try {
            renderEntry(context, entry);
            context.report().addCount(ID, "recipe_bindings", 1);
        } catch (Throwable throwable) {
            context.report().error(ID, entry.type.getUid() + "#" + entry.index, throwable);
        }
        if (index >= entries.size()) {
            writeOutputs(context);
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
        JsonArray serializedSlots = serializeSlots(context, layout);
        List<ProbabilityText.Draw> drawnProbabilities;
        try {
            drawnProbabilities = OffscreenRenderer.captureProbabilityText(
                canvasWidth,
                canvasHeight,
                graphics -> layout.drawRecipe(graphics, -1000, -1000)
            );
        } catch (Throwable throwable) {
            drawnProbabilities = List.of();
            context.report().error(ID, recipeKey(entry) + ":jei_probability_capture", throwable);
        }
        JeiSlotSemantics.enrich(serializedSlots, drawnProbabilities);
        JeiLayoutTemplateJson.Split split = JeiLayoutTemplateJson.split(serializedSlots);
        renderTemplateBackground(context, entry, layout, split.templateId(), canvasWidth, canvasHeight);

        String categoryId = entry.type.getUid().toString();
        LinkedHashMap<String, JsonObject> templates = templatesByCategory.computeIfAbsent(
            categoryId,
            ignored -> new LinkedHashMap<>()
        );
        if (!templates.containsKey(split.templateId())) {
            JsonObject template = new JsonObject();
            template.addProperty("schema_version", "mcmods-jei-layout-template/v2");
            template.addProperty("template_id", split.templateId());
            template.addProperty(
                "background",
                relative(context, templateBackgroundPath(context, entry.type.getUid(), split.templateId()))
            );
            template.addProperty("background_contains_ingredients", false);
            template.addProperty("slot_count", split.templateSlots().size());
            template.add("slots", split.templateSlots());
            templates.put(split.templateId(), template);
            context.report().addCount(ID, "layout_templates", 1);
        } else {
            mergeTemplateSemantics(
                templates.get(split.templateId()).getAsJsonArray("slots"),
                split.templateSlots()
            );
            context.report().addCount(ID, "layout_template_reuses", 1);
        }

        JsonObject recipeBindings = new JsonObject();
        recipeBindings.addProperty("schema_version", "mcmods-jei-recipe-bindings/v2");
        recipeBindings.addProperty("recipe_type_id", categoryId);
        addRecipeIdentity(recipeBindings, entry);
        RecipeLayoutNormalizer.write(recipeBindings, entry.recipe);
        recipeBindings.addProperty("recipe_key", recipeKey(entry));
        recipeBindings.addProperty("template_id", split.templateId());
        recipeBindings.addProperty("render_locale", context.minecraft().getLanguageManager().getSelected());
        JsonObject parameters = RecipeParameterExtractor.extract(entry.recipe);
        recipeBindings.addProperty("parameter_count", parameters.size());
        recipeBindings.add("parameters", parameters);
        recipeBindings.addProperty("binding_count", split.bindings().size());
        recipeBindings.add("bindings", split.bindings());
        recipesByCategory.computeIfAbsent(categoryId, ignored -> new JsonArray()).add(recipeBindings);

        JsonObject indexEntry = recipeIndexByKey.get(recipeIndexKey(entry));
        if (indexEntry != null) {
            indexEntry.addProperty("template_id", split.templateId());
            indexEntry.addProperty(
                "background",
                relative(context, templateBackgroundPath(context, entry.type.getUid(), split.templateId()))
            );
            indexEntry.addProperty("layout_available", true);
            indexEntry.addProperty("background_available", true);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void renderTemplateBackground(
        ExportContext context,
        Entry entry,
        IRecipeLayoutDrawable layout,
        String templateId,
        int canvasWidth,
        int canvasHeight
    ) throws Exception {
        String templateKey = entry.type.getUid() + "|" + templateId;
        if (renderedTemplates.contains(templateKey)) {
            return;
        }
        for (IRecipeSlotView slot : layout.getRecipeSlotsView().getSlotViews()) {
            if (slot instanceof IRecipeSlotDrawable drawable) {
                // An empty display override keeps the slot/background but makes the
                // ingredient renderer return Optional.empty().
                drawable.createDisplayOverrides();
            }
        }
        OffscreenRenderer.renderGuiSuppressingProbabilityText(
            canvasWidth * IMAGE_SCALE,
            canvasHeight * IMAGE_SCALE,
            canvasWidth,
            canvasHeight,
            templateBackgroundPath(context, entry.type.getUid(), templateId),
            graphics -> layout.drawRecipe(graphics, -1000, -1000)
        );
        renderedTemplates.add(templateKey);
        context.report().addCount(ID, "template_backgrounds", 1);
    }

    @SuppressWarnings("removal")
    private JsonArray serializeSlots(ExportContext context, IRecipeLayoutDrawable<?> layout) {
        JsonArray slots = new JsonArray();
        int slotIndex = 0;
        for (IRecipeSlotView slot : layout.getRecipeSlotsView().getSlotViews()) {
            JsonObject json = new JsonObject();
            json.addProperty("index", slotIndex++);
            String jeiRole = slot.getRole().name().toLowerCase(Locale.ROOT);
            json.addProperty("role", jeiRole);
            json.addProperty("jei_role", jeiRole);
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
                JsonArray tooltip = new JsonArray();
                try {
                    for (Component component : drawable.getTooltip()) {
                        JsonObject line = new JsonObject();
                        line.addProperty("text", component.getString());
                        line.add("texts", context.componentNames(component));
                        if (component.getContents() instanceof TranslatableContents translatable) {
                            line.addProperty("translation_key", translatable.getKey());
                        }
                        tooltip.add(line);
                    }
                } catch (Throwable throwable) {
                    context.report().error(ID, "slot_" + (slotIndex - 1) + ":tooltip", throwable);
                }
                json.add("_jei_tooltip", tooltip);
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

    private static Path templateCollectionPath(ExportContext context, ResourceLocation categoryId) {
        return ExportPaths.idFile(context.root(), "recipes/jei/templates", null, categoryId, ".json");
    }

    private static Path recipeCollectionPath(ExportContext context, ResourceLocation categoryId) {
        return ExportPaths.idFile(context.root(), "recipes/jei/recipes", null, categoryId, ".json");
    }

    private static Path templateBackgroundPath(
        ExportContext context,
        ResourceLocation categoryId,
        String templateId
    ) {
        Path namespaceRoot = context.root().resolve("recipes/jei/backgrounds").resolve(categoryId.getNamespace());
        Path categoryRoot = ExportPaths.safeResolve(namespaceRoot, categoryId.getPath());
        return ExportPaths.safeResolve(categoryRoot, templateId + ".png");
    }

    private static String relative(ExportContext context, Path path) {
        return context.root().relativize(path).toString().replace('\\', '/');
    }

    private static String recipeKey(Entry entry) {
        return entry.recipeId + "#" + String.format("%06d", entry.index);
    }

    private static String recipeIndexKey(Entry entry) {
        return entry.type.getUid() + "|" + recipeKey(entry);
    }

    private void writeOutputs(ExportContext context) {
        if (outputsWritten) {
            return;
        }
        outputsWritten = true;
        Map<String, Entry> categories = new HashMap<>();
        for (Entry entry : entries) {
            categories.putIfAbsent(entry.type.getUid().toString(), entry);
        }
        List<String> categoryIds = new ArrayList<>(categories.keySet());
        categoryIds.sort(String::compareTo);
        for (String categoryId : categoryIds) {
            Entry representative = categories.get(categoryId);
            if (representative == null) {
                continue;
            }

            LinkedHashMap<String, JsonObject> templates = templatesByCategory.getOrDefault(
                categoryId,
                new LinkedHashMap<>()
            );
            JsonArray templateArray = new JsonArray();
            templates.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> templateArray.add(entry.getValue()));
            JsonArray recipes = recipesByCategory.getOrDefault(categoryId, new JsonArray());

            int contentWidth = representative.category.getWidth();
            int contentHeight = representative.category.getHeight();
            int canvasWidth = contentWidth + PADDING * 2;
            int canvasHeight = contentHeight + PADDING * 2;

            JsonObject templateRoot = new JsonObject();
            templateRoot.addProperty("schema_version", "mcmods-jei-template-collection/v2");
            templateRoot.addProperty("recipe_type_id", categoryId);
            templateRoot.addProperty("coordinate_space", "logical_pixels");
            templateRoot.addProperty("image_scale", IMAGE_SCALE);
            templateRoot.add("canvas", rect(0, 0, canvasWidth, canvasHeight));
            templateRoot.add("image_pixels", rect(0, 0, canvasWidth * IMAGE_SCALE, canvasHeight * IMAGE_SCALE));
            templateRoot.add("content", rect(PADDING, PADDING, contentWidth, contentHeight));
            templateRoot.addProperty("template_count", templateArray.size());
            templateRoot.add("templates", templateArray);

            JsonObject recipeRoot = new JsonObject();
            recipeRoot.addProperty("schema_version", "mcmods-jei-recipe-collection/v2");
            recipeRoot.addProperty("recipe_type_id", categoryId);
            recipeRoot.addProperty(
                "template_collection",
                relative(context, templateCollectionPath(context, representative.type.getUid()))
            );
            recipeRoot.addProperty("count", recipes.size());
            recipeRoot.add("recipes", recipes);

            JsonObject categoryMetadata = categoryMetadataById.get(categoryId);
            if (categoryMetadata != null) {
                categoryMetadata.addProperty("template_count", templateArray.size());
                categoryMetadata.addProperty("background_count", templateArray.size());
                categoryMetadata.addProperty("exported_recipe_count", recipes.size());
            }

            try {
                context.writeJson(
                    relative(context, templateCollectionPath(context, representative.type.getUid())),
                    templateRoot
                );
                context.report().addCount(ID, "template_collection_files", 1);
            } catch (Exception exception) {
                context.report().error(ID, categoryId + ":template_collection", exception);
            }
            try {
                context.writeJson(
                    relative(context, recipeCollectionPath(context, representative.type.getUid())),
                    recipeRoot
                );
                context.report().addCount(ID, "recipe_collection_files", 1);
            } catch (Exception exception) {
                context.report().error(ID, categoryId + ":recipe_collection", exception);
            }
        }

        JsonObject indexJson = new JsonObject();
        indexJson.addProperty("schema_version", "mcmods-jei-categories/v6");
        indexJson.addProperty("category_count", categoryIndex.size());
        indexJson.addProperty("recipe_count", entries.size());
        indexJson.add("filter_summary", filterSummary);
        indexJson.add("categories", categoryIndex);
        indexJson.add("recipes", recipeIndex);
        try {
            context.writeJson("recipes/jei/categories.json", indexJson);
        } catch (Exception exception) {
            context.report().error(ID, "categories_index", exception);
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

    private static String underlyingRecipeTypeId(Object recipe) {
        if (!(recipe instanceof Recipe<?> minecraftRecipe)) {
            return null;
        }
        ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(minecraftRecipe.getType());
        return type == null ? null : type.toString();
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
        target.addProperty("origin_kind", entry.canonicalRecipeId ? "registered_recipe" : "jei_generated_recipe");
        if (entry.underlyingRecipeTypeId != null) {
            target.addProperty("underlying_recipe_type_id", entry.underlyingRecipeTypeId);
        }
        RecipeSourceMetadata.add(
            target,
            entry.canonicalRecipeId ? entry.recipeId : entry.type.getUid(),
            entry.canonicalRecipeId ? "recipe_id_namespace" : "recipe_type_namespace"
        );
    }

    private static JsonObject filterSummary(
        int discovered,
        int exported,
        Map<String, Integer> excludedByReason,
        Map<String, Map<String, Integer>> excludedByCategory
    ) {
        JsonObject summary = new JsonObject();
        summary.addProperty("policy", "source_recipe_namespace_with_recipe_id_deduplication");
        summary.addProperty("discovered_recipe_presentations", discovered);
        summary.addProperty("exported_recipe_presentations", exported);
        summary.addProperty("excluded_recipe_presentations", discovered - exported);
        JsonObject reasons = new JsonObject();
        excludedByReason.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> reasons.addProperty(entry.getKey(), entry.getValue()));
        summary.add("excluded_by_reason", reasons);
        JsonObject categories = new JsonObject();
        excludedByCategory.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(category -> {
                JsonObject counts = new JsonObject();
                category.getValue().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(reason -> counts.addProperty(reason.getKey(), reason.getValue()));
                categories.add(category.getKey(), counts);
            });
        summary.add("excluded_by_recipe_type", categories);
        return summary;
    }

    private static void mergeTemplateSemantics(JsonArray targetSlots, JsonArray incomingSlots) {
        Map<String, JsonObject> targetById = new HashMap<>();
        targetSlots.forEach(element -> {
            JsonObject slot = element.getAsJsonObject();
            targetById.put(slot.get("slot_id").getAsString(), slot);
        });
        incomingSlots.forEach(element -> {
            JsonObject incoming = element.getAsJsonObject();
            JsonObject target = targetById.get(incoming.get("slot_id").getAsString());
            if (target != null
                && "byproduct".equals(incoming.get("role").getAsString())
                && !"byproduct".equals(target.get("role").getAsString())) {
                target.addProperty("role", "byproduct");
            }
        });
    }

    @Override
    public String progress() {
        return index + "/" + entries.size();
    }

    @SuppressWarnings("rawtypes")
    private record Entry(
        int ordinal,
        RecipeType type,
        IRecipeCategory category,
        Object recipe,
        int index,
        ResourceLocation recipeId,
        String recipeIdSource,
        boolean canonicalRecipeId,
        String underlyingRecipeTypeId
    ) {
    }
}
