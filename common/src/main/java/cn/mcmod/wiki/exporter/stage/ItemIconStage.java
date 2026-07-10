package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.render.OffscreenRenderer;
import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.ExportStage;
import cn.mcmod.wiki.exporter.task.StageResult;
import cn.mcmod.wiki.exporter.util.ExportPaths;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ItemIconStage implements ExportStage {
    public static final String ID = "item_icons";
    private final List<Entry> entries = new ArrayList<>();
    private int index;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void start(ExportContext context) {
        BuiltInRegistries.ITEM.entrySet().stream()
            .filter(entry -> context.matches(entry.getKey().location()))
            .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
            .forEach(entry -> entries.add(new Entry(entry.getKey().location(), entry.getValue())));
    }

    @Override
    public StageResult step(ExportContext context) {
        if (index >= entries.size()) {
            return StageResult.DONE;
        }
        Entry entry = entries.get(index++);
        try {
            ItemStack stack = entry.item.getDefaultInstance();
            if (stack.isEmpty()) {
                context.report().addCount(ID, "empty", 1);
            } else {
                for (int size : context.config().imageSizes()) {
                    OffscreenRenderer.renderItem(stack, size, ExportPaths.idFile(context.root(), "icons/items", size, entry.id, ".png"));
                    context.report().addCount(ID, "images", 1);
                }
                context.report().addCount(ID, "items", 1);
            }
        } catch (Throwable throwable) {
            context.report().error(ID, entry.id.toString(), throwable);
        }
        return index >= entries.size() ? StageResult.DONE : StageResult.MORE_WORK;
    }

    @Override
    public String progress() {
        return index + "/" + entries.size();
    }

    private record Entry(ResourceLocation id, Item item) {
    }
}
