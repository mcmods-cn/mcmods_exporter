package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.config.ExportConfig;
import cn.mcmod.wiki.exporter.config.ExportProfile;
import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.ExportReport;
import cn.mcmod.wiki.exporter.util.JsonFiles;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * New8062 wire/output boundary contract. This does not fabricate a Minecraft
 * instance or Architectury Platform. Declared serialized-slot vectors are inputs;
 * actual semantics/split/normalizer/writer/JsonFiles are the implementations.
 * Stage start, live slot/ItemStack serialization, GL rendering and the no-item
 * pixel oracle are separate real-client checks, never inferred from this test.
 */
class Sys48WireMatrixContractTest {
    private static final String RESOURCE = "/audit/sys48-contract-20261115/matrix.json";
    private static final List<String> KEYS = List.of("mcmods.export.config", "mcmods.export.output",
        "mcmods.export.languages", "mcmods.export.sizes", "mcmods.export.modules", "mcmods.export.profile",
        "mcmods.export.tickBudgetMs", "mcmods.export.exit", "mcmods.export.recipeTypes.exclude",
        "mcmods.export.recipeTypes.templateOnly");
    private final Map<String, String> previous = new HashMap<>();

    @BeforeEach
    void preserveProcessProperties() {
        for (String key : KEYS) {
            previous.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
    }

    @AfterEach
    void restoreProcessProperties() {
        for (String key : KEYS) {
            if (previous.get(key) == null) System.clearProperty(key);
            else System.setProperty(key, previous.get(key));
        }
    }

    @Test
    void materialNbtChanceAndDynamicCapacityHaveIndependentExpectedRelations(@TempDir Path output) throws Exception {
        JsonObject matrix = matrix();
        Map<String, JeiLayoutTemplateJson.Split> split = new LinkedHashMap<>();
        for (JsonElement element : matrix.getAsJsonArray("vectors")) {
            JsonObject vector = element.getAsJsonObject();
            JsonArray slots = vector.getAsJsonArray("slots").deepCopy();
            JeiSlotSemantics.enrich(slots, List.of());
            JeiLayoutTemplateJson.Split actual = JeiLayoutTemplateJson.split(slots);
            String id = vector.get("id").getAsString();
            split.put(id, actual);
            assertEquals(vector.get("expected_slot_count").getAsInt(), actual.templateSlots().size(), id);
            assertEquals(actual.templateSlots().size(), actual.bindings().size(), id);
            assertTrue(actual.templateId().matches("template_[a-f0-9]{16}"), id);
            for (JsonElement slot : actual.templateSlots()) {
                assertFalse(slot.getAsJsonObject().has("alternatives"), id);
                assertFalse(slot.getAsJsonObject().has("chance"), id);
            }
            JsonObject document = new JsonObject();
            document.addProperty("vector_id", id);
            document.addProperty("template_id", actual.templateId());
            document.add("slots", actual.templateSlots());document.add("bindings", actual.bindings());
            RecipeLayoutNormalizer.write(document, new Object());
            Path path = output.resolve(id + ".json");
            JsonFiles.write(path, document);
            JsonObject persisted = read(path);
            assertEquals(document.getAsJsonArray("bindings"), persisted.getAsJsonArray("bindings"), id);
            assertEquals("unknown", persisted.get("layout_kind").getAsString(), id);
        }
        JsonObject expected = matrix.getAsJsonObject("expected_relations");
        for (JsonElement pair : expected.getAsJsonArray("same_template")) {
            JsonArray names = pair.getAsJsonArray();
            assertEquals(split.get(names.get(0).getAsString()).templateId(), split.get(names.get(1).getAsString()).templateId(), pair.toString());
        }
        for (JsonElement pair : expected.getAsJsonArray("different_template")) {
            JsonArray names = pair.getAsJsonArray();
            assertNotEquals(split.get(names.get(0).getAsString()).templateId(), split.get(names.get(1).getAsString()).templateId(), pair.toString());
        }
        assertEquals(10, split.get("stick").templateSlots().size());
        long empty = split.get("stick").bindings().asList().stream()
            .map(JsonElement::getAsJsonObject).filter(x -> !x.get("ingredient_present").getAsBoolean()).count();
        assertEquals(7, empty, "two occupied inputs + one output in a ten-cell layout");
        assertBinding(split.get("nbt_chance_a"), "input_0", "minecraft:stone", 3, "{AuditNBT:1}");
        assertBinding(split.get("nbt_chance_b"), "input_0", "minecraft:dirt", 7, "{AuditNBT:2}");
        assertEquals(0.25D, binding(split.get("nbt_chance_a"), "output_0").get("chance").getAsDouble());
        assertEquals(0.75D, binding(split.get("nbt_chance_b"), "output_0").get("chance").getAsDouble());
        assertEquals("output_1", binding(split.get("semantics_known"), "output_1").get("slot_id").getAsString());
        assertEquals("byproduct", binding(split.get("semantics_known"), "output_1").get("semantic_role").getAsString());
        JsonObject unknown = binding(split.get("semantics_unknown"), "output_1");
        assertFalse(unknown.get("chance_available").getAsBoolean());
        assertFalse(unknown.has("chance"));
        assertEquals("output", unknown.get("semantic_role").getAsString());
        assertEquals(split.get("semantics_known").templateId(), split.get("semantics_unknown").templateId());
    }

    @Test
    void unknownOrderingRemainsExplicitNullAfterActualDiskSerializer(@TempDir Path output) throws Exception {
        JsonObject document = new JsonObject();
        RecipeLayoutNormalizer.write(document, new Object());
        assertTrue(document.has("ordered") && document.get("ordered").isJsonNull(), "normalizer precondition");
        Path file = output.resolve("unknown-ordering.json");
        JsonFiles.write(file, document);
        JsonObject persisted = read(file);
        // A separate test preserves this original assertion without hiding
        // the independent geometry results if the disk serializer drops null.
        assertTrue(persisted.has("ordered"), "real disk serializer omitted required explicit ordered=null");
        assertTrue(persisted.get("ordered").isJsonNull());
        assertEquals("unknown", persisted.get("layout_kind").getAsString());
        assertEquals("unavailable", persisted.get("layout_classification_source").getAsString());
    }

    @Test
    void realCollectionWriterKeepsTypeScopesAndDeclaredTemplateOnlyBoundary(@TempDir Path game) throws Exception {
        Path configFile = game.resolve("input.properties");
        Files.writeString(configFile, "mcmods.export.output=output\nmcmods.export.languages=en_us\n"
            + "mcmods.export.recipeTypes.templateOnly=minecraft:anvil\n"
            + "mcmods.export.recipeTypes.exclude=geometryfixture:excluded\n");
        System.setProperty("mcmods.export.config", configFile.toString());
        ExportConfig config = ExportConfig.fromSystemProperties(game, false, "geometryfixture", ExportProfile.ALL);
        assertTrue(config.exportsRecipeTypeTemplatesOnly("minecraft:anvil"));
        assertTrue(config.excludesRecipeType("geometryfixture:excluded"));
        assertFalse(config.exportsRecipeTypeTemplatesOnly("geometryfixture:semantics"));
        ExportContext context = new ExportContext(null, config, config.outputRoot(), new ExportReport());
        assertNull(context.minecraft(), "No fabricated Minecraft/Platform instance in this boundary contract");
        JeiRecipeGuiStage stage = new JeiRecipeGuiStage();
        JsonObject matrix = matrix();
        Map<String, JsonObject> byId = new LinkedHashMap<>();
        matrix.getAsJsonArray("vectors").forEach(e -> byId.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject()));
        Map<String, String> ids = new LinkedHashMap<>();
        addBoundary(stage, "minecraft:crafting", List.of(byId.get("stick"), byId.get("door")), false, ids);
        addBoundary(stage, "geometryfixture:mirror", List.of(byId.get("stick")), false, ids);
        addBoundary(stage, "geometryfixture:dynamic", List.of(byId.get("dynamic_two"), byId.get("dynamic_five")), false, ids);
        addBoundary(stage, "minecraft:anvil", List.of(byId.get("anvil")), true, ids);
        addBoundary(stage, "geometryfixture:semantics", List.of(byId.get("semantics_known"), byId.get("semantics_unknown")), false, ids);
        // Deliberately no synthetic runtime/start: this is the valid writer
        // boundary after category selection/rendering, NOT proof of exclusion
        // or renderEntry's templateOnly omission. The real-client fixture owns
        // those missing upstream assertions and all background pixels.
        Method write = JeiRecipeGuiStage.class.getDeclaredMethod("writeOutputs", ExportContext.class);
        write.setAccessible(true);write.invoke(stage, context);
        JsonObject crafting = read(context.root().resolve("recipes/jei/templates/minecraft/crafting.json"));
        JsonObject mirror = read(context.root().resolve("recipes/jei/templates/geometryfixture/mirror.json"));
        assertEquals("minecraft:crafting", crafting.get("recipe_type_id").getAsString());
        assertEquals("geometryfixture:mirror", mirror.get("recipe_type_id").getAsString());
        assertEquals(crafting.getAsJsonArray("templates").get(0).getAsJsonObject().get("template_id"),
            mirror.getAsJsonArray("templates").get(0).getAsJsonObject().get("template_id"));
        JsonObject dynamic = read(context.root().resolve("recipes/jei/templates/geometryfixture/dynamic.json"));
        assertEquals(2, dynamic.get("template_count").getAsInt());
        JsonObject omitted = read(context.root().resolve("recipes/jei/recipes/minecraft/anvil.json"));
        assertTrue(omitted.get("recipes_omitted").getAsBoolean());
        assertEquals("templates_only", omitted.get("export_mode").getAsString());
        assertEquals(0, omitted.get("count").getAsInt());
        assertTrue(omitted.getAsJsonArray("recipes").isEmpty());
        JsonObject semantics = read(context.root().resolve("recipes/jei/recipes/geometryfixture/semantics.json"));
        assertEquals(2, semantics.get("count").getAsInt());
        assertFalse(semantics.get("recipes_omitted").getAsBoolean());
        for (JsonElement recipe : semantics.getAsJsonArray("recipes")) {
            assertTrue(recipe.getAsJsonObject().has("ordered"), "real writer omitted required unknown ordered");
            assertTrue(recipe.getAsJsonObject().get("ordered").isJsonNull());
        }
        JsonObject index = read(context.root().resolve("recipes/jei/categories.json"));
        assertEquals("mcmods-jei-categories/v7", index.get("schema_version").getAsString());
        assertEquals(5, index.get("category_count").getAsInt());
        assertEquals(7, index.get("recipe_count").getAsInt());
    }

