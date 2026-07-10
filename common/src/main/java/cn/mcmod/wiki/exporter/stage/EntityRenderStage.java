package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.render.OffscreenRenderer;
import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.ExportStage;
import cn.mcmod.wiki.exporter.task.StageResult;
import cn.mcmod.wiki.exporter.util.ExportPaths;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class EntityRenderStage implements ExportStage {
    public static final String ID = "entity_renders";
    private final List<Entry> entries = new ArrayList<>();
    private int index;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void start(ExportContext context) {
        BuiltInRegistries.ENTITY_TYPE.entrySet().stream()
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
        Entity entity = null;
        try {
            entity = entry.type.create(context.minecraft().level);
            if (entity instanceof LivingEntity living) {
                for (int size : context.config().imageSizes()) {
                    OffscreenRenderer.renderEntity(
                        living,
                        size,
                        ExportPaths.idFile(context.root(), "entities/renders", size, entry.id, ".png")
                    );
                    context.report().addCount(ID, "images", 1);
                }
                context.report().addCount(ID, "entities", 1);
            } else {
                context.report().addCount(ID, "not_living", 1);
            }
        } catch (Throwable throwable) {
            context.report().error(ID, entry.id.toString(), throwable);
        } finally {
            if (entity != null) {
                entity.discard();
            }
        }
        return index >= entries.size() ? StageResult.DONE : StageResult.MORE_WORK;
    }

    @Override
    public String progress() {
        return index + "/" + entries.size();
    }

    private record Entry(ResourceLocation id, EntityType<?> type) {
    }
}
