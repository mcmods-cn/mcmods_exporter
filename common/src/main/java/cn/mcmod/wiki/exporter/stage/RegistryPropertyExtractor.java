package cn.mcmod.wiki.exporter.stage;

import com.google.common.collect.Multimap;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts version-specific Minecraft runtime properties into the stable registry JSON model.
 * Keep loader and Minecraft-version compatibility code here instead of spreading it through stages.
 */
@SuppressWarnings("deprecation")
final class RegistryPropertyExtractor {
    private static final Map<String, Integer> VANILLA_MINING_LEVELS = Map.of(
        "wood", 0,
        "gold", 0,
        "stone", 1,
        "iron", 2,
        "diamond", 3,
        "netherite", 4
    );

    private RegistryPropertyExtractor() {
    }

    static void addItemProperties(JsonObject json, Item item, ItemStack stack) {
        List<String> itemTags = stack.getTags()
            .map(tag -> tag.location().toString())
            .sorted()
            .toList();
        json.add("item_tags", strings(itemTags));

        boolean damageable = stack.isDamageableItem();
        JsonObject durability = new JsonObject();
        durability.addProperty("damageable", damageable);
        durability.addProperty("max_damage", stack.getMaxDamage());
        json.add("durability", durability);

        json.addProperty("fire_resistant", item.isFireResistant());
        json.addProperty("use_animation", stack.getUseAnimation().name().toLowerCase(Locale.ROOT));
        json.addProperty("use_duration_ticks", stack.getUseDuration());
        if (item.hasCraftingRemainingItem()) {
            ResourceLocation remainder = BuiltInRegistries.ITEM.getKey(item.getCraftingRemainingItem());
            if (remainder != null) {
                json.addProperty("crafting_remainder", remainder.toString());
            }
        }

        LinkedHashSet<String> types = classifyItem(item, itemTags);
        json.add("item_types", strings(types));
        if (!types.isEmpty()) {
            json.addProperty("primary_type", primaryType(types));
        }

        addTool(json, item, types);
        addEnchanting(json, item, stack);
        addAttributes(json, stack);
        addEquipment(json, item);
        addFood(json, item);
    }

    static void addBlockProperties(JsonObject json, Block block, BlockState state) {
        json.addProperty("hardness", block.defaultDestroyTime());
        json.addProperty("explosion_resistance", block.getExplosionResistance());
        json.addProperty("friction", block.getFriction());
        json.addProperty("speed_factor", block.getSpeedFactor());
        json.addProperty("jump_factor", block.getJumpFactor());
        json.addProperty("light_emission", state.getLightEmission());
        json.addProperty("blocks_motion", state.blocksMotion());
        json.addProperty("solid", state.isSolid());
        json.addProperty("liquid", state.liquid());
        json.addProperty("randomly_ticking", state.isRandomlyTicking());
        json.addProperty("piston_reaction", state.getPistonPushReaction().name().toLowerCase(Locale.ROOT));
        ResourceLocation lootTable = block.getLootTable();
        if (lootTable != null) {
            json.addProperty("loot_table", lootTable.toString());
        }

        List<String> blockTags = state.getTags()
            .map(tag -> tag.location().toString())
            .sorted()
            .toList();
        json.add("block_tags", strings(blockTags));
        JsonObject mining = normalizeBlockMining(blockTags, state.requiresCorrectToolForDrops());
        json.addProperty("requires_correct_tool", mining.get("requires_correct_tool").getAsBoolean());
        json.add("preferred_tools", mining.get("preferred_tools"));
        json.add("mining_tags", mining.get("mining_tags"));
        json.add("tier_tags", mining.get("tier_tags"));
        if (mining.has("required_tier")) {
            json.add("required_tier", mining.get("required_tier"));
        }
        if (mining.has("required_mining_level")) {
            json.add("required_mining_level", mining.get("required_mining_level"));
        }
    }

