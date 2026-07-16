package cn.mcmod.wiki.exporter.stage;

import cn.mcmod.wiki.exporter.render.ObjVertexConsumer;
import cn.mcmod.wiki.exporter.task.ExportContext;
import cn.mcmod.wiki.exporter.task.ExportStage;
import cn.mcmod.wiki.exporter.task.StageResult;
import cn.mcmod.wiki.exporter.util.ExportPaths;
import cn.mcmod.wiki.exporter.util.JsonFiles;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Exports UV-complete meshes and source textures for specially rendered block entities. */
public final class BlockEntityModelStage implements ExportStage {
    public static final String ID = "block_entity_models";

    private final List<Entry> entries = new ArrayList<>();
    private JsonArray exported = new JsonArray();
    private int index;
    private boolean outputWritten;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void start(ExportContext context) {
        entries.clear();
        exported = new JsonArray();
        index = 0;
        outputWritten = false;
        BuiltInRegistries.BLOCK.entrySet().stream()
            .filter(entry -> context.matches(entry.getKey().location()))
            .filter(entry -> entry.getValue() instanceof EntityBlock)
            .sorted(Comparator.comparing(entry -> entry.getKey().location().toString()))
            .forEach(entry -> entries.add(new Entry(
                entry.getKey().location(),
                entry.getValue(),
                (EntityBlock) entry.getValue()
            )));
    }

    @Override
    public StageResult step(ExportContext context) {
        if (index >= entries.size()) {
            writeIndex(context);
            return StageResult.DONE;
        }
        Entry entry = entries.get(index++);
        BlockEntity blockEntity = null;
        try {
            blockEntity = entry.factory.newBlockEntity(BlockPos.ZERO, entry.block.defaultBlockState());
            if (blockEntity == null) {
                context.report().addCount(ID, "construction_unavailable", 1);
                return finishIfNeeded(context);
            }
            BlockEntityRenderer<?> renderer = context.minecraft()
                .getBlockEntityRenderDispatcher()
                .getRenderer(blockEntity);
            List<BlockEntityTextureResolver.TextureReference> textures =
                BlockEntityTextureResolver.resolve(blockEntity, renderer);
            JsonObject json = baseEntry(entry, blockEntity);
            JsonArray variants = new JsonArray();
            if (blockEntity instanceof EnderChestBlockEntity && renderer instanceof ChestRenderer<?>) {
                variants.add(captureLayerVariant(
                    context,
                    entry.id,
                    "single",
                    ChestRenderer.createSingleBodyLayer(),
                    textures
                ));
                json.addProperty("model_source", "minecraft_chest_layer");
            } else if (blockEntity instanceof ChestBlockEntity && renderer instanceof ChestRenderer<?>) {
                variants.add(captureLayerVariant(
                    context,
                    entry.id,
                    "single",
                    ChestRenderer.createSingleBodyLayer(),
                    textures
                ));
                variants.add(captureLayerVariant(
                    context,
                    entry.id,
                    "left",
                    ChestRenderer.createDoubleBodyLeftLayer(),
                    textures
                ));
                variants.add(captureLayerVariant(
                    context,
                    entry.id,
                    "right",
                    ChestRenderer.createDoubleBodyRightLayer(),
                    textures
                ));
                json.addProperty("model_source", "minecraft_chest_layer");
            } else if (renderer != null) {
                JsonObject variant = captureRuntimeVariant(context, entry.id, blockEntity, renderer, textures);
                if (variant != null) {
                    variants.add(variant);
                }
                json.addProperty("model_source", "runtime_block_entity_renderer");
            } else {
                json.addProperty("model_source", "renderer_unavailable");
            }
            json.addProperty("variant_count", variants.size());
            json.addProperty("model_available", !variants.isEmpty());
            json.add("variants", variants);
            exported.add(json);
            context.report().addCount(ID, "block_entities", 1);
            context.report().addCount(ID, "variants", variants.size());
        } catch (Throwable throwable) {
            context.report().error(ID, entry.id.toString(), throwable);
        } finally {
            if (blockEntity != null) {
                blockEntity.setRemoved();
            }
        }
        return finishIfNeeded(context);
    }

    private StageResult finishIfNeeded(ExportContext context) {
        if (index >= entries.size()) {
            writeIndex(context);
            return StageResult.DONE;
        }
        return StageResult.MORE_WORK;
    }

