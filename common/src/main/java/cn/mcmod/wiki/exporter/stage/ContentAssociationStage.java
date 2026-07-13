package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.OnceStage;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports website-facing relationships that are not represented by a normal
 * registry entry: creative tab membership and block-to-block-entity machines.
 */
public final class ContentAssociationStage extends OnceStage {
    public static final String ID = "content_associations";

    @Override
    public String id() {
        return ID;
    }

    @Override
    protected void run(ExportContext context) throws Exception {
        exportCreativeTabs(context);
        exportBlockEntitiesAndMachines(context);
        exportMenuTypes(context);
    }

    private static void exportCreativeTabs(ExportContext context) throws Exception {
        JsonArray tabs = new JsonArray();
        BuiltInRegistries.CREATIVE_MODE_TAB.entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
            .forEach(entry -> {
                ResourceLocation id = entry.getKey().location();
                CreativeModeTab tab = entry.getValue();
                boolean selectedTab = context.matches(id);
                List<ItemStack> visibleItems = new ArrayList<>();
                for (ItemStack stack : tab.getDisplayItems()) {
                    ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    if (itemId != null && (selectedTab || context.matches(itemId))) {
                        visibleItems.add(stack);
                    }
                }
                if (!selectedTab && visibleItems.isEmpty()) {
                    return;
                }
                JsonObject json = baseId(id);
                json.addProperty("selected_namespace", selectedTab);
                json.addProperty("inclusion_reason", selectedTab ? "target_tab" : "contains_target_items");
                json.add("names", context.componentNames(tab.getDisplayName()));
                json.add("icon", ExportContext.stackJson(tab.getIconItem()));
                JsonArray items = stableStacks(visibleItems);
                json.addProperty("item_count", items.size());
                json.add("items", items);
                tabs.add(json);
            });
        JsonObject root = registryRoot("creative_tabs", tabs);
        root.addProperty("membership_scope", "display_items");
        context.writeJson("registries/creative_tabs.json", root);
        context.report().addCount(ID, "creative_tabs", tabs.size());
    }