    static JsonObject normalizeBlockMining(Collection<String> blockTags, boolean requiresCorrectTool) {
        LinkedHashSet<String> preferredTools = new LinkedHashSet<>();
        List<String> miningTags = new ArrayList<>();
        List<String> tierTags = new ArrayList<>();
        String requiredTier = null;
        Integer requiredLevel = null;

        List<String> sortedTags = blockTags.stream().sorted().toList();
        for (String tag : sortedTags) {
            String path = resourcePath(tag);
            String tool = toolFromMiningPath(path);
            if (tool != null) {
                preferredTools.add(normalizeToolName(tool));
                miningTags.add(tag);
            }

            String tier = tierFromRequirementPath(path);
            if (tier != null) {
                tierTags.add(tag);
                Integer level = miningLevel(tier, path);
                if (level != null && (requiredLevel == null || level > requiredLevel)) {
                    requiredLevel = level;
                    requiredTier = tier;
                } else if (requiredTier == null) {
                    requiredTier = tier;
                }
            }
        }

        JsonObject mining = new JsonObject();
        mining.addProperty("requires_correct_tool", requiresCorrectTool);
        mining.add("preferred_tools", strings(preferredTools));
        mining.add("mining_tags", strings(miningTags));
        mining.add("tier_tags", strings(tierTags));
        if (requiredTier != null) {
            mining.addProperty("required_tier", requiredTier);
        }
        if (requiredLevel != null) {
            mining.addProperty("required_mining_level", requiredLevel);
        }
        return mining;
    }

    private static void addTool(JsonObject json, Item item, Set<String> itemTypes) {
        List<String> toolTypes = itemTypes.stream().filter(RegistryPropertyExtractor::isToolType).toList();
        if (toolTypes.isEmpty() && !(item instanceof TieredItem)) {
            return;
        }

        JsonObject tool = new JsonObject();
        tool.add("types", strings(toolTypes));
        if (item instanceof TieredItem tieredItem) {
            Tier tier = tieredItem.getTier();
            JsonObject tierJson = new JsonObject();
            String tierId = vanillaTierId(tier);
            if (tierId != null) {
                tierJson.addProperty("id", tierId);
            }
            tierJson.addProperty("mining_level", tier.getLevel());
            tierJson.addProperty("durability", tier.getUses());
            tierJson.addProperty("mining_speed", tier.getSpeed());
            tierJson.addProperty("attack_damage_bonus", tier.getAttackDamageBonus());
            tierJson.addProperty("enchantment_value", tier.getEnchantmentValue());
            tierJson.add("repair_items", ingredientItems(tier.getRepairIngredient()));
            tool.add("tier", tierJson);
        }
        if (item instanceof DiggerItem diggerItem) {
            tool.addProperty("attack_damage", diggerItem.getAttackDamage());
        } else if (item instanceof SwordItem swordItem) {
            tool.addProperty("attack_damage", swordItem.getDamage());
        }
        json.add("tool", tool);
    }

    private static void addEnchanting(JsonObject json, Item item, ItemStack stack) {
        JsonObject enchanting = new JsonObject();
        enchanting.addProperty("enchantable", stack.isEnchantable());
        enchanting.addProperty("enchantment_value", item.getEnchantmentValue());

        JsonArray compatible = new JsonArray();
        BuiltInRegistries.ENCHANTMENT.entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
            .forEach(entry -> {
                try {
                    Enchantment enchantment = entry.getValue();
                    if (enchantment.canEnchant(stack)) {
                        compatible.add(entry.getKey().location().toString());
                    }
                } catch (RuntimeException ignored) {
                    // A third-party enchantment must not abort the entire registry export.
                }
            });
        enchanting.add("compatible_enchantments", compatible);
        json.add("enchanting", enchanting);
    }