    private static JsonObject captureLayerVariant(
        ExportContext context,
        ResourceLocation blockId,
        String variantId,
        LayerDefinition layer,
        List<BlockEntityTextureResolver.TextureReference> textures
    ) throws Exception {
        ModelPart root = layer.bakeRoot();
        ObjVertexConsumer consumer = new ObjVertexConsumer();
        root.render(
            new PoseStack(),
            consumer,
            LightTexture.FULL_BRIGHT,
            OverlayTexture.NO_OVERLAY
        );
        return writeVariant(
            context,
            blockId,
            variantId,
            consumer,
            "source_texture_normalized",
            textures.stream().filter(texture ->
                texture.modelVariant().equals(variantId) || texture.modelVariant().equals("default")
            ).toList(),
            true
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static JsonObject captureRuntimeVariant(
        ExportContext context,
        ResourceLocation blockId,
        BlockEntity blockEntity,
        BlockEntityRenderer renderer,
        List<BlockEntityTextureResolver.TextureReference> textures
    ) throws Exception {
        ObjVertexConsumer consumer = new ObjVertexConsumer();
        renderer.render(
            blockEntity,
            0.0F,
            new PoseStack(),
            renderType -> consumer,
            LightTexture.FULL_BRIGHT,
            OverlayTexture.NO_OVERLAY
        );
        if (consumer.vertexCount() == 0) {
            context.report().addCount(ID, "empty_runtime_mesh", 1);
            return null;
        }
        return writeVariant(
            context,
            blockId,
            "default",
            consumer,
            "render_target_normalized",
            textures,
            false
        );
    }

    private static JsonObject writeVariant(
        ExportContext context,
        ResourceLocation blockId,
        String variantId,
        ObjVertexConsumer consumer,
        String uvSpace,
        List<BlockEntityTextureResolver.TextureReference> textures,
        boolean sourceTextureUvs
    ) throws Exception {
        Path objPath = ExportPaths.idFile(
            context.root(),
            "block_entities/models",
            null,
            blockId,
            "/" + variantId + ".obj"
        );
        Path meshPath = ExportPaths.idFile(
            context.root(),
            "block_entities/models",
            null,
            blockId,
            "/" + variantId + ".mesh.json"
        );
        consumer.write(objPath, blockId + "_" + variantId);
        JsonFiles.write(meshPath, consumer.meshJson("block_units", uvSpace));

        JsonObject json = new JsonObject();
        json.addProperty("variant_id", variantId);
        json.addProperty("obj", relative(context, objPath));
        json.addProperty("mesh", relative(context, meshPath));
        json.addProperty("vertex_count", consumer.vertexCount());
        json.addProperty("quad_count", consumer.faceCount());
        json.addProperty("uv_space", uvSpace);
        json.addProperty("uv_complete", consumer.vertexCount() % 4 == 0);
        JsonArray textureJson = new JsonArray();
        for (BlockEntityTextureResolver.TextureReference texture : textures) {
            JsonObject reference = textureJson(context, texture, sourceTextureUvs);
            if (reference != null) {
                textureJson.add(reference);
            }
        }
        json.addProperty("texture_count", textureJson.size());
        json.add("textures", textureJson);
        return json;
    }

    private static JsonObject textureJson(
        ExportContext context,
        BlockEntityTextureResolver.TextureReference reference,
        boolean sourceTextureUvs
    ) throws Exception {
        ResourceLocation resourceId = textureFile(reference.texture());
        Optional<Resource> resource = context.minecraft().getResourceManager().getResource(resourceId);
        if (resource.isEmpty()) {
            return null;
        }
        Path path = ExportPaths.resourceFile(context.root(), "assets", resourceId);
        Files.createDirectories(path.getParent());
        try (InputStream input = resource.get().open()) {
            Files.copy(input, path, StandardCopyOption.REPLACE_EXISTING);
        }
        JsonObject json = new JsonObject();
        json.addProperty("appearance", reference.appearance());
        json.addProperty("texture_id", reference.texture().toString());
        json.addProperty("resource_id", resourceId.toString());
        json.addProperty("path", relative(context, path));
        json.addProperty("source", reference.source());
        json.addProperty("uv_transform_required", !sourceTextureUvs && reference.atlas() != null);
        if (reference.atlas() != null) {
            json.addProperty("atlas_id", reference.atlas().toString());
        }
        try (InputStream input = resource.get().open(); NativeImage image = NativeImage.read(input)) {
            json.addProperty("width", image.getWidth());
            json.addProperty("height", image.getHeight());
        }
        return json;
    }

    private static ResourceLocation textureFile(ResourceLocation texture) {
        String path = texture.getPath();
        if (!path.startsWith("textures/")) {
            path = "textures/" + path;
        }
        if (!path.endsWith(".png")) {
            path += ".png";
        }
        return new ResourceLocation(texture.getNamespace(), path);
    }

    private static JsonObject baseEntry(Entry entry, BlockEntity blockEntity) {
        JsonObject json = new JsonObject();
        json.addProperty("block_id", entry.id.toString());
        ResourceLocation typeId = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
        if (typeId != null) {
            json.addProperty("block_entity_type_id", typeId.toString());
        }
        return json;
    }

    private static String relative(ExportContext context, Path path) {
        return context.root().relativize(path).toString().replace('\\', '/');
    }

    private void writeIndex(ExportContext context) {
        if (outputWritten) {
            return;
        }
        outputWritten = true;
        JsonObject root = new JsonObject();
        root.addProperty("schema_version", "mcmods-block-entity-models/v1");
        root.addProperty("coordinate_space", "block_units");
        root.addProperty("uv_representation", "explicit_quad_vertices");
        root.addProperty("count", exported.size());
        root.add("block_entities", exported);
        try {
            context.writeJson("block_entities/index.json", root);
        } catch (Exception exception) {
            context.report().error(ID, "index", exception);
        }
    }

    @Override
    public String progress() {
        return index + "/" + entries.size();
    }

    private record Entry(ResourceLocation id, Block block, EntityBlock factory) {
    }
}