    @SuppressWarnings("unchecked")
    private static void addBoundary(JeiRecipeGuiStage stage, String typeId, List<JsonObject> vectors,
                                    boolean templatesOnly, Map<String, String> ids) throws Exception {
        RecipeType<Object> type = new RecipeType<>(new ResourceLocation(typeId), Object.class);
        BoundaryCategory category = new BoundaryCategory(type);
        Class<?> entryClass = Class.forName(JeiRecipeGuiStage.class.getName() + "$Entry");
        Constructor<?> constructor = entryClass.getDeclaredConstructors()[0];constructor.setAccessible(true);
        List<Object> entries = (List<Object>) value(stage, "entries");
        entries.add(constructor.newInstance(entries.size(), type, category, new Object(), 0,
            new ResourceLocation("geometryfixture", "boundary_" + entries.size()), "declared_wire_contract", true, null));
        Map<String, LinkedHashMap<String, JsonObject>> allTemplates = (Map<String, LinkedHashMap<String, JsonObject>>) value(stage, "templatesByCategory");
        Map<String, JsonArray> allRecipes = (Map<String, JsonArray>) value(stage, "recipesByCategory");
        LinkedHashMap<String, JsonObject> templates = new LinkedHashMap<>();JsonArray recipes = new JsonArray();
        for (JsonObject vector : vectors) {
            JsonArray slots = vector.getAsJsonArray("slots").deepCopy();JeiSlotSemantics.enrich(slots, List.of());
            JeiLayoutTemplateJson.Split split = JeiLayoutTemplateJson.split(slots);
            ids.put(typeId + "|" + vector.get("id").getAsString(), split.templateId());
            JsonObject template = new JsonObject();template.addProperty("schema_version", "mcmods-jei-layout-template/v2");
            template.addProperty("template_id", split.templateId());template.addProperty("background", "PENDING_REAL_GL_NOT_A_PNG");
            template.addProperty("slot_count", split.templateSlots().size());template.add("slots", split.templateSlots());
            templates.putIfAbsent(split.templateId(), template);
            if (!templatesOnly) {
                JsonObject recipe = new JsonObject();recipe.addProperty("schema_version", "mcmods-jei-recipe-bindings/v2");
                recipe.addProperty("recipe_type_id", typeId);recipe.addProperty("recipe_id", "geometryfixture:" + vector.get("id").getAsString());
                recipe.addProperty("template_id", split.templateId());recipe.addProperty("binding_count", split.bindings().size());
                recipe.add("bindings", split.bindings());RecipeLayoutNormalizer.write(recipe, new Object());recipes.add(recipe);
            }
        }
        allTemplates.put(typeId, templates);allRecipes.put(typeId, recipes);
        JsonObject meta = new JsonObject();meta.addProperty("recipe_type_id", typeId);
        ((JsonArray) value(stage, "categoryIndex")).add(meta);
        ((Map<String, JsonObject>) value(stage, "categoryMetadataById")).put(typeId, meta);
        recipes.forEach(e -> ((JsonArray) valueUnchecked(stage, "recipeIndex")).add(e.deepCopy()));
    }