    private static void addAttributes(JsonObject json, ItemStack stack) {
        JsonArray slots = new JsonArray();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            Multimap<Attribute, AttributeModifier> modifiers = stack.getAttributeModifiers(slot);
            if (modifiers.isEmpty()) {
                continue;
            }
            JsonObject slotJson = new JsonObject();
            slotJson.addProperty("slot", slot.getName());
            JsonArray values = new JsonArray();
            modifiers.entries().stream()
                .sorted(Comparator
                    .comparing((Map.Entry<Attribute, AttributeModifier> entry) -> attributeId(entry.getKey()))
                    .thenComparing(entry -> entry.getValue().getId().toString()))
                .forEach(entry -> values.add(attributeModifier(entry.getKey(), entry.getValue())));
            slotJson.add("modifiers", values);
            slots.add(slotJson);
        }
        json.add("attribute_modifiers", slots);

        Multimap<Attribute, AttributeModifier> mainHand = stack.getAttributeModifiers(EquipmentSlot.MAINHAND);
        if (mainHand.containsKey(Attributes.ATTACK_DAMAGE) || mainHand.containsKey(Attributes.ATTACK_SPEED)) {
            JsonObject combat = new JsonObject();
            addModifierSummary(combat, "attack_damage_modifier", mainHand.get(Attributes.ATTACK_DAMAGE));
            addModifierSummary(combat, "attack_speed_modifier", mainHand.get(Attributes.ATTACK_SPEED));
            combat.addProperty("attack_damage", applyModifiers(1.0D, mainHand.get(Attributes.ATTACK_DAMAGE)));
            combat.addProperty("attack_speed", applyModifiers(4.0D, mainHand.get(Attributes.ATTACK_SPEED)));
            json.add("combat", combat);
        }
    }

    private static void addEquipment(JsonObject json, Item item) {
        if (item instanceof Equipable equipable) {
            json.addProperty("equipment_slot", equipable.getEquipmentSlot().getName());
        }
        if (!(item instanceof ArmorItem armorItem)) {
            return;
        }

        JsonObject armor = new JsonObject();
        armor.addProperty("type", armorItem.getType().getName());
        armor.addProperty("slot", armorItem.getEquipmentSlot().getName());
        armor.addProperty("material", armorItem.getMaterial().getName());
        armor.addProperty("defense", armorItem.getDefense());
        armor.addProperty("toughness", armorItem.getToughness());
        armor.addProperty("knockback_resistance", armorItem.getMaterial().getKnockbackResistance());
        armor.addProperty("enchantment_value", armorItem.getEnchantmentValue());
        armor.add("repair_items", ingredientItems(armorItem.getMaterial().getRepairIngredient()));
        json.add("armor", armor);
    }

    private static void addFood(JsonObject json, Item item) {
        FoodProperties properties = item.getFoodProperties();
        if (properties == null) {
            return;
        }

        JsonObject food = new JsonObject();
        food.addProperty("nutrition", properties.getNutrition());
        food.addProperty("saturation_modifier", properties.getSaturationModifier());
        food.addProperty("meat", properties.isMeat());
        food.addProperty("always_edible", properties.canAlwaysEat());
        food.addProperty("fast_food", properties.isFastFood());
        JsonArray effects = new JsonArray();
        for (Pair<MobEffectInstance, Float> pair : properties.getEffects()) {
            MobEffectInstance instance = pair.getFirst();
            ResourceLocation effectId = BuiltInRegistries.MOB_EFFECT.getKey(instance.getEffect());
            JsonObject effect = new JsonObject();
            if (effectId != null) {
                effect.addProperty("effect", effectId.toString());
            }
            effect.addProperty("probability", pair.getSecond());
            effect.addProperty("duration_ticks", instance.getDuration());
            effect.addProperty("amplifier", instance.getAmplifier());
            effect.addProperty("ambient", instance.isAmbient());
            effect.addProperty("visible", instance.isVisible());
            effect.addProperty("show_icon", instance.showIcon());
            effects.add(effect);
        }
        food.add("effects", effects);
        json.add("food", food);
    }

    private static LinkedHashSet<String> classifyItem(Item item, Collection<String> itemTags) {
        LinkedHashSet<String> types = new LinkedHashSet<>();
        if (item instanceof BlockItem) types.add("block_item");
        if (item instanceof PickaxeItem) types.add("pickaxe");
        if (item instanceof AxeItem) types.add("axe");
        if (item instanceof ShovelItem) types.add("shovel");
        if (item instanceof HoeItem) types.add("hoe");
        if (item instanceof SwordItem) types.add("sword");
        if (item instanceof ShearsItem) types.add("shears");
        if (item instanceof BowItem) types.add("bow");
        if (item instanceof CrossbowItem) types.add("crossbow");
        if (item instanceof TridentItem) types.add("trident");
        if (item instanceof FishingRodItem) types.add("fishing_rod");
        if (item instanceof ShieldItem) types.add("shield");
        if (item instanceof ArmorItem) types.add("armor");
        if (item instanceof ElytraItem) types.add("elytra");
        if (item instanceof ProjectileWeaponItem) types.add("projectile_weapon");
        if (item instanceof DiggerItem) types.add("mining_tool");
        if (item instanceof TieredItem) types.add("tiered");
        if (item.isEdible()) types.add("food");
        if (item.canBeDepleted()) types.add("damageable");

        for (String tag : itemTags) {
            String path = resourcePath(tag);
            int toolsIndex = path.indexOf("tools/");
            if (toolsIndex >= 0 && toolsIndex + 6 < path.length()) {
                String taggedType = path.substring(toolsIndex + 6);
                int slash = taggedType.indexOf('/');
                if (slash >= 0) {
                    taggedType = taggedType.substring(0, slash);
                }
                types.add(normalizeToolName(taggedType));
            }
        }
        return types;
    }

    private static String primaryType(Set<String> types) {
        List<String> priority = List.of(
            "pickaxe", "axe", "shovel", "hoe", "sword", "bow", "crossbow", "trident",
            "shears", "fishing_rod", "shield", "armor", "elytra", "block_item", "food",
            "projectile_weapon", "mining_tool", "tiered", "damageable"
        );
        for (String candidate : priority) {
            if (types.contains(candidate)) {
                return candidate;
            }
        }
        return types.iterator().next();
    }

    private static boolean isToolType(String type) {
        return Set.of(
            "pickaxe", "axe", "shovel", "hoe", "sword", "bow", "crossbow", "trident",
            "shears", "fishing_rod", "mining_tool", "projectile_weapon"
        ).contains(type);
    }

    private static JsonArray ingredientItems(Ingredient ingredient) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (ItemStack ingredientStack : ingredient.getItems()) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(ingredientStack.getItem());
            if (id != null) {
                ids.add(id.toString());
            }
        }
        return strings(ids.stream().sorted().toList());
    }

    private static JsonObject attributeModifier(Attribute attribute, AttributeModifier modifier) {
        JsonObject json = new JsonObject();
        json.addProperty("attribute", attributeId(attribute));
        json.addProperty("id", modifier.getId().toString());
        json.addProperty("name", modifier.getName());
        json.addProperty("amount", modifier.getAmount());
        json.addProperty("operation", modifier.getOperation().name().toLowerCase(Locale.ROOT));
        json.addProperty("operation_id", modifier.getOperation().toValue());
        return json;
    }

    private static String attributeId(Attribute attribute) {
        ResourceLocation id = BuiltInRegistries.ATTRIBUTE.getKey(attribute);
        return id == null ? "unregistered:unknown" : id.toString();
    }

    private static void addModifierSummary(
        JsonObject target,
        String name,
        Collection<AttributeModifier> modifiers
    ) {
        if (modifiers.isEmpty()) {
            return;
        }
        double addition = modifiers.stream()
            .filter(modifier -> modifier.getOperation() == AttributeModifier.Operation.ADDITION)
            .mapToDouble(AttributeModifier::getAmount)
            .sum();
        target.addProperty(name, addition);
    }

    static double applyModifiers(double baseValue, Collection<AttributeModifier> modifiers) {
        double value = baseValue;
        for (AttributeModifier modifier : modifiers) {
            if (modifier.getOperation() == AttributeModifier.Operation.ADDITION) {
                value += modifier.getAmount();
            }
        }
        for (AttributeModifier modifier : modifiers) {
            if (modifier.getOperation() == AttributeModifier.Operation.MULTIPLY_BASE) {
                value += baseValue * modifier.getAmount();
            }
        }
        for (AttributeModifier modifier : modifiers) {
            if (modifier.getOperation() == AttributeModifier.Operation.MULTIPLY_TOTAL) {
                value *= 1.0D + modifier.getAmount();
            }
        }
        return value;
    }

    private static String vanillaTierId(Tier tier) {
        if (tier == Tiers.WOOD) return "minecraft:wood";
        if (tier == Tiers.STONE) return "minecraft:stone";
        if (tier == Tiers.IRON) return "minecraft:iron";
        if (tier == Tiers.DIAMOND) return "minecraft:diamond";
        if (tier == Tiers.NETHERITE) return "minecraft:netherite";
        if (tier == Tiers.GOLD) return "minecraft:gold";
        return null;
    }

    private static String resourcePath(String id) {
        int separator = id.indexOf(':');
        return separator < 0 ? id : id.substring(separator + 1);
    }

    private static String toolFromMiningPath(String path) {
        int mineable = path.indexOf("mineable/");
        if (mineable >= 0) {
            return firstSegment(path.substring(mineable + "mineable/".length()));
        }
        int withSlash = path.indexOf("mineable_with/");
        if (withSlash >= 0) {
            return firstSegment(path.substring(withSlash + "mineable_with/".length()));
        }
        int withUnderscore = path.indexOf("mineable_with_");
        if (withUnderscore >= 0) {
            return firstSegment(path.substring(withUnderscore + "mineable_with_".length()));
        }
        return null;
    }

    private static String tierFromRequirementPath(String path) {
        int needs = path.lastIndexOf("needs_");
        int tool = path.lastIndexOf("_tool");
        if (needs >= 0 && tool > needs + 6) {
            return path.substring(needs + 6, tool);
        }
        int level = path.lastIndexOf("needs_tool_level_");
        if (level >= 0) {
            return "level_" + firstSegment(path.substring(level + "needs_tool_level_".length()));
        }
        return null;
    }

    private static Integer miningLevel(String tier, String path) {
        Integer vanilla = VANILLA_MINING_LEVELS.get(tier);
        if (vanilla != null) {
            return vanilla;
        }
        String numeric = tier.startsWith("level_") ? tier.substring("level_".length()) : null;
        if (numeric == null && path.contains("needs_tool_level_")) {
            numeric = path.substring(path.lastIndexOf("needs_tool_level_") + "needs_tool_level_".length());
            numeric = firstSegment(numeric);
        }
        if (numeric != null) {
            try {
                return Integer.parseInt(numeric);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String firstSegment(String value) {
        int slash = value.indexOf('/');
        return slash < 0 ? value : value.substring(0, slash);
    }

    private static String normalizeToolName(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace('-', '_');
        if (normalized.equals("axes")) return "axe";
        if (normalized.equals("pickaxes")) return "pickaxe";
        if (normalized.equals("shovels")) return "shovel";
        if (normalized.equals("hoes")) return "hoe";
        if (normalized.equals("swords")) return "sword";
        if (normalized.equals("shears")) return "shears";
        if (normalized.endsWith("s") && normalized.length() > 1) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static JsonArray strings(Collection<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}
