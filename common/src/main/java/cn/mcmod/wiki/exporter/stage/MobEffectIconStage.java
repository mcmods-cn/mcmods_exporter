package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.render.OffscreenRenderer;
import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.ExportStage;
import cn.mcmod.wiki.exporter.task.StageResult;
import cn.mcmod.wiki.exporter.util.ExportPaths;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Exports the status-effect sprite itself, never a potion bottle or tipped arrow. */
public final class MobEffectIconStage implements ExportStage {
    public static final String ID = "mob_effect_icons";
    private static final int LOGICAL_ICON_SIZE = 18;
    private final List<Entry> entries = new ArrayList<>();
    private int index;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void start(ExportContext context) {
        BuiltInRegistries.MOB_EFFECT.entrySet().stream()
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
            TextureAtlasSprite sprite = context.minecraft().getMobEffectTextures().get(entry.effect());
            for (int size : context.config().imageSizes()) {
                OffscreenRenderer.renderGui(
                    size,
                    size,
                    LOGICAL_ICON_SIZE,
                    LOGICAL_ICON_SIZE,
                    ExportPaths.idFile(context.root(), "icons/mob_effects", size, entry.id(), ".png"),
                    graphics -> graphics.blit(0, 0, 0, LOGICAL_ICON_SIZE, LOGICAL_ICON_SIZE, sprite)
                );
                context.report().addCount(ID, "images", 1);
            }
            context.report().addCount(ID, "effects", 1);
        } catch (Throwable throwable) {
            context.report().error(ID, entry.id().toString(), throwable);
        }
        return index >= entries.size() ? StageResult.DONE : StageResult.MORE_WORK;
    }

    @Override
    public String progress() {
        return index + "/" + entries.size();
    }

    private record Entry(ResourceLocation id, MobEffect effect) {
    }
}
