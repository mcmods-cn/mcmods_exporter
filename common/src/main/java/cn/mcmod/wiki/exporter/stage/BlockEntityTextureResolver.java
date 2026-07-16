package cn.mcmod.wiki.exporter.stage;

import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.resources.model.Material;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves source textures used by block-entity renderers without relying on field names. */
final class BlockEntityTextureResolver {
    private BlockEntityTextureResolver() {
    }

    static List<TextureReference> resolve(BlockEntity blockEntity, BlockEntityRenderer<?> renderer) {
        Map<String, TextureReference> references = new LinkedHashMap<>();
        if (blockEntity instanceof EnderChestBlockEntity) {
            addMaterial(references, "single", "default", Sheets.ENDER_CHEST_LOCATION, "minecraft_chest_material");
        } else if (blockEntity instanceof ChestBlockEntity) {
            addChestMaterial(references, blockEntity, ChestType.SINGLE, "single");
            addChestMaterial(references, blockEntity, ChestType.LEFT, "left");
            addChestMaterial(references, blockEntity, ChestType.RIGHT, "right");
        }
        collectRendererValues(renderer, references);
        return List.copyOf(references.values());
    }

    private static void addChestMaterial(
        Map<String, TextureReference> references,
        BlockEntity blockEntity,
        ChestType type,
        String modelVariant
    ) {
        addMaterial(
            references,
            modelVariant,
            "default",
            Sheets.chooseMaterial(blockEntity, type, false),
            "minecraft_chest_material"
        );
        addMaterial(
            references,
            modelVariant,
            "christmas",
            Sheets.chooseMaterial(blockEntity, type, true),
            "minecraft_chest_material"
        );
    }

    private static void collectRendererValues(
        BlockEntityRenderer<?> renderer,
        Map<String, TextureReference> references
    ) {
        if (renderer == null) {
            return;
        }
        int materialIndex = 0;
        int textureIndex = 0;
        for (Class<?> type = renderer.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    if (!field.trySetAccessible()) {
                        continue;
                    }
                    Object owner = Modifier.isStatic(field.getModifiers()) ? null : renderer;
                    Collection<Object> values = flatten(field.get(owner));
                    for (Object value : values) {
                        if (value instanceof Material material) {
                            addMaterial(
                                references,
                                "default",
                                "renderer_material_" + materialIndex++,
                                material,
                                "renderer_material"
                            );
                        } else if (value instanceof ResourceLocation texture) {
                            addTexture(
                                references,
                                "default",
                                "renderer_texture_" + textureIndex++,
                                texture,
                                null,
                                "renderer_resource_location"
                            );
                        }
                    }
                } catch (Throwable ignored) {
                    // A renderer may contain inaccessible platform fields. Other fields and runtime capture remain usable.
                }
            }
        }
    }

    private static Collection<Object> flatten(Object value) {
        List<Object> values = new ArrayList<>();
        if (value == null) {
            return values;
        }
        if (value instanceof Material || value instanceof ResourceLocation) {
            values.add(value);
        } else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(element -> {
                if (element instanceof Material || element instanceof ResourceLocation) {
                    values.add(element);
                }
            });
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(element -> {
                if (element instanceof Material || element instanceof ResourceLocation) {
                    values.add(element);
                }
            });
        } else if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                Object element = Array.get(value, index);
                if (element instanceof Material || element instanceof ResourceLocation) {
                    values.add(element);
                }
            }
        }
        return values;
    }

    private static void addMaterial(
        Map<String, TextureReference> references,
        String modelVariant,
        String appearance,
        Material material,
        String source
    ) {
        if (material != null) {
            addTexture(
                references,
                modelVariant,
                appearance,
                material.texture(),
                material.atlasLocation(),
                source
            );
        }
    }

    private static void addTexture(
        Map<String, TextureReference> references,
        String modelVariant,
        String appearance,
        ResourceLocation texture,
        ResourceLocation atlas,
        String source
    ) {
        if (texture == null) {
            return;
        }
        String key = modelVariant + '|' + appearance + '|' + texture + '|' + atlas;
        references.putIfAbsent(
            key,
            new TextureReference(modelVariant, appearance, texture, atlas, source)
        );
    }

    record TextureReference(
        String modelVariant,
        String appearance,
        ResourceLocation texture,
        ResourceLocation atlas,
        String source
    ) {
    }
}
