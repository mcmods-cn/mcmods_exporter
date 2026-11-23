package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.ExportStage;
import cn.mcmod.wiki.exporter.task.StageResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.architectury.platform.Mod;
import dev.architectury.platform.Platform;
import net.minecraft.client.KeyMapping;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
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

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

public final class RegistryDataStage implements ExportStage {
    public static final String ID = "registry_data";
    private static final String KEY_MAPPINGS = "key_mappings";
    private final Deque<RegistryDump<?>> registries = new ArrayDeque<>();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void start(ExportContext context) {
        // All access to game objects stays on the client thread. Reuse the full item
        // order for spawn eggs and breeding items even in a namespace-limited export.
        var items = sortedEntries(BuiltInRegistries.ITEM);
        registries.add(registryDump(context, "items", () -> items, (id, item) -> itemJson(context, id, item)));
        registries.add(registryDump(context, "blocks", () -> sortedEntries(BuiltInRegistries.BLOCK),
            (id, block) -> blockJson(context, id, block)));
        registries.add(new RegistryDump<>("entity_types", () -> entityEntries(context, items),
            entity -> entityJson(context, entity.id(), entity.type(), entity.spawnEggs(), items)));
        registries.add(registryDump(context, "mob_effects", () -> sortedEntries(BuiltInRegistries.MOB_EFFECT),
            (id, effect) -> effectJson(context, id, effect)));
        registries.add(registryDump(context, "enchantments", () -> sortedEntries(BuiltInRegistries.ENCHANTMENT),
            (id, enchantment) -> enchantmentJson(context, id, enchantment)));
        registries.add(registryDump(context, "fluids", () -> sortedEntries(BuiltInRegistries.FLUID),
            (id, fluid) -> fluidJson(context, id, fluid)));
        registries.add(new RegistryDump<>(KEY_MAPPINGS,
            () -> List.of(context.minecraft().options.keyMappings).stream()
                .sorted(Comparator.comparing(KeyMapping::getName)).toList(),
            mapping -> keyMappingJson(context, mapping)));
        registries.add(new RegistryDump<>("mods",
            () -> Platform.getMods().stream().sorted(Comparator.comparing(Mod::getModId)).toList(),
            mod -> modJson(context, mod)));
    }

    @Override
    public StageResult step(ExportContext context) throws IOException {
        RegistryDump<?> registry = registries.peekFirst();
        if (registry == null) {
            return StageResult.DONE;
        }
        if (registry.captureNext()) {
            return StageResult.MORE_WORK;
        }
        writeRegistry(context, registry.name, registry.entries);
        if (KEY_MAPPINGS.equals(registry.name)) {
            context.report().addCount(ID, "key_mappings_excluded", registry.excluded);
        }
        registries.removeFirst();
        return registries.isEmpty() ? StageResult.DONE : StageResult.MORE_WORK;
    }

    private static <T> List<Map.Entry<ResourceKey<T>, T>> sortedEntries(Registry<T> registry) {
        return registry.entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
            .toList();
    }

    private static <T> RegistryDump<Map.Entry<ResourceKey<T>, T>> registryDump(
        ExportContext context,
        String name,
        Supplier<List<Map.Entry<ResourceKey<T>, T>>> entries,
        BiFunction<ResourceLocation, T, JsonObject> extract
    ) {
        return new RegistryDump<>(name, entries, entry -> {
            ResourceLocation id = entry.getKey().location();
            return context.matches(id) ? extract.apply(id, entry.getValue()) : null;
        });
    }

    private static JsonObject itemJson(ExportContext context, ResourceLocation id, Item item) {
        ItemStack stack = item.getDefaultInstance();
        JsonObject json = baseNamed(context, id, item.getDescriptionId());
        json.addProperty("max_stack_size", item.getMaxStackSize());
        json.addProperty("max_damage", item.getMaxDamage());
        json.addProperty("edible", item.isEdible());
        json.addProperty("rarity", stack.getRarity().name().toLowerCase(Locale.ROOT));
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
        return json;
    }

    private static JsonObject blockJson(ExportContext context, ResourceLocation id, Block block) {
        BlockState state = block.defaultBlockState();
        JsonObject json = baseNamed(context, id, block.getDescriptionId());
        json.addProperty("air", state.isAir());
        json.addProperty("can_occlude", state.canOcclude());
        json.addProperty("render_shape", state.getRenderShape().name().toLowerCase(Locale.ROOT));
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
        return json;
    }

    private static JsonObject entityJson(
        ExportContext context,
        ResourceLocation id,
        EntityType<?> type,
        List<ResourceLocation> spawnEggs,
        List<Map.Entry<ResourceKey<Item>, Item>> items
    ) {
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
        spawnEggs.forEach(egg -> eggs.add(egg.toString()));
        json.add("spawn_eggs", eggs);
        json.addProperty("spawn_egg_count", eggs.size());
        addEntityRuntimeProperties(context, id, type, json, items);
        return json;
    }

