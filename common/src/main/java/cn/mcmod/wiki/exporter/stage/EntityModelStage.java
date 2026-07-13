package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.render.ObjVertexConsumer;
import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.ExportStage;
import cn.mcmod.wiki.exporter.task.StageResult;
import cn.mcmod.wiki.exporter.util.ExportPaths;
import cn.mcmod.wiki.exporter.util.JsonFiles;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class EntityModelStage implements ExportStage {
    public static final String ID = "entity_models";
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
            if (!(entity instanceof LivingEntity living)) {
                context.report().addCount(ID, "not_living", 1);
                return index >= entries.size() ? StageResult.DONE : StageResult.MORE_WORK;
            }
            EntityRenderer<? super LivingEntity> renderer = context.minecraft().getEntityRenderDispatcher().getRenderer(living);
            if (!(renderer instanceof LivingEntityRenderer<?, ?> livingRenderer)) {
                context.report().addCount(ID, "unsupported_renderer", 1);
                return index >= entries.size() ? StageResult.DONE : StageResult.MORE_WORK;
            }
            capture(context, entry.id, living, renderer, livingRenderer);
            context.report().addCount(ID, "models", 1);
        } catch (Throwable throwable) {
            context.report().error(ID, entry.id.toString(), throwable);
        } finally {
            if (entity != null) {
                entity.discard();
            }
        }
        return index >= entries.size() ? StageResult.DONE : StageResult.MORE_WORK;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void capture(
        ExportContext context,
        ResourceLocation id,
        LivingEntity entity,
        EntityRenderer renderer,
        LivingEntityRenderer livingRenderer
    ) throws Exception {
        EntityModel model = livingRenderer.getModel();
        model.young = entity.isBaby();
        model.riding = false;
        model.attackTime = 0.0F;
        model.prepareMobModel(entity, 0.0F, 0.0F, 0.0F);
        model.setupAnim(entity, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
        ObjVertexConsumer consumer = new ObjVertexConsumer();
        model.renderToBuffer(new PoseStack(), consumer, 15728880, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);

        Path objPath = ExportPaths.idFile(context.root(), "entities/models", null, id, ".obj");
        consumer.write(objPath, id.toString());
        ResourceLocation texture = renderer.getTextureLocation(entity);
        JsonObject metadata = new JsonObject();
        metadata.addProperty("schema_version", "mcmods-entity-model/v1");
        metadata.addProperty("id", id.toString());
        metadata.addProperty("texture", texture == null ? "" : texture.toString());
        metadata.addProperty("vertex_count", consumer.vertexCount());
        metadata.addProperty("quad_count", consumer.faceCount());
        metadata.addProperty("obj", context.root().relativize(objPath).toString().replace('\\', '/'));
        metadata.addProperty("snapshot_pose", "idle");
        metadata.addProperty("base_model_only", true);
        JsonFiles.write(ExportPaths.idFile(context.root(), "entities/models", null, id, ".json"), metadata);
    }

    @Override
    public String progress() {
        return index + "/" + entries.size();
    }

    private record Entry(ResourceLocation id, EntityType<?> type) {
    }
}