    private static void exportBlockEntitiesAndMachines(ExportContext context) throws Exception {
        Map<BlockEntityType<?>, MachineEntry> associations = new LinkedHashMap<>();
        BuiltInRegistries.BLOCK.entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
            .forEach(entry -> {
                Block block = entry.getValue();
                if (!(block instanceof EntityBlock entityBlock)) {
                    return;
                }
                BlockEntity blockEntity = null;
                try {
                    blockEntity = entityBlock.newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
                    if (blockEntity == null) {
                        return;
                    }
                    BlockEntityType<?> type = blockEntity.getType();
                    MachineEntry machine = associations.computeIfAbsent(type, ignored -> new MachineEntry());
                    MachineBlock machineBlock = new MachineBlock(entry.getKey().location());
                    if (blockEntity instanceof MenuProvider provider) {
                        machineBlock.menuProvider = true;
                        machineBlock.menuNames = context.componentNames(provider.getDisplayName());
                    }
                    machine.blocks.add(machineBlock);
                } catch (Throwable throwable) {
                    context.report().error(ID, "block_entity:" + entry.getKey().location(), throwable);
                }
            });

        JsonArray types = new JsonArray();
        JsonArray machines = new JsonArray();
        BuiltInRegistries.BLOCK_ENTITY_TYPE.entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
            .forEach(entry -> {
                ResourceLocation typeId = entry.getKey().location();
                MachineEntry machine = associations.getOrDefault(entry.getValue(), new MachineEntry());
                boolean selectedType = context.matches(typeId);
                boolean containsSelectedBlock = machine.blocks.stream().anyMatch(block -> context.matches(block.id));
                if (!selectedType && !containsSelectedBlock) {
                    return;
                }
                JsonObject typeJson = baseId(typeId);
                typeJson.addProperty("selected_namespace", selectedType);
                typeJson.addProperty("inclusion_reason", selectedType ? "target_type" : "used_by_target_block");
                JsonArray blockIds = new JsonArray();
                machine.blocks.stream()
                    .map(block -> block.id)
                    .filter(id -> selectedType || context.matches(id))
                    .distinct()
                    .forEach(id -> blockIds.add(id.toString()));
                typeJson.addProperty("block_count", blockIds.size());
                typeJson.add("blocks", blockIds);
                types.add(typeJson);

                JsonObject machineJson = baseId(typeId);
                JsonArray blocks = new JsonArray();
                for (MachineBlock block : machine.blocks) {
                    if (!selectedType && !context.matches(block.id)) {
                        continue;
                    }
                    JsonObject blockJson = baseId(block.id);
                    Block registeredBlock = BuiltInRegistries.BLOCK.get(block.id);
                    if (registeredBlock != null) {
                        blockJson.add("names", context.names(registeredBlock.getDescriptionId()));
                        if (registeredBlock.asItem() != Items.AIR) {
                            blockJson.addProperty("item", BuiltInRegistries.ITEM.getKey(registeredBlock.asItem()).toString());
                        }
                    }
                    blockJson.addProperty("menu_provider", block.menuProvider);
                    if (block.menuNames != null) {
                        blockJson.add("menu_names", block.menuNames);
                    }
                    blocks.add(blockJson);
                }
                machineJson.addProperty("block_count", blocks.size());
                machineJson.add("blocks", blocks);
                machineJson.addProperty("recipe_type_relation_source", "jei_category_catalysts");
                machines.add(machineJson);
            });

        context.writeJson("registries/block_entity_types.json", registryRoot("block_entity_types", types));
        JsonObject machineRoot = new JsonObject();
        machineRoot.addProperty("schema_version", "mcmods-machines/v1");
        machineRoot.addProperty("count", machines.size());
        machineRoot.addProperty("recipe_type_relations", "recipes/jei/categories.json#categories[].catalysts");
        machineRoot.add("machines", machines);
        context.writeJson("registries/machines.json", machineRoot);
        context.report().addCount(ID, "block_entity_types", types.size());
        context.report().addCount(ID, "machines", machines.size());
    }

    private static void exportMenuTypes(ExportContext context) throws Exception {
        JsonArray menus = new JsonArray();
        BuiltInRegistries.MENU.entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
            .filter(entry -> context.matches(entry.getKey().location()))
            .forEach(entry -> menus.add(baseId(entry.getKey().location())));
        JsonObject root = registryRoot("menu_types", menus);
        root.addProperty("association_status", "registry_only");
        root.addProperty("association_note", "Runtime menu constructors do not expose a stable block or machine relation; use machines.json and JEI catalysts.");
        context.writeJson("registries/menu_types.json", root);
        context.report().addCount(ID, "menu_types", menus.size());
    }

    private static JsonArray stableStacks(List<ItemStack> stacks) {
        Map<String, JsonObject> unique = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            JsonObject json = ExportContext.stackJson(stack);
            String key = json.toString();
            unique.putIfAbsent(key, json);
        }
        JsonArray result = new JsonArray();
        unique.values().forEach(result::add);
        return result;
    }

    private static JsonObject registryRoot(String registry, JsonArray entries) {
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", "mcmods-registry/v1");
        root.addProperty("registry", registry);
        root.addProperty("count", entries.size());
        root.add("entries", entries);
        return root;
    }

    private static JsonObject baseId(ResourceLocation id) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id.toString());
        json.addProperty("namespace", id.getNamespace());
        json.addProperty("path", id.getPath());
        return json;
    }

    private static final class MachineEntry {
        private final List<MachineBlock> blocks = new ArrayList<>();
    }

    private static final class MachineBlock {
        private final ResourceLocation id;
        private boolean menuProvider;
        private JsonObject menuNames;

        private MachineBlock(ResourceLocation id) {
            this.id = id;
        }
    }
}