    private static JsonObject effectJson(ExportContext context, ResourceLocation id, MobEffect effect) {
        JsonObject json = baseNamed(context, id, effect.getDescriptionId());
        json.addProperty("category", effect.getCategory().name().toLowerCase(Locale.ROOT));
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
        return json;
    }

    private static JsonObject enchantmentJson(ExportContext context, ResourceLocation id, Enchantment enchantment) {
        JsonObject json = baseNamed(context, id, enchantment.getDescriptionId());
        json.addProperty("category", enchantment.category.name().toLowerCase(Locale.ROOT));
        json.addProperty("rarity", enchantment.getRarity().name().toLowerCase(Locale.ROOT));
        json.addProperty("rarity_weight", enchantment.getRarity().getWeight());
        json.addProperty("min_level", enchantment.getMinLevel());
        json.addProperty("max_level", enchantment.getMaxLevel());
        json.addProperty("treasure_only", enchantment.isTreasureOnly());
        json.addProperty("curse", enchantment.isCurse());
        json.addProperty("tradeable", enchantment.isTradeable());
        json.addProperty("discoverable", enchantment.isDiscoverable());
        return json;
    }

    private static JsonObject fluidJson(ExportContext context, ResourceLocation id, Fluid fluid) {
        FluidState state = fluid.defaultFluidState();
        if (!state.isSource()) {
            return null;
        }
        Item bucket = fluid.getBucket();
        String translationKey = firstTranslationKey(context, id);
        JsonObject json = baseNamed(context, id, translationKey);
        json.addProperty("ingredient_kind", "fluid");
        json.addProperty("source", state.isSource());
        json.addProperty("amount", state.getAmount());
        if (bucket != Items.AIR) {
            json.addProperty("bucket", BuiltInRegistries.ITEM.getKey(bucket).toString());
        }
        return json;
    }

    private static JsonObject keyMappingJson(ExportContext context, KeyMapping mapping) {
        if (!isRelevantKeyMapping(context, mapping.getName(), mapping.getCategory())) {
            return null;
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
        return json;
    }

    private static JsonObject modJson(ExportContext context, Mod mod) {
        if (!context.config().matchesNamespace(mod.getModId())) {
            return null;
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
        return json;
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

    private static List<EntityEntry> entityEntries(ExportContext context, List<Map.Entry<ResourceKey<Item>, Item>> items) {
        Map<EntityType<?>, List<ResourceLocation>> spawnEggs = new LinkedHashMap<>();
        for (var entry : items) {
            if (entry.getValue() instanceof SpawnEggItem egg) {
                spawnEggs.computeIfAbsent(egg.getType(null), ignored -> new ArrayList<>()).add(entry.getKey().location());
            }
        }
        return sortedEntries(BuiltInRegistries.ENTITY_TYPE).stream()
            .filter(entry -> context.matches(entry.getKey().location()))
            .map(entry -> new EntityEntry(entry.getKey().location(), entry.getValue(),
                spawnEggs.getOrDefault(entry.getValue(), List.of())))
            .toList();
    }

    private static void addEntityRuntimeProperties(
        ExportContext context,
        ResourceLocation id,
        EntityType<?> type,
        JsonObject json,
        List<Map.Entry<ResourceKey<Item>, Item>> items
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
                items.stream()
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

    private static void writeRegistry(ExportContext context, String name, JsonArray data) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", "mcmods-registry/v1");
        root.addProperty("registry", name);
        root.addProperty("count", data.size());
        root.add("entries", data);
        context.writeJson("registries/" + name + ".json", root);
        context.report().addCount(ID, name, data.size());
    }

    private record EntityEntry(ResourceLocation id, EntityType<?> type, List<ResourceLocation> spawnEggs) { }

    static final class RegistryDump<T> {
        private final String name;
        private final Supplier<List<T>> source;
        private final Function<T, JsonObject> extract;
        private final JsonArray entries = new JsonArray();
        private Iterator<T> remaining;
        private int excluded;

        RegistryDump(String name, Supplier<List<T>> source, Function<T, JsonObject> extract) {
            this.name = name;
            this.source = source;
            this.extract = extract;
        }

        boolean captureNext() {
            if (remaining == null) {
                remaining = source.get().iterator();
                return true;
            }
            if (!remaining.hasNext()) {
                return false;
            }
            JsonObject row = extract.apply(remaining.next());
            if (row == null) {
                excluded++;
            } else {
                entries.add(row);
            }
            return true;
        }
    }
}
