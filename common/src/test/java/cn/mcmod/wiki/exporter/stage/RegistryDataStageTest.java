package cn.mcmod.wiki.exporter.stage;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryDataStageTest {
    @Test
    void keyMappingOwnershipUsesWholeTranslationKeySegments() {
        assertTrue(RegistryDataStage.keyTranslationMatchesNamespaces("key.mekanism.mode", List.of("mekanism")));
        assertTrue(RegistryDataStage.keyTranslationMatchesNamespaces("key.categories.create", List.of("create")));
        assertFalse(RegistryDataStage.keyTranslationMatchesNamespaces("key.some_create_addon.mode", List.of("create")));
        assertFalse(RegistryDataStage.keyTranslationMatchesNamespaces("key.minecraft.jump", List.of("mekanism")));
    }

    @Test
    void registryCaptureYieldsBetweenObjectsInSourceOrder() {
        List<String> visited = new ArrayList<>();
        var dump = new RegistryDataStage.RegistryDump<>("items",
            () -> List.of("minecraft:acacia_boat", "other:boat", "minecraft:oak_boat"), id -> {
                visited.add(id);
                if (id.startsWith("other:")) {
                    return null;
                }
                JsonObject entry = new JsonObject();
                entry.addProperty("id", id);
                return entry;
            });

        assertTrue(dump.captureNext());
        assertTrue(visited.isEmpty());
        assertTrue(dump.captureNext());
        assertEquals(List.of("minecraft:acacia_boat"), visited);
        assertTrue(dump.captureNext());
        assertEquals(List.of("minecraft:acacia_boat", "other:boat"), visited);
        assertTrue(dump.captureNext());
        assertEquals(List.of("minecraft:acacia_boat", "other:boat", "minecraft:oak_boat"), visited);
        assertFalse(dump.captureNext());
    }
}
