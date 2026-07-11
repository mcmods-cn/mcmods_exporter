package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.OnceStage;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.architectury.platform.Mod;
import dev.architectury.platform.Platform;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RegistryDataStage extends OnceStage {
    public static final String ID = "registry_data";

    @Override
    public String id() {
        return ID;
    }

    @Override
    protected void run(ExportContext context) throws Exception {
        exportItems(context);
        exportBlocks(context);
        exportEntities(context);
        exportEffects(context);
        exportEnchantments(context);
        exportPotions(context);
        exportFluids(context);
        exportKeyMappings(context);
        exportMods(context);
    }

    private static void exportItems(ExportContext context) throws Exception {
        JsonArray data = new JsonArray();
        BuiltInRegistries.ITEM.entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
            .filter(entry -> context.matches(entry.getKey().location()))
            .forEach(entry -> {
                ResourceLocation id = entry.getKey().location();
                Item item = entry.getValue();
                ItemStack stack = item.getDefaultInstance();
                JsonObject json = baseNamed(context, id, item.getDescriptionId());
                json.addProperty("max_stack_size", item.getMaxStackSize());
                json.addProperty("max_damage", item.getMaxDamage());
                json.addProperty("edible", item.isEdible());
                json.addProperty("rarity", stack.getRarity().name().toLowerCase());
                if (stack.hasTag()) {
                    json.addProperty("default_nbt_snbt", stack.getTag().toString());
                }
                Block block = Block.byItem(item);
                ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
                if (blockId != null && block != net.minecraft.world.level.block.Blocks.AIR) {
                    json.addProperty("block", blockId.toString());
                }
                data.add(json);
            });
        writeRegistry(context, "items", data);
    }

    private static void exportBlocks(ExportContext context) throws Exception {
        JsonArray data = new JsonArray();
        BuiltInRegistries.BLOCK.entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
            .filter(entry -> context.matches(entry.getKey().location()))
            .forEach(entry -> {
                ResourceLocation id = entry.getKey().location();
                Block block = entry.getValue();
                BlockState state = block.defaultBlockState();
                JsonObject json = baseNamed(context, id, block.getDescriptionId());
                json.addProperty("air", state.isAir());
                json.addProperty("can_occlude", state.canOcclude());
                json.addProperty("render_shape", state.getRenderShape().name().toLowerCase());
                json.addProperty("has_block_entity", block instanceof EntityBlock);
                Item item = block.asItem();
                if (item != Items.AIR) {
                    json.addProperty("item", BuiltInRegistries.ITEM.getKey(item).toString());
                }
                JsonObject properties = new JsonObject();
                JsonObject defaultState = new JsonObject();
                for (Property<?> property : block.getStateDefinition().getProperties()) {
                    JsonArray values = new JsonArray();
                    property.getPossibleValues().forEach(value -> values.add(String.valueOf(value)));
                    properties.add(property.getName(), values);
                    defaultState.addProperty(property.getName(), stateValue(state, property));
                }
                json.add("state_properties", properties);
                json.add("default_state", defaultState);
                data.add(json);
            });
        writeRegistry(context, "blocks", data);
    }

    private static void exportEntities(ExportContext context) throws Exception {
        JsonArray data = new JsonArray();
        BuiltInRegistries.ENTITY_TYPE.entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
            .filter(entry -> context.matches(entry.getKey().location()))
            .forEach(entry -> {
                ResourceLocation id = entry.getKey().location();
                EntityType<?> type = entry.getValue();
                JsonObject json = baseNamed(context, id, type.getDescriptionId());
                json.addProperty("category", type.getCategory().getName());
                json.addProperty("width", type.getWidth());
                json.addProperty("height", type.getHeight());
                json.addProperty("can_summon", type.canSummon());
                json.addProperty("can_serialize", type.canSerialize());
                json.addProperty("fire_immune", type.fireImmune());
                json.addProperty("client_tracking_range", type.clientTrackingRange());
                json.addProperty("update_interval", type.updateInterval());
                data.add(json);
            });
        writeRegistry(context, "entity_types", data);
    }

    private static void exportEffects(ExportContext context) throws Exception {
        JsonArray data = new JsonArray();
        BuiltInRegistries.MOB_EFFECT.entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
            .filter(entry -> context.matches(entry.getKey().location()))
            .forEach(entry -> {
                ResourceLocation id = entry.getKey().location();
                MobEffect effect = entry.getValue();
                JsonObject json = baseNamed(context, id, effect.getDescriptionId());
                json.addProperty("category", effect.getCategory().name().toLowerCase());
                json.addProperty("color_rgb", effect.getColor());
                json.addProperty("instant", effect.isInstantenous());
                json.addProperty("beneficial", effect.isBeneficial());
                data.add(json);
            });
        writeRegistry(context, "mob_effects", data);
    }

    private static void exportEnchantments(ExportContext context) throws Exception {
        JsonArray data = new JsonArray();
        BuiltInRegistries.ENCHANTMENT.entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
            .filter(entry -> context.matches(entry.getKey().location()))
            .forEach(entry -> {
                ResourceLocation id = entry.getKey().location();
                Enchantment enchantment = entry.getValue();
                JsonObject json = baseNamed(context, id, enchantment.getDescriptionId());
                json.addProperty("category", enchantment.category.name().toLowerCase());
                json.addProperty("rarity", enchantment.getRarity().name().toLowerCase());
                json.addProperty("rarity_weight", enchantment.getRarity().getWeight());
                json.addProperty("min_level", enchantment.getMinLevel());
                json.addProperty("max_level", enchantment.getMaxLevel());
                json.addProperty("treasure_only", enchantment.isTreasureOnly());
                json.addProperty("curse", enchantment.isCurse());
                json.addProperty("tradeable", enchantment.isTradeable());
                json.addProperty("discoverable", enchantment.isDiscoverable());
                data.add(json);
            });
        writeRegistry(context, "enchantments", data);
    }

    private static void exportPotions(ExportContext context) throws Exception {
        JsonArray data = new JsonArray();
        BuiltInRegistries.POTION.entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
            .filter(entry -> context.matches(entry.getKey().location()))
            .forEach(entry -> {
                ResourceLocation id = entry.getKey().location();
                Potion potion = entry.getValue();
                String translationKey = potion.getName("item.minecraft.potion.effect.");
                JsonObject json = baseNamed(context, id, translationKey);
                json.addProperty("color_rgb", PotionUtils.getColor(potion));
                json.addProperty("has_instant_effects", potion.hasInstantEffects());
                JsonArray effects = new JsonArray();
                for (MobEffectInstance instance : potion.getEffects()) {
                    JsonObject effect = new JsonObject();
                    ResourceLocation effectId = BuiltInRegistries.MOB_EFFECT.getKey(instance.getEffect());
                    effect.addProperty("effect", effectId == null ? "unknown" : effectId.toString());
                    effect.addProperty("duration_ticks", instance.getDuration());
                    effect.addProperty("amplifier", instance.getAmplifier());
                    effect.addProperty("ambient", instance.isAmbient());
                    effect.addProperty("visible", instance.isVisible());
                    effect.addProperty("show_icon", instance.showIcon());
                    effects.add(effect);
                }
                json.add("effects", effects);
                JsonArray forms = new JsonArray();
                forms.add("potion");
                forms.add("splash_potion");
                forms.add("lingering_potion");
                forms.add("tipped_arrow");
                json.add("rendered_forms", forms);
                data.add(json);
            });
        writeRegistry(context, "potions", data);
    }

    private static void exportFluids(ExportContext context) throws Exception {
        JsonArray data = new JsonArray();
        BuiltInRegistries.FLUID.entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
            .filter(entry -> context.matches(entry.getKey().location()))
            .forEach(entry -> {
                ResourceLocation id = entry.getKey().location();
                Fluid fluid = entry.getValue();
                Item bucket = fluid.getBucket();
                String translationKey = bucket == Items.AIR
                    ? firstTranslationKey(context, id)
                    : bucket.getDescriptionId();
                JsonObject json = baseNamed(context, id, translationKey);
                FluidState state = fluid.defaultFluidState();
                json.addProperty("source", state.isSource());
                json.addProperty("amount", state.getAmount());
                if (bucket != Items.AIR) {
                    json.addProperty("bucket", BuiltInRegistries.ITEM.getKey(bucket).toString());
                }
                data.add(json);
            });
        writeRegistry(context, "fluids", data);
    }

    private static void exportKeyMappings(ExportContext context) throws Exception {
        JsonArray data = new JsonArray();
        List<KeyMapping> mappings = new ArrayList<>(List.of(context.minecraft().options.keyMappings));
        mappings.sort(Comparator.comparing(KeyMapping::getName));
        for (KeyMapping mapping : mappings) {
            JsonObject json = new JsonObject();
            json.addProperty("id", mapping.getName());
            json.addProperty("translation_key", mapping.getName());
            json.add("names", context.names(mapping.getName()));
            json.addProperty("category_translation_key", mapping.getCategory());
            json.add("category_names", context.names(mapping.getCategory()));
            json.addProperty("default_key", mapping.getDefaultKey().getName());
            json.addProperty("bound_key", mapping.saveString());
            json.addProperty("is_default", mapping.isDefault());
            json.addProperty("is_unbound", mapping.isUnbound());
            data.add(json);
        }
        writeRegistry(context, "key_mappings", data);
    }

    private static void exportMods(ExportContext context) throws Exception {
        JsonArray data = new JsonArray();
        Platform.getMods().stream().sorted(Comparator.comparing(Mod::getModId)).forEach(mod -> {
            if (!context.config().matchesNamespace(mod.getModId())) {
                return;
            }
            JsonObject json = new JsonObject();
            json.addProperty("id", mod.getModId());
            json.addProperty("name", mod.getName());
            json.addProperty("version", mod.getVersion());
            json.addProperty("description", mod.getDescription());
            JsonArray authors = new JsonArray();
            mod.getAuthors().forEach(authors::add);
            json.add("authors", authors);
            JsonArray licenses = new JsonArray();
            if (mod.getLicense() != null) {
                mod.getLicense().forEach(licenses::add);
            }
            json.add("licenses", licenses);
            mod.getHomepage().ifPresent(value -> json.addProperty("homepage", value));
            mod.getSources().ifPresent(value -> json.addProperty("sources", value));
            mod.getIssueTracker().ifPresent(value -> json.addProperty("issues", value));
            mod.getLogoFile(256).ifPresent(value -> json.addProperty("logo", value));
            data.add(json);
        });
        writeRegistry(context, "mods", data);
    }

    private static JsonObject baseNamed(ExportContext context, ResourceLocation id, String translationKey) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id.toString());
        json.addProperty("namespace", id.getNamespace());
        json.addProperty("path", id.getPath());
        json.addProperty("translation_key", translationKey);
        json.add("names", context.names(translationKey));
        return json;
    }

    private static String firstTranslationKey(ExportContext context, ResourceLocation id) {
        List<String> candidates = List.of(
            "fluid." + id.getNamespace() + "." + id.getPath().replace('/', '.'),
            "block." + id.getNamespace() + "." + id.getPath().replace('/', '.')
        );
        for (String candidate : candidates) {
            if (context.names(candidate).size() > 0) {
                return candidate;
            }
        }
        return candidates.get(0);
    }

    private static <T extends Comparable<T>> String stateValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static void writeRegistry(ExportContext context, String name, JsonArray data) throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", "mcmods-registry/v1");
        root.addProperty("registry", name);
        root.addProperty("count", data.size());
        root.add("entries", data);
        context.writeJson("registries/" + name + ".json", root);
        context.report().addCount(ID, name, data.size());
    }
}
