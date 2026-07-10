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
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PotionIconStage implements ExportStage {
    public static final String ID = "potion_icons";
    private static final Map<String, Item> FORMS = createForms();
    private final List<Entry> entries = new ArrayList<>();
    private int index;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void start(ExportContext context) {
        BuiltInRegistries.POTION.entrySet().stream()
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
            for (Map.Entry<String, Item> form : FORMS.entrySet()) {
                ItemStack stack = PotionUtils.setPotion(new ItemStack(form.getValue()), entry.potion);
                for (int size : context.config().imageSizes()) {
                    OffscreenRenderer.renderItem(
                        stack,
                        size,
                        ExportPaths.idFile(context.root(), "potions/" + form.getKey(), size, entry.id, ".png")
                    );
                    context.report().addCount(ID, "images", 1);
                }
            }
            context.report().addCount(ID, "potions", 1);
        } catch (Throwable throwable) {
            context.report().error(ID, entry.id.toString(), throwable);
        }
        return index >= entries.size() ? StageResult.DONE : StageResult.MORE_WORK;
    }

    @Override
    public String progress() {
        return index + "/" + entries.size();
    }

    private static Map<String, Item> createForms() {
        Map<String, Item> forms = new LinkedHashMap<>();
        forms.put("potion", Items.POTION);
        forms.put("splash_potion", Items.SPLASH_POTION);
        forms.put("lingering_potion", Items.LINGERING_POTION);
        forms.put("tipped_arrow", Items.TIPPED_ARROW);
        return forms;
    }

    private record Entry(ResourceLocation id, Potion potion) {
    }
}