    private static Object value(JeiRecipeGuiStage stage, String name) throws Exception {
        Field field = JeiRecipeGuiStage.class.getDeclaredField(name);field.setAccessible(true);return field.get(stage);
    }
    private static Object valueUnchecked(JeiRecipeGuiStage stage, String name) {
        try { return value(stage, name); } catch (Exception e) { throw new AssertionError(e); }
    }
    private static JsonObject matrix() throws Exception {
        try (InputStream input = Sys48WireMatrixContractTest.class.getResourceAsStream(RESOURCE)) {
            assertNotNull(input, "new declared matrix is required");
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    private static JsonObject read(Path path) throws Exception { return JsonParser.parseString(Files.readString(path)).getAsJsonObject(); }
    private static JsonObject binding(JeiLayoutTemplateJson.Split split, String id) {
        return split.bindings().asList().stream().map(JsonElement::getAsJsonObject)
            .filter(x -> id.equals(x.get("slot_id").getAsString())).findFirst().orElseThrow();
    }
    private static void assertBinding(JeiLayoutTemplateJson.Split split, String slot, String item, int count, String nbt) {
        JsonObject ingredient = binding(split, slot).getAsJsonArray("alternatives").get(0).getAsJsonObject();
        assertEquals(item, ingredient.get("item").getAsString());assertEquals(count, ingredient.get("count").getAsInt());
        assertEquals(nbt, ingredient.get("nbt_snbt").getAsString());
    }

    private record BoundaryCategory(RecipeType<Object> getRecipeType) implements IRecipeCategory<Object> {
        @Override public Component getTitle() { return Component.literal("Declared writer boundary, not a running game"); }
        @Override public int getWidth() { return 180; }
        @Override public int getHeight() { return 90; }
        @Override @SuppressWarnings("removal") public IDrawable getBackground() { throw new AssertionError("real GL fixture required"); }
        @Override public IDrawable getIcon() { throw new AssertionError("real GL fixture required"); }
        @Override public void setRecipe(IRecipeLayoutBuilder builder, Object recipe, IFocusGroup focuses) { throw new AssertionError("real JEI fixture required"); }
    }
}
