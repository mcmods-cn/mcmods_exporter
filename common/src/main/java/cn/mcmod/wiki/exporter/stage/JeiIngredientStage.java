package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.compat.jei.McWikiJeiPlugin;
import cn.mcmod.wiki.exporter.render.OffscreenRenderer;
import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.ExportStage;
import cn.mcmod.wiki.exporter.task.StageResult;
import cn.mcmod.wiki.exporter.util.ExportPaths;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.TooltipFlag;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loader- and mod-independent export for non-item JEI ingredients. This covers
 * Forge/Fabric fluids as well as mod-defined media such as Mekanism chemicals.
 */
public final class JeiIngredientStage implements ExportStage {
    public static final String ID = "jei_ingredients";

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, TypeOutput> outputs = new LinkedHashMap<>();
    private final boolean renderImages;
    private IJeiRuntime runtime;
    private int index;
    private boolean indexWritten;

    public JeiIngredientStage(boolean renderImages) {
        this.renderImages = renderImages;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void start(ExportContext context) {
        runtime = McWikiJeiPlugin.runtime();
        if (runtime == null) {
            return;
        }
        IIngredientManager manager = runtime.getIngredientManager();
        List<IIngredientType<?>> types = manager.getRegisteredIngredientTypes().stream()
            .filter(type -> type != VanillaTypes.ITEM_STACK)
            .sorted(Comparator.comparing(IIngredientType::getUid))
            .toList();
        for (IIngredientType type : types) {
            String typeUid = type.getUid();
            String typeSlug = safeSlug(typeUid);
            try {
                IIngredientHelper helper = manager.getIngredientHelper(type);
                IIngredientRenderer renderer = manager.getIngredientRenderer(type);
                List<?> ingredients = new ArrayList<>(manager.getAllIngredients(type));
                ingredients.sort(Comparator.comparing(ingredient -> safeUniqueId(helper, ingredient)));
                for (Object ingredient : ingredients) {
                    try {
                        ResourceLocation resourceId = helper.getResourceLocation(ingredient);
                        if (resourceId != null && context.matches(resourceId)) {
                            entries.add(new Entry(typeUid, typeSlug, type, helper, renderer, ingredient, resourceId));
                        }
                    } catch (Throwable throwable) {
                        context.report().error(ID, typeUid + ":resource_id", throwable);
                    }
                }
            } catch (Throwable throwable) {
                context.report().error(ID, typeUid, throwable);
            }
        }
        entries.sort(Comparator.comparing(Entry::typeUid)
            .thenComparing(entry -> entry.resourceId().toString())
            .thenComparing(entry -> safeUniqueId(entry.helper(), entry.ingredient())));
    }

    @Override
    public StageResult step(ExportContext context) throws Exception {
        if (runtime == null || index >= entries.size()) {
            writeIndex(context);
            return StageResult.DONE;
        }
        Entry entry = entries.get(index++);
        try {
            exportEntry(context, entry);
            context.report().addCount(ID, "ingredients", 1);
        } catch (Throwable throwable) {
            context.report().error(ID, entry.typeUid() + ":" + entry.resourceId(), throwable);
        }
        if (index >= entries.size()) {
            writeIndex(context);
            return StageResult.DONE;
        }
        return StageResult.MORE_WORK;
    }

    @SuppressWarnings({"rawtypes", "unchecked", "removal"})
    private void exportEntry(ExportContext context, Entry entry) throws Exception {
        IIngredientHelper helper = entry.helper();
        IIngredientRenderer renderer = entry.renderer();
        Object ingredient = entry.ingredient();
        String uniqueId = safeUniqueId(helper, ingredient);
        String hash = shortHash(uniqueId);

        JsonObject json = new JsonObject();
        json.addProperty("resource_location", entry.resourceId().toString());
        json.addProperty("unique_id", uniqueId);
        long amount = helper.getAmount(ingredient);
        if (amount >= 0) {
            json.addProperty("amount", amount);
        }
        JsonObject names = new JsonObject();
        try {
            List<Component> tooltip = renderer.getTooltip(ingredient, TooltipFlag.NORMAL);
            if (!tooltip.isEmpty()) {
                names = context.componentNames(tooltip.get(0));
            }
        } catch (Throwable throwable) {
            context.report().error(ID, entry.resourceId() + ":tooltip", throwable);
        }
        json.add("names", names);

        JsonArray tags = new JsonArray();
        try {
            helper.getTagStream(ingredient).sorted().forEach(tag -> tags.add(tag.toString()));
        } catch (Throwable ignored) {
        }
        json.add("tags", tags);

        JsonObject icons = new JsonObject();
        if (renderImages) {
            int logicalSize = Math.max(1, Math.max(renderer.getWidth(), renderer.getHeight()));
            int offsetX = (logicalSize - renderer.getWidth()) / 2;
            int offsetY = (logicalSize - renderer.getHeight()) / 2;
            for (int size : context.config().imageSizes()) {
                Path path = ingredientIconPath(context, entry, hash, size);
                OffscreenRenderer.renderGui(
                    size,
                    size,
                    logicalSize,
                    logicalSize,
                    path,
                    graphics -> renderer.render(graphics, ingredient, offsetX, offsetY)
                );
                icons.addProperty(Integer.toString(size), relative(context, path));
                context.report().addCount(ID, "images", 1);
            }
        }
        json.add("icons", icons);
        outputs.computeIfAbsent(entry.typeUid(), ignored -> new TypeOutput(entry.typeUid(), entry.typeSlug()))
            .entries().add(json);
    }

    private void writeIndex(ExportContext context) throws Exception {
        if (indexWritten) {
            return;
        }
        indexWritten = true;
        JsonArray types = new JsonArray();
        int exportedCount = 0;
        for (TypeOutput output : outputs.values()) {
            JsonObject type = new JsonObject();
            type.addProperty("ingredient_type", output.uid());
            type.addProperty("path_key", output.slug());
            type.addProperty("count", output.entries().size());
            type.add("entries", output.entries());
            types.add(type);
            exportedCount += output.entries().size();
        }
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", "mcwiki-ingredients/v1");
        root.addProperty("type_count", types.size());
        root.addProperty("ingredient_count", exportedCount);
        root.add("types", types);
        context.writeJson("ingredients/ingredients.json", root);
        context.report().addCount(ID, "types", types.size());
    }

    private static Path ingredientIconPath(ExportContext context, Entry entry, String hash, int size) {
        Path base = context.root().resolve("ingredients")
            .resolve(entry.typeSlug())
            .resolve(Integer.toString(size))
            .resolve(entry.resourceId().getNamespace());
        return ExportPaths.safeResolve(base, entry.resourceId().getPath() + "--" + hash + ".png");
    }

    @SuppressWarnings("rawtypes")
    private static String safeUniqueId(IIngredientHelper helper, Object ingredient) {
        try {
            return helper.getUniqueId(ingredient, UidContext.Recipe);
        } catch (Throwable throwable) {
            try {
                return helper.getResourceLocation(ingredient).toString();
            } catch (Throwable ignored) {
                return String.valueOf(ingredient);
            }
        }
    }

    private static String safeSlug(String value) {
        String slug = value.toLowerCase().replaceAll("[^a-z0-9._-]+", "_");
        if (slug.length() <= 72) {
            return slug;
        }
        return slug.substring(0, 56) + "_" + shortHash(value);
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(12);
            for (int index = 0; index < 6; index++) {
                output.append(String.format("%02x", digest[index]));
            }
            return output.toString();
        } catch (Exception exception) {
            return Integer.toUnsignedString(value.hashCode(), 16);
        }
    }

    private static String relative(ExportContext context, Path path) {
        return context.root().relativize(path).toString().replace('\\', '/');
    }

    @Override
    public String progress() {
        return index + "/" + entries.size();
    }

    @SuppressWarnings("rawtypes")
    private record Entry(
        String typeUid,
        String typeSlug,
        IIngredientType type,
        IIngredientHelper helper,
        IIngredientRenderer renderer,
        Object ingredient,
        ResourceLocation resourceId
    ) {
    }

    private record TypeOutput(String uid, String slug, JsonArray entries) {
        private TypeOutput(String uid, String slug) {
            this(uid, slug, new JsonArray());
        }
    }
}
