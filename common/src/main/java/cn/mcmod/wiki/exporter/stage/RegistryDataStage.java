package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.OnceStage;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.architectury.platform.Mod;
import dev.architectury.platform.Platform;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;

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
                try {
                    RegistryPropertyExtractor.addItemProperties(json, item, stack);
                } catch (RuntimeException throwable) {
                    context.report().error(ID, id + ":properties", throwable);
                }
                addLocalizedTooltips(context, json, stack);
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
                try {
                    RegistryPropertyExtractor.addBlockProperties(json, block, state);
                } catch (RuntimeException throwable) {
                    context.report().error(ID, id + ":properties", throwable);
                }
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
        Map<EntityType<?>, List<ResourceLocation>> spawnEggs = spawnEggs();
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
                JsonArray eggs = new JsonArray();
                spawnEggs.getOrDefault(type, List.of()).forEach(egg -> eggs.add(egg.toString()));
                json.add("spawn_eggs", eggs);
                json.addProperty("spawn_egg_count", eggs.size());
                addEntityRuntimeProperties(context, id, type, json);
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
                JsonArray modifiers = new JsonArray();
                effect.getAttributeModifiers().entrySet().stream()
                    .sorted(Comparator.comparing(attributeEntry -> String.valueOf(BuiltInRegistries.ATTRIBUTE.getKey(attributeEntry.getKey()))))
                    .forEach(attributeEntry -> {
                        AttributeModifier modifier = attributeEntry.getValue();
                        JsonObject modifierJson = new JsonObject();
                        ResourceLocation attributeId = BuiltInRegistries.ATTRIBUTE.getKey(attributeEntry.getKey());
                        modifierJson.addProperty("attribute", attributeId == null ? "unregistered:unknown" : attributeId.toString());
                        modifierJson.addProperty("uuid", modifier.getId().toString());
                        modifierJson.addProperty("operation", modifier.getOperation().name().toLowerCase(Locale.ROOT));
                        modifierJson.addProperty("base_amount", modifier.getAmount());
                        modifierJson.addProperty("amount_at_amplifier_0", effect.getAttributeModifierValue(0, modifier));
                        modifierJson.addProperty("amount_at_amplifier_1", effect.getAttributeModifierValue(1, modifier));
                        modifiers.add(modifierJson);
                    });
                json.add("effect_attribute_modifiers", modifiers);
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

    private static void exportFluids(ExportContext context) throws Exception {
        JsonArray data = new JsonArray();
        BuiltInRegistries.FLUID.entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
            .filter(entry -> context.matches(entry.getKey().location()))
            .filter(entry -> entry.getValue().defaultFluidState().isSource())
            .forEach(entry -> {
                ResourceLocation id = entry.getKey().location();
                Fluid fluid = entry.getValue();
                Item bucket = fluid.getBucket();
                String translationKey = firstTranslationKey(context, id);
                JsonObject json = baseNamed(context, id, translationKey);
                json.addProperty("ingredient_kind", "fluid");
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
        int excluded = 0;
        for (KeyMapping mapping : mappings) {
            if (!isRelevantKeyMapping(context, mapping.getName(), mapping.getCategory())) {
                excluded++;
                continue;
            }
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
        context.report().addCount(ID, "key_mappings_excluded", excluded);
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

    private static void addLocalizedTooltips(ExportContext context, JsonObject json, ItemStack stack) {
        try {
            List<Component> lines = stack.getTooltipLines(context.minecraft().player, TooltipFlag.NORMAL);
            JsonObject localized = new JsonObject();
            for (String language : context.languages()) {
                JsonArray values = new JsonArray();
                for (Component line : lines) {
                    values.add(context.localize(line, language));
                }
                localized.add(language, values);
            }
            json.add("tooltips", localized);
            json.addProperty("tooltip_context", "default_stack_normal");
        } catch (Throwable throwable) {
            context.report().error(ID, stack.getDescriptionId() + ":tooltips", throwable);
        }
    }

    private static Map<EntityType<?>, List<ResourceLocation>> spawnEggs() {
        Map<EntityType<?>, List<ResourceLocation>> result = new LinkedHashMap<>();
        BuiltInRegistries.ITEM.entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
            .forEach(entry -> {
                if (entry.getValue() instanceof SpawnEggItem egg) {
                    EntityType<?> type = egg.getType(null);
                    result.computeIfAbsent(type, ignored -> new ArrayList<>()).add(entry.getKey().location());
                }
            });
        return result;
    }

    private static void addEntityRuntimeProperties(
        ExportContext context,
        ResourceLocation id,
        EntityType<?> type,
        JsonObject json
    ) {
        if (context.minecraft().level == null) {
            json.addProperty("runtime_properties_available", false);
            return;
        }
        Entity entity = null;
        try {
            entity = type.create(context.minecraft().level);
            if (entity == null) {
                json.addProperty("runtime_properties_available", false);
                return;
            }
            json.addProperty("runtime_properties_available", true);
            json.addProperty("eye_height", entity.getEyeHeight());
            json.addProperty("max_air_supply", entity.getMaxAirSupply());
            json.addProperty("living", entity instanceof LivingEntity);
            json.addProperty("mob", entity instanceof Mob);
            json.addProperty("animal", entity instanceof Animal);
            json.addProperty("ageable", entity instanceof AgeableMob);
            json.addProperty("tamable", entity instanceof TamableAnimal);
            json.addProperty("hostile", entity instanceof Enemy || type.getCategory() == MobCategory.MONSTER);
            json.addProperty("water_animal", entity instanceof WaterAnimal);
            if (entity instanceof Animal animal) {
                JsonArray breedingItems = new JsonArray();
                BuiltInRegistries.ITEM.entrySet().stream()
                    .sorted(Comparator.comparing(itemEntry -> itemEntry.getKey().location().toString()))
                    .filter(itemEntry -> {
                        try {
                            return animal.isFood(itemEntry.getValue().getDefaultInstance());
                        } catch (Throwable ignored) {
                            return false;
                        }
                    })
                    .forEach(itemEntry -> breedingItems.add(itemEntry.getKey().location().toString()));
                json.add("breeding_items", breedingItems);
            }
            if (entity instanceof LivingEntity living) {
                json.addProperty("mob_type", mobTypeName(living));
                json.addProperty("max_health", living.getMaxHealth());
                json.addProperty("armor_value", living.getArmorValue());
                json.addProperty("default_loot_table", living.getLootTable().toString());
                JsonArray attributes = new JsonArray();
                BuiltInRegistries.ATTRIBUTE.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
                    .forEach(entry -> {
                        AttributeInstance instance = living.getAttribute(entry.getValue());
                        if (instance == null) {
                            return;
                        }
                        JsonObject attribute = new JsonObject();
                        attribute.addProperty("attribute", entry.getKey().location().toString());
                        attribute.addProperty("base_value", instance.getBaseValue());
                        attribute.addProperty("default_value", instance.getValue());
                        attributes.add(attribute);
                    });
                json.add("default_attributes", attributes);
                JsonArray equipment = new JsonArray();
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    ItemStack stack = living.getItemBySlot(slot);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    JsonObject equipped = ExportContext.stackJson(stack);
                    equipped.addProperty("slot", slot.getName());
                    equipment.add(equipped);
                }
                json.add("default_equipment", equipment);
            }
        } catch (Throwable throwable) {
            json.addProperty("runtime_properties_available", false);
            context.report().error(ID, id + ":runtime_properties", throwable);
        } finally {
            if (entity != null) {
                entity.discard();
            }
        }
    }

    private static String mobTypeName(LivingEntity living) {
        if (living.getMobType() == net.minecraft.world.entity.MobType.UNDEAD) return "undead";
        if (living.getMobType() == net.minecraft.world.entity.MobType.ARTHROPOD) return "arthropod";
        if (living.getMobType() == net.minecraft.world.entity.MobType.ILLAGER) return "illager";
        if (living.getMobType() == net.minecraft.world.entity.MobType.WATER) return "water";
        return "undefined";
    }

    static boolean keyTranslationMatchesNamespaces(String translationKey, Iterable<String> namespaces) {
        String normalized = translationKey.toLowerCase(Locale.ROOT);
        String[] parts = normalized.split("[^a-z0-9_]+", -1);
        for (String namespace : namespaces) {
            for (String part : parts) {
                if (part.equals(namespace)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isRelevantKeyMapping(ExportContext context, String name, String category) {
        if (context.config().namespaces().isEmpty()) {
            return !keyTranslationMatchesNamespaces(name, List.of("mcmods_exporter"));
        }
        return keyTranslationMatchesNamespaces(name, context.config().namespaces())
            || keyTranslationMatchesNamespaces(category, context.config().namespaces());
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
